//! The policy rules the daemon adds beside the engine's: one
//! `from <host address> lookup main` per global address of the host, ahead of
//! the engine's rules, so replies of inbound connections keep their normal
//! route while everything the host originates goes through the tun; the kill
//! switch; and the clean-up of what a crashed run left.
//!
//! The rules are written over a rtnetlink socket (`netlink.rs`): no `ip`, no
//! netlink crate. Held to the Go program's `internal/routes` by
//! `tests/netns.rs` (scripts/cli-checks/netns.sh).

mod netlink;
mod watch;

pub mod hev;

pub use watch::Watcher;

use std::collections::BTreeSet;
use std::fmt;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::Mutex;

use netlink::{attrs, ne32, put_attr, Socket};

pub const OWN_ADDRESS_PRIORITY: u32 = 8990;
/// The engine's rules (9000 to 9010) and table; a crash leaves them behind.
pub const ENGINE_RULE_FIRST: u32 = 9000;
pub const ENGINE_RULE_LAST: u32 = ENGINE_RULE_FIRST + 10;
pub const ENGINE_TABLE: u32 = 2022;

/// The kill switch sits behind the engine's rules: while the tun is up they
/// take everything; while it is down, main answers only for what it routes
/// specifically (LAN, Docker, link-local: suppress_prefixlength 0) and for the
/// daemon's own uid (carriers, servers), and all else is unreachable.
/// The tun's rules: the engine's own uid keeps main (9000), everything else
/// takes table 2022 (9010), whose IPv4 default is the tun and whose IPv6
/// default is a blackhole: IPv6 is refused at once, not carried.
pub const TUN_BYPASS: u32 = ENGINE_RULE_FIRST;
pub const TUN_LOOKUP: u32 = ENGINE_RULE_LAST;

pub const KILL_SWITCH_LAN: u32 = 9098;
pub const KILL_SWITCH_SELF: u32 = 9099;
pub const KILL_SWITCH_DROP: u32 = 9100;
pub const KILL_SWITCH_TABLE: u32 = 2023;

const RT_TABLE_MAIN: u32 = 254;
const FR_ACT_TO_TBL: u8 = 1;
const FRA_SRC: u16 = 2;
const FRA_PRIORITY: u16 = 6;
const FRA_SUPPRESS_PREFIXLEN: u16 = 14;
const FRA_TABLE: u16 = 15;
const FRA_UID_RANGE: u16 = 20;
const IFA_ADDRESS: u16 = 1;
const IFA_LOCAL: u16 = 2;
const RTA_DST: u16 = 1;
const RTA_OIF: u16 = 4;
const RTA_GATEWAY: u16 = 5;
const RTA_PRIORITY: u16 = 6;
const RTA_PREFSRC: u16 = 7;
const RTA_TABLE: u16 = 15;
const RTPROT_BOOT: u8 = 3;
const RTN_UNICAST: u8 = 1;
const RTN_BLACKHOLE: u8 = 6;
const RTN_UNREACHABLE: u8 = 7;
const RT_SCOPE_LINK: u8 = 253;
const RT_SCOPE_UNIVERSE: u8 = 0;
const AF_INET: u8 = libc::AF_INET as u8;
const AF_INET6: u8 = libc::AF_INET6 as u8;
const FAMILIES: [u8; 2] = [AF_INET, AF_INET6];

#[derive(Debug)]
pub struct Error {
    /// The errnos the kernel named, in the order they came.
    pub errnos: Vec<i32>,
    pub message: String,
}

impl Error {
    fn os(errno: i32, what: impl fmt::Display) -> Error {
        Error {
            errnos: vec![errno],
            message: format!("{what}: {}", std::io::Error::from_raw_os_error(errno)),
        }
    }

    fn join(errors: Vec<Error>) -> Result<(), Error> {
        if errors.is_empty() {
            return Ok(());
        }
        Err(Error {
            errnos: errors.iter().flat_map(|e| e.errnos.clone()).collect(),
            message: errors
                .iter()
                .map(|e| e.message.as_str())
                .collect::<Vec<_>>()
                .join("; "),
        })
    }

