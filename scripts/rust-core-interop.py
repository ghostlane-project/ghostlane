#!/usr/bin/env python3
"""The Rust core's CLI as the client, official Xray-core releases as servers:
the lines the app writes for the core, carried end to end.

  rust-core-interop.py --client <xray-rust> --servers "<version>=<xray> ..."

<xray-rust> is the CLI of ghostlane-project/xray-rust at the pin
(rust-core.yml builds it); the servers are the official Xray-linux-64 builds
of scripts/xray-servers.txt (fetch-xray-servers.sh prints the list), the
versions servers out there run (the plan's 26.4.25, 26.7.11 and 26.11.9).

Each line is the outbound the app writes into core.json for that kind of link
(XrayConfig.buildIosCore; its reference configs are ios-core-*.json), behind
the SOCKS inbound with a login the app puts in front of it, against the
server inbound the ProofKit exits run (proofkit agent, xray_config.rs) or, for
WebTunnel, what nginx ends there (TLS offering http/1.1):

  reality-vision         VLESS, REALITY, raw, flow xtls-rprx-vision-udp443
  reality-grpc           VLESS, REALITY, gRPC
  xhttp-reality          VLESS, REALITY, XHTTP packet-up with the app's sizes
  webtunnel-ws           VLESS, TLS (ALPN h2, http/1.1), WebSocket
  webtunnel-httpupgrade  VLESS, TLS (ALPN h2, http/1.1), HTTPUpgrade
  hysteria2              Hysteria2 with Salamander, a pinned self-signed
                         certificate, ChromeParrot off (the app's finalmask)
  reality-wrong-key      reality-vision with another public key: must NOT
                         reach the page, or a pass proves nothing

Through each: a 2 MiB download and a 1 MiB upload, both checked by sha256,
fetched from http://203.0.113.10/ (TEST-NET-3), which the server's freedom
outbound redirects to a page on loopback: a request that did not take the
tunnel goes nowhere. REALITY mirrors a local TLS 1.3 target offering h2, so
nothing leaves the machine. A cell gets three tries over the same pair of
processes (a REALITY server may spend its first connection on the target).

Prints a table (versions across, lines down), appends it to
$GITHUB_STEP_SUMMARY, prints the end of both logs of every failed cell, and
exits 1 when any cell failed. Needs python3, curl and openssl.
"""
import argparse
import hashlib
import http.server
import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

UUID = "11111111-1111-1111-1111-111111111111"
SOCKS_USER, SOCKS_PASS = "ios-tun-user", "ios-tun-password"
REALITY_SNI = "reality.interop.test"
WEBTUNNEL_HOST = "wt.example.com"
HY2_SNI = "hy.example.com"
HY2_AUTH, HY2_OBFS = "PASSWORD123", "OBFSPW"
THROUGH = "http://203.0.113.10"
DOWN_BYTES = 2 * 1024 * 1024
UP_BYTES = 1024 * 1024
LINES = ["reality-vision", "reality-grpc", "xhttp-reality", "webtunnel-ws", "webtunnel-httpupgrade",
         "hysteria2", "reality-wrong-key"]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def pattern(size, seed):
    out = bytearray()
    block = hashlib.sha256(seed).digest()
    while len(out) < size:
        block = hashlib.sha256(block).digest()
        out += block * 64
    return bytes(out[:size])


class Page(http.server.BaseHTTPRequestHandler):
    down = b""

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = self.down if self.path == "/down" else b"page"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        received = hashlib.sha256()
        while length > 0:
            chunk = self.rfile.read(min(length, 65536))
            if not chunk:
                break
            received.update(chunk)
            length -= len(chunk)
        body = received.hexdigest().encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def start_page():
    Page.down = pattern(DOWN_BYTES, b"down")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def certificate(work, name, kind):
    """A self-signed certificate for <name>; returns (cert, key, sha256 of its DER)."""
    cert, key = os.path.join(work, name + ".crt"), os.path.join(work, name + ".key")
    algorithm = ["-newkey", "rsa:2048"] if kind == "rsa" else ["-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256"]
    subprocess.run(["openssl", "req", "-x509", *algorithm, "-nodes", "-days", "2", "-subj", "/CN=" + name,
                    "-addext", "subjectAltName=DNS:" + name, "-keyout", key, "-out", cert],
                   check=True, capture_output=True)
    der = subprocess.run(["openssl", "x509", "-in", cert, "-outform", "DER"], check=True, capture_output=True).stdout
    return cert, key, hashlib.sha256(der).hexdigest()


