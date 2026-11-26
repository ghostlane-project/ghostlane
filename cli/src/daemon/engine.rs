//! The fake engine, front, routes and fetch behind the cases. Every effect a
//! connection has is a word in `WorldState::log`; the cases read that log
//! instead of opening a socket.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use gocompat::time::Timestamp;

use super::clock::{Clock, FakeClock};
use super::links::{Device, FetchError, REFUSAL_NONE};
use super::{Deps, Engine, Front, FrontParams, OlcrtcParams, Routes, XrayParams};
use crate::ipc::VersionInfo;
use crate::store;
use crate::store::Headers;

#[derive(Clone, Debug)]
pub struct Refusal {
    pub kind: String,
    pub announce: String,
}

#[derive(Debug)]
pub struct WorldState {
    pub log: Vec<String>,
    pub logs: Vec<String>,
    pub body: Vec<u8>,
    pub bodies: BTreeMap<String, Vec<u8>>,
    pub fetch_err: Option<String>,
    pub fetch_errs: BTreeMap<String, String>,
    pub refusal: Option<Refusal>,
    pub refusals: BTreeMap<String, Refusal>,
    pub fetches: usize,
    pub devices: Vec<Device>,
    pub engine_err: BTreeMap<String, String>,
    pub xray_err: Option<String>,
    pub probe_err: Option<String>,
    pub dead_probe: BTreeSet<String>,
    pub dead_from: usize,
    pub fronts: usize,
    pub front_probe: BTreeMap<String, String>,
    pub probes: usize,
    pub eng_state: String,
    pub probe_ports: usize,
    pub fail_first_probe_inbound: bool,
}

impl WorldState {
    fn rec(&mut self, word: impl Into<String>) {
        self.log.push(word.into());
    }
}

pub type World = Arc<Mutex<WorldState>>;

pub fn world(body: Vec<u8>) -> World {
    Arc::new(Mutex::new(WorldState {
        log: Vec::new(),
        logs: Vec::new(),
        body,
        bodies: BTreeMap::new(),
        fetch_err: None,
        fetch_errs: BTreeMap::new(),
        refusal: None,
        refusals: BTreeMap::new(),
        fetches: 0,
        devices: Vec::new(),
        engine_err: BTreeMap::new(),
        xray_err: None,
        probe_err: None,
        dead_probe: BTreeSet::new(),
        dead_from: 0,
        fronts: 0,
        front_probe: BTreeMap::new(),
        probes: 0,
        eng_state: String::new(),
        probe_ports: 0,
        fail_first_probe_inbound: false,
    }))
}

pub fn events(world: &World) -> String {
    world.lock().expect("world").log.join(" ")
}

pub fn logged(world: &World, part: &str) -> bool {
    world
        .lock()
        .expect("world")
        .logs
        .iter()
        .any(|line| line.contains(part))
}

pub fn fixture(name: &str) -> Vec<u8> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("internal/links/testdata")
        .join(name);
    std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// A word counts as one event; `count` is the Go test's spaced-token count.
pub fn count(event: &str, word: &str) -> usize {
    event
        .split_whitespace()
        .filter(|token| *token == word)
        .count()
}

pub struct FakeEngine {
    pub world: World,
    pub name: String,
    pub addr: String,
}

impl Engine for FakeEngine {
    fn socks_addr(&self) -> String {
        self.addr.clone()
    }
    fn credentials(&self) -> (String, String) {
        ("u".to_string(), "p".to_string())
    }
    fn state(&self) -> String {
        let state = self.world.lock().expect("world").eng_state.clone();
        if state.is_empty() {
            "running".to_string()
        } else {
            state
        }
    }
    fn stop(&self) -> Result<(), String> {
        self.world
            .lock()
            .expect("world")
            .rec(if self.name == "xray" {
                "xray-stop"
            } else {
                "engine-stop"
            });
        Ok(())
    }
}

pub struct FakeFront {
    pub world: World,
}

impl Front for FakeFront {
    fn close(&self) -> Result<(), String> {
        self.world.lock().expect("world").rec("front-close");
        Ok(())
    }
}

#[derive(Clone)]
pub struct RecordingRoutes {
    pub world: World,
}

