//! One person's encrypted account: libsignal sessions (PQXDH + Triple Ratchet) stored in SQLCipher,
//! with sequence numbers, a persistent outbox, retries and end-to-end encrypted ACKs.
//!
//! Every step that advances the ratchet runs in one SQL transaction with the row it produces, so a
//! crash can never burn a message key without saving the message.

use std::path::Path;
use std::rc::Rc;
use std::time::SystemTime;

use async_trait::async_trait;
use futures_executor::block_on;
use libsignal_protocol::{
    kem, message_decrypt, message_encrypt, process_prekey_bundle, CiphertextMessage, DeviceId,
    Direction, Fingerprint, GenericSignedPreKey, IdentityChange, IdentityKey, IdentityKeyPair, IdentityKeyStore,
    KeyPair, KyberPreKeyId, KyberPreKeyRecord, KyberPreKeyStore, PreKeyBundle, PreKeyId,
    PreKeyRecord, PreKeySignalMessage, PreKeyStore, ProtocolAddress, PublicKey, SessionRecord,
    SessionStore, SignalMessage, SignalProtocolError, SignedPreKeyId, SignedPreKeyRecord,
    SignedPreKeyStore, Timestamp,
};
use rand::rngs::OsRng;
use rand::{Rng, RngCore, TryRngCore};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("database: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("signal: {0}")]
    Signal(#[from] SignalProtocolError),
    #[error("bad card: {0}")]
    Card(#[from] serde_json::Error),
    #[error("no contact named {0}")]
    UnknownContact(String),
    #[error("malformed frame")]
    Frame,
    #[error("passphrase must not be empty")]
    EmptyPassphrase,
    #[error("message too long")]
    TooLong,
}

pub type Result<T> = std::result::Result<T, Error>;
type SResult<T> = std::result::Result<T, SignalProtocolError>;

pub const MAX_TEXT: usize = 32 * 1024;
const TAG_TEXT: u8 = 0;
const TAG_ACK: u8 = 1;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS identity (
  id INTEGER PRIMARY KEY CHECK (id = 1), key_pair BLOB NOT NULL,
  registration_id INTEGER NOT NULL, endpoint_secret BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS signed_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS kyber_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS identities (address TEXT PRIMARY KEY, identity BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS contacts (
  name TEXT PRIMARY KEY, address TEXT NOT NULL, endpoint TEXT NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0, peek INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS sessions (address TEXT PRIMARY KEY, record BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS messages (
  contact TEXT NOT NULL, outgoing INTEGER NOT NULL, seq INTEGER NOT NULL,
  text TEXT NOT NULL, status TEXT NOT NULL, at_ms INTEGER NOT NULL, PRIMARY KEY (contact, outgoing, seq));
CREATE TABLE IF NOT EXISTS outbox (
  contact TEXT NOT NULL, seq INTEGER NOT NULL, kind INTEGER NOT NULL, body BLOB NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (contact, seq));
";

fn rng() -> impl Rng + rand::CryptoRng {
    OsRng.unwrap_err()
}

fn device() -> DeviceId {
    DeviceId::new(1).expect("1 is in range")
}

/// Sessions are keyed by identity key, which both sides agree on. Contact names are private labels.
fn address_of(identity: &IdentityKey) -> ProtocolAddress {
    ProtocolAddress::new(hex::encode(identity.serialize()), device())
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// 1 s, doubling, capped at 60 s.
pub fn backoff_ms(attempts: u32) -> u64 {
    (1000u64 << attempts.min(6)).min(60_000)
}

/// What travels on the wire: outer `seq` only lets a receiver re-ACK a retransmission it can no longer decrypt.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    pub seq: u64,
    pub kind: u8,
    pub body: Vec<u8>,
}

impl Frame {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(9 + self.body.len());
        out.extend_from_slice(&self.seq.to_be_bytes());
        out.push(self.kind);
        out.extend_from_slice(&self.body);
        out
    }

    pub fn decode(raw: &[u8]) -> Result<Self> {
        if raw.len() < 9 {
            return Err(Error::Frame);
        }
        let (head, body) = raw.split_at(9);
        let seq = u64::from_be_bytes(head[..8].try_into().expect("8 bytes"));
        Ok(Frame { seq, kind: head[8], body: body.to_vec() })
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Incoming {
    Text { seq: u64, text: String },
    Ack { seq: u64 },
    /// A retransmission of something already saved: ACK it again, the first ACK was lost.
    Duplicate { seq: u64 },
}

#[derive(Debug, PartialEq, Eq)]
pub struct Line {
    pub outgoing: bool,
    pub seq: u64,
    pub text: String,
    pub status: String,
    pub at_ms: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContactRow {
    pub name: String,
    pub endpoint: String,
    pub verified: bool,
    pub peek: bool,
    pub unread: u32,
}

/// A contact card: everything the other side needs, exchanged by hand until Glyph pairing (step 4).
/// One-time: each export holds fresh prekeys, so give each contact their own.
#[derive(Serialize, Deserialize)]
struct Card {
    registration_id: u32,
    identity: Vec<u8>,
    pre_key_id: u32,
    pre_key: Vec<u8>,
    signed_id: u32,
    signed: Vec<u8>,
    signed_sig: Vec<u8>,
    kyber_id: u32,
    kyber: Vec<u8>,
    kyber_sig: Vec<u8>,
    endpoint: String,
}

fn plain(tag: u8, seq: u64, rest: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + rest.len());
    out.push(tag);
    out.extend_from_slice(&seq.to_be_bytes());
    out.extend_from_slice(rest);
    out
}

// ---------- libsignal's stores, backed by SQLCipher ----------

/// libsignal takes each store as a separate `&mut`, so this is a cheap handle onto one connection.
#[derive(Clone)]
struct Sql(Rc<Connection>);

fn db_err(e: rusqlite::Error) -> SignalProtocolError {
    SignalProtocolError::InvalidState("storage", e.to_string())
}

impl Sql {
    fn blob(&self, sql: &str, key: impl rusqlite::ToSql) -> SResult<Option<Vec<u8>>> {
        self.0.query_row(sql, [key], |r| r.get(0)).optional().map_err(db_err)
    }

    fn write(&self, sql: &str, p: impl rusqlite::Params) -> SResult<()> {
        self.0.execute(sql, p).map(|_| ()).map_err(db_err)
    }

    fn pair(&self) -> SResult<IdentityKeyPair> {
        let raw = self
            .blob("SELECT key_pair FROM identity WHERE id = ?", 1)?
            .ok_or_else(|| SignalProtocolError::InvalidState("storage", "no identity".into()))?;
        IdentityKeyPair::try_from(raw.as_slice())
    }
}

#[async_trait(?Send)]
impl IdentityKeyStore for Sql {
    async fn get_identity_key_pair(&self) -> SResult<IdentityKeyPair> {
        self.pair()
    }

    async fn get_local_registration_id(&self) -> SResult<u32> {
        let id: i64 = self
            .0
            .query_row("SELECT registration_id FROM identity WHERE id = 1", [], |r| r.get(0))
            .map_err(db_err)?;
        Ok(id as u32)
    }

    async fn save_identity(&mut self, a: &ProtocolAddress, identity: &IdentityKey) -> SResult<IdentityChange> {
        let new = identity.serialize();
        let old = self.blob("SELECT identity FROM identities WHERE address = ?", a.name())?;
        self.write("INSERT OR REPLACE INTO identities (address, identity) VALUES (?, ?)", params![a.name(), &*new])?;
        Ok(if old.is_some_and(|o| o.as_slice() != &*new) {
            IdentityChange::ReplacedExisting
        } else {
            IdentityChange::NewOrUnchanged
        })
    }

    /// Only the key pinned when the contact was added is trusted. Unknown peers are refused.
    async fn is_trusted_identity(&self, a: &ProtocolAddress, identity: &IdentityKey, _: Direction) -> SResult<bool> {
        let pinned = self.blob("SELECT identity FROM identities WHERE address = ?", a.name())?;
        Ok(pinned.is_some_and(|o| o.as_slice() == &*identity.serialize()))
    }

    async fn get_identity(&self, a: &ProtocolAddress) -> SResult<Option<IdentityKey>> {
        self.blob("SELECT identity FROM identities WHERE address = ?", a.name())?
            .map(|b| IdentityKey::decode(&b))
            .transpose()
    }
}

#[async_trait(?Send)]
impl PreKeyStore for Sql {
    async fn get_pre_key(&self, id: PreKeyId) -> SResult<PreKeyRecord> {
        let raw = self
            .blob("SELECT record FROM prekeys WHERE id = ?", u32::from(id) as i64)?
            .ok_or(SignalProtocolError::InvalidPreKeyId)?;
        PreKeyRecord::deserialize(&raw)
    }

    async fn save_pre_key(&mut self, id: PreKeyId, record: &PreKeyRecord) -> SResult<()> {
        self.write("INSERT OR REPLACE INTO prekeys (id, record) VALUES (?, ?)", params![u32::from(id) as i64, record.serialize()?])
    }

    async fn remove_pre_key(&mut self, id: PreKeyId) -> SResult<()> {
        self.write("DELETE FROM prekeys WHERE id = ?", [u32::from(id) as i64])
    }
}

#[async_trait(?Send)]
impl SignedPreKeyStore for Sql {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> SResult<SignedPreKeyRecord> {
        let raw = self
            .blob("SELECT record FROM signed_prekeys WHERE id = ?", u32::from(id) as i64)?
            .ok_or(SignalProtocolError::InvalidSignedPreKeyId)?;
        SignedPreKeyRecord::deserialize(&raw)
    }

    async fn save_signed_pre_key(&mut self, id: SignedPreKeyId, record: &SignedPreKeyRecord) -> SResult<()> {
        self.write("INSERT OR REPLACE INTO signed_prekeys (id, record) VALUES (?, ?)", params![u32::from(id) as i64, record.serialize()?])
    }
}

#[async_trait(?Send)]
impl KyberPreKeyStore for Sql {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> SResult<KyberPreKeyRecord> {
        let raw = self
            .blob("SELECT record FROM kyber_prekeys WHERE id = ?", u32::from(id) as i64)?
            .ok_or(SignalProtocolError::InvalidKyberPreKeyId)?;
        KyberPreKeyRecord::deserialize(&raw)
    }

    async fn save_kyber_pre_key(&mut self, id: KyberPreKeyId, record: &KyberPreKeyRecord) -> SResult<()> {
        self.write("INSERT OR REPLACE INTO kyber_prekeys (id, record) VALUES (?, ?)", params![u32::from(id) as i64, record.serialize()?])
    }

    /// Every card has its own Kyber key, so it is one-time: delete it for forward secrecy.
    async fn mark_kyber_pre_key_used(&mut self, id: KyberPreKeyId, _: SignedPreKeyId, _: &PublicKey) -> SResult<()> {
        self.write("DELETE FROM kyber_prekeys WHERE id = ?", [u32::from(id) as i64])
    }
}

#[async_trait(?Send)]
impl SessionStore for Sql {
    async fn load_session(&self, a: &ProtocolAddress) -> SResult<Option<SessionRecord>> {
        self.blob("SELECT record FROM sessions WHERE address = ?", a.name())?
            .map(|b| SessionRecord::deserialize(&b))
            .transpose()
    }

    async fn store_session(&mut self, a: &ProtocolAddress, record: &SessionRecord) -> SResult<()> {
        self.write("INSERT OR REPLACE INTO sessions (address, record) VALUES (?, ?)", params![a.name(), record.serialize()?])
    }
}

// ---------- The account ----------

pub struct Account {
    sql: Sql,
    local: ProtocolAddress,
}

impl Account {
    /// Opens or creates an encrypted database. SQLCipher derives the key from the passphrase.
    // ponytail: passphrase only. The chip-wrapped random DB key from the spec needs the keystore module.
    pub fn open(path: &Path, passphrase: &str) -> Result<Self> {
        if passphrase.is_empty() {
            return Err(Error::EmptyPassphrase); // an empty SQLCipher key means no encryption at all
        }
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "key", passphrase)?;
        conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))?; // fails on a wrong key
        conn.execute_batch(SCHEMA)?;
        let sql = Sql(Rc::new(conn));
        if sql.blob("SELECT key_pair FROM identity WHERE id = ?", 1)?.is_none() {
            let mut r = rng();
            let pair = IdentityKeyPair::generate(&mut r);
            let mut secret = [0u8; 32];
            r.fill_bytes(&mut secret);
            sql.write(
                "INSERT INTO identity (id, key_pair, registration_id, endpoint_secret) VALUES (1, ?, ?, ?)",
                params![&*pair.serialize(), r.random_range(1..16380i64), &secret[..]],
            )?;
        }
        let local = address_of(sql.pair()?.identity_key());
        Ok(Account { sql, local })
    }

    /// Stable secret for the iroh endpoint, so the peer's dial address survives restarts.
    pub fn endpoint_secret(&self) -> Result<[u8; 32]> {
        let raw = self.sql.blob("SELECT endpoint_secret FROM identity WHERE id = ?", 1)?.ok_or(Error::Frame)?;
        raw.try_into().map_err(|_| Error::Frame)
    }

    pub fn export_card(&self, endpoint: &str) -> Result<String> {
        let mut r = rng();
        let identity = self.sql.pair()?;
        let pre = KeyPair::generate(&mut r);
        let signed = KeyPair::generate(&mut r);
        let kyber = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut r);
        let signed_public = signed.public_key.serialize();
        let kyber_public = kyber.public_key.serialize();
        let signed_sig = identity.private_key().calculate_signature(&signed_public, &mut r).map_err(SignalProtocolError::from)?;
        let kyber_sig = identity.private_key().calculate_signature(&kyber_public, &mut r).map_err(SignalProtocolError::from)?;
        let (pre_key_id, signed_id, kyber_id): (u32, u32, u32) = (r.random(), r.random(), r.random());
        let now = Timestamp::from_epoch_millis(now_ms());
        let mut s = self.sql.clone();
        block_on(async {
            s.save_pre_key(pre_key_id.into(), &PreKeyRecord::new(pre_key_id.into(), &pre)).await?;
            s.save_signed_pre_key(signed_id.into(), &SignedPreKeyRecord::new(signed_id.into(), now, &signed, &signed_sig)).await?;
            s.save_kyber_pre_key(kyber_id.into(), &KyberPreKeyRecord::new(kyber_id.into(), now, &kyber, &kyber_sig)).await
        })?;
        let registration_id = block_on(self.sql.get_local_registration_id())?;
        Ok(serde_json::to_string(&Card {
            registration_id,
            identity: identity.identity_key().serialize().to_vec(),
            pre_key_id,
            pre_key: pre.public_key.serialize().to_vec(),
            signed_id,
            signed: signed_public.to_vec(),
            signed_sig: signed_sig.to_vec(),
            kyber_id,
            kyber: kyber_public.to_vec(),
            kyber_sig: kyber_sig.to_vec(),
            endpoint: endpoint.to_string(),
        })?)
    }

    /// Pins the card's identity key and starts a session. Fails if the name is already taken.
    pub fn add_contact(&self, name: &str, card: &str, verified: bool) -> Result<()> {
        let c: Card = serde_json::from_str(card)?;
        let identity = IdentityKey::decode(&c.identity)?;
        let bundle = PreKeyBundle::new(
            c.registration_id,
            device(),
            Some((c.pre_key_id.into(), PublicKey::deserialize(&c.pre_key).map_err(SignalProtocolError::from)?)),
            c.signed_id.into(),
            PublicKey::deserialize(&c.signed).map_err(SignalProtocolError::from)?,
            c.signed_sig,
            c.kyber_id.into(),
            kem::PublicKey::deserialize(&c.kyber)?,
            c.kyber_sig,
            identity,
        )?;
        let remote = address_of(&identity);
        self.tx(|| {
            self.sql.0.execute("INSERT INTO contacts (name, address, endpoint, verified) VALUES (?, ?, ?, ?)", params![name, remote.name(), c.endpoint, verified as i64])?;
            self.sql.write("INSERT OR REPLACE INTO identities (address, identity) VALUES (?, ?)", params![remote.name(), &*identity.serialize()])?;
            let (mut s, mut i) = (self.sql.clone(), self.sql.clone());
            block_on(process_prekey_bundle(&remote, &self.local, &mut s, &mut i, &bundle, SystemTime::now(), &mut rng()))?;
            Ok(())
        })
    }

    pub fn contacts(&self) -> Result<Vec<ContactRow>> {
        let mut stmt = self.sql.0.prepare("SELECT name, endpoint, verified, peek, unread FROM contacts ORDER BY rowid DESC")?;
        let rows = stmt.query_map([], |r| {
            Ok(ContactRow {
                name: r.get(0)?,
                endpoint: r.get(1)?,
                verified: r.get::<_, i64>(2)? == 1,
                peek: r.get::<_, i64>(3)? == 1,
                unread: r.get::<_, i64>(4)? as u32,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn mark_read(&self, name: &str) -> Result<()> {
        self.sql.0.execute("UPDATE contacts SET unread = 0 WHERE name = ?", [name])?;
        Ok(())
    }

    pub fn mark_verified(&self, name: &str) -> Result<()> {
        self.sql.0.execute("UPDATE contacts SET verified = 1 WHERE name = ?", [name])?;
        Ok(())
    }

    pub fn set_peek(&self, name: &str, on: bool) -> Result<()> {
        self.sql.0.execute("UPDATE contacts SET peek = ? WHERE name = ?", params![on as i64, name])?;
        Ok(())
    }

    /// The start of our public identity key, for display only.
    pub fn fingerprint(&self) -> String {
        let hex = self.local.name();
        // The first byte is a key-type tag and says nothing about the person.
        format!("{} {} {}", &hex[2..6], &hex[6..10], &hex[10..14])
    }

    /// 60 digits in 12 groups of 5: the same on both sides only if neither identity was swapped.
    pub fn safety_number(&self, name: &str) -> Result<Vec<String>> {
        let remote = self.remote(name)?;
        let their_key = block_on(self.sql.get_identity(&remote))?.ok_or_else(|| Error::UnknownContact(name.into()))?;
        let ours = self.sql.pair()?;
        let fp = Fingerprint::new(2, 5200, self.local.name().as_bytes(), ours.identity_key(), remote.name().as_bytes(), &their_key)?;
        let digits = fp.display_string()?;
        Ok(digits.as_bytes().chunks(5).map(|c| String::from_utf8_lossy(c).into_owned()).collect())
    }

    pub fn contact_endpoint(&self, name: &str) -> Result<String> {
        self.sql
            .0
            .query_row("SELECT endpoint FROM contacts WHERE name = ?", [name], |r| r.get(0))
            .optional()?
            .ok_or_else(|| Error::UnknownContact(name.into()))
    }

    fn remote(&self, name: &str) -> Result<ProtocolAddress> {
        let a: String = self
            .sql
            .0
            .query_row("SELECT address FROM contacts WHERE name = ?", [name], |r| r.get(0))
            .optional()?
            .ok_or_else(|| Error::UnknownContact(name.into()))?;
        Ok(ProtocolAddress::new(a, device()))
    }

    fn tx<T>(&self, f: impl FnOnce() -> Result<T>) -> Result<T> {
        self.sql.0.execute_batch("BEGIN IMMEDIATE")?;
        match f() {
            Ok(v) => {
                self.sql.0.execute_batch("COMMIT")?;
                Ok(v)
            }
            Err(e) => {
                let _ = self.sql.0.execute_batch("ROLLBACK");
                Err(e)
            }
        }
    }

    fn encrypt(&self, remote: &ProtocolAddress, plaintext: &[u8]) -> Result<(u8, Vec<u8>)> {
        let (mut s, mut i) = (self.sql.clone(), self.sql.clone());
        let m = block_on(message_encrypt(plaintext, remote, &self.local, &mut s, &mut i, SystemTime::now(), &mut rng()))?;
        Ok((m.message_type() as u8, m.serialize().to_vec()))
    }

    fn decrypt(&self, remote: &ProtocolAddress, kind: u8, body: &[u8]) -> Result<Vec<u8>> {
        let msg = match kind {
            3 => CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(body)?),
            2 => CiphertextMessage::SignalMessage(SignalMessage::try_from(body)?),
            _ => return Err(Error::Frame),
        };
        let (mut s, mut i, mut p, sp, mut k) = (self.sql.clone(), self.sql.clone(), self.sql.clone(), self.sql.clone(), self.sql.clone());
        Ok(block_on(message_decrypt(&msg, remote, &self.local, &mut s, &mut i, &mut p, &sp, &mut k, &mut rng()))?)
    }

    /// Encrypts once and queues the ciphertext; the outbox resends those exact bytes until ACKed.
    pub fn send_text(&self, name: &str, text: &str) -> Result<u64> {
        if text.len() > MAX_TEXT {
            return Err(Error::TooLong);
        }
        let remote = self.remote(name)?;
        self.tx(|| {
            let seq: i64 = self.sql.0.query_row(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE contact = ? AND outgoing = 1",
                [name],
                |r| r.get(0),
            )?;
            let (kind, body) = self.encrypt(&remote, &plain(TAG_TEXT, seq as u64, text.as_bytes()))?;
            self.sql.0.execute("INSERT INTO messages (contact, outgoing, seq, text, status, at_ms) VALUES (?, 1, ?, ?, 'queued', ?)", params![name, seq, text, now_ms() as i64])?;
            self.sql.0.execute("INSERT INTO outbox (contact, seq, kind, body) VALUES (?, ?, ?, ?)", params![name, seq, kind as i64, body])?;
            Ok(seq as u64)
        })
    }

    /// A fresh connection: everything unacknowledged goes out again right away.
    pub fn retry_now(&self, name: &str) -> Result<()> {
        self.sql.0.execute("UPDATE outbox SET next_at = 0 WHERE contact = ?", [name])?;
        Ok(())
    }

    pub fn due(&self, name: &str, now_ms: u64) -> Result<Vec<Frame>> {
        let mut stmt = self.sql.0.prepare("SELECT seq, kind, body FROM outbox WHERE contact = ? AND next_at <= ? ORDER BY seq")?;
        let rows = stmt.query_map(params![name, now_ms as i64], |r| {
            Ok(Frame { seq: r.get::<_, i64>(0)? as u64, kind: r.get::<_, i64>(1)? as u8, body: r.get(2)? })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn mark_tried(&self, name: &str, seq: u64, now_ms: u64) -> Result<()> {
        let attempts: i64 = self.sql.0.query_row("SELECT attempts FROM outbox WHERE contact = ? AND seq = ?", params![name, seq as i64], |r| r.get(0))?;
        self.sql.0.execute(
            "UPDATE outbox SET attempts = ?, next_at = ? WHERE contact = ? AND seq = ?",
            params![attempts + 1, (now_ms + backoff_ms(attempts as u32)) as i64, name, seq as i64],
        )?;
        self.sql.0.execute("UPDATE messages SET status = 'sent' WHERE contact = ? AND outgoing = 1 AND seq = ? AND status = 'queued'", params![name, seq as i64])?;
        Ok(())
    }

    fn has_received(&self, name: &str, seq: u64) -> Result<bool> {
        Ok(self
            .sql
            .0
            .query_row("SELECT 1 FROM messages WHERE contact = ? AND outgoing = 0 AND seq = ?", params![name, seq as i64], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// Decrypts and saves in one transaction. Any failure rolls the ratchet back.
    pub fn open_frame(&self, name: &str, f: &Frame) -> Result<Incoming> {
        let remote = self.remote(name)?;
        let result = self.tx(|| {
            let p = self.decrypt(&remote, f.kind, &f.body)?;
            if p.len() < 9 {
                return Err(Error::Frame);
            }
            let seq = u64::from_be_bytes(p[1..9].try_into().expect("8 bytes"));
            match p[0] {
                TAG_TEXT => {
                    if seq != f.seq {
                        return Err(Error::Frame);
                    }
                    let text = String::from_utf8(p[9..].to_vec()).map_err(|_| Error::Frame)?;
                    self.sql.0.execute(
                        "INSERT OR IGNORE INTO messages (contact, outgoing, seq, text, status, at_ms) VALUES (?, 0, ?, ?, 'received', ?)",
                        params![name, seq as i64, text, now_ms() as i64],
                    )?;
                    self.sql.0.execute("UPDATE contacts SET unread = unread + 1 WHERE name = ?", [name])?;
                    Ok(Incoming::Text { seq, text })
                }
                TAG_ACK => {
                    self.sql.0.execute("DELETE FROM outbox WHERE contact = ? AND seq = ?", params![name, seq as i64])?;
                    self.sql.0.execute("UPDATE messages SET status = 'delivered' WHERE contact = ? AND outgoing = 1 AND seq = ?", params![name, seq as i64])?;
                    Ok(Incoming::Ack { seq })
                }
                _ => Err(Error::Frame),
            }
        });
        match result {
            Err(Error::Signal(SignalProtocolError::DuplicatedMessage(..))) if self.has_received(name, f.seq)? => {
                Ok(Incoming::Duplicate { seq: f.seq })
            }
            other => other,
        }
    }

    /// An end-to-end encrypted ACK, so a relay or node can't fake "delivered". Not queued: if it is lost the sender retransmits.
    pub fn ack_frame(&self, name: &str, seq: u64) -> Result<Frame> {
        let remote = self.remote(name)?;
        self.tx(|| {
            let (kind, body) = self.encrypt(&remote, &plain(TAG_ACK, seq, &[]))?;
            Ok(Frame { seq: 0, kind, body })
        })
    }

    pub fn history(&self, name: &str) -> Result<Vec<Line>> {
        let mut stmt = self.sql.0.prepare("SELECT outgoing, seq, text, status, at_ms FROM messages WHERE contact = ? ORDER BY rowid")?;
        let rows = stmt.query_map([name], |r| {
            Ok(Line { outgoing: r.get::<_, i64>(0)? == 1, seq: r.get::<_, i64>(1)? as u64, text: r.get(2)?, status: r.get(3)?, at_ms: r.get::<_, i64>(4)? as u64 })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }
}
