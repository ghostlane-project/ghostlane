#!/usr/bin/env python3
"""The olcRTC port's host-process footprint harness (plan §9.2), on macOS,
and the same rows on Linux.

A measurement, not a check. Each flavour runs as a process of its own against
the T1 host (the interop-pipe hub and the pinned Go server on this machine, no
carrier, no live room), and only that process is sampled:

  rust     fp-host (host/main.swift), a plain command-line process linking
           ghostlane-core in-process and driving OlcrtcRuntime as the
           packet-tunnel extension will, with EngineLog and MemoryWatch's
           work beside it; its memory_stats() comes with every 4 Hz tick.
           On Linux (--rust-client) xray-rust's olc-ffi-client in its
           control mode (OLC_FFI_CONTROL=1), the same protocol, linking
           olc-ffi with the counting allocator.
  rust-b3  (--budget-arm) the same host with the phone's byte budget pressed:
           set_byte_budget(3000000) before start, then the 256 and 512 rows.
  go       the engine's `olcrtc` binary at the pin in mode cnc (the baseline
           the plan names), GOMEMLIMIT=40MiB as PacketTunnelProvider sets it.

On macOS all get setup f: the system allocator with MallocLargeCache=0, iOS's
reclaim settings on macOS (lab/core-footprint 09-allocator-rows.md), and a
sampler process reads proc_pid_rusage(RUSAGE_INFO_V4) (phys_footprint, its
lifetime maximum, resident size) and the thread count at 10 Hz. On Linux the
sampler reads /proc/<pid>/status: RssAnon as the footprint (anonymous
resident bytes, the nearest to phys_footprint), VmHWM as the lifetime maximum
(of VmRSS: Linux keeps none of RssAnon), VmRSS and Threads; glibc's malloc as
it is.

Phases, each followed by SETTLE_S of quiet inside its window:
  loaded  (rust) the binary up, nothing started
  idle    started and ready
  down    BURST_FLOWS downloads of the origin's /big at once
  up      BURST_FLOWS uploads of UP_BYTES to /sink at once
  dns     DNS_BURST DNS queries through one UDP associate, twice (gate S5)
  wide    WIDE_FLOWS downloads of WIDE_BYTES at once, from an origin of the
          driver's own (256 × 4 MiB at once ran the runner's network buffers
          out and the T1 server tore the session down, for both clients)
  after trim
          (rust) trim() right after the 256 downloads: the footprint just
          before and just after the call, as the host reads it, and what
          trim() says it gave (from xray-rust 2026-11-15 on, trim() asks
          the allocator on Apple targets itself)
  after malloc_trim
          (rust, Linux) glibc's malloc_trim(0) after that: what the
          allocator still had to give
  conns512
          CONNS_FLOWS downloads of WIDE_BYTES at once: every connection the
          SOCKS front takes (512), each with its floor
  cycles  (rust) CYCLES stop/starts, as a link change does
  trim    (rust) trim(), then quiet
  relief  (rust, macOS) malloc_zone_pressure_relief(NULL, 0), then quiet:
          what the system allocator gives back when asked after trim()
The rust-b3 arm runs loaded, budget (set_byte_budget(PHONE_BUDGET)), idle,
wide and conns512; its B column carries memory_stats' bytes.peak.

  footprint.py run --t1 <t1.json> --rust <fp-host> --go <olcrtc> [--budget-arm] --runs 2 --out <dir>
  footprint.py run --t1 <t1.json> --rust-client <olc-ffi-client> --go <olcrtc> ...     (Linux)
  footprint.py sample <pid> <csv> <stop file>       (the sampler; run starts it)

Writes <dir>/rows.json, <dir>/rows.md and, per run, the samples, the ticks and
the logs. Standard library only.
"""

import argparse
import ctypes
import http.server
import json
import os
import queue
import secrets
import socket
import statistics
import struct
import subprocess
import sys
import threading
import time
import urllib.parse

