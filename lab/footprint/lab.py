#!/usr/bin/env python3
"""The core footprint lab's driver (standard library only, Python 3.9+).

    lab.py desktop      --bin DIR --out DIR --platform NAME [--engine-src DIR] [--tun]
    lab.py android      --bin DIR --device-bin DIR --apk FILE --out DIR
    lab.py table        FILE_OR_DIR...        prints (and adds to the job summary) one table
    lab.py shapes-check                       the lab's client shapes against the app's builders
    lab.py udp-flow     --bin DIR --out DIR   where a new UDP flow's time goes behind hev (Linux, macOS; root)
    lab.py heap-profile --bin DIR --out DIR   where xray-rust's memory goes (heaptrack on Linux, footprint on macOS)
    lab.py alloc-rows   --bin DIR --out DIR   phys_footprint under five allocator setups (macOS)

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
import base64
import ctypes
import difflib
import hashlib
import http.client
import ipaddress
import json
import math
import os
import queue
import re
import select
import shlex
import shutil
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
# The burst after the single transfers: BURST_FLOWS transfers at once, one
# connection each, as a speed test opens them (the iPhone extension's deaths
# came with 23 connections at once and 1117 to 1510 goroutines; notes
# project_olcrtc_connect_ack_timeouts, project_ookla_two_engines_2026_09_13).
BURST_FLOWS = 32
BURST_EACH_DOWN = 4_000_000
BURST_EACH_UP = 2_000_000
BURST_TAIL_S = 3.0

PORT_SOCKS = 10810
PORT_PAGE = 18080
PORT_DEST = 18443
PORT_REALITY = 20443
PORT_XHTTP = 20444
PORT_HY2 = 20445
PORT_WEBTUNNEL = 20446
UUID = "9f4b2c3a-1111-4222-8333-444455556666"
SHORT_ID = "0123abcd"
SNI_REALITY = "reality.lab.test"
SNI_XHTTP = "xhttp.lab.test"
SNI_HY2 = "hy2.lab.test"
SNI_WEBTUNNEL = "webtunnel.lab.test"
WEBTUNNEL_PATH = "/wt"
WEBTUNNEL = "webtunnel (vless tls httpupgrade)"
XHTTP_PATH = "/lab"
HY2_AUTH = "lab-hy2-auth"
HY2_OBFS = "lab-hy2-obfs"
THROUGH = "203.0.113.10"
TUN_NAME = "pklab0"
# hev's own tun in the hev row (Linux, root). The MTU is the extension's:
# PacketTunnelProvider hands HevTunnel.start LibboxPlatform.Tun.mtu, 9000
# (iosApp/PacketTunnel/LibboxBridge.swift). On a phone the tun comes with its
# address; a process that opens its own needs one, so it gets one.
TUN_HEV = "pklab1"
HEV_TUN_IPV4 = "172.19.0.5"
HEV_MTU = 9000
HEV_CORE = "hev + cores harness: sing-box"
# The iOS shape rows (Phase 0, uncommitted branch feat/ios-singbox-behind-hev):
# the app's own iOS sing-box configs, sing-box behind hev and sing-box on its
# gVisor tun. Tuns of their own, so they never meet the two rows above. The
# tun's address and resolver are the extension's (LibboxPlatform.Tun.address,
# Tun.dns; checked against LibboxBridge.swift); the name is any name, since
# the lab's servers send every destination to the page.
IOS_SHAPE_HEV = "ios-shape sing-box+hev"
IOS_SHAPE_TUN = "ios-shape sing-box gVisor tun"
TUN_IOS_HEV = "pklab3"
TUN_IOS_GVISOR = "pklab2"
IOS_TUN_ADDRESS = "172.19.0.1"
IOS_TUN_DNS = "172.19.0.2"
IOS_CHECK_NAME = "page.lab.test"
IOS_CHECK_BYTES = 65536
IOS_UDP_EXCHANGES = 8
# SingBoxConfig.DIRECT_DNS_PLACEHOLDER / DirectResolver.placeholder and fallback.
DIRECT_DNS_PLACEHOLDER = "198.51.100.53"
DIRECT_DNS_FALLBACK = "77.88.8.8"
RULES_DIR = REPO / "sharedUI" / "src" / "commonMain" / "composeResources" / "files" / "rules"
# The ClientHello tap: a relay on this port in front of a server port.
PORT_TAP = 20550

PKG = "org.proofkit.app"
ACTIVITY = PKG + "/org.olcbox.app.AppActivity"
RECEIVER = PKG + "/org.olcbox.app.DebugVpnControlReceiver"
DEV_DIR = "/data/local/tmp/lab"

# The Go runtime, and Tokio for xray-rust, read these at start; a runner that
# happens to set one would change every figure without saying so. (XRAY_* goes
# too, which takes xray-rust's XRAY_TUN_PROFILE with it unless a row sets it.)
SANITIZED = ("GOGC", "GOMEMLIMIT", "GODEBUG", "GOMAXPROCS", "TOKIO_WORKER_THREADS", "RUST_MIN_STACK")
# The same for the allocators: mimalloc's options, the lab patch's switches
# (xray-rust-patches/lab-allocator-switch.patch) and Apple's malloc settings
# (MallocNanoZone and the rest), which a row sets only on purpose.
SANITIZED_PREFIXES = ("XRAY_", "MIMALLOC_", "XRS_LAB_", "Malloc")


class LabError(Exception):
    pass


def log(*parts):
    print("[lab %s]" % time.strftime("%H:%M:%S"), *parts, flush=True)


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if k not in SANITIZED and not k.startswith(SANITIZED_PREFIXES)}
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


def thread_census(pid):
    """A process's threads: on Linux by name (/proc/<pid>/task/*/comm, cut to
    15 characters; Tokio names its workers and its blocking-pool threads
    alike, tokio-runtime-worker), on macOS the count `ps -M` lists."""
    if sys.platform.startswith("linux"):
        names = {}
        for comm in Path("/proc/%d/task" % pid).glob("*/comm"):
            try:
                n = comm.read_text().strip()
            except OSError:
                continue
            names[n] = names.get(n, 0) + 1
        return {"threads": sum(names.values()), "by_name": dict(sorted(names.items()))} if names else None
    try:
        out = subprocess.run(["ps", "-M", "-p", str(pid)], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    lines = [ln for ln in out.splitlines()[1:] if ln.strip()]
    return {"threads": len(lines)} if lines else None


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


def singbox_reality(server, pub, port=PORT_REALITY):
    """sharedUI reference-configs/vless-reality.json (SingBoxConfig.build of a
    Reality line, which SingBoxConfigDumpTest holds equal to it), with the
    lab's address and keys."""
    c = load_json(REFS / "vless-reality.json")
    out = c["outbounds"][0]
    expect(out["type"] == "vless" and out.get("flow") == "xtls-rprx-vision", "vless-reality outbound")
    expect(out["tls"]["utls"]["fingerprint"] == "chrome" and out["tls"]["reality"]["enabled"], "vless-reality tls")
    expect(c["inbounds"][0]["type"] == "socks" and c["inbounds"][0]["listen_port"] == PORT_SOCKS, "socks inbound")
    out.update(server=server, server_port=port, uuid=UUID)
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


def singbox_webtunnel(server, port=PORT_WEBTUNNEL):
    """A WebTunnel line (VLESS, security=tls, type=httpupgrade, fp=chrome:
    proofkit-dvpn/docs/system/webtunnel.md) as the app writes it. No reference
    config is committed for this shape, so it is vless-reality.json changed
    only where SingBoxConfig.kt addOutbound and putTransport write a TLS
    httpupgrade line differently: no flow (LinkParser keeps flow on TCP only),
    no reality block, the httpupgrade transport with path and host; uTLS
    chrome and xudp stay; alpn is not written (LinkParser leaves it unread for
    VLESS). insecure: the lab's server has a self-signed certificate, the
    exits a Let's Encrypt one."""
    c = load_json(REFS / "vless-reality.json")
    out = c["outbounds"][0]
    expect(out["type"] == "vless" and out.get("packet_encoding") == "xudp", "vless outbound")
    expect(out["tls"]["utls"]["fingerprint"] == "chrome", "vless utls")
    expect(c["inbounds"][0]["type"] == "socks" and c["inbounds"][0]["listen_port"] == PORT_SOCKS, "socks inbound")
    out.pop("flow", None)
    out["tls"].pop("reality", None)
    out.update(server=server, server_port=port, uuid=UUID)
    out["tls"].update(server_name=SNI_WEBTUNNEL, insecure=True)
    out["transport"] = {"type": "httpupgrade", "path": WEBTUNNEL_PATH, "host": SNI_WEBTUNNEL}
    return c


def xray_xhttp(server, pub, answers_dns, port=PORT_XHTTP):
    """XrayConfig.buildXhttp as XrayConfigDumpTest writes it (shapes/, checked
    against a fresh dump by `lab.py shapes-check`), with the lab's address,
    keys, name and path. answers_dns is the iPhone's shape behind hev."""
    c = load_json(SHAPES / ("vless-xhttp-answers-dns.json" if answers_dns else "vless-xhttp.json"))
    out = c["outbounds"][0]
    expect(out["protocol"] == "vless" and out["streamSettings"]["network"] == "xhttp", "xhttp outbound")
    expect(c["inbounds"][0]["port"] == PORT_SOCKS, "socks inbound")
    vnext = out["settings"]["vnext"][0]
    vnext.update(address=server, port=port)
    vnext["users"][0]["id"] = UUID
    ss = out["streamSettings"]
    ss["realitySettings"].update(serverName=SNI_XHTTP, publicKey=pub, shortId=SHORT_ID)
    ss["xhttpSettings"].update(path=XHTTP_PATH, host=SNI_XHTTP)
    expect(ss["xhttpSettings"]["mode"] == "packet-up", "xhttp mode")
    return c


# ---------------------------------------------------------------- xray-rust
#
# xray-rust reads Xray-style JSON, a documented subset of it
# (docs/config-compatibility.md at the tag). Its configs here are the app's
# shapes written in that form: the XHTTP line is the app's own Xray shape,
# unchanged; the three lines the app gives sing-box are translated field by
# field from the sing-box reference configs. Each builder returns the config
# and the notes that say where it differs from the app's.


def cert_sha256(pem_path):
    """SHA-256 of a PEM certificate's DER, the form pinnedPeerCertSha256 takes."""
    text = Path(pem_path).read_text()
    m = re.search(r"-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", text, re.S)
    if not m:
        raise LabError("no certificate in %s" % pem_path)
    return hashlib.sha256(base64.b64decode("".join(m.group(1).split()))).hexdigest()


def xrs_config(outbound):
    """The app's own Xray SOCKS inbound (shapes/vless-xhttp.json) and one outbound."""
    inbound = load_json(SHAPES / "vless-xhttp.json")["inbounds"][0]
    expect(inbound["protocol"] == "socks" and inbound["port"] == PORT_SOCKS, "xray socks inbound")
    return {"log": {"loglevel": "warning"}, "inbounds": [inbound], "outbounds": [outbound]}


def xrs_reality(server, pub, port=PORT_REALITY):
    ref = load_json(REFS / "vless-reality.json")["outbounds"][0]
    expect(ref["type"] == "vless" and ref.get("flow") == "xtls-rprx-vision", "vless-reality outbound")
    expect(ref["tls"]["utls"]["enabled"] and ref["tls"]["reality"]["enabled"], "vless-reality tls")
    fp = ref["tls"]["utls"]["fingerprint"]
    out = {"tag": "out", "protocol": "vless",
           "settings": {"vnext": [{"address": server, "port": port,
                                   "users": [{"id": UUID, "encryption": "none", "flow": ref["flow"]}]}]},
           "streamSettings": {"network": "tcp", "security": "reality",
                              "realitySettings": {"serverName": SNI_REALITY, "fingerprint": fp,
                                                  "publicKey": pub, "shortId": SHORT_ID}}}
    return xrs_config(out), [
        "vless-reality.json written as Xray JSON: flow %s, fingerprint %s, the Reality keys; sing-box's "
        "packet_encoding xudp has no outbound field there (xray-rust frames VLESS UDP itself) and the lab "
        "moves no UDP" % (ref["flow"], fp)]


def xrs_xhttp(server, pub, port=PORT_XHTTP):
    return xray_xhttp(server, pub, answers_dns=False, port=port), [
        "the app's own Xray shape (shapes/vless-xhttp.json, what the xray (exec) row runs), unchanged: "
        "packet-up over Reality H2, fingerprint chrome, scMaxEachPostBytes 200000"]


def xrs_hy2(server, pin, port=PORT_HY2):
    ref = load_json(REFS / "hysteria2.json")["outbounds"][0]
    expect(ref["type"] == "hysteria2" and ref["obfs"]["type"] == "salamander", "hysteria2 outbound")
    expect(ref["tls"].get("insecure") is True, "hysteria2 insecure")
    expect(not any(k in ref for k in ("up_mbps", "down_mbps")), "hysteria2 bandwidth")
    out = {"tag": "out", "protocol": "hysteria",
           "settings": {"version": 2, "address": server, "port": port},
           "streamSettings": {"network": "hysteria", "security": "tls",
                              "tlsSettings": {"serverName": SNI_HY2, "alpn": ["h3"], "pinnedPeerCertSha256": pin},
                              "hysteriaSettings": {"version": 2, "auth": HY2_AUTH},
                              "finalmask": {"udp": [{"type": "salamander", "settings": {"password": HY2_OBFS}}]}}}
    return xrs_config(out), [
        "hysteria2.json written as Xray JSON (version 2, auth, Salamander under finalmask.udp, alpn h3); "
        "its insecure:true becomes pinnedPeerCertSha256 of the lab's certificate, because xray-rust refuses "
        "allowInsecure:true (the exits' lines publish a pin, which sing-box cannot use); no bandwidth "
        "in either; xray-rust has no ChromeParrot QUIC hello (its parser warns)"]


def xrs_webtunnel(server, pin, port=PORT_WEBTUNNEL):
    sb = singbox_webtunnel(server, port)["outbounds"][0]
    expect(sb["transport"]["type"] == "httpupgrade" and "flow" not in sb, "webtunnel outbound")
    expect("alpn" not in sb["tls"], "webtunnel alpn")
    out = {"tag": "out", "protocol": "vless",
           "settings": {"vnext": [{"address": server, "port": port,
                                   "users": [{"id": UUID, "encryption": "none"}]}]},
           "streamSettings": {"network": "httpupgrade", "security": "tls",
                              "tlsSettings": {"serverName": sb["tls"]["server_name"],
                                              "fingerprint": sb["tls"]["utls"]["fingerprint"],
                                              "pinnedPeerCertSha256": pin},
                              "httpupgradeSettings": {"path": sb["transport"]["path"],
                                                      "host": sb["transport"]["host"]}}}
    return xrs_config(out), [
        "the sing-box WebTunnel shape (singbox_webtunnel) written as Xray JSON: no flow, TLS with fingerprint "
        "chrome, httpupgrade path and host, no alpn written (the app writes none, so the chrome profile's "
        "ALPN goes out); insecure becomes pinnedPeerCertSha256 of the lab's certificate"]


# The relay buffer rows: policy.levels."0".bufferSize in KiB, the field the
# core reads for a relay's read size (xray-core-rs/src/policy.rs
# effective_policy_for_level, relay_buffer_size: KiB, clamped to 1 KiB..1 MiB;
# a VLESS outbound takes its user's level, 0 here; unset, 128 KiB). In the
# fork a read past the first 4 KiB goes into a 128 KiB buffer lent by a
# process-wide pool whatever the cap (policy/relay_buffer.rs, LENT_BUFFER_SIZE;
# a cap above 128 KiB grows a buffer of its own instead), so the cap bounds
# what one read takes, not the buffer it reads into.
RELAY_KIB = (16, 32, 64)
RELAY_LINES = ("vless-reality-vision", WEBTUNNEL)
ONE_WORKER_LINES = ("vless-xhttp-reality packet-up", WEBTUNNEL)


def with_relay_buffer(cfg, kib):
    out = json.loads(json.dumps(cfg))
    out["policy"] = {"levels": {"0": {"bufferSize": kib}}}
    return out


def xrs_build(dir_):
    """A rust-core-build.sh output directory: (binary, build record, ref,
    commit). The ref is the record's `ref` (a branch or a tag), or its `tag`
    when an older script wrote it."""
    if not dir_:
        return None, {}, "?", "?"
    d = Path(dir_).resolve()
    try:
        info = load_json(d / "xray-rust.build.json")
    except (OSError, ValueError):
        info = {}
    return d / "xray-rust", info, str(info.get("ref") or info.get("tag") or "?"), str(info.get("commit") or "?")


def xrs_version_text(info):
    ref = info.get("ref") or info.get("tag") or "?"
    repo = re.sub(r"^https://github.com/|\.git$", "", str(info.get("repo") or "?"))
    patches = ", ".join("%s (sha256 %s)" % (Path(q.get("file", "?")).name, str(q.get("sha256"))[:12])
                        for q in info.get("patches") or [])
    return "xray-rust %s %s at %s, %s, cargo --release [%s]%s%s" % (
        repo, ref, str(info.get("commit") or "?")[:12], info.get("rustc", "rustc ?"), info.get("profile_release", "?"),
        (" + " + " ".join(info.get("profile_overrides") or [])) if info.get("profile_overrides") else "",
        (" + lab patches: " + patches) if patches else "")


def xrs_build_note(info):
    here_run = os.environ.get("GITHUB_RUN_ID", "local")
    if str(info.get("built_by_run")) == here_run:
        return "built in this run in %s s" % info.get("build_seconds")
    return ("restored from the actions cache: built by run %s from the same commit, toolchain and lab patches"
            % info.get("built_by_run"))


def xrs_check(xrs, cfg_path, report_path):
    """`xray-rust config check --json`: the linked parser, no core started.
    Returns (valid, notes, report)."""
    try:
        cp = subprocess.run([str(xrs), "config", "check", "-config", str(cfg_path), "--json"],
                            capture_output=True, text=True, timeout=60, env=clean_env())
    except (OSError, subprocess.SubprocessError) as e:
        return False, ["xray-rust config check did not run: %s" % e], {}
    Path(report_path).write_text((cp.stdout or "") + (cp.stderr or ""))
    try:
        report = json.loads(cp.stdout)
    except ValueError:
        return False, ["xray-rust config check said: %s" % (cp.stdout + cp.stderr).strip()[-400:]], {}
    notes = []
    for d in report.get("diagnostics") or []:
        notes.append("xray-rust config check %s at %s: %s" % (
            d.get("severity", "?"), d.get("path", "$"), str(d.get("message", d))[:300]))
    return bool(report.get("valid")), notes, report


# ---------------------------------------------------------------- hev

# HevTunnel.configuration (iosApp/PacketTunnel/HevTunnel.swift), the lines it
# writes after `misc:`. hev_config refuses to run when the Swift no longer
# writes one of them.
HEV_MISC = ("task-stack-size: 24576", "tcp-buffer-size: 4096", "max-session-count: 768", "connect-timeout: 5000",
            "read-write-timeout: 60000", "udp-read-write-timeout: 30000", "log-file: stderr", "log-level: warn")


def hev_config(port, login=None, name=TUN_HEV, ipv4=HEV_TUN_IPV4):
    """The iPhone's hev configuration (HevTunnel.configuration) for a SOCKS
    on `port`, with the login lines it writes when `login` is a (username,
    password) pair and without them when it is None, plus the two lines a
    process that opens its own tun needs and the extension's handed-in
    descriptor makes unnecessary: the interface's name and address."""
    swift = (REPO / "iosApp" / "PacketTunnel" / "HevTunnel.swift").read_text()
    for line in HEV_MISC + ("address: 127.0.0.1", "udp: 'udp'"):
        expect('"  %s"' % line in swift, "HevTunnel.swift no longer writes %r" % line)
    expect('"  mtu: \\(mtu)"' in swift and '"  port: \\(socks.port)"' in swift, "HevTunnel.swift mtu and port lines")
    bridge = (REPO / "iosApp" / "PacketTunnel" / "LibboxBridge.swift").read_text()
    expect(re.search(r"static let mtu = %d\b" % HEV_MTU, bridge) is not None, "LibboxPlatform.Tun.mtu")
    login_lines = ""
    if login is not None:
        for key, var in (("username", "username"), ("password", "password")):
            expect(r'''"  %s: '\(yamlQuoted(%s))'"''' % (key, var) in swift,
                   "HevTunnel.swift no longer writes the %s line" % key)
        expect("replacingOccurrences(of: \"'\", with: \"''\")" in swift, "HevTunnel.yamlQuoted")
        login_lines = "".join("  %s: '%s'\n" % (key, value.replace("'", "''"))
                              for key, value in zip(("username", "password"), login))
    return ("tunnel:\n  name: %s\n  mtu: %d\n  ipv4: %s\n"
            "socks5:\n  port: %d\n  address: 127.0.0.1\n  udp: 'udp'\n%s"
            "misc:\n%s") % (name, HEV_MTU, ipv4, port, login_lines, "".join("  %s\n" % m for m in HEV_MISC))


# ---------------------------------------------------------------- the iOS shape (Phase 0)
#
# Phase 0 moves the iPhone's sing-box lines (Reality, Hysteria2, WebTunnel)
# behind hev: sing-box listens on a loopback SOCKS port with a login and still
# answers DNS with fake addresses; hev owns the tun. The configs are Phase 0's
# own (shapes/ios-socks.provenance.txt says where they come from); the
# baseline is sing-box on its gVisor tun with the committed ios-tun shapes.


def ios_socks_copy(name):
    """A Phase 0 copy from shapes/, refused unless its sha256 is the one the
    provenance file names: the copies are copied, never edited."""
    listed = {}
    for line in (SHAPES / "ios-socks.provenance.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) >= 2 and not line.startswith("#") and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            listed[parts[1]] = parts[0]
    expect(name in listed, "shapes/ios-socks.provenance.txt does not list %s" % name)
    raw = (SHAPES / name).read_bytes()
    got = hashlib.sha256(raw).hexdigest()
    if got != listed[name]:
        raise LabError("shapes/%s has sha256 %s, but its provenance names %s: copy it again from the Phase 0 "
                       "branch, do not edit it" % (name, got, listed[name]))
    return json.loads(raw)


# copy in shapes/ -> (its buildTun twin in reference-configs/, the reference whose
# first outbound the copy carries instead of the twin's, or None)
IOS_SOCKS = {
    "ios-socks-reality.json": ("ios-tun-reality.json", None),
    "ios-socks-hysteria2.json": ("ios-tun-reality.json", "bypass-tun-hysteria2.json"),
    "bypass-ios-socks-reality.json": ("bypass-tun-reality.json", None),
}


def rules_normalized(value):
    """SingBoxConfigDumpTest.normalizedReference: a local rule-set path
    becomes @rules/<file>, so a fresh dump compares with a reference."""
    if isinstance(value, dict):
        return {k: ("@rules/" + v.replace("\\", "/").rsplit("/", 1)[-1]
                    if k == "path" and isinstance(v, str) and v.endswith(".srs") else rules_normalized(v))
                for k, v in value.items()}
    if isinstance(value, list):
        return [rules_normalized(v) for v in value]
    return value


def ios_socks_drift(copy, twin, outbound_source=None):
    """Where a Phase 0 copy differs from buildTun's shape for the same line,
    apart from the inbound (Phase 0's SingBoxConfigTest: everything but the
    inbounds equals buildTun, in the same key order). [] when nowhere."""
    a, b = dict(rules_normalized(copy)), dict(rules_normalized(twin))
    problems = []
    if list(a) != list(b):
        problems.append("top-level keys %s, buildTun's %s" % (list(a), list(b)))
    a.pop("inbounds", None)
    b.pop("inbounds", None)
    if outbound_source is not None:
        want = [rules_normalized(outbound_source)["outbounds"][0]]
        if a.pop("outbounds", None) != want:
            problems.append("outbounds are not the first outbound of the Hysteria2 reference")
        b.pop("outbounds", None)
    for key in sorted(set(a) | set(b)):
        if a.get(key) != b.get(key):
            problems.append("%s differs" % key)
    inbounds = copy.get("inbounds") or []
    if not (len(inbounds) == 1 and inbounds[0].get("type") == "socks" and inbounds[0].get("listen") == "127.0.0.1"
            and inbounds[0].get("listen_port") == PORT_SOCKS and len(inbounds[0].get("users") or []) == 1
            and inbounds[0]["users"][0].get("username") and inbounds[0]["users"][0].get("password")):
        problems.append("the inbound is not one loopback socks on %d with one login: %s" % (PORT_SOCKS, inbounds))
    return problems


def ios_hev_socks(config):
    """TunArrangement.singBoxSocks (iosApp/PacketTunnel/TunArrangement.swift
    on the Phase 0 branch) in Python: None when sing-box is to own the tun (a
    tun inbound anywhere, or no socks inbound); else (port, login) for hev,
    the login a (username, password) pair or None, since half of one is sent
    as none; the port 10810 when the config names none."""
    inbounds = config.get("inbounds") or []
    if any(i.get("type") == "tun" for i in inbounds):
        return None
    socks = next((i for i in inbounds if i.get("type") == "socks"), None)
    if socks is None:
        return None
    user = (socks.get("users") or [{}])[0]
    username, password = user.get("username") or None, user.get("password") or None
    port = socks.get("listen_port")
    port = port if isinstance(port, int) and 1 <= port <= 65535 else 10810
    return port, ((username, password) if username and password else None)


def runner_resolvers():
    for path in ("/run/systemd/resolve/resolv.conf", "/etc/resolv.conf"):
        try:
            found = [ln.split()[1] for ln in Path(path).read_text().splitlines()
                     if ln.startswith("nameserver") and len(ln.split()) > 1]
        except OSError:
            continue
        if found:
            return found
    return []


def direct_resolver_pick(servers):
    """DirectResolver.pick (iosApp/PacketTunnel/DirectResolver.swift): the
    first IPv4 that is not loopback, else the first IPv6 that is neither
    link-local nor ::1, else a zoned link-local IPv6, else Yandex DNS."""
    unzoned = [s.split("%", 1)[0] for s in servers]
    for h in unzoned:
        if "." in h and ":" not in h and not h.startswith("127."):
            return h
    for h in unzoned:
        if ":" in h and not h.lower().startswith("fe80:") and h != "::1":
            return h
    for h in servers:
        if h.lower().startswith("fe80:") and "%" in h:
            return h
    return DIRECT_DNS_FALLBACK


def ios_extension_constants():
    """The tun address, resolver and placeholder the lab mirrors, as the
    extension's sources on this branch spell them."""
    bridge = (REPO / "iosApp" / "PacketTunnel" / "LibboxBridge.swift").read_text()
    expect('static let address = "%s"' % IOS_TUN_ADDRESS in bridge, "LibboxPlatform.Tun.address")
    expect('static let mask = "255.255.255.252"' in bridge, "LibboxPlatform.Tun.mask")
    expect('static let dns = ["%s"]' % IOS_TUN_DNS in bridge, "LibboxPlatform.Tun.dns")
    resolver = (REPO / "iosApp" / "PacketTunnel" / "DirectResolver.swift").read_text()
    expect('static let placeholder = "%s"' % DIRECT_DNS_PLACEHOLDER in resolver, "DirectResolver.placeholder")
    expect('static let fallback = "%s"' % DIRECT_DNS_FALLBACK in resolver, "DirectResolver.fallback")


def ios_lab_server(c, server, pub):
    """The lab's server in place of the reference's: address, port, keys and
    names. Returns which line it is."""
    out = c["outbounds"][0]
    expect(out.get("tag") == "out" and c["route"].get("final") == "out", "the line's outbound is route.final")
    if out["type"] == "vless":
        expect(out.get("flow") == "xtls-rprx-vision" and out["tls"]["reality"]["enabled"], "ios vless-reality outbound")
        out.update(server=server, server_port=PORT_REALITY, uuid=UUID)
        out["tls"]["server_name"] = SNI_REALITY
        out["tls"]["reality"].update(public_key=pub, short_id=SHORT_ID)
        return "vless-reality-vision"
    expect(out["type"] == "hysteria2" and out["obfs"]["type"] == "salamander", "ios hysteria2 outbound")
    expect(out["tls"].get("insecure") is True, "ios hysteria2 insecure")
    out.update(server=server, server_port=PORT_HY2, password=HY2_AUTH)
    out["obfs"]["password"] = HY2_OBFS
    out["tls"]["server_name"] = SNI_HY2
    return "hysteria2-salamander"


def ios_runner_fields(c, rundir):
    """What only the runner can fill in, as the extension fills it on a
    phone: the cache file's place (libbox's working directory there), the
    direct resolver (DirectResolver.substitute over the runner's own
    resolvers), the rule-sets' files (the app's bundled .srs). Returns notes."""
    ios_extension_constants()
    cache = c.get("experimental", {}).get("cache_file", {})
    expect(cache.get("enabled") is True and cache.get("store_fakeip") is True and "path" not in cache,
           "cache_file enabled with store_fakeip and no path")
    cache["path"] = str(rundir / "cache.db")
    placeholders = [s for s in c["dns"]["servers"] if s.get("server") == DIRECT_DNS_PLACEHOLDER]
    expect(len(placeholders) == 1, "exactly one direct resolver placeholder")
    resolvers = runner_resolvers()
    placeholders[0]["server"] = direct_resolver_pick(resolvers)
    notes = ["the direct resolver placeholder %s became %s (DirectResolver.pick over the runner's %s)"
             % (DIRECT_DNS_PLACEHOLDER, placeholders[0]["server"], resolvers or "no resolvers")]
    for rs in c.get("route", {}).get("rule_set", []):
        expect(rs.get("type") == "local" and str(rs.get("path", "")).startswith("@rules/"), "local @rules/ rule-set")
        path = RULES_DIR / rs["path"][len("@rules/"):]
        if not path.is_file():
            raise LabError("rule-set %s: %s is not in the tree" % (rs.get("tag"), rel(path)))
        rs["path"] = str(path)
    if c.get("route", {}).get("rule_set"):
        notes.append("rule-sets from %s, the files the app bundles" % rel(RULES_DIR))
    return notes


def ios_socks_shape(name, server, pub, rundir):
    """Phase 0's iOS SOCKS shape for one line, ready for this runner. Refuses
    a copy that drifted from the committed tun reference beside it."""
    copy = ios_socks_copy(name)
    twin_name, out_name = IOS_SOCKS[name]
    drift = ios_socks_drift(copy, load_json(REFS / twin_name), load_json(REFS / out_name) if out_name else None)
    expect(not drift, "shapes/%s against reference-configs/%s: %s" % (name, twin_name, "; ".join(drift)))
    expect(ios_hev_socks(copy) is not None, "TunArrangement would give sing-box the tun for %s" % name)
    line = ios_lab_server(copy, server, pub)
    return copy, line, ios_runner_fields(copy, rundir)


def ios_tun_shape(name, server, pub, rundir, tun_name):
    """The baseline: sing-box on its gVisor tun, the committed reference for
    the line (ios-tun-reality.json, bypass-tun-reality.json), or for
    Hysteria2 in Global, which has none, ios-tun-reality.json carrying the
    Hysteria2 outbound of bypass-tun-hysteria2.json: buildTun's shape for it,
    as Phase 0's ios-socks-hysteria2.json is built. auto_route off and a
    named interface: the lab routes only what the checks and loads use."""
    if name == "hysteria2":
        c = load_json(REFS / "ios-tun-reality.json")
        c["outbounds"] = [load_json(REFS / "bypass-tun-hysteria2.json")["outbounds"][0]]
    else:
        c = load_json(REFS / name)
    tun = c["inbounds"][0]
    expect(len(c["inbounds"]) == 1 and tun["type"] == "tun" and tun.get("stack") == "gvisor", "ios tun inbound")
    expect(tun.get("address") == ["%s/30" % IOS_TUN_ADDRESS] and tun.get("mtu") == HEV_MTU, "ios tun address and mtu")
    expect(ios_hev_socks(c) is None, "TunArrangement would put hev in front of %s" % name)
    tun["auto_route"] = False
    tun["interface_name"] = tun_name
    line = ios_lab_server(c, server, pub)
    return c, line, ios_runner_fields(c, rundir)


def ios_routes(config):
    """What the kernel sends into the tun for an iOS shape row: sing-box's
    fake-IP range and the tun's resolver (on a phone the default route)."""
    fake = [s["inet4_range"] for s in config["dns"]["servers"] if s.get("type") == "fakeip"]
    expect(len(fake) == 1, "one fakeip server")
    return [fake[0], IOS_TUN_DNS + "/32"]


def page_pattern(n):
    """The page's bytes (loadgen main.go `pattern`, xorshift32): a body that
    equals them came from the page, through the core and the server."""
    out = bytearray(n)
    x = 2463534242
    for i in range(n):
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        out[i] = x & 0xFF
    return bytes(out)


def dns_skip_name(data, off):
    while True:
        n = data[off]
        if n == 0:
            return off + 1
        if n & 0xC0 == 0xC0:
            return off + 2
        off += 1 + n


def dns_a_query(name):
    """(id, bytes) of an A query for `name`, recursion desired."""
    qid = int.from_bytes(os.urandom(2), "big")
    query = struct.pack(">HHHHHH", qid, 0x0100, 1, 0, 0, 0)
    for label in name.rstrip(".").split("."):
        query += bytes([len(label)]) + label.encode("ascii")
    query += b"\0" + struct.pack(">HH", 1, 1)
    return qid, query


def dns_a_answer(data):
    """(rcode, [IPv4 addresses]) of a DNS answer."""
    _, flags, qd, an, _, _ = struct.unpack(">HHHHHH", data[:12])
    off = 12
    for _ in range(qd):
        off = dns_skip_name(data, off) + 4
    addrs = []
    for _ in range(an):
        off = dns_skip_name(data, off)
        rtype, _, _, rdlen = struct.unpack(">HHIH", data[off:off + 10])
        off += 10
        if rtype == 1 and rdlen == 4:
            addrs.append(socket.inet_ntoa(data[off:off + 4]))
        off += rdlen
    return flags & 0xF, addrs


def dns_query_a(server, name, timeout=2.0):
    """One A query over UDP, on a socket of its own (as the system resolver
    asks): {rcode, a: [addresses], rtt_s}. Raises OSError on silence."""
    qid, query = dns_a_query(name)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(timeout)
        t0 = time.time()
        s.sendto(query, (server, 53))
        while True:
            data, _ = s.recvfrom(4096)
            if len(data) >= 12 and struct.unpack(">H", data[:2])[0] == qid:
                break
        rtt = time.time() - t0
    rcode, addrs = dns_a_answer(data)
    return {"rcode": rcode, "a": addrs, "rtt_s": round(rtt, 3)}


def ios_shape_checks(fake_range, seconds=20):
    """Before any load, what a phone needs of the shape: (1) the tun's
    resolver answers an A query with a fake address; (2) HTTP to that name
    brings the page's own bytes; (3) UDP crosses the tun both ways: check 1
    and IOS_UDP_EXCHANGES more queries, each on a socket (a UDP session) of
    its own, each answered with a fake address of its own. The lab has no
    UDP echo; DNS is the UDP a phone sends most. Returns the checks and the
    fake address the loads go to (None when check 1 failed)."""
    net = ipaddress.ip_network(fake_range)
    checks = {"resolver": IOS_TUN_DNS, "name": IOS_CHECK_NAME, "fake_ip_range": fake_range}
    deadline = time.time() + seconds
    answer, err, tries = None, "not tried", 0
    while time.time() < deadline:
        tries += 1
        try:
            answer = dns_query_a(IOS_TUN_DNS, IOS_CHECK_NAME)
            break
        except OSError as e:
            err = "%s: %s" % (type(e).__name__, e)
            time.sleep(0.3)
    fake = None
    if answer is not None:
        fake = next((a for a in answer["a"] if ipaddress.ip_address(a) in net), None)
    checks["dns"] = {
        "ok": fake is not None, "tries": tries, "answer": answer,
        "detail": ("%s A %s (rcode %d, %.3f s, try %d)" % (IOS_CHECK_NAME, fake, answer["rcode"], answer["rtt_s"], tries))
        if fake else ("no fake address: %s" % (answer if answer is not None else err))}
    if fake is None:
        checks["http"] = {"ok": False, "detail": "not tried: no address for the name"}
        checks["udp"] = {"ok": False, "detail": "no DNS answer through the tun: %s" % err if answer is None else
                         "an answer came back over UDP, but not a fake address"}
        return checks, None
    # 2. HTTP to the name, at the address sing-box handed out, with its Host.
    http_res, err = None, "not tried"
    t0 = time.time()
    while time.time() < deadline + 10:
        conn = http.client.HTTPConnection(fake, 80, timeout=15)
        try:
            conn.request("GET", "/bytes?n=%d" % IOS_CHECK_BYTES, headers={"Host": IOS_CHECK_NAME, "Connection": "close"})
            resp = conn.getresponse()
            body = resp.read()
            http_res = {"status": resp.status, "bytes": len(body), "page_bytes": body == page_pattern(IOS_CHECK_BYTES)}
            break
        except (OSError, http.client.HTTPException) as e:
            err = "%s: %s" % (type(e).__name__, e)
            time.sleep(0.5)
        finally:
            conn.close()
    ok = bool(http_res) and http_res["status"] == 200 and http_res["page_bytes"]
    checks["http"] = {"ok": ok, "result": http_res, "seconds": round(time.time() - t0, 3),
                      "detail": ("GET http://%s/bytes?n=%d at %s: %s, %d bytes, %s" % (
                          IOS_CHECK_NAME, IOS_CHECK_BYTES, fake, http_res["status"], http_res["bytes"],
                          "the page's own bytes" if http_res["page_bytes"] else "NOT the page's bytes"))
                      if http_res else "failed: %s" % err}
    # 3. More UDP sessions, one socket each.
    seen, fails = {fake}, []
    for i in range(IOS_UDP_EXCHANGES):
        name = "udp-%d.lab.test" % i
        try:
            a = dns_query_a(IOS_TUN_DNS, name)
            got = [x for x in a["a"] if ipaddress.ip_address(x) in net]
            if not got:
                fails.append("%s: %s" % (name, a))
            seen.update(got[:1])
        except OSError as e:
            fails.append("%s: %s" % (name, e))
    good = 1 + IOS_UDP_EXCHANGES - len(fails)
    checks["udp"] = {"ok": not fails and len(seen) == 1 + IOS_UDP_EXCHANGES, "exchanges": 1 + IOS_UDP_EXCHANGES,
                     "answered": good, "distinct_fake_addresses": len(seen), "failures": fails[:4],
                     "detail": "%d of %d DNS exchanges over UDP, each on its own socket, %d distinct fake addresses%s"
                               % (good, 1 + IOS_UDP_EXCHANGES, len(seen),
                                  ("; first failure %s" % fails[0]) if fails else "")}
    return checks, fake


def find_pid_by_argv(exe_name, needle, seconds=8):
    """The pid of a process whose argv[0] ends in exe_name and whose argv
    holds needle: the real process behind `sudo` (which keeps a monitor
    process of its own between them when use_pty is set)."""
    deadline = time.time() + seconds
    while time.time() < deadline and sys.platform == "darwin":
        # No /proc: ps's full command lines (macOS paths hold no spaces here).
        r = subprocess.run(["ps", "-axww", "-o", "pid=", "-o", "command="], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0].isdigit() and int(parts[0]) > 1 and \
                    parts[1].endswith(exe_name) and needle in parts[2:]:
                return int(parts[0])
        time.sleep(0.1)
    while time.time() < deadline:
        for entry in os.listdir("/proc"):
            if not entry.isdigit() or int(entry) <= 1:
                continue
            try:
                argv = Path("/proc/%s/cmdline" % entry).read_bytes().split(b"\0")
            except OSError:
                continue
            if argv and argv[0].decode(errors="replace").endswith(exe_name) and \
                    needle.encode() in argv[1:]:
                return int(entry)
        time.sleep(0.1)
    return None


def link_up(name):
    """The interface exists and is administratively up (IFF_UP). hev creates
    its tun, then sets the MTU and address, then brings it up; a route into a
    tun that is not up yet is refused ("Device for nexthop is not up")."""
    try:
        return int(Path("/sys/class/net/%s/flags" % name).read_text().strip(), 16) & 0x1 == 0x1
    except (OSError, ValueError):
        return False


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
        "outbounds": [page_outbound()],
    }


