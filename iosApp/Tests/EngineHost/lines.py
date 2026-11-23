"""The lines the Mac host fetches, and the local servers they reach.

A line is a reference config the app's builder writes for one case of one
kind (sharedUI/src/jvmTest/resources/reference-configs): ios-core-<shape>.json
for the case named after a shape, core-<case>.json for every other case. Its
server is K5's (scripts/rust-core-interop/lines/<line>.server.json, the
servers scripts/rust-core-interop.py runs the builder's dumps against): the
file named after the line, or else the one line of the reference's shape
whose server's settings are the reference's (a Shadowsocks cipher). No list
here names a line: a kind joins by its reference and its server's file.

  lines.py lines <reference-dir> <lines-dir>
      prints `<line> <reference> <server-line> <tcp|udp>` for every line,
      and a FAIL line on stderr for a reference with no server, or with more
      than one, and for a shape K5 carries that no line here does; exits 1
      after any of those.
  lines.py names <reference-dir> <lines-dir>
      the server names the lines send (TLS and REALITY), as an openssl
      subjectAltName: the local servers' certificate is made for them all.
  lines.py line <line> <reference-dir> <lines-dir> <out-dir> <server-host>
                <socks-port> <server-port> <target-port> <page-port>
                <cert.pem> <key.pem> <private-key> <public-key>
                <wrong-public-key>
      writes <out-dir>/<line>.server.json (K5's server for the line on
      <server-host>: the address of the interface the engine's sockets are
      pinned to, which cannot reach loopback), <line>.client.json (the
      reference pointed at it) and the line's refusals, each a client the
      server must not let through, <line>.<refusal>.json, listed in
      <line>.refusals as `<refusal><TAB><what it changed>`; prints the
      transport the server listens on (tcp or udp).
  lines.py selftest <reference-dir> <lines-dir> <work-dir>
      every line of the tree made with stand-in values and read back, and
      each refusal of a line or of a server that does not fit it, in copies
      under <work-dir>.
  lines.py socks <core.json> <out.json> <socks-port>
      a copy of a core.json whose SOCKS inbound listens on another port, as
      scripts/check-rust-core-configs.sh starts them: a run of starts never
      waits for the last one's port.
  lines.py refuse <core.json> <out.json> <socks-port> <field>
      the same copy with a field no core.json has, on the line's outbound:
      the core refuses a whole config over one field it does not know.
  lines.py rules <bundled-lists.json> <out.txt>
      writes the olcRTC engine's direct rules for the lists in that config,
      in the app's order (OlcrtcDirectRules.text): the private ranges, the
      names the engine takes (domain:, full:), the address list.
  lines.py pin <cert.pem>
      prints the sha256 of the certificate's DER, in hex.

The client is the app's line: nothing of it but its server's address and
port, its SOCKS port, the REALITY public key and the certificate pin is
changed. A line whose TLS waives the certificate checks (allowInsecure) is
given no pin; every other TLS line trusts the local server's self-signed
certificate by its pin, as K5's `trust: pin` does. The server is K5's with
the run's ports, keys and certificate, listening on <server-host>, and told
what the line sends that K5's link spells otherwise: its credential (the
leaves of the server's `settings` under the key K5's mutant points at), the
server name (REALITY's list, and TLS's where the server names one), REALITY's
short id, and the Host a transport's server checks.
Where else the line and its server both name a thing (the protocol, the
transport, the security, a path or a gRPC service), they must agree.

The refusals: the credential K5's mutant puts in the line's place; for
REALITY a public key the server does not hold; for TLS a pin of another
certificate and no pin at all (the core trusts only public roots); for a line
that waives the checks, the same line without the waiver.
"""

import copy
import hashlib
import json
import os
import re
import shutil
import ssl
import sys
import urllib.parse

