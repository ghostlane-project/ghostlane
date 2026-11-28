//! The engine child (`ghostlane_cli::engine`): a process of its own, its
//! events, its liveness, its stop, its session file, and the questions it
//! answers once.
//!
//! Two kinds of test:
//!
//! - on a stand-in program (a shell script that prints the events the
//!   contract names), which every machine has: ready, a run that ends by
//!   itself, a start that is refused, one that never says ready, a stop that
//!   has to kill, the session file's mode and its removal, `probe` and its
//!   words;
//! - on the real `ghostlane-engine` of the pin, which only the job that built
//!   it has: `GHOSTLANE_ENGINE` names it, and `GHOSTLANE_ENGINE_REQUIRED`
//!   makes its absence a failure instead of a pass at once. With
//!   `XRAY_SERVERS` ("<version>=<xray> ...", scripts/fetch-xray-servers.sh)
//!   a VLESS line to each Xray release is run in proxy mode: SOCKS5, HTTP
//!   CONNECT and a plain GET for a URL carry a page; `--probe-url` is asked
//!   through `ghostlane-engine probe`; a dead line and a wrong login fail.
//!   A room on the `none` carrier is joined on a hub of this test's own.
//!
//! Nothing here reaches beyond loopback: the page is asked for at
//! 203.0.113.10 (TEST-NET-3), which only the Xray server's freedom redirect
//! turns into the page on loopback.

mod common;

use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use common::Scratch;
use coreconfig::{build_plan, Own, Plan, Spec};
use ghostlane_cli::daemon::links::OlcrtcLine;
use ghostlane_cli::daemon::{Engine, OlcrtcParams};
use ghostlane_cli::engine::{self, Options};
use serde_json::json;

const PAGE: &str = "ghostlane-engine-child-page\n";

fn wait_for(what: &str, within: Duration, mut done: impl FnMut() -> bool) {
    let deadline = Instant::now() + within;
    while !done() {
        assert!(Instant::now() < deadline, "{what}: not within {within:?}");
        thread::sleep(Duration::from_millis(20));
    }
}

fn free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.local_addr().unwrap().port()
}

fn read_all(stream: &mut TcpStream) -> Vec<u8> {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(20)));
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        match stream.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => out.extend_from_slice(&buf[..n]),
        }
    }
    out
}

fn read_head(stream: &mut TcpStream) -> String {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(20)));
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") && head.len() < 16384 {
        match stream.read(&mut byte) {
            Ok(1) => head.push(byte[0]),
            _ => break,
        }
    }
    String::from_utf8_lossy(&head).into_owned()
}

fn body_of(answer: &[u8]) -> String {
    let text = String::from_utf8_lossy(answer).into_owned();
    match text.split_once("\r\n\r\n") {
        Some((_, body)) => body.to_owned(),
        None => text,
    }
}

// ---------------------------------------------------------------------------
// A stand-in program

const FAKE: &str = r#"#!/bin/sh
# A stand-in for ghostlane-engine: the scenario is a word in the session file.
case "$1" in
version)
  echo '{"event":"version","engine":"0.0.0","protocol":1,"pin":"unknown","boringssl":false,"rooms":false,"target":"fake"}'
  exit 0 ;;
probe)
  echo "$*" > "$3.argv"
  if grep -q scn-badprobe "$3"; then
    echo '{"event":"probe","ok":false,"millis":null,"error":"timeout"}'
    exit 1
  fi
  echo '{"event":"probe","ok":true,"millis":12,"error":null}'
  exit 0 ;;
esac
file="$3"
stat -c %a "$file" > "$file.mode"
if grep -q scn-refuse "$file"; then
  echo '{"event":"ended","reason":"failed","code":"config","message":"the line is refused"}'
  exit 1
