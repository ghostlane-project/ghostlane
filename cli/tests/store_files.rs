//! The state files. The files under `testdata/go` hold what the Go program's
//! own tests save (`internal/store/store_test.go`, and the configs the daemon
//! tests start from): each one loads into the values the Go test put in, and
//! is written back byte for byte.

mod common;

use std::collections::BTreeMap;
use std::fs;
use std::os::unix::fs::PermissionsExt as _;

use common::{fixture_text, state_dir, Scratch};
use ghostlane_cli::store::{
    self, Cache, Config, Headers, Proxy, Selection, Subscription, UserInfo,
};
use gocompat::time::Timestamp;

const LIST: &str = "https://s/sub/a/b?c=olcbox";
const PARTNER_LIST: &str = "https://sub.example/sub/a/b?c=olcbox";
const CACHED: &str = "https://s/sub/x";

fn config_of(name: &str) -> Config {
    Config::from_yaml(fixture_text(name).as_bytes()).unwrap_or_else(|err| panic!("{name}: {err}"))
}

/// What `TestConfigRoundTripAndPerms` saves.
fn round_trip_config() -> Config {
    Config {
        subscriptions: vec![Subscription {
            url: LIST.to_string(),
            title: "t".to_string(),
            interval_hours: 1,
            added_at: Timestamp::from_unix(1, 0),
        }],
        selection: Some(Selection {
            subscription: LIST.to_string(),
            selector: "DE".to_string(),
            mode: "tun".to_string(),
            ..Selection::default()
        }),
        proxy: Config::defaults().proxy,
    }
}

#[test]
fn the_go_tests_config_loads_and_is_written_back_the_same() {
    let config = config_of("config.yaml");
    assert_eq!(config, round_trip_config());
    assert_eq!(config.to_yaml(), fixture_text("config.yaml"));
}

#[test]
fn the_defaults_are_gos() {
    let defaults = Config::defaults();
    assert_eq!(
        defaults.proxy,
        Proxy {
            listen: "127.0.0.1".to_string(),
            port: 1080,
            ..Proxy::default()
        }
    );
    assert!(defaults.subscriptions.is_empty() && defaults.selection.is_none());
    assert_eq!(config_of("config-defaults.yaml"), defaults);
    assert_eq!(defaults.to_yaml(), fixture_text("config-defaults.yaml"));
}

#[test]
fn a_selection_with_the_kill_switch_keeps_it() {
    let config = config_of("config-kill-switch.yaml");
    let want = Config {
        subscriptions: vec![Subscription {
            url: PARTNER_LIST.to_string(),
            interval_hours: 1,
            ..Subscription::default()
        }],
        selection: Some(Selection {
            selector: "DE".to_string(),
            mode: "tun".to_string(),
            kill_switch: true,
            ..Selection::default()
        }),
        proxy: Config::defaults().proxy,
    };
    assert_eq!(config, want);
    assert!(config.subscriptions[0].added_at.is_zero());
    assert_eq!(config.to_yaml(), fixture_text("config-kill-switch.yaml"));
}

#[test]
fn a_line_given_by_hand_and_chosen_by_its_index_keeps_both() {
    let config = config_of("config-inline.yaml");
    let selection = config.selection.as_ref().expect("a selection");
    assert_eq!(selection.selector, "1");
    assert!(
        selection.subscription.starts_with("inline:")
            && selection.subscription.ends_with("$DE \u{b7} olcRTC")
    );
    assert_eq!(config.subscriptions[0].url, selection.subscription);
    assert_eq!(config.to_yaml(), fixture_text("config-inline.yaml"));
}

