//! The command line: the commands, the flags, the words and the exit codes
//! of the Go program (`cmd/ghostlane/main_test.go` is ported here test by
//! test, with the whole output where the Go test looks for a part of it).

mod common;

use std::collections::HashMap;
use std::fs;
use std::os::unix::fs::PermissionsExt as _;
use std::path::Path;
use std::process::{Child, Command, Output, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use common::{fixture_text, ghostlane, FakeDaemon, Scratch};
use ghostlane_cli::commands::{self, apply_proxy_env, default_state_dir};
use ghostlane_cli::ipc::{self, CallError, EntryView, ProxyView, Request, Response, Status};
use ghostlane_cli::store::{self, Config, Proxy, Selection};

const FLAG: &str = "\u{1f1e9}\u{1f1ea}";
const LIST: &str = "https://sub.example/sub/a/b?c=olcbox";

/// The daemon of the Go program's `TestStatusAndListRendering`.
fn rendering_daemon() -> FakeDaemon {
    FakeDaemon::start(|request: Request| match request.verb.as_str() {
        "status" => Response {
            ok: true,
            status: Some(Status {
                state: "up".to_string(),
                mode: "proxy".to_string(),
                selector: "DE".to_string(),
                since: "2026-09-30T10:00:00Z".to_string(),
                line: Some(EntryView {
                    label: format!("{FLAG} DE \u{b7} VP8"),
                    carrier: "telemost".to_string(),
                    country: "DE".to_string(),
                    ..EntryView::default()
                }),
                proxy: Some(ProxyView {
                    socks: "127.0.0.1:1080".to_string(),
                    http: "127.0.0.1:1080".to_string(),
                }),
                ..Status::default()
            }),
            ..Response::default()
        },
        "list" => Response {
            ok: true,
            entries: vec![
                EntryView {
                    index: 1,
                    label: format!("{FLAG} DE \u{b7} VP8"),
                    country: "DE".to_string(),
                    kind: "olcrtc".to_string(),
                    carrier: "telemost".to_string(),
                    ..EntryView::default()
                },
                EntryView {
                    index: 2,
                    label: "x".to_string(),
                    kind: "vless".to_string(),
                    problem: "VLESS lines come in the next version".to_string(),
                    ..EntryView::default()
                },
            ],
            ..Response::default()
        },
        "connect" => Response {
            ok: true,
            message: format!(
                "connecting to {} in {} mode",
                request.selector, request.mode
            ),
            ..Response::default()
        },
        verb => ipc::fail("bad_verb", verb),
    })
}

/// A daemon that answers with the answer line of a transcript, whatever it is asked.
fn transcript_daemon(name: &str) -> FakeDaemon {
    let text = fixture_text(&format!("ipc/{name}.txt"));
    let answer = text.lines().nth(1).expect("an answer line");
    let response: Response = serde_json::from_str(answer).expect("the answer of a transcript");
    FakeDaemon::start(move |_| response.clone())
}

#[test]
fn usage_and_exit_codes() {
    let nothing = ghostlane(&[]);
    assert_eq!(nothing.code, 2);
    assert!(
        nothing
            .stderr
            .starts_with("ghostlane: Ghostlane for Linux servers and boxes.\n"),
        "{}",
        nothing.stderr
    );
    assert!(
        nothing
            .stderr
            .contains("\nUsage:\n  ghostlane <command> [arguments] [flags]\n"),
        "{}",
        nothing.stderr
    );
    assert_eq!(nothing.stdout, "");

    let unknown = ghostlane(&["frobnicate"]);
    assert_eq!(unknown.code, 2);
    assert_eq!(
        unknown.stderr,
        format!("unknown command \"frobnicate\"\n{}", nothing.stderr)
    );

    let scratch = Scratch::create();
    let down = ghostlane(&["status", "--socket", &scratch.arg("none.sock")]);
    assert_eq!(down.code, 3);
    let want = format!(
        "the ghostlane daemon is not running (systemctl start ghostlane, or run `ghostlane run` where there is no systemd): dial unix {}: connect: no such file or directory\n",
        scratch.arg("none.sock")
    );
    assert_eq!(
        (down.stdout.as_str(), down.stderr.as_str()),
        ("", want.as_str())
    );
}

#[test]
fn version_names_the_build_and_the_release_key() {
    let version = ghostlane(&["version"]);
    let want = format!(
        "ghostlane {}\nengine unknown\ncrypt1 lists unavailable (no key in this build)\nrelease signing key sha256 4a6b2c2eda5bfd6c56a5d031f4e4be9cd5fe9c67a02e168dbe192c7caa0eec6a\n",
        commands::VERSION
    );
    assert_eq!(
        (
            version.code,
            version.stdout.as_str(),
            version.stderr.as_str()
        ),
        (0, want.as_str(), "")
    );

    let json = ghostlane(&["version", "--json"]);
    let want = format!("{{\n  \"version\": \"{}\",\n  \"engine\": \"unknown\",\n  \"singbox\": \"\",\n  \"xray\": \"\",\n  \"crypt1\": false\n}}\n", commands::VERSION);
    assert_eq!((json.code, json.stdout.as_str()), (0, want.as_str()));

    let wrong = ghostlane(&["version", "--yaml"]);
    assert_eq!(wrong.code, 2);
    assert_eq!(wrong.stderr, "flag provided but not defined: -yaml\nUsage of version:\n  -json\n    \tmachine-readable output\n");
}

#[test]
fn status_is_printed_as_the_go_program_prints_it() {
    let daemon = rendering_daemon();
    let status = ghostlane(&["status", "--socket", &daemon.socket()]);
    let want = format!(
        "State:     up\n\
         Selection: DE (proxy mode)\n\
         Line:      {FLAG} DE \u{b7} VP8 via telemost\n\
         Since:     2026-09-30T10:00:00Z\n\
         Proxy:     socks5://127.0.0.1:1080  http://127.0.0.1:1080\n  \
           export ALL_PROXY=socks5h://127.0.0.1:1080\n  \
           export http_proxy=http://127.0.0.1:1080 https_proxy=http://127.0.0.1:1080\n"
    );
    assert_eq!(
        (status.code, status.stdout.as_str(), status.stderr.as_str()),
        (0, want.as_str(), "")
    );

    let json = ghostlane(&["status", "--socket", &daemon.socket(), "--json"]);
    let want = format!(
        "{{\n  \"ok\": true,\n  \"status\": {{\n    \"state\": \"up\",\n    \"mode\": \"proxy\",\n    \"selector\": \"DE\",\n    \"since\": \"2026-09-30T10:00:00Z\",\n    \
         \"line\": {{\n      \"index\": 0,\n      \"id\": \"\",\n      \"label\": \"{FLAG} DE \u{b7} VP8\",\n      \"country\": \"DE\",\n      \"kind\": \"\",\n      \"carrier\": \"telemost\"\n    }},\n    \
         \"proxy\": {{\n      \"socks\": \"127.0.0.1:1080\",\n      \"http\": \"127.0.0.1:1080\"\n    }}\n  }}\n}}\n"
    );
    assert_eq!((json.code, json.stdout.as_str()), (0, want.as_str()));
}

#[test]
fn a_status_with_everything_in_it() {
    let daemon = transcript_daemon("status-full");
    let status = ghostlane(&["status", "--socket", &daemon.socket()]);
    let want = "\
State:     up
Selection: DE (tun mode, kill switch on)
Line:      DE \u{b7} VP8 via telemost
Since:     2026-09-30T10:00:00Z
Last error: the line before this one did not answer
Proxy:     socks5://127.0.0.1:1080  http://127.0.0.1:1080
  export ALL_PROXY=socks5h://127.0.0.1:1080
  export http_proxy=http://127.0.0.1:1080 https_proxy=http://127.0.0.1:1080
List:      https://sub.example/sub/a/\u{2026}?c=olcbox  (Partner)  next refresh 2026-09-30T10:00:00Z  used 3.5 of 10.0 GB
List:      inline:///\u{2026}  ERROR: device refused
";
    assert_eq!(
        (status.code, status.stdout.as_str(), status.stderr.as_str()),
        (0, want, "")
    );
}

/// `TestStatusShowsARefusedList`: the daemon's sentence, under ERROR.
#[test]
fn status_shows_a_refused_list() {
    let daemon = FakeDaemon::start(|_| Response {
        ok: true,
        status: Some(Status {
            state: "idle".to_string(),
            subscriptions: vec![ipc::SubscriptionView {
                url: "https://sub.example/sub/a/\u{2026}".to_string(),
                title: "Acme".to_string(),
                interval_hours: 1,
                error: "device refused: This list has no place for this device. Ask your provider."
                    .to_string(),
                ..ipc::SubscriptionView::default()
            }],
            ..Status::default()
        }),
        ..Response::default()
    });
    let status = ghostlane(&["status", "--socket", &daemon.socket()]);
    let want = "State:     idle\nList:      https://sub.example/sub/a/\u{2026}  (Acme)  ERROR: device refused: This list has no place for this device. Ask your provider.\n";
    assert_eq!((status.code, status.stdout.as_str()), (0, want));

    let nothing = FakeDaemon::start(|_| Response {
        ok: true,
        ..Response::default()
    });
    let status = ghostlane(&["status", "--socket", &nothing.socket()]);
    assert_eq!((status.code, status.stdout.as_str()), (0, "no status\n"));
}

#[test]
fn the_list_is_a_table_as_the_go_program_lays_it_out() {
    let daemon = rendering_daemon();
    let list = ghostlane(&["list", "--socket", &daemon.socket()]);
    let want = format!(
        "#    COUNTRY  KIND    CARRIER   LABEL        NOTE\n 1   DE       olcrtc  telemost  {FLAG} DE \u{b7} VP8  \n 2            vless             x            VLESS lines come in the next version\n"
    );
    assert_eq!(
        (list.code, list.stdout.as_str(), list.stderr.as_str()),
        (0, want.as_str(), "")
    );

    // flags before the command's argument, after it, with one dash and with two
    let socket = daemon.socket();
    let assigned = format!("--socket={socket}");
    for args in [
        ["list", "-socket", socket.as_str()],
        ["list", assigned.as_str(), "--json=false"],
    ] {
        let again = ghostlane(&args);
        assert_eq!(
            (again.code, again.stdout.as_str()),
            (0, want.as_str()),
            "{args:?}"
        );
    }
    let extra = ghostlane(&["list", "DE", "--socket", &daemon.socket()]);
    assert_eq!(
        (extra.code, extra.stdout.as_str(), extra.stderr.as_str()),
        (2, "", "list takes no argument\n")
    );
}

/// `TestListSaysWhyItIsEmpty`: an empty list that is empty because its host
/// refused the device says so, and does not tell the person to add the list
/// they have added.
#[test]
fn a_list_says_why_it_is_empty() {
    let why = "Partner: device refused: Acme: this link is already in use on its 2 devices. Ask Acme to free one.";
    let listing = |message: &'static str| {
        FakeDaemon::start(move |request: Request| match request.verb.as_str() {
            "list" => Response {
                ok: true,
                message: message.to_string(),
                ..Response::default()
            },
            verb => ipc::fail("bad_verb", verb),
        })
    };
    let refused = listing(why);
    let list = ghostlane(&["list", "--socket", &refused.socket()]);
    assert_eq!(
        (list.code, list.stdout.as_str()),
        (
            0,
            format!("#  COUNTRY  KIND  CARRIER  LABEL  NOTE\n{why}\n").as_str()
        )
    );
    let empty = listing("");
    let list = ghostlane(&["list", "--socket", &empty.socket()]);
    assert_eq!(
        (list.code, list.stdout.as_str()),
        (
            0,
            "#  COUNTRY  KIND  CARRIER  LABEL  NOTE\n(no lines; ghostlane add <url>)\n"
        )
    );
}

