#!/usr/bin/env python3
"""Lines against Xray servers (check K5): the core.json the app's builder
writes for a link, run by the Rust core's CLI as the client, against official
Xray-core releases as the servers.

  rust-core-interop.py prepare --work <dir> --xray <xray> [--lines <dir>]
  rust-core-interop.py run --work <dir> --dumps <dir> --client <xray-rust> \\
      --servers "<version>=<xray> ..."
  rust-core-interop.py selftest

A run has three steps, and the middle one is not this script's:

  1. prepare: the run's own keys (`xray x25519`), a self-signed certificate,
     a server port and a SOCKS port for every line, and the line's link with
     them written in. <work>/links.json holds the links, for step 2.
  2. The builder: sharedUI's jvmTest InteropDumpTest, with INTEROP_LINKS set
     to <work>/links.json and INTEROP_DUMPS to <dumps>, parses each link as
     the app does and writes the config the app would start the core from,
     <dumps>/<line>.core.json, with <dumps>/manifest.json naming the kind
     and shape of each link (or why the builder refused it) and every shape
     the builder writes.
  3. run: for every server release and every line, the server from the
     line's file, the client from the builder's dump, and through them a
     2 MiB download and a 1 MiB upload, each checked by sha256, fetched from
     http://203.0.113.10/ (TEST-NET-3), which the server's freedom outbound
     redirects to a page on loopback: a request that did not take the tunnel
     goes nowhere. The client's SOCKS port and login are read from the dump.

The matrix is the files under scripts/rust-core-interop/lines/, one file a
line, <line>.server.json, and no list here names one: a line joins the
matrix by its file. A file holds the server's inbounds and what the driver
needs to know of the line:

  shape     the builder's name for the shape of the line's link (CoreLine
            shapes); the builder's own answer for the link must be it.
  link      the share link, with placeholders the run fills (below).
  expect    "page": the line must reach the page. "refused": it must not,
            while the line named by `against` does on the same server (a
            wrong key, a certificate nobody pinned, a pin of another one).
  mutant    for a "page" line: {"pointer": <JSON pointer into the dump>,
            "value": <another value>}. The dump with that one value changed
            must not reach the page, or the server never read the dump's
            credential and a pass proves nothing.
  network   "tcp" (the default) or "udp": how the server's port is chosen
            and waited for.
  tls_name  the name the run's certificate is made for (default
            interop.test).
  trust     "pin": the core trusts only public roots, so a line whose kind
            verifies a certificate by its chain alone cannot reach a
            self-signed test server. For such a line the driver adds one key
            to the dump, the run certificate's pinnedPeerCertSha256 under the
            line outbound's tlsSettings, and the table says so. Nothing else
            of a dump is ever changed.
  about     words for the reader.
  inbounds  the Xray server's inbounds. The driver adds `log` and the one
            outbound, the freedom redirect to the page; a file with
            `outbounds` of its own is refused.

Placeholders. In the link: {port}, {public_key}, {other_public_key} (a
public key whose private key no server holds), {pin} (the certificate's
SHA-256, lower-case hex), {pin_colons} (the same in OpenSSL's spelling) and
{other_pin_colons} (a SHA-256 of no certificate). In the inbounds, those and
{private_key}, {target} (the TLS 1.3 site a REALITY server mirrors, on
loopback), {cert_file} and {key_file}. A string that is a placeholder alone,
like "{port}", becomes the number. Anything else in braces is refused, and so
is a server's own secret in a link.

A cell gets three tries over the same pair of processes when it must reach
the page (a REALITY server may spend its first connection on the target),
one when it must not. Every cell is named: one that cannot run (no dump, a
builder refusal, a process that did not start) is a failure, never a skip.
A shape the builder writes that no "page" line carries is a failure too.

Prints a table (server releases across, lines down), appends it to
$GITHUB_STEP_SUMMARY, prints both ends' configs and logs of every failed
cell, and exits 1 when anything failed. Needs python3, curl and openssl.
"""
import argparse
import copy
import hashlib
import http.server
import json
import os
import random
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LINES_DIR = os.path.join(HERE, "rust-core-interop", "lines")
SUFFIX = ".server.json"
NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
LINK_VALUES = ("port", "public_key", "other_public_key", "pin", "pin_colons", "other_pin_colons")
SERVER_VALUES = LINK_VALUES + ("private_key", "target", "cert_file", "key_file")
KEYS = {"about", "shape", "link", "expect", "against", "mutant", "network", "tls_name", "trust", "inbounds"}
LINE_OUTBOUND = "out"  # CoreConfig.LINE: the tag of the line's outbound in a dump
THROUGH = "http://203.0.113.10"
DOWN_BYTES = 2 * 1024 * 1024
UP_BYTES = 1024 * 1024
PORTS = (20000, 29999)  # below Linux's ephemeral range, where the page and the target bind
TRIES, TRY_SECONDS, REFUSED_SECONDS = 3, 30, 10


class Refused(Exception):
    """A line file, a dump or a run the driver will not go on with."""


# ---------------------------------------------------------------- the lines

