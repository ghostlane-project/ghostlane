# Release Gate, Plan A: the engine suite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `go test` suite in the engine (`internal/gate`) that pairs the engine's client with a real relay and a server — a child process it starts, or the fleet's DE node through an `olcrtc://` link — runs the load shapes that broke the tunnel, judges them against thresholds in one file, and writes a report the release compares against the previous one; plus the engine CI running it on every push.

**Architecture:** One package holds a registry of scenarios (S0–S7), two targets (Local, Link), two client flavours (`cli` = the public client path, `mobile` = `mobile.Runtime` under the `olcrtc_lean` tag), an HTTP origin for local load, SOCKS TCP/UDP helpers, a memory sampler, a log capture with a scrubber, thresholds, and a JSON report writer. A separate `cmd/gate-report` renders Markdown and compares two reports. A `testhooks` package compiled only with `olcrtc_testhooks` lets the child server delay its Jitsi bridge for scenario S6.

**Tech Stack:** Go 1.26, `go test` flags in the `-olcrtc.*` style already used by `internal/e2e`, `golang.org/x/net/proxy` (SOCKS5 TCP dialer), `gopkg.in/yaml.v3` (instance list), `net/http/httptest`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-15-release-gate-design.md` (in the olcbox repository; this plan implements its sections 1–8 for the engine side).

**Repository and branch:** the engine fork `/root/olcrtc-proofkit` (GitHub `romanpodpriatov/olcrtc`), branch `feat/gate` from `refs/heads/proofkit` (the name `proofkit` is also a remote, so always write `refs/heads/proofkit`). Work in a worktree: `git -C /root/olcrtc-proofkit worktree add -b feat/gate /root/olcrtc-wt-gate refs/heads/proofkit`. Go is `/usr/local/go/bin/go`, golangci-lint is `/root/go/bin/golangci-lint`; export both on PATH. Every task ends with `gofmt -l`, `go vet` and `golangci-lint run` clean on the packages it touched, and `go test -race` green for them. Commit messages follow the repository style: `fix(scope): what changed`, a body that says why, and the two attribution lines from the session (`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and the `Claude-Session:` line).

## Global Constraints

- Nothing secret leaves a run: no room id, key, link, subscriber id or token in the report, the job summary, a committed file or an uploaded log. Cells are named `platform/provider/transport/client/scenario`. Uploaded logs pass the scrubber first (spec §10).
- Zero skip: every planned cell ends `pass` or `fail`; a planned cell that did not execute is reported as `fail` with reason `did not run` (spec §11).
- Thresholds live in `internal/gate/thresholds.go` and nowhere else (spec §6). Starting values: `ConnectP95 5s`; Local `ThroughputDown 2 Mbit/s`, `ThroughputUp 2 Mbit/s`; Link `ThroughputDown 0.8 Mbit/s`, `ThroughputUp 1.5 Mbit/s`; `HeapPeak 16 MiB`, `RSSPeak 45 MiB`, `GoroutineGrowth 20`, `ResolverAnswered 63` of 64.
- Load sizes: S2 pulls 6 × 10 MB, S3 pushes 4 × 5 MB (the relay caps at ~5 Mbit/s; these keep a pair under three minutes while keeping the saturation shape). The spec's "60 MB / 30 MB" is corrected to these values by this plan (Task 16 updates the spec).
- The `mobile` flavour runs with `mobile.SetMemoryLimit(40 MiB)` and `debug.SetGCPercent(10)`, `SetVP8Options(60, 64)`, DNS `8.8.8.8:53`, device id `gate-mobile` — the app's numbers.
- `internal/testhooks` is compiled only with the `olcrtc_testhooks` build tag; a release build never passes it, and a test proves the default and lean builds carry no hook (spec §2).
- Providers run sequentially, transports and scenarios in sequence, so captured log lines belong to exactly one cell.
- The test entry is `TestGate` in `internal/gate`, enabled by `-olcrtc.gate`; without the flag the package's `go test` runs only unit tests.

---

## File structure

```
internal/link/link.go                 Parse / (Link).String for olcrtc:// links (app grammar)
internal/link/link_test.go
internal/testhooks/hooks.go           //go:build olcrtc_testhooks: Enabled=true, BeforeBridgeOpen reads env
internal/testhooks/hooks_off.go       //go:build !olcrtc_testhooks: Enabled=false, BeforeBridgeOpen no-op
internal/testhooks/hooks_test.go      both builds: Enabled matches the tag; the no-op returns at once
internal/testhooks/hooks_on_test.go   //go:build olcrtc_testhooks: the env delay is honoured
internal/engine/jitsi/negotiate.go    one call: testhooks.BeforeBridgeOpen() before a bridge opens
internal/gate/gate.go                 Pair, Endpoint, Tunnel, Client, Target, LoadURLs, Metrics, Env, Scenario, registry, PlanCells
internal/gate/report.go               Cell, Report, Recorder (zero-skip accounting), Write
internal/gate/thresholds.go           Thresholds, Local, Link, (Thresholds).Map
internal/gate/verdict.go              Evaluate(scenario, metrics, thresholds) []string
internal/gate/sampler.go              Sampler: heap/RSS/goroutines at 1 s, marks, PeakBetween, CSV
internal/gate/origin.go               Origin: local HTTP origin (/kb, /big, /sink), LoadURLs for it
internal/gate/socks.go                DialFunc via x/net/proxy; HTTPClient; UDPAssoc (RFC 1928 UDP)
internal/gate/dns.go                  BuildQuery / ParseResponse for one A question
internal/gate/logcapture.go           CellLog, Capture (mobile.SetLogWriter tee), Count
internal/gate/scrub.go                Scrub(text, secrets...) string
internal/gate/rooms.go                JitsiRoom (instance list + probe), PoolRoom, NewKey
internal/gate/target_local.go         LocalTarget: build child server binary, run per pair, wait for link
internal/gate/target_link.go          LinkTarget: one pair from a parsed link, public load URLs
internal/gate/client_cli.go           cliClient: pkg/olcrtc/client with an ephemeral SOCKS port
internal/gate/client_mobile.go        //go:build olcrtc_lean: mobileClient over mobile.Runtime
internal/gate/client_mobile_off.go    //go:build !olcrtc_lean: MobileClient() returns nil
internal/gate/load.go                 Pull, Push, ConnectBurst, OnTopConnects, p95
internal/gate/scenarios.go            S0–S7 registered in init(); each Run in its own func
internal/gate/gate_test.go            flags, TestMain (report written last), TestGate, dry run
internal/gate/*_test.go               unit tests named after the file they test
cmd/gate-report/main.go               render | compare
cmd/gate-report/render.go, compare.go, *_test.go
docs/gate.md                          how to run it locally, flags, thresholds, reading a report
.github/workflows/ci.yml              hygiene fix, lean unit tests, gate-local job
```

Shared vocabulary every task uses (defined in Task 4 and Task 5; repeated here so a task can be read alone):

```go
type Pair struct{ Provider, Transport string }            // "jitsi"/"datachannel"
type Endpoint struct {
    Provider, Transport, Room, Key, DNS string           // Room: URL or id as the provider wants it
    VP8FPS, VP8Batch int                                 // 60, 64
}
type Tunnel struct{ SocksAddr string; Stop func() }
type Client interface {
    Name() string                                        // "cli" | "mobile"
    Start(ctx context.Context, ep Endpoint) (*Tunnel, error)
}
type OpenOptions struct{ BridgeDelay time.Duration }     // Local only; S6
type Target interface {
    Name() string                                        // "local" | "link"
    Platform() string                                    // "engine-linux"
    Pairs() []Pair
    Open(ctx context.Context, p Pair, dir string, opt OpenOptions) (Endpoint, func(), error)
    Load() LoadURLs
}
type LoadURLs struct{ Small, Big, Sink string; BigBytes int64 }
type Metrics map[string]float64
type Scenario struct {
    ID, Name string
    Applies func(target Target, p Pair, client string) bool
    Run     func(ctx context.Context, env *Env) (Metrics, error)
}
```

Metric keys (the verdict and the report use exactly these): `handshake_ms`, `pull_ok`, `pull_total`, `push_ok`, `push_total`, `connect_ok`, `connect_total`, `connect_p95_ms`, `throughput_down_bps`, `throughput_up_bps`, `on_top_ok`, `on_top_total`, `on_top_p95_ms`, `alive`, `missed_pong`, `reconnects`, `final_pull_ok`, `answered_1`, `answered_2`, `ready_3s_ms`, `ready_8s_ms`, `heap_peak_bytes`, `rss_peak_bytes`, `goroutines_idle`, `goroutines_after`.

---

### Task 1: Engine CI hygiene and the lean unit-test run

**Files:**
- Modify: `go.sum` (via `go mod tidy`)
- Modify: `.github/workflows/ci.yml` (`test` job)

**Interfaces:** none. Produces a green Hygiene job and a second unit-test run under `-tags olcrtc_lean`.

- [ ] **Step 1: Reproduce the red Hygiene job locally**

Run: `cd /root/olcrtc-wt-gate && go mod tidy -diff | head -5; echo "exit=$?"`
Expected: a diff (198 `go.sum` lines) and a non-zero exit.

- [ ] **Step 2: Tidy and verify**

Run: `go mod tidy && go mod tidy -diff; echo "exit=$?"` → `exit=0`. `git diff --stat` shows only `go.sum`.

- [ ] **Step 3: Run the unit tests under both tag sets**

Replace the `Run tests` step of the `test` job in `.github/workflows/ci.yml` with a matrix. The job today:

```yaml
  test:
    name: Test (-race, coverage)
    runs-on: ubuntu-latest
    timeout-minutes: 20
```

becomes:

```yaml
  test:
    name: Test (-race, ${{ matrix.name }})
    runs-on: ubuntu-latest
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        include:
          - name: default
            tags: ""
          - name: lean
            tags: "olcrtc_lean"
```

and the run/upload steps:

```yaml
      - name: Run tests
        run: go test -count=1 -race -timeout 15m -tags "${{ matrix.tags }}" -coverprofile=coverage.out ./...

      - name: Coverage summary
        run: go tool cover -func=coverage.out | tail -1

      - name: Upload coverage
        uses: actions/upload-artifact@v4
        with:
          name: coverage-${{ matrix.name }}
          path: coverage.out
```

- [ ] **Step 4: Prove the lean run would have caught the wbstream regression**

Run: `go test -count=1 -tags olcrtc_lean ./internal/engine/builtin/ ./pkg/olcrtc/client/` → `ok` for both (the registry invariant test from 850aa5f9 runs under the tag now).

- [ ] **Step 5: Commit**

```bash
git add go.sum .github/workflows/ci.yml
git commit -m "ci: tidy go.sum so Hygiene is green, and run the unit tests under the lean tag too"
```
(body: the four red pushes, and that the lean contract had never executed in CI.)

---

### Task 2: `internal/link` — the olcrtc:// grammar in Go

**Files:**
- Create: `internal/link/link.go`
- Test: `internal/link/link_test.go`

**Interfaces:**
- Produces: `type Link struct { Provider, Transport, Room, Key, Device, Label string; VP8FPS, VP8Batch int }`, `func Parse(line string) (Link, error)`, `func (l Link) String() string`, `var ErrMalformed`.

The grammar is the app's (`LocationsDatasource.parseOlcRtcUri`): after `olcrtc://`, the provider runs to the first `?`; the transport token runs to the first `@` after it; the room runs to the first `#` after that; the key runs to the first `%` (device) or `$` (label) after that, whichever comes first, else to the end. A transport token may carry options in angle brackets: `vp8channel<vp8-fps=60&vp8-batch=64>`; keys `vp8-fps`/`fps` and `vp8-batch`/`batch` are read, defaults 60 and 64. Rooms are inserted verbatim (a Telemost join URL or a Jitsi `https://host/room` contain none of `?@#$%`).

- [ ] **Step 1: Write the failing tests**

```go
package link

import "testing"

func TestParseAppFixtures(t *testing.T) {
	cases := []struct {
		in   string
		want Link
	}{
		{"olcrtc://wbstream?seichannel@room-01#" + hex64('a') + "%android-01$RU / olc free sub / IPv6",
			Link{Provider: "wbstream", Transport: "seichannel", Room: "room-01", Key: hex64('a'),
				Device: "android-01", Label: "RU / olc free sub / IPv6", VP8FPS: 60, VP8Batch: 64}},
		{"olcrtc://telemost?vp8channel<vp8-fps=30&vp8-batch=32>@https://telemost.yandex.ru/j/5092358972#" + hex64('b'),
			Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/5092358972",
				Key: hex64('b'), VP8FPS: 30, VP8Batch: 32}},
		{"olcrtc://jitsi?datachannel@https://meet.example.org/gate-abc#" + hex64('c') + "$DE",
			Link{Provider: "jitsi", Transport: "datachannel", Room: "https://meet.example.org/gate-abc",
				Key: hex64('c'), Label: "DE", VP8FPS: 60, VP8Batch: 64}},
	}
	for _, c := range cases {
		got, err := Parse(c.in)
		if err != nil {
			t.Fatalf("Parse(%q) error = %v", c.in, err)
		}
		if got != c.want {
			t.Fatalf("Parse(%q)\n got  %+v\n want %+v", c.in, got, c.want)
		}
	}
}

func TestParseRejectsMalformed(t *testing.T) {
	for _, in := range []string{"", "vless://x", "olcrtc://jitsi", "olcrtc://jitsi?datachannel",
		"olcrtc://jitsi?datachannel@room", "olcrtc://?datachannel@room#" + hex64('a')} {
		if _, err := Parse(in); err == nil {
			t.Fatalf("Parse(%q) accepted a malformed link", in)
		}
	}
}

func TestStringRoundTrip(t *testing.T) {
	l := Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: hex64('d'), Label: "DE", VP8FPS: 60, VP8Batch: 64}
	back, err := Parse(l.String())
	if err != nil || back != l {
		t.Fatalf("round trip: %v / %+v", err, back)
	}
}

func hex64(c byte) string {
	b := make([]byte, 64)
	for i := range b {
		b[i] = c
	}
	return string(b)
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/link/` → build failure, `undefined: Parse`.

- [ ] **Step 3: Implement**

```go
// Package link parses and prints olcrtc:// links in the grammar the olcbox app
// uses: olcrtc://<provider>?<transport>[<opts>]@<room>#<key>[%<device>][$<label>].
package link

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

const prefix = "olcrtc://"

const (
	defaultVP8FPS   = 60
	defaultVP8Batch = 64
)

// ErrMalformed is returned for a line that is not an olcrtc:// link.
var ErrMalformed = errors.New("malformed olcrtc link")

// Link is one parsed line. Room and Key are verbatim.
type Link struct {
	Provider  string
	Transport string
	Room      string
	Key       string
	Device    string
	Label     string
	VP8FPS    int
	VP8Batch  int
}

// Parse reads one link. Options in the transport token are honoured for vp8
// fps and batch; anything else in there is ignored, as the app ignores it.
func Parse(line string) (Link, error) {
	line = strings.TrimSpace(line)
	if !strings.HasPrefix(line, prefix) {
		return Link{}, fmt.Errorf("%w: no olcrtc:// prefix", ErrMalformed)
	}
	payload := line[len(prefix):]
	q := strings.Index(payload, "?")
	if q <= 0 {
		return Link{}, fmt.Errorf("%w: no transport", ErrMalformed)
	}
	at := strings.Index(payload[q+1:], "@")
	if at < 0 {
		return Link{}, fmt.Errorf("%w: no room", ErrMalformed)
	}
	at += q + 1
	hash := strings.Index(payload[at+1:], "#")
	if hash < 0 {
		return Link{}, fmt.Errorf("%w: no key", ErrMalformed)
	}
	hash += at + 1
	rest := payload[hash+1:]
	keyEnd := len(rest)
	device, label := "", ""
	pct := strings.Index(rest, "%")
	dollar := strings.Index(rest, "$")
	if pct >= 0 && (dollar < 0 || pct < dollar) {
		keyEnd = pct
		if dollar > pct {
			device = strings.TrimSpace(rest[pct+1 : dollar])
			label = strings.TrimSpace(rest[dollar+1:])
		} else {
			device = strings.TrimSpace(rest[pct+1:])
		}
	} else if dollar >= 0 {
		keyEnd = dollar
		label = strings.TrimSpace(rest[dollar+1:])
	}
	transport, fps, batch := parseTransportToken(strings.TrimSpace(payload[q+1 : at]))
	l := Link{
		Provider:  strings.TrimSpace(payload[:q]),
		Transport: transport,
		Room:      strings.TrimSpace(payload[at+1 : hash]),
		Key:       strings.TrimSpace(rest[:keyEnd]),
		Device:    device,
		Label:     label,
		VP8FPS:    fps,
		VP8Batch:  batch,
	}
	if l.Provider == "" || l.Transport == "" || l.Room == "" || len(l.Key) != 64 {
		return Link{}, fmt.Errorf("%w: empty field or key not 64 hex chars", ErrMalformed)
	}
	return l, nil
}

func parseTransportToken(token string) (string, int, int) {
	fps, batch := defaultVP8FPS, defaultVP8Batch
	lt := strings.Index(token, "<")
	if lt < 0 {
		return token, fps, batch
	}
	name := token[:lt]
	opts := strings.TrimSuffix(token[lt+1:], ">")
	for _, kv := range strings.Split(opts, "&") {
		k, v, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		n, err := strconv.Atoi(strings.TrimSpace(v))
		if err != nil || n <= 0 {
			continue
		}
		switch strings.TrimSpace(k) {
		case "vp8-fps", "fps":
			fps = n
		case "vp8-batch", "batch":
			batch = n
		}
	}
	return name, fps, batch
}

// String prints the link the way the app shares it: vp8 options only when they
// differ from the defaults, device and label only when set.
func (l Link) String() string {
	var b strings.Builder
	b.WriteString(prefix)
	b.WriteString(l.Provider)
	b.WriteByte('?')
	b.WriteString(l.Transport)
	if l.Transport == "vp8channel" && (l.VP8FPS != defaultVP8FPS || l.VP8Batch != defaultVP8Batch) {
		fmt.Fprintf(&b, "<vp8-fps=%d&vp8-batch=%d>", l.VP8FPS, l.VP8Batch)
	}
	b.WriteByte('@')
	b.WriteString(l.Room)
	b.WriteByte('#')
	b.WriteString(l.Key)
	if l.Device != "" {
		b.WriteByte('%')
		b.WriteString(l.Device)
	}
	if l.Label != "" {
		b.WriteByte('$')
		b.WriteString(l.Label)
	}
	return b.String()
}
```

- [ ] **Step 4: Run the tests**

Run: `go test -race ./internal/link/` → `ok`. Then `golangci-lint run ./internal/link/` → `0 issues`.

- [ ] **Step 5: Commit**

```bash
git add internal/link
git commit -m "feat(link): parse and print olcrtc:// links in the app's grammar"
```

---

### Task 3: `internal/testhooks` and the Jitsi bridge-delay call site

**Files:**
- Create: `internal/testhooks/hooks.go`, `internal/testhooks/hooks_off.go`, `internal/testhooks/hooks_test.go`, `internal/testhooks/hooks_on_test.go`
- Modify: `internal/engine/jitsi/negotiate.go` (`completeJingleSetup`, before the bridge opens)

**Interfaces:**
- Produces: `testhooks.Enabled bool` (const), `testhooks.BeforeBridgeOpen()`, env `OLCRTC_TEST_BRIDGE_DELAY` (a Go duration, e.g. `3s`), read only under the `olcrtc_testhooks` tag.

- [ ] **Step 1: Write the failing tests**

`internal/testhooks/hooks_test.go` (no build tag; runs in every build):

```go
package testhooks

import (
	"os"
	"testing"
	"time"
)

// Whatever the build, BeforeBridgeOpen must return at once when no delay is
// configured; and without the tag it must ignore the environment entirely.
func TestBeforeBridgeOpenIsFreeWithoutDelay(t *testing.T) {
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d > 50*time.Millisecond {
		t.Fatalf("BeforeBridgeOpen took %v with no delay configured", d)
	}
}

func TestEnabledMatchesTheBuild(t *testing.T) {
	if Enabled != tagged {
		t.Fatalf("Enabled = %v, want %v for this build", Enabled, tagged)
	}
}

func TestEnvIgnoredWhenNotEnabled(t *testing.T) {
	if Enabled {
		t.Skip("tagged build honours the env; see hooks_on_test.go")
	}
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "2s")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d > 50*time.Millisecond {
		t.Fatalf("an untagged build slept %v on OLCRTC_TEST_BRIDGE_DELAY", d)
	}
	_ = os.Getenv
}
```

