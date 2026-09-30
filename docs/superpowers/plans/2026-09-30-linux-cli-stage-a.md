# Ghostlane CLI for Linux — Stage A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A static `ghostlane` binary for Linux that takes an olcRTC subscription, joins a country's rooms with carrier failover, and routes the box through a sing-box front in `tun` or `proxy` mode, packaged as deb/rpm/apk/Arch plus a signed tarball installer.

**Architecture:** One Go module `cli/` linking the olcRTC engine (`mobile.Runtime`), sing-box (`box.New`) and, later, Xray-core. A daemon (`ghostlane run`) owns the engine, the front and the policy rules and serves a unix control socket; every other subcommand is a client of that socket. Packaging is nfpm from one file; CI is a job in `pr-checks.yml` and a leg in `release.yml`.

**Tech Stack:** Go 1.26.3+, `github.com/openlibrecommunity/olcrtc/mobile` (replaced by `github.com/ghostlane-project/olcrtc`), `github.com/sagernet/sing-box` 1.13.14, `github.com/vishvananda/netlink` + `netns`, `gopkg.in/yaml.v3`, `golang.org/x/net/proxy`, nfpm 2.x, OpenSSL ed25519 for release signatures.

**Spec:** `docs/superpowers/specs/2026-09-30-linux-cli-design.md` (Stage A = its §3.5 first stage; Stage B is a separate plan).

## Global Constraints

- `cli/go.mod`: `go 1.26.3`; engine `github.com/openlibrecommunity/olcrtc` at `OLCRTC_VERSION` from `scripts/cores-pins.sh` (today `v0.0.0-20260929121845-f9edaa7f1ab5`) via `replace … => github.com/ghostlane-project/olcrtc <same version>`; `github.com/sagernet/sing-box v1.13.14` (= `SINGBOX_VERSION` in `release.yml`). A test fails when they disagree.
- Build: `CGO_ENABLED=0`, `-tags with_utls,with_quic`, `-trimpath -ldflags "-s -w -X main.version=…"`; GOOS linux; GOARCH amd64, arm64, arm (GOARM=7).
- Names: package `ghostlane-cli`, binary `ghostlane`, unit `ghostlane.service`, user/group `ghostlane`, socket `/run/ghostlane/ghostlane.sock`, state `/var/lib/ghostlane/` (config lives there too: `ProtectSystem=strict` keeps `/etc` read-only and the daemon owns its config).
- The daemon execs nothing, writes only under its state dir, and never logs a key, a token or a tokened URL.
- Tun facts: sing-box table 2022, rule range 9000–9010, our own-address rule pref 8990, tun name `ghostlane0`, `exclude_uid` = the daemon's uid, `strict_route: false`, IPv6 rejected.
- Licence of `cli/`: GPL-3.0-or-later (sing-box is linked in).
- Never run tun mode on the DATA box's host network; netns only. Proxy mode is safe anywhere.
- Go on DATA: `/usr/local/go/bin/go` (1.26.5), not on PATH — every command below assumes `export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH`.

## Review Focus

1. A list body that is plaintext but base64-shaped (a single `vless://…` line with no spaces): `DecodeBody` must keep the raw text when the decode has no `://`. Test in Task 3.
2. Labels that name no country (`☁️ CDN → 🇪🇺`, `VPN MOBL`) or repeat across a list: selection by label is exact, by country never guesses, and the error lists what exists. Test in Task 3.
3. The host's addresses change under a running tun (DHCP renew, a second IP added): the own-address rules must follow, adding and removing, never duplicating. Test in Task 8 (Sync diff) and Task 11 (live).
4. The daemon starts before the network (boot): the first fetch fails, the stored selection must keep retrying with backoff and connect when the network comes. Test in Task 9.
5. `connect` while already connecting or up, with another selector: the old engine and front are stopped before the new one starts, rules cleared for a proxy target. Test in Task 9.

---

### Task 1: Module skeleton, pinned dependencies, pins test

**Files:**
- Create: `cli/go.mod`, `cli/go.sum` (generated), `cli/cmd/ghostlane/main.go`, `cli/Makefile`, `cli/LICENSE`, `cli/internal/pins/pins.go`, `cli/internal/pins/pins_test.go`, `cli/.golangci.yml`

**Interfaces:**
- Produces: `main.version`, `main.enginePin`, `main.singboxPin` (ldflags strings); `pins.FromRepo(repoRoot string) (Pins, error)`; `pins.FromGoMod(goModPath string) (Pins, error)`; `type Pins struct { Engine, SingBox string }`.

- [ ] **Step 1: Create the module and pin the dependencies**

```bash
export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH
cd /root/olcbox-fork && mkdir -p cli/cmd/ghostlane cli/internal/pins && cd cli
go mod init github.com/ghostlane-project/ghostlane/cli
go mod edit -go=1.26.3
go mod edit -replace=github.com/openlibrecommunity/olcrtc=github.com/ghostlane-project/olcrtc@v0.0.0-20260929121845-f9edaa7f1ab5
go get github.com/openlibrecommunity/olcrtc@v0.0.0-20260929121845-f9edaa7f1ab5
go get github.com/sagernet/sing-box@v1.13.14 github.com/sagernet/sing@v0.8.11
go get github.com/vishvananda/netlink@v1.3.1 github.com/vishvananda/netns@v0.0.5 gopkg.in/yaml.v3@v3.0.1 golang.org/x/net@latest golang.org/x/sys@latest
```

- [ ] **Step 2: Write main.go that imports all three cores so the dependency graph is proven now**

```go
// cli/cmd/ghostlane/main.go
package main

import (
	"fmt"
	"os"

	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/include"
)

// Set by -ldflags at build time (see Makefile).
var (
	version    = "dev"
	enginePin  = "unknown"
	singboxPin = "unknown"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Printf("ghostlane %s\nengine %s\nsing-box %s\n", version, enginePin, singboxPin)
		return
	}
	fmt.Fprintln(os.Stderr, "usage: ghostlane version")
	os.Exit(2)
}
```

- [ ] **Step 3: Makefile**

```make
# cli/Makefile
GO      ?= go
TAGS    ?= with_utls,with_quic
VERSION ?= dev
ENGINE_PIN  := $(shell sed -n 's/^OLCRTC_VERSION="$${OLCRTC_VERSION:-\(.*\)}"$$/\1/p' ../scripts/cores-pins.sh)
SINGBOX_PIN := $(shell grep -m1 'SINGBOX_VERSION:' ../.github/workflows/release.yml | sed 's/.*"\(.*\)".*/\1/')
LDFLAGS := -s -w -X main.version=$(VERSION) -X main.enginePin=$(ENGINE_PIN) -X main.singboxPin=$(SINGBOX_PIN)
export CGO_ENABLED = 0

.PHONY: build test vet lint
build:
	$(GO) build -trimpath -tags $(TAGS) -ldflags '$(LDFLAGS)' -o dist/ghostlane ./cmd/ghostlane
test:
	$(GO) test -tags $(TAGS) ./...
vet:
	$(GO) vet -tags $(TAGS) ./...
```

- [ ] **Step 4: Build; fix any MVS conflict by bumping the offending module with `go get <module>@<version>` and note it in the commit message**

Run: `cd /root/olcbox-fork/cli && go mod tidy && make build && ./dist/ghostlane version`
Expected: three lines; `engine v0.0.0-20260929121845-f9edaa7f1ab5`, `sing-box 1.13.14`.

- [ ] **Step 5: Write the failing pins test**

```go
// cli/internal/pins/pins_test.go
package pins

import "testing"

func TestGoModMatchesRepoPins(t *testing.T) {
	repo, err := FromRepo("../../..")
	if err != nil {
		t.Fatal(err)
	}
	mod, err := FromGoMod("../../go.mod")
	if err != nil {
		t.Fatal(err)
	}
	if repo.Engine != mod.Engine {
		t.Fatalf("engine pin: scripts/cores-pins.sh says %q, cli/go.mod replaces with %q", repo.Engine, mod.Engine)
	}
	if "v"+repo.SingBox != mod.SingBox {
		t.Fatalf("sing-box pin: release.yml says %q, cli/go.mod requires %q", repo.SingBox, mod.SingBox)
	}
}
```

- [ ] **Step 6: Run it to verify it fails** — `go test ./internal/pins/` → FAIL: `FromRepo` undefined.

- [ ] **Step 7: Implement pins.go**

```go
// cli/internal/pins/pins.go
// Package pins reads the core versions the app pins and the ones cli/go.mod uses,
// so the two cannot drift apart unnoticed.
package pins

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type Pins struct {
	Engine  string // pseudo-version of the olcrtc engine
	SingBox string // e.g. "1.13.14" from the repo, "v1.13.14" from go.mod
}

var (
	enginePinRe  = regexp.MustCompile(`(?m)^OLCRTC_VERSION="\$\{OLCRTC_VERSION:-([^}]+)\}"`)
	singboxRepoRe = regexp.MustCompile(`SINGBOX_VERSION:\s*"([^"]+)"`)
	replaceRe    = regexp.MustCompile(`(?m)^replace github\.com/openlibrecommunity/olcrtc => github\.com/ghostlane-project/olcrtc (\S+)`)
	singboxModRe = regexp.MustCompile(`(?m)^\s*github\.com/sagernet/sing-box (\S+)`)
)

func FromRepo(root string) (Pins, error) {
	pinsSh, err := os.ReadFile(filepath.Join(root, "scripts", "cores-pins.sh"))
	if err != nil {
		return Pins{}, err
	}
	release, err := os.ReadFile(filepath.Join(root, ".github", "workflows", "release.yml"))
	if err != nil {
		return Pins{}, err
	}
	m := enginePinRe.FindSubmatch(pinsSh)
	s := singboxRepoRe.FindSubmatch(release)
	if m == nil || s == nil {
		return Pins{}, errors.New("pins: OLCRTC_VERSION or SINGBOX_VERSION not found in the repo")
	}
	return Pins{Engine: string(m[1]), SingBox: string(s[1])}, nil
}

func FromGoMod(path string) (Pins, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Pins{}, err
	}
	r := replaceRe.FindSubmatch(b)
	s := singboxModRe.FindSubmatch(b)
	if r == nil || s == nil {
		return Pins{}, errors.New("pins: replace of olcrtc or sing-box require not found in go.mod")
	}
	return Pins{Engine: strings.TrimSpace(string(r[1])), SingBox: strings.TrimSpace(string(s[1]))}, nil
}
```

- [ ] **Step 8: Run tests** — `go test ./internal/pins/` → PASS.

- [ ] **Step 9: Lint config and licence**

`cli/.golangci.yml`:
```yaml
version: "2"
run:
  timeout: 5m
  tests: true
  build-tags: [with_utls, with_quic]
linters:
  enable: [errcheck, govet, staticcheck, unused, errorlint, gocritic, misspell, unconvert, bodyclose, revive]
  settings:
    revive:
      rules:
        - name: exported
          disabled: true
```
`cli/LICENSE`: copy the GPL-3.0 text (`cp /root/go/pkg/mod/github.com/sagernet/sing-box@v1.13.14/LICENSE cli/LICENSE` gives GPLv3 with sing-box's copyright line; replace the first line with `Copyright (C) 2026 Ghostlane contributors` and keep the licence text). Add to the top of `cli/cmd/ghostlane/main.go` a comment: `// ghostlane is GPL-3.0-or-later: it links sing-box. See cli/LICENSE.`

- [ ] **Step 10: Commit**

```bash
cd /root/olcbox-fork && git add cli && git commit -m "feat(cli): module skeleton — engine, sing-box pinned to the app's versions"
```

---

### Task 2: olcRTC line grammar, normalisers, country

**Files:**
- Create: `cli/internal/links/olcrtc.go`, `cli/internal/links/olcrtc_test.go`, `cli/internal/links/country.go`, `cli/internal/links/country_test.go`

**Interfaces:**
- Produces: `type OlcrtcLine struct { Provider, Transport, Room, Key, Label string; VP8FPS, VP8Batch int }`; `func ParseOlcrtc(line string) (*OlcrtcLine, error)`; `var ErrNotOlcrtc, ErrCrypt1 error`; `func NormalizeProvider(s string) (string, bool)`; `func NormalizeTransport(s string) (string, bool)`; `func CountryOf(label string) string`.

- [ ] **Step 1: Write the failing tests**

```go
// cli/internal/links/olcrtc_test.go
package links

import (
	"errors"
	"strings"
	"testing"
)

const key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestParseOlcrtcPartnerShapes(t *testing.T) {
	cases := []struct {
		line                      string
		provider, transport, room string
		label                     string
	}{
		{"olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/3305071026#" + key + "$🇨🇦 CA · VP8",
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/3305071026", "🇨🇦 CA · VP8"},
		{"olcrtc://wbstream?vp8channel@room_z9dszi2t#" + key + "$🇨🇦 CA · VP8 · WB",
			"wbstream", "vp8channel", "room_z9dszi2t", "🇨🇦 CA · VP8 · WB"},
		{"olcrtc://salutejazz?datachannel@53a942:cb3gjji2#" + key + "$🇨🇦 CA · SJ",
			"salutejazz", "datachannel", "53a942:cb3gjji2", "🇨🇦 CA · SJ"},
		{"olcrtc://vkcalls?vp8channel@https://vk.ru/call/join/v60W-XIZsCXbDpgg#" + key + "$🇨🇦 CA · VP8",
			"vkcalls", "vp8channel", "https://vk.ru/call/join/v60W-XIZsCXbDpgg", "🇨🇦 CA · VP8"},
		// ProofKit's own list names lines "DE · olcRTC"
		{"olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/99#" + key + "$DE · olcRTC",
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/99", "DE · olcRTC"},
		// no label: the room is the label
		{"olcrtc://yandex?vp8@https://telemost.yandex.ru/j/1#" + key,
			"telemost", "vp8channel", "https://telemost.yandex.ru/j/1", "https://telemost.yandex.ru/j/1"},
		// a %client segment ends the key; the label still follows the $
		{"olcrtc://telemost?vp8channel@r#" + key + "%c=1$IT · olcRTC",
			"telemost", "vp8channel", "r", "IT · olcRTC"},
	}
	for _, c := range cases {
		got, err := ParseOlcrtc(c.line)
		if err != nil {
			t.Fatalf("%s: %v", c.line, err)
		}
		if got.Provider != c.provider || got.Transport != c.transport || got.Room != c.room || got.Label != c.label || got.Key != key {
			t.Fatalf("%s:\n got %+v", c.line, got)
		}
	}
}

func TestParseOlcrtcVP8Options(t *testing.T) {
	got, err := ParseOlcrtc("olcrtc://telemost?vp8channel&fps=25&batch=6@room#" + key + "$X")
	if err != nil {
		t.Fatal(err)
	}
	if got.VP8FPS != 25 || got.VP8Batch != 6 {
		t.Fatalf("got fps=%d batch=%d", got.VP8FPS, got.VP8Batch)
	}
	got, _ = ParseOlcrtc("olcrtc://telemost?vp8channel&vp8-fps=30&vp8-batch=8@room#" + key)
	if got.VP8FPS != 30 || got.VP8Batch != 8 {
		t.Fatalf("vp8- prefixed options: got fps=%d batch=%d", got.VP8FPS, got.VP8Batch)
	}
}

func TestParseOlcrtcRefusals(t *testing.T) {
	if _, err := ParseOlcrtc("vless://x@y:1"); !errors.Is(err, ErrNotOlcrtc) {
		t.Fatalf("want ErrNotOlcrtc, got %v", err)
	}
	if _, err := ParseOlcrtc("olcrtc://crypt1/abcdef"); !errors.Is(err, ErrCrypt1) {
		t.Fatalf("want ErrCrypt1, got %v", err)
	}
	bad := []string{
		"olcrtc://telemost@room#" + key,            // no transport
		"olcrtc://telemost?vp8channel#" + key,      // no room
		"olcrtc://telemost?vp8channel@room",         // no key
		"olcrtc://telemost?vp8channel@room#abc",     // short key
		"olcrtc://zoom?vp8channel@room#" + key,      // unknown carrier
		"olcrtc://telemost?h264@room#" + key,        // unknown transport
	}
	for _, b := range bad {
		if _, err := ParseOlcrtc(b); err == nil || errors.Is(err, ErrNotOlcrtc) {
			t.Fatalf("%s: want a parse error, got %v", b, err)
		}
	}
	_, err := ParseOlcrtc("olcrtc://zoom?vp8channel@room#" + key)
	if !strings.Contains(err.Error(), "zoom") {
		t.Fatalf("the error names the carrier: %v", err)
	}
}

func TestNormalizers(t *testing.T) {
	for in, want := range map[string]string{"Telemost": "telemost", "yandex_telemost": "telemost",
		"wb-stream": "wbstream", "wildberries": "wbstream", "jitsi-meet": "jitsi", "sberjazz": "salutejazz",
		"jazz": "salutejazz", "vk": "vkcalls", "vk_calls": "vkcalls"} {
		got, ok := NormalizeProvider(in)
		if !ok || got != want {
			t.Fatalf("NormalizeProvider(%q) = %q,%v", in, got, ok)
		}
	}
	if _, ok := NormalizeProvider("zoom"); ok {
		t.Fatal("zoom is not a carrier")
	}
	for in, want := range map[string]string{"vp8": "vp8channel", "video-vp8": "vp8channel", "dc": "datachannel",
		"data": "datachannel", "sei": "seichannel", "video": "videochannel", "VideoChannel": "videochannel"} {
		got, ok := NormalizeTransport(in)
		if !ok || got != want {
			t.Fatalf("NormalizeTransport(%q) = %q,%v", in, got, ok)
		}
	}
}
```

```go
// cli/internal/links/country_test.go
package links

import "testing"

func TestCountryOf(t *testing.T) {
	for label, want := range map[string]string{
		"🇩🇪 DE · VP8":        "DE",
		"🇩🇪 DE · VP8 · WB":   "DE",
		"DE · olcRTC":         "DE",
		"US via RU | 0.13TON/GB": "US",
		"☁️ CDN → 🇪🇺":         "",
		"VPN MOBL":            "",
		"de · lower":          "",
		"  🇯🇵  JP":            "JP",
		"":                    "",
	} {
		if got := CountryOf(label); got != want {
			t.Fatalf("CountryOf(%q) = %q, want %q", label, got, want)
		}
	}
}
```

- [ ] **Step 2: Run to verify they fail** — `go test ./internal/links/` → FAIL: undefined `ParseOlcrtc`.

- [ ] **Step 3: Implement**

```go
// cli/internal/links/olcrtc.go
// Package links parses the lines and lists Ghostlane accepts.
package links

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

const olcrtcPrefix = "olcrtc://"

var (
	ErrNotOlcrtc = errors.New("not an olcrtc:// line")
	ErrCrypt1    = errors.New("olcrtc://crypt1 lists need a build secret and are not supported by this version")
)

// OlcrtcLine is one room: olcrtc://<provider>?<transport>[&k=v…]@<room>#<key>[%<client>][$<label>]
// (the grammar of LocationsDatasource.parseOlcRtcUri in the app).
type OlcrtcLine struct {
	Provider  string // telemost | wbstream | jitsi | salutejazz | vkcalls
	Transport string // datachannel | vp8channel | seichannel | videochannel
	Room      string
	Key       string // 64 hex characters
	Label     string // after the $, else the room
	VP8FPS    int    // 0 = engine default
	VP8Batch  int    // 0 = engine default
}

func ParseOlcrtc(line string) (*OlcrtcLine, error) {
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, olcrtcPrefix) {
		return nil, ErrNotOlcrtc
	}
	payload := line[len(olcrtcPrefix):]
	if strings.HasPrefix(payload, "crypt1/") {
		return nil, ErrCrypt1
	}
	t := strings.IndexByte(payload, '?')
	r := indexFrom(payload, '@', t+1)
	k := indexFrom(payload, '#', r+1)
	if t <= 0 || r <= t || k <= r {
		return nil, errors.New("olcrtc line: expected provider?transport@room#key")
	}
	keyEnd := len(payload)
	labelAt := indexFrom(payload, '$', k+1)
	if i := indexFrom(payload, '%', k+1); i >= 0 && i < keyEnd {
		keyEnd = i
	}
	if labelAt >= 0 && labelAt < keyEnd {
		keyEnd = labelAt
	}
	provider, ok := NormalizeProvider(payload[:t])
	if !ok {
		return nil, fmt.Errorf("olcrtc line: unknown carrier %q", payload[:t])
	}
	tokens := strings.Split(payload[t+1:r], "&")
	transport, ok := NormalizeTransport(tokens[0])
	if !ok {
		return nil, fmt.Errorf("olcrtc line: unknown transport %q", tokens[0])
	}
	out := &OlcrtcLine{Provider: provider, Transport: transport}
	for _, kv := range tokens[1:] {
		name, value, _ := strings.Cut(kv, "=")
		n, err := strconv.Atoi(strings.TrimSpace(value))
		if err != nil || n <= 0 {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(name)) {
		case "fps", "vp8-fps":
			out.VP8FPS = n
		case "batch", "vp8-batch":
			out.VP8Batch = n
		}
	}
	out.Room = strings.TrimSpace(payload[r+1 : k])
	out.Key = strings.TrimSpace(payload[k+1 : keyEnd])
	if out.Room == "" {
		return nil, errors.New("olcrtc line: empty room")
	}
	if !isHex64(out.Key) {
		return nil, errors.New("olcrtc line: key must be 64 hex characters")
	}
	if labelAt >= 0 {
		out.Label = strings.TrimSpace(payload[labelAt+1:])
	}
	if out.Label == "" {
		out.Label = out.Room
	}
	return out, nil
}

func indexFrom(s string, c byte, from int) int {
	if from < 0 || from > len(s) {
		return -1
	}
	i := strings.IndexByte(s[from:], c)
	if i < 0 {
		return -1
	}
	return from + i
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// NormalizeProvider mirrors LocationConfig.normalizeProvider in the app, minus its
// silent fallback: an unknown carrier is refused, not guessed.
func NormalizeProvider(s string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "telemost", "yandex", "yandex_telemost":
		return "telemost", true
	case "wbstream", "wb-stream", "wb_stream", "wildberries":
		return "wbstream", true
	case "jitsi", "jitsi-meet", "jitsi_meet", "meet":
		return "jitsi", true
	case "salutejazz", "jazz", "sberjazz", "sber_jazz":
		return "salutejazz", true
	case "vkcalls", "vk", "vkcall", "vk_calls", "vk-calls":
		return "vkcalls", true
	}
	return "", false
}

// NormalizeTransport mirrors LocationConfig.transportOrNull in the app.
func NormalizeTransport(s string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "datachannel", "data", "dc":
		return "datachannel", true
	case "vp8channel", "vp8", "video_vp8", "video-vp8":
		return "vp8channel", true
	case "seichannel", "sei":
		return "seichannel", true
	case "videochannel", "video":
		return "videochannel", true
	}
	return "", false
}
```

```go
// cli/internal/links/country.go
package links

import "strings"

// CountryOf is the app's TransportGroup.countryOf with one addition: a leading
// flag (regional-indicator pair) is stripped first, because a partner's labels
// start with one ("🇩🇪 DE · VP8") and the app's rule alone reads the flag as
// the first token and finds no country.
func CountryOf(label string) string {
	s := strings.TrimSpace(label)
	for {
		r, size := firstRune(s)
		if r >= 0x1F1E6 && r <= 0x1F1FF { // regional indicator symbols
			s = strings.TrimSpace(s[size:])
			continue
		}
		break
	}
	first := s
	if i := strings.IndexByte(first, ' '); i >= 0 {
		first = first[:i]
	}
	if i := strings.IndexByte(first, '|'); i >= 0 {
		first = first[:i]
	}
	first = strings.TrimSpace(first)
	if len(first) != 2 || first[0] < 'A' || first[0] > 'Z' || first[1] < 'A' || first[1] > 'Z' {
		return ""
	}
	return first
}

func firstRune(s string) (rune, int) {
	for _, r := range s {
		return r, len(string(r))
	}
	return 0, 0
}
```

- [ ] **Step 4: Run tests** — `go test ./internal/links/` → PASS. (If `firstRune` returns a wrong width for the variation selector in `☁️`, note that `☁` is U+2601, not a regional indicator, so the loop stops at once — the test expects `""` because `☁️` is not two capitals.)

