//! The engine child: `ghostlane-engine`, the Core build's program, run as a
//! process of its own, the way the desktop app runs it.
//!
//! One run is one child. `start_core` and `start_room` write a session file
//! (mode 0600, in a directory of this program's; nothing secret is ever an
//! argument), start `ghostlane-engine run --session <file>` with a pipe on
//! its stdin, and read its stdout, one JSON event a line, until `ready`
//! names the listeners. From then on the child is [`Engine`]:
//!
//! - it is alive while the process is, and `ended` has not been printed;
//!   `state()` says `running` or `stopped`, and `last_end()` says why;
//! - `stop()` closes its stdin, which is the engine's stop (it is gone within
//!   two seconds), waits a grace for the process, and kills it after that;
//! - dropping it does the same, so no engine outlives its parent, and the
//!   session file is removed in every case.
//!
//! `probe_line` is `ghostlane-engine probe`: one request for a URL through a
//! session's line, in a process of its own that serves no port (the
//! liveness probe, `--probe-url`). `socks_probe` is the same question asked
//! through a SOCKS5 port that is already serving.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, PoisonError};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::daemon::{Engine as EngineTrait, OlcrtcParams};

/// The contract the program is written against: the session file read and
/// the events printed.
pub const PROTOCOL: u64 = 1;

/// The tag of the core's own inbound, where proxy mode and the tun connect.
pub const CORE_TAG: &str = "in";

/// The tag the engine gives the SOCKS port of a room with no core in front.
pub const ROOM_TAG: &str = "room";

/// Keeps LAN, link-local and CGNAT traffic off a room, in the engine's
/// `route.direct` syntax.
pub const PRIVATE_DIRECT_RULES: &str = "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n100.64.0.0/10\n169.254.0.0/16\n127.0.0.0/8\nfc00::/7\nfe80::/10\n";

/// How much of a child's stderr a failure's sentence may carry.
const STDERR_KEPT: usize = 600;

type Log = Arc<dyn Fn(&str) + Send + Sync>;

// ---------------------------------------------------------------------------
// Events

/// One listener of a running session.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Listener {
    pub tag: Option<String>,
    pub address: String,
}

/// How a run ended, as the last line of the engine says it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Ended {
    /// `stdin_closed`, `signal` or `failed`.
    pub reason: String,
    pub code: Option<String>,
    pub message: Option<String>,
}

impl Ended {
    /// One sentence for a log or an error.
    pub fn sentence(&self) -> String {
        match (&self.code, &self.message) {
            (Some(code), Some(message)) => format!("the engine ended ({code}): {message}"),
            (None, Some(message)) => format!("the engine ended: {message}"),
            _ => format!("the engine ended: {}", self.reason),
        }
    }
}

/// The lines of the engine this program reads. A kind it does not read is
/// `Other`: the contract only grows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    Version {
        engine: String,
        protocol: u64,
        pin: String,
        boringssl: bool,
        rooms: bool,
        target: String,
    },
    Ready {
        inbounds: Vec<Listener>,
    },
    Bound {
        interface: String,
    },
    Warning {
        source: String,
        message: String,
    },
    Log {
        source: String,
        level: String,
        line: String,
    },
    SessionOpened {
        room: String,
    },
    Probe {
        ok: bool,
        millis: Option<u64>,
        error: Option<String>,
    },
    Error {
        code: String,
        message: String,
    },
    Ended(Ended),
    Other(String),
}

fn text(v: &Value, key: &str) -> Result<String, String> {
    v.get(key)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| format!("no text `{key}`"))
}

fn maybe_text(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_owned)
}