fi
if grep -q scn-silent "$file"; then exec sleep 30; fi
echo '{"event":"log","source":"core","level":"info","line":"inbound in is listening"}'
echo '{"event":"ready","inbounds":[{"tag":"other","address":"127.0.0.1:41081"},{"tag":"in","address":"127.0.0.1:41080"}]}'
if grep -q scn-crash "$file"; then sleep 0.3; exit 1; fi
if grep -q scn-stubborn "$file"; then exec sleep 30; fi
cat > /dev/null
echo '{"event":"ended","reason":"stdin_closed","code":null,"message":null}'
"#;

struct Stand {
    scratch: Scratch,
    options: Options,
    lines: Arc<Mutex<Vec<String>>>,
}

fn stand() -> Stand {
    let scratch = Scratch::create();
    let program = scratch.join("fake-engine");
    std::fs::write(&program, FAKE).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&program, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    let mut options = Options::new(program, scratch.join("run"));
    options.ready_timeout = Duration::from_secs(10);
    options.stop_grace = Duration::from_secs(2);
    let lines = Arc::new(Mutex::new(Vec::new()));
    let into = Arc::clone(&lines);
    options.log = Arc::new(move |line| into.lock().unwrap().push(line.to_owned()));
    Stand {
        scratch,
        options,
        lines,
    }
}

fn session_files(dir: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .filter_map(Result::ok)
                .map(|e| e.path())
                .filter(|p| p.extension().is_some_and(|x| x == "json"))
                .collect()
        })
        .unwrap_or_default();
    out.sort();
    out
}

fn core(scenario: &str) -> String {
    json!({"x-scenario": scenario}).to_string()
}

#[test]
fn a_ready_engine_serves_the_inbound_it_tagged_and_stops_on_a_closed_stdin() {
    let stand = stand();
    let engine = engine::start_core(
        &stand.options,
        &core("scn-ok"),
        Some(("u", "p")),
        None,
        None,
    )
    .expect("a start");
    assert_eq!(
        engine.socks_addr(),
        "127.0.0.1:41080",
        "the tag `in`, not the first"
    );
    assert_eq!(engine.credentials(), ("u".to_string(), "p".to_string()));
    assert_eq!(engine.state(), "running");
    assert_eq!(engine.listeners().len(), 2);
    assert!(stand
        .lines
        .lock()
        .unwrap()
        .iter()
        .any(|l| l.contains("inbound in is listening")));

    // the session file is private while the engine runs, and is the only way
    // the session reaches it
    let files = session_files(&stand.scratch.join("run"));
    assert_eq!(files.len(), 1, "{files:?}");
    let mode = std::fs::read_to_string(format!("{}.mode", files[0].display())).unwrap();
    assert_eq!(mode.trim(), "600");

    let started = Instant::now();
    engine.stop().expect("a stop");
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "{:?}",
        started.elapsed()
    );
    assert_eq!(engine.state(), "stopped");
    assert!(session_files(&stand.scratch.join("run")).is_empty());
    wait_for("the end is read", Duration::from_secs(3), || {
        engine.last_end().is_some()
    });
    assert_eq!(engine.last_end().unwrap().reason, "stdin_closed");
    // a second stop is nothing
    engine.stop().expect("a second stop");
}

#[test]
fn an_engine_that_dies_is_seen_as_stopped() {
    let stand = stand();
    let engine =
        engine::start_core(&stand.options, &core("scn-crash"), None, None, None).expect("a start");
    assert_eq!(engine.state(), "running");
    wait_for("the engine's death is seen", Duration::from_secs(5), || {
        engine.state() == "stopped"
    });
    engine.stop().expect("a stop after a death");
    assert!(session_files(&stand.scratch.join("run")).is_empty());
}

#[test]
fn a_refused_session_is_an_error_with_the_engines_sentence() {
    let stand = stand();
    let Err(why) = engine::start_core(&stand.options, &core("scn-refuse"), None, None, None) else {
        panic!("a refused session started");
    };
    assert!(why.contains("the line is refused"), "{why}");
    assert!(why.contains("config"), "{why}");
    assert!(session_files(&stand.scratch.join("run")).is_empty());
}

