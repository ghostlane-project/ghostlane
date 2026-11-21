//! The command line: the same commands, flags, words and exit codes as the Go
//! program's `cmd/ghostlane` (0 done, 1 refused or failed, 2 a command line
//! that says nothing usable, 3 no daemon to ask).
//!
//! `run` is the daemon. Here it is the part that this crate holds: it reads
//! the config, applies the arguments and the environment to it as the Go
//! daemon does at its start, and serves the control socket. What connects a
//! line is not in this crate, and the daemon says so to whoever asks.

use std::fmt::Write as _;
use std::io::Write;
use std::net::IpAddr;
use std::path::Path;
use std::thread;

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use gocompat::text::{hex, quote};
use gocompat::time::Timestamp;
use sha2::{Digest as _, Sha256};
use signal_hook::consts::{SIGINT, SIGTERM};
use signal_hook::iterator::Signals;

use crate::ipc::{self, CallError, EntryView, Request, Response, Server, Status, VersionInfo};
use crate::store::{self, Config, Selection, Subscription};

/// Set at build time (`GHOSTLANE_VERSION`), as the Go build sets its own.
pub const VERSION: &str = match option_env!("GHOSTLANE_VERSION") {
    Some(version) => version,
    None => "dev",
};

/// The engine this build is made with. It is a program of its own, and no
/// build pins one yet.
const ENGINE: &str = "unknown";

/// The key every release's SHA256SUMS is signed with (base64 of its DER);
/// install.sh carries the same one.
const RELEASE_KEY: &str = "MCowBQYDK2VwAyEAz252wYKYdbLzn/fyN1ZvGt7oTDNZTNJrWosR8/L+S/0=";

const USAGE: &str = r#"ghostlane: Ghostlane for Linux servers and boxes.
Connects the lines of your subscription: olcRTC rooms (a tunnel inside a
video call), VLESS Reality, Hysteria2 and XHTTP, with failover between them.
Routes the machine through the tunnel: the whole box (tun) or a local
proxy (proxy).

Usage:
  ghostlane <command> [arguments] [flags]

Commands:
  add <source>         Add a subscription: a list URL, a ghostlane:// link, or one olcrtc://, vless:// or hysteria2:// line
  list                 Every line with its index, country and carrier
  connect <selector>   Connect a country (DE), a label ("DE · SJ") or an index (6); --tun [--kill-switch] or --proxy
  status               What is connected, since when, and the proxy lines to paste
  disconnect           Stop the tunnel and forget the selection
  refresh              Re-fetch every list now
  remove <sub>         Remove a subscription by URL, masked-URL prefix or title
  version              Version, engine pin, release signing key
  run                  The daemon itself (systemd runs it; use directly in containers)
  help [command]       This text, or one command's details and examples

Quick start:
  ghostlane add 'https://…/sub/…'      # the URL your provider gave you (or one vless:// / hy2:// / olcrtc:// line)
  ghostlane list
  ghostlane connect DE --proxy         # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
  ghostlane connect DE --tun           # the whole machine; SSH and your services keep working
  ghostlane status

Global flags:
  --socket <path>   control socket (default /run/ghostlane/ghostlane.sock)
  --json            machine-readable output for list, status and version

Docs: https://github.com/ghostlane-project/ghostlane/blob/main/docs/cli.md
"#;

const HELP_ADD: &str = r#"ghostlane add <source>

Adds a subscription and fetches it at once. <source> is one of:
  a list URL          https://provider.example/sub/…  (the ?c=olcbox variant of a partner list)
  a ghostlane:// link ghostlane://add?url=…  or  ghostlane://add/<url>
  one line            'olcrtc://telemost?vp8channel@…#<key>$DE · olcRTC', a vless:// or a
                      hysteria2:// (hy2://) line, as a provider hands them out

The list is refreshed on the interval the provider announces (default every
24 h) and on 'ghostlane refresh'. The URL is a credential: it is stored
root-only under /var/lib/ghostlane and never printed in full.

Examples:
  ghostlane add 'https://sub.example/sub/a1b2c3/token?c=olcbox'
  ghostlane add 'olcrtc://wbstream?vp8channel@room_x#<64 hex>$DE · VP8 · WB'
  ghostlane add 'vless://<uuid>@203.0.113.9:443?type=tcp&security=reality&sni=…&pbk=…&sid=…&flow=xtls-rprx-vision#DE'
"#;

const HELP_LIST: &str = r#"ghostlane list [--json]

Shows every line of every subscription: its index (for 'connect <index>'),
country, kind (olcrtc, vless, hysteria2), carrier and label. A line this
version cannot connect (VLESS over grpc/ws, trojan, shadowsocks, vmess) is
listed with a note saying why. A list whose provider turned this device away
has no lines, and the reason is printed under the table.

Example:
  ghostlane list
   #   COUNTRY  KIND    CARRIER     LABEL             NOTE
   1   DE       olcrtc  telemost    🇩🇪 DE · VP8
   2   DE       olcrtc  wbstream    🇩🇪 DE · VP8 · WB
