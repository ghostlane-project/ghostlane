"""One connection, opened once and asked over again and again.

  held-open.py <uid> <gid> <host> <out> [<a path that answers a range>]

Becomes <uid> in group <gid>, opens one TLS connection to <host>, and once a
second asks for a thousand bytes over that same connection, writing what came
of each ask to <out>. Once a file <out>.big stands, the next ask is for three
million bytes, one time. It never opens a second connection: the first one
broken is the end of it.

With no path the host is asked for so many bytes by its own address
(speed.cloudflare.com's /__down); with one, for a range of that file.
"""
import os
import socket
import ssl
import sys
import time

uid, gid, host, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
ranged = sys.argv[5] if len(sys.argv) > 5 else ""
os.setgid(gid)
os.setuid(uid)
log = open(out, "a", buffering=1)
progress = 0


def say(text):
    log.write(text + "\n")


try:
    raw = socket.create_connection((host, 443), timeout=8)
    conn = ssl.create_default_context().wrap_socket(raw, server_hostname=host)
except Exception as e:  # noqa: BLE001 - whatever it is, it is the answer
    say("never opened %s: %s" % (type(e).__name__, e))
    sys.exit(1)
say("opened to %s" % raw.getpeername()[0])


def ask(count):
    global progress
    progress = 0
    conn.settimeout(8)
    began = time.time()
    if ranged:
        first = "GET %s HTTP/1.1\r\nRange: bytes=0-%d\r\n" % (ranged, count - 1)
    else:
        first = "GET /__down?bytes=%d HTTP/1.1\r\n" % count
    conn.sendall((first + "Host: %s\r\nConnection: keep-alive\r\n\r\n" % host).encode())
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(65536)
        if not chunk:
            raise EOFError("closed by the far end")
        data += chunk
    head, body = data.split(b"\r\n\r\n", 1)
    lines = head.split(b"\r\n")
    status = lines[0].split(b" ")[1].decode()
    length = None
    for line in lines[1:]:
        key, _, value = line.partition(b":")
        if key.strip().lower() == b"content-length":
            length = int(value.strip())
    if length is None:
        raise ValueError("an answer of no stated length, status %s" % status)
    progress = len(body)
    while progress < length:
        chunk = conn.recv(65536)
        if not chunk:
            raise EOFError("closed by the far end at %d of %d" % (progress, length))
        progress += len(chunk)
    return status, progress, int((time.time() - began) * 1000)


asked_big = False
while True:
    want = 1000
    if not asked_big and os.path.exists(out + ".big"):
        asked_big = True
        want = 3000000
    try:
        status, got, took = ask(want)
        say("answered %s %d %d" % (status, got, took))
        if status not in ("200", "206") or got != want:
            sys.exit(4)
    except socket.timeout:
        say("stalled %d of %d" % (progress, want))
        sys.exit(2)
    except Exception as e:  # noqa: BLE001
        say("broken %s: %s" % (type(e).__name__, e))
        sys.exit(3)
    time.sleep(1)