MIB = 1 << 20
PERIOD_S = 0.1
SETTLE_S = 6.0
BURST_FLOWS = 32
UP_BYTES = 2_000_000
WIDE_FLOWS = 256
WIDE_BYTES = 128 << 10
CONNS_FLOWS = 512
# The phone's byte budget, B on the iPhone (xray-transport memory_budget,
# Constrained), which the rust-b3 arm sets on this machine.
PHONE_BUDGET = 3_000_000
DNS_BURST = 64
DNS_WAIT_S = 5.0
DNS_RESOLVER = ("8.8.8.8", 53)
CYCLES = 10
CHUNK = 64 << 10
IO_TIMEOUT_S = 120
READY_TIMEOUT_S = 40
GO_MEMORY_LIMIT = "40MiB"  # PacketTunnelProvider.goMemoryLimit
LAYERS = ("socks", "udp", "smux_rx", "smux_tx", "mux_in")


# ---------------------------------------------------------------- sampling

class MacReader:
    """proc_pid_rusage(RUSAGE_INFO_V4) and proc_pidinfo(PROC_PIDTASKINFO),
    lab/core-footprint's MacReader layout."""

    RUSAGE = "<16s35Q"
    TASKINFO = "<6Q12i"

    def __init__(self):
        self.lib = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
        self.lib.proc_pid_rusage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        self.lib.proc_pid_rusage.restype = ctypes.c_int
        self.lib.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p,
                                          ctypes.c_int]
        self.lib.proc_pidinfo.restype = ctypes.c_int
        self.rsize = struct.calcsize(self.RUSAGE)
        self.tsize = struct.calcsize(self.TASKINFO)

    def read(self, pid):
        buf = ctypes.create_string_buffer(self.rsize)
        if self.lib.proc_pid_rusage(pid, 4, buf) != 0:
            raise OSError(ctypes.get_errno(), "proc_pid_rusage")
        v = struct.unpack_from(self.RUSAGE, buf.raw)[1:]
        threads = -1
        tbuf = ctypes.create_string_buffer(self.tsize)
        if self.lib.proc_pidinfo(pid, 4, 0, tbuf, self.tsize) == self.tsize:
            threads = struct.unpack_from(self.TASKINFO, tbuf.raw)[6 + 9]
        return {"rss": v[6], "fp": v[7], "lifemax": v[28], "threads": threads}


class LinuxReader:
    """/proc/<pid>/status: RssAnon as the footprint, VmHWM, VmRSS, Threads."""

    def read(self, pid):
        try:
            with open(f"/proc/{pid}/status") as f:
                text = f.read()
        except OSError as e:
            raise OSError(e.errno, "status") from e
        v = {}
        for line in text.splitlines():
            k, _, rest = line.partition(":")
            v[k] = rest.split()
        if "RssAnon" not in v:
            raise OSError(0, "the process is gone")

        def kb(name):
            return int(v[name][0]) * 1024 if v.get(name) else 0
        return {"rss": kb("VmRSS"), "fp": kb("RssAnon"), "lifemax": kb("VmHWM"),
                "threads": int(v["Threads"][0]) if v.get("Threads") else -1}


LINUX = sys.platform.startswith("linux")
Reader = LinuxReader if LINUX else MacReader


def sample_main(pid, path, stop):
    reader = Reader()
    with open(path, "w") as f:
        f.write("t,fp,lifemax,rss,threads\n")
        due = time.time()
        while not os.path.exists(stop):
            try:
                v = reader.read(pid)
            except OSError:
                break
            f.write(f"{time.time():.3f},{v['fp']},{v['lifemax']},{v['rss']},{v['threads']}\n")
            f.flush()
            due += PERIOD_S
            time.sleep(max(0.0, due - time.time()))


def load_samples(path):
    out = []
    with open(path) as f:
        next(f, None)
        for line in f:
            p = line.strip().split(",")
            if len(p) == 5:
                out.append((float(p[0]), int(p[1]), int(p[2]), int(p[3]), int(p[4])))
    return out


# ---------------------------------------------------------------- SOCKS5

def recv_exact(s, n):
    out = bytearray()
    while len(out) < n:
        b = s.recv(n - len(out))
        if not b:
            raise ConnectionError(f"closed after {len(out)} of {n} bytes")
        out += b
    return bytes(out)