- [ ] **Step 5: Commit** — `git add cli/internal/links && git commit -m "feat(cli): olcrtc line grammar, carrier and transport names, country of a label"`

---

### Task 3: Lists — body, headers, entries, groups, selectors, fetch

**Files:**
- Create: `cli/internal/links/list.go`, `cli/internal/links/list_test.go`, `cli/internal/links/fetch.go`, `cli/internal/links/fetch_test.go`, `cli/internal/links/testdata/partner-olcbox.txt` (the decoded partner body with every key replaced by `0123…cdef` and rooms kept), `cli/internal/links/testdata/partner-plain.txt` (the plain partner body, uuids/passwords replaced)

**Interfaces:**
- Produces: `type Kind int` (`KindOlcrtc`, `KindVless`, `KindHysteria2`, `KindUnsupported`); `type Entry struct { ID, Label, Country, Raw, Problem string; Kind Kind; Olcrtc *OlcrtcLine }`; `func DecodeBody(body []byte) []string`; `type Headers struct { Title string; UpdateIntervalHours int; UserInfo *UserInfo; SupportURL, WebPageURL, Announce string }`; `type UserInfo struct { Upload, Download, Total int64; Expire int64 }`; `func ParseHeaders(h http.Header) Headers`; `func Entries(subURL string, lines []string) []Entry`; `type Group struct { Country string; Entries []Entry }`; `func GroupByCountry(entries []Entry) []Group`; `func Select(entries []Entry, selector string) ([]Entry, error)`; `func Fetch(ctx context.Context, client *http.Client, url, userAgent string) ([]byte, Headers, error)`; `const UserAgentPrefix = "Ghostlane-cli/"`.

- [ ] **Step 1: Write the fixtures** — from the scratchpad decode of the partner list, `sed -E 's/#[0-9a-f]{64}/#0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef/'` into `testdata/partner-olcbox.txt` (68 lines); for `partner-plain.txt` replace the uuid before `@` with `00000000-0000-4000-8000-000000000000`, `obfs-password=` and `pinSHA256=` values with `x` repeated, `pbk=`/`sid=` values with `x`. Both files are plaintext (decoded).

- [ ] **Step 2: Write the failing tests**

```go
// cli/internal/links/list_test.go
package links

import (
	"encoding/base64"
	"net/http"
	"os"
	"strings"
	"testing"
)

func fixtureLines(t *testing.T, name string) []string {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return DecodeBody(b)
}

func TestDecodeBodyBase64AndPlain(t *testing.T) {
	plain := "olcrtc://telemost?vp8channel@r#" + key + "$DE · olcRTC\nvless://u@h:443?type=tcp#x\n"
	if got := DecodeBody([]byte(plain)); len(got) != 2 {
		t.Fatalf("plaintext: %d lines", len(got))
	}
	enc := base64.StdEncoding.EncodeToString([]byte(plain))
	wrapped := enc[:40] + "\n" + enc[40:] + "\n"
	if got := DecodeBody([]byte(wrapped)); len(got) != 2 || !strings.HasPrefix(got[1], "vless://") {
		t.Fatalf("base64 with newlines: %v", got)
	}
	unpadded := strings.TrimRight(enc, "=")
	if got := DecodeBody([]byte(unpadded)); len(got) != 2 {
		t.Fatalf("base64 without padding: %v", got)
	}
	// Review Focus 1: base64-shaped plaintext stays plaintext.
	one := "vless://abcdefabcdefabcdef@hostname:443"
	if got := DecodeBody([]byte(one)); len(got) != 1 || got[0] != one {
		t.Fatalf("a single unpadded line must not be base64-decoded: %v", got)
	}
}

func TestEntriesPartnerOlcbox(t *testing.T) {
	entries := Entries("https://sub.example/sub/a/b?c=olcbox", fixtureLines(t, "partner-olcbox.txt"))
	if len(entries) != 68 {
		t.Fatalf("%d entries", len(entries))
	}
	for _, e := range entries {
		if e.Kind != KindOlcrtc || e.Olcrtc == nil || e.Country == "" || e.ID == "" {
			t.Fatalf("entry %+v", e)
		}
	}
	groups := GroupByCountry(entries)
	if len(groups) != 17 || groups[0].Country != "CA" || len(groups[0].Entries) != 4 {
		t.Fatalf("groups: %d, first %+v", len(groups), groups[0])
	}
	want := []string{"telemost", "wbstream", "salutejazz", "vkcalls"}
	for i, e := range groups[0].Entries {
		if e.Olcrtc.Provider != want[i] {
			t.Fatalf("carrier order in a country must be the list's: %v", groups[0].Entries)
		}
	}
	ids := map[string]bool{}
	for _, e := range entries {
		if ids[e.ID] {
			t.Fatalf("duplicate id %s (labels repeat: %q)", e.ID, e.Label)
		}
		ids[e.ID] = true
	}
}

func TestEntriesPlainListIsUnsupportedForNow(t *testing.T) {
	entries := Entries("https://sub.example/sub/a/b", fixtureLines(t, "partner-plain.txt"))
	if len(entries) != 17 {
		t.Fatalf("%d entries", len(entries))
	}
	kinds := map[Kind]int{}
	for _, e := range entries {
		kinds[e.Kind]++
	}
	if kinds[KindHysteria2] != 8 || kinds[KindVless] != 9 {
		t.Fatalf("kinds %v", kinds)
	}
}

func TestSelect(t *testing.T) {
	entries := Entries("u", fixtureLines(t, "partner-olcbox.txt"))
	got, err := Select(entries, "de")
	if err != nil || len(got) != 4 || got[0].Country != "DE" {
		t.Fatalf("country: %v %v", got, err)
	}
	got, err = Select(entries, "🇩🇪 DE · SJ")
	if err != nil || len(got) != 1 || got[0].Olcrtc.Provider != "salutejazz" {
		t.Fatalf("label: %v %v", got, err)
	}
	got, err = Select(entries, "6")
	if err != nil || len(got) != 1 || got[0] != entries[5] {
		t.Fatalf("index: %v %v", got, err)
	}
	// Review Focus 2: no guessing.
	if _, err = Select(entries, "D"); err == nil || !strings.Contains(err.Error(), "CA") {
		t.Fatalf("an unknown selector fails and lists the countries: %v", err)
	}
	if _, err = Select(entries, "99"); err == nil {
		t.Fatal("index out of range")
	}
	dup := Entries("u", []string{
		"olcrtc://telemost?vp8channel@a#" + key + "$VPN MOBL",
		"olcrtc://telemost?vp8channel@b#" + key + "$VPN MOBL",
	})
	if dup[0].ID == dup[1].ID {
		t.Fatal("same label twice must still get distinct ids")
	}
	if _, err = Select(dup, "VPN MOBL"); err == nil || !strings.Contains(err.Error(), "index") {
		t.Fatalf("an ambiguous label asks for the index: %v", err)
	}
}

func TestParseHeaders(t *testing.T) {
	h := http.Header{}
	h.Set("Profile-Title", "base64:QFhyYXl2bGVzc3BheW1lbnRib3Q=")
	h.Set("Profile-Update-Interval", "1")
	h.Set("Subscription-Userinfo", "upload=0; download=12; total=107373108658176; expire=1854106383")
	h.Set("Support-Url", "https://t.me/x")
	h.Set("Announce", "base64:aGVsbG8=")
	got := ParseHeaders(h)
	if got.Title != "@Xrayvlesspaymentbot" || got.UpdateIntervalHours != 1 || got.SupportURL != "https://t.me/x" || got.Announce != "hello" {
		t.Fatalf("%+v", got)
	}
	if got.UserInfo == nil || got.UserInfo.Download != 12 || got.UserInfo.Total != 107373108658176 || got.UserInfo.Expire != 1854106383 {
		t.Fatalf("%+v", got.UserInfo)
	}
	if def := ParseHeaders(http.Header{}); def.UpdateIntervalHours != 24 || def.UserInfo != nil {
		t.Fatalf("defaults: %+v", def)
	}
}
```

```go
// cli/internal/links/fetch_test.go
package links

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestFetch(t *testing.T) {
	body := "olcrtc://telemost?vp8channel@r#" + key + "$DE · olcRTC\n"
	var ua string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ua = r.Header.Get("User-Agent")
		if r.URL.RawQuery != "c=olcbox" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Profile-Update-Interval", "2")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte(base64.StdEncoding.EncodeToString([]byte(body))))
	}))
	defer srv.Close()
	got, headers, err := Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b?c=olcbox", UserAgentPrefix+"0.0.1 (linux)")
	if err != nil {
		t.Fatal(err)
	}
	if lines := DecodeBody(got); len(lines) != 1 || headers.UpdateIntervalHours != 2 || ua != "Ghostlane-cli/0.0.1 (linux)" {
		t.Fatalf("lines=%v headers=%+v ua=%q", lines, headers, ua)
	}
	if _, _, err = Fetch(context.Background(), srv.Client(), srv.URL+"/sub/a/b", "x"); err == nil {
		t.Fatal("a 404 is an error")
	}
}
```

- [ ] **Step 3: Run to verify they fail** — `go test ./internal/links/` → FAIL: undefined `DecodeBody` etc.

- [ ] **Step 4: Implement list.go and fetch.go**

```go
// cli/internal/links/list.go
package links

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"
)

type Kind int

const (
	KindUnsupported Kind = iota
	KindOlcrtc
	KindVless
	KindHysteria2
)

func (k Kind) String() string {
	switch k {
	case KindOlcrtc:
		return "olcrtc"
	case KindVless:
		return "vless"
	case KindHysteria2:
		return "hysteria2"
	}
	return "unsupported"
}

type Entry struct {
	ID      string
	Label   string
	Country string // "" when the label names none
	Kind    Kind
	Raw     string
	Olcrtc  *OlcrtcLine
	Problem string // why the line cannot be connected, for `list`
}

// DecodeBody turns a subscription body into lines: base64 (whole body, wrapped or
// unpadded) when the decode yields text with a scheme in it, else the text itself.
func DecodeBody(body []byte) []string {
	text := string(body)
	compact := strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == ' ' || r == '\t' {
			return -1
		}
		return r
	}, text)
	if decoded, ok := tryBase64(compact); ok && strings.Contains(decoded, "://") {
		text = decoded
	}
	var lines []string
	for _, l := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		l = strings.TrimSpace(l)
		if l != "" {
			lines = append(lines, l)
		}
	}
	return lines
}

func tryBase64(s string) (string, bool) {
	if s == "" {
		return "", false
	}
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(s); err == nil && utf8.Valid(b) {
			return string(b), true
		}
	}
	return "", false
}

type UserInfo struct{ Upload, Download, Total, Expire int64 }

type Headers struct {
	Title               string
	UpdateIntervalHours int
	UserInfo            *UserInfo
	SupportURL          string
	WebPageURL          string
	Announce            string
}

func ParseHeaders(h http.Header) Headers {
	out := Headers{UpdateIntervalHours: 24}
	out.Title = maybeBase64(h.Get("Profile-Title"))
	out.Announce = maybeBase64(h.Get("Announce"))
	out.SupportURL = strings.TrimSpace(h.Get("Support-Url"))
	out.WebPageURL = strings.TrimSpace(h.Get("Profile-Web-Page-Url"))
	if n, err := strconv.Atoi(strings.TrimSpace(h.Get("Profile-Update-Interval"))); err == nil && n > 0 {
		out.UpdateIntervalHours = n
	}
	if ui := strings.TrimSpace(h.Get("Subscription-Userinfo")); ui != "" {
		info := &UserInfo{}
		for _, part := range strings.Split(ui, ";") {
			k, v, _ := strings.Cut(strings.TrimSpace(part), "=")
			n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
			if err != nil {
				continue
			}
			switch strings.ToLower(strings.TrimSpace(k)) {
			case "upload":
				info.Upload = n
			case "download":
				info.Download = n
			case "total":
				info.Total = n
			case "expire":
				info.Expire = n
			}
		}
		out.UserInfo = info
	}
	return out
}

func maybeBase64(v string) string {
	v = strings.TrimSpace(v)
	if rest, ok := strings.CutPrefix(v, "base64:"); ok {
		if d, ok := tryBase64(rest); ok {
			return strings.TrimSpace(d)
		}
	}
	return v
}

// Entries parses every line; ids are stable across refreshes by (list, label),
// a repeated label counted up so two rooms named alike stay two entries.
func Entries(subURL string, lines []string) []Entry {
	seen := map[string]int{}
	out := make([]Entry, 0, len(lines))
	for _, raw := range lines {
		e := Entry{Raw: raw}
		switch {
		case strings.HasPrefix(raw, olcrtcPrefix):
			l, err := ParseOlcrtc(raw)
			if err != nil {
				e.Kind, e.Problem, e.Label = KindUnsupported, err.Error(), raw
			} else {
				e.Kind, e.Olcrtc, e.Label = KindOlcrtc, l, l.Label
			}
		case strings.HasPrefix(raw, "vless://"):
			e.Kind, e.Label, e.Problem = KindVless, fragmentLabel(raw), "VLESS lines come in the next version"
		case strings.HasPrefix(raw, "hysteria2://"), strings.HasPrefix(raw, "hy2://"):
			e.Kind, e.Label, e.Problem = KindHysteria2, fragmentLabel(raw), "Hysteria2 lines come in the next version"
		default:
			scheme, _, _ := strings.Cut(raw, "://")
			e.Kind, e.Label, e.Problem = KindUnsupported, raw, fmt.Sprintf("%s:// is not supported", scheme)
		}
		e.Country = CountryOf(e.Label)
		seen[e.Label]++
		e.ID = entryID(subURL, e.Label, seen[e.Label])
		out = append(out, e)
	}
	return out
}

func fragmentLabel(raw string) string {
	if i := strings.LastIndexByte(raw, '#'); i >= 0 {
		if l := strings.TrimSpace(unescape(raw[i+1:])); l != "" {
			return l
		}
	}
	return raw
}

func unescape(s string) string {
	if u, err := urlPathUnescape(s); err == nil {
		return u
	}
	return s
}

func entryID(subURL, label string, ordinal int) string {
	h := sha256.Sum256([]byte(subURL + "\x00" + label + "\x00" + strconv.Itoa(ordinal)))
	return hex.EncodeToString(h[:6])
}

type Group struct {
	Country string
	Entries []Entry
}

// GroupByCountry keeps first-appearance order of countries and list order inside.
// Entries without a country are not grouped: they are reachable by label or index.
func GroupByCountry(entries []Entry) []Group {
	var out []Group
	index := map[string]int{}
	for _, e := range entries {
		if e.Country == "" {
			continue
		}
		i, ok := index[e.Country]
		if !ok {
			index[e.Country] = len(out)
			out = append(out, Group{Country: e.Country})
			i = len(out) - 1
		}
		out[i].Entries = append(out[i].Entries, e)
	}
	return out
}

var ErrNoMatch = errors.New("no such location")

// Select resolves a selector: a country code (its group), an exact label (one
// entry) or a 1-based index from `list`. Nothing is guessed.
func Select(entries []Entry, selector string) ([]Entry, error) {
	sel := strings.TrimSpace(selector)
	if sel == "" {
		return nil, fmt.Errorf("%w: empty selector", ErrNoMatch)
	}
	if n, err := strconv.Atoi(sel); err == nil {
		if n < 1 || n > len(entries) {
			return nil, fmt.Errorf("%w: index %d, the list has %d entries", ErrNoMatch, n, len(entries))
		}
		return []Entry{entries[n-1]}, nil
	}
	var byLabel []Entry
	for _, e := range entries {
		if e.Label == sel {
			byLabel = append(byLabel, e)
		}
	}
	if len(byLabel) == 1 {
		return byLabel, nil
	}
	if len(byLabel) > 1 {
		return nil, fmt.Errorf("%w: %d entries are named %q, pick one by index", ErrNoMatch, len(byLabel), sel)
	}
	for _, g := range GroupByCountry(entries) {
		if strings.EqualFold(g.Country, sel) {
			return g.Entries, nil
		}
	}
	var countries []string
	for _, g := range GroupByCountry(entries) {
		countries = append(countries, g.Country)
	}
	return nil, fmt.Errorf("%w: %q; countries: %s; or a label or an index from `ghostlane list`", ErrNoMatch, sel, strings.Join(countries, " "))
}
```

Add `urlPathUnescape` as `net/url.PathUnescape` via an import (`import "net/url"`; `func urlPathUnescape(s string) (string, error) { return url.PathUnescape(s) }`).

```go
// cli/internal/links/fetch.go
package links

import (
	"context"
	"fmt"
	"io"
	"net/http"
)

const (
	UserAgentPrefix = "Ghostlane-cli/"
	maxBody         = 4 << 20
)

func Fetch(ctx context.Context, client *http.Client, url, userAgent string) ([]byte, Headers, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, Headers{}, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/plain, */*")
	resp, err := client.Do(req)
	if err != nil {
		return nil, Headers{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, Headers{}, fmt.Errorf("list: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return nil, Headers{}, err
	}
	if len(body) > maxBody {
		return nil, Headers{}, fmt.Errorf("list: body over %d bytes", maxBody)
	}
	return body, ParseHeaders(resp.Header), nil
}
```

- [ ] **Step 5: Run tests** — `go test ./internal/links/` → PASS.
- [ ] **Step 6: Commit** — `git add cli/internal/links && git commit -m "feat(cli): lists — body, headers, entries, countries, selectors, fetch"`

---

### Task 4: Store — config and state files

**Files:**
- Create: `cli/internal/store/store.go`, `cli/internal/store/store_test.go`

**Interfaces:**
- Produces: `type Config struct { Subscriptions []Subscription; Selection *Selection; Proxy Proxy }`; `type Subscription struct { URL, Title string; IntervalHours int; AddedAt time.Time }`; `type Selection struct { Subscription, Selector, Mode string }`; `type Proxy struct { Listen string; Port int; User, Pass string }`; `func Load(path string) (*Config, error)` (missing file → defaults); `func Save(path string, c *Config) error` (0600, atomic); `type Cache struct { Body []byte; Headers links.Headers; FetchedAt time.Time }`; `func LoadCache(dir, subURL string) (*Cache, error)`; `func SaveCache(dir, subURL string, c *Cache) error`; `func LoadLastGood(dir string) (map[string]string, error)`; `func SaveLastGood(dir string, m map[string]string) error`; `func MaskURL(u string) string`.

- [ ] **Step 1: Failing tests**

```go
// cli/internal/store/store_test.go
package store

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

func TestConfigRoundTripAndPerms(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "config.yaml")
	c, err := Load(p)
	if err != nil || len(c.Subscriptions) != 0 || c.Proxy.Port != 1080 || c.Proxy.Listen != "127.0.0.1" {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c.Subscriptions = append(c.Subscriptions, Subscription{URL: "https://s/sub/a/b?c=olcbox", Title: "t", IntervalHours: 1, AddedAt: time.Unix(1, 0).UTC()})
	c.Selection = &Selection{Subscription: "https://s/sub/a/b?c=olcbox", Selector: "DE", Mode: "tun"}
	if err := Save(p, c); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(p)
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("perm %v", st.Mode().Perm())
	}
	back, err := Load(p)
	if err != nil || back.Selection == nil || back.Selection.Selector != "DE" || back.Subscriptions[0].AddedAt != c.Subscriptions[0].AddedAt {
		t.Fatalf("%+v %v", back, err)
	}
	if _, err := os.Stat(p + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file left behind")
	}
}

func TestCacheAndLastGood(t *testing.T) {
	dir := t.TempDir()
	if c, err := LoadCache(dir, "https://s/sub/x"); err != nil || c != nil {
		t.Fatalf("missing cache is nil,nil: %v %v", c, err)
	}
	in := &Cache{Body: []byte("olcrtc://…"), Headers: links.Headers{Title: "T", UpdateIntervalHours: 3}, FetchedAt: time.Unix(5, 0).UTC()}
	if err := SaveCache(dir, "https://s/sub/x", in); err != nil {
		t.Fatal(err)
	}
	out, err := LoadCache(dir, "https://s/sub/x")
	if err != nil || string(out.Body) != "olcrtc://…" || out.Headers.Title != "T" || !out.FetchedAt.Equal(in.FetchedAt) {
		t.Fatalf("%+v %v", out, err)
	}
	if err := SaveLastGood(dir, map[string]string{"https://s/sub/x|DE": "abc"}); err != nil {
		t.Fatal(err)
	}
	m, err := LoadLastGood(dir)
	if err != nil || m["https://s/sub/x|DE"] != "abc" {
		t.Fatalf("%v %v", m, err)
	}
}

func TestMaskURL(t *testing.T) {
	if got := MaskURL("https://sub.x.org/sub/j7k9e/4gg96t28380ot6es?c=olcbox"); got != "https://sub.x.org/sub/j7k9e/…?c=olcbox" {
		t.Fatalf("%q", got)
	}
	if got := MaskURL("https://proofkit.org/sub/abcdef/unified"); got != "https://proofkit.org/sub/…/unified" {
		t.Fatalf("%q", got)
	}
	if got := MaskURL("https://x.org/list.txt"); got != "https://x.org/…" {
		t.Fatalf("%q", got)
	}
}
```

- [ ] **Step 2: Run to verify failure** — `go test ./internal/store/` → FAIL.

- [ ] **Step 3: Implement**

```go
// cli/internal/store/store.go
// Package store keeps the daemon's config and state under its state directory.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Subscription struct {
	URL           string    `yaml:"url"`
	Title         string    `yaml:"title,omitempty"`
	IntervalHours int       `yaml:"interval_hours"`
	AddedAt       time.Time `yaml:"added_at"`
}

type Selection struct {
	Subscription string `yaml:"subscription"`
	Selector     string `yaml:"selector"`
	Mode         string `yaml:"mode"` // "tun" | "proxy"
}

type Proxy struct {
	Listen string `yaml:"listen"`
	Port   int    `yaml:"port"`
	User   string `yaml:"user,omitempty"`
	Pass   string `yaml:"pass,omitempty"`
}

type Config struct {
	Subscriptions []Subscription `yaml:"subscriptions"`
	Selection     *Selection     `yaml:"selection,omitempty"`
	Proxy         Proxy          `yaml:"proxy"`
}

func Defaults() *Config {
	return &Config{Proxy: Proxy{Listen: "127.0.0.1", Port: 1080}}
}

func Load(path string) (*Config, error) {
	c := Defaults()
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return nil, err
	}
	if err := yaml.Unmarshal(b, c); err != nil {
		return nil, err
	}
	if c.Proxy.Listen == "" {
		c.Proxy.Listen = "127.0.0.1"
	}
	if c.Proxy.Port == 0 {
		c.Proxy.Port = 1080
	}
	return c, nil
}

func Save(path string, c *Config) error {
	b, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	return writeAtomic(path, b, 0o600)
}

func writeAtomic(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	if err := os.Chmod(tmp, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

type Cache struct {
	Body      []byte        `json:"body"`
	Headers   links.Headers `json:"headers"`
	FetchedAt time.Time     `json:"fetched_at"`
}

func cachePath(dir, subURL string) string {
	h := sha256.Sum256([]byte(subURL))
	return filepath.Join(dir, "lists", hex.EncodeToString(h[:8])+".json")
}

func LoadCache(dir, subURL string) (*Cache, error) {
	b, err := os.ReadFile(cachePath(dir, subURL))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var c Cache
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

func SaveCache(dir, subURL string, c *Cache) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	return writeAtomic(cachePath(dir, subURL), b, 0o600)
}

func lastGoodPath(dir string) string { return filepath.Join(dir, "last-good.json") }

func LoadLastGood(dir string) (map[string]string, error) {
	m := map[string]string{}
	b, err := os.ReadFile(lastGoodPath(dir))
	if errors.Is(err, os.ErrNotExist) {
		return m, nil
	}
	if err != nil {
		return nil, err
	}
	return m, json.Unmarshal(b, &m)
}

func SaveLastGood(dir string, m map[string]string) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return writeAtomic(lastGoodPath(dir), b, 0o600)
}

// MaskURL hides the credential part of a list URL: the segment after /sub/<x>/,
// or the whole path when the URL is not of that shape.
func MaskURL(u string) string {
	p, err := url.Parse(u)
	if err != nil {
		return "…"
	}
	segs := strings.Split(strings.Trim(p.Path, "/"), "/")
	for i, s := range segs {
		if s == "sub" && i+1 < len(segs) {
			if i+2 < len(segs) {
				segs[i+2] = "…"
			} else {
				segs[i+1] = "…"
			}
			p.Path = "/" + strings.Join(segs, "/")
			return p.String()
		}
	}
	p.Path = "/…"
	return p.String()
}
```

