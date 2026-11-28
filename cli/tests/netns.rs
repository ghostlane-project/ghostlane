//! The Go CLI's two netns cases of `internal/daemon` (`netns_test.go`), for the
//! Rust daemon with its real rules (`gl-routes`) and the real tun (hev). Each
//! case starts this binary again under `unshare -n`; they need root,
//! GHOSTLANE_NETNS=1 and GHOSTLANE_HEV, the hev-socks5-tunnel program
//! (scripts/cli-checks/netns.sh sets all three and holds the run to: every
//! case ran, none was skipped). A case that is skipped says `SKIPPED`.
//!
//! The Go cases ran sing-box in front of a fake engine. Here the daemon is
//! the Rust one with `RulesRoutes` and `Hev`; the engine is a fake SOCKS5
//! server that answers every request with 204 (what the Go tests' `fakesocks`
//! did), and the probe inbound is a second one on the address the daemon
//! names. Topology, addresses and the order of the steps are the Go test's.

use std::io::{BufRead as _, BufReader, Read as _, Write as _};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::os::fd::FromRawFd as _;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use ghostlane_cli::daemon::clock::Clock;
use ghostlane_cli::daemon::links::{Device, FetchError};
use ghostlane_cli::daemon::routes::RulesRoutes;
use ghostlane_cli::daemon::{Daemon, Deps, Engine, Front, FrontParams, OlcrtcParams, XrayParams};
use ghostlane_cli::ipc::{Request, Response, VersionInfo};
use ghostlane_cli::store::{self, Selection, Subscription};
use gl_routes::hev::{Hev, HevParams, TUN_NAME};
use gl_routes::{
    Manager, KILL_SWITCH_DROP, KILL_SWITCH_LAN, KILL_SWITCH_SELF, OWN_ADDRESS_PRIORITY,
};
use gocompat::time::Timestamp;

const HOST: &str = "198.51.100.1";
const PEER: &str = "198.51.100.2";
/// The "SSH client on the internet": behind the peer, no route in main.
const FAR: &str = "192.0.2.5";
const INTERNET: &str = "203.0.113.10";
const UID: i32 = 65534;

// ---- the harness: the test binary again, under unshare -n ----------------------------

fn enter(name: &str) -> bool {
    if std::env::var("GHOSTLANE_NETNS").as_deref() != Ok("1") {
        eprintln!("SKIPPED {name}: set GHOSTLANE_NETNS=1 (and run as root) for the netns tests");
        return false;
    }
    // SAFETY: geteuid has no arguments and cannot fail.
    if unsafe { libc::geteuid() } != 0 {
        eprintln!("SKIPPED {name}: netns tests need root");
        return false;
    }
    if std::env::var("GHOSTLANE_IN_NETNS").as_deref() == Ok("1") {
        ip(&["link", "set", "lo", "up"]);
        return true;
    }
    let exe = std::env::current_exe().expect("the test binary's path");
    let out = Command::new("unshare")
        .args(["-n", "--"])
        .arg(exe)
        .args(["--exact", name, "--nocapture", "--test-threads=1"])
        .env("GHOSTLANE_IN_NETNS", "1")
        .output()
        .expect("unshare -n");
    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    print!("{stdout}");
    eprint!("{stderr}");
    assert!(
        out.status.success(),
        "{name} failed inside the netns: {}",
        out.status
    );
    assert!(
        stdout.contains("1 passed"),
        "{name} did not run inside the netns"
    );
    false
}

fn ip(args: &[&str]) -> String {
    let out = Command::new("ip").args(args).output().expect("ip");
    assert!(
        out.status.success(),
        "ip {args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).into_owned()
}

fn rules_with_priority(family: &str, prio: u32) -> Vec<String> {
    let want = format!("{prio}:");
    ip(&[family, "rule", "show"])
        .lines()
        .filter_map(|line| {
            let (head, rest) = line.split_once(':')?;
            (format!("{}:", head.trim()) == want)
                .then(|| rest.split_whitespace().collect::<Vec<_>>().join(" "))
        })
        .collect()
}

fn routes_of_table(family: &str, table: u32) -> Vec<String> {
    ip(&[family, "route", "show", "table", &table.to_string()])
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty())
        .collect()
}