`internal/testhooks/hooks_on_test.go`:

```go
//go:build olcrtc_testhooks

package testhooks

import (
	"testing"
	"time"
)

const tagged = true

func TestDelayIsHonoured(t *testing.T) {
	t.Setenv("OLCRTC_TEST_BRIDGE_DELAY", "300ms")
	start := time.Now()
	BeforeBridgeOpen()
	if d := time.Since(start); d < 300*time.Millisecond {
		t.Fatalf("BeforeBridgeOpen returned after %v, want ≥ 300ms", d)
	}
}
```

and a file `internal/testhooks/hooks_off_test.go`:

```go
//go:build !olcrtc_testhooks

package testhooks

const tagged = false
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/testhooks/` → `undefined: BeforeBridgeOpen`.

- [ ] **Step 3: Implement both builds**

`internal/testhooks/hooks.go`:

```go
//go:build olcrtc_testhooks

// Package testhooks holds knobs a test build may turn that a release build
// does not even contain. With the olcrtc_testhooks tag the functions here read
// the environment; without it they are empty and the compiler drops them.
package testhooks

import (
	"os"
	"time"
)

// Enabled reports whether this binary was built with the hooks.
const Enabled = true

// BeforeBridgeOpen sleeps for OLCRTC_TEST_BRIDGE_DELAY (a Go duration) before
// a Jitsi session opens its bridge. The gate's S6 uses it to make a server
// come up after the client, the ordering that lost the hello in olcbox#22.
func BeforeBridgeOpen() {
	d, err := time.ParseDuration(os.Getenv("OLCRTC_TEST_BRIDGE_DELAY"))
	if err != nil || d <= 0 {
		return
	}
	time.Sleep(d)
}
```

`internal/testhooks/hooks_off.go`:

```go
//go:build !olcrtc_testhooks

package testhooks

// Enabled is false in every build that does not pass the tag.
const Enabled = false

// BeforeBridgeOpen does nothing in a build without the hooks.
func BeforeBridgeOpen() {}
```

- [ ] **Step 4: Call it before the bridge opens**

In `internal/engine/jitsi/negotiate.go`, `completeJingleSetup`, the block

```go
	needBridge := s.onData != nil || s.onPeerData != nil
	wantVideo := s.shouldRequestVideo()
	sctpBridge := (needBridge || wantVideo) && jSess.ColibriWS == ""
```

gets one line after it:

```go
	testhooks.BeforeBridgeOpen()
```

and the import `"github.com/openlibrecommunity/olcrtc/internal/testhooks"`. The hook sits before both the websocket and the SCTP bridge paths, so it delays whichever the host offers.

- [ ] **Step 5: Run both builds' tests**

Run: `go test -race ./internal/testhooks/ ./internal/engine/jitsi/ && go test -race -tags olcrtc_testhooks ./internal/testhooks/` → all `ok`. `golangci-lint run ./internal/testhooks/ ./internal/engine/jitsi/` → `0 issues`.

- [ ] **Step 6: Commit**

```bash
git add internal/testhooks internal/engine/jitsi/negotiate.go
git commit -m "feat(testhooks): a build-tagged delay before the Jitsi bridge opens, for the gate's late-server scenario"
```

---

### Task 4: `internal/gate` core — types, registry, plan, recorder, report

**Files:**
- Create: `internal/gate/gate.go`, `internal/gate/report.go`
- Test: `internal/gate/gate_core_test.go`, `internal/gate/report_test.go`

**Interfaces:**
- Produces (used by every later task): the types in "Shared vocabulary" above; `func Register(s Scenario)`, `func Scenarios() []Scenario` (sorted by ID), `func PlanCells(t Target, clients []string) []Cell`, `func CellID(platform string, p Pair, client, scenario string) string`; `type Cell`, `type Report`, `type Recorder`, `func NewRecorder(meta Report) *Recorder`, `(r *Recorder) Plan(cells []Cell)`, `(r *Recorder) Finish(id string, m Metrics, thresholds map[string]float64, failures []string, logPath string, took time.Duration)`, `(r *Recorder) Report() Report`, `(r *Recorder) Write(path string) error`.

- [ ] **Step 1: Write the failing tests**

`internal/gate/gate_core_test.go`:

```go
package gate

import (
	"context"
	"testing"
)

type fakeTarget struct{ pairs []Pair }

func (f fakeTarget) Name() string      { return "fake" }
func (f fakeTarget) Platform() string  { return "engine-test" }
func (f fakeTarget) Pairs() []Pair     { return f.pairs }
func (f fakeTarget) Load() LoadURLs    { return LoadURLs{} }
func (f fakeTarget) Open(context.Context, Pair, string, OpenOptions) (Endpoint, func(), error) {
	return Endpoint{}, func() {}, nil
}

func TestPlanCellsEnumeratesEveryApplicableCell(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S1", Name: "one", Applies: func(Target, Pair, string) bool { return true }})
	Register(Scenario{ID: "S0", Name: "zero", Applies: func(_ Target, p Pair, c string) bool { return c == "mobile" }})
	target := fakeTarget{pairs: []Pair{{"jitsi", "datachannel"}, {"telemost", "vp8channel"}}}
	cells := PlanCells(target, []string{"cli", "mobile"})
	want := []string{
		"engine-test/jitsi/datachannel/mobile/S0",
		"engine-test/telemost/vp8channel/mobile/S0",
		"engine-test/jitsi/datachannel/cli/S1",
		"engine-test/jitsi/datachannel/mobile/S1",
		"engine-test/telemost/vp8channel/cli/S1",
		"engine-test/telemost/vp8channel/mobile/S1",
	}
	if len(cells) != len(want) {
		t.Fatalf("PlanCells returned %d cells, want %d: %+v", len(cells), len(want), cells)
	}
	for i, c := range cells {
		if c.ID != want[i] || c.Status != "planned" {
			t.Fatalf("cell %d = %q (%s), want %q (planned)", i, c.ID, c.Status, want[i])
		}
	}
}

func TestScenariosAreSortedByID(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S7"})
	Register(Scenario{ID: "S2"})
	if got := Scenarios(); got[0].ID != "S2" || got[1].ID != "S7" {
		t.Fatalf("Scenarios() order = %s, %s", got[0].ID, got[1].ID)
	}
}
```

`internal/gate/report_test.go`:

```go
package gate

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRecorderCountsAndMarksUnrunCellsFailed(t *testing.T) {
	r := NewRecorder(Report{EngineCommit: "abc", Target: "local"})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}, {ID: "p/a/b/cli/S2"}})
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, map[string]float64{}, nil, "", 2*time.Second)
	r.Finish("p/a/b/cli/S1", Metrics{"connect_ok": 3}, map[string]float64{"connect_p95_ms": 5000},
		[]string{"connect_p95_ms 6100 > 5000"}, "logs/x.log", time.Second)
	rep := r.Report()
	if rep.Planned != 3 || rep.Executed != 2 || rep.Passed != 1 || rep.Failed != 2 {
		t.Fatalf("counts = planned %d executed %d passed %d failed %d", rep.Planned, rep.Executed, rep.Passed, rep.Failed)
	}
	byID := map[string]Cell{}
	for _, c := range rep.Cells {
		byID[c.ID] = c
	}
	if byID["p/a/b/cli/S2"].Status != "fail" || byID["p/a/b/cli/S2"].Failures[0] != "did not run" {
		t.Fatalf("unrun cell = %+v", byID["p/a/b/cli/S2"])
	}
	if byID["p/a/b/cli/S1"].Status != "fail" || byID["p/a/b/cli/S1"].DurationS != 1 {
		t.Fatalf("failed cell = %+v", byID["p/a/b/cli/S1"])
	}
	if byID["p/a/b/cli/S0"].Status != "pass" || byID["p/a/b/cli/S0"].Metrics["pull_ok"] != 1 {
		t.Fatalf("passed cell = %+v", byID["p/a/b/cli/S0"])
	}
}

func TestRecorderFinishUnknownCellIsRecordedAsFailure(t *testing.T) {
	r := NewRecorder(Report{})
	r.Plan(nil)
	r.Finish("p/x/y/cli/S9", nil, nil, nil, "", 0)
	rep := r.Report()
	if rep.Failed != 1 || rep.Cells[0].Failures[0] != "cell was not planned" {
		t.Fatalf("unplanned cell = %+v", rep.Cells)
	}
}

func TestWriteProducesSchemaOne(t *testing.T) {
	r := NewRecorder(Report{EngineCommit: "abc", Target: "local", Runner: "ubuntu"})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}})
	r.Finish("p/a/b/cli/S0", Metrics{"pull_ok": 1}, nil, nil, "", time.Second)
	path := filepath.Join(t.TempDir(), "gate-report.json")
	if err := r.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(path)
	var back Report
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.Schema != 1 || back.EngineCommit != "abc" || back.Passed != 1 || len(back.Cells) != 1 {
		t.Fatalf("written report = %+v", back)
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/` → `undefined: Register` (and the rest).

- [ ] **Step 3: Implement `gate.go`**

```go
// Package gate runs the engine the way users break it: a real relay, a real
// server, bulk transfer with connects on top, a phone's memory budget, and a
// report a release is compared against. See the design in the olcbox
// repository, docs/superpowers/specs/2026-09-15-release-gate-design.md.
package gate

import (
	"context"
	"net"
	"net/http"
	"sort"
	"sync"
	"time"
)

// Pair is one provider and one transport of it.
type Pair struct {
	Provider  string
	Transport string
}

func (p Pair) String() string { return p.Provider + "/" + p.Transport }

// Endpoint is everything a client needs to join a server's room.
type Endpoint struct {
	Provider  string
	Transport string
	Room      string
	Key       string
	DNS       string
	VP8FPS    int
	VP8Batch  int
}

// Tunnel is a running client: a SOCKS5 listener and a way to stop it.
type Tunnel struct {
	SocksAddr string
	Stop      func()
}

// Client is a way of running the engine's client: the CLI path or the phone's.
type Client interface {
	Name() string
	Start(ctx context.Context, ep Endpoint) (*Tunnel, error)
}

// OpenOptions tunes how a target brings a server up. Only Local honours them.
type OpenOptions struct {
	// BridgeDelay makes the server open its relay bridge this much later than
	// it would; the child process is started with OLCRTC_TEST_BRIDGE_DELAY.
	BridgeDelay time.Duration
}

// Target is where the server side of a pair comes from.
type Target interface {
	Name() string
	Platform() string
	Pairs() []Pair
	// Open brings up the server side for p (or, for a link, checks that the
	// link is p) and returns what the client needs plus a stop function.
	Open(ctx context.Context, p Pair, dir string, opt OpenOptions) (Endpoint, func(), error)
	Load() LoadURLs
}

// LoadURLs is what the load scenarios pull from and push to through the tunnel.
type LoadURLs struct {
	Small    string // ~1 KB resource
	Big      string // BigBytes long
	Sink     string // accepts POST bodies
	BigBytes int64
}

// Metrics is what a scenario measured, by the keys the verdict knows.
type Metrics map[string]float64

// DialFunc dials through the tunnel.
type DialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// Env is one cell's world: the pair, the client, the tunnel and the helpers.
type Env struct {
	Target     Target
	Pair       Pair
	Client     Client
	Endpoint   Endpoint
	Tunnel     *Tunnel
	Load       LoadURLs
	HTTP       *http.Client
	Dial       DialFunc
	UDP        func(ctx context.Context) (*UDPAssoc, error)
	Sampler    *Sampler
	Thresholds Thresholds
	Log        *CellLog
	Dir        string
	Logf       func(format string, args ...any)
	// Delayed opens a fresh server for the pair with the given options and
	// returns its endpoint; nil for targets that cannot (Link). S6 uses it.
	Delayed func(ctx context.Context, opt OpenOptions) (Endpoint, func(), error)
}

// Scenario is one named cell body.
type Scenario struct {
	ID      string
	Name    string
	Applies func(target Target, p Pair, client string) bool
	Run     func(ctx context.Context, env *Env) (Metrics, error)
}

var (
	registryMu sync.Mutex
	registry   []Scenario
)

// Register adds a scenario. Called from init in scenarios.go.
func Register(s Scenario) {
	registryMu.Lock()
	defer registryMu.Unlock()
	registry = append(registry, s)
}

// Scenarios returns the registered scenarios sorted by ID.
func Scenarios() []Scenario {
	registryMu.Lock()
	defer registryMu.Unlock()
	out := append([]Scenario(nil), registry...)
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// CellID names a cell: platform/provider/transport/client/scenario. Never a
// room, a key or a link: this is what the report and the release show.
func CellID(platform string, p Pair, client, scenario string) string {
	return platform + "/" + p.Provider + "/" + p.Transport + "/" + client + "/" + scenario
}

// PlanCells enumerates every cell a target and a set of clients will run, in
// scenario order, so the recorder can hold each one to an outcome.
func PlanCells(t Target, clients []string) []Cell {
	var cells []Cell
	for _, s := range Scenarios() {
		for _, p := range t.Pairs() {
			for _, c := range clients {
				if s.Applies != nil && !s.Applies(t, p, c) {
					continue
				}
				cells = append(cells, Cell{
					ID: CellID(t.Platform(), p, c, s.ID), Platform: t.Platform(),
					Provider: p.Provider, Transport: p.Transport, Client: c, Scenario: s.ID,
					Status: "planned",
				})
			}
		}
	}
	return cells
}
```

and in `gate_core_test.go` add the helper the tests call:

```go
func resetRegistryForTest(t *testing.T) {
	t.Helper()
	registryMu.Lock()
	saved := registry
	registry = nil
	registryMu.Unlock()
	t.Cleanup(func() {
		registryMu.Lock()
		registry = saved
		registryMu.Unlock()
	})
}
```

- [ ] **Step 4: Implement `report.go`**

```go
package gate

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
	"time"
)

// Cell is one scenario on one pair with one client, and what it measured.
type Cell struct {
	ID         string             `json:"id"`
	Platform   string             `json:"platform"`
	Provider   string             `json:"provider"`
	Transport  string             `json:"transport"`
	Client     string             `json:"client"`
	Scenario   string             `json:"scenario"`
	Status     string             `json:"status"` // planned | pass | fail
	Metrics    map[string]float64 `json:"metrics"`
	Thresholds map[string]float64 `json:"thresholds"`
	Failures   []string           `json:"failures"`
	Log        string             `json:"log,omitempty"`
	DurationS  float64            `json:"duration_s"`
}

// Report is gate-report.json, schema 1.
type Report struct {
	Schema       int     `json:"schema"`
	EngineCommit string  `json:"engine_commit"`
	EngineRef    string  `json:"engine_ref"`
	AppVersion   string  `json:"app_version"`
	Target       string  `json:"target"`
	Runner       string  `json:"runner"`
	StartedAt    string  `json:"started_at"`
	DurationS    float64 `json:"duration_s"`
	Planned      int     `json:"planned"`
	Executed     int     `json:"executed"`
	Passed       int     `json:"passed"`
	Failed       int     `json:"failed"`
	Cells        []Cell  `json:"cells"`
}

// Recorder holds every planned cell to an outcome.
type Recorder struct {
	mu      sync.Mutex
	meta    Report
	started time.Time
	cells   map[string]*Cell
	order   []string
}

// NewRecorder starts a report with the run's metadata filled in.
func NewRecorder(meta Report) *Recorder {
	meta.Schema = 1
	if meta.StartedAt == "" {
		meta.StartedAt = time.Now().UTC().Format(time.RFC3339)
	}
	return &Recorder{meta: meta, started: time.Now(), cells: map[string]*Cell{}}
}

// Plan registers the cells that must end pass or fail.
func (r *Recorder) Plan(cells []Cell) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i := range cells {
		c := cells[i]
		if c.Status == "" {
			c.Status = "planned"
		}
		if _, dup := r.cells[c.ID]; !dup {
			r.order = append(r.order, c.ID)
		}
		r.cells[c.ID] = &c
	}
}

// Finish records a cell's outcome: pass when failures is empty.
func (r *Recorder) Finish(id string, m Metrics, thresholds map[string]float64, failures []string, logPath string, took time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.cells[id]
	if !ok {
		c = &Cell{ID: id}
		r.cells[id] = c
		r.order = append(r.order, id)
		failures = append([]string{"cell was not planned"}, failures...)
	}
	c.Metrics = map[string]float64(m)
	if c.Metrics == nil {
		c.Metrics = map[string]float64{}
	}
	c.Thresholds = thresholds
	if c.Thresholds == nil {
		c.Thresholds = map[string]float64{}
	}
	c.Failures = failures
	if c.Failures == nil {
		c.Failures = []string{}
	}
	c.Log = logPath
	c.DurationS = took.Seconds()
	c.Status = "pass"
	if len(failures) > 0 {
		c.Status = "fail"
	}
}

// Report builds the report: counts, and every still-planned cell as a failure.
func (r *Recorder) Report() Report {
	r.mu.Lock()
	defer r.mu.Unlock()
	rep := r.meta
	rep.DurationS = time.Since(r.started).Seconds()
	ids := append([]string(nil), r.order...)
	sort.Strings(ids)
	for _, id := range ids {
		c := *r.cells[id]
		rep.Planned++
		switch c.Status {
		case "pass":
			rep.Executed++
			rep.Passed++
		case "fail":
			rep.Executed++
			rep.Failed++
		default:
			c.Status = "fail"
			c.Failures = []string{"did not run"}
			if c.Metrics == nil {
				c.Metrics = map[string]float64{}
			}
			if c.Thresholds == nil {
				c.Thresholds = map[string]float64{}
			}
			rep.Failed++
		}
		rep.Cells = append(rep.Cells, c)
	}
	return rep
}

// Write stores the report as indented JSON.
func (r *Recorder) Write(path string) error {
	raw, err := json.MarshalIndent(r.Report(), "", "  ")
	if err != nil {
		return fmt.Errorf("marshal report: %w", err)
	}
	if err := os.WriteFile(path, append(raw, '\n'), 0o644); err != nil { //nolint:gosec // a report, not a secret
		return fmt.Errorf("write report: %w", err)
	}
	return nil
}
```

`Executed` counts cells that ran (pass or fail); a planned cell that never ran is failed but not executed — the counts in the test (`planned 3 executed 2 passed 1 failed 2`) pin that.

- [ ] **Step 5: Run the tests**

Run: `go test -race ./internal/gate/` → the three test files pass (Sampler/UDPAssoc/CellLog/Thresholds are referenced by `Env`; until Tasks 5–9 exist, add placeholder-free minimal type declarations only if the build needs them — it does: create the real files from Tasks 5, 6, 8, 9 first, or implement Task 4 last in the batch. Recommended order for an executor: Tasks 5, 6, 8, 9 declare their types; if working strictly in order, temporarily keep `Env` without the `UDP`, `Sampler`, `Log`, `Thresholds` fields and add them in the task that defines each type.)

- [ ] **Step 6: Commit**

```bash
git add internal/gate/gate.go internal/gate/report.go internal/gate/gate_core_test.go internal/gate/report_test.go
git commit -m "feat(gate): the cell registry, the plan and a report that fails what never ran"
```

---

### Task 5: Thresholds and the verdict

**Files:**
- Create: `internal/gate/thresholds.go`, `internal/gate/verdict.go`
- Test: `internal/gate/verdict_test.go`

**Interfaces:**
- Produces: `type Thresholds struct{ ConnectP95 time.Duration; ThroughputDownBps, ThroughputUpBps float64; HeapPeakBytes, RSSPeakBytes float64; GoroutineGrowth int; ResolverAnswered int }`, `var Local, Link Thresholds`, `func (t Thresholds) Map() map[string]float64`, `func Evaluate(scenario string, m Metrics, t Thresholds) []string`.

- [ ] **Step 1: Write the failing tests**

