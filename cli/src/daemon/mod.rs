//! `ghostlane run`'s state: the lists, the selection, the live line and the
//! policy rules, held to the cases of the Go daemon test in `cases.rs`.
//!
//! The cases drive this module on a fake engine and a clock that is paused
//! between steps. Every case has a name from `cli/internal/daemon/*_test.go`;
//! `cases::CASE_NAMES` is the list, and a missing case fails a test rather
//! than quietly reducing the suite.

pub mod clock;
pub mod device;
#[cfg(test)]
pub mod engine;
pub mod links;
pub mod program;
pub mod routes;
pub mod scrub;
mod state;

#[cfg(test)]
pub mod cases;

use std::collections::BTreeMap;
use std::path::PathBuf;

use gocompat::time::Timestamp;

use crate::ipc::{Request, Response, Status, VersionInfo};
use crate::store::{Config, Headers, Selection};

use clock::Clock;
use links::{Device, Entry, FetchError, OlcrtcLine, VlessLine};

/// A connected line's engine (none for a native line) and its front.
pub trait Engine {
    fn socks_addr(&self) -> String;
    fn credentials(&self) -> (String, String);
    fn state(&self) -> String;
    fn stop(&self) -> Result<(), String>;
}

pub trait Front {
    fn close(&self) -> Result<(), String>;
}

/// The rules of the tunnel: the own-address rules, the kill switch and the
/// watcher that reapplies them when addresses change.
pub trait Routes {
    fn global_addresses(&self) -> Result<Vec<String>, String>;
    fn sync(&self, addresses: &[String]) -> Result<(), String>;
    fn clear(&self) -> Result<(), String>;
    fn cleanup_stale(&self) -> Result<(), String>;
    fn install_kill_switch(&self, uid: i32) -> Result<(), String>;
    fn remove_kill_switch(&self) -> Result<(), String>;
    fn watch(&self, id: usize) -> Result<(), String>;
    fn unwatch(&self, id: usize) -> Result<(), String>;
}

pub struct NoopRoutes;

impl Routes for NoopRoutes {
    fn global_addresses(&self) -> Result<Vec<String>, String> {
        Ok(vec!["203.0.113.5".to_string()])
    }
    fn sync(&self, _addresses: &[String]) -> Result<(), String> {
        Ok(())
    }
    fn clear(&self) -> Result<(), String> {
        Ok(())
    }
    fn cleanup_stale(&self) -> Result<(), String> {
        Ok(())
    }
    fn install_kill_switch(&self, _uid: i32) -> Result<(), String> {
        Ok(())
    }
    fn remove_kill_switch(&self) -> Result<(), String> {
        Ok(())
    }
    fn watch(&self, _id: usize) -> Result<(), String> {
        Ok(())
    }
    fn unwatch(&self, _id: usize) -> Result<(), String> {
        Ok(())
    }
}

/// What the daemon starts, and what it is told about this machine.
pub struct OlcrtcParams {
    pub line: OlcrtcLine,
    pub socks_port: u16,
    pub device_id_path: PathBuf,
    pub device_id: String,
}

pub struct XrayParams {
    pub line: VlessLine,
    /// The link as the list wrote it: what the core is started from.
    pub raw: String,
}

pub struct FrontParams {
    pub mode: String,
    /// vless, hy2 or socks, what the fake engine records.
    pub upstream: String,
    pub proxy_listen: String,
    pub proxy_port: u16,
    pub probe_listen: String,
    pub probe_user: String,
    pub probe_pass: String,
    /// The link as the list wrote it, for a line the front carries itself
    /// (`upstream` is `vless` or `hy2`).
    pub line: String,
    /// The proxy's own login, from the config; none when both are empty.
    pub proxy_user: String,
    pub proxy_pass: String,
    /// Only the probe inbound is wanted: the check of a native line before
    /// the tun's rules take the default route.
    pub preflight: bool,
    /// Where the engine in front of this front listens, and its login
    /// (`upstream` is `socks`).
    pub upstream_addr: String,
    pub upstream_user: String,
    pub upstream_pass: String,
}

/// Everything the daemon is given instead of doing itself. The cases replace
/// every one of these; the program will give them their real bodies.
/// The injected parts of the daemon, named so a type is not repeated in
/// every field and in every case that builds one.
pub type Fetch = Box<dyn Fn(&str, Device) -> Result<(Vec<u8>, Headers), FetchError>>;
pub type Decrypt = Box<dyn Fn(&str) -> Option<Vec<u8>>>;
pub type StartEngine = Box<dyn Fn(&OlcrtcParams) -> Result<Box<dyn Engine>, String>>;
pub type StartXray = Box<dyn Fn(&XrayParams) -> Result<Box<dyn Engine>, String>>;
pub type StartFront = Box<dyn Fn(&FrontParams) -> Result<Box<dyn Front>, String>>;
pub type Probe = Box<dyn Fn(&str, &str, &str) -> Result<(), String>>;
pub type ProbeInbound = Box<dyn Fn() -> Result<(String, String, String), String>>;