def socks_open(port, user, password, cmd, ip, dport):
    """A SOCKS5 request with the login (RFC 1928, RFC 1929); the socket and
    the reply's address."""
    s = socket.create_connection(("127.0.0.1", port), timeout=IO_TIMEOUT_S)
    try:
        s.sendall(b"\x05\x01\x02")
        if recv_exact(s, 2) != b"\x05\x02":
            raise ConnectionError("no login method")
        u, p = user.encode(), password.encode()
        s.sendall(bytes([1, len(u)]) + u + bytes([len(p)]) + p)
        if recv_exact(s, 2) != b"\x01\x00":
            raise ConnectionError("login refused")
        s.sendall(bytes([5, cmd, 0, 1]) + socket.inet_aton(ip) + struct.pack(">H", dport))
        head = recv_exact(s, 4)
        if head[1] != 0:
            raise ConnectionError(f"request refused: {head[1]}")
        if head[3] == 1:
            raw = recv_exact(s, 6)
            bound = (socket.inet_ntoa(raw[:4]), struct.unpack(">H", raw[4:])[0])
        elif head[3] == 4:
            raw = recv_exact(s, 18)
            bound = (socket.inet_ntop(socket.AF_INET6, raw[:16]), struct.unpack(">H", raw[16:])[0])
        elif head[3] == 3:
            n = recv_exact(s, 1)[0]
            raw = recv_exact(s, n + 2)
            bound = (raw[:n].decode(), struct.unpack(">H", raw[n:])[0])
        else:
            raise ConnectionError(f"reply address type {head[3]}")
        return s, bound
    except BaseException:
        s.close()
        raise


def read_head(s):
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        b = s.recv(4096)
        if not b:
            raise ConnectionError(f"no response head in {len(buf)} bytes")
        buf += b
    head, _, rest = bytes(buf).partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    length = None
    for h in lines[1:]:
        k, _, v = h.partition(":")
        if k.strip().lower() == "content-length":
            length = int(v.strip())
    return lines[0], length, rest


def get(origin, conn, path):
    s, _ = socks_open(conn["port"], conn["user"], conn["pass"], 1, origin[0], origin[1])
    with s:
        s.sendall(f"GET {path} HTTP/1.1\r\nHost: {origin[0]}:{origin[1]}\r\nConnection: close\r\n\r\n".encode())
        status, length, rest = read_head(s)
        got = len(rest)
        view = memoryview(bytearray(CHUNK))
        while length is None or got < length:
            n = s.recv_into(view)
            if n == 0:
                break
            got += n
        if " 200" not in status or length is None or got != length:
            raise ConnectionError(f"{status}: {got} of {length} bytes")
        return got


BODY = bytes((i * 7 + 13) & 0xFF for i in range(CHUNK))


def post(origin, conn, path, size):
    s, _ = socks_open(conn["port"], conn["user"], conn["pass"], 1, origin[0], origin[1])
    with s:
        s.sendall(f"POST {path} HTTP/1.1\r\nHost: {origin[0]}:{origin[1]}\r\nContent-Length: {size}\r\n"
                  f"Connection: close\r\n\r\n".encode())
        left = size
        while left > 0:
            n = min(left, CHUNK)
            s.sendall(BODY[:n])
            left -= n
        status, length, rest = read_head(s)
        body = bytearray(rest)
        while length is None or len(body) < length:
            b = s.recv(4096)
            if not b:
                break
            body += b
        if " 200" not in status or body.decode(errors="replace").strip() != str(size):
            raise ConnectionError(f"{status}: sink said {bytes(body[:40])!r}")
        return size


def parallel(n, fn):
    """n of fn at once; (ok, failures, bytes, seconds)."""
    results, errors = [], []
    lock = threading.Lock()

    def one(i):
        try:
            got = fn(i)
            with lock:
                results.append(got)
        except Exception as e:  # noqa: BLE001 - every failure is a row's fact
            with lock:
                errors.append(f"{type(e).__name__}: {e}")

    t0 = time.time()
    threads = [threading.Thread(target=one, args=(i,), daemon=True) for i in range(n)]
    for t in threads:
        t.start()
    deadline = t0 + IO_TIMEOUT_S + 30
    for t in threads:
        t.join(max(0.0, deadline - time.time()))
    hung = sum(t.is_alive() for t in threads)
    secs = time.time() - t0
    if hung:
        errors.append(f"{hung} still running after {IO_TIMEOUT_S + 30} s")
    return {"ok": len(results), "of": n, "bytes": sum(results), "seconds": round(secs, 3),
            "mbit_s": round(sum(results) * 8 / secs / 1e6, 1) if secs > 0 else None,
            "errors": errors[:5], "failed": len(errors)}