def page_outbound():
    return {"tag": "page", "protocol": "freedom",
            "settings": {"redirect": "127.0.0.1:%d" % PORT_PAGE, "ipsBlocked": [],
                         "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}


def xray_webtunnel_server(cert, key):
    """WebTunnel's server side: VLESS over httpupgrade on a path, as the exits'
    `vless-webtunnel-in` (proofkit-dvpn/agent/src/xray_config.rs:981-996), with
    the TLS that nginx ends on an exit ended here by Xray itself, offering
    http/1.1 only. Its own process, so a field this Xray refuses fails only
    the WebTunnel rows."""
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {"tag": "webtunnel", "listen": "127.0.0.1", "port": PORT_WEBTUNNEL, "protocol": "vless",
             "settings": {"clients": [{"id": UUID, "email": "lab-webtunnel"}], "decryption": "none"},
             "streamSettings": {"network": "httpupgrade", "security": "tls",
                                "tlsSettings": {"alpn": ["http/1.1"],
                                                "certificates": [{"certificateFile": str(cert), "keyFile": str(key)}]},
                                "httpupgradeSettings": {"path": WEBTUNNEL_PATH}}},
        ],
        "outbounds": [page_outbound()],
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
        # webtunnel.md's line, plus insecure=1 for the lab's self-signed certificate.
        "webtunnel": "vless://%s@%s:%d?type=httpupgrade&security=tls&encryption=none&fp=chrome&sni=%s&host=%s&path=%s&alpn=h2%%2Chttp%%2F1.1&insecure=1#lab-webtunnel"
                     % (UUID, server, PORT_WEBTUNNEL, SNI_WEBTUNNEL, SNI_WEBTUNNEL, q(WEBTUNNEL_PATH, safe="")),
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
        self.wt_error = "not started"

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
        self.start_webtunnel()

    def start_webtunnel(self):
        try:
            cert, key = self.dir / "webtunnel.crt", self.dir / "webtunnel.key"
            subprocess.run([str(self.loadgen), "selfsigned", "-cert", str(cert), "-key", str(key),
                            "-name", SNI_WEBTUNNEL], check=True, timeout=30)
            config = self.dir / "xray-webtunnel.json"
            write_json(config, xray_webtunnel_server(cert, key))
            test = subprocess.run([str(self.xray), "run", "-test", "-c", str(config)], capture_output=True,
                                  text=True, timeout=60, env=clean_env())
            if test.returncode != 0:
                raise LabError("the WebTunnel server config does not load: " + (test.stdout + test.stderr)[-600:])
            proc = Proc([self.xray, "run", "-c", config], self.dir / "xray-webtunnel.log", cwd=self.dir, env=clean_env())
            self.procs.append(proc)
            if not wait_port(PORT_WEBTUNNEL, 20, proc):
                raise LabError("port %d did not open: %s" % (PORT_WEBTUNNEL, tail(proc.log_path)))
            self.wt_error = None
        except Exception as e:  # only the WebTunnel rows depend on it
            self.wt_error = str(e)
            log("WebTunnel server not up:", e)

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


def transfer_argv(op, nbytes, socks, timeout_s, flows=None, target=THROUGH):
    """get/put: one transfer of nbytes. burst-get/burst-put: `flows` transfers
    at once, nbytes each, one connection each. `target` is the address the
    URLs name: TEST-NET-3 everywhere but the iOS shape rows, which go to a
    fake address sing-box handed out."""
    if op == "get":
        argv = ["get", "-url", "http://%s/bytes?n=%d" % (target, nbytes), "-expect", str(nbytes)]
    elif op == "put":
        argv = ["put", "-url", "http://%s/sink" % target, "-bytes", str(nbytes)]
    elif op == "burst-get":
        argv = ["burst", "-op", "get", "-n", str(flows), "-each", str(nbytes),
                "-url", "http://%s/bytes?n=%d" % (target, nbytes)]
    elif op == "burst-put":
        argv = ["burst", "-op", "put", "-n", str(flows), "-each", str(nbytes), "-url", "http://%s/sink" % target]
    else:
        raise LabError("unknown transfer %r" % op)
    argv += ["-timeout", "%ds" % timeout_s]
    if socks:
        argv += ["-socks", socks]
    return argv


class LocalHost:
    def __init__(self, loadgen):
        self.loadgen = loadgen

    def loadgen_run(self, op, nbytes, socks, timeout_s, flows=None, target=THROUGH):
        argv = [str(self.loadgen)] + transfer_argv(op, nbytes, socks, timeout_s, flows, target)
        try:
            cp = subprocess.run(argv, capture_output=True, text=True, timeout=timeout_s + 30, env=clean_env())
            return parse_loadgen(cp.stdout, cp.stderr)
        except subprocess.TimeoutExpired:
            return {"ok": False, "error": "loadgen did not return"}

    def probe(self, socks, seconds=30, target=THROUGH):
        deadline = time.time() + seconds
        last = {"ok": False, "error": "not tried"}
        while time.time() < deadline:
            last = self.loadgen_run("get", 1000, socks, 8, target=target)
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


def run_burst(host, socks, timeout_s=150, target=THROUGH):
    """The burst phase: BURST_FLOWS downloads at once, a pause, BURST_FLOWS
    uploads at once, a short tail. Returns the two results and the window
    (start, end) the figures are read over, tail included."""
    b0 = time.time()
    bdown = host.loadgen_run("burst-get", BURST_EACH_DOWN, socks, timeout_s, BURST_FLOWS, target=target)
    time.sleep(PAUSE_S)
    # Where the upload half starts, so the figures can say which half peaked.
    bdown["host_up_start"] = time.time()
    bup = host.loadgen_run("burst-put", BURST_EACH_UP, socks, timeout_s, BURST_FLOWS, target=target)
    time.sleep(BURST_TAIL_S)
    return bdown, bup, (b0, time.time())


def burst_figures(row, bdown, bup, rows, win, mem_key="rss", cpu_key="cpu"):
    """extra.burst_*: the peak memory, throughput and CPU of the burst. The
    headline peak_rss_mb covers the whole life, so it includes the burst too."""
    b0, b1 = win
    moved = (bdown.get("bytes") or 0) + (bup.get("bytes") or 0)
    cpu = delta(rows, cpu_key, b0, b1)
    ex = row["extra"]
    ex.update({
        "burst_flows": BURST_FLOWS,
        "burst_bytes_each_down_up": [BURST_EACH_DOWN, BURST_EACH_UP],
        "burst_peak_mb": mb(max_of(rows, mem_key, b0, b1)),
        "burst_mbps_down": rnd(bdown.get("mbps")) if bdown.get("ok") else None,
        "burst_mbps_up": rnd(bup.get("mbps")) if bup.get("ok") else None,
        "burst_ok_flows_down_up": [bdown.get("ok_flows", 0), bup.get("ok_flows", 0)],
        "burst_slowest_first_byte_s": rnd(bdown.get("ttfb_s"), 3),
        "burst_cpu_s_per_gb": rnd(cpu_per_gb(cpu, moved), 2) if moved else None,
    })
    split = bdown.get("host_up_start")
    if split:
        # The two halves apart: 32 downloads at once (receive windows), then
        # 32 uploads at once (upload buffers).
        ex["burst_down_half_peak_mb"] = mb(max_of(rows, mem_key, b0, split))
        ex["burst_up_half_peak_mb"] = mb(max_of(rows, mem_key, split, b1))
    if rows and "fp" in rows[0]:
        ex["burst_peak_phys_footprint_mb"] = mb(max_of(rows, "fp", b0, b1))
        if split:
            ex["burst_down_half_peak_phys_footprint_mb"] = mb(max_of(rows, "fp", b0, split))
            ex["burst_up_half_peak_phys_footprint_mb"] = mb(max_of(rows, "fp", split, b1))
    return [("burst download: " + bdown.get("error", "")) if not bdown.get("ok") else "",
            ("burst upload: " + bup.get("error", "")) if not bup.get("ok") else ""]


def harness_burst_figures(row, h, win):
    if not h or win[0] is None:
        return
    row["extra"].update({
        "burst_go_sys_minus_released_peak_mb": mb(max_of(h, "sysrel", *win)),
        "burst_go_goroutines_peak": max_of(h, "goroutines", *win),
        "burst_go_heap_objects_peak_mb": mb(max_of(h, "heap", *win)),
    })


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
        self.kind = kw["kind"]              # exec | harness | harness-tun | harness-hev
        self.core = kw["core"]
        self.scenario = kw["scenario"]
        self.settings = kw["settings"]
        self.argv = kw.get("argv")          # exec
        # A dict, or a function of the run directory returning (dict, notes):
        # built inside the run, so a shape that fails fails its own row.
        self.config = kw.get("config")
        self.tun_name = kw.get("tun_name")  # harness-tun / harness-hev: a tun other than the kind's own
        self.ios_shape = kw.get("ios_shape", False)  # the iOS shape checks, routes and fake-address loads
        self.harness_core = kw.get("harness_core")
        self.mode = kw.get("mode")
        self.socks = kw.get("socks", "127.0.0.1:%d" % PORT_SOCKS)
        self.version = kw.get("version", "unknown")
        self.notes = list(kw.get("notes", []))
        self.needs_webtunnel = kw.get("needs_webtunnel", False)
        self.env = kw.get("env")            # added to the scrubbed environment
        self.xrs_check = kw.get("xrs_check")  # xray-rust binary: `config check` the config first
        self.aux_checks = kw.get("aux_checks", {})  # name -> config, `config check`ed only
        self.extra = dict(kw.get("extra", {}))
        self.slug = re.sub(r"[^a-z0-9]+", "-", ("%s %s %s" % (self.core, self.scenario, self.settings)).lower()).strip("-")


def pair_reader(reader, core_pid, front_pid):
    """One sample of two processes taken together: the sum, and each."""
    def read():
        a, b = reader.read(core_pid), reader.read(front_pid)
        return {"rss": a["rss"] + b["rss"], "cpu": a["cpu"] + b["cpu"],
                "rss_core": a["rss"], "rss_front": b["rss"], "cpu_core": a["cpu"], "cpu_front": b["cpu"],
                "hwm_core": a.get("hwm"), "hwm_front": b.get("hwm")}
    return read


def run_desktop_spec(spec, ctx):
    rundir = ctx["out"] / "runs" / spec.slug
    rundir.mkdir(parents=True, exist_ok=True)
    reader, host, platform = ctx["reader"], ctx["host"], ctx["platform"]
    notes = list(spec.notes)
    metric = "resident size (VmRSS)" if isinstance(reader, LinuxReader) else "resident size (ri_resident_size)"
    if spec.kind == "harness-hev":
        metric = "resident size (VmRSS), hev + the harness summed"
    row = base_row(platform, spec.core, spec.version, spec.scenario, spec.settings, metric)
    row["extra"].update(spec.extra)
    if (spec.socks or spec.kind == "harness-hev") and not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken by the previous run" % PORT_SOCKS)
    cfg_path = None
    config = spec.config
    if callable(config):
        config, cfg_notes = config(rundir)
        notes += cfg_notes
    if config is not None:
        cfg_path = rundir / "config.json"
        write_json(cfg_path, config)
    if spec.xrs_check:
        for name, aux in spec.aux_checks.items():
            aux_path = rundir / ("aux-%s.json" % re.sub(r"[^a-z0-9]+", "-", name.lower()))
            write_json(aux_path, aux)
            ok, aux_notes, _ = xrs_check(spec.xrs_check, aux_path, rundir / ("aux-check-%s.json" % aux_path.stem))
            row["extra"].setdefault("config_check_other_shapes", {})[name] = {"accepted": ok, "diagnostics": aux_notes}
        ok, check_notes, report = xrs_check(spec.xrs_check, cfg_path, rundir / "config-check.json")
        row["extra"]["xray_rust_core_version"] = report.get("core_version")
        notes += check_notes
        if not ok:
            raise LabError("xray-rust refused the config (%s): %s" % (
                rundir / "config-check.json", "; ".join(check_notes) or "no diagnostics"))
    sudo = spec.kind == "harness-tun"
    tun_dev = {"harness-tun": TUN_NAME, "harness-hev": TUN_HEV}.get(spec.kind)
    if tun_dev and spec.tun_name:
        tun_dev = spec.tun_name
    if spec.ios_shape:
        # A tun an earlier iOS shape row left behind would hold the same
        # address; neither should exist between rows.
        for stale in (TUN_IOS_HEV, TUN_IOS_GVISOR):
            if Path("/sys/class/net/%s" % stale).exists():
                notes.append("deleted a stale %s before the run" % stale)
                subprocess.run(["sudo", "-n", "ip", "link", "delete", stale], capture_output=True, check=False)
    if spec.kind == "exec":
        argv = [a if a != "{config}" else str(cfg_path) for a in spec.argv]
    else:
        argv = [ctx["bin"] / "harness", "-core", spec.harness_core, "-mode", spec.mode,
                "-stats", rundir / "stats.jsonl", "-ready", rundir / "ready.json"]
        if cfg_path:
            argv += ["-config", cfg_path]
    t_launch = time.time()
    proc = Proc(argv, rundir / "core.log", cwd=rundir, env=clean_env(spec.env), sudo=sudo)
    front = None
    rows, final = [], {}
    down = up = {"ok": False, "error": "not run"}
    idle_win = (None, None)
    wins = ((None, None), (None, None))
    bwin = (None, None)
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
        if spec.kind == "harness-hev":
            # hev in front of the harness's SOCKS, as root: it opens its own tun.
            hev_cfg = rundir / "hev.yml"
            hev_port = PORT_SOCKS
            if spec.ios_shape:
                # The extension's decision and the hev it starts: the port and
                # the login TunArrangement.singBoxSocks reads from the config.
                arrangement = ios_hev_socks(config)
                if arrangement is None:
                    raise LabError("TunArrangement.singBoxSocks finds no SOCKS inbound for hev in %s" % cfg_path)
                hev_port, login = arrangement
                row["extra"]["arrangement"] = {"case": "singBoxBehindHev", "port": hev_port,
                                               "login": "username %r and a password" % login[0] if login else None}
                hev_cfg.write_text(hev_config(hev_port, login=login, name=tun_dev, ipv4=IOS_TUN_ADDRESS))
            else:
                hev_cfg.write_text(hev_config(PORT_SOCKS))
            if not wait_port(hev_port, 20, proc):
                raise LabError("the harness's SOCKS port never opened:\n" + tail(rundir / "core.log"))
            front = Proc([ctx["hev"], hev_cfg], rundir / "hev.log", cwd=rundir, env=clean_env(), sudo=True)
            real = find_pid_by_argv(Path(ctx["hev"]).name, str(hev_cfg))
            if real is None:
                raise LabError("hev did not start:\n" + tail(rundir / "hev.log"))
            front.pid = real
            deadline = time.time() + 10
            while not link_up(tun_dev) and time.time() < deadline and front.alive():
                time.sleep(0.1)
            if not link_up(tun_dev):
                raise LabError("hev did not bring up %s:\n%s" % (tun_dev, tail(rundir / "hev.log")))
            row["version"] += ", " + ctx.get("hev_version", "hev-socks5-tunnel")
        elif spec.ios_shape:
            row["extra"]["arrangement"] = {"case": "singBoxTun" if ios_hev_socks(config) is None else "singBoxBehindHev"}
        t_ready = time.time()
        row["extra"]["start_to_ready_s"] = rnd(t_ready - t_launch, 2)
        if tun_dev:
            nets = ios_routes(config) if spec.ios_shape else [THROUGH.rsplit(".", 1)[0] + ".0/24"]
            for net in nets:
                r = subprocess.run(["sudo", "-n", "ip", "route", "replace", net, "dev", tun_dev],
                                   capture_output=True, text=True)
                if r.returncode != 0:
                    raise LabError("route %s into %s: %s" % (net, tun_dev, r.stderr.strip()))
        target = THROUGH
        t_probe = t_ready
        if spec.ios_shape:
            checks, fake = ios_shape_checks(ios_routes(config)[0])
            t_probe = time.time()
            checks["seconds"] = rnd(t_probe - t_ready, 2)
            write_json(rundir / "ios-checks.json", checks)
            row["extra"]["ios_checks"] = checks
            summary = "; ".join("check %s %s: %s" % (k, "ok" if checks[k]["ok"] else "FAILED", checks[k]["detail"])
                                for k in ("dns", "http", "udp"))
            if not all(checks[k]["ok"] for k in ("dns", "http", "udp")):
                err = LabError("the iOS shape checks failed before any load: %s\ncore.log:\n%s%s" % (
                    summary, tail(rundir / "core.log"), ("\nhev.log:\n" + tail(rundir / "hev.log")) if front else ""))
                err.extra = {"ios_checks": checks, "arrangement": row["extra"].get("arrangement")}
                raise err
            notes.append(summary)
            notes.append("every load goes to %s's fake address, http://%s/, so each connection is a fake-IP lookup "
                         "in sing-box" % (IOS_CHECK_NAME, fake))
            target = fake
        probe = host.probe(spec.socks, 40, target=target)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core in 40 s (%s):\n%s%s" % (
                probe.get("error"), tail(rundir / "core.log"),
                ("\nhev:\n" + tail(rundir / "hev.log")) if front else ""))
        row["extra"]["first_bytes_s"] = rnd(time.time() - t_probe, 2)
        read = pair_reader(reader, proc.pid, front.pid) if front else (lambda: reader.read(proc.pid))
        sampler = Sampler(read, PERIOD_S)
        sampler.start()
        t0 = time.time()
        time.sleep(IDLE_SETTLE_S)
        idle_win = (t0 + IDLE_SETTLE_S / 2, time.time())
        if spec.xrs_check:
            # After the idle window: how many threads the runtime started.
            row["extra"]["threads_idle"] = thread_census(proc.pid)
        h0 = time.time()
        down = host.loadgen_run("get", DOWN_BYTES, spec.socks, 240, target=target)
        h1 = time.time()
        time.sleep(PAUSE_S)
        h2 = time.time()
        up = host.loadgen_run("put", UP_BYTES, spec.socks, 180, target=target)
        h3 = time.time()
        time.sleep(IDLE_AFTER_S)
        t_idle_end = time.time()
        bdown, bup, bwin = run_burst(host, spec.socks, target=target)
        try:
            final = read()
        except Exception:
            final = {}
        if spec.xrs_check:
            row["extra"]["threads_after_burst"] = thread_census(proc.pid)
        rows = sampler.halt()
        rows = reader.finish(rows, proc.pid, notes)
        wins = (window_of(down, (h0, h1)), window_of(up, (h2, h3)))
        row["extra"]["idle_after_rss_mb"] = mb(median_in(rows, "rss", h3 + 1, t_idle_end))
        notes += burst_figures(row, bdown, bup, rows, bwin)
    finally:
        if sampler is not None and sampler.is_alive():
            sampler.halt()
        if front is not None:
            front.stop()
        proc.stop()
    if front is not None and rows:
        # Each process on its own; the headline columns are their sum.
        ex = row["extra"]
        # Bytes as transfer_figures counts them: a failed transfer moved none.
        moved = ((down.get("bytes") if down.get("ok") else 0) or 0) + ((up.get("bytes") if up.get("ok") else 0) or 0)
        for who, label in (("front", "hev"), ("core", "harness")):
            ex["%s_rss_idle_mb" % label] = mb(median_in(rows, "rss_" + who, *idle_win))
            ex["%s_rss_steady_mb" % label] = mb(median_mid(rows, "rss_" + who, *wins[0]))
            ex["%s_rss_peak_mb" % label] = mb(max(final.get("hwm_" + who) or 0, max_of(rows, "rss_" + who) or 0) or None)
            ex["%s_rss_burst_peak_mb" % label] = mb(max_of(rows, "rss_" + who, *bwin))
            cd = delta(rows, "cpu_" + who, *wins[0])
            cu = delta(rows, "cpu_" + who, *wins[1])
            ex["%s_cpu_s_per_gb" % label] = rnd(cpu_per_gb(cd + cu, moved), 2) if None not in (cd, cu) and moved else None
        notes.append("hev's own peak is its VmHWM; the summed peak is the largest summed sample (two "
                     "processes' high-water marks do not add)")
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
        h = read_harness_stats(rundir / "stats.jsonl")
        harness_figures(row, h, t_ready, idle_win, wins[0])
        harness_burst_figures(row, h, bwin)
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
    env = clean_env({"GOGC": "10", "GOMEMLIMIT": "40MiB", "OLCRTC_LAB_CONSTRAINED": "1"}
                    if settings == "ios-limits" else None)
    if settings == "ios-limits":
        notes.append("GOGC=10 GOMEMLIMIT=40MiB through the environment, the extension's percent and limit; "
                     "the phones' constrained buffer profile (mobile.New's client.UseConstrainedBuffers) "
                     "on for both peers")
    else:
        notes.append("server-sized buffers (smux 32 MiB / 4 MiB) on both peers: the desktop app's olcrtc binary's "
                     "profile, which never calls mobile.New()")
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
    constrained = "[lab] constrained buffers on" in text
    row["extra"]["constrained_buffers"] = constrained
    if settings == "ios-limits" and not constrained:
        notes.append("WARNING: the constrained profile was not confirmed in the log; this row is NOT the phone's profile")
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


# ---------------------------------------------------------------- ClientHello


class HelloTap(threading.Thread):
    """A loopback relay in front of a server port that keeps the client's
    first flight of each connection, in accept order (up to `keep` of them):
    what the server is shown. The bytes pass unchanged both ways."""

    def __init__(self, listen_port, upstream_port, keep=8):
        super().__init__(daemon=True)
        self.upstream = upstream_port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", listen_port))
        self.sock.listen(64)
        self.sock.settimeout(0.5)
        self.keep = keep
        self.flights = []  # one bytearray per kept connection, client to server
        self._halt = threading.Event()
        self.conns = []

    @property
    def first(self):
        return bytes(self.flights[0]) if self.flights else None

    def run(self):
        while not self._halt.is_set():
            try:
                c, _ = self.sock.accept()
            except OSError:
                continue
            try:
                u = socket.create_connection(("127.0.0.1", self.upstream), timeout=5)
            except OSError:
                c.close()
                continue
            u.settimeout(None)
            self.conns += [c, u]
            buf = None
            if len(self.flights) < self.keep:
                buf = bytearray()
                self.flights.append(buf)
            threading.Thread(target=self._pump, args=(c, u, buf), daemon=True).start()
            threading.Thread(target=self._pump, args=(u, c, None), daemon=True).start()

    def _pump(self, src, dst, buf):
        try:
            while True:
                data = src.recv(65536)
                if not data:
                    break
                if buf is not None and len(buf) < 32768:
                    buf += data
                dst.sendall(data)
        except OSError:
            pass
        finally:
            for s in (src, dst):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def halt(self):
        self._halt.set()
        self.join(5)
        for s in self.conns + [self.sock]:
            try:
                s.close()
            except OSError:
                pass


def is_grease(v):
    return (v & 0x0f0f) == 0x0a0a and (v >> 8) == (v & 0xff)


EXT_NAMES = {0: "server_name", 5: "status_request", 10: "supported_groups", 11: "ec_point_formats",
             13: "signature_algorithms", 16: "alpn", 18: "sct", 21: "padding", 23: "extended_master_secret",
             27: "compress_certificate", 28: "record_size_limit", 34: "delegated_credentials", 35: "session_ticket",
             41: "pre_shared_key", 43: "supported_versions", 45: "psk_key_exchange_modes", 51: "key_share",
             17513: "application_settings_old", 17613: "application_settings", 65037: "encrypted_client_hello",
             65281: "renegotiation_info"}
GROUP_NAMES = {0x001d: "x25519", 0x0017: "secp256r1", 0x0018: "secp384r1", 0x0019: "secp521r1",
               0x11ec: "X25519MLKEM768", 0x6399: "X25519Kyber768Draft00", 0x0100: "ffdhe2048", 0x0101: "ffdhe3072"}
CERT_COMPRESSION = {1: "zlib", 2: "brotli", 3: "zstd"}


def _u8_list(data):
    return list(data[1:1 + data[0]]) if data else []


