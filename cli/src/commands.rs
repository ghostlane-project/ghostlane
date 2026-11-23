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
    Some(match command {
        "add" => HELP_ADD,
        "list" => HELP_LIST,
        "connect" => HELP_CONNECT,
        "status" => HELP_STATUS,
        "disconnect" => HELP_DISCONNECT,
        "refresh" => HELP_REFRESH,
        "remove" => HELP_REMOVE,
        "version" => HELP_VERSION,
        "run" => HELP_RUN,
        _ => return None,
    })
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
    let Some((command, rest)) = args.split_first() else {
        say!(stderr, "{USAGE}");
        return 2;
    };
    let asks_help = matches!(rest.first().map(String::as_str), Some("-h" | "--help"));
    match command.as_str() {
        "run" | "version" | "add" | "list" | "connect" | "disconnect" | "status" | "refresh"
        | "remove"
            if asks_help =>
        {
            say!(stdout, "{}", help_for(command).unwrap_or(USAGE));
            0
        }
        "run" => run_daemon(rest, stderr),
        "version" => version(rest, stdout, stderr),
        "add" | "list" | "connect" | "disconnect" | "status" | "refresh" | "remove" => {
            client(command, rest, stdout, stderr)
        }
        "-h" | "--help" | "help" => match rest.first() {
            None => {
                say!(stdout, "{USAGE}");
                0
            }
            Some(topic) => match help_for(topic) {
                Some(help) => {
                    say!(stdout, "{help}");
                    0
                }
                None => {
                    say!(stderr, "no help for {}\n{USAGE}", quote(topic));
                    2
                }
            },
        },
        _ => {
            say!(stderr, "unknown command {}\n{USAGE}", quote(command));
            2
        }
    }
}

/// What this build is made of, for `version` and for the daemon's answer.
pub fn version_info() -> VersionInfo {
    VersionInfo {
        version: VERSION.to_string(),
        engine: ENGINE.to_string(),
        ..VersionInfo::default()
    }
}

/// The sha256 of the release signing key, as install.sh prints it.
pub fn release_key_fingerprint() -> String {
    match STANDARD.decode(RELEASE_KEY) {
        Ok(der) => hex(&Sha256::digest(der)),
        Err(_) => "invalid".to_string(),
    }
}

fn version(args: &[String], stdout: &mut dyn Write, stderr: &mut dyn Write) -> u8 {
    let mut flags = FlagSet::new("version").boolean("json", "machine-readable output");
    if !flags.parse(args, stderr) {
        return 2;
    }
    let info = version_info();
    if flags.is_on("json") {
        return print_json(stdout, &info);
    }
    let crypt = if info.crypt1 {
        "available"
    } else {
        "unavailable (no key in this build)"
    };
    say!(
        stdout,
        "ghostlane {}\nengine {}\ncrypt1 lists {crypt}\nrelease signing key sha256 {}\n",
        info.version,
        info.engine,
        release_key_fingerprint()
    );
    0
}

fn client(command: &str, args: &[String], stdout: &mut dyn Write, stderr: &mut dyn Write) -> u8 {
    let mut flags = FlagSet::new(command)
        .text("socket", ipc::DEFAULT_SOCKET_PATH, "control socket")
        .boolean("json", "machine-readable output")
        .boolean(
            "tun",
            "route the whole machine (needs the service's CAP_NET_ADMIN)",
        )
        .boolean("proxy", "local SOCKS5/HTTP proxy only")
        .boolean(
            "kill-switch",
            "with --tun: refuse traffic outside the tunnel while no line is up",
        )
        .text("subscription", "", "restrict to one subscription");
    // flags may follow the positional argument: `connect DE --tun`
    let mut positional: Vec<String> = Vec::new();
    let mut left = args.to_vec();
    while !left.is_empty() {
        if !flags.parse(&left, stderr) {
            return 2;
        }
        left = flags.rest.clone();
        if !left.is_empty() {
            positional.push(left.remove(0));
        }
    }
    let mut request = Request {
        verb: command.to_string(),
        subscription: flags.value("subscription").to_string(),
        ..Request::default()
    };
    match command {
        "add" | "remove" | "connect" => {
            let [argument] = positional.as_slice() else {
                say!(stderr, "{command} needs exactly one argument\n{USAGE}");
                return 2;
            };
            match command {
                "add" => request.source.clone_from(argument),
                "remove" => request.subscription.clone_from(argument),
                _ => {
                    request.selector.clone_from(argument);
                    let (tun, proxy, kill_switch) = (
                        flags.is_on("tun"),
                        flags.is_on("proxy"),
                        flags.is_on("kill-switch"),
                    );
                    if tun && proxy {
                        sayln!(stderr, "pick one of --tun and --proxy");
                        return 2;
                    }
                    if kill_switch && !tun {
                        sayln!(stderr, "the kill switch needs --tun");
                        return 2;
                    }
                    if tun {
                        request.mode = "tun".to_string();
                        request.kill_switch = kill_switch;
                    } else if proxy {
                        request.mode = "proxy".to_string();
                    }
                }
            }
        }
        _ if !positional.is_empty() => {
            sayln!(stderr, "{command} takes no argument");
            return 2;
        }
        _ => {}
    }
    let response = match ipc::call(Path::new(flags.value("socket")), &request) {
        Ok(response) => response,
        Err(err) => {
            sayln!(stderr, "{err}");
            return match err {
                CallError::DaemonDown(_) => 3,
                CallError::Exchange(_) => 1,
            };
        }
    };
    if !response.ok {
        sayln!(stderr, "{}: {}", response.error, response.message);
        return 1;
    }
    if flags.is_on("json") {
        return print_json(stdout, &response);
    }
    match command {
        "status" => print_status(stdout, response.status.as_ref()),
        "list" => print_list(stdout, &response.entries, &response.message),
        _ if !response.message.is_empty() => sayln!(stdout, "{}", response.message),
        _ => {}
    }
    0
}

