//go:build with_utls

package singbox

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	sbtls "github.com/sagernet/sing-box/common/tls"
	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

// A Reality hello with the X25519MLKEM768 key share is about 1.8 KB: two TCP
// segments. Some networks deliver the first and never the second, so the
// server waits for the end of the hello and the client for an answer. The
// patched sing-box watches such a hello for helloStallWindow and then opens
// one new connection with the hello upstream sing-box sends, which has no
// such share and fits one segment (scripts/patches/sing-box-reality-hello.patch).
//
// These tests put that network in front of real Xray servers: a relay that
// lets through the first firstSegment bytes of a connection's first flight
// and drops the rest of it.
const (
	firstSegment     = 1388
	helloStallWindow = sbtls.RealityHelloStallWindow
)

// rememberedAt is what the process-wide memory holds for the line's server.
func rememberedAt(port int) sbtls.RealityShortHello {
	return sbtls.RealityShortHelloAt(sbtls.RealityHelloServer{Address: "127.0.0.1:" + strconv.Itoa(port), ServerName: realitySNI})
}

// cuttingRelay stands between the front and a server. It keeps the first
// record (the ClientHello) of every connection it is sent; with cut > 0 only
// that many of the record's bytes reach the server. Everything after the
// first record passes, both ways.
type cuttingRelay struct {
	ln       net.Listener
	upstream string
	cut      int

	mu     sync.Mutex
	hellos [][]byte
	conns  []net.Conn
}

func newCuttingRelay(t *testing.T, upstream string, cut int) *cuttingRelay {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := &cuttingRelay{ln: ln, upstream: upstream, cut: cut}
	t.Cleanup(func() {
		_ = ln.Close()
		r.mu.Lock()
		defer r.mu.Unlock()
		for _, c := range r.conns {
			_ = c.Close()
		}
	})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go r.serve(c)
		}
	}()
	return r
}

func (r *cuttingRelay) port() int { return r.ln.Addr().(*net.TCPAddr).Port }

func (r *cuttingRelay) keep(c net.Conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.conns = append(r.conns, c)
}

func (r *cuttingRelay) serve(client net.Conn) {
	r.keep(client)
	defer client.Close()
	flight, err := readFirstRecord(client)
	if err != nil {
		return
	}
	r.mu.Lock()
	r.hellos = append(r.hellos, flight)
	r.mu.Unlock()
	server, err := net.DialTimeout("tcp", r.upstream, 5*time.Second)
	if err != nil {
		return
	}
	r.keep(server)
	defer server.Close()
	passed := flight
	if r.cut > 0 && len(passed) > r.cut {
		passed = passed[:r.cut]
	}
	if _, err := server.Write(passed); err != nil {
		return
	}
	go func() {
		_, _ = io.Copy(server, client)
		_ = server.Close()
	}()
	_, _ = io.Copy(client, server)
}

// readFirstRecord returns the first TLS record a client sends, header
// included.
func readFirstRecord(c net.Conn) ([]byte, error) {
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	defer func() { _ = c.SetReadDeadline(time.Time{}) }()
	header := make([]byte, 5)
	if _, err := io.ReadFull(c, header); err != nil {
		return nil, err
	}
	record := make([]byte, 5+int(binary.BigEndian.Uint16(header[3:5])))
	copy(record, header)
	if _, err := io.ReadFull(c, record[5:]); err != nil {
		return nil, err
	}
	return record, nil
}

type seenHello struct {
	size   int
	hybrid bool
}

// seen describes the first flight of every connection so far, in the order
// they arrived.
func (r *cuttingRelay) seen(t *testing.T) []seenHello {
	t.Helper()
	r.mu.Lock()
	flights := append([][]byte(nil), r.hellos...)
	r.mu.Unlock()
	return describeHellos(t, flights)
}

func describeHellos(t *testing.T, flights [][]byte) []seenHello {
	t.Helper()
	out := make([]seenHello, 0, len(flights))
	for _, flight := range flights {
		if len(flight) < 9 || flight[0] != 0x16 || flight[5] != 1 {
			t.Fatalf("a first flight that is not a ClientHello: % x", flight[:min(len(flight), 16)])
		}
		_, shares := helloGroupsAndShares(t, flight[5:])
		out = append(out, seenHello{len(flight), contains(shareGroups(shares), groupX25519MLKEM768)})
	}
	return out
}

