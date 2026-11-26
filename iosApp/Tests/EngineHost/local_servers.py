"""The two servers behind the local Xray servers, on loopback.

  local_servers.py page <port-file> <download-bytes>
      the page the lines fetch: GET /download answers that many random bytes
      with their sha256 in X-Body-Sha256; POST /upload answers the sha256 of
      the body it read, in hex.
  local_servers.py target <port-file> <cert.pem> <key.pem>
      the site a Reality server mirrors, as a real one answers: TLS 1.3,
      h2 offered, a certificate larger than the Reality server's own.
  local_servers.py resolver <port-file> <host> <name=address>...
      the network's resolver for the lines named by a hostname: a UDP server
      on <host> (the address the engine's pinned sockets can reach) that
      answers an A question for each <name> with its <address>, an AAAA
      question for it with no answer, and a name it does not know with
      NXDOMAIN. Every name asked is appended to <port-file>.asked.
  local_servers.py ask <host> <port> <name> A|AAAA
      one question to such a resolver; prints the rcode and the addresses.
  local_servers.py free-port
      prints a loopback TCP port nothing listens on.

Each server binds an ephemeral port, writes it to <port-file> once it
listens, and serves until it is killed.
"""

import hashlib
import http.server
import os
import socket
import socketserver
import ssl
import sys
import threading


def announce(port_file, port):
    tmp = port_file + ".tmp"
    with open(tmp, "w", encoding="ascii") as f:
        f.write(f"{port}\n")
    os.replace(tmp, port_file)


def page(port_file, size):
    body = os.urandom(size)
    digest = hashlib.sha256(body).hexdigest()

    class Page(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write("page: " + (fmt % args) + "\n")

        def answer(self, status, payload, extra=None):
            self.send_response(status)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(payload)
            self.close_connection = True

        def do_GET(self):
            if self.path != "/download":
                self.answer(404, b"no such page\n")
                return
            self.answer(200, body, {"X-Body-Sha256": digest})

        def do_POST(self):
            if self.path != "/upload":
                self.answer(404, b"no such page\n")
                return
            left = int(self.headers.get("Content-Length", "0"))
            h = hashlib.sha256()
            while left > 0:
                chunk = self.rfile.read(min(left, 65536))
                if not chunk:
                    break
                h.update(chunk)
                left -= len(chunk)
            self.answer(200, (h.hexdigest() + "\n").encode())

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Page)
    server.daemon_threads = True
    announce(port_file, server.server_address[1])
    server.serve_forever()


def target(port_file, cert, key):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.set_alpn_protocols(["h2", "http/1.1"])
    context.load_cert_chain(cert, key)

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(10)
            try:
                with context.wrap_socket(self.request, server_side=True) as tls:
                    tls.recv(4096)
                    tls.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\ncamouflage")
            except (OSError, ssl.SSLError) as e:
                sys.stderr.write(f"target: {e}\n")

    class Server(socketserver.ThreadingTCPServer):
        daemon_threads = True
        allow_reuse_address = True

    server = Server(("127.0.0.1", 0), Handler)
    announce(port_file, server.server_address[1])
    server.serve_forever()


def read_name(packet, at):
    """(name, index past it) of the uncompressed name at <at>."""
    labels = []
    while True:
        length = packet[at]
        at += 1
        if length == 0:
            return ".".join(labels), at
        if length & 0xC0:
            raise ValueError("a compressed name in a question")
        labels.append(packet[at:at + length].decode("ascii").lower())
        at += length


def answer_for(packet, names):
    """(response, name asked) to one query, or None for a packet that is none."""
    if len(packet) < 12 or packet[2] & 0x80 or int.from_bytes(packet[4:6], "big") != 1:
        return None
    try:
        name, at = read_name(packet, 12)
    except (ValueError, IndexError):
        return None
    if at + 4 > len(packet):
        return None
    kind = int.from_bytes(packet[at:at + 2], "big")
    question = packet[12:at + 4]
    known = names.get(name)
    flags = 0x8180 | (0 if known else 3)  # response, recursion desired and available; NXDOMAIN when unknown
    answers = b""
    count = 0
    if known and kind == 1:
        # A pointer to the name in the question, type A, class IN, TTL 60.
        answers = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04" + socket.inet_aton(known)
        count = 1
    header = packet[:2] + flags.to_bytes(2, "big") + b"\x00\x01" + count.to_bytes(2, "big") + b"\x00\x00\x00\x00"
    return header + question + answers, name


def resolver(port_file, host, pairs):
    names = {}
    for pair in pairs:
        name, _, address = pair.partition("=")
        socket.inet_aton(address)
        names[name.lower()] = address
    asked_file = port_file + ".asked"
    open(asked_file, "w", encoding="ascii").close()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((host, 0))
    announce(port_file, sock.getsockname()[1])
    while True:
        packet, peer = sock.recvfrom(4096)
        made = answer_for(packet, names)
        if made is None:
            continue
        response, name = made
        with open(asked_file, "a", encoding="ascii") as f:
            f.write(name + "\n")
        sock.sendto(response, peer)


def ask(host, port, name, kind):
    query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00"
    for label in name.split("."):
        query += bytes([len(label)]) + label.encode("ascii")
    query += b"\x00" + (1 if kind == "A" else 28).to_bytes(2, "big") + b"\x00\x01"
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(3)
        s.sendto(query, (host, port))
        response = s.recv(4096)
    rcode = response[3] & 0x0F
    found = []
    if int.from_bytes(response[6:8], "big") == 1 and rcode == 0:
        found.append(socket.inet_ntoa(response[-4:]))
    print(rcode, " ".join(found))


def free_port():
    # Free for TCP and for UDP both: a hysteria2 server listens on UDP.
    for _ in range(50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as u:
                u.bind(("127.0.0.1", port))
        except OSError:
            continue
        return port
    raise SystemExit("no port free for TCP and UDP in 50 tries")


def main(argv):
    if len(argv) >= 2 and argv[1] == "page" and len(argv) == 4:
        page(argv[2], int(argv[3]))
    elif len(argv) >= 2 and argv[1] == "target" and len(argv) == 5:
        target(argv[2], argv[3], argv[4])
    elif len(argv) >= 4 and argv[1] == "resolver":
        resolver(argv[2], argv[3], argv[4:])
    elif len(argv) == 6 and argv[1] == "ask":
        ask(argv[2], int(argv[3]), argv[4], argv[5])
    elif len(argv) == 2 and argv[1] == "free-port":
        print(free_port())
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    threading.current_thread().name = "local-servers"
    main(sys.argv)