impl Routes for RecordingRoutes {
    fn global_addresses(&self) -> Result<Vec<String>, String> {
        Ok(vec!["203.0.113.5".to_string()])
    }
    fn sync(&self, _addresses: &[String]) -> Result<(), String> {
        self.world.lock().expect("world").rec("routes:sync");
        Ok(())
    }
    fn clear(&self) -> Result<(), String> {
        self.world.lock().expect("world").rec("routes:clear");
        Ok(())
    }
    fn cleanup_stale(&self) -> Result<(), String> {
        self.world.lock().expect("world").rec("routes:cleanup");
        Ok(())
    }
    fn install_kill_switch(&self, uid: i32) -> Result<(), String> {
        self.world
            .lock()
            .expect("world")
            .rec(format!("routes:killswitch:{uid}"));
        Ok(())
    }
    fn remove_kill_switch(&self) -> Result<(), String> {
        self.world
            .lock()
            .expect("world")
            .rec("routes:killswitch-off");
        Ok(())
    }
    fn watch(&self, _id: usize) -> Result<(), String> {
        self.world.lock().expect("world").rec("watch");
        Ok(())
    }
    fn unwatch(&self, _id: usize) -> Result<(), String> {
        self.world.lock().expect("world").rec("watch-stop");
        Ok(())
    }
}

/// The fetch answers from the world: a per-URL body or error wins, then a
/// refusal, then the world's body. The url handed to the daemon is unchanged,
/// so the cases can check that a credential never reaches a log.
pub fn fetch(world: &World) -> super::Fetch {
    let world = Arc::clone(world);
    Box::new(move |url, device| {
        let mut state = world.lock().expect("world");
        state.fetches += 1;
        state.devices.push(device);
        let body = state
            .bodies
            .get(url)
            .cloned()
            .unwrap_or_else(|| state.body.clone());
        let error = state
            .fetch_errs
            .get(url)
            .cloned()
            .or_else(|| state.fetch_err.clone());
        let refusal = state
            .refusals
            .get(url)
            .cloned()
            .or_else(|| state.refusal.clone());
        drop(state);
        if let Some(why) = error {
            return Err(FetchError::Other(why));
        }
        let mut headers = Headers {
            title: "Partner".to_string(),
            update_interval_hours: 1,
            ..Headers::default()
        };
        if let Some(refusal) = refusal {
            headers.refusal = refusal.kind;
            headers.announce = refusal.announce;
            return Err(FetchError::Refused {
                kind: headers.refusal.clone(),
                announce: headers.announce.clone(),
                headers: Box::new(headers),
            });
        }
        Ok((body, headers))
    })
}

