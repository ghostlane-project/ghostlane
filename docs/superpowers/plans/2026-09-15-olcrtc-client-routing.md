# olcRTC client routing (Bypass Russia in the engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Under Bypass Russia an olcRTC location on iOS dials Russian names, Russian addresses and the local network directly from the engine, resolves those names through the network's own resolver, and sends everything else through the room — with no second engine.

**Architecture:** A leaf `internal/route` package parses the app's rule text (Xray list syntax) into a suffix set, an exact-name set and merged address ranges. The SOCKS client consults it before waiting for the session: names match by suffix, addresses by range, and an address outside the ranges is sniffed (TLS server name, HTTP Host) after the SOCKS reply. Direct sockets go through the existing `protect.Dialer`; DNS for matching names goes to the existing resolver ring's preferred server over a new `Exchange` method. The app passes the rule text through `olcrtc.json` and the new `mobile.Runtime.SetDirectRules`.

**Tech Stack:** Go 1.26 (engine, `golang.org/x/net/dns/dnsmessage` already in go.mod), gomobile/gobind (iOS Cores framework), Kotlin Multiplatform (app), Swift (extension bridge).

**Spec:** `docs/superpowers/specs/2026-09-15-olcrtc-client-routing-design.md`

## Global Constraints

- Engine repo rules (`AGENTS.md`): pure Go, zero new dependencies, `golangci-lint` v2 with 0 issues, functions under 60 statements, cyclomatic complexity under 15, tests with `-race`, conventional-commit messages in lowercase, no em-dashes in code or commits, package comments, no globals without `//nolint:` + reason.
- Every engine test must pass with and without `-tags olcrtc_lean` (the phones build with the tag).
- Rules off (`nil`) must leave the wire and every existing test untouched.
- Direct sockets must be created through `protect` (the `controlFunc` pin); never a bare `net.Dial`.
- Rule syntax is exactly: `domain:<name>`, `full:<name>`, `<prefix>`, `<address>`, blank, `# comment`. Anything else is an error naming the line.
- App: no new strings outside `OlcrtcDirectRules`; Android and desktop behaviour unchanged.

Engine worktree: `/root/olcrtc-proofkit` (branch `proofkit`), env `PATH=/usr/local/go/bin:/root/go/bin:$PATH`.
App worktree: `/root/olcbox-fork` (branch `main`).

---

### Task 1: `internal/route` — rule text to a matcher

**Files:**
- Create: `internal/route/route.go`
- Test: `internal/route/route_test.go`

**Interfaces:**
- Produces:
  ```go
  package route
  var ErrRule = errors.New("route: bad rule")          // wrapped with the line number and text
  type Rules struct{ /* unexported */ }
  func Parse(text string) (*Rules, error)                // "" or comments only → (nil, nil)
  func (r *Rules) MatchDomain(name string) bool          // nil receiver → false
  func (r *Rules) MatchIP(ip netip.Addr) bool            // nil receiver → false; unmaps v4-in-v6
  func (r *Rules) MatchHost(host string) bool            // address → MatchIP, else MatchDomain
  func (r *Rules) Summary() string                       // "1103 names, 12832 prefixes"
  ```

- [ ] **Step 1: Write the failing tests**