fn device_exists(name: &str) -> bool {
    Command::new("ip")
        .args(["link", "show", name])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

// ---- the topology: host <-veth-> peer ------------------------------------------------

/// The peer namespace, `ip netns` named; gone when this is dropped.
struct Peer;

const PEER_NS: &str = "gl-peer";

impl Drop for Peer {
    fn drop(&mut self) {
        let _ = Command::new("ip").args(["netns", "del", PEER_NS]).output();
    }
}

fn topology() -> Peer {
    let _ = Command::new("ip").args(["netns", "del", PEER_NS]).output();
    ip(&["netns", "add", PEER_NS]);
    let peer = Peer;
    ip(&[
        "link", "add", "gl-veth0", "type", "veth", "peer", "name", "gl-veth1",
    ]);
    ip(&["link", "set", "gl-veth1", "netns", PEER_NS]);
    ip(&["addr", "add", &format!("{HOST}/24"), "dev", "gl-veth0"]);
    ip(&["link", "set", "gl-veth0", "up"]);
    let in_peer = |args: &[&str]| {
        let mut all = vec!["-n", PEER_NS];
        all.extend_from_slice(args);
        ip(&all);
    };
    in_peer(&["link", "set", "lo", "up"]);
    in_peer(&["addr", "add", &format!("{PEER}/24"), "dev", "gl-veth1"]);
    in_peer(&["addr", "add", &format!("{FAR}/32"), "dev", "gl-veth1"]);
    in_peer(&["link", "set", "gl-veth1", "up"]);
    // default via the peer, so the far client's replies have a route in main
    ip(&["route", "add", "default", "via", PEER]);
    // Strict reverse-path filtering, as on RHEL-family hosts: the reverse
    // lookup for an inbound packet is `from <our address> to <client>`, which
    // the own-address rule sends to main, so the SYN must still be accepted.
    for f in [
        "/proc/sys/net/ipv4/conf/all/rp_filter",
        "/proc/sys/net/ipv4/conf/gl-veth0/rp_filter",
    ] {
        std::fs::write(f, "1").expect("rp_filter");
    }
    peer
}

/// Runs `f` on a thread of the peer namespace (setns is per thread). A socket
/// made there stays in the peer namespace whichever thread uses it later.
fn in_peer<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    thread::spawn(move || {
        let ns = std::fs::File::open(format!("/run/netns/{PEER_NS}")).expect("the peer namespace");
        use std::os::fd::AsRawFd as _;
        // SAFETY: a namespace descriptor, for this thread only.
        let rc = unsafe { libc::setns(ns.as_raw_fd(), libc::CLONE_NEWNET) };
        assert_eq!(rc, 0, "setns: {}", std::io::Error::last_os_error());
        f()
    })
    .join()
    .expect("the peer thread")
}