/// Reads one line of the engine's stdout.
pub fn parse_event(line: &str) -> Result<Event, String> {
    let v: Value = serde_json::from_str(line).map_err(|e| format!("not JSON: {e}"))?;
    let kind = text(&v, "event")?;
    Ok(match kind.as_str() {
        "version" => Event::Version {
            engine: text(&v, "engine")?,
            protocol: v.get("protocol").and_then(Value::as_u64).unwrap_or(0),
            pin: text(&v, "pin")?,
            boringssl: v.get("boringssl").and_then(Value::as_bool).unwrap_or(false),
            rooms: v.get("rooms").and_then(Value::as_bool).unwrap_or(false),
            target: text(&v, "target")?,
        },
        "ready" => {
            let list = v
                .get("inbounds")
                .and_then(Value::as_array)
                .ok_or("no `inbounds`")?;
            let mut inbounds = Vec::new();
            for item in list {
                inbounds.push(Listener {
                    tag: maybe_text(item, "tag"),
                    address: text(item, "address")?,
                });
            }
            Event::Ready { inbounds }
        }
        "bound" => Event::Bound {
            interface: text(&v, "interface")?,
        },
        "warning" => Event::Warning {
            source: text(&v, "source")?,
            message: text(&v, "message")?,
        },
        "log" => Event::Log {
            source: text(&v, "source")?,
            level: text(&v, "level")?,
            line: text(&v, "line")?,
        },
        "session_opened" => Event::SessionOpened {
            room: text(&v, "room")?,
        },
        "probe" => Event::Probe {
            ok: v.get("ok").and_then(Value::as_bool).unwrap_or(false),
            millis: v.get("millis").and_then(Value::as_u64),
            error: maybe_text(&v, "error"),
        },
        "error" => Event::Error {
            code: text(&v, "code")?,
            message: text(&v, "message")?,
        },
        "ended" => Event::Ended(Ended {
            reason: text(&v, "reason")?,
            code: maybe_text(&v, "code"),
            message: maybe_text(&v, "message"),
        }),
        other => Event::Other(other.to_owned()),
    })
}

// ---------------------------------------------------------------------------
// The program, the session files

/// Where the program is and how it is run.
#[derive(Clone)]
pub struct Options {
    /// The `ghostlane-engine` file.
    pub program: PathBuf,
    /// Where session files are written: a directory of this program's own.
    pub dir: PathBuf,
    /// How long a start may take before `ready`.
    pub ready_timeout: Duration,
    /// How long `stop` waits for the process before it kills it.
    pub stop_grace: Duration,
    /// Where the engine's own lines go (the daemon's log).
    pub log: Log,
}

impl Options {
    pub fn new(program: PathBuf, dir: PathBuf) -> Options {
        Options {
            program,
            dir,
            ready_timeout: Duration::from_secs(30),
            stop_grace: Duration::from_secs(3),
            log: Arc::new(|_| {}),
        }
    }
}

/// The program: `GHOSTLANE_ENGINE` when it is set, else `ghostlane-engine`
/// beside this program's own file.
pub fn locate(named: Option<&std::ffi::OsStr>, exe: &Path) -> Result<PathBuf, String> {
    let path = match named.filter(|n| !n.is_empty()) {
        Some(named) => PathBuf::from(named),
        None => exe
            .parent()
            .map(|dir| dir.join("ghostlane-engine"))
            .ok_or("this program has no directory to look for ghostlane-engine in")?,
    };
    if path.is_file() {
        Ok(path)
    } else {
        Err(format!("no ghostlane-engine at {}", path.display()))
    }
}

/// [`locate`] for the running program.
pub fn locate_here() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(|e| format!("this program's own path: {e}"))?;
    locate(std::env::var_os("GHOSTLANE_ENGINE").as_deref(), &exe)
}

/// A session file: mode 0600, removed when this is dropped.
pub struct SessionFile {
    path: PathBuf,
}