def dns_query(qid, name):
    q = struct.pack(">HHHHHH", qid, 0x0100, 1, 0, 0, 0)
    for label in name.split("."):
        q += bytes([len(label)]) + label.encode()
    return q + b"\x00" + struct.pack(">HH", 1, 1)


def dns_burst(conn, resolver):
    """Gate S5's round: DNS_BURST queries on a fresh association, the
    distinct ids answered within DNS_WAIT_S."""
    ctl, relay = socks_open(conn["port"], conn["user"], conn["pass"], 3, "0.0.0.0", 0)
    with ctl:
        if relay[0] in ("0.0.0.0", "::"):
            relay = ("127.0.0.1", relay[1])
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        with u:
            u.bind(("127.0.0.1", 0))
            head = b"\x00\x00\x00\x01" + socket.inet_aton(resolver[0]) + struct.pack(">H", resolver[1])
            for qid in range(1, DNS_BURST + 1):
                u.sendto(head + dns_query(qid, f"gate{qid}.example.com"), relay)
            seen = set()
            deadline = time.time() + DNS_WAIT_S
            while len(seen) < DNS_BURST:
                left = deadline - time.time()
                if left <= 0:
                    break
                u.settimeout(left)
                try:
                    data = u.recv(4096 + 300)
                except socket.timeout:
                    break
                if len(data) < 10 or data[3] != 1:
                    continue
                payload = data[10:]
                if len(payload) >= 2:
                    qid = struct.unpack(">H", payload[:2])[0]
                    if 1 <= qid <= DNS_BURST:
                        seen.add(qid)
            return len(seen)


def resolver_reachable():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    with s:
        s.settimeout(3)
        try:
            s.sendto(dns_query(7, "example.com"), DNS_RESOLVER)
            return len(s.recv(4096)) > 0
        except OSError:
            return False


class Pages(http.server.BaseHTTPRequestHandler):
    """GET /bytes?n=N: N bytes of the pattern, with their length."""

    def do_GET(self):  # noqa: N802 - the handler's name
        u = urllib.parse.urlsplit(self.path)
        n = int((urllib.parse.parse_qs(u.query).get("n") or ["0"])[0])
        if u.path != "/bytes" or n < 0:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(n))
        self.end_headers()
        left = n
        while left > 0:
            k = min(left, CHUNK)
            self.wfile.write(BODY[:k])
            left -= k

    def log_message(self, *args):
        pass


class Origin(http.server.ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 1024


def free_port():
    s = socket.socket()
    with s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


# ---------------------------------------------------------------- the flavours

class RustHost:
    """fp-host over stdin/stdout: answers on a queue, TICK lines kept."""

    def __init__(self, cmd, env, stderr):
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
                                     env=env, text=True, bufsize=1)
        self.answers = queue.Queue()
        self.ticks = []
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            if line.startswith("TICK "):
                parts = line.split(" ", 3)
                try:
                    self.ticks.append((int(parts[1]) / 1000.0, int(parts[2]), json.loads(parts[3])))
                except (IndexError, ValueError):
                    pass
            else:
                self.answers.put(line)
        self.answers.put(None)

    def expect(self, prefix, timeout):
        deadline = time.time() + timeout
        while True:
            try:
                line = self.answers.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                raise TimeoutError(f"no {prefix} within {timeout} s") from None
            if line is None:
                raise ConnectionError(f"fp-host ended before {prefix} (exit {self.proc.poll()})")
            if line.startswith(prefix + " ") or line == prefix:
                return line[len(prefix):].strip()
            if line.startswith("ERR "):
                raise RuntimeError(line)

    def call(self, command, prefix, timeout=60):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()
        return self.expect(prefix, timeout)

    @property
    def pid(self):
        return self.proc.pid