- [ ] **Step 4: Run tests** — PASS. (`MaskURL` for `/sub/abcdef/unified`: segs = [sub abcdef unified] → i=0, i+2 exists → segs[2]="…"? That yields `/sub/abcdef/…`, but the test wants `/sub/…/unified`. Fix the rule: the token is the last non-static segment: mask `segs[i+1]` when it is the token (`/sub/<token>` or `/sub/<token>/unified|olcrtc`), and mask `segs[i+2]` only when `segs[i+1]` is a short partner id (≤ 8 chars) followed by a longer token. Implement: `if i+2 < len(segs) && len(segs[i+2]) > len(segs[i+1]) { segs[i+2] = "…" } else { segs[i+1] = "…" }`. Re-run: both cases pass.)
- [ ] **Step 5: Commit** — `git add cli/internal/store && git commit -m "feat(cli): store — config, list cache, last good line, masked URLs"`

---

### Task 5: IPC — protocol, server loop, client

**Files:**
- Create: `cli/internal/ipc/ipc.go`, `cli/internal/ipc/ipc_test.go`

**Interfaces:**
- Produces: `const DefaultSocketPath = "/run/ghostlane/ghostlane.sock"`; `type Request struct { Verb, Source, Selector, Mode, Subscription string }`; `type Response struct { OK bool; Error, Message string; Status *Status; Entries []EntryView; Version *VersionInfo; Subscriptions []SubscriptionView }`; `type Status struct { State, Mode, Selector, Since, LastError string; Line *EntryView; Proxy *ProxyView; Subscriptions []SubscriptionView }`; `type EntryView struct { Index int; ID, Label, Country, Kind, Carrier, Transport, Problem string }`; `type ProxyView struct { Socks, HTTP string }`; `type SubscriptionView struct { URL, Title string; IntervalHours int; FetchedAt, NextRefresh string; UserInfo *links.UserInfo; Error string }`; `type VersionInfo struct { Version, Engine, SingBox string }`; `type Handler func(context.Context, Request) Response`; `func Serve(ctx context.Context, l net.Listener, h Handler) error`; `func Listen(path string, mode os.FileMode) (net.Listener, error)`; `func Call(ctx context.Context, path string, req Request) (Response, error)`; `var ErrDaemonDown error`; `func Fail(code, msg string) Response`.

- [ ] **Step 1: Failing test**

```go
// cli/internal/ipc/ipc_test.go
package ipc

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	sock := filepath.Join(t.TempDir(), "s.sock")
	l, err := Listen(sock, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		_ = Serve(ctx, l, func(_ context.Context, r Request) Response {
			if r.Verb == "status" {
				return Response{OK: true, Status: &Status{State: "up", Selector: r.Selector}}
			}
			return Fail("bad_verb", "unknown verb "+r.Verb)
		})
	}()
	resp, err := Call(ctx, sock, Request{Verb: "status", Selector: "DE"})
	if err != nil || !resp.OK || resp.Status.State != "up" || resp.Status.Selector != "DE" {
		t.Fatalf("%+v %v", resp, err)
	}
	resp, err = Call(ctx, sock, Request{Verb: "nope"})
	if err != nil || resp.OK || resp.Error != "bad_verb" {
		t.Fatalf("%+v %v", resp, err)
	}
	_, err = Call(ctx, filepath.Join(t.TempDir(), "missing.sock"), Request{Verb: "status"})
	if !errors.Is(err, ErrDaemonDown) {
		t.Fatalf("want ErrDaemonDown, got %v", err)
	}
}
```

- [ ] **Step 2: Run to verify failure**, then **Step 3: Implement**

```go
// cli/internal/ipc/ipc.go
// Package ipc is the newline-delimited JSON protocol between `ghostlane run` and
// the other subcommands, over a unix socket.
package ipc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const DefaultSocketPath = "/run/ghostlane/ghostlane.sock"

var ErrDaemonDown = errors.New("the ghostlane daemon is not running (systemctl start ghostlane)")

type Request struct {
	Verb         string `json:"verb"`
	Source       string `json:"source,omitempty"`
	Selector     string `json:"selector,omitempty"`
	Mode         string `json:"mode,omitempty"`
	Subscription string `json:"subscription,omitempty"`
}

type EntryView struct {
	Index     int    `json:"index"`
	ID        string `json:"id"`
	Label     string `json:"label"`
	Country   string `json:"country,omitempty"`
	Kind      string `json:"kind"`
	Carrier   string `json:"carrier,omitempty"`
	Transport string `json:"transport,omitempty"`
	Problem   string `json:"problem,omitempty"`
}

type ProxyView struct {
	Socks string `json:"socks"`
	HTTP  string `json:"http"`
}

type SubscriptionView struct {
	URL           string          `json:"url"` // masked
	Title         string          `json:"title,omitempty"`
	IntervalHours int             `json:"interval_hours"`
	FetchedAt     string          `json:"fetched_at,omitempty"`
	NextRefresh   string          `json:"next_refresh,omitempty"`
	UserInfo      *links.UserInfo `json:"userinfo,omitempty"`
	Error         string          `json:"error,omitempty"`
}

type Status struct {
	State         string             `json:"state"` // idle | connecting | up | failed
	Mode          string             `json:"mode,omitempty"`
	Selector      string             `json:"selector,omitempty"`
	Since         string             `json:"since,omitempty"`
	LastError     string             `json:"last_error,omitempty"`
	Line          *EntryView         `json:"line,omitempty"`
	Proxy         *ProxyView         `json:"proxy,omitempty"`
	Subscriptions []SubscriptionView `json:"subscriptions,omitempty"`
}

type VersionInfo struct {
	Version string `json:"version"`
	Engine  string `json:"engine"`
	SingBox string `json:"singbox"`
}

type Response struct {
	OK            bool               `json:"ok"`
	Error         string             `json:"error,omitempty"`
	Message       string             `json:"message,omitempty"`
	Status        *Status            `json:"status,omitempty"`
	Entries       []EntryView        `json:"entries,omitempty"`
	Subscriptions []SubscriptionView `json:"subscriptions,omitempty"`
	Version       *VersionInfo       `json:"version,omitempty"`
}

func Fail(code, msg string) Response { return Response{Error: code, Message: msg} }

type Handler func(context.Context, Request) Response

func Listen(path string, mode os.FileMode) (net.Listener, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, err
	}
	_ = os.Remove(path)
	l, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, mode); err != nil {
		l.Close()
		return nil, err
	}
	return l, nil
}

// Serve answers one request per connection until ctx ends.
func Serve(ctx context.Context, l net.Listener, h Handler) error {
	go func() {
		<-ctx.Done()
		l.Close()
	}()
	for {
		conn, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func() {
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(2 * time.Minute))
			var req Request
			if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&req); err != nil {
				_ = json.NewEncoder(conn).Encode(Fail("bad_request", err.Error()))
				return
			}
			_ = json.NewEncoder(conn).Encode(h(ctx, req))
		}()
	}
}

func Call(ctx context.Context, path string, req Request) (Response, error) {
	d := net.Dialer{Timeout: 3 * time.Second}
	conn, err := d.DialContext(ctx, "unix", path)
	if err != nil {
		return Response{}, fmt.Errorf("%w: %v", ErrDaemonDown, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Minute))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return Response{}, err
	}
	var resp Response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return Response{}, fmt.Errorf("daemon answered nothing: %w", err)
	}
	return resp, nil
}
```

- [ ] **Step 4: Run tests** — PASS. **Step 5: Commit** — `git commit -am "feat(cli): ipc — request/response, socket server, client"` (after `git add cli/internal/ipc`).

---

### Task 6: Engine wrapper (olcRTC)

**Files:**
- Create: `cli/internal/engine/olcrtc/olcrtc.go`, `cli/internal/engine/olcrtc/olcrtc_test.go`, `cli/internal/engine/olcrtc/live_test.go`

**Interfaces:**
- Produces: `type Runtime interface { SetProvider(string) error; SetTransport(string) error; SetRoom(string) error; SetKey(string) error; SetDNS(string) error; SetSocksListenHost(string) error; SetSocksPort(int) error; SetSocksCredentials(string, string) error; SetUDP(bool); SetDirectRules(string) error; SetDeviceIDPath(string); SetVP8Options(int, int) error; Start() error; WaitReady(int) error; Stop(int) error; State() string }`; `var NewRuntime = func() Runtime { return mobile.New() }`; `type Params struct { Line links.OlcrtcLine; SocksHost string; SocksPort int; SocksUser, SocksPass, DNS, DirectRules, DeviceIDPath string }`; `type Session struct { … }` with `SocksAddr() string`, `Credentials() (string, string)`, `State() string`, `Stop(timeout time.Duration) error`; `func Start(ctx context.Context, p Params, readyTimeout time.Duration) (*Session, error)`; `func FreePort() (int, error)`; `func RandomCredentials() (string, string)`; `func HostResolvers(resolvConf string) string`; `const PrivateDirectRules string`; `func SetLogSink(f func(string))`.

- [ ] **Step 1: Failing tests with a fake runtime**

```go
// cli/internal/engine/olcrtc/olcrtc_test.go
package olcrtc

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type fakeRuntime struct {
	calls     []string
	startErr  error
	readyErr  error
	state     string
	port      int
	user, pwd string
}

func (f *fakeRuntime) rec(s string) { f.calls = append(f.calls, s) }
func (f *fakeRuntime) SetProvider(p string) error   { f.rec("provider=" + p); return nil }
func (f *fakeRuntime) SetTransport(t string) error  { f.rec("transport=" + t); return nil }
func (f *fakeRuntime) SetRoom(r string) error       { f.rec("room=" + r); return nil }
func (f *fakeRuntime) SetKey(k string) error        { f.rec("key"); return nil }
func (f *fakeRuntime) SetDNS(d string) error        { f.rec("dns=" + d); return nil }
func (f *fakeRuntime) SetSocksListenHost(h string) error { f.rec("host=" + h); return nil }
func (f *fakeRuntime) SetSocksPort(p int) error     { f.port = p; f.rec("port"); return nil }
func (f *fakeRuntime) SetSocksCredentials(u, p string) error { f.user, f.pwd = u, p; f.rec("creds"); return nil }
func (f *fakeRuntime) SetUDP(b bool)                { f.rec("udp") }
func (f *fakeRuntime) SetDirectRules(t string) error { f.rec("direct"); return nil }
func (f *fakeRuntime) SetDeviceIDPath(p string)     { f.rec("deviceid=" + p) }
func (f *fakeRuntime) SetVP8Options(fps, b int) error { f.rec("vp8"); return nil }
func (f *fakeRuntime) Start() error                 { f.rec("start"); f.state = "running"; return f.startErr }
func (f *fakeRuntime) WaitReady(ms int) error       { f.rec("ready"); return f.readyErr }
func (f *fakeRuntime) Stop(ms int) error            { f.rec("stop"); f.state = "stopped"; return nil }
func (f *fakeRuntime) State() string                { return f.state }

func params() Params {
	return Params{
		Line:      links.OlcrtcLine{Provider: "wbstream", Transport: "vp8channel", Room: "room_x", Key: strings.Repeat("ab", 32), VP8FPS: 25, VP8Batch: 6},
		SocksHost: "127.0.0.1", SocksPort: 12345, SocksUser: "u", SocksPass: "p",
		DNS: "1.1.1.1:53", DirectRules: PrivateDirectRules, DeviceIDPath: "/tmp/x/device-id",
	}
}

func TestStartAppliesEverythingInOrder(t *testing.T) {
	f := &fakeRuntime{}
	NewRuntime = func() Runtime { return f }
	s, err := Start(context.Background(), params(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	want := "provider=wbstream transport=vp8channel room=room_x key vp8 dns=1.1.1.1:53 host=127.0.0.1 port creds udp direct deviceid=/tmp/x/device-id start ready"
	if got := strings.Join(f.calls, " "); got != want {
		t.Fatalf("\n got %s\nwant %s", got, want)
	}
	if s.SocksAddr() != "127.0.0.1:12345" || f.user != "u" {
		t.Fatalf("addr %s", s.SocksAddr())
	}
	if err := s.Stop(time.Second); err != nil || f.state != "stopped" {
		t.Fatal(err)
	}
}

func TestStartStopsOnNotReady(t *testing.T) {
	f := &fakeRuntime{readyErr: errors.New("no room")}
	NewRuntime = func() Runtime { return f }
	if _, err := Start(context.Background(), params(), time.Second); err == nil || !strings.Contains(err.Error(), "no room") {
		t.Fatalf("%v", err)
	}
	if f.calls[len(f.calls)-1] != "stop" {
		t.Fatalf("a runtime that is not ready is stopped: %v", f.calls)
	}
}

func TestNoVP8OptionsWhenDefault(t *testing.T) {
	f := &fakeRuntime{}
	NewRuntime = func() Runtime { return f }
	p := params()
	p.Line.VP8FPS, p.Line.VP8Batch = 0, 0
	if _, err := Start(context.Background(), p, time.Second); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(strings.Join(f.calls, " "), "vp8") {
		t.Fatal("engine defaults are left alone")
	}
}

func TestHelpers(t *testing.T) {
	p, err := FreePort()
	if err != nil || p < 1024 {
		t.Fatalf("%d %v", p, err)
	}
	u, pw := RandomCredentials()
	u2, _ := RandomCredentials()
	if len(u) < 8 || len(pw) < 16 || u == u2 {
		t.Fatal("credentials are random")
	}
	rc := filepath.Join(t.TempDir(), "resolv.conf")
	_ = os.WriteFile(rc, []byte("# x\nnameserver 127.0.0.53\nsearch lan\nnameserver 2606:4700:4700::1111\n"), 0o644)
	if got := HostResolvers(rc); got != "127.0.0.53:53,[2606:4700:4700::1111]:53,1.1.1.1:53" {
		t.Fatalf("%q", got)
	}
	if got := HostResolvers(filepath.Join(t.TempDir(), "none")); got != "1.1.1.1:53" {
		t.Fatalf("%q", got)
	}
}
```

- [ ] **Step 2: Run to verify failure**, then **Step 3: Implement**

```go
// cli/internal/engine/olcrtc/olcrtc.go
// Package olcrtc drives the engine the apps use, mobile.Runtime, as a plain Go
// package: one Runtime per connection.
package olcrtc

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/openlibrecommunity/olcrtc/mobile"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type Runtime interface {
	SetProvider(string) error
	SetTransport(string) error
	SetRoom(string) error
	SetKey(string) error
	SetDNS(string) error
	SetSocksListenHost(string) error
	SetSocksPort(int) error
	SetSocksCredentials(string, string) error
	SetUDP(bool)
	SetDirectRules(string) error
	SetDeviceIDPath(string)
	SetVP8Options(int, int) error
	Start() error
	WaitReady(int) error
	Stop(int) error
	State() string
}

// NewRuntime is swapped by tests.
var NewRuntime = func() Runtime { return mobile.New() }

// PrivateDirectRules keeps LAN, link-local and CGNAT traffic off the tunnel,
// in the engine's route.direct syntax.
const PrivateDirectRules = "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n100.64.0.0/10\n169.254.0.0/16\n127.0.0.0/8\nfc00::/7\nfe80::/10\n"

type Params struct {
	Line         links.OlcrtcLine
	SocksHost    string
	SocksPort    int
	SocksUser    string
	SocksPass    string
	DNS          string
	DirectRules  string
	DeviceIDPath string
}

type Session struct {
	rt   Runtime
	addr string
	user string
	pass string
}

func Start(ctx context.Context, p Params, readyTimeout time.Duration) (*Session, error) {
	rt := NewRuntime()
	steps := []struct {
		name string
		fn   func() error
	}{
		{"provider", func() error { return rt.SetProvider(p.Line.Provider) }},
		{"transport", func() error { return rt.SetTransport(p.Line.Transport) }},
		{"room", func() error { return rt.SetRoom(p.Line.Room) }},
		{"key", func() error { return rt.SetKey(p.Line.Key) }},
		{"vp8", func() error {
			if p.Line.VP8FPS == 0 && p.Line.VP8Batch == 0 {
				return nil
			}
			return rt.SetVP8Options(p.Line.VP8FPS, p.Line.VP8Batch)
		}},
		{"dns", func() error { return rt.SetDNS(p.DNS) }},
		{"socks host", func() error { return rt.SetSocksListenHost(p.SocksHost) }},
		{"socks port", func() error { return rt.SetSocksPort(p.SocksPort) }},
		{"socks credentials", func() error { return rt.SetSocksCredentials(p.SocksUser, p.SocksPass) }},
		{"udp", func() error { rt.SetUDP(true); return nil }},
		{"direct rules", func() error { return rt.SetDirectRules(p.DirectRules) }},
		{"device id", func() error { rt.SetDeviceIDPath(p.DeviceIDPath); return nil }},
	}
	for _, s := range steps {
		if err := s.fn(); err != nil {
			return nil, fmt.Errorf("engine %s: %w", s.name, err)
		}
	}
	if err := rt.Start(); err != nil {
		return nil, fmt.Errorf("engine start: %w", err)
	}
	if err := rt.WaitReady(int(readyTimeout / time.Millisecond)); err != nil {
		_ = rt.Stop(5000)
		return nil, fmt.Errorf("engine not ready within %s: %w", readyTimeout, err)
	}
	return &Session{rt: rt, addr: net.JoinHostPort(p.SocksHost, strconv.Itoa(p.SocksPort)), user: p.SocksUser, pass: p.SocksPass}, nil
}

func (s *Session) SocksAddr() string                { return s.addr }
func (s *Session) Credentials() (string, string)    { return s.user, s.pass }
func (s *Session) State() string                    { return s.rt.State() }
func (s *Session) Stop(timeout time.Duration) error { return s.rt.Stop(int(timeout / time.Millisecond)) }

func FreePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func RandomCredentials() (string, string) {
	u := make([]byte, 6)
	p := make([]byte, 12)
	_, _ = rand.Read(u)
	_, _ = rand.Read(p)
	return "gl" + hex.EncodeToString(u), hex.EncodeToString(p)
}

// HostResolvers reads the nameserver lines of a resolv.conf and appends a
// public operator, in the "host:port,host:port" shape SetDNS takes.
func HostResolvers(resolvConf string) string {
	var out []string
	if f, err := os.Open(resolvConf); err == nil {
		defer f.Close()
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			fields := strings.Fields(sc.Text())
			if len(fields) >= 2 && fields[0] == "nameserver" {
				if ip := net.ParseIP(fields[1]); ip != nil {
					out = append(out, net.JoinHostPort(ip.String(), "53"))
				}
			}
		}
	}
	out = append(out, "1.1.1.1:53")
	return strings.Join(out, ",")
}

type logSink struct{ f func(string) }

func (l logSink) WriteLog(msg string) { l.f(msg) }

// SetLogSink routes the engine's log lines to f (the daemon scrubs and forwards).
func SetLogSink(f func(string)) { mobile.SetLogWriter(logSink{f}) }
```

- [ ] **Step 4: Live test (opt-in)**

```go
// cli/internal/engine/olcrtc/live_test.go
package olcrtc

import (
	"context"
	"net/http"
	"os"
	"testing"
	"time"

	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

// GHOSTLANE_LIVE_OLCRTC_LINE='olcrtc://…' go test -run TestLive ./internal/engine/olcrtc/
func TestLiveLine(t *testing.T) {
	raw := os.Getenv("GHOSTLANE_LIVE_OLCRTC_LINE")
	if raw == "" {
		t.Skip("set GHOSTLANE_LIVE_OLCRTC_LINE to a real room line")
	}
	line, err := links.ParseOlcrtc(raw)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := FreePort()
	u, p := RandomCredentials()
	s, err := Start(context.Background(), Params{Line: *line, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: u, SocksPass: p,
		DNS: HostResolvers("/etc/resolv.conf"), DirectRules: PrivateDirectRules, DeviceIDPath: t.TempDir() + "/device-id"}, 90*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop(10 * time.Second)
	d, _ := proxy.SOCKS5("tcp", s.SocksAddr(), &proxy.Auth{User: u, Password: p}, proxy.Direct)
	c := &http.Client{Transport: &http.Transport{Dial: d.Dial}, Timeout: 60 * time.Second}
	var resp *http.Response
	for i := 0; i < 10; i++ {
		resp, err = c.Get("http://cp.cloudflare.com/generate_204")
		if err == nil {
			break
		}
		time.Sleep(3 * time.Second)
	}
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("through the room: %v %v", resp, err)
	}
}
```

- [ ] **Step 5: Run unit tests** — `go test ./internal/engine/olcrtc/` → PASS (live test skipped). Then run the live test once from DATA with a line from the partner's `?c=olcbox` list (DE Telemost) — expected PASS; if the room refuses (full/rotated), try the WB line. Record the outcome in the commit message.
- [ ] **Step 6: Commit** — `git add cli/internal/engine && git commit -m "feat(cli): engine wrapper over mobile.Runtime, live room test"`

---

### Task 7: sing-box front — config builder, validation, lifecycle

**Files:**
- Create: `cli/internal/engine/singbox/config.go`, `cli/internal/engine/singbox/config_test.go`, `cli/internal/engine/singbox/front.go`, `cli/internal/engine/singbox/front_test.go`, `cli/internal/testutil/fakesocks/fakesocks.go`

**Interfaces:**
- Produces: `type Mode string` (`ModeProxy = "proxy"`, `ModeTun = "tun"`); `type FrontParams struct { Mode Mode; UpstreamAddr, UpstreamUser, UpstreamPass string; ProxyListen string; ProxyPort int; ProxyUser, ProxyPass string; ExcludeUID int; InterfaceName string; LogLevel string }`; `const (TableIndex = 2022; RuleIndex = 9000; TunName = "ghostlane0")`; `func BuildConfig(p FrontParams) ([]byte, error)`; `func Parse(cfg []byte) (option.Options, error)`; `type Front struct{…}`; `func Start(ctx context.Context, p FrontParams) (*Front, error)`; `func (f *Front) Close() error`; `func New(ctx context.Context, p FrontParams) (*Front, error)` (built, not started — the validator).
- `fakesocks.Serve(t testing.TB, user, pass string, handle func(target string, conn net.Conn)) (addr string)`: a SOCKS5 server (RFC 1928 + 1929 user/pass, CONNECT only) that hands each accepted CONNECT to `handle`; `fakesocks.Answer204` is a handler writing `HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n` after reading the request head.

- [ ] **Step 1: fakesocks helper** (no test of its own; used by the tests below)

```go
// cli/internal/testutil/fakesocks/fakesocks.go
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
```

- [ ] **Step 2: Failing tests**

```go
// cli/internal/engine/singbox/config_test.go
package singbox

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func base(mode Mode) FrontParams {
	return FrontParams{Mode: mode, UpstreamAddr: "127.0.0.1:10808", UpstreamUser: "u", UpstreamPass: "p",
		ProxyListen: "127.0.0.1", ProxyPort: 1080, ExcludeUID: 977, InterfaceName: TunName, LogLevel: "warn"}
}

func TestProxyConfigParsesAndBuilds(t *testing.T) {
	cfg, err := BuildConfig(base(ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused the proxy config: %v\n%s", err, cfg)
	}
	f, err := New(context.Background(), base(ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	var m map[string]any
	_ = json.Unmarshal(cfg, &m)
	in := m["inbounds"].([]any)[0].(map[string]any)
	if in["type"] != "mixed" || in["listen"] != "127.0.0.1" || in["listen_port"] != float64(1080) {
		t.Fatalf("inbound %v", in)
	}
	if strings.Contains(string(cfg), `"tun"`) {
		t.Fatal("proxy mode has no tun")
	}
}

func TestTunConfigParsesAndBuildsUnprivileged(t *testing.T) {
	cfg, err := BuildConfig(base(ModeTun))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused the tun config: %v\n%s", err, cfg)
	}
	f, err := New(context.Background(), base(ModeTun)) // the device opens at Start, so this needs no root
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	s := string(cfg)
	for _, want := range []string{`"interface_name":"ghostlane0"`, `"auto_route":true`, `"strict_route":false`,
		`"iproute2_table_index":2022`, `"iproute2_rule_index":9000`, `"exclude_uid":[977]`, `"stack":"system"`,
		`"172.19.0.1/30"`, `"fdfe:dcba:9876::1/126"`, `"169.254.0.0/16"`, `"hijack-dns"`, `"ip_version":6`,
		`"default_domain_resolver":"local"`, `"detour":"tunnel"`, `"final":"tunnel"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("tun config lacks %s:\n%s", want, s)
		}
	}
}