fn print_json<T: serde::Serialize>(out: &mut dyn Write, value: &T) -> u8 {
    match gocompat::json::encode_indent(value) {
        Ok(text) if out.write_all(&text).is_ok() => 0,
        _ => 1,
    }
}

fn print_status(out: &mut dyn Write, status: Option<&Status>) {
    let Some(status) = status else {
        sayln!(out, "no status");
        return;
    };
    sayln!(out, "State:     {}", status.state);
    if !status.selector.is_empty() {
        let kill_switch = if status.kill_switch {
            ", kill switch on"
        } else {
            ""
        };
        sayln!(
            out,
            "Selection: {} ({} mode{kill_switch})",
            status.selector,
            status.mode
        );
    }
    if let Some(line) = &status.line {
        say!(out, "Line:      {}", line.label);
        if !line.carrier.is_empty() {
            say!(out, " via {}", line.carrier);
        }
        sayln!(out);
    }
    if !status.since.is_empty() {
        sayln!(out, "Since:     {}", status.since);
    }
    if !status.last_error.is_empty() {
        sayln!(out, "Last error: {}", status.last_error);
    }
    if let Some(proxy) = &status.proxy {
        sayln!(
            out,
            "Proxy:     socks5://{}  http://{}",
            proxy.socks,
            proxy.http
        );
        say!(out, "  export ALL_PROXY=socks5h://{}\n  export http_proxy=http://{} https_proxy=http://{}\n", proxy.socks, proxy.http, proxy.http);
    }
    for list in &status.subscriptions {
        say!(out, "List:      {}", list.url);
        if !list.title.is_empty() {
            say!(out, "  ({})", list.title);
        }
        if !list.next_refresh.is_empty() {
            say!(out, "  next refresh {}", list.next_refresh);
        }
        if let Some(used) = list.user_info.as_ref().filter(|used| used.total > 0) {
            // bytes to GB; the sum wraps as Go's does
            let spent = used.upload.wrapping_add(used.download) as f64 / 1e9;
            say!(
                out,
                "  used {spent:.1} of {:.1} GB",
                used.total as f64 / 1e9
            );
        }
        if !list.error.is_empty() {
            say!(out, "  ERROR: {}", list.error);
        }
        sayln!(out);
    }
}

/// Prints the lines; `note` is what the daemon says of the lists, such as
/// why one has none.
fn print_list(out: &mut dyn Write, entries: &[EntryView], note: &str) {
    let mut table = String::from("#\tCOUNTRY\tKIND\tCARRIER\tLABEL\tNOTE\n");
    for entry in entries {
        let _ = writeln!(
            table,
            " {} \t{}\t{}\t{}\t{}\t{}",
            entry.index, entry.country, entry.kind, entry.carrier, entry.label, entry.problem
        );
    }
    say!(out, "{}", tabulate(&table, 2));
    if entries.is_empty() && note.is_empty() {
        sayln!(out, "(no lines; ghostlane add <url>)");
    }
    if !note.is_empty() {
        sayln!(out, "{note}");
    }
}