def start_reality_target(work):
    """What a REALITY server mirrors, as a real site answers: TLS 1.3, h2
    offered, a certificate larger than the server's own (an RSA one)."""
    cert, key, _ = certificate(work, "target.interop.test", "rsa")
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
    if len(values) < 2:
        raise RuntimeError("cannot read `xray x25519`: " + out)
    return values[0], values[1]


def line_configs(name, port, keys, wrong_public, target, tls):
    """(server inbound, client outbound) for one line."""
    private, public = keys
    reality_in = {"dest": target, "serverNames": [REALITY_SNI], "privateKey": private, "shortIds": ["ab12cd34"]}
    reality_out = {"serverName": REALITY_SNI, "fingerprint": "chrome", "publicKey": public, "shortId": "ab12cd34"}
    vless_out = lambda user, stream: {
        "tag": "out", "protocol": "vless",
        "settings": {"vnext": [{"address": "127.0.0.1", "port": port, "users": [{"id": UUID, "encryption": "none", **user}]}]},
        "streamSettings": stream}
    vless_in = lambda client, stream: {
        "tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "vless",
        "settings": {"clients": [{"id": UUID, **client}], "decryption": "none"}, "streamSettings": stream}
    if name in ("reality-vision", "reality-wrong-key"):
        out_reality = dict(reality_out, publicKey=wrong_public if name == "reality-wrong-key" else public)
        return (vless_in({"flow": "xtls-rprx-vision"}, {"network": "raw", "security": "reality", "realitySettings": reality_in}),
                vless_out({"flow": "xtls-rprx-vision-udp443"},
                          {"network": "raw", "security": "reality", "realitySettings": out_reality}))
    if name == "reality-grpc":
        grpc = {"serviceName": "media/sync"}
        return (vless_in({}, {"network": "grpc", "grpcSettings": grpc, "security": "reality", "realitySettings": reality_in}),
                vless_out({}, {"network": "grpc", "grpcSettings": grpc, "security": "reality", "realitySettings": reality_out}))
    if name == "xhttp-reality":
        return (vless_in({}, {"network": "xhttp", "security": "reality", "realitySettings": reality_in,
                              "xhttpSettings": {"host": REALITY_SNI, "path": "/download", "mode": "auto"}}),
                vless_out({}, {"network": "xhttp", "security": "reality", "realitySettings": reality_out,
                               "xhttpSettings": {"path": "/download", "host": REALITY_SNI, "mode": "packet-up",
                                                 "scMaxEachPostBytes": 200000, "h2StreamReceiveWindow": 524288}}))
    if name in ("webtunnel-ws", "webtunnel-httpupgrade"):
        cert, key, pin = tls["webtunnel"]
        network, path = ("ws", "/v") if name == "webtunnel-ws" else ("httpupgrade", "/u")
        settings_key = network + "Settings"
        return (vless_in({}, {"network": network, settings_key: {"path": path}, "security": "tls",
                              "tlsSettings": {"alpn": ["http/1.1"], "certificates": [{"certificateFile": cert, "keyFile": key}]}}),
                vless_out({}, {"network": network, settings_key: {"path": path, "host": WEBTUNNEL_HOST}, "security": "tls",
                               "tlsSettings": {"serverName": WEBTUNNEL_HOST, "fingerprint": "chrome", "alpn": ["h2", "http/1.1"],
                                               "pinnedPeerCertSha256": pin}}))
    if name == "hysteria2":
        cert, key, pin = tls["hysteria2"]
        salamander = [{"type": "salamander", "settings": {"password": HY2_OBFS}}]
        return ({"tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "hysteria",
                 "settings": {"version": 2, "clients": [{"auth": HY2_AUTH, "email": "interop@test", "level": 0}]},
                 "streamSettings": {"network": "hysteria", "security": "tls",
                                    "tlsSettings": {"serverName": HY2_SNI, "alpn": ["h3"],
                                                    "certificates": [{"certificateFile": cert, "keyFile": key}]},
                                    "hysteriaSettings": {"version": 2, "udpIdleTimeout": 60},
                                    "finalmask": {"udp": salamander}}},
                {"tag": "out", "protocol": "hysteria", "settings": {"version": 2, "address": "127.0.0.1", "port": port},
                 "streamSettings": {"network": "hysteria", "security": "tls",
                                    "tlsSettings": {"serverName": HY2_SNI, "pinnedPeerCertSha256": pin},
                                    "hysteriaSettings": {"version": 2, "auth": HY2_AUTH},
                                    "finalmask": {"udp": salamander, "quicParams": {"disableChromeParrot": True}}}})
    raise ValueError(name)


