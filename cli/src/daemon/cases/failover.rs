//! Failover cases: the next carrier, the next kind, the next round and the
//! line that last worked.

use super::*;
use crate::daemon::engine;
use crate::store;

#[test]
pub fn carrier_failover_and_last_good() {
    let mut rig = Rig::partner();
    engine::set_engine_error(&rig.world, "telemost", Some("room full".to_string()));
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    let status = rig.wait_state("up");
    assert_eq!(
        status.line.as_ref().expect("a line").carrier.as_str(),
        "wbstream"
    );

    engine::set_probe_error(&rig.world, Some("dead".to_string()));
    let mut status = status;
    for _ in 0..4_000 {
        status = rig.status();
        let carrier = status.line.as_ref().map(|line| line.carrier.as_str());
        if status.state == "up" && carrier == Some("salutejazz") {
            break;
        }
        if status.state == "connecting" && carrier == Some("salutejazz") {
            engine::set_probe_error(&rig.world, None);
        }
        rig.step();
    }
    assert_eq!(
        status.line.as_ref().expect("a line").carrier.as_str(),
        "salutejazz"
    );
    let good = store::load_last_good(&rig.dir).expect("a last-good file");
    assert_eq!(
        good.get("|DE"),
        Some(&status.line.as_ref().expect("a line").id)
    );

    rig.call("disconnect");
    rig.wait_state("idle");
    engine::clear_engine_errors(&rig.world);
    rig.connect("DE", "proxy");
    assert_eq!(
        rig.wait_state("up")
            .line
            .as_ref()
            .expect("a line")
            .carrier
            .as_str(),
        "salutejazz"
    );
}

#[test]
pub fn boot_before_network_retries() {
    let mut rig = Rig::partner();
    let mut config = store::Config::defaults();
    config.subscriptions.push(store::Subscription {
        url: LIST_URL.to_string(),
        interval_hours: 1,
        ..store::Subscription::default()
    });
    config.selection = Some(store::Selection {
        subscription: LIST_URL.to_string(),
        selector: "GB".to_string(),
        mode: "proxy".to_string(),
        ..store::Selection::default()
    });
    rig.set_config(config);
    engine::set_error(&rig.world, Some("dial: network is unreachable".to_string()));
    rig.d.run();
    let status = rig.wait_state("failed");
    assert!(status.last_error.contains("unreachable"), "{status:?}");
    engine::set_error(&rig.world, None);
    assert_eq!(rig.wait_state("up").line.expect("a line").country, "GB");
}

#[test]
pub fn watcher_stops_before_clear() {
    let mut rig = Rig::partner();
    engine::set_engine_error(&rig.world, "telemost", Some("room full".to_string()));
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "tun");
    rig.wait_state("up");
    engine::set_probe_error(&rig.world, Some("dead".to_string()));
    for _ in 0..4_000 {
        let status = rig.status();
        let carrier = status.line.as_ref().map(|line| line.carrier.as_str());
        if status.state == "connecting" && carrier == Some("salutejazz") {
            engine::set_probe_error(&rig.world, None);
        }
        if status.state == "up" && carrier == Some("salutejazz") {
            break;
        }
        rig.step();
    }
    rig.call("disconnect");
    rig.wait_state("idle");
    let events = rig.events();
    assert_eq!(engine::count(&events, "watch"), 2, "{events}");
    assert_eq!(engine::count(&events, "watch-stop"), 2, "{events}");
    assert_eq!(
        events
            .matches("watch-stop front-close engine-stop routes:clear")
            .count(),
        2,
        "{events}"
    );
}

#[test]
pub fn engine_state_ends_line() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    rig.wait_state("up");
    rig.world.lock().expect("world").eng_state = "stopped".to_string();
    for _ in 0..4_000 {
        if rig.events().contains("engine-stop engine:wbstream@") {
            rig.world.lock().expect("world").eng_state.clear();
            return;
        }
        rig.step();
    }
    panic!("a stopped engine did not move on: {}", rig.events());
}

