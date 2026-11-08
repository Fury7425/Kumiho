//! The running node the apps talk to: one worker thread owns the account and all iroh links.
//!
//! The account is not `Send` (libsignal's stores and SQLite), so everything that touches it runs on
//! that thread. FFI calls queue a closure and wait for its reply; network tasks only pass messages.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId, SecretKey};
use rand::Rng;
use sha2::{Digest, Sha256};
use spake2::{Ed25519Group, Identity, Password, Spake2};
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::task::AbortHandle;

use crate::account::{now_ms, Account, ContactRow, Error, Frame, Incoming, Line, MAX_TEXT};

const CHAT_ALPN: &[u8] = b"kumiho/chat/1";
const PAIR_ALPN: &[u8] = b"kumiho/pair/1";
const MAX_FRAME: usize = MAX_TEXT + 8 * 1024;
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const CODE_LIFETIME: Duration = Duration::from_secs(600);

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("wrong passphrase")]
    WrongPassphrase,
    #[error("that doesn't look like a code")]
    BadCode,
    #[error("{0}")]
    Failed(String),
}

impl From<Error> for CoreError {
    fn from(e: Error) -> Self {
        match &e {
            Error::Db(rusqlite::Error::SqliteFailure(f, _)) if f.code == rusqlite::ErrorCode::NotADatabase => CoreError::WrongPassphrase,
            _ => CoreError::Failed(e.to_string()),
        }
    }
}

#[derive(uniffi::Record)]
pub struct ContactInfo {
    pub name: String,
    pub verified: bool,
    pub peek: bool,
    pub unread: u32,
}

#[derive(uniffi::Record)]
pub struct MessageInfo {
    pub contact: String,
    pub outgoing: bool,
    pub seq: u64,
    pub text: String,
    pub status: String,
    pub at_ms: u64,
}

#[derive(uniffi::Record)]
pub struct PairingCode {
    pub words: String,
    pub digits: String,
}

/// Called from the worker thread; implementations must hand off to their own UI thread.
#[uniffi::export(with_foreign)]
pub trait Events: Send + Sync {
    fn message(&self, m: MessageInfo);
    fn status(&self, contact: String, seq: u64, status: String);
    fn link(&self, contact: String, up: bool);
    fn pairing_ready(&self, words: Vec<String>);
    fn pairing_failed(&self, reason: String);
}

/// `Local` keeps tests off the internet: fixed localhost ports, no relay, no discovery.
#[derive(Clone, Copy)]
pub enum Net {
    Online,
    Local { port: u16, peer_pairing_port: u16 },
}

// ---------- Pairing codes ----------

/// Five word indexes: two public (nameplate, where to meet) and three secret (password).
type CodeIds = [u16; 5];

fn words() -> &'static [&'static str; 2048] {
    bip39::Language::English.word_list()
}

pub fn format_words(ids: &CodeIds) -> String {
    let w = |i: usize| words()[ids[i] as usize];
    format!("{}-{} · {}-{}-{}", w(0), w(1), w(2), w(3), w(4))
}

pub fn format_digits(ids: &CodeIds) -> String {
    format!("{:04}-{:04} · {:04}-{:04}-{:04}", ids[0], ids[1], ids[2], ids[3], ids[4])
}

/// Accepts five words or 20 digits, in any mix of spaces, hyphens and dots.
pub fn parse_code(input: &str) -> Option<CodeIds> {
    let input = input.trim().to_lowercase();
    let parts: Vec<&str> = input.split(|c: char| c.is_whitespace() || c == '-' || c == '·').filter(|p| !p.is_empty()).collect();
    let mut ids = [0u16; 5];
    let digits: String = input.chars().filter(char::is_ascii_digit).collect();
    if digits.len() == 20 && input.chars().all(|c| c.is_ascii_digit() || c.is_whitespace() || c == '-' || c == '·') {
        for (i, chunk) in digits.as_bytes().chunks(4).enumerate() {
            let v: u16 = std::str::from_utf8(chunk).ok()?.parse().ok()?;
            if v > 2047 {
                return None;
            }
            ids[i] = v;
        }
        return Some(ids);
    }
    if parts.len() != 5 {
        return None;
    }
    for (i, p) in parts.iter().enumerate() {
        ids[i] = words().iter().position(|w| w == p)? as u16;
    }
    Some(ids)
}

fn hkdf(ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, ikm).expand(info, &mut out).expect("32 bytes is a valid length");
    out
}