#[test]
fn an_engine_that_is_never_ready_is_an_error_and_is_gone() {
    let mut stand = stand();
    stand.options.ready_timeout = Duration::from_millis(400);
    stand.options.stop_grace = Duration::from_millis(300);
    let started = Instant::now();
    let Err(why) = engine::start_core(&stand.options, &core("scn-silent"), None, None, None) else {
        panic!("a silent engine started");
    };
    assert!(why.contains("not ready"), "{why}");
    assert!(
        started.elapsed() < Duration::from_secs(5),
        "{:?}",
        started.elapsed()
    );
    assert!(session_files(&stand.scratch.join("run")).is_empty());
}

#[test]
fn an_engine_that_ignores_a_closed_stdin_is_killed_after_the_grace() {
    let mut stand = stand();
    stand.options.stop_grace = Duration::from_millis(500);
    let engine = engine::start_core(&stand.options, &core("scn-stubborn"), None, None, None)
        .expect("a start");
    let started = Instant::now();
    engine.stop().expect("a stop");
    let took = started.elapsed();
    assert!(took >= Duration::from_millis(400), "{took:?}");
    assert!(took < Duration::from_secs(4), "{took:?}");
    assert_eq!(engine.state(), "stopped");
}

#[test]
fn a_dropped_engine_leaves_no_process_and_no_session_file() {
    let stand = stand();
    let engine =
        engine::start_core(&stand.options, &core("scn-ok"), None, None, None).expect("a start");
    assert_eq!(session_files(&stand.scratch.join("run")).len(), 1);
    drop(engine);
    assert!(session_files(&stand.scratch.join("run")).is_empty());
}

#[test]
fn a_probe_is_one_process_one_answer_and_no_secret_in_its_arguments() {
    let stand = stand();
    let session = engine::core_session(
        &json!({"x-scenario": "scn-ok", "secret": "do-not-argue"}).to_string(),
        None,
        None,
    )
    .unwrap();
    let millis = engine::probe_line(
        &stand.options,
        &session,
        "http://probe.test/generate_204",
        Duration::from_secs(5),
        Some("out"),
    )
    .expect("an answer");
    assert_eq!(millis, 12);
    assert!(session_files(&stand.scratch.join("run")).is_empty());
    let argv: Vec<PathBuf> = std::fs::read_dir(stand.scratch.join("run"))
        .unwrap()
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "argv"))
        .collect();
    assert_eq!(argv.len(), 1);
    let said = std::fs::read_to_string(&argv[0]).unwrap();
    assert!(
        said.contains("--url http://probe.test/generate_204"),
        "{said}"
    );
    assert!(said.contains("--timeout-ms 5000"), "{said}");
    assert!(said.contains("--outbound out"), "{said}");
    assert!(!said.contains("do-not-argue"), "{said}");

    let bad = engine::core_session(&core("scn-badprobe"), None, None).unwrap();
    let Err(why) = engine::probe_line(
        &stand.options,
        &bad,
        "http://probe.test/",
        Duration::from_secs(5),
        None,
    ) else {
        panic!("a probe that failed said yes");
    };
    assert_eq!(why, "timeout");
}

#[test]
fn the_program_says_what_it_is_and_a_stranger_protocol_is_refused() {
    let stand = stand();
    let version = engine::check_version(&stand.options.program).expect("a version");
    assert_eq!(version.protocol, 1);
    assert_eq!(version.target, "fake");
    let nowhere = stand.scratch.join("nowhere");
    assert!(engine::check_version(&nowhere).is_err());
}

#[test]
fn the_program_is_found_by_its_name_or_beside_this_one() {
    let stand = stand();
    let named = stand.options.program.clone();
    let exe = stand.scratch.join("ghostlane");
    assert_eq!(
        engine::locate(Some(named.as_os_str()), &exe).unwrap(),
        named
    );
    assert!(
        engine::locate(None, &exe).is_err(),
        "no engine beside it yet"
    );
    std::fs::copy(&named, stand.scratch.join("ghostlane-engine")).unwrap();
    assert_eq!(
        engine::locate(None, &exe).unwrap(),
        stand.scratch.join("ghostlane-engine")
    );
    assert!(engine::locate(Some(stand.scratch.join("no").as_os_str()), &exe).is_err());
}

