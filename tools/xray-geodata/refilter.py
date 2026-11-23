#!/usr/bin/env python3
"""Re:filter's list for "only blocked sites" in the form the Rust core reads:
one v2fly geodata file, refilter.dat, that routing and DNS rules name through
`ext:refilter.dat:domains` (the names) and `ext:refilter.dat:ips` (the
addresses).

  refilter.py make  [--root <repo>] [--inputs <dir>] [--refresh]
  refilter.py check [--root <repo>] [--inputs <dir>]
  refilter.py selftest

Where it comes from. Re:filter (github.com/1andrevich/Re-filter-lists, MIT)
publishes each release as text lists, as sing-box rule sets (the two .srs the
Go cores read) and as v2fly geodata: geosite.dat and geoip.dat, each with the
code REFILTER. The release is the one scripts/rule-sets.lock names on its
`refilter <tag>` line, the same release the .srs come from, so the Go cores
and the Rust core carry one list. The lock also pins the sha256 of the two
downloads (`refilter-geosite.dat <sha256>`, `refilter-geoip.dat <sha256>`)
and of the file this tool writes (`<sha256>  refilter.dat`).

What it writes. The REFILTER entry of each download, read field by field and
written again with a code of its own: DOMAINS from geosite.dat, IPS from
geoip.dat, in one file. The core indexes a geodata file by each entry's code
and decodes an entry only when a rule names it, as a GeoSite for a name rule
and as a GeoIP for an address rule, so one file holds both. Codes are upper
case because the core looks a code up in upper case. A name listed twice is
written once. Nothing else is changed: the order is the release's, and the
same release gives the same bytes. Everything is checked against what the
core at the pin accepts (crates/xray-config/src/geodata.rs): at most 200,000
names and 500,000 prefixes a code, a name of 1 to 4096 bytes, no attribute
(the app names no attribute), no reverse-matched address list, an address of
4 or 16 bytes with no bit set past its prefix. A download that holds anything
the tool does not know is refused rather than guessed at.

  make      writes sharedUI/src/commonMain/composeResources/files/xray/
            refilter.dat and its lines in scripts/rule-sets.lock. The
            downloads must hash to the lock's pins; --refresh takes what the
            release serves now and pins that instead (after the refilter tag
            moved: scripts/update-rule-sets.sh rewrites the lock without these
            lines, so run this with --refresh after it).
  check     writes nothing: the downloads must hash to the pins, the file the
            tool makes from them must be the committed refilter.dat byte for
            byte, and that file must hash to the lock's line and to the pin in
            XrayGeodata.kt (XrayGeodata.REFILTER).
  selftest  offline, on made-up lists: what is carried, what is refused, and
            that check fails on a file that differs by one byte.

--inputs <dir> reads geosite.dat and geoip.dat from <dir> instead of the
release. Needs python3 alone.
"""
import argparse
import hashlib
import ipaddress
import os
import re
import shutil
import sys
import tempfile
import time
import urllib.request

RELEASES = "https://github.com/1andrevich/Re-filter-lists/releases/download"
LOCK = "scripts/rule-sets.lock"
OUT = "sharedUI/src/commonMain/composeResources/files/xray/refilter.dat"
KOTLIN = "sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayGeodata.kt"
NAME = "refilter.dat"
SOURCE_CODE = "REFILTER"
# (download, the lock's key for its pin, the code it is written under, what it holds)
INPUTS = (("geosite.dat", "refilter-geosite.dat", "DOMAINS", "site"),
          ("geoip.dat", "refilter-geoip.dat", "IPS", "ip"))
LOCK_NOTE = "# Written by tools/xray-geodata/refilter.py from the refilter release above:"

# The core's limits (crates/xray-config/src/geodata.rs at the pin).
MAX_FILE = 128 * 1024 * 1024
MAX_ENTRY = 32 * 1024 * 1024
MAX_CODE = 256
MAX_DOMAINS = 200_000
MAX_VALUE = 4 * 1024
MAX_CIDRS = 500_000

# v2fly's Domain.Type.
PLAIN, REGEX, ROOT_DOMAIN, FULL = 0, 1, 2, 3


