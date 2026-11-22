"""The local servers' configs and the lines that reach them, from the app's
reference configs (sharedUI/src/jvmTest/resources/reference-configs).

  lines.py line <name> <reference-dir> <out-dir> <server-host> <socks-port>
                <server-port> <target-port> <page-port> <cert.pem> <key.pem>
                <private-key> <public-key> <wrong-public-key>
      writes <out-dir>/<name>.server.json (an Xray server for the line, on
      <server-host>: the address of the interface the engine's sockets are
      pinned to, which cannot reach loopback),
      <name>.client.json (ios-core-<name>.json pointed at it) and
      <name>.wrong.json (the same line with a key or a certificate pin the
      server does not answer to), and prints the transport the server
      listens on (tcp or udp) and what the wrong line changed.
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

Nothing but the addresses, the ports, the keys and the pins of the
reference line is changed: the line is the app's.
"""

import copy
import hashlib
import json
import os
import ssl
import sys

LINES = ("reality", "grpc-reality", "webtunnel-ws", "webtunnel-httpupgrade", "hysteria2", "xhttp")

# An address in TEST-NET-3, public to the line's rules, so the request takes
# the tunnel; the server's freedom outbound sends it to the page on loopback.
# `finalRules` allows loopback from Xray 26.7 on, `ipsBlocked: []` up to 26.4;
# each version ignores the other's key.


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


def outbound(config):
    for o in config["outbounds"]:
        if o.get("tag") == "out":
            return o
    raise SystemExit("the reference line has no outbound tagged out")


def point(out, host, port):
    """The client's server address and port."""
    settings = out["settings"]
    if "vnext" in settings:
        settings["vnext"][0]["address"] = host
        settings["vnext"][0]["port"] = port
    else:
        settings["address"] = host
        settings["port"] = port


def server_for(name, out, host, port, target_port, page_port, cert, key, private_key):
    stream = out["streamSettings"]
    network = stream["network"]
    certificates = [{"certificateFile": cert, "keyFile": key}]
    if name == "hysteria2":
        hy = stream["hysteriaSettings"]
        inbound = {
            "tag": "in",
            "listen": host,
            "port": port,
            "protocol": "hysteria",
            # The user by `clients`, the key every version here reads (26.4
            # knows no other), as a server of the platform lists it.
            "settings": {"version": hy["version"], "clients": [{"auth": hy["auth"]}]},
            "streamSettings": {
                "network": "hysteria",
                "security": "tls",
                "hysteriaSettings": {"version": hy["version"], "auth": hy["auth"]},
                "finalmask": {"udp": copy.deepcopy(stream["finalmask"]["udp"])},
                "tlsSettings": {"certificates": certificates},
            },
        }
    else:
        user = out["settings"]["vnext"][0]["users"][0]
        client = {"id": user["id"]}
        if user.get("flow"):
            # The client's -udp443 is its own: what it sends is the flow.
            client["flow"] = user["flow"].removesuffix("-udp443")
        server_stream = {"network": network}
        for transport in ("grpcSettings", "wsSettings", "httpupgradeSettings", "xhttpSettings"):
            if transport in stream:
                server_stream[transport] = copy.deepcopy(stream[transport])
        if "xhttpSettings" in server_stream:
            # The client's own windows and post sizes are its side's.
            server_stream["xhttpSettings"] = {
                k: v for k, v in server_stream["xhttpSettings"].items() if k in ("path", "host", "mode")
            }
        if stream["security"] == "reality":
            reality = stream["realitySettings"]
            server_stream["security"] = "reality"
            server_stream["realitySettings"] = {
                "dest": f"127.0.0.1:{target_port}",
                "serverNames": [reality["serverName"]],
                "privateKey": private_key,
                "shortIds": [reality["shortId"]],
            }
        else:
            server_stream["security"] = "tls"
            server_stream["tlsSettings"] = {"certificates": certificates, "alpn": ["http/1.1"]}
        inbound = {
            "tag": "in",
            "listen": host,
            "port": port,
            "protocol": "vless",
            "settings": {"clients": [client], "decryption": "none"},
            "streamSettings": server_stream,
        }
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [inbound],
        "outbounds": [freedom_to_page(page_port)],
    }


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(path, body):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(body, f, indent=1)