func (h seenHello) long() bool { return h.hybrid && h.size > firstSegment }
func (h seenHello) short() bool { return !h.hybrid && h.size <= firstSegment }

// realityLineAt is a VLESS + Reality + Vision line to 127.0.0.1:port with
// chrome's hello, the app's default and one that carries the hybrid share.
func realityLineAt(port int, pub string) FrontParams {
	return realityLineWith(port, pub, "chrome")
}

// realityLineWith is the same line with the fingerprint a link names.
func realityLineWith(port int, pub, fp string) FrontParams {
	proxyPort, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.ProxyPort, p.ProbeListen = proxyPort, ""
	p.Upstream = Upstream{Vless: &links.VlessLine{UUID: e2eUUID, Host: "127.0.0.1", Port: port, SNI: realitySNI, PublicKey: pub, ShortID: "0123",
		Fingerprint: fp, Flow: "xtls-rprx-vision", Transport: links.Transport{Kind: "tcp"}}}
	return p
}

// fetchWithin starts a front for the line, asks for the page through it once
// and says what came back and how long it took. One request is one dial of
// the line. The front stays up for `timeout` whatever the answer, so a dial
// that would open another connection later has the time to.
func fetchWithin(t *testing.T, p FrontParams, timeout time.Duration, keepUp bool) (string, time.Duration, error) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f, err := Start(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	d, _ := proxy.SOCKS5("tcp", p.ProxyListen+":"+strconv.Itoa(p.ProxyPort), nil, proxy.Direct)
	cd, _ := d.(proxy.ContextDialer)
	hc := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext, DisableKeepAlives: true}, Timeout: timeout}
	start := time.Now()
	body, err := func() (string, error) {
		resp, err := hc.Get(throughTunnel)
		if err != nil {
			return "", err
		}
		defer resp.Body.Close()
		raw, err := io.ReadAll(resp.Body)
		return string(raw), err
	}()
	took := time.Since(start)
	if keepUp && took < timeout {
		time.Sleep(timeout - took)
	}
	return body, took, err
}

// serversByHybridRule splits XRAY_SERVERS at Xray-core v26.9.8, the first
// release whose REALITY hands a hello without an X25519MLKEM768 share to the
// camouflage target. The fallback opens the line on the servers before it and
// cannot on the ones from it on.
func serversByHybridRule(t *testing.T) (accepting, requiring []xrayBin) {
	t.Helper()
	list := os.Getenv("XRAY_SERVERS")
	if list == "" {
		t.Skip("set XRAY_SERVERS (scripts/fetch-xray-servers.sh) for the hello fallback end-to-ends")
	}
	for _, entry := range strings.Fields(list) {
		version, path, ok := strings.Cut(entry, "=")
		if !ok || version == "" || path == "" {
			t.Fatalf("XRAY_SERVERS: %q is not <version>=<path>", entry)
		}
		var v [3]int
		for i, part := range strings.SplitN(version, ".", 3) {
			n, err := strconv.Atoi(part)
			if err != nil {
				t.Fatalf("XRAY_SERVERS: version %q", version)
			}
			v[i] = n
		}
		bin := xrayBin{"xray-" + version, path}
		if v[0] > 26 || v[0] == 26 && (v[1] > 9 || v[1] == 9 && v[2] >= 8) {
			requiring = append(requiring, bin)
		} else {
			accepting = append(accepting, bin)
		}
	}
	// A skip must not read as a pass: with servers named, both kinds are.
	if len(accepting) == 0 || len(requiring) == 0 {
		t.Fatalf("XRAY_SERVERS %q: needs a server older than 26.9.8 and one from it on", list)
	}
	return accepting, requiring
}

func realityServerFor(t *testing.T, bin xrayBin, target string, pg *httptest.Server) (port int, pub string, logs *syncBuffer) {
	t.Helper()
	privB64, pubB64 := realityKey(t)
	serverPort, _ := olcrtc.FreePort()
	logs = xrayServer(t, bin, map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "vless",
			"settings": map[string]any{"clients": []map[string]any{{"id": e2eUUID, "flow": "xtls-rprx-vision"}}, "decryption": "none"},
			"streamSettings": map[string]any{"network": "tcp", "security": "reality",
				"realitySettings": map[string]any{"dest": target, "serverNames": []string{realitySNI}, "privateKey": privB64, "shortIds": []string{"0123"}}},
		}},
		"outbounds": []map[string]any{freedomToPage(pg)},
	}, serverPort)
	return serverPort, pubB64, logs
}