/// A TCP connection from a chosen source address, with a connect deadline.
fn connect_from(src: Ipv4Addr, dst: SocketAddrV4, timeout: Duration) -> std::io::Result<TcpStream> {
    // SAFETY: plain system calls; the descriptor is owned by the stream at once.
    unsafe {
        let fd = libc::socket(libc::AF_INET, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 {
            return Err(std::io::Error::last_os_error());
        }
        let stream = TcpStream::from_raw_fd(fd);
        let tv = libc::timeval {
            tv_sec: timeout.as_secs() as libc::time_t,
            tv_usec: i64::from(timeout.subsec_micros()) as libc::suseconds_t,
        };
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDTIMEO,
            (&tv as *const libc::timeval).cast::<libc::c_void>(),
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        );
        let sin = |addr: Ipv4Addr, port: u16| libc::sockaddr_in {
            sin_family: libc::AF_INET as libc::sa_family_t,
            sin_port: port.to_be(),
            sin_addr: libc::in_addr {
                s_addr: u32::from_ne_bytes(addr.octets()),
            },
            sin_zero: [0; 8],
        };
        let size = std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t;
        let local = sin(src, 0);
        if libc::bind(fd, (&local as *const libc::sockaddr_in).cast(), size) < 0 {
            return Err(std::io::Error::last_os_error());
        }
        let remote = sin(*dst.ip(), dst.port());
        if libc::connect(fd, (&remote as *const libc::sockaddr_in).cast(), size) < 0 {
            return Err(std::io::Error::last_os_error());
        }
        stream.set_read_timeout(Some(timeout))?;
        Ok(stream)
    }
}

fn first_line(stream: TcpStream) -> std::io::Result<String> {
    let mut line = String::new();
    BufReader::new(stream).read_line(&mut line)?;
    Ok(line.trim().to_string())
}

/// What a client at FAR gets from `target` (an address in the host namespace).
fn dial_from_far(target: &str, timeout: Duration) -> std::io::Result<String> {
    let dst: SocketAddrV4 = target.parse().expect("an address");
    in_peer(move || {
        let stream = connect_from(FAR.parse().expect("far"), dst, timeout)?;
        first_line(stream)
    })
}

/// A listener that writes `line` to everyone and closes.
fn answer(listener: TcpListener, line: &'static str) {
    thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            let _ = conn.write_all(line.as_bytes());
        }
    });
}

/// From the host namespace: a request, and the first line of the answer.
fn dial_read(target: &str) -> std::io::Result<String> {
    let addr: std::net::SocketAddr = target.parse().expect("an address");
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))?;
    stream.set_read_timeout(Some(Duration::from_secs(3)))?;
    stream.write_all(b"GET / HTTP/1.0\r\nHost: x\r\n\r\n")?;
    first_line(stream)
}

// ---- a fake SOCKS5 server that answers every request with 204 ------------------------

struct FakeSocks {
    addr: String,
    stop: Arc<AtomicBool>,
}

impl FakeSocks {
    fn serve(listen: &str, user: &str, pass: &str) -> Result<FakeSocks, String> {
        let listener = TcpListener::bind(listen).map_err(|e| format!("listen {listen}: {e}"))?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let addr = listener
            .local_addr()
            .map_err(|e| e.to_string())?
            .to_string();
        let stop = Arc::new(AtomicBool::new(false));
        let (flag, user, pass) = (Arc::clone(&stop), user.to_string(), pass.to_string());
        thread::spawn(move || {
            while !flag.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((conn, _)) => {
                        let (user, pass) = (user.clone(), pass.clone());
                        thread::spawn(move || {
                            let _ = conn.set_nonblocking(false);
                            let _ = socks_session(conn, &user, &pass);
                        });
                    }
                    Err(_) => thread::sleep(Duration::from_millis(10)),
                }
            }
        });
        Ok(FakeSocks { addr, stop })
    }

    fn close(&self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

fn socks_session(mut conn: TcpStream, user: &str, pass: &str) -> std::io::Result<()> {
    conn.set_read_timeout(Some(Duration::from_secs(10)))?;
    let mut head = [0u8; 2];
    conn.read_exact(&mut head)?;
    let mut methods = vec![0u8; head[1] as usize];
    conn.read_exact(&mut methods)?;
    conn.write_all(&[5, 2])?;
    let mut version = [0u8; 2];
    conn.read_exact(&mut version)?;
    let mut name = vec![0u8; version[1] as usize];
    conn.read_exact(&mut name)?;
    let mut len = [0u8; 1];
    conn.read_exact(&mut len)?;
    let mut secret = vec![0u8; len[0] as usize];
    conn.read_exact(&mut secret)?;
    if name != user.as_bytes() || secret != pass.as_bytes() {
        conn.write_all(&[1, 1])?;
        return Ok(());
    }
    conn.write_all(&[1, 0])?;
    let mut request = [0u8; 4];
    conn.read_exact(&mut request)?;
    let skip = match request[3] {
        1 => 4,
        4 => 16,
        3 => {
            let mut n = [0u8; 1];
            conn.read_exact(&mut n)?;
            n[0] as usize
        }
        _ => return Ok(()),
    };
    let mut rest = vec![0u8; skip + 2];
    conn.read_exact(&mut rest)?;
    conn.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0])?;
    let mut seen = Vec::new();
    let mut byte = [0u8; 1];
    while !seen.ends_with(b"\r\n\r\n") {
        if conn.read(&mut byte)? == 0 {
            return Ok(());
        }
        seen.push(byte[0]);
    }
    conn.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")?;
    Ok(())
}