pub struct Deps {
    pub config_path: PathBuf,
    pub state_dir: PathBuf,
    pub socket_path: Option<PathBuf>,
    pub fetch: Fetch,
    pub device: Device,
    pub decrypt: Option<Decrypt>,
    pub start_engine: StartEngine,
    pub start_xray: StartXray,
    pub start_front: StartFront,
    pub routes: Box<dyn Routes>,
    pub probe: Probe,
    pub probe_inbound: ProbeInbound,
    pub clock: Clock,
    /// The cases move the clock themselves; the program's clock moves on its
    /// own. Refresh moves it by the longest fetch of a parallel round.
    pub advance: Box<dyn Fn(i64)>,
    pub logf: Box<dyn Fn(&str)>,
    pub uid: i32,
    /// A case can move the clock by the longest fetch instead of by each one:
    /// four fetches in a refresh overlap, they do not take turns.
    pub fetch_delay_ms: i64,
    /// How long stopping an engine takes, in the same paused time.
    pub stop_delay_ms: i64,
    pub confirm_timeout_ms: i64,
    pub probe_interval_ms: i64,
    pub probe_failures: usize,
    pub retry_min_ms: i64,
    pub retry_max_ms: i64,
    pub version: VersionInfo,
}

pub struct Live {
    pub entry: Entry,
    pub mode: String,
    pub probe_addr: String,
    pub own_rules: bool,
    pub watcher: Option<usize>,
    pub probe_user: String,
    pub probe_pass: String,
    pub engine: Option<Box<dyn Engine>>,
    pub front: Option<Box<dyn Front>>,
}

pub struct Daemon {
    pub(crate) deps: Deps,
    pub(crate) cfg: Config,
    pub(crate) last_good: BTreeMap<String, String>,
    pub(crate) sub_err: BTreeMap<String, String>,
    pub(crate) state: String,
    pub(crate) last_err: String,
    pub(crate) since: Timestamp,
    pub(crate) sel: Option<Selection>,
    pub(crate) cur: Option<Live>,
    pub(crate) pending: Option<Entry>,
    pub(crate) ks_on: bool,
    pub(crate) ks_watcher: Option<usize>,
    pub(crate) loop_: Option<LoopState>,
    pub(crate) next_at: Option<i64>,
    pub(crate) stop_until: Option<i64>,
    pub(crate) pending_start: Option<Selection>,
    pub(crate) pending_forget: bool,
    pub(crate) dev_id: Option<String>,
    pub(crate) dev_err: String,
    pub(crate) run_active: bool,
    pub(crate) next_watcher: usize,
    pub(crate) configured: bool,
    pub(crate) probe_failures: usize,
}

/// One connection attempt, from resolving the selection to the next round.
pub struct LoopState {
    pub sel: Selection,
    pub phase: Phase,
    pub round: usize,
    pub backoff_ms: i64,
    pub last_tried: String,
    pub candidates: Vec<Entry>,
    pub at: usize,
    pub any_up: bool,
    pub bringing: Option<BringUp>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Phase {
    Resolve,
    TryNext,
    Confirm,
    Up,
    Backoff(i64),
}

pub struct BringUp {
    pub entry: Entry,
    pub mode: String,
    pub stage: BringUpStage,
    pub engine_obj: Option<Box<dyn Engine>>,
    pub front_obj: Option<Box<dyn Front>>,
    pub probe_addr: String,
    pub probe_user: String,
    pub probe_pass: String,
    pub own_rules: bool,
    pub deadline: i64,
    pub retry_at: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BringUpStage {
    Start,
    ConfirmEngine,
    Preflight,
    SyncRules,
    ProbeInbound,
    StartFront,
    ConfirmFront,
}

impl Daemon {
    pub fn new(deps: Deps) -> Daemon {
        Daemon {
            deps,
            cfg: Config::defaults(),
            last_good: BTreeMap::new(),
            sub_err: BTreeMap::new(),
            state: "idle".to_string(),
            last_err: String::new(),
            since: Timestamp::ZERO,
            sel: None,
            cur: None,
            pending: None,
            ks_on: false,
            ks_watcher: None,
            loop_: None,
            next_at: None,
            stop_until: None,
            pending_start: None,
            pending_forget: false,
            dev_id: None,
            dev_err: String::new(),
            run_active: false,
            next_watcher: 0,
            configured: false,
            probe_failures: 0,
        }
    }

    pub fn now_ms(&self) -> i64 {
        clock::millis((self.deps.clock)())
    }

    pub fn kill_switch_on(&self) -> bool {
        self.ks_on
    }

    /// The machine's id as a list host sees it. A reason it could not be had
    /// is logged once, and asked again at the next fetch.
    pub fn device(&mut self) -> Device {
        if self.dev_id.is_none() {
            match device::device_id(&self.deps.state_dir) {
                Ok(id) => {
                    self.dev_id = Some(id);
                    self.dev_err.clear();
                }
                Err(err) => {
                    if err != self.dev_err {
                        self.dev_err = err.clone();
                        self.log(&format!(
                            "device id: {err}; lists are fetched without one until it can be read or made"
                        ));
                    }
                }
            }
        }
        let mut device = self.deps.device.clone();
        device.id = self.dev_id.clone().unwrap_or_default();
        device
    }

    pub fn log(&self, text: &str) {
        (self.deps.logf)(&scrub::scrub(text));
    }

    pub fn handle(&mut self, request: Request) -> Response {
        self.dispatch(request)
    }

    /// One step at the current instant. Cases advance the clock to
    /// `next_deadline()` and step again until the state they want is reached.
    pub fn tick(&mut self) {
        self.step();
    }

    pub fn next_deadline(&self) -> Option<i64> {
        self.next_at
    }

    pub fn run(&mut self) {
        self.start_run();
    }

    pub fn stop(&mut self) {
        self.stop_all(false);
    }

    pub fn status(&self) -> Status {
        self.make_status()
    }
}