impl SessionFile {
    pub fn create(dir: &Path, session: &Value) -> Result<SessionFile, String> {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
        }
        let path = dir.join(format!(
            "engine-{}-{}.json",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options
            .open(&path)
            .map_err(|e| format!("the session file {}: {e}", path.display()))?;
        let made = SessionFile { path };
        file.write_all(session.to_string().as_bytes())
            .map_err(|e| format!("the session file: {e}"))?;
        Ok(made)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for SessionFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// A session with a core half: `core_json` is the config the builder writes.
pub fn core_session(
    core_json: &str,
    geodata_dir: Option<&Path>,
    bind_interface: Option<&str>,
) -> Result<Value, String> {
    let core: Value =
        serde_json::from_str(core_json).map_err(|e| format!("the core config is not JSON: {e}"))?;
    let mut session = json!({"version": PROTOCOL, "core": core});
    if let Some(dir) = geodata_dir {
        session["geodata_dir"] = json!(dir.to_string_lossy());
    }
    if let Some(interface) = bind_interface {
        session["bind"] = json!({"interface": interface});
    }
    Ok(session)
}

/// A session with a room half and no core: the room's SOCKS port is the
/// session's, on `params.socks_port`, behind `login`.
pub fn room_session(
    params: &OlcrtcParams,
    login: &(String, String),
    bind_interface: Option<&str>,
) -> Value {
    let line = &params.line;
    let mut room = json!({
        "provider": line.provider,
        "rooms": [line.room],
        "key": line.key,
        "socks": {"port": params.socks_port, "user": login.0, "password": login.1},
        "udp": true,
        "direct_rules": PRIVATE_DIRECT_RULES,
    });
    if !line.transport.is_empty() {
        room["transport"] = json!(line.transport);
    }
    if !params.device_id.is_empty() {
        room["device_id"] = json!(params.device_id);
    }
    let mut session = json!({"version": PROTOCOL, "room": room});
    if let Some(interface) = bind_interface {
        session["bind"] = json!({"interface": interface});
    }
    session
}

// ---------------------------------------------------------------------------
// The child

#[derive(Default)]
struct Seen {
    ready: Option<Vec<Listener>>,
    ended: Option<Ended>,
    closed: bool,
}

struct Shared {
    seen: Mutex<Seen>,
    changed: Condvar,
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, Seen> {
        self.seen.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn apply(&self, event: Event, log: &Log) {
        match event {
            Event::Ready { inbounds } => self.lock().ready = Some(inbounds),
            Event::Ended(ended) => self.lock().ended = Some(ended),
            Event::Warning { source, message } => log(&format!("engine {source}: {message}")),
            Event::Log {
                source,
                level,
                line,
            } if level != "debug" && level != "access" => {
                log(&format!("engine {source} {level}: {line}"));
            }
            Event::SessionOpened { room } => log(&format!("engine: room session opened ({room})")),
            Event::Bound { interface } => log(&format!("engine: sockets leave by {interface}")),
            _ => {}
        }
        self.changed.notify_all();
    }

    fn close(&self) {
        self.lock().closed = true;
        self.changed.notify_all();
    }

    fn wait_ready(&self, timeout: Duration) -> Result<Vec<Listener>, String> {
        let deadline = Instant::now() + timeout;
        let mut seen = self.lock();
        loop {
            if let Some(listeners) = &seen.ready {
                return Ok(listeners.clone());
            }
            if let Some(ended) = &seen.ended {
                return Err(ended.sentence());
            }
            if seen.closed {
                return Err("the engine closed its output before it was ready".to_string());
            }
            let now = Instant::now();
            if now >= deadline {
                return Err(format!(
                    "the engine was not ready within {} s",
                    timeout.as_secs()
                ));
            }
            seen = self
                .changed
                .wait_timeout(seen, deadline - now)
                .unwrap_or_else(PoisonError::into_inner)
                .0;
        }
    }
}

fn read_events(stdout: impl Read, shared: &Shared, log: &Log) {
    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        match parse_event(&line) {
            Ok(event) => shared.apply(event, log),
            // never the line itself: what it says is not this program's to repeat
            Err(why) => log(&format!("engine: a line that is no event ({why})")),
        }
    }
    shared.close();
}

fn keep_stderr(stderr: impl Read + Send + 'static) -> Arc<Mutex<String>> {
    let kept = Arc::new(Mutex::new(String::new()));
    let into = Arc::clone(&kept);
    thread::spawn(move || {
        let mut text = String::new();
        let _ = BufReader::new(stderr)
            .take(64 * 1024)
            .read_to_string(&mut text);
        let tail: String = text
            .chars()
            .rev()
            .take(STDERR_KEPT)
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect();
        *into.lock().unwrap_or_else(PoisonError::into_inner) = tail;
    });
    kept
}

/// A running `ghostlane-engine run`.
pub struct EngineChild {
    child: Mutex<Child>,
    stdin: Mutex<Option<ChildStdin>>,
    shared: Arc<Shared>,
    stderr: Arc<Mutex<String>>,
    stopped: Mutex<bool>,
    grace: Duration,
    socks: String,
    login: (String, String),
    listeners: Vec<Listener>,
    session: Mutex<Option<SessionFile>>,
}

fn spawn(options: &Options, command: &str, session: &SessionFile) -> Result<Child, String> {
    Command::new(&options.program)
        .arg(command)
        .arg("--session")
        .arg(session.path())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("cannot start {}: {e}", options.program.display()))
}

/// Starts `ghostlane-engine run` on `session` and waits for `ready`. The
/// SOCKS address and the login the parent uses are the listener tagged
/// `tag` (or the first) and `login`.
pub fn start(
    options: &Options,
    session: &Value,
    tag: &str,
    login: (String, String),
) -> Result<EngineChild, String> {
    let file = SessionFile::create(&options.dir, session)?;
    let mut child = spawn(options, "run", &file)?;
    let stdin = child.stdin.take();
    let stdout = child.stdout.take().ok_or("no stdout")?;
    let stderr = keep_stderr(child.stderr.take().ok_or("no stderr")?);
    let shared = Arc::new(Shared {
        seen: Mutex::new(Seen::default()),
        changed: Condvar::new(),
    });
    {
        let shared = Arc::clone(&shared);
        let log = Arc::clone(&options.log);
        thread::spawn(move || read_events(stdout, &shared, &log));
    }
    let mut engine = EngineChild {
        child: Mutex::new(child),
        stdin: Mutex::new(stdin),
        shared: Arc::clone(&shared),
        stderr,
        stopped: Mutex::new(false),
        grace: options.stop_grace,
        socks: String::new(),
        login,
        listeners: Vec::new(),
        session: Mutex::new(Some(file)),
    };
    match shared.wait_ready(options.ready_timeout) {
        Ok(listeners) => {
            let chosen = listeners
                .iter()
                .find(|l| l.tag.as_deref() == Some(tag))
                .or_else(|| listeners.first());
            match chosen {
                Some(l) => engine.socks = l.address.clone(),
                None => {
                    engine.shut();
                    return Err("the engine is ready and listens on nothing".to_string());
                }
            }
            engine.listeners = listeners;
            Ok(engine)
        }
        Err(why) => {
            engine.shut();
            let said = engine.stderr_tail();
            Err(if said.is_empty() {
                why
            } else {
                format!("{why} ({said})")
            })
        }
    }
}

/// A core line: the session's inbound tagged `in` is the engine's SOCKS.
pub fn start_core(
    options: &Options,
    core_json: &str,
    login: Option<(&str, &str)>,
    geodata_dir: Option<&Path>,
    bind_interface: Option<&str>,
) -> Result<EngineChild, String> {
    let session = core_session(core_json, geodata_dir, bind_interface)?;
    let (user, pass) = login.unwrap_or(("", ""));
    start(
        options,
        &session,
        CORE_TAG,
        (user.to_owned(), pass.to_owned()),
    )
}

/// A room: its SOCKS port is the engine's.
pub fn start_room(
    options: &Options,
    params: &OlcrtcParams,
    login: (String, String),
    bind_interface: Option<&str>,
) -> Result<EngineChild, String> {
    let session = room_session(params, &login, bind_interface);
    start(options, &session, ROOM_TAG, login)
}

impl EngineChild {
    /// Every listener `ready` named.
    pub fn listeners(&self) -> &[Listener] {
        &self.listeners
    }

    /// How the run ended, once `ended` has been printed.
    pub fn last_end(&self) -> Option<Ended> {
        self.shared.lock().ended.clone()
    }

    pub fn stderr_tail(&self) -> String {
        self.stderr
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .trim()
            .to_owned()
    }

    fn exited(&self) -> bool {
        let mut child = self.child.lock().unwrap_or_else(PoisonError::into_inner);
        !matches!(child.try_wait(), Ok(None))
    }

    /// Closes stdin, waits `grace` for the process, kills it after that.
    fn shut(&self) {
        self.stdin
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
        let deadline = Instant::now() + self.grace;
        loop {
            if self.exited() {
                return;
            }
            if Instant::now() >= deadline {
                break;
            }
            thread::sleep(Duration::from_millis(20));
        }
        let mut child = self.child.lock().unwrap_or_else(PoisonError::into_inner);
        let _ = child.kill();
        let _ = child.wait();
    }
}

impl EngineTrait for EngineChild {
    fn socks_addr(&self) -> String {
        self.socks.clone()
    }

    fn credentials(&self) -> (String, String) {
        self.login.clone()
    }

    fn state(&self) -> String {
        if *self.stopped.lock().unwrap_or_else(PoisonError::into_inner) {
            return "stopped".to_string();
        }
        if self.shared.lock().ended.is_some() || self.exited() {
            return "stopped".to_string();
        }
        "running".to_string()
    }

    fn stop(&self) -> Result<(), String> {
        {
            let mut stopped = self.stopped.lock().unwrap_or_else(PoisonError::into_inner);
            if *stopped {
                return Ok(());
            }
            *stopped = true;
        }
        self.shut();
        self.session
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
        Ok(())
    }
}

impl Drop for EngineChild {
    fn drop(&mut self) {
        let _ = EngineTrait::stop(self);
    }
}

// ---------------------------------------------------------------------------
// One-shot questions

/// What `ghostlane-engine version` says.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Version {
    pub engine: String,
    pub protocol: u64,
    pub pin: String,
    pub boringssl: bool,
    pub rooms: bool,
    pub target: String,
}

/// Runs a command that answers once and returns its events. The process is
/// killed when it is not done `timeout` after it started.
fn ask(program: &Path, args: &[String], timeout: Duration) -> Result<Vec<Event>, String> {
    let mut child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("cannot start {}: {e}", program.display()))?;
    let mut stdout = child.stdout.take().ok_or("no stdout")?;
    let stderr = keep_stderr(child.stderr.take().ok_or("no stderr")?);
    let reader = thread::spawn(move || {
        let mut out = String::new();
        let _ = stdout.read_to_string(&mut out);
        out
    });
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("no answer within {} s", timeout.as_secs()));
            }
            Ok(None) => thread::sleep(Duration::from_millis(10)),
            Err(e) => return Err(format!("waiting for the engine: {e}")),
        }
    }
    let out = reader.join().map_err(|_| "reading the engine panicked")?;
    let events: Vec<Event> = out
        .lines()
        .filter(|line| !line.trim().is_empty())
        .filter_map(|line| parse_event(line).ok())
        .collect();
    if events.is_empty() {
        let said = stderr
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .trim()
            .to_owned();
        return Err(if said.is_empty() {
            "the engine said nothing".to_string()
        } else {
            format!("the engine said nothing ({said})")
        });
    }
    Ok(events)
}

