//go:build with_utls

package singbox

import (
	"net"
	"sync"
	"testing"
	"time"

	sbtls "github.com/sagernet/sing-box/common/tls"
)

// scriptedServer keeps the ClientHello of every connection it is sent and
// then does `after` with the connection, which it is given with its place in
// the order of arrival. Connections `after` leaves open stay open until the
// test ends.
func scriptedServer(t *testing.T, after func(index int, c net.Conn)) (port int, hellos func() [][]byte) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var seen [][]byte
	var conns []net.Conn
	t.Cleanup(func() {
		_ = ln.Close()
		mu.Lock()
		defer mu.Unlock()
		for _, c := range conns {
			_ = c.Close()
		}
	})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			conns = append(conns, c)
			mu.Unlock()
			go func() {
				flight, err := readFirstRecord(c)
				if err != nil {
					return
				}
				mu.Lock()
				seen = append(seen, flight)
				index := len(seen) - 1
				mu.Unlock()
				after(index, c)
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, func() [][]byte {
		mu.Lock()
		defer mu.Unlock()
		return append([][]byte(nil), seen...)
	}
}

// TestRealityShortHelloOfEveryFingerprint reads, off the wire, the hello the
// retry sends for every fingerprint a link may name: the server leaves the
// first hello unanswered and refuses the second with an alert. The short
// hello is the one that stalled without its hybrid share, so it has an
// X25519 key share to seal the session id with, no X25519MLKEM768 group or
// share, and fits the one segment the cut networks let through.
func TestRealityShortHelloOfEveryFingerprint(t *testing.T) {
	_, pub := realityKey(t)
	sbtls.RealityHelloNetworkReset()
	for _, fp := range []string{"chrome", "", "firefox", "safari", "ios", "edge", "android", "360", "qq", "random"} {
		name := fp
		if name == "" {
			name = "unnamed"
		}
		t.Run(name, func(t *testing.T) {
			port, hellos := scriptedServer(t, func(index int, c net.Conn) {
				if index > 0 {
					// handshake_failure, fatal
					_, _ = c.Write([]byte{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28})
					_ = c.Close()
				}
			})
			_, took, _ := fetchWithin(t, realityLineWith(port, pub, fp), 3*helloStallWindow, false)
			flights := hellos()
			seen := describeHellos(t, flights)
			if len(seen) != 2 || !seen[0].long() {
				t.Fatalf("hellos %+v after %v: want the long one, then the retry's", seen, took)
			}
			short := flights[1]
			groups, shares := helloGroupsAndShares(t, short[5:])
			order := shareGroups(shares)
			if !contains(order, groupX25519) {
				t.Errorf("key shares %x: the short hello needs an X25519 share", order)
			}
			if contains(order, groupX25519MLKEM768) {
				t.Errorf("key shares %x: the short hello still has the X25519MLKEM768 share", order)
			}
			if contains(groups, groupX25519MLKEM768) {
				t.Errorf("supported_groups %x: the short hello still names X25519MLKEM768", groups)
			}
			if len(short) > firstSegment {
				t.Errorf("the short hello is %d bytes: more than the %d of one segment", len(short), firstSegment)
			}
		})
	}
}

// TestRealityHelloRetryThatEndsOtherwiseRemembersNothing: a retry the server
// closes or resets says nothing about the hello. It may be the path's doing,
// so neither verdict is kept and the next dial is watched again.
func TestRealityHelloRetryThatEndsOtherwiseRemembersNothing(t *testing.T) {
	_, pub := realityKey(t)
	sbtls.RealityHelloNetworkReset()
	for _, c := range []struct {
		name  string
		reset bool
	}{
		{"closed", false},
		{"reset", true},
	} {
		t.Run(c.name, func(t *testing.T) {
			port, hellos := scriptedServer(t, func(index int, conn net.Conn) {
				if index == 0 {
					return
				}
				if tcp, ok := conn.(*net.TCPConn); ok && c.reset {
					_ = tcp.SetLinger(0)
				}
				_ = conn.Close()
			})
			if got, _, err := fetchWithin(t, realityLineWith(port, pub, "chrome"), 3*helloStallWindow, false); err == nil {
				t.Fatalf("a page came back from a server that is none: %q", got)
			}
			// the front is gone; the memory is the process's
			time.Sleep(200 * time.Millisecond)
			seen := describeHellos(t, hellos())
			if len(seen) != 2 || !seen[0].long() || !seen[1].short() {
				t.Fatalf("hellos %+v: want the long one, then the short one", seen)
			}
			if got := rememberedAt(port); got != sbtls.RealityShortHelloUnknown {
				t.Fatalf("remembered %q for a retry the server only %s", got, c.name)
			}
		})
	}
}
