//! Refresh, device id, crypt1 and refusal cases from `daemon_test.go`, plus
//! the one case of `scrub_test.go`.

use super::*;
use crate::daemon::engine::{self, Refusal};
use crate::daemon::links::{REFUSAL_LIMIT, REFUSAL_NO_DEVICE};
use crate::store;
use base64::Engine as _;
use std::os::unix::fs::PermissionsExt as _;

pub fn refusal(kind: &str, announce: &str) -> Refusal {
    Refusal {
        kind: kind.to_string(),
        announce: announce.to_string(),
    }
}

/// A stand-in for the crypt1 key and blob C2 ports: the daemon only ever
/// calls the injected decryptor, so the case can make a blob it can verify.
pub fn encrypt(plain: &[u8]) -> String {
    format!(
        "CRY1{}",
        base64::engine::general_purpose::STANDARD_NO_PAD.encode(plain)
    )
}

pub fn decrypt(blob: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD_NO_PAD
        .decode(blob.strip_prefix("CRY1")?)
        .ok()
}

pub fn with_decrypt(rig: &mut Rig) {
    rig.d.deps.decrypt = Some(Box::new(decrypt));
}

#[test]
pub fn refresh_reconnects_on_rotated_room() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    rig.wait_state("up");
    let body = rig.world.lock().expect("world").body.clone();
    let rotated = String::from_utf8(body)
        .unwrap()
        .replacen(
            "https://telemost.yandex.ru/j/5092358972",
            "https://telemost.yandex.ru/j/1111111111",
            1,
        )
        .into_bytes();
    engine::with_body(&rig.world, rotated);
    assert!(rig.call("refresh").ok);
    for _ in 0..4_000 {
        if rig
            .events()
            .contains("engine:telemost@https://telemost.yandex.ru/j/1111111111")
        {
            return;
        }
        rig.step();
    }
    panic!("a rotated room did not reconnect: {}", rig.events());
}

#[test]
pub fn refresh_keeps_entries_when_list_fails() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    engine::set_error(&rig.world, Some("list: HTTP 404".to_string()));
    rig.call("refresh");
    assert_eq!(rig.entries().len(), 64);
    let status = rig.status();
    assert_eq!(status.subscriptions.len(), 1);
    assert!(
        status.subscriptions[0].error.contains("404")
            && status.subscriptions[0].url == "https://sub.example/sub/a/…?c=olcbox",
        "{status:?}"
    );
}

#[test]
pub fn fetch_error_never_shows_token() {
    let mut rig = Rig::partner();
    let secret = "https://sub.example/sub/a/b?c=olcbox&token=SECRETTOKEN";
    engine::set_error(
        &rig.world,
        Some(format!("Get {secret}: dial tcp: i/o timeout")),
    );
    let response = rig.add(secret);
    assert!(
        !response.ok
            && !response.message.contains("SECRETTOKEN")
            && !response.message.contains("4gg96"),
        "{response:?}"
    );
    let mut config = store::Config::defaults();
    config.subscriptions.push(store::Subscription {
        url: secret.to_string(),
        interval_hours: 1,
        ..store::Subscription::default()
    });
    config.selection = Some(store::Selection {
        subscription: secret.to_string(),
        selector: "DE".to_string(),
        mode: "proxy".to_string(),
        ..store::Selection::default()
    });
    rig.set_config(config);
    rig.call("refresh");
    rig.pump_until(|d| d.state == "failed");
    let status = rig.status();
    let mut all = status.last_error.clone();
    for sub in &status.subscriptions {
        all.push(' ');
        all.push_str(&sub.error);
        all.push(' ');
        all.push_str(&sub.url);
    }
    assert!(!all.contains("SECRETTOKEN"), "{all}");
}

#[test]
pub fn selector_retried_on_refresh() {
    let mut rig = Rig::partner();
    let without_fr: Vec<u8> = String::from_utf8(rig.world.lock().expect("world").body.clone())
        .unwrap()
        .lines()
        .filter(|line| !line.contains("FR"))
        .collect::<Vec<_>>()
        .join("\n")
        .into_bytes();
    let full = rig.world.lock().expect("world").body.clone();
    engine::with_body(&rig.world, without_fr);
    let mut config = store::Config::defaults();
    config.subscriptions.push(store::Subscription {
        url: LIST_URL.to_string(),
        interval_hours: 1,
        ..store::Subscription::default()
    });
    config.selection = Some(store::Selection {
        subscription: LIST_URL.to_string(),
        selector: "FR".to_string(),
        mode: "proxy".to_string(),
        ..store::Selection::default()
    });
    rig.set_config(config);
    rig.d.run();
    rig.wait_state("failed");
    engine::with_body(&rig.world, full);
    rig.call("refresh");
    assert_eq!(rig.wait_state("up").line.expect("a line").country, "FR");
}