/// The daemon's probe: through a SOCKS5 address, a request to the internet
/// address, and a 204 back.
fn probe(addr: &str, user: &str, pass: &str) -> Result<(), String> {
    let target: std::net::SocketAddr = addr.parse().map_err(|e| format!("{addr}: {e}"))?;
    let mut conn =
        TcpStream::connect_timeout(&target, Duration::from_secs(2)).map_err(|e| e.to_string())?;
    conn.set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| e.to_string())?;
    let io = |e: std::io::Error| e.to_string();
    conn.write_all(&[5, 1, 2]).map_err(io)?;
    let mut chosen = [0u8; 2];
    conn.read_exact(&mut chosen).map_err(io)?;
    let mut login = vec![1, user.len() as u8];
    login.extend_from_slice(user.as_bytes());
    login.push(pass.len() as u8);
    login.extend_from_slice(pass.as_bytes());
    conn.write_all(&login).map_err(io)?;
    let mut ok = [0u8; 2];
    conn.read_exact(&mut ok).map_err(io)?;
    if ok[1] != 0 {
        return Err("the login was refused".to_string());
    }
    let mut connect = vec![5, 1, 0, 1];
    connect.extend_from_slice(&INTERNET.parse::<Ipv4Addr>().expect("ip").octets());
    connect.extend_from_slice(&80u16.to_be_bytes());
    conn.write_all(&connect).map_err(io)?;
    let mut reply = [0u8; 10];
    conn.read_exact(&mut reply).map_err(io)?;
    if reply[1] != 0 {
        return Err(format!("connect refused: {}", reply[1]));
    }
    conn.write_all(b"GET /probe HTTP/1.0\r\nHost: x\r\n\r\n")
        .map_err(io)?;
    let mut line = String::new();
    BufReader::new(conn).read_line(&mut line).map_err(io)?;
    if line.contains("204") {
        Ok(())
    } else {
        Err(format!("answered {:?}", line.trim()))
    }
}

// ---- the daemon, on a thread of its own, on the wall clock ---------------------------

struct SimEngine {
    addr: String,
    state: Arc<Mutex<String>>,
}

impl Engine for SimEngine {
    fn socks_addr(&self) -> String {
        self.addr.clone()
    }
    fn credentials(&self) -> (String, String) {
        ("u".to_string(), "p".to_string())
    }
    fn state(&self) -> String {
        self.state.lock().expect("state").clone()
    }
    fn stop(&self) -> Result<(), String> {
        Ok(())
    }
}

/// The tun (hev) and the probe inbound, one front.
struct TunFront {
    hev: Option<Hev>,
    probe: FakeSocks,
}

impl Front for TunFront {
    fn close(&self) -> Result<(), String> {
        let result = match &self.hev {
            Some(hev) => hev.close(),
            None => Ok(()),
        };
        self.probe.close();
        result
    }
}

