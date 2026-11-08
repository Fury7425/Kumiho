//! Two-terminal chat over iroh and libsignal (build-order steps 1-2).
//!
//!   kumiho-cli alice.db card alice.card      write a one-time contact card
//!   kumiho-cli alice.db add bob bob.card     pin a card you got by hand
//!   kumiho-cli alice.db chat bob             talk; Ctrl+D or Ctrl+Z quits
//!   kumiho-cli alice.db history bob          what is stored
//!
//! The passphrase comes from KUMIHO_PASS or a prompt, never from the command line.

use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, SecretKey};
use kumiho_core::account::{Account, Frame, Incoming, MAX_TEXT};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::mpsc;

const ALPN: &[u8] = b"kumiho/chat/1";
/// Largest frame accepted from a peer: a full text plus ratchet, Kyber and header overhead.
const MAX_FRAME: usize = MAX_TEXT + 8 * 1024;

fn err(e: impl std::fmt::Display) -> anyhow::Error {
    anyhow!("{e}")
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

async fn bind(acct: &Account) -> Result<Endpoint> {
    Endpoint::builder(presets::N0)
        .secret_key(SecretKey::from_bytes(&acct.endpoint_secret()?))
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await
        .map_err(err)
}

/// Exactly one side dials, chosen by comparing ids, so two peers never race two connections.
async fn connect(ep: &Endpoint, peer: EndpointAddr) -> Result<(SendStream, RecvStream, Connection)> {
    if ep.id() < peer.id {
        loop {
            match ep.connect(peer.clone(), ALPN).await {
                Ok(conn) => {
                    let (mut send, recv) = conn.open_bi().await.map_err(err)?;
                    // accept_bi only returns once the opener has sent something: an empty frame.
                    send.write_all(&0u32.to_be_bytes()).await.map_err(err)?;
                    return Ok((send, recv, conn));
                }
                Err(e) => {
                    eprintln!("waiting for peer: {e}");
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
            }
        }
    }
    loop {
        let incoming = ep.accept().await.context("endpoint closed")?;
        match incoming.await {
            Ok(conn) if conn.remote_id() == peer.id => {
                let (send, recv) = conn.accept_bi().await.map_err(err)?;
                return Ok((send, recv, conn));
            }
            Ok(conn) => conn.close(1u8.into(), b"unknown peer"),
            Err(e) => eprintln!("connection failed: {e}"),
        }
    }
}

/// `read_exact` is not cancel-safe, so reading lives in its own task instead of in a `select!`.
fn spawn_reader(mut recv: RecvStream) -> mpsc::Receiver<Vec<u8>> {
    let (tx, rx) = mpsc::channel(16);
    tokio::spawn(async move {
        loop {
            let mut len = [0u8; 4];
            if recv.read_exact(&mut len).await.is_err() {
                break;
            }
            let n = u32::from_be_bytes(len) as usize;
            if n > MAX_FRAME {
                break;
            }
            let mut buf = vec![0u8; n];
            if recv.read_exact(&mut buf).await.is_err() {
                break;
            }
            if n > 0 && tx.send(buf).await.is_err() {
                break;
            }
        }
    });
    rx
}

async fn write_frame(send: &mut SendStream, f: &Frame) -> Result<()> {
    let raw = f.encode();
    send.write_all(&(raw.len() as u32).to_be_bytes()).await.map_err(err)?;
    send.write_all(&raw).await.map_err(err)
}

async fn flush(acct: &Account, name: &str, send: &mut SendStream) -> Result<()> {
    for frame in acct.due(name, now_ms())? {
        write_frame(send, &frame).await?;
        acct.mark_tried(name, frame.seq, now_ms())?;
    }
    Ok(())
}

enum Outcome {
    Quit,
    Lost,
}

async fn pump(
    acct: &Account,
    name: &str,
    send: &mut SendStream,
    mut frames: mpsc::Receiver<Vec<u8>>,
    input: &mut mpsc::Receiver<String>,
) -> Result<Outcome> {
    acct.retry_now(name)?;
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        // Network failures mean "reconnect". Only a storage failure ends the chat.
        let sent = tokio::select! {
            raw = frames.recv() => {
                let Some(raw) = raw else { return Ok(Outcome::Lost) };
                // Bad frames come from the network: report and carry on.
                let opened = Frame::decode(&raw).and_then(|f| acct.open_frame(name, &f));
                match opened {
                    Ok(Incoming::Text { seq, text }) => {
                        println!("{name}: {text}");
                        write_frame(send, &acct.ack_frame(name, seq)?).await
                    }
                    Ok(Incoming::Duplicate { seq }) => write_frame(send, &acct.ack_frame(name, seq)?).await,
                    Ok(Incoming::Ack { seq }) => {
                        println!("  [delivered #{seq}]");
                        Ok(())
                    }
                    Err(e) => {
                        eprintln!("dropped a frame: {e}");
                        Ok(())
                    }
                }
            }
            line = input.recv() => {
                let Some(line) = line else { return Ok(Outcome::Quit) };
                if let Err(e) = acct.send_text(name, &line) {
                    eprintln!("not sent: {e}");
                }
                flush(acct, name, send).await
            }
            _ = tick.tick() => flush(acct, name, send).await,
        };
        if sent.is_err() {
            return Ok(Outcome::Lost);
        }
    }
}

fn stdin_lines() -> mpsc::Receiver<String> {
    let (tx, rx) = mpsc::channel(16);
    tokio::spawn(async move {
        let mut lines = BufReader::new(tokio::io::stdin()).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            if !line.trim().is_empty() && tx.send(line).await.is_err() {
                break;
            }
        }
    });
    rx
}