func TestProxyWithAuthAndListenAll(t *testing.T) {
	p := base(ModeProxy)
	p.ProxyListen, p.ProxyUser, p.ProxyPass = "0.0.0.0", "a", "b"
	cfg, err := BuildConfig(p)
	if err != nil || !strings.Contains(string(cfg), `"users":[{"username":"a","password":"b"}]`) {
		t.Fatalf("%v\n%s", err, cfg)
	}
	p.ProxyUser = ""
	if _, err := BuildConfig(p); err == nil {
		t.Fatal("listening beyond loopback without credentials is refused")
	}
}
```

```go
// cli/internal/engine/singbox/front_test.go
package singbox

import (
	"context"
	"net/http"
	"testing"
	"time"

	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/fakesocks"
)

func TestProxyModeEndToEnd(t *testing.T) {
	upstream := fakesocks.Serve(t, "u", "p", fakesocks.Answer204)
	port, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.UpstreamAddr, p.ProxyPort = upstream, port
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f, err := Start(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	d, _ := proxy.SOCKS5("tcp", p.ProxyListen+":"+itoa(port), nil, proxy.Direct)
	c := &http.Client{Transport: &http.Transport{Dial: d.Dial}, Timeout: 5 * time.Second}
	resp, err := c.Get("http://203.0.113.10/anything")
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("through the front: %v %v", resp, err)
	}
	// the HTTP half of the mixed inbound
	hc := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(mustURL("http://" + p.ProxyListen + ":" + itoa(port)))}, Timeout: 5 * time.Second}
	resp, err = hc.Get("http://203.0.113.11/")
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("http proxy: %v %v", resp, err)
	}
}
```
(`itoa` = `strconv.Itoa`, `mustURL` = `url.Parse` ignoring the error; define both in the test file.)

- [ ] **Step 3: Run to verify failure**, then **Step 4: Implement**

```go
// cli/internal/engine/singbox/config.go
// Package singbox is the front: a tun or a local proxy in front of the engine's SOCKS.
package singbox

import (
	"encoding/json"
	"errors"
	"net"
	"strconv"
)

type Mode string

const (
	ModeProxy Mode = "proxy"
	ModeTun   Mode = "tun"

	TableIndex = 2022
	RuleIndex  = 9000
	TunName    = "ghostlane0"
)

type FrontParams struct {
	Mode          Mode
	UpstreamAddr  string // the engine's SOCKS, host:port
	UpstreamUser  string
	UpstreamPass  string
	ProxyListen   string
	ProxyPort     int
	ProxyUser     string
	ProxyPass     string
	ExcludeUID    int
	InterfaceName string
	LogLevel      string
}

var privateRanges = []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
	"169.254.0.0/16", "127.0.0.0/8", "224.0.0.0/4", "fc00::/7", "fe80::/10", "ff00::/8"}

// BuildConfig emits the sing-box JSON of the spec's §6–§7.
func BuildConfig(p FrontParams) ([]byte, error) {
	host, portStr, err := net.SplitHostPort(p.UpstreamAddr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, err
	}
	level := p.LogLevel
	if level == "" {
		level = "warn"
	}
	var inbound map[string]any
	switch p.Mode {
	case ModeProxy:
		if p.ProxyListen != "127.0.0.1" && p.ProxyListen != "::1" && p.ProxyListen != "localhost" && (p.ProxyUser == "" || p.ProxyPass == "") {
			return nil, errors.New("a proxy that listens beyond loopback needs a user and a password")
		}
		inbound = map[string]any{"type": "mixed", "tag": "proxy-in", "listen": p.ProxyListen, "listen_port": p.ProxyPort}
		if p.ProxyUser != "" {
			inbound["users"] = []map[string]any{{"username": p.ProxyUser, "password": p.ProxyPass}}
		}
	case ModeTun:
		name := p.InterfaceName
		if name == "" {
			name = TunName
		}
		inbound = map[string]any{
			"type": "tun", "tag": "tun-in",
			"interface_name":        name,
			"address":               []string{"172.19.0.1/30", "fdfe:dcba:9876::1/126"},
			"auto_route":            true,
			"strict_route":          false,
			"iproute2_table_index":  TableIndex,
			"iproute2_rule_index":   RuleIndex,
			"exclude_uid":           []int{p.ExcludeUID},
			"route_exclude_address": privateRanges,
			"stack":                 "system",
		}
	default:
		return nil, errors.New("mode must be tun or proxy")
	}
	upstream := map[string]any{"type": "socks", "tag": "tunnel", "server": host, "server_port": port, "version": "5"}
	if p.UpstreamUser != "" {
		upstream["username"], upstream["password"] = p.UpstreamUser, p.UpstreamPass
	}
	cfg := map[string]any{
		"log": map[string]any{"level": level, "timestamp": false},
		"dns": map[string]any{
			"servers": []map[string]any{
				{"type": "https", "tag": "remote", "server": "1.1.1.1", "detour": "tunnel"},
				{"type": "local", "tag": "local"},
			},
			"final": "remote",
		},
		"inbounds":  []map[string]any{inbound},
		"outbounds": []map[string]any{upstream, {"type": "direct", "tag": "direct"}},
		"route": map[string]any{
			"rules": []map[string]any{
				{"action": "sniff"},
				{"protocol": "dns", "action": "hijack-dns"},
				{"ip_is_private": true, "outbound": "direct"},
				{"ip_version": 6, "action": "reject"},
			},
			"final":                   "tunnel",
			"auto_detect_interface":   true,
			"default_domain_resolver": "local",
		},
	}
	return json.Marshal(cfg)
}
```

```go
// cli/internal/engine/singbox/front.go
package singbox

import (
	"context"
	"fmt"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

// Parse is `sing-box check` for an in-memory config: the typed decoder refuses
// unknown fields and wrong types.
func Parse(cfg []byte) (option.Options, error) {
	ctx := include.Context(context.Background())
	return sjson.UnmarshalExtendedContext[option.Options](ctx, cfg)
}

type Front struct {
	box    *box.Box
	cancel context.CancelFunc
}

// New builds the box without starting it: every option is validated, the tun
// device is not opened (that happens in Start).
func New(ctx context.Context, p FrontParams) (*Front, error) {
	cfg, err := BuildConfig(p)
	if err != nil {
		return nil, err
	}
	opts, err := Parse(cfg)
	if err != nil {
		return nil, fmt.Errorf("sing-box config: %w", err)
	}
	ctx, cancel := context.WithCancel(include.Context(ctx))
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("sing-box: %w", err)
	}
	return &Front{box: b, cancel: cancel}, nil
}

func Start(ctx context.Context, p FrontParams) (*Front, error) {
	f, err := New(ctx, p)
	if err != nil {
		return nil, err
	}
	if err := f.box.Start(); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("sing-box start: %w", err)
	}
	return f, nil
}

func (f *Front) Close() error {
	defer f.cancel()
	return f.box.Close()
}
```

- [ ] **Step 5: Run tests** — `go test -tags with_utls,with_quic ./internal/engine/singbox/ ./internal/testutil/...` → PASS. If `Parse` rejects a field name, the sing-box 1.13.14 option structs in `/root/go/pkg/mod/github.com/sagernet/sing-box@v1.13.14/option/` are the reference; fix the builder, never the test's expectations of the spec'd values. If the end-to-end test fails with the fake refusing auth, check the socks outbound sends RFC 1929 (it does when `username` is set).
- [ ] **Step 6: Commit** — `git add cli/internal/engine/singbox cli/internal/testutil && git commit -m "feat(cli): sing-box front — tun and proxy configs, typed validation, lifecycle"`

---

### Task 8: Policy rules — own-address rules, stale cleanup, address watch, netns harness

**Files:**
- Create: `cli/internal/routes/routes.go`, `cli/internal/routes/routes_test.go`, `cli/internal/routes/netns_test.go`, `cli/internal/testutil/netnstest/netnstest.go`

**Interfaces:**
- Produces: `const OwnAddressPriority = 8990`; `type Manager struct{…}`; `func New(excludeIface string) *Manager`; `func (m *Manager) GlobalAddresses() ([]netip.Addr, error)`; `func (m *Manager) Sync(addrs []netip.Addr) error`; `func (m *Manager) Clear() error`; `func (m *Manager) CleanupStale() error`; `func (m *Manager) WatchAddresses(ctx context.Context, onChange func()) error`; pure helpers `filterGlobal(addrs []netlink.Addr, excludeIndex int) []netip.Addr`, `diff(have, want []netip.Addr) (add, del []netip.Addr)`.
- `netnstest.Enter(t *testing.T) bool`: skips unless `GHOSTLANE_NETNS=1` and euid 0; in the parent, re-runs the same test under `unshare -n` and returns false; in the child, brings `lo` up and returns true.

- [ ] **Step 1: The netns harness**

```go
// cli/internal/testutil/netnstest/netnstest.go
// Package netnstest runs a test inside a fresh network namespace: the parent
// re-executes the test binary under `unshare -n`, the child does the work.
package netnstest

import (
	"os"
	"os/exec"
	"testing"

	"github.com/vishvananda/netlink"
)

func Enter(t *testing.T) bool {
	t.Helper()
	if os.Getenv("GHOSTLANE_NETNS") != "1" {
		t.Skip("set GHOSTLANE_NETNS=1 (and run as root) for the netns tests")
	}
	if os.Geteuid() != 0 {
		t.Skip("netns tests need root")
	}
	if os.Getenv("GHOSTLANE_IN_NETNS") == "1" {
		lo, err := netlink.LinkByName("lo")
		if err != nil {
			t.Fatal(err)
		}
		if err := netlink.LinkSetUp(lo); err != nil {
			t.Fatal(err)
		}
		return true
	}
	cmd := exec.Command("unshare", "-n", "--", os.Args[0], "-test.run=^"+t.Name()+"$", "-test.v", "-test.count=1")
	cmd.Env = append(os.Environ(), "GHOSTLANE_IN_NETNS=1")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("%s failed inside the netns: %v", t.Name(), err)
	}
	return false
}
```

- [ ] **Step 2: Failing unit tests (pure parts)**

```go
// cli/internal/routes/routes_test.go
package routes

import (
	"net"
	"net/netip"
	"testing"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"
)

func addr(cidr string, scope int, index int) netlink.Addr {
	ip, n, _ := net.ParseCIDR(cidr)
	n.IP = ip
	return netlink.Addr{IPNet: n, Scope: scope, LinkIndex: index}
}

func TestFilterGlobal(t *testing.T) {
	in := []netlink.Addr{
		addr("203.0.113.5/24", unix.RT_SCOPE_UNIVERSE, 2),
		addr("10.0.0.7/8", unix.RT_SCOPE_UNIVERSE, 2),
		addr("127.0.0.1/8", unix.RT_SCOPE_HOST, 1),
		addr("169.254.3.3/16", unix.RT_SCOPE_LINK, 2),
		addr("2001:db8::9/64", unix.RT_SCOPE_UNIVERSE, 2),
		addr("fe80::1/64", unix.RT_SCOPE_LINK, 2),
		addr("172.19.0.1/30", unix.RT_SCOPE_UNIVERSE, 9), // the tun itself
	}
	got := filterGlobal(in, 9)
	want := []netip.Addr{netip.MustParseAddr("203.0.113.5"), netip.MustParseAddr("10.0.0.7"), netip.MustParseAddr("2001:db8::9")}
	if len(got) != len(want) {
		t.Fatalf("%v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("%v", got)
		}
	}
}

func TestDiff(t *testing.T) {
	a, b, c := netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("2.2.2.2"), netip.MustParseAddr("3.3.3.3")
	add, del := diff([]netip.Addr{a, b}, []netip.Addr{b, c})
	if len(add) != 1 || add[0] != c || len(del) != 1 || del[0] != a {
		t.Fatalf("add=%v del=%v", add, del)
	}
	add, del = diff([]netip.Addr{a}, []netip.Addr{a})
	if len(add) != 0 || len(del) != 0 {
		t.Fatal("no change, no work")
	}
}
```

- [ ] **Step 3: Netns test**

```go
// cli/internal/routes/netns_test.go
package routes

import (
	"net"
	"net/netip"
	"testing"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/netnstest"
)

func rulesWithPriority(t *testing.T, family, prio int) []netlink.Rule {
	t.Helper()
	all, err := netlink.RuleList(family)
	if err != nil {
		t.Fatal(err)
	}
	var out []netlink.Rule
	for _, r := range all {
		if r.Priority == prio {
			out = append(out, r)
		}
	}
	return out
}

func TestNetnsOwnAddressRulesAndCleanup(t *testing.T) {
	if !netnstest.Enter(t) {
		return
	}
	dummy := &netlink.Dummy{LinkAttrs: netlink.LinkAttrs{Name: "gl-dum0"}}
	if err := netlink.LinkAdd(dummy); err != nil {
		t.Fatal(err)
	}
	link, _ := netlink.LinkByName("gl-dum0")
	_ = netlink.LinkSetUp(link)
	a, _ := netlink.ParseAddr("198.51.100.7/24")
	_ = netlink.AddrAdd(link, a)
	a6, _ := netlink.ParseAddr("2001:db8::7/64")
	_ = netlink.AddrAdd(link, a6)

	m := New("ghostlane0")
	addrs, err := m.GlobalAddresses()
	if err != nil || len(addrs) != 2 {
		t.Fatalf("%v %v", addrs, err)
	}
	if err := m.Sync(addrs); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 || got[0].Src.IP.String() != "198.51.100.7" || got[0].Table != unix.RT_TABLE_MAIN {
		t.Fatalf("v4 rule: %+v", got)
	}
	if got := rulesWithPriority(t, unix.AF_INET6, OwnAddressPriority); len(got) != 1 {
		t.Fatalf("v6 rule: %+v", got)
	}
	// Review Focus 3: a second Sync with one address gone and one added.
	if err := m.Sync([]netip.Addr{netip.MustParseAddr("198.51.100.8")}); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 || got[0].Src.IP.String() != "198.51.100.8" {
		t.Fatalf("after change: %+v", got)
	}
	if got := rulesWithPriority(t, unix.AF_INET6, OwnAddressPriority); len(got) != 0 {
		t.Fatalf("v6 rule should be gone: %+v", got)
	}
	if err := m.Sync([]netip.Addr{netip.MustParseAddr("198.51.100.8")}); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 {
		t.Fatalf("sync is idempotent: %+v", got)
	}
	if err := m.Clear(); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 0 {
		t.Fatalf("clear: %+v", got)
	}

	// A crash leaves sing-box's rules and table behind; CleanupStale removes them.
	stale := netlink.NewRule()
	stale.Priority, stale.Table, stale.Family = 9003, 2022, unix.AF_INET
	if err := netlink.RuleAdd(stale); err != nil {
		t.Fatal(err)
	}
	_, dst, _ := net.ParseCIDR("203.0.113.0/24")
	if err := netlink.RouteAdd(&netlink.Route{Dst: dst, LinkIndex: link.Attrs().Index, Table: 2022}); err != nil {
		t.Fatal(err)
	}
	if err := m.CleanupStale(); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, 9003); len(got) != 0 {
		t.Fatalf("stale rule: %+v", got)
	}
	routes, _ := netlink.RouteListFiltered(unix.AF_INET, &netlink.Route{Table: 2022}, netlink.RT_FILTER_TABLE)
	if len(routes) != 0 {
		t.Fatalf("table 2022 not flushed: %+v", routes)
	}
}
```

- [ ] **Step 4: Run to verify failure** — `go test ./internal/routes/` → FAIL (undefined). **Step 5: Implement**

```go
// cli/internal/routes/routes.go
// Package routes owns the policy rules the daemon adds beside sing-box's: one
// `from <host address> lookup main` per global address of the host, ahead of
// sing-box's rules, so replies of inbound connections keep their normal route
// while everything the host originates goes through the tun.
package routes

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"time"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
)

const (
	OwnAddressPriority = 8990
	singBoxRuleFirst   = singbox.RuleIndex
	singBoxRuleLast    = singbox.RuleIndex + 10
	singBoxTable       = singbox.TableIndex
)

type Manager struct{ excludeIface string }

func New(excludeIface string) *Manager { return &Manager{excludeIface: excludeIface} }

func (m *Manager) GlobalAddresses() ([]netip.Addr, error) {
	addrs, err := netlink.AddrList(nil, netlink.FAMILY_ALL)
	if err != nil {
		return nil, err
	}
	exclude := -1
	if link, err := netlink.LinkByName(m.excludeIface); err == nil {
		exclude = link.Attrs().Index
	}
	return filterGlobal(addrs, exclude), nil
}

func filterGlobal(addrs []netlink.Addr, excludeIndex int) []netip.Addr {
	var out []netip.Addr
	for _, a := range addrs {
		if a.Scope != unix.RT_SCOPE_UNIVERSE || a.LinkIndex == excludeIndex || a.IPNet == nil {
			continue
		}
		ip, ok := netip.AddrFromSlice(a.IPNet.IP)
		if !ok {
			continue
		}
		ip = ip.Unmap()
		if ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		out = append(out, ip)
	}
	return out
}

func diff(have, want []netip.Addr) (add, del []netip.Addr) {
	h := map[netip.Addr]bool{}
	w := map[netip.Addr]bool{}
	for _, a := range have {
		h[a] = true
	}
	for _, a := range want {
		w[a] = true
		if !h[a] {
			add = append(add, a)
		}
	}
	for _, a := range have {
		if !w[a] {
			del = append(del, a)
		}
	}
	return add, del
}

func ownRule(a netip.Addr) *netlink.Rule {
	r := netlink.NewRule()
	r.Priority = OwnAddressPriority
	r.Table = unix.RT_TABLE_MAIN
	bits := 32
	r.Family = unix.AF_INET
	if a.Is6() {
		bits = 128
		r.Family = unix.AF_INET6
	}
	r.Src = &net.IPNet{IP: a.AsSlice(), Mask: net.CIDRMask(bits, bits)}
	return r
}

func (m *Manager) existing() ([]netip.Addr, error) {
	var out []netip.Addr
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		rules, err := netlink.RuleList(fam)
		if err != nil {
			return nil, err
		}
		for _, r := range rules {
			if r.Priority == OwnAddressPriority && r.Src != nil {
				if ip, ok := netip.AddrFromSlice(r.Src.IP); ok {
					out = append(out, ip.Unmap())
				}
			}
		}
	}
	return out, nil
}

// Sync makes the set of own-address rules equal to addrs: missing ones added,
// extra ones removed, nothing duplicated.
func (m *Manager) Sync(addrs []netip.Addr) error {
	have, err := m.existing()
	if err != nil {
		return err
	}
	add, del, errs := diffAndApply(have, addrs)
	_ = add
	_ = del
	return errs
}

func diffAndApply(have, want []netip.Addr) (add, del []netip.Addr, err error) {
	add, del = diff(have, want)
	var errs []error
	for _, a := range del {
		if e := netlink.RuleDel(ownRule(a)); e != nil {
			errs = append(errs, fmt.Errorf("rule del %s: %w", a, e))
		}
	}
	for _, a := range add {
		if e := netlink.RuleAdd(ownRule(a)); e != nil {
			errs = append(errs, fmt.Errorf("rule add %s: %w", a, e))
		}
	}
	return add, del, errors.Join(errs...)
}

func (m *Manager) Clear() error { return m.Sync(nil) }

// CleanupStale removes what a crashed run left: our rules, sing-box's rules
// (9000–9010) and every route of table 2022. The box has one tun, ours.
func (m *Manager) CleanupStale() error {
	var errs []error
	if err := m.Clear(); err != nil {
		errs = append(errs, err)
	}
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		rules, err := netlink.RuleList(fam)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		for i := range rules {
			r := rules[i]
			if r.Priority >= singBoxRuleFirst && r.Priority <= singBoxRuleLast {
				if e := netlink.RuleDel(&r); e != nil {
					errs = append(errs, fmt.Errorf("stale rule %d: %w", r.Priority, e))
				}
			}
		}
		routes, err := netlink.RouteListFiltered(fam, &netlink.Route{Table: singBoxTable}, netlink.RT_FILTER_TABLE)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		for i := range routes {
			if e := netlink.RouteDel(&routes[i]); e != nil {
				errs = append(errs, fmt.Errorf("stale route: %w", e))
			}
		}
	}
	return errors.Join(errs...)
}

// WatchAddresses calls onChange, debounced, whenever an address is added or
// removed on any interface, until ctx ends.
func (m *Manager) WatchAddresses(ctx context.Context, onChange func()) error {
	ch := make(chan netlink.AddrUpdate, 64)
	done := make(chan struct{})
	if err := netlink.AddrSubscribe(ch, done); err != nil {
		return err
	}
	go func() {
		defer close(done)
		var timer *time.Timer
		var fire <-chan time.Time
		for {
			select {
			case <-ctx.Done():
				return
			case <-ch:
				if timer == nil {
					timer = time.NewTimer(500 * time.Millisecond)
				} else {
					timer.Reset(500 * time.Millisecond)
				}
				fire = timer.C
			case <-fire:
				fire = nil
				onChange()
			}
		}
	}()
	return nil
}
```
(Simplify `Sync`: call `diffAndApply` and return its error; drop the unused `add`/`del` locals — the split exists so the diff stays a pure, tested function.)

- [ ] **Step 6: Run** — `go test ./internal/routes/` (pure) → PASS; `sudo -E env PATH=$PATH GHOSTLANE_NETNS=1 go test -run TestNetns -v ./internal/routes/` → PASS on DATA (root already; `unshare -n` never touches the host network).
- [ ] **Step 7: Commit** — `git add cli/internal/routes cli/internal/testutil/netnstest && git commit -m "feat(cli): own-address policy rules, stale cleanup, address watch; netns harness"`

---

### Task 9: Daemon — state machine, failover, refresh, control socket

**Files:**
- Create: `cli/internal/daemon/daemon.go`, `cli/internal/daemon/connect.go`, `cli/internal/daemon/handlers.go`, `cli/internal/daemon/scrub.go`, `cli/internal/daemon/notify.go`, `cli/internal/daemon/probe.go`, `cli/internal/daemon/daemon_test.go`, `cli/internal/daemon/scrub_test.go`, `cli/internal/links/import.go`, `cli/internal/links/import_test.go`

**Interfaces:**
- Consumes: Tasks 3–8.
- Produces: `type Engine interface { SocksAddr() string; Credentials() (string, string); State() string; Stop(time.Duration) error }`; `type Front interface{ Close() error }`; `type Routes interface { GlobalAddresses() ([]netip.Addr, error); Sync([]netip.Addr) error; Clear() error; CleanupStale() error; WatchAddresses(context.Context, func()) error }`; `type Deps struct{…}` (below); `func New(d Deps) *Daemon`; `func (d *Daemon) Run(ctx context.Context) error`; `func (d *Daemon) Handle(ctx context.Context, req ipc.Request) ipc.Response`; `func Scrub(s string) string`; `func NotifyReady()`; `func HTTPProbe(probeURL string) func(ctx context.Context, socksAddr, user, pass string) error`; `links.ImportPayload(s string) string`.

- [ ] **Step 1: `links.ImportPayload` (the `ghostlane://` forms of ImportLink.kt) with its test**

```go
// cli/internal/links/import.go
package links

import (
	"net/url"
	"strings"
)

// ImportPayload unwraps the app's deep links: ghostlane://add?url=<encoded>
// and ghostlane://add/<raw list URL> (also import/). Anything else is returned
// as given.
func ImportPayload(s string) string {
	s = strings.TrimSpace(s)
	lower := strings.ToLower(s)
	if !strings.HasPrefix(lower, "ghostlane://") {
		return s
	}
	rest := s[len("ghostlane://"):]
	for _, host := range []string{"add", "import"} {
		if strings.HasPrefix(strings.ToLower(rest), host+"?") {
			q, _ := url.ParseQuery(rest[len(host)+1:])
			if v := q.Get("url"); v != "" {
				return v
			}
			return s
		}
		if strings.HasPrefix(strings.ToLower(rest), host+"/") {
			return rest[len(host)+1:]
		}
	}
	return s
}
```