class Refused(Exception):
    """A download, a lock or a file the tool will not go on with."""


# ---------------------------------------------------------------- protobuf

def varint(data, i):
    value, shift = 0, 0
    while True:
        if i >= len(data):
            raise Refused("a varint runs past the end")
        byte = data[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7
        if shift > 63:
            raise Refused("a varint is too long")


def fields(data):
    """(field, wire type, value) of each field of one message."""
    i = 0
    while i < len(data):
        key, i = varint(data, i)
        field, wire = key >> 3, key & 7
        if field == 0:
            raise Refused("a field numbered 0")
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            size, i = varint(data, i)
            if i + size > len(data):
                raise Refused("a field runs past the end of its message")
            value, i = data[i:i + size], i + size
        else:
            raise Refused("wire type %d, which no geodata field has" % wire)
        yield field, wire, value


def encode_varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def length_field(field, payload):
    return encode_varint(field << 3 | 2) + encode_varint(len(payload)) + payload


def varint_field(field, value):
    # proto3 writes no field that holds its default.
    return encode_varint(field << 3) + encode_varint(value) if value else b""


# ---------------------------------------------------------------- reading

def entries(data, what):
    """{code: body} of a GeoSiteList or a GeoIPList (both: field 1, repeated)."""
    found = {}
    for field, wire, body in fields(data):
        if field != 1 or wire != 2:
            raise Refused("%s: a top-level field %d, not a list entry" % (what, field))
        code = None
        for f, w, v in fields(body):
            if f == 1 and w == 2:
                code = v.decode("utf-8")
        if code is None:
            raise Refused("%s: an entry with no code" % what)
        found.setdefault(code.upper(), body)
    return found


def one_entry(data, what):
    found = entries(data, what)
    if SOURCE_CODE not in found:
        raise Refused("%s has no code %s (it has %s)" % (what, SOURCE_CODE, ", ".join(sorted(found)) or "none"))
    return found[SOURCE_CODE]


def read_site(body, what):
    """[(type, value)] of a GeoSite, in its order, a value listed twice once."""
    out, seen = [], set()
    for field, wire, value in fields(body):
        if field == 1 and wire == 2:
            continue
        if field != 2 or wire != 2:
            raise Refused("%s: a GeoSite field %d the tool does not know" % (what, field))
        kind, name = PLAIN, None
        for f, w, v in fields(value):
            if f == 1 and w == 0:
                kind = v
            elif f == 2 and w == 2:
                name = v
            elif f == 3:
                raise Refused("%s: a name with an attribute, which the app never names" % what)
            else:
                raise Refused("%s: a Domain field %d the tool does not know" % (what, f))
        if kind not in (PLAIN, REGEX, ROOT_DOMAIN, FULL):
            raise Refused("%s: a name of type %d" % (what, kind))
        if not name or len(name) > MAX_VALUE:
            raise Refused("%s: a name of %d bytes (1 to %d)" % (what, len(name or b""), MAX_VALUE))
        text = name.decode("utf-8")
        if kind != REGEX and (not text.isascii() or text != text.lower() or any(c.isspace() for c in text)):
            raise Refused("%s: the name %r is not a lower-case ASCII name" % (what, text))
        if kind == REGEX:
            try:
                re.compile(text)
            except re.error as e:
                raise Refused("%s: the pattern %r does not compile: %s" % (what, text, e))
        if (kind, name) in seen:
            continue
        seen.add((kind, name))
        out.append((kind, name))
    if not out:
        raise Refused("%s: no name" % what)
    if len(out) > MAX_DOMAINS:
        raise Refused("%s: %d names, over the core's %d a code" % (what, len(out), MAX_DOMAINS))
    return out


def read_ip(body, what):
    """[(address bytes, prefix)] of a GeoIP, in its order."""
    out = []
    for field, wire, value in fields(body):
        if field == 1 and wire == 2:
            continue
        if field == 3 and wire == 0:
            if value:
                raise Refused("%s: a reverse-matched address list, which the rules cannot carry" % what)
            continue
        if field != 2 or wire != 2:
            raise Refused("%s: a GeoIP field %d the tool does not know" % (what, field))
        address, prefix = None, 0
        for f, w, v in fields(value):
            if f == 1 and w == 2:
                address = bytes(v)
            elif f == 2 and w == 0:
                prefix = v
            else:
                raise Refused("%s: a CIDR field %d the tool does not know" % (what, f))
        if address is None or len(address) not in (4, 16):
            raise Refused("%s: an address of %d bytes" % (what, len(address or b"")))
        try:
            ipaddress.ip_network((ipaddress.ip_address(address), prefix), strict=True)
        except ValueError as e:
            raise Refused("%s: %s" % (what, e))
        out.append((address, prefix))
    if not out:
        raise Refused("%s: no address" % what)
    if len(out) > MAX_CIDRS:
        raise Refused("%s: %d prefixes, over the core's %d a code" % (what, len(out), MAX_CIDRS))
    return out


# ---------------------------------------------------------------- writing

def site_entry(code, names):
    body = length_field(1, code.encode())
    for kind, name in names:
        body += length_field(2, varint_field(1, kind) + length_field(2, name))
    return body


def ip_entry(code, cidrs):
    body = length_field(1, code.encode())
    for address, prefix in cidrs:
        body += length_field(2, length_field(1, address) + varint_field(2, prefix))
    return body


def build(geosite, geoip):
    """refilter.dat's bytes from the two downloads' bytes."""
    out = b""
    for (download, _, code, kind), data in zip(INPUTS, (geosite, geoip)):
        body = one_entry(data, download)
        what = "%s:%s" % (download, SOURCE_CODE)
        entry = site_entry(code, read_site(body, what)) if kind == "site" else ip_entry(code, read_ip(body, what))
        if len(code) > MAX_CODE or code != code.upper():
            raise Refused("the code %r: the core looks codes up in upper case, at most %d bytes" % (code, MAX_CODE))
        if len(entry) > MAX_ENTRY:
            raise Refused("%s: an entry of %d bytes, over the core's %d" % (code, len(entry), MAX_ENTRY))
        out += length_field(1, entry)
    if len(out) > MAX_FILE:
        raise Refused("%d bytes, over the core's %d" % (len(out), MAX_FILE))
    return out


def summary(data):
    """{code: count} of a file this tool wrote, read as the core indexes it."""
    out = {}
    for code, body in entries(data, NAME).items():
        out[code] = sum(1 for f, _, _ in fields(body) if f == 2)
    return out


# ---------------------------------------------------------------- the lock

def read_lock(root):
    path = os.path.join(root, LOCK)
    try:
        with open(path) as f:
            text = f.read()
    except OSError as e:
        raise Refused("no lock: %s" % e)
    keys, sums = {}, {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and not line.startswith("#"):
            if re.fullmatch(r"[0-9a-f]{64}", parts[0]):
                sums[parts[1].lstrip("*")] = parts[0]
            else:
                keys[parts[0]] = parts[1]
    return text, keys, sums


def write_lock(root, pins, out_sha):
    text, _, _ = read_lock(root)
    ours = {key for _, key, _, _ in INPUTS}
    kept = []
    for line in text.splitlines():
        parts = line.split()
        if line == LOCK_NOTE or (len(parts) == 2 and (parts[0] in ours or parts[1] == NAME)):
            continue
        kept.append(line)
    kept.append(LOCK_NOTE)
    kept += ["%s %s" % (key, pins[key]) for _, key, _, _ in INPUTS]
    kept.append("%s  %s" % (out_sha, NAME))
    with open(os.path.join(root, LOCK), "w") as f:
        f.write("\n".join(kept) + "\n")


def kotlin_pin(root):
    try:
        with open(os.path.join(root, KOTLIN)) as f:
            text = f.read()
    except OSError as e:
        raise Refused("no %s: %s" % (KOTLIN, e))
    found = re.search(r'name\s*=\s*"%s",\s*sha256\s*=\s*"([0-9a-f]{64})"' % re.escape(NAME), text)
    if not found:
        raise Refused("%s pins no %s (File(name = \"%s\", sha256 = \"...\"))" % (KOTLIN, NAME, NAME))
    return found.group(1)


# ---------------------------------------------------------------- the downloads

def fetch(tag, name):
    url = "%s/%s/%s" % (RELEASES, tag, name)
    last = None
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "ghostlane-tools"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read(MAX_FILE + 1)
        except OSError as e:
            last = e
            time.sleep(2 * (attempt + 1))
    raise Refused("cannot fetch %s: %s" % (url, last))


def downloads(root, inputs, refresh):
    """(geosite bytes, geoip bytes, {lock key: sha256}): the inputs, each checked against its pin."""
    _, keys, _ = read_lock(root)
    tag = keys.get("refilter")
    if not tag:
        raise Refused("%s names no refilter release (a line `refilter <tag>`)" % LOCK)
    got, pins = [], {}
    for download, key, _, _ in INPUTS:
        if inputs:
            with open(os.path.join(inputs, download), "rb") as f:
                data = f.read(MAX_FILE + 1)
        else:
            data = fetch(tag, download)
        sha = hashlib.sha256(data).hexdigest()
        pinned = keys.get(key)
        if not refresh:
            if not pinned:
                raise Refused("%s pins no %s (`%s <sha256>`): run `refilter.py make --refresh`" % (LOCK, download, key))
            if pinned != sha:
                raise Refused("%s of release %s is %s, the lock pins %s" % (download, tag, sha, pinned))
        pins[key] = sha
        got.append(data)
        print("%s of Re:filter %s: %d bytes, sha256 %s" % (download, tag, len(data), sha))
    return got[0], got[1], pins


# ---------------------------------------------------------------- commands

def make(root, inputs, refresh):
    geosite, geoip, pins = downloads(root, inputs, refresh)
    data = build(geosite, geoip)
    out = os.path.join(root, OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    sha = hashlib.sha256(data).hexdigest()
    write_lock(root, pins, sha)
    print("%s: %d bytes, sha256 %s, %s" % (OUT, len(data), sha, summary(data)))
    print("XrayGeodata.REFILTER in %s must pin %s" % (KOTLIN, sha))
    return 0


def check(root, inputs):
    geosite, geoip, _ = downloads(root, inputs, refresh=False)
    want = build(geosite, geoip)
    try:
        with open(os.path.join(root, OUT), "rb") as f:
            have = f.read()
    except OSError as e:
        raise Refused("no %s: %s" % (OUT, e))
    if have != want:
        raise Refused("%s is not what the tool makes from the pinned release (%d bytes, the tool's %d): "
                      "a file edited by hand, or a refresh not recorded" % (OUT, len(have), len(want)))
    sha = hashlib.sha256(have).hexdigest()
    _, _, sums = read_lock(root)
    if sums.get(NAME) != sha:
        raise Refused("%s pins %s at %s, the file is %s" % (LOCK, NAME, sums.get(NAME), sha))
    pinned = kotlin_pin(root)
    if pinned != sha:
        raise Refused("%s pins %s at %s, the file is %s" % (KOTLIN, NAME, pinned, sha))
    print("%s is the tool's make of the pinned release: %d bytes, %s, sha256 %s" % (OUT, len(have), summary(have), sha))
    return 0


# ---------------------------------------------------------------- selftest

def made_site(code, domains, extra=b""):
    body = length_field(1, code.encode())
    for item in domains:
        body += length_field(2, item)
    return length_field(1, body + extra)


def made_domain(kind, value, attribute=False):
    out = varint_field(1, kind) + length_field(2, value.encode())
    if attribute:
        out += length_field(3, length_field(1, b"ads"))
    return out


def made_ip(code, cidrs, reverse=False):
    body = length_field(1, code.encode())
    for address, prefix in cidrs:
        body += length_field(2, length_field(1, address) + varint_field(2, prefix))
    if reverse:
        body += varint_field(3, 1)
    return length_field(1, body)


def selftest():
    failures = []

    def expect(what, ok):
        print("%s  %s" % ("ok  " if ok else "FAIL", what))
        if not ok:
            failures.append(what)

    def refused(what, fn, words):
        try:
            fn()
        except Refused as e:
            expect("refused: %s (%s)" % (what, e), words in str(e))
            return
        expect("refused: %s (it was carried)" % what, False)

    v4 = lambda text: ipaddress.ip_address(text).packed
    names = [made_domain(ROOT_DOMAIN, "blocked.example"), made_domain(FULL, "exact.example.org"),
             made_domain(PLAIN, "keyword-xyz"), made_domain(REGEX, r"^re[0-9]+\.example\.net$"),
             made_domain(ROOT_DOMAIN, "blocked.example")]
    geosite = made_site("PRIVATE", [made_domain(ROOT_DOMAIN, "local")]) + made_site("refilter", names)
    geoip = made_ip("PRIVATE", [(v4("10.0.0.0"), 8)]) + made_ip(
        "REFILTER", [(v4("198.51.100.0"), 24), (v4("203.0.113.7"), 32), (ipaddress.ip_address("2001:db8:1::").packed, 48)])

    out = build(geosite, geoip)
    found = entries(out, NAME)
    expect("one file, two codes in upper case: DOMAINS and IPS", sorted(found) == ["DOMAINS", "IPS"])
    expect("the names in the release's order, the one listed twice once",
           read_site(found["DOMAINS"], "x") == [(ROOT_DOMAIN, b"blocked.example"), (FULL, b"exact.example.org"),
                                                (PLAIN, b"keyword-xyz"), (REGEX, rb"^re[0-9]+\.example\.net$")])
    expect("the addresses as the release has them, IPv6 too",
           read_ip(found["IPS"], "x") == [(v4("198.51.100.0"), 24), (v4("203.0.113.7"), 32),
                                          (ipaddress.ip_address("2001:db8:1::").packed, 48)])
    expect("only REFILTER is carried: no PRIVATE entry", b"local" not in out and v4("10.0.0.0") not in out)
    expect("the same inputs make the same bytes", build(geosite, geoip) == out)
    # As the core indexes a file: one byte it skips, a length, then the entry,
    # whose field 1 is its code.
    i, codes = 0, []
    while i < len(out):
        size, j = varint(out, i + 1)
        codes.append([v for f, _, v in fields(out[j:j + size]) if f == 1][0])
        i = j + size
    expect("each entry is found as the core's index finds it", codes == [b"DOMAINS", b"IPS"])

    refused("no REFILTER code", lambda: build(made_site("OTHER", names), geoip), "has no code REFILTER")
    refused("a name with an attribute",
            lambda: build(made_site("REFILTER", [made_domain(ROOT_DOMAIN, "a.example", attribute=True)]), geoip), "attribute")
    refused("a name in upper case", lambda: build(made_site("REFILTER", [made_domain(ROOT_DOMAIN, "Blocked.example")]), geoip),
            "lower-case")
    refused("an empty name", lambda: build(made_site("REFILTER", [made_domain(ROOT_DOMAIN, "")]), geoip), "a name of 0 bytes")
    refused("a name over 4096 bytes",
            lambda: build(made_site("REFILTER", [made_domain(ROOT_DOMAIN, "a" * 4097)]), geoip), "a name of 4097 bytes")
    refused("a name of an unknown type", lambda: build(made_site("REFILTER", [made_domain(7, "a.example")]), geoip), "type 7")
    refused("a GeoSite field the tool does not know",
            lambda: build(made_site("REFILTER", names, extra=length_field(9, b"x")), geoip), "field 9")
    refused("a reverse-matched address list", lambda: build(geosite, made_ip("REFILTER", [(v4("192.0.2.0"), 24)], reverse=True)),
            "reverse-matched")
    refused("an address of 5 bytes", lambda: build(geosite, made_ip("REFILTER", [(b"\x01\x02\x03\x04\x05", 24)])), "5 bytes")
    refused("a prefix past the address", lambda: build(geosite, made_ip("REFILTER", [(v4("192.0.2.0"), 33)])), "")
    refused("a bit set past the prefix", lambda: build(geosite, made_ip("REFILTER", [(v4("192.0.2.1"), 24)])), "host bits")
    refused("an empty list", lambda: build(made_site("REFILTER", []), geoip), "no name")
    refused("a file cut short", lambda: build(geosite[:-3], geoip), "past the end")

    # make, then check, on a copy of the repository's layout.
    tmp = tempfile.mkdtemp()
    try:
        inputs = os.path.join(tmp, "inputs")
        os.makedirs(inputs)
        for name, data in (("geosite.dat", geosite), ("geoip.dat", geoip)):
            with open(os.path.join(inputs, name), "wb") as f:
                f.write(data)
        os.makedirs(os.path.join(tmp, os.path.dirname(LOCK)))
        with open(os.path.join(tmp, LOCK), "w") as f:
            f.write("# Written by scripts/update-rule-sets.sh. Do not edit by hand.\nrefilter 01012026\n"
                    + "0" * 64 + "  refilter-domains.srs\n")
        quiet = lambda fn: _quietly(fn)
        refused("make with no pin of the downloads", lambda: quiet(lambda: make(tmp, inputs, refresh=False)), "pins no geosite.dat")
        quiet(lambda: make(tmp, inputs, refresh=True))
        _, keys, sums = read_lock(tmp)
        with open(os.path.join(tmp, OUT), "rb") as f:
            made = f.read()
        expect("make pins both downloads and the file, and keeps the lock's other lines",
               keys.get("refilter-geosite.dat") == hashlib.sha256(geosite).hexdigest()
               and keys.get("refilter-geoip.dat") == hashlib.sha256(geoip).hexdigest()
               and sums.get(NAME) == hashlib.sha256(made).hexdigest() and keys.get("refilter") == "01012026"
               and "refilter-domains.srs" in sums)
        quiet(lambda: make(tmp, inputs, refresh=False))
        with open(os.path.join(tmp, LOCK)) as f:
            expect("make again writes its lines once", f.read().count(LOCK_NOTE) == 1)
        os.makedirs(os.path.dirname(os.path.join(tmp, KOTLIN)))
        with open(os.path.join(tmp, KOTLIN), "w") as f:
            f.write('val GEOIP_RU = File(name = "geoip-ru.txt", sha256 = "%s")\n' % ("0" * 64))
        refused("check with no pin in XrayGeodata.kt", lambda: quiet(lambda: check(tmp, inputs)), "pins no refilter.dat")
        with open(os.path.join(tmp, KOTLIN), "w") as f:
            f.write('val REFILTER = File(\n    name = "refilter.dat",\n    sha256 = "%s"\n)\n' % hashlib.sha256(made).hexdigest())
        try:
            quiet(lambda: check(tmp, inputs))
            expect("check passes on what make wrote", True)
        except Refused as e:
            expect("check passes on what make wrote (%s)" % e, False)
        with open(os.path.join(tmp, OUT), "wb") as f:
            f.write(made[:-1] + bytes([made[-1] ^ 1]))
        refused("check on a file one byte off", lambda: quiet(lambda: check(tmp, inputs)), "not what the tool makes")
        with open(os.path.join(tmp, OUT), "wb") as f:
            f.write(made)
        with open(os.path.join(inputs, "geoip.dat"), "wb") as f:
            f.write(geoip + made_ip("EXTRA", [(v4("192.0.2.0"), 24)]))
        refused("check on a download that is not the pinned one", lambda: quiet(lambda: check(tmp, inputs)), "the lock pins")
    finally:
        shutil.rmtree(tmp)

    if failures:
        print("%d of the tool's own checks failed" % len(failures))
        return 1
    print("every check of the tool passed")
    return 0


def _quietly(fn):
    stdout = sys.stdout
    sys.stdout = open(os.devnull, "w")
    try:
        return fn()
    finally:
        sys.stdout.close()
        sys.stdout = stdout


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    for name in ("make", "check"):
        p = sub.add_parser(name)
        p.add_argument("--root", default=root)
        p.add_argument("--inputs", help="read geosite.dat and geoip.dat from this directory instead of the release")
        if name == "make":
            p.add_argument("--refresh", action="store_true", help="pin what the release serves now")
    sub.add_parser("selftest")
    args = parser.parse_args(argv)
    try:
        if args.command == "make":
            return make(args.root, args.inputs, args.refresh)
        if args.command == "check":
            return check(args.root, args.inputs)
        return selftest()
    except Refused as e:
        print("refilter.py: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
