#!/usr/bin/env python3
"""The core footprint lab's driver (standard library only, Python 3.9+).

    lab.py desktop      --bin DIR --out DIR --platform NAME [--engine-src DIR] [--tun]
    lab.py android      --bin DIR --device-bin DIR --apk FILE --out DIR
    lab.py table        FILE_OR_DIR...        prints (and adds to the job summary) one table
    lab.py shapes-check                       the lab's client shapes against the app's builders

Every run is one core on one config, between servers this script starts on
the same machine: one Xray 26.4.25 process (the version scripts/xray-servers.txt
calls the platform's own exits) holding a VLESS Reality Vision inbound, a VLESS
XHTTP Reality inbound and a Hysteria2 inbound shaped as the exits shape them,
whose only outbound sends every destination to the page (`loadgen serve`), and
a TLS 1.3 site for Reality to mirror (`loadgen tlsdest`). Traffic goes to
http://203.0.113.10/ (TEST-NET-3), which nothing answers but the server's
redirect, so a byte that arrives crossed the core and the server.

A run: start the core, wait until 1000 bytes cross it, sample it for an idle
settle, download 500 MB, pause, upload 100 MB, sample an idle tail, stop it.
Units: MiB for memory, Mbit/s (10^6 bits) for throughput, CPU seconds per GB
(10^9 bytes moved, both directions together) for cost.

Results: <out>/results.json, a list with one object per core x scenario:
platform, core, version, scenario, settings, memory_metric, idle_rss_mb,
steady_rss_mb, peak_rss_mb, go_sys_minus_released_mb, mbps_down, mbps_up,
cpu_s_per_gb, notes, extra. README.md says what each figure is.
"""

from __future__ import annotations

import argparse
import ctypes
import difflib
import json
import os
import re
import shlex
import signal
import socket
import statistics
import struct
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SHAPES = HERE / "shapes"
REFS = REPO / "sharedUI" / "src" / "jvmTest" / "resources" / "reference-configs"

MIB = 1 << 20
DOWN_BYTES = 500_000_000
UP_BYTES = 100_000_000
IDLE_SETTLE_S = 6.0
PAUSE_S = 2.0
IDLE_AFTER_S = 5.0
PERIOD_S = 0.2
DEVICE_PERIOD_S = 0.5
SOAK_S = 30

PORT_SOCKS = 10810
PORT_PAGE = 18080
PORT_DEST = 18443
PORT_REALITY = 20443
PORT_XHTTP = 20444
PORT_HY2 = 20445
UUID = "9f4b2c3a-1111-4222-8333-444455556666"
SHORT_ID = "0123abcd"
SNI_REALITY = "reality.lab.test"
SNI_XHTTP = "xhttp.lab.test"
SNI_HY2 = "hy2.lab.test"
XHTTP_PATH = "/lab"
HY2_AUTH = "lab-hy2-auth"
HY2_OBFS = "lab-hy2-obfs"
THROUGH = "203.0.113.10"
TUN_NAME = "pklab0"

PKG = "org.proofkit.app"
ACTIVITY = PKG + "/org.olcbox.app.AppActivity"
RECEIVER = PKG + "/org.olcbox.app.DebugVpnControlReceiver"
DEV_DIR = "/data/local/tmp/lab"

# The Go runtime reads these at start; a runner that happens to set one would
# change every figure without saying so.
SANITIZED = ("GOGC", "GOMEMLIMIT", "GODEBUG", "GOMAXPROCS")


class LabError(Exception):
    pass


def log(*parts):
    print("[lab %s]" % time.strftime("%H:%M:%S"), *parts, flush=True)


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if k not in SANITIZED and not k.startswith("XRAY_")}
    if extra:
        env.update(extra)
    return env


def mb(value):
    return None if value is None else round(value / MIB, 1)


def rnd(value, digits=1):
    return None if value is None else round(value, digits)


def write_json(path, data):
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(json.dumps(data, indent=1, sort_keys=False) + "\n")
    tmp.replace(path)


def tail(path, lines=25):
    try:
        text = Path(path).read_text(errors="replace").splitlines()
    except OSError:
        return ""
    return "\n".join(text[-lines:])


class Budget:
    def __init__(self, minutes):
        self.end = time.monotonic() + minutes * 60

    def left(self):
        return self.end - time.monotonic()

    def allows(self, seconds):
        return self.left() >= seconds


# ---------------------------------------------------------------- processes


LIVE = []