# The reference of a case, under Global: ios-core-<shape>.json for the case
# named after a shape (CoreCase.reference), core-<case>.json for every other.
CASE_PREFIXES = ("ios-core-", "core-")
SUFFIX = ".server.json"
LINE_OUTBOUND = "out"  # CoreConfig.LINE
PLACEHOLDER = re.compile(r"\{([a-z_]+)\}")
# The REALITY public key, the pin and the waiver: the client's fields a
# refusal changes, and the only ones of its stream this program writes.
WAIVER = "allowInsecure"
PIN = "pinnedPeerCertSha256"


class Problem(Exception):
    """A reference, a server's file or a pairing this program will not run."""


# An address in TEST-NET-3, public to the line's rules, so the request takes
# the tunnel; the server's freedom outbound sends it to the page on loopback.
# `finalRules` allows loopback from Xray 26.7 on, `ipsBlocked: []` up to 26.4;
# each version ignores the other's key. K5's driver writes the same.
def freedom_to_page(page_port):
    return {
        "tag": "direct",
        "protocol": "freedom",
        "settings": {
            "redirect": f"127.0.0.1:{page_port}",
            "ipsBlocked": [],
            "finalRules": [{"action": "allow", "ip": ["127.0.0.0/8"]}],
        },
    }


def pin(cert_path):
    with open(cert_path, encoding="ascii") as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    return hashlib.sha256(der).hexdigest()


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(path, body):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(body, f, indent=1)


def outbound(config):
    found = [o for o in config.get("outbounds") or [] if isinstance(o, dict) and o.get("tag") == LINE_OUTBOUND]
    if len(found) != 1:
        raise Problem(f"{len(found)} outbounds tagged {LINE_OUTBOUND}, not one")
    return found[0]


def parts(pointer):
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise Problem(f"not a JSON pointer: {pointer!r}")
    return [p.replace("~1", "/").replace("~0", "~") for p in pointer[1:].split("/")]


def walk(config, path, pointer):
    node = config
    for part in path:
        if isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        elif isinstance(node, dict) and part in node:
            node = node[part]
        else:
            raise Problem(f"nothing at {pointer}")
    return node


def at(config, pointer):
    return walk(config, parts(pointer), pointer)


def put(config, pointer, value):
    *path, last = parts(pointer)
    node = walk(config, path, pointer)
    if isinstance(node, list) and last.isdigit() and int(last) < len(node):
        node[int(last)] = value
    elif isinstance(node, dict) and last in node:
        node[last] = value
    else:
        raise Problem(f"nothing at {pointer}")


def strings(node):
    """Every string under <node>."""
    if isinstance(node, str):
        return [node]
    if isinstance(node, dict):
        node = list(node.values())
    if isinstance(node, list):
        return [s for value in node for s in strings(value)]
    return []


def leaves(node, key):
    """Every dict under <node> that holds <key>, depth first."""
    found = []
    if isinstance(node, dict):
        if key in node:
            found.append(node)
        for value in node.values():
            found += leaves(value, key)
    elif isinstance(node, list):
        for value in node:
            found += leaves(value, key)
    return found


def fill(value, values):
    """K5's placeholders: "{x}" alone becomes values[x] as it is; inside text, its text."""
    if isinstance(value, str):
        alone = PLACEHOLDER.fullmatch(value)
        if alone:
            if alone.group(1) not in values:
                raise Problem(f"a placeholder this program does not fill: {value}")
            return values[alone.group(1)]

        def one(match):
            if match.group(1) not in values:
                raise Problem(f"a placeholder this program does not fill: {match.group(0)}")
            return str(values[match.group(1)])

        return PLACEHOLDER.sub(one, value)
    if isinstance(value, list):
        return [fill(v, values) for v in value]
    if isinstance(value, dict):
        return {k: fill(v, values) for k, v in value.items()}
    return value


# ---------------------------------------------------------------- the lines

def references(ref_dir):
    """{line: reference file name} for every case's reference under Global."""
    found = {}
    files = sorted(n for n in os.listdir(ref_dir) if n.endswith(".json"))
    # The shapes first (the iPhone's own tests read those names), then the other cases.
    for prefix in CASE_PREFIXES:
        for name in files:
            if name.startswith(prefix):
                line = name[len(prefix):-len(".json")]
                if line in found:
                    raise Problem(f"{found[line]} and {name} are both the line {line}")
                found[line] = name
    if not found:
        raise Problem(f"no reference ({', '.join(p + '*.json' for p in CASE_PREFIXES)}) in {ref_dir}")
    return found