def load_lines(directory):
    """[(name, line)] from <directory>/*.server.json, sorted by name."""
    if not os.path.isdir(directory):
        raise Refused("no directory of lines: %s" % directory)
    names = sorted(n[:-len(SUFFIX)] for n in os.listdir(directory) if n.endswith(SUFFIX))
    others = sorted(n for n in os.listdir(directory) if not n.endswith(SUFFIX))
    if others:
        raise Refused("files under %s that are no line (<line>%s): %s" % (directory, SUFFIX, ", ".join(others)))
    if not names:
        raise Refused("no line under %s" % directory)
    lines = []
    for name in names:
        path = os.path.join(directory, name + SUFFIX)
        try:
            with open(path) as f:
                line = json.load(f)
        except (OSError, ValueError) as e:
            raise Refused("%s: %s" % (path, e))
        check_line(name, line)
        lines.append((name, line))
    by_name = dict(lines)
    for name, line in lines:
        if line["expect"] == "refused":
            other = by_name.get(line["against"])
            if other is None or other["expect"] != "page":
                raise Refused("%s: `against` must name a line that reaches the page, not %r" % (name, line["against"]))
    return lines


def check_line(name, line):
    where = name + SUFFIX
    if not NAME.match(name):
        raise Refused("%s: a line's name is lower-case letters, digits and dashes" % where)
    if not isinstance(line, dict):
        raise Refused("%s: not a JSON object" % where)
    if "outbounds" in line:
        raise Refused("%s: a line's server has no outbounds of its own: the driver's freedom redirect is the only way to the page" % where)
    extra = set(line) - KEYS
    if extra:
        raise Refused("%s: keys the driver does not know: %s" % (where, ", ".join(sorted(extra))))
    for key in ("shape", "link", "expect", "inbounds"):
        if key not in line:
            raise Refused("%s: no `%s`" % (where, key))
    if not isinstance(line["shape"], str) or not line["shape"]:
        raise Refused("%s: `shape` is the builder's name for the link's shape" % where)
    if line["expect"] not in ("page", "refused"):
        raise Refused("%s: `expect` is \"page\" or \"refused\"" % where)
    if line["expect"] == "page":
        mutant = line.get("mutant")
        if not (isinstance(mutant, dict) and set(mutant) == {"pointer", "value"} and isinstance(mutant["pointer"], str)
                and mutant["pointer"].startswith("/")):
            raise Refused("%s: a line that reaches the page names its mutant, {\"pointer\": \"/...\", \"value\": ...}" % where)
        if "against" in line:
            raise Refused("%s: `against` is for a line that must not reach the page" % where)
    else:
        if not isinstance(line.get("against"), str):
            raise Refused("%s: a line that must not reach the page names the line that does (`against`)" % where)
        if "mutant" in line:
            raise Refused("%s: a mutant is for a line that reaches the page" % where)
    if line.get("network", "tcp") not in ("tcp", "udp"):
        raise Refused("%s: `network` is \"tcp\" or \"udp\"" % where)
    if line.get("trust") not in (None, "pin"):
        raise Refused("%s: `trust` is \"pin\" or absent" % where)
    if not isinstance(line["inbounds"], list) or not line["inbounds"]:
        raise Refused("%s: `inbounds` is a list of the server's inbounds" % where)
    if not isinstance(line["link"], str):
        raise Refused("%s: `link` is a string" % where)
    placeholders(where + " link", line["link"], LINK_VALUES)
    placeholders(where + " inbounds", line["inbounds"], SERVER_VALUES)


def placeholders(where, value, allowed):
    """The placeholder names in <value>; Refused for one not in <allowed>."""
    found = set()
    if isinstance(value, str):
        found.update(PLACEHOLDER.findall(value))
    elif isinstance(value, list):
        for item in value:
            found |= placeholders(where, item, allowed)
    elif isinstance(value, dict):
        for item in value.values():
            found |= placeholders(where, item, allowed)
    bad = sorted(found - set(allowed))
    if bad:
        raise Refused("%s: placeholders %s are not %s's (it knows %s)" % (
            where, ", ".join("{%s}" % b for b in bad), "a link" if allowed == LINK_VALUES else "a server",
            ", ".join("{%s}" % a for a in allowed)))
    return found


def fill(value, values):
    """<value> with every placeholder replaced; "{x}" alone becomes values[x] as it is."""
    if isinstance(value, str):
        alone = PLACEHOLDER.fullmatch(value)
        if alone:
            return values[alone.group(1)]
        return PLACEHOLDER.sub(lambda m: str(values[m.group(1)]), value)
    if isinstance(value, list):
        return [fill(v, values) for v in value]
    if isinstance(value, dict):
        return {k: fill(v, values) for k, v in value.items()}
    return value


# ---------------------------------------------------------------- dumps

def pointer_parts(pointer):
    if not pointer.startswith("/"):
        raise Refused("a JSON pointer starts with /: %r" % pointer)
    return [p.replace("~1", "/").replace("~0", "~") for p in pointer[1:].split("/")]


def mutate(config, pointer, value):
    """A copy of <config> with the value at <pointer> replaced by <value>."""
    out = copy.deepcopy(config)
    parts = pointer_parts(pointer)
    node = out
    for part in parts[:-1]:
        node = step(node, part, pointer)
    last = parts[-1]
    if isinstance(node, list):
        index = int(last) if last.isdigit() else -1
        if not 0 <= index < len(node):
            raise Refused("the dump has nothing at %s" % pointer)
        old, node[index] = node[index], value
    elif isinstance(node, dict) and last in node:
        old, node[last] = node[last], value
    else:
        raise Refused("the dump has nothing at %s" % pointer)
    if old == value:
        raise Refused("the mutant at %s is the dump's own value" % pointer)
    return out


def step(node, part, pointer):
    if isinstance(node, list) and part.isdigit() and int(part) < len(node):
        return node[int(part)]
    if isinstance(node, dict) and part in node:
        return node[part]
    raise Refused("the dump has nothing at %s" % pointer)