/// The meeting point: both sides derive the same endpoint key from the public nameplate. The
/// password never touches it. Knowing the nameplate only gets an attacker one guess at the PAKE.
// ponytail: rendezvous through iroh discovery, not the spec's Veilid DHT record, until step 5.
fn rendezvous_key(ids: &CodeIds) -> SecretKey {
    SecretKey::from_bytes(&hkdf(format!("{}-{}", ids[0], ids[1]).as_bytes(), b"kumiho rendezvous"))
}

fn spake(ids: &CodeIds) -> (Spake2<Ed25519Group>, Vec<u8>) {
    let password = format!("{}-{}-{}", ids[2], ids[3], ids[4]);
    Spake2::<Ed25519Group>::start_symmetric(&Password::new(password.as_bytes()), &Identity::new(b"kumiho pairing"))
}

fn seal(key: &[u8; 32], plain: &[u8]) -> Vec<u8> {
    // Single use per key, so a fixed nonce is safe.
    ChaCha20Poly1305::new(Key::from_slice(key)).encrypt(Nonce::from_slice(&[0; 12]), plain).expect("encrypting never fails")
}

fn unseal(key: &[u8; 32], sealed: &[u8]) -> Option<Vec<u8>> {
    ChaCha20Poly1305::new(Key::from_slice(key)).decrypt(Nonce::from_slice(&[0; 12]), sealed).ok()
}

/// Four words both screens show. Bound to the PAKE key and both cards, so a relay can't match them.
fn confirm_words(k: &[u8], card_a: &[u8], card_b: &[u8]) -> Vec<String> {
    let mut h = Sha256::new();
    h.update(k);
    h.update(card_a);
    h.update(card_b);
    let out = hkdf(&h.finalize(), b"kumiho confirm");
    // u16 % 2048 is uniform: 2048 divides 65536.
    out.chunks(2).take(4).map(|c| words()[u16::from_be_bytes([c[0], c[1]]) as usize % 2048].to_string()).collect()
}

const BURNED: &str = "That code didn't match, so it's burned now. Ask them for a new one.";

fn text(e: impl std::fmt::Display) -> String {
    e.to_string()
}

async fn write_msg(s: &mut SendStream, b: &[u8]) -> Result<(), String> {
    s.write_all(&(b.len() as u32).to_be_bytes()).await.map_err(text)?;
    s.write_all(b).await.map_err(text)
}

async fn read_msg(r: &mut RecvStream) -> Result<Vec<u8>, String> {
    let mut len = [0u8; 4];
    r.read_exact(&mut len).await.map_err(text)?;
    let n = u32::from_be_bytes(len) as usize;
    if n > MAX_FRAME {
        return Err("frame too large".into());
    }
    let mut buf = vec![0u8; n];
    r.read_exact(&mut buf).await.map_err(text)?;
    Ok(buf)
}

async fn bind(net: Net, offset: u16, key: SecretKey, alpn: &[u8]) -> Result<Endpoint, String> {
    let builder = match net {
        Net::Online => Endpoint::builder(presets::N0),
        Net::Local { port, .. } => Endpoint::builder(presets::N0DisableRelay)
            .clear_address_lookup()
            .clear_ip_transports()
            .bind_addr(format!("127.0.0.1:{}", port + offset).as_str())
            .map_err(text)?,
    };
    builder.secret_key(key).alpns(vec![alpn.to_vec()]).bind().await.map_err(text)
}

struct Paired {
    card: String,
    words: Vec<String>,
}

/// The side that made the code waits at the meeting point.
async fn host_pairing(net: Net, ids: CodeIds, my_card: String) -> Result<Paired, String> {
    let ep = bind(net, 1, rendezvous_key(&ids), PAIR_ALPN).await?;
    let incoming = ep.accept().await.ok_or("endpoint closed")?;
    let conn = incoming.await.map_err(text)?;
    let (mut send, mut recv) = conn.accept_bi().await.map_err(text)?;
    let (state, outbound) = spake(&ids);
    let inbound = read_msg(&mut recv).await?;
    write_msg(&mut send, &outbound).await?;
    let k = state.finish(&inbound).map_err(|_| BURNED)?;
    let sealed = read_msg(&mut recv).await?;
    // Their sealed card only opens if they knew the password. One wrong try ends this rendezvous.
    let card = unseal(&hkdf(&k, b"joiner card"), &sealed).ok_or(BURNED)?;
    write_msg(&mut send, &seal(&hkdf(&k, b"host card"), my_card.as_bytes())).await?;
    send.finish().map_err(text)?;
    let _ = tokio::time::timeout(Duration::from_secs(2), conn.closed()).await;
    ep.close().await;
    let words = confirm_words(&k, my_card.as_bytes(), &card);
    Ok(Paired { card: String::from_utf8(card).map_err(text)?, words })
}