struct Sim {
    dir: PathBuf,
    hev: PathBuf,
    upstream: String,
    /// Whether the carrier answers: a start of the engine fails while it does not.
    alive: Arc<Mutex<bool>>,
    engine_state: Arc<Mutex<String>>,
}

enum Message {
    Call(Request, Sender<Response>),
    Stop,
}

struct Driver {
    tx: Sender<Message>,
    thread: Option<JoinHandle<()>>,
}

fn deps(sim: &Sim) -> Deps {
    let log: Arc<dyn Fn(&str) + Send + Sync> = Arc::new(|line| eprintln!("daemon: {line}"));
    let rules = RulesRoutes::new(Arc::clone(&log));
    let manager = rules.manager();
    let (alive, engine_state, upstream) = (
        Arc::clone(&sim.alive),
        Arc::clone(&sim.engine_state),
        sim.upstream.clone(),
    );
    let start_engine = {
        let upstream = upstream.clone();
        Box::new(move |_: &OlcrtcParams| -> Result<Box<dyn Engine>, String> {
            if !*alive.lock().expect("alive") {
                return Err("carrier down".to_string());
            }
            *engine_state.lock().expect("state") = "running".to_string();
            Ok(Box::new(SimEngine {
                addr: upstream.clone(),
                state: Arc::clone(&engine_state),
            }) as Box<dyn Engine>)
        })
    };
    let start_front = {
        let (hev, dir) = (sim.hev.clone(), sim.dir.join("hev"));
        Box::new(move |p: &FrontParams| -> Result<Box<dyn Front>, String> {
            let probe = FakeSocks::serve(&p.probe_listen, &p.probe_user, "pp")?;
            let hev = if p.mode == "tun" {
                let params = HevParams {
                    binary: hev.clone(),
                    work_dir: dir.clone(),
                    socks_addr: upstream.clone(),
                    user: "u".to_string(),
                    pass: "p".to_string(),
                    bypass_uid: Some(UID as u32),
                };
                match Hev::start(Arc::clone(&manager), &params) {
                    Ok(hev) => Some(hev),
                    Err(err) => {
                        probe.close();
                        return Err(err);
                    }
                }
            } else {
                None
            };
            Ok(Box::new(TunFront { hev, probe }) as Box<dyn Front>)
        })
    };
    let clock: Clock = Arc::new(Timestamp::now);
    Deps {
        config_path: sim.dir.join("config.yaml"),
        state_dir: sim.dir.clone(),
        socket_path: None,
        fetch: Box::new(|_, _| Err(FetchError::other("no lists are fetched here"))),
        device: Device {
            os: "Linux".to_string(),
            ..Device::default()
        },
        decrypt: None,
        start_engine,
        start_xray: Box::new(|_: &XrayParams| Err("no xray here".to_string())),
        start_front,
        routes: Box::new(rules),
        probe: Box::new(probe),
        probe_inbound: Box::new(|| {
            let free = TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
            let addr = free.local_addr().map_err(|e| e.to_string())?.to_string();
            Ok((addr, "pu".to_string(), "pp".to_string()))
        }),
        clock,
        advance: Box::new(|_| {}),
        logf: Box::new(move |line| log(line)),
        uid: UID,
        fetch_delay_ms: 0,
        stop_delay_ms: 0,
        confirm_timeout_ms: 5_000,
        probe_interval_ms: 300,
        probe_failures: 2,
        retry_min_ms: 200,
        retry_max_ms: 500,
        version: VersionInfo::default(),
    }
}