```go
package route

import (
	"errors"
	"net/netip"
	"testing"
)

func TestParseEmptyIsOff(t *testing.T) {
	for _, text := range []string{"", "\n\n", "# only a comment\n"} {
		r, err := Parse(text)
		if err != nil || r != nil {
			t.Fatalf("Parse(%q) = %v, %v; want nil, nil", text, r, err)
		}
	}
	var off *Rules
	if off.MatchDomain("example.ru") || off.MatchIP(netip.MustParseAddr("10.0.0.1")) || off.MatchHost("x") {
		t.Fatal("nil rules matched")
	}
}

func TestParseNamesAndSuffixes(t *testing.T) {
	r, err := Parse("domain:ru\nfull:api.example.com\n# c\n\n domain:Example.Org. \n")
	if err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]bool{
		"ru": true, "yandex.ru": true, "mail.Yandex.RU.": true, "notru": false, "ru.com": false,
		"api.example.com": true, "www.api.example.com": false, "example.com": false,
		"example.org": true, "a.example.org": true,
	} {
		if got := r.MatchDomain(name); got != want {
			t.Errorf("MatchDomain(%q) = %v, want %v", name, got, want)
		}
	}
}

func TestParsePrefixesMergeAndMatch(t *testing.T) {
	r, err := Parse("10.0.0.0/8\n10.1.0.0/16\n192.168.1.0/24\n192.168.2.0/24\n5.6.7.8\n2001:db8::/32\n")
	if err != nil {
		t.Fatal(err)
	}
	for addr, want := range map[string]bool{
		"10.200.1.1": true, "192.168.1.255": true, "192.168.2.0": true, "192.168.3.0": false,
		"5.6.7.8": true, "5.6.7.9": false, "2001:db8::1": true, "2001:db9::1": false,
		"::ffff:10.0.0.1": true,
	} {
		if got := r.MatchIP(netip.MustParseAddr(addr)); got != want {
			t.Errorf("MatchIP(%s) = %v, want %v", addr, got, want)
		}
	}
	if got := r.Summary(); got != "3 names, 5 prefixes" && got != "0 names, 5 prefixes" {
		t.Errorf("Summary() = %q", got)
	}
}

func TestMatchHostPicksByShape(t *testing.T) {
	r, _ := Parse("domain:ru\n10.0.0.0/8\n")
	if !r.MatchHost("ozon.ru") || !r.MatchHost("10.9.8.7") || r.MatchHost("example.com") || r.MatchHost("8.8.8.8") {
		t.Fatal("MatchHost")
	}
}

func TestParseRejectsUnknownLines(t *testing.T) {
	for _, text := range []string{"keyword:ru", "regexp:.*", "example.ru", "domain:", "10.0.0.0/33", "not an ip"} {
		_, err := Parse("domain:ru\n" + text + "\n")
		if !errors.Is(err, ErrRule) {
			t.Errorf("Parse(%q) error = %v, want ErrRule", text, err)
		}
		if err != nil && !strings.Contains(err.Error(), "line 2") {
			t.Errorf("Parse(%q) error %q does not name line 2", text, err)
		}
	}
}
```
(`Summary` counts: 0 names, 5 prefixes for the prefix test — fix the assertion to `"0 names, 5 prefixes"` once written.)

- [ ] **Step 2: Run to verify failure**

Run: `cd /root/olcrtc-proofkit && go test ./internal/route/`
Expected: build failure, package does not exist.

- [ ] **Step 3: Implement**

```go
// Package route decides which destinations a client dials directly instead
// of through the tunnel: a set of names and address prefixes parsed from
// text, one rule per line, in the syntax Xray's lists use so an app can hand
// the same file to either.
//
// ai-generated: the whole package (olcbox#28).
package route

// Rules: full map[string]struct{}, suffix map[string]struct{}, ranges []addrRange (sorted, merged), names, prefixes int.
// Parse: split lines, trim, skip blank/#; "domain:"→suffix, "full:"→full, else netip.ParsePrefix / netip.ParseAddr (→ /32 or /128); else fmt.Errorf("%w: line %d: %q", ErrRule, n, line). Names: strings.ToLower, TrimSuffix("."), empty → error. After the loop: sort ranges by lo (Addr.Compare), merge when next.lo <= prev.hi.Next() (adjacent) — compute with Compare; keep counts. Return nil when both maps are empty and no ranges.
// MatchDomain: normalise; full hit; then for i := 0; ; { suffix[name[i:]] hit; i = next dot+1 }.
// MatchIP: ip = ip.Unmap(); binary search on ranges by lo: idx := sort.Search(len, ranges[i].lo.Compare(ip) > 0) - 1; idx >= 0 && ranges[idx].hi.Compare(ip) >= 0.
// addrRange{lo, hi netip.Addr}; hi of a prefix = last address: for v4 use uint32 math via As4; for v6 via As16 with a mask.
```

- [ ] **Step 4: Run to verify pass**

Run: `go test -race ./internal/route/ && golangci-lint run ./internal/route/`
Expected: PASS, 0 issues.

- [ ] **Step 5: Commit**

```bash
git add internal/route && git commit -m "feat(route): rules for destinations a client dials directly"
```