/// What the daemon sees of the world, with the clock it must move itself.
pub fn deps(
    world: &World,
    state_dir: PathBuf,
    clock: &Arc<FakeClock>,
    logf: Box<dyn Fn(&str)>,
) -> Deps {
    let start_engine = {
        let world = Arc::clone(world);
        Box::new(move |params: &OlcrtcParams| {
            let state = world.lock().expect("world");
            let error = state.engine_err.get(&params.line.provider).cloned();
            let word = format!("engine:{}@{}", params.line.provider, params.line.room);
            drop(state);
            world.lock().expect("world").rec(word);
            if let Some(why) = error {
                return Err(why);
            }
            Ok(Box::new(FakeEngine {
                world: Arc::clone(&world),
                name: "engine".to_string(),
                addr: "127.0.0.1:1".to_string(),
            }) as Box<dyn Engine>)
        })
    };
    let start_xray = {
        let world = Arc::clone(world);
        Box::new(move |params: &XrayParams| {
            let state = world.lock().expect("world");
            let error = state.xray_err.clone();
            let word = format!("xray:{}", params.line.host);
            drop(state);
            world.lock().expect("world").rec(word);
            if let Some(why) = error {
                return Err(why);
            }
            Ok(Box::new(FakeEngine {
                world: Arc::clone(&world),
                name: "xray".to_string(),
                addr: "127.0.0.1:2".to_string(),
            }) as Box<dyn Engine>)
        })
    };
    let start_front = {
        let world = Arc::clone(world);
        Box::new(move |params: &FrontParams| {
            if params.probe_listen.is_empty() || params.probe_user.is_empty() {
                return Err("front without a probe inbound".to_string());
            }
            let mut state = world.lock().expect("world");
            state.fronts += 1;
            let n = state.fronts;
            let address = params.probe_listen.clone();
            state
                .front_probe
                .insert(address.clone(), format!("front:{n}"));
            if state.dead_from > 0 && n >= state.dead_from {
                state.dead_probe.insert(format!("front:{n}"));
            }
            state.rec(format!("front:{}:{}", params.mode, params.upstream));
            drop(state);
            Ok(Box::new(FakeFront {
                world: Arc::clone(&world),
            }) as Box<dyn Front>)
        })
    };
    let probe = {
        let world = Arc::clone(world);
        Box::new(move |address: &str, _user: &str, _pass: &str| {
            let mut state = world.lock().expect("world");
            state.probes += 1;
            let named = state
                .front_probe
                .get(address)
                .cloned()
                .unwrap_or_else(|| address.to_string());
            if state.dead_probe.contains(&named) {
                return Err(format!("dead probe {named}"));
            }
            state.probe_err.clone().map_or(Ok(()), Err)
        })
    };
    let probe_inbound = {
        let world = Arc::clone(world);
        Box::new(move || {
            let mut state = world.lock().expect("world");
            if state.fail_first_probe_inbound {
                state.fail_first_probe_inbound = false;
                return Err("no free port".to_string());
            }
            state.probe_ports += 1;
            Ok((
                format!("127.0.0.1:{}", 30_000 + state.probe_ports),
                "u".to_string(),
                "p".to_string(),
            ))
        })
    };
    let clock_handle: Clock = clock.handle();
    let advance = {
        let clock = Arc::clone(clock);
        Box::new(move |ms: i64| clock.advance(ms))
    };
    Deps {
        config_path: state_dir.join("config.yaml"),
        state_dir,
        socket_path: None,
        fetch: fetch(world),
        device: Device {
            os: "Linux".to_string(),
            os_version: "6.1.0-test".to_string(),
            model: "PC".to_string(),
            ..Device::default()
        },
        decrypt: None,
        start_engine,
        start_xray,
        start_front,
        routes: Box::new(RecordingRoutes {
            world: Arc::clone(world),
        }),
        probe,
        probe_inbound,
        clock: clock_handle,
        advance,
        logf,
        uid: 977,
        fetch_delay_ms: 0,
        stop_delay_ms: 0,
        confirm_timeout_ms: 2_000,
        probe_interval_ms: 20,
        probe_failures: 3,
        retry_min_ms: 20,
        retry_max_ms: 50,
        version: VersionInfo {
            version: "test".to_string(),
            ..VersionInfo::default()
        },
    }
}

/// Cases set the answer of one list without touching the others.
pub fn bodies_lock(world: &World) -> std::sync::MutexGuard<'_, WorldState> {
    world.lock().expect("world")
}
pub fn fetch_errs_lock(world: &World) -> std::sync::MutexGuard<'_, WorldState> {
    world.lock().expect("world")
}
pub fn refusals_lock(world: &World) -> std::sync::MutexGuard<'_, WorldState> {
    world.lock().expect("world")
}

pub fn with_body(world: &World, body: Vec<u8>) {
    world.lock().expect("world").body = body;
}

pub fn set_error(world: &World, error: Option<String>) {
    world.lock().expect("world").fetch_err = error;
}

pub fn set_probe_error(world: &World, error: Option<String>) {
    world.lock().expect("world").probe_err = error;
}

pub fn set_engine_error(world: &World, provider: &str, error: Option<String>) {
    let mut state = world.lock().expect("world");
    match error {
        Some(why) => {
            state.engine_err.insert(provider.to_string(), why);
        }
        None => {
            state.engine_err.remove(provider);
        }
    }
}

pub fn clear_engine_errors(world: &World) {
    world.lock().expect("world").engine_err.clear();
}

/// A list URL masked in status, as the Go cases read it.
pub fn masked(url: &str) -> String {
    store::mask_url(url)
}

pub const NO_REFUSAL: &str = REFUSAL_NONE;

pub fn timestamp(at: i64) -> Timestamp {
    Timestamp::from_unix(at, 0)
}