#[test]
pub fn refresh_is_parallel() {
    let mut rig = Rig::partner();
    rig.d.run();
    for url in [
        "https://a.example/sub/1/x",
        "https://b.example/sub/1/x",
        "https://c.example/sub/1/x",
        "https://d.example/sub/1/x",
    ] {
        rig.add(url);
    }
    rig.d.deps.fetch_delay_ms = 200;
    let before = rig.clock.millis();
    rig.call("refresh");
    let elapsed = rig.clock.millis() - before;
    assert!(elapsed <= 200, "four fetches took {elapsed}ms, not 200ms");
    assert_eq!(rig.world.lock().expect("world").fetches, 8);
}

#[test]
pub fn crypt1_links() {
    let mut rig = Rig::partner();
    with_decrypt(&mut rig);
    rig.d.run();
    let link = format!("olcrtc://crypt1/{}", encrypt(LIST_URL.as_bytes()));
    let response = rig.add(&link);
    assert!(
        response.ok && response.message.contains("64 entries"),
        "{response:?}"
    );
    let lines = format!(
        "olcrtc://wbstream?vp8channel@room_q#{}$JP · WB\nolcrtc://salutejazz?datachannel@a:b#{}$JP · SJ\n",
        "cd".repeat(32),
        "cd".repeat(32)
    );
    assert!(
        rig.add(&format!("olcrtc://crypt1/{}", encrypt(lines.as_bytes())))
            .ok
    );
    let entries = rig.entries();
    assert_eq!(entries.len(), 66);
    assert_eq!(entries[65].label, "JP · SJ");
    let response = rig.add(&format!("olcrtc://crypt1/{}", "A".repeat(88)));
    assert!(!response.ok && response.error == "bad_line", "{response:?}");
}

#[test]
pub fn crypt1_body() {
    let mut rig = Rig::partner();
    with_decrypt(&mut rig);
    let body = rig.world.lock().expect("world").body.clone();
    engine::with_body(&rig.world, encrypt(&body).into_bytes());
    rig.d.run();
    let response = rig.add(LIST_URL);
    assert!(
        response.ok && response.message.contains("64 entries"),
        "{response:?}"
    );
    rig.connect("DE", "proxy");
    rig.wait_state("up");
}

#[test]
pub fn crypt1_without_key() {
    let mut rig = Rig::partner();
    let response = rig.add(CRYPT1_LINK);
    assert!(
        !response.ok && response.message.contains("crypt1"),
        "{response:?}"
    );
}