    /// The kernel has no IPv6 (booted with ipv6.disable=1, common on hardened
    /// servers): there is nothing to kill on that family.
    fn no_ipv6(&self) -> bool {
        self.errnos.iter().any(|e| {
            [
                libc::EAFNOSUPPORT,
                libc::EPFNOSUPPORT,
                libc::EPROTONOSUPPORT,
                libc::EOPNOTSUPP,
            ]
            .contains(e)
        })
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

fn family_name(family: u8) -> &'static str {
    if family == AF_INET6 {
        "IPv6"
    } else {
        "IPv4"
    }
}

/// An error on the v6 half of the kill switch that only says there is no IPv6.
fn skip_v6(family: u8, err: &Error) -> bool {
    family == AF_INET6 && err.no_ipv6()
}

pub struct Manager {
    exclude_iface: String,
    /// One rule edit at a time: sync, clear and cleanup_stale.
    lock: Mutex<()>,
}

struct Rule {
    priority: u32,
    src: Option<IpAddr>,
}

fn ip_bytes(addr: IpAddr) -> Vec<u8> {
    match addr {
        IpAddr::V4(a) => a.octets().to_vec(),
        IpAddr::V6(a) => a.octets().to_vec(),
    }
}

fn ip_from(bytes: &[u8]) -> Option<IpAddr> {
    if let Ok(b) = <[u8; 4]>::try_from(bytes) {
        return Some(IpAddr::V4(Ipv4Addr::from(b)));
    }
    if let Ok(b) = <[u8; 16]>::try_from(bytes) {
        return Some(IpAddr::V6(Ipv6Addr::from(b)));
    }
    None
}

fn unmap(addr: IpAddr) -> IpAddr {
    match addr {
        IpAddr::V6(a) => a.to_ipv4_mapped().map_or(addr, IpAddr::V4),
        v4 => v4,
    }
}

fn family_of(addr: IpAddr) -> u8 {
    if addr.is_ipv6() {
        AF_INET6
    } else {
        AF_INET
    }
}

/// fib_rule_hdr: family, dst_len, src_len, tos, table, two reserved, action, flags.
fn rule_header(family: u8, src_len: u8, table: u32, action: u8) -> Vec<u8> {
    let mut h = vec![
        family,
        0,
        src_len,
        0,
        if table < 256 { table as u8 } else { 0 },
        0,
        0,
        action,
    ];
    h.extend_from_slice(&0u32.to_ne_bytes());
    h
}

fn list_rules(sock: &mut Socket, family: u8) -> Result<Vec<Rule>, Error> {
    let header = rule_header(family, 0, 0, 0);
    let messages = sock
        .request(
            netlink::RTM_GETRULE,
            netlink::NLM_F_REQUEST | netlink::NLM_F_DUMP,
            &header,
            &[],
        )
        .map_err(|e| Error::os(e, format!("list {} rules", family_name(family))))?;
    let mut out = Vec::new();
    for msg in messages {
        if msg.len() < 12 {
            continue;
        }
        let mut rule = Rule {
            priority: 0,
            src: None,
        };
        for (ty, data) in attrs(&msg[12..]) {
            match ty {
                FRA_PRIORITY => rule.priority = ne32(data).unwrap_or(0),
                FRA_SRC if msg[2] > 0 => rule.src = ip_from(data).map(unmap),
                _ => {}
            }
        }
        out.push(rule);
    }
    Ok(out)
}

struct NewRule {
    family: u8,
    priority: u32,
    table: u32,
    src: Option<IpAddr>,
    suppress_prefixlen: Option<u32>,
    uid: Option<u32>,
}

impl NewRule {
    fn message(&self) -> (Vec<u8>, Vec<u8>) {
        let src_len = match self.src {
            Some(IpAddr::V4(_)) => 32,
            Some(IpAddr::V6(_)) => 128,
            None => 0,
        };
        let header = rule_header(self.family, src_len, self.table, FR_ACT_TO_TBL);
        let mut attributes = Vec::new();
        put_attr(&mut attributes, FRA_PRIORITY, &self.priority.to_ne_bytes());
        put_attr(&mut attributes, FRA_TABLE, &self.table.to_ne_bytes());
        if let Some(src) = self.src {
            put_attr(&mut attributes, FRA_SRC, &ip_bytes(src));
        }
        if let Some(n) = self.suppress_prefixlen {
            put_attr(&mut attributes, FRA_SUPPRESS_PREFIXLEN, &n.to_ne_bytes());
        }
        if let Some(uid) = self.uid {
            let mut range = uid.to_ne_bytes().to_vec();
            range.extend_from_slice(&uid.to_ne_bytes());
            put_attr(&mut attributes, FRA_UID_RANGE, &range);
        }
        (header, attributes)
    }