#[test]
fn a_probe_inbound_is_a_free_port_and_a_login_per_start() {
    let (address, user, pass) = engine::probe_inbound().unwrap();
    assert!(address.starts_with("127.0.0.1:"), "{address}");
    assert!(!user.is_empty() && !pass.is_empty());
    let (_, user2, pass2) = engine::probe_inbound().unwrap();
    assert_ne!((user, pass), (user2, pass2));
}

/// A SOCKS5 server that answers every request with a status, and says what
/// login it was given.
fn fake_socks(status: u16, login: Option<(&'static str, &'static str)>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap().to_string();
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut s) = stream else { continue };
            let mut greeting = [0u8; 3];
            if s.read_exact(&mut greeting).is_err() {
                continue;
            }
            match login {
                None => {
                    let _ = s.write_all(&[5, 0]);
                }
                Some((user, pass)) => {
                    let _ = s.write_all(&[5, 2]);
                    let mut head = [0u8; 2];
                    let _ = s.read_exact(&mut head);
                    let mut u = vec![0u8; head[1] as usize];
                    let _ = s.read_exact(&mut u);
                    let mut n = [0u8; 1];
                    let _ = s.read_exact(&mut n);
                    let mut p = vec![0u8; n[0] as usize];
                    let _ = s.read_exact(&mut p);
                    let good = u == user.as_bytes() && p == pass.as_bytes();
                    let _ = s.write_all(&[1, u8::from(!good)]);
                    if !good {
                        continue;
                    }
                }
            }
            let mut request = [0u8; 5];
            let _ = s.read_exact(&mut request);
            let mut rest = vec![0u8; request[4] as usize + 2];
            let _ = s.read_exact(&mut rest);
            let _ = s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
            let _ = read_head(&mut s);
            let _ = s.write_all(
                format!("HTTP/1.1 {status} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .as_bytes(),
            );
            let _ = s.shutdown(Shutdown::Both);
        }
    });
    address
}

#[test]
fn a_socks_probe_is_alive_on_a_2xx_and_dead_otherwise() {
    let ok = fake_socks(204, None);
    engine::socks_probe(&ok, "", "", "http://cp.test/generate_204").expect("alive");
    let bad = fake_socks(503, None);
    let Err(why) = engine::socks_probe(&bad, "", "", "http://cp.test/") else {
        panic!("a 503 is alive");
    };
    assert!(why.contains("503"), "{why}");
    let login = fake_socks(204, Some(("who", "secret")));
    engine::socks_probe(&login, "who", "secret", "http://cp.test/").expect("with the login");
    assert!(engine::socks_probe(&login, "who", "wrong", "http://cp.test/").is_err());
    let nobody = format!("127.0.0.1:{}", free_port());
    assert!(engine::socks_probe(&nobody, "", "", "http://cp.test/").is_err());
    assert!(
        engine::socks_probe(&ok, "", "", "https://cp.test/").is_err(),
        "https is no probe URL"
    );
}

// ---------------------------------------------------------------------------
// The real engine

/// The pin's `ghostlane-engine`, where the job built one.
fn real_engine() -> Option<PathBuf> {
    let named = std::env::var_os("GHOSTLANE_ENGINE").filter(|p| !p.is_empty());
    let Some(path) = named.map(PathBuf::from) else {
        assert!(
            std::env::var_os("GHOSTLANE_ENGINE_REQUIRED").is_none(),
            "GHOSTLANE_ENGINE_REQUIRED is set and GHOSTLANE_ENGINE is not"
        );
        eprintln!("nothing run: GHOSTLANE_ENGINE names no ghostlane-engine");
        return None;
    };
    assert!(
        path.is_file(),
        "GHOSTLANE_ENGINE names no file: {}",
        path.display()
    );
    Some(path)
}