#[test]
fn connect_sends_the_selector_and_the_mode() {
    let asked = |request: Request| Response {
        ok: true,
        message: gocompat_line(&request),
        ..Response::default()
    };
    let daemon = FakeDaemon::start(asked);
    let socket = daemon.socket();
    let socket = socket.as_str();
    let cases: Vec<(Vec<&str>, &str)> = vec![
        (vec!["connect", "DE", "--tun", "--socket", socket], "{\"verb\":\"connect\",\"selector\":\"DE\",\"mode\":\"tun\"}"),
        (vec!["connect", "--socket", socket, "--tun", "--kill-switch", "DE"], "{\"verb\":\"connect\",\"selector\":\"DE\",\"mode\":\"tun\",\"kill_switch\":true}"),
        (vec!["connect", "6", "--proxy", "--socket", socket], "{\"verb\":\"connect\",\"selector\":\"6\",\"mode\":\"proxy\"}"),
        (vec!["connect", "DE \u{b7} SJ", "--socket", socket, "--subscription", "https://sub.example/sub/a/"], "{\"verb\":\"connect\",\"selector\":\"DE \u{b7} SJ\",\"subscription\":\"https://sub.example/sub/a/\"}"),
        (vec!["add", "--socket", socket, "https://sub.example/sub/a/b?c=olcbox&x=1"], "{\"verb\":\"add\",\"source\":\"https://sub.example/sub/a/b?c=olcbox\\u0026x=1\"}"),
        (vec!["remove", "Partner", "--socket", socket], "{\"verb\":\"remove\",\"subscription\":\"Partner\"}"),
        (vec!["refresh", "--socket", socket], "{\"verb\":\"refresh\"}"),
        (vec!["disconnect", "--socket", socket], "{\"verb\":\"disconnect\"}"),
    ];
    for (args, want) in cases {
        let done = ghostlane(&args);
        assert_eq!(
            (done.code, done.stdout.as_str(), done.stderr.as_str()),
            (0, format!("{want}\n").as_str(), ""),
            "{args:?}"
        );
    }

    let rendering = rendering_daemon();
    let connect = ghostlane(&["connect", "DE", "--tun", "--socket", &rendering.socket()]);
    assert_eq!(
        (connect.code, connect.stdout.as_str()),
        (0, "connecting to DE in tun mode\n")
    );
}