def go_config(t1, conn):
    q = json.dumps
    return "\n".join([
        "mode: cnc", "auth: { provider: none }",
        "engine: { name: interop-pipe, url: " + q(t1["room"]) + " }",
        "crypto: { key: " + q(t1["key"]) + " }",
        "net: { transport: " + t1["transport"] + ", dns: " + q(t1["dns"]) + " }",
        "socks: { host: \"127.0.0.1\", port: " + str(conn["port"]) + ", user: " + q(conn["user"]) +
        ", pass: " + q(conn["pass"]) + " }",
        "udp: { enabled: true }", "debug: false",
    ]) + "\n"


def wait_for_line(path, needle, timeout, proc):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(path, errors="replace") as f:
                if needle in f.read().lower():
                    return True
        except OSError:
            pass
        if proc.poll() is not None:
            return False
        time.sleep(0.05)
    return False


def client_config(t1, conn):
    """olc-ffi-client's JSON (olc_client::Config): the T1 room, fp-host's
    settings."""
    return json.dumps({"url": t1["room"], "key": t1["key"], "transport": t1["transport"], "dns": t1["dns"],
                       "socks": "127.0.0.1:%d" % conn["port"], "socks_user": conn["user"],
                       "socks_pass": conn["pass"], "device_id": "lab-footprint"})