---

### Task 2: `internal/sniff` — server name from the first bytes

**Files:**
- Create: `internal/sniff/sniff.go`
- Test: `internal/sniff/sniff_test.go`

**Interfaces:**
- Produces:
  ```go
  package sniff
  const MaxHead = 16 * 1024   // the most a caller should read before giving up
  func Host(head []byte) (host string, need bool)
  ```
  `need=true` means read more (a TLS record longer than head, an HTTP request without the end of headers, fewer than 5 bytes). `host` is lowercased, without a port; `""` with `need=false` means "no name here".

- [ ] **Step 1: Write the failing tests**

```go
package sniff

import (
	"crypto/tls"
	"net"
	"testing"
)

// clientHello captures the bytes crypto/tls sends first for serverName.
func clientHello(t *testing.T, serverName string) []byte {
	t.Helper()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	go func() { _ = tls.Client(a, &tls.Config{ServerName: serverName, InsecureSkipVerify: true}).Handshake() }()
	buf := make([]byte, MaxHead)
	n, err := b.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	return buf[:n]
}

func TestHostFromClientHello(t *testing.T) {
	for _, name := range []string{"yandex.ru", "www.gosuslugi.ru", "xn--80adxhks.xn--p1ai"} {
		hello := clientHello(t, name)
		got, need := Host(hello)
		if need || got != name {
			t.Errorf("Host(hello %s) = %q, need=%v", name, got, need)
		}
		// Every prefix of the record asks for more, never guesses.
		for cut := 1; cut < len(hello); cut++ {
			if got, need := Host(hello[:cut]); !need || got != "" {
				t.Fatalf("Host(hello[:%d]) = %q, need=%v; want more", cut, got, need)
			}
		}
	}
}

func TestHostFromHTTP(t *testing.T) {
	for req, want := range map[string]string{
		"GET / HTTP/1.1\r\nHost: Example.RU\r\n\r\n":              "example.ru",
		"POST /x HTTP/1.1\r\nhost: example.ru:8080\r\nX: y\r\n\r\n": "example.ru",
		"GET / HTTP/1.1\r\nHost: [2001:db8::1]:80\r\n\r\n":         "2001:db8::1",
	} {
		if got, need := Host([]byte(req)); need || got != want {
			t.Errorf("Host(%q) = %q, need=%v; want %q", req, got, need, want)
		}
	}
	if got, need := Host([]byte("GET / HTTP/1.1\r\nHost: a.ru\r\n")); !need || got != "" {
		t.Errorf("unfinished headers: %q, need=%v", got, need)
	}
}

func TestHostFromJunk(t *testing.T) {
	for _, junk := range [][]byte{[]byte("SSH-2.0-OpenSSH\r\n"), {0x16, 0x03, 0x01, 0x00, 0x05, 0x02, 0, 0, 1, 0}, []byte("GET ")} {
		got, need := Host(junk)
		if got != "" {
			t.Errorf("Host(%q) = %q", junk, got)
		}
		_ = need
	}
	if _, need := Host([]byte("GET ")); !need { t.Error("a short request should ask for more") }
	if _, need := Host([]byte("SSH-2.0-OpenSSH\r\n")); need { t.Error("ssh is neither tls nor http") }
}
```

- [ ] **Step 2: Run to verify failure** — `go test ./internal/sniff/` → package missing.

- [ ] **Step 3: Implement**

Rules of the parser (all bounds-checked, every read via a small cursor helper returning ok=false on short input):
- `len(head) < 5` → `("", true)`.
- TLS: `head[0]==0x16 && head[1]==3`; `rec := int(head[3])<<8|int(head[4])`; `rec > 16384` → not TLS; `len(head) < 5+rec` → need; body `head[5:5+rec]`; `body[0] != 1` → ""; `hsLen := 3 bytes`; `hsLen > len(body)-4` → "" (spans records); skip version 2, random 32, session id (1+n), cipher suites (2+n), compression (1+n); extensions (2+n): loop type(2) len(2) data; type 0 → list len(2), name type(1)==0, name len(2), name → lower, return.
- HTTP: the head starts with one of `GET `, `POST `, `PUT `, `HEAD `, `DELETE `, `OPTIONS `, `PATCH `, `CONNECT `, `TRACE ` (or is a proper prefix of one of them → need); end of headers `\r\n\r\n` absent → need if `len(head) < MaxHead` else ""; scan lines after the first for a name equal-fold `host`; value trimmed; `[v6]:port` → inside brackets; `h:port` → before the last colon when exactly one colon.
- Otherwise `("", false)`.