impl Driver {
    fn start(sim: &Sim) -> Driver {
        let (tx, rx): (Sender<Message>, Receiver<Message>) = mpsc::channel();
        let (dir, hev, upstream) = (sim.dir.clone(), sim.hev.clone(), sim.upstream.clone());
        let (alive, engine_state) = (Arc::clone(&sim.alive), Arc::clone(&sim.engine_state));
        let thread = thread::spawn(move || {
            let sim = Sim {
                dir,
                hev,
                upstream,
                alive,
                engine_state,
            };
            let mut daemon = Daemon::new(deps(&sim));
            daemon.run();
            loop {
                match rx.recv_timeout(Duration::from_millis(15)) {
                    Ok(Message::Call(request, reply)) => {
                        let _ = reply.send(daemon.handle(request));
                    }
                    Ok(Message::Stop) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                        daemon.stop();
                        return;
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                }
                let due = daemon
                    .next_deadline()
                    .is_none_or(|at| ghostlane_cli::daemon::clock::millis(Timestamp::now()) >= at);
                if due {
                    daemon.tick();
                }
            }
        });
        Driver {
            tx,
            thread: Some(thread),
        }
    }

    fn call(&self, verb: &str) -> Response {
        let (reply, answer) = mpsc::channel();
        self.tx
            .send(Message::Call(
                Request {
                    verb: verb.to_string(),
                    ..Request::default()
                },
                reply,
            ))
            .expect("the daemon thread");
        answer
            .recv_timeout(Duration::from_secs(20))
            .expect("an answer")
    }

    fn state(&self) -> String {
        self.call("status").status.expect("a status").state
    }

    fn wait_state(&self, want: &str) {
        let until = Instant::now() + Duration::from_secs(40);
        while self.state() != want {
            assert!(
                Instant::now() < until,
                "the state never became {want}: {}",
                self.state()
            );
            thread::sleep(Duration::from_millis(50));
        }
    }

    /// A clean stop, as the program's own end.
    fn stop(mut self) {
        let _ = self.tx.send(Message::Stop);
        if let Some(thread) = self.thread.take() {
            thread.join().expect("the daemon thread");
        }
    }
}

fn hev_binary() -> PathBuf {
    let path = PathBuf::from(
        std::env::var("GHOSTLANE_HEV").expect("GHOSTLANE_HEV: the hev-socks5-tunnel program"),
    );
    assert!(path.is_file(), "{} is not a file", path.display());
    path
}

const LINE_KEY: &str = "abababababababababababababababababababababababababababababababab";

fn config(dir: &Path, kill_switch: bool) -> store::Config {
    let line = format!("olcrtc://telemost?vp8channel@room#{LINE_KEY}$DE \u{b7} olcRTC");
    let mut cfg = store::Config::defaults();
    cfg.subscriptions = vec![Subscription {
        url: format!("inline:{line}"),
        interval_hours: 1,
        ..Subscription::default()
    }];
    cfg.selection = Some(Selection {
        subscription: if kill_switch {
            String::new()
        } else {
            format!("inline:{line}")
        },
        selector: "1".to_string(),
        mode: "tun".to_string(),
        kill_switch,
        ..Selection::default()
    });
    store::save(&dir.join("config.yaml"), &cfg).expect("config.yaml");
    cfg
}

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("gl-netns-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("a directory");
    dir
}

fn sim(dir: &Path, upstream: &FakeSocks, alive: bool) -> Sim {
    Sim {
        dir: dir.to_path_buf(),
        hev: hev_binary(),
        upstream: upstream.addr.clone(),
        alive: Arc::new(Mutex::new(alive)),
        engine_state: Arc::new(Mutex::new("running".to_string())),
    }
}

// ---- the cases ------------------------------------------------------------------------