/// Go's `text/tabwriter` as the list uses it: cells end at a tab, a column
/// is as wide as its widest cell plus `padding` spaces, and the last cell of
/// a line is not part of a column. A column is measured over the lines that
/// follow each other and have it, so a line without tabs starts the measuring
/// anew. Widths are counted in characters.
fn tabulate(text: &str, padding: usize) -> String {
    let mut out = String::new();
    let mut lines: Vec<Vec<String>> = Vec::new();
    let mut line: Vec<String> = Vec::new();
    let mut cell = String::new();
    for c in text.chars() {
        match c {
            '\t' | '\u{b}' => line.push(std::mem::take(&mut cell)),
            '\n' | '\u{c}' => {
                line.push(std::mem::take(&mut cell));
                let cells = line.len();
                lines.push(std::mem::take(&mut line));
                // a line of one cell bears on no column: what stands so far
                // is laid out and written
                if c == '\u{c}' || cells == 1 {
                    lines.push(Vec::new());
                    lay_out(&lines, 0, lines.len(), &mut Vec::new(), padding, &mut out);
                    lines.clear();
                }
            }
            _ => cell.push(c),
        }
    }
    if !cell.is_empty() {
        line.push(cell);
    }
    lines.push(line);
    lay_out(&lines, 0, lines.len(), &mut Vec::new(), padding, &mut out);
    out
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
    let column = widths.len();
    let has_column = |at: usize| column + 1 < lines[at].len();
    let (mut start, mut at) = (from, from);
    while at < to {
        if !has_column(at) {
            at += 1;
            continue;
        }
        write_lines(lines, start, at, widths, out);
        start = at;
        let mut width = 0;
        while at < to && has_column(at) {
            width = width.max(lines[at][column].chars().count() + padding);
            at += 1;
        }
        widths.push(width);
        lay_out(lines, start, at, widths, padding, out);
        widths.pop();
        start = at;
    }
    write_lines(lines, start, to, widths, out);
}

/// tabwriter's `writeLines`: each cell, padded to its column's width where
/// it has one, and a line break after every line but the last one there is.
fn write_lines(lines: &[Vec<String>], from: usize, to: usize, widths: &[usize], out: &mut String) {
    for (at, line) in lines.iter().enumerate().take(to).skip(from) {
        for (column, cell) in line.iter().enumerate() {
            out.push_str(cell);
            if let Some(width) = widths.get(column) {
                out.push_str(&" ".repeat(width.saturating_sub(cell.chars().count())));
            }
        }
        if at + 1 != lines.len() {
            out.push('\n');
        }
    }
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
        FlagSet {
            name: name.to_string(),
            flags: Vec::new(),
            rest: Vec::new(),
        }
    }

    fn text(mut self, name: &'static str, default: &str, usage: &'static str) -> FlagSet {
        self.flags.push(Flag {
            name,
            usage,
            boolean: false,
            default: default.to_string(),
            value: default.to_string(),
        });
        self
    }

    fn boolean(mut self, name: &'static str, usage: &'static str) -> FlagSet {
        self.flags.push(Flag {
            name,
            usage,
            boolean: true,
            default: "false".to_string(),
            value: "false".to_string(),
        });
        self
    }

    fn value(&self, name: &str) -> &str {
        self.flags
            .iter()
            .find(|flag| flag.name == name)
            .map_or("", |flag| flag.value.as_str())
    }

    fn is_on(&self, name: &str) -> bool {
        self.value(name) == "true"
    }

    /// Reads the flags at the start of `args`; false when one was wrong
    /// (which is said on `stderr`, with the flags there are).
    fn parse(&mut self, args: &[String], stderr: &mut dyn Write) -> bool {
        let mut at = 0;
        let read = loop {
            match self.parse_one(args, &mut at) {
                Ok(true) => {}
                Ok(false) => break true,
                Err(complaint) => {
                    if let Some(complaint) = complaint {
                        sayln!(stderr, "{complaint}");
                    }
                    say!(stderr, "{}", self.usage());
                    break false;
                }
            }
        };
        self.rest = args[at.min(args.len())..].to_vec();
        read
    }

    /// Go's `parseOne`: true when a flag was read, false at the end of the
    /// flags; an error is the complaint to print, none for a call for help.
    fn parse_one(&mut self, args: &[String], at: &mut usize) -> Result<bool, Option<String>> {
        let Some(arg) = args.get(*at) else {
            return Ok(false);
        };
        let Some(name) = arg.strip_prefix('-').filter(|name| !name.is_empty()) else {
            return Ok(false);
        };
        let name = match name.strip_prefix('-') {
            // "--" ends the flags
            Some("") => {
                *at += 1;
                return Ok(false);
            }
            Some(name) => name,
            None => name,
        };
        if name.starts_with('-') || name.starts_with('=') {
            return Err(Some(format!("bad flag syntax: {arg}")));
        }
        *at += 1;
        let (name, given) = match name.split_once('=') {
            Some((name, value)) => (name, Some(value)),
            None => (name, None),
        };
        let Some(flag) = self.flags.iter_mut().find(|flag| flag.name == name) else {
            return Err(match name {
                "help" | "h" => None,
                _ => Some(format!("flag provided but not defined: -{name}")),
            });
        };
        if flag.boolean {
            let on = match given {
                None => true,
                Some(value) => parse_bool(value).ok_or_else(|| {
                    Some(format!(
                        "invalid boolean value {} for -{name}: parse error",
                        quote(value)
                    ))
                })?,
            };
            flag.value = on.to_string();
            return Ok(true);
        }
        flag.value = match given {
            Some(value) => value.to_string(),
            // the value is the next argument
            None => {
                let next = args
                    .get(*at)
                    .ok_or_else(|| Some(format!("flag needs an argument: -{name}")))?;
                *at += 1;
                next.clone()
            }
        };
        Ok(true)
    }

    /// Go's default usage: the flags by name, each with its use and the
    /// value it has when it is not given.
    fn usage(&self) -> String {
        let mut text = format!("Usage of {}:\n", self.name);
        let mut flags: Vec<&Flag> = self.flags.iter().collect();
        flags.sort_by_key(|flag| flag.name);
        for flag in flags {
            let kind = if flag.boolean { "" } else { " string" };
            // a flag of one letter has its use on its own line
            let gap = if flag.name.len() + kind.len() <= 1 {
                "\t"
            } else {
                "\n    \t"
            };
            let _ = write!(
                text,
                "  -{}{kind}{gap}{}",
                flag.name,
                flag.usage.replace('\n', "\n    \t")
            );
            if !flag.boolean && !flag.default.is_empty() {
                let _ = write!(text, " (default {})", quote(&flag.default));
            }
            text.push('\n');
        }
        text
    }
}