- [ ] **Step 4: Run to verify pass** — `go test -race ./internal/sniff/ && golangci-lint run ./internal/sniff/`

- [ ] **Step 5: Commit** — `git commit -m "feat(sniff): the server name from a client's first bytes"`

---

### Task 3: `protect.Resolver.Exchange` — a raw query on the ring

**Files:**
- Modify: `internal/protect/resolver.go`
- Test: `internal/protect/resolver_test.go` (append)

**Interfaces:**
- Produces:
  ```go
  // Exchanger sends one DNS message and returns its response.
  type Exchanger interface { Exchange(ctx context.Context, query []byte) ([]byte, error) }
  func (r *Resolver) HasServers() bool
  func (r *Resolver) Exchange(ctx context.Context, query []byte) ([]byte, error)  // ErrDNSUnreachable when no servers
  ```
  Uses `ring.dial(ctx, "udp", "")`, which already picks the preferred server and wraps the conn so silence demotes it; one write, one read into a 4096-byte buffer, a copy returned. `dnsQueryTimeout` is applied by the wrapped conn's Read.

- [ ] **Step 1: Test** — `TestExchangeAsksThePreferredServer`: `fakedns.Start({"a.ru": "10.0.0.1"})`, `NewResolver(srv.Addr)`, build a query with `dnsmessage.Builder` for `a.ru.` A, `Exchange` → parse the answer, expect 10.0.0.1 and `srv.Queries()==1`. `TestExchangeMovesPastASilentServer`: `StartSilent` first, answering second in the list; first `Exchange` errors (timeout) and demotes; second `Exchange` answers. `TestExchangeWithoutServers`: `NewResolver("")` → `HasServers()==false`, `Exchange` → `ErrDNSUnreachable`.
- [ ] **Step 2: Run** — fails to compile.
- [ ] **Step 3: Implement** as above; the buffer is `make([]byte, 4096)` per call (a query is rare next to a packet).
- [ ] **Step 4: Run** — `go test -race ./internal/protect/`.
- [ ] **Step 5: Commit** — `git commit -m "feat(protect): a raw dns exchange on the resolver ring"`

---

### Task 4: client — direct CONNECT, sniffing, replay

**Files:**
- Modify: `internal/client/client.go` (Config.Direct, Client.direct/dialer, start log), `internal/client/socks.go` (`handleSocks5` → `serveConnect`), `internal/client/tunnel.go` (`head`, `replied`)
- Create: `internal/client/direct.go`
- Test: `internal/client/direct_test.go`

**Interfaces:**
- Consumes: `route.Rules`, `sniff.Host`, `protect.NewDialer(cfg.Resolver)`.
- Produces (unexported):
  ```go
  type routeDecision int  // routeTunnel, routeDirect, routeSniff
  func (c *Client) classifyConnect(host string) (routeDecision, string /*reason*/)
  func (c *Client) serveConnect(ctx context.Context, conn net.Conn, req socksRequest)
  func (c *Client) tunnelWhenReady(ctx, conn, host string, port int, head []byte, replied bool)
  func (c *Client) tunnel(ctx, conn, session, host, port, head []byte, replied bool)
  func (c *Client) direct(ctx, conn net.Conn, host string, port int, head []byte, replied bool, fallbackIP string)
  func (c *Client) sniffThenRoute(ctx, conn, req socksRequest)
  var sniffTimeout = 300 * time.Millisecond //nolint:gochecknoglobals // test hook
  ```

