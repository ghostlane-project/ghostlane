//! State transitions in `daemon_test.go`: order of rules and engines, one
//! loop, the kill switch, and native lines in tun mode.

use super::*;
use crate::daemon::engine;
use crate::store;

#[test]
pub fn tun_mode_order() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("FR", "tun");
    rig.wait_state("up");
    let events = rig.events();
    assert!(
        events.find("routes:sync").expect("rules") < events.find("front:tun").expect("the tun"),
        "{events}"
    );
    rig.call("disconnect");
    rig.wait_state("idle");
    assert!(
        rig.events()
            .ends_with("front-close engine-stop routes:clear"),
        "{}",
        rig.events()
    );
}

#[test]
pub fn connect_replaces_running() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "tun");
    rig.wait_state("up");
    rig.connect("FR", "proxy");
    rig.pump_until(|d| d.state == "up" && d.cur.as_ref().is_some_and(|l| l.entry.country == "FR"));
    let events = rig.events();
    assert!(
        events.contains(
            "front-close engine-stop routes:clear engine:telemost@https://telemost.yandex.ru/j/1479206118"
        ),
        "{events}"
    );
    assert_eq!(rig.status().mode, "proxy");
}

#[test]
pub fn two_callers_do_not_overlap() {
    let mut rig = Rig::partner();
    rig.d.deps.stop_delay_ms = 30;
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "tun");
    rig.wait_state("up");
    // Paused time: three callers arrive while an engine is stopping. The last
    // selection stands, and no engine, front or rule set is left behind.
    for selector in ["FR", "GB", "IT"] {
        rig.connect(selector, "tun");
    }
    rig.pump_until(|d| d.state == "up" && d.stop_until.is_none() && d.pending_start.is_none());
    rig.call("disconnect");
    rig.wait_state("idle");
    let events = rig.events();
    let starts = events.matches("engine:").count();
    let stops = engine::count(&events, "engine-stop");
    assert_eq!(starts, stops, "{events}");
    assert_eq!(
        engine::count(&events, "front:tun:socks"),
        engine::count(&events, "front-close"),
        "{events}"
    );
    assert_eq!(
        engine::count(&events, "routes:sync"),
        engine::count(&events, "routes:clear"),
        "{events}"
    );
    assert!(events.ends_with("routes:clear"), "{events}");
}

#[test]
pub fn native_line_proxy_mode() {
    let mut rig = Rig::unified();
    rig.d.run();
    rig.add(LIST_URL);
    assert!(rig.connect("1", "proxy").ok);
    let status = rig.wait_state("up");
    assert_eq!(status.line.expect("a line").kind, "vless");
    let events = rig.events();
    assert!(
        !events.contains("engine:") && !events.contains("xray:"),
        "{events}"
    );
    assert!(events.contains("front:proxy:vless"), "{events}");
    rig.call("disconnect");
    rig.wait_state("idle");
    assert!(rig.events().ends_with("front-close"), "{}", rig.events());
}

#[test]
pub fn native_line_tun_preflight() {
    let mut rig = Rig::unified();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("2", "tun"); // the Hysteria2 line
    rig.wait_state("up");
    let first = rig.events();
    assert!(
        first.contains("front:proxy:hy2 front-close routes:sync front:tun:hy2"),
        "{first}"
    );
    rig.call("disconnect");
    rig.wait_state("idle");

    // The next front is the third one made. Its pre-flight fails, so no rule
    // and no tun front are left behind.
    rig.world.lock().expect("world").dead_from = 3;
    rig.connect("2", "tun");
    let status = rig.wait_state("failed");
    let tail = rig.events()[first.len()..].to_string();
    assert!(
        !tail.contains("routes:sync") && !tail.contains("front:tun"),
        "{tail}"
    );
    assert!(!status.last_error.is_empty(), "{status:?}");
}