fn gocompat_line(request: &Request) -> String {
    String::from_utf8(gocompat::json::marshal(request).expect("a request")).expect("JSON is UTF-8")
}

#[test]
fn a_command_line_that_says_too_little_or_too_much_is_refused() {
    let daemon = rendering_daemon();
    let socket = daemon.socket();
    let socket = socket.as_str();
    let usage = ghostlane(&["help"]).stdout;
    let cases: Vec<(Vec<&str>, String)> = vec![
        (
            vec!["connect", "--socket", socket],
            format!("connect needs exactly one argument\n{usage}"),
        ),
        (
            vec!["connect", "DE", "FR", "--socket", socket],
            format!("connect needs exactly one argument\n{usage}"),
        ),
        (
            vec!["add", "--socket", socket],
            format!("add needs exactly one argument\n{usage}"),
        ),
        (
            vec!["remove", "--socket", socket],
            format!("remove needs exactly one argument\n{usage}"),
        ),
        (
            vec!["connect", "DE", "--tun", "--proxy", "--socket", socket],
            "pick one of --tun and --proxy\n".to_string(),
        ),
        (
            vec!["connect", "DE", "--kill-switch", "--socket", socket],
            "the kill switch needs --tun\n".to_string(),
        ),
    ];
    for (args, want) in cases {
        let refused = ghostlane(&args);
        assert_eq!(
            (
                refused.code,
                refused.stdout.as_str(),
                refused.stderr.as_str()
            ),
            (2, "", want.as_str()),
            "{args:?}"
        );
    }
}