    fn add(&self, sock: &mut Socket) -> Result<(), Error> {
        let (header, attributes) = self.message();
        sock.request(
            netlink::RTM_NEWRULE,
            netlink::NLM_F_REQUEST
                | netlink::NLM_F_ACK
                | netlink::NLM_F_CREATE
                | netlink::NLM_F_EXCL,
            &header,
            &attributes,
        )
        .map(drop)
        .map_err(|e| {
            Error::os(
                e,
                format!("rule add {} (family {})", self.priority, self.family),
            )
        })
    }

    fn delete(&self, sock: &mut Socket) -> Result<(), Error> {
        let (header, attributes) = self.message();
        sock.request(
            netlink::RTM_DELRULE,
            netlink::NLM_F_REQUEST | netlink::NLM_F_ACK,
            &header,
            &attributes,
        )
        .map(drop)
        .map_err(|e| Error::os(e, format!("rule del {}", self.priority)))
    }
}

fn own_rule(addr: IpAddr) -> NewRule {
    NewRule {
        family: family_of(addr),
        priority: OWN_ADDRESS_PRIORITY,
        table: RT_TABLE_MAIN,
        src: Some(addr),
        suppress_prefixlen: None,
        uid: None,
    }
}

/// Deletes one rule of a priority, whatever it holds.
fn delete_priority(sock: &mut Socket, family: u8, priority: u32) -> Result<(), Error> {
    let header = rule_header(family, 0, 0, 0);
    let mut attributes = Vec::new();
    put_attr(&mut attributes, FRA_PRIORITY, &priority.to_ne_bytes());
    sock.request(
        netlink::RTM_DELRULE,
        netlink::NLM_F_REQUEST | netlink::NLM_F_ACK,
        &header,
        &attributes,
    )
    .map(drop)
    .map_err(|e| Error::os(e, format!("rule {priority}")))
}

/// Deletes every rule whose priority is in `first..=last`.
fn delete_priorities(sock: &mut Socket, family: u8, first: u32, last: u32) -> Result<(), Error> {
    let rules = list_rules(sock, family)?;
    let mut errors = Vec::new();
    for rule in rules {
        if (first..=last).contains(&rule.priority) {
            if let Err(e) = delete_priority(sock, family, rule.priority) {
                errors.push(e);
            }
        }
    }
    Error::join(errors)
}

/// What a route dump says about one route of a wanted table: enough to name it
/// again in a delete.
struct RouteEntry {
    header: Vec<u8>,
    attributes: Vec<u8>,
}

fn rtmsg(family: u8, table: u32, ty: u8, scope: u8) -> Vec<u8> {
    // family, dst_len, src_len, tos, table, protocol, scope, type, flags
    let mut h = vec![
        family,
        0,
        0,
        0,
        if table < 256 { table as u8 } else { 0 },
        RTPROT_BOOT,
        scope,
        ty,
    ];
    h.extend_from_slice(&0u32.to_ne_bytes());
    h
}

fn list_table(sock: &mut Socket, family: u8, table: u32) -> Result<Vec<RouteEntry>, Error> {
    let header = rtmsg(family, 0, 0, RT_SCOPE_UNIVERSE);
    let messages = sock
        .request(
            netlink::RTM_GETROUTE,
            netlink::NLM_F_REQUEST | netlink::NLM_F_DUMP,
            &header,
            &[],
        )
        .map_err(|e| Error::os(e, format!("list {} routes", family_name(family))))?;
    let mut out = Vec::new();
    for msg in messages {
        if msg.len() < 12 {
            continue;
        }
        let mut found = u32::from(msg[4]);
        let mut attributes = Vec::new();
        for (ty, data) in attrs(&msg[12..]) {
            match ty {
                RTA_TABLE => found = ne32(data).unwrap_or(found),
                RTA_DST | RTA_OIF | RTA_GATEWAY | RTA_PRIORITY | RTA_PREFSRC => {
                    put_attr(&mut attributes, ty, data)
                }
                _ => {}
            }
        }
        if found != table {
            continue;
        }
        put_attr(&mut attributes, RTA_TABLE, &table.to_ne_bytes());
        let mut header = msg[..12].to_vec();
        header[8..12].copy_from_slice(&0u32.to_ne_bytes()); // no flags
        out.push(RouteEntry { header, attributes });
    }
    Ok(out)
}

fn flush_table(sock: &mut Socket, family: u8, table: u32) -> Result<(), Error> {
    let mut errors = Vec::new();
    for route in list_table(sock, family, table)? {
        if let Err(e) = sock.request(
            netlink::RTM_DELROUTE,
            netlink::NLM_F_REQUEST | netlink::NLM_F_ACK,
            &route.header,
            &route.attributes,
        ) {
            errors.push(Error::os(e, format!("table {table} route")));
        }
    }
    Error::join(errors)
}

fn unreachable_default(sock: &mut Socket, family: u8) -> Result<(), Error> {
    let header = rtmsg(
        family,
        KILL_SWITCH_TABLE,
        RTN_UNREACHABLE,
        RT_SCOPE_UNIVERSE,
    );
    let mut attributes = Vec::new();
    put_attr(&mut attributes, RTA_TABLE, &KILL_SWITCH_TABLE.to_ne_bytes());
    sock.request(
        netlink::RTM_NEWROUTE,
        netlink::NLM_F_REQUEST
            | netlink::NLM_F_ACK
            | netlink::NLM_F_CREATE
            | netlink::NLM_F_REPLACE,
        &header,
        &attributes,
    )
    .map(drop)
    .map_err(|e| Error::os(e, format!("unreachable default (family {family})")))
}

fn tun_route(sock: &mut Socket, family: u8, index: u32) -> Result<(), Error> {
    let mut attributes = Vec::new();
    let header = if family == AF_INET6 {
        rtmsg(family, ENGINE_TABLE, RTN_BLACKHOLE, RT_SCOPE_UNIVERSE)
    } else {
        put_attr(&mut attributes, RTA_OIF, &index.to_ne_bytes());
        rtmsg(family, ENGINE_TABLE, RTN_UNICAST, RT_SCOPE_LINK)
    };
    put_attr(&mut attributes, RTA_TABLE, &ENGINE_TABLE.to_ne_bytes());
    sock.request(
        netlink::RTM_NEWROUTE,
        netlink::NLM_F_REQUEST
            | netlink::NLM_F_ACK
            | netlink::NLM_F_CREATE
            | netlink::NLM_F_REPLACE,
        &header,
        &attributes,
    )
    .map(drop)
    .map_err(|e| Error::os(e, format!("tun route (family {family})")))
}

fn kill_switch_rules(family: u8, uid: u32) -> [NewRule; 3] {
    [
        NewRule {
            family,
            priority: KILL_SWITCH_LAN,
            table: RT_TABLE_MAIN,
            src: None,
            suppress_prefixlen: Some(0),
            uid: None,
        },
        NewRule {
            family,
            priority: KILL_SWITCH_SELF,
            table: RT_TABLE_MAIN,
            src: None,
            suppress_prefixlen: None,
            uid: Some(uid),
        },
        NewRule {
            family,
            priority: KILL_SWITCH_DROP,
            table: KILL_SWITCH_TABLE,
            src: None,
            suppress_prefixlen: None,
            uid: None,
        },
    ]
}

fn open() -> Result<Socket, Error> {
    Socket::open().map_err(|e| Error::os(e, "netlink socket"))
}

impl Manager {
    pub fn new(exclude_iface: &str) -> Manager {
        Manager {
            exclude_iface: exclude_iface.to_string(),
            lock: Mutex::new(()),
        }
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The global-scope addresses of the host, those of the tun left out.
    pub fn global_addresses(&self) -> Result<Vec<IpAddr>, Error> {
        let mut sock = open()?;
        let exclude = std::ffi::CString::new(self.exclude_iface.as_str())
            .ok()
            // SAFETY: a valid NUL-terminated name.
            .map(|name| unsafe { libc::if_nametoindex(name.as_ptr()) })
            .unwrap_or(0);
        // ifaddrmsg: family (unspecified: both), prefixlen, flags, scope, index
        let request = [0u8; 8];
        let messages = sock
            .request(
                netlink::RTM_GETADDR,
                netlink::NLM_F_REQUEST | netlink::NLM_F_DUMP,
                &request,
                &[],
            )
            .map_err(|e| Error::os(e, "list addresses"))?;
        let mut out = Vec::new();
        for msg in messages {
            if msg.len() < 8 {
                continue;
            }
            let (scope, index) = (msg[3], ne32(&msg[4..]).unwrap_or(0));
            if scope != 0 || (exclude != 0 && index == exclude) {
                continue;
            }
            let mut address = None;
            let mut local = None;
            for (ty, data) in attrs(&msg[8..]) {
                match ty {
                    IFA_ADDRESS => address = ip_from(data),
                    IFA_LOCAL => local = ip_from(data),
                    _ => {}
                }
            }
            let Some(ip) = local.or(address).map(unmap) else {
                continue;
            };
            let link_local = match ip {
                IpAddr::V4(a) => a.is_link_local(),
                IpAddr::V6(a) => a.is_unicast_link_local(),
            };
            if ip.is_loopback() || link_local {
                continue;
            }
            out.push(ip);
        }
        Ok(out)
    }

    fn existing(sock: &mut Socket) -> Result<BTreeSet<IpAddr>, Error> {
        let mut out = BTreeSet::new();
        for family in FAMILIES {
            let rules = match list_rules(sock, family) {
                Ok(rules) => rules,
                Err(e) if skip_v6(family, &e) => continue,
                Err(e) => return Err(e),
            };
            for rule in rules {
                if rule.priority == OWN_ADDRESS_PRIORITY {
                    if let Some(src) = rule.src {
                        out.insert(src);
                    }
                }
            }
        }
        Ok(out)
    }

    /// Makes the set of own-address rules equal to `addresses`: missing ones
    /// added, extra ones removed, nothing duplicated.
    pub fn sync(&self, addresses: &[IpAddr]) -> Result<(), Error> {
        let _g = self.guard();
        Self::sync_locked(addresses)
    }

    fn sync_locked(addresses: &[IpAddr]) -> Result<(), Error> {
        let mut sock = open()?;
        let have = Self::existing(&mut sock)?;
        let want: BTreeSet<IpAddr> = addresses.iter().copied().map(unmap).collect();
        let mut errors = Vec::new();
        for addr in have.difference(&want) {
            if let Err(e) = own_rule(*addr).delete(&mut sock) {
                errors.push(Error {
                    message: format!("{e} ({addr})"),
                    ..e
                });
            }
        }
        for addr in want.difference(&have) {
            if let Err(e) = own_rule(*addr).add(&mut sock) {
                errors.push(Error {
                    message: format!("{e} ({addr})"),
                    ..e
                });
            }
        }
        Error::join(errors)
    }

    pub fn clear(&self) -> Result<(), Error> {
        self.sync(&[])
    }

    /// Puts the kill switch in for a daemon running as `uid`, replacing
    /// whatever a previous run left at its priorities.
    pub fn install_kill_switch(&self, uid: u32) -> Result<(), Error> {
        let _g = self.guard();
        let mut sock = open()?;
        let mut errors = Vec::new();
        for family in FAMILIES {
            if let Err(e) = delete_priorities(&mut sock, family, KILL_SWITCH_LAN, KILL_SWITCH_DROP)
            {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
            if let Err(e) = unreachable_default(&mut sock, family) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
                continue;
            }
            for rule in kill_switch_rules(family, uid) {
                if let Err(e) = rule.add(&mut sock) {
                    if skip_v6(family, &e) {
                        break;
                    }
                    errors.push(e);
                }
            }
        }
        match Error::join(errors) {
            Ok(()) => Ok(()),
            Err(e) => {
                let _ = Self::remove_kill_switch_locked(&mut sock);
                Err(e)
            }
        }
    }

    /// Takes the kill switch out: its rules and table 2023.
    pub fn remove_kill_switch(&self) -> Result<(), Error> {
        let _g = self.guard();
        let mut sock = open()?;
        Self::remove_kill_switch_locked(&mut sock)
    }

    fn remove_kill_switch_locked(sock: &mut Socket) -> Result<(), Error> {
        let mut errors = Vec::new();
        for family in FAMILIES {
            if let Err(e) = delete_priorities(sock, family, KILL_SWITCH_LAN, KILL_SWITCH_DROP) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
            if let Err(e) = flush_table(sock, family, KILL_SWITCH_TABLE) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
        }
        Error::join(errors)
    }

    /// Puts the tun's rules in (see `TUN_LOOKUP`), replacing what is there. The
    /// device must exist. `bypass_uid` is the engine's uid, whose traffic keeps
    /// the main table.
    pub fn install_tun_rules(&self, tun: &str, bypass_uid: Option<u32>) -> Result<(), Error> {
        let _g = self.guard();
        let mut sock = open()?;
        let index = std::ffi::CString::new(tun)
            .ok()
            // SAFETY: a valid NUL-terminated name.
            .map(|name| unsafe { libc::if_nametoindex(name.as_ptr()) })
            .unwrap_or(0);
        if index == 0 {
            return Err(Error {
                errnos: vec![libc::ENODEV],
                message: format!("no device {tun}"),
            });
        }
        let _ = Self::remove_tun_rules_locked(&mut sock);
        let mut errors = Vec::new();
        for family in FAMILIES {
            if let Err(e) = tun_route(&mut sock, family, index) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
                continue;
            }
            let mut rules = Vec::new();
            if let Some(uid) = bypass_uid {
                rules.push(NewRule {
                    family,
                    priority: TUN_BYPASS,
                    table: RT_TABLE_MAIN,
                    src: None,
                    suppress_prefixlen: None,
                    uid: Some(uid),
                });
            }
            rules.push(NewRule {
                family,
                priority: TUN_LOOKUP,
                table: ENGINE_TABLE,
                src: None,
                suppress_prefixlen: None,
                uid: None,
            });
            for rule in rules {
                if let Err(e) = rule.add(&mut sock) {
                    if skip_v6(family, &e) {
                        break;
                    }
                    errors.push(e);
                }
            }
        }
        match Error::join(errors) {
            Ok(()) => Ok(()),
            Err(e) => {
                let _ = Self::remove_tun_rules_locked(&mut sock);
                Err(e)
            }
        }
    }