"#;

const HELP_CONNECT: &str = r#"ghostlane connect <selector> [--tun [--kill-switch] | --proxy] [--subscription <url-prefix>]

<selector> is a country code (all of that country's lines, tried in the
list's order with failover between them, rooms and servers alike), an
exact label from 'list' (one line), or an index from 'list'. Lists whose
labels carry no country code (city names) are selected by label or index.

Which core carries what: olcRTC rooms run in the engine, XHTTP lines in
Xray-core, VLESS Reality and Hysteria2 in sing-box itself. In tun mode a
Reality or Hysteria2 server is proven through a local proxy first, so a dead
server never gets the tun.

  --proxy   (default) a local SOCKS5 + HTTP proxy on 127.0.0.1:1080; needs no
            privileges; 'status' prints the environment lines to paste.
  --tun     routes the whole machine through the tunnel. Inbound services
            (SSH, web, databases) keep answering on the public address; private
            ranges stay direct; IPv6 is refused rather than leaked. Needs the
            service's CAP_NET_ADMIN (the installed unit has it).
  --kill-switch  with --tun: while the selection stands and no line is up
            (boot, a failover, a dead server), traffic that is not for the
            tunnel is refused instead of leaking. The LAN, Docker networks and
            inbound services keep working; 'disconnect' or stopping the service
            opens the box again. Connections opened before the switch keep
            their path.

The selection is remembered: the service reconnects it after a reboot.
A line that does not answer within a minute, or three failed liveness probes
in a row, moves the connection to the next line of the selection. A line
chosen by its label or index stays that line, in its own list: while that
list's provider turns this device away, the connection waits ('status' says
why) and never moves to another list's line.

Examples:
  ghostlane connect DE --tun
  ghostlane connect "🇩🇪 DE · SJ" --proxy
  ghostlane connect 6
"#;

const HELP_STATUS: &str = r#"ghostlane status [--json]

Prints the state (idle | connecting | up | failed), the selection and mode,
the connected line and carrier, since when, the last error, the proxy
addresses with the export lines to paste, and each subscription with its
next refresh, traffic quota and last error.
"#;

const HELP_DISCONNECT: &str = r#"ghostlane disconnect

Stops the tunnel (the tun and its rules go away; traffic flows directly
again) and clears the stored selection, so nothing reconnects at boot.
"#;

const HELP_REFRESH: &str = r#"ghostlane refresh

Re-fetches every subscription now. A refresh never disconnects by itself;
if the connected room or key was rotated, the daemon reconnects to the new one.
A list whose provider turned this device away is named with the reason.
"#;

const HELP_REMOVE: &str = r#"ghostlane remove <subscription>

Removes a subscription by its URL, by the masked URL 'status' shows
(prefix is enough), or by its title. Removing the subscription in use
disconnects first.

Example:
  ghostlane remove 'https://sub.example/sub/a1b2c3/'
"#;

const HELP_VERSION: &str = r#"ghostlane version [--json]

Prints the version, the pinned engine version, and the sha256 of the public
key every release is signed with (install.sh verifies against it).
"#;

const HELP_RUN: &str = r#"ghostlane run [--state-dir <dir>] [--socket <path>] [--subscription <url> --connect <selector> --mode tun|proxy [--kill-switch]]

The daemon, in the foreground. systemd (or OpenRC) runs it as user ghostlane
with CAP_NET_ADMIN; in a container run it yourself:

  ghostlane run --state-dir /data --subscription 'https://…' --connect DE --mode proxy

The proxy is set through the environment in a container: GHOSTLANE_PROXY_LISTEN
(0.0.0.0 to publish the port), GHOSTLANE_PROXY_PORT, GHOSTLANE_PROXY_USER and
GHOSTLANE_PROXY_PASS: a listen that is not loopback needs the credentials.
For --mode tun the container needs --cap-add NET_ADMIN --device /dev/net/tun.
Other flags: --probe-url <url> (liveness probe through the tunnel).
Image: ghcr.io/ghostlane-project/ghostlane-cli (see docs/cli.md).
"#;

fn help_for(command: &str) -> Option<&'static str> {
    todo!()
}

/// Writes to the terminal like Go's `fmt.Fprint`: a write that fails is not
/// the command's failure.
macro_rules! say {
    ($out:expr, $($arg:tt)*) => {{
        let _ = write!($out, $($arg)*);
    }};
}

/// The same with a line break after it.
macro_rules! sayln {
    ($out:expr) => {{
        let _ = writeln!($out);
    }};
    ($out:expr, $($arg:tt)*) => {{
        let _ = writeln!($out, $($arg)*);
    }};
}

/// Runs one command line (without the program's name) and returns the exit
/// code.
pub fn run(args: &[String], stdout: &mut dyn Write, stderr: &mut dyn Write) -> u8 {
    todo!()
}