/// The side that typed the code goes to the meeting point it derives from the nameplate.
async fn join_pairing(net: Net, ids: CodeIds, my_card: String) -> Result<Paired, String> {
    let key = SecretKey::from_bytes(&rand::rng().random::<[u8; 32]>());
    let ep = bind(net, 2, key, PAIR_ALPN).await?;
    let target = match net {
        Net::Online => EndpointAddr::new(rendezvous_key(&ids).public()),
        Net::Local { peer_pairing_port, .. } => EndpointAddr::new(rendezvous_key(&ids).public())
            .with_ip_addr(format!("127.0.0.1:{peer_pairing_port}").parse::<std::net::SocketAddr>().map_err(text)?),
    };
    // The other side may still be publishing its address.
    let mut last = String::new();
    let mut conn = None;
    for _ in 0..30 {
        match ep.connect(target.clone(), PAIR_ALPN).await {
            Ok(c) => {
                conn = Some(c);
                break;
            }
            Err(e) => {
                last = e.to_string();
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        }
    }
    let conn = conn.ok_or_else(|| format!("Couldn't reach them. Check the code and try again. ({last})"))?;
    let (mut send, mut recv) = conn.open_bi().await.map_err(text)?;
    let (state, outbound) = spake(&ids);
    write_msg(&mut send, &outbound).await?;
    let inbound = read_msg(&mut recv).await?;
    let k = state.finish(&inbound).map_err(|_| BURNED)?;
    write_msg(&mut send, &seal(&hkdf(&k, b"joiner card"), my_card.as_bytes())).await?;
    // A wrong password shows up here: the host hangs up instead of answering.
    let sealed = read_msg(&mut recv).await.map_err(|_| BURNED.to_string())?;
    let card = unseal(&hkdf(&k, b"host card"), &sealed).ok_or(BURNED)?;
    conn.close(0u8.into(), b"done");
    ep.close().await;
    let words = confirm_words(&k, &card, my_card.as_bytes());
    Ok(Paired { card: String::from_utf8(card).map_err(text)?, words })
}

// ---------- The worker ----------

enum Msg {
    Run(Box<dyn FnOnce(&mut Worker) + Send>),
    Linked(EndpointId, SendStream, RecvStream, Connection),
    Frame(String, u64, Vec<u8>),
    Lost(String, u64),
    DialStopped(String),
    Paired(Result<Paired, String>),
}

struct Link {
    send: SendStream,
    conn: Connection,
    generation: u64,
}

struct Worker {
    acct: Account,
    ep: Endpoint,
    net: Net,
    tx: UnboundedSender<Msg>,
    events: Arc<dyn Events>,
    peers: HashMap<EndpointId, (String, EndpointAddr)>,
    links: HashMap<String, Link>,
    dialing: HashSet<String>,
    generation: u64,
    pairing: Option<AbortHandle>,
    paired_card: Option<String>,
    stop: bool,
}

fn message_info(contact: &str, l: Line) -> MessageInfo {
    MessageInfo { contact: contact.to_string(), outgoing: l.outgoing, seq: l.seq, text: l.text, status: l.status, at_ms: l.at_ms }
}

fn contact_info(c: ContactRow) -> ContactInfo {
    ContactInfo { name: c.name, verified: c.verified, peek: c.peek, unread: c.unread }
}

impl Worker {
    fn local_addr(&self) -> String {
        let addr = match self.net {
            Net::Online => self.ep.addr(),
            Net::Local { port, .. } => EndpointAddr::new(self.ep.id()).with_ip_addr(format!("127.0.0.1:{port}").parse::<std::net::SocketAddr>().expect("valid address")),
        };
        serde_json::to_string(&addr).expect("addresses serialize")
    }

    fn remember_peers(&mut self) -> Result<(), CoreError> {
        for c in self.acct.contacts()? {
            if let Ok(addr) = serde_json::from_str::<EndpointAddr>(&c.endpoint) {
                self.peers.insert(addr.id, (c.name, addr));
            }
        }
        Ok(())
    }

    /// Exactly one side dials, chosen by comparing ids, so two peers never race two connections.
    fn dial_missing(&mut self) {
        let me = self.ep.id();
        for (id, (name, addr)) in &self.peers {
            if me < *id && !self.links.contains_key(name) && self.dialing.insert(name.clone()) {
                let (ep, addr, id, name, tx) = (self.ep.clone(), addr.clone(), *id, name.clone(), self.tx.clone());
                tokio::spawn(async move {
                    let mut wait = 1u64;
                    loop {
                        if let Ok(conn) = ep.connect(addr.clone(), CHAT_ALPN).await {
                            if let Ok((mut send, recv)) = conn.open_bi().await {
                                // accept_bi only returns once the opener has sent something: an empty frame.
                                if send.write_all(&0u32.to_be_bytes()).await.is_ok() {
                                    let _ = tx.send(Msg::Linked(id, send, recv, conn));
                                    break;
                                }
                            }
                        }
                        tokio::time::sleep(Duration::from_secs(wait)).await;
                        wait = (wait * 2).min(30);
                    }
                    let _ = tx.send(Msg::DialStopped(name));
                });
            }
        }
    }

    fn install_link(&mut self, id: EndpointId, send: SendStream, mut recv: RecvStream, conn: Connection) {
        let Some((name, _)) = self.peers.get(&id).cloned() else {
            conn.close(1u8.into(), b"unknown peer");
            return;
        };
        self.generation += 1;
        let (generation, tx, who) = (self.generation, self.tx.clone(), name.clone());
        // read_exact is not cancel-safe, so reading lives in its own task instead of a select!.
        tokio::spawn(async move {
            while let Ok(raw) = read_msg(&mut recv).await {
                if !raw.is_empty() && tx.send(Msg::Frame(who.clone(), generation, raw)).is_err() {
                    return;
                }
            }
            let _ = tx.send(Msg::Lost(who, generation));
        });
        if let Some(old) = self.links.insert(name.clone(), Link { send, conn, generation }) {
            old.conn.close(0u8.into(), b"replaced");
        }
        let _ = self.acct.retry_now(&name);
        self.events.link(name, true);
    }

    fn drop_link(&mut self, name: &str) {
        if let Some(l) = self.links.remove(name) {
            l.conn.close(0u8.into(), b"lost");
            self.events.link(name.to_string(), false);
        }
    }

    async fn write(&mut self, name: &str, f: &Frame) -> bool {
        let Some(link) = self.links.get_mut(name) else { return false };
        let raw = f.encode();
        let ok = matches!(tokio::time::timeout(WRITE_TIMEOUT, write_msg(&mut link.send, &raw)).await, Ok(Ok(())));
        if !ok {
            self.drop_link(name);
        }
        ok
    }

    async fn flush(&mut self) {
        let names: Vec<String> = self.links.keys().cloned().collect();
        for name in names {
            let Ok(due) = self.acct.due(&name, now_ms()) else { continue };
            for frame in due {
                if !self.write(&name, &frame).await {
                    break;
                }
                if self.acct.mark_tried(&name, frame.seq, now_ms()).is_ok() {
                    self.events.status(name.clone(), frame.seq, "sent".into());
                }
            }
        }
    }

    async fn on_frame(&mut self, name: String, raw: Vec<u8>) {
        // Bad frames come from the network: never let one take the node down.
        let Ok(opened) = Frame::decode(&raw).and_then(|f| self.acct.open_frame(&name, &f)) else { return };
        match opened {
            Incoming::Text { seq, .. } => {
                if let Some(line) = self.acct.history(&name).ok().and_then(|h| h.into_iter().rev().find(|l| !l.outgoing && l.seq == seq)) {
                    self.events.message(message_info(&name, line));
                }
                self.ack(&name, seq).await;
            }
            Incoming::Duplicate { seq } => self.ack(&name, seq).await,
            Incoming::Ack { seq } => self.events.status(name, seq, "delivered".into()),
        }
    }

    async fn ack(&mut self, name: &str, seq: u64) {
        if let Ok(frame) = self.acct.ack_frame(name, seq) {
            self.write(name, &frame).await;
        }
    }

    fn begin_pairing<F>(&mut self, start: impl FnOnce(String) -> F)
    where
        F: std::future::Future<Output = Result<Paired, String>> + Send + 'static,
    {
        if let Some(old) = self.pairing.take() {
            old.abort();
        }
        self.paired_card = None;
        let tx = self.tx.clone();
        let card = match self.acct.export_card(&self.local_addr()) {
            Ok(c) => c,
            Err(e) => {
                self.events.pairing_failed(e.to_string());
                return;
            }
        };
        let fut = start(card);
        let handle = tokio::spawn(async move {
            let result = match tokio::time::timeout(CODE_LIFETIME, fut).await {
                Ok(r) => r,
                Err(_) => Err("This code expired. Make a new one.".to_string()),
            };
            let _ = tx.send(Msg::Paired(result));
        });
        self.pairing = Some(handle.abort_handle());
    }
}

async fn worker(path: PathBuf, passphrase: String, events: Arc<dyn Events>, net: Net, tx: UnboundedSender<Msg>, mut rx: UnboundedReceiver<Msg>, ready: std::sync::mpsc::Sender<Result<(), CoreError>>) {
    let start = async {
        let acct = Account::open(&path, &passphrase)?;
        let key = SecretKey::from_bytes(&acct.endpoint_secret()?);
        let ep = bind(net, 0, key, CHAT_ALPN).await.map_err(CoreError::Failed)?;
        Ok::<_, CoreError>((acct, ep))
    };
    let (acct, ep) = match start.await {
        Ok(v) => v,
        Err(e) => {
            let _ = ready.send(Err(e));
            return;
        }
    };
    let mut w = Worker {
        acct, ep: ep.clone(), net, tx: tx.clone(), events, peers: HashMap::new(), links: HashMap::new(),
        dialing: HashSet::new(), generation: 0, pairing: None, paired_card: None, stop: false,
    };
    if let Err(e) = w.remember_peers() {
        let _ = ready.send(Err(e));
        return;
    }
    let _ = ready.send(Ok(()));
    let accept_ep = ep.clone();
    let accept_tx = tx.clone();
    tokio::spawn(async move {
        while let Some(incoming) = accept_ep.accept().await {
            let tx = accept_tx.clone();
            tokio::spawn(async move {
                let Ok(conn) = incoming.await else { return };
                if let Ok(Ok((send, recv))) = tokio::time::timeout(Duration::from_secs(10), conn.accept_bi()).await {
                    let _ = tx.send(Msg::Linked(conn.remote_id(), send, recv, conn));
                }
            });
        }
    });
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    while !w.stop {
        tokio::select! {
            msg = rx.recv() => {
                let Some(msg) = msg else { break };
                match msg {
                    Msg::Run(f) => f(&mut w),
                    Msg::Linked(id, send, recv, conn) => w.install_link(id, send, recv, conn),
                    Msg::Frame(name, generation, raw) => {
                        if w.links.get(&name).is_some_and(|l| l.generation == generation) {
                            w.on_frame(name, raw).await;
                        }
                    }
                    Msg::Lost(name, generation) => {
                        if w.links.get(&name).is_some_and(|l| l.generation == generation) {
                            w.drop_link(&name);
                        }
                    }
                    Msg::DialStopped(name) => { w.dialing.remove(&name); }
                    Msg::Paired(Ok(p)) => {
                        w.pairing = None;
                        w.paired_card = Some(p.card);
                        w.events.pairing_ready(p.words);
                    }
                    Msg::Paired(Err(e)) => {
                        w.pairing = None;
                        w.events.pairing_failed(e);
                    }
                }
            }
            _ = tick.tick() => {}
        }
        w.dial_missing();
        w.flush().await;
    }
    drop(w); // releases the database file before the slower network shutdown, for wipe()
    ep.close().await;
}

#[derive(uniffi::Object)]
pub struct Node {
    tx: UnboundedSender<Msg>,
    path: PathBuf,
}

fn stopped<E>(_: E) -> CoreError {
    CoreError::Failed("the node has stopped".into())
}

impl Node {
    pub fn start_with(db_path: &Path, passphrase: &str, events: Arc<dyn Events>, net: Net) -> Result<Arc<Self>, CoreError> {
        let (tx, rx) = unbounded_channel();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (path, pass, worker_tx) = (db_path.to_path_buf(), passphrase.to_string(), tx.clone());
        std::thread::Builder::new()
            .name("kumiho-node".into())
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime");
                rt.block_on(worker(path, pass, events, net, worker_tx, rx, ready_tx));
            })
            .map_err(|e| CoreError::Failed(e.to_string()))?;
        ready_rx.recv().map_err(stopped)??;
        Ok(Arc::new(Node { tx, path: db_path.to_path_buf() }))
    }

    fn call<T: Send + 'static>(&self, f: impl FnOnce(&mut Worker) -> Result<T, CoreError> + Send + 'static) -> Result<T, CoreError> {
        let (reply, answer) = std::sync::mpsc::channel();
        self.tx
            .send(Msg::Run(Box::new(move |w| {
                let _ = reply.send(f(w));
            })))
            .map_err(stopped)?;
        answer.recv().map_err(stopped)?
    }
}