// TestRealityHelloFallbackEndToEnd: the line through a network that drops the
// second segment of a hello, against real servers.
func TestRealityHelloFallbackEndToEnd(t *testing.T) {
	const want = "through the short hello"
	accepting, requiring := serversByHybridRule(t)
	// The memory is the process's: nothing an earlier test learnt at a port
	// this one is handed again.
	sbtls.RealityHelloNetworkReset()
	target := realityTarget(t)
	pg := page(t, want)

	for _, server := range accepting {
		t.Run(server.name, func(t *testing.T) {
			serverPort, pub, logs := realityServerFor(t, server, target, pg)
			addr := "127.0.0.1:" + strconv.Itoa(serverPort)

			// The server reached whole: one long hello and nothing after it.
			t.Run("whole-hello-is-not-retried", func(t *testing.T) {
				relay := newCuttingRelay(t, addr, 0)
				got, _, err := fetchWithin(t, realityLineAt(relay.port(), pub), 15*time.Second, false)
				if got != want {
					t.Fatalf("%q, %v\nserver log:\n%s", got, err, logs.String())
				}
				if seen := relay.seen(t); len(seen) != 1 || !seen[0].long() {
					t.Fatalf("hellos %+v: want the one long hello", seen)
				}
				if got := rememberedAt(relay.port()); got != sbtls.RealityShortHelloUnknown {
					t.Fatalf("remembered %q for a server that answered the long hello", got)
				}
			})

			relay := newCuttingRelay(t, addr, firstSegment)
			line := func() FrontParams { return realityLineAt(relay.port(), pub) }
			// Behind the cut: the long hello goes unanswered, the dial waits
			// the window out and opens through one short hello.
			t.Run("unanswered-hello-is-retried-short", func(t *testing.T) {
				got, took, err := fetchWithin(t, line(), 20*time.Second, false)
				seen := relay.seen(t)
				if got != want {
					t.Fatalf("%q, %v after %v, hellos %+v\nserver log:\n%s", got, err, took, seen, logs.String())
				}
				if len(seen) != 2 || !seen[0].long() || !seen[1].short() {
					t.Fatalf("hellos %+v: want the long one, then the short one", seen)
				}
				if took < helloStallWindow {
					t.Fatalf("opened after %v: the long hello was not given its %v", took, helloStallWindow)
				}
				if got := rememberedAt(relay.port()); got != sbtls.RealityShortHelloWorks {
					t.Fatalf("remembered %q, want %q", got, sbtls.RealityShortHelloWorks)
				}
			})
			// What worked is remembered for the server: the next dial, from a
			// new front, starts short and does not wait.
			t.Run("next-dial-starts-short", func(t *testing.T) {
				got, took, err := fetchWithin(t, line(), 20*time.Second, false)
				seen := relay.seen(t)
				if got != want {
					t.Fatalf("%q, %v after %v, hellos %+v\nserver log:\n%s", got, err, took, seen, logs.String())
				}
				if len(seen) != 3 || !seen[2].short() {
					t.Fatalf("hellos %+v: want one more, the short one", seen)
				}
				if took >= helloStallWindow {
					t.Fatalf("opened after %v: the dial waited though the short hello is known to work here", took)
				}
			})
			// A fingerprint whose own hello has no hybrid share is sent as
			// Chrome's (firefox), and android's own has no key share at all:
			// their retry is the hello that stalled without its hybrid share,
			// not their own. Each has a relay, so a server, of its own.
			for _, fp := range []string{"firefox", "android"} {
				t.Run("unanswered-hello-is-retried-short-"+fp, func(t *testing.T) {
					relay := newCuttingRelay(t, addr, firstSegment)
					got, took, err := fetchWithin(t, realityLineWith(relay.port(), pub, fp), 20*time.Second, false)
					seen := relay.seen(t)
					if got != want {
						t.Fatalf("%q, %v after %v, hellos %+v\nserver log:\n%s", got, err, took, seen, logs.String())
					}
					if len(seen) != 2 || !seen[0].long() || !seen[1].short() {
						t.Fatalf("hellos %+v: want the long one, then the short one", seen)
					}
					if got := rememberedAt(relay.port()); got != sbtls.RealityShortHelloWorks {
						t.Fatalf("remembered %q, want %q", got, sbtls.RealityShortHelloWorks)
					}
				})
			}
		})
	}

	for _, server := range requiring {
		t.Run(server.name, func(t *testing.T) {
			serverPort, pub, logs := realityServerFor(t, server, target, pg)
			relay := newCuttingRelay(t, "127.0.0.1:"+strconv.Itoa(serverPort), firstSegment)
			line := func() FrontParams { return realityLineAt(relay.port(), pub) }
			// This server hands the short hello to its camouflage target, so
			// the retry meets a real certificate and the dial fails.
			t.Run("retry-is-refused", func(t *testing.T) {
				got, took, _ := fetchWithin(t, line(), 20*time.Second, false)
				seen := relay.seen(t)
				if got == want {
					t.Fatalf("the page came through a server that requires the hybrid share, hellos %+v\nserver log:\n%s", seen, logs.String())
				}
				if len(seen) != 2 || !seen[0].long() || !seen[1].short() {
					t.Fatalf("hellos %+v after %v: want the long one, then the short one", seen, took)
				}
				if took < helloStallWindow {
					t.Fatalf("failed after %v: the long hello was not given its %v", took, helloStallWindow)
				}
				if got := rememberedAt(relay.port()); got != sbtls.RealityShortHelloDoesNotHelp {
					t.Fatalf("remembered %q, want %q", got, sbtls.RealityShortHelloDoesNotHelp)
				}
			})
			// "It does not help here" is remembered: the next dial sends the
			// long hello alone and is left to its own timeout, as before.
			t.Run("next-dial-is-not-watched", func(t *testing.T) {
				if before := relay.seen(t); len(before) != 2 {
					t.Fatalf("hellos so far %+v: the retry did not happen", before)
				}
				got, _, _ := fetchWithin(t, line(), helloStallWindow+2500*time.Millisecond, true)
				seen := relay.seen(t)
				if got == want {
					t.Fatalf("the page came through, hellos %+v", seen)
				}
				if len(seen) != 3 || !seen[2].long() {
					t.Fatalf("hellos %+v: want one more, the long one, and no retry", seen)
				}
				// A network change empties the memory: the dial after it is
				// watched and retried again.
				sbtls.RealityHelloNetworkReset()
				if got := rememberedAt(relay.port()); got != sbtls.RealityShortHelloUnknown {
					t.Fatalf("remembered %q after the network was reset", got)
				}
			})
		})
	}
}