def line_outbound(config):
    found = [o for o in config.get("outbounds") or [] if isinstance(o, dict) and o.get("tag") == LINE_OUTBOUND]
    if len(found) != 1:
        raise Refused("the dump has %d outbounds tagged %r, not one" % (len(found), LINE_OUTBOUND))
    return found[0]


def trusted(config, pin):
    """A copy of <config> whose line outbound pins <pin>: the one key the driver may add."""
    out = copy.deepcopy(config)
    stream = line_outbound(out).get("streamSettings") or {}
    tls = stream.get("tlsSettings")
    if stream.get("security") != "tls" or not isinstance(tls, dict):
        raise Refused("`trust: pin` on a line whose dump has no tlsSettings on its line outbound")
    for key in ("pinnedPeerCertSha256", "allowInsecure", "verifyPeerCertByName"):
        if key in tls:
            raise Refused("`trust: pin` on a dump that already says how to trust the server (%s)" % key)
    tls["pinnedPeerCertSha256"] = pin
    return out


def socks_of(config, port):
    """(port, user, password) of the dump's one SOCKS inbound, which must listen on loopback at <port>."""
    inbounds = config.get("inbounds") or []
    if len(inbounds) != 1:
        raise Refused("the dump has %d inbounds, not one" % len(inbounds))
    inbound = inbounds[0]
    settings = inbound.get("settings") or {}
    accounts = settings.get("accounts") or []
    if inbound.get("protocol") != "socks" or settings.get("auth") != "password" or len(accounts) != 1:
        raise Refused("the dump's inbound is not a SOCKS5 inbound with one login")
    if inbound.get("listen") != "127.0.0.1" or inbound.get("port") != port:
        raise Refused("the dump's SOCKS inbound is %s:%s, not 127.0.0.1:%d the run asked for" % (
            inbound.get("listen"), inbound.get("port"), port))
    return port, accounts[0]["user"], accounts[0]["pass"]


# ---------------------------------------------------------------- prepare

def free_port(used, network):
    kind = socket.SOCK_DGRAM if network == "udp" else socket.SOCK_STREAM
    for _ in range(200):
        port = random.randint(*PORTS)
        if port in used:
            continue
        try:
            with socket.socket(socket.AF_INET, kind) as s:
                s.bind(("127.0.0.1", port))
        except OSError:
            continue
        used.add(port)
        return port
    raise Refused("no free port in %d-%d" % PORTS)


def certificate(work, name, host):
    """A self-signed P-256 certificate for <host>: (cert, key, sha256 hex of its DER)."""
    cert, key = os.path.join(work, name + ".crt"), os.path.join(work, name + ".key")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                    "-days", "2", "-subj", "/CN=" + host, "-addext", "subjectAltName=DNS:" + host,
                    "-keyout", key, "-out", cert], check=True, capture_output=True)
    der = subprocess.run(["openssl", "x509", "-in", cert, "-outform", "DER"], check=True, capture_output=True).stdout
    return cert, key, hashlib.sha256(der).hexdigest()


def colons(hexdigest):
    return ":".join(hexdigest[i:i + 2] for i in range(0, len(hexdigest), 2)).upper()


def reality_keys(xray):
    """(private, public) from `xray x25519`: the values of its first two lines."""
    out = subprocess.run([xray, "x25519"], check=True, capture_output=True, text=True).stdout
    values = [line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line]
    if len(values) < 2 or not all(values[:2]):
        raise Refused("cannot read `xray x25519`: %r" % out)
    return values[0], values[1]


def prepare(work, xray, lines_dir):
    lines = load_lines(lines_dir)
    os.makedirs(work, exist_ok=True)
    private, public = reality_keys(xray)
    other_public = reality_keys(xray)[1]
    if other_public == public:
        raise Refused("`xray x25519` gave the same key twice")
    other_pin = hashlib.sha256(b"no certificate of this run").hexdigest()
    used, links, plan = set(), [], []
    certs = os.path.join(work, "certs")
    os.makedirs(certs, exist_ok=True)
    for name, line in lines:
        cert, key, pin = certificate(certs, name, line.get("tls_name", "interop.test"))
        values = {"port": free_port(used, line.get("network", "tcp")), "public_key": public,
                  "other_public_key": other_public, "pin": pin, "pin_colons": colons(pin),
                  "other_pin_colons": colons(other_pin), "private_key": private, "cert_file": cert, "key_file": key}
        socks = free_port(used, "tcp")
        link = fill(line["link"], values)
        links.append({"name": name, "link": link, "socksPort": socks})
        plan.append({"name": name, "line": line, "values": values, "socks": socks, "link": link})
    with open(os.path.join(work, "links.json"), "w") as f:
        json.dump({"lines": links}, f, indent=1)
    with open(os.path.join(work, "run.json"), "w") as f:
        json.dump({"lines": plan}, f, indent=1)
    for entry in plan:
        print("%-28s %s" % (entry["name"], entry["line"]["shape"]))
    print("%d lines; links for the builder in %s" % (len(plan), os.path.join(work, "links.json")))
    return 0


# ---------------------------------------------------------------- run

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


def start_reality_target(work):
    """What a REALITY server mirrors, as a real site answers: TLS 1.3, h2
    offered, a certificate larger than the server's own (an RSA one)."""
    cert, key = os.path.join(work, "target.crt"), os.path.join(work, "target.key")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                    "-subj", "/CN=target.interop.test", "-addext", "subjectAltName=DNS:target.interop.test",
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


