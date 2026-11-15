#!/usr/bin/env python3
"""Lab only (ghostlane lab/olc-alps): reads the ALPS server's log and sets
each client's ClientHello, ALPS and h2 preface side by side.

  analyze.py <server.jsonl> <out.json>

Ports name the clients (the workflow's): 8443 Chrome's page load, 8444
Chrome's WebSocket, 8448 Chrome's fetch(), 8445 our fetch, 8446 our
WebSocket; on the late ports (the server holds its SETTINGS) 8452 Chrome's
fetch(), 8453 Chrome's page load, 8454 our fetch. Prints a markdown
report, with when each of a connection's first h2 frames arrived; writes
the parsed connections and the comparisons to <out.json>. Exits 0: the
workflow reads the RESULT lines, among them `RESULT must-equal`, which
names the h2 fields of Chrome's fetch() and ours that differ (MUST_EQUAL).
"""
import json
import sys

CLIENTS = {
    8443: "chrome-navigation",
    8444: "chrome-websocket",
    8448: "chrome-fetch",
    8445: "ours-fetch",
    8446: "ours-websocket",
    8452: "chrome-fetch-late",
    8453: "chrome-navigation-late",
    8454: "ours-fetch-late",
}

# The fields of `compare` that must be the same for Chrome's fetch() and
# ours: what a preconnect's HTTP/2 shows an observer.
MUST_EQUAL = ("chrome-fetch", "ours-fetch", [
    "h2 SETTINGS", "h2 connection WINDOW_UPDATE", "h2 first frame types",
    "h2 pseudo-header order", "h2 HEADERS flags", "h2 HEADERS priority",
])

FRAME_NAMES = {0: "DATA", 1: "HEADERS", 2: "PRIORITY", 3: "RST_STREAM", 4: "SETTINGS", 6: "PING",
               7: "GOAWAY", 8: "WINDOW_UPDATE", 9: "CONTINUATION"}

EXT_NAMES = {
    0x0000: "server_name", 0x0005: "status_request", 0x000A: "supported_groups",
    0x000B: "ec_point_formats", 0x000D: "signature_algorithms", 0x0010: "alpn",
    0x0012: "sct", 0x0015: "padding", 0x0017: "extended_master_secret",
    0x001B: "compress_certificate", 0x0023: "session_ticket", 0x0029: "pre_shared_key",
    0x002B: "supported_versions", 0x002D: "psk_key_exchange_modes", 0x0033: "key_share",
    0x4469: "application_settings_old", 0x44CD: "application_settings",
    0xCA34: "trust_anchors", 0xFE0D: "encrypted_client_hello", 0xFF01: "renegotiation_info",
}

# RFC 7541 Appendix A, the names.
STATIC = [None, ":authority", ":method", ":method", ":path", ":path", ":scheme", ":scheme",
          ":status", ":status", ":status", ":status", ":status", ":status", ":status",
          "accept-charset", "accept-encoding", "accept-language", "accept-ranges", "accept",
          "access-control-allow-origin", "age", "allow", "authorization", "cache-control",
          "content-disposition", "content-encoding", "content-language", "content-length",
          "content-location", "content-range", "content-type", "cookie", "date", "etag",
          "expect", "expires", "from", "host", "if-match", "if-modified-since", "if-none-match",
          "if-range", "if-unmodified-since", "last-modified", "link", "location",
          "max-forwards", "proxy-authenticate", "proxy-authorization", "range", "referer",
          "refresh", "retry-after", "server", "set-cookie", "strict-transport-security",
          "transfer-encoding", "user-agent", "vary", "via", "www-authenticate"]


def grease(v):
    return (v & 0x0F0F) == 0x0A0A and (v >> 8) == (v & 0xFF)


def g(v):
    return "GREASE" if grease(v) else f"{v:04x}"


def u16s(b):
    return [int.from_bytes(b[i:i + 2], "big") for i in range(0, len(b) - 1, 2)]


def names(b):
    out, i = [], 2
    end = min(len(b), 2 + int.from_bytes(b[0:2], "big")) if len(b) >= 2 else 0
    while i < end:
        n = b[i]
        out.append(b[i + 1:i + 1 + n].decode("latin-1"))
        i += 1 + n
    return out