def run_one(flavour, index, args, t1, base):
    d = os.path.join(base, f"{flavour}-{index}")
    os.makedirs(d, exist_ok=True)
    conn = {"port": free_port(), "user": "lab", "pass": "lab-" + secrets.token_hex(8)}
    env = dict(os.environ, MallocLargeCache="0")
    phases = []
    host = None
    go = None
    t_spawn = time.time()
    if flavour.startswith("rust"):
        if args.rust:
            cmd = [args.rust, "--t1", args.t1, "--port", str(conn["port"]), "--user", conn["user"], "--pass",
                   conn["pass"], "--log", os.path.join(d, "olcrtc.log"), "--watch", os.path.join(d, "memory.txt")]
        else:
            cfg = os.path.join(d, "client.json")
            with open(cfg, "w") as f:
                f.write(client_config(t1, conn))
            cmd = [args.rust_client, cfg]
            env["OLC_FFI_CONTROL"] = "1"
        host = RustHost(cmd, env, open(os.path.join(d, "host.stderr"), "w"))
        host.expect("LOADED", 30)
        pid = host.pid
    else:
        env["GOMEMLIMIT"] = GO_MEMORY_LIMIT
        cfg = os.path.join(d, "cnc.yaml")
        with open(cfg, "w") as f:
            f.write(go_config(t1, conn))
        log = open(os.path.join(d, "olcrtc.log"), "w")
        go = subprocess.Popen([args.go, cfg], stdout=log, stderr=subprocess.STDOUT, env=env)
        pid = go.pid
    csv = os.path.join(d, "samples.csv")
    stop = os.path.join(d, "samples.stop")
    sampler = subprocess.Popen([sys.executable, "-I", os.path.abspath(__file__), "sample", str(pid), csv, stop])

    def phase(name, body):
        t0 = time.time()
        try:
            info = body() or {}
        except Exception as e:  # noqa: BLE001 - the row says what failed
            info = {"error": f"{type(e).__name__}: {e}"}
        t_load = time.time()
        time.sleep(SETTLE_S)
        stats = None
        if host is not None:
            try:
                stats = json.loads(host.call("stats", "STATS"))
            except Exception as e:  # noqa: BLE001
                info.setdefault("error", f"stats: {e}")
        phases.append({"name": name, "t0": t0, "t_load": t_load, "t_end": time.time(), "info": info,
                       "stats": stats})
        print(f"  {flavour}-{index} {name}: {json.dumps(info)[:300]}", flush=True)

    origin = args.origin

    def trimmed(command, prefix, key):
        n, before, after = host.call(command, prefix).split()
        return {key: int(n), "fp_before": int(before), "fp_after": int(after)}

    def wide(n):
        return parallel(n, lambda i: get(args.pages, conn, f"/bytes?n={WIDE_BYTES}"))

    def all_rows():
        phase("down", lambda: parallel(BURST_FLOWS, lambda i: get(origin, conn, "/big")))
        phase("up", lambda: parallel(BURST_FLOWS, lambda i: post(origin, conn, "/sink", UP_BYTES)))
        phase("dns", lambda: {"target": "%s:%d" % args.resolver,
                              "answered": [dns_burst(conn, args.resolver), dns_burst(conn, args.resolver)],
                              "of": DNS_BURST})
        phase("wide", lambda: wide(WIDE_FLOWS))
        if host is not None:
            phase("after trim", lambda: trimmed("trim", "TRIM", "trimmed"))
            if LINUX:
                phase("after malloc_trim", lambda: trimmed("mtrim", "MTRIM", "malloc_trim"))
        phase("conns512", lambda: wide(CONNS_FLOWS))
        if host is not None:
            def cycles():
                out = host.call(f"cycle {CYCLES}", "CYCLED", 60 * CYCLES)
                info = dict(kv.split("=", 1) for kv in out.split())
                info["fetch_after"] = get(origin, conn, "/kb")
                return info
            phase("cycles", cycles)
            phase("trim", lambda: {"trimmed": int(host.call("trim", "TRIM").split()[0])})
            if not LINUX:
                phase("relief", lambda: {"relieved": int(host.call("relief", "RELIEF"))})
    try:
        if host is not None:
            phase("loaded", lambda: {})
            if flavour == "rust-b3":
                phase("budget", lambda: {"cap": int(host.call(f"budget {PHONE_BUDGET}", "BUDGET").split("=", 1)[1])})

            def start():
                return {"ready_ms": int(host.call("start", "READY", 60).split("=", 1)[1])}
            phase("idle", start)
        else:
            def ready():
                ok = wait_for_line(os.path.join(d, "olcrtc.log"), "socks5 server listening", READY_TIMEOUT_S, go)
                if not ok:
                    raise RuntimeError("no ready line")
                return {"ready_ms": int((time.time() - t_spawn) * 1000)}
            phase("idle", ready)
        if flavour == "rust-b3":
            # The phone's budget pressed: the 256 and the 512 rows alone.
            phase("wide", lambda: wide(WIDE_FLOWS))
            phase("conns512", lambda: wide(CONNS_FLOWS))
        else:
            all_rows()
    finally:
        if host is not None:
            try:
                host.call("quit", "BYE", 30)
            except Exception:  # noqa: BLE001
                host.proc.kill()
            host.proc.wait(30)
        if go is not None:
            go.terminate()
            try:
                go.wait(15)
            except subprocess.TimeoutExpired:
                go.kill()
                go.wait(5)
        open(stop, "w").close()
        try:
            sampler.wait(10)
        except subprocess.TimeoutExpired:
            sampler.kill()

    samples = load_samples(csv)
    ticks = host.ticks if host is not None else []
    with open(os.path.join(d, "ticks.jsonl"), "w") as f:
        for t, fp, s in ticks:
            f.write(json.dumps({"t": t, "fp": fp, "stats": s}) + "\n")
    rows = []
    for p in phases:
        win = [s for s in samples if p["t0"] <= s[0] <= p["t_end"]]
        tail = [s[1] for s in win if s[0] >= p["t_end"] - 1.0] or [s[1] for s in win[-5:]]
        tk = [s for t, _, s in ticks if p["t0"] <= t <= p["t_end"]]
        live = [((s.get("heap") or {}).get("live") or 0) for s in tk]
        layer_max = {}
        for name in LAYERS:
            held = [((s.get("layers") or {}).get(name) or [None])[0] or 0 for s in tk]
            layer_max[name] = max(held) if held else None
        conns = [((s.get("n") or {}).get("conns") or 0) for s in tk]
        # The process's byte budget (memory_stats' "bytes", from xray-rust
        # 2026-11-15 on): what was taken of B, highest tick.
        budget = [((s.get("bytes") or {}).get("held") or 0) for s in tk if "bytes" in s]
        st = p["stats"] or {}
        rows.append({
            "flavour": flavour, "run": index, "phase": p["name"],
            "samples": len(win),
            "fp_end": int(statistics.median(tail)) if tail else None,
            "fp_peak": max(s[1] for s in win) if win else None,
            "lifemax": win[-1][2] if win else None,
            "rss_peak": max(s[3] for s in win) if win else None,
            "threads": max(s[4] for s in win) if win else None,
            "heap_live_max": max(live) if live else None,
            "heap_live_end": (st.get("heap") or {}).get("live"),
            "heap_peak": (st.get("heap") or {}).get("peak"),
            "workers": (st.get("rt") or {}).get("workers"),
            "rt_threads": (st.get("rt") or {}).get("threads"),
            "tasks": (st.get("rt") or {}).get("tasks"),
            "conns_max": max(conns) if conns else None,
            "budget_held_max": max(budget) if budget else None,
            "budget_cap": ((st.get("bytes") or {}).get("cap")),
            # bytes.peak at the phase's end: the most ever taken of B in
            # this process.
            "budget_peak": ((st.get("bytes") or {}).get("peak")),
            "layers_max": layer_max if tk else None,
            "load_s": round(p["t_load"] - p["t0"], 3),
            "info": p["info"],
        })
    return rows