def servers(lines_dir):
    """{name: K5's line} from <lines-dir>/*.server.json."""
    found = {}
    for name in sorted(os.listdir(lines_dir)):
        if name.endswith(SUFFIX):
            spec = load(os.path.join(lines_dir, name))
            if not isinstance(spec, dict) or not isinstance(spec.get("inbounds"), list):
                raise Problem(f"lines/{name}: not a line of K5's (no inbounds)")
            found[name[:-len(SUFFIX)]] = spec
    if not found:
        raise Problem(f"no *{SUFFIX} in {lines_dir}")
    return found


def credential_key(spec, where):
    """The key K5's mutant changes: the line's credential, on the client and on the server."""
    mutant = spec.get("mutant")
    if not isinstance(mutant, dict) or "pointer" not in mutant or "value" not in mutant:
        raise Problem(f"{where}: a line that reaches the page names its mutant")
    return parts(mutant["pointer"])[-1]


def inbound_of(spec, where):
    inbounds = spec["inbounds"]
    if len(inbounds) != 1 or not isinstance(inbounds[0], dict):
        raise Problem(f"{where}: {len(inbounds)} inbounds; the host runs a server of one")
    return inbounds[0]


def server_of(line, reference, specs):
    """The name of K5's line whose server the line reaches."""
    if line in specs:
        if specs[line].get("expect") != "page":
            raise Problem(f"lines/{line}{SUFFIX} is a line that must not reach the page; a reference is one that must")
        return line
    ours = outbound(reference).get("settings") or {}
    fits = []
    for name, spec in sorted(specs.items()):
        if spec.get("shape") != line or spec.get("expect") != "page":
            continue
        key = credential_key(spec, f"lines/{name}{SUFFIX}")
        theirs = inbound_of(spec, f"lines/{name}{SUFFIX}").get("settings") or {}
        if all(theirs[k] == ours[k] for k in theirs if k in ours and k != key):
            fits.append(name)
    if len(fits) == 1:
        return fits[0]
    if not fits:
        raise Problem(f"no server for it: no lines/{line}{SUFFIX}, and no line of the shape {line} whose server's settings are the line's")
    raise Problem(f"{len(fits)} lines of the shape {line} fit it ({', '.join(fits)}): name its server lines/{line}{SUFFIX}")


def plan(ref_dir, lines_dir):
    """([(line, reference, server line, network)], [problems])."""
    refs = references(ref_dir)
    specs = servers(lines_dir)
    found, problems = [], []
    for line, name in refs.items():
        try:
            server = server_of(line, load(os.path.join(ref_dir, name)), specs)
            found.append((line, name, server, specs[server].get("network", "tcp")))
        except Problem as e:
            problems.append(f"{name}: {e}")
    carried = {specs[server].get("shape") for _, _, server, _ in found}
    for shape in sorted({s.get("shape") for s in specs.values() if s.get("expect") == "page"} - carried):
        names = sorted(n for n, s in specs.items() if s.get("shape") == shape and s.get("expect") == "page")
        problems.append(f"K5 carries the shape {shape} ({', '.join(names)}) and no reference line here reaches its server")
    return found, problems


def lines(args):
    if len(args) != 2:
        raise SystemExit(__doc__)
    found, problems = plan(args[0], args[1])
    for row in found:
        print(" ".join(row))
    for p in problems:
        print(f"FAIL {p}", file=sys.stderr)
    return 1 if problems else 0


def names(args):
    if len(args) != 2:
        raise SystemExit(__doc__)
    found, _ = plan(args[0], args[1])
    seen = []
    for _, name, _, _ in found:
        stream = outbound(load(os.path.join(args[0], name))).get("streamSettings") or {}
        for security in ("tlsSettings", "realitySettings"):
            server_name = (stream.get(security) or {}).get("serverName")
            if server_name and server_name not in seen:
                seen.append(server_name)
    if not seen:
        raise Problem("no line names a server")
    print(",".join("DNS:" + n for n in seen))
    return 0