```go
package gate

import (
	"strings"
	"testing"
	"time"
)

func TestEvaluateEachRuleWithAPassAndAFail(t *testing.T) {
	th := Thresholds{ConnectP95: 5 * time.Second, ThroughputDownBps: 2e6, ThroughputUpBps: 2e6,
		HeapPeakBytes: 16 << 20, RSSPeakBytes: 45 << 20, GoroutineGrowth: 20, ResolverAnswered: 63}
	cases := []struct {
		scenario string
		pass     Metrics
		fail     Metrics
		reason   string
	}{
		{"S0", Metrics{"handshake_ms": 900, "pull_ok": 1, "push_ok": 1},
			Metrics{"handshake_ms": 16000, "pull_ok": 1, "push_ok": 1}, "handshake_ms"},
		{"S0", Metrics{"handshake_ms": 900, "pull_ok": 1, "push_ok": 1},
			Metrics{"handshake_ms": 900, "pull_ok": 0, "push_ok": 1}, "pull_ok"},
		{"S1", Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 700},
			Metrics{"connect_ok": 47, "connect_total": 48, "connect_p95_ms": 700}, "connect_ok"},
		{"S1", Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 700},
			Metrics{"connect_ok": 48, "connect_total": 48, "connect_p95_ms": 5001}, "connect_p95_ms"},
		{"S2", Metrics{"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 4e6, "on_top_ok": 20, "on_top_total": 20, "on_top_p95_ms": 900},
			Metrics{"pull_ok": 6, "pull_total": 6, "throughput_down_bps": 1e6, "on_top_ok": 20, "on_top_total": 20, "on_top_p95_ms": 900}, "throughput_down_bps"},
		{"S3", Metrics{"push_ok": 4, "push_total": 4, "throughput_up_bps": 3e6, "on_top_ok": 10, "on_top_total": 10, "on_top_p95_ms": 900},
			Metrics{"push_ok": 4, "push_total": 4, "throughput_up_bps": 3e6, "on_top_ok": 9, "on_top_total": 10, "on_top_p95_ms": 900}, "on_top_ok"},
		{"S4", Metrics{"alive": 1, "missed_pong": 0, "reconnects": 0, "final_pull_ok": 1},
			Metrics{"alive": 1, "missed_pong": 2, "reconnects": 0, "final_pull_ok": 1}, "missed_pong"},
		{"S5", Metrics{"answered_1": 64, "answered_2": 63},
			Metrics{"answered_1": 64, "answered_2": 60}, "answered_2"},
		{"S6", Metrics{"ready_3s_ms": 4100, "ready_8s_ms": 9200},
			Metrics{"ready_3s_ms": 4100, "ready_8s_ms": 0}, "ready_8s_ms"},
		{"S7", Metrics{"heap_peak_bytes": 9 << 20, "rss_peak_bytes": 30 << 20, "goroutines_idle": 80, "goroutines_after": 90},
			Metrics{"heap_peak_bytes": 9 << 20, "rss_peak_bytes": 30 << 20, "goroutines_idle": 80, "goroutines_after": 140}, "goroutines"},
	}
	for _, c := range cases {
		if got := Evaluate(c.scenario, c.pass, th); len(got) != 0 {
			t.Errorf("%s pass sample failed: %v", c.scenario, got)
		}
		got := Evaluate(c.scenario, c.fail, th)
		if len(got) == 0 || !strings.Contains(strings.Join(got, ";"), c.reason) {
			t.Errorf("%s fail sample: failures = %v, want one naming %s", c.scenario, got, c.reason)
		}
	}
}

func TestEvaluateUnknownScenarioFails(t *testing.T) {
	if got := Evaluate("S9", Metrics{}, Local); len(got) != 1 || !strings.Contains(got[0], "no rules") {
		t.Fatalf("unknown scenario verdict = %v", got)
	}
}

func TestThresholdMapHasEveryKnob(t *testing.T) {
	m := Link.Map()
	for _, k := range []string{"connect_p95_ms", "throughput_down_bps", "throughput_up_bps",
		"heap_peak_bytes", "rss_peak_bytes", "goroutine_growth", "resolver_answered"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("Map() lacks %s", k)
		}
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestEvaluate|TestThreshold'` → `undefined: Evaluate`.

- [ ] **Step 3: Implement `thresholds.go`**

```go
package gate

import "time"

// Thresholds are the only numbers the verdicts use. Change them here.
type Thresholds struct {
	// ConnectP95 bounds the 95th percentile of a connect through the tunnel,
	// idle or with bulk transfer in flight (the ack deadline is the same order).
	ConnectP95 time.Duration
	// ThroughputDownBps and ThroughputUpBps are aggregate floors for the
	// saturation scenarios: half of what the harness measured, so runner
	// variance does not fail a good build.
	ThroughputDownBps float64
	ThroughputUpBps   float64
	// HeapPeakBytes and RSSPeakBytes bound the mobile flavour over S2–S4
	// under the phone's memory limit and GC settings.
	HeapPeakBytes float64
	RSSPeakBytes  float64
	// GoroutineGrowth bounds goroutines 60 s after load versus idle.
	GoroutineGrowth int
	// ResolverAnswered is how many of 64 burst queries must be answered.
	ResolverAnswered int
}

// Local is the target with the server in the runner: Jitsi's relay carries
// ~5 Mbit/s, WB Stream and Telemost a few Mbit/s.
var Local = Thresholds{ //nolint:gochecknoglobals // the one place these numbers live
	ConnectP95:        5 * time.Second,
	ThroughputDownBps: 2_000_000,
	ThroughputUpBps:   2_000_000,
	HeapPeakBytes:     16 << 20,
	RSSPeakBytes:      45 << 20,
	GoroutineGrowth:   20,
	ResolverAnswered:  63,
}

// Link is the fleet's DE node through Telemost, measured at 1.4–1.6 Mbit/s
// down and ~3 Mbit/s up from a datacenter.
var Link = Thresholds{ //nolint:gochecknoglobals // the one place these numbers live
	ConnectP95:        5 * time.Second,
	ThroughputDownBps: 800_000,
	ThroughputUpBps:   1_500_000,
	HeapPeakBytes:     16 << 20,
	RSSPeakBytes:      45 << 20,
	GoroutineGrowth:   20,
	ResolverAnswered:  63,
}

// Map is what a cell records next to its metrics.
func (t Thresholds) Map() map[string]float64 {
	return map[string]float64{
		"connect_p95_ms":      float64(t.ConnectP95.Milliseconds()),
		"throughput_down_bps": t.ThroughputDownBps,
		"throughput_up_bps":   t.ThroughputUpBps,
		"heap_peak_bytes":     t.HeapPeakBytes,
		"rss_peak_bytes":      t.RSSPeakBytes,
		"goroutine_growth":    float64(t.GoroutineGrowth),
		"resolver_answered":   float64(t.ResolverAnswered),
	}
}
```

- [ ] **Step 4: Implement `verdict.go`**

```go
package gate

import "fmt"

const handshakeBudgetMs = 15_000 // handshake.DefaultTimeout

// Evaluate judges a scenario's metrics. An empty result is a pass; every
// string names the metric that missed and both numbers, so the report reads.
func Evaluate(scenario string, m Metrics, t Thresholds) []string {
	var f []string
	need := func(key string) float64 { return m[key] }
	equal := func(okKey, totalKey string) {
		if need(okKey) != need(totalKey) || need(totalKey) == 0 {
			f = append(f, fmt.Sprintf("%s %v of %s %v", okKey, need(okKey), totalKey, need(totalKey)))
		}
	}
	atMost := func(key string, limit float64) {
		if need(key) > limit {
			f = append(f, fmt.Sprintf("%s %v > %v", key, need(key), limit))
		}
	}
	atLeast := func(key string, floor float64) {
		if need(key) < floor {
			f = append(f, fmt.Sprintf("%s %v < %v", key, need(key), floor))
		}
	}
	isOne := func(key string) {
		if need(key) != 1 {
			f = append(f, fmt.Sprintf("%s %v != 1", key, need(key)))
		}
	}
	p95 := float64(t.ConnectP95.Milliseconds())
	switch scenario {
	case "S0":
		atMost("handshake_ms", handshakeBudgetMs)
		isOne("pull_ok")
		isOne("push_ok")
	case "S1":
		equal("connect_ok", "connect_total")
		atMost("connect_p95_ms", p95)
	case "S2":
		equal("pull_ok", "pull_total")
		atLeast("throughput_down_bps", t.ThroughputDownBps)
		equal("on_top_ok", "on_top_total")
		atMost("on_top_p95_ms", p95)
	case "S3":
		equal("push_ok", "push_total")
		atLeast("throughput_up_bps", t.ThroughputUpBps)
		equal("on_top_ok", "on_top_total")
		atMost("on_top_p95_ms", p95)
	case "S4":
		isOne("alive")
		atMost("missed_pong", 0)
		atMost("reconnects", 0)
		isOne("final_pull_ok")
	case "S5":
		atLeast("answered_1", float64(t.ResolverAnswered))
		atLeast("answered_2", float64(t.ResolverAnswered))
	case "S6":
		for _, key := range []string{"ready_3s_ms", "ready_8s_ms"} {
			if need(key) <= 0 {
				f = append(f, fmt.Sprintf("%s %v: never ready", key, need(key)))
			}
		}
		atMost("ready_3s_ms", 3_000+handshakeBudgetMs)
		atMost("ready_8s_ms", 8_000+handshakeBudgetMs)
	case "S7":
		atMost("heap_peak_bytes", t.HeapPeakBytes)
		atMost("rss_peak_bytes", t.RSSPeakBytes)
		if growth := need("goroutines_after") - need("goroutines_idle"); growth > float64(t.GoroutineGrowth) {
			f = append(f, fmt.Sprintf("goroutines grew by %v > %d", growth, t.GoroutineGrowth))
		}
	default:
		f = append(f, "no rules for scenario "+scenario)
	}
	return f
}
```

- [ ] **Step 5: Run the tests**

Run: `go test -race ./internal/gate/ -run 'TestEvaluate|TestThreshold'` → `ok`; `golangci-lint run ./internal/gate/`.

- [ ] **Step 6: Commit**

```bash
git add internal/gate/thresholds.go internal/gate/verdict.go internal/gate/verdict_test.go
git commit -m "feat(gate): thresholds in one file and a verdict that names what missed"
```

---

### Task 6: The memory sampler

**Files:**
- Create: `internal/gate/sampler.go`
- Test: `internal/gate/sampler_test.go`

**Interfaces:**
- Produces: `type Sample struct{ At time.Time; HeapInuse, RSS uint64; Goroutines int }`, `type Sampler`, `func NewSampler(every time.Duration) *Sampler`, `(s *Sampler) Start()`, `(s *Sampler) Stop()`, `(s *Sampler) Mark(name string)`, `(s *Sampler) PeakBetween(fromMark, toMark string) (heap, rss uint64, ok bool)`, `(s *Sampler) Samples() []Sample`, `(s *Sampler) WriteCSV(path string) error`, `func readRSS() uint64` (Linux `/proc/self/status` VmRSS; 0 elsewhere).

- [ ] **Step 1: Write the failing tests**

```go
package gate

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSamplerRecordsAndPeaksBetweenMarks(t *testing.T) {
	s := NewSampler(5 * time.Millisecond)
	s.Start()
	s.Mark("a")
	junk := make([][]byte, 0, 64)
	for i := 0; i < 64; i++ {
		junk = append(junk, make([]byte, 256<<10))
	}
	time.Sleep(40 * time.Millisecond)
	s.Mark("b")
	_ = junk
	time.Sleep(20 * time.Millisecond)
	s.Stop()
	if len(s.Samples()) < 5 {
		t.Fatalf("only %d samples", len(s.Samples()))
	}
	heap, rss, ok := s.PeakBetween("a", "b")
	if !ok || heap == 0 {
		t.Fatalf("PeakBetween = %d %d %v", heap, rss, ok)
	}
	if _, _, ok := s.PeakBetween("a", "nope"); ok {
		t.Fatal("PeakBetween accepted an unknown mark")
	}
	for _, sm := range s.Samples() {
		if sm.Goroutines <= 0 {
			t.Fatal("a sample without goroutines")
		}
	}
}

func TestSamplerWritesCSV(t *testing.T) {
	s := NewSampler(5 * time.Millisecond)
	s.Start()
	time.Sleep(20 * time.Millisecond)
	s.Stop()
	p := filepath.Join(t.TempDir(), "samples.csv")
	if err := s.WriteCSV(p); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(p)
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if lines[0] != "t_ms,heap_inuse,rss,goroutines" || len(lines) < 3 {
		t.Fatalf("csv = %q", string(raw))
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run TestSampler` → `undefined: NewSampler`.

- [ ] **Step 3: Implement**

```go
package gate

import (
	"bufio"
	"fmt"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Sample is one reading of the test process, which is the client's process.
type Sample struct {
	At         time.Time
	HeapInuse  uint64
	RSS        uint64
	Goroutines int
}

// Sampler reads memory and goroutines on a ticker and remembers named
// moments so a scenario can ask for the peak over a window.
type Sampler struct {
	every   time.Duration
	mu      sync.Mutex
	samples []Sample
	marks   map[string]time.Time
	stop    chan struct{}
	done    chan struct{}
	start   time.Time
}

// NewSampler samples every interval once started.
func NewSampler(every time.Duration) *Sampler {
	return &Sampler{every: every, marks: map[string]time.Time{}}
}

// Start begins sampling in a goroutine; Stop ends it and waits.
func (s *Sampler) Start() {
	s.mu.Lock()
	s.stop = make(chan struct{})
	s.done = make(chan struct{})
	s.start = time.Now()
	s.mu.Unlock()
	go s.loop(s.stop, s.done)
}

func (s *Sampler) loop(stop, done chan struct{}) {
	defer close(done)
	t := time.NewTicker(s.every)
	defer t.Stop()
	s.take()
	for {
		select {
		case <-stop:
			s.take()
			return
		case <-t.C:
			s.take()
		}
	}
}

func (s *Sampler) take() {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	sm := Sample{At: time.Now(), HeapInuse: m.HeapInuse, RSS: readRSS(), Goroutines: runtime.NumGoroutine()}
	s.mu.Lock()
	s.samples = append(s.samples, sm)
	s.mu.Unlock()
}

// Stop ends sampling. Safe to call once after Start.
func (s *Sampler) Stop() {
	s.mu.Lock()
	stop, done := s.stop, s.done
	s.stop = nil
	s.mu.Unlock()
	if stop == nil {
		return
	}
	close(stop)
	<-done
}

// Mark names now.
func (s *Sampler) Mark(name string) {
	s.mu.Lock()
	s.marks[name] = time.Now()
	s.mu.Unlock()
}

// PeakBetween returns the highest heap and RSS sampled between two marks.
func (s *Sampler) PeakBetween(fromMark, toMark string) (uint64, uint64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	from, okF := s.marks[fromMark]
	to, okT := s.marks[toMark]
	if !okF || !okT {
		return 0, 0, false
	}
	var heap, rss uint64
	seen := false
	for _, sm := range s.samples {
		if sm.At.Before(from) || sm.At.After(to) {
			continue
		}
		seen = true
		heap = max(heap, sm.HeapInuse)
		rss = max(rss, sm.RSS)
	}
	return heap, rss, seen
}

// Samples returns a copy of everything sampled so far.
func (s *Sampler) Samples() []Sample {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Sample(nil), s.samples...)
}

// WriteCSV stores the samples for the artifacts.
func (s *Sampler) WriteCSV(path string) error {
	f, err := os.Create(path) //nolint:gosec // an artifact path the gate chose
	if err != nil {
		return fmt.Errorf("create csv: %w", err)
	}
	defer func() { _ = f.Close() }()
	w := bufio.NewWriter(f)
	_, _ = w.WriteString("t_ms,heap_inuse,rss,goroutines\n")
	for _, sm := range s.Samples() {
		fmt.Fprintf(w, "%d,%d,%d,%d\n", sm.At.Sub(s.start).Milliseconds(), sm.HeapInuse, sm.RSS, sm.Goroutines)
	}
	return w.Flush()
}

// readRSS reads VmRSS in bytes from /proc on Linux, 0 elsewhere.
func readRSS() uint64 {
	raw, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(raw), "\n") {
		if !strings.HasPrefix(line, "VmRSS:") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			return 0
		}
		kb, err := strconv.ParseUint(fields[1], 10, 64)
		if err != nil {
			return 0
		}
		return kb << 10
	}
	return 0
}
```

- [ ] **Step 4: Run the tests**

Run: `go test -race ./internal/gate/ -run TestSampler` → `ok`; lint clean.

- [ ] **Step 5: Commit**

```bash
git add internal/gate/sampler.go internal/gate/sampler_test.go
git commit -m "feat(gate): a sampler for the client's heap, RSS and goroutines with named marks"
```

---

### Task 7: The local HTTP origin

**Files:**
- Create: `internal/gate/origin.go`
- Test: `internal/gate/origin_test.go`

**Interfaces:**
- Produces: `type Origin struct{ Addr string; URLs LoadURLs }`, `func StartOrigin(bigBytes int64) (*Origin, error)`, `(o *Origin) Close()`, `(o *Origin) SinkBytes() int64` (total bytes received on `/sink`). `/kb` returns 1024 bytes; `/big` returns `bigBytes` of a repeating pattern with `Content-Length`; `/sink` accepts any `POST` body, discards it, returns `200` with the byte count as text.

- [ ] **Step 1: Write the failing tests**

```go
package gate

import (
	"bytes"
	"io"
	"net/http"
	"testing"
)

func TestOriginServesSmallBigAndSink(t *testing.T) {
	o, err := StartOrigin(3 << 20)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()
	if o.URLs.BigBytes != 3<<20 {
		t.Fatalf("BigBytes = %d", o.URLs.BigBytes)
	}
	for url, want := range map[string]int{o.URLs.Small: 1024, o.URLs.Big: 3 << 20} {
		resp, err := http.Get(url) //nolint:noctx // test
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode != 200 || len(body) != want {
			t.Fatalf("GET %s = %d, %d bytes; want 200, %d", url, resp.StatusCode, len(body), want)
		}
	}
	resp, err := http.Post(o.URLs.Sink, "application/octet-stream", bytes.NewReader(make([]byte, 70000))) //nolint:noctx // test
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != 200 || o.SinkBytes() != 70000 {
		t.Fatalf("POST sink = %d, sink bytes %d", resp.StatusCode, o.SinkBytes())
	}
}
```

- [ ] **Step 2: Run to see it fail**

Run: `go test ./internal/gate/ -run TestOrigin` → `undefined: StartOrigin`.

- [ ] **Step 3: Implement**

```go
package gate

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync/atomic"
)

// Origin is the HTTP server the local target's client pulls from and pushes
// to. It lives in the test process on loopback; the server side of the pair
// reaches it as any exit would.
type Origin struct {
	Addr string
	URLs LoadURLs
	srv  *http.Server
	sink atomic.Int64
}

// StartOrigin listens on a free loopback port.
func StartOrigin(bigBytes int64) (*Origin, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen origin: %w", err)
	}
	o := &Origin{Addr: ln.Addr().String()}
	pattern := make([]byte, 64<<10)
	for i := range pattern {
		pattern[i] = byte(i*7 + 13)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/kb", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Length", "1024")
		_, _ = w.Write(pattern[:1024])
	})
	mux.HandleFunc("/big", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Length", strconv.FormatInt(bigBytes, 10))
		left := bigBytes
		for left > 0 {
			n := int64(len(pattern))
			if left < n {
				n = left
			}
			if _, err := w.Write(pattern[:n]); err != nil {
				return
			}
			left -= n
		}
	})
	mux.HandleFunc("/sink", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		n, _ := io.Copy(io.Discard, r.Body)
		o.sink.Add(n)
		_, _ = fmt.Fprintf(w, "%d", n)
	})
	o.srv = &http.Server{Handler: mux, ReadHeaderTimeout: 10e9}
	go func() { _ = o.srv.Serve(ln) }()
	base := "http://" + o.Addr
	o.URLs = LoadURLs{Small: base + "/kb", Big: base + "/big", Sink: base + "/sink", BigBytes: bigBytes}
	return o, nil
}

// SinkBytes is how much the sink has swallowed so far.
func (o *Origin) SinkBytes() int64 { return o.sink.Load() }

// Close stops the server.
func (o *Origin) Close() { _ = o.srv.Close() }
```

- [ ] **Step 4: Run the test**

Run: `go test -race ./internal/gate/ -run TestOrigin` → `ok`; lint clean.

- [ ] **Step 5: Commit**

```bash
git add internal/gate/origin.go internal/gate/origin_test.go
git commit -m "feat(gate): a loopback origin with a small file, a big file and a sink"
```

---

### Task 8: SOCKS helpers and a DNS query

**Files:**
- Create: `internal/gate/socks.go`, `internal/gate/dns.go`
- Test: `internal/gate/socks_test.go`, `internal/gate/dns_test.go`

**Interfaces:**
- Produces: `func SocksDialer(socksAddr string) (DialFunc, error)` (via `golang.org/x/net/proxy`), `func HTTPClient(dial DialFunc, timeout time.Duration) *http.Client` (no keep-alives: every request is a fresh tunnel connect, which is the point), `type UDPAssoc struct{...}`, `func UDPAssociate(ctx context.Context, socksAddr string) (*UDPAssoc, error)`, `(u *UDPAssoc) Send(dst *net.UDPAddr, payload []byte) error`, `(u *UDPAssoc) Recv(buf []byte, deadline time.Time) (payload []byte, from *net.UDPAddr, err error)`, `(u *UDPAssoc) Close()`, `func encodeUDPHeader(dst *net.UDPAddr) []byte`, `func decodeUDPHeader(pkt []byte) (*net.UDPAddr, []byte, error)`; `func BuildDNSQuery(id uint16, name string) []byte`, `func DNSResponseID(pkt []byte) (uint16, bool)`.