def parse_hello(raw):
    msg, i = b"", 0
    while i + 5 <= len(raw):
        n = int.from_bytes(raw[i + 3:i + 5], "big")
        msg += raw[i + 5:i + 5 + n]
        i += 5 + n
    body = msg[4:]
    p = 34
    sid = body[p]
    p += 1 + sid
    n = int.from_bytes(body[p:p + 2], "big")
    suites = u16s(body[p + 2:p + 2 + n])
    p += 2 + n
    p += 1 + body[p]
    end = p + 2 + int.from_bytes(body[p:p + 2], "big")
    p += 2
    exts = []
    while p + 4 <= end:
        t = int.from_bytes(body[p:p + 2], "big")
        n = int.from_bytes(body[p + 2:p + 4], "big")
        exts.append((t, body[p + 4:p + 4 + n]))
        p += 4 + n
    h = {
        "length": len(msg),
        "session_id_len": sid,
        "cipher_suites": [g(s) for s in suites],
        "extension_order": [g(t) for t, _ in exts],
        "extensions": {},
    }
    for t, d in exts:
        key = "GREASE" if grease(t) else EXT_NAMES.get(t, f"{t:04x}")
        if key == "GREASE":
            h["extensions"].setdefault("GREASE", []).append(d.hex())
            continue
        if t == 0x000A:
            v = [g(x) for x in u16s(d[2:])]
        elif t == 0x000D:
            v = [g(x) for x in u16s(d[2:])]
        elif t == 0x002B:
            v = [g(x) for x in u16s(d[1:])]
        elif t == 0x001B:
            v = [f"{x:04x}" for x in u16s(d[1:])]
        elif t == 0x002D:
            v = d[1:].hex()
        elif t in (0x0010, 0x4469, 0x44CD):
            v = {"protocols": names(d), "hex": d.hex()}
        elif t == 0x0033:
            shares, q = [], 2
            while q + 4 <= len(d):
                grp = int.from_bytes(d[q:q + 2], "big")
                n = int.from_bytes(d[q + 2:q + 4], "big")
                shares.append(f"{g(grp)}:{n}")
                q += 4 + n
            v = shares
        elif t == 0x0000:
            v = d[5:].decode("latin-1")
        elif t in (0xFE0D, 0x0015, 0x0029):
            v = {"len": len(d), "head": d[:8].hex()}
        else:
            v = d.hex()
        h["extensions"][key] = v
    return h


def hpack_names(block, limit=40):
    def integer(at, prefix):
        mask = (1 << prefix) - 1
        v = block[at] & mask
        at += 1
        if v < mask:
            return v, at
        shift = 0
        while True:
            x = block[at]
            at += 1
            v += (x & 0x7F) << shift
            shift += 7
            if not x & 0x80:
                return v, at

    def skip(at):
        n, at = integer(at, 7)
        return at + n

    def lit(at):
        huff = block[at] & 0x80
        n, at = integer(at, 7)
        s = block[at:at + n]
        return ("~huffman" if huff else s.decode("latin-1")), at + n

    out, at = [], 0
    try:
        while at < len(block) and len(out) < limit:
            x = block[at]
            if x & 0x80:
                i, at = integer(at, 7)
                out.append(STATIC[i] if i < len(STATIC) else f"dyn{i}")
            elif x & 0xE0 == 0x20:
                _, at = integer(at, 5)
                out.append("(table size update)")
            else:
                i, at = integer(at, 6 if x & 0x40 else 4)
                if i == 0:
                    name, at = lit(at)
                else:
                    name = STATIC[i] if i < len(STATIC) else f"dyn{i}"
                at = skip(at)
                out.append(name)
    except IndexError:
        out.append("(truncated)")
    return out


