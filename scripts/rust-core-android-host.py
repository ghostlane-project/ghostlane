#!/usr/bin/env python3
"""The host's half of the Android kill and soak runs (rust-core-android.yml).

  rust-core-android-host.py serve --xray <xray> --work <dir> [--address 10.0.2.2]
      A VLESS REALITY line the emulator reaches at <address> (the emulator's
      name for the host's loopback): an official Xray server on loopback with
      the run's own keys (`xray x25519`), a TLS 1.3 site on loopback for it to
      mirror, and a page. The server sends 192.0.2.1 (TEST-NET-1, reachable
      nowhere) to the page and everything else straight out, so the app's
      tunnel check reaches the internet and an answer from 192.0.2.1 can only
      have come through the line. The page answers GET /bytes/<n>/<any> with
      <n> bytes that begin with the run's nonce and counts what it served.
      Writes <dir>/link, <dir>/nonce, then <dir>/ready; <dir>/hits holds the
      count. Runs until SIGTERM, or ends with 1 when Xray does; either way the
      run's keys, the server's config and the link are removed.
  rust-core-android-host.py meminfo
      `dumpsys meminfo <package>` on stdin: prints "<pss> <rss> <native> <java>"
      in KiB, a dash for a figure the text lacks (RSS before Android 11).
  rust-core-android-host.py soak-summary --csv <file> [--max-growth-mib 16] [--warmup 10] [--window 20]
      The soak's verdict from its samples: the PSS curve, its growth from the
      first window after the warm-up to the last window, the slope, the
      fetches and any restart of the process. Exit 1 unless the curve is
      flat (growth within the bound), no process restarted and no more than
      2 % of the fetch rounds failed.

Needs python3, openssl. The keys, the nonce and the certificate are the run's
own and leave nothing behind.
"""
import argparse
import csv
import http.server
import json
import os
import re
import secrets
import signal
import socket
import ssl
import statistics
import subprocess
import sys
import threading
import time
import uuid

PAGE_IP = "192.0.2.1"
SNI = "reality.e2e.test"


class Page(http.server.BaseHTTPRequestHandler):
    nonce = b""
    hits_file = ""
    lock = threading.Lock()
    hits = 0

    def log_message(self, *args):
        pass

    def do_GET(self):
        size = 64
        match = re.search(r"(?:[?&]bytes=|/bytes/)(\d+)", self.path)
        if match:
            size = max(len(self.nonce) + 1, min(int(match.group(1)), 64 * 1024 * 1024))
        head = self.nonce + b"\n"
        self.send_response(200)
        self.send_header("Content-Length", str(size))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(head)
        left = size - len(head)
        block = b"0123456789abcdef" * 4096
        while left > 0:
            chunk = block[:min(left, len(block))]
            self.wfile.write(chunk)
            left -= len(chunk)
        with Page.lock:
            Page.hits += 1
            tmp = self.hits_file + ".tmp"
            with open(tmp, "w") as f:
                f.write("%d\n" % Page.hits)
            os.replace(tmp, self.hits_file)


class PageServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        if not isinstance(sys.exc_info()[1], ConnectionError):
            super().handle_error(request, client_address)


def reality_target(work):
    """What a REALITY server mirrors, as a real site answers: TLS 1.3, h2 offered."""
    cert, key = os.path.join(work, "target.crt"), os.path.join(work, "target.key")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                    "-subj", "/CN=target.e2e.test", "-addext", "subjectAltName=DNS:target.e2e.test",
                    "-keyout", key, "-out", cert], check=True, capture_output=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.set_alpn_protocols(["h2", "http/1.1"])
    context.load_cert_chain(cert, key)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(64)

    def serve(conn):
        try:
            conn.settimeout(15)
            with context.wrap_socket(conn, server_side=True) as tls:
                while tls.recv(4096):
                    pass
        except (OSError, ssl.SSLError):
            pass
        finally:
            conn.close()

    def accept():
        while True:
            try:
                conn, _ = listener.accept()
            except OSError:
                return
            threading.Thread(target=serve, args=(conn,), daemon=True).start()

    threading.Thread(target=accept, daemon=True).start()
    return "127.0.0.1:%d" % listener.getsockname()[1]


def reality_keys(xray):
    """(private, public) from `xray x25519`: the values of its first two lines."""
    out = subprocess.run([xray, "x25519"], check=True, capture_output=True, text=True).stdout
    values = [line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line]
    if len(values) < 2 or not all(values[:2]):
        sys.exit("cannot read `xray x25519`: %r" % out)
    return values[0], values[1]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_port(port, seconds, process):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if process.poll() is not None:
            return False
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def warm(target, page_port):
    """One handshake with the target and one request of the page, so the first fetch is neither's first use."""
    host, port = target.rsplit(":", 1)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname, context.verify_mode = False, ssl.CERT_NONE
    with socket.create_connection((host, int(port)), timeout=10) as raw:
        with context.wrap_socket(raw, server_hostname="target.e2e.test") as tls:
            if tls.version() != "TLSv1.3":
                sys.exit("the REALITY target answers %s, not TLS 1.3" % tls.version())
    with socket.create_connection(("127.0.0.1", page_port), timeout=10) as raw:
        raw.sendall(b"GET /warm HTTP/1.0\r\n\r\n")
        if not raw.recv(64).startswith(b"HTTP/1."):
            sys.exit("the page does not answer")