def _ext_detail(et, data):
    """What an extension says, beyond its length, for the fields the parity
    table compares. Random bodies (ECH payload, key shares, GREASE) are
    described by their lengths only."""
    if et == 27:
        return [CERT_COMPRESSION.get(v, "0x%04x" % v) for v in _u8_list_u16(data)]
    if et in (17513, 17613):
        protos, q = [], 2
        while q < len(data):
            n = data[q]
            protos.append(data[q + 1:q + 1 + n].decode(errors="replace"))
            q += 1 + n
        return {"codepoint": et, "protocols": protos}
    if et == 65037 and len(data) >= 6:
        d = {"type": data[0], "kdf": "0x%04x" % int.from_bytes(data[1:3], "big"),
             "aead": "0x%04x" % int.from_bytes(data[3:5], "big")}
        if data[0] == 0 and len(data) >= 8:
            enc = int.from_bytes(data[6:8], "big")
            d["enc_bytes"] = enc
            if len(data) >= 10 + enc:
                d["payload_bytes"] = int.from_bytes(data[8 + enc:10 + enc], "big")
        return d
    if et == 45 or et == 11:
        return _u8_list(data)
    if et in (5, 65281, 28, 34):
        return data.hex()
    return None


def _u8_list_u16(data):
    """A u8-length-prefixed list of u16 values (compress_certificate)."""
    if not data:
        return []
    return [int.from_bytes(data[j:j + 2], "big") for j in range(1, 1 + data[0], 2)]


def parse_client_hello(buf):
    """The first ClientHello in a client's first flight: its shape, JA3 and
    JA4. GREASE values are kept in the ordered lists as "GREASE" and left out
    of the fingerprints, as JA3 and JA4 define them."""
    hs, i = b"", 0
    rec_len = None
    rec_version = buf[1:3].hex() if len(buf) >= 3 else None
    while i + 5 <= len(buf) and buf[i] == 0x16:
        n = struct.unpack(">H", buf[i + 3:i + 5])[0]
        rec_len = n if rec_len is None else rec_len
        hs += buf[i + 5:i + 5 + n]
        i += 5 + n
        if len(hs) >= 4 and len(hs) >= 4 + int.from_bytes(hs[1:4], "big"):
            break
    if len(hs) < 4 or hs[0] != 1:
        return {"error": "no TLS ClientHello in the first %d bytes (starts %s)" % (len(buf), buf[:8].hex())}
    body = hs[4:4 + int.from_bytes(hs[1:4], "big")]
    p = 0

    def take(n):
        nonlocal p
        v = body[p:p + n]
        p += n
        return v

    legacy = take(2).hex()
    take(32)
    sid_len = take(1)[0]
    take(sid_len)
    cs = take(int.from_bytes(take(2), "big"))
    ciphers = [int.from_bytes(cs[j:j + 2], "big") for j in range(0, len(cs), 2)]
    compression = list(take(take(1)[0]))
    ext_end = p + 2 + int.from_bytes(body[p:p + 2], "big")
    p += 2
    exts, info = [], {}
    while p + 4 <= ext_end:
        et, el = struct.unpack(">HH", body[p:p + 4])
        data = body[p + 4:p + 4 + el]
        p += 4 + el
        exts.append((et, el, data))
    sigs, groups, shares, alpn, versions, sni = [], [], [], [], [], None
    for et, el, data in exts:
        if et == 0 and len(data) > 5:
            sni = data[5:5 + int.from_bytes(data[3:5], "big")].decode(errors="replace")
        elif et == 10:
            groups = [int.from_bytes(data[j:j + 2], "big") for j in range(2, 2 + int.from_bytes(data[:2], "big"), 2)]
        elif et == 13:
            sigs = [int.from_bytes(data[j:j + 2], "big") for j in range(2, 2 + int.from_bytes(data[:2], "big"), 2)]
        elif et == 16:
            q = 2
            while q < len(data):
                n = data[q]
                alpn.append(data[q + 1:q + 1 + n].decode(errors="replace"))
                q += 1 + n
        elif et == 43:
            versions = [int.from_bytes(data[j:j + 2], "big") for j in range(1, 1 + data[0], 2)]
        elif et == 51:
            q = 2
            while q + 4 <= len(data):
                g, n = struct.unpack(">HH", data[q:q + 4])
                shares.append((g, n))
                q += 4 + n
        elif et == 21:
            info["padding_bytes"] = el
        elif et == 65037:
            info["ech_bytes"] = el

    def name(v, names):
        return "GREASE" if is_grease(v) else names.get(v, "0x%04x" % v)

    clean_c = [c for c in ciphers if not is_grease(c)]
    clean_e = [e for e, _, _ in exts if not is_grease(e)]
    clean_v = [v for v in versions if not is_grease(v)]
    top = max(clean_v) if clean_v else int(legacy, 16)
    ver = {0x0304: "13", 0x0303: "12", 0x0302: "11", 0x0301: "10"}.get(top, "00")
    first = alpn[0] if alpn else ""
    a_alpn = (first[0] + first[-1]) if first else "00"
    ja4_a = "t%s%s%02d%02d%s" % (ver, "d" if sni is not None else "i", min(len(clean_c), 99), min(len(clean_e), 99), a_alpn)
    ja4_b = hashlib.sha256(",".join(sorted("%04x" % c for c in clean_c)).encode()).hexdigest()[:12]
    c_exts = ",".join(sorted("%04x" % e for e in clean_e if e not in (0, 16)))
    c_sigs = ",".join("%04x" % s for s in sigs if not is_grease(s))
    ja4_c = hashlib.sha256((c_exts + ("_" + c_sigs if c_sigs else "")).encode()).hexdigest()[:12]
    ec_formats = []
    for et, _, data in exts:
        if et == 11 and data:
            ec_formats = list(data[1:1 + data[0]])
    ja3 = "%d,%s,%s,%s,%s" % (int(legacy, 16), "-".join(str(c) for c in clean_c), "-".join(str(e) for e in clean_e),
                              "-".join(str(g) for g in groups if not is_grease(g)), "-".join(str(f) for f in ec_formats))
    # The parity table's fields: the extension set and order without GREASE,
    # each extension's length, where GREASE sits, and what the extensions
    # whose content matters say.
    ext_order = [name(e, EXT_NAMES) for e, _, _ in exts if not is_grease(e)]
    ext_lengths, ext_details, grease_exts = {}, {}, []
    for pos, (et, el, data) in enumerate(exts):
        if is_grease(et):
            grease_exts.append("%s:%d" % ("first" if pos == 0 else "last" if pos == len(exts) - 1 else "at %d" % pos, el))
            continue
        ext_lengths[name(et, EXT_NAMES)] = el
        d = _ext_detail(et, data)
        if d is not None:
            ext_details[name(et, EXT_NAMES)] = d
    info.update({
        "record_version": rec_version,
        "compression_methods": compression,
        "ext_order": ext_order,
        "ext_set": sorted(ext_order),
        "ext_lengths": ext_lengths,
        "ext_details": ext_details,
        "grease_extensions": grease_exts,
        "grease_cipher_positions": [j for j, c in enumerate(ciphers) if is_grease(c)],
        "ja3_full": ja3,
    })
    info.update({
        "ja4": "%s_%s_%s" % (ja4_a, ja4_b, ja4_c),
        "ja3_md5": hashlib.md5(ja3.encode()).hexdigest(),
        "first_record_bytes": rec_len,
        "client_hello_bytes": len(body) + 4,
        "legacy_version": legacy,
        "session_id_bytes": sid_len,
        "sni": sni,
        "alpn": alpn,
        "supported_versions": [name(v, {0x0304: "TLS1.3", 0x0303: "TLS1.2"}) for v in versions],
        "cipher_suites": [name(c, {}) for c in ciphers],
        "extensions": ["%s(%d)" % (name(e, EXT_NAMES), n) for e, n, _ in exts],
        "supported_groups": [name(g, GROUP_NAMES) for g in groups],
        "key_shares": ["%s:%d" % (name(g, GROUP_NAMES), n) for g, n in shares],
        "signature_algorithms": ["%04x" % s for s in sigs],
        "grease": any(is_grease(v) for v in ciphers + [e for e, _, _ in exts] + groups),
    })
    return info


# Each client's hello is captured this many times, each from a fresh process
# (a fresh browser profile for Chrome), so what a client draws per connection
# shows against what it keeps.
HELLO_CAPTURES = 3
# The fields the parity table compares, each reduced to what a fingerprinter
# sees; the server name's own bytes are taken out of the lengths, since the
# lines use names of different lengths.
# Fields a client draws per connection (Chrome's ECH GREASE payload is one of
# four lengths, and the hello's length follows): a difference here is told
# apart from a fixed one ("≈" in the table, "drawn" in the JSON).
HELLO_DRAWN = ("ech", "ech_bytes", "padding_bytes", "hello_bytes_minus_name", "record_bytes_minus_name")
HELLO_FIELDS = ("ja4", "record_version", "legacy_version", "session_id_bytes", "compression_methods",
                "cipher_suites", "grease_cipher_positions", "supported_versions", "ext_set", "permutes",
                "ext_lengths", "grease_extensions", "supported_groups", "key_shares", "alpn", "signature_algorithms",
                "ech", "ech_bytes", "padding_bytes", "compress_certificate", "application_settings",
                "psk_key_exchange_modes", "ec_point_formats", "status_request", "renegotiation_info",
                "hello_bytes_minus_name", "record_bytes_minus_name")


def hello_slug(core, line):
    return "hello-" + re.sub(r"[^a-z0-9]+", "-", ("%s %s" % (core, line)).lower()).strip("-")


def hello_summary(h):
    """One capture reduced to HELLO_FIELDS (permutes is filled in across captures)."""
    if not h or h.get("error"):
        return None
    n = len(h.get("sni") or "")
    # ECH and padding have fields of their own (their lengths are drawn).
    lengths = {k: v for k, v in (h.get("ext_lengths") or {}).items() if k not in ("encrypted_client_hello", "padding")}
    if "server_name" in lengths:
        lengths["server_name"] = "name+%d" % (lengths["server_name"] - n)
    lengths = dict(sorted(lengths.items()))
    det = h.get("ext_details") or {}
    alps = [k for k in det if k.startswith("application_settings")]
    return {
        "ja4": h.get("ja4"), "record_version": h.get("record_version"), "legacy_version": h.get("legacy_version"),
        "session_id_bytes": h.get("session_id_bytes"), "compression_methods": h.get("compression_methods"),
        "cipher_suites": h.get("cipher_suites"), "grease_cipher_positions": h.get("grease_cipher_positions"),
        "supported_versions": h.get("supported_versions"), "ext_set": h.get("ext_set"), "ext_order": h.get("ext_order"),
        "ext_lengths": lengths, "grease_extensions": h.get("grease_extensions"),
        "supported_groups": h.get("supported_groups"), "key_shares": h.get("key_shares"), "alpn": h.get("alpn"),
        "signature_algorithms": h.get("signature_algorithms"),
        "ech": {k: v for k, v in (det.get("encrypted_client_hello") or {}).items()} or None,
        "ech_bytes": h.get("ech_bytes"), "padding_bytes": h.get("padding_bytes"),
        "compress_certificate": det.get("compress_certificate"),
        "application_settings": ["codepoint %d, %s" % (det[k]["codepoint"], ",".join(det[k]["protocols"]))
                                 for k in alps] or None,
        "psk_key_exchange_modes": det.get("psk_key_exchange_modes"), "ec_point_formats": det.get("ec_point_formats"),
        "status_request": det.get("status_request"), "renegotiation_info": det.get("renegotiation_info"),
        "hello_bytes_minus_name": (h.get("client_hello_bytes") or 0) - n,
        "record_bytes_minus_name": (h.get("first_record_bytes") or 0) - n,
    }


def hello_variation(summaries):
    """Across the captures of one client: each field's distinct values, and
    whether the extension order (GREASE aside) moved between connections."""
    caps = [s for s in summaries if s]
    out = {"captures": len(caps)}
    if not caps:
        return out
    orders = {tuple(s["ext_order"] or []) for s in caps}
    out["permutes"] = len(orders) > 1 if len(caps) > 1 else None
    out["distinct_orders"] = len(orders)
    varies = []
    for f in HELLO_FIELDS:
        if f == "permutes":
            continue
        vals = []
        for s in caps:
            v = s.get(f)
            if v not in vals:
                vals.append(v)
        out[f] = vals[0] if len(vals) == 1 else {"varies": vals}
        if len(vals) > 1:
            varies.append(f)
    out["varies"] = varies
    return out


def run_hello(core, line, config, argv, env, upstream, ctx):
    """HELLO_CAPTURES short starts of a core whose line points at the tap
    instead of the server. Each start makes one 1000-byte fetch, whose
    connection's first flight is a capture, then a second fetch: a later
    connection of the same process, kept apart (it may resume or reuse)."""
    rundir = ctx["out"] / "runs" / hello_slug(core, line)
    rundir.mkdir(parents=True, exist_ok=True)
    row = base_row(ctx["platform"], core, "", "client hello: " + line, "", "")
    cfg = rundir / "config.json"
    write_json(cfg, config)
    captures, later, fetches = [], [], []
    for i in range(HELLO_CAPTURES):
        if not wait_port_free(PORT_SOCKS):
            raise LabError("port %d is still taken by the previous run" % PORT_SOCKS)
        tap = HelloTap(PORT_TAP, upstream)
        tap.start()
        proc = None
        log_path = rundir / ("core-%d.log" % (i + 1))
        try:
            proc = Proc([a if a != "{config}" else str(cfg) for a in argv], log_path, cwd=rundir, env=clean_env(env))
            if not wait_port(PORT_SOCKS, 20, proc):
                raise LabError("the core's SOCKS port never opened:\n" + tail(log_path))
            probe = ctx["host"].probe("127.0.0.1:%d" % PORT_SOCKS, 20)
            again = ctx["host"].loadgen_run("get", 1000, "127.0.0.1:%d" % PORT_SOCKS, 8)
            fetches.append([bool(probe.get("ok")), bool(again.get("ok"))])
            time.sleep(0.3)
        finally:
            if proc is not None:
                proc.stop()
            tap.halt()
        flights = [bytes(f) for f in tap.flights]
        for j, f in enumerate(flights):
            (rundir / ("first-flight-%d-%d.bin" % (i + 1, j + 1))).write_bytes(f)
        if not flights or not flights[0]:
            raise LabError("the core sent nothing to the server port (capture %d):\n%s" % (i + 1, tail(log_path)))
        if i == 0:
            (rundir / "first-flight.bin").write_bytes(flights[0])
        captures.append(parse_client_hello(flights[0]))
        for f in flights[1:]:
            h = parse_client_hello(f)
            later.append({"capture": i + 1, "ja4": h.get("ja4"), "error": h.get("error"),
                          "pre_shared_key": "pre_shared_key" in (h.get("ext_set") or []),
                          "ext_order": h.get("ext_order"), "client_hello_bytes": h.get("client_hello_bytes")})
    hello = captures[0]
    row["extra"].update(hello)
    row["extra"]["fetch_through_tap_ok"] = all(f[0] for f in fetches)
    row["extra"]["fetches_ok"] = fetches
    row["extra"]["captures"] = [hello_summary(h) or {"error": h.get("error")} for h in captures]
    row["extra"]["variation"] = hello_variation([hello_summary(h) for h in captures])
    row["extra"]["later_connections"] = later
    errors = [h.get("error") for h in captures if h.get("error")]
    row["notes"] = "; ".join(errors) if errors else (
        "the first flight of the first connection of %d fresh processes, as the server's port saw it "
        "(first-flight-<process>-<connection>.bin beside the config); a second fetch per process opened %d later "
        "connection(s), kept apart in extra.later_connections" % (len(captures), len(later)))
    return row


def find_chrome():
    """A Google Chrome on the runner: $CHROME_BIN, then the usual names."""
    cands = [os.environ.get("CHROME_BIN", "")]
    if sys.platform == "darwin":
        cands.append("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    for n in ("google-chrome", "google-chrome-stable", "chrome"):
        cands.append(shutil.which(n) or "")
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


CHROME_CORE = "Google Chrome (headless, fresh profile)"


def run_chrome_hello(chrome, version, name, label, upstream, ctx):
    """A real Chrome's first flight: a navigation to https://<name>:PORT_TAP/,
    the name mapped to 127.0.0.1 by --host-resolver-rules (no DNS, so no ECH
    config and Chrome sends its ECH GREASE, as for any site without an HTTPS
    record), HELLO_CAPTURES times, each a new browser with a fresh profile.
    The tap passes the bytes to `upstream`; the certificate Chrome then gets is
    not one it trusts, which does not touch the hello it already sent."""
    rundir = ctx["out"] / "runs" / hello_slug("chrome", label)
    rundir.mkdir(parents=True, exist_ok=True)
    row = base_row(ctx["platform"], CHROME_CORE, version, "client hello: " + label, "", "")
    url = "https://%s:%d/" % (name, PORT_TAP)
    flags = ["--headless", "--no-first-run", "--no-default-browser-check", "--disable-gpu",
             "--disable-background-networking", "--disable-component-update", "--disable-sync",
             "--host-resolver-rules=MAP %s 127.0.0.1" % name, "--dump-dom"]
    if sys.platform == "darwin":
        flags.append("--use-mock-keychain")
    else:
        flags.append("--no-sandbox")
    captures, later = [], []
    for i in range(HELLO_CAPTURES):
        profile = rundir / ("profile-%d" % (i + 1))
        tap = HelloTap(PORT_TAP, upstream)
        tap.start()
        log_path = rundir / ("chrome-%d.log" % (i + 1))
        proc = None
        try:
            proc = Proc([chrome] + flags + ["--user-data-dir=%s" % profile, url], log_path, cwd=rundir,
                        env=clean_env())
            deadline = time.time() + 40
            while time.time() < deadline and not (tap.flights and parse_client_hello(bytes(tap.flights[0])).get("ja4")):
                if not proc.alive() and not tap.flights:
                    break
                time.sleep(0.2)
            time.sleep(2.0)  # any second connection Chrome opens
        finally:
            if proc is not None:
                proc.stop(grace=4)
                try:
                    os.killpg(proc.p.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError, OSError):
                    pass
            tap.halt()
        flights = [bytes(f) for f in tap.flights]
        for j, f in enumerate(flights):
            (rundir / ("first-flight-%d-%d.bin" % (i + 1, j + 1))).write_bytes(f)
        if not flights or not flights[0]:
            raise LabError("Chrome sent nothing to the tap (capture %d):\n%s" % (i + 1, tail(log_path)))
        captures.append(parse_client_hello(flights[0]))
        for f in flights[1:]:
            h = parse_client_hello(f)
            # A second fresh connection of the same new browser is one more
            # full hello; one that resumes says so.
            later.append({"capture": i + 1, "ja4": h.get("ja4"), "error": h.get("error"),
                          "pre_shared_key": "pre_shared_key" in (h.get("ext_set") or []),
                          "ext_order": h.get("ext_order"), "client_hello_bytes": h.get("client_hello_bytes"),
                          "summary": hello_summary(h)})
    hello = captures[0]
    row["extra"].update(hello)
    row["extra"]["chrome"] = chrome
    row["extra"]["chrome_flags"] = flags
    row["extra"]["url"] = url
    row["extra"]["captures"] = [hello_summary(h) or {"error": h.get("error")} for h in captures]
    row["extra"]["variation"] = hello_variation([hello_summary(h) for h in captures])
    row["extra"]["later_connections"] = later
    errors = [h.get("error") for h in captures if h.get("error")]
    row["notes"] = "; ".join(errors) if errors else (
        "%s, the first connection of %d fresh headless browsers navigating to %s (the name mapped to 127.0.0.1 by "
        "--host-resolver-rules, the tap in front of port %d); %d further connection(s) from the same browsers in "
        "extra.later_connections; the flags change no TLS setting; no variations seed (a fresh profile with "
        "background networking off), so no server-side field trial either" % (
            version, len(captures), url, upstream, len(later)))
    return row


# ---------------------------------------------------------------- hello parity

HELLO_REALITY_LINES = ("vless-reality-vision", "vless-xhttp-reality packet-up")


def hello_target(r):
    """'reality' for a hello sent to a Reality server name, 'tls' for a plain
    TLS server (WebTunnel's), from the row's line."""
    line = str(r.get("scenario", "")).replace("client hello: ", "")
    if line.startswith("chrome"):
        return "reality" if "reality" in line else "tls"
    return "reality" if line in HELLO_REALITY_LINES else "tls"


def hello_parity(rows):
    """Chrome against every other client, per platform and target: each
    field's values (a list where captures differ), whether the extension
    order moves, and the fields where a client's values are not Chrome's."""
    out = {}
    for r in rows:
        if not is_hello(r) or not r.get("extra", {}).get("variation"):
            continue
        plat = out.setdefault(r["platform"], {})
        tgt = plat.setdefault(hello_target(r), {"clients": {}, "differences": {}})
        label = "%s | %s" % (r["core"], r["scenario"].replace("client hello: ", ""))
        tgt["clients"][label] = dict(r["extra"]["variation"], version=r.get("version"))
    for plat in out.values():
        for tgt in plat.values():
            chrome = [k for k in tgt["clients"] if k.startswith(CHROME_CORE)]
            if not chrome:
                continue
            ref = tgt["clients"][chrome[0]]
            for k, v in tgt["clients"].items():
                if k == chrome[0]:
                    continue
                diffs, drawn = {}, {}
                for f in HELLO_FIELDS:
                    a, b = ref.get(f), v.get(f)
                    if a != b:
                        (drawn if f in HELLO_DRAWN else diffs)[f] = {"chrome": a, "client": b}
                tgt["differences"][k] = diffs
                tgt.setdefault("drawn_differences", {})[k] = drawn
    return out


def _cell(v):
    if isinstance(v, dict) and set(v) == {"varies"}:
        return "varies: " + " / ".join(_cell(x) for x in v["varies"])
    if isinstance(v, list):
        return " ".join(str(x) for x in v) if v else "none"
    if isinstance(v, dict):
        return ", ".join("%s=%s" % (k, _cell(x)) for k, x in v.items())
    return "-" if v is None else str(v)


def parity_markdown(rows):
    par = hello_parity(rows)
    lines = []
    for plat, tgts in sorted(par.items()):
        for tgt, d in sorted(tgts.items()):
            clients = list(d["clients"])
            if not any(c.startswith(CHROME_CORE) for c in clients):
                continue
            clients.sort(key=lambda c: (not c.startswith(CHROME_CORE), c))
            lines += ["", "ClientHello parity, %s, %s (%d captures each, fresh process each; server name bytes taken "
                      "out of lengths; `varies` = the captures differ; **≠** a fixed field unlike Chrome's, ≈ a field "
                      "drawn per connection that came out differently):" % (
                          plat, "Reality server names" if tgt == "reality" else "a plain TLS server", HELLO_CAPTURES),
                      "", "| field | " + " | ".join(c.replace("|", "/") for c in clients) + " |",
                      "|---|" + "---|" * len(clients)]
            for f in HELLO_FIELDS:
                cells_ = []
                for c in clients:
                    v = d["clients"][c].get(f)
                    mark = ""
                    if not c.startswith(CHROME_CORE):
                        if f in d["differences"].get(c, {}):
                            mark = " **≠**"
                        elif f in d.get("drawn_differences", {}).get(c, {}):
                            mark = " ≈"
                    cells_.append(_cell(v).replace("|", "/") + mark)
                lines.append("| %s | %s |" % (f, " | ".join(cells_)))
    return lines


def parity_text(rows):
    par = hello_parity(rows)
    out = []
    for plat, tgts in sorted(par.items()):
        for tgt, d in sorted(tgts.items()):
            out.append("== ClientHello parity, %s, %s" % (plat, tgt))
            for c, v in d["clients"].items():
                out.append("  %s: captures %s, permutes %s, ja4 %s, version %s" % (
                    c, v.get("captures"), v.get("permutes"), _cell(v.get("ja4")), v.get("version")))
            for c, diffs in d["differences"].items():
                drawn = d.get("drawn_differences", {}).get(c, {})
                out.append("  -- %s against Chrome: %s%s" % (c, ", ".join(diffs) or "no fixed difference",
                                                             (" (drawn per connection, differ: %s)" % ", ".join(drawn))
                                                             if drawn else ""))
                for f, ab in list(diffs.items()) + list(drawn.items()):
                    out.append("     %s: chrome %s | client %s" % (f, _cell(ab["chrome"])[:400], _cell(ab["client"])[:400]))
    return "\n".join(out)


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
    row["extra"].update(getattr(err, "extra", None) or {})
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
    missing = []  # (spec, why): rows that fail by name without a run
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
            Spec(kind="harness", core="cores harness: sing-box", harness_core="singbox", mode=mode, settings=settings,
                 scenario=WEBTUNNEL, config=singbox_webtunnel(loop), needs_webtunnel=True,
                 notes=extra + ["socks shape derived from vless-reality.json (see singbox_webtunnel)", srv_note]),
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
                Spec(kind="exec", core="sing-box (exec)", argv=[singbox, "run", "-c", "{config}"], settings="go-defaults",
                     scenario=WEBTUNNEL, config=singbox_webtunnel(loop), version=sb_version, needs_webtunnel=True,
                     notes=["scripts/build-singbox.sh", srv_note]),
            ]
    if a.tun:
        specs.append(Spec(kind="harness-tun", core="cores harness: sing-box", harness_core="singbox", mode="ios",
                          settings="ios-limits", scenario="gVisor tun, vless-reality-vision", socks=None,
                          config=singbox_ios_tun_reality(loop, servers.pub),
                          notes=[ios_note, "ios-tun-reality.json with auto_route off; root via sudo; the kernel routes "
                                 "203.0.113.0/24 into the tun, the way NEPacketTunnelFlow hands packets to libbox",
                                 srv_note]))
        # The same line with hev owning the tun and sing-box on SOCKS behind
        # it: the shape the iPhone already uses for XHTTP and olcRTC, and the
        # one Android uses for every sing-box line.
        # The iPhone's code path: Apple's clang builds the pin with MSG_MORE
        # 0 (Darwin has none), so these rows run the pin built without it
        # (hev-build.sh) when it was built. The pin as Linux builds it corks
        # the SOCKS5 handshake's two writes for about 200 ms each: every new
        # flow paid about 0.42 s in run 43532557527's rows (lab.py udp-flow,
        # run 43538233589), the phone does not.
        hev_bin = b / "hev-socks5-tunnel-nomore"
        hev_which = ("the pin built without MSG_MORE on the two handshake writes (hev-socks5-tunnel-nomore, "
                     "hev-build.sh), as Apple's clang builds the iPhone's copy")
        if not hev_bin.exists():
            hev_bin = b / "hev-socks5-tunnel"
            hev_which = ("the pin as Linux builds it, MSG_MORE on the two handshake writes (no MSG_MORE-free build "
                         "in this run): every new flow waits about 0.42 s, which the iPhone does not")
        hev_spec = Spec(kind="harness-hev", core=HEV_CORE, harness_core="singbox", mode="ios",
                        settings="ios-limits", scenario="hev tun, vless-reality-vision", socks=None,
                        config=singbox_reality(loop, servers.pub),
                        notes=[ios_note, "hev-socks5-tunnel at scripts/hev-pins.sh's commit, the iPhone's pin (the "
                               "desktop app and Android build their own patched copy, androidApp/src/main/jni/"
                               "hev-socks5-tunnel, 2.14.4), built with the desktop's Linux make target (make clean "
                               "exec, -O3 as the iPhone's make static), run as root with HevTunnel.swift's configuration "
                               "(MTU 9000, task stack 24576, tcp buffer 4096, 768 sessions) plus a tun name and "
                               "address; sing-box on SOCKS with vless-reality.json behind it, the harness in -mode ios; "
                               "the kernel routes 203.0.113.0/24 into hev's tun; on a phone hev is a thread of the "
                               "extension, here a process of its own, so the two are sampled together and summed",
                               srv_note])
        hev_spec.notes.append("hev binary: " + hev_which)
        if hev_bin.exists():
            ctx["hev"] = hev_bin
            vfile = "hev-nomore.versions.txt" if hev_bin.name.endswith("-nomore") else "hev.versions.txt"
            try:
                ctx["hev_version"] = (b / vfile).read_text().splitlines()[0]
            except (OSError, IndexError):
                ctx["hev_version"] = "%s (version file missing)" % hev_bin.name
            specs.append(hev_spec)
        else:
            missing.append((hev_spec, "hev-socks5-tunnel was not built (hev-build.log in this artifact): %s missing; %s"
                            % (hev_bin, tail(out / "hev-build.log", 8).replace("\n", " | ") or "no build log")))

        # Phase 0's iOS shape, line by line: sing-box on its gVisor tun (the
        # committed ios-tun / bypass-tun shape, what the iPhone runs today),
        # then sing-box on SOCKS behind hev (Phase 0's own configs, copied
        # into shapes/). The same server, the same checks, the same load.
        ios_common = [ios_note, "the cores harness: the patched sing-box 1.13.14 (scripts/patched-singbox.sh) linked "
                      "as Cores.xcframework links it, run like `sing-box run` (box.New), not through libbox's command "
                      "server; root via sudo where it opens a tun"]
        ios_lines = (
            ("vless-reality-vision", "ios-socks-reality.json", "ios-tun-reality.json", "ios-tun-reality.json"),
            ("hysteria2-salamander", "ios-socks-hysteria2.json", "hysteria2",
             "ios-tun-reality.json with the Hysteria2 outbound of bypass-tun-hysteria2.json (no Global Hysteria2 tun "
             "reference is committed; it is buildTun's shape, and Phase 0's ios-socks-hysteria2.json is that with the "
             "SOCKS inbound)"),
            ("bypass russia, vless-reality-vision", "bypass-ios-socks-reality.json", "bypass-tun-reality.json",
             "bypass-tun-reality.json"),
        )
        for scenario, socks_name, tun_name, tun_said in ios_lines:
            def tun_config(rundir, tun_name=tun_name):
                c, _, cfg_notes = ios_tun_shape(tun_name, loop, servers.pub, rundir, TUN_IOS_GVISOR)
                return c, cfg_notes

            def socks_config(rundir, socks_name=socks_name):
                c, _, cfg_notes = ios_socks_shape(socks_name, loop, servers.pub, rundir)
                return c, cfg_notes

            routed = ("the kernel routes the fake-IP range 198.18.0.0/15 and the tun resolver %s/32 into the tun "
                      "(on a phone the default route goes there)" % IOS_TUN_DNS)
            specs.append(Spec(
                kind="harness-tun", core=IOS_SHAPE_TUN, harness_core="singbox", mode="ios", settings="ios-limits",
                scenario=scenario, socks=None, config=tun_config, tun_name=TUN_IOS_GVISOR, ios_shape=True,
                notes=ios_common + ["the baseline, today's iPhone shape: sing-box owns a gVisor tun (TunArrangement "
                                    "singBoxTun) with %s, auto_route off and a named interface, the lab server's "
                                    "address, keys and names" % tun_said, routed, srv_note]))
            ios_hev = Spec(
                kind="harness-hev", core=IOS_SHAPE_HEV, harness_core="singbox", mode="ios", settings="ios-limits",
                scenario=scenario, socks=None, config=socks_config, tun_name=TUN_IOS_HEV, ios_shape=True,
                notes=ios_common + [
                    "Phase 0's shape (TunArrangement singBoxBehindHev): shapes/%s, copied from the uncommitted branch "
                    "feat/ios-singbox-behind-hev (shapes/ios-socks.provenance.txt), sing-box on 127.0.0.1:%d with its "
                    "login and the lab server's address, keys and names; hev-socks5-tunnel at the iPhone's pin in front, "
                    "as root, with HevTunnel.configuration for the port and login TunArrangement.singBoxSocks reads "
                    "from that config, plus a tun name and the tun's address %s (LibboxPlatform.Tun.address); the two "
                    "processes are sampled together and summed" % (socks_name, PORT_SOCKS, IOS_TUN_ADDRESS),
                    routed, "hev binary: " + hev_which, srv_note])
            if hev_bin.exists():
                specs.append(ios_hev)
            else:
                missing.append((ios_hev, "hev-socks5-tunnel was not built (hev-build.log in this artifact)"))

    if not a.go_rows:
        # --no-go-rows: the Go rows, the tun, hev and iOS shape rows, the idle
        # harness and the olcRTC soaks stay out (their figures are known from
        # earlier runs); xray-rust's rows and the ClientHellos run as before.
        specs, missing = [], []

    # xray-rust. The main rows are the fork (--xray-rust): two runtime
    # profiles on the four lines, then two passes of the mobile profile, the
    # relay buffer rows and the one-worker rows, so each of those has two runs
    # side by side. The fork's XHTTP upload fix (--xray-rust-up) adds its
    # XHTTP mobile row to each pass and runs XHTTP's one-worker rows. v0.9.0
    # (--xray-rust-baseline) runs once per line in the mobile profile, as the
    # historical baseline. The ClientHello rows run the build
    # --xray-rust-hello names.
    main_bin, main_info, main_ref, _ = xrs_build(a.xray_rust)
    base_bin, base_info, base_ref, _ = xrs_build(a.xray_rust_baseline)
    hello_bin, hello_info, hello_ref, _ = xrs_build(a.xray_rust_hello)
    up_bin, up_info, up_ref, _ = xrs_build(a.xray_rust_up)
    main_core = "xray-rust %s (exec)" % main_ref
    base_core = "xray-rust %s (exec)" % base_ref
    hello_core = "xray-rust %s (exec)" % hello_ref
    up_core = "xray-rust %s (exec)" % up_ref
    xrs_specs = []
    hy2_pin = cert_sha256(servers.dir / "hy2.crt")
    try:
        wt_pin = cert_sha256(servers.dir / "webtunnel.crt")
    except (OSError, LabError):
        wt_pin = "0" * 64
    workers = max(2, min(6, os.cpu_count() or 2))
    mobile_env = {"XRAY_TUN_PROFILE": "mobile", "TOKIO_WORKER_THREADS": str(workers)}
    one_env = dict(mobile_env, TOKIO_WORKER_THREADS="1")
    cli_notes = ["xray-rust's CLI as it starts: 2 Tokio workers (crates/xray-cli/src/main.rs), runtime profile Default, "
                 "which on a desktop target is the Standard transport memory class (H2 stream windows a fixed 4 MiB)"]
    mobile_notes = ["the library's profile on a phone: XRAY_TUN_PROFILE=mobile (TunRuntimeProfile::Mobile, which the "
                    "Android VpnService adapter passes and which iOS's Default resolves to: the Constrained transport "
                    "memory class, XHTTP H2 stream windows opening at 1 MiB and growing for a reader that keeps up, "
                    "DNS 16 operations), and %d Tokio workers, as xray-ffi sizes its runtime here (available "
                    "parallelism clamped to 2..6; a 6-core phone gets 6)" % workers]
    one_notes = ["the mobile profile with TOKIO_WORKER_THREADS=1 instead of %d: Tokio's multi-thread runtime with one "
                 "worker (crates/xray-cli/src/main.rs leaves the variable to Tokio); xray-ffi, which the extension "
                 "links, sets its worker count itself (available parallelism clamped to 2..6) and reads no variable, "
                 "so one worker on a phone needs a change there" % workers]
    lines = (
        ("vless-reality-vision", xrs_reality(loop, servers.pub), False),
        ("hysteria2-salamander", xrs_hy2(loop, hy2_pin), False),
        ("vless-xhttp-reality packet-up", xrs_xhttp(loop, servers.pub), False),
        (WEBTUNNEL, xrs_webtunnel(loop, wt_pin), True),
    )
    by_line = {scenario: (cfg, cfg_notes, wt) for scenario, (cfg, cfg_notes), wt in lines}

    def xrs_spec(binary, info, core, scenario, settings, env, notes, config=None, extra=None, aux=None):
        cfg, cfg_notes, wt = by_line[scenario]
        return Spec(kind="exec", core=core, argv=[binary, "run", "-config", "{config}"], settings=settings, env=env,
                    scenario=scenario, config=config if config is not None else cfg, version=xrs_version_text(info),
                    needs_webtunnel=wt, xrs_check=binary, aux_checks=aux or {},
                    extra=dict({"xray_rust_build": info}, **(extra or {})),
                    notes=notes + cfg_notes + ["%s, %s" % (xrs_version_text(info), xrs_build_note(info)), srv_note])

    def want(binary, ref, log_name, spec):
        # A build that is not there fails its rows by name, with its log's tail.
        if binary.exists():
            xrs_specs.append(spec)
        else:
            missing.append((spec, "xray-rust %s was not built (%s in this artifact): %s" % (
                ref, log_name, tail(out / log_name, 8).replace("\n", " | "))))

    main_want = lambda spec: want(main_bin, main_ref, "xray-rust-main-build.log", spec)
    up_want = lambda spec: want(up_bin, up_ref, "xray-rust-up-build.log", spec)
    xhttp = "vless-xhttp-reality packet-up"
    # Order: the CLI's defaults, the baseline, then the two passes.
    if a.xray_rust:
        for scenario in by_line:
            aux = {}
            if scenario.startswith("vless-xhttp"):
                aux = {"the iPhone's answers-dns XHTTP shape": xray_xhttp(loop, servers.pub, answers_dns=True)}
            main_want(xrs_spec(main_bin, main_info, main_core, scenario, "cli defaults", None, cli_notes, aux=aux))
    if a.xray_rust_baseline:
        for scenario in by_line:
            want(base_bin, base_ref, "xray-rust-baseline-build.log",
                 xrs_spec(base_bin, base_info, base_core, scenario, "mobile profile", mobile_env,
                          mobile_notes + ["the historical baseline: the upstream release the lab measured before the "
                                          "fork"]))
    if a.xray_rust:
        for run in (1, 2):
            for scenario in by_line:
                main_want(xrs_spec(main_bin, main_info, main_core, scenario, "mobile #%d" % run, mobile_env,
                                   mobile_notes + ["run %d of 2; the relay buffer's default (128 KiB) and the "
                                                   "one-worker rows' partner" % run], extra={"run": run}))
            if a.xray_rust_up:
                # The XHTTP upload fix (--xray-rust-up): the same row on its
                # build, beside the main build's.
                up_want(xrs_spec(up_bin, up_info, up_core, xhttp, "mobile #%d" % run, mobile_env,
                                 mobile_notes + ["run %d of 2; the fork's XHTTP upload fix, the one-worker row's "
                                                 "partner" % run], extra={"run": run}))
            for scenario in RELAY_LINES:
                for kib in RELAY_KIB:
                    main_want(xrs_spec(
                        main_bin, main_info, main_core, scenario, "mob buf%dK #%d" % (kib, run), mobile_env,
                        mobile_notes + ["run %d of 2; policy.levels.\"0\".bufferSize %d (KiB): each relay read takes at "
                                        "most %d KiB instead of the default 128 KiB; in this core a read past the first "
                                        "4 KiB still lands in a 128 KiB buffer lent by a process-wide pool "
                                        "(xray-core-rs/src/policy/relay_buffer.rs)" % (run, kib, kib)],
                        config=with_relay_buffer(by_line[scenario][0], kib),
                        extra={"run": run, "relay_buffer_kib": kib}))
            for scenario in ONE_WORKER_LINES:
                # XHTTP's one-worker row runs the upload fix when it is given:
                # the build a phone would take.
                if scenario == xhttp and a.xray_rust_up:
                    up_want(xrs_spec(up_bin, up_info, up_core, scenario, "mob 1 worker #%d" % run, one_env,
                                     one_notes + ["run %d of 2" % run], extra={"run": run, "tokio_worker_threads": 1}))
                else:
                    main_want(xrs_spec(main_bin, main_info, main_core, scenario, "mob 1 worker #%d" % run, one_env,
                                       one_notes + ["run %d of 2" % run], extra={"run": run, "tokio_worker_threads": 1}))

    def run_specs(batch):
        for spec in batch:
            if not budget.allows(90):
                add(failed_row(a.platform, spec.core, spec.scenario, spec.settings, "skipped: out of time budget"))
                continue
            if spec.needs_webtunnel and servers.wt_error:
                add(failed_row(a.platform, spec.core, spec.scenario, spec.settings,
                               "the WebTunnel server: " + servers.wt_error))
                continue
            log("run:", spec.core, "|", spec.scenario, "|", spec.settings)
            try:
                add(run_desktop_spec(spec, ctx))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, spec.core, spec.scenario, spec.settings, e))

    # Order: the Go rows (and the tun rows), the idle harness and the olcRTC
    # soaks, then xray-rust, then the ClientHellos. A new core that stalls
    # spends the budget after every Go figure is in, never before.
    try:
        for spec, reason in missing:
            add(failed_row(a.platform, spec.core, spec.scenario, spec.settings, reason))
        run_specs(specs)
        for mode in ("ios", "plain") if a.go_rows else ():
            if not budget.allows(30):
                break
            try:
                add(run_idle_harness(mode, ctx))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, "cores harness (no core started)", "idle", mode, e))
        if a.go_rows and ctx["engine_src"] and (b / "olcrtc-e2e.test").exists():
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
        run_specs(xrs_specs)
        # The ClientHello each core sends on the three TCP lines, through a
        # relay on PORT_TAP in front of the server port. Hysteria2's hello is
        # inside a QUIC Initial under Salamander, which this relay cannot read.
        sb_argv, xr_argv = [singbox, "run", "-c", "{config}"], [xray_client, "run", "-c", "{config}"]
        hellos = []
        if a.xray_rust_hello and hello_bin.exists():
            xa = [hello_bin, "run", "-config", "{config}"]
            hv = xrs_version_text(hello_info)
            hellos += [(hello_core, hv, "vless-reality-vision", lambda p: xrs_reality(loop, servers.pub, p)[0], xa, PORT_REALITY),
                       (hello_core, hv, "vless-xhttp-reality packet-up", lambda p: xrs_xhttp(loop, servers.pub, p)[0], xa, PORT_XHTTP),
                       (hello_core, hv, WEBTUNNEL, lambda p: xrs_webtunnel(loop, wt_pin, p)[0], xa, PORT_WEBTUNNEL)]
        elif a.xray_rust_hello:
            for line in ("vless-reality-vision", "vless-xhttp-reality packet-up", WEBTUNNEL):
                add(failed_row(a.platform, hello_core, "client hello: " + line, "",
                               "xray-rust %s was not built (xray-rust-hello-build.log in this artifact): %s" % (
                                   hello_ref, tail(out / "xray-rust-hello-build.log", 8).replace("\n", " | "))))
        if a.go_rows:
            hellos += [("sing-box (exec)", sb_version, "vless-reality-vision", lambda p: singbox_reality(loop, servers.pub, p), sb_argv, PORT_REALITY),
                       ("xray (exec)", xr_version, "vless-xhttp-reality packet-up", lambda p: xray_xhttp(loop, servers.pub, False, p), xr_argv, PORT_XHTTP),
                       ("sing-box (exec)", sb_version, WEBTUNNEL, lambda p: singbox_webtunnel(loop, p), sb_argv, PORT_WEBTUNNEL)]
        for core, version, line, build, argv, upstream in hellos:
            if not budget.allows(30):
                add(failed_row(a.platform, core, "client hello: " + line, "", "skipped: out of time budget"))
                continue
            if upstream == PORT_WEBTUNNEL and servers.wt_error:
                add(failed_row(a.platform, core, "client hello: " + line, "", "the WebTunnel server: " + servers.wt_error))
                continue
            log("hello:", core, "|", line)
            try:
                row = run_hello(core, line, build(PORT_TAP), argv, None, upstream, ctx)
                row["version"] = version
                add(row)
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, core, "client hello: " + line, "", e))
        # Gate 2.2 (tasks.md 6.2): a real current Google Chrome's first flight
        # through the same tap, to a Reality server name and to a plain TLS
        # server, beside the cores' hellos above.
        chrome = find_chrome()
        chrome_targets = (("chrome, Reality server name %s" % SNI_REALITY, SNI_REALITY, PORT_REALITY),
                          ("chrome, plain TLS server %s" % SNI_WEBTUNNEL, SNI_WEBTUNNEL, PORT_WEBTUNNEL))
        chrome_version = first_line([chrome, "--version"]) if chrome else ""
        for label, name, upstream in chrome_targets:
            if chrome is None:
                add(failed_row(a.platform, CHROME_CORE, "client hello: " + label, "",
                               "no Google Chrome on this runner ($CHROME_BIN, google-chrome, the macOS app)"))
                continue
            if not budget.allows(60):
                add(failed_row(a.platform, CHROME_CORE, "client hello: " + label, "", "skipped: out of time budget"))
                continue
            if upstream == PORT_WEBTUNNEL and servers.wt_error:
                add(failed_row(a.platform, CHROME_CORE, "client hello: " + label, "",
                               "the WebTunnel server: " + servers.wt_error))
                continue
            log("hello:", chrome_version, "|", label)
            try:
                add(run_chrome_hello(chrome, chrome_version, name, label, upstream, ctx))
            except Exception as e:
                traceback.print_exc()
                add(failed_row(a.platform, CHROME_CORE, "client hello: " + label, "", e))
        hello_rows = [r for r in results if is_hello(r)]
        write_json(out / "hello-parity.json", {"chrome_version": chrome_version, "chrome": chrome,
                                               "captures_per_client": HELLO_CAPTURES,
                                               "parity": hello_parity(hello_rows)})
        print(parity_text(hello_rows), flush=True)
    finally:
        servers.stop()
    for name, path, what in (
            ("sing-box (exec)", singbox, "build-singbox.sh, CGO 0, release tags, -s -w"),
            ("xray (exec)", xray_client, "official Xray-core 26.7.11 release"),
            ("cores harness", b / "harness", "sing-box + libXray + olcRTC mobile in one binary, the Cores' tags, -s -w; "
                                             "a stand-in for one slice of Cores.xcframework, not its size"),
            ("olcrtc (desktop)", b / "olcrtc", "desktopApp registerOlcRtcBuildTask recipe")) if a.go_rows else ():
        add(size_row(a.platform, name, path, what))
    for core, binary, info in ((main_core, main_bin, main_info), (base_core, base_bin, base_info),
                               (hello_core, hello_bin, hello_info), (up_core, up_bin, up_info)):
        if binary is not None:
            add(size_row(a.platform, core.replace(" (exec)", ""), binary,
                         "the xray-rust CLI (xray-cli), cargo --release with the workspace's [profile.release]: %s; an "
                         "app links the xray-ffi static library instead, whose size this is not" % xrs_version_text(info)))
    if a.tun and a.go_rows:
        add(size_row(a.platform, "hev-socks5-tunnel", b / "hev-socks5-tunnel",
                     "make clean exec at scripts/hev-pins.sh's commit (-O3, stripped); the iPhone links the same "
                     "tree as a static library"))
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

    def loadgen_run(self, op, nbytes, socks, timeout_s, flows=None, target=THROUGH):
        argv = [DEV_DIR + "/loadgen"] + transfer_argv(op, nbytes, socks, timeout_s, flows, target)
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
    bdown, bup, bwin = run_burst(host, socks, 240)
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
        notes += burst_figures(row, bdown, bup, merged, bwin, mem_key="pss")
        row["extra"].update({
            "app_pss_burst_peak_mb": mb(max_of(app_rows, "pss", *bwin)),
            "core_pss_burst_peak_mb": mb(max_of(core_rows, "pss", *bwin)),
        })
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
        notes += burst_figures(row, bdown, bup, core_rows, bwin)
        row["extra"].update({
            "pss_burst_peak_mb": mb(max_of(core_rows, "pss", *bwin)),
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
            ("sing-box (exec)", "libsingboxcore.so", WEBTUNNEL, singbox_webtunnel(server), sb_version),
        ]
        for core, lib, scenario, config, version in execs:
            if not budget.allows(120):
                add(failed_row(platform, core, scenario, "go-defaults", "skipped: out of time budget"))
                continue
            if scenario == WEBTUNNEL and servers.wt_error:
                add(failed_row(platform, core, scenario, "go-defaults", "the WebTunnel server: " + servers.wt_error))
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
                    ("xhttp", "libxraycore", "vless-xhttp-reality packet-up"),
                    ("webtunnel", "libsingboxcore", WEBTUNNEL)]
            for key, core_lib, scenario in plan:
                if not budget.allows(150):
                    add(failed_row(platform, "Ghostlane app + " + core_lib, scenario, "app", "skipped: out of time budget"))
                    continue
                if key == "webtunnel" and servers.wt_error:
                    add(failed_row(platform, "Ghostlane app + " + core_lib, scenario, "app",
                                   "the WebTunnel server: " + servers.wt_error))
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