/// `ghostlane-engine version`.
pub fn version(program: &Path) -> Result<Version, String> {
    let events = ask(program, &["version".to_string()], Duration::from_secs(10))?;
    for event in events {
        match event {
            Event::Version {
                engine,
                protocol,
                pin,
                boringssl,
                rooms,
                target,
            } => {
                return Ok(Version {
                    engine,
                    protocol,
                    pin,
                    boringssl,
                    rooms,
                    target,
                })
            }
            Event::Error { code, message } => return Err(format!("{code}: {message}")),
            _ => {}
        }
    }
    Err("the engine printed no `version`".to_string())
}

/// `version`, and refuses a program that reads another protocol than this
/// one writes.
pub fn check_version(program: &Path) -> Result<Version, String> {
    let v = version(program)?;
    if v.protocol != PROTOCOL {
        return Err(format!(
            "ghostlane-engine reads protocol {}; this program writes protocol {PROTOCOL}",
            v.protocol
        ));
    }
    Ok(v)
}

/// `ghostlane-engine probe`: one request for `url` through the line
/// `session` holds, by the outbound `outbound` (the core's default when
/// `None`). The milliseconds it took, or why it did not answer. The session
/// is the line's own: the probe starts its own core and serves no port.
pub fn probe_line(
    options: &Options,
    session: &Value,
    url: &str,
    timeout: Duration,
    outbound: Option<&str>,
) -> Result<u64, String> {
    let file = SessionFile::create(&options.dir, session)?;
    let mut args = vec![
        "probe".to_string(),
        "--session".to_string(),
        file.path().to_string_lossy().into_owned(),
        "--url".to_string(),
        url.to_string(),
        "--timeout-ms".to_string(),
        timeout.as_millis().to_string(),
    ];
    if let Some(tag) = outbound {
        args.push("--outbound".to_string());
        args.push(tag.to_string());
    }
    // the engine's own bound is the timeout; the rest is its start and stop
    let events = ask(&options.program, &args, timeout + Duration::from_secs(10))?;
    for event in events.into_iter().rev() {
        match event {
            Event::Probe {
                ok: true,
                millis: Some(millis),
                ..
            } => return Ok(millis),
            Event::Probe { error, .. } => {
                return Err(error.unwrap_or_else(|| "no answer".to_string()))
            }
            Event::Error { code, message } => return Err(format!("{code}: {message}")),
            _ => {}
        }
    }
    Err("the engine printed no `probe`".to_string())
}