#[test]
pub fn xhttp_line_uses_xray() {
    let mut rig = Rig::unified();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("3", "proxy");
    rig.wait_state("up");
    let events = rig.events();
    assert!(
        events.contains("xray:203.0.113.9 front:proxy:socks"),
        "{events}"
    );
    rig.call("disconnect");
    rig.wait_state("idle");
    assert!(
        rig.events().ends_with("front-close xray-stop"),
        "{}",
        rig.events()
    );
}

#[test]
pub fn tun_rules_cleared_when_probe_inbound_fails() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.world.lock().expect("world").fail_first_probe_inbound = true;
    rig.connect("DE", "tun");
    rig.wait_state("up");
    let events = rig.events();
    assert!(
        events.contains("routes:sync routes:clear engine-stop"),
        "{events}"
    );
}

#[test]
pub fn kill_switch_lifecycle() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    let response = rig.connect_as("DE", "proxy", true, "");
    assert!(!response.ok && response.error == "bad_mode", "{response:?}");
    rig.connect_as("DE", "tun", true, "");
    let status = rig.wait_state("up");
    assert!(status.kill_switch, "{status:?}");
    let config = store::load(&rig.dir.join("config.yaml")).expect("a config");
    assert!(config.selection.expect("a selection").kill_switch);

    let events = rig.events();
    let installed = events.find("routes:killswitch:977 routes:sync watch");
    let engine = events.find("engine");
    assert!(
        installed.is_some() && engine.is_some() && installed < engine,
        "{events}"
    );

    rig.world
        .lock()
        .expect("world")
        .dead_probe
        .insert("front:1".to_string());
    rig.pump_until(|d| {
        d.state == "up"
            && d.cur
                .as_ref()
                .is_some_and(|l| carrier(&l.entry) != "telemost")
    });
    let events = rig.events();
    assert!(
        !events.contains("killswitch-off")
            && !events.contains("routes:clear")
            && engine::count(&events, "routes:sync") == 1,
        "{events}"
    );

    rig.call("disconnect");
    rig.wait_state("idle");
    assert!(
        rig.events()
            .ends_with("watch-stop routes:clear routes:killswitch-off"),
        "{}",
        rig.events()
    );
}

#[test]
pub fn kill_switch_restored_at_start_removed_at_stop() {
    let mut rig = Rig::partner();
    let mut config = store::Config::defaults();
    config.subscriptions.push(store::Subscription {
        url: LIST_URL.to_string(),
        interval_hours: 1,
        ..store::Subscription::default()
    });
    config.selection = Some(store::Selection {
        selector: "DE".to_string(),
        mode: "tun".to_string(),
        kill_switch: true,
        ..store::Selection::default()
    });
    rig.set_config(config);
    rig.d.run();
    rig.wait_state("up");
    let events = rig.events();
    let cleanup = events.find("routes:cleanup");
    let switch = events.find("routes:killswitch:977");
    let engine = events.find("engine");
    assert!(
        cleanup.is_some()
            && switch.is_some()
            && engine.is_some()
            && cleanup < switch
            && switch < engine,
        "{events}"
    );
    rig.d.stop();
    assert!(
        rig.events().contains("routes:killswitch-off"),
        "{}",
        rig.events()
    );
}

#[test]
pub fn remove_subscription_removes_kill_switch() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect_as("DE", "tun", true, LIST_URL);
    rig.wait_state("up");
    assert!(rig.remove(LIST_URL).ok);
    let status = rig.wait_state("idle");
    assert!(!status.kill_switch && !rig.d.kill_switch_on(), "{status:?}");
    assert!(
        rig.events().ends_with("routes:killswitch-off"),
        "{}",
        rig.events()
    );
    let config = store::load(&rig.dir.join("config.yaml")).expect("a config");
    assert!(config.selection.is_none() && config.subscriptions.is_empty());
}

#[test]
pub fn connect_before_run_starts_one_loop() {
    let mut rig = Rig::partner();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    rig.wait_state("up");
    rig.d.run();
    rig.settle(10);
    let status = rig.status();
    let events = rig.events();
    assert!(
        status.state == "up"
            && events.matches("engine:").count() == 1
            && !events.contains("engine-stop"),
        "{status:?} / {events}"
    );
}
