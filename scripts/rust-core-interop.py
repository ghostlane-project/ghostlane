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
     them written in. <work>/links.json holds the links, for step 2, and
     each routing mode (below) with the line it rides and the resolver the
     builder is to name.
  2. The builder: sharedUI's jvmTest InteropDumpTest, with INTEROP_LINKS set
     to <work>/links.json and INTEROP_DUMPS to <dumps>, parses each link as
     the app does and writes the config the app would start the core from,
     <dumps>/<line>.core.json, with <dumps>/manifest.json naming the kind
     and shape of each link (or why the builder refused it) and every shape
     the builder writes; and for each mode <dumps>/<line>.<mode>.core.json,
     the same link under that mode, named in the manifest's "modes".
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

A VMess link is base64 of a JSON object, where no placeholder can stand. A
line's file writes it as vmess:// and the object itself, placeholders in
its strings ("port": "{port}"); prepare fills them and then encodes the
object, so the builder is handed the link as it is shared.

Routing modes. A line's cells run the builder's dump of its link under
Global. A mode is the same link written under another routing, with pages
that prove where the mode sends a name. MODES names each mode and the line
whose link carries it, here and not in the line's file: the iPhone's Mac
harness reads those files too, and the iPhone has no routing modes.

  blocked-only  "Only blocked sites": the names Re:filter lists by the line,
            every other name straight out. The list is the file the app
            bundles (refilter.dat under files/xray), copied beside the
            client's config, where the core looks for the geodata a rule
            names. Two pages through the client, fetched by name (socks5h),
            at the port of a page on loopback that only a connection made
            straight out reaches: a name under the first root domain of the
            list must bring the line's page (the server's redirect), and a
            name the list does not hold (direct.interop.test) the direct page.
            The core resolves the names itself: prepare hands the builder this
            host's own address as the network's resolver, and run answers
            there, on port 53, each name with 127.0.0.1, so a listed name
            whose rule did not match would go straight out too. The unlisted
            name is fetched first: its page proves the resolver answers.
            Port 53 needs root: when run is not root, `sudo -n` binds the
            socket in a helper that hands it back, and nothing else runs as
            root. The mutant sends the rule that names the list straight
            out: the listed name must then not bring the line's page.

A mode's cells are named "<line>, <mode>" and "<line>, <mode> (mutated)",
and are held to the same rules as a line's: no dump, a refusal, a dump that
names no list or names one the run does not have, all fail.