def through(socks, upload, seconds):
    """None when both transfers came back right, else what went wrong."""
    port, user, password = socks
    proxy = "socks5://%s:%s@127.0.0.1:%d" % (user, password, port)
    base = ["curl", "-sS", "--max-time", str(seconds), "-x", proxy]
    down = subprocess.run(base + [THROUGH + "/down"], capture_output=True)
    if down.returncode != 0:
        return "download: curl exit %d: %s" % (down.returncode, down.stderr.decode(errors="replace").strip())
    if hashlib.sha256(down.stdout).digest() != hashlib.sha256(Page.down).digest():
        return "download: %d bytes, not the page's %d" % (len(down.stdout), len(Page.down))
    up = subprocess.run(base + ["--data-binary", "@" + upload, "-H", "Content-Type: application/octet-stream",
                                THROUGH + "/up"], capture_output=True)
    if up.returncode != 0:
        return "upload: curl exit %d: %s" % (up.returncode, up.stderr.decode(errors="replace").strip())
    with open(upload, "rb") as f:
        want = hashlib.sha256(f.read()).hexdigest()
    if up.stdout.decode(errors="replace").strip() != want:
        return "upload: the page received other bytes"
    return None


def tail(path, lines=40):
    try:
        with open(path, errors="replace") as f:
            return "".join(f.readlines()[-lines:])
    except OSError:
        return "(no log)\n"


class Cell:
    """One line (or its mutant) against one server release."""

    def __init__(self, version, row, line, expect, against=None):
        self.version, self.row, self.line, self.expect, self.against = version, row, line, expect, against
        self.reached = None     # True: the page came back right; False: it did not; None: the cell did not get that far
        self.why = None         # what went wrong, or kept the cell from running
        self.attempts, self.seconds, self.note = 0, 0.0, ""
        self.dir = None

    def verdict(self, cells):
        """(ok, text)."""
        if self.reached is None:
            return False, "FAILED: " + (self.why or "not run")
        if self.expect == "page":
            if self.reached:
                return True, "ok" + (" (try %d)" % self.attempts if self.attempts > 1 else "") + ", %.1f s" % self.seconds + self.note
            return False, "FAILED: " + (self.why or "")
        if self.reached:
            return False, "FAILED: reached the page, and must not"
        other = cells.get((self.version, self.against))
        if other is None or not other.verdict(cells)[0]:
            return False, "FAILED: proves nothing, %s did not reach the page on this server" % self.against
        return True, "refused, as it must"


def run_cell(cell, cells_dir, client, xray, server_config, client_config, socks, network, upload):
    cell.dir = os.path.join(cells_dir, "%s-%s" % (cell.version, re.sub(r"[^a-z0-9.-]+", "-", cell.row).strip("-")))
    os.makedirs(cell.dir)
    for name, config in (("server", server_config), ("client", client_config)):
        with open(os.path.join(cell.dir, name + ".json"), "wb" if isinstance(config, bytes) else "w") as f:
            f.write(config) if isinstance(config, bytes) else json.dump(config, f, indent=1)
    port = server_config["inbounds"][0]["port"]
    server = client_process = None
    start = time.monotonic()
    try:
        with open(os.path.join(cell.dir, "server.log"), "w") as log:
            server = subprocess.Popen([xray, "run", "-config", os.path.join(cell.dir, "server.json")], stdout=log, stderr=log)
        if network == "udp":
            time.sleep(1.5)
            if server.poll() is not None:
                cell.why = "the server exited at start"
                return
        elif not wait_tcp(port, server, 15):
            cell.why = "the server did not listen"
            return
        with open(os.path.join(cell.dir, "client.log"), "w") as log:
            client_process = subprocess.Popen([client, "run", "-config", os.path.join(cell.dir, "client.json")],
                                              stdout=log, stderr=log)
        if not wait_tcp(socks[0], client_process, 15):
            cell.why = "the client did not listen"
            return
        tries, seconds = (TRIES, TRY_SECONDS) if cell.expect == "page" else (1, REFUSED_SECONDS)
        for attempt in range(1, tries + 1):
            cell.attempts = attempt
            why = through(socks, upload, seconds)
            if why is None:
                cell.reached, cell.why = True, None
                break
            cell.reached, cell.why = False, why
            if attempt < tries:
                time.sleep(1)
        if client_process.poll() is not None:
            cell.reached, cell.why = None, "the client exited during the cell (code %s)" % client_process.returncode
    finally:
        cell.seconds = time.monotonic() - start
        stop(client_process)
        stop(server)