// ---------------------------------------------------------------------------
// What the daemon asks of its engine

/// A free loopback port and a login for the engine to serve behind, as the
/// daemon's `probe_inbound` returns them.
pub fn probe_inbound() -> Result<(String, String, String), String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| format!("no free port: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("no free port: {e}"))?
        .port();
    drop(listener);
    let (user, pass) = random_login()?;
    Ok((format!("127.0.0.1:{port}"), user, pass))
}

/// A username and a password for one start, from the system's randomness.
pub fn random_login() -> Result<(String, String), String> {
    let mut bytes = [0u8; 24];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .map_err(|e| format!("no randomness: {e}"))?;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    Ok((hex[..16].to_owned(), hex[16..].to_owned()))
}

/// `http://host[:port]/path`, the only URLs a liveness probe has.
fn split_url(url: &str) -> Result<(String, u16, String), String> {
    let rest = url
        .strip_prefix("http://")
        .ok_or_else(|| "a probe URL is http://".to_string())?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => (
            host,
            port.parse::<u16>()
                .map_err(|_| "the probe URL's port is no port".to_string())?,
        ),
        None => (authority, 80),
    };
    if host.is_empty() || host.len() > 253 {
        return Err("the probe URL names no host".to_string());
    }
    Ok((host.to_owned(), port, path.to_owned()))
}

