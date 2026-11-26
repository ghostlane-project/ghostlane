//! Selection and list cases: TestAddListConnectStatusDisconnect and its
//! neighbours in `daemon_test.go`.

use super::*;
use crate::daemon::engine;
use crate::store;

#[test]
pub fn add_list_connect_status_disconnect() {
    let mut rig = Rig::partner();
    rig.d.run();
    let response = rig.add(LIST_URL);
    assert!(response.ok, "{response:?}");
    assert!(response.message.contains("64"), "{response:?}");
    let entries = rig.entries();
    assert_eq!(entries.len(), 64);
    assert_eq!(entries[0].index, 1);
    assert_eq!(entries[0].country, "CA");

    assert!(rig.connect("DE", "proxy").ok);
    let status = rig.wait_state("up");
    let line = status.line.as_ref().expect("a line");
    assert_eq!(line.country, "DE");
    assert_eq!(line.carrier, "telemost");
    assert_eq!(status.mode, "proxy");
    assert_eq!(
        status.proxy.as_ref().expect("a proxy").socks,
        "127.0.0.1:1080"
    );

    let events = rig.events();
    assert!(
        events.starts_with("routes:cleanup engine:telemost@")
            && events
                .split_whitespace()
                .any(|word| word == "front:proxy:socks"),
        "{events}"
    );
    let config = store::load(&rig.dir.join("config.yaml")).expect("a config");
    assert_eq!(
        config.selection.as_ref().map(|s| s.selector.as_str()),
        Some("DE")
    );

    assert!(rig.call("disconnect").ok);
    rig.wait_state("idle");
    assert!(
        rig.events().ends_with("front-close engine-stop"),
        "{}",
        rig.events()
    );
    let config = store::load(&rig.dir.join("config.yaml")).expect("a config");
    assert!(config.selection.is_none(), "the selection is cleared");
}

#[test]
pub fn add_inline_line_and_connect_by_index() {
    let mut rig = Rig::partner();
    rig.d.run();
    let line = format!(
        "olcrtc://wbstream?vp8channel@room_q#{}$🇯🇵 JP · VP8 · WB",
        "cd".repeat(32)
    );
    assert!(rig.add(&line).ok);
    let entries = rig.entries();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].carrier, "wbstream");
    assert!(rig.connect("1", "proxy").ok);
    let status = rig.wait_state("up");
    assert_eq!(status.line.expect("a line").label, "🇯🇵 JP · VP8 · WB");
}

#[test]
pub fn refusals() {
    let mut rig = Rig::partner();
    let response = rig.connect("DE", "proxy");
    assert!(
        !response.ok && response.error == "no_subscription",
        "{response:?}"
    );
    rig.add(LIST_URL);
    let response = rig.connect("XX", "proxy");
    assert!(!response.ok && response.error == "no_match", "{response:?}");
    let response = rig.connect("DE", "vpn");
    assert!(!response.ok && response.error == "bad_mode", "{response:?}");
    let response = rig.add("trojan://p@h:443#x");
    assert!(
        !response.ok && response.error == "unsupported",
        "{response:?}"
    );
    let response = rig.add("olcrtc://crypt1/abc");
    assert!(
        !response.ok && response.message.contains("crypt1"),
        "{response:?}"
    );
    assert!(rig.remove("https://sub.example/sub/a/").ok);
    assert!(rig.entries().is_empty());
}

#[test]
pub fn list_shows_kinds() {
    let mut rig = Rig::partner();
    engine::with_body(&rig.world, engine::fixture("partner-plain.txt"));
    rig.d.run();
    let response = rig.add(LIST_URL);
    assert!(response.ok, "{response:?}");
    assert!(
        response.message.contains("17 entries (17 usable now)"),
        "{response:?}"
    );
    let entries = rig.entries();
    let (vless, hy2) = entries.iter().fold((0, 0), |(v, h), entry| {
        assert!(entry.problem.is_empty(), "{entry:?}");
        match entry.kind.as_str() {
            "vless" => (v + 1, h),
            "hysteria2" => (v, h + 1),
            other => panic!("an unexpected kind {other}"),
        }
    });
    assert_eq!((vless, hy2), (9, 8));
}

#[test]
pub fn add_single_share_lines() {
    let mut rig = Rig::partner();
    rig.d.run();
    let vless = "vless://00000000-0000-4000-8000-000000000000@203.0.113.9:443?type=tcp&security=reality&sni=yandex.ru&fp=chrome&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=ab12&flow=xtls-rprx-vision#DE via RU";
    let hy2 = "hy2://pw@203.0.113.9:38443?sni=s.example&obfs=salamander&obfs-password=salt#DE hy2";
    assert!(rig.add(vless).ok);
    assert!(rig.add(hy2).ok);
    let entries = rig.entries();
    assert_eq!(entries.len(), 2);
    assert_eq!(entries[0].kind, "vless");
    assert_eq!(entries[0].label, "DE via RU");
    assert_eq!(entries[1].kind, "hysteria2");
    assert_eq!(entries[1].label, "DE hy2");
    let response = rig.add("vless://x@h:443?type=grpc");
    assert!(
        !response.ok && response.message.contains("grpc"),
        "{response:?}"
    );
    rig.connect("DE hy2", "proxy");
    assert_eq!(rig.wait_state("up").line.expect("a line").kind, "hysteria2");
}

#[test]
pub fn global_indices() {
    let mut rig = Rig::partner();
    rig.d.run();
    assert!(rig.add(VLESS_SOLO).ok);
    assert!(rig.add(LIST_URL).ok);
    let entries = rig.entries();
    assert_eq!(entries.len(), 65);
    assert_eq!(entries[0].index, 1);
    assert_eq!(entries[1].index, 2);
    assert_eq!(entries[64].index, 65);
    rig.connect("2", "proxy");
    let line = rig.wait_state("up").line.expect("a line");
    assert_eq!(line.country, "CA");
    assert_eq!(line.carrier, "telemost");
}

#[test]
pub fn http_list_refused() {
    let mut rig = Rig::partner();
    let response = rig.add("http://sub.example/sub/a/b");
    assert!(!response.ok && response.error == "insecure", "{response:?}");
}

#[test]
pub fn listen_checks() {
    let mut rig = Rig::partner();
    let mut config = store::Config::defaults();
    config.proxy.listen = "localhost".to_string();
    rig.set_config(config);
    rig.d.run();
    rig.add(LIST_URL);
    let response = rig.connect("DE", "proxy");
    assert!(
        !response.ok && response.error == "bad_listen",
        "{response:?}"
    );
    rig.d.cfg.proxy.listen = "::1".to_string();
    rig.connect("DE", "proxy");
    let status = rig.wait_state("up");
    assert_eq!(status.proxy.expect("a proxy").socks, "[::1]:1080");
}

#[test]
pub fn inline_line_added_once() {
    let mut rig = Rig::partner();
    let line = format!("olcrtc://wbstream?vp8channel@room_q#{}$JP", "cd".repeat(32));
    assert!(rig.add(&line).ok);
    let response = rig.add(&line);
    assert!(
        response.ok && response.message == "already added",
        "{response:?}"
    );
    assert_eq!(rig.entries().len(), 1);
}