```go
// cli/internal/links/import_test.go
package links

import "testing"

func TestImportPayload(t *testing.T) {
	list := "https://sub.example/sub/a/b?c=olcbox"
	for in, want := range map[string]string{
		"ghostlane://add?url=https%3A%2F%2Fsub.example%2Fsub%2Fa%2Fb%3Fc%3Dolcbox": list,
		"ghostlane://add/" + list:                                                    list,
		"ghostlane://import/" + list:                                                 list,
		list:                                                                         list,
		"olcrtc://telemost?vp8channel@r#" + key:                                      "olcrtc://telemost?vp8channel@r#" + key,
	} {
		if got := ImportPayload(in); got != want {
			t.Fatalf("ImportPayload(%q) = %q", in, got)
		}
	}
}
```

- [ ] **Step 2: Scrubber and notify (small, tested)**

```go
// cli/internal/daemon/scrub.go
package daemon

import "regexp"

var (
	hexKey   = regexp.MustCompile(`[0-9a-fA-F]{64}`)
	subToken = regexp.MustCompile(`(/sub/[^/\s?#]+/)[^\s?#/]+`)
	subOnly  = regexp.MustCompile(`(/sub/)[^\s?#/]{12,}`)
	tokenKV  = regexp.MustCompile(`(?i)(token|key|password|pass)=([^&\s]+)`)
)

// Scrub hides keys, list tokens and credentials in a log line.
func Scrub(s string) string {
	s = hexKey.ReplaceAllString(s, "<key>")
	s = subToken.ReplaceAllString(s, "$1…")
	s = subOnly.ReplaceAllString(s, "$1…")
	s = tokenKV.ReplaceAllString(s, "$1=…")
	return s
}
```

```go
// cli/internal/daemon/scrub_test.go
package daemon

import (
	"strings"
	"testing"
)

func TestScrub(t *testing.T) {
	k := strings.Repeat("ab", 32)
	in := "joined olcrtc://telemost?vp8channel@room#" + k + " from https://sub.x/sub/j7k9e/4gg96t28380ot6es?c=olcbox and https://proofkit.org/sub/abcdefghijklmnop/unified token=zzz"
	got := Scrub(in)
	for _, bad := range []string{k, "4gg96t28380ot6es", "abcdefghijklmnop", "zzz"} {
		if strings.Contains(got, bad) {
			t.Fatalf("%q leaked in %q", bad, got)
		}
	}
	if !strings.Contains(got, "/sub/j7k9e/…") || !strings.Contains(got, "/sub/…/unified") || !strings.Contains(got, "#<key>") {
		t.Fatalf("%q", got)
	}
}
```

```go
// cli/internal/daemon/notify.go
package daemon

import (
	"net"
	"os"
)

// NotifyReady tells systemd (Type=notify) the daemon serves; a no-op elsewhere.
func NotifyReady() {
	path := os.Getenv("NOTIFY_SOCKET")
	if path == "" {
		return
	}
	conn, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: path, Net: "unixgram"})
	if err != nil {
		return
	}
	defer conn.Close()
	_, _ = conn.Write([]byte("READY=1"))
}
```

```go
// cli/internal/daemon/probe.go
package daemon

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/proxy"
)

// HTTPProbe fetches probeURL through the SOCKS at socksAddr; 2xx is alive.
func HTTPProbe(probeURL string) func(ctx context.Context, socksAddr, user, pass string) error {
	return func(ctx context.Context, socksAddr, user, pass string) error {
		var auth *proxy.Auth
		if user != "" {
			auth = &proxy.Auth{User: user, Password: pass}
		}
		d, err := proxy.SOCKS5("tcp", socksAddr, auth, &net.Dialer{Timeout: 5 * time.Second})
		if err != nil {
			return err
		}
		tr := &http.Transport{DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return d.(proxy.ContextDialer).DialContext(ctx, network, addr)
		}, DisableKeepAlives: true}
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
		resp, err := tr.RoundTrip(req)
		if err != nil {
			return err
		}
		resp.Body.Close()
		if resp.StatusCode/100 != 2 {
			return fmt.Errorf("probe: HTTP %d", resp.StatusCode)
		}
		return nil
	}
}
```

- [ ] **Step 3: Failing daemon tests (fakes)**

```go
// cli/internal/daemon/daemon_test.go
package daemon

import (
	"context"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

const listURL = "https://sub.example/sub/a/b?c=olcbox"

type world struct {
	mu        sync.Mutex
	body      []byte
	fetchErr  error
	fetches   int
	log       []string // "engine:<carrier>", "engine-stop", "front:<mode>", "front-close", "routes:sync", "routes:clear"
	engineErr map[string]error // carrier → error at start
	probeErr  error
	probes    int
}

func (w *world) rec(s string) { w.mu.Lock(); w.log = append(w.log, s); w.mu.Unlock() }
func (w *world) events() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return strings.Join(w.log, " ")
}

type fakeEngine struct {
	w    *world
	addr string
}

func (e *fakeEngine) SocksAddr() string             { return e.addr }
func (e *fakeEngine) Credentials() (string, string) { return "u", "p" }
func (e *fakeEngine) State() string                 { return "running" }
func (e *fakeEngine) Stop(time.Duration) error      { e.w.rec("engine-stop"); return nil }

type fakeFront struct{ w *world }

func (f *fakeFront) Close() error { f.w.rec("front-close"); return nil }

type fakeRoutes struct{ w *world }

func (r *fakeRoutes) GlobalAddresses() ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("203.0.113.5")}, nil
}
func (r *fakeRoutes) Sync(a []netip.Addr) error {
	r.w.rec("routes:sync")
	return nil
}
func (r *fakeRoutes) Clear() error                                 { r.w.rec("routes:clear"); return nil }
func (r *fakeRoutes) CleanupStale() error                          { r.w.rec("routes:cleanup"); return nil }
func (r *fakeRoutes) WatchAddresses(context.Context, func()) error { return nil }

func newWorld(t *testing.T) (*world, *Daemon, string) {
	t.Helper()
	fixture, err := os.ReadFile("../links/testdata/partner-olcbox.txt")
	if err != nil {
		t.Fatal(err)
	}
	w := &world{body: fixture, engineErr: map[string]error{}}
	dir := t.TempDir()
	deps := Deps{
		ConfigPath: filepath.Join(dir, "config.yaml"), StateDir: dir, SocketPath: filepath.Join(dir, "s.sock"),
		Fetch: func(_ context.Context, url string) ([]byte, links.Headers, error) {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.fetches++
			if w.fetchErr != nil {
				return nil, links.Headers{}, w.fetchErr
			}
			return w.body, links.Headers{Title: "Partner", UpdateIntervalHours: 1}, nil
		},
		StartEngine: func(_ context.Context, p olcrtc.Params) (Engine, error) {
			w.mu.Lock()
			err := w.engineErr[p.Line.Provider]
			w.mu.Unlock()
			w.rec("engine:" + p.Line.Provider + "@" + p.Line.Room)
			if err != nil {
				return nil, err
			}
			return &fakeEngine{w: w, addr: "127.0.0.1:1"}, nil
		},
		StartFront: func(_ context.Context, p singbox.FrontParams) (Front, error) {
			w.rec("front:" + string(p.Mode))
			return &fakeFront{w: w}, nil
		},
		Routes: &fakeRoutes{w: w},
		Probe: func(context.Context, string, string, string) error {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.probes++
			return w.probeErr
		},
		Now: time.Now, Logf: t.Logf, UID: 977,
		ReadyTimeout: time.Second, ConfirmTimeout: 200 * time.Millisecond, ProbeInterval: 20 * time.Millisecond,
		ProbeFailures: 3, RetryMin: 20 * time.Millisecond, RetryMax: 50 * time.Millisecond,
		Version: ipc.VersionInfo{Version: "test"},
	}
	return w, New(deps), dir
}

func waitState(t *testing.T, d *Daemon, want string) *ipc.Status {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st := d.Handle(context.Background(), ipc.Request{Verb: "status"}).Status
		if st != nil && st.State == want {
			return st
		}
		time.Sleep(10 * time.Millisecond)
	}
	st := d.Handle(context.Background(), ipc.Request{Verb: "status"}).Status
	t.Fatalf("state %q never reached; now %+v", want, st)
	return nil
}

func TestAddListConnectStatusDisconnect(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if !resp.OK || !strings.Contains(resp.Message, "68") {
		t.Fatalf("%+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 68 || list.Entries[0].Index != 1 || list.Entries[0].Country != "CA" {
		t.Fatalf("%+v", list.Entries[:2])
	}
	if resp = d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	st := waitState(t, d, "up")
	if st.Line == nil || st.Line.Country != "DE" || st.Line.Carrier != "telemost" || st.Mode != "proxy" || st.Proxy == nil || st.Proxy.Socks != "127.0.0.1:1080" {
		t.Fatalf("%+v", st)
	}
	if ev := w.events(); !strings.HasPrefix(ev, "routes:cleanup engine:telemost@") || !strings.Contains(ev, "front:proxy") {
		t.Fatalf("%s", ev)
	}
	cfg, _ := store.Load(filepath.Join(d.deps.StateDir, "config.yaml"))
	if cfg.Selection == nil || cfg.Selection.Selector != "DE" {
		t.Fatalf("selection persisted: %+v", cfg.Selection)
	}
	if resp = d.Handle(ctx, ipc.Request{Verb: "disconnect"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	waitState(t, d, "idle")
	if ev := w.events(); !strings.HasSuffix(ev, "front-close engine-stop") {
		t.Fatalf("%s", ev)
	}
	cfg, _ = store.Load(filepath.Join(d.deps.StateDir, "config.yaml"))
	if cfg.Selection != nil {
		t.Fatal("selection cleared")
	}
}

func TestCarrierFailoverAndLastGood(t *testing.T) {
	w, d, dir := newWorld(t)
	w.engineErr["telemost"] = errors.New("room full")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	st := waitState(t, d, "up")
	if st.Line.Carrier != "wbstream" {
		t.Fatalf("second carrier expected: %+v", st.Line)
	}
	w.mu.Lock()
	w.probeErr = errors.New("dead")
	w.mu.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st = d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		if st.State == "up" && st.Line.Carrier == "salutejazz" {
			break
		}
		if st.State == "connecting" && st.Line != nil && st.Line.Carrier == "salutejazz" {
			w.mu.Lock()
			w.probeErr = nil
			w.mu.Unlock()
		}
		time.Sleep(10 * time.Millisecond)
	}
	if st.Line.Carrier != "salutejazz" {
		t.Fatalf("three probe failures move to the next carrier: %+v", st.Line)
	}
	lg, _ := store.LoadLastGood(dir)
	if lg[listURL+"|DE"] != st.Line.ID {
		t.Fatalf("last good %v", lg)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	w.engineErr = map[string]error{}
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	if st = waitState(t, d, "up"); st.Line.Carrier != "salutejazz" {
		t.Fatalf("the last good line goes first: %+v", st.Line)
	}
}

func TestTunModeOrder(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "FR", Mode: "tun"})
	waitState(t, d, "up")
	ev := w.events()
	if !strings.Contains(ev, "routes:sync front:tun") {
		t.Fatalf("rules before the tun: %s", ev)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	if ev = w.events(); !strings.HasSuffix(ev, "front-close engine-stop routes:clear") {
		t.Fatalf("%s", ev)
	}
}

// Review Focus 4: the daemon boots before the network.
func TestBootBeforeNetworkRetries(t *testing.T) {
	w, d, dir := newWorld(t)
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: listURL, IntervalHours: 1}}
	cfg.Selection = &store.Selection{Subscription: listURL, Selector: "GB", Mode: "proxy"}
	if err := store.Save(filepath.Join(dir, "config.yaml"), cfg); err != nil {
		t.Fatal(err)
	}
	w.fetchErr = errors.New("dial: network is unreachable")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	st := waitState(t, d, "failed")
	if !strings.Contains(st.LastError, "unreachable") {
		t.Fatalf("%+v", st)
	}
	w.mu.Lock()
	w.fetchErr = nil
	w.mu.Unlock()
	if st = waitState(t, d, "up"); st.Line.Country != "GB" {
		t.Fatalf("%+v", st.Line)
	}
}

// Review Focus 5: connect while up replaces the running line cleanly.
func TestConnectReplacesRunning(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun"})
	waitState(t, d, "up")
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "FR", Mode: "proxy"})
	deadline := time.Now().Add(5 * time.Second)
	var st *ipc.Status
	for time.Now().Before(deadline) {
		st = d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		if st.State == "up" && st.Line.Country == "FR" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	ev := w.events()
	if !strings.Contains(ev, "front-close engine-stop routes:clear engine:telemost@https://telemost.yandex.ru/j/1479206118") {
		t.Fatalf("old line torn down (rules cleared: proxy target) before the new engine: %s", ev)
	}
	if st.Mode != "proxy" {
		t.Fatalf("%+v", st)
	}
}

func TestRefreshReconnectsOnRotatedRoom(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	waitState(t, d, "up")
	w.mu.Lock()
	w.body = []byte(strings.Replace(string(w.body), "https://telemost.yandex.ru/j/5092358972", "https://telemost.yandex.ru/j/1111111111", 1))
	w.mu.Unlock()
	if resp := d.Handle(ctx, ipc.Request{Verb: "refresh"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(w.events(), "engine:telemost@https://telemost.yandex.ru/j/1111111111") {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("a rotated room reconnects: %s", w.events())
}

func TestRefreshKeepsEntriesWhenListFails(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	w.mu.Lock()
	w.fetchErr = errors.New("list: HTTP 404")
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	list := d.Handle(ctx, ipc.Request{Verb: "list"})
	if len(list.Entries) != 68 {
		t.Fatalf("cached entries stay: %d", len(list.Entries))
	}
	st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
	if len(st.Subscriptions) != 1 || !strings.Contains(st.Subscriptions[0].Error, "404") || st.Subscriptions[0].URL != "https://sub.example/sub/a/…?c=olcbox" {
		t.Fatalf("%+v", st.Subscriptions)
	}
}

func TestAddInlineLineAndConnectByIndex(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	line := "olcrtc://wbstream?vp8channel@room_q#" + strings.Repeat("cd", 32) + "$🇯🇵 JP · VP8 · WB"
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: line}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 1 || list.Entries[0].Carrier != "wbstream" {
		t.Fatalf("%+v", list.Entries)
	}
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "1", Mode: "proxy"})
	if st := waitState(t, d, "up"); st.Line.Label != "🇯🇵 JP · VP8 · WB" {
		t.Fatalf("%+v", st.Line)
	}
}

func TestRefusals(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx := context.Background()
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE"}); resp.OK || resp.Error != "no_subscription" {
		t.Fatalf("%+v", resp)
	}
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "XX", Mode: "proxy"}); resp.OK || resp.Error != "no_match" {
		t.Fatalf("%+v", resp)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "vpn"}); resp.OK || resp.Error != "bad_mode" {
		t.Fatalf("%+v", resp)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: "vless://u@h:443#x"}); resp.OK || resp.Error != "unsupported" {
		t.Fatalf("%+v", resp)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: "olcrtc://crypt1/abc"}); resp.OK || !strings.Contains(resp.Message, "crypt1") {
		t.Fatalf("%+v", resp)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "remove", Subscription: "https://sub.example/sub/a/"}); !resp.OK {
		t.Fatalf("remove by masked prefix: %+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 0 {
		t.Fatalf("%+v", list.Entries)
	}
}
```

- [ ] **Step 4: Run to verify failure** — `go test ./internal/daemon/` → FAIL (undefined `Deps`…). **Step 5: Implement**

```go
// cli/internal/daemon/daemon.go
// Package daemon is `ghostlane run`: it owns the engine, the front and the
// policy rules, keeps the config and list cache, and answers the control socket.
package daemon

import (
	"context"
	"fmt"
	"net/netip"
	"path/filepath"
	"sync"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

type Engine interface {
	SocksAddr() string
	Credentials() (string, string)
	State() string
	Stop(time.Duration) error
}

type Front interface{ Close() error }

type Routes interface {
	GlobalAddresses() ([]netip.Addr, error)
	Sync([]netip.Addr) error
	Clear() error
	CleanupStale() error
	WatchAddresses(context.Context, func()) error
}

type Deps struct {
	ConfigPath  string
	StateDir    string
	SocketPath  string
	Fetch       func(ctx context.Context, url string) ([]byte, links.Headers, error)
	StartEngine func(ctx context.Context, p olcrtc.Params) (Engine, error)
	StartFront  func(ctx context.Context, p singbox.FrontParams) (Front, error)
	Routes      Routes
	Probe       func(ctx context.Context, socksAddr, user, pass string) error
	Now         func() time.Time
	Logf        func(format string, args ...any)
	UID         int
	ResolvConf  string // "" = /etc/resolv.conf

	ReadyTimeout   time.Duration // engine WaitReady
	ConfirmTimeout time.Duration // probes after ready before the line counts as up
	ProbeInterval  time.Duration
	ProbeFailures  int
	RetryMin       time.Duration
	RetryMax       time.Duration
	Version        ipc.VersionInfo
}

type live struct {
	entry  links.Entry
	mode   string
	engine Engine
	front  Front
	user   string
	pass   string
}

type Daemon struct {
	deps Deps

	mu       sync.Mutex
	cfg      *store.Config
	lastGood map[string]string
	subErr   map[string]string
	state    string
	lastErr  string
	since    time.Time
	sel      *store.Selection
	cur      *live
	cancel   context.CancelFunc
	runCtx   context.Context
}

func New(d Deps) *Daemon {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.Logf == nil {
		d.Logf = func(string, ...any) {}
	}
	if d.ResolvConf == "" {
		d.ResolvConf = "/etc/resolv.conf"
	}
	return &Daemon{deps: d, state: "idle", subErr: map[string]string{}, lastGood: map[string]string{}}
}

func (d *Daemon) logf(format string, args ...any) {
	d.deps.Logf("%s", Scrub(fmt.Sprintf(format, args...)))
}

// Run serves until ctx ends. The control socket is optional (tests call Handle).
func (d *Daemon) Run(ctx context.Context) error {
	d.mu.Lock()
	d.runCtx = ctx
	d.mu.Unlock()
	if err := d.deps.Routes.CleanupStale(); err != nil {
		d.logf("stale rules: %v", err)
	}
	cfg, err := store.Load(d.deps.ConfigPath)
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	lg, err := store.LoadLastGood(d.deps.StateDir)
	if err != nil {
		d.logf("last-good: %v", err)
		lg = map[string]string{}
	}
	d.mu.Lock()
	d.cfg, d.lastGood = cfg, lg
	sel := cfg.Selection
	d.mu.Unlock()

	var listener interface{ Close() error }
	if d.deps.SocketPath != "" {
		l, err := ipc.Listen(d.deps.SocketPath, 0o660)
		if err != nil {
			return fmt.Errorf("control socket: %w", err)
		}
		listener = l
		go func() { _ = ipc.Serve(ctx, l, d.Handle) }()
	}
	NotifyReady()
	go d.refreshLoop(ctx)
	if sel != nil {
		d.startConnect(*sel)
	}
	<-ctx.Done()
	d.stopConnect()
	if listener != nil {
		_ = listener.Close()
	}
	return nil
}

func (d *Daemon) deviceIDPath() string { return filepath.Join(d.deps.StateDir, "device-id") }
```

```go
// cli/internal/daemon/connect.go
package daemon

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

// startConnect cancels any running connection and starts the loop for sel.
func (d *Daemon) startConnect(sel store.Selection) {
	d.stopConnect()
	d.mu.Lock()
	ctx, cancel := context.WithCancel(d.runCtx)
	d.cancel = cancel
	d.sel = &sel
	d.state, d.lastErr = "connecting", ""
	d.mu.Unlock()
	go d.connectLoop(ctx, sel)
}

// stopConnect ends the loop and tears the connection down, synchronously.
func (d *Daemon) stopConnect() {
	d.mu.Lock()
	cancel := d.cancel
	d.cancel = nil
	d.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	d.mu.Lock()
	for d.cur != nil {
		d.mu.Unlock()
		time.Sleep(5 * time.Millisecond)
		d.mu.Lock()
	}
	d.state = "idle"
	d.sel = nil
	d.mu.Unlock()
}

func (d *Daemon) setState(state, lastErr string, entry *links.Entry) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.state = state
	if lastErr != "" {
		d.lastErr = lastErr
	}
	if state == "up" {
		d.since = d.deps.Now()
		d.lastErr = ""
	}
	d.pending = entry
}

func (d *Daemon) connectLoop(ctx context.Context, sel store.Selection) {
	backoff := d.deps.RetryMin
	for ctx.Err() == nil {
		entries, err := d.entriesFor(ctx, sel.Subscription, false)
		if err != nil {
			d.setState("failed", err.Error(), nil)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, d.deps.RetryMax)
			continue
		}
		cands, err := links.Select(entries, sel.Selector)
		if err != nil {
			d.setState("failed", err.Error(), nil)
			return // a selector that matches nothing is not retried
		}
		cands = d.orderCandidates(sel, onlyOlcrtc(cands))
		if len(cands) == 0 {
			d.setState("failed", "no olcRTC line matches "+sel.Selector, nil)
			return
		}
		anyUp := false
		for _, cand := range cands {
			if ctx.Err() != nil {
				return
			}
			d.setState("connecting", "", &cand)
			l, err := d.bringUp(ctx, cand, sel.Mode)
			if err != nil {
				d.logf("%s: %v", cand.Label, err)
				d.setState("connecting", err.Error(), &cand)
				continue
			}
			anyUp = true
			backoff = d.deps.RetryMin
			d.markUp(sel, l)
			reason := d.supervise(ctx, l)
			d.tearDown(l)
			if ctx.Err() != nil {
				return
			}
			d.logf("%s: %s, moving on", cand.Label, reason)
		}
		if !anyUp {
			d.setState("failed", d.lastErrLocked(), nil)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, d.deps.RetryMax)
		}
	}
}

func (d *Daemon) lastErrLocked() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.lastErr
}

func onlyOlcrtc(in []links.Entry) []links.Entry {
	var out []links.Entry
	for _, e := range in {
		if e.Kind == links.KindOlcrtc {
			out = append(out, e)
		}
	}
	return out
}

func lastGoodKey(sel store.Selection) string { return sel.Subscription + "|" + sel.Selector }

func (d *Daemon) orderCandidates(sel store.Selection, cands []links.Entry) []links.Entry {
	d.mu.Lock()
	id := d.lastGood[lastGoodKey(sel)]
	d.mu.Unlock()
	for i, c := range cands {
		if c.ID == id && i > 0 {
			out := append([]links.Entry{c}, cands[:i]...)
			return append(out, cands[i+1:]...)
		}
	}
	return cands
}

func (d *Daemon) bringUp(ctx context.Context, e links.Entry, mode string) (*live, error) {
	port, err := olcrtc.FreePort()
	if err != nil {
		return nil, err
	}
	user, pass := olcrtc.RandomCredentials()
	eng, err := d.deps.StartEngine(ctx, olcrtc.Params{
		Line: *e.Olcrtc, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: user, SocksPass: pass,
		DNS: olcrtc.HostResolvers(d.deps.ResolvConf), DirectRules: olcrtc.PrivateDirectRules, DeviceIDPath: d.deviceIDPath(),
	}, )
	if err != nil {
		return nil, err
	}
	if err := d.confirm(ctx, eng); err != nil {
		_ = eng.Stop(5 * time.Second)
		return nil, err
	}
	if mode == "tun" {
		addrs, err := d.deps.Routes.GlobalAddresses()
		if err == nil {
			err = d.deps.Routes.Sync(addrs)
		}
		if err != nil {
			_ = eng.Stop(5 * time.Second)
			return nil, fmt.Errorf("policy rules: %w", err)
		}
	}
	d.mu.Lock()
	proxy := d.cfg.Proxy
	d.mu.Unlock()
	fp := singbox.FrontParams{Mode: singbox.Mode(mode), UpstreamAddr: eng.SocksAddr(), UpstreamUser: user, UpstreamPass: pass,
		ProxyListen: proxy.Listen, ProxyPort: proxy.Port, ProxyUser: proxy.User, ProxyPass: proxy.Pass,
		ExcludeUID: d.deps.UID, InterfaceName: singbox.TunName}
	front, err := d.deps.StartFront(ctx, fp)
	if err != nil {
		if mode == "tun" {
			_ = d.deps.Routes.Clear()
		}
		_ = eng.Stop(5 * time.Second)
		return nil, fmt.Errorf("front: %w", err)
	}
	return &live{entry: e, mode: mode, engine: eng, front: front, user: user, pass: pass}, nil
}