- [ ] **Step 1: Write the failing tests**

`socks_test.go`:

```go
package gate

import (
	"bytes"
	"net"
	"testing"
)

func TestUDPHeaderRoundTrip(t *testing.T) {
	dst := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	hdr := encodeUDPHeader(dst)
	if !bytes.Equal(hdr, []byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53}) {
		t.Fatalf("header = %v", hdr)
	}
	back, payload, err := decodeUDPHeader(append(hdr, 'x', 'y'))
	if err != nil || back.String() != "8.8.8.8:53" || string(payload) != "xy" {
		t.Fatalf("decode = %v %q %v", back, payload, err)
	}
	if _, _, err := decodeUDPHeader([]byte{0, 0, 1, 1}); err == nil {
		t.Fatal("fragmented or short header accepted")
	}
}

func TestSocksDialerRejectsBadAddress(t *testing.T) {
	if _, err := SocksDialer("not an address"); err == nil {
		t.Fatal("SocksDialer accepted a bad address")
	}
}
```

`dns_test.go`:

```go
package gate

import "testing"

func TestBuildDNSQueryAndReadID(t *testing.T) {
	q := BuildDNSQuery(0xbeef, "example.com")
	if len(q) != 12+13+4 { // header + 1+7+1+3+1 labels + qtype/qclass
		t.Fatalf("query length %d", len(q))
	}
	if q[0] != 0xbe || q[1] != 0xef || q[2] != 0x01 || q[5] != 1 {
		t.Fatalf("header = %v", q[:6])
	}
	if id, ok := DNSResponseID(q); !ok || id != 0xbeef {
		t.Fatalf("DNSResponseID = %x %v", id, ok)
	}
	if _, ok := DNSResponseID([]byte{1}); ok {
		t.Fatal("short packet accepted")
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestUDPHeader|TestSocksDialer|TestBuildDNS'` → undefined symbols.

- [ ] **Step 3: Implement `socks.go`**

```go
package gate

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/proxy"
)

var errSocksReply = errors.New("unexpected SOCKS5 reply")

// SocksDialer dials TCP through the tunnel's SOCKS5 listener.
func SocksDialer(socksAddr string) (DialFunc, error) {
	if _, _, err := net.SplitHostPort(socksAddr); err != nil {
		return nil, fmt.Errorf("socks address: %w", err)
	}
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, &net.Dialer{Timeout: 10 * time.Second})
	if err != nil {
		return nil, fmt.Errorf("socks dialer: %w", err)
	}
	cd, ok := d.(proxy.ContextDialer)
	if !ok {
		return nil, errors.New("socks dialer lacks DialContext")
	}
	return cd.DialContext, nil
}

// HTTPClient makes every request a fresh connect through the tunnel: the
// connect path is what the load scenarios measure, so no keep-alives.
func HTTPClient(dial DialFunc, timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DialContext:       dial,
			DisableKeepAlives: true,
			MaxIdleConns:      0,
			ForceAttemptHTTP2: false,
		},
	}
}

// UDPAssoc is a SOCKS5 UDP ASSOCIATE: the control connection kept open and
// the relay socket datagrams go through (RFC 1928 §7).
type UDPAssoc struct {
	control net.Conn
	relay   *net.UDPConn
	relayTo *net.UDPAddr
}

// UDPAssociate opens the association on the tunnel's SOCKS listener.
func UDPAssociate(ctx context.Context, socksAddr string) (*UDPAssoc, error) {
	var d net.Dialer
	control, err := d.DialContext(ctx, "tcp4", socksAddr)
	if err != nil {
		return nil, fmt.Errorf("dial socks: %w", err)
	}
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		_ = control.Close()
		return nil, fmt.Errorf("greeting: %w", err)
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(control, reply); err != nil || reply[0] != 5 || reply[1] != 0 {
		_ = control.Close()
		return nil, fmt.Errorf("greeting reply %v: %w", reply, errSocksReply)
	}
	if _, err := control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		_ = control.Close()
		return nil, fmt.Errorf("associate request: %w", err)
	}
	reply = make([]byte, 10)
	if _, err := io.ReadFull(control, reply); err != nil || reply[1] != 0 || reply[3] != 1 {
		_ = control.Close()
		return nil, fmt.Errorf("associate reply %v: %w", reply, errSocksReply)
	}
	relayIP := net.IPv4(reply[4], reply[5], reply[6], reply[7])
	if relayIP.Equal(net.IPv4zero) {
		host, _, _ := net.SplitHostPort(socksAddr)
		relayIP = net.ParseIP(host)
	}
	relayTo := &net.UDPAddr{IP: relayIP, Port: int(binary.BigEndian.Uint16(reply[8:10]))}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = control.Close()
		return nil, fmt.Errorf("relay socket: %w", err)
	}
	return &UDPAssoc{control: control, relay: relay, relayTo: relayTo}, nil
}

// Send relays one datagram to dst through the association.
func (u *UDPAssoc) Send(dst *net.UDPAddr, payload []byte) error {
	pkt := append(encodeUDPHeader(dst), payload...)
	if _, err := u.relay.WriteToUDP(pkt, u.relayTo); err != nil {
		return fmt.Errorf("relay write: %w", err)
	}
	return nil
}

// Recv waits for one relayed datagram until the deadline.
func (u *UDPAssoc) Recv(buf []byte, deadline time.Time) ([]byte, *net.UDPAddr, error) {
	_ = u.relay.SetReadDeadline(deadline)
	n, _, err := u.relay.ReadFromUDP(buf)
	if err != nil {
		return nil, nil, fmt.Errorf("relay read: %w", err)
	}
	return decodeUDPHeaderPayload(buf[:n])
}

func decodeUDPHeaderPayload(pkt []byte) ([]byte, *net.UDPAddr, error) {
	from, payload, err := decodeUDPHeader(pkt)
	if err != nil {
		return nil, nil, err
	}
	return payload, from, nil
}

// Close ends the association; the tunnel drops the relay with the control connection.
func (u *UDPAssoc) Close() {
	_ = u.relay.Close()
	_ = u.control.Close()
}

// encodeUDPHeader builds RSV FRAG ATYP=IPv4 ADDR PORT.
func encodeUDPHeader(dst *net.UDPAddr) []byte {
	hdr := []byte{0, 0, 0, 1}
	hdr = append(hdr, dst.IP.To4()...)
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], uint16(dst.Port)) //nolint:gosec // a port
	return append(hdr, port[:]...)
}

// decodeUDPHeader reads the header of a relayed datagram (IPv4 only, no fragments).
func decodeUDPHeader(pkt []byte) (*net.UDPAddr, []byte, error) {
	if len(pkt) < 10 || pkt[2] != 0 || pkt[3] != 1 {
		return nil, nil, fmt.Errorf("udp header %v: %w", pkt[:min(len(pkt), 4)], errSocksReply)
	}
	addr := &net.UDPAddr{IP: net.IPv4(pkt[4], pkt[5], pkt[6], pkt[7]), Port: int(binary.BigEndian.Uint16(pkt[8:10]))}
	return addr, pkt[10:], nil
}
```

- [ ] **Step 4: Implement `dns.go`**

```go
package gate

import (
	"encoding/binary"
	"strings"
)

// BuildDNSQuery encodes one standard A question with recursion desired.
func BuildDNSQuery(id uint16, name string) []byte {
	q := make([]byte, 12, 12+len(name)+6)
	binary.BigEndian.PutUint16(q[0:2], id)
	q[2] = 0x01 // RD
	q[5] = 1    // QDCOUNT
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0, 0, 1, 0, 1) // root, QTYPE A, QCLASS IN
	return q
}

// DNSResponseID returns the id of a DNS packet, false when it is too short.
func DNSResponseID(pkt []byte) (uint16, bool) {
	if len(pkt) < 12 {
		return 0, false
	}
	return binary.BigEndian.Uint16(pkt[0:2]), true
}
```

- [ ] **Step 5: Run the tests**

Run: `go test -race ./internal/gate/ -run 'TestUDPHeader|TestSocksDialer|TestBuildDNS'` → `ok`; lint clean.

- [ ] **Step 6: Commit**

```bash
git add internal/gate/socks.go internal/gate/dns.go internal/gate/socks_test.go internal/gate/dns_test.go
git commit -m "feat(gate): TCP and UDP through the tunnel's SOCKS listener, and one DNS question"
```

---

### Task 9: Log capture per cell and the scrubber

**Files:**
- Create: `internal/gate/logcapture.go`, `internal/gate/scrub.go`
- Test: `internal/gate/logcapture_test.go`, `internal/gate/scrub_test.go`