#[test]
fn netns_tun_end_to_end() {
    if !enter("netns_tun_end_to_end") {
        return;
    }
    let _peer = topology();
    // The inbound service: an "sshd" on the host's public address.
    answer(
        TcpListener::bind(format!("{HOST}:2222")).expect("listen"),
        "hi\n",
    );
    let far = |timeout| dial_from_far(&format!("{HOST}:2222"), timeout);
    assert_eq!(
        far(Duration::from_secs(3)).as_deref().ok(),
        Some("hi"),
        "before the tun, the far client reaches the host"
    );

    let upstream = FakeSocks::serve("127.0.0.1:0", "u", "p").expect("the upstream");
    let dir = scratch("tun");
    config(&dir, false);
    let world = sim(&dir, &upstream, true);
    let daemon = Driver::start(&world);
    daemon.wait_state("up");

    assert!(device_exists(TUN_NAME), "tun device");
    assert_eq!(
        rules_with_priority("-4", OWN_ADDRESS_PRIORITY),
        [format!("from {HOST} lookup main")],
        "own-address rule"
    );

    // 1. Inbound survives: the far client still reaches the host through the tun's rules.
    assert_eq!(
        far(Duration::from_secs(3)).as_deref().ok(),
        Some("hi"),
        "inbound through the tun's rules"
    );
    // 2. Outbound is tunnelled: an address with only a default route lands in the fake upstream.
    let status = dial_read(&format!("{INTERNET}:80")).expect("a tunnelled dial");
    assert!(
        status.contains("204"),
        "through the tun and the front: {status:?}"
    );
    // 3. IPv6 is refused, not leaked: the tun may accept the SYN before the
    // line rejects the connection, so no data may flow either way.
    if let Ok(mut c6) = TcpStream::connect_timeout(
        &"[2001:db8::1]:80".parse().expect("v6"),
        Duration::from_secs(3),
    ) {
        let _ = c6.set_read_timeout(Some(Duration::from_secs(3)));
        let _ = c6.write_all(b"GET / HTTP/1.0\r\n\r\n");
        let mut buf = [0u8; 16];
        if let Ok(n) = c6.read(&mut buf) {
            assert_eq!(n, 0, "IPv6 must be refused, got {:?}", &buf[..n]);
        }
    }
    // 4. The own-address rule is what keeps inbound alive: without it the far client is cut off.
    let manager = Manager::new(TUN_NAME);
    manager.clear().expect("clear");
    assert!(
        far(Duration::from_millis(1500)).is_err(),
        "without the rule the reply enters the tun and the client hangs"
    );
    let addresses = manager.global_addresses().expect("addresses");
    manager.sync(&addresses).expect("sync");
    assert_eq!(
        far(Duration::from_secs(3)).as_deref().ok(),
        Some("hi"),
        "rule restored"
    );
    // 5. Disconnect leaves nothing behind.
    daemon.call("disconnect");
    daemon.wait_state("idle");
    assert!(!device_exists(TUN_NAME), "tun device still present");
    for prio in std::iter::once(OWN_ADDRESS_PRIORITY).chain(9000..=9010) {
        let left = rules_with_priority("-4", prio);
        assert!(left.is_empty(), "rule {prio} left: {left:?}");
    }
    let left = routes_of_table("-4", 2022);
    assert!(left.is_empty(), "table 2022 left: {left:?}");
    daemon.stop();
    upstream.close();
}