// confirm probes through the engine until it answers or ConfirmTimeout passes:
// WaitReady says the SOCKS listens, not that the room carries traffic.
func (d *Daemon) confirm(ctx context.Context, eng Engine) error {
	deadline := d.deps.Now().Add(d.deps.ConfirmTimeout)
	var last error
	for {
		last = d.deps.Probe(ctx, eng.SocksAddr(), eng.Credentials())
		if last == nil {
			return nil
		}
		if ctx.Err() != nil || !d.deps.Now().Before(deadline) {
			return fmt.Errorf("room carries no traffic: %w", last)
		}
		if !sleepCtx(ctx, min(3*time.Second, d.deps.ConfirmTimeout/4)) {
			return ctx.Err()
		}
	}
}

func (d *Daemon) markUp(sel store.Selection, l *live) {
	d.mu.Lock()
	d.cur = l
	d.state, d.lastErr, d.since, d.pending = "up", "", d.deps.Now(), nil
	d.lastGood[lastGoodKey(sel)] = l.entry.ID
	lg := map[string]string{}
	for k, v := range d.lastGood {
		lg[k] = v
	}
	d.mu.Unlock()
	if err := store.SaveLastGood(d.deps.StateDir, lg); err != nil {
		d.logf("last-good: %v", err)
	}
	d.logf("up: %s over %s", l.entry.Label, l.entry.Olcrtc.Provider)
}

// supervise probes every ProbeInterval; ProbeFailures in a row end the line.
func (d *Daemon) supervise(ctx context.Context, l *live) string {
	if l.mode == "tun" {
		_ = d.deps.Routes.WatchAddresses(ctx, func() {
			if addrs, err := d.deps.Routes.GlobalAddresses(); err == nil {
				if err := d.deps.Routes.Sync(addrs); err != nil {
					d.logf("policy rules: %v", err)
				}
			}
		})
	}
	failures := 0
	t := time.NewTicker(d.deps.ProbeInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return "stopped"
		case <-t.C:
			if err := d.deps.Probe(ctx, l.engine.SocksAddr(), l.user, l.pass); err != nil {
				failures++
				d.logf("probe %d/%d failed: %v", failures, d.deps.ProbeFailures, err)
				if failures >= d.deps.ProbeFailures {
					return fmt.Sprintf("%d probes failed", failures)
				}
			} else {
				failures = 0
			}
		}
	}
}

func (d *Daemon) tearDown(l *live) {
	_ = l.front.Close()
	_ = l.engine.Stop(5 * time.Second)
	if l.mode == "tun" {
		_ = d.deps.Routes.Clear()
	}
	d.mu.Lock()
	if d.cur == l {
		d.cur = nil
	}
	d.mu.Unlock()
}

func sleepCtx(ctx context.Context, dur time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(dur):
		return true
	}
}

// entriesFor returns the parsed entries of a subscription from the cache,
// fetching when there is no cache or force is set. An inline: subscription is
// its own body.
func (d *Daemon) entriesFor(ctx context.Context, subURL string, force bool) ([]links.Entry, error) {
	if body, ok := inlineBody(subURL); ok {
		return links.Entries(subURL, links.DecodeBody(body)), nil
	}
	cache, err := store.LoadCache(d.deps.StateDir, subURL)
	if err != nil {
		return nil, err
	}
	if cache == nil || force {
		cache, err = d.fetchInto(ctx, subURL)
		if err != nil {
			return nil, err
		}
	}
	return links.Entries(subURL, links.DecodeBody(cache.Body)), nil
}

func (d *Daemon) fetchInto(ctx context.Context, subURL string) (*store.Cache, error) {
	body, headers, err := d.deps.Fetch(ctx, subURL)
	d.mu.Lock()
	if err != nil {
		d.subErr[subURL] = err.Error()
	} else {
		delete(d.subErr, subURL)
	}
	d.mu.Unlock()
	if err != nil {
		return nil, err
	}
	c := &store.Cache{Body: body, Headers: headers, FetchedAt: d.deps.Now()}
	if err := store.SaveCache(d.deps.StateDir, subURL, c); err != nil {
		return nil, err
	}
	d.mu.Lock()
	for i := range d.cfg.Subscriptions {
		if d.cfg.Subscriptions[i].URL == subURL {
			d.cfg.Subscriptions[i].Title = headers.Title
			d.cfg.Subscriptions[i].IntervalHours = headers.UpdateIntervalHours
		}
	}
	cfg := *d.cfg
	d.mu.Unlock()
	_ = store.Save(d.deps.ConfigPath, &cfg)
	return c, nil
}

const inlinePrefix = "inline:"

func inlineBody(subURL string) ([]byte, bool) {
	if len(subURL) > len(inlinePrefix) && subURL[:len(inlinePrefix)] == inlinePrefix {
		return []byte(subURL[len(inlinePrefix):]), true
	}
	return nil, false
}

// refreshLoop refreshes due lists every minute and reconnects when the running
// line's room or key changed.
func (d *Daemon) refreshLoop(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			d.refresh(ctx, false)
		}
	}
}

func (d *Daemon) refresh(ctx context.Context, force bool) {
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	for _, s := range subs {
		if _, inline := inlineBody(s.URL); inline {
			continue
		}
		cache, _ := store.LoadCache(d.deps.StateDir, s.URL)
		hours := s.IntervalHours
		if hours <= 0 {
			hours = 24
		}
		if !force && cache != nil && d.deps.Now().Before(cache.FetchedAt.Add(time.Duration(hours)*time.Hour)) {
			continue
		}
		var old []links.Entry
		if cache != nil {
			old = links.Entries(s.URL, links.DecodeBody(cache.Body))
		}
		fresh, err := d.fetchInto(ctx, s.URL)
		if err != nil {
			d.logf("refresh %s: %v", store.MaskURL(s.URL), err)
			continue
		}
		d.afterRefresh(s.URL, old, links.Entries(s.URL, links.DecodeBody(fresh.Body)))
	}
}

func (d *Daemon) afterRefresh(subURL string, old, fresh []links.Entry) {
	d.mu.Lock()
	cur, sel := d.cur, d.sel
	d.mu.Unlock()
	if cur == nil || sel == nil || sel.Subscription != subURL {
		return
	}
	for _, e := range fresh {
		if e.ID != cur.entry.ID || e.Olcrtc == nil {
			continue
		}
		if e.Olcrtc.Room != cur.entry.Olcrtc.Room || e.Olcrtc.Key != cur.entry.Olcrtc.Key {
			d.logf("%s: room or key rotated, reconnecting", e.Label)
			d.startConnect(*sel)
		}
		return
	}
	_ = old
	d.mu.Lock()
	d.lastErr = "the connected line is no longer in the list; still connected"
	d.mu.Unlock()
}

var errNoSubscription = errors.New("no subscription holds that selector")
```
Add to `Daemon` the field `pending *links.Entry` (the line being tried, shown by status while connecting).

```go
// cli/internal/daemon/handlers.go
package daemon

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

func (d *Daemon) Handle(ctx context.Context, req ipc.Request) ipc.Response {
	switch req.Verb {
	case "version":
		v := d.deps.Version
		return ipc.Response{OK: true, Version: &v}
	case "status":
		return ipc.Response{OK: true, Status: d.status()}
	case "list":
		return d.list(ctx, req.Subscription)
	case "add":
		return d.add(ctx, req.Source)
	case "remove":
		return d.remove(req.Subscription)
	case "refresh":
		d.refresh(ctx, true)
		return ipc.Response{OK: true, Message: "refreshed"}
	case "connect":
		return d.connect(ctx, req)
	case "disconnect":
		d.stopConnect()
		d.mu.Lock()
		d.cfg.Selection = nil
		cfg := *d.cfg
		d.mu.Unlock()
		if err := store.Save(d.deps.ConfigPath, &cfg); err != nil {
			return ipc.Fail("io", err.Error())
		}
		return ipc.Response{OK: true, Message: "disconnected"}
	}
	return ipc.Fail("bad_verb", "unknown verb "+req.Verb)
}

func (d *Daemon) ensureConfig() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.cfg == nil {
		d.cfg = store.Defaults()
	}
}

func entryView(i int, e links.Entry) ipc.EntryView {
	v := ipc.EntryView{Index: i + 1, ID: e.ID, Label: e.Label, Country: e.Country, Kind: e.Kind.String(), Problem: e.Problem}
	if e.Olcrtc != nil {
		v.Carrier, v.Transport = e.Olcrtc.Provider, e.Olcrtc.Transport
	}
	return v
}

func (d *Daemon) status() *ipc.Status {
	d.mu.Lock()
	defer d.mu.Unlock()
	st := &ipc.Status{State: d.state, LastError: d.lastErr}
	if d.sel != nil {
		st.Mode, st.Selector = d.sel.Mode, d.sel.Selector
	}
	if d.cur != nil {
		v := entryView(0, d.cur.entry)
		v.Index = 0
		st.Line = &v
		st.Since = d.since.UTC().Format(time.RFC3339)
		if d.cur.mode == "proxy" {
			addr := d.cfg.Proxy.Listen + ":" + strconv.Itoa(d.cfg.Proxy.Port)
			st.Proxy = &ipc.ProxyView{Socks: addr, HTTP: addr}
		}
	} else if d.pending != nil {
		v := entryView(0, *d.pending)
		v.Index = 0
		st.Line = &v
	}
	if d.cfg != nil {
		for _, s := range d.cfg.Subscriptions {
			sv := ipc.SubscriptionView{URL: store.MaskURL(s.URL), Title: s.Title, IntervalHours: s.IntervalHours, Error: d.subErr[s.URL]}
			if _, inline := inlineBody(s.URL); inline {
				sv.URL = "inline line"
			} else if c, _ := store.LoadCache(d.deps.StateDir, s.URL); c != nil {
				sv.FetchedAt = c.FetchedAt.UTC().Format(time.RFC3339)
				sv.NextRefresh = c.FetchedAt.Add(time.Duration(max(s.IntervalHours, 1)) * time.Hour).UTC().Format(time.RFC3339)
				sv.UserInfo = c.Headers.UserInfo
			}
			st.Subscriptions = append(st.Subscriptions, sv)
		}
	}
	return st
}

func (d *Daemon) list(ctx context.Context, only string) ipc.Response {
	d.ensureConfig()
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	var out []ipc.EntryView
	for _, s := range subs {
		if only != "" && !matchesSub(s.URL, only) {
			continue
		}
		entries, err := d.entriesFor(ctx, s.URL, false)
		if err != nil {
			d.logf("list %s: %v", store.MaskURL(s.URL), err)
			continue
		}
		for i, e := range entries {
			out = append(out, entryView(i, e))
		}
	}
	return ipc.Response{OK: true, Entries: out}
}

func matchesSub(url, needle string) bool {
	return url == needle || strings.HasPrefix(url, needle) || strings.HasPrefix(store.MaskURL(url), needle)
}

func (d *Daemon) add(ctx context.Context, source string) ipc.Response {
	d.ensureConfig()
	payload := links.ImportPayload(source)
	var sub store.Subscription
	var msg string
	switch {
	case strings.HasPrefix(payload, "https://") || strings.HasPrefix(payload, "http://"):
		sub = store.Subscription{URL: payload, AddedAt: d.deps.Now()}
		d.mu.Lock()
		for _, s := range d.cfg.Subscriptions {
			if s.URL == payload {
				d.mu.Unlock()
				return ipc.Response{OK: true, Message: "already added"}
			}
		}
		d.cfg.Subscriptions = append(d.cfg.Subscriptions, sub)
		d.mu.Unlock()
		c, err := d.fetchInto(ctx, payload)
		if err != nil {
			d.mu.Lock()
			d.cfg.Subscriptions = d.cfg.Subscriptions[:len(d.cfg.Subscriptions)-1]
			d.mu.Unlock()
			return ipc.Fail("fetch", err.Error())
		}
		entries := links.Entries(payload, links.DecodeBody(c.Body))
		groups := links.GroupByCountry(entries)
		var countries []string
		for _, g := range groups {
			countries = append(countries, g.Country)
		}
		usable := len(onlyOlcrtc(entries))
		msg = fmt.Sprintf("%s: %d entries (%d usable now); countries: %s", firstNonEmpty(c.Headers.Title, store.MaskURL(payload)), len(entries), usable, strings.Join(countries, " "))
	case strings.HasPrefix(payload, "olcrtc://"):
		line, err := links.ParseOlcrtc(payload)
		if err != nil {
			return ipc.Fail("bad_line", err.Error())
		}
		sub = store.Subscription{URL: inlinePrefix + payload, Title: line.Label, AddedAt: d.deps.Now()}
		d.mu.Lock()
		d.cfg.Subscriptions = append(d.cfg.Subscriptions, sub)
		d.mu.Unlock()
		msg = "added " + line.Label
	default:
		scheme, _, _ := strings.Cut(payload, "://")
		return ipc.Fail("unsupported", scheme+":// lines are not supported by this version; add a list URL or an olcrtc:// line")
	}
	d.mu.Lock()
	cfg := *d.cfg
	d.mu.Unlock()
	if err := store.Save(d.deps.ConfigPath, &cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	return ipc.Response{OK: true, Message: msg}
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func (d *Daemon) remove(needle string) ipc.Response {
	d.ensureConfig()
	d.mu.Lock()
	kept := d.cfg.Subscriptions[:0:0]
	removed := 0
	var dropSelection bool
	for _, s := range d.cfg.Subscriptions {
		if matchesSub(s.URL, needle) || s.Title == needle {
			removed++
			if d.sel != nil && d.sel.Subscription == s.URL {
				dropSelection = true
			}
			continue
		}
		kept = append(kept, s)
	}
	d.cfg.Subscriptions = kept
	d.mu.Unlock()
	if removed == 0 {
		return ipc.Fail("no_match", "no subscription matches "+needle)
	}
	if dropSelection {
		d.stopConnect()
		d.mu.Lock()
		d.cfg.Selection = nil
		d.mu.Unlock()
	}
	d.mu.Lock()
	cfg := *d.cfg
	d.mu.Unlock()
	if err := store.Save(d.deps.ConfigPath, &cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	return ipc.Response{OK: true, Message: fmt.Sprintf("removed %d", removed)}
}

func (d *Daemon) connect(ctx context.Context, req ipc.Request) ipc.Response {
	d.ensureConfig()
	mode := req.Mode
	if mode == "" {
		mode = "proxy"
	}
	if mode != "tun" && mode != "proxy" {
		return ipc.Fail("bad_mode", "mode must be tun or proxy")
	}
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	if len(subs) == 0 {
		return ipc.Fail("no_subscription", "add a list first: ghostlane add <url>")
	}
	var chosen string
	var lastErr error
	for _, s := range subs {
		if req.Subscription != "" && !matchesSub(s.URL, req.Subscription) {
			continue
		}
		entries, err := d.entriesFor(ctx, s.URL, false)
		if err != nil {
			lastErr = err
			continue
		}
		if _, err := links.Select(entries, req.Selector); err == nil {
			chosen = s.URL
			break
		} else {
			lastErr = err
		}
	}
	if chosen == "" {
		if lastErr == nil {
			lastErr = errNoSubscription
		}
		if errors.Is(lastErr, links.ErrNoMatch) {
			return ipc.Fail("no_match", lastErr.Error())
		}
		return ipc.Fail("no_subscription", lastErr.Error())
	}
	sel := store.Selection{Subscription: chosen, Selector: req.Selector, Mode: mode}
	d.mu.Lock()
	d.cfg.Selection = &sel
	cfg := *d.cfg
	d.mu.Unlock()
	if err := store.Save(d.deps.ConfigPath, &cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	d.startConnect(sel)
	return ipc.Response{OK: true, Message: fmt.Sprintf("connecting to %s in %s mode", req.Selector, mode)}
}
```

Notes for the implementer: `runCtx` is nil until `Run` — `Handle` in tests always runs after `Run` started; make `startConnect` fall back to `context.Background()` when `runCtx` is nil so a `connect` before `Run` does not panic (the test `TestRefusals` calls `Handle` without `Run`; `ensureConfig` covers the nil config). `stopConnect` waits for `d.cur == nil`: `tearDown` clears it, and the loop returns on ctx cancel after `tearDown`; a loop that is between candidates has `cur == nil` already.

- [ ] **Step 6: Run** — `go test -race ./internal/daemon/ ./internal/links/` → PASS. Race-check is mandatory here: the daemon is concurrent by nature. If `TestConnectReplacesRunning` flakes on ordering, the fix is in `stopConnect` (it must not return before `tearDown` ran), never in the test.
- [ ] **Step 7: Commit** — `git add cli/internal/daemon cli/internal/links/import.go cli/internal/links/import_test.go && git commit -m "feat(cli): daemon — connect loop with carrier failover, probes, refresh, control verbs"`

---

### Task 10: The `ghostlane` command

**Files:**
- Modify: `cli/cmd/ghostlane/main.go` (replace Task 1's stub)
- Create: `cli/cmd/ghostlane/commands.go`, `cli/cmd/ghostlane/run.go`, `cli/cmd/ghostlane/main_test.go`

**Interfaces:**
- Consumes: `daemon.New/Run/Deps`, `ipc.Call`, `olcrtc.Start`, `singbox.Start`, `routes.New`, `daemon.HTTPProbe`.
- Produces: `func run(args []string, stdout, stderr io.Writer) int` (exit codes 0 ok, 1 failed, 2 usage, 3 daemon unreachable).

- [ ] **Step 1: Failing tests**

```go
// cli/cmd/ghostlane/main_test.go
package main

import (
	"bytes"
	"context"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
)

func fakeDaemon(t *testing.T, h ipc.Handler) string {
	t.Helper()
	sock := filepath.Join(t.TempDir(), "s.sock")
	l, err := ipc.Listen(sock, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { _ = ipc.Serve(ctx, l, h) }()
	return sock
}

func TestUsageAndExitCodes(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run(nil, &out, &errb); code != 2 || !strings.Contains(errb.String(), "usage") {
		t.Fatalf("%d %s", code, errb.String())
	}
	if code := run([]string{"frobnicate"}, &out, &errb); code != 2 {
		t.Fatalf("%d", code)
	}
	errb.Reset()
	if code := run([]string{"status", "--socket", filepath.Join(t.TempDir(), "none.sock")}, &out, &errb); code != 3 || !strings.Contains(errb.String(), "not running") {
		t.Fatalf("%d %s", code, errb.String())
	}
}

func TestStatusAndListRendering(t *testing.T) {
	sock := fakeDaemon(t, func(_ context.Context, r ipc.Request) ipc.Response {
		switch r.Verb {
		case "status":
			return ipc.Response{OK: true, Status: &ipc.Status{State: "up", Mode: "proxy", Selector: "DE", Since: "2026-09-30T10:00:00Z",
				Line: &ipc.EntryView{Label: "🇩🇪 DE · VP8", Carrier: "telemost", Country: "DE"}, Proxy: &ipc.ProxyView{Socks: "127.0.0.1:1080", HTTP: "127.0.0.1:1080"}}}
		case "list":
			return ipc.Response{OK: true, Entries: []ipc.EntryView{{Index: 1, Label: "🇩🇪 DE · VP8", Country: "DE", Kind: "olcrtc", Carrier: "telemost"},
				{Index: 2, Label: "x", Kind: "vless", Problem: "VLESS lines come in the next version"}}}
		case "connect":
			return ipc.Response{OK: true, Message: "connecting to " + r.Selector + " in " + r.Mode + " mode"}
		}
		return ipc.Fail("bad_verb", r.Verb)
	})
	var out, errb bytes.Buffer
	if code := run([]string{"status", "--socket", sock}, &out, &errb); code != 0 {
		t.Fatalf("%d %s", code, errb.String())
	}
	for _, want := range []string{"up", "telemost", "DE", "socks5://127.0.0.1:1080", "http_proxy=http://127.0.0.1:1080"} {
		if !strings.Contains(out.String(), want) {
			t.Fatalf("status lacks %q:\n%s", want, out.String())
		}
	}
	out.Reset()
	if code := run([]string{"status", "--socket", sock, "--json"}, &out, &errb); code != 0 || !strings.Contains(out.String(), `"state": "up"`) {
		t.Fatalf("%d %s", code, out.String())
	}
	out.Reset()
	if code := run([]string{"list", "--socket", sock}, &out, &errb); code != 0 || !strings.Contains(out.String(), "next version") || !strings.Contains(out.String(), " 1 ") {
		t.Fatalf("%d\n%s", code, out.String())
	}
	out.Reset()
	if code := run([]string{"connect", "DE", "--tun", "--socket", sock}, &out, &errb); code != 0 || !strings.Contains(out.String(), "tun mode") {
		t.Fatalf("%d %s", code, out.String())
	}
	if code := run([]string{"connect", "--socket", sock}, &out, &errb); code != 2 {
		t.Fatal("connect needs a selector")
	}
}
```

- [ ] **Step 2: Run to verify failure**, then **Step 3: Implement**

```go
// cli/cmd/ghostlane/main.go
// ghostlane is GPL-3.0-or-later: it links sing-box. See cli/LICENSE.
package main

import (
	"os"
)

var (
	version    = "dev"
	enginePin  = "unknown"
	singboxPin = "unknown"
)

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }
```

```go
// cli/cmd/ghostlane/commands.go
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
)

const usage = `usage: ghostlane <command> [flags]

  add <list-url | ghostlane:// | olcrtc://>   add a subscription or one room line
  list [--json]                               the lines of every subscription
  connect <selector> [--tun | --proxy]        selector: country (DE), label, or index from list
  disconnect
  status [--json]
  refresh                                     re-fetch every list now
  remove <subscription>                       by URL, masked URL prefix, or title
  version [--json]
  run                                         the daemon (systemd runs this)

flags: --socket <path>  (default /run/ghostlane/ghostlane.sock)
`

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "run":
		return runDaemon(rest, stdout, stderr)
	case "version":
		fs := flag.NewFlagSet("version", flag.ContinueOnError)
		asJSON := fs.Bool("json", false, "")
		if err := fs.Parse(rest); err != nil {
			return 2
		}
		v := ipc.VersionInfo{Version: version, Engine: enginePin, SingBox: singboxPin}
		if *asJSON {
			return printJSON(stdout, v)
		}
		fmt.Fprintf(stdout, "ghostlane %s\nengine %s\nsing-box %s\nrelease signing key sha256 %s\n", v.Version, v.Engine, v.SingBox, releasePubKeyFingerprint())
		return 0
	case "add", "list", "connect", "disconnect", "status", "refresh", "remove":
		return client(cmd, rest, stdout, stderr)
	case "-h", "--help", "help":
		fmt.Fprint(stdout, usage)
		return 0
	}
	fmt.Fprintf(stderr, "unknown command %q\n%s", cmd, usage)
	return 2
}

func client(cmd string, args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet(cmd, flag.ContinueOnError)
	fs.SetOutput(stderr)
	socket := fs.String("socket", ipc.DefaultSocketPath, "control socket")
	asJSON := fs.Bool("json", false, "machine-readable output")
	tun := fs.Bool("tun", false, "route the whole machine (needs the service's CAP_NET_ADMIN)")
	proxyMode := fs.Bool("proxy", false, "local SOCKS5/HTTP proxy only")
	sub := fs.String("subscription", "", "restrict to one subscription")
	// flags may follow the positional argument: `connect DE --tun`
	var positional []string
	for len(args) > 0 {
		if err := fs.Parse(args); err != nil {
			return 2
		}
		args = fs.Args()
		if len(args) > 0 {
			positional = append(positional, args[0])
			args = args[1:]
		}
	}
	req := ipc.Request{Verb: cmd, Subscription: *sub}
	switch cmd {
	case "add", "remove", "connect":
		if len(positional) != 1 {
			fmt.Fprintf(stderr, "%s needs exactly one argument\n%s", cmd, usage)
			return 2
		}
		if cmd == "add" {
			req.Source = positional[0]
		} else if cmd == "remove" {
			req.Subscription = positional[0]
		} else {
			req.Selector = positional[0]
			switch {
			case *tun && *proxyMode:
				fmt.Fprintln(stderr, "pick one of --tun and --proxy")
				return 2
			case *tun:
				req.Mode = "tun"
			case *proxyMode:
				req.Mode = "proxy"
			}
		}
	default:
		if len(positional) != 0 {
			fmt.Fprintf(stderr, "%s takes no argument\n", cmd)
			return 2
		}
	}
	resp, err := ipc.Call(context.Background(), *socket, req)
	if err != nil {
		fmt.Fprintln(stderr, err)
		if errors.Is(err, ipc.ErrDaemonDown) {
			return 3
		}
		return 1
	}
	if !resp.OK {
		fmt.Fprintf(stderr, "%s: %s\n", resp.Error, resp.Message)
		return 1
	}
	if *asJSON {
		return printJSON(stdout, resp)
	}
	switch cmd {
	case "status":
		printStatus(stdout, resp.Status)
	case "list":
		printList(stdout, resp.Entries)
	default:
		if resp.Message != "" {
			fmt.Fprintln(stdout, resp.Message)
		}
	}
	return 0
}

func printJSON(w io.Writer, v any) int {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		return 1
	}
	return 0
}