/// The Xray servers the job fetched: (version, program).
fn xray_servers() -> Vec<(String, PathBuf)> {
    let text = std::env::var("XRAY_SERVERS").unwrap_or_default();
    if text.trim().is_empty() {
        assert!(
            std::env::var_os("GHOSTLANE_ENGINE_REQUIRED").is_none(),
            "GHOSTLANE_ENGINE_REQUIRED is set and XRAY_SERVERS is not"
        );
        return Vec::new();
    }
    text.split_whitespace()
        .map(|pair| {
            let (version, path) = pair
                .split_once('=')
                .expect("XRAY_SERVERS is <version>=<path>");
            (version.to_owned(), PathBuf::from(path))
        })
        .collect()
}

#[test]
fn the_real_engine_is_the_pin_and_speaks_the_contract() {
    let Some(program) = real_engine() else { return };
    let version = engine::check_version(&program).expect("a version");
    assert!(version.rooms, "the CLI's engine carries rooms: {version:?}");
    if let Ok(pin) = std::env::var("GHOSTLANE_CORE_COMMIT") {
        assert_eq!(version.pin, pin);
    }
}

/// A hub of the `none` carrier: a participant's first frame is its JOIN, and
/// the hub then holds the connection.
fn hub() -> (String, Arc<AtomicUsize>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let joins = Arc::new(AtomicUsize::new(0));
    let counted = Arc::clone(&joins);
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let counted = Arc::clone(&counted);
            thread::spawn(move || {
                let mut head = [0u8; 5];
                if stream.read_exact(&mut head).is_err() {
                    return;
                }
                let length = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as usize;
                let mut payload = vec![0u8; length.saturating_sub(1)];
                if stream.read_exact(&mut payload).is_err() {
                    return;
                }
                counted.fetch_add(1, Ordering::SeqCst);
                let mut sink = [0u8; 4096];
                while matches!(stream.read(&mut sink), Ok(n) if n > 0) {}
            });
        }
    });
    (format!("tcp://{address}/ghostlane-cli-test"), joins)
}

#[test]
fn a_room_on_a_fake_hub_is_joined_and_its_end_is_the_starts_error() {
    let Some(program) = real_engine() else { return };
    let scratch = Scratch::create();
    let (room, joins) = hub();
    let mut options = Options::new(program, scratch.join("run"));
    options.ready_timeout = Duration::from_secs(60);
    let port = free_port();
    let params = OlcrtcParams {
        line: OlcrtcLine {
            provider: "none".into(),
            transport: String::new(),
            room,
            key: "4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e".into(),
            label: "fake room".into(),
        },
        socks_port: port,
        device_id_path: scratch.join("device-id"),
        device_id: "0123456789abcdef".into(),
    };
    let login = engine::random_login().unwrap();
    // No olcRTC server is in the hub's room, so the join never gets past its
    // hello and the room ends: the engine says so, and the child reports it
    // as a start that failed, with nothing left behind.
    let Err(why) = engine::start_room(&options, &params, login, None) else {
        panic!("a room with nobody in it came up");
    };
    assert!(why.contains("(room)"), "{why}");
    assert!(
        joins.load(Ordering::SeqCst) >= 1,
        "the engine joined the room the session names: {why}"
    );
    assert!(session_files(&scratch.join("run")).is_empty());
}

/// The page, answered to any request on loopback.
fn page_server() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut s) = stream else { continue };
            thread::spawn(move || {
                let _ = read_head(&mut s);
                let _ = s.write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{PAGE}",
                        PAGE.len()
                    )
                    .as_bytes(),
                );
                let _ = s.shutdown(Shutdown::Both);
            });
        }
    });
    port
}

struct Xray {
    child: Child,
}