#[test]
pub fn mixed_country_failover() {
    let mut rig = Rig::unified();
    rig.world
        .lock()
        .expect("world")
        .dead_probe
        .insert("front:1".to_string());
    rig.world.lock().expect("world").xray_err = Some("xhttp server gone".to_string());
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    let status = rig.wait_state("up");
    assert_eq!(status.line.as_ref().expect("a line").kind, "hysteria2");
    let first = rig.events();
    assert!(
        first.contains("front:proxy:vless front-close front:proxy:hy2"),
        "{first}"
    );

    engine::set_probe_error(&rig.world, Some("dead".to_string()));
    let mut status = status;
    for _ in 0..4_000 {
        status = rig.status();
        let kind = status.line.as_ref().map(|line| line.kind.as_str());
        if status.state == "connecting" && kind == Some("olcrtc") {
            engine::set_probe_error(&rig.world, None);
        }
        if status.state == "up" && kind == Some("olcrtc") {
            break;
        }
        rig.step();
    }
    assert_eq!(status.line.as_ref().expect("a line").kind, "olcrtc");
    assert!(
        rig.events().contains("xray:203.0.113.9 engine:telemost@"),
        "{}",
        rig.events()
    );
}

#[test]
pub fn failover_continues_after_wrap() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    let mut order: Vec<String> = Vec::new();
    let mut killed = std::collections::BTreeSet::new();
    for _ in 0..4_000 {
        if order.len() == 6 {
            break;
        }
        if rig.d.state == "up" {
            if let Some(live) = rig.d.cur.as_ref() {
                let named = {
                    let world = rig.world.lock().expect("world");
                    world
                        .front_probe
                        .get(&live.probe_addr)
                        .cloned()
                        .unwrap_or_else(|| live.probe_addr.clone())
                };
                if killed.insert(named.clone()) {
                    order.push(carrier(&live.entry));
                    rig.world.lock().expect("world").dead_probe.insert(named);
                }
            }
        }
        rig.step();
    }
    assert_eq!(
        order,
        vec![
            "telemost",
            "wbstream",
            "salutejazz",
            "vkcalls",
            "telemost",
            "wbstream"
        ],
        "{}",
        rig.events()
    );
}

#[test]
pub fn mixed_country_failover_tun() {
    let mut rig = Rig::unified();
    rig.world
        .lock()
        .expect("world")
        .dead_probe
        .insert("front:1".to_string());
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "tun");
    let status = rig.wait_state("up");
    assert_eq!(status.line.as_ref().expect("a line").kind, "hysteria2");
    let events = rig.events();
    assert!(
        events.contains(
            "front:proxy:vless front-close front:proxy:hy2 front-close routes:sync front:tun:hy2"
        ),
        "{events}"
    );
    assert!(!events.contains("front:tun:vless"), "{events}");
}

#[test]
pub fn numeric_selection_sticks_to_its_line() {
    let mut rig = Rig::partner();
    let url_a = "https://a.example/sub/1/x";
    engine::bodies_lock(&rig.world)
        .bodies
        .insert(url_a.to_string(), format!("{VLESS_SOLO}\n").into_bytes());
    rig.d.run();
    rig.add(url_a);
    rig.add(LIST_URL);
    rig.connect("2", "proxy");
    let first = rig.wait_state("up").line.expect("a line").clone();
    assert_eq!(first.carrier, "telemost");

    rig.d.stop();
    let _ = std::fs::remove_dir_all(rig.dir.join("lists"));
    engine::bodies_lock(&rig.world)
        .bodies
        .insert(url_a.to_string(), Vec::new());
    engine::fetch_errs_lock(&rig.world)
        .fetch_errs
        .insert(url_a.to_string(), "503".to_string());
    rig.restart();
    rig.d.run();
    let again = rig.wait_state("up").line.expect("a line").clone();
    assert_eq!(again.id, first.id, "the selection moved to another line");
}