def wait_tcp(port, process, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return False
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def stop(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def through(socks_port, upload, seconds):
    """None when both transfers came back right, else what went wrong."""
    proxy = "socks5://%s:%s@127.0.0.1:%d" % (SOCKS_USER, SOCKS_PASS, socks_port)
    base = ["curl", "-sS", "--max-time", str(seconds), "-x", proxy]
    down = subprocess.run(base + [THROUGH + "/down"], capture_output=True)
    if down.returncode != 0:
        return "download: curl exit %d: %s" % (down.returncode, down.stderr.decode(errors="replace").strip())
    if hashlib.sha256(down.stdout).hexdigest() != hashlib.sha256(Page.down).hexdigest():
        return "download: %d bytes, not the page's %d" % (len(down.stdout), len(Page.down))
    up = subprocess.run(base + ["--data-binary", "@" + upload, "-H", "Content-Type: application/octet-stream",
                                THROUGH + "/up"], capture_output=True)
    if up.returncode != 0:
        return "upload: curl exit %d: %s" % (up.returncode, up.stderr.decode(errors="replace").strip())
    want = hashlib.sha256(open(upload, "rb").read()).hexdigest()
    if up.stdout.decode(errors="replace").strip() != want:
        return "upload: the page received other bytes"
    return None


def tail(path, lines=40):
    try:
        with open(path, errors="replace") as f:
            return "".join(f.readlines()[-lines:])
    except OSError:
        return "(no log)\n"


def run_cell(work, client, version, xray, line, page_port, keys, wrong_public, target, tls, upload):
    """(ok, attempts, seconds, why) for one line against one server."""
    cell = os.path.join(work, "%s-%s" % (version, line))
    os.makedirs(cell)
    udp = line == "hysteria2"
    port = free_port(socket.SOCK_DGRAM if udp else socket.SOCK_STREAM)
    socks_port = free_port()
    inbound, outbound = line_configs(line, port, keys, wrong_public, target, tls)
    server_config = {"log": {"loglevel": "warning"}, "inbounds": [inbound], "outbounds": [{
        "tag": "direct", "protocol": "freedom",
        "settings": {"redirect": "127.0.0.1:%d" % page_port, "ipsBlocked": [],
                     "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}]}
    client_config = {"log": {"loglevel": "warning"}, "inbounds": [{
        "tag": "in", "protocol": "socks", "listen": "127.0.0.1", "port": socks_port,
        "settings": {"auth": "password", "accounts": [{"user": SOCKS_USER, "pass": SOCKS_PASS}], "udp": True}}],
        "outbounds": [outbound]}
    for name, config in (("server", server_config), ("client", client_config)):
        with open(os.path.join(cell, name + ".json"), "w") as f:
            json.dump(config, f, indent=1)
    server = client_process = None
    start = time.monotonic()
    try:
        with open(os.path.join(cell, "server.log"), "w") as log:
            server = subprocess.Popen([xray, "run", "-config", os.path.join(cell, "server.json")], stdout=log, stderr=log)
        if udp:
            time.sleep(1.5)
            if server.poll() is not None:
                return False, 0, 0.0, "the server exited at start"
        elif not wait_tcp(port, server, 15):
            return False, 0, 0.0, "the server did not listen"
        with open(os.path.join(cell, "client.log"), "w") as log:
            client_process = subprocess.Popen([client, "run", "-config", os.path.join(cell, "client.json")], stdout=log, stderr=log)
        if not wait_tcp(socks_port, client_process, 15):
            return False, 0, 0.0, "the client did not listen"
        if line == "reality-wrong-key":
            why = through(socks_port, upload, 10)
            return why is not None, 1, time.monotonic() - start, "a wrong key reached the page" if why is None else None
        why = None
        for attempt in (1, 2, 3):
            why = through(socks_port, upload, 30)
            if why is None:
                return True, attempt, time.monotonic() - start, None
            time.sleep(1)
        return False, 3, time.monotonic() - start, why
    finally:
        stop(client_process)
        stop(server)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--client", required=True, help="the xray-rust CLI")
    p.add_argument("--servers", required=True, help='"<version>=<xray binary> ..."')
    p.add_argument("--line", action="append", choices=LINES, help="only these lines")
    a = p.parse_args()
    for tool in ("curl", "openssl"):
        if not shutil.which(tool):
            sys.exit("missing " + tool)
    servers = []
    for item in a.servers.split():
        version, _, path = item.partition("=")
        if not version or not os.access(path, os.X_OK):
            sys.exit("not a <version>=<binary>: " + item)
        servers.append((version, path))
    client = os.path.abspath(a.client)
    lines = a.line or LINES
    work = tempfile.mkdtemp(prefix="rust-core-interop.")
    page = start_page()
    target = start_reality_target(work)
    tls = {"webtunnel": certificate(work, WEBTUNNEL_HOST, "ec"), "hysteria2": certificate(work, HY2_SNI, "ec")}
    upload = os.path.join(work, "upload.bin")
    with open(upload, "wb") as f:
        f.write(pattern(UP_BYTES, b"up"))
    results = {}
    failed = []
    for version, xray in servers:
        print("== Xray %s: %s" % (version, subprocess.run([xray, "version"], capture_output=True, text=True).stdout.splitlines()[0]), flush=True)
        keys = reality_keys(xray)
        wrong_public = reality_keys(xray)[1]
        for line in lines:
            ok, attempts, seconds, why = run_cell(work, client, version, xray, line, page.server_address[1],
                                                  keys, wrong_public, target, tls, upload)
            if line == "reality-wrong-key":
                text = "refused, as it must" if ok else "FAILED: " + (why or "")
            elif ok:
                text = "ok" + (" (try %d)" % attempts if attempts > 1 else "") + ", %.1f s" % seconds
            else:
                text = "FAILED: " + (why or "")
            results[(version, line)] = text
            print("%-8s %-22s %s" % (version, line, text), flush=True)
            if not ok:
                failed.append((version, line))
    header = "| line | " + " | ".join("Xray " + v for v, _ in servers) + " |"
    rows = ["### xray-rust's CLI against official Xray servers", "",
            "A 2 MiB download and a 1 MiB upload through each line, checked by sha256.", "",
            header, "|---|" + "---|" * len(servers)]
    for line in lines:
        rows.append("| `%s` | " % line + " | ".join(results[(v, line)].replace("|", "\\|") for v, _ in servers) + " |")
    table = "\n".join(rows) + "\n"
    print(table)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(table + "\n")
    for version, line in failed:
        cell = os.path.join(work, "%s-%s" % (version, line))
        print("::group::%s %s: logs" % (version, line))
        for name in ("server", "client"):
            print("--- %s.json" % name)
            print(open(os.path.join(cell, name + ".json")).read() if os.path.exists(os.path.join(cell, name + ".json")) else "")
            print("--- %s.log (end)" % name)
            print(tail(os.path.join(cell, name + ".log")))
        print("::endgroup::")
    page.shutdown()
    if failed:
        print("::error::%d of %d cells failed: %s" % (len(failed), len(results), ", ".join("%s %s" % f for f in failed)))
        return 1
    print("all %d cells passed" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