COLUMNS = [("platform", 22), ("core", 32), ("scenario", 30), ("settings", 14), ("idle", 6), ("steady", 6),
           ("peak", 6), ("go s-r", 6), ("down", 7), ("up", 7), ("cpu/GB", 6), ("burst", 6), ("b-gor", 6),
           ("fp-pk", 6)]


def is_hello(r):
    return str(r.get("scenario", "")).startswith("client hello")


def footprint_cell(r):
    """macOS phys_footprint, idle / steady / peak / burst peak: what iOS holds
    against the extension's limit. Empty elsewhere."""
    e = r.get("extra", {})
    keys = ("idle_phys_footprint_mb", "steady_phys_footprint_mb", "peak_phys_footprint_mb",
            "burst_peak_phys_footprint_mb")
    if not any(e.get(k) is not None for k in keys):
        return ""
    return " / ".join("-" if e.get(k) is None else str(e.get(k)) for k in keys)


def cells(r):
    def f(v):
        return "-" if v is None else str(v)
    if r.get("scenario") == "binary size":
        return [r["platform"], r["core"], "size %s MB" % f(r.get("extra", {}).get("mb"))] + [""] * 11
    if is_hello(r):
        return [r["platform"], r["core"], r["scenario"], f(r.get("extra", {}).get("ja4"))] + [""] * 10
    return [r.get("platform", ""), r.get("core", ""), r.get("scenario", ""), r.get("settings", ""),
            f(r.get("idle_rss_mb")), f(r.get("steady_rss_mb")), f(r.get("peak_rss_mb")),
            f(r.get("go_sys_minus_released_mb")), f(r.get("mbps_down")), f(r.get("mbps_up")),
            f(r.get("cpu_s_per_gb")), f(r.get("extra", {}).get("burst_peak_mb")),
            f(r.get("extra", {}).get("burst_go_goroutines_peak")),
            f(r.get("extra", {}).get("peak_phys_footprint_mb"))]


def print_table(rows, header=True):
    if header:
        print("memory in MiB (idle / steady under load / peak; go s-r = Go sys minus released, peak), "
              "throughput in Mbit/s, CPU in s per GB moved; burst = peak memory while %d transfers ran at once, "
              "b-gor = the harness's goroutines then; fp-pk = macOS lifetime peak phys_footprint" % BURST_FLOWS)
        print("  ".join(name.ljust(w) for name, w in COLUMNS))
    for r in rows:
        c = cells(r)
        print("  ".join(str(v)[:w].ljust(w) for v, (_, w) in zip(c, COLUMNS)), flush=True)
        if r.get("notes", "").startswith("FAILED") or r.get("notes", "").startswith("skipped"):
            print("    " + r["notes"][:600], flush=True)


def markdown(rows):
    head = ["platform", "core", "scenario", "settings", "metric", "idle MiB", "steady MiB", "peak MiB",
            "Go sys-rel MiB", "down Mbit/s", "up Mbit/s", "CPU s/GB", "burst peak MiB", "burst goroutines",
            "macOS phys_footprint idle / steady / peak / burst MiB"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in rows:
        if r.get("scenario") == "binary size" or is_hello(r):
            continue
        c = cells(r)
        lines.append("| " + " | ".join([c[0], c[1], c[2], c[3], r.get("memory_metric", "")] + c[4:13] +
                                       [footprint_cell(r)]).replace("\n", " ") + " |")
    sizes = [r for r in rows if r.get("scenario") == "binary size"]
    if sizes:
        lines += ["", "| platform | binary | MB | what |", "|---|---|---|---|"]
        for r in sizes:
            lines.append("| %s | %s | %s | %s |" % (r["platform"], r["core"], r.get("extra", {}).get("mb"), r.get("notes", "")))
    hellos = [r for r in rows if is_hello(r) and r.get("extra", {}).get("ja4")]
    if hellos:
        lines += ["", "ClientHello as the server's port saw it (first connection; JA4 sorts away Chrome's "
                      "extension shuffle):", "",
                  "| platform | core | line | JA4 | key shares | ALPN | hello bytes | ECH GREASE | extensions |",
                  "|---|---|---|---|---|---|---|---|---|"]
        for r in hellos:
            e = r["extra"]
            lines.append("| %s | %s | %s | `%s` | %s | %s | %s | %s | %d |" % (
                r["platform"], r["core"], r["scenario"].replace("client hello: ", ""), e.get("ja4"),
                ", ".join(e.get("key_shares") or []), ", ".join(e.get("alpn") or []) or "none",
                e.get("client_hello_bytes"), "yes" if e.get("ech_bytes") else "no", len(e.get("extensions") or [])))
        lines += parity_markdown(rows)
    xh = [r for r in rows if r.get("scenario") == "vless-xhttp-reality packet-up"
          and r.get("extra", {}).get("peak_phys_footprint_mb") is not None]
    if xh:
        lines += ["", "XHTTP packet-up, macOS phys_footprint (what iOS counts) and the burst's two halves "
                      "(32 downloads at once, then 32 uploads at once; the halves are sampled every %.1f s, the peak "
                      "is the lifetime maximum):" % PERIOD_S, "",
                  "| platform | core | settings | peak fp MiB | burst down half fp | burst up half fp | down Mbit/s | "
                  "up Mbit/s | CPU s/GB | burst down / up Mbit/s | burst CPU s/GB |", "|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in xh:
            e = r["extra"]
            lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s / %s | %s |" % (
                r["platform"], r["core"], r["settings"], e.get("peak_phys_footprint_mb"),
                e.get("burst_down_half_peak_phys_footprint_mb"), e.get("burst_up_half_peak_phys_footprint_mb"),
                r.get("mbps_down"), r.get("mbps_up"), r.get("cpu_s_per_gb"), e.get("burst_mbps_down"),
                e.get("burst_mbps_up"), e.get("burst_cpu_s_per_gb")))
    checked = [r for r in rows if r.get("extra", {}).get("ios_checks")]
    if checked:
        lines += ["", "iOS shape checks, before any load: (1) an A query to the tun's resolver %s, (2) HTTP to that "
                      "name at the address it got, (3) more UDP exchanges, each on a socket of its own:" % IOS_TUN_DNS,
                  "", "| platform | core | scenario | (1) DNS through the tun | (2) HTTP to the name | (3) UDP |",
                  "|---|---|---|---|---|---|"]
        for r in checked:
            c = r["extra"]["ios_checks"]
            lines.append("| %s | %s | %s | %s |" % (r["platform"], r["core"], r["scenario"], " | ".join(
                "%s: %s" % ("ok" if c.get(k, {}).get("ok") else "FAILED", c.get(k, {}).get("detail", "-"))
                for k in ("dns", "http", "udp"))))
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
    text = parity_text([r for r in rows if is_hello(r)])
    if text:
        print(text)
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
    # Phase 0's iOS SOCKS copies: their builder is not on this branch, so each
    # is held, apart from its inbound, to the builder's fresh tun dump of the
    # same line (Phase 0's SingBoxConfigTest holds the two equal there).
    for name, (twin_name, out_name) in IOS_SOCKS.items():
        twin = build / "singbox-configs" / twin_name
        source = build / "singbox-configs" / out_name if out_name else None
        absent = [p for p in (twin, source) if p is not None and not p.exists()]
        if absent:
            print("MISSING  %s (did the dump test run?)" % ", ".join(rel(p) for p in absent))
            bad += 1
            continue
        try:
            drift = ios_socks_drift(ios_socks_copy(name), load_json(twin), load_json(source) if source else None)
        except (LabError, OSError, ValueError) as e:
            drift = [str(e)]
        if drift:
            bad += 1
            print("DIFFERS  %s vs %s, apart from the inbound: %s" % (rel(SHAPES / name), rel(twin), "; ".join(drift)))
        else:
            print("same     %s == %s, apart from the inbound%s" % (
                rel(SHAPES / name), rel(twin), (" (outbound: %s's first)" % rel(source)) if source else ""))
    return 1 if bad else 0


# ---------------------------------------------------------------- udp-flow: where a new UDP flow's time goes
#
# The ios-shape hev rows took about 0.42 s for one DNS query to the tun's
# resolver where the gVisor rows took 0.012 s, and every new UDP socket cost
# about that again. `lab.py udp-flow` (Linux, root via sudo) takes the shape
# apart: each variant changes one thing, then sends FLOW_N A queries to the
# resolver, each on a socket of its own (a new UDP flow: behind hev, a new
# SOCKS5 association), and opens FLOW_N fresh TCP connections, each with a
# small GET. It starts its own servers and its own tuns under its own output
# directory, and the workflow runs it after Measure, so no row moves.

FLOW_N = 20
# On macOS (the runner nearest the iPhone: Darwin's TCP and Apple's clang) a
# tun is a utun with a number; it goes, routes and all, with its process.
FLOW_DARWIN = sys.platform == "darwin"
FLOW_TUN_HEV = "utun77" if FLOW_DARWIN else "pklab4"
FLOW_TUN_GVISOR = "utun78" if FLOW_DARWIN else "pklab5"
FLOW_PORT_PYSOCKS = 10830
FLOW_LOGIN = ("flow-user", "flow-pass")
FLOW_GET_BYTES = 1024
# The python SOCKS server sends every CONNECT to the page, so any address in
# the routed fake range will do for its TCP series.
FLOW_PY_TCP_TARGET = "198.18.0.10"
# hev-socks5-core f5c97b0 (in the pin) sends the method offer and the login
# with MSG_MORE in the standard handshake too; upstream b43fa85 (2026-11-06)
# keeps MSG_MORE for the pipelined one only. Darwin has no MSG_MORE, so the
# iPhone's build takes the file's fallback, 0.
FLOW_HEV_MORE = "hev-socks5-tunnel"
FLOW_HEV_NOMORE = "hev-socks5-tunnel-nomore"


def pctl(values, q):
    """Nearest-rank percentile: the p90 of 20 is the 18th smallest."""
    s = sorted(values)
    if not s:
        return None
    return s[max(0, math.ceil(q * len(s) - 1e-9) - 1)]


def flow_summary(values):
    return {"n": len(values), "median_s": rnd(statistics.median(values), 4) if values else None,
            "p90_s": rnd(pctl(values, 0.9), 4), "min_s": rnd(min(values), 4) if values else None,
            "max_s": rnd(max(values), 4) if values else None}


def recv_exact(s, n):
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk:
            raise OSError("closed after %d of %d bytes" % (len(data), n))
        data += chunk
    return data


def http_read_page(s, nbytes):
    """Reads one response to a GET /bytes?n=nbytes and checks it is the
    page's: status 200 and the page's own bytes. Returns when the body is in,
    without waiting for the close."""
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = s.recv(65536)
        if not chunk:
            raise OSError("closed in the headers: %r" % data[:80])
        data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    m = re.search(rb"(?im)^content-length:\s*(\d+)", head)
    if not head.startswith(b"HTTP/1.1 200") or not m or int(m.group(1)) != nbytes:
        raise OSError("not the page: %r" % head[:120])
    while len(body) < nbytes:
        chunk = s.recv(65536)
        if not chunk:
            raise OSError("closed after %d of %d body bytes" % (len(body), nbytes))
        body += chunk
    if body[:nbytes] != page_pattern(nbytes):
        raise OSError("%d bytes, but not the page's" % len(body))


class Prefixed:
    """A socket whose first recv returns bytes already read."""

    def __init__(self, sock, head):
        self.sock, self.head = sock, head

    def recv(self, n):
        if self.head:
            out, self.head = self.head, b""
            return out
        return self.sock.recv(n)


def flow_http_once(addr, port=80, timeout=10.0):
    """One fresh TCP connection and a GET of FLOW_GET_BYTES of the page:
    (connect s, first byte s), both counted from the start of the connect."""
    t0 = time.perf_counter()
    s = socket.create_connection((addr, port), timeout=timeout)
    try:
        t1 = time.perf_counter()
        s.sendall(("GET /bytes?n=%d HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n"
                   % (FLOW_GET_BYTES, IOS_CHECK_NAME)).encode())
        first = s.recv(1)
        t2 = time.perf_counter()
        if not first:
            raise OSError("closed before the first byte")

        http_read_page(Prefixed(s, first), FLOW_GET_BYTES)
    finally:
        s.close()
    return t1 - t0, t2 - t0


def flow_series(n, once):
    """`once(i)` n times; stops after 3 failures in a row."""
    ok, fails, streak = [], [], 0
    for i in range(n):
        try:
            ok.append(once(i))
            streak = 0
        except (OSError, LabError, struct.error, IndexError, ValueError) as e:
            fails.append("#%d %s: %s" % (i, type(e).__name__, e))
            streak += 1
            if streak >= 3:
                fails.append("stopped after 3 failures in a row")
                break
    return ok, fails


def flow_dns_series(tag, n=FLOW_N):
    """n A queries to the tun's resolver, each for a new name on a socket of
    its own: a new UDP flow each."""
    def once(i):
        t0 = time.perf_counter()
        a = dns_query_a(IOS_TUN_DNS, "%s-%d.flow.lab.test" % (tag, i), timeout=3.0)
        rtt = time.perf_counter() - t0
        if not a["a"]:
            raise OSError("no A record (rcode %d)" % a["rcode"])
        return rtt, a["a"][0]
    ok, fails = flow_series(n, once)
    out = {"queries": n, "answered": len(ok), "distinct_addresses": len({x[1] for x in ok}),
           "rtt_s": [round(x[0], 4) for x in ok], "failures": fails[:5]}
    out.update(flow_summary([x[0] for x in ok]))
    return out


def flow_tcp_series(addr, n=FLOW_N):
    ok, fails = flow_series(n, lambda i: flow_http_once(addr))
    return {"connections": n, "ok": len(ok), "target": addr,
            "connect": flow_summary([x[0] for x in ok]), "first_byte": flow_summary([x[1] for x in ok]),
            "connect_s": [round(x[0], 4) for x in ok], "first_byte_s": [round(x[1], 4) for x in ok],
            "failures": fails[:5]}


def flow_measure(tag, tcp_target=None):
    """Warm up (one answered query for the page's name, then one GET), then
    the DNS series and the TCP series through the tun. The TCP series goes to
    the address the warm-up query got (sing-box's fake address for the page),
    or to `tcp_target`."""
    deadline = time.time() + 20
    addr, err, tries = None, "not tried", 0
    while time.time() < deadline:
        tries += 1
        try:
            ans = dns_query_a(IOS_TUN_DNS, IOS_CHECK_NAME, timeout=3.0)
            if ans["a"]:
                addr = ans["a"][0]
                break
            err = "no address: %s" % ans
        except OSError as e:
            err = "%s: %s" % (type(e).__name__, e)
        time.sleep(0.3)
    if addr is None:
        raise LabError("no DNS answer through the tun in 20 s (%d tries): %s" % (tries, err))
    target = tcp_target or addr
    deadline, warm_tcp, err = time.time() + 20, None, "not tried"
    while time.time() < deadline:
        try:
            warm_tcp = flow_http_once(target)
            break
        except OSError as e:
            err = "%s: %s" % (type(e).__name__, e)
            time.sleep(0.5)
    if warm_tcp is None:
        raise LabError("no page through the tun at %s in 20 s: %s" % (target, err))
    return {"warmup": {"dns_tries": tries, "page_address": addr, "tcp_first_byte_s": rnd(warm_tcp[1], 4)},
            "dns": flow_dns_series(tag), "tcp": flow_tcp_series(target)}


def flow_routes(tun):
    for net in ("198.18.0.0/15", IOS_TUN_DNS + "/32"):
        if FLOW_DARWIN:
            kind = ["-host", net[:-3]] if net.endswith("/32") else ["-net", net]
            subprocess.run(["sudo", "-n", "route", "-n", "delete"] + kind, capture_output=True, check=False)
            r = subprocess.run(["sudo", "-n", "route", "-n", "add"] + kind + ["-interface", tun],
                               capture_output=True, text=True)
        else:
            r = subprocess.run(["sudo", "-n", "ip", "route", "replace", net, "dev", tun], capture_output=True, text=True)
        if r.returncode != 0:
            raise LabError("route %s into %s: %s" % (net, tun, (r.stderr or r.stdout).strip()))
    if FLOW_DARWIN:
        r = subprocess.run(["route", "-n", "get", IOS_TUN_DNS], capture_output=True, text=True)
        if "interface: %s" % tun not in r.stdout:
            raise LabError("route get %s does not name %s: %s" % (IOS_TUN_DNS, tun, r.stdout.strip()))