fn socks_connect(
    stream: &mut TcpStream,
    host: &str,
    port: u16,
    login: Option<(&str, &str)>,
) -> Result<(), String> {
    let io = |e: std::io::Error| e.to_string();
    let offer: &[u8] = if login.is_some() {
        &[5, 1, 2]
    } else {
        &[5, 1, 0]
    };
    stream.write_all(offer).map_err(io)?;
    let mut choice = [0u8; 2];
    stream.read_exact(&mut choice).map_err(io)?;
    match (choice, login) {
        ([5, 0], None) => {}
        ([5, 2], Some((user, pass))) => {
            if user.len() > 255 || pass.len() > 255 {
                return Err("the login is too long for SOCKS5".to_string());
            }
            let mut ask = vec![1, user.len() as u8];
            ask.extend_from_slice(user.as_bytes());
            ask.push(pass.len() as u8);
            ask.extend_from_slice(pass.as_bytes());
            stream.write_all(&ask).map_err(io)?;
            let mut answer = [0u8; 2];
            stream.read_exact(&mut answer).map_err(io)?;
            if answer[1] != 0 {
                return Err("the SOCKS port refused the login".to_string());
            }
        }
        _ => return Err("the SOCKS port chose a method that was not offered".to_string()),
    }
    if host.len() > 255 {
        return Err("the host is too long for SOCKS5".to_string());
    }
    let mut request = vec![5, 1, 0, 3, host.len() as u8];
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request).map_err(io)?;
    let mut head = [0u8; 4];
    stream.read_exact(&mut head).map_err(io)?;
    if head[1] != 0 {
        return Err(format!("the SOCKS port answered {}", head[1]));
    }
    let rest = match head[3] {
        1 => 4 + 2,
        4 => 16 + 2,
        3 => {
            let mut n = [0u8; 1];
            stream.read_exact(&mut n).map_err(io)?;
            n[0] as usize + 2
        }
        _ => return Err("the SOCKS port answered an address of no kind".to_string()),
    };
    let mut skip = vec![0u8; rest];
    stream.read_exact(&mut skip).map_err(io)?;
    Ok(())
}