def run(work, dumps, client, servers, out=sys.stdout):
    with open(os.path.join(work, "run.json")) as f:
        plan = json.load(f)["lines"]
    if not servers:
        raise Refused("no server release to run against")
    manifest_path = os.path.join(dumps, "manifest.json")
    manifest = None
    if os.path.isfile(manifest_path):
        with open(manifest_path) as f:
            manifest = json.load(f)
    page = start_page()
    target = start_reality_target(work)
    upload = os.path.join(work, "upload.bin")
    with open(upload, "wb") as f:
        f.write(pattern(UP_BYTES, b"up"))
    cells, rows, problems = {}, [], []
    cells_dir = tempfile.mkdtemp(prefix="cells.", dir=work)

    def say(text):
        print(text, file=out, flush=True)

    # What the builder said of each line, before any server runs.
    dumped = {}
    for entry in plan:
        name, line = entry["name"], entry["line"]
        rows.append(name)
        if line["expect"] == "page":
            rows.append(name + " (mutated)")
        info = ((manifest or {}).get("lines") or {}).get(name)
        why, config, raw = None, None, None
        if manifest is None:
            why = "no manifest from the builder: the dump step did not run"
        elif info is None:
            why = "the builder's manifest does not name this line"
        elif "refusal" in info:
            why = "the builder refused the link: " + str(info["refusal"])
        elif info.get("shape") != line["shape"]:
            why = "the builder reads the link as the shape %r, the line's file says %r" % (info.get("shape"), line["shape"])
        else:
            path = os.path.join(dumps, name + ".core.json")
            try:
                with open(path, "rb") as f:
                    raw = f.read()
                config = json.loads(raw)
                socks = socks_of(config, entry["socks"])
                if line.get("trust") == "pin":
                    config = trusted(config, entry["values"]["pin"])
                mutant = mutate(config, line["mutant"]["pointer"], line["mutant"]["value"]) if line["expect"] == "page" else None
                dumped[name] = (raw, config, socks, mutant)
            except OSError:
                why = "no dump %s from the builder" % os.path.basename(path)
            except (ValueError, Refused) as e:
                why = "the dump cannot be run: %s" % e
        if why:
            dumped[name] = why
    if manifest is not None:
        for name in sorted(set(manifest.get("lines") or {}) - {e["name"] for e in plan}):
            problems.append("the builder's manifest names %s, which is no line of this run" % name)

    for version, xray in servers:
        head = subprocess.run([xray, "version"], capture_output=True, text=True).stdout.splitlines()
        say("== Xray %s: %s" % (version, head[0] if head else "(no version)"))
        values_target = {"target": target}
        for entry in plan:
            name, line = entry["name"], entry["line"]
            values = dict(entry["values"], **values_target)
            server_config = {"log": {"loglevel": "warning"}, "inbounds": fill(line["inbounds"], values), "outbounds": [{
                "tag": "direct", "protocol": "freedom",
                "settings": {"redirect": "127.0.0.1:%d" % page.server_address[1], "ipsBlocked": [],
                             "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}]}
            variants = [(name, line["expect"], None)]
            if line["expect"] == "page":
                variants.append((name + " (mutated)", "refused", name))
            for row, expect, against in variants:
                cell = Cell(version, row, name, expect, against if row != name else line.get("against"))
                cells[(version, row)] = cell
                what = dumped[name]
                if isinstance(what, str):
                    cell.why = what
                else:
                    raw, config, socks, mutant = what
                    if row != name:
                        client_config = mutant
                    elif line.get("trust") == "pin":
                        client_config = config
                        cell.note = "; the run's certificate pinned"
                    else:
                        client_config = raw  # the builder's bytes, as they are
                    run_cell(cell, cells_dir, client, xray, server_config, client_config, socks, line.get("network", "tcp"), upload)
                say("%-8s %-36s %s" % (version, row, cell.verdict(cells)[1]))

    # A shape the builder writes that no line carries to the page on every server.
    if manifest is not None:
        for shape in manifest.get("shapes") or []:
            carriers = [e["name"] for e in plan if e["line"]["shape"] == shape and e["line"]["expect"] == "page"]
            if not carriers:
                problems.append("the builder writes the shape %s and no line under lines/ carries it to the page" % shape)
            elif not any(all(cells[(v, c)].verdict(cells)[0] for v, _ in servers) for c in carriers):
                problems.append("no line of the shape %s reached the page on every server release" % shape)
        if not manifest.get("shapes"):
            problems.append("the builder's manifest names no shape")

    failed = [(key, cell) for key, cell in cells.items() if not cell.verdict(cells)[0]]
    header = "| line | " + " | ".join("Xray " + v for v, _ in servers) + " |"
    table = ["### The builder's dumps through xray-rust against official Xray servers", "",
             "A 2 MiB download and a 1 MiB upload through each line, checked by sha256; the client config is the "
             "builder's dump of the run's link. A mutated dump and a line marked to fail must not reach the page.", "",
             header, "|---|" + "---|" * len(servers)]
    for row in rows:
        table.append("| `%s` | " % row + " | ".join(cells[(v, row)].verdict(cells)[1].replace("|", "\\|") for v, _ in servers) + " |")
    if problems:
        table += [""] + ["- " + p for p in problems]
    text = "\n".join(table) + "\n"
    say(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(text + "\n")
    for (version, row), cell in failed:
        if not cell.dir:
            continue
        say("::group::%s %s: configs and logs" % (version, row))
        for name in ("server.json", "client.json", "server.log", "client.log"):
            say("--- %s%s" % (name, " (end)" if name.endswith(".log") else ""))
            say(tail(os.path.join(cell.dir, name), 40 if name.endswith(".log") else 400))
        say("::endgroup::")
    page.shutdown()
    if failed or problems:
        say("::error::%d of %d cells failed%s" % (len(failed), len(cells), ", and %d more problems" % len(problems) if problems else ""))
        return 1
    say("all %d cells passed" % len(cells))
    return 0


def parse_servers(text):
    servers = []
    for item in text.split():
        version, _, path = item.partition("=")
        if not version or not os.access(path, os.X_OK):
            raise Refused("not a <version>=<binary>: " + item)
        servers.append((version, os.path.abspath(path)))
    return servers


# ---------------------------------------------------------------- selftest

STUB = r'''#!/usr/bin/env python3
# A stand-in for both ends: `x25519`, `version`, and `run -config <file>`.
# As a server (an inbound with clients) it takes a connection whose first line
# is its client's id and relays the rest to its freedom redirect. As a client
# (a SOCKS inbound with a login) it is a SOCKS5 server that sends its
# outbound's id first, then relays to the outbound's address.
import json, os, socket, struct, sys, threading
if sys.argv[1] == "x25519":
    key = os.urandom(8).hex()
    print("PrivateKey: priv" + key); print("Password (PublicKey): pub" + key); sys.exit(0)
if sys.argv[1] == "version":
    print("Stub 0.0.0"); sys.exit(0)
config = json.load(open(sys.argv[3]))
inbound = config["inbounds"][0]
def relay(a, b):
    def pump(x, y):
        try:
            while True:
                data = x.recv(65536)
                if not data: break
                y.sendall(data)
        except OSError: pass
        finally:
            for s in (x, y):
                try: s.shutdown(socket.SHUT_RDWR)
                except OSError: pass
    threading.Thread(target=pump, args=(a, b), daemon=True).start(); pump(b, a)
def exact(conn, n):
    data = b""
    while len(data) < n:
        more = conn.recv(n - len(data))
        if not more: raise OSError("closed")
        data += more
    return data
def as_server(conn):
    line = b""
    while not line.endswith(b"\n"):
        line += exact(conn, 1)
    if line.strip().decode() != inbound["settings"]["clients"][0]["id"]:
        conn.close(); return
    host, port = config["outbounds"][0]["settings"]["redirect"].split(":")
    relay(conn, socket.create_connection((host, int(port))))
def as_client(conn):
    account = inbound["settings"]["accounts"][0]
    head = exact(conn, 2); exact(conn, head[1]); conn.sendall(b"\x05\x02")
    exact(conn, 1)
    user = exact(conn, exact(conn, 1)[0]).decode()
    password = exact(conn, exact(conn, 1)[0]).decode()
    if (user, password) != (account["user"], account["pass"]):
        conn.sendall(b"\x01\x01"); conn.close(); return
    conn.sendall(b"\x01\x00")
    request = exact(conn, 4)
    if request[3] == 1: exact(conn, 4)
    elif request[3] == 3: exact(conn, exact(conn, 1)[0])
    else: exact(conn, 16)
    exact(conn, 2)
    out = [o for o in config["outbounds"] if o["tag"] == "out"][0]
    server = out["settings"]["vnext"][0]
    upstream = socket.create_connection((server["address"], server["port"]))
    upstream.sendall((server["users"][0]["id"] + "\n").encode())
    conn.sendall(b"\x05\x00\x00\x01" + bytes(4) + struct.pack(">H", 0))
    relay(conn, upstream)
listener = socket.socket(); listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind((inbound.get("listen", "127.0.0.1"), inbound["port"])); listener.listen(16)
serve = as_client if inbound["protocol"] == "socks" else as_server
def guarded(conn):
    try: serve(conn)
    except OSError: conn.close()
while True:
    conn, _ = listener.accept()
    threading.Thread(target=guarded, args=(conn,), daemon=True).start()
'''


def stub_dump(entry):
    """What the builder would write for a stub line: a SOCKS inbound with a login and the line's outbound."""
    link = entry["link"]
    port = int(re.search(r"@127\.0\.0\.1:(\d+)", link).group(1))
    uuid = re.search(r"^stub://([^@]+)@", link).group(1)
    return {"log": {"loglevel": "warning", "access": "none"},
            "inbounds": [{"tag": "in", "protocol": "socks", "listen": "127.0.0.1", "port": entry["socksPort"],
                          "settings": {"auth": "password", "accounts": [{"user": "u-stub", "pass": "p-stub"}], "udp": True}}],
            "outbounds": [{"tag": "out", "protocol": "vless", "settings": {"vnext": [{"address": "127.0.0.1", "port": port,
                                                                                      "users": [{"id": uuid, "encryption": "none"}]}]},
                           "streamSettings": {"network": "raw", "security": "tls", "tlsSettings": {"serverName": "stub.test"}}}]}


def selftest():
    failures = []

    def check(what, ok):
        print(("ok   " if ok else "FAIL ") + what)
        if not ok:
            failures.append(what)

    def refused(what, fn):
        try:
            fn()
        except Refused as e:
            check("refuses %s (%s)" % (what, str(e)[:110]), True)
            return
        check("refuses %s" % what, False)

    # The tree's own lines: each file is a line the driver takes.
    lines = load_lines(LINES_DIR)
    check("the lines under scripts/rust-core-interop/lines/ load (%d)" % len(lines), len(lines) > 0)
    check("every line's link names its server port", all("{port}" in line["link"] for _, line in lines))
    check("a line that must fail names one that must not", all(
        dict(lines)[line["against"]]["expect"] == "page" for _, line in lines if line["expect"] == "refused"))

    good = {"shape": "s", "link": "x://{port}", "expect": "page", "inbounds": [{"port": "{port}"}],
            "mutant": {"pointer": "/a", "value": 1}}
    refused("a line with outbounds of its own", lambda: check_line("l", dict(good, outbounds=[])))
    refused("a page line with no mutant", lambda: check_line("l", {k: v for k, v in good.items() if k != "mutant"}))
    refused("a refused line with no `against`", lambda: check_line("l", dict(good, expect="refused", mutant=None)))
    refused("a server's private key in a link", lambda: check_line("l", dict(good, link="x://{port}?k={private_key}")))
    refused("an unknown placeholder", lambda: check_line("l", dict(good, inbounds=[{"port": "{prot}"}])))
    refused("a key the driver does not know", lambda: check_line("l", dict(good, expected="page")))
    refused("a name that is no file name", lambda: check_line("L/1", good))
    check("a placeholder alone becomes the value, in text it is written in",
          fill({"port": "{port}", "dest": "127.0.0.1:{port}"}, {"port": 7}) == {"port": 7, "dest": "127.0.0.1:7"})

    dump = {"inbounds": [{"protocol": "socks", "listen": "127.0.0.1", "port": 5, "settings": {
        "auth": "password", "accounts": [{"user": "u", "pass": "p"}]}}],
        "outbounds": [{"tag": "out", "settings": {"vnext": [{"users": [{"id": "a"}]}]},
                       "streamSettings": {"security": "tls", "tlsSettings": {"serverName": "n"}}}]}
    moved = mutate(dump, "/outbounds/0/settings/vnext/0/users/0/id", "b")
    check("a mutant changes the one value it points at", moved["outbounds"][0]["settings"]["vnext"][0]["users"][0]["id"] == "b"
          and mutate(moved, "/outbounds/0/settings/vnext/0/users/0/id", "a") == dump)
    refused("a mutant that points at nothing", lambda: mutate(dump, "/outbounds/0/settings/vnext/1/users", 1))
    refused("a mutant that is the dump's own value", lambda: mutate(dump, "/outbounds/0/settings/vnext/0/users/0/id", "a"))
    pinned = trusted(dump, "ab" * 32)
    unpinned = copy.deepcopy(pinned)
    del unpinned["outbounds"][0]["streamSettings"]["tlsSettings"]["pinnedPeerCertSha256"]
    check("`trust: pin` adds the pin under the line outbound's tlsSettings and nothing else",
          pinned["outbounds"][0]["streamSettings"]["tlsSettings"]["pinnedPeerCertSha256"] == "ab" * 32
          and unpinned == dump and "pinnedPeerCertSha256" not in dump["outbounds"][0]["streamSettings"]["tlsSettings"])
    refused("`trust: pin` on a dump already pinned", lambda: trusted(pinned, "cd" * 32))
    refused("`trust: pin` on a dump with no TLS", lambda: trusted(mutate(dump, "/outbounds/0/streamSettings/security", "reality"), "ab" * 32))
    check("the SOCKS port and login are the dump's", socks_of(dump, 5) == (5, "u", "p"))
    refused("a dump whose SOCKS port is not the run's", lambda: socks_of(dump, 6))
    refused("a dump with an open SOCKS inbound", lambda: socks_of(mutate(dump, "/inbounds/0/settings/auth", "noauth"), 5))

    # The whole run on stand-ins for Xray and for the Rust core.
    root = tempfile.mkdtemp(prefix="rust-core-interop-selftest.")
    try:
        stub = os.path.join(root, "stub")
        with open(stub, "w") as f:
            f.write(STUB)
        os.chmod(stub, 0o755)
        lines_dir = os.path.join(root, "lines")
        os.makedirs(lines_dir)
        server = [{"protocol": "vless", "listen": "127.0.0.1", "port": "{port}", "settings": {"clients": [{"id": "id-1"}]}}]
        stub_lines = {
            "stub": {"shape": "stub-shape", "expect": "page", "link": "stub://id-1@127.0.0.1:{port}?k={public_key}",
                     "mutant": {"pointer": "/outbounds/0/settings/vnext/0/users/0/id", "value": "id-2"}, "inbounds": server},
            "stub-wrong": {"shape": "stub-shape", "expect": "refused", "against": "stub",
                           "link": "stub://id-9@127.0.0.1:{port}", "inbounds": server},
            "stub-pinned": {"shape": "stub-shape", "expect": "page", "trust": "pin", "link": "stub://id-1@127.0.0.1:{port}",
                            "mutant": {"pointer": "/outbounds/0/settings/vnext/0/users/0/id", "value": "id-3"}, "inbounds": server},
        }
        for name, line in stub_lines.items():
            with open(os.path.join(lines_dir, name + SUFFIX), "w") as f:
                json.dump(line, f)
        work = os.path.join(root, "work")
        quiet = open(os.devnull, "w")
        stdout, sys.stdout = sys.stdout, quiet
        try:
            prepare(work, stub, lines_dir)
        finally:
            sys.stdout = stdout
        with open(os.path.join(work, "links.json")) as f:
            links = json.load(f)["lines"]
        check("prepare writes a link per line, with the run's port and key",
              sorted(e["name"] for e in links) == sorted(stub_lines)
              and all("{" not in e["link"] for e in links)
              and re.search(r"k=pub[0-9a-f]{16}$", [e for e in links if e["name"] == "stub"][0]["link"]) is not None)

        def dumps(name, edit=None, shapes=("stub-shape",), drop=(), refuse=(), shape_of=None):
            """A dumps directory as InteropDumpTest writes one, for the stand-in lines."""
            d = os.path.join(root, name)
            os.makedirs(d)
            manifest = {"shapes": list(shapes), "lines": {}}
            for entry in links:
                if entry["name"] in drop:
                    continue
                if entry["name"] in refuse:
                    manifest["lines"][entry["name"]] = {"refusal": "no such line"}
                    continue
                config = stub_dump(entry)
                if edit:
                    config = edit(entry["name"], config)
                with open(os.path.join(d, entry["name"] + ".core.json"), "w") as f:
                    json.dump(config, f, separators=(",", ":"))
                manifest["lines"][entry["name"]] = {"kind": "Stub", "shape": (shape_of or {}).get(entry["name"], "stub-shape")}
            with open(os.path.join(d, "manifest.json"), "w") as f:
                json.dump(manifest, f)
            return d

        servers = [("1.0", stub), ("2.0", stub)]

        def go(d, label):
            """(exit code, {row: [cell text per server]}, whole output) of a run on the stand-ins."""
            log = os.path.join(root, label + ".log")
            with open(log, "w") as f:
                code = run(work, d, stub, servers, out=f)
            with open(log) as f:
                text = f.read()
            table = {}
            for row in text.splitlines():
                m = re.match(r"^\| `([^`]+)` \| (.*) \|$", row)
                if m:
                    table[m.group(1)] = m.group(2).split(" | ")
            if os.environ.get("INTEROP_SELFTEST_VERBOSE"):
                print(text)
            return code, table, text

        every = ["stub", "stub (mutated)", "stub-pinned", "stub-pinned (mutated)", "stub-wrong"]
        code, table, text = go(dumps("dumps-good"), "good")
        check("the builder's dumps of the stand-in lines pass every cell (exit %d)" % code, code == 0
              and all(c.startswith("ok, ") for row in ("stub", "stub-pinned") for c in table[row])
              and all(c == "refused, as it must" for row in ("stub (mutated)", "stub-pinned (mutated)", "stub-wrong") for c in table[row])
              and all(c.endswith("; the run's certificate pinned") for c in table["stub-pinned"])
              and not any(c.endswith("pinned") for c in table["stub"]))
        check("every cell is named in the table: each line and each mutant on each server",
              sorted(table) == sorted(every) and all(len(cells) == len(servers) for cells in table.values()))
        code, table, text = go(dumps("dumps-wrong-id", edit=lambda n, c: mutate(c, "/outbounds/0/settings/vnext/0/users/0/id", "id-7") if n == "stub" else c), "wrong-id")
        check("a dump the builder got wrong fails its line, and what is held against it then proves nothing (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: download") for c in table["stub"])
              and all("proves nothing, stub did not reach the page" in c for row in ("stub (mutated)", "stub-wrong") for c in table[row])
              and all(c.startswith("ok, ") for c in table["stub-pinned"]))
        code, table, text = go(dumps("dumps-missing", drop=("stub-pinned",)), "missing")
        check("a line with no dump fails, and is not skipped (exit %d)" % code, code == 1
              and all(c == "FAILED: the builder's manifest does not name this line"
                      for row in ("stub-pinned", "stub-pinned (mutated)") for c in table[row])
              and all(c.startswith("ok, ") for c in table["stub"]))
        code, table, text = go(dumps("dumps-shape", shapes=("stub-shape", "other-shape")), "shape")
        check("a shape the builder writes that no line carries fails (exit %d)" % code, code == 1
              and "- the builder writes the shape other-shape and no line under lines/ carries it to the page" in text)
        empty = os.path.join(root, "no-dumps")
        os.makedirs(empty)
        code, table, text = go(empty, "no-dumps")
        check("with no dumps at all every cell fails, named (exit %d)" % code, code == 1 and sorted(table) == sorted(every)
              and all(c == "FAILED: no manifest from the builder: the dump step did not run" for cells in table.values() for c in cells))
        code, table, text = go(dumps("dumps-open", edit=lambda n, c: mutate(c, "/inbounds/0/settings/auth", "noauth")), "open")
        check("a dump with an open SOCKS inbound is not run (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the dump cannot be run") for cells in table.values() for c in cells))
        code, table, text = go(dumps("dumps-refused", refuse=("stub",)), "refused-by-builder")
        check("a link the builder refuses fails its cells with the builder's reason (exit %d)" % code, code == 1
              and all(c == "FAILED: the builder refused the link: no such line" for row in ("stub", "stub (mutated)") for c in table[row]))
        code, table, text = go(dumps("dumps-other-shape", shape_of={"stub-pinned": "other-shape"}), "other-shape")
        check("a line the builder reads as another shape than its file says fails (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the builder reads the link as the shape 'other-shape'") for c in table["stub-pinned"]))
    finally:
        shutil.rmtree(root, ignore_errors=True)

    if failures:
        print("%d case(s) failed" % len(failures))
        return 1
    print("all cases passed")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    pr = sub.add_parser("prepare")
    pr.add_argument("--work", required=True)
    pr.add_argument("--xray", required=True, help="an Xray binary, for `xray x25519`")
    pr.add_argument("--lines", default=LINES_DIR)
    rn = sub.add_parser("run")
    rn.add_argument("--work", required=True)
    rn.add_argument("--dumps", required=True)
    rn.add_argument("--client", required=True, help="the xray-rust CLI")
    rn.add_argument("--servers", required=True, help='"<version>=<xray binary> ..."')
    sub.add_parser("selftest")
    a = p.parse_args()
    for tool in ("curl", "openssl"):
        if not shutil.which(tool):
            sys.exit("missing " + tool)
    try:
        if a.command == "selftest":
            return selftest()
        if a.command == "prepare":
            return prepare(os.path.abspath(a.work), a.xray, a.lines)
        return run(os.path.abspath(a.work), os.path.abspath(a.dumps), os.path.abspath(a.client), parse_servers(a.servers))
    except Refused as e:
        print("::error::%s" % e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