def parse_h2(app):
    pre = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    if not app.startswith(pre):
        return None
    at, frames = len(pre), []
    while at + 9 <= len(app):
        n = int.from_bytes(app[at:at + 3], "big")
        kind, flags = app[at + 3], app[at + 4]
        stream = int.from_bytes(app[at + 5:at + 9], "big") & 0x7FFFFFFF
        payload = app[at + 9:at + 9 + n]
        if len(payload) < n:
            break
        f = {"type": kind, "flags": flags, "stream": stream, "len": n, "end": at + 9 + n}
        if kind == 4 and not flags & 1:
            f["settings"] = [(int.from_bytes(payload[i:i + 2], "big"),
                              int.from_bytes(payload[i + 2:i + 6], "big")) for i in range(0, n, 6)]
        elif kind == 8:
            f["increment"] = int.from_bytes(payload[:4], "big") & 0x7FFFFFFF
        elif kind == 1:
            q = 0
            if flags & 0x08:
                q += 1
            if flags & 0x20:
                dep = int.from_bytes(payload[q:q + 4], "big")
                f["priority"] = {"exclusive": bool(dep >> 31), "depends_on": dep & 0x7FFFFFFF,
                                 "weight": payload[q + 4] + 1}
                q += 5
            f["names"] = hpack_names(payload[q:])
        elif kind == 2:
            f["priority_frame"] = payload.hex()
        elif kind == 0x10:
            f["priority_update"] = payload.hex()
        frames.append(f)
        at += 9 + n
    return frames


def timeline(c):
    """Each of the first h2 frames, named, and when its last byte arrived
    (milliseconds since the handshake ended)."""
    times = c.get("times") or []
    out = []
    for f in (c.get("h2") or [])[:8]:
        name = FRAME_NAMES.get(f["type"], str(f["type"]))
        if f["type"] == 4 and f["flags"] & 1:
            name = "SETTINGS-ACK"
        if f["type"] == 1:
            name += f"({f['flags']:#04x})"
        at = next((t for t, n in times if n >= f["end"]), None)
        out.append((name, None if at is None else round(at / 1000, 2)))
    return out


def summary(c):
    h = c.get("hello_parsed")
    out = {"client": c.get("client"), "handshake": c.get("handshake"), "alpn": c.get("alpn"),
           "alps_negotiated": c.get("alps"), "client_alps": c.get("peer_alps")}
    if h:
        out["alps_offered"] = c.get("alps_offered")
        out["extension_set"] = sorted(set(h["extension_order"]))
    return out


COMPARED = ["cipher_suites", "session_id_len"]


def compare(a, b):
    """Field-by-field: (field, a, b, same)."""
    rows = []
    ha, hb = a["hello_parsed"], b["hello_parsed"]
    for f in COMPARED:
        rows.append((f, ha[f], hb[f], ha[f] == hb[f]))
    sa, sb = sorted(set(ha["extension_order"])), sorted(set(hb["extension_order"]))
    rows.append(("extension set", sa, sb, sa == sb))
    for k in sorted(set(ha["extensions"]) | set(hb["extensions"])):
        va, vb = ha["extensions"].get(k), hb["extensions"].get(k)
        if k == "GREASE":
            va, vb = len(va or []), len(vb or [])
        if k in ("encrypted_client_hello", "padding", "pre_shared_key") and va and vb:
            va, vb = {"len": va["len"]}, {"len": vb["len"]}
        rows.append((f"ext {k}", va, vb, va == vb))
    rows.append(("client ALPS (EncryptedExtensions)", a.get("peer_alps"), b.get("peer_alps"),
                 a.get("peer_alps") == b.get("peer_alps")))
    fa, fb = a.get("h2") or [], b.get("h2") or []
    def pick(frames, kind):
        return [f for f in frames if f["type"] == kind]
    sa = [f.get("settings") for f in pick(fa, 4) if "settings" in f][:1]
    sb = [f.get("settings") for f in pick(fb, 4) if "settings" in f][:1]
    if fa or fb:
        rows.append(("h2 SETTINGS", sa, sb, sa == sb))
        wa = [f["increment"] for f in pick(fa, 8) if f["stream"] == 0][:1]
        wb = [f["increment"] for f in pick(fb, 8) if f["stream"] == 0][:1]
        rows.append(("h2 connection WINDOW_UPDATE", wa, wb, wa == wb))
        oa = [f["type"] for f in fa][:6]
        ob = [f["type"] for f in fb][:6]
        rows.append(("h2 first frame types", oa, ob, oa == ob))
        ha2 = (pick(fa, 1) or [{}])[0]
        hb2 = (pick(fb, 1) or [{}])[0]
        pa = [n for n in ha2.get("names", []) if n.startswith(":")]
        pb = [n for n in hb2.get("names", []) if n.startswith(":")]
        rows.append(("h2 pseudo-header order", pa, pb, pa == pb))
        rows.append(("h2 HEADERS flags", ha2.get("flags"), hb2.get("flags"),
                     ha2.get("flags") == hb2.get("flags")))
        rows.append(("h2 HEADERS priority", ha2.get("priority"), hb2.get("priority"),
                     ha2.get("priority") == hb2.get("priority")))
    return rows