    /// Takes the tun's rules and table 2022 out.
    pub fn remove_tun_rules(&self) -> Result<(), Error> {
        let _g = self.guard();
        let mut sock = open()?;
        Self::remove_tun_rules_locked(&mut sock)
    }

    fn remove_tun_rules_locked(sock: &mut Socket) -> Result<(), Error> {
        let mut errors = Vec::new();
        for family in FAMILIES {
            if let Err(e) = delete_priorities(sock, family, ENGINE_RULE_FIRST, ENGINE_RULE_LAST) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
            if let Err(e) = flush_table(sock, family, ENGINE_TABLE) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
        }
        Error::join(errors)
    }

    /// Removes what a crashed run left: our rules, the engine's rules (9000 to
    /// 9010), every route of table 2022 and the kill switch (the daemon puts it
    /// back at once when the stored selection asks). The box has one tun, ours.
    pub fn cleanup_stale(&self) -> Result<(), Error> {
        let _g = self.guard();
        let mut errors = Vec::new();
        if let Err(e) = Self::sync_locked(&[]) {
            errors.push(e);
        }
        let mut sock = open()?;
        if let Err(e) = Self::remove_kill_switch_locked(&mut sock) {
            errors.push(e);
        }
        for family in FAMILIES {
            if let Err(e) =
                delete_priorities(&mut sock, family, ENGINE_RULE_FIRST, ENGINE_RULE_LAST)
            {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
            if let Err(e) = flush_table(&mut sock, family, ENGINE_TABLE) {
                if !skip_v6(family, &e) {
                    errors.push(e);
                }
            }
        }
        Error::join(errors)
    }
}