# ---------------------------------------------------------------- one line

def move_socks(config, port):
    inbounds = config.get("inbounds") or []
    if len(inbounds) != 1 or inbounds[0].get("protocol") != "socks":
        raise Problem(f"want one SOCKS inbound, found {[i.get('protocol') for i in inbounds]}")
    inbounds[0]["port"] = port


def point(out, host, port):
    """The client's server address and port: the one object of its settings that holds both."""
    found = [d for d in leaves(out.get("settings") or {}, "address") if "port" in d]
    if len(found) != 1:
        raise Problem(f"{len(found)} server addresses in the line's settings, not one")
    found[0]["address"] = host
    found[0]["port"] = port


def colons(hexdigest):
    return ":".join(hexdigest[i:i + 2] for i in range(0, len(hexdigest), 2)).upper()


def network_of(stream):
    network = stream.get("network") or "raw"
    return "raw" if network == "tcp" else network


def make(line, ref_dir, lines_dir, host, socks, port, target_port, page_port, cert, key, private_key, public_key, wrong_public_key):
    """(network, server, client, [(refusal, what, client)]) of one line."""
    refs = references(ref_dir)
    if line not in refs:
        raise Problem(f"no reference for the line {line} in {ref_dir}")
    specs = servers(lines_dir)
    client = load(os.path.join(ref_dir, refs[line]))
    server_line = server_of(line, client, specs)
    spec = specs[server_line]
    where = f"lines/{server_line}{SUFFIX}"
    pointer = spec["mutant"]["pointer"]
    credential_name = credential_key(spec, where)
    try:
        credential = at(client, pointer)
    except Problem:
        raise Problem(f"{refs[line]} has nothing at {pointer}, where {where} says the line's credential is")

    the_pin = pin(cert)
    other_pin = hashlib.sha256(the_pin.encode()).hexdigest()
    # What K5's driver fills a server's placeholders with, for this run.
    values = {
        "port": port, "private_key": private_key, "public_key": public_key, "other_public_key": wrong_public_key,
        "target": f"127.0.0.1:{target_port}", "cert_file": cert, "key_file": key,
        "pin": the_pin, "pin_colons": colons(the_pin), "other_pin_colons": colons(other_pin),
    }
    inbound = fill(copy.deepcopy(inbound_of(spec, where)), values)
    inbound["listen"] = host
    inbound["port"] = port

    move_socks(client, socks)
    out = outbound(client)
    point(out, host, port)
    stream = out.get("streamSettings") or {}
    server_stream = inbound.get("streamSettings") or {}

    # Where the line and its server both name a thing, they agree.
    if out.get("protocol") != inbound.get("protocol"):
        raise Problem(f"the line speaks {out.get('protocol')}, the server of {where} {inbound.get('protocol')}")
    if network_of(stream) != network_of(server_stream):
        raise Problem(f"the line's transport is {network_of(stream)}, the server's of {where} {network_of(server_stream)}")
    security = stream.get("security") or "none"
    if security != (server_stream.get("security") or "none"):
        raise Problem(f"the line's security is {security}, the server's of {where} {server_stream.get('security') or 'none'}")
    transport = network_of(stream) + "Settings"
    ours, theirs = stream.get(transport) or {}, server_stream.get(transport)
    if isinstance(theirs, dict):
        for field in ("path", "serviceName"):
            if field in theirs and theirs[field] != ours.get(field):
                raise Problem(f"the line's {transport}.{field} is {ours.get(field)!r}, the server's of {where} {theirs[field]!r}")
        # A transport's server checks the Host it names: the line's.
        if "host" in theirs:
            if ours.get("host"):
                theirs["host"] = ours["host"]
            else:
                del theirs["host"]

    # The server holds the line's credential where K5's holds its link's.
    holders = leaves(inbound.get("settings") or {}, credential_name)
    if not holders:
        raise Problem(f"the server of {where} holds no {credential_name} in its settings")
    link = urllib.parse.unquote(spec.get("link", ""))
    for holder in holders:
        if not isinstance(holder[credential_name], str) or holder[credential_name] not in link:
            raise Problem(f"the server of {where} holds the {credential_name} {holder[credential_name]!r}, which its link does not carry")
        holder[credential_name] = credential

    if spec["mutant"]["value"] == credential:
        raise Problem(f"{where}'s mutant is the line's own {credential_name}")
    # The client's last edits (the key, the pin), then the refusals, each the
    # final client with one thing changed.
    edits = []
    if security == "reality":
        reality, server_reality = stream["realitySettings"], server_stream["realitySettings"]
        reality["publicKey"] = public_key
        # The server is told the name and the short id the line sends, as a
        # server lists those of the clients it serves.
        server_reality["serverNames"] = [reality["serverName"]]
        server_reality["shortIds"] = [reality.get("shortId", "")]
        edits.append(("wrong-key", "with a public key the server does not hold",
                      lambda s: s["realitySettings"].update(publicKey=wrong_public_key)))
    elif security == "tls":
        tls = stream["tlsSettings"]
        server_tls = server_stream.get("tlsSettings") or {}
        if "serverName" in server_tls:
            if tls.get("serverName"):
                server_tls["serverName"] = tls["serverName"]
            else:
                del server_tls["serverName"]
        if tls.get(WAIVER) is True:
            if PIN in tls:
                raise Problem(f"{refs[line]} both waives the certificate checks and pins a certificate")
            edits.append(("no-waiver", f"without its waiver ({WAIVER}), to a certificate no public root signed",
                          lambda s: s["tlsSettings"].pop(WAIVER)))
        else:
            # The certificate is the local server's own; the line trusts it by
            # its pin, as K5's `trust: pin` and a hysteria2 link's pinSHA256 do.
            tls[PIN] = the_pin
            edits.append(("wrong-pin", "with a certificate pin the server does not match",
                          lambda s: s["tlsSettings"].update({PIN: other_pin})))
            edits.append(("no-pin", "with no pin, to a certificate no public root signed",
                          lambda s: s["tlsSettings"].pop(PIN)))
    elif security != "none":
        raise Problem(f"a security this program does not know: {security}")

    mutant = copy.deepcopy(client)
    put(mutant, pointer, spec["mutant"]["value"])
    refusals = [("credential", f"with another {credential_name} than the server holds", mutant)]
    for refusal, what, edit in edits:
        body = copy.deepcopy(client)
        edit(outbound(body)["streamSettings"])
        refusals.append((refusal, what, body))

    server = {"log": {"loglevel": "warning"}, "inbounds": [inbound], "outbounds": [freedom_to_page(page_port)]}
    return spec.get("network", "tcp"), server, client, refusals