**Interfaces:**
- Produces: `type CellLog struct{...}` with `(l *CellLog) Count(substr string) int`, `(l *CellLog) Text() string`, `(l *CellLog) WriteScrubbed(path string, secrets ...string) error`; `type Capture struct{...}`, `func StartCapture() *Capture` (installs itself with `mobile.SetLogWriter`; the engine's `internal/logger` writes through the standard `log` package, which is what `SetLogWriter` redirects, so both flavours are captured), `(c *Capture) Begin() *CellLog` (a fresh log becomes current), `(c *Capture) Stop()` (restores stderr); `func Scrub(text string, secrets ...string) string` replaces every secret with a placeholder: a 64-hex key with `<key>`, anything else with `<room>`.

- [ ] **Step 1: Write the failing tests**

`logcapture_test.go`:

```go
package gate

import (
	"log"
	"testing"
)

func TestCaptureRoutesLinesToTheCurrentCell(t *testing.T) {
	c := StartCapture()
	defer c.Stop()
	first := c.Begin()
	log.Printf("control missed pong role=client missed=1")
	log.Printf("session abc opened (device=x)")
	second := c.Begin()
	log.Printf("control missed pong role=client missed=2")
	if first.Count("control missed pong") != 1 || second.Count("control missed pong") != 1 {
		t.Fatalf("first=%q second=%q", first.Text(), second.Text())
	}
	if first.Count("session abc opened") != 1 || second.Count("session abc opened") != 0 {
		t.Fatalf("lines leaked between cells: %q / %q", first.Text(), second.Text())
	}
}
```

`scrub_test.go`:

```go
package gate

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScrubReplacesRoomsAndKeys(t *testing.T) {
	key := strings.Repeat("ab", 32)
	room := "https://meet.example.org/gate-7f3a9c"
	in := "jitsi: joining MUC meet.example.org/gate-7f3a9c as X … key=" + key + " room=" + room
	out := Scrub(in, key, room, "gate-7f3a9c")
	if strings.Contains(out, key) || strings.Contains(out, "gate-7f3a9c") {
		t.Fatalf("scrubbed = %q", out)
	}
	if !strings.Contains(out, "<key>") || !strings.Contains(out, "<room>") {
		t.Fatalf("placeholders missing: %q", out)
	}
	if Scrub("plain", "") != "plain" {
		t.Fatal("empty secret must be ignored")
	}
}

func TestCellLogWriteScrubbed(t *testing.T) {
	c := StartCapture()
	defer c.Stop()
	l := c.Begin()
	logPrintf("joined room secret-room-1 with %s", strings.Repeat("cd", 32))
	p := filepath.Join(t.TempDir(), "cell.log")
	if err := l.WriteScrubbed(p, strings.Repeat("cd", 32), "secret-room-1"); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(p)
	if strings.Contains(string(raw), "secret-room-1") || strings.Contains(string(raw), "cdcd") {
		t.Fatalf("file = %q", string(raw))
	}
}
```

with `func logPrintf(format string, a ...any) { log.Printf(format, a...) }` in the test file (import `log`).

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestCapture|TestScrub|TestCellLog'` → undefined symbols.

- [ ] **Step 3: Implement `logcapture.go`**

```go
package gate

import (
	"os"
	"strings"
	"sync"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

// CellLog is every engine log line written while a cell was current.
type CellLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *CellLog) add(line string) {
	l.mu.Lock()
	l.lines = append(l.lines, line)
	l.mu.Unlock()
}

// Count returns how many captured lines contain substr.
func (l *CellLog) Count(substr string) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := 0
	for _, line := range l.lines {
		if strings.Contains(line, substr) {
			n++
		}
	}
	return n
}

// Text returns the captured lines joined.
func (l *CellLog) Text() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "")
}

// WriteScrubbed stores the lines with the run's secrets replaced.
func (l *CellLog) WriteScrubbed(path string, secrets ...string) error {
	return os.WriteFile(path, []byte(Scrub(l.Text(), secrets...)), 0o644) //nolint:gosec // an artifact
}

// Capture routes the process log to the current cell. The engine's logger
// writes through the standard log package, and mobile.SetLogWriter is the
// engine's own switch for that output, so one capture serves both flavours.
type Capture struct {
	mu      sync.Mutex
	current *CellLog
	stderr  bool
}

// StartCapture installs the capture; lines before the first Begin go to stderr.
func StartCapture() *Capture {
	c := &Capture{}
	mobile.SetLogWriter(c)
	return c
}

// WriteLog implements mobile.LogWriter.
func (c *Capture) WriteLog(msg string) {
	c.mu.Lock()
	cur := c.current
	c.mu.Unlock()
	if cur == nil {
		_, _ = os.Stderr.WriteString(msg)
		return
	}
	cur.add(msg)
}

// Begin makes a fresh log the current cell's.
func (c *Capture) Begin() *CellLog {
	l := &CellLog{}
	c.mu.Lock()
	c.current = l
	c.mu.Unlock()
	return l
}

// Stop sends the log back to stderr.
func (c *Capture) Stop() {
	c.mu.Lock()
	c.current = nil
	c.mu.Unlock()
	mobile.SetLogWriter(nil)
}
```

- [ ] **Step 4: Implement `scrub.go`**

```go
package gate

import (
	"regexp"
	"strings"
)

var hex64 = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)

// Scrub replaces every secret of the run in text: keys become <key>, every
// other secret (a room URL, a room id, a slug) becomes <room>. Longer secrets
// are replaced first so a slug inside a URL cannot leave the URL's host behind.
func Scrub(text string, secrets ...string) string {
	sorted := make([]string, 0, len(secrets))
	for _, s := range secrets {
		if s != "" {
			sorted = append(sorted, s)
		}
	}
	for i := 1; i < len(sorted); i++ {
		for j := i; j > 0 && len(sorted[j]) > len(sorted[j-1]); j-- {
			sorted[j], sorted[j-1] = sorted[j-1], sorted[j]
		}
	}
	for _, s := range sorted {
		placeholder := "<room>"
		if hex64.MatchString(s) {
			placeholder = "<key>"
		}
		text = strings.ReplaceAll(text, s, placeholder)
	}
	return text
}
```

- [ ] **Step 5: Run the tests**

Run: `go test -race ./internal/gate/ -run 'TestCapture|TestScrub|TestCellLog'` → `ok`; lint clean.

- [ ] **Step 6: Commit**

```bash
git add internal/gate/logcapture.go internal/gate/scrub.go internal/gate/logcapture_test.go internal/gate/scrub_test.go
git commit -m "feat(gate): capture the engine log per cell and scrub rooms and keys before it leaves"
```

---

### Task 10: Rooms and the two targets

**Files:**
- Create: `internal/gate/rooms.go`, `internal/gate/target_local.go`, `internal/gate/target_link.go`
- Test: `internal/gate/rooms_test.go`, `internal/gate/target_local_test.go`, `internal/gate/target_link_test.go`

**Interfaces:**
- Produces: `func NewKey() (string, error)` (64 hex from crypto/rand); `func JitsiRoom(ctx context.Context, instancesPath string, probe func(ctx context.Context, host string) bool) (string, error)` (first reachable instance, room `https://<host>/gate-<12 hex>`); `func ProbeHTTPS(ctx context.Context, host string) bool`; `func PoolRoom(pool []string, runNumber int) (string, error)`; `func TelemostURL(idOrURL string) string`.
- `type LocalTarget struct{...}`, `func NewLocalTarget(opts LocalOptions) *LocalTarget` with `type LocalOptions struct{ ModuleRoot, WorkDir, Instances string; TelemostRooms, WBStreamRooms []string; RunNumber int; Providers, Transports []string; DNS string; BigBytes int64; Origin *Origin }`, `(t *LocalTarget) Build(ctx) error` (once: `go build -tags olcrtc_testhooks -o WorkDir/olcrtc ./cmd/olcrtc`), `(t *LocalTarget) Open(...)` starts the child and waits for `Link connected` in its log (60 s), `func RenderServerConfig(ep Endpoint) string` (YAML), `func WaitForLine(path, substr string, timeout time.Duration) error`.
- `type LinkTarget struct{...}`, `func NewLinkTarget(l link.Link, load LoadURLs) *LinkTarget`: one pair, `Open` returns the endpoint from the link and checks the pair matches; `Load()` returns the public URLs.

- [ ] **Step 1: Write the failing tests**

`rooms_test.go`:

```go
package gate

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestJitsiRoomPicksTheFirstReachableInstance(t *testing.T) {
	p := filepath.Join(t.TempDir(), "instances.yaml")
	_ = os.WriteFile(p, []byte("instances:\n  - down.example\n  - up.example\n  - other.example\n"), 0o600)
	probe := func(_ context.Context, host string) bool { return host == "up.example" }
	room, err := JitsiRoom(context.Background(), p, probe)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(room, "https://up.example/gate-") || len(room) != len("https://up.example/gate-")+12 {
		t.Fatalf("room = %q", room)
	}
	if _, err := JitsiRoom(context.Background(), p, func(context.Context, string) bool { return false }); err == nil {
		t.Fatal("no reachable instance must be an error")
	}
}

func TestPoolRoomAndKeys(t *testing.T) {
	pool := []string{"a", "b", "c"}
	if r, _ := PoolRoom(pool, 7); r != "b" {
		t.Fatalf("PoolRoom(7) = %q", r)
	}
	if _, err := PoolRoom(nil, 1); err == nil {
		t.Fatal("empty pool accepted")
	}
	k, err := NewKey()
	if err != nil || len(k) != 64 || strings.Trim(k, "0123456789abcdef") != "" {
		t.Fatalf("NewKey = %q %v", k, err)
	}
	if TelemostURL("5092358972") != "https://telemost.yandex.ru/j/5092358972" || TelemostURL("https://x/y") != "https://x/y" {
		t.Fatal("TelemostURL")
	}
}
```

`target_local_test.go`:

```go
package gate

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRenderServerConfigGolden(t *testing.T) {
	ep := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: strings.Repeat("ab", 32), DNS: "8.8.8.8:53", VP8FPS: 60, VP8Batch: 64}
	want := `mode: srv
auth: { provider: telemost }
room: { id: "https://telemost.yandex.ru/j/1" }
crypto: { key: "` + strings.Repeat("ab", 32) + `" }
net: { transport: vp8channel, dns: "8.8.8.8:53" }
vp8: { fps: 60, batch_size: 64 }
debug: true
`
	if got := RenderServerConfig(ep); got != want {
		t.Fatalf("config:\n%s\nwant:\n%s", got, want)
	}
	sei := RenderServerConfig(Endpoint{Provider: "jitsi", Transport: "seichannel", Room: "r", Key: strings.Repeat("ab", 32), DNS: "1.1.1.1:53"})
	if !strings.Contains(sei, "sei: { fps: 60, batch_size: 64, fragment_size: 900, ack_timeout_ms: 2000 }") || strings.Contains(sei, "vp8:") {
		t.Fatalf("sei config:\n%s", sei)
	}
}

func TestWaitForLine(t *testing.T) {
	p := filepath.Join(t.TempDir(), "srv.log")
	_ = os.WriteFile(p, []byte("starting\n"), 0o600)
	go func() {
		time.Sleep(100 * time.Millisecond)
		f, _ := os.OpenFile(p, os.O_APPEND|os.O_WRONLY, 0o600)
		_, _ = f.WriteString("2026/09/15 Link connected\n")
		_ = f.Close()
	}()
	if err := WaitForLine(p, "Link connected", 3*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := WaitForLine(p, "never", 200*time.Millisecond); err == nil {
		t.Fatal("WaitForLine returned without the line")
	}
}

func TestLocalTargetPairsFollowProvidersAndTransports(t *testing.T) {
	lt := NewLocalTarget(LocalOptions{Providers: []string{"jitsi", "telemost"}, Transports: []string{"datachannel", "vp8channel"}})
	got := lt.Pairs()
	if len(got) != 4 || got[0] != (Pair{"jitsi", "datachannel"}) || got[3] != (Pair{"telemost", "vp8channel"}) {
		t.Fatalf("Pairs = %v", got)
	}
	if lt.Platform() != "engine-linux" || lt.Name() != "local" {
		t.Fatal("names")
	}
}
```

`target_link_test.go`:

```go
package gate

import (
	"context"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/link"
)

func TestLinkTargetIsOnePairFromTheLink(t *testing.T) {
	l := link.Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: strings.Repeat("ab", 32), VP8FPS: 60, VP8Batch: 64}
	load := LoadURLs{Small: "https://proofkit.org/gate/kb", Big: "https://proofkit.org/gate/10mb.bin", Sink: "https://speed.cloudflare.com/__up", BigBytes: 10 << 20}
	lt := NewLinkTarget(l, load, "8.8.8.8:53")
	if got := lt.Pairs(); len(got) != 1 || got[0] != (Pair{"telemost", "vp8channel"}) {
		t.Fatalf("Pairs = %v", got)
	}
	ep, stop, err := lt.Open(context.Background(), Pair{"telemost", "vp8channel"}, t.TempDir(), OpenOptions{})
	if err != nil {
		t.Fatal(err)
	}
	stop()
	if ep.Room != l.Room || ep.Key != l.Key || ep.VP8FPS != 60 || ep.DNS != "8.8.8.8:53" {
		t.Fatalf("endpoint = %+v", ep)
	}
	if _, _, err := lt.Open(context.Background(), Pair{"jitsi", "datachannel"}, t.TempDir(), OpenOptions{}); err == nil {
		t.Fatal("a pair the link does not carry must be refused")
	}
	if lt.Load() != load || lt.Platform() != "engine-linux" || lt.Name() != "link" {
		t.Fatal("names/load")
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestJitsi|TestPool|TestRender|TestWaitFor|TestLocalTarget|TestLinkTarget'` → undefined symbols.

- [ ] **Step 3: Implement `rooms.go`**

```go
package gate

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

var errNoInstance = errors.New("no reachable jitsi instance")

// NewKey returns a fresh 64-hex session key.
func NewKey() (string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("random key: %w", err)
	}
	return hex.EncodeToString(raw[:]), nil
}

// JitsiRoom picks the first instance in the list that answers over HTTPS and
// names a fresh room on it. Jitsi conjures rooms on first join.
func JitsiRoom(ctx context.Context, instancesPath string, probe func(ctx context.Context, host string) bool) (string, error) {
	raw, err := os.ReadFile(instancesPath) //nolint:gosec // a repository file
	if err != nil {
		return "", fmt.Errorf("read instances: %w", err)
	}
	var doc struct {
		Instances []string `yaml:"instances"`
	}
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return "", fmt.Errorf("parse instances: %w", err)
	}
	var slug [6]byte
	if _, err := rand.Read(slug[:]); err != nil {
		return "", fmt.Errorf("random slug: %w", err)
	}
	for _, host := range doc.Instances {
		host = strings.TrimSpace(host)
		if host == "" || !probe(ctx, host) {
			continue
		}
		return "https://" + host + "/gate-" + hex.EncodeToString(slug[:]), nil
	}
	return "", errNoInstance
}

// ProbeHTTPS is the default probe: one GET of the front page within 5 s.
func ProbeHTTPS(ctx context.Context, host string) bool {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+host+"/", nil)
	if err != nil {
		return false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return false
	}
	_ = resp.Body.Close()
	return resp.StatusCode < 500
}

// PoolRoom takes one room of a pre-made pool by run number, so concurrent
// runs (bounded by the workflow's concurrency group) never share one.
func PoolRoom(pool []string, runNumber int) (string, error) {
	if len(pool) == 0 {
		return "", errors.New("empty room pool")
	}
	if runNumber < 0 {
		runNumber = -runNumber
	}
	return strings.TrimSpace(pool[runNumber%len(pool)]), nil
}

// TelemostURL accepts a bare room id or a full join URL.
func TelemostURL(idOrURL string) string {
	if strings.HasPrefix(idOrURL, "http://") || strings.HasPrefix(idOrURL, "https://") {
		return idOrURL
	}
	return "https://telemost.yandex.ru/j/" + idOrURL
}
```

- [ ] **Step 4: Implement `target_local.go`**

```go
package gate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// LocalOptions configures a target whose servers are child processes.
type LocalOptions struct {
	ModuleRoot    string   // the engine repository root, where go build runs
	WorkDir       string   // binary, configs and logs
	Instances     string   // docs/jitsi.instances.yaml
	TelemostRooms []string // pool, from secrets
	WBStreamRooms []string // pool, from secrets
	RunNumber     int
	Providers     []string
	Transports    []string
	DNS           string
	BigBytes      int64
	Origin        *Origin
}

// LocalTarget builds cmd/olcrtc once and runs it as mode: srv per pair.
type LocalTarget struct {
	opts    LocalOptions
	buildMu sync.Mutex
	binary  string
	probe   func(ctx context.Context, host string) bool
}

// NewLocalTarget prepares the target; Build compiles the server.
func NewLocalTarget(opts LocalOptions) *LocalTarget {
	if opts.DNS == "" {
		opts.DNS = "8.8.8.8:53"
	}
	return &LocalTarget{opts: opts, probe: ProbeHTTPS}
}

func (t *LocalTarget) Name() string     { return "local" }
func (t *LocalTarget) Platform() string { return "engine-linux" }

// Pairs is providers × transports in the order given.
func (t *LocalTarget) Pairs() []Pair {
	var out []Pair
	for _, p := range t.opts.Providers {
		for _, tr := range t.opts.Transports {
			out = append(out, Pair{Provider: p, Transport: tr})
		}
	}
	return out
}

// Load is the loopback origin's URLs.
func (t *LocalTarget) Load() LoadURLs { return t.opts.Origin.URLs }

// Build compiles the server with the test hooks into WorkDir.
func (t *LocalTarget) Build(ctx context.Context) error {
	t.buildMu.Lock()
	defer t.buildMu.Unlock()
	if t.binary != "" {
		return nil
	}
	out := filepath.Join(t.opts.WorkDir, "olcrtc")
	cmd := exec.CommandContext(ctx, "go", "build", "-tags", "olcrtc_testhooks", "-o", out, "./cmd/olcrtc") //nolint:gosec // fixed arguments
	cmd.Dir = t.opts.ModuleRoot
	if b, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("build server: %w\n%s", err, b)
	}
	t.binary = out
	return nil
}

// Open picks a room for the pair, writes the server config, starts the child
// with its log in dir, and returns once the server reports Link connected.
func (t *LocalTarget) Open(ctx context.Context, p Pair, dir string, opt OpenOptions) (Endpoint, func(), error) {
	if err := t.Build(ctx); err != nil {
		return Endpoint{}, nil, err
	}
	room, err := t.room(ctx, p.Provider)
	if err != nil {
		return Endpoint{}, nil, err
	}
	key, err := NewKey()
	if err != nil {
		return Endpoint{}, nil, err
	}
	ep := Endpoint{Provider: p.Provider, Transport: p.Transport, Room: room, Key: key, DNS: t.opts.DNS, VP8FPS: 60, VP8Batch: 64}
	cfgPath := filepath.Join(dir, "srv.yaml")
	if err := os.WriteFile(cfgPath, []byte(RenderServerConfig(ep)), 0o600); err != nil {
		return Endpoint{}, nil, fmt.Errorf("write server config: %w", err)
	}
	logPath := filepath.Join(dir, "srv.log")
	logFile, err := os.Create(logPath) //nolint:gosec // an artifact path
	if err != nil {
		return Endpoint{}, nil, fmt.Errorf("create server log: %w", err)
	}
	runCtx, cancel := context.WithCancel(ctx)
	cmd := exec.CommandContext(runCtx, t.binary, cfgPath) //nolint:gosec // our own binary
	cmd.Stdout, cmd.Stderr = logFile, logFile
	cmd.Env = append(os.Environ(), "OLCRTC_TEST_BRIDGE_DELAY="+opt.BridgeDelay.String())
	if err := cmd.Start(); err != nil {
		cancel()
		_ = logFile.Close()
		return Endpoint{}, nil, fmt.Errorf("start server: %w", err)
	}
	stop := func() {
		cancel()
		_ = cmd.Wait()
		_ = logFile.Close()
	}
	if err := WaitForLine(logPath, "Link connected", 60*time.Second); err != nil {
		stop()
		return Endpoint{}, nil, fmt.Errorf("server for %s: %w", p, err)
	}
	return ep, stop, nil
}

func (t *LocalTarget) room(ctx context.Context, provider string) (string, error) {
	switch provider {
	case "jitsi":
		return JitsiRoom(ctx, t.opts.Instances, t.probe)
	case "telemost":
		r, err := PoolRoom(t.opts.TelemostRooms, t.opts.RunNumber)
		if err != nil {
			return "", fmt.Errorf("telemost: %w", err)
		}
		return TelemostURL(r), nil
	case "wbstream":
		r, err := PoolRoom(t.opts.WBStreamRooms, t.opts.RunNumber)
		if err != nil {
			return "", fmt.Errorf("wbstream: %w", err)
		}
		return r, nil
	default:
		return "", fmt.Errorf("provider %q: %w", provider, errors.ErrUnsupported)
	}
}

// RenderServerConfig writes the mode: srv YAML for an endpoint. Options for
// vp8channel and seichannel are the app's defaults.
func RenderServerConfig(ep Endpoint) string {
	var b strings.Builder
	b.WriteString("mode: srv\n")
	fmt.Fprintf(&b, "auth: { provider: %s }\n", ep.Provider)
	fmt.Fprintf(&b, "room: { id: %q }\n", ep.Room)
	fmt.Fprintf(&b, "crypto: { key: %q }\n", ep.Key)
	fmt.Fprintf(&b, "net: { transport: %s, dns: %q }\n", ep.Transport, ep.DNS)
	switch ep.Transport {
	case "vp8channel":
		fps, batch := ep.VP8FPS, ep.VP8Batch
		if fps == 0 {
			fps = 60
		}
		if batch == 0 {
			batch = 64
		}
		fmt.Fprintf(&b, "vp8: { fps: %d, batch_size: %d }\n", fps, batch)
	case "seichannel":
		b.WriteString("sei: { fps: 60, batch_size: 64, fragment_size: 900, ack_timeout_ms: 2000 }\n")
	}
	b.WriteString("debug: true\n")
	return b.String()
}

// WaitForLine polls a log file until it contains substr.
func WaitForLine(path, substr string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		raw, err := os.ReadFile(path) //nolint:gosec // an artifact path
		if err == nil && strings.Contains(string(raw), substr) {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("%q not seen in %s within %s", substr, filepath.Base(path), timeout)
		}
		time.Sleep(500 * time.Millisecond)
	}
}
```

- [ ] **Step 5: Implement `target_link.go`**

```go
package gate

import (
	"context"
	"fmt"

	"github.com/openlibrecommunity/olcrtc/internal/link"
)

// LinkTarget is a server somebody else runs: the fleet's node behind an
// olcrtc:// link. It offers exactly the one pair the link names.
type LinkTarget struct {
	l    link.Link
	load LoadURLs
	dns  string
}

// NewLinkTarget wraps a parsed link and the public load URLs.
func NewLinkTarget(l link.Link, load LoadURLs, dns string) *LinkTarget {
	return &LinkTarget{l: l, load: load, dns: dns}
}

func (t *LinkTarget) Name() string     { return "link" }
func (t *LinkTarget) Platform() string { return "engine-linux" }
func (t *LinkTarget) Pairs() []Pair    { return []Pair{{Provider: t.l.Provider, Transport: t.l.Transport}} }
func (t *LinkTarget) Load() LoadURLs   { return t.load }

// Open returns the link's endpoint; there is no server to start or stop.
func (t *LinkTarget) Open(_ context.Context, p Pair, _ string, _ OpenOptions) (Endpoint, func(), error) {
	if p.Provider != t.l.Provider || p.Transport != t.l.Transport {
		return Endpoint{}, nil, fmt.Errorf("link carries %s/%s, not %s", t.l.Provider, t.l.Transport, p)
	}
	return Endpoint{
		Provider: t.l.Provider, Transport: t.l.Transport, Room: t.l.Room, Key: t.l.Key,
		DNS: t.dns, VP8FPS: t.l.VP8FPS, VP8Batch: t.l.VP8Batch,
	}, func() {}, nil
}
```

- [ ] **Step 6: Run the tests**

Run: `go test -race ./internal/gate/ -run 'TestJitsi|TestPool|TestRender|TestWaitFor|TestLocalTarget|TestLinkTarget'` → `ok`; lint clean.

- [ ] **Step 7: Commit**

```bash
git add internal/gate/rooms.go internal/gate/target_local.go internal/gate/target_link.go internal/gate/rooms_test.go internal/gate/target_local_test.go internal/gate/target_link_test.go
git commit -m "feat(gate): rooms from a probed instance list or a pool, a child-process server and a link target"
```

---

### Task 11: The two client flavours

**Files:**
- Create: `internal/gate/client_cli.go`, `internal/gate/client_mobile.go` (`//go:build olcrtc_lean`), `internal/gate/client_mobile_off.go` (`//go:build !olcrtc_lean`)
- Test: `internal/gate/client_test.go`

**Interfaces:**
- Produces: `func CLIClient() Client`, `func MobileClient() Client` (nil without the lean tag), `func cliConfig(ep Endpoint) client.Config` (for the test), `func freePort() (int, error)`.

- [ ] **Step 1: Write the failing tests**

```go
package gate

import (
	"context"
	"strings"
	"testing"
	"time"

	client "github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

func TestCLIConfigMirrorsTheEndpoint(t *testing.T) {
	ep := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: strings.Repeat("ab", 32), DNS: "8.8.8.8:53", VP8FPS: 60, VP8Batch: 64}
	cfg := cliConfig(ep)
	if cfg.Provider != "telemost" || cfg.Transport != "vp8channel" || cfg.RoomURL != ep.Room || cfg.KeyHex != ep.Key {
		t.Fatalf("cfg = %+v", cfg)
	}
	if cfg.LocalAddr != "127.0.0.1:0" || cfg.DNSServer != "8.8.8.8:53" || cfg.DeviceID != "gate-cli" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if opts, ok := cfg.TransportOptions.(client.VP8Options); !ok || opts.FPS != 60 || opts.BatchSize != 64 {
		t.Fatalf("vp8 options = %#v", cfg.TransportOptions)
	}
	sei := cliConfig(Endpoint{Provider: "jitsi", Transport: "seichannel", Room: "r", Key: ep.Key, DNS: ep.DNS})
	if opts, ok := sei.TransportOptions.(client.SEIOptions); !ok || opts.FragmentSize != 900 || opts.AckTimeoutMS != 2000 {
		t.Fatalf("sei options = %#v", sei.TransportOptions)
	}
	if cliConfig(Endpoint{Transport: "datachannel"}).TransportOptions != nil {
		t.Fatal("datachannel must carry no options")
	}
}

func TestCLIClientRefusesABadKeyFast(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err := CLIClient().Start(ctx, Endpoint{Provider: "jitsi", Transport: "datachannel", Room: "https://x/y", Key: "short", DNS: "8.8.8.8:53"})
	if err == nil {
		t.Fatal("a short key must fail before any network")
	}
}

func TestMobileClientPresenceFollowsTheTag(t *testing.T) {
	if (MobileClient() != nil) != leanBuild {
		t.Fatalf("MobileClient() present=%v, lean build=%v", MobileClient() != nil, leanBuild)
	}
}
```

plus `internal/gate/lean_test.go` (`//go:build olcrtc_lean`, `const leanBuild = true`) and `internal/gate/lean_off_test.go` (`//go:build !olcrtc_lean`, `const leanBuild = false`), the pattern `internal/e2e` already uses.

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestCLI|TestMobileClient'` → undefined symbols.

- [ ] **Step 3: Implement `client_cli.go`**

```go
package gate

import (
	"context"
	"errors"
	"fmt"
	"net"
	"time"

	client "github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// startBudget is how long a flavour may take to report a listening SOCKS
// port: the relay join, the handshake and its resends.
const startBudget = 60 * time.Second

type cliClient struct{}

// CLIClient runs what cmd/olcrtc runs in mode cnc: the public client.
func CLIClient() Client { return cliClient{} }

func (cliClient) Name() string { return "cli" }

// cliConfig maps an endpoint to the public client's config the way the CLI's
// YAML would, with an ephemeral SOCKS port.
func cliConfig(ep Endpoint) client.Config {
	cfg := client.Config{
		Transport: ep.Transport, Provider: ep.Provider, RoomURL: ep.Room, KeyHex: ep.Key,
		LocalAddr: "127.0.0.1:0", DNSServer: ep.DNS, DeviceID: "gate-cli",
	}
	switch ep.Transport {
	case "vp8channel":
		fps, batch := ep.VP8FPS, ep.VP8Batch
		if fps == 0 {
			fps = 60
		}
		if batch == 0 {
			batch = 64
		}
		cfg.TransportOptions = client.VP8Options{FPS: fps, BatchSize: batch}
	case "seichannel":
		cfg.TransportOptions = client.SEIOptions{FPS: 60, BatchSize: 64, FragmentSize: 900, AckTimeoutMS: 2000}
	}
	return cfg
}

// Start runs the client until Stop; it returns once the SOCKS port listens.
func (cliClient) Start(ctx context.Context, ep Endpoint) (*Tunnel, error) {
	if len(ep.Key) != 64 {
		return nil, errors.New("key must be 64 hex characters")
	}
	runCtx, cancel := context.WithCancel(ctx)
	ready := make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- client.New(cliConfig(ep)).RunWithAddress(runCtx, func(addr string) { ready <- addr })
	}()
	select {
	case addr := <-ready:
		return &Tunnel{SocksAddr: addr, Stop: func() { cancel(); <-done }}, nil
	case err := <-done:
		cancel()
		return nil, fmt.Errorf("cli client: %w", err)
	case <-time.After(startBudget):
		cancel()
		<-done
		return nil, fmt.Errorf("cli client: not ready within %s", startBudget)
	}
}

// freePort asks the kernel for a port and releases it; the phone flavour
// needs a number because mobile.Runtime refuses port 0.
func freePort() (int, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("free port: %w", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port, nil
}
```

- [ ] **Step 4: Implement `client_mobile.go` and `client_mobile_off.go`**

`client_mobile.go`:

```go
//go:build olcrtc_lean

package gate

import (
	"context"
	"fmt"
	"strconv"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

type mobileClient struct{}

// MobileClient runs mobile.Runtime the way OlcboxVpnService and the iOS
// provider do. Only the lean build has it, because only the lean build is
// what the phones run.
func MobileClient() Client { return mobileClient{} }

func (mobileClient) Name() string { return "mobile" }

func (mobileClient) Start(ctx context.Context, ep Endpoint) (*Tunnel, error) {
	port, err := freePort()
	if err != nil {
		return nil, err
	}
	rt := mobile.New()
	steps := []struct {
		name string
		err  error
	}{
		{"provider", rt.SetProvider(ep.Provider)},
		{"transport", rt.SetTransport(ep.Transport)},
		{"room", rt.SetRoom(ep.Room)},
		{"key", rt.SetKey(ep.Key)},
		{"dns", rt.SetDNS(ep.DNS)},
		{"socks host", rt.SetSocksListenHost("127.0.0.1")},
		{"socks port", rt.SetSocksPort(port)},
		{"vp8", rt.SetVP8Options(vp8Or(ep.VP8FPS, 60), vp8Or(ep.VP8Batch, 64))},
	}
	for _, s := range steps {
		if s.err != nil {
			return nil, fmt.Errorf("mobile %s: %w", s.name, s.err)
		}
	}
	rt.SetDeviceID("gate-mobile")
	if err := rt.Start(); err != nil {
		return nil, fmt.Errorf("mobile start: %w", err)
	}
	if err := rt.WaitReady(int(startBudget.Milliseconds())); err != nil {
		_ = rt.Stop(10_000)
		return nil, fmt.Errorf("mobile ready: %w", err)
	}
	_ = ctx
	return &Tunnel{SocksAddr: "127.0.0.1:" + strconv.Itoa(port), Stop: func() { _ = rt.Stop(10_000) }}, nil
}

func vp8Or(v, def int) int {
	if v == 0 {
		return def
	}
	return v
}
```

`client_mobile_off.go`:

```go
//go:build !olcrtc_lean

package gate

// MobileClient is nil in a build without the lean tag: the phones' flavour
// only exists in the phones' build.
func MobileClient() Client { return nil }
```

- [ ] **Step 5: Run the tests under both tags**

Run: `go test -race ./internal/gate/ -run 'TestCLI|TestMobileClient' && go test -race -tags olcrtc_lean ./internal/gate/ -run 'TestCLI|TestMobileClient'` → both `ok`; lint clean in both (`golangci-lint run --build-tags olcrtc_lean ./internal/gate/`).

- [ ] **Step 6: Commit**

```bash
git add internal/gate/client_cli.go internal/gate/client_mobile.go internal/gate/client_mobile_off.go internal/gate/client_test.go internal/gate/lean_test.go internal/gate/lean_off_test.go
git commit -m "feat(gate): two client flavours, the CLI's path and the phone's mobile.Runtime"
```

---

### Task 12: Load helpers and the scenarios S0–S7

**Files:**
- Create: `internal/gate/load.go`, `internal/gate/scenarios.go`
- Test: `internal/gate/load_test.go`, `internal/gate/scenarios_test.go`

**Interfaces:**
- Produces (`load.go`): `type Outcome struct{ OK int; Total int; P95 time.Duration; Bytes int64; Took time.Duration }`, `func Pull(ctx, hc *http.Client, url string, want int64) (int64, error)`, `func Push(ctx, hc *http.Client, url string, size int64) error`, `func ConnectBurst(ctx, hc *http.Client, url string, concurrent, sequential int) Outcome`, `func Parallel(ctx, n int, each func(ctx context.Context, i int) (int64, error)) Outcome`, `func OnTop(ctx, hc *http.Client, url string, every time.Duration) (stop func() Outcome)`, `func p95(ds []time.Duration) time.Duration`, `func bps(bytes int64, took time.Duration) float64`.
- Produces (`scenarios.go`): the eight registered scenarios; `func hasBigLocalOrigin(t Target) bool`; helper `env.Logf`.

- [ ] **Step 1: Write the failing tests**

`load_test.go` runs the helpers straight against the origin (no tunnel):

```go
package gate

import (
	"context"
	"net"
	"testing"
	"time"
)

func directHTTP() (DialFunc, *Origin) {
	o, err := StartOrigin(2 << 20)
	if err != nil {
		panic(err)
	}
	var d net.Dialer
	return d.DialContext, o
}

func TestPullPushAndBurstAgainstTheOrigin(t *testing.T) {
	dial, o := directHTTP()
	defer o.Close()
	hc := HTTPClient(dial, 30*time.Second)
	ctx := context.Background()
	n, err := Pull(ctx, hc, o.URLs.Big, o.URLs.BigBytes)
	if err != nil || n != 2<<20 {
		t.Fatalf("Pull = %d %v", n, err)
	}
	if _, err := Pull(ctx, hc, o.URLs.Big, 1); err == nil {
		t.Fatal("Pull must fail when the size differs")
	}
	if err := Push(ctx, hc, o.URLs.Sink, 300_000); err != nil || o.SinkBytes() != 300_000 {
		t.Fatalf("Push = %v, sink %d", err, o.SinkBytes())
	}
	out := ConnectBurst(ctx, hc, o.URLs.Small, 8, 4)
	if out.OK != 12 || out.Total != 12 || out.P95 <= 0 {
		t.Fatalf("ConnectBurst = %+v", out)
	}
}

func TestOnTopCountsConnectsUntilStopped(t *testing.T) {
	dial, o := directHTTP()
	defer o.Close()
	hc := HTTPClient(dial, 10*time.Second)
	stop := OnTop(context.Background(), hc, o.URLs.Small, 30*time.Millisecond)
	time.Sleep(200 * time.Millisecond)
	out := stop()
	if out.Total < 4 || out.OK != out.Total {
		t.Fatalf("OnTop = %+v", out)
	}
}

func TestP95AndBps(t *testing.T) {
	ds := make([]time.Duration, 0, 100)
	for i := 1; i <= 100; i++ {
		ds = append(ds, time.Duration(i)*time.Millisecond)
	}
	if got := p95(ds); got != 95*time.Millisecond {
		t.Fatalf("p95 = %v", got)
	}
	if p95(nil) != 0 {
		t.Fatal("p95 of nothing")
	}
	if got := bps(1_000_000, 4*time.Second); got != 2_000_000 {
		t.Fatalf("bps = %v", got)
	}
}
```

`scenarios_test.go` runs the scenario bodies with a direct dialer, a captured log and a sampler — the logic, not the tunnel:

```go
package gate

import (
	"context"
	"testing"
	"time"
)

func directEnv(t *testing.T) (*Env, *Origin) {
	t.Helper()
	dial, o := directHTTP()
	t.Cleanup(o.Close)
	c := StartCapture()
	t.Cleanup(c.Stop)
	s := NewSampler(20 * time.Millisecond)
	s.Start()
	t.Cleanup(s.Stop)
	return &Env{
		Load: o.URLs, HTTP: HTTPClient(dial, 30*time.Second), Dial: dial,
		Sampler: s, Thresholds: Local, Log: c.Begin(), Dir: t.TempDir(), Logf: t.Logf,
	}, o
}

func scenario(t *testing.T, id string) Scenario {
	t.Helper()
	for _, s := range Scenarios() {
		if s.ID == id {
			return s
		}
	}
	t.Fatalf("scenario %s not registered", id)
	return Scenario{}
}

func TestS0S1S2S3PassAgainstTheOrigin(t *testing.T) {
	env, _ := directEnv(t)
	env.Tunnel = &Tunnel{SocksAddr: "direct"}
	env.Endpoint = Endpoint{Provider: "test", Transport: "datachannel"}
	for _, id := range []string{"S0", "S1", "S2", "S3"} {
		m, err := scenario(t, id).Run(context.Background(), env)
		if err != nil {
			t.Fatalf("%s error = %v", id, err)
		}
		if f := Evaluate(id, m, env.Thresholds); len(f) != 0 {
			t.Fatalf("%s failed on loopback: %v (metrics %v)", id, f, m)
		}
	}
}

func TestS4ReadsTheCapturedLog(t *testing.T) {
	env, _ := directEnv(t)
	quietAfterLoad = 50 * time.Millisecond
	t.Cleanup(func() { quietAfterLoad = 60 * time.Second })
	logPrintf("control missed pong role=client missed=1")
	m, err := scenario(t, "S4").Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	if m["missed_pong"] != 1 || m["alive"] != 1 || m["final_pull_ok"] != 1 {
		t.Fatalf("S4 metrics = %v", m)
	}
}

func TestS7ReadsPeaksBetweenMarks(t *testing.T) {
	env, _ := directEnv(t)
	env.Sampler.Mark("S2-start")
	time.Sleep(60 * time.Millisecond)
	env.Sampler.Mark("S4-end")
	env.Sampler.Mark("goroutines_idle")
	m, err := scenario(t, "S7").Run(context.Background(), env)
	if err != nil {
		t.Fatal(err)
	}
	if m["heap_peak_bytes"] <= 0 || m["rss_peak_bytes"] <= 0 || m["goroutines_after"] <= 0 {
		t.Fatalf("S7 metrics = %v", m)
	}
}

func TestSixApplyToJitsiDatachannelLocalOnly(t *testing.T) {
	s6 := scenario(t, "S6")
	lt := NewLocalTarget(LocalOptions{})
	if !s6.Applies(lt, Pair{"jitsi", "datachannel"}, "cli") || s6.Applies(lt, Pair{"telemost", "vp8channel"}, "cli") {
		t.Fatal("S6 must apply to jitsi/datachannel only")
	}
	if s6.Applies(fakeTarget{}, Pair{"jitsi", "datachannel"}, "cli") {
		t.Fatal("S6 must not apply to a target that cannot delay a server")
	}
	s7 := scenario(t, "S7")
	if s7.Applies(lt, Pair{"jitsi", "datachannel"}, "cli") || !s7.Applies(lt, Pair{"jitsi", "datachannel"}, "mobile") {
		t.Fatal("S7 is the mobile flavour's")
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./internal/gate/ -run 'TestPull|TestOnTop|TestP95|TestS'` → undefined symbols.

- [ ] **Step 3: Implement `load.go`**

```go
package gate

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"sort"
	"sync"
	"time"
)

// Outcome is what a batch of requests came to.
type Outcome struct {
	OK    int
	Total int
	P95   time.Duration
	Bytes int64
	Took  time.Duration
}

// Pull GETs url and insists on exactly want bytes.
func Pull(ctx context.Context, hc *http.Client, url string, want int64) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, fmt.Errorf("pull request: %w", err)
	}
	resp, err := hc.Do(req)
	if err != nil {
		return 0, fmt.Errorf("pull: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	n, err := io.Copy(io.Discard, resp.Body)
	if err != nil {
		return n, fmt.Errorf("pull body: %w", err)
	}
	if resp.StatusCode != http.StatusOK || n != want {
		return n, fmt.Errorf("pull: status %d, %d bytes, want 200 and %d", resp.StatusCode, n, want)
	}
	return n, nil
}

// Push POSTs size bytes of pattern to url.
func Push(ctx context.Context, hc *http.Client, url string, size int64) error {
	body := bytes.Repeat([]byte{0x5a, 0xa5, 0x3c, 0xc3}, int(size/4)+1)[:size]
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("push request: %w", err)
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := hc.Do(req)
	if err != nil {
		return fmt.Errorf("push: %w", err)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("push: status %d", resp.StatusCode)
	}
	return nil
}

// Parallel runs each n times at once and folds the results.
func Parallel(ctx context.Context, n int, each func(ctx context.Context, i int) (int64, error)) Outcome {
	var wg sync.WaitGroup
	var mu sync.Mutex
	out := Outcome{Total: n}
	durations := make([]time.Duration, 0, n)
	start := time.Now()
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			t0 := time.Now()
			b, err := each(ctx, i)
			mu.Lock()
			defer mu.Unlock()
			out.Bytes += b
			durations = append(durations, time.Since(t0))
			if err == nil {
				out.OK++
			}
		}(i)
	}
	wg.Wait()
	out.Took = time.Since(start)
	out.P95 = p95(durations)
	return out
}

// ConnectBurst fetches a small resource concurrent times at once, then
// sequential times one after another; every fetch is a fresh tunnel connect.
func ConnectBurst(ctx context.Context, hc *http.Client, url string, concurrent, sequential int) Outcome {
	fetch := func(ctx context.Context, _ int) (int64, error) { return Pull(ctx, hc, url, 1024) }
	out := Parallel(ctx, concurrent, fetch)
	durations := []time.Duration{out.P95}
	for i := 0; i < sequential; i++ {
		t0 := time.Now()
		_, err := fetch(ctx, i)
		durations = append(durations, time.Since(t0))
		out.Total++
		if err == nil {
			out.OK++
		}
	}
	out.P95 = p95(durations)
	return out
}

// OnTop fetches the small resource every interval until stopped, the way a
// browser keeps opening connections while a download runs.
func OnTop(ctx context.Context, hc *http.Client, url string, every time.Duration) func() Outcome {
	stopCh := make(chan struct{})
	done := make(chan Outcome, 1)
	go func() {
		var out Outcome
		var durations []time.Duration
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			select {
			case <-stopCh:
				out.P95 = p95(durations)
				done <- out
				return
			case <-t.C:
				t0 := time.Now()
				_, err := Pull(ctx, hc, url, 1024)
				durations = append(durations, time.Since(t0))
				out.Total++
				if err == nil {
					out.OK++
				}
			}
		}
	}()
	return func() Outcome {
		close(stopCh)
		return <-done
	}
}

func p95(ds []time.Duration) time.Duration {
	if len(ds) == 0 {
		return 0
	}
	sorted := append([]time.Duration(nil), ds...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	idx := (len(sorted)*95 + 99) / 100
	if idx < 1 {
		idx = 1
	}
	return sorted[idx-1]
}

func bps(b int64, took time.Duration) float64 {
	if took <= 0 {
		return 0
	}
	return float64(b*8) / took.Seconds()
}
```

- [ ] **Step 4: Implement `scenarios.go`**

```go
package gate

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"time"
)

// quietAfterLoad is S4's idle window; tests shorten it.
var quietAfterLoad = 60 * time.Second //nolint:gochecknoglobals // tests shorten it

const (
	burstConcurrent = 24
	burstSequential = 24
	pullsInFlight   = 6
	pushesInFlight  = 4
	pushBytes       = 5 << 20
	onTopEvery      = 5 * time.Second
	resolverBurst   = 64
	resolverWait    = 5 * time.Second
)

func always(Target, Pair, string) bool { return true }

func mobileOnly(_ Target, _ Pair, client string) bool { return client == "mobile" }

// loadPairs are the pairs the load scenarios run on: one per provider.
func loadPair(_ Target, p Pair, client string) bool {
	if client != "mobile" {
		return false
	}
	switch p {
	case Pair{"jitsi", "datachannel"}, Pair{"telemost", "vp8channel"}, Pair{"wbstream", "vp8channel"}:
		return true
	}
	return false
}

func init() { //nolint:gochecknoinits // the registry is filled at load
	Register(Scenario{ID: "S0", Name: "connect", Applies: always, Run: runS0})
	Register(Scenario{ID: "S1", Name: "idle burst", Applies: loadPair, Run: runS1})
	Register(Scenario{ID: "S2", Name: "download saturation", Applies: loadPair, Run: runS2})
	Register(Scenario{ID: "S3", Name: "upload saturation", Applies: loadPair, Run: runS3})
	Register(Scenario{ID: "S4", Name: "quiet after load", Applies: loadPair, Run: runS4})
	Register(Scenario{ID: "S5", Name: "resolver burst", Applies: loadPair, Run: runS5})
	Register(Scenario{ID: "S6", Name: "late server bridge", Applies: func(t Target, p Pair, _ string) bool {
		_, local := t.(*LocalTarget)
		return local && p == Pair{"jitsi", "datachannel"}
	}, Run: runS6})
	Register(Scenario{ID: "S7", Name: "phone memory", Applies: loadPair, Run: runS7})
}

// S0: the tunnel came up (handshake_ms is measured by the runner and passed
// in env.Endpoint's cell metrics), pull and push once.
func runS0(ctx context.Context, env *Env) (Metrics, error) {
	m := Metrics{"handshake_ms": env.handshakeMs()}
	if _, err := Pull(ctx, env.HTTP, env.Load.Big, env.Load.BigBytes); err == nil {
		m["pull_ok"] = 1
	} else {
		env.Logf("S0 pull: %v", err)
	}
	if err := Push(ctx, env.HTTP, env.Load.Sink, pushBytes); err == nil {
		m["push_ok"] = 1
	} else {
		env.Logf("S0 push: %v", err)
	}
	return m, nil
}

func runS1(ctx context.Context, env *Env) (Metrics, error) {
	env.Sampler.Mark("goroutines_idle")
	out := ConnectBurst(ctx, env.HTTP, env.Load.Small, burstConcurrent, burstSequential)
	return Metrics{"connect_ok": float64(out.OK), "connect_total": float64(out.Total),
		"connect_p95_ms": float64(out.P95.Milliseconds())}, nil
}

func runS2(ctx context.Context, env *Env) (Metrics, error) {
	env.Sampler.Mark("S2-start")
	stop := OnTop(ctx, env.HTTP, env.Load.Small, onTopEvery)
	out := Parallel(ctx, pullsInFlight, func(ctx context.Context, _ int) (int64, error) {
		return Pull(ctx, env.HTTP, env.Load.Big, env.Load.BigBytes)
	})
	top := stop()
	return Metrics{"pull_ok": float64(out.OK), "pull_total": float64(out.Total),
		"throughput_down_bps": bps(out.Bytes, out.Took),
		"on_top_ok": float64(top.OK), "on_top_total": float64(top.Total), "on_top_p95_ms": float64(top.P95.Milliseconds())}, nil
}

func runS3(ctx context.Context, env *Env) (Metrics, error) {
	stop := OnTop(ctx, env.HTTP, env.Load.Small, onTopEvery)
	out := Parallel(ctx, pushesInFlight, func(ctx context.Context, _ int) (int64, error) {
		if err := Push(ctx, env.HTTP, env.Load.Sink, pushBytes); err != nil {
			return 0, err
		}
		return pushBytes, nil
	})
	top := stop()
	return Metrics{"push_ok": float64(out.OK), "push_total": float64(out.Total),
		"throughput_up_bps": bps(out.Bytes, out.Took),
		"on_top_ok": float64(top.OK), "on_top_total": float64(top.Total), "on_top_p95_ms": float64(top.P95.Milliseconds())}, nil
}

func runS4(ctx context.Context, env *Env) (Metrics, error) {
	before := env.Log.Count("control missed pong")
	reconnectsBefore := env.Log.Count("client reconnect")
	select {
	case <-time.After(quietAfterLoad):
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	env.Sampler.Mark("S4-end")
	m := Metrics{
		"missed_pong": float64(env.Log.Count("control missed pong") - before),
		"reconnects":  float64(env.Log.Count("client reconnect") - reconnectsBefore),
		"alive":       1,
	}
	if env.Log.Count("Client link reported conference end") > 0 {
		m["alive"] = 0
	}
	if _, err := Pull(ctx, env.HTTP, env.Load.Small, 1024); err == nil {
		m["final_pull_ok"] = 1
	} else {
		env.Logf("S4 final pull: %v", err)
	}
	return m, nil
}

func runS5(ctx context.Context, env *Env) (Metrics, error) {
	if env.UDP == nil {
		return nil, fmt.Errorf("S5 needs a UDP association")
	}
	m := Metrics{}
	for round := 1; round <= 2; round++ {
		answered, err := resolverBurstRound(ctx, env)
		if err != nil {
			return nil, err
		}
		m[fmt.Sprintf("answered_%d", round)] = float64(answered)
	}
	return m, nil
}

func resolverBurstRound(ctx context.Context, env *Env) (int, error) {
	assoc, err := env.UDP(ctx)
	if err != nil {
		return 0, fmt.Errorf("udp associate: %w", err)
	}
	defer assoc.Close()
	resolver := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	for i := 0; i < resolverBurst; i++ {
		if err := assoc.Send(resolver, BuildDNSQuery(uint16(i+1), fmt.Sprintf("gate%d.example.com", i))); err != nil { //nolint:gosec // small
			return 0, err
		}
	}
	seen := map[uint16]bool{}
	deadline := time.Now().Add(resolverWait)
	buf := make([]byte, 4096)
	for len(seen) < resolverBurst {
		payload, _, err := assoc.Recv(buf, deadline)
		if err != nil {
			break
		}
		if id, ok := DNSResponseID(payload); ok && id >= 1 && id <= resolverBurst {
			seen[id] = true
		}
	}
	return len(seen), nil
}

// S6 brings up a server whose bridge opens late and measures how long the
// client needs to become ready; the hello resend is what makes it pass.
func runS6(ctx context.Context, env *Env) (Metrics, error) {
	if env.Delayed == nil {
		return nil, fmt.Errorf("S6 needs a target that can delay a server")
	}
	m := Metrics{}
	for _, d := range []time.Duration{3 * time.Second, 8 * time.Second} {
		key := fmt.Sprintf("ready_%ds_ms", int(d.Seconds()))
		ep, stop, err := env.Delayed(ctx, OpenOptions{BridgeDelay: d})
		if err != nil {
			env.Logf("S6 open with %s delay: %v", d, err)
			m[key] = 0
			continue
		}
		t0 := time.Now()
		tun, err := env.Client.Start(ctx, ep)
		if err != nil {
			env.Logf("S6 client with %s delay: %v", d, err)
			m[key] = 0
		} else {
			m[key] = float64(time.Since(t0).Milliseconds())
			tun.Stop()
		}
		stop()
	}
	return m, nil
}

func runS7(_ context.Context, env *Env) (Metrics, error) {
	heap, rss, ok := env.Sampler.PeakBetween("S2-start", "S4-end")
	if !ok {
		return nil, fmt.Errorf("S7: no samples between S2-start and S4-end")
	}
	idle := 0.0
	for _, s := range env.Sampler.Samples() {
		if idleAt, has := env.Sampler.markTime("goroutines_idle"); has && !s.At.Before(idleAt) {
			idle = float64(s.Goroutines)
			break
		}
	}
	samples := env.Sampler.Samples()
	after := 0.0
	if len(samples) > 0 {
		after = float64(samples[len(samples)-1].Goroutines)
	}
	return Metrics{"heap_peak_bytes": float64(heap), "rss_peak_bytes": float64(rss),
		"goroutines_idle": idle, "goroutines_after": after}, nil
}

// handshakeMs is how long the client took to report a listening port; the
// runner stores it on the Env when it starts the tunnel.
func (e *Env) handshakeMs() float64 { return e.handshake }

var _ = binary.BigEndian // keep the import list honest if a helper moves
```

Add to `Env` in `gate.go` the field `handshake float64` (unexported; the runner sets it) and to `Sampler` the method used above:

```go
// markTime returns when a mark was set.
func (s *Sampler) markTime(name string) (time.Time, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.marks[name]
	return t, ok
}
```

Remove the `var _ = binary.BigEndian` line and the `encoding/binary` import if nothing in the file uses them (they exist only so the executor does not leave an unused import while assembling the file).

- [ ] **Step 5: Run the tests**

Run: `go test -race ./internal/gate/ -run 'TestPull|TestOnTop|TestP95|TestS'` → `ok` (S2 over loopback moves 12 MB; keep the origin's `BigBytes` at 2 MB in the tests as `directHTTP` does); `golangci-lint run ./internal/gate/` → `0 issues` (if `gocognit` flags `runS6` or `runS7`, split the loop bodies into helpers; do not raise the limit).

- [ ] **Step 6: Commit**

```bash
git add internal/gate/load.go internal/gate/scenarios.go internal/gate/load_test.go internal/gate/scenarios_test.go internal/gate/gate.go internal/gate/sampler.go
git commit -m "feat(gate): the load helpers and scenarios S0–S7, the shapes that broke the tunnel"
```

---

### Task 13: The `TestGate` entry point, flags, dry run and the report on the way out

**Files:**
- Create: `internal/gate/gate_test.go` (the entry), `internal/gate/run.go` (the runner, non-test so it is lint-checked and reusable)
- Test: `internal/gate/run_test.go`

**Interfaces:**
- Produces (`run.go`): `type Options struct{ Target Target; Clients []Client; Thresholds Thresholds; Dir string; Recorder *Recorder; Capture *Capture; Secrets *[]string; Logf func(string, ...any) }`, `func RunPlan(ctx context.Context, opt Options, run func(name string, fn func(ctx context.Context)))` — `run` wraps each cell as a subtest (`t.Run`) in the entry; `func RunCell(ctx, opt Options, cell Cell, env *Env) (Metrics, []string, time.Duration)`.
- Flags (`gate_test.go`): `-olcrtc.gate`, `-olcrtc.gate-target` (`local`|`link`), `-olcrtc.gate-link` (else env `OLCRTC_GATE_LINK`), `-olcrtc.gate-dir` (default `gate-artifacts`), `-olcrtc.gate-providers` (`jitsi,telemost,wbstream`), `-olcrtc.gate-transports` (`datachannel,vp8channel,seichannel`), `-olcrtc.gate-clients` (`cli,mobile`), `-olcrtc.gate-telemost-rooms`, `-olcrtc.gate-wbstream-rooms`, `-olcrtc.gate-jitsi-instances` (default `docs/jitsi.instances.yaml` under the module root), `-olcrtc.gate-run-number` (default env `GITHUB_RUN_NUMBER`), `-olcrtc.gate-big-mb` (default 10), `-olcrtc.gate-link-small`, `-olcrtc.gate-link-big`, `-olcrtc.gate-link-sink` (defaults `https://proofkit.org/gate/kb`, `https://proofkit.org/gate/10mb.bin`, `https://speed.cloudflare.com/__up`), `-olcrtc.gate-dry`.
- Report metadata from env: `OLCRTC_GATE_ENGINE_COMMIT` (else `git rev-parse HEAD` in the module root), `OLCRTC_GATE_ENGINE_REF`, `OLCRTC_GATE_APP_VERSION`, `RUNNER_OS`+`ImageOS` (else `runtime.GOOS`).

- [ ] **Step 1: Write the failing test for the runner**

`run_test.go`:

```go
package gate

import (
	"context"
	"strings"
	"testing"
	"time"
)

type recClient struct{ name string }

func (c recClient) Name() string { return c.name }
func (c recClient) Start(context.Context, Endpoint) (*Tunnel, error) {
	return &Tunnel{SocksAddr: "direct", Stop: func() {}}, nil
}

func TestRunPlanRecordsEveryCellAndKeepsSecretsOut(t *testing.T) {
	resetRegistryForTest(t)
	Register(Scenario{ID: "S0", Name: "connect", Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		return Metrics{"handshake_ms": 10, "pull_ok": 1, "push_ok": 1}, nil
	}})
	Register(Scenario{ID: "S1", Name: "burst", Applies: always, Run: func(context.Context, *Env) (Metrics, error) {
		return Metrics{"connect_ok": 1, "connect_total": 2, "connect_p95_ms": 1}, nil
	}})
	target := fakeTarget{pairs: []Pair{{"jitsi", "datachannel"}}}
	rec := NewRecorder(Report{Target: "fake"})
	cap := StartCapture()
	defer cap.Stop()
	secrets := []string{}
	opt := Options{Target: target, Clients: []Client{recClient{"cli"}}, Thresholds: Local, Dir: t.TempDir(),
		Recorder: rec, Capture: cap, Secrets: &secrets, Logf: t.Logf}
	RunPlan(context.Background(), opt, func(name string, fn func(ctx context.Context)) {
		t.Run(name, func(t *testing.T) { fn(context.Background()) })
	})
	rep := rec.Report()
	if rep.Planned != 2 || rep.Executed != 2 || rep.Passed != 1 || rep.Failed != 1 {
		t.Fatalf("report = planned %d executed %d passed %d failed %d", rep.Planned, rep.Executed, rep.Passed, rep.Failed)
	}
	for _, c := range rep.Cells {
		if strings.Contains(c.ID, "@") || strings.Contains(c.ID, "#") {
			t.Fatalf("cell id carries link material: %s", c.ID)
		}
		if c.Scenario == "S1" && !strings.Contains(strings.Join(c.Failures, ";"), "connect_ok") {
			t.Fatalf("S1 verdict = %v", c.Failures)
		}
	}
}

func TestRunCellTimesOutAStuckScenario(t *testing.T) {
	cellDeadline = 100 * time.Millisecond
	t.Cleanup(func() { cellDeadline = 5 * time.Minute })
	stuck := Scenario{ID: "S0", Run: func(ctx context.Context, _ *Env) (Metrics, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}}
	env := &Env{Logf: func(string, ...any) {}, Log: &CellLog{}, Thresholds: Local}
	_, failures, took := RunCell(context.Background(), Options{Thresholds: Local}, Cell{ID: "x", Scenario: "S0"}, env, stuck)
	if len(failures) == 0 || took > time.Second {
		t.Fatalf("stuck scenario: failures %v took %v", failures, took)
	}
}
```

- [ ] **Step 2: Run to see it fail**

Run: `go test ./internal/gate/ -run 'TestRunPlan|TestRunCell'` → undefined symbols.

- [ ] **Step 3: Implement `run.go`**

```go
package gate

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// cellDeadline bounds one scenario; the bulk ones get longer in RunCell.
var cellDeadline = 5 * time.Minute //nolint:gochecknoglobals // tests shorten it

// Options is everything RunPlan needs.
type Options struct {
	Target     Target
	Clients    []Client
	Thresholds Thresholds
	Dir        string
	Recorder   *Recorder
	Capture    *Capture
	Secrets    *[]string
	Logf       func(format string, args ...any)
}

// RunPlan enumerates the plan, then walks it pair by pair, client by client,
// scenario by scenario, one at a time. run wraps each cell (the entry uses
// t.Run) so the report and the test output name the same thing.
func RunPlan(ctx context.Context, opt Options, run func(name string, fn func(ctx context.Context))) {
	clients := make([]string, 0, len(opt.Clients))
	for _, c := range opt.Clients {
		clients = append(clients, c.Name())
	}
	opt.Recorder.Plan(PlanCells(opt.Target, clients))
	for _, pair := range opt.Target.Pairs() {
		pair := pair
		run(pair.String(), func(ctx context.Context) { runPair(ctx, opt, pair, run) })
	}
}

func runPair(ctx context.Context, opt Options, pair Pair, run func(string, func(context.Context))) {
	dir := filepath.Join(opt.Dir, pair.Provider+"-"+pair.Transport)
	_ = os.MkdirAll(dir, 0o755) //nolint:gosec // artifacts
	ep, stopServer, err := openWithRetry(ctx, opt, pair, dir)
	if err != nil {
		opt.Logf("%s: server: %v", pair, err)
		failPair(opt, pair, "server: "+err.Error())
		return
	}
	defer stopServer()
	*opt.Secrets = append(*opt.Secrets, ep.Room, ep.Key, roomSlug(ep.Room))
	for _, client := range opt.Clients {
		client := client
		run(client.Name(), func(ctx context.Context) { runClient(ctx, opt, pair, client, ep, dir, run) })
	}
}

func openWithRetry(ctx context.Context, opt Options, pair Pair, dir string) (Endpoint, func(), error) {
	ep, stop, err := opt.Target.Open(ctx, pair, dir, OpenOptions{})
	if err == nil {
		return ep, stop, nil
	}
	opt.Logf("%s: first open failed, retrying in 30 s: %v", pair, err)
	select {
	case <-time.After(30 * time.Second):
	case <-ctx.Done():
		return Endpoint{}, nil, ctx.Err()
	}
	return opt.Target.Open(ctx, pair, dir, OpenOptions{})
}

func failPair(opt Options, pair Pair, reason string) {
	for _, s := range Scenarios() {
		for _, c := range opt.Clients {
			if s.Applies == nil || s.Applies(opt.Target, pair, c.Name()) {
				opt.Recorder.Finish(CellID(opt.Target.Platform(), pair, c.Name(), s.ID), nil, opt.Thresholds.Map(), []string{reason}, "", 0)
			}
		}
	}
}

func runClient(ctx context.Context, opt Options, pair Pair, client Client, ep Endpoint, dir string, run func(string, func(context.Context))) {
	sampler := NewSampler(time.Second)
	sampler.Start()
	defer sampler.Stop()
	env := &Env{Target: opt.Target, Pair: pair, Client: client, Endpoint: ep, Load: opt.Target.Load(),
		Sampler: sampler, Thresholds: opt.Thresholds, Dir: dir, Logf: opt.Logf}
	if lt, ok := opt.Target.(*LocalTarget); ok {
		env.Delayed = func(ctx context.Context, o OpenOptions) (Endpoint, func(), error) {
			sub := filepath.Join(dir, fmt.Sprintf("delay-%s", o.BridgeDelay))
			_ = os.MkdirAll(sub, 0o755) //nolint:gosec // artifacts
			e, stop, err := lt.Open(ctx, pair, sub, o)
			if err == nil {
				*opt.Secrets = append(*opt.Secrets, e.Room, e.Key, roomSlug(e.Room))
			}
			return e, stop, err
		}
	}
	startLog := opt.Capture.Begin()
	t0 := time.Now()
	tun, err := client.Start(ctx, ep)
	env.handshake = float64(time.Since(t0).Milliseconds())
	if err != nil {
		opt.Logf("%s/%s: client: %v", pair, client.Name(), err)
		_ = startLog.WriteScrubbed(filepath.Join(dir, client.Name()+"-start.log"), *opt.Secrets...)
		for _, s := range Scenarios() {
			if s.Applies == nil || s.Applies(opt.Target, pair, client.Name()) {
				opt.Recorder.Finish(CellID(opt.Target.Platform(), pair, client.Name(), s.ID), nil, opt.Thresholds.Map(), []string{"client: " + err.Error()}, "", 0)
			}
		}
		return
	}
	defer tun.Stop()
	env.Tunnel = tun
	dial, err := SocksDialer(tun.SocksAddr)
	if err != nil {
		opt.Logf("%s/%s: dialer: %v", pair, client.Name(), err)
		return
	}
	env.Dial = dial
	env.HTTP = HTTPClient(dial, 2*time.Minute)
	env.UDP = func(ctx context.Context) (*UDPAssoc, error) { return UDPAssociate(ctx, tun.SocksAddr) }
	for _, s := range Scenarios() {
		if s.Applies != nil && !s.Applies(opt.Target, pair, client.Name()) {
			continue
		}
		s := s
		cell := Cell{ID: CellID(opt.Target.Platform(), pair, client.Name(), s.ID), Scenario: s.ID}
		run(s.ID, func(ctx context.Context) {
			env.Log = opt.Capture.Begin()
			m, failures, took := RunCell(ctx, opt, cell, env, s)
			logPath := filepath.Join(dir, client.Name()+"-"+s.ID+".log")
			if opt.Target.Name() == "link" {
				logPath = "" // never uploaded: the fleet's rooms are not ours to show
			} else if err := env.Log.WriteScrubbed(logPath, *opt.Secrets...); err != nil {
				opt.Logf("write log: %v", err)
			}
			if s.ID == "S7" {
				_ = sampler.WriteCSV(filepath.Join(dir, client.Name()+"-samples.csv"))
			}
			opt.Recorder.Finish(cell.ID, m, opt.Thresholds.Map(), failures, logPath, took)
		})
	}
}

// RunCell runs one scenario under its deadline and judges it.
func RunCell(ctx context.Context, opt Options, cell Cell, env *Env, s Scenario) (Metrics, []string, time.Duration) {
	deadline := cellDeadline
	if s.ID == "S2" || s.ID == "S3" {
		deadline = cellDeadline * 2
	}
	cctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	t0 := time.Now()
	m, err := s.Run(cctx, env)
	took := time.Since(t0)
	if m == nil {
		m = Metrics{}
	}
	var failures []string
	if err != nil {
		failures = append(failures, "scenario error: "+err.Error())
	}
	failures = append(failures, Evaluate(s.ID, m, opt.Thresholds)...)
	return m, failures, took
}

// roomSlug is the last path element of a room URL, so a log line that names
// only the slug is scrubbed too.
func roomSlug(room string) string {
	if i := strings.LastIndex(room, "/"); i >= 0 && i < len(room)-1 {
		return room[i+1:]
	}
	return ""
}
```

Adjust the two tests to the final signature (`RunCell(ctx, opt, cell, env, scenario)`); the stuck-scenario test passes `stuck` as the last argument.

- [ ] **Step 4: Write `gate_test.go` — the entry**

```go
package gate

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/link"
	"github.com/openlibrecommunity/olcrtc/mobile"
)

var (
	gateOn        = flag.Bool("olcrtc.gate", false, "run the release gate (network, minutes)")
	gateTarget    = flag.String("olcrtc.gate-target", "local", "local (child server) or link (olcrtc:// link)")
	gateLink      = flag.String("olcrtc.gate-link", "", "olcrtc:// link for the link target; else OLCRTC_GATE_LINK")
	gateDir       = flag.String("olcrtc.gate-dir", "gate-artifacts", "where the report, logs and samples go")
	gateProviders = flag.String("olcrtc.gate-providers", "jitsi,telemost,wbstream", "providers for the local target")
	gateTransport = flag.String("olcrtc.gate-transports", "datachannel,vp8channel,seichannel", "transports for the local target")
	gateClients   = flag.String("olcrtc.gate-clients", "cli,mobile", "client flavours (mobile needs -tags olcrtc_lean)")
	gateTelemost  = flag.String("olcrtc.gate-telemost-rooms", "", "comma-separated Telemost room ids or URLs (a pool)")
	gateWBStream  = flag.String("olcrtc.gate-wbstream-rooms", "", "comma-separated WB Stream room ids (a pool)")
	gateInstances = flag.String("olcrtc.gate-jitsi-instances", "", "path to jitsi.instances.yaml (default: the repository's)")
	gateRunNumber = flag.Int("olcrtc.gate-run-number", -1, "run number for pool selection (default: GITHUB_RUN_NUMBER)")
	gateBigMB     = flag.Int64("olcrtc.gate-big-mb", 10, "size of the big pull in MB")
	gateLinkSmall = flag.String("olcrtc.gate-link-small", "https://proofkit.org/gate/kb", "1 KB resource reachable from the fleet")
	gateLinkBig   = flag.String("olcrtc.gate-link-big", "https://proofkit.org/gate/10mb.bin", "big resource reachable from the fleet")
	gateLinkSink  = flag.String("olcrtc.gate-link-sink", "https://speed.cloudflare.com/__up", "upload sink reachable from the fleet")
	gateDry       = flag.Bool("olcrtc.gate-dry", false, "print the plan and exit")
)

var gateRecorder *Recorder //nolint:gochecknoglobals // written by TestMain after the run

func TestMain(m *testing.M) {
	flag.Parse()
	code := m.Run()
	if gateRecorder != nil {
		path := filepath.Join(*gateDir, "gate-report.json")
		if err := gateRecorder.Write(path); err != nil {
			fmt.Fprintln(os.Stderr, "gate: write report:", err)
			if code == 0 {
				code = 1
			}
		}
	}
	os.Exit(code)
}

func moduleRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("no caller information")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", ".."))
}

func engineCommit(root string) string {
	if v := os.Getenv("OLCRTC_GATE_ENGINE_COMMIT"); v != "" {
		return v
	}
	out, err := exec.Command("git", "-C", root, "rev-parse", "HEAD").Output() //nolint:gosec // fixed arguments
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}

func runnerName() string {
	if os.Getenv("RUNNER_OS") != "" {
		return os.Getenv("RUNNER_OS") + "/" + os.Getenv("ImageOS")
	}
	return runtime.GOOS + "/" + runtime.GOARCH
}

func TestGate(t *testing.T) {
	if !*gateOn {
		t.Skip("release gate disabled; pass -olcrtc.gate")
	}
	root := moduleRoot(t)
	if err := os.MkdirAll(*gateDir, 0o755); err != nil { //nolint:gosec // artifacts
		t.Fatal(err)
	}
	clients := gateClientsFromFlag(t)
	target, thresholds, cleanup := gateTargetFromFlags(t, root)
	defer cleanup()
	names := make([]string, 0, len(clients))
	for _, c := range clients {
		names = append(names, c.Name())
	}
	if *gateDry {
		for _, c := range PlanCells(target, names) {
			fmt.Println(c.ID)
		}
		return
	}
	mobile.SetMemoryLimit(40 << 20)
	debug.SetGCPercent(10)
	gateRecorder = NewRecorder(Report{
		EngineCommit: engineCommit(root), EngineRef: os.Getenv("OLCRTC_GATE_ENGINE_REF"),
		AppVersion: os.Getenv("OLCRTC_GATE_APP_VERSION"), Target: target.Name(), Runner: runnerName(),
	})
	capture := StartCapture()
	defer capture.Stop()
	secrets := []string{}
	opt := Options{Target: target, Clients: clients, Thresholds: thresholds, Dir: *gateDir,
		Recorder: gateRecorder, Capture: capture, Secrets: &secrets, Logf: t.Logf}
	RunPlan(context.Background(), opt, func(name string, fn func(ctx context.Context)) {
		t.Run(name, func(t *testing.T) { fn(context.Background()) })
	})
	rep := gateRecorder.Report()
	if rep.Failed > 0 {
		t.Errorf("gate: %d of %d cells failed", rep.Failed, rep.Planned)
	}
}

func gateClientsFromFlag(t *testing.T) []Client {
	t.Helper()
	var clients []Client
	for _, name := range strings.Split(*gateClients, ",") {
		switch strings.TrimSpace(name) {
		case "cli":
			clients = append(clients, CLIClient())
		case "mobile":
			if MobileClient() == nil {
				t.Fatal("the mobile flavour needs -tags olcrtc_lean")
			}
			clients = append(clients, MobileClient())
		case "":
		default:
			t.Fatalf("unknown client flavour %q", name)
		}
	}
	return clients
}

func gateTargetFromFlags(t *testing.T, root string) (Target, Thresholds, func()) {
	t.Helper()
	switch *gateTarget {
	case "local":
		origin, err := StartOrigin(*gateBigMB << 20)
		if err != nil {
			t.Fatal(err)
		}
		instances := *gateInstances
		if instances == "" {
			instances = filepath.Join(root, "docs", "jitsi.instances.yaml")
		}
		run := *gateRunNumber
		if run < 0 {
			run, _ = strconv.Atoi(os.Getenv("GITHUB_RUN_NUMBER"))
		}
		lt := NewLocalTarget(LocalOptions{
			ModuleRoot: root, WorkDir: *gateDir, Instances: instances,
			TelemostRooms: splitList(*gateTelemost), WBStreamRooms: splitList(*gateWBStream), RunNumber: run,
			Providers: splitList(*gateProviders), Transports: splitList(*gateTransport),
			DNS: "8.8.8.8:53", BigBytes: *gateBigMB << 20, Origin: origin,
		})
		return lt, Local, origin.Close
	case "link":
		raw := *gateLink
		if raw == "" {
			raw = os.Getenv("OLCRTC_GATE_LINK")
		}
		l, err := link.Parse(raw)
		if err != nil {
			t.Fatalf("link target: %v", err)
		}
		load := LoadURLs{Small: *gateLinkSmall, Big: *gateLinkBig, Sink: *gateLinkSink, BigBytes: *gateBigMB << 20}
		return NewLinkTarget(l, load, "8.8.8.8:53"), Link, func() {}
	default:
		t.Fatalf("unknown target %q", *gateTarget)
		return nil, Thresholds{}, nil
	}
}

func splitList(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}
```

- [ ] **Step 5: Run the unit tests, the dry run and one live pair**

Run: `go test -race ./internal/gate/` and `go test -race -tags olcrtc_lean ./internal/gate/` → `ok` (TestGate skips without the flag).
Dry run: `go test -tags olcrtc_lean ./internal/gate -run '^TestGate$' -olcrtc.gate -olcrtc.gate-dry -olcrtc.gate-providers=jitsi -olcrtc.gate-transports=datachannel -v` → prints `engine-linux/jitsi/datachannel/cli/S0`, `…/mobile/S0`, `…/mobile/S1` … `…/mobile/S7`, `…/cli/S6`, `…/mobile/S6`.
Live: `go test -tags olcrtc_lean -timeout 20m ./internal/gate -run '^TestGate$' -olcrtc.gate -olcrtc.gate-providers=jitsi -olcrtc.gate-transports=datachannel -olcrtc.gate-dir=/tmp/gate-jitsi -v` → every cell `pass`, `/tmp/gate-jitsi/gate-report.json` written, `jitsi-datachannel/srv.log` present and scrubbed (`grep -c gate- /tmp/gate-jitsi/jitsi-datachannel/*.log` → 0).

- [ ] **Step 6: Commit**

```bash
git add internal/gate/run.go internal/gate/run_test.go internal/gate/gate_test.go
git commit -m "feat(gate): TestGate runs the plan pair by pair and writes the report on the way out"
```

---

### Task 14: `cmd/gate-report` — render and compare

**Files:**
- Create: `cmd/gate-report/main.go`, `cmd/gate-report/render.go`, `cmd/gate-report/compare.go`
- Test: `cmd/gate-report/render_test.go`, `cmd/gate-report/compare_test.go`

**Interfaces:**
- CLI: `gate-report render <report.json>` writes Markdown to stdout; `gate-report compare <previous.json> <current.json> [-severity warn|fail]` writes the delta table to stdout and exits 2 on a regression when `-severity=fail`, 0 otherwise (regressions are still printed and prefixed `REGRESSION`).
- Produces: `func Render(r gate.Report) string`, `func Compare(prev, cur gate.Report) []Delta`, `type Delta struct{ Cell, Metric string; Prev, Cur, Change float64; Regression bool }`. Regression rules: `throughput_down_bps` or `throughput_up_bps` fell by more than 25 %; `heap_peak_bytes` or `rss_peak_bytes` rose by more than 25 %; a cell that passed before and fails now.

- [ ] **Step 1: Write the failing tests**

`render_test.go`:

```go
package main

import (
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

func sampleReport() gate.Report {
	return gate.Report{Schema: 1, EngineCommit: "850aa5f9", AppVersion: "1.0.431", Target: "local", Runner: "Linux/ubuntu24",
		Planned: 2, Executed: 2, Passed: 1, Failed: 1, DurationS: 612,
		Cells: []gate.Cell{
			{ID: "engine-linux/jitsi/datachannel/mobile/S2", Scenario: "S2", Status: "pass", DurationS: 95,
				Metrics: map[string]float64{"throughput_down_bps": 4_812_000, "on_top_p95_ms": 830}, Failures: []string{}},
			{ID: "engine-linux/jitsi/datachannel/mobile/S4", Scenario: "S4", Status: "fail", DurationS: 61,
				Metrics: map[string]float64{"missed_pong": 2}, Failures: []string{"missed_pong 2 > 0"}},
		}}
}

func TestRenderIsATableWithVerdicts(t *testing.T) {
	md := Render(sampleReport())
	for _, want := range []string{"| Cell |", "engine-linux/jitsi/datachannel/mobile/S2", "4.8 Mbit/s", "✅", "❌", "missed_pong 2 > 0", "1 of 2 cells failed", "850aa5f9"} {
		if !strings.Contains(md, want) {
			t.Fatalf("render lacks %q:\n%s", want, md)
		}
	}
}
```

`compare_test.go`:

```go
package main

import (
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

func TestCompareFlagsRegressions(t *testing.T) {
	prev := sampleReport()
	cur := sampleReport()
	cur.Cells[0].Metrics["throughput_down_bps"] = 3_000_000 // −38 %
	cur.Cells[1].Status = "pass"
	cur.Cells[1].Failures = []string{}
	cur.Cells = append(cur.Cells, gate.Cell{ID: "engine-linux/jitsi/datachannel/mobile/S7", Scenario: "S7", Status: "pass",
		Metrics: map[string]float64{"heap_peak_bytes": 9 << 20}})
	deltas := Compare(prev, cur)
	var regressions []Delta
	for _, d := range deltas {
		if d.Regression {
			regressions = append(regressions, d)
		}
	}
	if len(regressions) != 1 || regressions[0].Metric != "throughput_down_bps" {
		t.Fatalf("regressions = %+v", regressions)
	}
	prev2 := cur
	cur2 := sampleReport()
	cur2.Cells[0].Metrics["throughput_down_bps"] = 4_000_000 // within 25 % of 3.0? no: +33 %, not a regression
	cur2.Cells[1].Status = "fail"
	found := false
	for _, d := range Compare(prev2, cur2) {
		if d.Cell == "engine-linux/jitsi/datachannel/mobile/S4" && d.Metric == "status" && d.Regression {
			found = true
		}
	}
	if !found {
		t.Fatal("a cell that passed before and fails now must be a regression")
	}
}
```

- [ ] **Step 2: Run to see them fail**

Run: `go test ./cmd/gate-report/` → undefined symbols.

- [ ] **Step 3: Implement `render.go`**

```go
package main

import (
	"fmt"
	"sort"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// Render writes the report as the table the job summary and the release show.
func Render(r gate.Report) string {
	var b strings.Builder
	fmt.Fprintf(&b, "### Gate: %s target, engine %s", r.Target, short(r.EngineCommit))
	if r.AppVersion != "" {
		fmt.Fprintf(&b, ", app %s", r.AppVersion)
	}
	fmt.Fprintf(&b, "\n\n%d of %d cells failed", r.Failed, r.Planned)
	if r.Failed == 0 {
		b.Reset()
		fmt.Fprintf(&b, "### Gate: %s target, engine %s", r.Target, short(r.EngineCommit))
		if r.AppVersion != "" {
			fmt.Fprintf(&b, ", app %s", r.AppVersion)
		}
		fmt.Fprintf(&b, "\n\nall %d cells passed", r.Planned)
	}
	fmt.Fprintf(&b, " · %s · %.0f s\n\n", r.Runner, r.DurationS)
	b.WriteString("| Cell | Verdict | Key metrics | Took |\n| --- | --- | --- | --- |\n")
	cells := append([]gate.Cell(nil), r.Cells...)
	sort.Slice(cells, func(i, j int) bool { return cells[i].ID < cells[j].ID })
	for _, c := range cells {
		verdict := "✅"
		if c.Status != "pass" {
			verdict = "❌ " + strings.Join(c.Failures, "; ")
		}
		fmt.Fprintf(&b, "| `%s` | %s | %s | %.0f s |\n", c.ID, verdict, keyMetrics(c), c.DurationS)
	}
	return b.String()
}

func keyMetrics(c gate.Cell) string {
	var parts []string
	add := func(key, text string) {
		if v, ok := c.Metrics[key]; ok {
			parts = append(parts, fmt.Sprintf(text, v))
		}
	}
	if v, ok := c.Metrics["throughput_down_bps"]; ok {
		parts = append(parts, fmt.Sprintf("↓ %.1f Mbit/s", v/1e6))
	}
	if v, ok := c.Metrics["throughput_up_bps"]; ok {
		parts = append(parts, fmt.Sprintf("↑ %.1f Mbit/s", v/1e6))
	}
	add("connect_p95_ms", "connect p95 %.0f ms")
	add("on_top_p95_ms", "on-top p95 %.0f ms")
	add("handshake_ms", "handshake %.0f ms")
	if v, ok := c.Metrics["heap_peak_bytes"]; ok {
		parts = append(parts, fmt.Sprintf("heap peak %.1f MB", v/1048576))
	}
	if v, ok := c.Metrics["rss_peak_bytes"]; ok {
		parts = append(parts, fmt.Sprintf("RSS peak %.1f MB", v/1048576))
	}
	add("missed_pong", "missed pongs %.0f")
	add("answered_1", "answered %.0f/64")
	add("ready_8s_ms", "ready after 8 s delay in %.0f ms")
	return strings.Join(parts, ", ")
}

func short(commit string) string {
	if len(commit) > 12 {
		return commit[:12]
	}
	return commit
}
```

- [ ] **Step 4: Implement `compare.go` and `main.go`**

`compare.go`:

```go
package main

import (
	"fmt"
	"sort"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// Delta is one metric of one cell, then and now.
type Delta struct {
	Cell       string
	Metric     string
	Prev       float64
	Cur        float64
	Change     float64 // fraction, +0.10 = 10 % higher now
	Regression bool
}

const regressionFraction = 0.25

// Compare pairs the cells of two reports by id and judges the metrics that
// track quality: throughput must not fall, memory must not rise, a pass
// must not become a failure.
func Compare(prev, cur gate.Report) []Delta {
	before := map[string]gate.Cell{}
	for _, c := range prev.Cells {
		before[c.ID] = c
	}
	var out []Delta
	for _, c := range cur.Cells {
		p, ok := before[c.ID]
		if !ok {
			continue
		}
		if p.Status == "pass" && c.Status != "pass" {
			out = append(out, Delta{Cell: c.ID, Metric: "status", Prev: 1, Cur: 0, Change: -1, Regression: true})
		}
		for _, key := range []string{"throughput_down_bps", "throughput_up_bps"} {
			out = appendDelta(out, c.ID, key, p.Metrics, c.Metrics, func(change float64) bool { return change < -regressionFraction })
		}
		for _, key := range []string{"heap_peak_bytes", "rss_peak_bytes"} {
			out = appendDelta(out, c.ID, key, p.Metrics, c.Metrics, func(change float64) bool { return change > regressionFraction })
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Cell+out[i].Metric < out[j].Cell+out[j].Metric })
	return out
}

func appendDelta(out []Delta, cell, key string, prev, cur map[string]float64, bad func(float64) bool) []Delta {
	pv, okP := prev[key]
	cv, okC := cur[key]
	if !okP || !okC || pv == 0 {
		return out
	}
	change := (cv - pv) / pv
	return append(out, Delta{Cell: cell, Metric: key, Prev: pv, Cur: cv, Change: change, Regression: bad(change)})
}

// RenderDeltas writes the comparison as Markdown.
func RenderDeltas(deltas []Delta, severity string) (string, bool) {
	var b strings.Builder
	regressed := false
	b.WriteString("### Against the previous release\n\n| Cell | Metric | Before | Now | Change |\n| --- | --- | --- | --- | --- |\n")
	for _, d := range deltas {
		mark := ""
		if d.Regression {
			regressed = true
			mark = " **REGRESSION**"
			if severity == "warn" {
				mark = " ⚠️ regression (warn)"
			}
		}
		fmt.Fprintf(&b, "| `%s` | %s | %.3g | %.3g | %+.0f%%%s |\n", d.Cell, d.Metric, d.Prev, d.Cur, d.Change*100, mark)
	}
	if len(deltas) == 0 {
		b.WriteString("| – | no cells in common | | | |\n")
	}
	return b.String(), regressed
}
```

`main.go`:

```go
// Command gate-report renders a gate-report.json as Markdown and compares two
// of them: gate-report render cur.json | gate-report compare prev.json cur.json [-severity fail]
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	switch os.Args[1] {
	case "render":
		if len(os.Args) != 3 {
			usage()
		}
		fmt.Print(Render(load(os.Args[2])))
	case "compare":
		fs := flag.NewFlagSet("compare", flag.ExitOnError)
		severity := fs.String("severity", "warn", "warn or fail: what a regression does to the exit code")
		_ = fs.Parse(os.Args[2:])
		if fs.NArg() != 2 {
			usage()
		}
		md, regressed := RenderDeltas(Compare(load(fs.Arg(0)), load(fs.Arg(1))), *severity)
		fmt.Print(md)
		if regressed && *severity == "fail" {
			os.Exit(2)
		}
	default:
		usage()
	}
}

func load(path string) gate.Report {
	raw, err := os.ReadFile(path) //nolint:gosec // a path from the command line
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	var r gate.Report
	if err := json.Unmarshal(raw, &r); err != nil {
		fmt.Fprintln(os.Stderr, "parse", path+":", err)
		os.Exit(1)
	}
	return r
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: gate-report render <report.json> | gate-report compare [-severity warn|fail] <prev.json> <cur.json>")
	os.Exit(64)
}
```

- [ ] **Step 5: Run the tests**

Run: `go test -race ./cmd/gate-report/` → `ok`; `golangci-lint run ./cmd/gate-report/` → `0 issues`; `go run ./cmd/gate-report render /tmp/gate-jitsi/gate-report.json` prints the table for the live run from Task 13.

- [ ] **Step 6: Commit**

```bash
git add cmd/gate-report
git commit -m "feat(gate-report): render a report as a table and compare two of them for regressions"
```

---

### Task 15: The `gate-local` job in the engine's CI

**Files:**
- Modify: `.github/workflows/ci.yml` (replace the `real-e2e` job)

**Interfaces:** consumes Tasks 13–14. Secrets: `GATE_TELEMOST_ROOMS`, `GATE_WBSTREAM_ROOMS` (comma-separated pools) in the engine repository.

- [ ] **Step 1: Replace the job**

Delete the `real-e2e` job (its matrix counted skips as green) and add:

```yaml
  gate-local:
    name: Gate (local server, real relays)
    runs-on: ubuntu-latest
    timeout-minutes: 35
    concurrency:
      group: gate-${{ github.ref }}
      cancel-in-progress: true
    env:
      OLCRTC_GATE_ENGINE_REF: ${{ github.ref_name }}
      OLCRTC_GATE_ENGINE_COMMIT: ${{ github.sha }}
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: ${{ env.GO_VERSION }}

      - name: Mask the room pools
        run: |
          for r in $(echo "${{ secrets.GATE_TELEMOST_ROOMS }},${{ secrets.GATE_WBSTREAM_ROOMS }}" | tr ',' ' '); do
            [ -n "$r" ] && echo "::add-mask::$r"
          done

      - name: Print the plan
        run: |
          go test -tags olcrtc_lean ./internal/gate -run '^TestGate$' -olcrtc.gate -olcrtc.gate-dry \
            -olcrtc.gate-telemost-rooms="${{ secrets.GATE_TELEMOST_ROOMS }}" \
            -olcrtc.gate-wbstream-rooms="${{ secrets.GATE_WBSTREAM_ROOMS }}"

      - name: Run the gate
        run: |
          go test -count=1 -tags olcrtc_lean -timeout 34m ./internal/gate -run '^TestGate$' -v \
            -olcrtc.gate -olcrtc.gate-target=local -olcrtc.gate-dir=gate-artifacts \
            -olcrtc.gate-telemost-rooms="${{ secrets.GATE_TELEMOST_ROOMS }}" \
            -olcrtc.gate-wbstream-rooms="${{ secrets.GATE_WBSTREAM_ROOMS }}"

      - name: Summary
        if: always()
        run: |
          if [ -f gate-artifacts/gate-report.json ]; then
            go run ./cmd/gate-report render gate-artifacts/gate-report.json >> "$GITHUB_STEP_SUMMARY"
          else
            echo "no report written" >> "$GITHUB_STEP_SUMMARY"
          fi

      - name: Upload artifacts
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: gate-local
          path: |
            gate-artifacts/**/*.log
            gate-artifacts/**/*.csv
            gate-artifacts/gate-report.json
```

- [ ] **Step 2: Push the branch and read the run**

Run: `git push proofkit feat/gate` then watch the `Gate (local server, real relays)` job: the summary table shows every planned cell with a verdict; the artifact holds `gate-report.json`, one `srv.log` per pair, one client log per cell, `mobile-samples.csv` per load pair. A grep for `gate-` and for any 64-hex string over the artifact's logs finds nothing.

- [ ] **Step 3: Commit (before the push)**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: the gate replaces the real e2e matrix, and a skip is no longer a pass"
```

---

### Task 16: Documentation and the spec's load sizes

**Files:**
- Create: `docs/gate.md` (engine repository)
- Modify (olcbox repository): `docs/superpowers/specs/2026-09-15-release-gate-design.md` §4 (S2 "6 parallel 10 MB pulls", S3 "4 parallel 5 MB pushes") and §8 (the job name `gate-local`, the dry-run step)

- [ ] **Step 1: Write `docs/gate.md`**

```markdown
# The release gate

`internal/gate` runs the engine's client against a real relay and a real server
with the load shapes that broke the tunnel, and writes `gate-report.json`.

## Run it locally

    go test -tags olcrtc_lean -timeout 30m ./internal/gate -run '^TestGate$' -v \
      -olcrtc.gate -olcrtc.gate-providers=jitsi -olcrtc.gate-transports=datachannel \
      -olcrtc.gate-dir=/tmp/gate

Telemost and WB Stream need pre-made rooms: `-olcrtc.gate-telemost-rooms=id1,id2`
and `-olcrtc.gate-wbstream-rooms=id1,id2` (never commit them). `-olcrtc.gate-dry`
prints the plan. Against a fleet node: `-olcrtc.gate-target=link` and the link in
`OLCRTC_GATE_LINK`.

## What a cell is

`platform/provider/transport/client/scenario`. Clients: `cli` (the CLI's client
path) and `mobile` (`mobile.Runtime`, lean build, the phone's memory settings).
Scenarios: S0 connect, S1 idle burst, S2 download saturation, S3 upload
saturation, S4 quiet after load, S5 resolver burst, S6 late server bridge
(local, Jitsi only), S7 phone memory (mobile only). Every planned cell ends
pass or fail; nothing is skipped.

## Thresholds

All in `internal/gate/thresholds.go`. Relative checks against the previous
release live in `cmd/gate-report compare`.

## Reading a report

`go run ./cmd/gate-report render gate-report.json` prints the table; `compare
prev.json cur.json -severity fail` exits 2 on a regression. Logs in the
artifacts are scrubbed: rooms read `<room>`, keys `<key>`.
```

- [ ] **Step 2: Fix the spec's sizes**

In the olcbox repository, §4's table rows for S2 and S3 read `6 parallel 10 MB pulls` and `4 parallel 5 MB pushes`; §8 names the job `gate-local` and lists the dry-run step. Commit there: `docs(specs): the gate's load sizes fit a relay's five megabits`.

- [ ] **Step 3: Commit the engine docs**

```bash
git add docs/gate.md
git commit -m "docs: how to run the gate and read its report"
```

---

## Self-review

**Spec coverage.** §1 layout → Tasks 4–14; §2 targets, rooms, test hooks → Tasks 3, 10; §3 clients → Task 11; §4 scenarios → Task 12 (sizes corrected in Task 16); §5 load endpoints → Tasks 7 (local) and 13 (link flags); §6 thresholds → Task 5; §7 report and `gate-report` → Tasks 4, 14; §8 engine CI (hygiene, lean tests, `gate-local`) → Tasks 1, 15; §10 secrecy → Tasks 9, 13, 15 (masking, scrubbing, link logs not uploaded); §11 zero-skip, retry once, deadlines, artifacts → Tasks 4, 13, 15. The olcbox `release.yml` job, the partner account, the static file and the detector check (§8 app side, §9) are Plan B.

**Placeholders.** None: every step carries its code. Two spots an executor must reconcile rather than invent: `Env` gains `handshake float64` and `Delayed` in Task 4/12 and the tests of Task 13 use the final `RunCell` signature.

**Type consistency.** `DialFunc`, `Endpoint`, `Tunnel`, `Client`, `Target`, `OpenOptions`, `LoadURLs`, `Metrics`, `Cell`, `Report`, `Recorder`, `Thresholds`, `Sampler`, `UDPAssoc`, `CellLog`, `Capture`, `Options` keep the names given in the file structure across Tasks 4–14; metric keys match the list under the file structure.
