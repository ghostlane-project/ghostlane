//! The policy rules the daemon adds beside the engine's: one
//! `from <host address> lookup main` per global address of the host, the kill
//! switch, and the clean-up of what a crashed run left.
//!
//! (red: the API is here, nothing behind it yet)

use std::fmt;
use std::net::IpAddr;
use std::sync::Mutex;

pub const OWN_ADDRESS_PRIORITY: u32 = 8990;
pub const ENGINE_RULE_FIRST: u32 = 9000;
pub const ENGINE_RULE_LAST: u32 = 9010;
pub const ENGINE_TABLE: u32 = 2022;
pub const KILL_SWITCH_LAN: u32 = 9098;
pub const KILL_SWITCH_SELF: u32 = 9099;
pub const KILL_SWITCH_DROP: u32 = 9100;
pub const KILL_SWITCH_TABLE: u32 = 2023;

#[derive(Debug)]
pub struct Error {
    pub message: String,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

fn unwritten<T>() -> Result<T, Error> {
    Err(Error {
        message: "not written".to_string(),
    })
}

pub struct Manager {
    #[allow(dead_code)]
    exclude_iface: String,
    #[allow(dead_code)]
    lock: Mutex<()>,
}

impl Manager {
    pub fn new(exclude_iface: &str) -> Manager {
        Manager {
            exclude_iface: exclude_iface.to_string(),
            lock: Mutex::new(()),
        }
    }
    pub fn global_addresses(&self) -> Result<Vec<IpAddr>, Error> {
        unwritten()
    }
    pub fn sync(&self, _addresses: &[IpAddr]) -> Result<(), Error> {
        unwritten()
    }
    pub fn clear(&self) -> Result<(), Error> {
        unwritten()
    }
    pub fn install_kill_switch(&self, _uid: u32) -> Result<(), Error> {
        unwritten()
    }
    pub fn remove_kill_switch(&self) -> Result<(), Error> {
        unwritten()
    }
    pub fn cleanup_stale(&self) -> Result<(), Error> {
        unwritten()
    }
}
