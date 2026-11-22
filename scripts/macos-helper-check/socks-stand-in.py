#!/usr/bin/env python3
"""A SOCKS5 server that stands in for the engine behind hev, and dials nowhere.

For scripts/macos-helper-check/hev-tunnel.sh: it asks for a login (RFC 1929)
and turns away a client without the right one, takes a CONNECT to any address
or name, says on a line of its log what was asked for, and answers the first
request on the connection itself, with one line naming it: "stand-in saw
<host>:<port>". So a request that reaches it went into the tun, through hev,
and was handed on with the login, and nothing it carries leaves the machine.

    socks-stand-in.py <port> <username> <password> <log>
"""
import socket
import struct
import sys
import threading


def read(conn, size):
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            raise EOFError("closed after %d of %d bytes" % (len(data), size))
        data += chunk
    return data


def main():
    port, username, password, log = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
    lock = threading.Lock()

    def say(line):
        with lock, open(log, "a") as out:
            out.write(line + "\n")

    def serve(conn):
        try:
            conn.settimeout(15)
            _, count = read(conn, 2)
            if 2 not in read(conn, count):
                conn.sendall(b"\x05\xff")
                say("refused: no login offered")
                return
            conn.sendall(b"\x05\x02")
            _, size = read(conn, 2)
            user = read(conn, size)
            secret = read(conn, read(conn, 1)[0])
            if user != username.encode() or secret != password.encode():
                conn.sendall(b"\x01\x01")
                say("refused: the wrong login, %r" % user.decode(errors="replace"))
                return
            conn.sendall(b"\x01\x00")
            _, command, _, kind = read(conn, 4)
            if kind == 1:
                host = socket.inet_ntop(socket.AF_INET, read(conn, 4))
            elif kind == 4:
                host = "[%s]" % socket.inet_ntop(socket.AF_INET6, read(conn, 16))
            elif kind == 3:
                host = read(conn, read(conn, 1)[0]).decode(errors="replace")
            else:
                say("refused: address type %d" % kind)
                return
            where = "%s:%d" % (host, struct.unpack("!H", read(conn, 2))[0])
            if command != 1:
                conn.sendall(b"\x05\x07\x00\x01" + bytes(6))
                say("refused: command %d to %s" % (command, where))
                return
            conn.sendall(b"\x05\x00\x00\x01" + bytes(6))
            say("connect %s as %s" % (where, username))
            conn.recv(65536)
            body = ("stand-in saw %s\n" % where).encode()
            conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n"
                         b"Content-Length: %d\r\n\r\n" % len(body) + body)
        except Exception as error:  # what became of it is the log's to say
            say("broken: %s" % error)
        finally:
            conn.close()

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", port))
    listener.listen(64)
    say("listening on 127.0.0.1:%d" % port)
    while True:
        conn, _ = listener.accept()
        threading.Thread(target=serve, args=(conn,), daemon=True).start()


main()
