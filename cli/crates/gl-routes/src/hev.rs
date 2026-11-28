//! The tun, as the Linux desktop makes it: hev-socks5-tunnel opens the device
//! and hands what it takes to the engine's SOCKS port; the rules that send the
//! host's traffic into it are this crate's (`Manager::install_tun_rules`), not
//! a script's. The child dies with its parent (PR_SET_PDEATHSIG), and the
//! rules are taken out before it is stopped.

use std::os::unix::process::CommandExt as _;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::Manager;

pub const TUN_NAME: &str = "ghostlane0";
const TUN_MTU: u32 = 1500;
const TUN_IPV4_ADDRESS: &str = "10.0.88.88";

pub struct HevParams {
    /// The hev-socks5-tunnel program.
    pub binary: PathBuf,
    /// A directory of the daemon's own, where hev's file is written.
    pub work_dir: PathBuf,
    /// host:port of the engine's SOCKS server.
    pub socks_addr: String,
    pub user: String,
    pub pass: String,
    /// The engine's uid: what it sends keeps the main table.
    pub bypass_uid: Option<u32>,
}

pub struct Hev {
    child: Mutex<Option<Child>>,
    manager: Arc<Manager>,
}

fn quoted(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

pub fn config(params: &HevParams) -> Result<String, String> {
    let (host, port) = params
        .socks_addr
        .rsplit_once(':')
        .ok_or_else(|| format!("{:?} is not host:port", params.socks_addr))?;
    let port: u16 = port
        .parse()
        .map_err(|_| format!("{:?} has no port", params.socks_addr))?;
    let mut out = format!(
        "tunnel:\n  name: {TUN_NAME}\n  mtu: {TUN_MTU}\n  multi-queue: false\n  ipv4: {TUN_IPV4_ADDRESS}\n\nsocks5:\n  address: {host}\n  port: {port}\n"
    );
    if !params.user.is_empty() {
        out.push_str(&format!(
            "  username: {}\n  password: {}\n",
            quoted(&params.user),
            quoted(&params.pass)
        ));
    }
    out.push_str(
        "  udp: 'udp'\n  pipeline: false\n\nmisc:\n  task-stack-size: 24576\n  tcp-buffer-size: 4096\n  max-session-count: 1200\n  connect-timeout: 10000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-file: stderr\n  log-level: warn\n",
    );
    Ok(out)
}

fn device_exists(name: &str) -> bool {
    std::ffi::CString::new(name)
        .ok()
        // SAFETY: a valid NUL-terminated name.
        .is_some_and(|c| unsafe { libc::if_nametoindex(c.as_ptr()) } != 0)
}

impl Hev {
    /// Starts hev, waits for the device, puts the rules in. On any failure
    /// nothing is left.
    #[allow(unreachable_code)]
    pub fn start(manager: Arc<Manager>, params: &HevParams) -> Result<Hev, String> {
        return Err("not written (red)".to_string());
        let text = config(params)?;
        std::fs::create_dir_all(&params.work_dir)
            .map_err(|e| format!("make {}: {e}", params.work_dir.display()))?;
        let file = params.work_dir.join("tun.yml");
        std::fs::write(&file, text).map_err(|e| format!("write {}: {e}", file.display()))?;
        let mut command = Command::new(&params.binary);
        command
            .arg(&file)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit());
        // SAFETY: only prctl, which is async-signal-safe, runs between fork and exec.
        unsafe {
            command.pre_exec(|| {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL as libc::c_ulong);
                Ok(())
            });
        }
        let child = command
            .spawn()
            .map_err(|e| format!("start {}: {e}", params.binary.display()))?;
        let hev = Hev {
            child: Mutex::new(Some(child)),
            manager,
        };
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if device_exists(TUN_NAME) {
                break;
            }
            let exited = hev
                .child
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .as_mut()
                .and_then(|c| c.try_wait().ok().flatten());
            if let Some(status) = exited {
                return Err(format!("hev ended at once: {status}"));
            }
            if Instant::now() >= deadline {
                return Err(format!("hev made no device {TUN_NAME} in 5 s"));
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        hev.manager
            .install_tun_rules(TUN_NAME, params.bypass_uid)
            .map_err(|e| format!("tun rules: {e}"))?;
        Ok(hev)
    }

    /// The rules out first, then hev: nothing is left pointing at a dead device.
    pub fn close(&self) -> Result<(), String> {
        let rules = self.manager.remove_tun_rules();
        let child = self.child.lock().unwrap_or_else(|p| p.into_inner()).take();
        if let Some(mut child) = child {
            // SAFETY: the pid is our child's, not yet waited for.
            unsafe {
                libc::kill(child.id() as libc::pid_t, libc::SIGTERM);
            }
            let deadline = Instant::now() + Duration::from_secs(3);
            loop {
                match child.try_wait() {
                    Ok(Some(_)) => break,
                    Ok(None) if Instant::now() < deadline => {
                        std::thread::sleep(Duration::from_millis(20))
                    }
                    _ => {
                        let _ = child.kill();
                        let _ = child.wait();
                        break;
                    }
                }
            }
        }
        rules.map_err(|e| format!("tun rules: {e}"))
    }
}

impl Drop for Hev {
    fn drop(&mut self) {
        let _ = self.close();
    }
}