/// Go's `strconv.ParseBool`.
fn parse_bool(text: &str) -> Option<bool> {
    match text {
        "1" | "t" | "T" | "TRUE" | "true" | "True" => Some(true),
        "0" | "f" | "F" | "FALSE" | "false" | "False" => Some(false),
        _ => None,
    }
}

/// `GHOSTLANE_STATE_DIR` when set (the Docker image sets it to /data, the
/// volume), else the package's directory.
pub fn default_state_dir(getenv: &dyn Fn(&str) -> String) -> String {
    let dir = getenv("GHOSTLANE_STATE_DIR");
    if dir.is_empty() {
        "/var/lib/ghostlane".to_string()
    } else {
        dir
    }
}

/// An address the machine alone reaches, as Go's `IsLoopback` has it: an
/// IPv4 one written as IPv6 counts too.
fn is_loopback(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => v6
            .to_ipv4_mapped()
            .map_or(v6.is_loopback(), |v4| v4.is_loopback()),
    }
}

/// Applies `GHOSTLANE_PROXY_LISTEN`, `_PORT`, `_USER` and `_PASS` to the
/// config (containers set the proxy this way) and says whether any was set.
/// A listen that is not loopback needs credentials, or the proxy would be
/// open to whoever reaches the port.
pub fn apply_proxy_env(
    config: &mut Config,
    getenv: &dyn Fn(&str) -> String,
) -> Result<bool, String> {
    let (listen, port) = (
        getenv("GHOSTLANE_PROXY_LISTEN"),
        getenv("GHOSTLANE_PROXY_PORT"),
    );
    let (user, pass) = (
        getenv("GHOSTLANE_PROXY_USER"),
        getenv("GHOSTLANE_PROXY_PASS"),
    );
    if listen.is_empty() && port.is_empty() && user.is_empty() && pass.is_empty() {
        return Ok(false);
    }
    if !listen.is_empty() {
        if listen.parse::<IpAddr>().is_err() {
            return Err(format!(
                "GHOSTLANE_PROXY_LISTEN must be an IP address (127.0.0.1, ::1 or 0.0.0.0), not {}",
                quote(&listen)
            ));
        }
        config.proxy.listen = listen;
    }
    if !port.is_empty() {
        config.proxy.port = match port.parse::<i64>() {
            Ok(number) if (1..=65535).contains(&number) => number,
            _ => {
                return Err(format!(
                    "GHOSTLANE_PROXY_PORT must be a port number, not {}",
                    quote(&port)
                ))
            }
        };
    }
    if !user.is_empty() || !pass.is_empty() {
        config.proxy.user = user;
        config.proxy.pass = pass;
    }
    let open = config
        .proxy
        .listen
        .parse::<IpAddr>()
        .is_ok_and(|address| !is_loopback(address));
    if open && (config.proxy.user.is_empty() || config.proxy.pass.is_empty()) {
        return Err(format!("a proxy on {} is reachable from the network: set GHOSTLANE_PROXY_USER and GHOSTLANE_PROXY_PASS", config.proxy.listen));
    }
    Ok(true)
}

