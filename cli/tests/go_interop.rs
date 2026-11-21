//! This program and the Go program side by side: the same command lines, the
//! same files, and each one's client asking the other one's daemon.
//!
//! The tests run where the Go program was built and `GHOSTLANE_GO_BIN` names
//! it (the second job of rust-cli.yml). Anywhere else they have nothing to
//! compare with and pass at once, unless `GHOSTLANE_GO_REQUIRED` is set.
//!
//! Nothing here connects a line: the Go daemon is given no list to fetch and
//! is never asked to connect, and the lines it is given by hand are on
//! addresses reserved for documentation.

mod common;

use std::fmt::Debug;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use common::{fixture_text, FakeDaemon, Scratch};
use ghostlane_cli::ipc::{self, CallError, Request, Response};
use ghostlane_cli::store::Config;

/// The Go program, where there is one to compare with.
fn go_program() -> Option<PathBuf> {
    let named = std::env::var_os("GHOSTLANE_GO_BIN").filter(|path| !path.is_empty());
    let Some(path) = named.map(PathBuf::from) else {
        let required = std::env::var_os("GHOSTLANE_GO_REQUIRED").is_some();
        assert!(
            !required,
            "GHOSTLANE_GO_REQUIRED is set and GHOSTLANE_GO_BIN is not"
        );
        eprintln!("nothing compared: GHOSTLANE_GO_BIN names no Go program");
        return None;
    };
    assert!(
        path.is_file(),
        "GHOSTLANE_GO_BIN names no file: {}",
        path.display()
    );
    Some(path)
}

fn this_program() -> &'static Path {
    Path::new(env!("CARGO_BIN_EXE_ghostlane"))
}

/// What a run of a program left behind.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Run {
    code: Option<i32>,
    stdout: String,
    stderr: String,
}

fn command(program: &Path, args: &[&str], env: &[(&str, &str)]) -> Command {
    let mut command = Command::new(program);
    command.args(args).stdin(Stdio::null());
    for name in [
        "GHOSTLANE_STATE_DIR",
        "GHOSTLANE_PROXY_LISTEN",
        "GHOSTLANE_PROXY_PORT",
        "GHOSTLANE_PROXY_USER",
        "GHOSTLANE_PROXY_PASS",
        "NOTIFY_SOCKET",
    ] {
        command.env_remove(name);
    }
    for (name, value) in env {
        command.env(name, value);
    }
    command
}

/// Runs a command line to its end. One that does not end within a minute
/// (a daemon that was not meant to start) is stopped, and has no exit code.
fn run(program: &Path, args: &[&str], env: &[(&str, &str)]) -> Run {
    let mut child = command(program, args, env)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("the program starts");
    let until = Instant::now() + Duration::from_secs(60);
    let ended = loop {
        match child.try_wait() {
            Ok(Some(_)) => break true,
            Ok(None) if Instant::now() < until => thread::sleep(Duration::from_millis(5)),
            _ => break false,
        }
    };
    if !ended {
        let _ = child.kill();
    }
    let output = child.wait_with_output().expect("what the program printed");
    Run {
        code: output.status.code().filter(|_| ended),
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
    }
}

/// Every difference of a test, so that one run shows them all.
#[derive(Default)]
struct Differences(Vec<String>);

impl Differences {
    fn same<T: PartialEq + Debug>(&mut self, what: &str, go: &T, rust: &T) {
        if go != rust {
            self.0.push(format!(
                "{what}\n  the Go program: {go:?}\n  this program:   {rust:?}"
            ));
        }
    }

    fn none(self) {
        assert!(
            self.0.is_empty(),
            "{} differences:\n{}",
            self.0.len(),
            self.0.join("\n")
        );
    }
}

/// A daemon that runs for as long as the test holds it.
struct Running(Child);

impl Running {
    /// Starts it with what it says on stderr going to the file `log`.
    fn start(program: &Path, args: &[&str], log: &Path) -> Running {
        let log = fs::File::create(log).expect("a file for what the daemon says");
        let started = command(program, args, &[])
            .stdout(Stdio::null())
            .stderr(log)
            .spawn();
        Running(started.expect("the daemon starts"))
    }