#[uniffi::export]
impl Node {
    #[uniffi::constructor]
    pub fn start(db_path: String, passphrase: String, events: Arc<dyn Events>) -> Result<Arc<Self>, CoreError> {
        Self::start_with(Path::new(&db_path), &passphrase, events, Net::Online)
    }

    pub fn contacts(&self) -> Result<Vec<ContactInfo>, CoreError> {
        self.call(|w| Ok(w.acct.contacts()?.into_iter().map(contact_info).collect()))
    }

    pub fn history(&self, contact: String) -> Result<Vec<MessageInfo>, CoreError> {
        self.call(move |w| Ok(w.acct.history(&contact)?.into_iter().map(|l| message_info(&contact, l)).collect()))
    }

    pub fn send_text(&self, contact: String, text: String) -> Result<MessageInfo, CoreError> {
        self.call(move |w| {
            let seq = w.acct.send_text(&contact, &text)?;
            Ok(MessageInfo { contact, outgoing: true, seq, text, status: "queued".into(), at_ms: now_ms() })
        })
    }

    pub fn mark_read(&self, contact: String) -> Result<(), CoreError> {
        self.call(move |w| Ok(w.acct.mark_read(&contact)?))
    }

    pub fn mark_verified(&self, contact: String) -> Result<(), CoreError> {
        self.call(move |w| Ok(w.acct.mark_verified(&contact)?))
    }