# ---------------------------------------------------------------- report

def mib(v):
    return "-" if v is None else f"{v / MIB:.2f}"


def kib(v):
    return "-" if v is None else f"{v / 1024:.0f}"


def load_text(r):
    i = r["info"]
    if "error" in i:
        return "ERROR " + i["error"][:80]
    if "ok" in i:
        s = f"{i['ok']}/{i['of']} in {i['seconds']} s, {i['mbit_s']} Mbit/s"
        return s + (f"; {i['failed']} failed: {i['errors'][0][:60]}" if i.get("failed") else "")
    if "answered" in i:
        return f"{i['answered'][0]}+{i['answered'][1]} of {i['of']}×2 ({i['target']})"
    if "ready_ms" in i:
        return f"ready {i['ready_ms']} ms"
    if "ready_min" in i:
        return f"{CYCLES} cycles, ready {i['ready_min']}-{i['ready_max']} ms; fetch {i['fetch_after']} B"
    if "fp_before" in i:
        what = (f"trim gave {i['trimmed']} B" if "trimmed" in i
                else f"malloc_trim(0) = {i['malloc_trim']}")
        fell = i["fp_before"] - i["fp_after"]
        return (f"{what}; footprint {mib(i['fp_before'])} -> {mib(i['fp_after'])} MiB "
                f"({fell / MIB:+.2f} MiB fell)")
    if "trimmed" in i:
        return f"trim gave {i['trimmed']} B"
    if "cap" in i:
        return f"set_byte_budget: B = {i['cap']} B"
    if "relieved" in i:
        return f"the allocator gave {i['relieved']} B"
    return ""


