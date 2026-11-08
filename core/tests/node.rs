use std::path::Path;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Arc;
use std::time::Duration;

use kumiho_core::account::Account;
use kumiho_core::node::{format_digits, format_words, parse_code, CoreError, Events, MessageInfo, Net, Node};
use tempfile::TempDir;

#[derive(Debug, PartialEq)]
enum Ev {
    Message(String),
    Status(u64, String),
    Link(bool),
    Ready(Vec<String>),
    Failed,
}

struct Rec(Sender<Ev>);

impl Events for Rec {
    fn message(&self, m: MessageInfo) {
        let _ = self.0.send(Ev::Message(m.text));
    }
    fn status(&self, _: String, seq: u64, status: String) {
        let _ = self.0.send(Ev::Status(seq, status));
    }
    fn link(&self, _: String, up: bool) {
        let _ = self.0.send(Ev::Link(up));
    }
    fn pairing_ready(&self, words: Vec<String>) {
        let _ = self.0.send(Ev::Ready(words));
    }
    fn pairing_failed(&self, _: String) {
        let _ = self.0.send(Ev::Failed);
    }
}

fn node(path: &Path, port: u16, peer_pairing_port: u16) -> (Arc<Node>, Receiver<Ev>) {
    let (tx, rx) = channel();
    let n = Node::start_with(path, "p", Arc::new(Rec(tx)), Net::Local { port, peer_pairing_port }).unwrap();
    (n, rx)
}

fn wait(rx: &Receiver<Ev>, want: impl Fn(&Ev) -> bool) -> Ev {
    loop {
        let ev = rx.recv_timeout(Duration::from_secs(30)).expect("timed out waiting for an event");
        if want(&ev) {
            return ev;
        }
    }
}

#[test]
fn pair_with_a_code_then_chat_over_iroh() {
    let dir = TempDir::new().unwrap();
    let (alice, ra) = node(&dir.path().join("a.db"), 47821, 0);
    let (bob, rb) = node(&dir.path().join("b.db"), 47831, 47822);

    let code = alice.start_pairing().unwrap();
    bob.join_pairing(code.words).unwrap();
    let wa = wait(&ra, |e| matches!(e, Ev::Ready(_) | Ev::Failed));
    let wb = wait(&rb, |e| matches!(e, Ev::Ready(_) | Ev::Failed));
    assert!(matches!(wa, Ev::Ready(_)));
    assert_eq!(wa, wb, "both screens must show the same four words");

    alice.confirm_pairing("bob".into(), true).unwrap();
    bob.confirm_pairing("alice".into(), false).unwrap();
    wait(&ra, |e| *e == Ev::Link(true));
    wait(&rb, |e| *e == Ev::Link(true));

    assert_eq!(alice.safety_number("bob".into()).unwrap(), bob.safety_number("alice".into()).unwrap());
    assert_eq!(alice.safety_number("bob".into()).unwrap().len(), 12);

    let sent = bob.send_text("alice".into(), "across the wire".into()).unwrap();
    wait(&ra, |e| *e == Ev::Message("across the wire".into()));
    wait(&rb, |e| *e == Ev::Status(sent.seq, "delivered".into()));

    let stored = alice.history("bob".into()).unwrap();
    assert_eq!(stored[0].text, "across the wire");
    let contacts = alice.contacts().unwrap();
    assert!(contacts[0].verified);
    assert_eq!(contacts[0].unread, 1);
    alice.mark_read("bob".into()).unwrap();
    assert_eq!(alice.contacts().unwrap()[0].unread, 0);
}

#[test]
fn a_wrong_password_burns_the_code_on_both_sides() {
    let dir = TempDir::new().unwrap();
    let (alice, ra) = node(&dir.path().join("a.db"), 47841, 0);
    let (bob, rb) = node(&dir.path().join("b.db"), 47851, 47842);

    let code = alice.start_pairing().unwrap();
    let mut ids = parse_code(&code.digits).unwrap();
    ids[4] = (ids[4] + 1) % 2048;
    bob.join_pairing(format_digits(&ids)).unwrap();
    assert_eq!(wait(&ra, |e| matches!(e, Ev::Ready(_) | Ev::Failed)), Ev::Failed);
    assert_eq!(wait(&rb, |e| matches!(e, Ev::Ready(_) | Ev::Failed)), Ev::Failed);
    assert!(alice.contacts().unwrap().is_empty());
    assert!(alice.confirm_pairing("x".into(), false).is_err());
}

#[test]
fn codes_parse_from_words_or_digits_and_reject_junk() {
    let ids = [0, 1, 2047, 1000, 5];
    assert_eq!(parse_code(&format_words(&ids)), Some(ids));
    assert_eq!(parse_code(&format_digits(&ids)), Some(ids));
    assert_eq!(parse_code("  ABANDON ability zoo  ").is_none(), true);
    assert_eq!(parse_code("river-maple"), None);
    assert_eq!(parse_code("9999-0000 · 0000-0000-0000"), None);
    assert_eq!(parse_code("notaword-ability · zoo-zoo-zoo"), None);
}

#[test]
fn a_wrong_passphrase_is_reported_as_such() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("a.db");
    drop(Account::open(&path, "right").unwrap());
    let (tx, _rx) = channel();
    let r = Node::start_with(&path, "wrong", Arc::new(Rec(tx)), Net::Local { port: 47861, peer_pairing_port: 0 });
    assert!(matches!(r, Err(CoreError::WrongPassphrase)));
}

#[test]
fn wipe_deletes_the_database() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("a.db");
    let (n, _r) = node(&path, 47871, 0);
    assert!(path.exists());
    n.wipe().unwrap();
    assert!(!path.exists());
    assert!(n.contacts().is_err());
}