def write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.replace(tmp, path)


def serve(args):
    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)
    for name in ("ready", "link", "nonce"):
        if os.path.exists(os.path.join(work, name)):
            os.remove(os.path.join(work, name))
    Page.nonce = ("E2E-NONCE-" + secrets.token_hex(12)).encode()
    Page.hits_file = os.path.join(work, "hits")
    write(Page.hits_file, "0\n")
    page = PageServer(("127.0.0.1", 0), Page)
    threading.Thread(target=page.serve_forever, daemon=True).start()
    target = reality_target(work)
    private, public = reality_keys(args.xray)
    client = str(uuid.uuid4())
    short_id = secrets.token_hex(4)
    port = free_port()
    config = {
        "log": {"loglevel": "warning"},
        "inbounds": [{
            "tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "vless",
            "settings": {"clients": [{"id": client, "flow": "xtls-rprx-vision"}], "decryption": "none"},
            "streamSettings": {
                "network": "raw", "security": "reality",
                "realitySettings": {"dest": target, "serverNames": [SNI], "privateKey": private, "shortIds": [short_id]},
            },
        }],
        "outbounds": [
            {"tag": "direct", "protocol": "freedom"},
            {"tag": "page", "protocol": "freedom", "settings": {"redirect": "127.0.0.1:%d" % page.server_address[1]}},
        ],
        "routing": {"domainStrategy": "AsIs", "rules": [{"type": "field", "ip": [PAGE_IP], "outboundTag": "page"}]},
    }
    server_json = os.path.join(work, "server.json")
    write(server_json, json.dumps(config, indent=1))
    log = open(os.path.join(work, "xray.log"), "w")
    xray = subprocess.Popen([args.xray, "run", "-config", server_json], stdout=log, stderr=log)

    def forget():
        # The run's keys and credential: nothing of them is kept with the run's logs.
        for name in ("server.json", "target.key", "target.crt", "link"):
            try:
                os.remove(os.path.join(work, name))
            except OSError:
                pass

    def stop(*_):
        xray.terminate()
        try:
            xray.wait(5)
        except subprocess.TimeoutExpired:
            xray.kill()
        forget()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    if not wait_port(port, 20, xray):
        log.flush()
        sys.exit("the Xray server did not listen on %d (xray.log in %s)" % (port, work))
    warm(target, page.server_address[1])
    link = ("vless://%s@%s:%d?security=reality&encryption=none&pbk=%s&sid=%s&fp=chrome&sni=%s"
            "&flow=xtls-rprx-vision&type=tcp#host-reality") % (client, args.address, port, public, short_id, SNI)
    write(os.path.join(work, "link"), link + "\n")
    write(os.path.join(work, "nonce"), Page.nonce.decode() + "\n")
    write(os.path.join(work, "ready"), "%d\n" % port)
    print("ready: Xray %s on 127.0.0.1:%d, the page on %d" % (args.xray, port, page.server_address[1]), flush=True)
    while True:
        time.sleep(1)
        if xray.poll() is not None:
            forget()
            sys.exit("the Xray server ended (%s); xray.log in %s" % (xray.returncode, work))


def meminfo(text):
    """(pss, rss, native, java) in KiB from `dumpsys meminfo <package>`, None for a figure it lacks."""
    def first(*patterns):
        for p in patterns:
            m = re.search(p, text, re.M)
            if m:
                return int(m.group(1))
        return None
    pss = first(r"TOTAL PSS:\s+(\d+)", r"^\s*TOTAL:\s+(\d+)", r"^\s*TOTAL\s+(\d+)")
    rss = first(r"TOTAL RSS:\s+(\d+)")
    native = first(r"^\s*Native Heap:\s+(\d+)", r"^\s*Native Heap\s+(\d+)")
    java = first(r"^\s*Java Heap:\s+(\d+)")
    return pss, rss, native, java