func printStatus(w io.Writer, st *ipc.Status) {
	if st == nil {
		fmt.Fprintln(w, "no status")
		return
	}
	fmt.Fprintf(w, "State:     %s\n", st.State)
	if st.Selector != "" {
		fmt.Fprintf(w, "Selection: %s (%s mode)\n", st.Selector, st.Mode)
	}
	if st.Line != nil {
		fmt.Fprintf(w, "Line:      %s", st.Line.Label)
		if st.Line.Carrier != "" {
			fmt.Fprintf(w, " via %s", st.Line.Carrier)
		}
		fmt.Fprintln(w)
	}
	if st.Since != "" {
		fmt.Fprintf(w, "Since:     %s\n", st.Since)
	}
	if st.LastError != "" {
		fmt.Fprintf(w, "Last error: %s\n", st.LastError)
	}
	if st.Proxy != nil {
		fmt.Fprintf(w, "Proxy:     socks5://%s  http://%s\n", st.Proxy.Socks, st.Proxy.HTTP)
		fmt.Fprintf(w, "  export ALL_PROXY=socks5h://%s\n  export http_proxy=http://%s https_proxy=http://%s\n", st.Proxy.Socks, st.Proxy.HTTP, st.Proxy.HTTP)
	}
	for _, s := range st.Subscriptions {
		fmt.Fprintf(w, "List:      %s", s.URL)
		if s.Title != "" {
			fmt.Fprintf(w, "  (%s)", s.Title)
		}
		if s.NextRefresh != "" {
			fmt.Fprintf(w, "  next refresh %s", s.NextRefresh)
		}
		if s.UserInfo != nil && s.UserInfo.Total > 0 {
			fmt.Fprintf(w, "  used %.1f of %.1f GB", float64(s.UserInfo.Upload+s.UserInfo.Download)/1e9, float64(s.UserInfo.Total)/1e9)
		}
		if s.Error != "" {
			fmt.Fprintf(w, "  ERROR: %s", s.Error)
		}
		fmt.Fprintln(w)
	}
}

func printList(w io.Writer, entries []ipc.EntryView) {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "#\tCOUNTRY\tKIND\tCARRIER\tLABEL\tNOTE")
	for _, e := range entries {
		fmt.Fprintf(tw, " %d \t%s\t%s\t%s\t%s\t%s\n", e.Index, e.Country, e.Kind, e.Carrier, e.Label, e.Problem)
	}
	_ = tw.Flush()
	if len(entries) == 0 {
		fmt.Fprintln(w, "(no lines; ghostlane add <url>)")
	}
	_ = strings.TrimSpace
}
```

```go
// cli/cmd/ghostlane/run.go
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/daemon"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/routes"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

func runDaemon(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	fs.SetOutput(stderr)
	socket := fs.String("socket", ipc.DefaultSocketPath, "control socket path")
	stateDir := fs.String("state-dir", "/var/lib/ghostlane", "state directory (config.yaml lives here)")
	subscription := fs.String("subscription", "", "add this list at start (containers)")
	selector := fs.String("connect", "", "connect this selector at start (containers)")
	mode := fs.String("mode", "proxy", "tun | proxy, with --connect")
	probeURL := fs.String("probe-url", "http://cp.cloudflare.com/generate_204", "liveness probe through the tunnel")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	logger := log.New(stderr, "", log.LstdFlags)
	logf := func(format string, a ...any) { logger.Printf("%s", daemon.Scrub(fmt.Sprintf(format, a...))) }
	olcrtc.SetLogSink(func(s string) { logf("engine: %s", s) })

	if *subscription != "" {
		cfgPath := filepath.Join(*stateDir, "config.yaml")
		cfg, err := store.Load(cfgPath)
		if err != nil {
			fmt.Fprintln(stderr, err)
			return 1
		}
		found := false
		for _, s := range cfg.Subscriptions {
			found = found || s.URL == *subscription
		}
		if !found {
			cfg.Subscriptions = append(cfg.Subscriptions, store.Subscription{URL: *subscription, AddedAt: time.Now(), IntervalHours: 24})
		}
		if *selector != "" {
			cfg.Selection = &store.Selection{Subscription: *subscription, Selector: *selector, Mode: *mode}
		}
		if err := store.Save(cfgPath, cfg); err != nil {
			fmt.Fprintln(stderr, err)
			return 1
		}
	}

	httpClient := &http.Client{Timeout: 30 * time.Second}
	ua := links.UserAgentPrefix + version + " (linux)"
	d := daemon.New(daemon.Deps{
		ConfigPath: filepath.Join(*stateDir, "config.yaml"), StateDir: *stateDir, SocketPath: *socket,
		Fetch: func(ctx context.Context, url string) ([]byte, links.Headers, error) {
			return links.Fetch(ctx, httpClient, url, ua)
		},
		StartEngine: func(ctx context.Context, p olcrtc.Params) (daemon.Engine, error) {
			return olcrtc.Start(ctx, p, 60*time.Second)
		},
		StartFront: func(ctx context.Context, p singbox.FrontParams) (daemon.Front, error) {
			return singbox.Start(ctx, p)
		},
		Routes: routes.New(singbox.TunName),
		Probe:  daemon.HTTPProbe(*probeURL),
		Logf:   logf,
		UID:    os.Getuid(),
		ReadyTimeout: 60 * time.Second, ConfirmTimeout: 45 * time.Second, ProbeInterval: 30 * time.Second,
		ProbeFailures: 3, RetryMin: 10 * time.Second, RetryMax: 5 * time.Minute,
		Version: ipc.VersionInfo{Version: version, Engine: enginePin, SingBox: singboxPin},
	})
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	logf("ghostlane %s starting (uid %d, state %s)", version, os.Getuid(), *stateDir)
	if err := d.Run(ctx); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	_ = stdout
	return 0
}
```
`releasePubKeyFingerprint()` comes from Task 13 (`pubkey.go`); until then, add a stub in `commands.go`: `func releasePubKeyFingerprint() string { return "unset" }` and remove it in Task 13.

- [ ] **Step 4: Run** — `go test ./cmd/... && make build && ./dist/ghostlane version && ./dist/ghostlane status; echo "exit $?"` → tests PASS; `version` prints the pins; `status` prints "not running" and exits 3.
- [ ] **Step 5: Commit** — `git add cli/cmd && git commit -m "feat(cli): the ghostlane command — run, add, list, connect, status, refresh, remove, version"`

---

### Task 11: netns end-to-end — real tun, real rules, inbound survives, IPv6 refused

**Files:**
- Create: `cli/internal/daemon/netns_test.go`

**Interfaces:**
- Consumes: `netnstest.Enter`, `fakesocks.Serve/Answer204`, `routes.New`, `singbox.Start`, `daemon.New/Run/Handle`, `daemon.HTTPProbe`.

- [ ] **Step 1: The test**

```go
// cli/internal/daemon/netns_test.go
package daemon

import (
	"bufio"
	"context"
	"net"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netns"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/routes"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/fakesocks"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/netnstest"
)

const (
	hostAddr = "198.51.100.1"
	peerAddr = "198.51.100.2"
	farAddr  = "192.0.2.5" // the "SSH client on the internet": behind the peer, no route in main
)

// topology returns the peer namespace after wiring host <-veth-> peer.
func topology(t *testing.T) netns.NsHandle {
	t.Helper()
	runtime.LockOSThread()
	orig, err := netns.Get()
	if err != nil {
		t.Fatal(err)
	}
	peer, err := netns.New() // enters the new namespace
	if err != nil {
		t.Fatal(err)
	}
	if err := netns.Set(orig); err != nil {
		t.Fatal(err)
	}
	runtime.UnlockOSThread()

	veth := &netlink.Veth{LinkAttrs: netlink.LinkAttrs{Name: "gl-veth0"}, PeerName: "gl-veth1"}
	if err := netlink.LinkAdd(veth); err != nil {
		t.Fatal(err)
	}
	v0, _ := netlink.LinkByName("gl-veth0")
	v1, _ := netlink.LinkByName("gl-veth1")
	if err := netlink.LinkSetNsFd(v1, int(peer)); err != nil {
		t.Fatal(err)
	}
	a, _ := netlink.ParseAddr(hostAddr + "/24")
	_ = netlink.AddrAdd(v0, a)
	_ = netlink.LinkSetUp(v0)
	gw := net.ParseIP(peerAddr)
	if err := netlink.RouteAdd(&netlink.Route{LinkIndex: v0.Attrs().Index, Gw: gw}); err != nil { // default via the peer
		t.Fatal(err)
	}
	ph, err := netlink.NewHandleAt(peer)
	if err != nil {
		t.Fatal(err)
	}
	plo, _ := ph.LinkByName("lo")
	_ = ph.LinkSetUp(plo)
	pv1, _ := ph.LinkByName("gl-veth1")
	pa, _ := netlink.ParseAddr(peerAddr + "/24")
	_ = ph.AddrAdd(pv1, pa)
	fa, _ := netlink.ParseAddr(farAddr + "/32")
	_ = ph.AddrAdd(pv1, fa)
	_ = ph.LinkSetUp(pv1)
	return peer
}

func inNamespace(ns netns.NsHandle, fn func()) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		orig, _ := netns.Get()
		_ = netns.Set(ns)
		defer func() { _ = netns.Set(orig) }()
		fn()
	}()
	<-done
}

func dialFromFar(ns netns.NsHandle, target string, timeout time.Duration) (string, error) {
	var out string
	var err error
	inNamespace(ns, func() {
		d := net.Dialer{Timeout: timeout, LocalAddr: &net.TCPAddr{IP: net.ParseIP(farAddr)}}
		var c net.Conn
		c, err = d.Dial("tcp", target)
		if err != nil {
			return
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(timeout))
		var line string
		line, err = bufio.NewReader(c).ReadString('\n')
		out = strings.TrimSpace(line)
	})
	return out, err
}

func TestNetnsTunEndToEnd(t *testing.T) {
	if !netnstest.Enter(t) {
		return
	}
	peer := topology(t)

	// The inbound service: an "sshd" on the host's public address.
	ln, err := net.Listen("tcp", hostAddr+":2222")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_, _ = c.Write([]byte("hi\n"))
			c.Close()
		}
	}()
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("before the tun, the far client reaches the host: %q %v", got, err)
	}

	upstream := fakesocks.Serve(t, "u", "p", fakesocks.Answer204)
	dir := t.TempDir()
	line := "olcrtc://telemost?vp8channel@room#" + strings.Repeat("ab", 32) + "$DE · olcRTC"
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: "inline:" + line}}
	cfg.Selection = &store.Selection{Subscription: "inline:" + line, Selector: "1", Mode: "tun"}
	if err := store.Save(filepath.Join(dir, "config.yaml"), cfg); err != nil {
		t.Fatal(err)
	}
	d := New(Deps{
		ConfigPath: filepath.Join(dir, "config.yaml"), StateDir: dir,
		StartEngine: func(context.Context, olcrtc.Params) (Engine, error) { return &fakeEngine{w: &world{}, addr: upstream}, nil },
		StartFront:  func(ctx context.Context, p singbox.FrontParams) (Front, error) { return singbox.Start(ctx, p) },
		Routes:      routes.New(singbox.TunName),
		Probe:       HTTPProbe("http://203.0.113.10/probe"),
		Logf:        t.Logf,
		UID:         65534, // nothing of ours is excluded: the rules alone must keep inbound alive
		ReadyTimeout: 5 * time.Second, ConfirmTimeout: 5 * time.Second, ProbeInterval: 500 * time.Millisecond,
		ProbeFailures: 3, RetryMin: 100 * time.Millisecond, RetryMax: time.Second,
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	waitState(t, d, "up")

	if _, err := netlink.LinkByName(singbox.TunName); err != nil {
		t.Fatalf("tun device: %v", err)
	}
	own := rulesWithPriority(t, unix.AF_INET, routes.OwnAddressPriority)
	if len(own) != 1 || own[0].Src.IP.String() != hostAddr {
		t.Fatalf("own-address rule: %+v", own)
	}

	// 1. Inbound survives: the far client still reaches the host through the tun's rules.
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("inbound through the tun's rules: %q %v", got, err)
	}
	// 2. Outbound is tunnelled: an address with only a default route lands in the fake upstream.
	c, err := net.DialTimeout("tcp", "203.0.113.10:80", 3*time.Second)
	if err != nil {
		t.Fatalf("tunnelled dial: %v", err)
	}
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = c.Write([]byte("GET / HTTP/1.0\r\nHost: x\r\n\r\n"))
	status, err := bufio.NewReader(c).ReadString('\n')
	c.Close()
	if err != nil || !strings.Contains(status, "204") {
		t.Fatalf("through the tun and the front: %q %v", status, err)
	}
	// 3. IPv6 is refused, not leaked.
	if c6, err := net.DialTimeout("tcp", "[2001:db8::1]:80", 3*time.Second); err == nil {
		c6.Close()
		t.Fatal("IPv6 must be refused")
	}
	// 4. The own-address rule is what keeps inbound alive: without it the far client is cut off.
	if err := routes.New(singbox.TunName).Clear(); err != nil {
		t.Fatal(err)
	}
	if _, err := dialFromFar(peer, hostAddr+":2222", 1500*time.Millisecond); err == nil {
		t.Fatal("without the rule the reply enters the tun and the client hangs")
	}
	addrs, _ := routes.New(singbox.TunName).GlobalAddresses()
	if err := routes.New(singbox.TunName).Sync(addrs); err != nil {
		t.Fatal(err)
	}
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("rule restored: %q %v", got, err)
	}
	// 5. Disconnect leaves nothing behind.
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	if _, err := netlink.LinkByName(singbox.TunName); err == nil {
		t.Fatal("tun device still present")
	}
	if got := rulesWithPriority(t, unix.AF_INET, routes.OwnAddressPriority); len(got) != 0 {
		t.Fatalf("own rules left: %+v", got)
	}
	for p := singbox.RuleIndex; p <= singbox.RuleIndex+10; p++ {
		if got := rulesWithPriority(t, unix.AF_INET, p); len(got) != 0 {
			t.Fatalf("sing-box rule %d left: %+v", p, got)
		}
	}
	rt, _ := netlink.RouteListFiltered(unix.AF_INET, &netlink.Route{Table: singbox.TableIndex}, netlink.RT_FILTER_TABLE)
	if len(rt) != 0 {
		t.Fatalf("table 2022 left: %+v", rt)
	}
}
```
`rulesWithPriority` is copied from Task 8's test (same body, package `daemon`).

- [ ] **Step 2: Run** — `sudo -E env PATH=$PATH GHOSTLANE_NETNS=1 go test -tags with_utls,with_quic -run TestNetnsTunEndToEnd -v ./internal/daemon/` on DATA. Expected PASS. Known ways it can fail and what they mean:
  - sing-box `start: … default interface` → `auto_detect_interface` found no default route; the test adds one via the peer, check `RouteAdd` succeeded.
  - step 1 fails while `own` has the rule → the kernel's reverse-path filter on `gl-veth0` drops the SYN from 192.0.2.5: set `net.ipv4.conf.gl-veth0.rp_filter=0` in the test (a netns sysctl, `/proc/sys/net/ipv4/conf/gl-veth0/rp_filter`), and record that production hosts with `rp_filter=1` need §7.4's per-interface setting on `ghostlane0`, not on the physical interface.
  - step 4 passes with the rule cleared → the peer reached the host through main's connected route: the far client's source must be 192.0.2.5 (`LocalAddr` set), which has no route in main.
- [ ] **Step 3: Commit** — `git add cli/internal/daemon/netns_test.go && git commit -m "test(cli): netns end-to-end — tun up, inbound survives by the own-address rule, IPv6 refused, clean teardown"`

---

### Task 12: Packaging — unit, nfpm, scripts, tarballs, container smoke

**Files:**
- Create: `cli/packaging/ghostlane.service`, `cli/packaging/nfpm.yaml`, `cli/packaging/scripts/preinstall.sh`, `cli/packaging/scripts/postinstall.sh`, `cli/packaging/scripts/preremove.sh`, `cli/packaging/README.md`
- Modify: `cli/Makefile` (targets `build-all`, `package`, `tarballs`, `sums`, `dist`)

- [ ] **Step 1: Unit file**

```ini
# cli/packaging/ghostlane.service
[Unit]
Description=Ghostlane tunnel (olcRTC rooms and share links; tun or proxy mode)
Documentation=https://github.com/ghostlane-project/ghostlane/blob/main/docs/cli.md
After=network-online.target
Wants=network-online.target

[Service]
Type=notify
ExecStart=/usr/bin/ghostlane run
User=ghostlane
Group=ghostlane
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
DeviceAllow=/dev/net/tun rw
RuntimeDirectory=ghostlane
RuntimeDirectoryMode=0750
StateDirectory=ghostlane
StateDirectoryMode=0700
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: Scripts** (POSIX sh; both `useradd` and busybox `adduser`; no systemd in containers is not an error)

```sh
#!/bin/sh
# cli/packaging/scripts/preinstall.sh
set -e
if ! getent group ghostlane >/dev/null 2>&1; then
  if command -v groupadd >/dev/null 2>&1; then groupadd -r ghostlane; else addgroup -S ghostlane; fi
fi
if ! getent passwd ghostlane >/dev/null 2>&1; then
  if command -v useradd >/dev/null 2>&1; then
    useradd -r -g ghostlane -d /var/lib/ghostlane -s /sbin/nologin -c "Ghostlane tunnel" ghostlane
  else
    adduser -S -G ghostlane -h /var/lib/ghostlane -s /sbin/nologin ghostlane
  fi
fi
```

```sh
#!/bin/sh
# cli/packaging/scripts/postinstall.sh
set -e
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
  systemctl enable ghostlane.service >/dev/null 2>&1 || true
  if systemctl is-active --quiet ghostlane.service; then
    systemctl restart ghostlane.service || true
  else
    systemctl start ghostlane.service || true
  fi
fi
echo "ghostlane installed. Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)."
```

```sh
#!/bin/sh
# cli/packaging/scripts/preremove.sh
set -e
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl stop ghostlane.service >/dev/null 2>&1 || true
  systemctl disable ghostlane.service >/dev/null 2>&1 || true
fi
```

- [ ] **Step 3: nfpm.yaml**

```yaml
# cli/packaging/nfpm.yaml — one file, four package formats. VERSION and GOARCH come from the environment.
name: ghostlane-cli
arch: "${GOARCH}"
platform: linux
version: "${VERSION}"
version_schema: semver
release: "1"
section: net
priority: optional
maintainer: "Ghostlane contributors <ghostlane-project@users.noreply.github.com>"
description: |
  Ghostlane tunnel for Linux servers and boxes.
  Joins olcRTC rooms (a tunnel inside a video call) with carrier failover and
  routes the machine through a tun, or serves a local SOCKS5/HTTP proxy.
vendor: Ghostlane
homepage: https://github.com/ghostlane-project/ghostlane
license: GPL-3.0-or-later
contents:
  - src: dist/linux-${GOARCH}/ghostlane
    dst: /usr/bin/ghostlane
    file_info:
      mode: 0755
  - src: packaging/ghostlane.service
    dst: /lib/systemd/system/ghostlane.service
    packager: deb
  - src: packaging/ghostlane.service
    dst: /usr/lib/systemd/system/ghostlane.service
    packager: rpm
  - src: packaging/ghostlane.service
    dst: /usr/lib/systemd/system/ghostlane.service
    packager: archlinux
  - src: packaging/ghostlane.service
    dst: /usr/lib/systemd/system/ghostlane.service
    packager: apk
  - src: packaging/README.md
    dst: /usr/share/doc/ghostlane-cli/README.md
  - src: LICENSE
    dst: /usr/share/doc/ghostlane-cli/LICENSE
scripts:
  preinstall: packaging/scripts/preinstall.sh
  postinstall: packaging/scripts/postinstall.sh
  preremove: packaging/scripts/preremove.sh
```

- [ ] **Step 4: Makefile targets**

```make
ARCHES  := amd64 arm64 arm7
FORMATS := deb rpm apk archlinux
NFPM    ?= nfpm

.PHONY: build-all package tarballs sums dist clean
build-all:
	@for a in $(ARCHES); do \
	  goarch=$$a; goarm=; if [ "$$a" = arm7 ]; then goarch=arm; goarm=7; fi; \
	  echo "== $$a"; \
	  GOOS=linux GOARCH=$$goarch GOARM=$$goarm $(GO) build -trimpath -tags $(TAGS) -ldflags '$(LDFLAGS)' -o dist/linux-$$a/ghostlane ./cmd/ghostlane || exit 1; \
	done

package: build-all
	@for a in $(ARCHES); do for f in $(FORMATS); do \
	  ext=$$f; [ "$$f" = archlinux ] && ext=pkg.tar.zst; \
	  echo "== $$f $$a"; \
	  VERSION=$(VERSION) GOARCH=$$a $(NFPM) package -p $$f -f packaging/nfpm.yaml -t dist/ghostlane-cli-$(VERSION)-linux-$$a.$$ext || exit 1; \
	done; done

tarballs: build-all
	@for a in $(ARCHES); do \
	  stage=dist/stage-$$a; rm -rf $$stage; mkdir -p $$stage; \
	  cp dist/linux-$$a/ghostlane packaging/ghostlane.service packaging/README.md LICENSE $$stage/; \
	  tar -C $$stage -czf dist/ghostlane-cli-$(VERSION)-linux-$$a.tar.gz .; rm -rf $$stage; \
	done

sums:
	cd dist && sha256sum ghostlane-cli-$(VERSION)-linux-* > ghostlane-cli-$(VERSION)-SHA256SUMS

dist: package tarballs sums

clean:
	rm -rf dist
```

- [ ] **Step 5: README shipped in the package** — `cli/packaging/README.md`: the four commands, the two modes with one sentence on what tun does to routing (own-address rule, fail-open on stop), `journalctl -u ghostlane`, where the config lives (`/var/lib/ghostlane/config.yaml`, root-only), how to remove. Keep it under 60 lines; `docs/cli.md` (Task 15) is the long form.

- [ ] **Step 6: Build packages locally and smoke them in containers**

```bash
export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH
go install github.com/goreleaser/nfpm/v2/cmd/nfpm@latest   # note the version it prints in the commit
cd /root/olcbox-fork/cli && make dist VERSION=0.0.1
ls -la dist/
docker run --rm -v "$PWD/dist:/dist:ro" rockylinux:9 sh -c 'rpm -i /dist/ghostlane-cli-0.0.1-linux-amd64.rpm && ghostlane version && id ghostlane && test -f /usr/lib/systemd/system/ghostlane.service'
docker run --rm -v "$PWD/dist:/dist:ro" debian:bookworm sh -c 'dpkg -i /dist/ghostlane-cli-0.0.1-linux-amd64.deb && ghostlane version && id ghostlane && test -f /lib/systemd/system/ghostlane.service'
docker run --rm -v "$PWD/dist:/dist:ro" alpine:3.20 sh -c 'apk add --allow-untrusted /dist/ghostlane-cli-0.0.1-linux-amd64.apk && ghostlane version && id ghostlane'
systemd-analyze verify packaging/ghostlane.service   # on DATA (systemd host); warnings about the missing user are expected here
```
Expected: every container prints `ghostlane 0.0.1`, the user exists, the unit landed. `dist/` is git-ignored (`cli/.gitignore`: `dist/`).