def line(args):
    if len(args) != 14:
        raise SystemExit(__doc__)
    name, ref_dir, lines_dir, out_dir, host = args[0:5]
    socks, port, target_port, page_port = (int(a) for a in args[5:9])
    network, server, client, refusals = make(name, ref_dir, lines_dir, host, socks, port, target_port, page_port, *args[9:14])
    save(os.path.join(out_dir, f"{name}.server.json"), server)
    save(os.path.join(out_dir, f"{name}.client.json"), client)
    with open(os.path.join(out_dir, f"{name}.refusals"), "w", encoding="utf-8") as f:
        for refusal, what, body in refusals:
            save(os.path.join(out_dir, f"{name}.{refusal}.json"), body)
            f.write(f"{refusal}\t{what}\n")
    print(network)
    return 0


# ---------------------------------------------------------------- selftest

def differences(a, b, at_path=""):
    """The JSON pointers where <a> and <b> part."""
    if isinstance(a, dict) and isinstance(b, dict):
        found = []
        for k in sorted(set(a) | set(b)):
            p = f"{at_path}/{k}"
            if k not in a or k not in b:
                found.append(p)
            else:
                found += differences(a[k], b[k], p)
        return found
    if isinstance(a, list) and isinstance(b, list) and len(a) == len(b):
        found = []
        for i, (x, y) in enumerate(zip(a, b)):
            found += differences(x, y, f"{at_path}/{i}")
        return found
    return [] if a == b else [at_path or "/"]


