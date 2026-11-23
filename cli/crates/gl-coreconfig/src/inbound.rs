//! What listens for the traffic a core carries, and who may use it: the
//! app's `CoreInbound`. One inbound of the core's own, tagged `in` (the
//! tun's or proxy mode's), and beside it, while the tunnel is shared with
//! the local network, the LAN's, tagged `lan-in`, in the same core.

use crate::json::{strings, Json, Object};
use crate::spec::{Lan, Own, Plan, SocksLogin};
use crate::text::{is_blank, trim};

/// The tag the routing matches the core's own inbound by.
pub const TAG: &str = "in";

/// The LAN inbound's tag.
pub const LAN_TAG: &str = "lan-in";

/// Where the tunnel's inbound listens, and proxy mode's unless told another loopback address.
pub const LOOPBACK: &str = "127.0.0.1";

/// What a SOCKS5 login holds of a username and of a password.
pub const MAX_CREDENTIAL_BYTES: usize = 255;

pub const PORT_REFUSAL: &str = "a listener's port is from 1 to 65535";
pub const LOGIN_REFUSAL: &str = "a loopback SOCKS port other apps can reach needs a login";
pub const HALF_LOGIN_REFUSAL: &str = "a login is a username and a password, neither of them blank";
pub const LONG_LOGIN_REFUSAL: &str =
    "a SOCKS5 login holds at most 255 bytes of username and 255 of password";
pub const PROXY_LISTEN_REFUSAL: &str =
    "proxy mode listens on a loopback address (127.0.0.1 or ::1): \
     an address other devices reach is LAN sharing's, which demands a login";
pub const LAN_ADDRESS_REFUSAL: &str =
    "LAN sharing listens on one private IPv4 address of this machine \
     (in 10/8, 172.16/12 or 192.168/16), not on every address, a public one or a name";
pub const LAN_PORT_REFUSAL: &str =
    "LAN sharing's port is from 1024 to 65535 and is not the core's own";
pub const LAN_LOGIN_REFUSAL: &str =
    "LAN sharing demands a login: the username and the password the devices on the network are given";

/// Why the tun's login is none: a loopback port is open to every program on the machine.
pub fn login_refusal_of_tunnel(login: &SocksLogin) -> Option<&'static str> {
    login_refusal(login, LOGIN_REFUSAL)
}

/// Why the core would refuse to listen as `plan` says, or None.
pub fn refusal(plan: &Plan) -> Option<&'static str> {
    own_refusal(&plan.own).or_else(|| {
        plan.lan
            .as_ref()
            .and_then(|lan| lan_refusal(lan, plan.own.port()))
    })
}

/// `listen` as the core is to be given it when it is a loopback address
/// (127/8 or ::1, brackets taken off), else None.
pub fn loopback_address(listen: &str) -> Option<String> {
    let trimmed = trim(listen);
    let bare = trimmed
        .strip_prefix('[')
        .and_then(|t| t.strip_suffix(']'))
        .unwrap_or(trimmed);
    (bare == "::1" || ipv4(bare).is_some_and(|o| o[0] == 127)).then(|| bare.to_string())
}

/// Whether `address` is an IPv4 address in 10/8, 172.16/12 or 192.168/16.
pub fn is_private_ipv4(address: &str) -> bool {
    let Some(o) = ipv4(address) else { return false };
    o[0] == 10 || (o[0] == 172 && (16..=31).contains(&o[1])) || (o[0] == 192 && o[1] == 168)
}

fn own_refusal(own: &Own) -> Option<&'static str> {
    match own {
        Own::Tunnel { port, login } => {
            port_refusal(*port).or_else(|| login_refusal(login, LOGIN_REFUSAL))
        }
        Own::Proxy {
            port,
            login,
            listen,
        } => port_refusal(*port)
            .or_else(|| {
                loopback_address(listen)
                    .is_none()
                    .then_some(PROXY_LISTEN_REFUSAL)
            })
            .or_else(|| {
                login
                    .as_ref()
                    .and_then(|l| login_refusal(l, HALF_LOGIN_REFUSAL))
            }),
    }
}