#[test]
fn every_field_survives_a_save() {
    let config = Config {
        subscriptions: vec![
            Subscription { url: LIST.to_string(), title: "Partner: fast & cheap".to_string(), interval_hours: 12, added_at: Timestamp::parse_rfc3339("2026-11-21T13:00:00.123456789+03:00").expect("a time") },
            Subscription { url: "inline:vless://id@192.0.2.1:443?type=tcp#one\nvless://id@192.0.2.2:443?type=tcp#two".to_string(), title: "\u{1f1e9}\u{1f1ea} DE".to_string(), interval_hours: 24, added_at: Timestamp::from_unix(1_795_266_000, 0) },
        ],
        selection: Some(Selection {
            subscription: LIST.to_string(),
            selector: "6".to_string(),
            mode: "tun".to_string(),
            kill_switch: true,
            entry_id: "a1b2".to_string(),
            entry_subscription: LIST.to_string(),
        }),
        proxy: Proxy { listen: "::1".to_string(), port: 1085, user: "u".to_string(), pass: "p: 1".to_string() },
    };
    let want = "\
subscriptions:
    - url: https://s/sub/a/b?c=olcbox
      title: 'Partner: fast & cheap'
      interval_hours: 12
      added_at: 2026-11-21T13:00:00.123456789+03:00
    - url: |-
        inline:vless://id@192.0.2.1:443?type=tcp#one
        vless://id@192.0.2.2:443?type=tcp#two
      title: \"\\U0001F1E9\\U0001F1EA DE\"
      interval_hours: 24
      added_at: 2026-11-21T13:00:00Z
selection:
    subscription: https://s/sub/a/b?c=olcbox
    selector: \"6\"
    mode: tun
    kill_switch: true
    entry_id: a1b2
    entry_subscription: https://s/sub/a/b?c=olcbox
proxy:
    listen: ::1
    port: 1085
    user: u
    pass: 'p: 1'
";
    assert_eq!(config.to_yaml(), want);
    assert_eq!(Config::from_yaml(want.as_bytes()), Ok(config));
}

#[test]
fn a_config_edited_by_hand_is_read() {
    let text = "\
# the proxy of this box
subscriptions:
  - url: \"https://s/sub/a/b?c=olcbox\"   # the list
    interval_hours: 24
    added_at: \"2026-01-02T03:04:05+03:00\"
proxy:
  listen: \"::1\"
  port: 1085
  user: \"\"
  pass: ~
something_else: [1, 2, {a: b}]
";
    let config = Config::from_yaml(text.as_bytes()).expect("a config edited by hand");
    assert_eq!(config.subscriptions.len(), 1);
    assert_eq!(config.subscriptions[0].url, LIST);
    assert_eq!(
        config.subscriptions[0].added_at.to_rfc3339(),
        "2026-01-02T03:04:05+03:00"
    );
    assert_eq!(
        config.proxy,
        Proxy {
            listen: "::1".to_string(),
            port: 1085,
            ..Proxy::default()
        }
    );
    assert_eq!(config.selection, None);
}

#[test]
fn an_empty_proxy_block_gets_the_defaults_and_a_wrong_one_is_refused() {
    let config = Config::from_yaml(b"proxy:\n    listen: \"\"\n    port: 0\n")
        .expect("an empty proxy block");
    assert_eq!(config, Config::defaults());
    assert_eq!(Config::from_yaml(b""), Ok(Config::defaults()));
    let refused =
        Config::from_yaml(b"proxy:\n    port: many\n").expect_err("a port that is no number");
    assert_eq!(
        refused.to_string(),
        "yaml: unmarshal errors:\n  line 2: cannot unmarshal !!str `many` into int"
    );
    let refused = Config::from_yaml(b"subscriptions: 5\n").expect_err("a list that is a number");
    assert_eq!(
        refused.to_string(),
        "yaml: unmarshal errors:\n  line 1: cannot unmarshal !!int `5` into []store.Subscription"
    );
}

