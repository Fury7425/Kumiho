//! Two-terminal test client for the Kumiho core.
//!
//!   kumiho-cli alice.db pair bob             show a one-time code and wait
//!   kumiho-cli bob.db join alice "<code>"    enter the code the other person read out
//!   kumiho-cli alice.db chat bob             talk; Ctrl+D or Ctrl+Z quits
//!   kumiho-cli alice.db history bob          what is stored
//!
//! The passphrase comes from KUMIHO_PASS or a prompt, never from the command line.

use std::io::BufRead;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};

use anyhow::{anyhow, bail, Result};
use kumiho_core::node::{Events, MessageInfo, Node};

struct Print(Mutex<Sender<Result<Vec<String>, String>>>);

impl Events for Print {
    fn message(&self, m: MessageInfo) {
        println!("{}: {}", m.contact, m.text);
    }
    fn status(&self, _: String, seq: u64, status: String) {
        if status == "delivered" {
            println!("  [delivered #{seq}]");
        }
    }
    fn link(&self, contact: String, up: bool) {
        println!("  [{contact} {}]", if up { "connected" } else { "disconnected" });
    }
    fn pairing_ready(&self, words: Vec<String>) {
        let _ = self.0.lock().unwrap().send(Ok(words));
    }
    fn pairing_failed(&self, reason: String) {
        let _ = self.0.lock().unwrap().send(Err(reason));
    }
}

/// Waits for the handshake, shows the four words and saves the contact only if they match.
fn finish_pairing(node: &Node, name: &str, result: &Receiver<Result<Vec<String>, String>>) -> Result<()> {
    let words = result.recv()?.map_err(|e| anyhow!(e))?;
    println!("Do both screens show: {} ? [y/N]", words.join(" "));
    let mut answer = String::new();
    std::io::stdin().read_line(&mut answer)?;
    if answer.trim().eq_ignore_ascii_case("y") {
        node.confirm_pairing(name.to_string(), false)?;
        println!("added {name}.");
    } else {
        node.cancel_pairing()?;
        println!("cancelled. Nothing was saved.");
    }
    Ok(())
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    let [db, cmd @ ..] = args.as_slice() else {
        bail!("usage: kumiho-cli <db> pair <name> | join <name> <code> | chat <name> | history <name>");
    };
    let pass = match std::env::var("KUMIHO_PASS") {
        Ok(p) => p,
        Err(_) => rpassword::prompt_password("Passphrase: ")?,
    };
    let (tx, rx) = channel();
    let node = Node::start(db.to_string(), pass, Arc::new(Print(Mutex::new(tx))))?;
    match cmd {
        ["pair", name] => {
            let code = node.start_pairing()?;
            println!("Read them this code (valid 10 minutes, one attempt):\n  {}\n  {}", code.words, code.digits);
            finish_pairing(&node, name, &rx)
        }
        ["join", name, code] => {
            node.join_pairing(code.to_string())?;
            finish_pairing(&node, name, &rx)
        }
        ["chat", name] => {
            println!("chatting with {name}. Anything you type is queued until they connect.");
            for line in std::io::stdin().lock().lines() {
                let line = line?;
                if !line.trim().is_empty() {
                    node.send_text(name.to_string(), line)?;
                }
            }
            Ok(())
        }
        ["history", name] => {
            for m in node.history(name.to_string())? {
                println!("{} #{} [{}] {}", if m.outgoing { "me" } else { *name }, m.seq, m.status, m.text);
            }
            Ok(())
        }
        _ => bail!("unknown command"),
    }
}
