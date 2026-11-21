//! The control socket: what `ghostlane run` and the other subcommands say to
//! each other over a unix socket.
//!
//! One request a connection, one JSON value each way, the answer ended by a
//! newline. The types, their field order and what is left out when empty are
//! those of the Go program (`cli/internal/ipc/ipc.go`), so either program's
//! client can ask either program's daemon.

use std::fmt;
use std::fs::{self, DirBuilder, Permissions};
use std::io::{self, BufReader, Read, Write};
use std::os::unix::fs::{DirBuilderExt as _, PermissionsExt as _};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::{Duration, Instant};

use gocompat::text::errno_text;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::store::UserInfo;

pub const DEFAULT_SOCKET_PATH: &str = "/run/ghostlane/ghostlane.sock";

/// What a subcommand says when nobody listens on the socket.
pub const DAEMON_DOWN: &str = "the ghostlane daemon is not running (systemctl start ghostlane, or run `ghostlane run` where there is no systemd)";

/// How long a dial may take.
const DIAL_LIMIT: Duration = Duration::from_secs(3);
/// How long one request and its answer may take, on either side.
const EXCHANGE_LIMIT: Duration = Duration::from_secs(120);

fn is_false(flag: &bool) -> bool {
    todo!()
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Request {
    pub verb: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub source: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub selector: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub mode: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub subscription: String,
    #[serde(skip_serializing_if = "is_false")]
    pub kill_switch: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct EntryView {
    pub index: i64,
    pub id: String,
    pub label: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub country: String,
    pub kind: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub carrier: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub transport: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub problem: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ProxyView {
    pub socks: String,
    pub http: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SubscriptionView {
    /// Masked: the URL is a credential.
    pub url: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub title: String,
    pub interval_hours: i64,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub fetched_at: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub next_refresh: String,
    #[serde(rename = "userinfo", skip_serializing_if = "Option::is_none")]
    pub user_info: Option<UserInfo>,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub error: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Status {
    /// idle | connecting | up | failed
    pub state: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub mode: String,
    #[serde(skip_serializing_if = "is_false")]
    pub kill_switch: bool,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub selector: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub since: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub last_error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<EntryView>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub proxy: Option<ProxyView>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub subscriptions: Vec<SubscriptionView>,
}

/// What a build is made of. The Go program names two more cores; the keys
/// stay for the clients that read them.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct VersionInfo {
    pub version: String,
    pub engine: String,
    pub singbox: String,
    pub xray: String,
    /// This build decrypts crypt1 lists and links.
    pub crypt1: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Response {
    pub ok: bool,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub error: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<Status>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub entries: Vec<EntryView>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub subscriptions: Vec<SubscriptionView>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<VersionInfo>,
}

/// A refusal: a code for programs and a sentence for people.
pub fn fail(code: &str, message: &str) -> Response {
    todo!()
}

/// Why a request got no answer.
#[derive(Debug)]
pub enum CallError {
    /// Nobody listens on the socket.
    DaemonDown(String),
    /// The daemon was reached and the exchange failed.
    Exchange(String),
}

impl fmt::Display for CallError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        todo!()
    }
}

impl std::error::Error for CallError {}

/// Asks the daemon on the socket at `path` and waits for its answer.
pub fn call(path: &Path, request: &Request) -> Result<Response, CallError> {
    todo!()
}

/// Connects within the dial limit. A unix socket answers at once or not at
/// all, but for a daemon whose queue is full: the connect runs aside, and is
/// left behind when it takes too long.
fn dial(path: &Path) -> Result<UnixStream, String> {
    todo!()
}

/// A connection whose reads and writes all end by one moment, as Go's
/// `SetDeadline` has it: the limit is for the exchange, not for each step.
struct Deadline<'a> {
    stream: &'a UnixStream,
    until: Instant,
}

impl<'a> Deadline<'a> {
    fn new(stream: &'a UnixStream) -> Deadline<'a> {
        todo!()
    }

    fn left(&self) -> io::Result<Duration> {
        todo!()
    }
}

fn timed_out() -> io::Error {
    todo!()
}

/// A read or a write that ran into the socket's time limit says so in one
/// way, whatever the system calls it.
fn as_timeout(err: io::Error) -> io::Error {
    todo!()
}

impl Read for Deadline<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        todo!()
    }
}

impl Write for Deadline<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        todo!()
    }

    fn flush(&mut self) -> io::Result<()> {
        todo!()
    }
}

/// Notes whether anything but white space came, which is the difference
/// between a peer that said nothing and one that stopped half way.
struct Seen<R> {
    inner: R,
    text: bool,
}

impl<R: Read> Read for Seen<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        todo!()
    }
}

/// Reads one JSON value and no further: the peer keeps the connection open
/// for the answer, so the end of the value is the end of the reading. The
/// error is a sentence for the other side, Go's where Go has one for it.
fn read_value<T: DeserializeOwned>(reader: impl Read) -> Result<T, String> {
    todo!()
}

/// Why the control socket could not be opened, as Go words it
/// ("listen unix /run/ghostlane/ghostlane.sock: bind: permission denied").
#[derive(Debug)]
pub struct BindError(String);

impl fmt::Display for BindError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        todo!()
    }
}

impl std::error::Error for BindError {}

/// The daemon's end of the control socket.
pub struct Server {
    listener: UnixListener,
    path: PathBuf,
    stopping: Arc<AtomicBool>,
}

/// Ends a `Server::serve` from another thread.
#[derive(Clone)]
pub struct Stopper {
    path: PathBuf,
    stopping: Arc<AtomicBool>,
}

impl Stopper {
    pub fn stop(&self) {
        todo!()
    }
}

impl Server {
    /// Listens on `path` with the file mode `mode`. The directory is made
    /// when it is missing, and a socket file a dead daemon left is replaced.
    pub fn bind(path: &Path, mode: u32) -> Result<Server, BindError> {
        todo!()
    }

    pub fn stopper(&self) -> Stopper {
        todo!()
    }

    /// Answers one request a connection, each on a thread of its own, until
    /// it is stopped; the socket file is removed on the way out.
    pub fn serve<H>(self, handler: H) -> io::Result<()>
    where
        H: Fn(Request) -> Response + Send + Sync + 'static,
    {
        todo!()
    }
}

fn answer(stream: &UnixStream, handler: &dyn Fn(Request) -> Response) {
    todo!()
}