#[test]
fn a_refusal_of_the_daemon_is_an_error_with_its_code() {
    let daemon = transcript_daemon("connect-refused");
    let refused = ghostlane(&["connect", "DE", "--socket", &daemon.socket()]);
    assert_eq!(
        (
            refused.code,
            refused.stdout.as_str(),
            refused.stderr.as_str()
        ),
        (
            1,
            "",
            "no_subscription: add a list first: ghostlane add <url>\n"
        )
    );
    let json = ghostlane(&["connect", "DE", "--json", "--socket", &daemon.socket()]);
    assert_eq!((json.code, json.stdout.as_str()), (1, ""));
}

#[test]
fn the_flags_are_gos() {
    let listed = "Usage of status:\n  -json\n    \tmachine-readable output\n  -kill-switch\n    \twith --tun: refuse traffic outside the tunnel while no line is up\n  -proxy\n    \tlocal SOCKS5/HTTP proxy only\n  -socket string\n    \tcontrol socket (default \"/run/ghostlane/ghostlane.sock\")\n  -subscription string\n    \trestrict to one subscription\n  -tun\n    \troute the whole machine (needs the service's CAP_NET_ADMIN)\n";
    let cases: Vec<(Vec<&str>, &str)> = vec![
        (
            vec!["status", "--nope"],
            "flag provided but not defined: -nope\n",
        ),
        (
            vec!["status", "--socket"],
            "flag needs an argument: -socket\n",
        ),
        (
            vec!["status", "--json=maybe"],
            "invalid boolean value \"maybe\" for -json: parse error\n",
        ),
        (vec!["status", "---json"], "bad flag syntax: ---json\n"),
        (vec!["status", "--=x"], "bad flag syntax: --=x\n"),
        // a call for help that is not the first argument: the flags, and no complaint
        (vec!["status", "--json", "-h"], ""),
    ];
    for (args, complaint) in cases {
        let refused = ghostlane(&args);
        assert_eq!(
            (
                refused.code,
                refused.stdout.as_str(),
                refused.stderr.as_str()
            ),
            (2, "", format!("{complaint}{listed}").as_str()),
            "{args:?}"
        );
    }
}