    /// Asks it to stop as systemd does, and says how it ended.
    fn stop(mut self) -> Option<i32> {
        let pid = self.0.id().to_string();
        let signalled = Command::new("kill")
            .args(["-TERM", pid.as_str()])
            .status()
            .expect("kill runs");
        assert!(signalled.success());
        let until = Instant::now() + Duration::from_secs(30);
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

/// Asks until somebody answers: a daemon that was just started is not there
/// yet. One that never answers has said why in `log`.
fn ask(socket: &Path, verb: &str, log: &Path) -> Response {
    let request = Request {
        verb: verb.to_string(),
        ..Request::default()
    };
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        match ipc::call(socket, &request) {
            Ok(response) => return response,
            Err(CallError::DaemonDown(_)) if Instant::now() < until => {
                thread::sleep(Duration::from_millis(50))
            }
            Err(err) => panic!(
                "{verb}: {err}\nthe daemon said:\n{}",
                fs::read_to_string(log).unwrap_or_default()
            ),
        }
    }
}

/// How a start that cannot open its socket ended, and the config it left.
#[derive(Debug, PartialEq, Eq)]
struct Prepared {
    code: Option<i32>,
    /// What it said on stderr, the directory of the test written as DIR.
    stderr: String,
    /// The last line of that: the Go program logs its start before it.
    last_words: String,
    config: Option<String>,
}

/// Starts `run` on a state directory that holds `config`, with a file where
/// the socket's directory would be: both programs get as far as writing the
/// config and stop at the socket.
fn prepare(program: &Path, config: Option<&str>, args: &[&str], env: &[(&str, &str)]) -> Prepared {
    let scratch = Scratch::create();
    let state = scratch.join("state");
    fs::create_dir_all(&state).expect("the state directory");
    if let Some(text) = config {
        fs::write(state.join("config.yaml"), text).expect("the config to start from");
    }
    fs::write(scratch.join("blocker"), "").expect("a file");
    let (state_arg, socket_arg) = (scratch.arg("state"), scratch.arg("blocker/ghostlane.sock"));
    let mut all = vec![
        "run",
        "--state-dir",
        state_arg.as_str(),
        "--socket",
        socket_arg.as_str(),
    ];
    all.extend_from_slice(args);
    let ended = run(program, &all, env);
    let here = scratch.path().to_str().expect("a path that is text");
    let stderr = ended.stderr.replace(here, "DIR");
    Prepared {
        code: ended.code,
        last_words: stderr.lines().last().unwrap_or_default().to_string(),
        stderr,
        config: fs::read_to_string(state.join("config.yaml")).ok(),
    }
}

/// What is compared of a start that got as far as the socket: how it ended,
/// its last words and the config it left. The `added_at` of a list that was
/// added by the start itself is the moment of the test: where `fresh` says
/// there is one (the last list), it is written out of the comparison.
fn outcome(prepared: &Prepared, fresh: bool) -> (Option<i32>, String, Option<String>) {
    let config = prepared.config.as_ref().map(|text| {
        let last = text.rfind("added_at: ").filter(|_| fresh);
        match last {
            Some(at) => {
                let end = text[at..]
                    .find('\n')
                    .map_or(text.len(), |length| at + length);
                format!(
                    "{}added_at: (the moment of the test){}",
                    &text[..at],
                    &text[end..]
                )
            }
            None => text.clone(),
        }
    });
    (prepared.code, prepared.last_words.clone(), config)
}

const BY_HAND: &str = "\
# edited by hand
subscriptions:
  - url: \"https://sub.example/sub/a/b?c=olcbox\"   # the list
    title: 'Partner: fast'
    interval_hours: 0x18
    added_at: 2026-01-02 03:04:05
  - {url: 'inline:hysteria2://secret@192.0.2.1:443#one', interval_hours: 2.0, added_at: \"2026-01-02T03:04:05.5+03:00\"}
  - url: |
      inline:hysteria2://secret@192.0.2.2:443#two
      hysteria2://secret@192.0.2.3:443#three
    title: yes
    added_at: 2026-01-02
  - url: https://sub.example/sub/c/d
    title: ~
    interval_hours:
    added_at: 2026-1-2t3:4:5Z
selection: &chosen
  subscription: https://sub.example/sub/a/b?c=olcbox
  selector: \"6\"
  mode: tun
  kill_switch: on
  entry_id: !!str 123
unknown: [1, 2, {deep: *chosen}]
proxy:
  listen: \"::1\"
  port: 1_085
  user: \"\"
  pass: ~
";

const WITH_A_MERGE: &str = "\
defaults: &defaults
  mode: proxy
  selector: DE
  kill_switch: false
subscriptions: []
selection:
  <<: *defaults
  subscription: \"\"
  selector: \"FR \\U0001F1EB\\U0001F1F7\"
proxy: {listen: 127.0.0.1, port: 1080.0, user: \"a: b\", pass: \"#1\"}
";

/// A start of `run`: the config it finds, its arguments and its environment.
struct Start<'a> {
    what: String,
    config: Option<&'a str>,
    args: Vec<&'a str>,
    env: Vec<(&'a str, &'a str)>,
}

#[test]
fn both_programs_leave_the_same_config() {
    let Some(go) = go_program() else {
        return;
    };
    let mut differences = Differences::default();
    let fixtures: Vec<(String, String)> = [
        "config.yaml",
        "config-defaults.yaml",
        "config-kill-switch.yaml",
        "config-inline.yaml",
    ]
    .into_iter()
    .map(|name| (name.to_string(), fixture_text(name)))
    .collect();
    let credentials = [
        ("GHOSTLANE_PROXY_LISTEN", "0.0.0.0"),
        ("GHOSTLANE_PROXY_USER", "user: \u{1f511}"),
        ("GHOSTLANE_PROXY_PASS", "p#ss w\u{f6}rd "),
    ];
    let windows = BY_HAND.replace('\n', "\r\n");
    let marked = format!("\u{feff}{BY_HAND}");
    let mut cases: Vec<Start<'_>> = Vec::new();
    for (name, text) in &fixtures {
        let text = text.as_str();
        // the config is read, the port is changed, the config is written
        cases.push(Start {
            what: format!("{name}, another port"),
            config: Some(text),
            args: vec![],
            env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
        });
        cases.push(Start {
            what: format!("{name}, credentials"),
            config: Some(text),
            args: vec![],
            env: credentials.to_vec(),
        });
        // a list that is there already is not added again, and the selection is replaced
        let known = if name == "config.yaml" {
            "https://s/sub/a/b?c=olcbox"
        } else {
            "https://sub.example/sub/a/b?c=olcbox"
        };
        cases.push(Start {
            what: format!("{name}, a selection"),
            config: Some(text),
            args: vec![
                "--subscription",
                known,
                "--connect",
                "FR",
                "--mode",
                "tun",
                "--kill-switch",
            ],
            env: vec![],
        });
        cases.push(Start {
            what: format!("{name}, the list again"),
            config: Some(text),
            args: vec!["--subscription", known],
            env: vec![],
        });
        cases.push(Start {
            what: format!("{name}, a line of its own"),
            config: Some(text),
            args: vec![
                "--subscription",
                "inline:hysteria2://secret@192.0.2.9:443#\u{1f1e9}\u{1f1ea} DE \u{b7} HY2",
                "--connect",
                "1",
            ],
            env: vec![],
        });
        // nothing to write: the file stays as it is
        cases.push(Start {
            what: format!("{name}, nothing asked"),
            config: Some(text),
            args: vec![],
            env: vec![],
        });
    }
    cases.push(Start {
        what: "no config, another proxy".to_string(),
        config: None,
        args: vec![],
        env: vec![
            ("GHOSTLANE_PROXY_LISTEN", "::1"),
            ("GHOSTLANE_PROXY_PORT", "9"),
        ],
    });
    cases.push(Start {
        what: "no config, a list".to_string(),
        config: None,
        args: vec![
            "--subscription",
            "https://sub.example/sub/a/b?c=olcbox",
            "--connect",
            "DE",
        ],
        env: vec![],
    });
    cases.push(Start {
        what: "no config, nothing asked".to_string(),
        config: None,
        args: vec![],
        env: vec![],
    });
    cases.push(Start {
        what: "an empty config".to_string(),
        config: Some(""),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    cases.push(Start {
        what: "a config of comments".to_string(),
        config: Some("# nothing yet\n---\n"),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    cases.push(Start {
        what: "a config edited by hand".to_string(),
        config: Some(BY_HAND),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    cases.push(Start {
        what: "a config edited by hand, credentials".to_string(),
        config: Some(BY_HAND),
        args: vec![
            "--subscription",
            "https://sub.example/sub/c/d",
            "--connect",
            "\u{1f1e9}\u{1f1ea} DE \u{b7} SJ",
        ],
        env: credentials.to_vec(),
    });
    cases.push(Start {
        what: "a config with a merge".to_string(),
        config: Some(WITH_A_MERGE),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    cases.push(Start {
        what: "a config with the line ends of another system".to_string(),
        config: Some(windows.as_str()),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    cases.push(Start {
        what: "a config behind a byte order mark".to_string(),
        config: Some(marked.as_str()),
        args: vec![],
        env: vec![("GHOSTLANE_PROXY_PORT", "1085")],
    });
    for Start {
        what,
        config,
        args,
        env,
    } in &cases
    {
        // a list the start is given and the config does not hold is added at the moment of the test
        let given = args
            .iter()
            .position(|arg| *arg == "--subscription")
            .map(|at| args[at + 1]);
        let fresh = given.is_some_and(|list| !config.unwrap_or_default().contains(list));
        let by_go = prepare(&go, *config, args, env);
        let by_rust = prepare(this_program(), *config, args, env);
        differences.same(what, &outcome(&by_go, fresh), &outcome(&by_rust, fresh));
        // each start got as far as the socket
        if by_go.last_words != "control socket: mkdir DIR/blocker: not a directory" {
            differences.0.push(format!(
                "{what}: the Go program stopped elsewhere: {by_go:?}"
            ));
        }
        // and what this program wrote, it reads
        if let Some(Err(err)) = by_rust
            .config
            .as_ref()
            .map(|text| Config::from_yaml(text.as_bytes()))
        {
            differences.0.push(format!(
                "{what}: this program does not read what it wrote: {err}"
            ));
        }
    }
    differences.none();
}

#[test]
fn both_programs_refuse_the_same_config_in_the_same_words() {
    let Some(go) = go_program() else {
        return;
    };
    let mut differences = Differences::default();
    let spoiled = [
        "proxy:\n    port: many\n",
        "proxy:\n    port: \"1080\"\n",
        "proxy:\n    port: [1080]\n",
        "proxy:\n    listen: {a: b}\n",
        "proxy: 5\n",
        "proxy: [a]\n",
        "subscriptions: 5\n",
        "subscriptions: {a: b}\n",
        "subscriptions:\n  - 5\n  - url: [x]\n    interval_hours: soon\n    added_at: [1]\n",
        "selection: yes\n",
        "selection:\n  kill_switch: \"true\"\n  mode: [tun]\n",
        "selection:\n  kill_switch: 1\n",
        "- a\n- b\n",
        "just a word\n",
        "proxy:\n    port: 1080\n    port: 1085\n",
        "selection:\n  <<: 5\n",
        "proxy:\n    port: !!int many\n",
        "proxy:\n    port: !!float 1e3\n    listen: !!binary '*'\n",
        "proxy:\n    port: 99999999999999999999\n",
        "proxy:\n    port: 1.5e300\n",
        "proxy:\n    user: a very long value that is not a number\n    port: a very long value that is not a number\n",
    ];
    for config in spoiled {
        let by_go = prepare(&go, Some(config), &[], &[]);
        let by_rust = prepare(this_program(), Some(config), &[], &[]);
        differences.same(&format!("{config:?}"), &by_go, &by_rust);
        if by_go.code != Some(1) || by_go.config.as_deref() != Some(config) {
            differences.0.push(format!(
                "{config:?}: the Go program did not refuse it: {by_go:?}"
            ));
        }
    }
    // a time that is none: the sentence is Go's, which goes on to say where the reading stopped
    for config in [
        "subscriptions:\n  - url: a\n    added_at: soon\n",
        "subscriptions:\n  - url: a\n    added_at: \"2026-01-02\"\n",
        "subscriptions:\n  - added_at: 5\n",
    ] {
        let by_go = prepare(&go, Some(config), &[], &[]);
        let by_rust = prepare(this_program(), Some(config), &[], &[]);
        let begins_alike = by_go.stderr.starts_with(by_rust.stderr.trim_end())
            && by_rust.stderr.starts_with("parsing time ");
        if !begins_alike || (by_go.code, &by_go.config) != (by_rust.code, &by_rust.config) {
            differences.0.push(format!(
                "{config:?}\n  the Go program: {by_go:?}\n  this program:   {by_rust:?}"
            ));
        }
    }
    differences.none();
}

#[test]
fn both_programs_read_a_command_line_alike() {
    let Some(go) = go_program() else {
        return;
    };
    let mut differences = Differences::default();
    let scratch = Scratch::create();
    let (missing, state) = (scratch.arg("missing.sock"), scratch.arg("state"));
    // what was understood comes back as the answer's message
    let echo = FakeDaemon::start(|request: Request| {
        let understood = String::from_utf8(gocompat::json::marshal(&request).expect("a request"))
            .expect("JSON is UTF-8");
        Response {
            ok: true,
            message: understood,
            ..Response::default()
        }
    });
    let socket = echo.socket();
    let (missing, state, socket) = (missing.as_str(), state.as_str(), socket.as_str());
    let lines: Vec<Vec<&str>> = vec![
        vec!["status", "--socket", missing],
        vec!["list", "-socket", missing, "--json"],
        vec!["status", "--nope"],
        vec!["status", "--socket"],
        vec!["status", "--json=maybe"],
        vec!["status", "---json"],
        vec!["status", "--=x"],
        vec!["status", "--json", "-h"],
        vec!["status", "--json", "--help"],
        vec!["list", "extra", "--socket", socket],
        vec!["connect", "DE", "--tun", "--proxy", "--socket", socket],
        vec!["connect", "DE", "--kill-switch", "--socket", socket],
        vec!["connect", "DE", "--tun", "--socket", socket],
        vec![
            "connect",
            "--tun",
            "--kill-switch",
            "--socket",
            socket,
            "DE",
        ],
        vec![
            "connect",
            "--tun=false",
            "--proxy=T",
            "DE \u{b7} SJ",
            "--socket",
            socket,
        ],
        vec![
            "connect",
            "6",
            "--socket",
            socket,
            "--subscription",
            "https://sub.example/sub/a/",
        ],
        vec!["connect", "--socket", socket, "--", "--tun"],
        vec!["connect", "-", "--socket", socket],
        vec!["connect", "", "--socket", socket],
        vec![
            "add",
            "--socket",
            socket,
            "https://sub.example/sub/a/b?c=olcbox&x=<1>",
        ],
        vec!["add", "two\nlines \u{1f1e9}\u{1f1ea}", "--socket", socket],
        vec!["remove", "Partner", "--socket", socket],
        vec!["remove", "--subscription", "a", "b", "--socket", socket],
        vec!["refresh", "--socket", socket, "--subscription=x"],
        vec!["disconnect", "--socket", socket, "--tun", "--kill-switch"],
        vec!["status", "--socket", socket, "--json"],
        vec!["list", "--socket", socket, "--json"],
        vec!["run", "--mode", "sideways"],
        vec!["run", "--kill-switch"],
        vec!["run", "--connect", "DE", "--mode", "proxy", "--kill-switch"],
        vec!["run", "--sideways"],
        vec!["run", "--mode"],
        vec!["run", "--kill-switch=perhaps"],
        vec!["version", "--nope"],
        vec!["version", "--json=0", "--help"],
    ];
    for args in &lines {
        differences.same(
            &format!("{args:?}"),
            &run(&go, args, &[]),
            &run(this_program(), args, &[]),
        );
    }
    // the usage that follows these is each program's own text: the first line is compared
    let first_lines: Vec<Vec<&str>> = vec![
        vec!["frobnicate"],
        vec!["help", "frobnicate"],
        vec!["connect", "--socket", socket],
        vec!["connect", "DE", "FR", "--socket", socket],
        vec!["add", "--socket", socket],
        vec!["remove", "--socket", socket],
    ];
    for args in &first_lines {
        let first_line = |ended: Run| Run {
            stderr: ended.stderr.lines().next().unwrap_or_default().to_string(),
            ..ended
        };
        differences.same(
            &format!("{args:?}"),
            &first_line(run(&go, args, &[])),
            &first_line(run(this_program(), args, &[])),
        );
    }
    // what the environment says of the proxy is checked before anything is touched
    let refused: Vec<Vec<(&str, &str)>> = vec![
        vec![("GHOSTLANE_PROXY_PORT", "nope")],
        vec![("GHOSTLANE_PROXY_PORT", "0")],
        vec![("GHOSTLANE_PROXY_PORT", "65536")],
        vec![("GHOSTLANE_PROXY_LISTEN", "localhost")],
        vec![("GHOSTLANE_PROXY_LISTEN", "0.0.0.0")],
        vec![
            ("GHOSTLANE_PROXY_LISTEN", "::"),
            ("GHOSTLANE_PROXY_USER", "u"),
        ],
    ];
    for env in &refused {
        let args = ["run", "--state-dir", state, "--socket", missing];
        differences.same(
            &format!("{env:?}"),
            &run(&go, &args, env),
            &run(this_program(), &args, env),
        );
    }
    differences.none();
}

#[test]
fn both_clients_print_the_same_for_the_same_answer() {
    let Some(go) = go_program() else {
        return;
    };
    let mut differences = Differences::default();
    for (name, commands) in [
        (
            "status-full",
            vec![vec!["status"], vec!["status", "--json"]],
        ),
        ("status", vec![vec!["status"], vec!["status", "--json"]]),
        ("list", vec![vec!["list"], vec!["list", "--json"]]),
        (
            "add",
            vec![
                vec!["add", "x"],
                vec!["add", "x", "--json"],
                vec!["status"],
                vec!["list"],
            ],
        ),
        (
            "connect-refused",
            vec![
                vec!["connect", "DE"],
                vec!["connect", "DE", "--json"],
                vec!["status"],
            ],
        ),
        (
            "unknown-verb",
            vec![vec!["refresh"], vec!["list", "--json"]],
        ),
        (
            "version",
            vec![
                vec!["status"],
                vec!["status", "--json"],
                vec!["list"],
                vec!["disconnect"],
            ],
        ),
    ] {
        let text = fixture_text(&format!("ipc/{name}.txt"));
        let response: Response = serde_json::from_str(text.lines().nth(1).expect("an answer line"))
            .expect("the answer of a transcript");
        let daemon = FakeDaemon::start(move |_| response.clone());
        let socket = daemon.socket();
        let socket = socket.as_str();
        for command in commands {
            let mut args: Vec<&str> = Vec::new();
            args.extend(command.iter().copied());
            args.extend(["--socket", socket]);
            differences.same(
                &format!("{name}: {args:?}"),
                &run(&go, &args, &[]),
                &run(this_program(), &args, &[]),
            );
        }
    }
    // answers no transcript holds: an empty list with and without a word on it, a status of nothing
    let answers = [
        Response {
            ok: true,
            ..Response::default()
        },
        Response {
            ok: true,
            message: "Partner: device refused: ask Acme".to_string(),
            ..Response::default()
        },
        Response {
            ok: false,
            ..Response::default()
        },
        Response {
            ok: true,
            status: Some(ipc::Status::default()),
            ..Response::default()
        },
    ];
    for response in answers {
        let said = format!("{response:?}");
        let daemon = FakeDaemon::start(move |_| response.clone());
        let socket = daemon.socket();
        let socket = socket.as_str();
        for command in ["status", "list", "refresh"] {
            for json in [false, true] {
                let mut args = vec![command, "--socket", socket];
                if json {
                    args.push("--json");
                }
                differences.same(
                    &format!("{said}: {args:?}"),
                    &run(&go, &args, &[]),
                    &run(this_program(), &args, &[]),
                );
            }
        }
    }
    differences.none();
}

#[test]
fn each_client_asks_the_other_programs_daemon() {
    let Some(go) = go_program() else {
        return;
    };
    let mut differences = Differences::default();

    // the Go client and this daemon
    let scratch = Scratch::create();
    let (state, socket) = (scratch.arg("state"), scratch.arg("run/ghostlane.sock"));
    let (state, socket) = (state.as_str(), socket.as_str());
    let log = scratch.join("daemon.log");
    let daemon = Running::start(
        this_program(),
        &["run", "--state-dir", state, "--socket", socket],
        &log,
    );
    assert!(ask(Path::new(socket), "version", &log).ok);
    for args in [
        vec!["status"],
        vec!["status", "--json"],
        vec!["list"],
        vec!["refresh"],
        vec!["disconnect"],
        vec!["add", "x"],
        vec!["remove", "x"],
        vec!["connect", "DE", "--tun"],
    ] {
        let mut args = args;
        args.extend(["--socket", socket]);
        let by_go = run(&go, &args, &[]);
        differences.same(
            &format!("this daemon: {args:?}"),
            &by_go,
            &run(this_program(), &args, &[]),
        );
        if by_go.code != Some(1)
            || !by_go
                .stderr
                .starts_with(&format!("no_engine: {}: ", args[0]))
        {
            differences.0.push(format!("this daemon: {args:?}: the Go client was not told that nothing connects here: {by_go:?}"));
        }
    }
    assert_eq!(daemon.stop(), Some(0), "this daemon, told to stop");

    // this client and the Go daemon: one that has no list and is given none to fetch
    let scratch = Scratch::create();
    let (state, socket) = (scratch.arg("state"), scratch.arg("run/ghostlane.sock"));
    let (state, socket) = (state.as_str(), socket.as_str());
    let log = scratch.join("daemon.log");
    let daemon = Running::start(
        &go,
        &["run", "--state-dir", state, "--socket", socket],
        &log,
    );
    let version = ask(Path::new(socket), "version", &log)
        .version
        .expect("the Go daemon says what it is made of");
    assert!(!version.version.is_empty(), "{version:?}");
    let one = "hysteria2://secret@192.0.2.1:443?sni=example.com#\u{1f1e9}\u{1f1ea} DE \u{b7} HY2";
    let two = "hysteria2://secret@192.0.2.2:443#yes";
    let three = "hysteria2://secret@192.0.2.3:443#a: b\nhysteria2://secret@192.0.2.4:443#1";
    // asked of a daemon that has no list, by both clients: the same answer, printed the same
    let idle: Vec<Vec<&str>> = vec![
        vec!["status"],
        vec!["status", "--json"],
        vec!["list"],
        vec!["list", "--json"],
        vec!["refresh"],
        vec!["disconnect"],
        vec!["remove", "nothing"],
        vec!["add", "http://sub.example/sub/a/b"],
        vec!["add", "ftp://sub.example/list"],
        vec!["add", "words"],
    ];
    let both = |differences: &mut Differences, args: &[&str]| {
        let mut args = args.to_vec();
        args.extend(["--socket", socket]);
        differences.same(
            &format!("the Go daemon: {args:?}"),
            &run(&go, &args, &[]),
            &run(this_program(), &args, &[]),
        );
    };
    for args in &idle {
        both(&mut differences, args);
    }
    // lines given by hand, which no start fetches: each is added by one client and found there by the other
    let found = Run {
        code: Some(0),
        stdout: "already added\n".to_string(),
        stderr: String::new(),
    };
    for (turn, line) in [one, two, three].into_iter().enumerate() {
        let (first, second) = if turn % 2 == 0 {
            (go.as_path(), this_program())
        } else {
            (this_program(), go.as_path())
        };
        let args = ["add", line, "--socket", socket];
        let added = run(first, &args, &[]);
        if added.code != Some(0) || !added.stderr.is_empty() || added == found {
            differences
                .0
                .push(format!("the Go daemon: {args:?} was not added: {added:?}"));
        }
        differences.same(
            &format!("the Go daemon: {args:?}, asked again"),
            &found,
            &run(second, &args, &[]),
        );
    }
    for args in [
        vec!["status"],
        vec!["status", "--json"],
        vec!["list"],
        vec!["list", "--json"],
        vec!["list", "--subscription", "inline:"],
        vec!["refresh"],
    ] {
        both(&mut differences, &args);
    }
    // removed by its title, by this client; the Go client then finds nothing of that name
    let args = ["remove", "yes", "--socket", socket];
    let removed = Run {
        code: Some(0),
        stdout: "removed 1\n".to_string(),
        stderr: String::new(),
    };
    differences.same(
        "the Go daemon: remove yes",
        &removed,
        &run(this_program(), &args, &[]),
    );
    differences.same(
        "the Go daemon: remove yes, asked again",
        &Some(1),
        &run(&go, &args, &[]).code,
    );
    both(&mut differences, &["status", "--json"]);
    let config_path = scratch.join("state").join("config.yaml");
    let written = fs::read_to_string(&config_path).expect("the config the Go daemon wrote");
    assert_eq!(daemon.stop(), Some(0), "the Go daemon, told to stop");

    // the file the Go daemon wrote is read here, and written back the same
    let config = Config::from_yaml(written.as_bytes()).expect("the Go daemon's config is read");
    differences.same(
        "the lists in the Go daemon's config",
        &2_usize,
        &config.subscriptions.len(),
    );
    differences.same(
        "the Go daemon's config, written back",
        &written,
        &config.to_yaml(),
    );
    differences.none();
}