- [ ] **Step 7: Commit** — `git add cli/packaging cli/Makefile cli/.gitignore && git commit -m "feat(cli): packaging — systemd unit, nfpm deb/rpm/apk/arch, tarballs, sums"`

---

### Task 13: install.sh and release signing

**Files:**
- Create: `cli/packaging/install.sh`, `cli/cmd/ghostlane/pubkey.go`, `cli/internal/packaging/install_test.go`, `cli/packaging/cli-release.pub.pem`
- Modify: `cli/cmd/ghostlane/commands.go` (remove the `releasePubKeyFingerprint` stub), `cli/Makefile` (`sign` target)

**Interfaces:**
- Produces: `install.sh` flags `--version V`, `--base-url URL`, `--family deb|rpm|apk|pacman|tar`, `--no-service`, env `GHOSTLANE_INSTALL_ROOT` (tests: prefix for the tarball install), `GHOSTLANE_PUBKEY_FILE` (tests: verify against this PEM instead of the embedded one); `func releasePubKeyFingerprint() string`; `const releasePubKeyPEM string`.

- [ ] **Step 1: Generate the release signing key (once, on DATA; the private half never enters the repo)**

```bash
mkdir -p /root/.ghostlane && chmod 700 /root/.ghostlane
openssl genpkey -algorithm ed25519 -out /root/.ghostlane/cli-release-signing.pem && chmod 600 /root/.ghostlane/cli-release-signing.pem
openssl pkey -in /root/.ghostlane/cli-release-signing.pem -pubout -out /root/olcbox-fork/cli/packaging/cli-release.pub.pem
openssl pkey -pubin -in /root/olcbox-fork/cli/packaging/cli-release.pub.pem -outform DER | sha256sum
```
The owner adds the repository secret `CLI_RELEASE_SIGNING_KEY` = the full PEM text of the private key (Settings → Secrets and variables → Actions). The release job refuses to run without it (Task 14).

- [ ] **Step 2: The public key in the binary**

```go
// cli/cmd/ghostlane/pubkey.go
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/pem"
)

// releasePubKeyPEM verifies SHA256SUMS.sig of every release; install.sh embeds
// the same key. Rotation ships a new key here and in install.sh first.
const releasePubKeyPEM = `-----BEGIN PUBLIC KEY-----
<paste cli-release.pub.pem's body>
-----END PUBLIC KEY-----
`

func releasePubKeyFingerprint() string {
	block, _ := pem.Decode([]byte(releasePubKeyPEM))
	if block == nil {
		return "invalid"
	}
	sum := sha256.Sum256(block.Bytes)
	return hex.EncodeToString(sum[:])
}
```

- [ ] **Step 3: Failing test for the installer**

```go
// cli/internal/packaging/install_test.go
package packaging

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func tarball(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, body := range files {
		mode := int64(0o644)
		if name == "ghostlane" {
			mode = 0o755
		}
		_ = tw.WriteHeader(&tar.Header{Name: "./" + name, Mode: mode, Size: int64(len(body))})
		_, _ = tw.Write([]byte(body))
	}
	_ = tw.Close()
	_ = gz.Close()
	return buf.Bytes()
}

func TestInstallScriptTarball(t *testing.T) {
	if _, err := exec.LookPath("openssl"); err != nil {
		t.Skip("openssl missing")
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(pub)
	pubPEM := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})
	pubFile := filepath.Join(t.TempDir(), "pub.pem")
	_ = os.WriteFile(pubFile, pubPEM, 0o644)

	tgz := tarball(t, map[string]string{"ghostlane": "#!/bin/sh\necho ghostlane 0.0.1\n", "ghostlane.service": "[Unit]\n", "README.md": "x", "LICENSE": "y"})
	sum := sha256.Sum256(tgz)
	sums := hex.EncodeToString(sum[:]) + "  ghostlane-cli-0.0.1-linux-amd64.tar.gz\n"
	sig := ed25519.Sign(priv, []byte(sums))
	assets := map[string][]byte{
		"/ghostlane-cli-0.0.1-linux-amd64.tar.gz": tgz,
		"/ghostlane-cli-0.0.1-SHA256SUMS":         []byte(sums),
		"/ghostlane-cli-0.0.1-SHA256SUMS.sig":     sig,
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, ok := assets[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write(b)
	}))
	defer srv.Close()

	root := t.TempDir()
	runInstall := func() (string, error) {
		cmd := exec.Command("sh", "../../packaging/install.sh", "--version", "0.0.1", "--base-url", srv.URL, "--family", "tar", "--no-service")
		cmd.Env = append(os.Environ(), "GHOSTLANE_INSTALL_ROOT="+root, "GHOSTLANE_PUBKEY_FILE="+pubFile, "GHOSTLANE_ARCH=amd64")
		out, err := cmd.CombinedOutput()
		return string(out), err
	}
	out, err := runInstall()
	if err != nil {
		t.Fatalf("%v\n%s", err, out)
	}
	if b, err := os.ReadFile(filepath.Join(root, "usr/local/bin/ghostlane")); err != nil || !strings.Contains(string(b), "echo ghostlane") {
		t.Fatalf("binary not installed: %v\n%s", err, out)
	}
	if _, err := os.Stat(filepath.Join(root, "etc/systemd/system/ghostlane.service")); err != nil {
		t.Fatalf("unit not installed: %v\n%s", err, out)
	}
	// A tampered sums file must be refused.
	assets["/ghostlane-cli-0.0.1-SHA256SUMS"] = []byte(strings.Replace(sums, "0", "1", 1))
	if out, err := runInstall(); err == nil || !strings.Contains(out, "signature") {
		t.Fatalf("tampered SHA256SUMS accepted: %v\n%s", err, out)
	}
	assets["/ghostlane-cli-0.0.1-SHA256SUMS"] = []byte(sums)
	// A tampered tarball must be refused.
	assets["/ghostlane-cli-0.0.1-linux-amd64.tar.gz"] = append([]byte{}, tgz...)
	assets["/ghostlane-cli-0.0.1-linux-amd64.tar.gz"][10] ^= 0xff
	if out, err := runInstall(); err == nil || !strings.Contains(out, "checksum") {
		t.Fatalf("tampered tarball accepted: %v\n%s", err, out)
	}
}
```

- [ ] **Step 4: Run to verify failure** (script missing), then **Step 5: install.sh**

```sh
#!/bin/sh
# cli/packaging/install.sh — installs ghostlane-cli on any Linux.
#   curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/download/<tag>/install.sh | sh
# Flags: --version V  --base-url URL  --family deb|rpm|apk|pacman|tar  --no-service
# Every download is checked against SHA256SUMS, and SHA256SUMS against the
# release signing key below; a mismatch stops the install.
set -eu

VERSION="${GHOSTLANE_VERSION:-__VERSION__}"
BASE_URL="${GHOSTLANE_BASE_URL:-}"
FAMILY="${GHOSTLANE_FAMILY:-}"
ROOT="${GHOSTLANE_INSTALL_ROOT:-}"
NO_SERVICE=0
REPO="ghostlane-project/ghostlane"

PUBKEY_PEM='-----BEGIN PUBLIC KEY-----
__PUBKEY_BODY__
-----END PUBLIC KEY-----'

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --family) FAMILY="$2"; shift 2 ;;
    --no-service) NO_SERVICE=1; shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) echo "unknown flag $1" >&2; exit 2 ;;
  esac
done

die() { echo "install.sh: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }

fetch() { # url dest
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$2" "$1"
  else die "curl or wget is required"; fi
}

arch="${GHOSTLANE_ARCH:-}"
if [ -z "$arch" ]; then
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    armv7l|armv7|armhf) arch=arm7 ;;
    *) die "unsupported architecture $(uname -m)" ;;
  esac
fi

if [ -z "$FAMILY" ]; then
  FAMILY=tar
  if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    ids="${ID:-} ${ID_LIKE:-}"
    case " $ids " in
      *" debian "*|*" ubuntu "*) FAMILY=deb ;;
      *" rhel "*|*" fedora "*|*" centos "*|*" rocky "*|*" almalinux "*|*" suse "*|*" opensuse "*) FAMILY=rpm ;;
      *" alpine "*) FAMILY=apk ;;
      *" arch "*) FAMILY=pacman ;;
    esac
  fi
fi

if [ "$VERSION" = "__VERSION__" ]; then
  need curl
  VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=10" \
    | grep -o '"tag_name": *"v[^"]*"' | head -1 | sed 's/.*"v\([^"]*\)"/\1/') || true
  [ -n "$VERSION" ] || die "could not find a release; pass --version"
fi
if [ -z "$BASE_URL" ]; then
  BASE_URL="https://github.com/$REPO/releases/download/v$VERSION"
fi

case "$FAMILY" in
  deb) asset="ghostlane-cli-$VERSION-linux-$arch.deb" ;;
  rpm) asset="ghostlane-cli-$VERSION-linux-$arch.rpm" ;;
  apk) asset="ghostlane-cli-$VERSION-linux-$arch.apk" ;;
  pacman) asset="ghostlane-cli-$VERSION-linux-$arch.pkg.tar.zst" ;;
  tar) asset="ghostlane-cli-$VERSION-linux-$arch.tar.gz" ;;
  *) die "unknown family $FAMILY" ;;
esac

need openssl
need sha256sum
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "ghostlane-cli $VERSION ($FAMILY, $arch) from $BASE_URL"
fetch "$BASE_URL/ghostlane-cli-$VERSION-SHA256SUMS" "$tmp/SHA256SUMS"
fetch "$BASE_URL/ghostlane-cli-$VERSION-SHA256SUMS.sig" "$tmp/SHA256SUMS.sig"
fetch "$BASE_URL/$asset" "$tmp/$asset"

pub="$tmp/pub.pem"
if [ -n "${GHOSTLANE_PUBKEY_FILE:-}" ]; then cp "$GHOSTLANE_PUBKEY_FILE" "$pub"; else printf '%s\n' "$PUBKEY_PEM" > "$pub"; fi
openssl pkeyutl -verify -pubin -inkey "$pub" -rawin -in "$tmp/SHA256SUMS" -sigfile "$tmp/SHA256SUMS.sig" >/dev/null 2>&1 \
  || die "signature of SHA256SUMS does not verify; refusing to install"
expected=$(grep " $asset\$" "$tmp/SHA256SUMS" | awk '{print $1}')
[ -n "$expected" ] || die "$asset is not in SHA256SUMS"
actual=$(sha256sum "$tmp/$asset" | awk '{print $1}')
[ "$expected" = "$actual" ] || die "checksum mismatch for $asset; refusing to install"
echo "signature and checksum verified"

as_root() { if [ "$(id -u)" -eq 0 ] || [ -n "$ROOT" ]; then "$@"; else sudo "$@"; fi; }

case "$FAMILY" in
  deb) as_root dpkg -i "$tmp/$asset" ;;
  rpm) as_root rpm -U --replacepkgs "$tmp/$asset" ;;
  apk) as_root apk add --allow-untrusted "$tmp/$asset" ;;
  pacman) as_root pacman -U --noconfirm "$tmp/$asset" ;;
  tar)
    stage="$tmp/stage"; mkdir -p "$stage"; tar -C "$stage" -xzf "$tmp/$asset"
    as_root install -d "$ROOT/usr/local/bin" "$ROOT/etc/systemd/system" "$ROOT/usr/local/share/doc/ghostlane-cli"
    as_root install -m 0755 "$stage/ghostlane" "$ROOT/usr/local/bin/ghostlane"
    as_root sed 's#/usr/bin/ghostlane#/usr/local/bin/ghostlane#' "$stage/ghostlane.service" > "$tmp/unit"
    as_root install -m 0644 "$tmp/unit" "$ROOT/etc/systemd/system/ghostlane.service"
    as_root install -m 0644 "$stage/README.md" "$stage/LICENSE" "$ROOT/usr/local/share/doc/ghostlane-cli/"
    if [ -z "$ROOT" ]; then
      getent group ghostlane >/dev/null 2>&1 || as_root groupadd -r ghostlane 2>/dev/null || as_root addgroup -S ghostlane
      getent passwd ghostlane >/dev/null 2>&1 || as_root useradd -r -g ghostlane -d /var/lib/ghostlane -s /sbin/nologin ghostlane 2>/dev/null \
        || as_root adduser -S -G ghostlane -h /var/lib/ghostlane -s /sbin/nologin ghostlane
      if [ "$NO_SERVICE" -eq 0 ] && [ -d /run/systemd/system ]; then
        as_root systemctl daemon-reload; as_root systemctl enable --now ghostlane.service
      fi
    fi
    ;;
esac
echo "done. Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)"
```
The `__PUBKEY_BODY__` placeholder is replaced by the real key body in this task (`sed -i "s#__PUBKEY_BODY__#$(sed -n '2,$p' cli/packaging/cli-release.pub.pem | sed '$d' | tr -d '\n')#" …` — the PEM body is one base64 line for ed25519, 44 chars). `__VERSION__` stays and is templated by CI at release time.

- [ ] **Step 6: Makefile `sign` target** (used by CI; locally with `KEY=/root/.ghostlane/cli-release-signing.pem`)

```make
sign:
	test -n "$(KEY)" || (echo "KEY=<ed25519 private key pem> is required" && exit 1)
	openssl pkeyutl -sign -inkey $(KEY) -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -out dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	openssl pkeyutl -verify -pubin -inkey packaging/cli-release.pub.pem -rawin -in dist/ghostlane-cli-$(VERSION)-SHA256SUMS -sigfile dist/ghostlane-cli-$(VERSION)-SHA256SUMS.sig
	sed "s/__VERSION__/$(VERSION)/" packaging/install.sh > dist/install.sh
```

- [ ] **Step 7: Run** — `go test ./internal/packaging/ ./cmd/...` → PASS; `make dist sign VERSION=0.0.1 KEY=/root/.ghostlane/cli-release-signing.pem` → `Signature Verified Successfully`; then a real end-to-end of the script against the local `dist/` served by `python3 -m http.server` in a container: `docker run --rm --network host -v "$PWD/dist:/dist:ro" debian:bookworm sh -c 'apt-get update -qq && apt-get install -y -qq curl openssl >/dev/null && sh /dist/install.sh --base-url http://127.0.0.1:8000 --no-service && ghostlane version'` (start `python3 -m http.server 8000 --directory dist` first; stop it after). Expected: "signature and checksum verified", `ghostlane 0.0.1`, and `ghostlane version` shows the key fingerprint printed in Step 1.
- [ ] **Step 8: Commit** — `git add cli/packaging/install.sh cli/packaging/cli-release.pub.pem cli/cmd/ghostlane/pubkey.go cli/cmd/ghostlane/commands.go cli/internal/packaging cli/Makefile && git commit -m "feat(cli): signed installer — install.sh verifies SHA256SUMS by the release key"`

---

### Task 14: CI — PR checks job and release leg

**Files:**
- Modify: `.github/workflows/pr-checks.yml` (scope output `cli`, new job `cli`), `.github/workflows/release.yml` (`plan` output `cli`, job `build-cli`)

- [ ] **Step 1: pr-checks — scope**: in the `scope` job add an output `cli: ${{ steps.paths.outputs.cli }}` and, in the `paths` step after the shared_kotlin decision, `if printf '%s\n' "$changed" | grep -qE '^cli/|^\.github/workflows/pr-checks\.yml$|^scripts/cores-pins\.sh$'; then echo 'cli=true' >> "$GITHUB_OUTPUT"; else echo 'cli=false' >> "$GITHUB_OUTPUT"; fi` (and `cli=true` on the no-base path).

- [ ] **Step 2: pr-checks — job**

```yaml
  cli:
    if: github.event_name != 'workflow_dispatch' && needs.scope.outputs.cli == 'true'
    name: Linux CLI
    needs: scope
    runs-on: ubuntu-latest
    timeout-minutes: 30
    defaults:
      run:
        working-directory: cli
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-go@v7
        with:
          go-version: "1.26.5"
          cache-dependency-path: cli/go.sum
      - name: Vet and lint
        run: |
          go vet -tags with_utls,with_quic ./...
          go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.6.0
          golangci-lint run ./...
      - name: Unit tests
        run: go test -race -tags with_utls,with_quic ./...
      - name: netns tests (root)
        run: |
          sudo -E env "PATH=$PATH" GHOSTLANE_NETNS=1 go test -tags with_utls,with_quic -run 'TestNetns' -v ./internal/routes/ ./internal/daemon/
      - name: Packages
        run: |
          go install github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.43.1
          make dist VERSION=0.0.0
          systemd-analyze verify packaging/ghostlane.service || true
          ls -la dist
      - name: Install smoke
        run: |
          docker run --rm -v "$PWD/dist:/dist:ro" debian:bookworm sh -c 'dpkg -i /dist/ghostlane-cli-0.0.0-linux-amd64.deb && ghostlane version && id ghostlane'
          docker run --rm -v "$PWD/dist:/dist:ro" rockylinux:9 sh -c 'rpm -i /dist/ghostlane-cli-0.0.0-linux-amd64.rpm && ghostlane version && id ghostlane'
```
Pin `golangci-lint` and `nfpm` to the versions that worked locally (replace `v2.6.0` / `v2.43.1` with what `go install …@latest` printed in Tasks 1 and 12).

- [ ] **Step 3: release.yml — plan**: add `cli: ${{ steps.p.outputs.cli }}` to the `plan` outputs; in "Resolve requested platforms" add `cli=false` to the defaults line, `cli=true` under `all)`, a new case `cli) cli=true ;;` (a test build of the CLI alone, no publish), and `echo "cli=$cli"` to the output block. Add `cli` to the `platforms` input's description/choices if it is a `choice` input (check the `on.workflow_dispatch.inputs.platforms` block at the top of the file and mirror how `android` is listed).

- [ ] **Step 4: release.yml — job** (after `build-linux`)

```yaml
  build-cli:
    needs: [release_version, plan, gate]
    if: needs.plan.outputs.cli == 'true'
    runs-on: ubuntu-latest
    permissions:
      contents: read
    env:
      OLCBOX_VERSION: ${{ needs.release_version.outputs.version }}
      CLI_RELEASE_SIGNING_KEY: ${{ secrets.CLI_RELEASE_SIGNING_KEY }}
    defaults:
      run:
        working-directory: cli
    steps:
      - uses: actions/checkout@v7
      - name: Signing key is set
        run: |
          [ -n "${CLI_RELEASE_SIGNING_KEY}" ] || { echo "::error::CLI_RELEASE_SIGNING_KEY is not set; the CLI release is signed or not built"; exit 1; }
      - uses: actions/setup-go@v7
        with:
          go-version: ${{ env.GO_VERSION }}
          cache-dependency-path: cli/go.sum
      - name: Build, package, sum, sign
        run: |
          set -euo pipefail
          go install github.com/goreleaser/nfpm/v2/cmd/nfpm@v2.43.1
          make dist VERSION="${OLCBOX_VERSION}"
          umask 077
          printf '%s\n' "${CLI_RELEASE_SIGNING_KEY}" > "$RUNNER_TEMP/cli-signing.pem"
          make sign VERSION="${OLCBOX_VERSION}" KEY="$RUNNER_TEMP/cli-signing.pem"
          rm -f "$RUNNER_TEMP/cli-signing.pem"
          rm -rf dist/linux-*
          ls -la dist
      - name: Upload artifact
        uses: actions/upload-artifact@v7
        with:
          name: Ghostlane-cli-linux
          path: |
            cli/dist/ghostlane-cli-*
            cli/dist/install.sh
```
The publish job downloads `Ghostlane-*` artifacts, so these ride along without a change there; check that its SHA256SUMS step (if it sums every asset) does not choke on our `.sig` (a binary file is fine for `sha256sum`).

- [ ] **Step 5: Validate the YAML** — `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/pr-checks.yml','.github/workflows/release.yml']]; print('ok')"` (PyYAML is on DATA; if not, `pip install --user pyyaml`). Then push the branch: the `cli` job of pr-checks must be green before the PR (memory: pushes may need manual mode — ask the owner if the push is denied).
- [ ] **Step 6: Commit** — `git add .github/workflows && git commit -m "ci(cli): PR checks job and release leg for the Linux CLI"`

---

### Task 15: Docs, notices, release notes, PR

**Files:**
- Create: `docs/cli.md`
- Modify: `README.MD` (a "Linux CLI" section pointing at docs/cli.md), `CONTRIBUTING.md` (the parser-parity rule), `THIRD_PARTY_NOTICES.md` (sing-box GPL-3.0-or-later, Xray-core MPL-2.0 — listed now, linked in Stage B —, olcrtc Apache-2.0, vishvananda/netlink+netns Apache-2.0, yaml.v3 MIT/Apache, x/net+x/sys BSD; the `cli/` module's own licence GPL-3.0-or-later), `release-notes/pending.md` (a "Linux CLI" section: install line, four commands, tun/proxy, what is not in yet: share links, crypt1)

- [ ] **Step 1: docs/cli.md** — sections: Install (one curl line; the package files; the mirror flag), First connection (`add`, `list`, `connect DE --tun`), Modes (proxy env lines; tun: what changes on the box, inbound services survive, fail-open on stop, IPv6 refused, private ranges direct), Service (`systemctl status`, `journalctl -u ghostlane`, user `ghostlane`, files under `/var/lib/ghostlane`, root-only config), Containers (`ghostlane run --subscription … --connect DE --mode proxy` with `--cap-add NET_ADMIN --device /dev/net/tun` for tun), Troubleshooting (state `failed` + last error; `refresh`; room full → the next carrier; `probe failed` lines), Security (what is signed, what is never logged), Not yet (share links, crypt1, kill switch, IPv6 through the tunnel), Licence (GPL-3 for the CLI binary, why).

- [ ] **Step 2: Run everything once more from a clean tree**

```bash
cd /root/olcbox-fork/cli && export PATH=/usr/local/go/bin:$HOME/go/bin:$PATH
go vet -tags with_utls,with_quic ./... && golangci-lint run ./... && go test -race -tags with_utls,with_quic ./... \
 && sudo -E env "PATH=$PATH" GHOSTLANE_NETNS=1 go test -tags with_utls,with_quic -run TestNetns ./internal/routes/ ./internal/daemon/ \
 && make dist sign VERSION=0.0.1 KEY=/root/.ghostlane/cli-release-signing.pem
```
Expected: all green; `dist/` holds 12 packages, 3 tarballs, sums, sig, install.sh.

- [ ] **Step 3: Live smoke on DATA, proxy mode only** (tun never on DATA's host network): `./dist/linux-amd64/ghostlane run --state-dir /tmp/gl-state --socket /tmp/gl.sock &`, then `./dist/linux-amd64/ghostlane add --socket /tmp/gl.sock '<the partner list URL>?c=olcbox'`, `… connect DE --proxy --socket /tmp/gl.sock`, wait for `status` = up, `curl -x socks5h://127.0.0.1:1080 https://api.ipify.org` shows the DE exit; `disconnect`; kill the daemon. Record the carrier that answered in the PR description.

- [ ] **Step 4: Commit docs and open the PR**

```bash
cd /root/olcbox-fork && git add docs/cli.md README.MD CONTRIBUTING.md THIRD_PARTY_NOTICES.md release-notes/pending.md && git commit -m "docs(cli): Linux CLI guide, notices, release notes"
git push -u proofkit feat/linux-cli
```
PR title: `feat(cli): Ghostlane CLI for Linux — Stage A (olcRTC, tun/proxy, packages)`. Body: the spec and plan paths, what ships (four commands, two modes, four package formats + tarball, signed installer), what is verified (unit + race, netns end-to-end on DATA and CI, container installs, live proxy smoke with the partner's list), what is not in (Stage B share links, crypt1), and the two owner actions: set `CLI_RELEASE_SIGNING_KEY`, run a release with `platforms: cli` once to see the assets.
