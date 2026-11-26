//! `core.json` for a line: what the Rust core (`ghostlane-engine`) is
//! started from, written as the app writes it for every platform.
//!
//! The app's builder is the source (sharedUI `net/core`: `CoreConfig`,
//! `CoreInbound`, `CoreRefusal`, `CoreLines` and the kinds under `lines/`);
//! this is its port for the CLI, which runs no JVM. It writes what the app
//! writes under Global (the CLI has no routing modes): one SOCKS5 inbound of
//! the core's own and, while the tunnel is shared, the LAN's; the line's
//! outbound; names answered by the core with fake addresses, every other
//! query type sent through the line. The same bytes, key for key: the
//! vectors the app's test writes (`vectors/core-inputs.json` beside the
//! app's tests, and the reference configs it names) hold the two together,
//! and `vectors.sha256` beside this crate names every file they are.
//!
//! A line the core would refuse is not written at all: [`refusal`] says why
//! in a sentence that holds no secret.

pub mod inbound;
pub mod json;
pub mod lines;
pub mod spec;
mod support;
pub mod text;

use std::fmt;

use json::{strings, Json, Object};
pub use lines::{hello_note, reality_hello};
pub use spec::{Lan, Own, Plan, SocksLogin, Spec, Tls, Transport};
pub use support::hello_name;

/// The line's outbound tag: first, so what no rule claims rides it.
pub const LINE: &str = "out";

/// The SOCKS port the app gives the tun's inbound by default.
pub const DEFAULT_SOCKS_PORT: i32 = 10810;

/// The fake addresses the core hands out for names bound for the tunnel.
pub const FAKE_IP_POOL: &str = "198.18.0.0/15";

/// The resolver through the line that answers every query type but A, AAAA and HTTPS.
pub const REMOTE_DNS_SERVER: &str = "1.1.1.1";

/// DNS type HTTPS, refused.
const QTYPE_HTTPS: i32 = 65;

/// REFUSED.
const RCODE_REFUSED: i32 = 5;

/// Every query type but A (1), AAAA (28) and HTTPS (65), and not 0.
const QTYPES_FORWARDED: &str = "2-27,29-64,66-65535";

/// A server named by a hostname: the core would resolve it through the
/// tunnel's own resolver. The caller resolves the name on the network
/// underneath and pins the address ([`pin_host`]).
pub const HOSTNAME: &str =
    "the server is named by a hostname, which the core would resolve through the tunnel's own resolver";

/// `allowInsecure` on ordinary TLS, in the words every kind that refuses it uses.
pub const WAIVED_CHECKS: &str =
    "the link waives certificate checks (allowInsecure), which the core does not do";

/// Why no config is written, in the words of the app's exception.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BuildError {
    /// The tun's login is none, or longer than SOCKS5 carries.
    Login(&'static str),
    /// The core would refuse the line ([`refusal`]).
    Line(String),
    /// The tun's port is none.
    Port(&'static str),
    /// The core would refuse to listen as the plan says ([`inbound::refusal`]).
    Listen(&'static str),
}

impl fmt::Display for BuildError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            BuildError::Login(why) | BuildError::Port(why) => f.write_str(why),
            BuildError::Line(why) => write!(f, "the Rust core cannot run this line: {why}"),
            BuildError::Listen(why) => write!(f, "the Rust core cannot listen so: {why}"),
        }
    }
}

impl std::error::Error for BuildError {}

/// Why the core would refuse `spec`, or None when it takes it: a link no
/// kind writes, a server named by a hostname, or the kind's own refusal.
pub fn refusal(spec: &Spec) -> Option<String> {
    let Some(line) = lines::of(spec) else {
        return Some(no_line(spec));
    };
    if !text::is_ip_literal(spec.host()) {
        return Some(HOSTNAME.to_string());
    }
    line.refusal(spec)
}

/// A link no kind writes a line for.
pub fn no_line(spec: &Spec) -> String {
    format!(
        "the Rust core has no {} line of this shape",
        spec.protocol_name()
    )
}

/// `spec` written with `address` for its server, for a line named by a
/// hostname that the caller resolved on the network underneath. A spec
/// whose host is already an address, or that is no kind's, comes back as
/// it is.
pub fn pin_host(spec: &Spec, address: &str) -> Spec {
    if text::is_ip_literal(spec.host()) {
        return spec.clone();
    }
    match lines::of(spec) {
        Some(line) => line.pin_host(spec, address),
        None => spec.clone(),
    }
}

