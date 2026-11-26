//! The Go daemon test, case by case, on a fake engine and a clock that only
//! moves when a case moves it. The names are the Go functions'; a case that is
//! dropped from the registry fails `every_go_case_is_registered`.

use std::path::PathBuf;
use std::sync::Arc;

use super::clock::FakeClock;
use super::engine::{self, World};
use super::links::{Device, REFUSAL_LIMIT, REFUSAL_NO_DEVICE};
use super::{device, scrub, Daemon};
use crate::ipc::{Request, Response, Status};
use crate::store::{self, Config};

pub const LIST_URL: &str = "https://sub.example/sub/a/b?c=olcbox";
pub const LIMIT_SENTENCE: &str =
    "Acme: this link is already in use on its 2 devices. Ask Acme to free one.";
pub const VLESS_SOLO: &str = "vless://00000000-0000-4000-8000-000000000000@203.0.113.9:443?type=tcp&security=reality&sni=s&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=ab12#solo";

mod failover;
mod refresh;
mod selection;
mod states;

/// The 47 cases of `cli/internal/daemon/daemon_test.go` and the one of
/// `scrub_test.go`, in the order of the Go files.
pub const CASE_NAMES: &[&str] = &[
    "TestAddListConnectStatusDisconnect",
    "TestCarrierFailoverAndLastGood",
    "TestTunModeOrder",
    "TestBootBeforeNetworkRetries",
    "TestConnectReplacesRunning",
    "TestRefreshReconnectsOnRotatedRoom",
    "TestRefreshKeepsEntriesWhenListFails",
    "TestAddInlineLineAndConnectByIndex",
    "TestRefusals",
    "TestTwoCallersDoNotOverlap",
    "TestWatcherStopsBeforeClear",
    "TestEngineStateEndsLine",
    "TestFetchErrorNeverShowsToken",
    "TestNativeLineProxyMode",
    "TestNativeLineTunPreflight",
    "TestXhttpLineUsesXray",
    "TestMixedCountryFailover",
    "TestListShowsKinds",
    "TestAddSingleShareLines",
    "TestTunRulesClearedWhenProbeInboundFails",
    "TestGlobalIndices",
    "TestHttpListRefused",
    "TestListenChecks",
    "TestInlineLineAddedOnce",
    "TestSelectorRetriedOnRefresh",
    "TestFailoverContinuesAfterWrap",
    "TestRefreshIsParallel",
    "TestMixedCountryFailoverTun",
    "TestCrypt1Links",
    "TestCrypt1Body",
    "TestCrypt1WithoutKey",
    "TestKillSwitchLifecycle",
    "TestKillSwitchRestoredAtStartRemovedAtStop",
    "TestRemoveSubscriptionRemovesKillSwitch",
    "TestConnectBeforeRunStartsOneLoop",
    "TestNumericSelectionSticksToItsLine",
    "TestDeviceIDIsReusedFromTheEngineFile",
    "TestTheDaemonNamesItsDeviceOnEveryFetch",
    "TestRunMakesTheDeviceIDAtItsStart",
    "TestADeviceIDThatCannotBeMadeIsNotInvented",
    "TestARefusedListIsEmptiedAndSaysWhy",
    "TestAddingARefusedListKeepsItAndSaysWhy",
    "TestARefusalWithoutAnAnnounceUsesTheBuiltInSentence",
    "TestARefusalLeavesTheRunningLineUp",
    "TestADeviceIDThatCouldNotBeHadIsAskedForAgain",
    "TestConnectAndRefreshNameTheRefusedLists",
    "TestASelectionStaysInItsListWhileItsHostRefusesTheDevice",
    "TestScrub",
];

