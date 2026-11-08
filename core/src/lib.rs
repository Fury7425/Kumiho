//! Kumiho core. The apps talk to it only through the API below; keys never cross it.
//! `account` holds build-order steps 1-2. The free functions below are still stubs for the app's FFI.

pub mod account;
pub mod node;

uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

pub type ContactId = String;
pub type MessageId = u64;

/// Ordered from least to most private, so `max` is "stricter".
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum PrivacyMode {
    Fast,
    Private,
    Untraceable,
}

impl PrivacyMode {
    /// A chat uses the stricter of the two people's settings.
    pub fn in_use(mine: Self, theirs: Self) -> Self {
        mine.max(theirs)
    }

    /// Only Fast uses a direct link, so only Fast allows calls.
    pub fn allows_calls(self) -> bool {
        self == Self::Fast
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeliveryStatus {
    Queued,
    Sent,
    Stored,
    Delivered,
    Read,
    NotDelivered,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verification {
    Verified,
    CodePaired,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ShieldLevel {
    Block,
    BlockFragile,
    Detect,
    None,
}

#[derive(Clone, Debug)]
pub struct Contact {
    pub id: ContactId,
    pub name: String,
    pub mode: PrivacyMode,
    pub verification: Verification,
}

#[derive(Clone, Debug)]
pub struct Message {
    pub id: MessageId,
    pub contact: ContactId,
    pub outgoing: bool,
    pub text: String,
    pub status: DeliveryStatus,
}

// ponytail: stubs mirror the spec's Core API; each is filled in by its build-order milestone.
pub fn list_contacts() -> Vec<Contact> {
    unimplemented!("milestone 2: storage")
}

pub fn set_contact_mode(_contact: &ContactId, _mode: PrivacyMode) {
    unimplemented!("milestone 2: storage")
}

pub fn send_text(_contact: &ContactId, _text: &str) -> MessageId {
    unimplemented!("milestone 1: CLI chat")
}

pub fn list_messages(_contact: &ContactId, _before: Option<MessageId>, _limit: u32) -> Vec<Message> {
    unimplemented!("milestone 2: storage")
}

pub fn safety_number(_contact: &ContactId) -> String {
    unimplemented!("milestone 4: pairing")
}

#[cfg(test)]
mod tests {
    use super::PrivacyMode::*;
    use super::*;

    #[test]
    fn stricter_mode_wins() {
        assert_eq!(PrivacyMode::in_use(Fast, Untraceable), Untraceable);
        assert_eq!(PrivacyMode::in_use(Private, Fast), Private);
        assert_eq!(PrivacyMode::in_use(Fast, Fast), Fast);
        assert!(PrivacyMode::in_use(Fast, Private).allows_calls() == false);
        assert!(Fast.allows_calls());
    }
}