/// `TestHelpIsUsable`.
#[test]
fn help_is_usable() {
    let help = ghostlane(&["help"]);
    assert_eq!((help.code, help.stderr.as_str()), (0, ""));
    for want in [
        "Quick start",
        "connect DE --tun",
        "ghostlane add",
        "docs/cli.md",
        "help [command]",
        "Hysteria2",
    ] {
        assert!(
            help.stdout.contains(want),
            "help lacks {want:?}:\n{}",
            help.stdout
        );
    }
    for flag in ["-h", "--help"] {
        assert_eq!(ghostlane(&[flag]).stdout, help.stdout);
    }
    for command in [
        "add",
        "list",
        "connect",
        "status",
        "disconnect",
        "refresh",
        "remove",
        "version",
        "run",
    ] {
        let by_help = ghostlane(&["help", command]);
        assert_eq!(by_help.code, 0, "help {command}");
        assert!(
            by_help.stdout.starts_with(&format!("ghostlane {command}")),
            "help {command}:\n{}",
            by_help.stdout
        );
        for flag in ["--help", "-h"] {
            let by_flag = ghostlane(&[command, flag]);
            assert_eq!(
                (by_flag.code, by_flag.stdout.as_str()),
                (0, by_help.stdout.as_str()),
                "{command} {flag}"
            );
        }
    }
    let connect = ghostlane(&["help", "connect"]).stdout;
    for want in ["--tun", "--proxy", "--kill-switch", "Examples:"] {
        assert!(connect.contains(want), "the help of connect lacks {want:?}");
    }
    let unknown = ghostlane(&["help", "frobnicate"]);
    assert_eq!((unknown.code, unknown.stdout.as_str()), (2, ""));
    assert_eq!(
        unknown.stderr,
        format!("no help for \"frobnicate\"\n{}", help.stdout)
    );
}

/// `TestProxyEnv`: a container sets the proxy through the environment; a
/// listen that is not loopback needs credentials, or the proxy is open to the
/// network.
#[test]
fn the_proxy_is_set_through_the_environment() {
    let mut config = Config::defaults();
    let mut env: HashMap<&str, &str> = HashMap::new();
    let apply = |config: &mut Config, env: &HashMap<&str, &str>| {
        apply_proxy_env(config, &|name: &str| {
            env.get(name).copied().unwrap_or_default().to_string()
        })
    };
    assert_eq!(apply(&mut config, &env), Ok(false));
    assert_eq!(config, Config::defaults());

    env.insert("GHOSTLANE_PROXY_LISTEN", "0.0.0.0");
    let open = "a proxy on 0.0.0.0 is reachable from the network: set GHOSTLANE_PROXY_USER and GHOSTLANE_PROXY_PASS";
    assert_eq!(apply(&mut config, &env), Err(open.to_string()));

    env.insert("GHOSTLANE_PROXY_USER", "u");
    env.insert("GHOSTLANE_PROXY_PASS", "p");
    env.insert("GHOSTLANE_PROXY_PORT", "1085");
    assert_eq!(apply(&mut config, &env), Ok(true));
    assert_eq!(
        config.proxy,
        Proxy {
            listen: "0.0.0.0".to_string(),
            port: 1085,
            user: "u".to_string(),
            pass: "p".to_string()
        }
    );

    for port in ["nope", "0", "65536", "-1", "1e3", " 80"] {
        env.insert("GHOSTLANE_PROXY_PORT", port);
        assert_eq!(
            apply(&mut config, &env),
            Err(format!(
                "GHOSTLANE_PROXY_PORT must be a port number, not {port:?}"
            ))
        );
    }
    env.insert("GHOSTLANE_PROXY_PORT", "1080");
    env.insert("GHOSTLANE_PROXY_LISTEN", "localhost");
    let not_an_address = "GHOSTLANE_PROXY_LISTEN must be an IP address (127.0.0.1, ::1 or 0.0.0.0), not \"localhost\"";
    assert_eq!(apply(&mut config, &env), Err(not_an_address.to_string()));

    // loopback needs no credentials, in either family
    for listen in ["127.0.0.1", "127.9.9.9", "::1", "::ffff:127.0.0.1"] {
        let mut config = Config::defaults();
        let env: HashMap<&str, &str> = [("GHOSTLANE_PROXY_LISTEN", listen)].into_iter().collect();
        assert_eq!(apply(&mut config, &env), Ok(true), "{listen}");
        assert_eq!(config.proxy.listen, listen);
    }
    for listen in ["192.0.2.1", "::", "2001:db8::1"] {
        let mut config = Config::defaults();
        let env: HashMap<&str, &str> = [
            ("GHOSTLANE_PROXY_LISTEN", listen),
            ("GHOSTLANE_PROXY_USER", "u"),
        ]
        .into_iter()
        .collect();
        assert!(
            apply(&mut config, &env).is_err(),
            "{listen} with half the credentials"
        );
    }
}