/// What this build is made of, for `version` and for the daemon's answer.
pub fn version_info() -> VersionInfo {
    todo!()
}

/// The sha256 of the release signing key, as install.sh prints it.
pub fn release_key_fingerprint() -> String {
    todo!()
}

fn version(args: &[String], stdout: &mut dyn Write, stderr: &mut dyn Write) -> u8 {
    todo!()
}

fn client(command: &str, args: &[String], stdout: &mut dyn Write, stderr: &mut dyn Write) -> u8 {
    todo!()
}

fn print_json<T: serde::Serialize>(out: &mut dyn Write, value: &T) -> u8 {
    todo!()
}

fn print_status(out: &mut dyn Write, status: Option<&Status>) {
    todo!()
}

/// Prints the lines; `note` is what the daemon says of the lists, such as
/// why one has none.
fn print_list(out: &mut dyn Write, entries: &[EntryView], note: &str) {
    todo!()
}

/// Go's `text/tabwriter` as the list uses it: cells end at a tab, a column
/// is as wide as its widest cell plus `padding` spaces, and the last cell of
/// a line is not part of a column. A column is measured over the lines that
/// follow each other and have it, so a line without tabs starts the measuring
/// anew. Widths are counted in characters.
fn tabulate(text: &str, padding: usize) -> String {
    todo!()
}

/// tabwriter's `format`: writes the lines `from..to`, whose columns up to
/// `widths.len()` are measured, block by block of the next column.
fn lay_out(
    lines: &[Vec<String>],
    from: usize,
    to: usize,
    widths: &mut Vec<usize>,
    padding: usize,
    out: &mut String,
) {
    todo!()
}

/// tabwriter's `writeLines`: each cell, padded to its column's width where
/// it has one, and a line break after every line but the last one there is.
fn write_lines(lines: &[Vec<String>], from: usize, to: usize, widths: &[usize], out: &mut String) {
    todo!()
}

/// One flag of a command.
struct Flag {
    name: &'static str,
    usage: &'static str,
    /// A flag that needs no value: `--tun`, or `--tun=false`.
    boolean: bool,
    default: String,
    value: String,
}

/// Go's `flag.FlagSet` as the commands use it: `-name` and `--name` are one
/// flag, a value follows after `=` or as the next argument, parsing stops at
/// the first argument that is not a flag or after `--`, and a mistake is
/// answered with the sentence and the list Go prints.
struct FlagSet {
    name: String,
    flags: Vec<Flag>,
    /// What the last parse left: the arguments from the first that is not a flag.
    rest: Vec<String>,
}

impl FlagSet {
    fn new(name: &str) -> FlagSet {
        todo!()
    }

    fn text(mut self, name: &'static str, default: &str, usage: &'static str) -> FlagSet {
        todo!()
    }

    fn boolean(mut self, name: &'static str, usage: &'static str) -> FlagSet {
        todo!()
    }

    fn value(&self, name: &str) -> &str {
        todo!()
    }

    fn is_on(&self, name: &str) -> bool {
        todo!()
    }

    /// Reads the flags at the start of `args`; false when one was wrong
    /// (which is said on `stderr`, with the flags there are).
    fn parse(&mut self, args: &[String], stderr: &mut dyn Write) -> bool {
        todo!()
    }

    /// Go's `parseOne`: true when a flag was read, false at the end of the
    /// flags; an error is the complaint to print, none for a call for help.
    fn parse_one(&mut self, args: &[String], at: &mut usize) -> Result<bool, Option<String>> {
        todo!()
    }

    /// Go's default usage: the flags by name, each with its use and the
    /// value it has when it is not given.
    fn usage(&self) -> String {
        todo!()
    }
}

/// Go's `strconv.ParseBool`.
fn parse_bool(text: &str) -> Option<bool> {
    todo!()
}

/// `GHOSTLANE_STATE_DIR` when set (the Docker image sets it to /data, the
/// volume), else the package's directory.
pub fn default_state_dir(getenv: &dyn Fn(&str) -> String) -> String {
    todo!()
}

/// An address the machine alone reaches, as Go's `IsLoopback` has it: an
/// IPv4 one written as IPv6 counts too.
fn is_loopback(address: IpAddr) -> bool {
    todo!()
}

/// Applies `GHOSTLANE_PROXY_LISTEN`, `_PORT`, `_USER` and `_PASS` to the
/// config (containers set the proxy this way) and says whether any was set.
/// A listen that is not loopback needs credentials, or the proxy would be
/// open to whoever reaches the port.
pub fn apply_proxy_env(
    config: &mut Config,
    getenv: &dyn Fn(&str) -> String,
) -> Result<bool, String> {
    todo!()
}

fn run_daemon(args: &[String], stderr: &mut dyn Write) -> u8 {
    todo!()
}

/// What the daemon of this crate answers: what it is made of, and for the
/// rest that it has nothing to do it with. The verbs are the Go daemon's.
pub fn answer(request: Request) -> Response {
    todo!()
}