- [ ] **Step 1: Tests** (`direct_test.go`; the client is built like `newDNSTestClient` but with `direct: rules, dialer: protect.NewDialer(nil)`, and a helper `socksConnect(t, c, host, port) net.Conn` that runs `c.handleSocks5` on one end of a `net.Pipe` and performs the SOCKS5 no-auth handshake + CONNECT on the other, returning the client end after the reply):
  - `TestDirectByNameNeedsNoSession`: rules `full:direct.test` hmm — a name must resolve: use rules `domain:localhost`? Names resolve through `protect.NewDialer(nil)` = the system resolver; `localhost` resolves on every CI box. Rules: `full:localhost`; local TCP echo server on `127.0.0.1:0`; CONNECT `localhost:<port>` with `c.session == nil` → reply success, echo round-trips.
  - `TestDirectByPrefix`: rules `127.0.0.0/8`; CONNECT `127.0.0.1:<port>` → echo.
  - `TestSniffedNameGoesDirectWithReplay`: rules `domain:sni.test` (a name that does not resolve) → the fallback IP must be used: CONNECT `127.0.0.1:<port>` (not in rules), after the reply write `clientHello(t, "a.sni.test")`, the echo server receives exactly those bytes first (replay), then echoes.
  - `TestSniffedForeignNameTakesTheTunnelWithReplay`: rules `domain:sni.test`; a smux pair like `newDNSTestClient` whose server accepts CONNECT to `127.0.0.1:<port>` and then reads the hello bytes from the stream; CONNECT `127.0.0.1:<port>`, write `clientHello(t, "example.com")` → server sees the CONNECT then the hello.
  - `TestSilentClientTakesTheTunnel`: rules on, `sniffTimeout = 50ms`, CONNECT to an address outside the rules, write nothing → the stream CONNECT happens within a second.
  - `TestRulesOffKeepsTheReplyAfterTheAck`: rules nil → the SOCKS reply arrives only after the server acks (existing behaviour: server delays the ack 100 ms, client sees no reply before it).
- [ ] **Step 2: Run** — compile failure.
- [ ] **Step 3: Implement**
  - `client.go`: `Config.Direct *route.Rules`; `Client.direct *route.Rules; dialer *protect.Dialer`; in `RunWithAddress`: `direct: cfg.Direct, dialer: protect.NewDialer(cfg.Resolver)`; after the listener log: `if client.direct != nil { logger.Infof("direct rules: %s", client.direct.Summary()) }`.
  - `socks.go`: `handleSocks5` ends with `c.serveConnect(ctx, conn, req)`; `serveConnect` switches on `classifyConnect`; `tunnelWhenReady` holds the old session-wait loop (60 s) and calls `c.tunnel(ctx, conn, session, host, port, head, replied)`; on timeout writes host-unreachable only when `!replied`.
  - `tunnel.go`: after a successful CONNECT ack: `if !replied { write replySuccess }`; `if len(head) > 0 { stream.Write(head) }`; on failure: reply only when `!replied`.
  - `direct.go`: `direct()` dials `net.JoinHostPort(host, port)` via `c.dialer.DialContext(ctx, "tcp", ...)`; on error and `fallbackIP != ""` dial the IP; on error reply host-unreachable when `!replied` and return; else reply success when `!replied`, replay head, `tunnelcore.CopyBidirectional(ctx, conn, remote)`. `sniffThenRoute()`: write `replySuccess`; `head := sniffBufPool.Get()`; `conn.SetReadDeadline(now+sniffTimeout)`; loop `n, err := conn.Read(head[filled:])`, `host, need := sniff.Host(head[:filled])`, stop when `!need` or err or `filled == len(head)`; clear the deadline; copy `head[:filled]` into a right-sized slice, return the buffer to the pool; if `host != "" && c.direct.MatchDomain(host)` → `direct(ctx, conn, host, port, copy, true, req.addr)`; else `tunnelWhenReady(ctx, conn, req.addr, req.port, copy, true)`. Pool: `sync.Pool{New: make([]byte, sniff.MaxHead)}` as a package var with `//nolint:gochecknoglobals // pooled buffers`.
  - Log lines: `logger.Infof("direct to %s:%d (%s)", host, port, reason)`.
- [ ] **Step 4: Run** — `go test -race ./internal/client/ && golangci-lint run ./internal/client/`.
- [ ] **Step 5: Commit** — `git commit -m "feat(client): dial rule-matched destinations directly, sniffing a name when the socks client gives an address"`

---