// answeringServer reads a ClientHello and answers it with `answer`, then
// holds the connection until the test ends. It counts what it was sent.
func answeringServer(t *testing.T, answer []byte, closeAfter bool) (port int, hellos func() [][]byte) {
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
				mu.Unlock()
				_, _ = c.Write(answer)
				if closeAfter {
					_ = c.Close()
				}
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, func() [][]byte {
		mu.Lock()
		defer mu.Unlock()
		return append([][]byte(nil), seen...)
	}
}

// TestRealityHelloAnsweredIsNeverRetried: a server that sent anything back got
// the hello, whatever it made of it. The dial ends as it always did, with no
// second connection and no short hello, however long the rest takes.
func TestRealityHelloAnsweredIsNeverRetried(t *testing.T) {
	_, pub := realityKey(t)
	sbtls.RealityHelloNetworkReset()
	for _, c := range []struct {
		name       string
		answer     []byte
		closeAfter bool
	}{
		// handshake_failure, fatal, and the connection closed
		{"an-alert", []byte{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28}, true},
		// the first byte of a record that never continues
		{"one-byte-then-silence", []byte{0x16}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			port, hellos := answeringServer(t, c.answer, c.closeAfter)
			// The front stays up past the window: whatever was going to be
			// dialled has been.
			if got, _, err := fetchWithin(t, realityLineAt(port, pub), helloStallWindow+2500*time.Millisecond, true); err == nil {
				t.Fatalf("a page came back from a server that is none: %q", got)
			}
			seen := describeHellos(t, hellos())
			if len(seen) != 1 || !seen[0].long() {
				t.Fatalf("hellos %+v: want the one long hello", seen)
			}
			if got := rememberedAt(port); got != sbtls.RealityShortHelloUnknown {
				t.Fatalf("remembered %q for a server that answered", got)
			}
		})
	}
}