def report(rows, meta):
    if LINUX:
        out = ["### Host-process footprint (plan §9.2): T1 pipe, Linux, glibc malloc", ""]
        what = ("MiB of `RssAnon` from `/proc/<pid>/status` at 10 Hz (*life*: `VmHWM`, the high-water mark "
                "of VmRSS)")
    else:
        out = ["### Host-process footprint (plan §9.2): T1 pipe, setup f", ""]
        what = "MiB of `phys_footprint` from `proc_pid_rusage` at 10 Hz"
    out += [f"- {k}: {v}" for k, v in meta.items()]
    out += ["", what + "; *end* is the median of the phase's "
            "last second (after the settle), *peak* the phase's highest sample, *life* the lifetime maximum "
            "at the phase's end. *heap* is the Rust counting allocator's live bytes (highest 4 Hz tick in the "
            "phase); *peak* its lifetime high-water mark. Layers: KiB held, highest tick. *B*: KiB taken of "
            "the process's byte budget, highest tick, of its cap, and `bytes.peak` at the phase's end.", ""]
    out += ["| flavour | run | phase | end | peak | life | threads | heap max | heap peak | conns | "
            "socks/udp/smux_rx/smux_tx/mux_in KiB | B KiB | load |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        lm = r["layers_max"]
        layers = "-" if not lm else "/".join(kib(lm[n]) for n in LAYERS)
        held = r.get("budget_held_max")
        budget = "-" if held is None else (f"{kib(held)} of {kib(r.get('budget_cap'))}, "
                                           f"peak {kib(r.get('budget_peak'))}")
        out.append(f"| {r['flavour']} | {r['run']} | {r['phase']} | {mib(r['fp_end'])} | {mib(r['fp_peak'])} | "
                   f"{mib(r['lifemax'])} | {r['threads']} | {mib(r['heap_live_max'])} | {mib(r['heap_peak'])} | "
                   f"{r['conns_max'] if r['conns_max'] is not None else '-'} | {layers} | {budget} | "
                   f"{load_text(r)} |")
    # The plan's rows, side by side.
    flavours = []
    names = []
    for r in rows:
        if r["flavour"] not in flavours:
            flavours.append(r["flavour"])
        if r["phase"] not in names:
            names.append(r["phase"])
    head = "| phase | " + " | ".join(f"{f} peak (run 1 / 2)" for f in flavours) + " | rust heap max |"
    out += ["", head, "|---" * (len(flavours) + 2) + "|"]
    for name in names:
        def pick(flavour, key):
            v = [r[key] for r in rows if r["flavour"] == flavour and r["phase"] == name]
            return " / ".join(mib(x) for x in v) if v else "-"
        out.append(f"| {name} | " + " | ".join(pick(f, "fp_peak") for f in flavours) +
                   f" | {pick('rust', 'heap_live_max')} |")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sample")
    s.add_argument("pid", type=int)
    s.add_argument("csv")
    s.add_argument("stop")
    r = sub.add_parser("run")
    r.add_argument("--t1", required=True)
    r.add_argument("--rust", help="fp-host (macOS)")
    r.add_argument("--rust-client", help="olc-ffi-client, driven in its control mode (Linux)")
    r.add_argument("--budget-arm", action="store_true",
                   help=f"also the rust-b3 arm: set_byte_budget({PHONE_BUDGET}), the 256 and 512 rows")
    r.add_argument("--go")
    r.add_argument("--runs", type=int, default=2)
    r.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "sample":
        sample_main(a.pid, a.csv, a.stop)
        return 0
    with open(a.t1) as f:
        t1 = json.load(f)
    u = urllib.parse.urlsplit(t1["origin"])
    a.origin = (u.hostname, u.port)
    # The resolver S5 asks; the T1 echo when the runner cannot reach it.
    a.resolver = DNS_RESOLVER
    if not resolver_reachable():
        a.resolver = (t1.get("echo_ip") or "127.0.0.1", int(t1["echo_port"]))
    pages = Origin(("127.0.0.1", 0), Pages)
    threading.Thread(target=pages.serve_forever, daemon=True).start()
    a.pages = ("127.0.0.1", pages.server_address[1])
    os.makedirs(a.out, exist_ok=True)
    rust = a.rust or a.rust_client
    flavours = [f for f, path in (("rust", rust), ("rust-b3", rust if a.budget_arm else None), ("go", a.go))
                if path]
    rows = []
    for i in range(1, a.runs + 1):
        for flavour in flavours:
            print(f"run {i} {flavour}", flush=True)
            rows += run_one(flavour, i, a, t1, a.out)
    order = {"rust": 0, "rust-b3": 1, "go": 2}
    rows.sort(key=lambda r: (order.get(r["flavour"], 3), r["run"]))
    meta = {
        "setup": ("Linux, glibc malloc as it is" if LINUX else "f (system allocator, MallocLargeCache=0)"),
        "go": f"GOMEMLIMIT={GO_MEMORY_LIMIT}",
        "load": f"down {BURST_FLOWS} × GET /big, up {BURST_FLOWS} × {UP_BYTES} B, dns {DNS_BURST} × 2, "
                f"wide {WIDE_FLOWS} × {WIDE_BYTES} B, conns512 {CONNS_FLOWS} × {WIDE_BYTES} B; "
                f"settle {SETTLE_S:g} s after each; rust-b3: B = {PHONE_BUDGET}",
        "resolver": "%s:%d" % a.resolver,
        "transport": t1["transport"],
    }
    with open(os.path.join(a.out, "rows.json"), "w") as f:
        json.dump({"meta": meta, "rows": rows}, f, indent=1)
    text = report(rows, meta)
    with open(os.path.join(a.out, "rows.md"), "w") as f:
        f.write(text)
    print(text)
    bad = [r for r in rows if "error" in r["info"] or r["info"].get("failed")]
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