class Proc:
    """A child in its own session. A sudo child is stopped through sudo, by the
    real pid its ready file names; a pid of 0 or 1 is never signalled (on a
    macOS runner `kill 0` takes the whole job's process group with it)."""

    def __init__(self, argv, log_path, cwd=None, env=None, sudo=False):
        self.argv = [str(a) for a in argv]
        self.log_path = Path(log_path)
        self.sudo = sudo
        self.logf = open(self.log_path, "wb")
        cmd = (["sudo", "-n"] + self.argv) if sudo else self.argv
        self.p = subprocess.Popen(cmd, stdout=self.logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                  cwd=cwd, env=env, start_new_session=True)
        self.pid = self.p.pid
        LIVE.append(self)

    def alive(self):
        return self.p.poll() is None

    def _signal(self, sig):
        target = self.pid if self.pid and self.pid > 1 else None
        if target is None:
            return
        if self.sudo:
            subprocess.run(["sudo", "-n", "kill", "-" + sig.name.replace("SIG", ""), str(target)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        else:
            try:
                os.kill(target, sig)
            except ProcessLookupError:
                pass

    def stop(self, grace=6.0):
        if self.p.poll() is None:
            self._signal(signal.SIGTERM)
            try:
                self.p.wait(grace)
            except subprocess.TimeoutExpired:
                self._signal(signal.SIGKILL)
                if self.sudo and self.p.pid > 1:
                    subprocess.run(["sudo", "-n", "kill", "-KILL", str(self.p.pid)], check=False,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    self.p.wait(5)
                except subprocess.TimeoutExpired:
                    pass
        if not self.logf.closed:
            self.logf.close()


def port_open(port, udp=False):
    if udp:
        return True
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def wait_port(port, seconds, proc=None):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if port_open(port):
            return True
        if proc is not None and not proc.alive():
            return False
        time.sleep(0.2)
    return False


def wait_port_free(port, seconds=15):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not port_open(port):
            return True
        time.sleep(0.3)
    return False


def wait_file(path, seconds, proc=None):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            text = Path(path).read_text()
            if text.strip():
                return json.loads(text)
        except (OSError, ValueError):
            pass
        if proc is not None and not proc.alive():
            return None
        time.sleep(0.2)
    return None


def first_line(argv):
    try:
        cp = subprocess.run([str(a) for a in argv], capture_output=True, text=True, timeout=30, env=clean_env())
        out = (cp.stdout or cp.stderr).strip().splitlines()
        return out[0].strip() if out else "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


# ---------------------------------------------------------------- samplers


def proc_status_kb(text, key):
    m = re.search(r"^%s:\s+(\d+)\s+kB" % key, text, re.M)
    return int(m.group(1)) * 1024 if m else None


def stat_cpu_ticks(stat):
    rest = stat[stat.rindex(")") + 2:].split()
    return int(rest[11]) + int(rest[12])


class LinuxReader:
    clock = "utime+stime from /proc/<pid>/stat"

    def __init__(self):
        self.tck = os.sysconf("SC_CLK_TCK")

    def read(self, pid):
        with open("/proc/%d/status" % pid) as f:
            status = f.read()
        with open("/proc/%d/stat" % pid) as f:
            stat = f.read()
        return {"rss": proc_status_kb(status, "VmRSS"), "hwm": proc_status_kb(status, "VmHWM"),
                "cpu": stat_cpu_ticks(stat) / self.tck}

    def finish(self, rows, pid, notes):
        return rows


class MacReader:
    """proc_pid_rusage(RUSAGE_INFO_V4): resident size, phys_footprint (what
    jetsam judges on a phone), the lifetime maximum footprint, user + system
    time. On Apple silicon the times have been reported in mach ticks rather
    than nanoseconds, so each run is checked against `ps -o time=`."""

    LAYOUT = "<16s35Q"

    def __init__(self):
        self.lib = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
        self.lib.proc_pid_rusage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        self.lib.proc_pid_rusage.restype = ctypes.c_int

        class Timebase(ctypes.Structure):
            _fields_ = [("numer", ctypes.c_uint32), ("denom", ctypes.c_uint32)]

        tb = Timebase()
        self.lib.mach_timebase_info(ctypes.byref(tb))
        self.tick_ns = tb.numer / tb.denom if tb.denom else 1.0
        self.size = struct.calcsize(self.LAYOUT)
        self.clock = "ri_user_time+ri_system_time"
        self.checked_scale = None

    def read(self, pid):
        buf = ctypes.create_string_buffer(self.size)
        if self.lib.proc_pid_rusage(pid, 4, buf) != 0:
            raise OSError(ctypes.get_errno(), "proc_pid_rusage")
        v = struct.unpack_from(self.LAYOUT, buf.raw)[1:]
        return {"rss": v[6], "fp": v[7], "lifemax": v[28], "cpu_raw": v[0] + v[1]}

    @staticmethod
    def ps_cpu(pid):
        try:
            out = subprocess.run(["ps", "-o", "time=", "-p", str(pid)], capture_output=True, text=True,
                                 timeout=10).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return None
        if not out:
            return None
        days = 0
        if "-" in out:
            d, out = out.split("-", 1)
            days = int(d)
        total = 0.0
        for part in out.split(":"):
            total = total * 60 + float(part)
        return total + days * 86400

    def finish(self, rows, pid, notes):
        """Picks the unit of the CPU times by the one `ps` agrees with."""
        ps = self.ps_cpu(pid)
        raw = rows[-1].get("cpu_raw") if rows else None
        scale = self.checked_scale or self.tick_ns
        if ps is not None and ps >= 0.3 and raw:
            as_ns, as_ticks = raw / 1e9, raw * self.tick_ns / 1e9
            scale = 1.0 if abs(as_ns - ps) < abs(as_ticks - ps) else self.tick_ns
            self.checked_scale = scale
            notes.append("cpu clock: rusage x%.4g ns (ps %.2fs, rusage %.2fs)" % (scale, ps, raw * scale / 1e9))
        elif self.checked_scale:
            notes.append("cpu clock: rusage x%.4g ns, as checked against ps on an earlier run" % scale)
        else:
            notes.append("cpu clock: rusage x%.4g ns, not checked against ps" % scale)
        for r in rows:
            if "cpu_raw" in r:
                r["cpu"] = r["cpu_raw"] * scale / 1e9
        return rows


class Sampler(threading.Thread):
    def __init__(self, read, period):
        super().__init__(daemon=True)
        self.read = read
        self.period = period
        self.rows = []
        self._halt = threading.Event()

    def run(self):
        while not self._halt.is_set():
            t = time.time()
            try:
                row = self.read()
            except Exception:  # the process may be gone between two samples
                row = None
            if row:
                row["t"] = t
                self.rows.append(row)
            self._halt.wait(self.period)

    def halt(self):
        self._halt.set()
        self.join(10)
        return self.rows


# ---------------------------------------------------------------- statistics


def values(rows, key, t0=None, t1=None):
    out = []
    for r in rows:
        if r.get(key) is None:
            continue
        if t0 is not None and r["t"] < t0:
            continue
        if t1 is not None and r["t"] > t1:
            continue
        out.append(r[key])
    return out


def median_mid(rows, key, t0, t1):
    """The median of the middle half of a window: the state the load settled
    into, without the ramp at either end."""
    if t0 is None or t1 is None or t1 <= t0:
        return None
    q = (t1 - t0) / 4
    vals = values(rows, key, t0 + q, t1 - q) or values(rows, key, t0, t1)
    return statistics.median(vals) if vals else None


def median_in(rows, key, t0, t1):
    vals = values(rows, key, t0, t1)
    return statistics.median(vals) if vals else None


def max_of(rows, key, t0=None, t1=None):
    vals = values(rows, key, t0, t1)
    return max(vals) if vals else None


def at_or_before(rows, key, t):
    best = None
    for r in rows:
        if r.get(key) is None:
            continue
        if r["t"] <= t:
            best = r[key]
        elif best is None:
            return r[key]
        else:
            break
    return best


def at_or_after(rows, key, t):
    for r in rows:
        if r.get(key) is not None and r["t"] >= t:
            return r[key]
    vals = values(rows, key)
    return vals[-1] if vals else None


def delta(rows, key, t0, t1):
    if t0 is None or t1 is None:
        return None
    a, b = at_or_before(rows, key, t0), at_or_after(rows, key, t1)
    if a is None or b is None:
        return None
    return max(0.0, b - a)


def window_of(transfer, fallback):
    if transfer and transfer.get("start_ms") and transfer.get("end_ms") and transfer.get("ok"):
        return transfer["start_ms"] / 1000.0, transfer["end_ms"] / 1000.0
    return fallback


def cpu_per_gb(cpu_s, nbytes):
    if cpu_s is None or not nbytes:
        return None
    return cpu_s / (nbytes / 1e9)


def read_harness_stats(path):
    rows = []
    try:
        with open(path) as f:
            for line in f:
                try:
                    j = json.loads(line)
                except ValueError:
                    continue
                total, released = j.get("memory/classes/total"), j.get("memory/classes/heap/released")
                rows.append({
                    "t": j["t"] / 1000.0,
                    "sysrel": (total - released) if total is not None and released is not None else None,
                    "heap": j.get("memory/classes/heap/objects"),
                    "gc": j.get("gc/cycles/total"),
                    "goroutines": j.get("sched/goroutines"),
                    "limit": j.get("gc/gomemlimit"),
                    "gogc": j.get("gc/gogc"),
                    "released": 1 if j.get("note") == "released" else 0,
                })
    except OSError:
        pass
    return rows


# ---------------------------------------------------------------- configs


def load_json(path):
    with open(path) as f:
        return json.load(f)


def expect(cond, what):
    if not cond:
        raise LabError("the app's config shape changed: " + what)


def singbox_reality(server, pub):
    """sharedUI reference-configs/vless-reality.json (SingBoxConfig.build of a
    Reality line, which SingBoxConfigDumpTest holds equal to it), with the
    lab's address and keys."""
    c = load_json(REFS / "vless-reality.json")
    out = c["outbounds"][0]
    expect(out["type"] == "vless" and out.get("flow") == "xtls-rprx-vision", "vless-reality outbound")
    expect(out["tls"]["utls"]["fingerprint"] == "chrome" and out["tls"]["reality"]["enabled"], "vless-reality tls")
    expect(c["inbounds"][0]["type"] == "socks" and c["inbounds"][0]["listen_port"] == PORT_SOCKS, "socks inbound")
    out.update(server=server, server_port=PORT_REALITY, uuid=UUID)
    out["tls"]["server_name"] = SNI_REALITY
    out["tls"]["reality"].update(public_key=pub, short_id=SHORT_ID)
    return c


def singbox_hy2(server):
    c = load_json(REFS / "hysteria2.json")
    out = c["outbounds"][0]
    expect(out["type"] == "hysteria2" and out["obfs"]["type"] == "salamander", "hysteria2 outbound")
    expect(c["inbounds"][0]["listen_port"] == PORT_SOCKS, "socks inbound")
    out.update(server=server, server_port=PORT_HY2, password=HY2_AUTH)
    out["obfs"]["password"] = HY2_OBFS
    out["tls"]["server_name"] = SNI_HY2
    expect(out["tls"].get("insecure") is True, "hysteria2 insecure")
    return c


def singbox_ios_tun_reality(server, pub):
    """reference-configs/ios-tun-reality.json, the extension's shape for a
    Reality line (gVisor tun, fake-IP DNS), with auto_route off and a named
    interface: the lab routes only TEST-NET-3 into it."""
    c = load_json(REFS / "ios-tun-reality.json")
    tun = c["inbounds"][0]
    expect(tun["type"] == "tun" and tun.get("stack") == "gvisor", "ios tun inbound")
    tun["auto_route"] = False
    tun["interface_name"] = TUN_NAME
    out = c["outbounds"][0]
    expect(out["type"] == "vless", "ios tun outbound")
    out.update(server=server, server_port=PORT_REALITY, uuid=UUID)
    out["tls"]["server_name"] = SNI_REALITY
    out["tls"]["reality"].update(public_key=pub, short_id=SHORT_ID)
    return c


def xray_xhttp(server, pub, answers_dns):
    """XrayConfig.buildXhttp as XrayConfigDumpTest writes it (shapes/, checked
    against a fresh dump by `lab.py shapes-check`), with the lab's address,
    keys, name and path. answers_dns is the iPhone's shape behind hev."""
    c = load_json(SHAPES / ("vless-xhttp-answers-dns.json" if answers_dns else "vless-xhttp.json"))
    out = c["outbounds"][0]
    expect(out["protocol"] == "vless" and out["streamSettings"]["network"] == "xhttp", "xhttp outbound")
    expect(c["inbounds"][0]["port"] == PORT_SOCKS, "socks inbound")
    vnext = out["settings"]["vnext"][0]
    vnext.update(address=server, port=PORT_XHTTP)
    vnext["users"][0]["id"] = UUID
    ss = out["streamSettings"]
    ss["realitySettings"].update(serverName=SNI_XHTTP, publicKey=pub, shortId=SHORT_ID)
    ss["xhttpSettings"].update(path=XHTTP_PATH, host=SNI_XHTTP)
    expect(ss["xhttpSettings"]["mode"] == "packet-up", "xhttp mode")
    return c


def xray_server(priv, cert, key):
    """One Xray 26.4.25 for the three protocols. The inbounds follow the
    exits' shapes (Hysteria2: version 2, clients with auth, alpn h3,
    udpIdleTimeout 60, Salamander under finalmask.udp; XHTTP: mode auto, host
    = the name), on loopback. Its one outbound is the page: `redirect` for
    every destination, `ipsBlocked: []` (up to 26.4) and `finalRules` allowing
    loopback (from 26.7), as cli/internal/engine/singbox/xray_e2e_test.go
    freedomToPage does."""
    dest = "127.0.0.1:%d" % PORT_DEST
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {"tag": "reality-vision", "listen": "127.0.0.1", "port": PORT_REALITY, "protocol": "vless",
             "settings": {"clients": [{"id": UUID, "flow": "xtls-rprx-vision", "email": "lab-reality"}],
                          "decryption": "none"},
             "streamSettings": {"network": "tcp", "security": "reality",
                                "realitySettings": {"dest": dest, "serverNames": [SNI_REALITY],
                                                    "privateKey": priv, "shortIds": [SHORT_ID]}}},
            {"tag": "xhttp-reality", "listen": "127.0.0.1", "port": PORT_XHTTP, "protocol": "vless",
             "settings": {"clients": [{"id": UUID, "email": "lab-xhttp"}], "decryption": "none"},
             "streamSettings": {"network": "xhttp", "security": "reality",
                                "realitySettings": {"dest": dest, "serverNames": [SNI_XHTTP],
                                                    "privateKey": priv, "shortIds": [SHORT_ID]},
                                "xhttpSettings": {"host": SNI_XHTTP, "path": XHTTP_PATH, "mode": "auto"}}},
            {"tag": "hy2", "listen": "127.0.0.1", "port": PORT_HY2, "protocol": "hysteria",
             "settings": {"version": 2, "clients": [{"auth": HY2_AUTH, "email": "lab-hy2", "level": 0}]},
             "streamSettings": {"network": "hysteria", "security": "tls",
                                "tlsSettings": {"alpn": ["h3"],
                                                "certificates": [{"certificateFile": str(cert), "keyFile": str(key)}]},
                                "hysteriaSettings": {"version": 2, "udpIdleTimeout": 60},
                                "finalmask": {"udp": [{"type": "salamander", "settings": {"password": HY2_OBFS}}]}}},
        ],
        "outbounds": [
            {"tag": "page", "protocol": "freedom",
             "settings": {"redirect": "127.0.0.1:%d" % PORT_PAGE, "ipsBlocked": [],
                          "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}},
        ],
    }


def links_for_app(server, pub):
    """The lines a person would paste, parsed by the app's own LinkParser."""
    q = urllib.parse.quote
    return {
        "reality": "vless://%s@%s:%d?type=tcp&security=reality&encryption=none&flow=xtls-rprx-vision&pbk=%s&sid=%s&fp=chrome&sni=%s#lab-reality"
                   % (UUID, server, PORT_REALITY, pub, SHORT_ID, SNI_REALITY),
        "hy2": "hysteria2://%s@%s:%d?sni=%s&obfs=salamander&obfs-password=%s&insecure=1#lab-hy2"
               % (HY2_AUTH, server, PORT_HY2, SNI_HY2, HY2_OBFS),
        "xhttp": "vless://%s@%s:%d?type=xhttp&security=reality&encryption=none&pbk=%s&sid=%s&fp=chrome&sni=%s&path=%s&host=%s&mode=packet-up#lab-xhttp"
                 % (UUID, server, PORT_XHTTP, pub, SHORT_ID, SNI_XHTTP, q(XHTTP_PATH, safe=""), SNI_XHTTP),
    }


# ---------------------------------------------------------------- the servers


class Servers:
    def __init__(self, out, loadgen, xray_server_bin):
        self.dir = out / "servers"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.loadgen = loadgen
        self.xray = xray_server_bin
        self.procs = []
        self.priv = self.pub = None

    def start(self):
        keys = json.loads(subprocess.run([str(self.loadgen), "x25519"], capture_output=True, text=True,
                                         check=True, timeout=30).stdout)
        self.priv, self.pub = keys["private"], keys["public"]
        cert, key = self.dir / "hy2.crt", self.dir / "hy2.key"
        subprocess.run([str(self.loadgen), "selfsigned", "-cert", str(cert), "-key", str(key), "-name", SNI_HY2],
                       check=True, timeout=30)
        page = Proc([self.loadgen, "serve", "-listen", "127.0.0.1:%d" % PORT_PAGE], self.dir / "page.log", env=clean_env())
        self.procs.append(page)
        dest = Proc([self.loadgen, "tlsdest", "-listen", "127.0.0.1:%d" % PORT_DEST], self.dir / "tlsdest.log",
                    env=clean_env())
        self.procs.append(dest)
        config = self.dir / "xray-server.json"
        write_json(config, xray_server(self.priv, cert, key))
        test = subprocess.run([str(self.xray), "run", "-test", "-c", str(config)], capture_output=True, text=True,
                              timeout=60, env=clean_env())
        if test.returncode != 0:
            raise LabError("the server config does not load:\n" + test.stdout + test.stderr)
        server = Proc([self.xray, "run", "-c", config], self.dir / "xray-server.log", cwd=self.dir, env=clean_env())
        self.procs.append(server)
        for port, proc in ((PORT_PAGE, page), (PORT_DEST, dest), (PORT_REALITY, server), (PORT_XHTTP, server)):
            if not wait_port(port, 20, proc):
                raise LabError("port %d did not open:\n%s" % (port, tail(proc.log_path)))
        self.version = first_line([self.xray, "version"])
        log("servers up:", self.version)

    def stop(self):
        for p in reversed(self.procs):
            p.stop()


# ---------------------------------------------------------------- loadgen


def parse_loadgen(stdout, stderr):
    for line in reversed((stdout or "").splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                break
    return {"ok": False, "error": (stderr or stdout or "no output").strip()[-400:]}


def transfer_argv(op, nbytes, socks, timeout_s):
    if op == "get":
        argv = ["get", "-url", "http://%s/bytes?n=%d" % (THROUGH, nbytes), "-expect", str(nbytes)]
    else:
        argv = ["put", "-url", "http://%s/sink" % THROUGH, "-bytes", str(nbytes)]
    argv += ["-timeout", "%ds" % timeout_s]
    if socks:
        argv += ["-socks", socks]
    return argv


class LocalHost:
    def __init__(self, loadgen):
        self.loadgen = loadgen

    def loadgen_run(self, op, nbytes, socks, timeout_s):
        argv = [str(self.loadgen)] + transfer_argv(op, nbytes, socks, timeout_s)
        try:
            cp = subprocess.run(argv, capture_output=True, text=True, timeout=timeout_s + 30, env=clean_env())
            return parse_loadgen(cp.stdout, cp.stderr)
        except subprocess.TimeoutExpired:
            return {"ok": False, "error": "loadgen did not return"}

    def probe(self, socks, seconds=30):
        deadline = time.time() + seconds
        last = {"ok": False, "error": "not tried"}
        while time.time() < deadline:
            last = self.loadgen_run("get", 1000, socks, 8)
            if last.get("ok"):
                return last
            time.sleep(0.5)
        return last


# ---------------------------------------------------------------- result rows


def base_row(platform, core, version, scenario, settings, metric):
    return {"platform": platform, "core": core, "version": version, "scenario": scenario, "settings": settings,
            "memory_metric": metric, "idle_rss_mb": None, "steady_rss_mb": None, "peak_rss_mb": None,
            "go_sys_minus_released_mb": None, "mbps_down": None, "mbps_up": None, "cpu_s_per_gb": None,
            "notes": "", "extra": {}}


def transfer_figures(row, down, up, rows, wins, cpu_key="cpu"):
    (d0, d1), (u0, u1) = wins
    row["mbps_down"] = rnd(down.get("mbps")) if down.get("ok") else None
    row["mbps_up"] = rnd(up.get("mbps")) if up.get("ok") else None
    cpu_d, cpu_u = delta(rows, cpu_key, d0, d1), delta(rows, cpu_key, u0, u1)
    moved_d = down.get("bytes") if down.get("ok") else 0
    moved_u = up.get("bytes") if up.get("ok") else 0
    if cpu_d is not None and cpu_u is not None and (moved_d or moved_u):
        row["cpu_s_per_gb"] = rnd(cpu_per_gb(cpu_d + cpu_u, (moved_d or 0) + (moved_u or 0)), 2)
    row["extra"].update({
        "cpu_s_download": rnd(cpu_d, 2), "cpu_s_upload": rnd(cpu_u, 2),
        "cpu_s_per_gb_down": rnd(cpu_per_gb(cpu_d, moved_d), 2) if moved_d else None,
        "cpu_s_per_gb_up": rnd(cpu_per_gb(cpu_u, moved_u), 2) if moved_u else None,
        "ttfb_down_s": rnd(down.get("ttfb_s"), 3), "seconds_down": rnd(down.get("seconds"), 2),
        "seconds_up": rnd(up.get("seconds"), 2),
    })
    errors = [("download: " + down.get("error", "")) if not down.get("ok") else "",
              ("upload: " + up.get("error", "")) if not up.get("ok") else ""]
    return [e for e in errors if e]


def harness_figures(row, h, t_ready, idle_win, down_win):
    if not h:
        return
    after = [r for r in h if r["t"] >= t_ready - 0.5] or h
    row["go_sys_minus_released_mb"] = mb(max_of(after, "sysrel"))
    gcs = values(after, "gc")
    row["extra"].update({
        "go_sys_minus_released_idle_mb": mb(median_in(h, "sysrel", *idle_win)),
        "go_sys_minus_released_steady_mb": mb(median_mid(h, "sysrel", *down_win)),
        "go_heap_objects_peak_mb": mb(max_of(after, "heap")),
        "go_heap_objects_idle_mb": mb(median_in(h, "heap", *idle_win)),
        "go_gc_cycles": (gcs[-1] - gcs[0]) if gcs else None,
        "go_goroutines_peak": max_of(after, "goroutines"),
        "go_memory_limit_mb": mb(after[-1].get("limit") or 0) if (after[-1].get("limit") or 0) < (1 << 62) else "none",
        "go_gc_percent": after[-1].get("gogc"),
        "memorywatch_releases": sum(r["released"] for r in after),
    })


# ---------------------------------------------------------------- desktop


class Spec:
    def __init__(self, **kw):
        self.kind = kw["kind"]              # exec | harness | harness-tun
        self.core = kw["core"]
        self.scenario = kw["scenario"]
        self.settings = kw["settings"]
        self.argv = kw.get("argv")          # exec
        self.config = kw.get("config")      # dict
        self.harness_core = kw.get("harness_core")
        self.mode = kw.get("mode")
        self.socks = kw.get("socks", "127.0.0.1:%d" % PORT_SOCKS)
        self.version = kw.get("version", "unknown")
        self.notes = list(kw.get("notes", []))
        self.slug = re.sub(r"[^a-z0-9]+", "-", ("%s %s %s" % (self.core, self.scenario, self.settings)).lower()).strip("-")


def run_desktop_spec(spec, ctx):
    rundir = ctx["out"] / "runs" / spec.slug
    rundir.mkdir(parents=True, exist_ok=True)
    reader, host, platform = ctx["reader"], ctx["host"], ctx["platform"]
    notes = list(spec.notes)
    metric = "resident size (VmRSS)" if isinstance(reader, LinuxReader) else "resident size (ri_resident_size)"
    row = base_row(platform, spec.core, spec.version, spec.scenario, spec.settings, metric)
    if spec.socks and not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken by the previous run" % PORT_SOCKS)
    cfg_path = None
    if spec.config is not None:
        cfg_path = rundir / "config.json"
        write_json(cfg_path, spec.config)
    sudo = spec.kind == "harness-tun"
    if spec.kind == "exec":
        argv = [a if a != "{config}" else str(cfg_path) for a in spec.argv]
    else:
        argv = [ctx["bin"] / "harness", "-core", spec.harness_core, "-mode", spec.mode,
                "-stats", rundir / "stats.jsonl", "-ready", rundir / "ready.json"]
        if cfg_path:
            argv += ["-config", cfg_path]
    t_launch = time.time()
    proc = Proc(argv, rundir / "core.log", cwd=rundir, env=clean_env(), sudo=sudo)
    rows, final = [], {}
    down = up = {"ok": False, "error": "not run"}
    idle_win = (None, None)
    wins = ((None, None), (None, None))
    t_ready = time.time()
    sampler = None
    try:
        if spec.kind != "exec":
            ready = wait_file(rundir / "ready.json", 40, proc)
            if ready is None:
                raise LabError("the harness never said ready:\n" + tail(rundir / "core.log"))
            proc.pid = int(ready["pid"])
            row["version"] = ", ".join("%s %s" % kv for kv in sorted(ready.get("versions", {}).items()))
            row["extra"]["ready"] = {k: ready.get(k) for k in ("limit_in_force", "gc_percent", "footprint_bytes")}
        t_ready = time.time()
        row["extra"]["start_to_ready_s"] = rnd(t_ready - t_launch, 2)
        if sudo:
            r = subprocess.run(["sudo", "-n", "ip", "route", "replace", THROUGH.rsplit(".", 1)[0] + ".0/24",
                                "dev", TUN_NAME], capture_output=True, text=True)
            if r.returncode != 0:
                raise LabError("route into %s: %s" % (TUN_NAME, r.stderr.strip()))
        probe = host.probe(spec.socks, 40)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core in 40 s (%s):\n%s" % (probe.get("error"), tail(rundir / "core.log")))
        row["extra"]["first_bytes_s"] = rnd(time.time() - t_ready, 2)
        sampler = Sampler(lambda: reader.read(proc.pid), PERIOD_S)
        sampler.start()
        t0 = time.time()
        time.sleep(IDLE_SETTLE_S)
        idle_win = (t0 + IDLE_SETTLE_S / 2, time.time())
        h0 = time.time()
        down = host.loadgen_run("get", DOWN_BYTES, spec.socks, 240)
        h1 = time.time()
        time.sleep(PAUSE_S)
        h2 = time.time()
        up = host.loadgen_run("put", UP_BYTES, spec.socks, 180)
        h3 = time.time()
        time.sleep(IDLE_AFTER_S)
        t_end = time.time()
        try:
            final = reader.read(proc.pid)
        except Exception:
            final = {}
        rows = sampler.halt()
        rows = reader.finish(rows, proc.pid, notes)
        wins = (window_of(down, (h0, h1)), window_of(up, (h2, h3)))
        row["extra"]["idle_after_rss_mb"] = mb(median_in(rows, "rss", h3 + 1, t_end))
    finally:
        if sampler is not None and sampler.is_alive():
            sampler.halt()
        proc.stop()
    row["idle_rss_mb"] = mb(median_in(rows, "rss", *idle_win))
    row["steady_rss_mb"] = mb(median_mid(rows, "rss", *wins[0]))
    sampled_peak = max_of(rows, "rss")
    true_peak = final.get("hwm") or None
    row["peak_rss_mb"] = mb(max(v for v in (sampled_peak, true_peak) if v is not None)) if (sampled_peak or true_peak) else None
    row["extra"]["peak_rss_during_transfers_mb"] = mb(max_of(rows, "rss", wins[0][0], wins[1][1]))
    if "fp" in (rows[0] if rows else {}):
        row["extra"].update({
            "idle_phys_footprint_mb": mb(median_in(rows, "fp", *idle_win)),
            "steady_phys_footprint_mb": mb(median_mid(rows, "fp", *wins[0])),
            "peak_phys_footprint_mb": mb(max(final.get("lifemax") or 0, max_of(rows, "fp") or 0) or None),
        })
    notes += transfer_figures(row, down, up, rows, wins)
    if spec.kind != "exec":
        harness_figures(row, read_harness_stats(rundir / "stats.jsonl"), t_ready, idle_win, wins[0])
    row["notes"] = "; ".join(n for n in notes if n)
    return row


def run_idle_harness(mode, ctx):
    """The harness with no core started: the floor of a runtime that links all
    three, which is what the extension carries before any line is up."""
    rundir = ctx["out"] / "runs" / ("harness-none-" + mode)
    rundir.mkdir(parents=True, exist_ok=True)
    reader = ctx["reader"]
    row = base_row(ctx["platform"], "cores harness (no core started)", "unknown", "idle", ctx["settings_name"][mode],
                   "resident size")
    notes = ["one Go runtime linking sing-box, libXray and the olcRTC engine; nothing started"]
    proc = Proc([ctx["bin"] / "harness", "-core", "none", "-mode", mode, "-stats", rundir / "stats.jsonl",
                 "-ready", rundir / "ready.json"], rundir / "core.log", cwd=rundir, env=clean_env())
    rows, final = [], {}
    try:
        ready = wait_file(rundir / "ready.json", 30, proc)
        if ready is None:
            raise LabError("the harness never said ready:\n" + tail(rundir / "core.log"))
        row["version"] = ", ".join("%s %s" % kv for kv in sorted(ready.get("versions", {}).items()))
        t_ready = time.time()
        sampler = Sampler(lambda: reader.read(proc.pid), PERIOD_S)
        sampler.start()
        time.sleep(8)
        try:
            final = reader.read(proc.pid)
        except Exception:
            final = {}
        rows = reader.finish(sampler.halt(), proc.pid, notes)
    finally:
        proc.stop()
    t1 = rows[-1]["t"] if rows else time.time()
    row["idle_rss_mb"] = mb(median_in(rows, "rss", t1 - 4, t1))
    row["peak_rss_mb"] = mb(max(final.get("hwm") or 0, max_of(rows, "rss") or 0) or None)
    if rows and "fp" in rows[0]:
        row["extra"]["idle_phys_footprint_mb"] = mb(median_in(rows, "fp", t1 - 4, t1))
    h = read_harness_stats(rundir / "stats.jsonl")
    harness_figures(row, h, t_ready, (t1 - 4, t1), (None, None))
    row["notes"] = "; ".join(notes)
    return row


SOAK_DONE = re.compile(r"\[soak\] DONE transport=(\S+) elapsed=(\S+) sent=([\d.]+ \w+) recv=([\d.]+ \w+) "
                       r"send=([\d.]+ \w+)/s recv=([\d.]+ \w+)/s")
UNITS = {"B": 1, "KiB": 1 << 10, "MiB": 1 << 20, "GiB": 1 << 30, "TiB": 1 << 40}


def human_bytes(text):
    num, unit = text.split()
    return float(num) * UNITS[unit]


def go_duration(text):
    total = 0.0
    for part in re.findall(r"([\d.]+)(ms|us|µs|ns|h|m|s)", text):
        n, u = float(part[0]), part[1]
        total += n * {"h": 3600, "m": 60, "s": 1, "ms": 1e-3, "us": 1e-6, "µs": 1e-6, "ns": 1e-9}[u]
    return total or None


def run_soak(transport, settings, ctx):
    """The engine's own loopback soak (internal/e2e TestLocalThroughputSoak):
    a server and a client over the in-memory provider, echo pump, both in this
    one process. vp8channel runs real pion PeerConnections between the two."""
    rundir = ctx["out"] / "runs" / ("olcrtc-soak-%s-%s" % (transport, settings))
    rundir.mkdir(parents=True, exist_ok=True)
    reader = ctx["reader"]
    row = base_row(ctx["platform"], "olcrtc engine (loopback soak)", ctx["olcrtc_version"],
                   "%s, in-memory provider, echo" % transport, settings, "resident size")
    notes = ["client AND server in one process: memory and CPU cover two peers",
             "throughput is what the loopback pump reaches, CPU-bound; no meeting service, no network",
             "vp8channel at the e2e options (30 fps, batch 64), not the app's 60 fps"]
    env = clean_env({"GOGC": "10", "GOMEMLIMIT": "40MiB"} if settings == "ios-limits" else None)
    if settings == "ios-limits":
        notes.append("GOGC=10 GOMEMLIMIT=40MiB through the environment, the extension's percent and limit")
    argv = [ctx["bin"] / "olcrtc-e2e.test", "-test.run", "^TestLocalThroughputSoak$", "-test.v", "-test.count=1",
            "-test.timeout=5m", "-olcrtc.local-soak", "-olcrtc.local-soak-duration=%ds" % SOAK_S,
            "-olcrtc.local-soak-transport=" + transport, "-olcrtc.local-soak-progress=5s"]
    logp = rundir / "soak.log"
    proc = Proc(argv, logp, cwd=ctx["engine_src"] / "internal" / "e2e", env=env)
    sampler = Sampler(lambda: reader.read(proc.pid), PERIOD_S)
    sampler.start()
    t_done = None
    deadline = time.time() + SOAK_S + 150
    final = {}
    try:
        while proc.alive() and time.time() < deadline:
            if t_done is None and "[soak] DONE" in Path(logp).read_text(errors="replace"):
                t_done = time.time()
            if t_done is None:
                try:
                    final = reader.read(proc.pid)
                except Exception:
                    pass
            time.sleep(0.2)
        rows = reader.finish(sampler.halt(), proc.pid, notes)
    finally:
        proc.stop()
    text = Path(logp).read_text(errors="replace")
    m = SOAK_DONE.search(text)
    if not m:
        raise LabError("the soak printed no DONE line:\n" + tail(logp, 40))
    t_done = t_done or (rows[-1]["t"] if rows else time.time())
    elapsed = go_duration(m.group(2)) or SOAK_S
    p0, p1 = t_done - elapsed, t_done
    sent, recv = human_bytes(m.group(3)), human_bytes(m.group(4))
    row["mbps_up"] = rnd(human_bytes(m.group(5)) * 8 / 1e6)
    row["mbps_down"] = rnd(human_bytes(m.group(6)) * 8 / 1e6)
    row["steady_rss_mb"] = mb(median_mid(rows, "rss", p0, p1))
    row["peak_rss_mb"] = mb(max(final.get("hwm") or 0, max_of(rows, "rss") or 0) or None)
    row["idle_rss_mb"] = mb(at_or_before(rows, "rss", p0))
    cpu = delta(rows, "cpu", p0, p1)
    row["cpu_s_per_gb"] = rnd(cpu_per_gb(cpu, sent + recv), 2)
    row["extra"].update({"sent_bytes": int(sent), "recv_bytes": int(recv), "pump_s": elapsed, "cpu_s_pump": rnd(cpu, 2)})
    if rows and "fp" in rows[0]:
        row["extra"]["steady_phys_footprint_mb"] = mb(median_mid(rows, "fp", p0, p1))
        row["extra"]["peak_phys_footprint_mb"] = mb(max(final.get("lifemax") or 0, max_of(rows, "fp") or 0) or None)
    notes.append("idle_rss_mb is the last sample before the pump started, with the session up")
    row["notes"] = "; ".join(notes)
    return row


def size_row(platform, name, path, what):
    row = base_row(platform, name, "", "binary size", "", "")
    try:
        row["extra"]["bytes"] = Path(path).stat().st_size
        row["extra"]["mb"] = mb(Path(path).stat().st_size)
        row["notes"] = what
    except OSError as e:
        row["notes"] = "missing: %s" % e
    return row


def failed_row(platform, spec_core, scenario, settings, err):
    row = base_row(platform, spec_core, "", scenario, settings, "")
    row["notes"] = "FAILED: %s" % err
    return row


def cmd_desktop(a):
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = Path(a.bin).resolve()
    budget = Budget(a.budget_min)
    is_mac = sys.platform == "darwin"
    reader = MacReader() if is_mac else LinuxReader()
    singbox, xray_client, xray_srv = b / "sing-box", b / "xray-26.7.11" / "xray", b / "xray-26.4.25" / "xray"
    results = []
    results_path = out / "results.json"

    def add(row):
        results.append(row)
        write_json(results_path, results)
        print_table([row], header=False)

    servers = Servers(out, b / "loadgen", xray_srv)
    servers.start()
    srv_note = "server: " + servers.version
    ctx = {"out": out, "bin": b, "reader": reader, "host": LocalHost(b / "loadgen"), "platform": a.platform,
           "settings_name": {"ios": "ios-limits", "plain": "go-defaults"},
           "engine_src": Path(a.engine_src).resolve() if a.engine_src else None,
           "olcrtc_version": pins().get("OLCRTC_VERSION", "unknown")}
    sb_version = first_line([singbox, "version"])
    xr_version = first_line([xray_client, "version"])
    loop = "127.0.0.1"
    ios_note = ("-mode ios: LibboxSetMemoryLimit(true) (GC 10, 45 MiB set explicitly), then for Xray the h2 windows "
                "and libXray's InitForceFree (GC 10, 30 MiB, FreeOSMemory every 1 s), then MobileSetMemoryLimit "
                "40 MiB, and MemoryWatch's release above 36 MiB")
    specs = []
    for mode in ("ios", "plain"):
        settings = ctx["settings_name"][mode]
        extra = [ios_note] if mode == "ios" else ["Go defaults inside the iOS composition"]
        specs += [
            Spec(kind="harness", core="cores harness: sing-box", harness_core="singbox", mode=mode, settings=settings,
                 scenario="vless-reality-vision", config=singbox_reality(loop, servers.pub),
                 notes=extra + ["socks shape vless-reality.json (the extension's is a gVisor tun)", srv_note]),
            Spec(kind="harness", core="cores harness: sing-box", harness_core="singbox", mode=mode, settings=settings,
                 scenario="hysteria2-salamander", config=singbox_hy2(loop),
                 notes=extra + ["socks shape hysteria2.json (the extension's is a gVisor tun)", srv_note]),
            Spec(kind="harness", core="cores harness: xray (libXray)", harness_core="xray", mode=mode,
                 settings=settings, scenario="vless-xhttp-reality packet-up",
                 config=xray_xhttp(loop, servers.pub, answers_dns=True),
                 notes=extra + ["the iPhone's answers-dns shape (behind hev on a phone)", srv_note]),
        ]
        if mode == "ios":
            specs += [
                Spec(kind="exec", core="sing-box (exec)", argv=[singbox, "run", "-c", "{config}"], settings="go-defaults",
                     scenario="vless-reality-vision", config=singbox_reality(loop, servers.pub), version=sb_version,
                     notes=["scripts/build-singbox.sh, the desktop recipe; Android exec's the same source", srv_note]),
                Spec(kind="exec", core="sing-box (exec)", argv=[singbox, "run", "-c", "{config}"], settings="go-defaults",
                     scenario="hysteria2-salamander", config=singbox_hy2(loop), version=sb_version,
                     notes=["scripts/build-singbox.sh", srv_note]),
                Spec(kind="exec", core="xray (exec)", argv=[xray_client, "run", "-c", "{config}"], settings="go-defaults",
                     scenario="vless-xhttp-reality packet-up", config=xray_xhttp(loop, servers.pub, answers_dns=False),
                     version=xr_version, notes=["official release zip, as the desktop app ships it", srv_note]),
            ]
    if a.tun:
        specs.append(Spec(kind="harness-tun", core="cores harness: sing-box", harness_core="singbox", mode="ios",
                          settings="ios-limits", scenario="gVisor tun, vless-reality-vision", socks=None,
                          config=singbox_ios_tun_reality(loop, servers.pub),
                          notes=[ios_note, "ios-tun-reality.json with auto_route off; root via sudo; the kernel routes "
                                 "203.0.113.0/24 into the tun, the way NEPacketTunnelFlow hands packets to libbox",
                                 srv_note]))
    try:
        for spec in specs:
            if not budget.allows(90):
                add(failed_row(a.platform, spec.core, spec.scenario, spec.settings, "skipped: out of time budget"))
                continue
            log("run:", spec.core, "|", spec.scenario, "|", spec.settings)
            try:
                add(run_desktop_spec(spec, ctx))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, spec.core, spec.scenario, spec.settings, e))
        for mode in ("ios", "plain"):
            if not budget.allows(30):
                break
            try:
                add(run_idle_harness(mode, ctx))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, "cores harness (no core started)", "idle", mode, e))
        if ctx["engine_src"] and (b / "olcrtc-e2e.test").exists():
            for transport in ("vp8channel", "datachannel"):
                for settings in ("ios-limits", "go-defaults"):
                    if not budget.allows(SOAK_S + 120):
                        add(failed_row(a.platform, "olcrtc engine (loopback soak)", transport, settings,
                                       "skipped: out of time budget"))
                        continue
                    log("soak:", transport, settings)
                    try:
                        add(run_soak(transport, settings, ctx))
                    except Exception as e:
                        traceback.print_exc()
                        add(failed_row(a.platform, "olcrtc engine (loopback soak)", transport, settings, e))
    finally:
        servers.stop()
    for name, path, what in (
            ("sing-box (exec)", singbox, "build-singbox.sh, CGO 0, release tags, -s -w"),
            ("xray (exec)", xray_client, "official Xray-core 26.7.11 release"),
            ("cores harness", b / "harness", "sing-box + libXray + olcRTC mobile in one binary, the Cores' tags, -s -w; "
                                             "a stand-in for one slice of Cores.xcframework, not its size"),
            ("olcrtc (desktop)", b / "olcrtc", "desktopApp registerOlcRtcBuildTask recipe")):
        add(size_row(a.platform, name, path, what))
    write_json(results_path, results)
    print_table(results)
    return 0