/// `TestRunFlagChecks`.
#[test]
fn the_daemon_checks_its_flags_before_it_touches_anything() {
    let scratch = Scratch::create();
    let state = scratch.arg("state");
    let kill_switch = ghostlane(&[
        "run",
        "--state-dir",
        &state,
        "--connect",
        "DE",
        "--mode",
        "proxy",
        "--kill-switch",
    ]);
    assert_eq!(
        (kill_switch.code, kill_switch.stderr.as_str()),
        (2, "--kill-switch needs --mode tun\n")
    );
    let mode = ghostlane(&["run", "--state-dir", &state, "--mode", "sideways"]);
    assert_eq!(
        (mode.code, mode.stderr.as_str()),
        (2, "--mode must be tun or proxy, not \"sideways\"\n")
    );
    let flag = ghostlane(&["run", "--state-dir", &state, "--sideways"]);
    assert_eq!(flag.code, 2);
    assert!(flag.stderr.starts_with("flag provided but not defined: -sideways\nUsage of run:\n  -connect string\n    \tconnect this selector at start (containers)\n  -kill-switch\n"), "{}", flag.stderr);
    assert!(
        flag.stderr
            .contains("  -mode string\n    \ttun | proxy, with --connect (default \"proxy\")\n"),
        "{}",
        flag.stderr
    );
    assert!(
        !Path::new(&state).exists(),
        "a refused command line leaves no state directory"
    );
}

/// `TestDefaultStateDir`.
#[test]
fn the_state_directory_is_the_packages_unless_the_environment_names_one() {
    assert_eq!(default_state_dir(&|_| String::new()), "/var/lib/ghostlane");
    let image = |name: &str| {
        if name == "GHOSTLANE_STATE_DIR" {
            "/data".to_string()
        } else {
            String::new()
        }
    };
    assert_eq!(default_state_dir(&image), "/data");
}

/// The program itself, without what the environment of a test run may hold.
fn program() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_ghostlane"));
    for name in [
        "GHOSTLANE_STATE_DIR",
        "GHOSTLANE_PROXY_LISTEN",
        "GHOSTLANE_PROXY_PORT",
        "GHOSTLANE_PROXY_USER",
        "GHOSTLANE_PROXY_PASS",
    ] {
        command.env_remove(name);
    }
    command.stdin(Stdio::null());
    command
}

/// How a run of the program ended: its exit code and what it said on stderr.
fn ended(output: &Output) -> (Option<i32>, String) {
    (
        output.status.code(),
        String::from_utf8_lossy(&output.stderr).into_owned(),
    )
}

/// A daemon that runs for as long as the test holds it.
struct Running(Child);