def soak_summary(args):
    rows = []
    with open(args.csv) as f:
        for r in csv.DictReader(f):
            rows.append(r)
    lines = []
    say = lines.append

    def num(r, k):
        v = r.get(k, "")
        return int(v) if v not in ("", "-", None) else None

    samples = [(float(r["minute"]), num(r, "pss_kb"), num(r, "native_kb"), num(r, "rss_kb"), r.get("pid", "")) for r in rows]
    measured = [s for s in samples if s[1] is not None]
    pids = []
    for s in samples:
        if s[4] and (not pids or pids[-1] != s[4]):
            pids.append(s[4])
    restarts = max(0, len(pids) - 1) + sum(1 for s in samples if not s[4])
    ok = sum(num(r, "ok") or 0 for r in rows)
    failed = sum(num(r, "fail") or 0 for r in rows)
    rounds = sum(1 for r in rows if (num(r, "ok") or 0) + (num(r, "fail") or 0) > 0)
    bad_rounds = sum(1 for r in rows if (num(r, "fail") or 0) > 0)
    moved = sum(num(r, "bytes") or 0 for r in rows)
    span = samples[-1][0] if samples else 0.0
    say("Soak: %d samples over %.0f minutes, process %s" % (len(samples), span, " -> ".join(pids) or "never seen"))
    say("Fetches: %d answered, %d failed, %.1f MiB moved; %d of %d rounds had a failure" % (ok, failed, moved / 1048576.0, bad_rounds, rounds))
    verdict = True
    if restarts:
        say("The process restarted or vanished %d time(s)" % restarts)
        verdict = False
    if rounds == 0 or bad_rounds > 0.02 * rounds:
        say("More than 2 % of the fetch rounds failed")
        verdict = False
    after = [s for s in measured if s[0] >= args.warmup]
    first = [s for s in after if s[0] < args.warmup + args.window]
    last = [s for s in after if s[0] > span - args.window]
    if len(first) < 3 or len(last) < 3:
        say("Too few samples after the warm-up for a curve")
        verdict = False
        growth = None
    else:
        p0 = statistics.median(s[1] for s in first)
        p1 = statistics.median(s[1] for s in last)
        growth = (p1 - p0) / 1024.0
        n0 = [s[2] for s in first if s[2] is not None]
        n1 = [s[2] for s in last if s[2] is not None]
        xs = [s[0] for s in after]
        ys = [s[1] for s in after]
        mx, my = statistics.fmean(xs), statistics.fmean(ys)
        den = sum((x - mx) ** 2 for x in xs)
        slope = (sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den * 60 / 1024.0) if den else 0.0
        say("PSS: median %.1f MiB in minutes %d-%d, %.1f MiB in the last %d; growth %+.1f MiB (bound %.1f), slope %+.2f MiB/hour; max %.1f MiB"
            % (p0 / 1024.0, args.warmup, args.warmup + args.window, p1 / 1024.0, args.window, growth, args.max_growth_mib,
               slope, max(s[1] for s in measured) / 1024.0))
        if n0 and n1:
            say("Native heap: median %.1f MiB, then %.1f MiB (%+.1f)" % (statistics.median(n0) / 1024.0, statistics.median(n1) / 1024.0,
                                                                   (statistics.median(n1) - statistics.median(n0)) / 1024.0))
        if growth > args.max_growth_mib:
            verdict = False
    bars = "▁▂▃▄▅▆▇█"
    if measured:
        lo = min(s[1] for s in measured)
        hi = max(s[1] for s in measured)
        buckets = {}
        for s in measured:
            buckets.setdefault(int(s[0] // 5), []).append(s[1])
        line = "".join(bars[0 if hi == lo else int((statistics.median(v) - lo) * (len(bars) - 1) / (hi - lo))]
                       for _, v in sorted(buckets.items()))
        say("PSS every 5 minutes, %.1f to %.1f MiB: %s" % (lo / 1024.0, hi / 1024.0, line))
        say("")
        say("| minute | PSS MiB | native heap MiB | RSS MiB |")
        say("|---:|---:|---:|---:|")
        shown = set()
        for s in measured:
            key = int(s[0] // 10)
            if key in shown:
                continue
            shown.add(key)
            say("| %.0f | %.1f | %s | %s |" % (s[0], s[1] / 1024.0, "%.1f" % (s[2] / 1024.0) if s[2] is not None else "-",
                                             "%.1f" % (s[3] / 1024.0) if s[3] is not None else "-"))
    say("")
    say("VERDICT %s" % ("flat" if verdict else "NOT flat or not clean"))
    print("\n".join(lines))
    return 0 if verdict else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--xray", required=True)
    s.add_argument("--work", required=True)
    s.add_argument("--address", default="10.0.2.2")
    sub.add_parser("meminfo")
    m = sub.add_parser("soak-summary")
    m.add_argument("--csv", required=True)
    m.add_argument("--max-growth-mib", type=float, default=16.0)
    m.add_argument("--warmup", type=float, default=10.0)
    m.add_argument("--window", type=float, default=20.0)
    args = parser.parse_args()
    if args.cmd == "serve":
        serve(args)
    elif args.cmd == "meminfo":
        figures = meminfo(sys.stdin.read())
        print(" ".join("-" if v is None else str(v) for v in figures))
    else:
        sys.exit(soak_summary(args))


if __name__ == "__main__":
    main()