def pins():
    out = {}
    try:
        for line in (REPO / "scripts" / "cores-pins.sh").read_text().splitlines():
            m = re.match(r'^([A-Z_]+)="\$\{\1:-([^}]*)\}"', line)
            if m:
                out[m.group(1)] = m.group(2)
    except OSError:
        pass
    return out


# ---------------------------------------------------------------- android


class Adb:
    def __init__(self):
        self.base = ["adb"]

    def sh(self, cmd, timeout=90):
        try:
            cp = subprocess.run(self.base + ["shell", cmd], capture_output=True, text=True, timeout=timeout)
            return cp.returncode, (cp.stdout or "").strip(), (cp.stderr or "").strip()
        except subprocess.TimeoutExpired:
            return 124, "", "timeout"

    def run(self, args, timeout=300):
        cp = subprocess.run(self.base + args, capture_output=True, text=True, timeout=timeout)
        return cp.returncode, (cp.stdout or "").strip(), (cp.stderr or "").strip()

    def popen(self, cmd):
        return subprocess.Popen(self.base + ["shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                text=True, bufsize=1, start_new_session=True)


class DeviceHost:
    """loadgen on the emulator, as the shell user (whose traffic the app's VPN
    carries like any other app's)."""

    def __init__(self, adb):
        self.adb = adb

    def loadgen_run(self, op, nbytes, socks, timeout_s):
        argv = [DEV_DIR + "/loadgen"] + transfer_argv(op, nbytes, socks, timeout_s)
        t0 = time.time()
        code, out, err = self.adb.sh(" ".join(shlex.quote(x) for x in argv), timeout=timeout_s + 40)
        res = parse_loadgen(out, err)
        res["host_window"] = (t0, time.time())
        if res.get("ok") and res.get("seconds"):
            res["host_window"] = (time.time() - res["seconds"], time.time())
        return res

    def probe(self, socks, seconds=30):
        deadline = time.time() + seconds
        last = {"ok": False, "error": "not tried"}
        while time.time() < deadline:
            last = self.loadgen_run("get", 1000, socks, 8)
            if last.get("ok"):
                return last
            time.sleep(1)
        return last


class DeviceSampler(threading.Thread):
    """One shell loop on the device printing, per pid and per period, VmRSS,
    VmHWM, /proc/<pid>/stat and the Pss of smaps_rollup; through run-as for
    the app's own processes (smaps needs the same uid). Lines are stamped on
    arrival here."""

    def __init__(self, adb, pids, runas, period=DEVICE_PERIOD_S, max_iter=1600):
        super().__init__(daemon=True)
        script = ('i=0; while [ $i -lt %d ]; do for p in %s; do '
                  'r=$(grep -E "^(VmRSS|VmHWM):" /proc/$p/status 2>/dev/null | tr -s " " | tr "\\n" " "); '
                  's=$(cat /proc/$p/stat 2>/dev/null); '
                  'q=$(grep "^Pss:" /proc/$p/smaps_rollup 2>/dev/null | tr -s " "); '
                  'echo "S $p|$r|$s|$q"; done; i=$((i+1)); sleep %s; done') % (
            max_iter, " ".join(str(p) for p in pids), period)
        cmd = ("run-as %s sh -c '%s'" % (PKG, script)) if runas else ("sh -c '%s'" % script)
        self.proc = adb.popen(cmd)
        self.rows = {str(p): [] for p in pids}

    def run(self):
        for line in self.proc.stdout:
            t = time.time()
            if not line.startswith("S "):
                continue
            parts = line[2:].rstrip("\n").split("|", 3)
            if len(parts) < 4:
                continue
            pid, status, stat, pss = parts
            row = {"t": t, "rss": proc_status_kb(status.replace("kB ", "kB\n"), "VmRSS"),
                   "hwm": proc_status_kb(status.replace("kB ", "kB\n"), "VmHWM")}
            try:
                row["cpu"] = stat_cpu_ticks(stat) / 100.0
            except (ValueError, IndexError):
                row["cpu"] = None
            m = re.search(r"Pss: (\d+) kB", pss)
            row["pss"] = int(m.group(1)) * 1024 if m else None
            if pid in self.rows:
                self.rows[pid].append(row)

    def halt(self):
        if self.proc.poll() is None:
            if self.proc.pid > 1:
                try:
                    os.killpg(self.proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.join(5)
        return self.rows


def dumpsys_pss_kb(adb):
    code, out, _ = adb.sh("dumpsys meminfo " + PKG, timeout=30)
    m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
    return int(m.group(1)) if m else None


class DumpsysLoop(threading.Thread):
    def __init__(self, adb):
        super().__init__(daemon=True)
        self.adb = adb
        self.rows = []
        self._halt = threading.Event()

    def run(self):
        while not self._halt.is_set():
            kb = dumpsys_pss_kb(self.adb)
            if kb:
                self.rows.append({"t": time.time(), "pss": kb * 1024})
            self._halt.wait(1.0)

    def halt(self):
        self._halt.set()
        self.join(40)
        return self.rows


def device_pid(adb, pattern, runas=False):
    # [x]rest: the regex still finds the core, and no longer the shell that
    # carries the pattern in its own command line.
    cmd = "pgrep -f '[%s]%s'" % (pattern[0], pattern[1:])
    code, out, _ = adb.sh(("run-as %s %s" % (PKG, cmd)) if runas else cmd)
    pids = [int(x) for x in out.split() if x.isdigit() and int(x) > 1]
    return pids[0] if pids else None


def device_kill(adb, pid):
    if not pid or pid <= 1:
        return
    adb.sh("kill -TERM %d" % pid)
    for _ in range(30):
        code, _, _ = adb.sh("kill -0 %d" % pid)
        if code != 0:
            return
        time.sleep(0.2)
    adb.sh("kill -KILL %d" % pid)


def device_measure(adb, host, socks, pids, runas, platform, core, version, scenario, settings, metric, notes, app=False):
    """The same run as the desktop's, with the device loop for samples. For an
    app run the headline is PSS of the app process plus the core process;
    dumpsys meminfo's TOTAL PSS (app process only) rides along in extra."""
    row = base_row(platform, core, version, scenario, settings, metric)
    sampler = DeviceSampler(adb, pids, runas)
    sampler.start()
    dloop, drows = None, []
    time.sleep(1.5)
    t0 = time.time()
    idle_dumpsys = dumpsys_pss_kb(adb) if app else None
    rest = IDLE_SETTLE_S - (time.time() - t0)
    if rest > 0:
        time.sleep(rest)
    idle_win = (t0 + IDLE_SETTLE_S / 2, time.time())
    if app:
        dloop = DumpsysLoop(adb)
        dloop.start()
    down = host.loadgen_run("get", DOWN_BYTES, socks, 300)
    if dloop:
        drows = dloop.halt()
    time.sleep(PAUSE_S)
    up = host.loadgen_run("put", UP_BYTES, socks, 240)
    time.sleep(IDLE_AFTER_S)
    rows = sampler.halt()
    wins = (down.get("host_window", (None, None)), up.get("host_window", (None, None)))
    core_rows = rows.get(str(pids[-1]), [])
    if app:
        app_rows = rows.get(str(pids[0]), [])
        merged = merge_sum(app_rows, core_rows, ("pss", "cpu"))
        row["idle_rss_mb"] = mb(median_in(merged, "pss", *idle_win))
        row["steady_rss_mb"] = mb(median_mid(merged, "pss", *wins[0]))
        row["peak_rss_mb"] = mb(max_of(merged, "pss"))
        notes += transfer_figures(row, down, up, merged, wins)
        row["extra"].update({
            "app_pss_idle_mb": mb(median_in(app_rows, "pss", *idle_win)),
            "app_pss_steady_mb": mb(median_mid(app_rows, "pss", *wins[0])),
            "app_pss_peak_mb": mb(max_of(app_rows, "pss")),
            "core_pss_idle_mb": mb(median_in(core_rows, "pss", *idle_win)),
            "core_pss_steady_mb": mb(median_mid(core_rows, "pss", *wins[0])),
            "core_pss_peak_mb": mb(max_of(core_rows, "pss")),
            "core_rss_idle_mb": mb(median_in(core_rows, "rss", *idle_win)),
            "core_rss_peak_mb": mb(max(max_of(core_rows, "hwm") or 0, max_of(core_rows, "rss") or 0) or None),
            "app_cpu_s_download": rnd(delta(app_rows, "cpu", *wins[0]), 2),
            "core_cpu_s_download": rnd(delta(core_rows, "cpu", *wins[0]), 2),
            "dumpsys_total_pss_idle_mb": mb(idle_dumpsys * 1024) if idle_dumpsys else None,
            "dumpsys_total_pss_during_download_median_mb": mb(statistics.median([r["pss"] for r in drows])) if dloop and drows else None,
            "dumpsys_total_pss_during_download_max_mb": mb(max(r["pss"] for r in drows)) if dloop and drows else None,
        })
        notes.append("dumpsys meminfo ran about every 2 s during the download; it costs the emulator CPU")
    else:
        row["idle_rss_mb"] = mb(median_in(core_rows, "rss", *idle_win))
        row["steady_rss_mb"] = mb(median_mid(core_rows, "rss", *wins[0]))
        row["peak_rss_mb"] = mb(max(max_of(core_rows, "hwm") or 0, max_of(core_rows, "rss") or 0) or None)
        notes += transfer_figures(row, down, up, core_rows, wins)
        row["extra"].update({
            "pss_idle_mb": mb(median_in(core_rows, "pss", *idle_win)),
            "pss_steady_mb": mb(median_mid(core_rows, "pss", *wins[0])),
            "pss_peak_mb": mb(max_of(core_rows, "pss")),
        })
    notes.append("transfer windows timed on the host around adb (about 0.1 s wide)")
    row["notes"] = "; ".join(n for n in notes if n)
    return row


def merge_sum(a_rows, b_rows, keys):
    """Pairs each sample of one process with the nearest of the other (same
    device loop, same pass) and sums them."""
    out = []
    j = 0
    for r in a_rows:
        while j + 1 < len(b_rows) and abs(b_rows[j + 1]["t"] - r["t"]) <= abs(b_rows[j]["t"] - r["t"]):
            j += 1
        if not b_rows:
            break
        o = {"t": r["t"]}
        for k in keys:
            va, vb = r.get(k), b_rows[j].get(k)
            o[k] = (va + vb) if va is not None and vb is not None else None
        out.append(o)
    return out


def cmd_android(a):
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    b, devb = Path(a.bin).resolve(), Path(a.device_bin).resolve()
    budget = Budget(a.budget_min)
    adb = Adb()
    host = DeviceHost(adb)
    results = []
    results_path = out / "results.json"

    def add(row):
        results.append(row)
        write_json(results_path, results)
        print_table([row], header=False)

    adb.run(["wait-for-device"], timeout=120)
    for _ in range(90):
        if adb.sh("getprop sys.boot_completed")[1] == "1":
            break
        time.sleep(2)
    release = adb.sh("getprop ro.build.version.release")[1]
    sdk = adb.sh("getprop ro.build.version.sdk")[1]
    abi = adb.sh("getprop ro.product.cpu.abi")[1]
    platform = "android-%s-emulator-api%s (Android %s)" % (abi or "x86_64", sdk, release)
    log("device:", platform)
    adb.sh("rm -rf %s && mkdir -p %s" % (DEV_DIR, DEV_DIR))
    for name in ("libsingboxcore.so", "libxraycore.so", "loadgen"):
        code, _, err = adb.run(["push", str(devb / name), DEV_DIR + "/" + name])
        if code != 0:
            raise LabError("push %s: %s" % (name, err))
    adb.sh("chmod 755 %s/*" % DEV_DIR)
    adb.run(["logcat", "-c"])

    servers = Servers(out, b / "loadgen", b / "xray-26.4.25" / "xray")
    servers.start()
    srv_note = "server: %s on the runner, reached at 10.0.2.2 through the emulator's user-mode network" % servers.version
    reach = adb.sh(DEV_DIR + "/loadgen get -url http://10.0.2.2:%d/bytes?n=1000 -expect 1000 -timeout 10s" % PORT_PAGE)
    log("emulator reaches the runner:", reach[1] or reach[2])
    sb_version = adb.sh(DEV_DIR + "/libsingboxcore.so version")[1].splitlines()[:1]
    xr_version = adb.sh(DEV_DIR + "/libxraycore.so version")[1].splitlines()[:1]
    sb_version = sb_version[0] if sb_version else "unknown"
    xr_version = xr_version[0] if xr_version else "unknown"
    socks = "127.0.0.1:%d" % PORT_SOCKS
    server = "10.0.2.2"
    try:
        # The cores alone, under adb shell, as the app's service exec's them
        # (AndroidCoreProcess: `<lib> run -c <config>`, Go defaults).
        execs = [
            ("sing-box (exec)", "libsingboxcore.so", "vless-reality-vision", singbox_reality(server, servers.pub), sb_version),
            ("sing-box (exec)", "libsingboxcore.so", "hysteria2-salamander", singbox_hy2(server), sb_version),
            ("xray (exec)", "libxraycore.so", "vless-xhttp-reality packet-up", xray_xhttp(server, servers.pub, False), xr_version),
        ]
        for core, lib, scenario, config, version in execs:
            if not budget.allows(120):
                add(failed_row(platform, core, scenario, "go-defaults", "skipped: out of time budget"))
                continue
            log("exec:", core, scenario)
            name = re.sub(r"[^a-z0-9]+", "-", scenario)
            local = out / ("exec-%s.json" % name)
            write_json(local, config)
            adb.run(["push", str(local), "%s/%s.json" % (DEV_DIR, name)])
            pid = None
            try:
                # `;`, not `&&`: the `&` must take the core alone, or $! is a subshell.
                code, o, e = adb.sh("cd %s; setsid nohup ./%s run -c %s.json > %s.log 2>&1 < /dev/null & echo $!"
                                    % (DEV_DIR, lib, name, name))
                time.sleep(1.5)
                pid = device_pid(adb, "%s run -c %s.json" % (lib, name))
                if not pid:
                    raise LabError("the core did not start: %s %s %s" % (
                        o, e, adb.sh("tail -n 20 %s/%s.log" % (DEV_DIR, name))[1]))
                probe = host.probe(socks, 40)
                if not probe.get("ok"):
                    raise LabError("no byte crossed the core in 40 s (%s): %s" % (
                        probe.get("error"), adb.sh("tail -n 20 %s/%s.log" % (DEV_DIR, name))[1]))
                add(device_measure(adb, host, socks, [pid], False, platform, core, version, scenario, "go-defaults",
                                   "resident size (VmRSS)", ["the APK's x86_64 core, run from %s as the shell user" % DEV_DIR,
                                                             srv_note]))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(platform, core, scenario, "go-defaults", e))
            finally:
                device_kill(adb, pid)
                adb.run(["pull", "%s/%s.log" % (DEV_DIR, name), str(out / ("exec-%s.log" % name))])

        # The app itself: the debug build, driven without touching the screen.
        if a.apk and Path(a.apk).exists():
            apk = Path(a.apk)
            try:
                for row in apk_size_rows(platform, apk):
                    add(row)
            except (OSError, zipfile.BadZipFile) as e:
                add(failed_row(platform, "debug APK", "binary size", "", e))
            code, o, e = adb.run(["install", "-r", "-g", str(apk)], timeout=300)
            log("install:", o or e)
            version_name = re.search(r"versionName=(\S+)", adb.sh("dumpsys package " + PKG)[1])
            app_version = "Ghostlane debug %s" % (version_name.group(1) if version_name else "unknown")
            links = links_for_app(server, servers.pub)
            plan = [("reality", "libsingboxcore", "vless-reality-vision"),
                    ("hy2", "libsingboxcore", "hysteria2-salamander"),
                    ("xhttp", "libxraycore", "vless-xhttp-reality packet-up")]
            for key, core_lib, scenario in plan:
                if not budget.allows(150):
                    add(failed_row(platform, "Ghostlane app + " + core_lib, scenario, "app", "skipped: out of time budget"))
                    continue
                log("app:", scenario)
                try:
                    add(app_run(adb, host, platform, app_version, key, links[key], core_lib, scenario, srv_note, out))
                except Exception as e:
                    traceback.print_exc()
                    add(failed_row(platform, "Ghostlane app + " + core_lib, scenario, "app", e))
                finally:
                    adb.sh("am broadcast -n %s -a org.proofkit.app.DEBUG_STOP_VPN" % RECEIVER)
                    time.sleep(2)
                    adb.sh("am force-stop " + PKG)
        else:
            add(failed_row(platform, "Ghostlane app", "all", "app", "no APK given"))
    finally:
        servers.stop()
        code, o, _ = adb.run(["logcat", "-d", "-v", "time"], timeout=120)
        (out / "logcat.txt").write_text(o)
    write_json(results_path, results)
    print_table(results)
    return 0


def apk_size_rows(platform, apk):
    rows = [size_row(platform, "debug APK (x86_64 only)", apk, "assembleGithubDebug with -Polcbox.android.abiFilters=x86_64")]
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            if info.filename.startswith("lib/") and info.filename.endswith(".so"):
                row = base_row(platform, info.filename, "", "binary size", "", "")
                row["extra"].update({"bytes": info.file_size, "mb": mb(info.file_size),
                                     "compressed_bytes": info.compress_size})
                row["notes"] = "inside the APK"
                rows.append(row)
    return rows


def app_run(adb, host, platform, app_version, key, link, core_lib, scenario, srv_note, out):
    notes = ["debug build (not minified): ART heap larger than a release build's", srv_note,
             "the app process carries hev-socks5-tunnel and the olcRTC Go runtime (libgojni.so) in-process"]
    adb.sh("am force-stop " + PKG)
    adb.sh("pm clear " + PKG)
    adb.sh("pm grant %s android.permission.POST_NOTIFICATIONS" % PKG)
    adb.sh("appops set %s ACTIVATE_VPN allow" % PKG)
    adb.sh("am start -W -n " + ACTIVITY, timeout=60)
    time.sleep(6)
    deeplink = "ghostlane://add?url=" + urllib.parse.quote(link, safe="")
    adb.sh("am start -W -a android.intent.action.VIEW -d '%s' -n %s" % (deeplink, ACTIVITY), timeout=60)
    tag = link.rsplit("#", 1)[1]
    imported = False
    for _ in range(30):
        code, o, _ = adb.sh("run-as %s sh -c 'grep -rl %s . 2>/dev/null | head -n 1'" % (PKG, tag))
        if o.strip():
            imported = True
            notes.append("imported through ghostlane://add, stored in %s" % o.strip())
            break
        time.sleep(1)
    if not imported:
        notes.append("the line's name was not found in the app's files after the import link")
    adb.sh("am broadcast -n %s -a org.proofkit.app.DEBUG_START_VPN" % RECEIVER)
    probe = host.probe(None, 60)
    if not probe.get("ok"):
        raise LabError("nothing came through the app's tunnel in 60 s (%s); imported=%s" % (probe.get("error"), imported))
    app_pid = None
    o = adb.sh("pidof " + PKG)[1].split()
    if o and o[0].isdigit():
        app_pid = int(o[0])
    core_pid = device_pid(adb, core_lib) or device_pid(adb, core_lib, runas=True)
    if not app_pid or not core_pid:
        ps = adb.sh("ps -A -o PID,USER,NAME")[1]
        (out / ("ps-%s.txt" % key)).write_text(ps)
        raise LabError("app pid %s, core pid %s (%s)" % (app_pid, core_pid, core_lib))
    # The config the app wrote for its core (AndroidCoreProcess: cacheDir/olcbox-<label>/config.json),
    # kept beside the figures: it is what was measured.
    code, written, _ = adb.sh("run-as %s sh -c 'for f in cache/olcbox-*/config.json; do echo \"== $f\"; cat \"$f\"; echo; done'" % PKG)
    (out / ("app-core-config-%s.txt" % key)).write_text(written + "\n")
    row = device_measure(adb, host, None, [app_pid, core_pid], True, platform, "Ghostlane app + " + core_lib,
                         app_version, scenario, "app", "PSS (app process + core process)", notes, app=True)
    return row


# ---------------------------------------------------------------- table


COLUMNS = [("platform", 22), ("core", 30), ("scenario", 30), ("settings", 11), ("idle", 6), ("steady", 6),
           ("peak", 6), ("go s-r", 6), ("down", 7), ("up", 7), ("cpu/GB", 6)]


def cells(r):
    def f(v):
        return "-" if v is None else str(v)
    if r.get("scenario") == "binary size":
        return [r["platform"], r["core"], "size %s MB" % f(r.get("extra", {}).get("mb")), "", "", "", "", "", "", "", ""]
    return [r.get("platform", ""), r.get("core", ""), r.get("scenario", ""), r.get("settings", ""),
            f(r.get("idle_rss_mb")), f(r.get("steady_rss_mb")), f(r.get("peak_rss_mb")),
            f(r.get("go_sys_minus_released_mb")), f(r.get("mbps_down")), f(r.get("mbps_up")),
            f(r.get("cpu_s_per_gb"))]


def print_table(rows, header=True):
    if header:
        print("memory in MiB (idle / steady under load / peak; go s-r = Go sys minus released, peak), "
              "throughput in Mbit/s, CPU in s per GB moved")
        print("  ".join(name.ljust(w) for name, w in COLUMNS))
    for r in rows:
        c = cells(r)
        print("  ".join(str(v)[:w].ljust(w) for v, (_, w) in zip(c, COLUMNS)), flush=True)
        if r.get("notes", "").startswith("FAILED") or r.get("notes", "").startswith("skipped"):
            print("    " + r["notes"][:600], flush=True)


def markdown(rows):
    head = ["platform", "core", "scenario", "settings", "metric", "idle MiB", "steady MiB", "peak MiB",
            "Go sys-rel MiB", "down Mbit/s", "up Mbit/s", "CPU s/GB"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in rows:
        if r.get("scenario") == "binary size":
            continue
        c = cells(r)
        lines.append("| " + " | ".join([c[0], c[1], c[2], c[3], r.get("memory_metric", "")] + c[4:]).replace("\n", " ") + " |")
    sizes = [r for r in rows if r.get("scenario") == "binary size"]
    if sizes:
        lines += ["", "| platform | binary | MB | what |", "|---|---|---|---|"]
        for r in sizes:
            lines.append("| %s | %s | %s | %s |" % (r["platform"], r["core"], r.get("extra", {}).get("mb"), r.get("notes", "")))
    failed = [r for r in rows if str(r.get("notes", "")).startswith(("FAILED", "skipped"))]
    if failed:
        lines += ["", "Not measured:", ""]
        for r in failed:
            lines.append("- %s / %s / %s / %s: %s" % (r["platform"], r["core"], r["scenario"], r["settings"],
                                                      str(r["notes"]).splitlines()[0][:300]))
    return "\n".join(lines) + "\n"


def cmd_table(a):
    rows = []
    for item in a.paths:
        p = Path(item)
        files = sorted(p.rglob("results.json")) if p.is_dir() else [p]
        for f in files:
            try:
                rows += json.loads(f.read_text())
            except (OSError, ValueError) as e:
                print("skip %s: %s" % (f, e))
    print_table(rows)
    if a.json_out:
        write_json(Path(a.json_out), rows)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## Core footprint\n\n" + markdown(rows))
    return 0


# ---------------------------------------------------------------- shapes


def rel(path):
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)


def canonical(path):
    return json.dumps(load_json(path), indent=1, sort_keys=True).splitlines()


def cmd_shapes_check(a):
    build = REPO / "sharedUI" / "build"
    pairs = [(build / "xray-configs" / "vless-xhttp.json", SHAPES / "vless-xhttp.json"),
             (build / "xray-configs" / "vless-xhttp-answers-dns.json", SHAPES / "vless-xhttp-answers-dns.json")]
    for name in ("vless-reality.json", "hysteria2.json", "ios-tun-reality.json"):
        pairs.append((build / "singbox-configs" / name, REFS / name))
    bad = 0
    for fresh, lab in pairs:
        if not fresh.exists():
            print("MISSING  %s (did the dump test run?)" % rel(fresh))
            bad += 1
            continue
        got, want = canonical(fresh), canonical(lab)
        if got == want:
            print("same     %s == %s" % (rel(fresh), rel(lab)))
            continue
        bad += 1
        print("DIFFERS  %s vs %s" % (rel(fresh), rel(lab)))
        for line in difflib.unified_diff(want, got, "lab", "app", lineterm="", n=2):
            print("    " + line)
    return 1 if bad else 0


# ---------------------------------------------------------------- main


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("desktop")
    d.add_argument("--bin", required=True)
    d.add_argument("--out", required=True)
    d.add_argument("--platform", required=True)
    d.add_argument("--engine-src")
    d.add_argument("--tun", action="store_true", help="also the gVisor tun run (Linux, passwordless sudo)")
    d.add_argument("--budget-min", type=float, default=24)
    n = sub.add_parser("android")
    n.add_argument("--bin", required=True, help="runner binaries: loadgen, xray-26.4.25/xray")
    n.add_argument("--device-bin", required=True, help="x86_64 libsingboxcore.so, libxraycore.so, loadgen")
    n.add_argument("--apk")
    n.add_argument("--out", required=True)
    n.add_argument("--budget-min", type=float, default=22)
    t = sub.add_parser("table")
    t.add_argument("paths", nargs="+")
    t.add_argument("--json-out")
    sub.add_parser("shapes-check")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "desktop":
            return cmd_desktop(a)
        if a.cmd == "android":
            return cmd_android(a)
        if a.cmd == "table":
            return cmd_table(a)
        return cmd_shapes_check(a)
    finally:
        # Whatever a failure left running (servers, a core) goes with the script.
        for proc in reversed(LIVE):
            try:
                proc.stop(grace=3)
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