def main():
    conns = []
    with open(sys.argv[1]) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            c = json.loads(line)
            if "port" not in c:
                continue
            c["client"] = CLIENTS.get(c["port"], f"port{c['port']}")
            if c.get("hello"):
                try:
                    c["hello_parsed"] = parse_hello(bytes.fromhex(c["hello"]))
                except (IndexError, ValueError) as e:
                    c["hello_parsed"] = None
                    c["hello_error"] = str(e)
            if c.get("app") and c.get("alpn") == "h2":
                c["h2"] = parse_h2(bytes.fromhex(c["app"]))
            conns.append(c)

    print("## Connections\n")
    print("| id | client | handshake | ALPS offered | ALPN | ALPS | client ALPS | requests |")
    print("|---|---|---|---|---|---|---|---|")
    for c in conns:
        print(f"| {c['id']} | {c['client']} | {c.get('handshake', '')[:90]} | {c.get('alps_offered')} "
              f"{c.get('alps_protocols', '')} | {c.get('alpn')} | {c.get('alps')} | "
              f"`{c.get('peer_alps', '')}` | {c.get('requests', '')} |")

    by = {}
    for c in conns:
        if c.get("hello_parsed"):
            by.setdefault(c["client"], []).append(c)

    # Is each client's ALPS stable across its connections?
    for name, cs in sorted(by.items()):
        values = sorted({c.get("peer_alps", "") for c in cs if c.get("alps")})
        print(f"\nRESULT alps {name} connections={len(cs)} negotiated={sum(1 for c in cs if c.get('alps'))} "
              f"client_alps={values}")
        offered = sorted({str(c.get("alps_offered")) for c in cs})
        print(f"RESULT alps-offered {name} {offered}")

    print("\n## h2 timing\n")
    print("Milliseconds since the handshake ended: when the server's SETTINGS went out, and when each "
          "of the client's first frames had arrived.\n")
    print("| id | client | server SETTINGS | client frames |")
    print("|---|---|---|---|")
    for c in conns:
        if not c.get("h2"):
            continue
        tl = timeline(c)
        settings = round(int(c.get("settings_at_us", 0)) / 1000, 2)
        frames = " ".join(f"{n}@{t}" for n, t in tl)
        print(f"| {c['id']} | {c['client']} | {settings} | {frames} |")
        print(f"RESULT timing {c['client']}#{c['id']} settings@{settings} {frames}")
        c["timeline"] = tl

    report = {"connections": conns, "comparisons": {}}
    for a, b in (("chrome-navigation", "ours-fetch"), ("chrome-fetch", "ours-fetch"),
                 ("chrome-websocket", "ours-websocket"), ("chrome-navigation", "chrome-fetch"),
                 ("chrome-fetch-late", "ours-fetch-late")):
        if a in by and b in by:
            # The first full (not resumed) handshake of each.
            ca = next((c for c in by[a] if "pre_shared_key" not in c["hello_parsed"]["extensions"]), by[a][0])
            cb = next((c for c in by[b] if "pre_shared_key" not in c["hello_parsed"]["extensions"]), by[b][0])
            rows = compare(ca, cb)
            report["comparisons"][f"{a} vs {b}"] = rows
            print(f"\n## {a} (#{ca['id']}) vs {b} (#{cb['id']})\n")
            print("| field | " + a + " | " + b + " | same |")
            print("|---|---|---|---|")
            for field, va, vb, same in rows:
                print(f"| {field} | `{json.dumps(va)[:300]}` | `{json.dumps(vb)[:300]}` | {'yes' if same else '**no**'} |")
            diffs = [r[0] for r in rows if not r[3]]
            print(f"\nRESULT compare {a} vs {b}: {len(rows) - len(diffs)} same, differ: {diffs}")
            if (a, b) == MUST_EQUAL[:2]:
                fields = {r[0]: r[3] for r in rows}
                bad = [f for f in MUST_EQUAL[2] if not fields.get(f, False)]
                print(f"RESULT must-equal {a} vs {b}: {'ok' if not bad else 'FAILED'} differ: {bad}")
    with open(sys.argv[2], "w") as f:
        json.dump(report, f, indent=1, default=str)


if __name__ == "__main__":
    main()