#[test]
pub fn device_id_is_reused_from_the_engine_file() {
    let v4 = |id: &str| is_v4(id);
    // A file the engine made is read back unchanged.
    let dir = std::env::temp_dir().join(format!("gl-c3-device-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("a directory");
    let engine_id = "6f1d3c2a-5b7e-4a90-8c11-2d3e4f5a6b7c";
    std::fs::write(device_path(&dir), format!("{engine_id}\n")).expect("a file");
    assert_eq!(make_device_id(&dir).expect("an id"), engine_id);
    assert_eq!(
        std::fs::read_to_string(device_path(&dir)).expect("the file"),
        format!("{engine_id}\n")
    );

    // Whatever the engine wrote is the id, trimmed.
    std::fs::write(device_path(&dir), "  install-0123456789abcdef \r\n\n").expect("a file");
    assert_eq!(
        make_device_id(&dir).expect("an id"),
        "install-0123456789abcdef"
    );

    // Absent: a v4 id is written with a newline and mode 0600.
    let first_dir = dir.join("state");
    let first = make_device_id(&first_dir).expect("an id");
    assert!(v4(&first), "{first}");
    let mode = std::fs::metadata(device_path(&first_dir))
        .expect("the file")
        .permissions()
        .mode()
        & 0o777;
    assert_eq!(mode, 0o600);
    assert_eq!(
        std::fs::read_to_string(device_path(&first_dir)).expect("the file"),
        format!("{first}\n")
    );
    assert_eq!(make_device_id(&first_dir).expect("an id"), first);
    let other = make_device_id(&dir.join("other-state")).expect("an id");
    assert_ne!(other, first);

    // An empty file holds no id.
    std::fs::write(device_path(&dir), " \n").expect("a file");
    let made = make_device_id(&dir).expect("an id");
    assert!(v4(&made));
    assert_eq!(
        std::fs::read_to_string(device_path(&dir)).expect("the file"),
        format!("{made}\n")
    );

    // Neither a state directory under a file nor a directory at the file's
    // path can hold an id: an error, not an invented id.
    let blocked = dir.join("blocked");
    std::fs::write(&blocked, "").expect("a file");
    assert!(make_device_id(&blocked.join("state")).is_err());
    std::fs::create_dir_all(dir.join("a-directory")).expect("a directory");
    let as_dir = dir.join("a-directory");
    std::fs::create_dir_all(device_path(&as_dir)).expect("a directory at the file");
    assert!(make_device_id(&as_dir).is_err());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
pub fn the_daemon_names_its_device_on_every_fetch() {
    let mut rig = Rig::partner();
    rig.add(LIST_URL);
    rig.call("refresh");
    let id = read_device_id(&rig.dir).expect("an id");
    let devices = rig.world.lock().expect("world").devices.clone();
    assert!(devices.len() >= 2 && !id.is_empty(), "{devices:?} / {id}");
    for device in devices {
        assert_eq!(
            device,
            Device {
                id: id.clone(),
                ..device_fixture()
            }
        );
    }
    rig.restart();
    assert_eq!(rig.d.device().id, id);
}

#[test]
pub fn run_makes_the_device_id_at_its_start() {
    let mut rig = Rig::partner();
    rig.d.run();
    assert!(
        read_device_id(&rig.dir).is_some_and(|id| !id.is_empty()),
        "Run left no device-id behind"
    );
}

#[test]
pub fn a_device_id_that_cannot_be_made_is_not_invented() {
    let dir = std::env::temp_dir().join(format!("gl-c3-no-device-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("a directory");
    let blocked = dir.join("file");
    std::fs::write(&blocked, "").expect("a file");
    let logs = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
    let for_log = std::sync::Arc::clone(&logs);
    let mut rig = Rig::partner();
    rig.d = Daemon::new(engine::deps(
        &rig.world,
        blocked.join("state"),
        &rig.clock,
        Box::new(move |line| for_log.lock().expect("log").push(line.to_string())),
    ));
    for _ in 0..3 {
        assert_eq!(rig.d.device(), device_fixture());
    }
    let logs = logs.lock().expect("log");
    assert_eq!(logs.len(), 1, "{logs:?}");
    assert!(logs[0].contains("device id"), "{logs:?}");
    drop(logs);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
pub fn a_refused_list_is_emptied_and_says_why() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    assert_eq!(rig.entries().len(), 64);
    rig.world.lock().expect("world").refusal = Some(refusal(REFUSAL_LIMIT, LIMIT_SENTENCE));
    assert!(rig.call("refresh").ok);
    let list = rig.call("list");
    assert!(list.entries.is_empty(), "{list:?}");
    assert!(
        list.message
            .contains(&format!("Partner: device refused: {LIMIT_SENTENCE}")),
        "{list:?}"
    );
    let status = rig.status();
    let sub = &status.subscriptions[0];
    assert_eq!(sub.error, format!("device refused: {LIMIT_SENTENCE}"));
    assert_eq!(sub.title, "Partner");
    assert!(
        !sub.fetched_at.is_empty() && !sub.next_refresh.is_empty(),
        "{sub:?}"
    );
    let cache = store::load_cache(&rig.dir, LIST_URL)
        .expect("a cache")
        .expect("a cache");
    assert!(cache.body.is_none());
    assert_eq!(cache.headers.refusal, REFUSAL_LIMIT);
    assert_eq!(cache.headers.announce, LIMIT_SENTENCE);
    assert!(rig
        .logs()
        .iter()
        .any(|line| { line.contains("device refused: ") && line.contains(LIMIT_SENTENCE) }));
    assert!(!engine::logged(&rig.world, "/sub/a/b"), "{:?}", rig.logs());

    rig.world.lock().expect("world").refusal = None;
    rig.call("refresh");
    let list = rig.call("list");
    assert_eq!(list.entries.len(), 64);
    assert!(list.message.is_empty(), "{list:?}");
    assert!(rig.status().subscriptions[0].error.is_empty());
}

#[test]
pub fn adding_a_refused_list_keeps_it_and_says_why() {
    let mut rig = Rig::partner();
    rig.world.lock().expect("world").refusal = Some(refusal(REFUSAL_LIMIT, LIMIT_SENTENCE));
    let response = rig.add(LIST_URL);
    assert!(
        response.ok
            && response
                .message
                .contains(&format!("device refused: {LIMIT_SENTENCE}")),
        "{response:?}"
    );
    let config = store::load(&rig.dir.join("config.yaml")).expect("a config");
    assert_eq!(config.subscriptions.len(), 1);
    assert_eq!(config.subscriptions[0].url, LIST_URL);
    assert_eq!(
        rig.status().subscriptions[0].error,
        format!("device refused: {LIMIT_SENTENCE}")
    );
    assert!(!rig.connect("DE", "proxy").ok);
    rig.world.lock().expect("world").refusal = None;
    rig.call("refresh");
    assert_eq!(rig.entries().len(), 64);
}

#[test]
pub fn a_refusal_without_an_announce_uses_the_built_in_sentence() {
    for (kind, wanted) in [
        (
            REFUSAL_LIMIT,
            "device refused: This list has no place for this device. Ask your provider.",
        ),
        (
            REFUSAL_NO_DEVICE,
            "device refused: The provider could not read this device's id. Refresh the list; if it stays so, ask your provider.",
        ),
    ] {
        let mut rig = Rig::partner();
        rig.world.lock().expect("world").refusal = Some(refusal(kind, ""));
        let response = rig.add(LIST_URL);
        assert!(response.ok && response.message.contains(wanted), "{response:?}");
        assert_eq!(rig.status().subscriptions[0].error, wanted);
    }
}

#[test]
pub fn a_refusal_leaves_the_running_line_up() {
    let mut rig = Rig::partner();
    rig.d.run();
    rig.add(LIST_URL);
    rig.connect("DE", "proxy");
    rig.wait_state("up");
    rig.world.lock().expect("world").refusal = Some(refusal(REFUSAL_LIMIT, LIMIT_SENTENCE));
    rig.call("refresh");
    rig.settle(5);
    let status = rig.status();
    assert!(
        status.state == "up" && status.line.as_ref().is_some_and(|l| l.country == "DE"),
        "{status:?}"
    );
    assert!(!rig.events().contains("engine-stop"), "{}", rig.events());
    assert_eq!(
        status.subscriptions[0].error,
        format!("device refused: {LIMIT_SENTENCE}")
    );
}

#[test]
pub fn a_device_id_that_could_not_be_had_is_asked_for_again() {
    let mut rig = Rig::partner();
    let place = device_path(&rig.dir);
    std::fs::create_dir_all(&place).expect("a directory where the file should be");
    rig.add(LIST_URL);
    rig.call("refresh");
    fn last(rig: &Rig) -> super::super::links::Device {
        rig.world
            .lock()
            .expect("world")
            .devices
            .last()
            .cloned()
            .expect("a device")
    }
    assert!(
        last(&rig).id.is_empty() && last(&rig).os == "Linux",
        "{:?}",
        last(&rig)
    );
    assert_eq!(id_logs(&rig), 1);

    std::fs::remove_dir(&place).expect("the directory gone");
    std::os::unix::fs::symlink(&place, &place).expect("a self-referential link");
    rig.call("refresh");
    rig.call("refresh");
    assert!(last(&rig).id.is_empty(), "{:?}", last(&rig));
    assert_eq!(id_logs(&rig), 2);

    std::fs::remove_file(&place).expect("the link gone");
    rig.call("refresh");
    let id = read_device_id(&rig.dir).expect("an id");
    assert_eq!(last(&rig).id, id);
    rig.call("refresh");
    assert_eq!(last(&rig).id, id);
    assert_eq!(id_logs(&rig), 2);
}

pub fn id_logs(rig: &Rig) -> usize {
    rig.logs()
        .iter()
        .filter(|line| line.contains("device id:"))
        .count()
}

#[test]
pub fn connect_and_refresh_name_the_refused_lists() {
    let mut rig = Rig::partner();
    rig.world.lock().expect("world").refusal = Some(refusal(REFUSAL_LIMIT, LIMIT_SENTENCE));
    rig.add(LIST_URL);
    let why = format!("Partner: device refused: {LIMIT_SENTENCE}");
    let response = rig.connect("DE", "proxy");
    assert!(
        !response.ok
            && response.error == "no_match"
            && response.message.ends_with(&format!("\n{why}")),
        "{response:?}"
    );
    let response = rig.call("refresh");
    assert!(
        response.ok && response.message == format!("refreshed\n{why}"),
        "{response:?}"
    );
    rig.world.lock().expect("world").refusal = None;
    let response = rig.call("refresh");
    assert!(
        response.ok && response.message == "refreshed",
        "{response:?}"
    );
    let response = rig.connect("XX", "proxy");
    assert!(
        !response.ok
            && response.error == "no_match"
            && !response.message.contains("device refused"),
        "{response:?}"
    );
}

#[test]
pub fn a_selection_stays_in_its_list_while_its_host_refuses_the_device() {
    const URL_B: &str = "https://b.example/sub/2/y";
    let vless = |label: &str| format!("{VLESS_SOLO}\n").replacen("#solo", &format!("#{label}"), 1);
    for (name, selector) in [("by index", "3"), ("by label", "🇨🇦 CA · SJ")] {
        let mut rig = Rig::partner();
        engine::bodies_lock(&rig.world).bodies.insert(
            URL_B.to_string(),
            format!("{}{}{}", vless("B one"), vless("B two"), vless("B three")).into_bytes(),
        );
        rig.d.run();
        rig.add(LIST_URL);
        rig.add(URL_B);
        assert!(rig.connect(selector, "proxy").ok, "{name}");
        let first = rig.wait_state("up").line.expect("a line").clone();
        assert_eq!(first.label, "🇨🇦 CA · SJ");
        assert_eq!(first.carrier, "salutejazz");
        let stored = store::load(&rig.dir.join("config.yaml"))
            .expect("a config")
            .selection
            .expect("a selection");
        assert_eq!(stored.entry_id, first.id);
        assert_eq!(stored.entry_subscription, LIST_URL);

        engine::refusals_lock(&rig.world)
            .refusals
            .insert(LIST_URL.to_string(), refusal(REFUSAL_LIMIT, LIMIT_SENTENCE));
        engine::bodies_lock(&rig.world).bodies.insert(
            URL_B.to_string(),
            format!(
                "{}{}{}",
                vless("B one"),
                vless("B two"),
                vless("🇨🇦 CA · SJ")
            )
            .into_bytes(),
        );
        rig.call("refresh");
        let status = rig.wait_state("up");
        assert_eq!(status.line.expect("a line").id, first.id);

        rig.world.lock().expect("world").eng_state = "exited".to_string();
        let status = rig.wait_state("failed");
        assert_eq!(
            status.last_error,
            format!("device refused: {LIMIT_SENTENCE}")
        );
        rig.d.stop();

        rig.world.lock().expect("world").eng_state.clear();
        rig.restart();
        rig.d.run();
        let status = rig.wait_state("failed");
        assert_eq!(
            status.last_error,
            format!("device refused: {LIMIT_SENTENCE}")
        );
        assert!(!rig.events().contains(":vless"), "{}", rig.events());
        let again = store::load(&rig.dir.join("config.yaml"))
            .expect("a config")
            .selection
            .expect("a selection");
        assert_eq!(again, stored);

        engine::refusals_lock(&rig.world).refusals.remove(LIST_URL);
        rig.call("refresh");
        let status = rig.wait_state("up");
        assert_eq!(status.line.expect("a line").id, first.id);
        let _ = name;
    }
}

#[test]
pub fn scrub() {
    let key = "ab".repeat(32);
    let text = format!(
        "joined olcrtc://telemost?vp8channel@room#{key} from https://sub.x/sub/j7k9e/4gg96t28380ot6es?c=olcbox and https://proofkit.org/sub/abcdefghijklmnop/unified token=zzz"
    );
    let got = scrub_text(&text);
    for bad in [key.as_str(), "4gg96t28380ot6es", "abcdefghijklmnop", "zzz"] {
        assert!(!got.contains(bad), "{got}");
    }
    assert!(
        got.contains("/sub/j7k9e/…") && got.contains("/sub/…/unified") && got.contains("#<key>"),
        "{got}"
    );
    let uuid = scrub_text("invalid UUID: 9f4b2c3a-1111-4222-8333-444455556666 in line");
    assert!(!uuid.contains("9f4b2c3a"), "{uuid}");
    let panel = scrub_text("refresh https://panel.example/api/v1/subscribe/TOKEN123?x=1: Get \"https://panel.example/api/v1/subscribe/TOKEN123\": dial tcp: timeout");
    assert!(
        !panel.contains("TOKEN123") && panel.contains("https://panel.example/…"),
        "{panel}"
    );
}