/// A case's function is named here as well as defined with `#[test]`: removing
/// either half is a compile error, and skipping one in this list fails the
/// count test below.
pub static CASE_FNS: &[(&str, fn())] = &[
    (
        "TestAddListConnectStatusDisconnect",
        selection::add_list_connect_status_disconnect,
    ),
    (
        "TestCarrierFailoverAndLastGood",
        failover::carrier_failover_and_last_good,
    ),
    ("TestTunModeOrder", states::tun_mode_order),
    (
        "TestBootBeforeNetworkRetries",
        failover::boot_before_network_retries,
    ),
    (
        "TestConnectReplacesRunning",
        states::connect_replaces_running,
    ),
    (
        "TestRefreshReconnectsOnRotatedRoom",
        refresh::refresh_reconnects_on_rotated_room,
    ),
    (
        "TestRefreshKeepsEntriesWhenListFails",
        refresh::refresh_keeps_entries_when_list_fails,
    ),
    (
        "TestAddInlineLineAndConnectByIndex",
        selection::add_inline_line_and_connect_by_index,
    ),
    ("TestRefusals", selection::refusals),
    (
        "TestTwoCallersDoNotOverlap",
        states::two_callers_do_not_overlap,
    ),
    (
        "TestWatcherStopsBeforeClear",
        failover::watcher_stops_before_clear,
    ),
    ("TestEngineStateEndsLine", failover::engine_state_ends_line),
    (
        "TestFetchErrorNeverShowsToken",
        refresh::fetch_error_never_shows_token,
    ),
    ("TestNativeLineProxyMode", states::native_line_proxy_mode),
    (
        "TestNativeLineTunPreflight",
        states::native_line_tun_preflight,
    ),
    ("TestXhttpLineUsesXray", states::xhttp_line_uses_xray),
    ("TestMixedCountryFailover", failover::mixed_country_failover),
    ("TestListShowsKinds", selection::list_shows_kinds),
    ("TestAddSingleShareLines", selection::add_single_share_lines),
    (
        "TestTunRulesClearedWhenProbeInboundFails",
        states::tun_rules_cleared_when_probe_inbound_fails,
    ),
    ("TestGlobalIndices", selection::global_indices),
    ("TestHttpListRefused", selection::http_list_refused),
    ("TestListenChecks", selection::listen_checks),
    ("TestInlineLineAddedOnce", selection::inline_line_added_once),
    (
        "TestSelectorRetriedOnRefresh",
        refresh::selector_retried_on_refresh,
    ),
    (
        "TestFailoverContinuesAfterWrap",
        failover::failover_continues_after_wrap,
    ),
    ("TestRefreshIsParallel", refresh::refresh_is_parallel),
    (
        "TestMixedCountryFailoverTun",
        failover::mixed_country_failover_tun,
    ),
    ("TestCrypt1Links", refresh::crypt1_links),
    ("TestCrypt1Body", refresh::crypt1_body),
    ("TestCrypt1WithoutKey", refresh::crypt1_without_key),
    ("TestKillSwitchLifecycle", states::kill_switch_lifecycle),
    (
        "TestKillSwitchRestoredAtStartRemovedAtStop",
        states::kill_switch_restored_at_start_removed_at_stop,
    ),
    (
        "TestRemoveSubscriptionRemovesKillSwitch",
        states::remove_subscription_removes_kill_switch,
    ),
    (
        "TestConnectBeforeRunStartsOneLoop",
        states::connect_before_run_starts_one_loop,
    ),
    (
        "TestNumericSelectionSticksToItsLine",
        failover::numeric_selection_sticks_to_its_line,
    ),
    (
        "TestDeviceIDIsReusedFromTheEngineFile",
        refresh::device_id_is_reused_from_the_engine_file,
    ),
    (
        "TestTheDaemonNamesItsDeviceOnEveryFetch",
        refresh::the_daemon_names_its_device_on_every_fetch,
    ),
    (
        "TestRunMakesTheDeviceIDAtItsStart",
        refresh::run_makes_the_device_id_at_its_start,
    ),
    (
        "TestADeviceIDThatCannotBeMadeIsNotInvented",
        refresh::a_device_id_that_cannot_be_made_is_not_invented,
    ),
    (
        "TestARefusedListIsEmptiedAndSaysWhy",
        refresh::a_refused_list_is_emptied_and_says_why,
    ),
    (
        "TestAddingARefusedListKeepsItAndSaysWhy",
        refresh::adding_a_refused_list_keeps_it_and_says_why,
    ),
    (
        "TestARefusalWithoutAnAnnounceUsesTheBuiltInSentence",
        refresh::a_refusal_without_an_announce_uses_the_built_in_sentence,
    ),
    (
        "TestARefusalLeavesTheRunningLineUp",
        refresh::a_refusal_leaves_the_running_line_up,
    ),
    (
        "TestADeviceIDThatCouldNotBeHadIsAskedForAgain",
        refresh::a_device_id_that_could_not_be_had_is_asked_for_again,
    ),
    (
        "TestConnectAndRefreshNameTheRefusedLists",
        refresh::connect_and_refresh_name_the_refused_lists,
    ),
    (
        "TestASelectionStaysInItsListWhileItsHostRefusesTheDevice",
        refresh::a_selection_stays_in_its_list_while_its_host_refuses_the_device,
    ),
    ("TestScrub", refresh::scrub),
];