/// A request for `url` through the SOCKS5 port at `address`, as the daemon's
/// probe asks it: 2xx is alive. An empty `user` is no login.
pub fn socks_probe(address: &str, user: &str, pass: &str, url: &str) -> Result<(), String> {
    let (host, port, path) = split_url(url)?;
    let target = address
        .to_socket_addrs()
        .map_err(|e| format!("the SOCKS address: {e}"))?
        .next()
        .ok_or("the SOCKS address names nothing")?;
    let mut stream = TcpStream::connect_timeout(&target, Duration::from_secs(5))
        .map_err(|e| format!("the SOCKS port: {e}"))?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
    let login = (!user.is_empty()).then_some((user, pass));
    socks_connect(&mut stream, &host, port, login)?;
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n");
    stream
        .write_all(request.as_bytes())
        .map_err(|e| e.to_string())?;
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while head.len() < 8192 && !head.ends_with(b"\r\n") {
        match stream.read(&mut byte) {
            Ok(0) => break,
            Ok(_) => head.push(byte[0]),
            Err(e) => return Err(format!("the answer: {e}")),
        }
    }
    let line = String::from_utf8_lossy(&head);
    let status = line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| "the answer is no HTTP status".to_string())?;
    if (200..300).contains(&status) {
        Ok(())
    } else {
        Err(format!("probe: HTTP {status}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_are_read_and_unknown_kinds_pass() {
        let ready = parse_event(
            r#"{"event":"ready","inbounds":[{"tag":"in","address":"127.0.0.1:41080"},{"tag":null,"address":"[::1]:2"}]}"#,
        )
        .unwrap();
        assert_eq!(
            ready,
            Event::Ready {
                inbounds: vec![
                    Listener {
                        tag: Some("in".into()),
                        address: "127.0.0.1:41080".into()
                    },
                    Listener {
                        tag: None,
                        address: "[::1]:2".into()
                    },
                ]
            }
        );
        assert_eq!(
            parse_event(r#"{"event":"something_new","x":1}"#).unwrap(),
            Event::Other("something_new".into())
        );
        assert!(parse_event("not json").is_err());
        assert!(parse_event(r#"{"no":"kind"}"#).is_err());
        let ended =
            parse_event(r#"{"event":"ended","reason":"failed","code":"config","message":"bad"}"#)
                .unwrap();
        match ended {
            Event::Ended(e) => assert_eq!(e.sentence(), "the engine ended (config): bad"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn the_url_of_a_probe_is_split() {
        assert_eq!(
            split_url("http://cp.cloudflare.com/generate_204").unwrap(),
            (
                "cp.cloudflare.com".to_string(),
                80,
                "/generate_204".to_string()
            )
        );
        assert_eq!(
            split_url("http://127.0.0.1:8080").unwrap(),
            ("127.0.0.1".to_string(), 8080, "/".to_string())
        );
        assert!(split_url("https://example.com/").is_err());
        assert!(split_url("http:///x").is_err());
    }

    #[test]
    fn the_room_session_names_its_half_and_keeps_the_login_out_of_the_core() {
        use crate::daemon::links::OlcrtcLine;
        let params = OlcrtcParams {
            line: OlcrtcLine {
                provider: "none".into(),
                transport: String::new(),
                room: "tcp://127.0.0.1:1/r".into(),
                key: "00".repeat(32),
                label: "t".into(),
            },
            socks_port: 30_000,
            device_id_path: PathBuf::from("/nowhere"),
            device_id: "dev".into(),
        };
        let session = room_session(&params, &("u".into(), "p".into()), None);
        assert_eq!(session["version"], 1);
        assert!(session.get("core").is_none());
        assert_eq!(session["room"]["rooms"][0], "tcp://127.0.0.1:1/r");
        assert_eq!(session["room"]["socks"]["port"], 30_000);
        assert!(session["room"].get("transport").is_none());
        assert_eq!(session["room"]["device_id"], "dev");
    }
}