/// `TestConfigRoundTripAndPerms`.
#[test]
fn a_config_is_saved_whole_for_its_owner_only() {
    let scratch = Scratch::create();
    let path = scratch.join("state").join("config.yaml");
    assert_eq!(
        store::load(&path).expect("no file is the defaults"),
        Config::defaults()
    );
    let config = round_trip_config();
    store::save(&path, &config).expect("the config is saved");
    let mode = fs::metadata(&path)
        .expect("the saved file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);
    assert_eq!(
        fs::read_to_string(&path).expect("the saved file"),
        fixture_text("config.yaml")
    );
    assert_eq!(store::load(&path).expect("the saved file loads"), config);
    assert!(
        !scratch.join("state").join("config.yaml.tmp").exists(),
        "temp file left behind"
    );
    // a file that was there with a wider mode is narrowed
    fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).expect("chmod");
    store::save(&path, &config).expect("the config is saved again");
    let mode = fs::metadata(&path)
        .expect("the saved file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);
}

#[test]
fn a_config_that_cannot_be_read_says_which_file() {
    let scratch = Scratch::create();
    // a directory where the file should be
    fs::create_dir_all(scratch.join("config.yaml")).expect("a directory");
    let refused = store::load(&scratch.join("config.yaml")).expect_err("a directory is no config");
    assert!(refused.to_string().contains("config.yaml: "), "{refused}");
}

/// `TestCacheAndLastGood`.
#[test]
fn the_go_tests_cache_loads_and_is_written_back_the_same() {
    let state = state_dir();
    assert_eq!(
        store::cache_path(state.path(), CACHED),
        state.join("lists").join("c489809199d82805.json")
    );
    let cache = store::load_cache(state.path(), CACHED)
        .expect("the cache is read")
        .expect("there is a cache");
    let want = Cache {
        body: Some("olcrtc://\u{2026}".as_bytes().to_vec()),
        headers: Headers {
            title: "T".to_string(),
            update_interval_hours: 3,
            ..Headers::default()
        },
        fetched_at: Timestamp::from_unix(5, 0),
    };
    assert_eq!(cache, want);
    let fresh = Scratch::create();
    assert_eq!(
        store::load_cache(fresh.path(), CACHED).expect("no cache is no error"),
        None
    );
    store::save_cache(fresh.path(), CACHED, &cache).expect("the cache is saved");
    let saved =
        fs::read_to_string(store::cache_path(fresh.path(), CACHED)).expect("the saved cache");
    assert_eq!(saved, fixture_text("lists/c489809199d82805.json"));
}

#[test]
fn the_record_of_a_refused_list_has_no_body_and_says_why() {
    let state = state_dir();
    let cache = store::load_cache(state.path(), PARTNER_LIST)
        .expect("the cache is read")
        .expect("there is a cache");
    assert_eq!(cache.body, None);
    assert_eq!(cache.headers.refusal, "limit");
    assert_eq!(cache.headers.title, "Partner");
    assert_eq!(
        cache.headers.announce,
        "Acme: this link is already in use on its 2 devices. Ask Acme to free one."
    );
    let fresh = Scratch::create();
    store::save_cache(fresh.path(), PARTNER_LIST, &cache).expect("the cache is saved");
    let saved =
        fs::read_to_string(store::cache_path(fresh.path(), PARTNER_LIST)).expect("the saved cache");
    assert_eq!(saved, fixture_text("lists/aab6ff902f89d36e.json"));
}

#[test]
fn a_cache_with_its_hosts_numbers_keeps_them() {
    let fresh = Scratch::create();
    let cache = Cache {
        body: Some(Vec::new()),
        headers: Headers {
            title: "<T>".to_string(),
            update_interval_hours: 24,
            user_info: Some(UserInfo {
                upload: 1,
                download: 2,
                total: 3,
                expire: 4,
            }),
            support_url: "https://support.example/".to_string(),
            web_page_url: "https://x.example/?a=1&b=2".to_string(),
            announce: "hello".to_string(),
            refusal: String::new(),
        },
        fetched_at: Timestamp::parse_rfc3339("2026-11-21T13:00:00.5+03:00").expect("a time"),
    };
    store::save_cache(fresh.path(), CACHED, &cache).expect("the cache is saved");
    let saved =
        fs::read_to_string(store::cache_path(fresh.path(), CACHED)).expect("the saved cache");
    let want = "{\"body\":\"\",\"headers\":{\"Title\":\"\\u003cT\\u003e\",\"UpdateIntervalHours\":24,\"UserInfo\":{\"Upload\":1,\"Download\":2,\"Total\":3,\"Expire\":4},\"SupportURL\":\"https://support.example/\",\"WebPageURL\":\"https://x.example/?a=1\\u0026b=2\",\"Announce\":\"hello\"},\"fetched_at\":\"2026-11-21T13:00:00.5+03:00\"}";
    assert_eq!(saved, want);
    assert_eq!(
        store::load_cache(fresh.path(), CACHED).expect("the cache is read"),
        Some(cache)
    );
}

/// `TestCorruptCacheIsAbsent`.
#[test]
fn a_corrupt_cache_counts_as_absent_and_is_removed() {
    let state = state_dir();
    let path = store::cache_path(state.path(), CACHED);
    fs::write(&path, "{not json").expect("the file is spoiled");
    assert_eq!(
        store::load_cache(state.path(), CACHED).expect("a corrupt cache is no error"),
        None
    );
    assert!(
        !path.exists(),
        "the corrupt file is removed so the next fetch replaces it"
    );
}

#[test]
fn the_go_tests_last_good_loads_and_is_written_back_the_same() {
    let state = state_dir();
    let last_good = store::load_last_good(state.path()).expect("last-good.json is read");
    let want: BTreeMap<String, String> = [("https://s/sub/x|DE".to_string(), "abc".to_string())]
        .into_iter()
        .collect();
    assert_eq!(last_good, want);
    let fresh = Scratch::create();
    assert!(store::load_last_good(fresh.path())
        .expect("no file is an empty map")
        .is_empty());
    store::save_last_good(fresh.path(), &last_good).expect("last-good.json is saved");
    assert_eq!(
        fs::read_to_string(fresh.join("last-good.json")).expect("the saved file"),
        fixture_text("last-good.json")
    );
    // sorted by key, as Go writes a map
    let mut more = last_good;
    more.insert("a|<1>".to_string(), "x".to_string());
    store::save_last_good(fresh.path(), &more).expect("last-good.json is saved");
    assert_eq!(
        fs::read_to_string(fresh.join("last-good.json")).expect("the saved file"),
        "{\"a|\\u003c1\\u003e\":\"x\",\"https://s/sub/x|DE\":\"abc\"}"
    );
    fs::write(fresh.join("last-good.json"), "[1]").expect("the file is spoiled");
    assert!(
        store::load_last_good(fresh.path()).is_err(),
        "a spoiled last-good.json is an error for the caller to log"
    );
}

/// `TestMaskURL`, and the shapes it does not name.
#[test]
fn a_list_url_is_never_shown_whole() {
    let cases = [
        (
            "https://sub.x.org/sub/j7k9e/4gg96t28380ot6es?c=olcbox",
            "https://sub.x.org/sub/j7k9e/\u{2026}?c=olcbox",
        ),
        (
            "https://proofkit.org/sub/abcdef/unified",
            "https://proofkit.org/sub/\u{2026}/unified",
        ),
        ("https://x.org/list.txt", "https://x.org/\u{2026}"),
        (
            "https://p.example/api/sub?token=abc123&c=olcbox",
            "https://p.example/\u{2026}?token=\u{2026}&c=olcbox",
        ),
        ("https://x.org/sub/abcdef", "https://x.org/sub/\u{2026}"),
        (
            "https://x.org/sub/abcdef/olcrtc",
            "https://x.org/sub/\u{2026}/olcrtc",
        ),
        (
            "https://user:secret@x.org:8443/sub/abcdef#frag",
            "https://x.org:8443/sub/\u{2026}",
        ),
        (
            "HTTPS://x.org/a/b?flag",
            "https://x.org/\u{2026}?flag=\u{2026}",
        ),
        (
            "inline:vless://id@192.0.2.1:443?type=tcp&security=reality#DE",
            "inline:///\u{2026}?type=\u{2026}&security=\u{2026}",
        ),
        ("https://x.org/%zz", "\u{2026}"),
        ("https://x.org/a\nb", "\u{2026}"),
        (":nothing", "\u{2026}"),
        ("", ":///\u{2026}"),
    ];
    for (url, want) in cases {
        assert_eq!(store::mask_url(url), want, "{url:?}");
    }
}