### Task 5: client — direct UDP flows and direct DNS

**Files:**
- Modify: `internal/client/udp.go` (`forwardLocalUDP`, sweeper, `removeUDPFlowsForConn`), `internal/client/dns.go` (`tryDNSDirect`), `internal/client/client.go` (fields)
- Create: `internal/client/direct_udp.go`
- Test: `internal/client/direct_udp_test.go`, `internal/client/dns_test.go` (append)

**Interfaces (unexported):**
```go
type directUDPFlow struct{ assoc *net.UDPConn; remote *net.UDPConn; client *net.UDPAddr; target udpwire.Endpoint; lastSeen time.Time }
func (c *Client) tryDirectUDP(ctx context.Context, assoc *net.UDPConn, src *net.UDPAddr, target udpwire.Endpoint, payload []byte) bool
func (c *Client) tryDNSDirect(ctx context.Context, assoc *net.UDPConn, src *net.UDPAddr, target udpwire.Endpoint, payload []byte) bool
func dnsQuestionName(query []byte) (string, bool)   // dnsmessage.Parser: Start, Question; name without the trailing dot
```
`Client` gains `directUDP map[clientUDPFlowKey]*directUDPFlow` under `udpMu`, counted against `normalizeMaxUDPFlows(c.maxUDPFlows)`.

- [ ] **Step 1: Tests**
  - `TestDirectUDPEchoesThroughTheAssociation`: a UDP echo server on `127.0.0.1:0`; rules `127.0.0.0/8`; client with `udpFlows` maps; `assoc, cli := dnsTestSockets(t)`; call `c.forwardLocalUDP(ctx, lane, assoc, cliAddr, socksDatagram(t, "127.0.0.1", port, []byte("ping")))`; read `cli` → a SOCKS UDP datagram whose payload is `ping` and target `127.0.0.1:port`; the lane recorded nothing.
  - `TestDirectUDPFlowsAreSweptAndClosedWithTheAssociation`: create a flow with `lastSeen` old → `removeIdleUDPFlows(now)` closes `remote` (read on it returns ErrClosed) and deletes the entry; `removeUDPFlowsForConn(assoc)` likewise.
  - `TestDNSForADirectNameAsksTheRing` (dns_test.go): `fakedns.Start({"a.ru": "10.0.0.1"})`; `resolver := protect.NewResolver(srv.Addr)`; client via `newDNSTestClient` with `direct: Parse("domain:ru")`, `exchanger: resolver`; send a query for `a.ru.` A as a port-53 datagram to `9.9.9.9`; the answer arrives on `cli` with 10.0.0.1 and `srv.accepted` stays empty (no stream). Then a query for `example.com.` → the stream (existing behaviour, `srv.accepted` receives one).
  - `TestDNSDirectFallsToTheStreamWithoutServers`: `exchanger := protect.NewResolver("")` → the stream is used for `a.ru`.
- [ ] **Step 2: Run** — compile failure.
- [ ] **Step 3: Implement**
  - `client.go`: `Client.exchanger protect.Exchanger` set in `RunWithAddress` when `cfg.Resolver` implements it (`if ex, ok := cfg.Resolver.(protect.Exchanger); ok`).
  - `forwardLocalUDP`: `if target.Port == dnsPort { if c.tryDNSDirect(...) {return}; if c.tryDNSOverStream(...) {return} }` then `if c.tryDirectUDP(...) {return}` then the lane.
  - `tryDirectUDP`: `c.direct == nil || !c.direct.MatchHost(target.Host)` → false; lock; existing flow → `remote.Write(payload)`, `lastSeen=now`, true; cap → log debug, return false hmm — the spec says the packet takes the lane when the table is full: return false; new flow: `c.dialer.DialContext(ctx, "udp", host:port)` (outside the lock), `remote.(*net.UDPConn)`; insert; `c.goTracked(readLoop)`; `ensureUDPFlowSweeper(ctx)`; write. Read loop: `buf := make([]byte, udpAssociationReadBufferSize())`; `n, err := remote.Read(buf)`; on err return; `packet := buildSocksUDP(target, buf[:n])`; `assoc.WriteToUDP(packet, client)`; update `lastSeen` under the lock.
  - Sweeper: `removeIdleUDPFlowsMatching` also walks `directUDP`: idle → delete + `remote.Close()` (outside the lock, collect first). `removeUDPFlowsForConn` likewise for `assoc == conn`.
  - `tryDNSDirect`: `c.direct == nil || c.exchanger == nil` → false; a `*protect.Resolver` without servers → false (check via `HasServers` when the exchanger is one; simplest: define `type serverLister interface{ HasServers() bool }` and check); `name, ok := dnsQuestionName(payload)`; `!ok || !c.direct.MatchDomain(name)` → false; cap via `dnsInFlight` as the stream path; goroutine: `ctx, cancel := context.WithTimeout(ctx, dnsQueryDeadline)`; `resp, err := c.exchanger.Exchange(ctx, query)`; on err debug log, return; `buildSocksUDP(target, resp)` → `assoc.WriteToUDP`. Log once per session `dns direct: resolver ring` — reuse `announce` shape with its own flag `dnsDirectAnnounced atomic.Bool`.