/// The config for `spec`: one SOCKS5 inbound on 127.0.0.1:`socks_port`
/// demanding `login` (the tun's), and the line. `log` is at warning (debug
/// with `verbose_logs`) with no access log.
pub fn build(
    spec: &Spec,
    login: &SocksLogin,
    socks_port: i32,
    verbose_logs: bool,
) -> Result<String, BuildError> {
    if let Some(why) = inbound::login_refusal_of_tunnel(login) {
        return Err(BuildError::Login(why));
    }
    let line = written_line(spec)?;
    let plan = Plan {
        own: Own::Tunnel {
            port: socks_port,
            login: login.clone(),
        },
        lan: None,
    };
    let inbounds = inbound::inbounds(&plan).map_err(BuildError::Port)?;
    Ok(write(line, spec, inbounds, verbose_logs))
}

/// The config for `spec` listening as `plan` says: what [`build`] writes
/// for the line, with the plan's inbounds. A plan of the tun alone is
/// [`build`]'s config for its port and login, byte for byte.
pub fn build_plan(spec: &Spec, plan: &Plan, verbose_logs: bool) -> Result<String, BuildError> {
    if let Some(why) = inbound::refusal(plan) {
        return Err(BuildError::Listen(why));
    }
    let line = written_line(spec)?;
    let inbounds = inbound::inbounds(plan).map_err(BuildError::Listen)?;
    Ok(write(line, spec, inbounds, verbose_logs))
}

fn written_line(spec: &Spec) -> Result<&'static dyn lines::Line, BuildError> {
    if let Some(why) = refusal(spec) {
        return Err(BuildError::Line(why));
    }
    Ok(lines::of(spec).expect("a line no kind takes is refused"))
}

fn write(line: &dyn lines::Line, spec: &Spec, inbounds: Vec<Json>, verbose_logs: bool) -> String {
    let mut root = Object::new();

    // The core reads no `log`: the embedding decides where its lines go.
    // Written for whoever reads the file, as Xray reads it: no access log.
    let mut log = Object::new();
    log.put("loglevel", if verbose_logs { "debug" } else { "warning" });
    log.put("access", "none");
    root.put("log", log);

    let mut fake_ip = Object::new();
    fake_ip.put("enabled", true);
    fake_ip.put("ipv4Pool", FAKE_IP_POOL);
    let mut dns = Object::new();
    dns.put("queryStrategy", "UseIPv4");
    dns.put("fakeIp", fake_ip);
    root.put("dns", dns);

    root.put("inbounds", Json::Array(inbounds));

    let mut out = Object::new();
    out.put("tag", LINE);
    line.put_outbound(spec, &mut out);
    root.put(
        "outbounds",
        Json::Array(vec![out.into(), dns_outbound().into()]),
    );

    let mut dns_rule = Object::new();
    dns_rule.put("type", "field");
    dns_rule.put("inboundTag", strings(&[inbound::TAG]));
    dns_rule.put("network", "tcp,udp");
    dns_rule.put("port", 53);
    dns_rule.put("outboundTag", "dns-out");
    let mut routing = Object::new();
    routing.put("domainStrategy", "AsIs");
    routing.put("rules", Json::Array(vec![dns_rule.into()]));
    root.put("routing", routing);

    Json::Object(root).to_string()
}

/// Answers every query the routing sends here: A with a fake address, AAAA
/// with none, HTTPS refused, and every other type forwarded through the line.
fn dns_outbound() -> Object {
    let mut https = Object::new();
    https.put("action", "return");
    https.put("qType", QTYPE_HTTPS);
    https.put("rCode", RCODE_REFUSED);
    let mut forward = Object::new();
    forward.put("action", "forward");
    forward.put("qType", QTYPES_FORWARDED);
    forward.put("address", REMOTE_DNS_SERVER);
    forward.put("port", 53);
    forward.put("outboundTag", LINE);
    let mut settings = Object::new();
    settings.put("rules", Json::Array(vec![https.into(), forward.into()]));
    let mut out = Object::new();
    out.put("tag", "dns-out");
    out.put("protocol", "dns");
    out.put("settings", settings);
    out
}
