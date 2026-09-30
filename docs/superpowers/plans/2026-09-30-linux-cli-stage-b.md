# Ghostlane CLI for Linux — Stage B Implementation Plan (share links)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ghostlane` connects the VLESS Reality, Hysteria2 and XHTTP lines of the same lists it already reads — a partner's plain list (8 hy2 + 9 XHTTP lines today) and ProofKit's `/sub/<token>/unified` — with the app's dispatch: Reality and Hysteria2 as sing-box outbounds, XHTTP through an in-process Xray-core.

**Architecture:** `links` learns the two link grammars (a port of the app's `LinkParser`); the sing-box front's upstream becomes a union (SOCKS to an engine, or a native vless/hysteria2 outbound) and gains a loopback probe inbound the daemon supervises through; a new `engine/xray` runs Xray-core as a library behind a loopback SOCKS, shaped like the olcRTC engine; the daemon's `bringUp` dispatches by kind, and in tun mode a native line is proven in a proxy-only front before the tun front replaces it, so a dead server never black-holes the box.

**Tech Stack:** Stage A's, plus `github.com/xtls/xray-core` at the version the iOS Cores link (`v1.260327.1-0.20260711155151-50231eaff98c`), imported with `_ "github.com/xtls/xray-core/main/distro/all"`; sing-box build tags `with_utls,with_quic` (already on).

**Spec:** `docs/superpowers/specs/2026-09-30-linux-cli-design.md` (§3.5 Stage B, §5, §6). Stage A plan: `docs/superpowers/plans/2026-09-30-linux-cli-stage-a.md` (merged as PR #78).

## Global Constraints

- Everything in Stage A's Global Constraints still holds (pins test, `CGO_ENABLED=0`, tags, names, GPL-3, no exec, never tun on DATA's host network).
- Xray-core pinned in `cli/go.mod` to `v1.260327.1-0.20260711155151-50231eaff98c` (the iOS Cores' version via libxray v1.260711.0); a comment in go.mod names that origin. Xray's role is XHTTP only.
- Config shapes are the app's, byte-compatible where the app's tests pin them: sing-box vless = `SingBoxConfig.addOutbound` (uuid, `packet_encoding: xudp`, `flow` when present, tls{enabled, server_name, utls{enabled, fingerprint}, reality{enabled, public_key, short_id} when pbk}); sing-box hysteria2 = (password, obfs{salamander, password} when present, tls{enabled, server_name, insecure: insecure || pin != ""}); Xray xhttp = `XrayConfig.buildXhttp` (socks inbound with password auth + udp; vless outbound `encryption none`; streamSettings network xhttp; `security reality` with realitySettings{serverName, fingerprint, publicKey, shortId} when pbk, else `security tls` with tlsSettings{serverName, fingerprint, allowInsecure false}; xhttpSettings{path, host, mode, scMaxEachPostBytes 200000}).
- Unsupported transports/kinds stay listed with a note, never guessed: vless over grpc/ws/httpupgrade, trojan, shadowsocks, vmess.
- Partner's plain list labels carry no country code (`🇷🇺 EKB · Hy2 → 🇪🇺`): those lines are selected by index or label; nothing is inferred.

## Review Focus

1. A native line (Reality/hy2) whose server is down, selected in tun mode at boot: the box must never be routed into a tun with no working upstream — the proxy-only pre-flight fails, the tun is never created, backoff continues. Test in Task 5.
2. A vless line with `type=xhttp` but no `pbk` (the CDN entry): Xray with `security tls`, `allowInsecure false`, `host` from the link (not the SNI) when given. Test in Task 4.
3. A hysteria2 line with `pinSHA256` and no `obfs`: `insecure: true` and no `obfs` object; with `insecure=1` and no pin: `insecure: true`. Test in Task 3.
4. A list mixing kinds in one country (our unified list: `DE via RU | …`, `… · Hysteria2`, `… · XHTTP`, `DE · olcRTC`): `connect DE` tries them in list order and fails over across kinds, tearing down the right engine each time. Test in Task 5.
5. `flow` must only reach sing-box for the tcp transport; an xhttp line with a stray `flow` must not set it (the app drops it). Test in Task 2.

---

### Task 1: Xray-core dependency and pins

**Files:**
- Modify: `cli/go.mod`, `cli/go.sum`, `cli/cmd/ghostlane/main.go` (blank import), `cli/internal/pins/pins.go`, `cli/internal/pins/pins_test.go`, `cli/Makefile` (ldflags `main.xrayPin`), `cli/cmd/ghostlane/commands.go` (version prints the Xray pin), `cli/COPYRIGHT`, `THIRD_PARTY_NOTICES.md` (Xray-core row in the CLI table: linked, MPL-2.0)

**Interfaces:**
- Produces: `pins.Pins.Xray` (from go.mod), `main.xrayPin` ldflag, `ipc.VersionInfo.Xray`.

- [ ] **Step 1: Failing pins test** — extend `TestGoModMatchesRepoPins` with `if mod.Xray != "v1.260327.1-0.20260711155151-50231eaff98c" { t.Fatalf("xray pin %q", mod.Xray) }` and a regex `xrayModRe = ^\s*github\.com/xtls/xray-core (\S+)` in `FromGoMod`. Run → FAIL (field missing).
- [ ] **Step 2: Add the module** — `go get github.com/xtls/xray-core@v1.260327.1-0.20260711155151-50231eaff98c`; `go mod tidy`; main.go: `_ "github.com/xtls/xray-core/main/distro/all"`; `make build` proves the merged graph links (the iOS Cores link the same pair, so a conflict is unexpected; if MVS raises a shared module, bump it with `go get` and note it in the commit). `ghostlane version` prints `xray <pin>` (Makefile: `XRAY_PIN := $(shell sed -n 's/^\s*github.com\/xtls\/xray-core \(\S*\).*/\1/p' go.mod | head -1)`, `-X main.xrayPin=$(XRAY_PIN)`; `ipc.VersionInfo` gains `Xray string \`json:"xray"\``).
- [ ] **Step 3: pins.go** — add `Xray` to `Pins` and `FromGoMod`; test → PASS. Notices: add the Xray-core row (`Linked as a Go library: XHTTP outbounds behind a loopback SOCKS. MPL-2.0 (file-level copyleft; unmodified)`); `COPYRIGHT` already names it.
- [ ] **Step 4: Commit** — `feat(cli): link Xray-core at the iOS Cores' version`

---

### Task 2: Link grammar — vless (tcp Reality, xhttp) and hysteria2

**Files:**
- Create: `cli/internal/links/share.go`, `cli/internal/links/share_test.go`
- Modify: `cli/internal/links/list.go` (`Entry.Vless`, `Entry.Hy2`, `Entry.Connectable()`, kinds), `cli/internal/links/list_test.go` (plain fixture now yields connectable entries), `cli/internal/links/testdata/proofkit-unified.txt` (new: ProofKit-shaped lines — Reality tcp with flow, XHTTP packet-up, hysteria2 with pin, one olcRTC — synthetic values)

**Interfaces:**
- Produces:
```go
type Transport struct { Kind string /* "tcp" | "xhttp" */; Path, Host, Mode string }
type VlessLine struct { UUID, Host string; Port int; SNI, PublicKey, ShortID, Fingerprint, Flow string; Transport Transport; Label string }
type Hy2Line struct { Password, Host string; Port int; SNI, ObfsPassword, PinSHA256 string; Insecure bool; Label string }
func ParseVless(line string) (*VlessLine, error)   // ErrUnsupportedTransport for grpc/ws/httpupgrade/other
func ParseHy2(line string) (*Hy2Line, error)       // hysteria2:// and hy2://
func (e Entry) Connectable() bool                   // olcrtc, vless(tcp|xhttp), hysteria2
```
- `Entries` fills `Vless`/`Hy2`, sets `Problem` only for unsupported shapes (`"vless over grpc is not supported"` etc.), and `Kind` `KindVless`/`KindHysteria2` as before; the "next version" notes are gone.

- [ ] **Step 1: Failing tests**

```go
// cli/internal/links/share_test.go
package links

import (
	"errors"
	"testing"
)

const uuid = "00000000-0000-4000-8000-000000000000"

func TestParseVlessRealityTCP(t *testing.T) {
	l, err := ParseVless("vless://" + uuid + "@203.0.113.9:443?type=tcp&security=reality&sni=yandex.ru&fp=chrome&pbk=PBK&sid=ab12&flow=xtls-rprx-vision#DE via RU | 0.13TON/GB")
	if err != nil {
		t.Fatal(err)
	}
	if l.UUID != uuid || l.Host != "203.0.113.9" || l.Port != 443 || l.SNI != "yandex.ru" || l.PublicKey != "PBK" || l.ShortID != "ab12" ||
		l.Fingerprint != "chrome" || l.Flow != "xtls-rprx-vision" || l.Transport.Kind != "tcp" || l.Label != "DE via RU | 0.13TON/GB" {
		t.Fatalf("%+v", l)
	}
	// defaults: no type = tcp, no fp = chrome, label = host
	l, _ = ParseVless("vless://" + uuid + "@h.example:8443?security=reality&sni=s&pbk=P&sid=1")
	if l.Transport.Kind != "tcp" || l.Fingerprint != "chrome" || l.Label != "h.example" {
		t.Fatalf("%+v", l)
	}
}

func TestParseVlessXhttp(t *testing.T) {
	// Review Focus 5: flow is dropped for xhttp
	l, err := ParseVless("vless://" + uuid + "@45.153.230.44:8444?type=xhttp&security=reality&encryption=none&pbk=P&sid=S&fp=chrome&sni=yandex.ru&path=/pk&host=yandex.ru&mode=packet-up&flow=xtls-rprx-vision#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20XHTTP")
	if err != nil {
		t.Fatal(err)
	}
	if l.Transport != (Transport{Kind: "xhttp", Path: "/pk", Host: "yandex.ru", Mode: "packet-up"}) || l.Flow != "" || l.Label != "🇷🇺 EKB · XHTTP" {
		t.Fatalf("%+v", l)
	}
	// Review Focus 2: CDN entry, TLS not Reality, host from the link
	l, _ = ParseVless("vless://" + uuid + "@cdn.example:443?type=xhttp&security=tls&path=/xh-de/&mode=stream-one&sni=cdn.example&host=cdn.example&fp=chrome&alpn=h2#CDN")
	if l.PublicKey != "" || l.Transport.Mode != "stream-one" || l.Transport.Host != "cdn.example" {
		t.Fatalf("%+v", l)
	}
	// xhttp defaults: path "/", host = sni, mode "auto"
	l, _ = ParseVless("vless://" + uuid + "@h:1?type=xhttp&security=reality&sni=s&pbk=P&sid=1")
	if l.Transport != (Transport{Kind: "xhttp", Path: "/", Host: "s", Mode: "auto"}) {
		t.Fatalf("%+v", l.Transport)
	}
}

func TestParseVlessRefusals(t *testing.T) {
	for _, bad := range []string{
		"vless://" + uuid + "@h:443?type=grpc&serviceName=x&security=reality&pbk=P",
		"vless://" + uuid + "@h:443?type=ws&path=/",
		"vless://" + uuid + "@h:443?type=httpupgrade",
	} {
		if _, err := ParseVless(bad); !errors.Is(err, ErrUnsupportedTransport) {
			t.Fatalf("%s: %v", bad, err)
		}
	}
	for _, bad := range []string{"vless://h:443", "vless://" + uuid + "@h", "vless://" + uuid + "@:443", "vless://" + uuid + "@h:x"} {
		if _, err := ParseVless(bad); err == nil || errors.Is(err, ErrUnsupportedTransport) {
			t.Fatalf("%s: want a parse error, got %v", bad, err)
		}
	}
	if _, err := ParseVless("hy2://x@h:1"); !errors.Is(err, ErrNotVless) {
		t.Fatal(err)
	}
}

func TestParseHy2(t *testing.T) {
	l, err := ParseHy2("hysteria2://3b8cdf3e-061c-4230-8e47-28a838ec91e0@45.153.230.44:38443?sni=s.example&obfs=salamander&obfs-password=470b5651&insecure=0&pinSHA256=abcd#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20Hy2")
	if err != nil {
		t.Fatal(err)
	}
	if l.Password != "3b8cdf3e-061c-4230-8e47-28a838ec91e0" || l.Port != 38443 || l.SNI != "s.example" || l.ObfsPassword != "470b5651" || l.PinSHA256 != "abcd" || l.Insecure || l.Label != "🇷🇺 EKB · Hy2" {
		t.Fatalf("%+v", l)
	}
	// Review Focus 3 shapes
	l, _ = ParseHy2("hy2://p@h:443?sni=s&insecure=1")
	if !l.Insecure || l.ObfsPassword != "" || l.PinSHA256 != "" {
		t.Fatalf("%+v", l)
	}
	if _, err := ParseHy2("hysteria2://h:443"); err == nil {
		t.Fatal("no password")
	}
	if _, err := ParseHy2("vless://x@h:1"); !errors.Is(err, ErrNotHy2) {
		t.Fatal(err)
	}
}
```
Extend `list_test.go`: `TestEntriesPlainListIsUnsupportedForNow` becomes `TestEntriesPlainListIsConnectable`: 17 entries, 8 `Hy2 != nil`, 9 `Vless != nil`, every `Connectable()`, no `Problem`; countries: all `""` except none (labels carry cities) — assert `GroupByCountry` returns 0 groups for that fixture. Add `TestEntriesProofkitUnified` over the new fixture: 4 entries, all connectable, `GroupByCountry` = one group `DE` with 4 entries in list order.

- [ ] **Step 2: Run** → FAIL (undefined). **Step 3: Implement** `share.go`:

```go
// cli/internal/links/share.go
package links

import (
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

var (
	ErrNotVless             = errors.New("not a vless:// line")
	ErrNotHy2               = errors.New("not a hysteria2:// line")
	ErrUnsupportedTransport = errors.New("transport not supported")
)

type Transport struct {
	Kind string // "tcp" | "xhttp"
	Path string
	Host string
	Mode string
}

type VlessLine struct {
	UUID, Host  string
	Port        int
	SNI         string
	PublicKey   string // Reality pbk; "" = plain TLS (xhttp via a CDN)
	ShortID     string
	Fingerprint string
	Flow        string // tcp only
	Transport   Transport
	Label       string
}

type Hy2Line struct {
	Password, Host string
	Port           int
	SNI            string
	ObfsPassword   string
	PinSHA256      string
	Insecure       bool
	Label          string
}

type parts struct {
	userinfo, host string
	port           int
	query          url.Values
	tag            string
}

// splitShare is the app's LinkParser.splitLink: scheme://userinfo@host:port?query#tag.
func splitShare(s, scheme string) (*parts, error) {
	body := strings.TrimPrefix(s, scheme)
	tag := ""
	if i := strings.IndexByte(body, '#'); i >= 0 {
		if t, err := url.PathUnescape(body[i+1:]); err == nil {
			tag = t
		} else {
			tag = body[i+1:]
		}
		body = body[:i]
	}
	query := url.Values{}
	if i := strings.IndexByte(body, '?'); i >= 0 {
		q, err := url.ParseQuery(body[i+1:])
		if err != nil {
			return nil, fmt.Errorf("query: %w", err)
		}
		query = q
		body = body[:i]
	}
	at := strings.LastIndexByte(body, '@')
	if at < 0 {
		return nil, errors.New("no credentials before @")
	}
	userinfo, hostPort := body[:at], body[at+1:]
	colon := strings.LastIndexByte(hostPort, ':')
	if colon <= 0 {
		return nil, errors.New("no host:port")
	}
	port, err := strconv.Atoi(hostPort[colon+1:])
	if err != nil || port < 1 || port > 65535 {
		return nil, errors.New("bad port")
	}
	host := strings.Trim(hostPort[:colon], "[]")
	if host == "" || userinfo == "" {
		return nil, errors.New("empty host or credentials")
	}
	return &parts{userinfo: userinfo, host: host, port: port, query: query, tag: strings.TrimSpace(tag)}, nil
}

func ParseVless(line string) (*VlessLine, error) {
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, "vless://") {
		return nil, ErrNotVless
	}
	p, err := splitShare(line, "vless://")
	if err != nil {
		return nil, fmt.Errorf("vless line: %w", err)
	}
	q := p.query
	typ := strings.ToLower(strings.TrimSpace(q.Get("type")))
	if typ == "" {
		typ = "tcp"
	}
	l := &VlessLine{UUID: p.userinfo, Host: p.host, Port: p.port, SNI: q.Get("sni"), PublicKey: q.Get("pbk"), ShortID: q.Get("sid"), Fingerprint: q.Get("fp"), Label: p.tag}
	if l.Fingerprint == "" {
		l.Fingerprint = "chrome"
	}
	if l.Label == "" {
		l.Label = p.host
	}
	switch typ {
	case "tcp", "raw":
		l.Transport = Transport{Kind: "tcp"}
		l.Flow = strings.TrimSpace(q.Get("flow"))
	case "xhttp":
		host := q.Get("host")
		if host == "" {
			host = l.SNI
		}
		path := q.Get("path")
		if path == "" {
			path = "/"
		}
		mode := q.Get("mode")
		if mode == "" {
			mode = "auto"
		}
		l.Transport = Transport{Kind: "xhttp", Path: path, Host: host, Mode: mode}
	default:
		return nil, fmt.Errorf("%w: vless over %s", ErrUnsupportedTransport, typ)
	}
	return l, nil
}

func ParseHy2(line string) (*Hy2Line, error) {
	line = strings.TrimSpace(line)
	scheme := ""
	for _, s := range []string{"hysteria2://", "hy2://"} {
		if strings.HasPrefix(line, s) {
			scheme = s
		}
	}
	if scheme == "" {
		return nil, ErrNotHy2
	}
	p, err := splitShare(line, scheme)
	if err != nil {
		return nil, fmt.Errorf("hysteria2 line: %w", err)
	}
	q := p.query
	ins := q.Get("insecure")
	l := &Hy2Line{Password: p.userinfo, Host: p.host, Port: p.port, SNI: q.Get("sni"), ObfsPassword: q.Get("obfs-password"),
		PinSHA256: q.Get("pinSHA256"), Insecure: ins == "1" || ins == "true", Label: p.tag}
	if l.Label == "" {
		l.Label = p.host
	}
	return l, nil
}
```
`list.go`: `Entry` gains `Vless *VlessLine; Hy2 *Hy2Line`; in `Entries`, the vless case calls `ParseVless` (on `ErrUnsupportedTransport` → `Problem = err.Error()`, other errors → `Problem` too), hy2 case calls `ParseHy2`; labels come from the parsed line; `func (e Entry) Connectable() bool { return e.Problem == "" && (e.Olcrtc != nil || e.Vless != nil || e.Hy2 != nil) }`.

- [ ] **Step 4: Run** → PASS; `go test ./internal/links/`. **Step 5: Commit** — `feat(cli): vless and hysteria2 link grammar, connectable entries`

---

### Task 3: sing-box front — native upstreams and the probe inbound

**Files:**
- Modify: `cli/internal/engine/singbox/config.go`, `config_test.go`, `front_test.go`
- Consumes: `links.VlessLine`, `links.Hy2Line`.

**Interfaces:**
- `FrontParams` changes: `UpstreamAddr/User/Pass` → `Upstream Upstream` where
```go
type Upstream struct {
	Socks *SocksUpstream   // an engine's loopback SOCKS (olcRTC, Xray)
	Vless *links.VlessLine // tcp Reality, native
	Hy2   *links.Hy2Line   // native
}
type SocksUpstream struct{ Addr, User, Pass string }
```
  and `ProbeListen string; ProbeUser, ProbePass string` (a loopback `mixed` inbound, tag `probe-in`, always present when `ProbeListen != ""`).
- `BuildConfig` emits the `tunnel` outbound as socks | vless | hysteria2 per the Global Constraints' shapes. A vless upstream with `Transport.Kind == "xhttp"` is refused (`errXhttpNotSingBox`): that is Xray's.

- [ ] **Step 1: Failing tests** — in `config_test.go`: `TestVlessRealityOutbound` (JSON contains `"type":"vless"`, `"packet_encoding":"xudp"`, `"flow":"xtls-rprx-vision"`, `"utls":{"enabled":true,"fingerprint":"chrome"}`, `"reality":{"enabled":true,"public_key":"P","short_id":"S"}`, and `Parse` + `New` accept it); `TestVlessXhttpRefused`; `TestHysteria2Outbound` (Review Focus 3: pin → `"insecure":true`, no obfs object without obfs-password; with obfs → `"obfs":{"password":"…","type":"salamander"}`; `Parse`+`New` accept); `TestProbeInbound` (config has a second inbound `mixed` on `127.0.0.1:<port>` with users). In `front_test.go`: the proxy e2e also fetches through the probe inbound with its creds → 204.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** — builder additions:

```go
func upstreamOutbound(u Upstream) (map[string]any, error) {
	switch {
	case u.Socks != nil:
		host, portStr, err := net.SplitHostPort(u.Socks.Addr)
		if err != nil {
			return nil, err
		}
		port, _ := strconv.Atoi(portStr)
		out := map[string]any{"type": "socks", "tag": "tunnel", "server": host, "server_port": port, "version": "5"}
		if u.Socks.User != "" {
			out["username"], out["password"] = u.Socks.User, u.Socks.Pass
		}
		return out, nil
	case u.Vless != nil:
		v := u.Vless
		if v.Transport.Kind != "tcp" {
			return nil, errXhttpNotSingBox
		}
		tls := map[string]any{"enabled": true, "server_name": v.SNI, "utls": map[string]any{"enabled": true, "fingerprint": v.Fingerprint}}
		if v.PublicKey != "" {
			tls["reality"] = map[string]any{"enabled": true, "public_key": v.PublicKey, "short_id": v.ShortID}
		}
		out := map[string]any{"type": "vless", "tag": "tunnel", "server": v.Host, "server_port": v.Port, "uuid": v.UUID, "packet_encoding": "xudp", "tls": tls}
		if v.Flow != "" {
			out["flow"] = v.Flow
		}
		return out, nil
	case u.Hy2 != nil:
		h := u.Hy2
		out := map[string]any{"type": "hysteria2", "tag": "tunnel", "server": h.Host, "server_port": h.Port, "password": h.Password,
			"tls": map[string]any{"enabled": true, "server_name": h.SNI, "insecure": h.Insecure || h.PinSHA256 != ""}}
		if h.ObfsPassword != "" {
			out["obfs"] = map[string]any{"type": "salamander", "password": h.ObfsPassword}
		}
		return out, nil
	}
	return nil, errNoUpstream
}
```
  The probe inbound: `{"type":"mixed","tag":"probe-in","listen":"127.0.0.1","listen_port":<port>,"users":[{"username":…,"password":…}]}` appended to `inbounds` when `ProbeListen != ""` (parse host:port). Note for sing-box 1.13: a `mixed` inbound with `users` requires both username and password non-empty.
- [ ] **Step 4: Run** → PASS (the typed parser is the oracle for field names; `New` builds the outbounds — a vless-reality outbound needs `with_utls`, which the tags provide). **Step 5: Commit** — `feat(cli): sing-box front — native vless-reality and hysteria2 upstreams, probe inbound`

---

### Task 4: Xray-core engine for XHTTP

**Files:**
- Create: `cli/internal/engine/xray/xray.go`, `cli/internal/engine/xray/config.go`, `cli/internal/engine/xray/xray_test.go`, `cli/internal/engine/xray/reality_e2e_test.go`

**Interfaces:**
- Produces: `type Params struct { Line links.VlessLine; SocksHost string; SocksPort int; SocksUser, SocksPass string; LogLevel string }`; `func BuildConfig(p Params) ([]byte, error)`; `type Session struct{…}` with `SocksAddr() string`, `Credentials() (string, string)`, `State() string` ("running" while the instance runs), `Stop(time.Duration) error`; `func Start(ctx context.Context, p Params) (*Session, error)` — `serial.LoadJSONConfig(bytes.NewReader(cfg))` → `core.New` → `instance.Start()`; a cancelled ctx before Start returns closes the instance. Refuses a non-xhttp line (`errNotXhttp`).

- [ ] **Step 1: Failing tests** — `TestBuildConfigReality` (JSON: `inbounds[0]` protocol socks, `settings.auth == "password"`, accounts user/pass, `udp: true`; `outbounds[0]` protocol vless, vnext address/port/users[0].id, `encryption none`; streamSettings network xhttp, security reality, realitySettings serverName/fingerprint/publicKey/shortId, xhttpSettings path/host/mode and `scMaxEachPostBytes == 200000`) and that `serial.LoadJSONConfig` + `core.New` accept it; `TestBuildConfigTLS` (Review Focus 2: no pbk → security tls, tlsSettings.allowInsecure false, serverName = SNI, xhttp host = link host); `TestStartStopLoopbackSocks` — `Start` with a line pointing at `127.0.0.1:1` (nothing listens), assert the SOCKS listener answers the handshake (dial the socks port, expect a byte), `State()=="running"`, `Stop` closes the listener.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**

```go
// cli/internal/engine/xray/config.go
package xray

import (
	"encoding/json"
	"errors"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const maxEachPostBytes = 200_000 // XrayConfig.XHTTP_MAX_EACH_POST_BYTES in the app

var errNotXhttp = errors.New("xray: only xhttp lines run here; Reality tcp and hysteria2 are sing-box's")

type Params struct {
	Line      links.VlessLine
	SocksHost string
	SocksPort int
	SocksUser string
	SocksPass string
	LogLevel  string
}

// BuildConfig is XrayConfig.buildXhttp without the routing and DNS the phone
// needs: a loopback SOCKS in, one vless+xhttp outbound out.
func BuildConfig(p Params) ([]byte, error) {
	l := p.Line
	if l.Transport.Kind != "xhttp" {
		return nil, errNotXhttp
	}
	level := p.LogLevel
	if level == "" {
		level = "warning"
	}
	stream := map[string]any{
		"network": "xhttp",
		"xhttpSettings": map[string]any{"path": l.Transport.Path, "host": l.Transport.Host, "mode": l.Transport.Mode, "scMaxEachPostBytes": maxEachPostBytes},
	}
	if l.PublicKey == "" {
		stream["security"] = "tls"
		stream["tlsSettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "allowInsecure": false}
	} else {
		stream["security"] = "reality"
		stream["realitySettings"] = map[string]any{"serverName": l.SNI, "fingerprint": l.Fingerprint, "publicKey": l.PublicKey, "shortId": l.ShortID}
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": level},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": p.SocksHost, "port": p.SocksPort, "protocol": "socks",
			"settings": map[string]any{"auth": "password", "accounts": []map[string]any{{"user": p.SocksUser, "pass": p.SocksPass}}, "udp": true},
		}},
		"outbounds": []map[string]any{{
			"tag": "out", "protocol": "vless",
			"settings":       map[string]any{"vnext": []map[string]any{{"address": l.Host, "port": l.Port, "users": []map[string]any{{"id": l.UUID, "encryption": "none"}}}}},
			"streamSettings": stream,
		}},
	}
	return json.Marshal(cfg)
}
```

```go
// cli/internal/engine/xray/xray.go
// Package xray runs Xray-core as a library for the one transport sing-box
// cannot speak, XHTTP, behind a loopback SOCKS the front dials.
package xray

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all" // registers every feature
)

type Session struct {
	inst *core.Instance
	addr string
	user string
	pass string
	mu   sync.Mutex
	done bool
}

func Start(ctx context.Context, p Params) (*Session, error) {
	cfg, err := BuildConfig(p)
	if err != nil {
		return nil, err
	}
	pb, err := serial.LoadJSONConfig(bytes.NewReader(cfg))
	if err != nil {
		return nil, fmt.Errorf("xray config: %w", err)
	}
	inst, err := core.New(pb)
	if err != nil {
		return nil, fmt.Errorf("xray: %w", err)
	}
	if err := inst.Start(); err != nil {
		return nil, fmt.Errorf("xray start: %w", err)
	}
	if ctx.Err() != nil {
		_ = inst.Close()
		return nil, ctx.Err()
	}
	return &Session{inst: inst, addr: net.JoinHostPort(p.SocksHost, strconv.Itoa(p.SocksPort)), user: p.SocksUser, pass: p.SocksPass}, nil
}

func (s *Session) SocksAddr() string             { return s.addr }
func (s *Session) Credentials() (string, string) { return s.user, s.pass }

func (s *Session) State() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.done {
		return "stopped"
	}
	return "running"
}

func (s *Session) Stop(time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.done {
		return nil
	}
	s.done = true
	return s.inst.Close()
}
```
- [ ] **Step 4: Reality end-to-end in-process (network needed for the Reality `dest`; skipped without it)** — `reality_e2e_test.go`: generate an x25519 pair (`crypto/ecdh` X25519, private 32 bytes base64url-raw for the server's `privateKey`, public for the client's `pbk`), start an Xray *server* instance with a vless inbound on `127.0.0.1:<port>` `streamSettings{network xhttp, security reality, realitySettings{dest "www.microsoft.com:443", serverNames ["www.microsoft.com"], privateKey, shortIds ["0123"]}, xhttpSettings{path "/e2e", mode "auto"}}` and a `freedom` outbound; then `Start` a client Session for the line `vless://<uuid>@127.0.0.1:<port>?type=xhttp&security=reality&pbk=<pub>&sid=0123&sni=www.microsoft.com&path=/e2e&host=www.microsoft.com&mode=packet-up`; run a local `httptest` server and GET it through the client's SOCKS (proxy.SOCKS5 with creds) → 200. Skip when `www.microsoft.com:443` is unreachable within 3 s (the Reality handshake mirrors that site). This is the proof that XHTTP+Reality works end to end in one process.
- [ ] **Step 5: Run** → PASS (`go test ./internal/engine/xray/`; the e2e prints `PASS` on DATA and CI, `SKIP` offline). **Step 6: Commit** — `feat(cli): Xray-core engine for XHTTP behind a loopback SOCKS, Reality end-to-end test`

---

### Task 5: Daemon — dispatch by kind, native pre-flight in tun mode, probe through the front

**Files:**
- Modify: `cli/internal/daemon/daemon.go` (`Deps.StartXray func(ctx, xray.Params) (Engine, error)`, `Deps.ProbeViaFront` no — see below), `connect.go`, `handlers.go` (list/add counts), `daemon_test.go`

**Interfaces:**
- `bringUp(ctx, e links.Entry, mode)`:
  - olcRTC: as today (engine → confirm via engine SOCKS → rules → front with `Upstream.Socks`).
  - xhttp: `StartXray` → confirm via its SOCKS → rules → front with `Upstream.Socks` (identical shape; `Engine` interface covers both).
  - vless tcp / hy2 (native): proxy mode → front with `Upstream.Vless|Hy2` + probe inbound → confirm through the probe inbound. Tun mode → **pre-flight**: a proxy-only front (`Mode: ModeProxy`, `ProxyListen: 127.0.0.1`, a free port, random creds, plus the probe inbound) → confirm → close it → rules → the tun front → confirm again through its probe inbound (fast; the server is known good).
- `supervise` probes through the front's probe inbound (`live.probeAddr/user/pass`) for every kind; `live.engine` may be nil (native): `State()` check only when an engine exists.
- `tearDown`: front close, engine stop if any, rules clear if tun.
- Candidates: `connectable(cands)` replaces `onlyOlcrtc`; order = list order (last-good first).

- [ ] **Step 1: Failing tests** — with the fakes: `StartFront` records the upstream kind (`front:proxy:socks`, `front:proxy:vless`, `front:tun:hy2`) and the probe inbound; a `probeVia` map in `world` decides which probe addr answers (engine SOCKS vs front probe) so the tests can fail the pre-flight. Tests:
  - `TestNativeLineProxyMode`: fixture `proofkit-unified.txt`; `connect 1 --proxy` (the Reality tcp line) → no engine started, `front:proxy:vless`, state up, probes go to the front's probe addr.
  - `TestNativeLineTunPreflight`: `connect 1 --tun` → events `front:proxy:vless front-close routes:sync front:tun:vless` (pre-flight before rules and tun); Review Focus 1: make the pre-flight probe fail → no `routes:sync`, no `front:tun`, state `failed`, retries.
  - `TestXhttpLineUsesXray`: `connect 2 --proxy` → `xray:` start recorded, `front:proxy:socks`.
  - `TestMixedCountryFailover` (Review Focus 4): `connect DE --proxy` with the unified fixture; the first (Reality) pre-flight fails, the second (hy2) fails, the third (XHTTP) works → order of events, right teardowns (`front-close` after each native failure, `xray-stop` never before success).
  - `TestListShowsKinds`: `list` on the partner plain fixture shows kinds `vless`/`hysteria2` with no note; `add` message says `17 entries (17 usable now)`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** — `live` gains `probeAddr, probeUser, probePass string`; `bringUp` per the interface above; `frontFor(mode, upstream, probe)` builds `FrontParams` incl. the probe inbound (`olcrtc.FreePort`, `RandomCredentials`); `preflight(ctx, upstream)` = proxy-only front + `confirmVia(probeAddr, user, pass)` + close; `confirm` generalised to `confirmVia(addr, user, pass)`; `supervise` uses `l.probeAddr`; `Deps.StartXray` wired in `run.go` (`xray.Start`).
- [ ] **Step 4: Run** — `go test -race ./internal/daemon/` → PASS; netns e2e unchanged (`GHOSTLANE_NETNS=1 …`). **Step 5: Commit** — `feat(cli): connect vless-reality, hysteria2 and xhttp lines; native pre-flight before the tun; probes through the front`

---

### Task 6: Command, docs, notes

**Files:**
- Modify: `cli/cmd/ghostlane/run.go` (StartXray), `commands.go` (help: kinds; `version` prints xray), `cli/README.md`, `docs/cli.md` ("Not in this version" shrinks to crypt1/kill switch/IPv6/split tunnelling; a "Which core carries what" paragraph; the pre-flight sentence for tun mode), `docs/release-notes/pending.md` (a paragraph: the CLI now connects VLESS Reality, Hysteria2 and XHTTP lines), `THIRD_PARTY_NOTICES.md` (done in Task 1), `cli/internal/links/list.go` notes text.

- [ ] **Step 1:** update help/docs/notes; `go test ./cmd/...` (help test names the kinds). **Step 2: Commit** — `docs(cli): share links in the CLI`

---

### Task 7: Live checks and the PR

- [ ] **Step 1: Live, proxy mode, from DATA** (never tun on DATA): with the partner's plain list (`…/sub/<id>/<token>` without `?c=`): `connect "<a Hy2 label>" --proxy` → `curl -x socks5h://127.0.0.1:1080 https://api.ipify.org` shows an EU exit; `connect "<an XHTTP label>" --proxy` → likewise (RU relay → EU; if the relay refuses from DE, note it and try the CDN entry). With the owner's own `/sub/<token>/unified` (token from the owner, or the account row on DATA's PG for the owner's email): `connect DE --proxy` → the Reality tcp line comes up, exit = the DE origin.
- [ ] **Step 2: Full verification** — vet, lint, `go test -race ./...`, netns tests, `make dist sign VERSION=0.0.2`, container install smoke (rpm), the Reality e2e test PASS (not SKIP) on DATA.
- [ ] **Step 3: PR** — branch `feat/linux-cli-share-links` from main after #78 merges; the PR body lists the live results and the deferred minors carried over from Stage A that this stage touched (per-subscription indices if fixed; else still deferred).