fn lan_refusal(lan: &Lan, own_port: i32) -> Option<&'static str> {
    if !is_private_ipv4(&lan.address) {
        Some(LAN_ADDRESS_REFUSAL)
    } else if !(1024..=65535).contains(&lan.port) || lan.port == own_port {
        Some(LAN_PORT_REFUSAL)
    } else {
        login_refusal(&lan.login, LAN_LOGIN_REFUSAL)
    }
}

fn port_refusal(port: i32) -> Option<&'static str> {
    (!(1..=65535).contains(&port)).then_some(PORT_REFUSAL)
}

fn login_refusal(login: &SocksLogin, blank: &'static str) -> Option<&'static str> {
    if is_blank(&login.username) || is_blank(&login.password) {
        Some(blank)
    } else if login.username.len() > MAX_CREDENTIAL_BYTES
        || login.password.len() > MAX_CREDENTIAL_BYTES
    {
        Some(LONG_LOGIN_REFUSAL)
    } else {
        None
    }
}

/// Four decimal octets as the core's parser reads an IPv4 address: no
/// leading zeros, none above 255.
fn ipv4(text: &str) -> Option<[u16; 4]> {
    let parts: Vec<&str> = text.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    let mut octets = [0u16; 4];
    for (i, part) in parts.iter().enumerate() {
        let ok = (1..=3).contains(&part.len())
            && part.bytes().all(|b| b.is_ascii_digit())
            && !(part.len() > 1 && part.starts_with('0'));
        if !ok {
            return None;
        }
        octets[i] = part.parse().ok().filter(|n| *n <= 255)?;
    }
    Some(octets)
}

/// The plan's inbounds: the core's own, then the LAN's, each SOCKS5 with
/// UDP on and a sniff that names a connection for the rules only.
pub fn inbounds(plan: &Plan) -> Result<Vec<Json>, &'static str> {
    if let Some(why) = refusal(plan) {
        return Err(why);
    }
    let mut out = Vec::new();
    match &plan.own {
        Own::Tunnel { port, login } => out.push(socks(TAG, LOOPBACK, *port, Some(login), false)),
        Own::Proxy {
            port,
            login,
            listen,
        } => {
            let listen =
                loopback_address(listen).expect("a refused listen address is never written");
            out.push(socks(TAG, &listen, *port, login.as_ref(), false));
        }
    }
    if let Some(lan) = &plan.lan {
        out.push(socks(
            LAN_TAG,
            &lan.address,
            lan.port,
            Some(&lan.login),
            true,
        ));
    }
    Ok(out)
}

fn socks(tag: &str, listen: &str, port: i32, login: Option<&SocksLogin>, lan: bool) -> Json {
    let mut settings = Object::new();
    match login {
        Some(login) => {
            let mut account = Object::new();
            account.put("user", &login.username);
            account.put("pass", &login.password);
            settings.put("auth", "password");
            settings.put("accounts", Json::Array(vec![account.into()]));
        }
        None => settings.put("auth", "noauth"),
    }
    settings.put("udp", true);
    // The parser's name for a listener off loopback, which it refuses
    // without this whether or not it demands a login. This one does.
    if lan {
        settings.put("allowUnauthenticatedLan", true);
    }
    let mut sniffing = Object::new();
    sniffing.put("enabled", true);
    sniffing.put("destOverride", strings(&["http", "tls", "quic"]));
    sniffing.put("routeOnly", true);
    let mut inbound = Object::new();
    inbound.put("tag", tag);
    inbound.put("protocol", "socks");
    inbound.put("listen", listen);
    inbound.put("port", port);
    inbound.put("settings", settings);
    inbound.put("sniffing", sniffing);
    inbound.into()
}