impl Xray {
    fn start(program: &Path, dir: &Path, port: u16, page: u16, uuid: &str) -> Xray {
        let config = json!({
            "log": {"loglevel": "warning"},
            "inbounds": [{
                "tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "vless",
                "settings": {"clients": [{"id": uuid}], "decryption": "none"},
                "streamSettings": {"network": "raw", "security": "none"}
            }],
            "outbounds": [{
                "tag": "direct", "protocol": "freedom",
                "settings": {
                    "redirect": format!("127.0.0.1:{page}"),
                    "ipsBlocked": [],
                    "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]
                }
            }]
        });
        let file = dir.join("xray-server.json");
        std::fs::write(&file, config.to_string()).unwrap();
        let log = std::fs::File::create(dir.join("xray-server.log")).unwrap();
        let child = Command::new(program)
            .args(["run", "-config"])
            .arg(&file)
            .stdout(Stdio::from(log.try_clone().unwrap()))
            .stderr(Stdio::from(log))
            .spawn()
            .expect("start xray");
        let mut server = Xray { child };
        wait_for("the Xray server listens", Duration::from_secs(20), || {
            if let Ok(Some(status)) = server.child.try_wait() {
                panic!("the Xray server exited at start: {status}");
            }
            TcpStream::connect(("127.0.0.1", port)).is_ok()
        });
        server
    }