/// With the kill switch stored, the box is closed while no line is up (at
/// boot, between lines), open only through the tun; inbound and the LAN keep
/// working; disconnect and a clean stop give the box back; a crash's
/// leftovers are replaced at the next start. Three answers tell the three
/// states apart: the switch's own EHOSTUNREACH (synchronous, from the
/// unreachable route), "204" from the fake upstream behind the tun, and
/// "direct" from a listener on the internet address in the peer namespace.
#[test]
fn netns_kill_switch() {
    if !enter("netns_kill_switch") {
        return;
    }
    let _peer = topology();
    answer(
        TcpListener::bind(format!("{HOST}:2222")).expect("listen"),
        "hi\n",
    );
    // the "internet" host lives in the peer namespace
    ip(&[
        "-n",
        PEER_NS,
        "addr",
        "add",
        &format!("{INTERNET}/32"),
        "dev",
        "gl-veth1",
    ]);
    let far_listener = in_peer(|| TcpListener::bind(format!("{INTERNET}:80")).expect("listen"));
    answer(far_listener, "direct\n");

    let upstream = FakeSocks::serve("127.0.0.1:0", "u", "p").expect("the upstream");
    let dir = scratch("ks");
    let cfg = config(&dir, true);
    let world = sim(&dir, &upstream, false);
    let daemon = Driver::start(&world);
    daemon.wait_state("failed");

    let closed = |what: &str| {
        let start = Instant::now();
        let err = dial_read(&format!("{INTERNET}:80")).expect_err(what);
        assert!(
            err.raw_os_error() == Some(libc::EHOSTUNREACH)
                && start.elapsed() < Duration::from_secs(2),
            "{what}: the box must be closed, at once: {err} after {:?}",
            start.elapsed()
        );
    };
    let inbound = |what: &str| {
        assert_eq!(
            dial_from_far(&format!("{HOST}:2222"), Duration::from_secs(3))
                .as_deref()
                .ok(),
            Some("hi"),
            "{what}"
        );
    };
    // 1. boot with the line down: closed, but inbound and the LAN work
    closed("at boot");
    inbound("inbound under the switch");
    let lan = TcpStream::connect_timeout(
        &format!("{PEER}:9").parse().expect("peer"),
        Duration::from_secs(2),
    )
    .expect_err("nothing listens on the peer's port 9");
    assert_eq!(
        lan.raw_os_error(),
        Some(libc::ECONNREFUSED),
        "the LAN (a specific route in main) is not killed: {lan}"
    );
    // 2. the line comes up: open through the tun only
    *world.alive.lock().expect("alive") = true;
    daemon.wait_state("up");
    let got = dial_read(&format!("{INTERNET}:80")).expect("through the tun");
    assert!(got.contains("204"), "through the tun: {got:?}");
    assert_eq!(
        rules_with_priority("-4", KILL_SWITCH_DROP).len(),
        1,
        "the switch stays while up"
    );
    // 3. the line dies (engine gone, restart refused): closed again between attempts
    *world.alive.lock().expect("alive") = false;
    *world.engine_state.lock().expect("state") = "stopped".to_string();
    let until = Instant::now() + Duration::from_secs(30);
    while daemon.state() == "up" {
        assert!(Instant::now() < until, "the line never went down");
        thread::sleep(Duration::from_millis(50));
    }
    closed("between lines");
    inbound("inbound between lines");
    // 4. disconnect gives the box back: the SYN reaches the internet host directly
    daemon.call("disconnect");
    daemon.wait_state("idle");
    assert_eq!(
        dial_read(&format!("{INTERNET}:80")).as_deref().ok(),
        Some("direct"),
        "after disconnect traffic goes direct"
    );
    for prio in [
        KILL_SWITCH_LAN,
        KILL_SWITCH_SELF,
        KILL_SWITCH_DROP,
        OWN_ADDRESS_PRIORITY,
    ] {
        let left = rules_with_priority("-4", prio);
        assert!(left.is_empty(), "rule {prio} left: {left:?}");
    }
    daemon.stop();
    // 5. a crash left the rules (with another uid); the next start with the
    // selection still stored replaces them, and a clean stop removes them
    Manager::new(TUN_NAME)
        .install_kill_switch(1)
        .expect("a crash's switch");
    store::save(&dir.join("config.yaml"), &cfg).expect("config.yaml");
    let again = Driver::start(&world);
    again.wait_state("failed");
    closed("after a restart");
    assert_eq!(
        rules_with_priority("-4", KILL_SWITCH_SELF),
        ["from all uidrange 65534-65534 lookup main"],
        "the stale uid rule is replaced by ours"
    );
    again.stop();
    let left = rules_with_priority("-4", KILL_SWITCH_DROP);
    assert!(left.is_empty(), "a clean stop removes the switch: {left:?}");
    assert_eq!(
        dial_read(&format!("{INTERNET}:80")).as_deref().ok(),
        Some("direct"),
        "after a clean stop traffic goes direct"
    );
    upstream.close();
}