def selftest(args):
    if len(args) != 3:
        raise SystemExit(__doc__)
    ref_dir, lines_dir, work = args
    failures = []

    def check(what, ok):
        print(("ok   " if ok else "FAIL ") + what)
        if not ok:
            failures.append(what)

    def refused(what, fn, word):
        try:
            fn()
        except Problem as e:
            check(f"refuses {what} ({str(e)[:140]})", word in str(e))
            return
        check(f"refuses {what}", False)

    cert = os.path.join(work, "cert.pem")
    key = os.path.join(work, "key.pem")
    the_pin = pin(cert)
    host, socks, port, target, page = "192.0.2.7", 20001, 20002, 20003, 20004
    stand_in = (host, socks, port, target, page, cert, key, "private-key", "public-key", "wrong-public-key")

    found, problems = plan(ref_dir, lines_dir)
    check(f"every reference has its server under lines/ ({len(found)} lines)", not problems and len(found) > 0)
    for p in problems:
        print(f"     {p}")
    specs = servers(lines_dir)

    for name, reference, server_line, network in found:
        network_, server, client, refusals = make(name, ref_dir, lines_dir, *stand_in)
        spec = specs[server_line]
        inbound = server["inbounds"][0]
        out = outbound(client)
        stream = out.get("streamSettings") or {}
        server_stream = inbound.get("streamSettings") or {}
        original = load(os.path.join(ref_dir, reference))
        pointer = spec["mutant"]["pointer"]
        credential_name = parts(pointer)[-1]
        got = []
        # The line is the app's: only its server, its SOCKS port, the key and the pin moved.
        moved = differences(original, client)
        allowed = {"/inbounds/0/port"} | {p for p in moved if p.endswith(("/address", "/port"))} | {
            "/outbounds/0/streamSettings/realitySettings/publicKey", f"/outbounds/0/streamSettings/tlsSettings/{PIN}"}
        got.append(("the line is the reference but for its server, its SOCKS port, the key and the pin", set(moved) <= allowed))
        got.append(("its SOCKS port is the run's", client["inbounds"][0]["port"] == socks))
        dials = [(d["address"], d["port"]) for d in leaves(out["settings"], "address") if "port" in d]
        got.append(("it dials the server", dials == [(host, port)]))
        got.append(("the server listens there and sends the page's way", inbound["listen"] == host and inbound["port"] == port
                    and server["outbounds"] == [freedom_to_page(page)]))
        got.append(("the server holds the line's credential", all(
            h[credential_name] == at(client, pointer) for h in leaves(inbound["settings"], credential_name))
            and bool(leaves(inbound["settings"], credential_name))))
        got.append(("no placeholder is left in the server", not any(PLACEHOLDER.search(t) for t in strings(server))))
        security = stream.get("security") or "none"
        want = {"credential"}
        if security == "reality":
            r, sr = stream["realitySettings"], server_stream["realitySettings"]
            got.append(("REALITY: the run's key, the target, the line's name and short id",
                        r["publicKey"] == "public-key" and sr["privateKey"] == "private-key" and sr["dest"] == f"127.0.0.1:{target}"
                        and sr["serverNames"] == [r["serverName"]] and sr["shortIds"] == [r.get("shortId", "")]))
            want.add("wrong-key")
        elif security == "tls":
            tls = stream["tlsSettings"]
            certificates = (server_stream.get("tlsSettings") or {}).get("certificates")
            got.append(("TLS: the run's certificate, and the line's server name where the server names one",
                        certificates == [{"certificateFile": cert, "keyFile": key}]
                        and server_stream["tlsSettings"].get("serverName", tls.get("serverName")) == tls.get("serverName")))
            if tls.get(WAIVER) is True:
                got.append(("a waiver, and no pin", PIN not in tls))
                want.add("no-waiver")
            else:
                got.append(("pinned to the run's certificate", tls.get(PIN) == the_pin))
                want |= {"wrong-pin", "no-pin"}
        transport = network_of(stream) + "Settings"
        if isinstance(server_stream.get(transport), dict) and isinstance(stream.get(transport), dict):
            theirs, ours = server_stream[transport], stream[transport]
            got.append(("the transport's path, service and Host agree", all(
                theirs[f] == ours.get(f) for f in ("path", "serviceName", "host") if f in theirs)))
        got.append(("its refusals are " + ", ".join(sorted(want)), {r for r, _, _ in refusals} == want))
        for refusal, what, body in refusals:
            moved = differences(client, body)
            expect = {"credential": [pointer], "wrong-key": ["/outbounds/0/streamSettings/realitySettings/publicKey"],
                      "wrong-pin": [f"/outbounds/0/streamSettings/tlsSettings/{PIN}"],
                      "no-pin": [f"/outbounds/0/streamSettings/tlsSettings/{PIN}"],
                      "no-waiver": [f"/outbounds/0/streamSettings/tlsSettings/{WAIVER}"]}[refusal]
            got.append((f"the refusal {refusal} changes that alone", moved == expect))
        bad = [w for w, ok in got if not ok]
        check(f"{name} ({reference}) against {server_line}{SUFFIX}, {network}: {len(got)} checks"
              + (", failed: " + "; ".join(bad) if bad else f", refusals {' '.join(r for r, _, _ in refusals)}"), not bad)

    # Each refusal of this program can be had: copies of the tree, each with
    # one thing wrong.
    def tree(label):
        root = os.path.join(work, label)
        shutil.copytree(ref_dir, os.path.join(root, "refs"), ignore=shutil.ignore_patterns("bypass-*"))
        shutil.copytree(lines_dir, os.path.join(root, "lines"))
        return os.path.join(root, "refs"), os.path.join(root, "lines")

    refs, k5 = tree("no-server")
    os.remove(os.path.join(k5, "trojan-ws" + SUFFIX))
    found_, problems_ = plan(refs, k5)
    check("a reference with no server fails, named, and the others are still listed",
          any(p.startswith("ios-core-trojan-ws.json: no server for it") for p in problems_) and len(found_) == len(found) - 1)

    refs, k5 = tree("two-servers")
    spec = load(os.path.join(k5, "shadowsocks-chacha20-poly1305" + SUFFIX))
    save(os.path.join(k5, "shadowsocks-chacha20-poly1305-again" + SUFFIX), spec)
    _, problems_ = plan(refs, k5)
    check("a reference two servers of its shape fit fails, naming them",
          any(p.startswith("ios-core-shadowsocks.json: 2 lines of the shape shadowsocks fit it") for p in problems_))

    refs, k5 = tree("uncarried-shape")
    os.remove(os.path.join(refs, "ios-core-vmess-ws.json"))
    _, problems_ = plan(refs, k5)
    check("a shape K5 carries and no reference line here does fails",
          any("the shape vmess-ws (vmess-ws)" in p for p in problems_))

    refs, k5 = tree("refused-by-name")
    os.replace(os.path.join(k5, "reality-wrong-key" + SUFFIX), os.path.join(k5, "reality-ru" + SUFFIX))
    shutil.copy(os.path.join(refs, "ios-core-reality.json"), os.path.join(refs, "core-reality-ru.json"))
    _, problems_ = plan(refs, k5)
    check("a reference whose server by name must not reach the page fails",
          any(p.startswith("core-reality-ru.json: lines/reality-ru.server.json is a line that must not reach the page") for p in problems_))

    def edited(label, server_line, edit):
        refs, k5 = tree(label)
        path = os.path.join(k5, server_line + SUFFIX)
        spec = load(path)
        edit(spec)
        save(path, spec)
        return refs, k5

    refs, k5 = edited("other-path", "vmess-ws", lambda s: s["inbounds"][0]["streamSettings"]["wsSettings"].update(path="/elsewhere"))
    refused("a server whose path is not the line's", lambda: make("vmess-ws", refs, k5, *stand_in), "wsSettings.path")
    refs, k5 = edited("other-protocol", "trojan", lambda s: s["inbounds"][0].update(protocol="vless"))
    refused("a server of another protocol", lambda: make("trojan", refs, k5, *stand_in), "speaks trojan")
    refs, k5 = edited("other-security", "vless-tls", lambda s: s["inbounds"][0]["streamSettings"].update(security="none"))
    refused("a server of another security", lambda: make("vless-tls", refs, k5, *stand_in), "security is tls")
    refs, k5 = edited("no-credential", "vless-none", lambda s: s["inbounds"][0]["settings"]["clients"][0].pop("id"))
    refused("a server that holds no credential under the mutant's key", lambda: make("vless-none", refs, k5, *stand_in), "holds no id")
    refs, k5 = edited("stray-credential", "vless-none",
                      lambda s: s["inbounds"][0]["settings"]["clients"][0].update(id="99999999-9999-9999-9999-999999999999"))
    refused("a server whose credential its link does not carry", lambda: make("vless-none", refs, k5, *stand_in), "which its link does not carry")
    refs, k5 = edited("mutant-is-the-line", "trojan",
                      lambda s: s["mutant"].update(value="trojan-fixture-password"))
    refused("a mutant that is the line's own credential", lambda: make("trojan", refs, k5, *stand_in), "the line's own password")
    refs, k5 = edited("mutant-nowhere", "vmess-raw", lambda s: s["mutant"].update(pointer="/outbounds/0/settings/vnext/0/users/1/id"))
    refused("a mutant that points at nothing in the reference", lambda: make("vmess-raw", refs, k5, *stand_in), "has nothing at")
    refs, k5 = edited("unknown-placeholder", "vless-tls",
                      lambda s: s["inbounds"][0]["streamSettings"]["tlsSettings"].update(serverName="{tls_name}"))
    refused("a placeholder this program does not fill", lambda: make("vless-tls", refs, k5, *stand_in), "{tls_name}")

    if failures:
        print(f"{len(failures)} case(s) failed")
        return 1
    return 0