    fn kill(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for Xray {
    fn drop(&mut self) {
        self.kill();
    }
}

fn vless_spec(port: u16, uuid: &str) -> Spec {
    serde_json::from_value(json!({
        "kind": "vless", "uuid": uuid, "host": "127.0.0.1", "port": port,
        "sni": "", "publicKey": "", "shortId": "", "fingerprint": "chrome",
        "flow": null, "transport": {"type": "tcp"}, "tag": "child-test",
        "plain": true, "insecure": false, "alpn": []
    }))
    .expect("a spec")
}

fn proxy_core(spec: &Spec, proxy_port: u16) -> String {
    let plan = Plan {
        own: Own::Proxy {
            port: i32::from(proxy_port),
            login: None,
            listen: "127.0.0.1".to_string(),
        },
        lan: None,
    };
    build_plan(spec, &plan, false).expect("the builder writes the line")
}

const GOOD: &str = "55555555-5555-5555-5555-555555555555";
const OTHER: &str = "55555555-5555-5555-5555-555555555553";
const TARGET: &str = "203.0.113.10";

fn socks5_page(proxy: &str) -> String {
    let mut s = TcpStream::connect(proxy).unwrap();
    s.write_all(&[5, 1, 0]).unwrap();
    let mut choice = [0u8; 2];
    s.read_exact(&mut choice).unwrap();
    assert_eq!(choice, [5, 0], "SOCKS5 with no login");
    let mut request = vec![5, 1, 0, 1, 203, 0, 113, 10];
    request.extend_from_slice(&80u16.to_be_bytes());
    s.write_all(&request).unwrap();
    let mut reply = [0u8; 10];
    s.read_exact(&mut reply).unwrap();
    assert_eq!(reply[1], 0, "the connect is granted: {reply:?}");
    s.write_all(
        format!("GET / HTTP/1.1\r\nHost: {TARGET}\r\nConnection: close\r\n\r\n").as_bytes(),
    )
    .unwrap();
    body_of(&read_all(&mut s))
}

fn connect_page(proxy: &str) -> String {
    let mut s = TcpStream::connect(proxy).unwrap();
    s.write_all(format!("CONNECT {TARGET}:80 HTTP/1.1\r\nHost: {TARGET}:80\r\n\r\n").as_bytes())
        .unwrap();
    let head = read_head(&mut s);
    assert!(
        head.starts_with("HTTP/1.1 200") || head.starts_with("HTTP/1.0 200"),
        "{head}"
    );
    s.write_all(
        format!("GET / HTTP/1.1\r\nHost: {TARGET}\r\nConnection: close\r\n\r\n").as_bytes(),
    )
    .unwrap();
    body_of(&read_all(&mut s))
}

fn plain_get_page(proxy: &str) -> String {
    let mut s = TcpStream::connect(proxy).unwrap();
    s.write_all(
        format!("GET http://{TARGET}/ HTTP/1.1\r\nHost: {TARGET}\r\nConnection: close\r\n\r\n")
            .as_bytes(),
    )
    .unwrap();
    body_of(&read_all(&mut s))
}

#[test]
fn proxy_mode_carries_a_page_by_socks5_connect_and_a_plain_get_against_every_xray() {
    let Some(program) = real_engine() else { return };
    let servers = xray_servers();
    if servers.is_empty() {
        eprintln!("nothing run: XRAY_SERVERS names no server");
        return;
    }
    let page = page_server();
    for (version, xray) in servers {
        let scratch = Scratch::create();
        let port = free_port();
        let mut server = Xray::start(&xray, scratch.path(), port, page, GOOD);
        let proxy_port = free_port();
        let core = proxy_core(&vless_spec(port, GOOD), proxy_port);
        let mut options = Options::new(program.clone(), scratch.join("run"));
        options.ready_timeout = Duration::from_secs(30);
        let engine = engine::start_core(&options, &core, None, None, None)
            .unwrap_or_else(|e| panic!("Xray {version}: {e}"));
        assert_eq!(engine.socks_addr(), format!("127.0.0.1:{proxy_port}"));
        let proxy = engine.socks_addr();

        assert_eq!(socks5_page(&proxy), PAGE, "Xray {version}: SOCKS5");
        assert_eq!(connect_page(&proxy), PAGE, "Xray {version}: CONNECT");
        assert_eq!(plain_get_page(&proxy), PAGE, "Xray {version}: a plain GET");

        // the liveness probe, through the port that serves
        engine::socks_probe(&proxy, "", "", &format!("http://{TARGET}/"))
            .unwrap_or_else(|e| panic!("Xray {version}: the SOCKS probe: {e}"));
        // --probe-url, through `ghostlane-engine probe`: a process of its own
        let session = engine::core_session(&core, None, None).unwrap();
        engine::probe_line(
            &options,
            &session,
            &format!("http://{TARGET}/"),
            Duration::from_secs(15),
            None,
        )
        .unwrap_or_else(|e| panic!("Xray {version}: ghostlane-engine probe: {e}"));
        assert_eq!(engine.state(), "running", "Xray {version}");

        // a line whose server is gone is dead to both probes, and the engine
        // is still up: the daemon, not the engine, judges a line
        server.kill();
        assert!(
            engine::socks_probe(&proxy, "", "", &format!("http://{TARGET}/")).is_err(),
            "Xray {version}: a dead line answered"
        );
        assert!(
            engine::probe_line(
                &options,
                &session,
                &format!("http://{TARGET}/"),
                Duration::from_secs(5),
                None
            )
            .is_err(),
            "Xray {version}: a dead line answered ghostlane-engine probe"
        );
        engine.stop().expect("a stop");
        assert_eq!(engine.state(), "stopped");
    }
}

#[test]
fn a_line_with_the_wrong_id_carries_nothing() {
    let Some(program) = real_engine() else { return };
    let servers = xray_servers();
    let Some((version, xray)) = servers.into_iter().next() else {
        eprintln!("nothing run: XRAY_SERVERS names no server");
        return;
    };
    let scratch = Scratch::create();
    let page = page_server();
    let port = free_port();
    let _server = Xray::start(&xray, scratch.path(), port, page, GOOD);
    let proxy_port = free_port();
    let core = proxy_core(&vless_spec(port, OTHER), proxy_port);
    let mut options = Options::new(program, scratch.join("run"));
    options.ready_timeout = Duration::from_secs(30);
    let engine = engine::start_core(&options, &core, None, None, None).expect("a start");
    assert!(
        engine::socks_probe(&engine.socks_addr(), "", "", &format!("http://{TARGET}/")).is_err(),
        "Xray {version}: a wrong id reached the page"
    );
    let session = engine::core_session(&core, None, None).unwrap();
    assert!(engine::probe_line(
        &options,
        &session,
        &format!("http://{TARGET}/"),
        Duration::from_secs(5),
        None
    )
    .is_err());
    engine.stop().expect("a stop");
}