def flow_drop_tun(tun):
    if FLOW_DARWIN:
        return  # a utun and its routes go with the process that opened it
    if Path("/sys/class/net/%s" % tun).exists():
        subprocess.run(["sudo", "-n", "ip", "link", "delete", tun], capture_output=True, check=False)


def flow_link_up(tun):
    if not FLOW_DARWIN:
        return link_up(tun)
    r = subprocess.run(["ifconfig", tun], capture_output=True, text=True)
    return r.returncode == 0 and "<UP" in r.stdout


def flow_wait_link(tun, front, rundir, log_name):
    deadline = time.time() + 10
    while not flow_link_up(tun) and time.time() < deadline and front.alive():
        time.sleep(0.1)
    if not flow_link_up(tun):
        raise LabError("%s is not up:\n%s" % (tun, tail(rundir / log_name)))


def flow_harness(ctx, rundir, config, sudo=False):
    """The cores harness (-mode ios, as the iOS shape rows run it) on
    `config`, once it says ready."""
    cfg = rundir / "config.json"
    write_json(cfg, config)
    if not sudo and not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken by the previous variant" % PORT_SOCKS)
    proc = Proc([ctx["bin"] / "harness", "-core", "singbox", "-mode", "ios", "-stats", rundir / "stats.jsonl",
                 "-ready", rundir / "ready.json", "-config", cfg], rundir / "core.log", cwd=rundir,
                env=clean_env(), sudo=sudo)
    ready = wait_file(rundir / "ready.json", 40, proc)
    if ready is None:
        proc.stop()
        raise LabError("the harness never said ready:\n" + tail(rundir / "core.log"))
    proc.pid = int(ready["pid"])
    return proc


def flow_hev(ctx, rundir, exe_name, yaml_text):
    """hev as root, with its own tun up and the fake range and the resolver
    routed into it."""
    exe = ctx["bin"] / exe_name
    if not exe.exists():
        same = ctx["bin"] / "hev-nomore.identical.txt"
        if exe_name == FLOW_HEV_NOMORE and same.exists():
            raise LabError("not run: " + same.read_text().strip())
        raise LabError("%s was not built (hev-build.log)" % exe)
    cfg = rundir / "hev.yml"
    cfg.write_text(yaml_text)
    front = Proc([exe, cfg], rundir / "hev.log", cwd=rundir, env=clean_env(), sudo=True)
    real = find_pid_by_argv(exe.name, str(cfg))
    if real is None:
        front.stop()
        raise LabError("hev did not start:\n" + tail(rundir / "hev.log"))
    front.pid = real
    try:
        flow_wait_link(FLOW_TUN_HEV, front, rundir, "hev.log")
        flow_routes(FLOW_TUN_HEV)
    except Exception:
        front.stop()
        raise
    return front


def hev_tweak(yaml_text, old, new):
    expect(yaml_text.count(old) == 1, "hev yaml holds %r once" % old)
    return yaml_text.replace(old, new)


def flow_ios_socks(ctx, rundir):
    """Phase 0's ios-socks-reality.json, as the ios-shape hev row runs it."""
    c, _, notes = ios_socks_shape("ios-socks-reality.json", "127.0.0.1", ctx["pub"], rundir)
    return c, notes


def run_flow_singbox_hev(ctx, rundir, v):
    """sing-box behind hev: V1, V2, V3, V8, V9."""
    config, notes = flow_ios_socks(ctx, rundir)
    if v.get("no_sniff"):
        rules = config["route"]["rules"]
        kept = [r for r in rules if r != {"action": "sniff"}]
        expect(len(kept) == len(rules) - 1, "exactly one {action: sniff} rule in the route")
        config["route"]["rules"] = kept
        notes.append("route rule {\"action\":\"sniff\"} removed; rules now %s" % json.dumps(kept))
    if v.get("no_login"):
        expect(config["inbounds"][0].pop("users", None), "the socks inbound had users")
        notes.append("the socks inbound's users removed, and hev's username and password lines with them")
    arrangement = ios_hev_socks(config)
    expect(arrangement is not None, "TunArrangement finds the SOCKS inbound")
    port, login = arrangement
    expect((login is None) == bool(v.get("no_login")), "the login follows the variant")
    yaml_text = hev_config(port, login=login, name=FLOW_TUN_HEV, ipv4=IOS_TUN_ADDRESS)
    if v.get("pipeline"):
        yaml_text = hev_tweak(yaml_text, "  udp: 'udp'\n", "  udp: 'udp'\n  pipeline: true\n")
        notes.append("hev's socks5 section adds `pipeline: true`")
    proc = flow_harness(ctx, rundir, config)
    front = None
    try:
        if not wait_port(port, 20, proc):
            raise LabError("the harness's SOCKS port never opened:\n" + tail(rundir / "core.log"))
        front = flow_hev(ctx, rundir, v.get("hev", FLOW_HEV_MORE), yaml_text)
        res = flow_measure(v["id"].lower())
    finally:
        if front is not None:
            front.stop()
        proc.stop()
        flow_drop_tun(FLOW_TUN_HEV)
    res["notes"] = notes
    return res


def run_flow_pysocks_hev(ctx, rundir, v):
    """hev in front of the python SOCKS5 server (`lab.py flow-socks`), no
    sing-box: V5, V6."""
    server = Proc([sys.executable, Path(__file__).resolve(), "flow-socks", "--port", str(FLOW_PORT_PYSOCKS),
                   "--login", "%s:%s" % FLOW_LOGIN, "--page-port", str(PORT_PAGE)],
                  rundir / "pysocks.log", cwd=rundir, env=clean_env())
    front = None
    try:
        if not wait_port(FLOW_PORT_PYSOCKS, 15, server):
            raise LabError("the python SOCKS server did not listen:\n" + tail(rundir / "pysocks.log"))
        yaml_text = hev_config(FLOW_PORT_PYSOCKS, login=FLOW_LOGIN, name=FLOW_TUN_HEV, ipv4=IOS_TUN_ADDRESS)
        notes = ["hev at the pin with HevTunnel.configuration and a login, in front of `lab.py flow-socks`: a "
                 "threaded python SOCKS5 server (TCP_NODELAY on its side, as Go sets it) that checks the login, "
                 "sends every CONNECT to the page and answers every datagram to port 53 itself with an A record"]
        if v.get("udp_mode") == "tcp":
            yaml_text = hev_tweak(yaml_text, "  udp: 'udp'\n", "  udp: 'tcp'\n")
            notes.append("hev `udp: 'tcp'`: UDP in TCP (hev's FWD_UDP command, 0x05, framed datagrams on the "
                         "control connection), which the python server speaks and sing-box does not")
        front = flow_hev(ctx, rundir, FLOW_HEV_MORE, yaml_text)
        res = flow_measure(v["id"].lower(), tcp_target=FLOW_PY_TCP_TARGET)
    finally:
        if front is not None:
            front.stop()
        server.stop()
        flow_drop_tun(FLOW_TUN_HEV)
    res["notes"] = notes
    return res


def run_flow_gvisor(ctx, rundir, v):
    """V7: sing-box on its gVisor tun, ios-tun-reality.json, as the ios-shape
    gVisor row runs it."""
    config, _, notes = ios_tun_shape("ios-tun-reality.json", "127.0.0.1", ctx["pub"], rundir, FLOW_TUN_GVISOR)
    proc = flow_harness(ctx, rundir, config, sudo=True)
    try:
        flow_wait_link(FLOW_TUN_GVISOR, proc, rundir, "core.log")
        flow_routes(FLOW_TUN_GVISOR)
        res = flow_measure("v7")
    finally:
        proc.stop()
        flow_drop_tun(FLOW_TUN_GVISOR)
    res["notes"] = notes
    return res


def socks5_open(port, login, mode, cmd, dst_ip, dst_port, timeout=5.0):
    """A SOCKS5 client handshake on loopback, one step at a time: the method
    offer, the login, the request. `mode`: 'plain' (no socket option, no
    flag), 'nodelay' (TCP_NODELAY), 'msg_more' (MSG_MORE on the method offer
    and the login, the flags hev at the pin sends them with). Returns the
    socket, the reply's address and the times since the start of connect()."""
    t0 = time.perf_counter()
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    try:
        times = {"tcp_connect_s": time.perf_counter() - t0}
        if mode == "nodelay":
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        more = socket.MSG_MORE if mode == "msg_more" else 0
        method = 2 if login else 0
        if s.send(bytes([5, 1, method]), more) != 3:
            raise OSError("short write of the method offer")
        rep = recv_exact(s, 2)
        times["method_reply_s"] = time.perf_counter() - t0
        if rep != bytes([5, method]):
            raise OSError("method reply %r" % rep)
        if login:
            u, p = login[0].encode(), login[1].encode()
            creds = bytes([1, len(u)]) + u + bytes([len(p)]) + p
            if s.send(creds, more) != len(creds):
                raise OSError("short write of the login")
            rep = recv_exact(s, 2)
            times["auth_reply_s"] = time.perf_counter() - t0
            if rep != b"\x01\x00":
                raise OSError("login reply %r" % rep)
        s.sendall(bytes([5, cmd, 0, 1]) + socket.inet_aton(dst_ip) + struct.pack(">H", dst_port))
        head = recv_exact(s, 4)
        if head[1] != 0:
            raise OSError("request reply %r" % head)
        if head[3] == 1:
            raw = recv_exact(s, 6)
            bnd = (socket.inet_ntoa(raw[:4]), struct.unpack(">H", raw[4:])[0])
        elif head[3] == 4:
            raw = recv_exact(s, 18)
            bnd = (socket.inet_ntop(socket.AF_INET6, raw[:16]), struct.unpack(">H", raw[16:])[0])
        else:
            raise OSError("request reply address type %d" % head[3])
        times["request_reply_s"] = time.perf_counter() - t0
        return s, bnd, times, t0
    except BaseException:
        s.close()
        raise


def socks5_udp_header(ip, port):
    return b"\0\0\0\x01" + socket.inet_aton(ip) + struct.pack(">H", port)


def socks5_udp_payload(data):
    atype = data[3]
    off = 4 + {1: 4, 4: 16}.get(atype, 1 + (data[4] if len(data) > 4 else 0)) + 2
    return data[off:]


def flow_socks_udp_once(port, login, mode, name):
    """V4, UDP: a fresh association to sing-box's socks inbound, one DNS query
    to the tun's resolver through it, as hev would send it."""
    s, bnd, times, t0 = socks5_open(port, login, mode, 3, "0.0.0.0", 0)
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        u.settimeout(3.0)
        if bnd[0] in ("0.0.0.0", "::"):
            bnd = ("127.0.0.1", bnd[1])
        qid, query = dns_a_query(name)
        t1 = time.perf_counter()
        u.sendto(socks5_udp_header(IOS_TUN_DNS, 53) + query, bnd)
        while True:
            data, _ = u.recvfrom(4096)
            payload = socks5_udp_payload(data)
            if len(payload) >= 12 and struct.unpack(">H", payload[:2])[0] == qid:
                break
        t2 = time.perf_counter()
        rcode, addrs = dns_a_answer(payload)
        if not addrs:
            raise OSError("no A record (rcode %d)" % rcode)
        times["datagram_to_answer_s"] = t2 - t1
        times["total_s"] = t2 - t0
        return times, addrs[0]
    finally:
        u.close()
        s.close()


def flow_socks_tcp_once(port, login, mode, addr):
    """V4, TCP: a fresh CONNECT to the page's fake address through sing-box's
    socks inbound, then the GET."""
    s, _, times, t0 = socks5_open(port, login, mode, 1, addr, 80)
    try:
        s.sendall(("GET /bytes?n=%d HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n"
                   % (FLOW_GET_BYTES, IOS_CHECK_NAME)).encode())
        first = s.recv(1)
        times["first_byte_s"] = time.perf_counter() - t0
        if not first:
            raise OSError("closed before the first byte")

        http_read_page(Prefixed(s, first), FLOW_GET_BYTES)
        return times
    finally:
        s.close()


FLOW_V4_MODES = (
    ("plain", "no socket option, no flag (Nagle on)"),
    ("nodelay", "TCP_NODELAY on the client socket"),
    ("msg_more", "MSG_MORE on the method offer and the login, as hev at the pin sends them"),
)[:3 if hasattr(socket, "MSG_MORE") else 2]  # Darwin has no MSG_MORE


def flow_steps(rows, keys):
    return {k: flow_summary([r[k] for r in rows if k in r]) for k in keys}


def run_flow_direct(ctx, rundir, v):
    """V4: no hev and no tun. A python SOCKS5 client on the runner talks to
    sing-box's socks inbound (ios-socks-reality.json, its login) directly,
    once per mode in FLOW_V4_MODES."""
    config, notes = flow_ios_socks(ctx, rundir)
    port, login = ios_hev_socks(config)
    expect(login is not None, "ios-socks-reality.json has a login")
    proc = flow_harness(ctx, rundir, config)
    modes = {}
    try:
        if not wait_port(port, 20, proc):
            raise LabError("the harness's SOCKS port never opened:\n" + tail(rundir / "core.log"))
        deadline, fake, err = time.time() + 20, None, "not tried"
        while time.time() < deadline:
            try:
                _, fake = flow_socks_udp_once(port, login, "nodelay", IOS_CHECK_NAME)
                break
            except OSError as e:
                err = "%s: %s" % (type(e).__name__, e)
                time.sleep(0.3)
        if fake is None:
            raise LabError("no DNS answer through sing-box's socks inbound in 20 s: %s" % err)
        flow_socks_tcp_once(port, login, "nodelay", fake)
        for mode, what in FLOW_V4_MODES:
            udp, ufails = flow_series(FLOW_N, lambda i: flow_socks_udp_once(
                port, login, mode, "v4-%s-%d.flow.lab.test" % (mode, i))[0])
            tcp, tfails = flow_series(FLOW_N, lambda i: flow_socks_tcp_once(port, login, mode, fake))
            modes[mode] = {
                "what": what,
                "udp": {"associations": FLOW_N, "answered": len(udp), "failures": ufails[:5],
                        "steps": flow_steps(udp, ("tcp_connect_s", "method_reply_s", "auth_reply_s", "request_reply_s",
                                                  "datagram_to_answer_s", "total_s")),
                        "total_s": [round(r["total_s"], 4) for r in udp]},
                "tcp": {"connections": FLOW_N, "ok": len(tcp), "target": fake, "failures": tfails[:5],
                        "steps": flow_steps(tcp, ("tcp_connect_s", "method_reply_s", "auth_reply_s", "request_reply_s",
                                                  "first_byte_s")),
                        "first_byte_s": [round(r["first_byte_s"], 4) for r in tcp]},
            }
    finally:
        proc.stop()
    notes.append("times count from the start of connect() to 127.0.0.1:%d; the UDP total ends at the DNS answer, the "
                 "TCP one at the first byte of the page" % port)
    return {"modes": modes, "notes": notes}


FLOW_VARIANTS = (
    {"id": "V1", "run": run_flow_singbox_hev,
     "name": "hev (pin) + sing-box SOCKS, login: the ios-shape hev row",
     "what": "ios-socks-reality.json as the ios-shape hev row runs it, hev at the pin with HevTunnel.configuration"},
    {"id": "V2", "run": run_flow_singbox_hev, "no_sniff": True,
     "name": "V1 without the route rule {action: sniff}", "what": "V1 with sing-box's sniff rule removed"},
    {"id": "V3", "run": run_flow_singbox_hev, "no_login": True,
     "name": "V1 without the SOCKS login (both sides)", "what": "V1 with no users in sing-box and no login in hev"},
    {"id": "V4", "run": run_flow_direct,
     "name": "no hev: python SOCKS5 client -> sing-box SOCKS, login",
     "what": "a fresh association (UDP) or CONNECT (TCP) per exchange, straight to sing-box's socks inbound"},
    {"id": "V5", "run": run_flow_pysocks_hev,
     "name": "hev (pin), login -> python SOCKS5 server (no sing-box)",
     "what": "hev alone: a trivial SOCKS5 server that answers DNS itself"},
    {"id": "V6", "run": run_flow_pysocks_hev, "udp_mode": "tcp",
     "name": "V5 with hev udp 'tcp' (UDP in TCP)", "what": "hev's FWD_UDP; sing-box does not speak it"},
    {"id": "V7", "run": run_flow_gvisor,
     "name": "sing-box gVisor tun: the ios-shape gVisor row (control)",
     "what": "ios-tun-reality.json on sing-box's own gVisor tun"},
    {"id": "V8", "run": run_flow_singbox_hev, "pipeline": True,
     "name": "V1 with hev `pipeline: true`",
     "what": "hev's pipelined handshake: offer, login and request in one write, where MSG_MORE is meant"},
    {"id": "V9", "run": run_flow_singbox_hev, "hev": FLOW_HEV_NOMORE,
     "name": "V1 with hev built without MSG_MORE (as for iOS)",
     "what": "the pin with MSG_MORE dropped from the two handshake writes (hev-build.sh): Darwin's build, where "
             "MSG_MORE is the fallback 0, and upstream b43fa85's standard handshake"},
)


def flow_cell(summary):
    if not summary or summary.get("median_s") is None:
        return "-", "-"
    return "%.4f" % summary["median_s"], "%.4f" % summary["p90_s"]


def flow_table(results):
    """Two tables: every variant's DNS and TCP figures, then V4's steps."""
    head = ("id", "variant", "DNS ok", "DNS med", "DNS p90", "TCP ok", "conn med", "conn p90", "1st med", "1st p90")
    lines = []
    for r in results:
        if r.get("error"):
            lines.append((r["id"], r["name"], "FAILED: " + r["error"].splitlines()[0][:150]))
            continue
        if "modes" in r:
            for mode, m in r["modes"].items():
                u, t = m["udp"], m["tcp"]
                lines.append(("%s %s" % (r["id"], mode), r["name"], "%d/%d" % (u["answered"], u["associations"]),
                              *flow_cell(u["steps"]["total_s"]), "%d/%d" % (t["ok"], t["connections"]),
                              *flow_cell(t["steps"]["tcp_connect_s"]), *flow_cell(t["steps"]["first_byte_s"])))
            continue
        d, t = r["dns"], r["tcp"]
        lines.append((r["id"], r["name"], "%d/%d" % (d["answered"], d["queries"]), *flow_cell(d),
                      "%d/%d" % (t["ok"], t["connections"]), *flow_cell(t["connect"]), *flow_cell(t["first_byte"])))
    steps_head = ("V4 mode", "proto", "connect", "method reply", "auth reply", "request reply", "dgram->answer / 1st byte")
    steps = []
    for r in results:
        for mode, m in (r.get("modes") or {}).items():
            for proto, last in (("udp", "datagram_to_answer_s"), ("tcp", "first_byte_s")):
                st = m[proto]["steps"]
                steps.append((mode, proto) + tuple("%s / %s" % flow_cell(st.get(k)) for k in (
                    "tcp_connect_s", "method_reply_s", "auth_reply_s", "request_reply_s", last)))
    return head, lines, steps_head, steps


def flow_print(results):
    head, lines, steps_head, steps = flow_table(results)
    print("\nudp-flow: seconds, median and p90 (nearest rank) over %d each; DNS = one A query to %s on a new UDP "
          "socket (V4: connect to answer); conn/1st = TCP connect and first byte of a %d-byte GET, both from the "
          "start of connect" % (FLOW_N, IOS_TUN_DNS, FLOW_GET_BYTES))
    for row in [head] + lines:
        if len(row) == 3:
            print("%-11s %-58s %s" % row)
        else:
            print("%-11s %-58s %7s %8s %8s %7s %9s %9s %8s %8s" % row)
    if steps:
        print("\nV4 steps, median / p90 seconds from the start of connect():")
        print("%-9s %-5s %-17s %-17s %-17s %-17s %s" % steps_head)
        for row in steps:
            print("%-9s %-5s %-17s %-17s %-17s %-17s %s" % row)


def flow_markdown(results):
    head, lines, steps_head, steps = flow_table(results)
    out = ["## UDP flow latency (lab.py udp-flow)", "",
           "Seconds, median and p90 over %d each. DNS: one A query to %s on a new UDP socket (V4: connect to "
           "answer). TCP: connect and first byte of a %d-byte GET from the start of connect." % (
               FLOW_N, IOS_TUN_DNS, FLOW_GET_BYTES), "",
           "| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for row in lines:
        row = row if len(row) == len(head) else row + ("",) * (len(head) - len(row))
        out.append("| " + " | ".join(str(c).replace("|", "/") for c in row) + " |")
    if steps:
        out += ["", "V4 steps (median / p90 from the start of connect):", "",
                "| " + " | ".join(steps_head) + " |", "|" + "---|" * len(steps_head)]
        out += ["| " + " | ".join(row) + " |" for row in steps]
    return "\n".join(out) + "\n"


def cmd_udp_flow(a):
    if not (sys.platform.startswith("linux") or FLOW_DARWIN):
        raise LabError("udp-flow runs on Linux and macOS only (root via sudo, tuns)")
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = Path(a.bin).resolve()
    budget = Budget(a.budget_min)
    for port in (PORT_PAGE, PORT_DEST, PORT_REALITY, PORT_XHTTP, PORT_WEBTUNNEL, PORT_SOCKS, FLOW_PORT_PYSOCKS):
        if not wait_port_free(port, 15):
            raise LabError("port %d is still taken" % port)
    for tun in (FLOW_TUN_HEV, FLOW_TUN_GVISOR):
        flow_drop_tun(tun)
    servers = Servers(out, b / "loadgen", b / "xray-26.4.25" / "xray")
    doc = {"what": "where a new UDP flow's time goes behind hev, against sing-box's gVisor tun",
           "queries_and_connections_each": FLOW_N, "resolver": IOS_TUN_DNS, "get_bytes": FLOW_GET_BYTES,
           "runner": " ".join(os.uname()), "variants": []}
    if FLOW_DARWIN:
        doc["darwin"] = ("macOS arm64: Apple's clang built the pin (no MSG_MORE on this OS, so hev's fallback makes it "
                         "0, as for the iPhone), Darwin's TCP on lo0; V4 has no msg_more mode here")
    for name in ("hev.versions.txt", "hev-nomore.versions.txt", "hev-nomore.identical.txt", "msg_more.txt"):
        try:
            doc[name] = (b / name).read_text()
        except OSError:
            doc[name] = None
    results = doc["variants"]
    try:
        servers.start()
        doc["server"] = servers.version
        ctx = {"bin": b, "pub": servers.pub}
        for v in FLOW_VARIANTS:
            base = {"id": v["id"], "name": v["name"], "what": v["what"]}
            if not budget.allows(25):
                results.append(dict(base, error="skipped: out of the %.1f min budget" % a.budget_min))
                continue
            rundir = out / "runs" / v["id"].lower()
            rundir.mkdir(parents=True, exist_ok=True)
            log("udp-flow:", v["id"], v["name"])
            t0 = time.time()
            try:
                res = dict(base, **v["run"](ctx, rundir, v))
            except Exception as e:
                traceback.print_exc()
                res = dict(base, error="%s: %s" % (type(e).__name__, e))
            res["seconds"] = rnd(time.time() - t0, 1)
            results.append(res)
            write_json(out / "udp-flow.json", doc)
            flow_print([res])
    finally:
        servers.stop()
        for tun in (FLOW_TUN_HEV, FLOW_TUN_GVISOR):
            flow_drop_tun(tun)
        write_json(out / "udp-flow.json", doc)
    flow_print(results)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(flow_markdown(results))
    return 0


def dns_fake_answer(query, addr):
    """An answer to `query`: one A record `addr` for an A question, none for
    any other type."""
    qid = struct.unpack(">H", query[:2])[0]
    end = dns_skip_name(query, 12) + 4
    qtype = struct.unpack(">H", query[end - 4:end - 2])[0]
    answer = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 600, 4) + socket.inet_aton(addr) if qtype == 1 else b""
    return struct.pack(">HHHHHH", qid, 0x8180, 1, 1 if answer else 0, 0, 0) + query[12:end] + answer


