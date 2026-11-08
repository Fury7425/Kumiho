use kumiho_core::account::{backoff_ms, Account, Error, Frame, Incoming};
use tempfile::TempDir;

const PASS: &str = "correct horse";

fn pair() -> (TempDir, Account, Account) {
    let dir = TempDir::new().unwrap();
    let a = Account::open(&dir.path().join("a.db"), PASS).unwrap();
    let b = Account::open(&dir.path().join("b.db"), PASS).unwrap();
    a.add_contact("bob", &b.export_card("b-endpoint").unwrap(), false).unwrap();
    b.add_contact("alice", &a.export_card("a-endpoint").unwrap(), false).unwrap();
    (dir, a, b)
}

/// Sends the only queued frame from `a` to `b`.
fn deliver_one(a: &Account, to: &str, b: &Account, from: &str) -> (Frame, Incoming) {
    let frames = a.due(to, u64::MAX >> 1).unwrap();
    let frame = frames.last().unwrap().clone();
    let got = b.open_frame(from, &frame).unwrap();
    (frame, got)
}

#[test]
fn message_round_trip_and_encrypted_ack() {
    let (_d, a, b) = pair();
    let seq = a.send_text("bob", "hello").unwrap();
    let (_, got) = deliver_one(&a, "bob", &b, "alice");
    assert_eq!(got, Incoming::Text { seq, text: "hello".into() });
    assert_eq!(a.history("bob").unwrap()[0].status, "queued");

    let ack = b.ack_frame("alice", seq).unwrap();
    assert_eq!(a.open_frame("bob", &ack).unwrap(), Incoming::Ack { seq });
    assert_eq!(a.history("bob").unwrap()[0].status, "delivered");
    assert!(a.due("bob", u64::MAX >> 1).unwrap().is_empty());

    let reply = b.send_text("alice", "hi back").unwrap();
    let (_, got) = deliver_one(&b, "alice", &a, "bob");
    assert_eq!(got, Incoming::Text { seq: reply, text: "hi back".into() });
}

#[test]
fn several_first_messages_before_any_reply() {
    let (_d, a, b) = pair();
    a.send_text("bob", "one").unwrap();
    a.send_text("bob", "two").unwrap();
    for (i, f) in a.due("bob", u64::MAX >> 1).unwrap().iter().enumerate() {
        assert!(matches!(b.open_frame("alice", f).unwrap(), Incoming::Text { seq, .. } if seq == i as u64 + 1));
    }
}

#[test]
fn both_sides_can_send_first() {
    let (_d, a, b) = pair();
    a.send_text("bob", "from a").unwrap();
    b.send_text("alice", "from b").unwrap();
    assert!(matches!(deliver_one(&a, "bob", &b, "alice").1, Incoming::Text { .. }));
    assert!(matches!(deliver_one(&b, "alice", &a, "bob").1, Incoming::Text { .. }));
}

#[test]
fn retransmission_is_reported_as_duplicate_not_saved_twice() {
    let (_d, a, b) = pair();
    let seq = a.send_text("bob", "once").unwrap();
    let (frame, _) = deliver_one(&a, "bob", &b, "alice");
    assert_eq!(b.open_frame("alice", &frame).unwrap(), Incoming::Duplicate { seq });
    assert_eq!(b.history("alice").unwrap().len(), 1);
}

#[test]
fn tampered_frame_is_rejected_and_the_session_survives() {
    let (_d, a, b) = pair();
    let seq = a.send_text("bob", "real").unwrap();
    let good = a.due("bob", u64::MAX >> 1).unwrap().remove(0);
    let mut bad = good.clone();
    let last = bad.body.len() - 1;
    bad.body[last] ^= 1;
    assert!(b.open_frame("alice", &bad).is_err());
    assert_eq!(b.open_frame("alice", &good).unwrap(), Incoming::Text { seq, text: "real".into() });
}

#[test]
fn sessions_and_outbox_survive_reopening() {
    let dir = TempDir::new().unwrap();
    let (pa, pb) = (dir.path().join("a.db"), dir.path().join("b.db"));
    {
        let a = Account::open(&pa, PASS).unwrap();
        let b = Account::open(&pb, PASS).unwrap();
        a.add_contact("bob", &b.export_card("b").unwrap(), false).unwrap();
        b.add_contact("alice", &a.export_card("a").unwrap(), false).unwrap();
        a.send_text("bob", "kept").unwrap();
    }
    let a = Account::open(&pa, PASS).unwrap();
    let b = Account::open(&pb, PASS).unwrap();
    assert!(matches!(deliver_one(&a, "bob", &b, "alice").1, Incoming::Text { .. }));
}

#[test]
fn wrong_or_empty_passphrase_is_refused_and_file_is_ciphertext() {
    let (dir, a, _b) = pair();
    a.send_text("bob", "needle-in-the-database").unwrap();
    drop(a);
    let path = dir.path().join("a.db");
    assert!(Account::open(&path, "wrong").is_err());
    assert!(matches!(Account::open(&dir.path().join("c.db"), ""), Err(Error::EmptyPassphrase)));
    let bytes = std::fs::read(&path).unwrap();
    assert!(!bytes.starts_with(b"SQLite format 3"));
    assert!(!bytes.windows(6).any(|w| w == b"needle"));
}

#[test]
fn a_taken_name_cannot_be_rebound_to_another_identity() {
    let (dir, a, _b) = pair();
    let mallory = Account::open(&dir.path().join("m.db"), PASS).unwrap();
    assert!(a.add_contact("bob", &mallory.export_card("m").unwrap(), false).is_err());
}

#[test]
fn unknown_contact_and_short_frames_are_errors() {
    let (_d, a, _b) = pair();
    assert!(matches!(a.send_text("nobody", "x"), Err(Error::UnknownContact(_))));
    assert!(matches!(Frame::decode(&[0; 8]), Err(Error::Frame)));
    let f = Frame { seq: 7, kind: 2, body: vec![1, 2, 3] };
    assert_eq!(Frame::decode(&f.encode()).unwrap(), f);
}

#[test]
fn outbox_backs_off_and_retries() {
    assert_eq!(backoff_ms(0), 1000);
    assert_eq!(backoff_ms(1), 2000);
    assert_eq!(backoff_ms(40), 60_000);
    let (_d, a, _b) = pair();
    let seq = a.send_text("bob", "later").unwrap();
    assert_eq!(a.due("bob", 5000).unwrap().len(), 1);
    a.mark_tried("bob", seq, 5000).unwrap();
    assert!(a.due("bob", 5999).unwrap().is_empty());
    assert_eq!(a.due("bob", 6000).unwrap().len(), 1);
    a.mark_tried("bob", seq, 6000).unwrap();
    a.retry_now("bob").unwrap();
    assert_eq!(a.due("bob", 0).unwrap().len(), 1);
}