    pub fn set_peek(&self, contact: String, on: bool) -> Result<(), CoreError> {
        self.call(move |w| Ok(w.acct.set_peek(&contact, on)?))
    }

    pub fn safety_number(&self, contact: String) -> Result<Vec<String>, CoreError> {
        self.call(move |w| Ok(w.acct.safety_number(&contact)?))
    }

    pub fn fingerprint(&self) -> Result<String, CoreError> {
        self.call(|w| Ok(w.acct.fingerprint()))
    }

    /// Shows a code and waits at its meeting point for up to 10 minutes.
    pub fn start_pairing(&self) -> Result<PairingCode, CoreError> {
        self.call(|w| {
            let mut r = rand::rng();
            let ids: CodeIds = std::array::from_fn(|_| r.random_range(0..2048));
            let net = w.net;
            w.begin_pairing(move |card| host_pairing(net, ids, card));
            Ok(PairingCode { words: format_words(&ids), digits: format_digits(&ids) })
        })
    }

    /// Goes to the meeting point of a code the other person read out.
    pub fn join_pairing(&self, code: String) -> Result<(), CoreError> {
        let ids = parse_code(&code).ok_or(CoreError::BadCode)?;
        self.call(move |w| {
            let net = w.net;
            w.begin_pairing(move |card| join_pairing(net, ids, card));
            Ok(())
        })
    }