# ---------------------------------------------------------------- the rest

def socks(args):
    if len(args) != 3:
        raise SystemExit(__doc__)
    config = load(args[0])
    move_socks(config, int(args[2]))
    save(args[1], config)
    return 0


def refuse(args):
    if len(args) != 4:
        raise SystemExit(__doc__)
    config = load(args[0])
    move_socks(config, int(args[2]))
    out = outbound(config)
    if args[3] in out:
        raise Problem(f"{args[0]}: the outbound already has {args[3]}")
    out[args[3]] = True
    save(args[1], config)
    return 0


def rules(args):
    if len(args) != 2:
        raise SystemExit(__doc__)
    config = load(args[0])
    direct = [r for r in config["routing"]["rules"] if r.get("outboundTag") == "direct"]
    ip_rules = [r["ip"] for r in direct if "ip" in r]
    domains = [r["domain"] for r in direct if "domain" in r]
    if len(ip_rules) != 2 or len(domains) != 1:
        raise Problem(f"{args[0]}: want the private ranges, one name list and one address list going direct")
    private, cidrs = ip_rules
    taken = [n for n in domains[0] if n.startswith(("domain:", "full:"))]
    text = "\n".join(private + taken + cidrs) + "\n"
    with open(args[1], "w", encoding="utf-8") as f:
        f.write(text)
    print(f"{len(private)} private ranges, {len(taken)} names, {len(cidrs)} prefixes")
    return 0


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    command, args = argv[1], argv[2:]
    commands = {"lines": lines, "names": names, "line": line, "selftest": selftest, "socks": socks, "refuse": refuse, "rules": rules}
    try:
        if command in commands:
            return commands[command](args)
        if command == "pin" and len(args) == 1:
            print(pin(args[0]))
            return 0
    except Problem as e:
        print(f"FAIL {e}", file=sys.stderr)
        return 1
    raise SystemExit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