def cmd_flow_socks(a):
    """The python SOCKS5 server of udp-flow's V5 and V6 (threads, standard
    library): the login when one is given; CONNECT to the page whatever the
    destination; UDP ASSOCIATE (RFC 1928, a UDP relay on 127.0.0.1) and hev's
    FWD_UDP (0x05, UDP in TCP: [datlen u16][hdrlen u8][address][data] on the
    control connection); every datagram to port 53 answered here with an A
    record from 198.18.1.0 on."""
    login = tuple(a.login.split(":", 1)) if a.login else None
    lock, counter = threading.Lock(), [0]

    def next_addr():
        with lock:
            counter[0] += 1
            i = counter[0]
        return "198.18.%d.%d" % (1 + (i // 250) % 200, 1 + i % 250)

    def answer(payload):
        if len(payload) < 17:
            return None
        return dns_fake_answer(payload, next_addr())

    def pump(src, dst):
        try:
            while True:
                data = src.recv(65536)
                if not data:
                    break
                dst.sendall(data)
        except OSError:
            pass
        finally:
            try:
                dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    def relay_udp(c):
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.bind(("127.0.0.1", 0))
        c.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", u.getsockname()[1]))
        try:
            while True:
                r, _, _ = select.select([c, u], [], [], 30)
                if not r:
                    return
                if c in r and not c.recv(1):
                    return
                if u in r:
                    data, peer = u.recvfrom(65535)
                    atype = data[3]
                    alen = {1: 4, 4: 16}.get(atype, 1 + data[4])
                    hdr = data[:4 + alen + 2]
                    if struct.unpack(">H", hdr[-2:])[0] == 53:
                        resp = answer(data[len(hdr):])
                        if resp:
                            u.sendto(b"\0\0\0" + hdr[3:] + resp, peer)
        finally:
            u.close()

    def relay_udp_in_tcp(c):
        c.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
        c.settimeout(30)
        while True:
            try:
                datlen, hdrlen = struct.unpack(">HB", recv_exact(c, 3))
                addr = recv_exact(c, hdrlen - 3)
                data = recv_exact(c, datlen)
            except OSError:
                return
            if struct.unpack(">H", addr[-2:])[0] == 53:
                resp = answer(data)
                if resp:
                    c.sendall(struct.pack(">HB", len(resp), 3 + len(addr)) + addr + resp)

    def handle(c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            c.settimeout(60)
            ver, nm = recv_exact(c, 2)
            methods = recv_exact(c, nm)
            want = 2 if login else 0
            if ver != 5 or want not in methods:
                c.sendall(b"\x05\xff")
                return
            c.sendall(bytes([5, want]))
            if login:
                _, ulen = recv_exact(c, 2)
                user = recv_exact(c, ulen)
                plen = recv_exact(c, 1)[0]
                pw = recv_exact(c, plen)
                ok = (user.decode(errors="replace"), pw.decode(errors="replace")) == login
                c.sendall(b"\x01\x00" if ok else b"\x01\x01")
                if not ok:
                    return
            _, cmd, _, atype = recv_exact(c, 4)
            recv_exact(c, {1: 4, 4: 16}.get(atype) or recv_exact(c, 1)[0])
            recv_exact(c, 2)
            if cmd == 1:
                up = socket.create_connection(("127.0.0.1", a.page_port), timeout=10)
                up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                up.settimeout(60)
                c.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("127.0.0.1") + struct.pack(">H", up.getsockname()[1]))
                t = threading.Thread(target=pump, args=(up, c), daemon=True)
                t.start()
                pump(c, up)
                t.join(65)
                up.close()
            elif cmd == 3:
                relay_udp(c)
            elif cmd == 5:
                relay_udp_in_tcp(c)
            else:
                c.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
        except (OSError, ValueError, IndexError, struct.error):
            pass
        finally:
            c.close()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.port))
    srv.listen(512)
    print("flow-socks on 127.0.0.1:%d, login %s, page 127.0.0.1:%d" % (a.port, "on" if login else "off", a.page_port),
          flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()


# ---------------------------------------------------------------- heap-profile: where xray-rust's memory goes
#
# `lab.py heap-profile` (the step after Measure, with servers of its own, so
# no row moves): xray-rust in the mobile profile on the four lines, under two
# loads each: the rows' burst (run_burst), and HEAP_HOLD_N connections opened
# through the core, one small GET on each, then left idle for HEAP_HOLD_S
# (Hold, threads of lab.py, which checks with a second GET that each one
# lived).
#
# Linux, per line and load, two runs of the build with line tables
# (rust-core-build.sh XRS_LINE_TABLES=1: the rows' code, debug info that is
# never loaded): one plain, for RSS (VmHWM, and RssAnon, RssFile and
# /proc/<pid>/smaps at its largest sample, polled every HEAP_POLL_S), and one
# under heaptrack, for the heap: its peak, and the bytes each allocation
# backtrace held at that peak (heaptrack_print, unmerged), grouped here by
# the backtrace's first frame in xray-rust's crates/ or vendor/. heaptrack's
# own merge groups by the address of the malloc call, which in Rust is the
# allocator shim, one place for nearly every allocation. The heaptrack run
# ends with HEAP_STOP, a signal xray-rust does not catch, so nothing unwinds
# and heaptrack's "leaked" is the heap still live at that moment: for the
# hold, what 32 idle connections keep.
#
# macOS: phys_footprint split by `footprint` and `vmmap --summary`. A burst
# half lasts 0.1 to 0.4 s here, less than either tool takes, so the first
# burst only finds the peak (phys_footprint polled every HEAP_POLL_S); in the
# next ones the core is stopped (SIGSTOP) the moment its footprint crosses
# HEAP_FREEZE_AT of that peak, both tools read the frozen process, and it
# goes on (SIGCONT). The hold is read in the middle of its idle.

HEAP_HOLD_N = BURST_FLOWS
HEAP_HOLD_S = 20
HEAP_HOLD_GET = 1024
HEAP_POLL_S = 0.01
HEAP_SETTLE_S = 3.0
HEAP_TOP = 25
HEAP_TRACES = 2000
# One freeze per burst after the first, until one lands; the last try lower.
HEAP_FREEZE_AT = (0.95, 0.95, 0.85)
HEAP_SOCKS = "127.0.0.1:%d" % PORT_SOCKS
HEAP_LINES = ("vless-reality-vision", WEBTUNNEL, "hysteria2-salamander", "vless-xhttp-reality packet-up")
HEAP_LINE_NAMES = {"reality": "vless-reality-vision", "webtunnel": WEBTUNNEL, "hysteria2": "hysteria2-salamander",
                   "xhttp": "vless-xhttp-reality packet-up"}
# How the heaptrack run stops the core: a signal it does not catch, so nothing
# unwinds and heaptrack's "leaked" is the heap live at that moment. v0.9.0's
# CLI caught only Ctrl-C, so SIGTERM did; the fork's CLI stops gracefully on
# SIGTERM and SIGINT (crates/xray-cli/src/main.rs, shutdown_signal), which
# would close the hold's connections first, so the lab sends SIGHUP.
HEAP_STOP = signal.SIGHUP


def xrs_mobile_env():
    """cmd_desktop's mobile profile: XRAY_TUN_PROFILE=mobile and the Tokio workers xray-ffi sizes here."""
    return {"XRAY_TUN_PROFILE": "mobile", "TOKIO_WORKER_THREADS": str(max(2, min(6, os.cpu_count() or 2)))}


class FastPoll(threading.Thread):
    """Samples one process every HEAP_POLL_S and keeps every sample and the
    largest by `key`. `on_max(row)` runs, in this thread, whenever a sample
    sets a new maximum (Linux reads smaps there). `arm(threshold, action)`
    runs `action(row)` once, in this thread, on the first sample at or above
    `threshold` (the macOS freeze)."""

    def __init__(self, read, key, on_max=None, period=HEAP_POLL_S):
        super().__init__(daemon=True)
        self.read, self.key, self.on_max, self.period = read, key, on_max, period
        self.rows = []
        self.max_row = None
        self._armed = None
        self._lock = threading.Lock()
        self._halt = threading.Event()

    def arm(self, threshold, action):
        with self._lock:
            self._armed = (threshold, action)

    def disarm(self):
        with self._lock:
            self._armed = None

    def last(self):
        return self.rows[-1] if self.rows else None

    def run(self):
        while not self._halt.is_set():
            t = time.time()
            try:
                row = self.read()
            except Exception:  # gone, or between two states
                row = None
            if row and row.get(self.key) is not None:
                row["t"] = t
                self.rows.append(row)
                if self.max_row is None or row[self.key] > self.max_row[self.key]:
                    self.max_row = row
                    if self.on_max:
                        try:
                            self.on_max(row)
                        except Exception:
                            pass
                fire = None
                with self._lock:
                    if self._armed and row[self.key] >= self._armed[0]:
                        fire, self._armed = self._armed[1], None
                if fire:
                    try:
                        fire(row)
                    except Exception:
                        traceback.print_exc()
            self._halt.wait(self.period)

    def halt(self):
        self._halt.set()
        self.join(60)
        return self.rows


class Hold:
    """HEAP_HOLD_N connections through the core's SOCKS port at once, a GET
    /bytes?n=HEAP_HOLD_GET on each (HTTP/1.1, kept alive), HEAP_HOLD_S idle,
    a second GET on each (each checked against the page's own bytes). They
    stay open after the result until release(). Threads of this process: the
    samples read only the core. Events come out of next(): {"event": "open"}
    once every connection had its first answer, then the result."""

    def __init__(self, rundir):
        self.rundir = rundir
        self.lines = queue.Queue()
        self.socks = [None] * HEAP_HOLD_N
        self.errors = []
        self._lock = threading.Lock()
        self._release = threading.Event()
        threading.Thread(target=self._run, daemon=True).start()

    def _get(self, s):
        s.sendall(("GET /bytes?n=%d HTTP/1.1\r\nHost: %s\r\nUser-Agent: lab-hold\r\nAccept-Encoding: identity\r\n\r\n"
                   % (HEAP_HOLD_GET, THROUGH)).encode())
        http_read_page(s, HEAP_HOLD_GET)

    def _err(self, text):
        with self._lock:
            self.errors.append(text)

    def _open(self, i):
        try:
            s = socks5_open(PORT_SOCKS, None, "plain", 1, THROUGH, 80, timeout=30)[0]
        except Exception as e:
            self._err("connection %d, SOCKS: %s" % (i, e))
            return
        try:
            self._get(s)
            self.socks[i] = s
        except Exception as e:
            self._err("connection %d, first GET: %s" % (i, e))
            s.close()

    def _again(self, i, alive):
        try:
            self._get(self.socks[i])
            with self._lock:
                alive.append(i)
        except Exception as e:
            self._err("connection %d, GET after the idle: %s" % (i, e))

    @staticmethod
    def _all(target, args_list):
        threads = [threading.Thread(target=target, args=args, daemon=True) for args in args_list]
        for t in threads:
            t.start()
        for t in threads:
            t.join(90)

    def _run(self):
        t0 = time.time()
        res = {"op": "hold", "flows": HEAP_HOLD_N, "ok_flows": 0, "alive_after_idle": 0, "start_ms": int(t0 * 1000),
               "ok": False}
        try:
            self._all(self._open, [(i,) for i in range(HEAP_HOLD_N)])
            opened = [i for i, s in enumerate(self.socks) if s is not None]
            t_open = time.time()
            res.update(ok_flows=len(opened), open_ms=int(t_open * 1000))
            self.lines.put({"event": "open", "flows": HEAP_HOLD_N, "ok_flows": len(opened),
                            "start_ms": res["start_ms"], "open_ms": res["open_ms"]})
            if opened:
                self._release.wait(HEAP_HOLD_S)
                res["idle_end_ms"] = int(time.time() * 1000)
                alive = []
                self._all(self._again, [(i, alive) for i in opened])
                res["alive_after_idle"] = len(alive)
            res["end_ms"] = int(time.time() * 1000)
            res["bytes"] = HEAP_HOLD_GET * (res["ok_flows"] + res["alive_after_idle"])
            res["ok"] = res["ok_flows"] == HEAP_HOLD_N and res["alive_after_idle"] == HEAP_HOLD_N
        except Exception as e:
            self._err("hold: %s: %s" % (type(e).__name__, e))
        if self.errors:
            res["error"] = "%d problems; first: %s" % (len(self.errors), "; ".join(self.errors[:3]))
        try:
            write_json(self.rundir / "hold.json", dict(res, problems=self.errors[:50]))
        except OSError:
            pass
        self.lines.put(res)

    def next(self, timeout):
        try:
            return self.lines.get(timeout=timeout)
        except queue.Empty:
            return None

    def release(self):
        self._release.set()
        for s in self.socks:
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass


def heap_drive(ctx, load, rundir, mid=None, at_end=None):
    """HEAP_SETTLE_S, then the load: 'burst', the rows' burst; 'hold', the
    Hold. `mid()` runs in the middle of the hold's idle; `at_end()` runs once
    the load is over, for the hold while its connections are still held.
    Returns the loadgen results and the windows (wall-clock seconds)."""
    t_settle = time.time()
    time.sleep(HEAP_SETTLE_S)
    rec = {"settle": (t_settle, time.time())}
    if load == "burst":
        bdown, bup, win = run_burst(ctx["host"], HEAP_SOCKS)
        rec.update(loadgen=[bdown, bup], window=win, up_start=bdown.get("host_up_start"),
                   ok=bool(bdown.get("ok") and bup.get("ok")))
        if at_end:
            at_end()
        return rec
    hold = Hold(rundir)
    try:
        ev = hold.next(120)
        if not ev or ev.get("event") != "open":
            raise LabError("the hold never had its connections open: %s" % ev)
        if not ev.get("ok_flows"):
            raise LabError("the hold opened none of its %d connections: %s" % (HEAP_HOLD_N, "; ".join(hold.errors[:3])))
        t_open = ev["open_ms"] / 1000.0
        rec["open"] = ev
        if mid:
            wait = t_open + HEAP_HOLD_S / 2.0 - time.time()
            if wait > 0:
                time.sleep(wait)
            mid()
        res = hold.next(HEAP_HOLD_S + 120)
        if not res:
            raise LabError("the hold gave no result: " + "; ".join(hold.errors[:3]))
        idle_end = (res.get("idle_end_ms") or 0) / 1000.0 or time.time()
        rec.update(loadgen=[ev, res], window=((res.get("start_ms") or 0) / 1000.0 or t_open, time.time()),
                   idle=(t_open + 2.0, idle_end), ok=bool(res.get("ok")))
        if at_end:
            at_end()
    finally:
        hold.release()
    return rec


def load_summary(rec):
    """The loadgen side of a run, short: did every transfer or connection make it."""
    out = []
    for r in rec.get("loadgen") or []:
        if r.get("event") == "open":
            continue
        if r.get("op") == "hold":
            out.append("hold: %s/%s opened, %s alive after %d s idle%s" % (
                r.get("ok_flows", 0), r.get("flows"), r.get("alive_after_idle", 0), HEAP_HOLD_S,
                "" if r.get("ok") else "; " + str(r.get("error", ""))[:200]))
        else:
            out.append("%s: %s/%s whole, %s Mbit/s%s" % (
                r.get("op"), r.get("ok_flows", 0), r.get("flows"), rnd(r.get("mbps")),
                "" if r.get("ok") else "; " + str(r.get("error", ""))[:200]))
    return "; ".join(out)


# ---- Linux: RSS by kind, from /proc/<pid>/status and smaps


def heap_mem_linux(pid):
    with open("/proc/%d/status" % pid) as f:
        s = f.read()
    m = re.search(r"^Threads:\s+(\d+)", s, re.M)
    return {"rss": proc_status_kb(s, "VmRSS"), "hwm": proc_status_kb(s, "VmHWM"),
            "anon": proc_status_kb(s, "RssAnon"), "file": proc_status_kb(s, "RssFile"),
            "threads": int(m.group(1)) if m else None}


SMAPS_HEAD = re.compile(r"^([0-9a-f]+)-([0-9a-f]+) (\S{4}) \S+ \S+ \d+\s*(.*)$")
# glibc's HEAP_MAX_SIZE on 64-bit: a thread arena's heaps start on it.
GLIBC_HEAP_ALIGN = 64 * MIB


def smaps_read(pid):
    maps, cur = [], None
    with open("/proc/%d/smaps" % pid) as f:
        for line in f:
            m = SMAPS_HEAD.match(line)
            if m:
                cur = {"start": int(m.group(1), 16), "end": int(m.group(2), 16), "perms": m.group(3),
                       "path": m.group(4).strip(), "rss": 0}
                maps.append(cur)
            elif cur is not None and line.startswith("Rss:"):
                cur["rss"] = int(line.split()[1]) * 1024
    return maps


def smaps_split(maps, exe):
    """RSS by kind, in bytes. The kernel no longer names thread stacks, so
    anonymous mappings are told apart by shape: rw-p right above a small
    ---p guard is a thread's stack (256 KiB and up) or a signal stack (Rust
    gives each thread one); rw-p starting on a 64 MiB boundary is a glibc
    thread arena's heap; the rest is malloc's own mmaps (chunks of 128 KiB
    and up) and anything else that maps memory."""
    exe = os.path.realpath(str(exe))
    out = {}
    prev = None
    for m in sorted(maps, key=lambda x: x["start"]):
        path, perms, size = m["path"], m["perms"], m["end"] - m["start"]
        if path == exe or path.startswith(exe + " "):
            kind = "binary: code" if "x" in perms else (
                "binary: written data" if perms[1] == "w" else "binary: read-only data")
        elif path == "[heap]":
            kind = "malloc: main arena ([heap])"
        elif path == "[stack]":
            kind = "stack: main thread"
        elif path.startswith("["):
            kind = "kernel ([vdso], [vvar])"
        elif path:
            kind = "shared libraries" if ".so" in os.path.basename(path) else "other files"
        elif perms == "---p":
            kind = None
        else:
            guard = (prev is not None and not prev["path"] and prev["perms"] == "---p" and prev["end"] == m["start"]
                     and prev["end"] - prev["start"] <= 64 * 1024)
            if guard and size >= 256 * 1024:
                kind = "stack: other threads"
            elif guard:
                kind = "stack: signal stacks"
            elif m["start"] % GLIBC_HEAP_ALIGN == 0:
                kind = "malloc: thread arenas"
            else:
                kind = "anonymous: other (malloc's mmapped chunks, runtime maps)"
        if kind:
            out[kind] = out.get(kind, 0) + m["rss"]
        prev = m
    return out


def proc_start_wall(pid):
    """When a process started, wall clock, to the kernel's tick: heaptrack's
    timestamps count from its start."""
    with open("/proc/%d/stat" % pid) as f:
        stat = f.read()
    ticks = int(stat[stat.rindex(")") + 2:].split()[19])
    with open("/proc/uptime") as f:
        uptime = float(f.read().split()[0])
    return time.time() - uptime + ticks / os.sysconf("SC_CLK_TCK")


# ---- heaptrack_print's text, and allocation sites by xray-rust's own frames

HT_UNIT = {"B": 1, "K": 10 ** 3, "M": 10 ** 6, "G": 10 ** 9, "T": 10 ** 12}
HT_ENTRY = re.compile(r"^(-?[\d.]+[BKMGT]B?) (?:peak memory consumed|leaked) over (\d+) calls from$")
HT_SUMMARY = re.compile(r"^(total runtime|calls to allocation functions|temporary memory allocations|"
                        r"peak heap memory consumption|peak RSS \(including heaptrack overhead\)|"
                        r"total memory leaked|suppressed leaks): (.*)$")
HT_OURS = re.compile(r"(?:^|/xray-rust-build-[^/]+/src/)((crates|vendor)/[^/]+/.+)$")
HT_REGISTRY = re.compile(r"/\.?cargo/registry/src/[^/]+/([^/]+)/(.+)$")
HT_GIT = re.compile(r"/\.?cargo/git/checkouts/[^/]+/[^/]+/(.+)$")
HT_STD = re.compile(r"/rustc/[0-9a-f]+/library/(.+)$|/rust/deps/(.+)$")


def ht_bytes(text):
    """heaptrack's formatBytes: 1000-based, two decimals, one letter (12.34M, 1.50K, 123B)."""
    m = re.match(r"^\s*(-?[\d.]+)([BKMGT])B?\b", text or "")
    return int(round(float(m.group(1)) * HT_UNIT[m.group(2)])) if m else None


def ht_parse(text):
    """heaptrack_print's unmerged output: {"peaks": [...], "leaks": [...],
    "summary": {...}}. Each entry: bytes, calls and frames, leaf first, each
    frame {fn, file, line, module} (inlined frames have no module)."""
    out = {"peaks": [], "leaks": [], "summary": {}}
    section = cur = frame = None
    for line in text.splitlines():
        if line in ("PEAK MEMORY CONSUMERS", "MEMORY LEAKS"):
            section = "peaks" if line.startswith("PEAK") else "leaks"
            cur = frame = None
            continue
        if line in ("MOST CALLS TO ALLOCATION FUNCTIONS", "MOST TEMPORARY ALLOCATIONS"):
            section = cur = frame = None
            continue
        m = HT_SUMMARY.match(line)
        if m:
            out["summary"][m.group(1)] = m.group(2).strip()
            section = cur = frame = None
            continue
        if section is None:
            continue
        m = HT_ENTRY.match(line)
        if m:
            cur = {"bytes": ht_bytes(m.group(1)), "calls": int(m.group(2)), "frames": []}
            out[section].append(cur)
            frame = None
            continue
        if cur is None:
            continue
        if line.startswith("    at "):
            if frame is not None:
                loc = line[7:]
                f, _, ln = loc.rpartition(":")
                frame["file"], frame["line"] = (f, int(ln)) if ln.isdigit() else (loc, None)
        elif line.startswith("    in "):
            if frame is not None:
                frame["module"] = line[7:]
        elif line.startswith("  ") and not line.startswith("   "):
            frame = {"fn": line[2:], "file": None, "line": None, "module": None}
            cur["frames"].append(frame)
    return out


def rust_name(name):
    """A Rust symbol as heaptrack names it, without the legacy hash and with
    the legacy escapes undone (when no Rust demangler was linked in)."""
    s = name or ""
    if s.startswith("_ZN") and s.endswith("E"):
        # Not demangled at all: the legacy scheme's length-prefixed parts.
        parts, i, body = [], 3, s[:-1]
        while i < len(body):
            m = re.match(r"\d+", body[i:])
            if not m:
                break
            n, i = int(m.group(0)), i + len(m.group(0))
            parts.append(body[i:i + n])
            i += n
        if parts and i >= len(body):
            s = "::".join(parts)
    s = re.sub(r"::h[0-9a-f]{16}$", "", s)
    if "$" in s:
        for k, v in (("$LT$", "<"), ("$GT$", ">"), ("$RF$", "&"), ("$BP$", "*"), ("$C$", ","), ("$u20$", " "),
                     ("$u27$", "'"), ("$u5b$", "["), ("$u5d$", "]"), ("$u7b$", "{"), ("$u7d$", "}"), ("$u3b$", ";"),
                     ("$u2b$", "+"), ("$u22$", '"'), ("$u3d$", "="), ("$u21$", "!"), ("$u7e$", "~")):
            s = s.replace(k, v)
        s = s.replace("..", "::")
        if s.startswith("_<"):
            s = s[1:]
    return s


def ht_where(frame):
    """(kind, where) of a frame by its source file: 'xray-rust' (crates/),
    'vendor' (vendor/: the crates xray-rust patches in), 'crate' (a registry
    or git crate), 'std', 'other' (C libraries, no file)."""
    f = frame.get("file") or ""
    ln = frame.get("line")
    at = (lambda p: "%s:%s" % (p, ln) if ln else p)
    m = HT_OURS.search(f)
    if m and "/.cargo/" not in f and "/rustc/" not in f:
        return ("xray-rust" if m.group(2) == "crates" else "vendor"), at(m.group(1))
    m = HT_REGISTRY.search(f)
    if m:
        return "crate", at(m.group(1) + "/" + m.group(2))
    m = HT_GIT.search(f)
    if m:
        return "crate", at(m.group(1))
    m = HT_STD.search(f)
    if m:
        return "std", at(m.group(1) or m.group(2))
    return "other", at(f) if f else (os.path.basename(frame.get("module") or "") or "?")


def heap_sites(entries, total, top=HEAP_TOP * 2):
    """The backtraces grouped by their first (leaf-most) frame in xray-rust or
    a vendored crate: that frame's file:line and function is the site, `via`
    the call it made (the frame just below it that is not the standard
    library's, else the one just below it), `callers` the next two of its
    own frames above it. A backtrace with no such frame is grouped by its
    first frame outside the standard library."""
    sites = {}
    for e in entries:
        frames = e["frames"]
        kinds = [ht_where(fr) for fr in frames]
        i = next((k for k, (kind, _) in enumerate(kinds) if kind in ("xray-rust", "vendor")), None)
        # Where the bytes are asked for: the leaf-most frame that is neither
        # the standard library nor a C library (a crate's buffer, a Vec in
        # xray-rust itself).
        a = next((k for k, (kind, _) in enumerate(kinds) if kind not in ("std", "other")), None)
        alloc_at = ("%s (%s)" % (rust_name(frames[a]["fn"]), kinds[a][1])) if a is not None else None
        if i is None:
            j = next((k for k, (kind, _) in enumerate(kinds) if kind not in ("std",)), None)
            j = j if j is not None else (len(frames) - 1 if frames else None)
            kind, where = ("none", "(no xray-rust frame) " + kinds[j][1]) if j is not None else ("none", "??")
            fn = rust_name(frames[j]["fn"]) if j is not None else "??"
            via, callers = None, []
        else:
            kind, where = kinds[i]
            fn = rust_name(frames[i]["fn"])
            below = [k for k in range(i - 1, -1, -1) if kinds[k][0] != "std"]
            v = below[0] if below else (i - 1 if i > 0 else None)
            via = ("%s (%s)" % (rust_name(frames[v]["fn"]), kinds[v][1])) if v is not None else None
            callers = ["%s (%s)" % (rust_name(frames[k]["fn"]), kinds[k][1]) for k in range(i + 1, len(frames))
                       if kinds[k][0] in ("xray-rust", "vendor")][:2]
        key = (where, fn)
        s = sites.setdefault(key, {"site": where, "function": fn, "kind": kind, "bytes": 0, "calls": 0,
                                   "traces": 0, "via": {}, "alloc_at": {}, "callers": {}})
        s["bytes"] += e["bytes"] or 0
        s["calls"] += e["calls"]
        s["traces"] += 1
        if via:
            s["via"][via] = s["via"].get(via, 0) + (e["bytes"] or 0)
        if alloc_at:
            s["alloc_at"][alloc_at] = s["alloc_at"].get(alloc_at, 0) + (e["bytes"] or 0)
        if callers:
            c = " <- ".join(callers)
            s["callers"][c] = s["callers"].get(c, 0) + (e["bytes"] or 0)
    out = sorted(sites.values(), key=lambda s: -s["bytes"])[:top]
    for s in out:
        s["mb"] = rnd(s["bytes"] / MIB, 2)
        s["share"] = rnd(100.0 * s["bytes"] / total, 1) if total else None
        s["via"] = [{"call": k, "bytes": v} for k, v in sorted(s["via"].items(), key=lambda kv: -kv[1])[:3]]
        s["alloc_at"] = [{"frame": k, "bytes": v} for k, v in sorted(s["alloc_at"].items(), key=lambda kv: -kv[1])[:3]]
        s["callers"] = [{"chain": k, "bytes": v} for k, v in sorted(s["callers"].items(), key=lambda kv: -kv[1])[:3]]
    return out


def massif_series(path):
    """heaptrack_print --print-massif with no detailed snapshots: the live heap
    at each of heaptrack's timestamps (every 10 ms), as (seconds since the
    process started, bytes)."""
    series, t = [], None
    try:
        with open(path) as f:
            for line in f:
                if line.startswith("time="):
                    t = float(line[5:])
                elif line.startswith("mem_heap_B=") and t is not None:
                    series.append((t, int(line[11:])))
                    t = None
    except (OSError, ValueError):
        pass
    return series


def heaptrack_report(ctx, data, slug):
    """heaptrack_print twice: unmerged (every backtrace's bytes at the peak
    and still live at the end, the heap over time) and merged (heaptrack's own
    view, kept as it prints it)."""
    htdir = ctx["htdir"]
    unmerged = htdir / (slug + ".peaks-and-leaks.txt")
    merged = htdir / (slug + ".peaks-merged.txt")
    massif = htdir / (slug + ".massif.txt")
    base = [str(ctx["heaptrack_print"]), "--file", str(data), "--print-allocators", "0", "--print-temporary", "0"]
    t0 = time.time()
    with open(unmerged, "w") as f:
        cp = subprocess.run(base + ["--print-peaks", "1", "--print-leaks", "1", "--merge-backtraces", "0",
                                    "--peak-limit", str(HEAP_TRACES), "--print-massif", str(massif),
                                    "--massif-detailed-freq", "0"],
                            stdout=f, stderr=subprocess.PIPE, text=True, timeout=420)
    if cp.returncode != 0:
        raise LabError("heaptrack_print (unmerged) exited %d: %s" % (cp.returncode, (cp.stderr or "")[-600:]))
    merged_s = time.time() - t0
    try:
        with open(merged, "w") as f:
            subprocess.run(base + ["--print-peaks", "1", "--print-leaks", "0", "--merge-backtraces", "1",
                                   "--peak-limit", str(HEAP_TOP), "--sub-peak-limit", str(HEAP_TOP)],
                           stdout=f, stderr=subprocess.STDOUT, text=True, timeout=max(60, min(300, 2 * merged_s)))
    except subprocess.TimeoutExpired:
        with open(merged, "a") as f:
            f.write("\n[lab: heaptrack_print --merge-backtraces 1 stopped after its timeout]\n")
    parsed = ht_parse(unmerged.read_text(errors="replace"))
    s = parsed["summary"]
    peak = ht_bytes(s.get("peak heap memory consumption"))
    leaked = ht_bytes(s.get("total memory leaked"))
    listed_peak = sum(e["bytes"] or 0 for e in parsed["peaks"])
    listed_leak = sum(e["bytes"] or 0 for e in parsed["leaks"])
    calls = re.match(r"(\d+)", s.get("calls to allocation functions", ""))
    return {
        "peak_heap_bytes": peak, "live_at_end_bytes": leaked,
        "peak_rss_with_heaptrack_bytes": ht_bytes(s.get("peak RSS (including heaptrack overhead)")),
        "calls": int(calls.group(1)) if calls else None, "runtime": s.get("total runtime"),
        "summary": s, "print_seconds": rnd(time.time() - t0, 1),
        "traces_listed_at_peak": len(parsed["peaks"]), "traces_listed_live_at_end": len(parsed["leaks"]),
        "listed_share_of_peak": rnd(100.0 * listed_peak / peak, 1) if peak else None,
        "listed_share_live_at_end": rnd(100.0 * listed_leak / leaked, 1) if leaked else None,
        "sites_at_peak": heap_sites(parsed["peaks"], peak),
        "sites_live_at_end": heap_sites(parsed["leaks"], leaked),
        "files": {"data": data.name, "unmerged": unmerged.name, "merged": merged.name, "massif": massif.name},
        "massif": massif_series(massif),
    }


def heap_series_figures(series, t_base, rec):
    """The heap over time against the load's windows: where its peak fell, and
    for the hold the median over the idle."""
    if not series or t_base is None:
        return {}
    wall = [(t_base + t, b) for t, b in series]
    t_pk, b_pk = max(wall, key=lambda tb: tb[1])
    out = {"heap_peak_at_s_into_load": rnd(t_pk - rec["settle"][1], 2)}
    if rec.get("up_start"):
        out["heap_peak_half"] = "down half" if t_pk < rec["up_start"] else "up half"
        b0, b1 = rec["window"]
        out["heap_down_half_peak_mb"] = mb(max([b for t, b in wall if b0 <= t < rec["up_start"]] or [0]))
        out["heap_up_half_peak_mb"] = mb(max([b for t, b in wall if rec["up_start"] <= t <= b1] or [0]))
    if rec.get("idle"):
        i0, i1 = rec["idle"]
        vals = [b for t, b in wall if i0 <= t <= i1]
        out["heap_hold_median_mb"] = mb(statistics.median(vals)) if vals else None
        out["heap_hold_max_mb"] = mb(max(vals)) if vals else None
        out["heap_peak_half"] = "during the idle" if i0 <= t_pk <= i1 else (
            "before the idle (connections opening)" if t_pk < i0 else "after the idle")
    out["heap_before_load_mb"] = mb(statistics.median([b for t, b in wall if rec["settle"][0] <= t <= rec["settle"][1]]
                                                      or [0]))
    return out


def heap_linux_plain(ctx, cfg_path, env, rundir, load):
    """The line-tables build alone: RSS, and what it is made of at its largest sample."""
    exe = ctx["xrs"]
    proc = Proc([exe, "run", "-config", cfg_path], rundir / "core.log", cwd=rundir, env=clean_env(env))
    at_max, at_mid = {}, {}

    def on_max(row):
        at_max["row"] = dict(row)
        at_max["smaps"] = smaps_split(smaps_read(proc.pid), exe)

    def mid():
        at_mid["row"] = heap_mem_linux(proc.pid)
        at_mid["smaps"] = smaps_split(smaps_read(proc.pid), exe)

    poll = None
    try:
        if not wait_port(PORT_SOCKS, 30, proc):
            raise LabError("xray-rust's SOCKS port never opened:\n" + tail(rundir / "core.log"))
        probe = ctx["host"].probe(HEAP_SOCKS, 40)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core in 40 s (%s):\n%s" % (probe.get("error"), tail(rundir / "core.log")))
        poll = FastPoll(lambda: heap_mem_linux(proc.pid), "rss", on_max=on_max)
        poll.start()
        rec = heap_drive(ctx, load, rundir, mid=mid if load == "hold" else None)
        final = heap_mem_linux(proc.pid)
    finally:
        if poll is not None:
            poll.halt()
        proc.stop()
    rows = poll.rows
    w0, w1 = rec["window"]
    out = {"vmhwm_mb": mb(final.get("hwm")), "rss_sampled_max_mb": mb(max_of(rows, "rss")),
           "rss_before_load_mb": mb(median_in(rows, "rss", *rec["settle"])),
           "rss_load_max_mb": mb(max_of(rows, "rss", w0, w1)), "threads_max": max_of(rows, "threads"),
           "samples": len(rows), "load": load_summary(rec), "load_ok": rec["ok"]}
    if at_max:
        r = at_max["row"]
        out.update(at_max={"t_into_load_s": rnd(r["t"] - rec["settle"][1], 2), "rss_mb": mb(r["rss"]),
                           "rss_anon_mb": mb(r.get("anon")), "rss_file_mb": mb(r.get("file")),
                           "smaps_mb": {k: mb(v) for k, v in sorted(at_max["smaps"].items(), key=lambda kv: -kv[1])}})
        if rec.get("up_start"):
            out["at_max"]["half"] = "down half" if r["t"] < rec["up_start"] else "up half"
    if rec.get("idle"):
        out["rss_hold_median_mb"] = mb(median_in(rows, "rss", *rec["idle"]))
        out["rss_anon_hold_median_mb"] = mb(median_in(rows, "anon", *rec["idle"]))
        if at_mid:
            out["mid_hold"] = {"rss_mb": mb(at_mid["row"].get("rss")), "rss_anon_mb": mb(at_mid["row"].get("anon")),
                               "rss_file_mb": mb(at_mid["row"].get("file")),
                               "smaps_mb": {k: mb(v) for k, v in sorted(at_mid["smaps"].items(), key=lambda kv: -kv[1])}}
    return out


def heap_linux_heaptrack(ctx, cfg_path, env, rundir, load, slug):
    """The line-tables build under heaptrack, stopped with HEAP_STOP once the
    load is over (the hold's connections still open), then heaptrack_print."""
    exe = ctx["xrs"]
    for old in ctx["htdir"].glob(slug + ".*"):
        old.unlink()
    script = Proc([ctx["heaptrack"], "-o", ctx["htdir"] / slug, exe, "run", "-config", cfg_path],
                  rundir / "heaptrack.log", cwd=rundir, env=clean_env(env))
    pid, poll, t_base, killed = None, None, None, {}

    def kill():
        os.kill(pid, HEAP_STOP)
        killed["t"] = time.time()

    try:
        pid = find_pid_by_argv(Path(exe).name, str(cfg_path), seconds=30)
        if pid is None:
            raise LabError("xray-rust under heaptrack did not start:\n" + tail(rundir / "heaptrack.log"))
        try:
            t_base = proc_start_wall(pid)
        except (OSError, ValueError, IndexError):
            t_base = None
        if not wait_port(PORT_SOCKS, 60, script):
            raise LabError("xray-rust's SOCKS port never opened under heaptrack:\n" + tail(rundir / "heaptrack.log"))
        probe = ctx["host"].probe(HEAP_SOCKS, 60)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core under heaptrack in 60 s (%s):\n%s" % (
                probe.get("error"), tail(rundir / "heaptrack.log")))
        poll = FastPoll(lambda: heap_mem_linux(pid), "rss", period=0.05)
        poll.start()
        rec = heap_drive(ctx, load, rundir, at_end=kill)
    finally:
        if poll is not None:
            poll.halt()
        if pid and not killed:
            try:
                os.kill(pid, HEAP_STOP)
            except ProcessLookupError:
                pass
        try:
            script.p.wait(300)  # heaptrack_interpret finishes the file after the core is gone
        except subprocess.TimeoutExpired:
            pass
        script.stop()
    data = next((p for p in sorted(ctx["htdir"].glob(slug + ".*")) if p.suffix in (".zst", ".gz")), None)
    if data is None:
        raise LabError("heaptrack wrote no data file:\n" + tail(rundir / "heaptrack.log"))
    rep = heaptrack_report(ctx, data, slug)
    rep["data_bytes"] = data.stat().st_size
    rep["rss_sampled_max_mb"] = mb(max_of(poll.rows, "rss"))
    rep["load"] = load_summary(rec)
    rep["load_ok"] = rec["ok"]
    rep.update(heap_series_figures(rep.pop("massif"), t_base, rec))
    return rep


def heap_linux_row(ctx, line, load, cfg, cfg_notes):
    slug = re.sub(r"[^a-z0-9]+", "-", ("%s %s" % (line, load)).lower()).strip("-")
    row = {"platform": ctx["platform"], "line": line, "load": load, "settings": "mobile profile",
           "version": ctx["xrs_version"], "notes": list(cfg_notes), "error": None}
    env = xrs_mobile_env()
    base = ctx["out"] / "runs" / slug
    plain_dir, ht_dir = base / "plain", base / "heaptrack"
    for d in (plain_dir, ht_dir):
        d.mkdir(parents=True, exist_ok=True)
    cfgs = []
    for d in (plain_dir, ht_dir):
        p = d / "config.json"
        write_json(p, cfg)
        cfgs.append(p)
    ok, check_notes, _ = xrs_check(ctx["xrs"], cfgs[0], plain_dir / "config-check.json")
    row["notes"] += check_notes
    if not ok:
        raise LabError("xray-rust refused the config: " + ("; ".join(check_notes) or "no diagnostics"))
    for port in (PORT_SOCKS,):
        if not wait_port_free(port):
            raise LabError("port %d is still taken" % port)
    row["plain"] = heap_linux_plain(ctx, cfgs[0], env, plain_dir, load)
    if not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken after the plain run" % PORT_SOCKS)
    row["heaptrack"] = ht = heap_linux_heaptrack(ctx, cfgs[1], env, ht_dir, load, slug)
    pl = row["plain"]
    heap = (ht.get("peak_heap_bytes") or 0) / MIB
    am = pl.get("at_max") or {}
    fig = {"heap_peak_mb": rnd(heap, 2), "rss_peak_mb": pl.get("vmhwm_mb"),
           "rss_anon_at_peak_mb": am.get("rss_anon_mb"), "rss_file_at_peak_mb": am.get("rss_file_mb"),
           "rss_minus_heap_mb": rnd(pl["vmhwm_mb"] - heap, 1) if pl.get("vmhwm_mb") is not None and heap else None,
           "anon_minus_heap_mb": rnd(am["rss_anon_mb"] - heap, 1) if am.get("rss_anon_mb") is not None and heap else None,
           "heap_live_at_end_mb": rnd((ht.get("live_at_end_bytes") or 0) / MIB, 2),
           "heaptrack_rss_peak_mb": mb(ht.get("peak_rss_with_heaptrack_bytes")),
           "heap_peak_when": ht.get("heap_peak_half")}
    if load == "hold":
        fig.update(heap_hold_median_mb=ht.get("heap_hold_median_mb"), rss_hold_median_mb=pl.get("rss_hold_median_mb"),
                   rss_anon_hold_median_mb=pl.get("rss_anon_hold_median_mb"))
    row["figures"] = fig
    row["notes"].append("RSS from a run of the same binary without heaptrack (its VmHWM; anon and file at its largest "
                        "sample, polled every %d ms); the heap from the heaptrack run, which is slower, so its peak "
                        "need not fall at the same moment" % int(HEAP_POLL_S * 1000))
    if not (pl.get("load_ok") and ht.get("load_ok")):
        row["notes"].append("load: plain %s; heaptrack %s" % (pl.get("load"), ht.get("load")))
    return row