- [ ] **Step 4: Run** — `go test -race ./internal/client/ && go test -race -tags olcrtc_lean ./internal/client/ && golangci-lint run ./...`
- [ ] **Step 5: Commit** — `git commit -m "feat(client): direct udp flows and direct dns for rule-matched names"`

---

### Task 6: configuration surfaces — mobile, public client, yaml, docs

**Files:**
- Modify: `mobile/config.go`, `mobile/config_test.go`
- Modify: `pkg/olcrtc/client/client.go`, `pkg/olcrtc/client/client_test.go`
- Modify: `internal/app/session/config.go`, `internal/app/session/run.go`
- Modify: `internal/config/config.go`, `internal/config/config_test.go`
- Modify: `docs/configuration.md`, `docs/configuration.ru.md`

**Interfaces:**
```go
// mobile
func (r *Runtime) SetDirectRules(text string) error   // ErrInvalidConfig wrapping route.ErrRule; "" clears
// pkg/olcrtc/client
type Config struct{ ...; DirectRules string }          // parsed in RunWithAddress; error "client: direct rules: ..."
// session
type Config struct{ ...; DirectRules string }
// config (yaml)
type Route struct { Direct []string `yaml:"direct"`; DirectFile string `yaml:"direct_file"` }
type Settings struct{ ...; Route Route `yaml:"route"` }
```

- [ ] **Step 1: Tests**
  - mobile: `TestSetDirectRulesParsesNow` (bad text → `ErrInvalidConfig`; good text → `clientConfig().Direct != nil`; `""` → nil).
  - pkg client: `TestRunRefusesBadDirectRules` (runner never called, error contains "direct rules"); `TestConfigMapping` gains `DirectRules` → the runner's `cfg.Direct.MatchDomain("a.ru")`.
  - config: `TestLoadRouteInlineAndFile` (a temp yaml with `route.direct` + `route.direct_file` pointing at a temp file; `Apply(file).DirectRules` joins inline then file lines with `\n`); a profile overriding `route.direct` replaces it.