impl Running {
    /// The exit code, once the program ended by itself within the time.
    fn exit_code(&mut self, within: Duration) -> Option<i32> {
        let until = Instant::now() + within;
        loop {
            match self.0.try_wait() {
                Ok(Some(status)) => return status.code(),
                Ok(None) if Instant::now() < until => thread::sleep(Duration::from_millis(20)),
                _ => return None,
            }
        }
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Asks until somebody answers: a daemon that was just started is not there yet.
fn ask(socket: &Path, verb: &str) -> Response {
    let request = Request {
        verb: verb.to_string(),
        ..Request::default()
    };
    let until = Instant::now() + Duration::from_secs(20);
    loop {
        match ipc::call(socket, &request) {
            Ok(response) => return response,
            Err(CallError::DaemonDown(_)) if Instant::now() < until => {
                thread::sleep(Duration::from_millis(20))
            }
            Err(err) => panic!("{verb}: {err}"),
        }
    }
}

/// A `ghostlane-engine` that answers `version` as the real one does.
fn stand_in_engine(dir: &Path, pin: &str) -> std::path::PathBuf {
    let program = dir.join("ghostlane-engine");
    fs::write(
        &program,
        format!(
            "#!/bin/sh\nprintf '%s\\n' '{{\"event\":\"version\",\"engine\":\"0.1.0\",\"protocol\":1,\"pin\":\"{pin}\",\"boringssl\":true,\"rooms\":true,\"target\":\"x86_64-unknown-linux-musl\"}}'\n"
        ),
    )
    .expect("the stand-in");
    fs::set_permissions(&program, fs::Permissions::from_mode(0o755)).expect("its mode");
    program
}

#[test]
fn version_names_the_core_the_engine_beside_it_says() {
    let scratch = Scratch::create();
    let pin = "0123456789abcdef0123456789abcdef01234567";
    let engine = stand_in_engine(scratch.path(), pin);

    let json = program()
        .args(["version", "--json"])
        .env("GHOSTLANE_ENGINE", &engine)
        .output()
        .expect("the program runs");
    assert_eq!(json.status.code(), Some(0));
    let said: serde_json::Value =
        serde_json::from_slice(&json.stdout).expect("version --json is JSON");
    assert_eq!(said["core"], pin, "{said}");

    let text = program()
        .arg("version")
        .env("GHOSTLANE_ENGINE", &engine)
        .output()
        .expect("the program runs");
    let text = String::from_utf8(text.stdout).expect("text");
    assert!(text.contains(&format!("core {pin}\n")), "{text}");

    // no engine: the line is not made up
    let none = program()
        .args(["version", "--json"])
        .env("GHOSTLANE_ENGINE", scratch.join("none"))
        .output()
        .expect("the program runs");
    let said: serde_json::Value = serde_json::from_slice(&none.stdout).expect("JSON");
    assert!(said.get("core").is_none(), "{said}");
}

#[test]
fn the_program_prints_its_version() {
    let output = program().arg("version").output().expect("the program runs");
    let text = String::from_utf8(output.stdout).expect("text");
    assert_eq!(output.status.code(), Some(0));
    assert_eq!(text, ghostlane(&["version"]).stdout);
    let output = program().output().expect("the program runs");
    assert_eq!(output.status.code(), Some(2));
    assert_eq!(
        String::from_utf8(output.stderr).expect("text"),
        ghostlane(&[]).stderr
    );
}

#[test]
fn the_daemon_serves_its_socket_until_it_is_told_to_stop() {
    let scratch = Scratch::create();
    let socket = scratch.join("run").join("ghostlane.sock");
    let (state, socket_arg) = (scratch.arg("state"), scratch.arg("run/ghostlane.sock"));
    let started = program()
        .args([
            "run",
            "--state-dir",
            state.as_str(),
            "--socket",
            socket_arg.as_str(),
        ])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    let mut daemon = Running(started.expect("the program starts"));

    let version = ask(&socket, "version");
    assert_eq!(
        version,
        Response {
            ok: true,
            version: Some(commands::version_info()),
            ..Response::default()
        }
    );
    let mode = fs::metadata(&socket)
        .expect("the socket file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o660);
    // what connects a line is not in this program yet, and it says so
    for verb in [
        "status",
        "list",
        "add",
        "connect",
        "disconnect",
        "refresh",
        "remove",
    ] {
        let answer = ask(&socket, verb);
        assert_eq!(
            (answer.ok, answer.error.as_str()),
            (false, "no_engine"),
            "{verb}"
        );
        assert!(
            answer.message.starts_with(&format!("{verb}: ")),
            "{verb}: {}",
            answer.message
        );
    }
    let unknown = ask(&socket, "frobnicate");
    assert_eq!(
        (unknown.ok, unknown.error.as_str(), unknown.message.as_str()),
        (false, "bad_verb", "unknown verb frobnicate")
    );
    // the command line of this program against its own daemon
    let status = ghostlane(&["status", "--socket", &socket_arg]);
    assert_eq!(status.code, 1);
    assert!(
        status.stderr.starts_with("no_engine: status: "),
        "{}",
        status.stderr
    );
    // no config is written by a start that was given nothing to write
    assert!(!scratch.join("state").join("config.yaml").exists());

    let pid = daemon.0.id().to_string();
    let signalled = Command::new("kill")
        .args(["-TERM", pid.as_str()])
        .status()
        .expect("kill runs");
    assert!(signalled.success());
    assert_eq!(
        daemon.exit_code(Duration::from_secs(20)),
        Some(0),
        "a daemon told to stop ends with 0"
    );
    assert!(
        !socket.exists(),
        "the socket file is removed on the way out"
    );
}

#[test]
fn the_daemon_writes_what_its_arguments_say_before_it_starts() {
    let scratch = Scratch::create();
    // a file where the socket's directory would be: the start ends there, after the config is written
    fs::write(scratch.join("blocker"), "").expect("a file");
    let (state, socket) = (scratch.arg("state"), scratch.arg("blocker/ghostlane.sock"));
    let (state, socket) = (state.as_str(), socket.as_str());
    let stopped = format!(
        "control socket: mkdir {}: not a directory\n",
        scratch.arg("blocker")
    );

    let before = gocompat::time::Timestamp::now().unix();
    let args = [
        "run",
        "--state-dir",
        state,
        "--socket",
        socket,
        "--subscription",
        LIST,
        "--connect",
        "DE",
        "--mode",
        "tun",
        "--kill-switch",
    ];
    let output = program()
        .args(args)
        .env("GHOSTLANE_PROXY_PORT", "1085")
        .output()
        .expect("the program runs");
    assert_eq!(ended(&output), (Some(1), stopped));
    let config_path = scratch.join("state").join("config.yaml");
    let config = store::load(&config_path).expect("the config that was written");
    assert_eq!(config.subscriptions.len(), 1);
    let list = &config.subscriptions[0];
    assert_eq!(
        (list.url.as_str(), list.title.as_str(), list.interval_hours),
        (LIST, "", 24)
    );
    assert!(
        (before..=before + 600).contains(&list.added_at.unix()),
        "added at {}",
        list.added_at
    );
    assert_eq!(
        config.selection,
        Some(Selection {
            subscription: LIST.to_string(),
            selector: "DE".to_string(),
            mode: "tun".to_string(),
            kill_switch: true,
            ..Selection::default()
        })
    );
    assert_eq!(
        config.proxy,
        Proxy {
            listen: "127.0.0.1".to_string(),
            port: 1085,
            ..Proxy::default()
        }
    );
    let mode = fs::metadata(&config_path)
        .expect("the config file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);

    // the same list again is not added twice, and without --connect the selection stands
    let written = fs::read_to_string(&config_path).expect("the config file");
    let output = program()
        .args([
            "run",
            "--state-dir",
            state,
            "--socket",
            socket,
            "--subscription",
            LIST,
        ])
        .output()
        .expect("the program runs");
    assert_eq!(output.status.code(), Some(1));
    assert_eq!(
        fs::read_to_string(&config_path).expect("the config file"),
        written
    );

    // what the environment asks and the program refuses, it refuses before it writes
    let output = program()
        .args(["run", "--state-dir", state, "--socket", socket])
        .env("GHOSTLANE_PROXY_LISTEN", "0.0.0.0")
        .output()
        .expect("the program runs");
    let open = "a proxy on 0.0.0.0 is reachable from the network: set GHOSTLANE_PROXY_USER and GHOSTLANE_PROXY_PASS\n";
    assert_eq!(ended(&output), (Some(2), open.to_string()));
    assert_eq!(
        fs::read_to_string(&config_path).expect("the config file"),
        written
    );

    // a config that cannot be read stops the start, with yaml.v3's words for it
    fs::write(&config_path, "proxy:\n    port: many\n").expect("the config is spoiled");
    let output = program()
        .args(["run", "--state-dir", state, "--socket", socket])
        .output()
        .expect("the program runs");
    let unread = "yaml: unmarshal errors:\n  line 2: cannot unmarshal !!str `many` into int\n";
    assert_eq!(ended(&output), (Some(1), unread.to_string()));
}