# ---- macOS: phys_footprint by category, from footprint and vmmap


class MacSnapper:
    """footprint and vmmap --summary of one process. The first invocation that
    works (with sudo -n when it needs root) is kept for the rest of the step,
    and every one tried is recorded."""

    CANDIDATES = {
        "footprint": (["footprint", "-f", "bytes", "-p", "{pid}"], ["footprint", "-p", "{pid}"],
                      ["footprint", "{pid}"]),
        "vmmap": (["vmmap", "--summary", "{pid}"], ["vmmap", "-summary", "{pid}"]),
    }
    MARK = {"footprint": "Footprint", "vmmap": "REGION TYPE"}

    def __init__(self):
        self.ways = {}
        self.tried = {}

    def run(self, tool, pid, path):
        if tool in self.ways:
            cands = [self.ways[tool]]
        else:
            base = self.CANDIDATES[tool]
            cands = list(base) + [["sudo", "-n"] + list(b) for b in base]
        for pre in cands:
            argv = [str(pid) if a == "{pid}" else a for a in pre]
            try:
                cp = subprocess.run(argv, capture_output=True, text=True, timeout=90)
            except (OSError, subprocess.SubprocessError) as e:
                self.tried.setdefault(tool, []).append({"argv": " ".join(argv), "error": str(e)[:300]})
                continue
            ok = cp.returncode == 0 and self.MARK[tool] in (cp.stdout or "")
            if tool not in self.ways:
                self.tried.setdefault(tool, []).append({"argv": " ".join(argv), "rc": cp.returncode, "worked": ok,
                                                        "said": ((cp.stderr or "") + (cp.stdout or ""))[:300]})
            if ok:
                self.ways[tool] = pre
                Path(path).write_text(cp.stdout + (("\n[stderr]\n" + cp.stderr) if (cp.stderr or "").strip() else ""))
                return cp.stdout
        Path(path).write_text("no invocation worked: %s\n" % json.dumps(self.tried.get(tool), indent=1))
        return None


MAC_UNIT = {"B": 1, "bytes": 1, "K": 1 << 10, "KB": 1 << 10, "M": 1 << 20, "MB": 1 << 20, "G": 1 << 30, "GB": 1 << 30,
            "T": 1 << 40, "TB": 1 << 40, "KiB": 1 << 10, "MiB": 1 << 20, "GiB": 1 << 30}
MAC_SIZE = re.compile(r"^(-?[\d.]+)([KMGT]i?B?|B)?$")


def mac_size(tokens, i):
    """One size at tokens[i] ('123', '123 KB', '1.5M'; Apple's tools count in
    KiB and MiB): (bytes, next index), or (None, i)."""
    m = MAC_SIZE.match(tokens[i])
    if not m:
        return None, i
    unit = m.group(2)
    if unit is None and i + 1 < len(tokens) and tokens[i + 1] in MAC_UNIT:
        unit, i = tokens[i + 1], i + 1
    return int(float(m.group(1)) * MAC_UNIT.get(unit or "B", 1)), i + 1


def footprint_parse(text):
    """footprint's category table: {footprint, columns, categories: {name:
    {column: bytes}}, total, aux}. Lenient: a format it cannot read leaves
    the categories empty and the text is in the artifact."""
    out = {"footprint": None, "columns": None, "categories": {}, "total": None, "aux": {}}
    m = re.search(r"Footprint:\s*([\d.]+)\s*([KMGT]?i?B|bytes)", text)
    if m:
        out["footprint"] = int(float(m.group(1)) * MAC_UNIT[m.group(2)])
    for k, v, u in re.findall(r"^\s*(phys_footprint(?:_peak)?):\s*([\d.]+)\s*([KMGT]?i?B|bytes)", text, re.M):
        out["aux"][k] = int(float(v) * MAC_UNIT[u])
    cols = None
    for line in text.splitlines():
        s = line.strip()
        if cols is None:
            if s.endswith("Category") and "Dirty" in s:
                cols = re.split(r"\s{2,}", s)[:-1]
                out["columns"] = cols
            continue
        if not s or not set(s.replace(" ", "")) - set("-="):
            continue
        tokens = s.split()
        vals, i = [], 0
        for _ in cols:
            if i >= len(tokens):
                break
            v, i2 = mac_size(tokens, i)
            if v is None:
                break
            vals.append(v)
            i = i2
        if len(vals) != len(cols) or i >= len(tokens):
            if out["categories"]:
                break
            continue
        name = " ".join(tokens[i:])
        if name.startswith("TOTAL"):
            out["total"] = dict(zip(cols, vals))
            break
        out["categories"][name] = dict(zip(cols, vals))
    return out


def vmmap_parse(text):
    """vmmap --summary: the physical footprint lines, the region table
    ({name: {column: bytes}}) and the MALLOC ZONE table (allocated bytes
    against dirty, the fragmentation)."""
    out = {"physical_footprint": None, "physical_footprint_peak": None, "regions": {}, "zones": {}}
    for key, label in (("physical_footprint", "Physical footprint:"),
                       ("physical_footprint_peak", "Physical footprint (peak):")):
        m = re.search(re.escape(label) + r"\s*([\d.]+[KMGT]?)\s*$", text, re.M)
        if m:
            out[key] = mac_size([m.group(1)], 0)[0]
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        if line.startswith("REGION TYPE") and idx > 0:
            cols = [c.lower() for c in lines[idx - 1].split()]
            rows = []
            seps = 0
            for r in lines[idx + 1:]:
                if r.startswith("==="):
                    seps += 1
                    if seps == 2:
                        continue
                    continue
                if not r.strip():
                    if seps >= 2:
                        break
                    continue
                rows.append((seps, r))
            for seps_at, r in rows:
                tokens = r.split()
                k = next((j for j in range(len(tokens)) if all(
                    MAC_SIZE.match(t) for t in tokens[j:j + len(cols)]) and len(tokens[j:j + len(cols)]) == len(cols)),
                    None)
                if not k:
                    continue
                name = " ".join(tokens[:k])
                vals = {}
                try:
                    for c, t in zip(cols, tokens[k:k + len(cols)]):
                        vals[c] = int(float(t)) if c == "region" else mac_size([t], 0)[0]
                except ValueError:
                    continue
                (out.setdefault("totals", {}) if seps_at >= 2 else out["regions"])[name] = vals
        if line.startswith("MALLOC ZONE") and idx > 0:
            names = ("virtual", "resident", "dirty", "swapped", "allocation_count", "bytes_allocated", "frag",
                     "frag_pct", "region_count")
            for r in lines[idx + 1:]:
                if r.startswith("==="):
                    continue
                if not r.strip():
                    break
                tokens = r.split()
                if len(tokens) < 1 + len(names):
                    continue
                name, vals = " ".join(tokens[:-len(names)]), tokens[-len(names):]
                rec = {}
                try:
                    for n, t in zip(names, vals):
                        if n in ("allocation_count", "region_count"):
                            rec[n] = int(t) if t.isdigit() else t
                        elif n == "frag_pct":
                            rec[n] = t
                        else:
                            rec[n] = mac_size([t], 0)[0]
                except ValueError:
                    continue
                out["zones"][name] = rec
    return out


def mac_group(name):
    """The split asked for: malloc by zone, stacks, dirty __DATA, the rest."""
    n = name.strip()
    up = n.upper()
    for zone in ("TINY", "SMALL", "LARGE", "MEDIUM", "NANO"):
        if up.startswith("MALLOC_" + zone):
            return "malloc " + zone.lower()
    if up.startswith("MALLOC"):
        return "malloc other"
    if up.startswith("STACK"):
        return "stacks"
    if (n.startswith("__") and not n.startswith(("__TEXT", "__LINKEDIT"))) or "__DATA" in n:
        return "dirty __DATA*"
    return "rest"


MAC_GROUPS = ("malloc tiny", "malloc small", "malloc large", "malloc medium", "malloc nano", "malloc other",
              "stacks", "dirty __DATA*", "rest")


def mac_split(snap):
    """Bytes of phys_footprint per group: footprint's dirty (and swapped or
    compressed, when it shows them) per category, else vmmap's dirty plus
    swapped per region type."""
    fp, vm = snap.get("footprint") or {}, snap.get("vmmap") or {}
    groups, source, rest = {g: 0 for g in MAC_GROUPS}, None, {}
    if fp.get("categories"):
        source = "footprint"
        for name, vals in fp["categories"].items():
            v = sum(b for c, b in vals.items() if re.search(r"dirty|swap|compress", c, re.I))
            groups[mac_group(name)] += v
            if mac_group(name) == "rest" and v:
                rest[name] = v
    elif vm.get("regions"):
        source = "vmmap"
        for name, vals in vm["regions"].items():
            v = (vals.get("dirty") or 0) + (vals.get("swapped") or 0)
            groups[mac_group(name)] += v
            if mac_group(name) == "rest" and v:
                rest[name] = v
    total = (fp.get("footprint") or vm.get("physical_footprint"))
    zones = vm.get("zones") or {}
    zt = zones.get("TOTAL")
    if zt is None and zones:
        zt = {k: sum(z.get(k) or 0 for z in zones.values() if isinstance(z.get(k), int))
              for k in ("bytes_allocated", "dirty", "frag")}
        zt["frag_pct"] = None
    return {"source": source, "total_mb": mb(total), "groups_mb": {g: mb(v) for g, v in groups.items()},
            "rest_mb": {k: mb(v) for k, v in sorted(rest.items(), key=lambda kv: -kv[1])[:8]},
            "malloc_allocated_mb": mb(zt.get("bytes_allocated")) if zt else None,
            "malloc_dirty_mb": mb(zt.get("dirty")) if zt else None,
            "malloc_frag_mb": mb(zt.get("frag")) if zt else None, "malloc_frag_pct": zt.get("frag_pct") if zt else None}


def mac_snapshot(snapper, pid, rundir, tag):
    fp_text = snapper.run("footprint", pid, rundir / ("footprint-%s.txt" % tag))
    vm_text = snapper.run("vmmap", pid, rundir / ("vmmap-%s.txt" % tag))
    snap = {"tag": tag, "t": time.time(), "footprint": None, "vmmap": None}
    for key, text, parse in (("footprint", fp_text, footprint_parse), ("vmmap", vm_text, vmmap_parse)):
        if text:
            try:
                snap[key] = parse(text)
            except Exception as e:  # the text is in the artifact; the row keeps the other tool
                snap.setdefault("parse_errors", {})[key] = "%s: %s" % (type(e).__name__, e)
    snap["split"] = mac_split(snap)
    return snap


def heap_mac_row(ctx, line, settings, load, binary, cfg, cfg_notes):
    slug = re.sub(r"[^a-z0-9]+", "-", ("%s %s %s" % (line, settings, load)).lower()).strip("-")
    rundir = ctx["out"] / "runs" / slug
    rundir.mkdir(parents=True, exist_ok=True)
    row = {"platform": ctx["platform"], "line": line, "load": load, "settings": settings,
           "version": ctx["xrs_version"] + (" + lab patches" if binary != ctx["xrs"] else ""),
           "notes": list(cfg_notes), "error": None}
    cfg_path = rundir / "config.json"
    write_json(cfg_path, cfg)
    ok, check_notes, _ = xrs_check(binary, cfg_path, rundir / "config-check.json")
    row["notes"] += check_notes
    if not ok:
        raise LabError("xray-rust refused the config: " + ("; ".join(check_notes) or "no diagnostics"))
    if not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken" % PORT_SOCKS)
    reader, snapper = ctx["reader"], ctx["snapper"]
    proc = Proc([binary, "run", "-config", cfg_path], rundir / "core.log", cwd=rundir, env=clean_env(xrs_mobile_env()))
    poll, snaps, bursts = None, [], []
    try:
        if not wait_port(PORT_SOCKS, 30, proc):
            raise LabError("xray-rust's SOCKS port never opened:\n" + tail(rundir / "core.log"))
        probe = ctx["host"].probe(HEAP_SOCKS, 40)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core in 40 s (%s):\n%s" % (probe.get("error"), tail(rundir / "core.log")))
        time.sleep(1.0)
        snaps.append(mac_snapshot(snapper, proc.pid, rundir, "idle"))
        poll = FastPoll(lambda: reader.read(proc.pid), "fp")
        poll.start()
        if load == "burst":
            rec = heap_drive(ctx, "burst", rundir)
            b0, b1 = rec["window"]
            peak0 = max_of(poll.rows, "fp", b0, b1)
            bursts.append({"freeze": None, "peak_mb": mb(peak0), "load": load_summary(rec)})
            if not peak0:
                raise LabError("no phys_footprint sample during the first burst")
            for n, at in enumerate(HEAP_FREEZE_AT, 1):
                start = (poll.last() or {}).get("fp") or 0
                # Near the first burst's peak, and above where this burst
                # starts (what the first one left behind) by a margin, so it
                # fires inside the burst, never at its first sample.
                threshold = max(at * peak0, start + 0.5 * max(0, peak0 - start), start + MIB / 4)
                hit = {}

                def freeze(r, n=n, hit=hit):
                    hit["started"] = True
                    t = time.time()
                    try:
                        os.kill(proc.pid, signal.SIGSTOP)
                        try:
                            s = mac_snapshot(snapper, proc.pid, rundir, "burst%d-frozen" % n)
                            try:
                                s["fp_frozen_mb"] = mb(reader.read(proc.pid)["fp"])
                            except Exception:
                                pass
                        finally:
                            os.kill(proc.pid, signal.SIGCONT)
                        s.update(frozen_at=t, trigger_fp_mb=mb(r["fp"]), frozen_s=rnd(time.time() - t, 2))
                        hit["snap"] = s
                    finally:
                        hit["done"] = True

                poll.arm(threshold, freeze)
                bdown, bup, win = run_burst(ctx["host"], HEAP_SOCKS)
                poll.disarm()
                deadline = time.time() + 120
                while hit.get("started") and not hit.get("done") and time.time() < deadline:
                    time.sleep(0.05)
                one = {"freeze": "at %.1f MiB (%.2f of %.1f, from %.1f at the start)" % (
                    threshold / MIB, at, peak0 / MIB, start / MIB),
                    "peak_mb": mb(max_of(poll.rows, "fp", *win)),
                    "load": load_summary({"loadgen": [bdown, bup]})}
                bursts.append(one)
                if hit.get("snap"):
                    s = hit["snap"]
                    up = bdown.get("host_up_start")
                    s["half"] = ("down half" if up and s["frozen_at"] < up else "up half") if up else None
                    s["into_burst_s"] = rnd(s["frozen_at"] - win[0], 2)
                    snaps.append(s)
                    one["froze"] = True
                    break
                time.sleep(1.0)
        else:
            rec = heap_drive(ctx, "hold", rundir,
                             mid=lambda: snaps.append(mac_snapshot(snapper, proc.pid, rundir, "hold-mid")))
            row["hold"] = {"fp_median_mb": mb(median_in(poll.rows, "fp", *rec["idle"])),
                           "fp_max_mb": mb(max_of(poll.rows, "fp", *rec["idle"])), "load": load_summary(rec),
                           "load_ok": rec["ok"]}
        final = reader.read(proc.pid)
    finally:
        if poll is not None:
            poll.halt()
        try:
            os.kill(proc.pid, signal.SIGCONT)
        except (ProcessLookupError, PermissionError):
            pass
        proc.stop()
    row["bursts"] = bursts
    row["fp_lifetime_peak_mb"] = mb(final.get("lifemax"))
    row["fp_sampled_max_mb"] = mb(max_of(poll.rows, "fp"))
    best = [s for s in snaps if s["tag"] != "idle"]
    best = best[-1] if best else None
    row["idle"] = {"split": snaps[0]["split"]} if snaps else None
    if best:
        row["snapshot"] = {k: best.get(k) for k in ("tag", "half", "into_burst_s", "trigger_fp_mb", "fp_frozen_mb",
                                                     "frozen_s", "split")}
        row["snapshot"]["vmmap_physical_footprint_mb"] = mb((best.get("vmmap") or {}).get("physical_footprint"))
        row["snapshot"]["footprint_mb"] = mb((best.get("footprint") or {}).get("footprint"))
        row["snapshot"]["footprint_columns"] = (best.get("footprint") or {}).get("columns")
    elif load == "burst":
        row["notes"].append("no burst crossed the freeze threshold, so there is no snapshot at the peak; the idle "
                            "snapshot is in row.idle")
    row["snapshots_files"] = sorted(p.name for p in rundir.glob("*.txt"))
    return row


# ---- tables


def heap_cell(v, digits=1):
    return "-" if v is None else ("%.*f" % (digits, v) if isinstance(v, float) else str(v))


def heap_text(doc):
    """The job log's tables: one line per run, then each run's allocation sites."""
    out = []
    linux = [r for r in doc["rows"] if r.get("platform", "").startswith("linux")]
    mac = [r for r in doc["rows"] if r.get("platform", "").startswith("macos")]
    if linux:
        out.append("\nheap-profile, Linux (heaptrack; RSS from a run without it), %s, mobile profile, MiB" %
                   doc.get("xray_rust_version", "xray-rust"))
        out.append("%-31s %-6s %8s %7s %8s %8s %9s %9s %9s %8s %-12s" % (
            "line", "load", "heap pk", "RSS pk", "anon@pk", "file@pk", "RSS-heap", "anon-heap", "heap@end", "ht RSS",
            "heap peak"))
        for r in linux:
            if r.get("error"):
                out.append("%-31s %-6s FAILED: %s" % (r["line"][:31], r["load"], r["error"].splitlines()[0][:160]))
                continue
            f = r["figures"]
            out.append("%-31s %-6s %8s %7s %8s %8s %9s %9s %9s %8s %-12s" % (
                r["line"][:31], r["load"], heap_cell(f.get("heap_peak_mb"), 2), heap_cell(f.get("rss_peak_mb")),
                heap_cell(f.get("rss_anon_at_peak_mb")), heap_cell(f.get("rss_file_at_peak_mb")),
                heap_cell(f.get("rss_minus_heap_mb")), heap_cell(f.get("anon_minus_heap_mb")),
                heap_cell(f.get("heap_live_at_end_mb"), 2), heap_cell(f.get("heaptrack_rss_peak_mb")),
                f.get("heap_peak_when") or "-"))
            if r["load"] == "hold":
                out.append("    hold idle: heap median %s, RSS median %s, RSS anon median %s" % (
                    heap_cell(f.get("heap_hold_median_mb"), 2), heap_cell(f.get("rss_hold_median_mb")),
                    heap_cell(f.get("rss_anon_hold_median_mb"))))
            sm = (r["plain"].get("at_max") or {}).get("smaps_mb") or {}
            if sm:
                out.append("    RSS at its largest sample by kind: " + ", ".join("%s %s" % (k, v) for k, v in sm.items()
                                                                                  if v))
        for r in linux:
            if r.get("error"):
                continue
            ht = r["heaptrack"]
            for key, label, n in (("sites_at_peak", "at the heap's peak", HEAP_TOP),
                                  ("sites_live_at_end", "still live when the core was stopped", 10)):
                if key == "sites_live_at_end" and r["load"] != "hold":
                    continue
                total = ht.get("peak_heap_bytes") if key == "sites_at_peak" else ht.get("live_at_end_bytes")
                out.append("\n  %s / %s: top %d allocation sites %s (%.2f MiB; the %s listed backtraces hold %s%%)" % (
                    r["line"], r["load"], n, label, (total or 0) / MIB,
                    ht.get("traces_listed_at_peak") if key == "sites_at_peak" else ht.get("traces_listed_live_at_end"),
                    ht.get("listed_share_of_peak") if key == "sites_at_peak" else ht.get("listed_share_live_at_end")))
                out.append("  %3s %7s %6s %8s  %-64s %s" % ("#", "MiB", "%", "calls", "site (file:line)", "function; via"))
                for k, s in enumerate(ht.get(key) or [], 1):
                    if k > n:
                        break
                    via = s["via"][0]["call"] if s.get("via") else ""
                    at = s["alloc_at"][0]["frame"] if s.get("alloc_at") else ""
                    out.append("  %3d %7.2f %6s %8d  %-64s %s%s%s" % (
                        k, s["bytes"] / MIB, heap_cell(s.get("share")), s["calls"], s["site"][-64:],
                        s["function"][-70:], ("; via " + via[-90:]) if via else "",
                        ("; asked in " + at[-90:]) if at and at != via else ""))
    if mac:
        out.append("\nheap-profile, macOS (footprint / vmmap of the core, frozen near its peak), %s, MiB of "
                   "phys_footprint" % doc.get("xray_rust_version", "xray-rust"))
        head = ("line", "settings", "load", "snap", "peak", "when") + MAC_GROUPS + ("m-alloc", "m-frag")
        out.append("%-29s %-15s %-5s %6s %6s %-9s " % head[:6] + " ".join("%7s" % g.replace("malloc ", "m-")[:7]
                                                                           for g in head[6:]))
        for r in mac:
            if r.get("error"):
                out.append("%-29s %-15s %-5s FAILED: %s" % (r["line"][:29], r["settings"][:15], r["load"],
                                                            r["error"].splitlines()[0][:150]))
                continue
            snap = r.get("snapshot") or {}
            sp = snap.get("split") or {}
            g = sp.get("groups_mb") or {}
            peak = r.get("fp_lifetime_peak_mb") if r["load"] == "burst" else (r.get("hold") or {}).get("fp_max_mb")
            when = snap.get("half") or ("mid-idle" if r["load"] == "hold" and snap else "-")
            out.append("%-29s %-15s %-5s %6s %6s %-9s " % (
                r["line"][:29], r["settings"][:15], r["load"], heap_cell(sp.get("total_mb")), heap_cell(peak),
                str(when)[:9]) + " ".join("%7s" % heap_cell(g.get(k)) for k in MAC_GROUPS) +
                " %7s %7s" % (heap_cell(sp.get("malloc_allocated_mb")), heap_cell(sp.get("malloc_frag_mb"))))
            if sp.get("rest_mb"):
                out.append("    rest: " + ", ".join("%s %s" % kv for kv in list(sp["rest_mb"].items())[:6]))
    return "\n".join(out)


def heap_markdown(doc):
    """The job summary: the same tables, ten sites per run."""
    out = ["## Where xray-rust's memory goes (lab.py heap-profile)", ""]
    linux = [r for r in doc["rows"] if r.get("platform", "").startswith("linux")]
    mac = [r for r in doc["rows"] if r.get("platform", "").startswith("macos")]
    if linux:
        out += ["Linux: heaptrack's heap peak against the RSS of a run without heaptrack (MiB).", "",
                "| line | load | heap peak | RSS peak | anon at peak | file at peak | RSS - heap | anon - heap | "
                "heap live at end | heap peak in |", "|---|---|---|---|---|---|---|---|---|---|"]
        for r in linux:
            if r.get("error"):
                out.append("| %s | %s | FAILED: %s |||||||" % (r["line"], r["load"],
                                                               r["error"].splitlines()[0][:200].replace("|", "/")))
                continue
            f = r["figures"]
            out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                r["line"], r["load"], heap_cell(f.get("heap_peak_mb"), 2), heap_cell(f.get("rss_peak_mb")),
                heap_cell(f.get("rss_anon_at_peak_mb")), heap_cell(f.get("rss_file_at_peak_mb")),
                heap_cell(f.get("rss_minus_heap_mb")), heap_cell(f.get("anon_minus_heap_mb")),
                heap_cell(f.get("heap_live_at_end_mb"), 2), f.get("heap_peak_when") or "-"))
        for r in linux:
            if r.get("error"):
                continue
            ht = r["heaptrack"]
            out += ["", "%s / %s, top 10 sites at the heap's peak (%.2f MiB):" % (
                r["line"], r["load"], (ht.get("peak_heap_bytes") or 0) / MIB), "",
                "| # | MiB | % | calls | site | function | via |", "|---|---|---|---|---|---|---|"]
            for k, s in enumerate((ht.get("sites_at_peak") or [])[:10], 1):
                out.append("| %d | %.2f | %s | %d | `%s` | `%s` | %s |" % (
                    k, s["bytes"] / MIB, heap_cell(s.get("share")), s["calls"], s["site"], s["function"][-80:],
                    ("`%s`" % s["via"][0]["call"][-100:]) if s.get("via") else ""))
    if mac:
        out += ["", "macOS: phys_footprint of the core frozen near its peak (MiB), by footprint's categories.", "",
                "| line | settings | load | snapshot | peak | when | " + " | ".join(MAC_GROUPS) +
                " | malloc allocated | malloc frag |", "|" + "---|" * (6 + len(MAC_GROUPS) + 2)]
        for r in mac:
            if r.get("error"):
                out.append("| %s | %s | %s | FAILED: %s |" % (r["line"], r["settings"], r["load"],
                                                             r["error"].splitlines()[0][:200].replace("|", "/")))
                continue
            snap = r.get("snapshot") or {}
            sp = snap.get("split") or {}
            g = sp.get("groups_mb") or {}
            peak = r.get("fp_lifetime_peak_mb") if r["load"] == "burst" else (r.get("hold") or {}).get("fp_max_mb")
            out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                r["line"], r["settings"], r["load"], heap_cell(sp.get("total_mb")), heap_cell(peak),
                snap.get("half") or ("mid-idle" if r["load"] == "hold" and snap else "-"),
                " | ".join(heap_cell(g.get(k)) for k in MAC_GROUPS), heap_cell(sp.get("malloc_allocated_mb")),
                heap_cell(sp.get("malloc_frag_mb"))))
    return "\n".join(out) + "\n"