    /// The four words matched: pin their keys and start chatting.
    pub fn confirm_pairing(&self, name: String, in_person: bool) -> Result<ContactInfo, CoreError> {
        self.call(move |w| {
            let card = w.paired_card.take().ok_or_else(|| CoreError::Failed("no pairing in progress".into()))?;
            w.acct.add_contact(&name, &card, in_person)?;
            w.remember_peers()?;
            Ok(ContactInfo { name, verified: in_person, peek: false, unread: 0 })
        })
    }

    /// The words didn't match, or the person backed out. Nothing was saved.
    pub fn cancel_pairing(&self) -> Result<(), CoreError> {
        self.call(|w| {
            if let Some(h) = w.pairing.take() {
                h.abort();
            }
            w.paired_card = None;
            Ok(())
        })
    }

    /// Stops the node and deletes the database. It can't be undone.
    pub fn wipe(&self) -> Result<(), CoreError> {
        self.call(|w| {
            w.stop = true;
            Ok(())
        })?;
        // The worker thread drops the database connection as it ends; retry until the file is free.
        for suffix in ["", "-wal", "-shm", "-journal"] {
            let mut file = self.path.clone().into_os_string();
            file.push(suffix);
            for _ in 0..50 {
                match std::fs::remove_file(&file) {
                    Err(e) if e.kind() != std::io::ErrorKind::NotFound => std::thread::sleep(Duration::from_millis(100)),
                    _ => break,
                }
            }
        }
        if self.path.exists() {
            return Err(CoreError::Failed("couldn't delete the database".into()));
        }
        Ok(())
    }
}