A second set of lines, scripts/rust-core-interop/sing-box-lines/, has the same
files for servers Xray cannot be: sing-box's, for the transports Xray removed
(VLESS over HTTP/2, `type=http`). It is its own run (`prepare --engine
sing-box`, then the builder's dump of those links, then `run --engine
sing-box --servers "<version>=<sing-box>"`). A line file there holds
`inbounds` as sing-box writes them ({listen_port} as "{port}", and the
placeholders above with {target_host} and {target_port} for a REALITY
handshake); the driver adds the one outbound, `direct`, and a route rule that
sends every connection to the page on loopback. A shape that only a line of
the other set carries is not a failure in a run: each set's shapes count as
carried for the other's run.

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
import base64
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
import struct
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LINES_DIR = os.path.join(HERE, "rust-core-interop", "lines")
SING_BOX_DIR = os.path.join(HERE, "rust-core-interop", "sing-box-lines")
ENGINES = ("xray", "sing-box")
SUFFIX = ".server.json"
NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
LINK_VALUES = ("port", "public_key", "other_public_key", "pin", "pin_colons", "other_pin_colons")
SERVER_VALUES = LINK_VALUES + ("private_key", "target", "target_host", "target_port", "cert_file", "key_file")
KEYS = {"about", "shape", "link", "expect", "against", "mutant", "network", "tls_name", "trust", "inbounds"}
LINE_OUTBOUND = "out"  # CoreConfig.LINE: the tag of the line's outbound in a dump
VMESS = "vmess://"
THROUGH = "http://203.0.113.10"
DOWN_BYTES = 2 * 1024 * 1024
UP_BYTES = 1024 * 1024
PORTS = (20000, 29999)  # below Linux's ephemeral range, where the page and the target bind
TRIES, TRY_SECONDS, REFUSED_SECONDS = 3, 30, 10

# Routing modes: each mode and the line whose link the builder writes under it.
MODES = {"blocked-only": "reality"}
# The lists the app bundles; a host puts the geodata files among them in the
# core's geodata directory.
GEODATA = os.path.normpath(os.path.join(HERE, "..", "sharedUI", "src", "commonMain", "composeResources", "files", "xray"))
# The blocked-only mode's list, and the code of its names in it.
BLOCKED_LIST, BLOCKED_NAMES = "refilter.dat", "DOMAINS"
# A name under a listed root (a root is every name under it), and one no list holds.
LISTED_LABEL, UNLISTED = "interop-k5", "direct.interop.test"
# What each name of a mode resolves to: the direct page's address.
RESOLVED = "127.0.0.1"
LINE_PAGE, DIRECT_PAGE = b"the line's page", b"the direct page"
EXT = re.compile(r"^ext:([^:]+):(.+)$")
# v2fly's Domain.Type.
PLAIN, REGEX, ROOT_DOMAIN, FULL = 0, 1, 2, 3


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


def line_shapes(directory):
    """The shapes the lines of <directory> carry to the page; none for a directory that is not there."""
    if not os.path.isdir(directory):
        return set()
    shapes = set()
    for name in os.listdir(directory):
        if name.endswith(SUFFIX):
            with open(os.path.join(directory, name)) as f:
                line = json.load(f)
            if isinstance(line, dict) and line.get("expect") == "page":
                shapes.add(line.get("shape"))
    return shapes


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
        if isinstance(mutant["value"], str) and mutant["value"] in line.get("link", ""):
            raise Refused("%s: the mutant's value %r is in the line's own link: a mutant is another value" % (where, mutant["value"]))
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
    if line["link"].startswith(VMESS + "{"):
        try:
            body = json.loads(line["link"][len(VMESS):])
        except ValueError as e:
            raise Refused("%s: a VMess link is written as vmess:// and its JSON object: %s" % (where, e))
        if not isinstance(body, dict):
            raise Refused("%s: a VMess link is written as vmess:// and its JSON object" % where)
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


def shared(link):
    """<link>, filled, as it is shared: a VMess line's JSON object base64-encoded, any other link as it is."""
    if link.startswith(VMESS + "{"):
        return VMESS + base64.b64encode(link[len(VMESS):].encode()).decode()
    return link


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


def resolver_of(config, address):
    """The port of the dump's one DNS server, which must be <address>: where the run answers the mode's names."""
    servers = (config.get("dns") or {}).get("servers") or []
    if len(servers) != 1 or not isinstance(servers[0], dict):
        raise Refused("the dump has %d DNS servers, not the one the run named" % len(servers))
    server = servers[0]
    port = server.get("port", 53)
    if server.get("address") != address or not isinstance(port, int):
        raise Refused("the dump asks names of %s:%s, not %s, the resolver the run named" % (server.get("address"), port, address))
    return port


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)


def lists_of(config):
    """The geodata files the dump names (`ext:<file>:<code>`), sorted."""
    return sorted({m.group(1) for m in map(EXT.match, strings(config)) if m})


def list_mutant(config):
    """The pointer of the routing rule that sends a listed name by the line: the one rule whose names are a geodata list's."""
    rules = (config.get("routing") or {}).get("rules") or []
    found = [i for i, rule in enumerate(rules) if isinstance(rule, dict) and rule.get("outboundTag") == LINE_OUTBOUND
             and any(EXT.match(name) for name in rule.get("domain") or [] if isinstance(name, str))]
    if len(found) != 1:
        raise Refused("the dump has %d routing rules that send a list's names by the line, not one" % len(found))
    return "/routing/rules/%d/outboundTag" % found[0]


# ---------------------------------------------------------------- geodata

def varint(data, i):
    value, shift = 0, 0
    while True:
        if i >= len(data) or shift > 63:
            raise Refused("a geodata varint runs past its end")
        byte = data[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def fields(data):
    """(field, value) of each field of one protobuf message: an int or bytes."""
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        wire = key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            size, i = varint(data, i)
            if i + size > len(data):
                raise Refused("a geodata field runs past its message")
            value, i = data[i:i + size], i + size
        else:
            raise Refused("a geodata field of wire type %d" % wire)
        yield key >> 3, value


def geosite(path, code):
    """[(type, name)] of the entry <code> of the geodata file at <path>, read as the core reads a GeoSite."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        raise Refused("the list %s: %s" % (path, e))
    for field, entry in fields(data):
        parts = list(fields(entry)) if field == 1 and isinstance(entry, bytes) else []
        if any(f == 1 and v.decode("utf-8", "replace").upper() == code for f, v in parts if isinstance(v, bytes)):
            names = []
            for f, domain in parts:
                if f == 2 and isinstance(domain, bytes):
                    d = dict(fields(domain))
                    names.append((d.get(1, PLAIN), d.get(2, b"").decode("utf-8", "replace")))
            return names
    raise Refused("the list %s holds no code %s" % (path, code))


def holds(names, name):
    """Whether one of a GeoSite's <names> matches <name>, as the core matches them."""
    for kind, value in names:
        if kind == ROOT_DOMAIN and (name == value or name.endswith("." + value)):
            return True
        if (kind == FULL and name == value) or (kind == PLAIN and value in name):
            return True
        if kind == REGEX and re.search(value, name):
            return True
    return False


def mode_names(geodata):
    """(listed, unlisted) for blocked-only: a name under the list's first root domain, and one it does not hold."""
    path = os.path.join(geodata, BLOCKED_LIST)
    names = geosite(path, BLOCKED_NAMES)
    roots = [value for kind, value in names if kind == ROOT_DOMAIN and re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+", value)]
    if not roots:
        raise Refused("%s:%s holds no root domain to fetch a page under" % (BLOCKED_LIST, BLOCKED_NAMES))
    listed = "%s.%s" % (LISTED_LABEL, roots[0])
    if holds(names, UNLISTED):
        raise Refused("%s:%s holds %s, the name the run fetches straight out" % (BLOCKED_LIST, BLOCKED_NAMES, UNLISTED))
    return listed, UNLISTED


def local_address():
    """This host's own IPv4 address towards the internet: no packet is sent to learn it."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("203.0.113.1", 9))
        except OSError as e:
            raise Refused("this host has no IPv4 route to name a resolver on: %s" % e)
        address = s.getsockname()[0]
    if address.startswith("127.") or address == "0.0.0.0":
        raise Refused("this host's address %s is loopback; the builder never names a loopback resolver" % address)
    return address


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


def plan_modes(lines, modes, geodata, resolver):
    """[{mode, line, directDns, listed, unlisted}] for each of <modes> ({mode: line})."""
    by_name = dict(lines)
    plan = []
    for mode, line in sorted(modes.items()):
        if mode not in MODES:
            raise Refused("the mode %s is none the driver has pages for (%s)" % (mode, ", ".join(sorted(MODES))))
        if line not in by_name or by_name[line]["expect"] != "page":
            raise Refused("the mode %s rides %s, which is no line under lines/ that reaches the page" % (mode, line))
        listed, unlisted = mode_names(geodata)
        plan.append({"mode": mode, "line": line, "directDns": resolver, "listed": listed, "unlisted": unlisted})
    return plan


def prepare(work, xray, lines_dir, modes=None, geodata=GEODATA, resolver=None):
    lines = load_lines(lines_dir)
    modes = MODES if modes is None else modes
    mode_plan = plan_modes(lines, modes, geodata, resolver or local_address()) if modes else []
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
        link = shared(fill(line["link"], values))
        links.append({"name": name, "link": link, "socksPort": socks})
        plan.append({"name": name, "line": line, "values": values, "socks": socks, "link": link})
    with open(os.path.join(work, "links.json"), "w") as f:
        json.dump({"lines": links, "modes": [{k: m[k] for k in ("mode", "line", "directDns")} for m in mode_plan]}, f, indent=1)
    with open(os.path.join(work, "run.json"), "w") as f:
        json.dump({"lines": plan, "modes": mode_plan}, f, indent=1)
    for entry in plan:
        print("%-28s %s" % (entry["name"], entry["line"]["shape"]))
    for entry in mode_plan:
        print("%-28s %s, names asked of %s: %s by the line, %s straight out" % (
            "%s, %s" % (entry["line"], entry["mode"]), entry["mode"], entry["directDns"], entry["listed"], entry["unlisted"]))
    print("%d lines and %d modes; links for the builder in %s" % (len(plan), len(mode_plan), os.path.join(work, "links.json")))
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
        body = self.down if self.path == "/down" else LINE_PAGE if self.path == "/which" else b"page"
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


class PageServer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A refused cell's client is cut while the page answers: that is the
        # cell's verdict, read from curl, not a fault of the page.
        if not isinstance(sys.exc_info()[1], ConnectionError):
            super().handle_error(request, client_address)


def start_page():
    Page.down = pattern(DOWN_BYTES, b"down")
    server = PageServer(("127.0.0.1", 0), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class DirectPage(http.server.BaseHTTPRequestHandler):
    """The page a mode's name reaches when it goes straight out: no server's redirect leads here."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(DIRECT_PAGE)))
        self.end_headers()
        self.wfile.write(DIRECT_PAGE)


def start_direct_page():
    server = PageServer(("127.0.0.1", 0), DirectPage)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


# Run by `sudo -n` when the driver is not root: binds the UDP socket and hands it back.
BIND_HELPER = r'''
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((sys.argv[1], int(sys.argv[2])))
u = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
u.connect(sys.argv[3])
socket.send_fds(u, [b"bound"], [s.fileno()])
'''


def bind_udp(address, port):
    """A UDP socket bound to <address>:<port>. A port under 1024 needs root:
    when this process may not bind it, `sudo -n` runs BIND_HELPER, which binds
    it and hands the socket back over a Unix socket; nothing else runs as root."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind((address, port))
        return sock
    except PermissionError:
        sock.close()
    except OSError as e:
        sock.close()
        raise Refused("cannot bind %s:%d/udp: %s" % (address, port, e))
    if os.geteuid() == 0 or not shutil.which("sudo"):
        raise Refused("cannot bind %s:%d/udp: not permitted, and no sudo to bind it" % (address, port))
    with tempfile.TemporaryDirectory(prefix="bind.") as d:
        path = os.path.join(d, "socket")
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(path)
            listener.listen(1)
            listener.settimeout(0.5)
            helper = subprocess.Popen(["sudo", "-n", sys.executable, "-I", "-c", BIND_HELPER, address, str(port), path],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            deadline, conn = time.monotonic() + 30, None
            while conn is None and time.monotonic() < deadline:
                try:
                    conn, _ = listener.accept()
                except socket.timeout:
                    if helper.poll() is not None:
                        break
            fds = []
            if conn is not None:
                with conn:
                    conn.settimeout(10)
                    _, fds, _, _ = socket.recv_fds(conn, 16, 1)
            try:
                _, err = helper.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                helper.kill()
                _, err = helper.communicate()
    if not fds:
        raise Refused("cannot bind %s:%d/udp through sudo -n: %s" % (address, port, err.decode(errors="replace").strip() or "no socket came back"))
    return socket.socket(fileno=fds[0])


def dns_answer(query, table):
    """(name, answer) for one DNS query: an A of <table>'s address for a name it holds, no record of another
    type for it, NXDOMAIN for any other name; (None, None) for what is no query of one question."""
    if len(query) < 12:
        return None, None
    ident, flags, questions = struct.unpack(">HHH", query[:6])
    if flags & 0x8000 or questions != 1:
        return None, None
    i, labels = 12, []
    while True:
        if i >= len(query):
            return None, None
        size = query[i]
        i += 1
        if size == 0:
            break
        if size & 0xC0 or i + size > len(query):
            return None, None
        labels.append(query[i:i + size])
        i += size
    if i + 4 > len(query):
        return None, None
    qtype, qclass = struct.unpack(">HH", query[i:i + 4])
    question = query[12:i + 4]
    name = b".".join(labels).decode("ascii", "replace").lower()
    address = table.get(name)
    answer = b""
    if address is not None and qtype == 1 and qclass == 1:
        answer = b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 60, 4) + socket.inet_aton(address)
    rcode = 0 if address is not None else 3
    head = struct.pack(">HHHHHH", ident, 0x8000 | (flags & 0x7900) | 0x0400 | 0x0080 | rcode, 1, 1 if answer else 0, 0, 0)
    return name, head + question + answer


class Resolver:
    """What answers a mode's names where the builder was told the network's resolver is: UDP on <address>:<port>."""

    def __init__(self, address, port, table):
        self.address, self.port, self.table, self.asked = address, port, dict(table), []
        self.sock = bind_udp(address, port)
        # A socket closed under a blocked recvfrom keeps its port: the loop wakes to see it is to stop.
        self.sock.settimeout(0.2)
        self.stopping = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while not self.stopping.is_set():
            try:
                query, peer = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            name, reply = dns_answer(query, self.table)
            if reply is None:
                continue
            self.asked.append(name)
            try:
                self.sock.sendto(reply, peer)
            except OSError:
                pass

    def said(self):
        return ", ".join(sorted(set(self.asked))) or "nothing"

    def close(self):
        self.stopping.set()
        self.thread.join(5)
        self.sock.close()


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


def warm(target, page_port):
    """One TLS handshake with the target and one request of the page before
    any cell, so that no cell is the first use of either."""
    host, port = target.rsplit(":", 1)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname, context.verify_mode = False, ssl.CERT_NONE
    with socket.create_connection((host, int(port)), timeout=10) as raw:
        with context.wrap_socket(raw, server_hostname="target.interop.test") as tls:
            if tls.version() != "TLSv1.3":
                raise Refused("the REALITY target answers %s, not TLS 1.3" % tls.version())
    with socket.create_connection(("127.0.0.1", page_port), timeout=10) as raw:
        raw.sendall(b"GET / HTTP/1.0\r\n\r\n")
        if not raw.recv(64).startswith(b"HTTP/1."):
            raise Refused("the page does not answer")


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


def routed(socks, port, pages, seconds, resolver=None):
    """None when each of <pages> [(name, page, what)] came back as that page,
    fetched by name through the client at <port>, else what went wrong."""
    socks_port, user, password = socks
    proxy = "socks5h://%s:%s@127.0.0.1:%d" % (user, password, socks_port)
    said = {LINE_PAGE: "the line's page", DIRECT_PAGE: "the direct page"}
    for name, page, what in pages:
        got = subprocess.run(["curl", "-sS", "--max-time", str(seconds), "-x", proxy, "http://%s:%d/which" % (name, port)],
                             capture_output=True)
        asked = "; the resolver was asked for %s" % resolver.said() if resolver else ""
        if got.returncode != 0:
            return "%s (%s): curl exit %d: %s%s" % (what, name, got.returncode, got.stderr.decode(errors="replace").strip(), asked)
        if got.stdout != page:
            return "%s (%s) brought %s, not %s%s" % (what, name, said.get(got.stdout, repr(got.stdout[:40])), said[page], asked)
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


def run_cell(cell, cells_dir, client, xray, server_config, client_config, socks, network, probe, beside=(), engine="xray"):
    """Runs <cell>: the server, then the client from <client_config> with the files <beside> copied next to
    it, then <probe>(seconds) (None when the page came back right, else what went wrong)."""
    cell.dir = os.path.join(cells_dir, "%s-%s" % (cell.version, re.sub(r"[^a-z0-9.-]+", "-", cell.row).strip("-")))
    os.makedirs(cell.dir)
    for name, config in (("server", server_config), ("client", client_config)):
        with open(os.path.join(cell.dir, name + ".json"), "wb" if isinstance(config, bytes) else "w") as f:
            f.write(config) if isinstance(config, bytes) else json.dump(config, f, indent=1)
    for path in beside:
        shutil.copyfile(path, os.path.join(cell.dir, os.path.basename(path)))
    inbound = server_config["inbounds"][0]
    port = inbound["listen_port"] if engine == "sing-box" else inbound["port"]
    server = client_process = None
    start = time.monotonic()
    try:
        with open(os.path.join(cell.dir, "server.log"), "w") as log:
            flag = "-c" if engine == "sing-box" else "-config"
            server = subprocess.Popen([xray, "run", flag, os.path.join(cell.dir, "server.json")], stdout=log, stderr=log)
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
            why = probe(seconds)
            if why is None:
                cell.reached, cell.why = True, None
                break
            cell.reached, cell.why = False, why
            if attempt < tries:
                time.sleep(1)
        if client_process.poll() is not None:
            cell.reached, cell.why = None, "the client exited during the cell (code %s)" % client_process.returncode
        elif cell.expect == "page" and not cell.reached:
            # Still a failure. Whether a new client process gets through says
            # whether the first one's connection was stuck or the line is.
            stop(client_process)
            with open(os.path.join(cell.dir, "client-again.log"), "w") as log:
                client_process = subprocess.Popen([client, "run", "-config", os.path.join(cell.dir, "client.json")],
                                                  stdout=log, stderr=log)
            again = probe(TRY_SECONDS) if wait_tcp(socks[0], client_process, 15) else "it did not listen"
            cell.why += "; then a new client process " + ("reached the page" if again is None else "failed too: " + again)
    finally:
        cell.seconds = time.monotonic() - start
        stop(client_process)
        stop(server)


def run(work, dumps, client, servers, out=sys.stdout, geodata=GEODATA, engine="xray"):
    with open(os.path.join(work, "run.json")) as f:
        whole = json.load(f)
    plan, modes = whole["lines"], whole.get("modes") or []
    if not servers:
        raise Refused("no server release to run against")
    manifest_path = os.path.join(dumps, "manifest.json")
    manifest = None
    if os.path.isfile(manifest_path):
        with open(manifest_path) as f:
            manifest = json.load(f)
    page = start_page()
    target = start_reality_target(work)
    warm(target, page.server_address[1])
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

    # What the builder wrote for each routing mode, and what answers its names.
    by_entry = {e["name"]: e for e in plan}
    direct = start_direct_page() if modes else None
    table = {name: RESOLVED for mode in modes for name in (mode["listed"], mode["unlisted"])}
    resolvers, dumped_modes, mode_rows = {}, {}, []
    for mode in modes:
        row = "%s, %s" % (mode["line"], mode["mode"])
        rows.extend([row, row + " (mutated)"])
        mode_rows.append((mode, row))
        entry = by_entry.get(mode["line"])
        info = ((manifest or {}).get("modes") or {}).get(mode["mode"])
        why = None
        if entry is None:
            why = "the mode rides %s, which is no line of this run" % mode["line"]
        elif manifest is None:
            why = "no manifest from the builder: the dump step did not run"
        elif info is None:
            why = "the builder's manifest names no mode %s: the builder did not write the link under it" % mode["mode"]
        elif info.get("line") != mode["line"]:
            why = "the builder wrote the mode for the line %r, not %r" % (info.get("line"), mode["line"])
        elif "refusal" in info:
            why = "the builder refused the mode: " + str(info["refusal"])
        else:
            path = os.path.join(dumps, "%s.%s.core.json" % (mode["line"], mode["mode"]))
            try:
                with open(path, "rb") as f:
                    raw = f.read()
                config = json.loads(raw)
                socks = socks_of(config, entry["socks"])
                port = resolver_of(config, mode["directDns"])
                named = lists_of(config)
                if not named:
                    raise Refused("it names no list (ext:<file>:<code>), as the mode does")
                beside = [os.path.join(geodata, n) for n in named]
                missing = [n for n, b in zip(named, beside) if not os.path.isfile(b)]
                if missing:
                    raise Refused("it names %s, which the lists in %s do not hold" % (", ".join(missing), geodata))
                if entry["line"].get("trust") == "pin":
                    config = trusted(config, entry["values"]["pin"])
                mutant = mutate(config, list_mutant(config), "direct")
                key = (mode["directDns"], port)
                if key not in resolvers:
                    try:
                        resolvers[key] = Resolver(mode["directDns"], port, table)
                    except (Refused, OSError) as e:
                        resolvers[key] = str(e)
                if isinstance(resolvers[key], str):
                    raise Refused("nothing answers its names: %s" % resolvers[key])
                dumped_modes[mode["mode"]] = (raw, config, socks, mutant, beside, resolvers[key])
            except OSError:
                why = "no dump %s from the builder" % os.path.basename(path)
            except (ValueError, Refused) as e:
                why = "the dump cannot be run: %s" % e
        if why:
            dumped_modes[mode["mode"]] = why
    if manifest is not None:
        for name in sorted(set(manifest.get("modes") or {}) - {m["mode"] for m in modes}):
            problems.append("the builder's manifest names the mode %s, which is no mode of this run" % name)

    def server_for(entry):
        host, _, target_port = target.rpartition(":")
        values = dict(entry["values"], target=target, target_host=host, target_port=int(target_port))
        if engine == "sing-box":
            return {"log": {"level": "warn"}, "inbounds": fill(entry["line"]["inbounds"], values),
                    "outbounds": [{"type": "direct", "tag": "direct"}],
                    "route": {"rules": [{"action": "route-options", "override_address": "127.0.0.1",
                                         "override_port": page.server_address[1]}], "final": "direct"}}
        return {"log": {"loglevel": "warning"}, "inbounds": fill(entry["line"]["inbounds"], values), "outbounds": [{
            "tag": "direct", "protocol": "freedom",
            "settings": {"redirect": "127.0.0.1:%d" % page.server_address[1], "ipsBlocked": [],
                         "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}]}}]}

    for version, xray in servers:
        head = subprocess.run([xray, "version"], capture_output=True, text=True).stdout.splitlines()
        say("== %s %s: %s" % ("sing-box" if engine == "sing-box" else "Xray", version, head[0] if head else "(no version)"))
        for entry in plan:
            name, line = entry["name"], entry["line"]
            server_config = server_for(entry)
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
                    run_cell(cell, cells_dir, client, xray, server_config, client_config, socks, line.get("network", "tcp"),
                             lambda seconds, s=socks: through(s, upload, seconds), engine=engine)
                say("%-8s %-36s %s" % (version, row, cell.verdict(cells)[1]))
        # Each mode: its two pages through the dump, then the mutant's listed page, which must not come.
        for mode, row in mode_rows:
            entry, what = by_entry.get(mode["line"]), dumped_modes[mode["mode"]]
            listed = (mode["listed"], LINE_PAGE, "the listed name")
            unlisted = (mode["unlisted"], DIRECT_PAGE, "the unlisted name")
            for r, expect, against in ((row, "page", None), (row + " (mutated)", "refused", row)):
                cell = Cell(version, r, mode["line"], expect, against)
                cells[(version, r)] = cell
                if isinstance(what, str):
                    cell.why = what
                else:
                    raw, config, socks, mutant, beside, resolver = what
                    if r == row:
                        client_config = config if entry["line"].get("trust") == "pin" else raw
                        pages = [unlisted, listed]
                    else:
                        client_config, pages = mutant, [listed]

                    def probe(seconds, s=socks, p=pages, res=resolver):
                        return routed(s, direct.server_address[1], p, seconds, res)
                    run_cell(cell, cells_dir, client, xray, server_for(entry), client_config, socks,
                             entry["line"].get("network", "tcp"), probe, beside, engine=engine)
                say("%-8s %-36s %s" % (version, r, cell.verdict(cells)[1]))

    # A shape the builder writes that no line carries to the page on every server.
    if manifest is not None:
        elsewhere = line_shapes(LINES_DIR if engine == "sing-box" else SING_BOX_DIR)
        for shape in manifest.get("shapes") or []:
            carriers = [e["name"] for e in plan if e["line"]["shape"] == shape and e["line"]["expect"] == "page"]
            if not carriers and shape in elsewhere:
                continue
            if not carriers:
                problems.append("the builder writes the shape %s and no line under lines/ carries it to the page" % shape)
            elif not any(all(cells[(v, c)].verdict(cells)[0] for v, _ in servers) for c in carriers):
                problems.append("no line of the shape %s reached the page on every server release" % shape)
        if not manifest.get("shapes"):
            problems.append("the builder's manifest names no shape")

    failed = [(key, cell) for key, cell in cells.items() if not cell.verdict(cells)[0]]
    label = "sing-box" if engine == "sing-box" else "Xray"
    header = "| line | " + " | ".join("%s %s" % (label, v) for v, _ in servers) + " |"
    table = ["### The builder's dumps through xray-rust against official %s servers" % label, "",
             "A 2 MiB download and a 1 MiB upload through each line, checked by sha256; the client config is the "
             "builder's dump of the run's link. A mutated dump and a line marked to fail must not reach the page. "
             "A routing mode's row fetches its names through the dump of the line's link under the mode: a listed "
             "name must bring the line's page and an unlisted one the page only a direct connection reaches.", "",
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
        for name in ("server.json", "client.json", "server.log", "client.log", "client-again.log"):
            if name == "client-again.log" and not os.path.exists(os.path.join(cell.dir, name)):
                continue
            say("--- %s%s" % (name, " (end)" if name.endswith(".log") else ""))
            say(tail(os.path.join(cell.dir, name), 40 if name.endswith(".log") else 400))
        say("::endgroup::")
    page.shutdown()
    if direct:
        direct.shutdown()
    for resolver in resolvers.values():
        if not isinstance(resolver, str):
            resolver.close()
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
# (a SOCKS inbound with a login) it is a SOCKS5 server that routes each
# request as the core would, by the config's routing: a name by its `domain`
# rules (domain:, full:, and ext:<file>:<code> read from the geodata file
# beside the config, which must be there, as the core refuses a config
# without it), then, under IPIfNonMatch, by the address its one DNS server
# answers; else the first outbound. To the line it sends the outbound's id
# first, then relays; straight out it connects to the address.
import ipaddress, json, os, re, socket, struct, sys, threading
if sys.argv[1] == "x25519":
    key = os.urandom(8).hex()
    print("PrivateKey: priv" + key); print("Password (PublicKey): pub" + key); sys.exit(0)
if sys.argv[1] == "version":
    print("Stub 0.0.0"); sys.exit(0)
config = json.load(open(sys.argv[3]))
inbound = config["inbounds"][0]
routing = config.get("routing") or {}
rules = [r for r in routing.get("rules") or [] if "inboundTag" not in r]
def varint(data, i):
    value, shift = 0, 0
    while True:
        value |= (data[i] & 0x7F) << shift
        i += 1
        if not data[i - 1] & 0x80: return value, i
        shift += 7
def fields(data):
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        if key & 7 == 0: value, i = varint(data, i)
        else:
            size, i = varint(data, i); value, i = data[i:i + size], i + size
        yield key >> 3, value
lists = {}
for rule in rules:
    for name in rule.get("domain") or []:
        m = re.match(r"^ext:([^:]+):(.+)$", name)
        if not m: continue
        path = os.path.join(os.path.dirname(os.path.abspath(sys.argv[3])), m.group(1))
        if not os.path.isfile(path): sys.exit("geodata file %s was not found" % m.group(1))
        for f, entry in fields(open(path, "rb").read()):
            parts = list(fields(entry))
            if (1, m.group(2).upper().encode()) in parts:
                lists[name] = [(dict(fields(d)).get(1, 0), dict(fields(d)).get(2, b"").decode()) for k, d in parts if k == 2]
        if name not in lists: sys.exit("geodata file %s holds no code %s" % (m.group(1), m.group(2)))
def named(entry, name):
    if entry in lists:
        return any((k == 2 and (name == v or name.endswith("." + v))) or (k == 3 and name == v) for k, v in lists[entry])
    if entry.startswith("domain:"): return name == entry[7:] or name.endswith("." + entry[7:])
    return entry.startswith("full:") and name == entry[5:]
def addressed(entry, ip):
    return not entry.startswith("ext:") and ipaddress.ip_address(ip) in ipaddress.ip_network(entry)
def first(name, ip):
    for rule in rules:
        if name is not None and any(named(e, name) for e in rule.get("domain") or []): return rule["outboundTag"]
        if ip is not None and any(addressed(e, ip) for e in rule.get("ip") or []): return rule["outboundTag"]
    return None
def resolve(name):
    server = config["dns"]["servers"][0]
    query = struct.pack(">HHHHHH", 7, 0x0100, 1, 0, 0, 0) + b"".join(
        bytes([len(l)]) + l.encode() for l in name.split(".")) + b"\0" + struct.pack(">HH", 1, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(3)
        s.sendto(query, (server["address"], server.get("port", 53)))
        try: answer = s.recv(512)
        except OSError: return None
    if answer[3] & 0x0F or struct.unpack(">H", answer[6:8])[0] < 1: return None
    return socket.inet_ntoa(answer[-4:])
def route(host, by_name):
    tag = first(host, None) if by_name else first(None, host)
    if tag is None and by_name and routing.get("domainStrategy") == "IPIfNonMatch":
        ip = resolve(host)
        if ip: tag = first(host, ip)
    return tag or config["outbounds"][0]["tag"]
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
    if request[3] == 1: host, by_name = socket.inet_ntoa(exact(conn, 4)), False
    elif request[3] == 3: host, by_name = exact(conn, exact(conn, 1)[0]).decode(), True
    else: host, by_name = socket.inet_ntop(socket.AF_INET6, exact(conn, 16)), False
    port = struct.unpack(">H", exact(conn, 2))[0]
    tag = route(host, by_name)
    if tag == "out":
        server = [o for o in config["outbounds"] if o["tag"] == "out"][0]["settings"]["vnext"][0]
        upstream = socket.create_connection((server["address"], server["port"]))
        upstream.sendall((server["users"][0]["id"] + "\n").encode())
    else:
        address = resolve(host) if by_name else host
        if address is None: conn.close(); return
        upstream = socket.create_connection((address, port))
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


def stub_mode_dump(entry, dns_port):
    """What the builder would write for a stub line under blocked-only: the line's dump with the resolver,
    a way straight out and the mode's routing, the list named as the builder names it."""
    config = stub_dump(entry)
    config["dns"] = {"servers": [{"tag": "dns-direct", "address": "127.0.0.1", "port": dns_port}]}
    config["outbounds"].append({"tag": "direct", "protocol": "freedom"})
    config["routing"] = {"domainStrategy": "IPIfNonMatch", "rules": [
        {"type": "field", "inboundTag": ["in"], "network": "tcp,udp", "port": 53, "outboundTag": "direct"},
        {"type": "field", "ip": ["10.0.0.0/8", "127.0.0.0/8"], "outboundTag": "direct"},
        {"type": "field", "domain": ["ext:%s:domains" % BLOCKED_LIST], "outboundTag": "out"},
        {"type": "field", "ip": ["ext:%s:ips" % BLOCKED_LIST], "outboundTag": "out"},
        {"type": "field", "ip": ["0.0.0.0/0", "::/0"], "outboundTag": "direct"}]}
    return config


def made_list(names, extra=b""):
    """A geodata file of one GeoSite, DOMAINS, of [(type, name)], written as tools/xray-geodata writes one."""
    def number(n):
        out = bytearray()
        while True:
            out.append((n & 0x7F) | (0x80 if n >> 7 else 0))
            n >>= 7
            if not n:
                return bytes(out)

    def field(f, payload):
        return number(f << 3 | 2) + number(len(payload)) + payload
    site = field(1, BLOCKED_NAMES.encode())
    for kind, name in names:
        site += field(2, (number(1 << 3) + number(kind) if kind else b"") + field(2, name.encode()))
    return field(1, site) + extra


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
    refused("a mutant that is the line's own value", lambda: check_line(
        "l", dict(good, link="x://id-1@h:{port}", mutant={"pointer": "/a", "value": "id-1"})))
    refused("a page line with no mutant", lambda: check_line("l", {k: v for k, v in good.items() if k != "mutant"}))
    refused("a refused line with no `against`", lambda: check_line("l", dict(good, expect="refused", mutant=None)))
    refused("a server's private key in a link", lambda: check_line("l", dict(good, link="x://{port}?k={private_key}")))
    refused("an unknown placeholder", lambda: check_line("l", dict(good, inbounds=[{"port": "{prot}"}])))
    refused("a key the driver does not know", lambda: check_line("l", dict(good, expected="page")))
    refused("a name that is no file name", lambda: check_line("L/1", good))
    check("a placeholder alone becomes the value, in text it is written in",
          fill({"port": "{port}", "dest": "127.0.0.1:{port}"}, {"port": 7}) == {"port": 7, "dest": "127.0.0.1:7"})
    vmess = shared(fill('vmess://{"add":"127.0.0.1","port":"{port}","id":"id-1"}', {"port": 7}))
    check("a VMess line's JSON object is filled, then shared as base64",
          vmess.startswith(VMESS) and "{" not in vmess
          and json.loads(base64.b64decode(vmess[len(VMESS):])) == {"add": "127.0.0.1", "port": "7", "id": "id-1"})
    check("any other link is shared as it is written", shared("vless://id-1@127.0.0.1:7?k=v") == "vless://id-1@127.0.0.1:7?k=v")
    refused("a VMess line whose JSON is not an object", lambda: check_line("l", dict(good, link='vmess://{"port":"{port}"')))

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

    # Routing modes: the list, the names, the resolver's answers and the mutant.
    geo_root = tempfile.mkdtemp(prefix="rust-core-interop-geodata.")
    try:
        geo = os.path.join(geo_root, "lists")
        os.makedirs(geo)
        with open(os.path.join(geo, BLOCKED_LIST), "wb") as f:
            f.write(made_list([(ROOT_DOMAIN, "blocked.example"), (FULL, "only.example"), (REGEX, "^re[0-9]+\\.example$")]))
        names = geosite(os.path.join(geo, BLOCKED_LIST), BLOCKED_NAMES)
        check("the list's names are read as the core reads a GeoSite", names == [
            (ROOT_DOMAIN, "blocked.example"), (FULL, "only.example"), (REGEX, "^re[0-9]+\\.example$")])
        check("a root holds every name under it, and only those", holds(names, "a.blocked.example")
              and holds(names, "blocked.example") and not holds(names, "notblocked.example")
              and not holds(names, "a.only.example") and holds(names, "re7.example"))
        check("the mode fetches a name under the list's first root, and one the list does not hold",
              mode_names(geo) == ("interop-k5.blocked.example", "direct.interop.test"))
        with open(os.path.join(geo_root, "holds-unlisted.dat"), "wb") as f:
            f.write(made_list([(ROOT_DOMAIN, "interop.test")]))
        os.makedirs(os.path.join(geo_root, "bad"))
        os.replace(os.path.join(geo_root, "holds-unlisted.dat"), os.path.join(geo_root, "bad", BLOCKED_LIST))
        refused("a list that holds the name fetched straight out", lambda: mode_names(os.path.join(geo_root, "bad")))
        refused("a list with no such code", lambda: geosite(os.path.join(geo, BLOCKED_LIST), "IPS"))
        refused("a run with no list", lambda: mode_names(geo_root))
        query = struct.pack(">HHHHHH", 9, 0x0100, 1, 0, 0, 0) + b"\x05a-b-c\x07example\x00"
        name, answer = dns_answer(query + struct.pack(">HH", 1, 1), {"a-b-c.example": "127.0.0.1"})
        check("a name of the run's is answered with its address", name == "a-b-c.example" and answer[:2] == query[:2]
              and answer[3] & 0x0F == 0 and answer[6:8] == b"\x00\x01" and answer[-4:] == b"\x7f\x00\x00\x01")
        name, answer = dns_answer(query + struct.pack(">HH", 28, 1), {"a-b-c.example": "127.0.0.1"})
        check("another type of a name of the run's has no record", answer[3] & 0x0F == 0 and answer[6:8] == b"\x00\x00")
        name, answer = dns_answer(query + struct.pack(">HH", 1, 1), {"other.example": "127.0.0.1"})
        check("a name that is not the run's does not exist", answer[3] & 0x0F == 3 and answer[6:8] == b"\x00\x00")
        check("what is no query gets no answer", dns_answer(answer, {}) == (None, None) and dns_answer(b"\x00" * 5, {}) == (None, None))
        port = free_port(set(), "udp")
        resolver = Resolver("127.0.0.1", port, {"a-b-c.example": "127.0.0.1"})
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.settimeout(5)
                s.sendto(query + struct.pack(">HH", 1, 1), ("127.0.0.1", port))
                reply = s.recv(512)
            check("the resolver answers over UDP, and says what it was asked", reply[-4:] == b"\x7f\x00\x00\x01"
                  and resolver.said() == "a-b-c.example")
        finally:
            resolver.close()
        mode_dump = stub_mode_dump({"link": "stub://id-1@127.0.0.1:7", "socksPort": 5}, 53)
        check("the mutant is the rule that sends the list's names by the line", list_mutant(mode_dump) == "/routing/rules/2/outboundTag"
              and lists_of(mode_dump) == [BLOCKED_LIST])
        refused("a mode's dump whose list does not go by the line", lambda: list_mutant(mutate(mode_dump, "/routing/rules/2/outboundTag", "direct")))
        check("the resolver's port is the dump's", resolver_of(mode_dump, "127.0.0.1") == 53)
        refused("a mode's dump that asks another resolver than the run's", lambda: resolver_of(mode_dump, "127.0.0.2"))
        lines_here = [("l", {"expect": "page"}), ("w", {"expect": "refused"})]
        refused("a mode on no line", lambda: plan_modes(lines_here, {"blocked-only": "none"}, geo, "127.0.0.1"))
        refused("a mode on a line that must not reach the page", lambda: plan_modes(lines_here, {"blocked-only": "w"}, geo, "127.0.0.1"))
        refused("a mode the driver has no pages for", lambda: plan_modes(lines_here, {"other-mode": "l"}, geo, "127.0.0.1"))
        check("the driver's own mode rides a line under lines/ that reaches the page",
              all(dict(lines).get(line, {}).get("expect") == "page" for line in MODES.values()))
        check("the run's list is the one the app bundles", os.path.isfile(os.path.join(GEODATA, BLOCKED_LIST)))
    finally:
        shutil.rmtree(geo_root, ignore_errors=True)

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
        geo = os.path.join(root, "lists")
        os.makedirs(geo)
        with open(os.path.join(geo, BLOCKED_LIST), "wb") as f:
            f.write(made_list([(ROOT_DOMAIN, "blocked.example")]))
        work = os.path.join(root, "work")
        quiet = open(os.devnull, "w")
        stdout, sys.stdout = sys.stdout, quiet
        try:
            prepare(work, stub, lines_dir, modes={"blocked-only": "stub"}, geodata=geo, resolver="127.0.0.1")
        finally:
            sys.stdout = stdout
        with open(os.path.join(work, "links.json")) as f:
            written = json.load(f)
        links = written["lines"]
        check("prepare writes a link per line, with the run's port and key",
              sorted(e["name"] for e in links) == sorted(stub_lines)
              and all("{" not in e["link"] for e in links)
              and re.search(r"k=pub[0-9a-f]{16}$", [e for e in links if e["name"] == "stub"][0]["link"]) is not None)
        with open(os.path.join(work, "run.json")) as f:
            planned = json.load(f)["modes"]
        check("prepare writes each mode with its line and the resolver for the builder, and its names for the run",
              written["modes"] == [{"mode": "blocked-only", "line": "stub", "directDns": "127.0.0.1"}]
              and [(m["listed"], m["unlisted"]) for m in planned] == [("interop-k5.blocked.example", UNLISTED)])
        dns_port = free_port(set(), "udp")

        def dumps(name, edit=None, shapes=("stub-shape",), drop=(), refuse=(), shape_of=None, mode=True, edit_mode=None):
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
            if mode:
                entry = [e for e in links if e["name"] == "stub"][0]
                config = stub_mode_dump(entry, dns_port)
                for change in (edit_mode, edit):
                    if change:
                        config = change("stub.blocked-only", config)
                with open(os.path.join(d, "stub.blocked-only.core.json"), "w") as f:
                    json.dump(config, f, separators=(",", ":"))
                manifest["modes"] = {"blocked-only": {"line": "stub", "config": "stub.blocked-only.core.json"}}
            with open(os.path.join(d, "manifest.json"), "w") as f:
                json.dump(manifest, f)
            return d

        servers = [("1.0", stub), ("2.0", stub)]

        def go(d, label, lists=geo):
            """(exit code, {row: [cell text per server]}, whole output) of a run on the stand-ins."""
            log = os.path.join(root, label + ".log")
            with open(log, "w") as f:
                code = run(work, d, stub, servers, out=f, geodata=lists)
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

        moded = "stub, blocked-only"
        every = ["stub", "stub (mutated)", "stub-pinned", "stub-pinned (mutated)", "stub-wrong", moded, moded + " (mutated)"]
        code, table, text = go(dumps("dumps-good"), "good")
        check("the builder's dumps of the stand-in lines pass every cell (exit %d)" % code, code == 0
              and all(c.startswith("ok, ") for row in ("stub", "stub-pinned", moded) for c in table[row])
              and all(c == "refused, as it must" for row in ("stub (mutated)", "stub-pinned (mutated)", "stub-wrong", moded + " (mutated)")
                      for c in table[row])
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
        code, table, text = go(dumps("dumps-no-mode", mode=False), "no-mode")
        check("a mode the builder did not write fails both its rows, named, and no line's (exit %d)" % code, code == 1
              and all(c == "FAILED: the builder's manifest names no mode blocked-only: the builder did not write the link under it"
                      for row in (moded, moded + " (mutated)") for c in table[row])
              and all(c.startswith("ok, ") for c in table["stub"]))

        def all_by_the_line(name, config):
            rules = config["routing"]["rules"]
            del rules[1]
            rules[-1]["outboundTag"] = "out"
            return config
        code, table, text = go(dumps("dumps-mode-by-line", edit_mode=all_by_the_line), "mode-by-line")
        check("a mode that sends the unlisted name by the line fails, and its mutant then proves nothing (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the unlisted name (direct.interop.test) brought the line's page, not the direct page")
                      for c in table[moded])
              and all("proves nothing, %s did not reach the page" % moded in c for c in table[moded + " (mutated)"]))
        def listed_direct(name, config):
            config["routing"]["rules"].insert(2, {"type": "field", "domain": ["domain:blocked.example"], "outboundTag": "direct"})
            return config
        code, table, text = go(dumps("dumps-mode-listed-direct", edit_mode=listed_direct), "mode-listed-direct")
        check("a mode that sends the listed name straight out fails (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the listed name (interop-k5.blocked.example) brought the direct page, not the line's page")
                      for c in table[moded]))
        bare = os.path.join(root, "no-lists")
        os.makedirs(bare)
        code, table, text = go(dumps("dumps-mode-no-list"), "mode-no-list", lists=bare)
        check("a mode whose list the run does not have fails, named (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the dump cannot be run: it names %s, which the lists in" % BLOCKED_LIST)
                      for row in (moded, moded + " (mutated)") for c in table[row]))
        code, table, text = go(dumps("dumps-mode-other-resolver", edit_mode=lambda n, c: mutate(
            c, "/dns/servers/0/address", "127.0.0.2")), "mode-other-resolver")
        check("a mode's dump that asks another resolver than the run's fails, named (exit %d)" % code, code == 1
              and all(c.startswith("FAILED: the dump cannot be run: the dump asks names of 127.0.0.2")
                      for row in (moded, moded + " (mutated)") for c in table[row]))
        d = dumps("dumps-mode-no-file")
        os.remove(os.path.join(d, "stub.blocked-only.core.json"))
        code, table, text = go(d, "mode-no-file")
        check("a mode the manifest names with no dump fails, named (exit %d)" % code, code == 1
              and all(c == "FAILED: no dump stub.blocked-only.core.json from the builder" for row in (moded, moded + " (mutated)")
                      for c in table[row]))
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
    pr.add_argument("--lines", default=None, help="the lines' directory (default: the engine's)")
    pr.add_argument("--engine", choices=ENGINES, default="xray", help="whose servers the lines are for")
    rn = sub.add_parser("run")
    rn.add_argument("--work", required=True)
    rn.add_argument("--dumps", required=True)
    rn.add_argument("--client", required=True, help="the xray-rust CLI")
    rn.add_argument("--servers", required=True, help='"<version>=<xray binary> ..."')
    rn.add_argument("--engine", choices=ENGINES, default="xray", help="whose servers --servers names")
    sub.add_parser("selftest")
    a = p.parse_args()
    for tool in ("curl", "openssl"):
        if not shutil.which(tool):
            sys.exit("missing " + tool)
    try:
        if a.command == "selftest":
            return selftest()
        if a.command == "prepare":
            lines = a.lines or (SING_BOX_DIR if a.engine == "sing-box" else LINES_DIR)
            # The routing modes ride a line of the Xray set.
            return prepare(os.path.abspath(a.work), a.xray, lines, modes={} if a.engine == "sing-box" else None)
        return run(os.path.abspath(a.work), os.path.abspath(a.dumps), os.path.abspath(a.client), parse_servers(a.servers),
                   engine=a.engine)
    except Refused as e:
        print("::error::%s" % e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