def move_socks(config, port):
    inbounds = config.get("inbounds") or []
    if len(inbounds) != 1 or inbounds[0].get("protocol") != "socks":
        raise SystemExit(f"want one SOCKS inbound, found {[i.get('protocol') for i in inbounds]}")
    inbounds[0]["port"] = port


def line(args):
    if len(args) != 13:
        raise SystemExit(__doc__)
    name, ref_dir, out_dir, host = args[0], args[1], args[2], args[3]
    socks, port, target_port, page_port = (int(a) for a in args[4:8])
    cert, key, private_key, public_key, wrong_public_key = args[8:13]
    if name not in LINES:
        raise SystemExit(f"no line {name}; the lines are {', '.join(LINES)}")
    client = load(os.path.join(ref_dir, f"ios-core-{name}.json"))
    move_socks(client, socks)
    out = outbound(client)
    point(out, host, port)
    stream = out["streamSettings"]
    the_pin = pin(cert)
    wrong = copy.deepcopy(client)
    wrong_stream = outbound(wrong)["streamSettings"]
    if stream["security"] == "reality":
        stream["realitySettings"]["publicKey"] = public_key
        wrong_stream["realitySettings"]["publicKey"] = wrong_public_key
        what = "a public key the server does not hold"
    else:
        # The certificate is the local server's own; the line trusts it by
        # its pin, as a hysteria2 line does in the reference.
        stream["tlsSettings"]["pinnedPeerCertSha256"] = the_pin
        wrong_stream["tlsSettings"]["pinnedPeerCertSha256"] = hashlib.sha256(the_pin.encode()).hexdigest()
        what = "a certificate pin the server does not match"
    server = server_for(name, out, host, port, target_port, page_port, cert, key, private_key)
    for suffix, body in (("server", server), ("client", client), ("wrong", wrong)):
        save(os.path.join(out_dir, f"{name}.{suffix}.json"), body)
    print("udp" if name == "hysteria2" else "tcp", what)


def socks(args):
    if len(args) != 3:
        raise SystemExit(__doc__)
    config = load(args[0])
    move_socks(config, int(args[2]))
    save(args[1], config)


def refuse(args):
    if len(args) != 4:
        raise SystemExit(__doc__)
    config = load(args[0])
    move_socks(config, int(args[2]))
    out = outbound(config)
    if args[3] in out:
        raise SystemExit(f"{args[0]}: the outbound already has {args[3]}")
    out[args[3]] = True
    save(args[1], config)


def rules(args):
    if len(args) != 2:
        raise SystemExit(__doc__)
    config = load(args[0])
    direct = [r for r in config["routing"]["rules"] if r.get("outboundTag") == "direct"]
    ip_rules = [r["ip"] for r in direct if "ip" in r]
    names = [r["domain"] for r in direct if "domain" in r]
    if len(ip_rules) != 2 or len(names) != 1:
        raise SystemExit(f"{args[0]}: want the private ranges, one name list and one address list going direct")
    private, cidrs = ip_rules
    taken = [n for n in names[0] if n.startswith(("domain:", "full:"))]
    text = "\n".join(private + taken + cidrs) + "\n"
    with open(args[1], "w", encoding="utf-8") as f:
        f.write(text)
    print(f"{len(private)} private ranges, {len(taken)} names, {len(cidrs)} prefixes")


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    command, args = argv[1], argv[2:]
    if command == "line":
        line(args)
    elif command == "socks":
        socks(args)
    elif command == "refuse":
        refuse(args)
    elif command == "rules":
        rules(args)
    elif command == "pin" and len(args) == 1:
        print(pin(args[0]))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