def cmd_heap_profile(a):
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = Path(a.bin).resolve()
    budget = Budget(a.budget_min)
    is_mac = sys.platform == "darwin"
    platform = a.platform
    doc = {"what": "where xray-rust's memory goes: %s" % (
        "phys_footprint by category (footprint, vmmap --summary) of the core frozen near its peak" if is_mac else
        "the heap by allocation site (heaptrack) beside RSS by kind (a run without heaptrack)"),
        "platform": platform, "runner": " ".join(os.uname()), "loads": {
            "burst": "the rows' burst: %d downloads of %d bytes at once, a %.0f s pause, %d uploads of %d "
                     "bytes at once, a %.0f s tail" % (BURST_FLOWS, BURST_EACH_DOWN, PAUSE_S, BURST_FLOWS, BURST_EACH_UP, BURST_TAIL_S),
            "hold": "%d connections opened at once, a GET of %d bytes on each, %d s idle, a second GET on each, "
                    "each answer checked against the page's bytes" % (HEAP_HOLD_N, HEAP_HOLD_GET, HEAP_HOLD_S)},
        "settings": "mobile profile: %s" % json.dumps(xrs_mobile_env(), sort_keys=True), "rows": []}
    rows = doc["rows"]
    xrs_dir = Path((a.xray_rust if is_mac else a.xray_rust_lines) or (out / "no-xray-rust-given")).resolve()
    xrs = xrs_dir / "xray-rust"
    try:
        info = load_json(xrs_dir / "xray-rust.build.json")
    except (OSError, ValueError):
        info = {}
    doc["xray_rust_build"] = info
    doc["xray_rust_version"] = "xray-rust %s %s%s" % (info.get("ref") or info.get("tag", "?"),
                                                      str(info.get("commit", "?"))[:12],
                                                      " + line tables" if info.get("profile_overrides") else "")
    ctx = {"out": out, "bin": b, "loadgen": b / "loadgen", "host": LocalHost(b / "loadgen"), "platform": platform,
           "xrs": xrs, "xrs_version": doc["xray_rust_version"]}
    lines = [HEAP_LINE_NAMES[n] for n in a.lines.split(",")] if a.lines else list(HEAP_LINES)
    loads = ("burst", "hold")
    plan = [(line, "mobile profile", load) for line in lines for load in loads]
    why_not = None
    if not xrs.exists():
        why_not = "the xray-rust %s was not built: %s missing (%s)" % (
            "build" if is_mac else "build with line tables", xrs, tail(out.parent / (
                "xray-rust-main-build.log" if is_mac else "xray-rust-lines-build.log"), 6).replace("\n", " | "))
    if is_mac:
        ctx["reader"] = MacReader()
        ctx["snapper"] = MacSnapper()
        for tool, argv in (("footprint", ["footprint", "--help"]), ("vmmap", ["vmmap", "--help"]),
                           ("sw_vers", ["sw_vers"])):
            try:
                cp = subprocess.run(argv, capture_output=True, text=True, timeout=30)
                (out / ("%s.help.txt" % tool)).write_text((cp.stdout or "") + (cp.stderr or ""))
            except (OSError, subprocess.SubprocessError) as e:
                (out / ("%s.help.txt" % tool)).write_text("did not run: %s\n" % e)
    else:
        ctx["heaptrack"] = shutil.which("heaptrack")
        ctx["heaptrack_print"] = shutil.which("heaptrack_print")
        ctx["htdir"] = Path(a.heaptrack_out or (out / "heaptrack")).resolve()
        ctx["htdir"].mkdir(parents=True, exist_ok=True)
        if why_not is None and not (ctx["heaptrack"] and ctx["heaptrack_print"]):
            why_not = "heaptrack is not installed (the workflow's apt step)"
        if ctx["heaptrack"]:
            doc["heaptrack_version"] = first_line([ctx["heaptrack"], "--version"])
            doc["heaptrack_print_version"] = first_line([ctx["heaptrack_print"], "--version"])
    if why_not:
        for line, settings, load in plan:
            rows.append({"platform": platform, "line": line, "settings": settings, "load": load, "error": why_not})
        write_json(out / "heap-profile.json", doc)
        print(heap_text(doc), flush=True)
        return 1
    for port in (PORT_PAGE, PORT_DEST, PORT_REALITY, PORT_XHTTP, PORT_WEBTUNNEL, PORT_SOCKS):
        if not wait_port_free(port, 15):
            raise LabError("port %d is still taken" % port)
    servers = Servers(out, b / "loadgen", b / "xray-26.4.25" / "xray")
    try:
        servers.start()
        doc["server"] = servers.version
        loop = "127.0.0.1"
        hy2_pin = cert_sha256(servers.dir / "hy2.crt")
        try:
            wt_pin = cert_sha256(servers.dir / "webtunnel.crt")
        except (OSError, LabError):
            wt_pin = "0" * 64
        build = {"vless-reality-vision": lambda: xrs_reality(loop, servers.pub),
                 "hysteria2-salamander": lambda: xrs_hy2(loop, hy2_pin),
                 "vless-xhttp-reality packet-up": lambda: xrs_xhttp(loop, servers.pub),
                 WEBTUNNEL: lambda: xrs_webtunnel(loop, wt_pin)}
        for line, settings, load in plan:
            base = {"platform": platform, "line": line, "settings": settings, "load": load}
            if not budget.allows(150):
                rows.append(dict(base, error="skipped: out of the %.0f min budget" % a.budget_min))
                continue
            if line == WEBTUNNEL and servers.wt_error:
                rows.append(dict(base, error="the WebTunnel server: " + servers.wt_error))
                continue
            log("heap-profile:", line, "|", settings, "|", load)
            t0 = time.time()
            try:
                cfg, cfg_notes = build[line]()
                if is_mac:
                    row = heap_mac_row(ctx, line, settings, load, xrs, cfg, cfg_notes)
                else:
                    row = heap_linux_row(ctx, line, load, cfg, cfg_notes)
            except Exception as e:
                traceback.print_exc()
                row = dict(base, error="%s: %s" % (type(e).__name__, e))
            row["seconds"] = rnd(time.time() - t0, 1)
            rows.append(row)
            write_json(out / "heap-profile.json", doc)
            print(heap_text(dict(doc, rows=[row])), flush=True)
    finally:
        servers.stop()
        if is_mac:
            doc["snapshot_tools"] = {"used": {k: " ".join(v) for k, v in ctx["snapper"].ways.items()},
                                     "tried": ctx["snapper"].tried}
        write_json(out / "heap-profile.json", doc)
    print(heap_text(doc), flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(heap_markdown(doc))
    return 0


# ---------------------------------------------------------------- alloc-rows
#
# Which allocator setup keeps the footprint down (macOS, phys_footprint, what
# jetsam holds the iPhone extension to). The heap profile found that after a
# burst most of malloc's dirty pages are free but kept, that the footprint
# never comes down, and that a second burst starts from the first one's peak.
# These rows run one build, the fork's XHTTP upload fix with the lab's
# allocator patch (xray-rust-patches/lab-allocator-switch.patch, applied by
# rust-core-build.sh), in the mobile profile, under five setups chosen by
# environment variables (crates/xray-cli/src/lab_alloc.rs in the patch):
#   a  the system allocator as the CLI has it;
#   b  the system allocator, and malloc_zone_pressure_relief(NULL, 0) from a
#      thread every second for as long as the process runs;
#   c  the system allocator, and the same call once when traffic stops: a
#      thread reads the process's CPU time every 250 ms and calls it after a
#      quiet 250 ms (under 2.5 ms of CPU) that follows a busy one;
#   d  mimalloc 3 (the mimalloc crate) as the global allocator, purging freed
#      memory at once (MIMALLOC_PURGE_DELAY=0);
#   e  the same with mimalloc's default purge delay (1000 ms);
#   f  the system allocator with MallocLargeCache=0: xzone malloc without
#      deferred reclaim, as iOS runs it by default (empty chunks are madvised
#      when freed; on macOS they wait in the kernel's reclaim ring).
# A run: start, 3 s idle, a burst (32 downloads of 4 MB at once, 2 s, 32
# uploads of 2 MB at once), 5 s, the same burst again, 5 s, footprint and
# vmmap of the process, then the single transfers (500 MB down, 2 s, 100 MB
# up) for throughput and CPU per GB, 5 s. phys_footprint is polled every
# ALLOC_POLL_S; the peak is the kernel's lifetime maximum, read after the
# bursts. Every setup on every line runs twice, the setups interleaved.

ALLOC_SETUPS = (
    ("a", "system", {}, ("lab: allocator system", "lab: relief none"),
     "the system allocator as the CLI has it (XRS_LAB_ALLOC, XRS_LAB_RELIEF unset)"),
    ("b", "system + relief every 1 s", {"XRS_LAB_RELIEF": "interval", "XRS_LAB_RELIEF_MS": "1000"},
     ("lab: allocator system", "lab: relief interval"),
     "the system allocator; a thread calls malloc_zone_pressure_relief(NULL, 0) every 1000 ms while the process runs"),
    ("c", "system + relief when traffic stops", {"XRS_LAB_RELIEF": "idle"},
     ("lab: allocator system", "lab: relief idle"),
     "the system allocator; a thread calls malloc_zone_pressure_relief(NULL, 0) once after a 250 ms window under "
     "2.5 ms of process CPU that follows a busy one (250 to 500 ms after traffic stops), armed again by traffic"),
    ("d", "mimalloc, purge at once", {"XRS_LAB_ALLOC": "mimalloc", "MIMALLOC_PURGE_DELAY": "0"},
     ("lab: allocator mimalloc", "option 'purge_delay': 0 "),
     "mimalloc 3 (crate mimalloc 0.1.52, libmimalloc-sys 0.1.49) as the global allocator, MIMALLOC_PURGE_DELAY=0: "
     "freed arena memory is decommitted at once (MADV_FREE_REUSABLE on macOS)"),
    ("e", "mimalloc, default purge", {"XRS_LAB_ALLOC": "mimalloc"},
     ("lab: allocator mimalloc", "option 'purge_delay': 1000 "),
     "mimalloc 3 as the global allocator with its default purge delay (1000 ms; purges run from inside later "
     "allocator calls)"),
    ("f", "system, iOS reclaim settings", {"MallocLargeCache": "0"},
     ("lab: allocator system", "lab: relief none"),
     "the system allocator with MallocLargeCache=0, which xzone malloc reads on release systems: no deferred "
     "reclaim for tiny, small or large blocks and no huge cache, as xzone malloc is set up by default on iOS "
     "(libmalloc-812 xzone_malloc.h:107-115); empty chunks are madvised when freed instead of waiting in the "
     "kernel's reclaim ring"),
)
# Every alloc row sets MallocReportConfig=1: libmalloc then prints its config
# (xzone or magazine, and xzone's deferred reclaim switches) to stderr, and the
# row keeps these lines of it.
ALLOC_MALLOC_CONFIG_KEYS = ("Enable Xzone Malloc", "Xzone Enablement Reason", "Defer Tiny", "Defer Small",
                            "Defer Large", "Huge Cache Size", "Reclaim Buffer Count")
ALLOC_SETTLE_S = 3.0
ALLOC_BURSTS = 2
ALLOC_MARKS = (0.5, 1.0, 2.0, 5.0)
ALLOC_POLL_S = 0.02
# --tail-s: after the last burst the core stays idle that long before the
# snapshot, and the footprint is read at these marks (seconds after the
# burst's last upload). On macOS 26 the default allocator is xzone malloc,
# which hands freed pages to the kernel's deferred reclaim ring; the kernel
# looks at the ring at most every 10 s on macOS, and only when malloc updates
# it, then trims what stayed unused (xnu osfmk/vm/vm_reclaim.c), so a 5 s
# window cannot show it. iOS runs xzone without the ring by default (setup f).
ALLOC_TAIL_MARKS = (5, 10, 15, 20, 30, 40, 60, 90)


def relief_summary(path, windows):
    """The relief log (one JSON line per call): how many calls, what they
    released and what they cost, overall and inside each named window."""
    calls = []
    try:
        for line in Path(path).read_text().splitlines():
            try:
                calls.append(json.loads(line))
            except ValueError:
                pass
    except OSError:
        return None
    if not calls:
        return {"calls": 0}
    out = {"calls": len(calls), "released_mb": mb(sum(c.get("released") or 0 for c in calls)),
           "cpu_ms": rnd(sum(c.get("cpu_us") or 0 for c in calls) / 1000.0, 1),
           "wall_ms": rnd(sum(c.get("wall_us") or 0 for c in calls) / 1000.0, 1),
           "max_wall_ms": rnd(max(c.get("wall_us") or 0 for c in calls) / 1000.0, 1),
           "largest_released_mb": mb(max(c.get("released") or 0 for c in calls))}
    for name, (t0, t1) in windows.items():
        inside = [c for c in calls if t0 <= c.get("t", 0) <= t1]
        out[name] = {"calls": len(inside), "released_mb": mb(sum(c.get("released") or 0 for c in inside)),
                     "cpu_ms": rnd(sum(c.get("cpu_us") or 0 for c in inside) / 1000.0, 1)}
    out["first_calls"] = [{"t": c.get("t"), "why": c.get("why"), "released_mb": mb(c.get("released")),
                           "cpu_ms": rnd((c.get("cpu_us") or 0) / 1000.0, 2),
                           "in_use_mb": mb(c.get("in_use")), "allocated_mb": mb(c.get("allocated"))} for c in calls[:12]]
    return out


def alloc_run(ctx, line, setup, run_no):
    key, name, env_add, expect_lines, what = setup
    slug = re.sub(r"[^a-z0-9]+", "-", ("%s %s run%d" % (line, key, run_no)).lower()).strip("-")
    rundir = ctx["out"] / "runs" / slug
    rundir.mkdir(parents=True, exist_ok=True)
    row = {"platform": ctx["platform"], "line": line, "setup": key, "setup_name": name, "run": run_no,
           "version": ctx["version"], "env": dict(env_add), "notes": [what], "error": None}
    cfg, cfg_notes = ctx["build"][line]()
    row["notes"] += cfg_notes
    cfg_path = rundir / "config.json"
    write_json(cfg_path, cfg)
    binary, reader, host = ctx["xrs"], ctx["reader"], ctx["host"]
    ok, check_notes, _ = xrs_check(binary, cfg_path, rundir / "config-check.json")
    row["notes"] += check_notes
    if not ok:
        raise LabError("xray-rust refused the config: " + ("; ".join(check_notes) or "no diagnostics"))
    if not wait_port_free(PORT_SOCKS):
        raise LabError("port %d is still taken" % PORT_SOCKS)
    env = dict(xrs_mobile_env(), **env_add)
    env["XRS_LAB_RELIEF_LOG"] = str(rundir / "relief.jsonl")
    env["MallocReportConfig"] = "1"
    row["env_full"] = {k: v for k, v in env.items()}
    proc = Proc([binary, "run", "-config", cfg_path], rundir / "core.log", cwd=rundir, env=clean_env(env))
    poll, rows, notes = None, [], []
    rec = {"bursts": []}
    try:
        if not wait_port(PORT_SOCKS, 30, proc):
            raise LabError("xray-rust's SOCKS port never opened:\n" + tail(rundir / "core.log"))
        probe = host.probe(HEAP_SOCKS, 40)
        if not probe.get("ok"):
            raise LabError("no byte crossed the core in 40 s (%s):\n%s" % (probe.get("error"), tail(rundir / "core.log")))
        said = (rundir / "core.log").read_text(errors="replace")
        absent = [e for e in expect_lines if e not in said]
        if absent:
            raise LabError("the core did not say %s, so the setup is not in force:\n%s" % (
                " / ".join(repr(e) for e in absent), tail(rundir / "core.log", 40)))
        row["core_said"] = [ln for ln in said.splitlines() if ln.startswith("lab: ") or "mimalloc: v" in ln
                            or "'purge_delay'" in ln or "'purge_decommits'" in ln][:8]
        row["malloc_config"] = {}
        for ln in said.splitlines():
            key, _, value = ln.strip().partition(": ")
            if key in ALLOC_MALLOC_CONFIG_KEYS and key not in row["malloc_config"]:
                row["malloc_config"][key] = value.strip()
        poll = FastPoll(lambda: reader.read(proc.pid), "fp", period=ALLOC_POLL_S)
        poll.start()
        t_settle = time.time()
        time.sleep(ALLOC_SETTLE_S)
        rec["settle"] = (t_settle, time.time())
        for n in range(1, ALLOC_BURSTS + 1):
            b0 = time.time()
            bdown = host.loadgen_run("burst-get", BURST_EACH_DOWN, HEAP_SOCKS, 150, BURST_FLOWS)
            time.sleep(PAUSE_S)
            up0 = time.time()
            bup = host.loadgen_run("burst-put", BURST_EACH_UP, HEAP_SOCKS, 150, BURST_FLOWS)
            b1 = time.time()
            time.sleep(max(ALLOC_MARKS) + 0.1)
            if n == ALLOC_BURSTS and ctx.get("tail_s"):
                time.sleep(max(0.0, b1 + ctx["tail_s"] + 0.1 - time.time()))
            rec["bursts"].append({"b0": b0, "up0": up0, "b1": b1, "down": bdown, "up": bup})
        rec["lifemax_bursts"] = reader.read(proc.pid).get("lifemax")
        t_snap = time.time()
        snap = mac_snapshot(ctx["snapper"], proc.pid, rundir, "after-bursts")
        rec["snapshot"] = dict(snap["split"], seconds=rnd(time.time() - t_snap, 2),
                               vmmap_physical_footprint_mb=mb((snap.get("vmmap") or {}).get("physical_footprint")))
        time.sleep(1.0)
        h0 = time.time()
        down = host.loadgen_run("get", DOWN_BYTES, HEAP_SOCKS, 240)
        h1 = time.time()
        time.sleep(PAUSE_S)
        h2 = time.time()
        up = host.loadgen_run("put", UP_BYTES, HEAP_SOCKS, 180)
        h3 = time.time()
        time.sleep(max(ALLOC_MARKS) + 0.1)
        rec.update(down=down, up=up, steady=(h0, h3), steady_windows=(window_of(down, (h0, h1)), window_of(up, (h2, h3))),
                   end=time.time())
        rec["final"] = reader.read(proc.pid)
        rows = poll.halt()
        poll = None
        rows = reader.finish(rows, proc.pid, notes)
    finally:
        if poll is not None:
            poll.halt()
        proc.stop()
    row["notes"] += notes
    st = rec["settle"]
    row["fp_idle_mb"] = mb(median_in(rows, "fp", st[0] + 1.0, st[1]))
    row["fp_peak_bursts_mb"] = mb(rec.get("lifemax_bursts"))
    row["fp_lifetime_peak_mb"] = mb((rec.get("final") or {}).get("lifemax"))
    row["bursts"] = []
    for b in rec["bursts"]:
        moved = (b["down"].get("bytes") or 0) + (b["up"].get("bytes") or 0)
        cpu = delta(rows, "cpu", b["b0"], b["b1"])
        row["bursts"].append({
            "fp_start_mb": mb(at_or_before(rows, "fp", b["b0"])),
            "fp_peak_sampled_mb": mb(max_of(rows, "fp", b["b0"], b["b1"] + 0.5)),
            "fp_down_half_peak_mb": mb(max_of(rows, "fp", b["b0"], b["up0"])),
            "fp_up_half_peak_mb": mb(max_of(rows, "fp", b["up0"], b["b1"] + 0.5)),
            "fp_after_mb": {str(m): mb(at_or_after(rows, "fp", b["b1"] + m)) for m in ALLOC_MARKS},
            "mbps_down": rnd(b["down"].get("mbps")) if b["down"].get("ok") else None,
            "mbps_up": rnd(b["up"].get("mbps")) if b["up"].get("ok") else None,
            "ok_flows_down_up": [b["down"].get("ok_flows", 0), b["up"].get("ok_flows", 0)],
            "cpu_s_per_gb": rnd(cpu_per_gb(cpu, moved), 2) if moved and cpu is not None else None,
            "errors": [("burst download: " + b["down"].get("error", "")) if not b["down"].get("ok") else "",
                       ("burst upload: " + b["up"].get("error", "")) if not b["up"].get("ok") else ""],
        })
    steady = base_row(ctx["platform"], "", "", "", "", "")
    errors = transfer_figures(steady, rec.get("down") or {}, rec.get("up") or {}, rows, rec["steady_windows"])
    row["steady"] = {"mbps_down": steady["mbps_down"], "mbps_up": steady["mbps_up"],
                     "cpu_s_per_gb": steady["cpu_s_per_gb"],
                     "cpu_s_per_gb_down": steady["extra"].get("cpu_s_per_gb_down"),
                     "cpu_s_per_gb_up": steady["extra"].get("cpu_s_per_gb_up"),
                     "fp_peak_mb": mb(max_of(rows, "fp", *rec["steady"])),
                     "fp_after_mb": mb(at_or_after(rows, "fp", rec["steady"][1] + max(ALLOC_MARKS))),
                     "errors": errors}
    row["rss_peak_mb"] = mb(max_of(rows, "rss"))
    row["snapshot_after_bursts"] = rec.get("snapshot")
    windows = {"in_bursts": (rec["bursts"][0]["b0"], rec["bursts"][-1]["b1"] + max(ALLOC_MARKS)),
               "in_steady": rec["steady"]}
    for i, b in enumerate(rec["bursts"], 1):
        windows["after_burst_%d" % i] = (b["b1"], b["b1"] + max(ALLOC_MARKS))
    if ctx.get("tail_s"):
        last = rec["bursts"][-1]["b1"]
        tail_s = ctx["tail_s"]
        row["tail"] = {
            "seconds": tail_s,
            "fp_after_last_burst_mb": {str(m): mb(at_or_after(rows, "fp", last + m))
                                       for m in ALLOC_TAIL_MARKS if m <= tail_s},
            "fp_min_mb": mb(min((r["fp"] for r in rows if last <= r["t"] <= last + tail_s), default=None)),
            "fp_each_second_mb": [mb(at_or_after(rows, "fp", last + k)) for k in range(0, int(tail_s) + 1)],
        }
    row["relief"] = relief_summary(rundir / "relief.jsonl", windows)
    if row["relief"] and row["relief"].get("first_calls"):
        ends = [b["b1"] for b in rec["bursts"]]
        for c in row["relief"]["first_calls"]:
            after = [c["t"] - e for e in ends if c.get("t") and c["t"] >= e]
            c["s_after_burst_end"] = rnd(min(after), 2) if after else None
    bad = [e for b in row["bursts"] for e in b.pop("errors") if e] + row["steady"].pop("errors")
    if bad:
        row["notes"] += bad
    return row


def alloc_cell(v, digits=1):
    return "-" if v is None else ("%.*f" % (digits, v) if isinstance(v, float) else str(v))


def alloc_line(r):
    """One run as a table line's cells."""
    b = r.get("bursts") or [{}, {}]
    b1, b2 = (b + [{}, {}])[:2]
    st = r.get("steady") or {}
    rel = r.get("relief") or {}
    sn = r.get("snapshot_after_bursts") or {}
    mbps = lambda x: "%s/%s" % (alloc_cell(x.get("mbps_down"), 0), alloc_cell(x.get("mbps_up"), 0))
    return [r["setup"] + " " + r.get("setup_name", ""), str(r.get("run")), alloc_cell(r.get("fp_idle_mb")),
            alloc_cell(b1.get("fp_peak_sampled_mb")), alloc_cell((b1.get("fp_after_mb") or {}).get("5.0")),
            alloc_cell(b2.get("fp_start_mb")), alloc_cell(b2.get("fp_peak_sampled_mb")),
            alloc_cell((b2.get("fp_after_mb") or {}).get("5.0")), alloc_cell(r.get("fp_peak_bursts_mb")),
            alloc_cell(sn.get("malloc_allocated_mb")), alloc_cell(sn.get("malloc_dirty_mb")),
            mbps(b1) + " " + mbps(b2), "%s %s" % (alloc_cell(b1.get("cpu_s_per_gb"), 2), alloc_cell(b2.get("cpu_s_per_gb"), 2)),
            mbps(st), alloc_cell(st.get("cpu_s_per_gb"), 2),
            "%s/%s/%s" % (rel.get("calls", "-") if rel else "-", alloc_cell(rel.get("released_mb")) if rel else "-",
                          alloc_cell(rel.get("cpu_ms")) if rel else "-")]


ALLOC_HEAD = ("setup", "run", "idle", "b1 peak", "b1 +5s", "b2 start", "b2 peak", "b2 +5s", "peak (kernel)",
              "malloc live", "malloc dirty", "burst Mbit/s b1 b2 (down/up)", "burst CPU s/GB b1 b2",
              "steady Mbit/s down/up", "steady CPU s/GB", "relief calls/MiB/CPU ms")


def alloc_tail_cells(r, marks):
    t = (r.get("tail") or {}).get("fp_after_last_burst_mb") or {}
    b = (r.get("bursts") or [{}])[-1]
    return ([r["setup"] + " " + r.get("setup_name", ""), str(r.get("run")), alloc_cell(b.get("fp_peak_sampled_mb"))]
            + [alloc_cell(t.get(str(m))) for m in marks] + [alloc_cell((r.get("tail") or {}).get("fp_min_mb"))])


def alloc_tail_marks(doc):
    tail = doc.get("tail_s") or 0
    return [m for m in ALLOC_TAIL_MARKS if m <= tail]


def alloc_text(doc):
    out = ["\nalloc-rows, macOS phys_footprint (MiB) by allocator setup, %s, mobile profile" % doc.get("version")]
    for line in doc.get("lines") or []:
        out.append("\n  " + line)
        out.append("  " + " | ".join(ALLOC_HEAD))
        for r in doc["rows"]:
            if r.get("line") != line:
                continue
            if r.get("error"):
                out.append("  %s %s | %s | FAILED: %s" % (r.get("setup"), r.get("setup_name", ""), r.get("run"),
                                                        str(r["error"]).splitlines()[0][:200]))
                continue
            out.append("  " + " | ".join(alloc_line(r)))
    marks = alloc_tail_marks(doc)
    if marks:
        out.append("\nthe idle tail after the last burst (phys_footprint MiB, seconds after its last upload)")
        for line in doc.get("lines") or []:
            out.append("\n  " + line)
            out.append("  " + " | ".join(["setup", "run", "b2 peak"] + ["+%ds" % m for m in marks] + ["min"]))
            for r in doc["rows"]:
                if r.get("line") == line and not r.get("error"):
                    out.append("  " + " | ".join(alloc_tail_cells(r, marks)))
    return "\n".join(out)


def alloc_markdown(doc):
    out = ["## Allocator rows (lab.py alloc-rows, macOS phys_footprint)", "",
           "Build: %s. Mobile profile. MiB of phys_footprint; b1/b2 = the two bursts (32 downloads of 4 MB, then "
           "32 uploads of 2 MB); +5s = 5 s after a burst's last upload ended; peak = the kernel's lifetime maximum "
           "over both bursts; malloc live / dirty = vmmap's malloc zones 5 s after the second burst (the system "
           "zones only: mimalloc's memory is not in them); relief = malloc_zone_pressure_relief calls over the "
           "whole run, MiB they released, their CPU ms." % doc.get("version"), ""]
    said = []
    for s in doc.get("setups") or []:
        cfg = next((r.get("malloc_config") for r in doc["rows"] if r.get("setup") == s["key"] and r.get("malloc_config")),
                   None)
        said.append("- %s %s: %s" % (s["key"], s["name"], "; ".join("%s %s" % kv for kv in cfg.items())
                                     if cfg else "libmalloc printed no config"))
    out += ["libmalloc's config as the core reported it (MallocReportConfig=1):", ""] + said + [""]
    for line in doc.get("lines") or []:
        out += ["### " + line, "", "| " + " | ".join(ALLOC_HEAD) + " |", "|" + "---|" * len(ALLOC_HEAD)]
        for r in doc["rows"]:
            if r.get("line") != line:
                continue
            if r.get("error"):
                out.append("| %s %s | %s | FAILED: %s |" % (r.get("setup"), r.get("setup_name", ""), r.get("run"),
                                                           str(r["error"]).splitlines()[0][:200].replace("|", "/")))
                continue
            out.append("| " + " | ".join(alloc_line(r)) + " |")
        out.append("")
    marks = alloc_tail_marks(doc)
    if marks:
        head = ["setup", "run", "b2 peak"] + ["+%ds" % m for m in marks] + ["min"]
        out += ["### The idle tail after the last burst", "",
                "phys_footprint (MiB) the given seconds after the last burst's last upload, the core idle.", ""]
        for line in doc.get("lines") or []:
            out += ["#### " + line, "", "| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
            for r in doc["rows"]:
                if r.get("line") == line and not r.get("error"):
                    out.append("| " + " | ".join(alloc_tail_cells(r, marks)) + " |")
            out.append("")
    return "\n".join(out) + "\n"


def cmd_alloc_rows(a):
    if sys.platform != "darwin":
        raise LabError("alloc-rows reads phys_footprint and malloc_zone_pressure_relief: macOS only")
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    b = Path(a.bin).resolve()
    budget = Budget(a.budget_min)
    binary, info, ref, commit = xrs_build(a.xray_rust)
    wanted = [k for k in a.setups.split(",") if k]
    unknown = [k for k in wanted if k not in {x[0] for x in ALLOC_SETUPS}]
    if unknown:
        raise LabError("--setups: no setup %s (there are %s)" % (", ".join(unknown), ", ".join(x[0] for x in ALLOC_SETUPS)))
    setups = [x for x in ALLOC_SETUPS if x[0] in wanted]
    doc = {"what": "phys_footprint of xray-rust under allocator setups (lab_alloc.rs in the lab's patch, and "
                   "libmalloc's own MallocLargeCache switch)",
           "platform": a.platform, "runner": " ".join(os.uname()), "version": xrs_version_text(info),
           "xray_rust_build": info, "mobile_env": xrs_mobile_env(),
           "setups": [{"key": k, "name": n, "env": e, "what": w} for k, n, e, _, w in setups],
           "tail_s": a.tail_s,
           "procedure": "3 s idle; %d x (burst: %d downloads of %d B at once, %.0f s, %d uploads of %d B at once; "
                        "then %.0f s); footprint + vmmap; 500 MB down, %.0f s, 100 MB up; %.0f s. phys_footprint "
                        "polled every %.2f s" % (ALLOC_BURSTS, BURST_FLOWS, BURST_EACH_DOWN, PAUSE_S, BURST_FLOWS,
                                                 BURST_EACH_UP, max(ALLOC_MARKS), PAUSE_S, max(ALLOC_MARKS),
                                                 ALLOC_POLL_S),
           "lines": [HEAP_LINE_NAMES[n] for n in a.lines.split(",")], "rows": []}
    rows = doc["rows"]
    if not (binary and binary.exists()):
        doc["error"] = "the patched xray-rust was not built: %s" % tail(out.parent / "xray-rust-alloc-build.log", 8)
        write_json(out / "alloc-rows.json", doc)
        print(doc["error"], flush=True)
        return 1
    for port in (PORT_PAGE, PORT_DEST, PORT_REALITY, PORT_XHTTP, PORT_WEBTUNNEL, PORT_SOCKS):
        if not wait_port_free(port, 15):
            raise LabError("port %d is still taken" % port)
    servers = Servers(out, b / "loadgen", b / "xray-26.4.25" / "xray")
    ctx = {"out": out, "platform": a.platform, "xrs": binary, "version": doc["version"], "reader": MacReader(),
           "snapper": MacSnapper(), "host": LocalHost(b / "loadgen"), "tail_s": a.tail_s}
    try:
        servers.start()
        doc["server"] = servers.version
        loop = "127.0.0.1"
        hy2_pin = cert_sha256(servers.dir / "hy2.crt")
        try:
            wt_pin = cert_sha256(servers.dir / "webtunnel.crt")
        except (OSError, LabError):
            wt_pin = "0" * 64
        ctx["build"] = {"vless-reality-vision": lambda: xrs_reality(loop, servers.pub),
                        "hysteria2-salamander": lambda: xrs_hy2(loop, hy2_pin),
                        "vless-xhttp-reality packet-up": lambda: xrs_xhttp(loop, servers.pub),
                        WEBTUNNEL: lambda: xrs_webtunnel(loop, wt_pin)}
        for run_no in (1, 2):
            for line in doc["lines"]:
                for setup in setups:
                    base = {"platform": a.platform, "line": line, "setup": setup[0], "setup_name": setup[1],
                            "run": run_no}
                    if not budget.allows(120):
                        rows.append(dict(base, error="skipped: out of the %.0f min budget" % a.budget_min))
                        continue
                    if line == WEBTUNNEL and servers.wt_error:
                        rows.append(dict(base, error="the WebTunnel server: " + servers.wt_error))
                        continue
                    log("alloc-rows:", line, "|", setup[0], setup[1], "| run", run_no)
                    t0 = time.time()
                    try:
                        row = alloc_run(ctx, line, setup, run_no)
                    except Exception as e:
                        traceback.print_exc()
                        row = dict(base, error="%s: %s" % (type(e).__name__, e))
                    row["seconds"] = rnd(time.time() - t0, 1)
                    rows.append(row)
                    write_json(out / "alloc-rows.json", doc)
                    if not row.get("error"):
                        print("  " + " | ".join(alloc_line(row)), flush=True)
    finally:
        servers.stop()
        write_json(out / "alloc-rows.json", doc)
    print(alloc_text(doc), flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(alloc_markdown(doc))
    return 0


# ---------------------------------------------------------------- main


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("desktop")
    d.add_argument("--bin", required=True)
    d.add_argument("--out", required=True)
    d.add_argument("--platform", required=True)
    d.add_argument("--engine-src")
    d.add_argument("--tun", action="store_true",
                   help="also the gVisor tun run, the hev run and the iOS shape rows, with BIN/hev-socks5-tunnel "
                        "(Linux, passwordless sudo)")
    d.add_argument("--xray-rust", help="the main xray-rust build: a directory with xray-rust and "
                                       "xray-rust.build.json (rust-core-build.sh); its rows fail by name when the "
                                       "binary is missing")
    d.add_argument("--xray-rust-baseline", help="the same for the historical baseline (v0.9.0): the mobile profile "
                                                "once per line")
    d.add_argument("--xray-rust-hello", help="the same for the ClientHello rows")
    d.add_argument("--xray-rust-up", help="the same for the fork's XHTTP upload fix: its XHTTP mobile rows beside "
                                          "the main build's, and XHTTP's one-worker rows")
    d.add_argument("--no-go-rows", dest="go_rows", action="store_false",
                   help="leave out the Go rows, the tun, hev and iOS shape rows, the idle harness and the olcRTC soaks")
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
    f = sub.add_parser("udp-flow", help="where a new UDP flow's time goes behind hev (Linux, macOS; passwordless sudo)")
    f.add_argument("--bin", required=True, help="loadgen, xray-26.4.25/xray, harness, hev-socks5-tunnel[-nomore]")
    f.add_argument("--out", required=True)
    f.add_argument("--budget-min", type=float, default=4.5)
    h = sub.add_parser("heap-profile", help="where xray-rust's memory goes: heaptrack on Linux, footprint and vmmap "
                                            "on macOS")
    h.add_argument("--bin", required=True, help="loadgen, xray-26.4.25/xray")
    h.add_argument("--out", required=True)
    h.add_argument("--platform", required=True)
    h.add_argument("--xray-rust", help="the build macOS runs")
    h.add_argument("--xray-rust-lines", help="the build with line tables (Linux runs it; rust-core-build.sh "
                                             "XRS_LINE_TABLES=1)")
    h.add_argument("--heaptrack-out", help="where heaptrack's data and heaptrack_print's text go (Linux)")
    h.add_argument("--lines", help="comma-separated, of %s (default: all)" % ", ".join(HEAP_LINE_NAMES))
    h.add_argument("--budget-min", type=float, default=24)
    al = sub.add_parser("alloc-rows", help="phys_footprint under five allocator setups (macOS; the lab's allocator "
                                           "patch)")
    al.add_argument("--bin", required=True, help="loadgen, xray-26.4.25/xray")
    al.add_argument("--out", required=True)
    al.add_argument("--platform", required=True)
    al.add_argument("--xray-rust", required=True, help="the build with xray-rust-patches/lab-allocator-switch.patch")
    al.add_argument("--lines", default="reality,hysteria2,webtunnel,xhttp",
                    help="comma-separated, of %s" % ", ".join(HEAP_LINE_NAMES))
    al.add_argument("--setups", default=",".join(x[0] for x in ALLOC_SETUPS),
                    help="comma-separated setup keys (default: all)")
    al.add_argument("--tail-s", type=float, default=0,
                    help="seconds the core stays idle after the last burst, the footprint read at %s (default 0: "
                         "the 5 s every burst has)" % ", ".join(str(m) for m in ALLOC_TAIL_MARKS))
    al.add_argument("--budget-min", type=float, default=40)
    fs = sub.add_parser("flow-socks", help="udp-flow's python SOCKS5 server (runs until killed)")
    fs.add_argument("--port", type=int, required=True)
    fs.add_argument("--login", help="username:password")
    fs.add_argument("--page-port", type=int, default=PORT_PAGE)
    a = ap.parse_args(argv)
    try:
        if a.cmd == "desktop":
            return cmd_desktop(a)
        if a.cmd == "android":
            return cmd_android(a)
        if a.cmd == "table":
            return cmd_table(a)
        if a.cmd == "udp-flow":
            return cmd_udp_flow(a)
        if a.cmd == "heap-profile":
            return cmd_heap_profile(a)
        if a.cmd == "alloc-rows":
            return cmd_alloc_rows(a)
        if a.cmd == "flow-socks":
            return cmd_flow_socks(a)
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