fn run_daemon(args: &[String], stderr: &mut dyn Write) -> u8 {
    let getenv = |name: &str| std::env::var(name).unwrap_or_default();
    let state_default = default_state_dir(&getenv);
    let mut flags = FlagSet::new("run")
        .text("socket", ipc::DEFAULT_SOCKET_PATH, "control socket path")
        .text(
            "state-dir",
            &state_default,
            "state directory (config.yaml lives here; GHOSTLANE_STATE_DIR)",
        )
        .text("subscription", "", "add this list at start (containers)")
        .text("connect", "", "connect this selector at start (containers)")
        .text("mode", "proxy", "tun | proxy, with --connect")
        .boolean(
            "kill-switch",
            "with --mode tun: refuse traffic outside the tunnel while no line is up",
        )
        .text(
            "probe-url",
            "http://cp.cloudflare.com/generate_204",
            "liveness probe through the tunnel",
        );
    if !flags.parse(args, stderr) {
        return 2;
    }
    let (mode, kill_switch) = (flags.value("mode"), flags.is_on("kill-switch"));
    if mode != "tun" && mode != "proxy" {
        sayln!(stderr, "--mode must be tun or proxy, not {}", quote(mode));
        return 2;
    }
    if kill_switch && mode != "tun" {
        sayln!(stderr, "--kill-switch needs --mode tun");
        return 2;
    }

    // containers: the list, the selection and the proxy come from the
    // arguments and the environment, written into the config before the start
    let config_path = Path::new(flags.value("state-dir")).join("config.yaml");
    let mut config = match store::load(&config_path) {
        Ok(config) => config,
        Err(err) => {
            sayln!(stderr, "{err}");
            return 1;
        }
    };
    let mut changed = match apply_proxy_env(&mut config, &getenv) {
        Ok(changed) => changed,
        Err(why) => {
            sayln!(stderr, "{why}");
            return 2;
        }
    };
    let (subscription, selector) = (flags.value("subscription"), flags.value("connect"));
    if !subscription.is_empty() {
        if !config
            .subscriptions
            .iter()
            .any(|list| list.url == subscription)
        {
            config.subscriptions.push(Subscription {
                url: subscription.to_string(),
                added_at: Timestamp::now(),
                interval_hours: 24,
                ..Subscription::default()
            });
        }
        if !selector.is_empty() {
            config.selection = Some(Selection {
                subscription: subscription.to_string(),
                selector: selector.to_string(),
                mode: mode.to_string(),
                kill_switch,
                ..Selection::default()
            });
        }
        changed = true;
    }
    if changed {
        if let Err(err) = store::save(&config_path, &config) {
            sayln!(stderr, "{err}");
            return 1;
        }
    }

    let server = match Server::bind(Path::new(flags.value("socket")), 0o660) {
        Ok(server) => server,
        Err(err) => {
            sayln!(stderr, "control socket: {err}");
            return 1;
        }
    };
    let mut signals = match Signals::new([SIGTERM, SIGINT]) {
        Ok(signals) => signals,
        Err(err) => {
            sayln!(stderr, "signals: {err}");
            return 1;
        }
    };
    let stopper = server.stopper();
    thread::spawn(move || {
        if signals.forever().next().is_some() {
            stopper.stop();
        }
    });
    match server.serve(answer) {
        Ok(()) => 0,
        Err(err) => {
            sayln!(stderr, "control socket: {err}");
            1
        }
    }
}

/// What the daemon of this crate answers: what it is made of, and for the
/// rest that it has nothing to do it with. The verbs are the Go daemon's.
pub fn answer(request: Request) -> Response {
    match request.verb.as_str() {
        "version" => Response { ok: true, version: Some(version_info()), ..Response::default() },
        verb @ ("add" | "list" | "connect" | "disconnect" | "status" | "refresh" | "remove") => {
            ipc::fail("no_engine", &format!("{verb}: this daemon holds the command line, the state files and the control socket; the part that connects a line is not in this build"))
        }
        verb => ipc::fail("bad_verb", &format!("unknown verb {verb}")),
    }
}