- [ ] **Step 2: Run** — failures.
- [ ] **Step 3: Implement**; `Apply`/`ApplySettings`: `if len(s.Route.Direct) > 0 || s.Route.DirectFile != ""` → `dst.DirectRules = strings.Join(lines, "\n")` where the file was read at `Load` (`loadExternalSecrets` → `resolveRoute`, relative to the config's dir, like `keys_file`). `runClient`: `DirectRules: cfg.DirectRules` → the public client, or parse in session? `session.runClient` calls `internalclient.Run(client.Config{...})` — parse there with `route.Parse` and set `Direct`; error `fmt.Errorf("route: %w", err)`.
  - Docs: a `route.direct` / `route.direct_file` row in the schema table and a short "Direct routes" section (rule syntax, what goes where, the DNS behaviour) in both languages.
- [ ] **Step 4: Run** — `mage check` (fmt, build, vet, lint, tests) plus `go test -race -tags olcrtc_lean ./...`.
- [ ] **Step 5: Commit** — `git commit -m "feat(config): route.direct rules from yaml, the mobile runtime and the library"`; push `proofkit`; record the pseudo-version: `TZ=UTC git log -1 --format='v0.0.0-%cd-%h' --date=format-local:%Y%m%d%H%M%S --abbrev=12`.

---

### Task 7: app — rule text and the iOS wiring

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OlcrtcDirectRules.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/OlcrtcDirectRulesTest.kt`
- Modify: `sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/IosBridge.kt` (`IosOlcRtcStartRequest.directRules: String = ""`), `sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt` (`packetTunnelRequest`, `startRequest`)
- Modify: `iosApp/iosApp/OlcboxIosApp.swift` (`olcrtcParameters` adds `"directRules"`), `iosApp/iosApp/SwiftOlcRtcManager.swift` (`setDirectRules`), `iosApp/PacketTunnel/OlcrtcEngine.swift` (`Parameters.directRules: String?`, `setDirectRules`)
- Modify: `scripts/typecheck-ios-olcrtc.sh` (shim gains `public func setDirectRules(_ t: String?) throws {}`)

**Interfaces:**
```kotlin
object OlcrtcDirectRules {
    /** The engine's rule text under Bypass Russia: private ranges, then the name lists, then the address list. */
    suspend fun text(): String
    const val NONE = ""
}
```

- [ ] **Step 1: Test** — `everyLineIsARuleTheEngineTakes` (each non-empty line matches `^(domain:|full:)[a-z0-9.-]+$` or an IPv4/IPv6 prefix regex), `includesThePrivateRangesAndTheBareTld` (`10.0.0.0/8`, `fe80::/10`, `domain:ru` present), `hasNoBlankLines`.
- [ ] **Step 2: Run** — `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests 'org.olcbox.app.net.OlcrtcDirectRulesTest'` → fails to compile.
- [ ] **Step 3: Implement** the object (`XrayConfig.PRIVATE_RANGES + lists.domains + lists.cidrs` joined by `\n` plus a trailing newline); iOS: `packetTunnelRequest` computes `directRules = if (routing is Routing.BypassRussia) OlcrtcDirectRules.text() else OlcrtcDirectRules.NONE` and passes it to `startRequest`; Swift as listed; `OlcrtcEngine.start`: `try runtime.setDirectRules(parameters.directRules ?? "")` right after `setDNS`, and `log.info("direct rules: \(bytes) bytes")`.
- [ ] **Step 4: Run** — jvmTest; `:desktopApp:compileKotlin`; `SWIFTC=... bash scripts/typecheck-ios-olcrtc.sh` (toolchain per `feedback_swift_typecheck_on_linux`).
- [ ] **Step 5: Commit** — `git commit -m "feat(ios): bypass russia on olcrtc through the engine's direct rules"`

---

### Task 8: app — pins, docs, notes; then build and release

**Files:**
- Modify: `scripts/cores-pins.sh` (`OLCRTC_VERSION`, `CORES_BUILD` 23 → 24 with a paragraph), `.github/workflows/ios-frameworks.yml` (`OLCRTC_VERSION`)
- Modify: `docs/ios-one-go-runtime.md` (the Bypass Russia decision), `docs/testflight-notes.md`, create `docs/release-notes/pending.md`

- [ ] **Step 1: Check the pseudo-version resolves** — `GOFLAGS=-mod=mod go mod download -json github.com/ghostlane-project/olcrtc@<pseudo>` prints a `Version` without error (the engine must be pushed first).
- [ ] **Step 2: Edit pins and docs; commit** — `git commit -m "build(cores): engine with direct rules (olcbox#28), cores b24"`; push `main`.
- [ ] **Step 3: Cores** — `POST /repos/ghostlane-project/ghostlane/actions/workflows/ios-frameworks.yml/dispatches {"ref":"main","inputs":{"publish":"true"}}`; poll the run to success (~10 min); confirm the tag `ios-cores-…-rtc<12hex>-b24`.
- [ ] **Step 4: Release** — `POST …/workflows/release.yml/dispatches {"ref":"main","inputs":{"platforms":"all","play_track":"internal"}}`; poll (~40 min); delete `docs/release-notes/pending.md` after dispatch.
- [ ] **Step 5: Comment on olcbox#28** with the version, the engine commit, and the device checks; shred the token.