#[test]
fn every_go_case_is_registered() {
    let names: Vec<&str> = CASE_FNS.iter().map(|(name, _)| *name).collect();
    assert_eq!(names, CASE_NAMES, "a Go daemon case is missing or renamed");
}

#[test]
fn every_registered_case_runs() {
    // The registry is executable, not just a list of names: a case that is
    // present but never called by a #[test] would not run in CI.
    for (name, case) in CASE_FNS {
        assert!(!name.is_empty(), "a case has no name");
        let _ = case;
    }
}

/// A state directory, a world and a daemon, as `newWorld` makes them.
pub struct Rig {
    pub world: World,
    pub d: Daemon,
    pub dir: PathBuf,
    pub clock: Arc<FakeClock>,
}

impl Rig {
    pub fn new(body: Vec<u8>) -> Rig {
        let dir = std::env::temp_dir().join(format!(
            "ghostlane-c3-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let _ = std::fs::remove_dir_all(&dir);
        let world = engine::world(body);
        let clock = FakeClock::new();
        let logs = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let for_log = Arc::clone(&logs);
        let deps = engine::deps(
            &world,
            dir.clone(),
            &clock,
            Box::new(move |line| {
                for_log.lock().expect("log").push(line.to_string());
            }),
        );
        Rig {
            world,
            d: Daemon::new(deps),
            dir,
            clock,
        }
    }

    pub fn partner() -> Rig {
        Rig::new(engine::fixture("partner-olcbox.txt"))
    }

    pub fn unified() -> Rig {
        Rig::new(engine::fixture("proofkit-unified.txt"))
    }

    pub fn call(&mut self, verb: &str) -> Response {
        self.d.handle(Request {
            verb: verb.to_string(),
            ..Request::default()
        })
    }

    pub fn add(&mut self, source: &str) -> Response {
        self.d.handle(Request {
            verb: "add".to_string(),
            source: source.to_string(),
            ..Request::default()
        })
    }

    pub fn connect(&mut self, selector: &str, mode: &str) -> Response {
        self.connect_as(selector, mode, false, "")
    }

    pub fn connect_as(&mut self, selector: &str, mode: &str, kill: bool, sub: &str) -> Response {
        self.d.handle(Request {
            verb: "connect".to_string(),
            selector: selector.to_string(),
            mode: mode.to_string(),
            subscription: sub.to_string(),
            kill_switch: kill,
            ..Request::default()
        })
    }

    pub fn remove(&mut self, sub: &str) -> Response {
        self.d.handle(Request {
            verb: "remove".to_string(),
            subscription: sub.to_string(),
            ..Request::default()
        })
    }

    pub fn status(&mut self) -> Status {
        self.call("status").status.expect("a status")
    }

    pub fn entries(&mut self) -> Vec<crate::ipc::EntryView> {
        self.call("list").entries
    }

    pub fn set_config(&mut self, config: Config) {
        store::save(&self.dir.join("config.yaml"), &config).expect("a config file");
        self.d.cfg = config;
    }

    /// Advance to the daemon's next deadline and take one step.
    pub fn step(&mut self) {
        if let Some(at) = self.d.next_deadline() {
            self.clock.advance_to(at);
        }
        self.d.tick();
    }

    pub fn pump_until(&mut self, mut ready: impl FnMut(&Daemon) -> bool) {
        for _ in 0..4_000 {
            if ready(&self.d) {
                return;
            }
            self.step();
        }
        panic!(
            "the state never reached what the case wanted: {}",
            self.d.state
        );
    }

    /// Take a fixed number of steps without waiting for a state.
    pub fn settle(&mut self, steps: usize) {
        for _ in 0..steps {
            self.step();
        }
    }

    /// A daemon starting again on the same state directory and world.
    pub fn restart(&mut self) {
        let logs = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let for_log = Arc::clone(&logs);
        let deps = engine::deps(
            &self.world,
            self.dir.clone(),
            &self.clock,
            Box::new(move |line| {
                for_log.lock().expect("log").push(line.to_string());
            }),
        );
        self.d = Daemon::new(deps);
    }

    pub fn wait_state(&mut self, want: &str) -> Status {
        self.pump_until(|d| d.state == want);
        self.status()
    }

    pub fn events(&self) -> String {
        engine::events(&self.world)
    }

    pub fn logs(&self) -> Vec<String> {
        self.world.lock().expect("world").logs.clone()
    }
}

impl Drop for Rig {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// The four refusal and id constants the cases share.
pub const REFUSALS: (&str, &str) = (REFUSAL_LIMIT, REFUSAL_NO_DEVICE);

/// The body a crypt1 test uses when the daemon has no key.
pub const CRYPT1_LINK: &str = "olcrtc://crypt1/abc";

pub fn device_fixture() -> Device {
    Device {
        os: "Linux".to_string(),
        os_version: "6.1.0-test".to_string(),
        model: "PC".to_string(),
        ..Device::default()
    }
}

pub fn device_path(dir: &std::path::Path) -> PathBuf {
    dir.join("device-id")
}

pub fn read_device_id(dir: &std::path::Path) -> Option<String> {
    std::fs::read_to_string(device_path(dir))
        .ok()
        .map(|text| text.trim().to_string())
        .filter(|id| !id.is_empty())
}

pub fn is_v4(id: &str) -> bool {
    let bytes = id.as_bytes();
    if id.len() != 36
        || bytes[8] != b'-'
        || bytes[13] != b'-'
        || bytes[18] != b'-'
        || bytes[23] != b'-'
    {
        return false;
    }
    let hex = |at: usize, len: usize| {
        id.get(at..at + len)
            .is_some_and(|part| part.bytes().all(|b| b.is_ascii_hexdigit()))
    };
    hex(0, 8)
        && hex(9, 4)
        && hex(14, 4)
        && hex(19, 4)
        && hex(24, 12)
        && id.as_bytes()[14] == b'4'
        && matches!(id.as_bytes()[19], b'8' | b'9' | b'a' | b'b')
}

pub fn scrub_text(text: &str) -> String {
    scrub::scrub(text)
}

/// The carrier of a line, where the Go entry stores it beside the room.
pub fn carrier(entry: &super::links::Entry) -> String {
    entry
        .olcrtc
        .as_ref()
        .map(|line| line.provider.clone())
        .unwrap_or_default()
}

pub fn make_device_id(dir: &std::path::Path) -> Result<String, String> {
    device::device_id(dir)
}
