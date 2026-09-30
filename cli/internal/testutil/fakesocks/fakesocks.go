// Package fakesocks is a SOCKS5 server for tests: user/pass auth, CONNECT only.
package fakesocks

import (
	"bufio"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
)

func Serve(t testing.TB, user, pass string, handle func(target string, conn net.Conn)) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go serveConn(c, user, pass, handle)
		}
	}()
	return l.Addr().String()
}

func serveConn(c net.Conn, user, pass string, handle func(string, net.Conn)) {
	defer c.Close()
	r := bufio.NewReader(c)
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil || head[0] != 5 {
		return
	}
	methods := make([]byte, int(head[1]))
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	if user == "" {
		_, _ = c.Write([]byte{5, 0})
	} else {
		_, _ = c.Write([]byte{5, 2})
		var v [2]byte
		if _, err := io.ReadFull(r, v[:]); err != nil || v[0] != 1 {
			return
		}
		u := make([]byte, int(v[1]))
		_, _ = io.ReadFull(r, u)
		var pl [1]byte
		_, _ = io.ReadFull(r, pl[:])
		p := make([]byte, int(pl[0]))
		_, _ = io.ReadFull(r, p)
		if string(u) != user || string(p) != pass {
			_, _ = c.Write([]byte{1, 1})
			return
		}
		_, _ = c.Write([]byte{1, 0})
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(r, req); err != nil || req[1] != 1 {
		return
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		_, _ = io.ReadFull(r, b)
		host = net.IP(b).String()
	case 3:
		var n [1]byte
		_, _ = io.ReadFull(r, n[:])
		b := make([]byte, int(n[0]))
		_, _ = io.ReadFull(r, b)
		host = string(b)
	case 4:
		b := make([]byte, 16)
		_, _ = io.ReadFull(r, b)
		host = net.IP(b).String()
	default:
		return
	}
	var pb [2]byte
	_, _ = io.ReadFull(r, pb[:])
	port := binary.BigEndian.Uint16(pb[:])
	_, _ = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	handle(net.JoinHostPort(host, strconv.Itoa(int(port))), &bufConn{Conn: c, r: r})
}

type bufConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufConn) Read(p []byte) (int, error) { return b.r.Read(p) }

// Answer204 reads the HTTP request head and answers 204 to anything.
func Answer204(_ string, conn net.Conn) {
	r := bufio.NewReader(conn)
	for {
		line, err := r.ReadString('\n')
		if err != nil || line == "\r\n" || line == "\n" {
			break
		}
	}
	_, _ = conn.Write([]byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
}