async fn chat(acct: &Account, name: &str) -> Result<()> {
    let peer: EndpointAddr = serde_json::from_str(&acct.contact_endpoint(name)?)?;
    let ep = bind(acct).await?;
    let mut input = stdin_lines();
    println!("connecting to {name}. Anything you type now is queued.");
    loop {
        let link = {
            let dial = connect(&ep, peer.clone());
            tokio::pin!(dial);
            loop {
                tokio::select! {
                    link = &mut dial => break link?,
                    line = input.recv() => match line {
                        Some(line) => { acct.send_text(name, &line)?; }
                        None => { ep.close().await; return Ok(()); }
                    },
                }
            }
        };
        let (mut send, recv, _conn) = link;
        println!("connected.");
        match pump(acct, name, &mut send, spawn_reader(recv), &mut input).await? {
            Outcome::Quit => {
                ep.close().await;
                return Ok(());
            }
            Outcome::Lost => println!("disconnected, reconnecting."),
        }
    }
}

async fn card(acct: &Account, out: &str) -> Result<()> {
    let ep = bind(acct).await?;
    // Best effort: without the network the card still carries the endpoint id.
    let _ = tokio::time::timeout(Duration::from_secs(15), ep.online()).await;
    let addr = serde_json::to_string(&ep.addr())?;
    std::fs::write(out, acct.export_card(&addr)?)?;
    ep.close().await;
    println!("wrote {out}. It is one-time: export a new card for each contact.");
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let (db, cmd) = match args.as_slice() {
        [db, cmd @ ..] if !cmd.is_empty() => (*db, cmd),
        _ => bail!("usage: kumiho-cli <db> card <file> | add <name> <file> | chat <name> | history <name>"),
    };
    let pass = match std::env::var("KUMIHO_PASS") {
        Ok(p) => p,
        Err(_) => rpassword::prompt_password("Passphrase: ")?,
    };
    let acct = Account::open(Path::new(db), &pass)?;
    match cmd {
        ["card", out] => card(&acct, out).await,
        ["add", name, file] => {
            acct.add_contact(name, &std::fs::read_to_string(file)?)?;
            println!("added {name}.");
            Ok(())
        }
        ["chat", name] => chat(&acct, name).await,
        ["history", name] => {
            for l in acct.history(name)? {
                println!("{} #{} [{}] {}", if l.outgoing { "me" } else { *name }, l.seq, l.status, l.text);
            }
            Ok(())
        }
        _ => bail!("unknown command"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn local(acct: &Account, port: u16) -> Endpoint {
        let builder = Endpoint::builder(presets::N0DisableRelay).clear_address_lookup().clear_ip_transports();
        let builder = if port == 47811 { builder.bind_addr("127.0.0.1:47811") } else { builder.bind_addr("127.0.0.1:47812") };
        builder
            .unwrap()
            .secret_key(SecretKey::from_bytes(&acct.endpoint_secret().unwrap()))
            .alpns(vec![ALPN.to_vec()])
            .bind()
            .await
            .unwrap()
    }

    /// The whole path on localhost: iroh QUIC, libsignal, SQLCipher, outbox and encrypted ACK.
    #[tokio::test]
    async fn message_is_delivered_and_acked_over_iroh() {
        let dir = tempfile::TempDir::new().unwrap();
        let a = Account::open(&dir.path().join("a.db"), "p").unwrap();
        let b = Account::open(&dir.path().join("b.db"), "p").unwrap();
        let (ea, eb) = (local(&a, 47811).await, local(&b, 47812).await);
        let addr_a = EndpointAddr::new(ea.id()).with_ip_addr("127.0.0.1:47811".parse().unwrap());
        let addr_b = EndpointAddr::new(eb.id()).with_ip_addr("127.0.0.1:47812".parse().unwrap());
        a.add_contact("bob", &b.export_card(&serde_json::to_string(&addr_b).unwrap()).unwrap()).unwrap();
        b.add_contact("alice", &a.export_card(&serde_json::to_string(&addr_a).unwrap()).unwrap()).unwrap();

        let (la, lb) = tokio::join!(connect(&ea, addr_b), connect(&eb, addr_a));
        let (mut sa, ra, _ca) = la.unwrap();
        let (mut sb, rb, _cb) = lb.unwrap();
        let (ta, mut ia) = mpsc::channel(4);
        let (tb, mut ib) = mpsc::channel::<String>(4);

        let driver = async {
            ta.send("across the wire".to_string()).await.unwrap();
            loop {
                let delivered = a.history("bob").unwrap().first().is_some_and(|l| l.status == "delivered");
                let got = b.history("alice").unwrap().first().is_some_and(|l| l.text == "across the wire");
                if delivered && got {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            drop(ta);
            drop(tb);
        };

        tokio::time::timeout(Duration::from_secs(30), async {
            let (x, y, ()) = tokio::join!(
                pump(&a, "bob", &mut sa, spawn_reader(ra), &mut ia),
                pump(&b, "alice", &mut sb, spawn_reader(rb), &mut ib),
                driver,
            );
            x.unwrap();
            y.unwrap();
        })
        .await
        .expect("timed out");
    }
}
