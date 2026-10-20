package daemon

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/crypt1"
	"github.com/ghostlane-project/ghostlane/cli/internal/crypt1/crypt1test"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

const listURL = "https://sub.example/sub/a/b?c=olcbox"

type world struct {
	mu         sync.Mutex
	body       []byte
	fetchErr   error
	fetches    int
	log        []string         // "engine:<carrier>@<room>", "engine-stop", "front:<mode>", "front-close", "routes:sync", "routes:clear", "routes:cleanup"
	engineErr  map[string]error // carrier → error at start
	probeErr   error
	probes     int
	stopDelay  time.Duration   // how long a fake engine takes to stop
	engState   string          // "" = running
	xrayErr    error           // error at StartXray
	deadProbe  map[string]bool // probe addresses that fail; a front's is "front:<n>"
	deadFrom   int             // every front numbered >= deadFrom fails its probe (0 = none)
	fronts     int
	frontProbe map[string]string // probe listen address → "front:<n>"
	fetchDelay time.Duration
	bodies     map[string][]byte       // per-URL bodies; "" = w.body
	fetchErrs  map[string]error        // per-URL fetch errors
	devices    []links.Device          // the device each fetch was made as
	refusal    *links.ErrDeviceRefused // while set, every fetch is refused with it
	logs       []string                // what the daemon logged
}

func (w *world) rec(s string) { w.mu.Lock(); w.log = append(w.log, s); w.mu.Unlock() }
func (w *world) events() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return strings.Join(w.log, " ")
}

// logged says whether the daemon has logged a line containing sub.
func (w *world) logged(sub string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, l := range w.logs {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

type fakeEngine struct {
	w    *world
	addr string
	name string
}

func (e *fakeEngine) SocksAddr() string             { return e.addr }
func (e *fakeEngine) Credentials() (string, string) { return "u", "p" }
func (e *fakeEngine) State() string {
	e.w.mu.Lock()
	defer e.w.mu.Unlock()
	if e.w.engState != "" {
		return e.w.engState
	}
	return "running"
}
func (e *fakeEngine) Stop(time.Duration) error {
	e.w.mu.Lock()
	delay := e.w.stopDelay
	e.w.mu.Unlock()
	time.Sleep(delay)
	if e.name == "xray" {
		e.w.rec("xray-stop")
	} else {
		e.w.rec("engine-stop")
	}
	return nil
}

type fakeFront struct{ w *world }

func (f *fakeFront) Close() error { f.w.rec("front-close"); return nil }

type fakeRoutes struct{ w *world }

func (r *fakeRoutes) GlobalAddresses() ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("203.0.113.5")}, nil
}
func (r *fakeRoutes) Sync([]netip.Addr) error { r.w.rec("routes:sync"); return nil }
func (r *fakeRoutes) Clear() error            { r.w.rec("routes:clear"); return nil }
func (r *fakeRoutes) CleanupStale() error     { r.w.rec("routes:cleanup"); return nil }
func (r *fakeRoutes) InstallKillSwitch(uid int) error {
	r.w.rec("routes:killswitch:" + strconv.Itoa(uid))
	return nil
}
func (r *fakeRoutes) RemoveKillSwitch() error { r.w.rec("routes:killswitch-off"); return nil }
func (r *fakeRoutes) WatchAddresses(context.Context, func()) (func(), error) {
	r.w.rec("watch")
	return func() { r.w.rec("watch-stop") }, nil
}

func newWorld(t *testing.T) (*world, *Daemon, string) {
	t.Helper()
	fixture, err := os.ReadFile("../links/testdata/partner-olcbox.txt")
	if err != nil {
		t.Fatal(err)
	}
	w := &world{body: fixture, engineErr: map[string]error{}, deadProbe: map[string]bool{}, frontProbe: map[string]string{}, bodies: map[string][]byte{}, fetchErrs: map[string]error{}}
	dir := t.TempDir()
	deps := Deps{
		ConfigPath: filepath.Join(dir, "config.yaml"), StateDir: dir, SocketPath: filepath.Join(dir, "s.sock"),
		Fetch: func(_ context.Context, url string, dev links.Device) ([]byte, links.Headers, error) {
			w.mu.Lock()
			delay, body, err, refusal := w.fetchDelay, w.body, w.fetchErr, w.refusal
			if b, ok := w.bodies[url]; ok {
				body = b
			}
			if e, ok := w.fetchErrs[url]; ok {
				err = e
			}
			w.fetches++
			w.devices = append(w.devices, dev)
			w.mu.Unlock()
			if delay > 0 {
				time.Sleep(delay)
			}
			if err != nil {
				return nil, links.Headers{}, err
			}
			if refusal != nil {
				// what links.Fetch returns: no list, the headers the answer carried, the error
				return nil, links.Headers{Title: "Partner", UpdateIntervalHours: 1, Refusal: refusal.Kind, Announce: refusal.Announce}, refusal
			}
			return body, links.Headers{Title: "Partner", UpdateIntervalHours: 1}, nil
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
			kind := "socks"
			switch {
			case p.Upstream.Vless != nil:
				kind = "vless"
			case p.Upstream.Hy2 != nil:
				kind = "hy2"
			}
			if p.ProbeListen == "" || p.ProbeUser == "" {
				return nil, errors.New("front without a probe inbound")
			}
			w.mu.Lock()
			w.fronts++
			n := w.fronts
			w.frontProbe[p.ProbeListen] = "front:" + strconv.Itoa(n)
			if w.deadFrom > 0 && n >= w.deadFrom {
				w.deadProbe["front:"+strconv.Itoa(n)] = true
			}
			w.mu.Unlock()
			w.rec("front:" + string(p.Mode) + ":" + kind)
			return &fakeFront{w: w}, nil
		},
		StartXray: func(_ context.Context, p xray.Params) (Engine, error) {
			w.mu.Lock()
			err := w.xrayErr
			w.mu.Unlock()
			w.rec("xray:" + p.Line.Host)
			if err != nil {
				return nil, err
			}
			return &fakeEngine{w: w, addr: "127.0.0.1:2", name: "xray"}, nil
		},
		Routes: &fakeRoutes{w: w},
		Probe: func(_ context.Context, addr, _, _ string) error {
			w.mu.Lock()
			defer w.mu.Unlock()
			w.probes++
			if name, ok := w.frontProbe[addr]; ok {
				addr = name
			}
			if w.deadProbe[addr] {
				return errors.New("dead probe " + addr)
			}
			return w.probeErr
		},
		Now: time.Now, UID: 977,
		Logf: func(format string, args ...any) {
			t.Logf(format, args...)
			w.mu.Lock()
			w.logs = append(w.logs, fmt.Sprintf(format, args...))
			w.mu.Unlock()
		},
		Device:       links.Device{OS: "Linux", OSVersion: "6.1.0-test", Model: "PC"},
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
	if !resp.OK || !strings.Contains(resp.Message, "64") {
		t.Fatalf("%+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 64 || list.Entries[0].Index != 1 || list.Entries[0].Country != "CA" {
		t.Fatalf("%d entries: %+v", len(list.Entries), list.Entries)
	}
	if resp = d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	st := waitState(t, d, "up")
	if st.Line == nil || st.Line.Country != "DE" || st.Line.Carrier != "telemost" || st.Mode != "proxy" || st.Proxy == nil || st.Proxy.Socks != "127.0.0.1:1080" {
		t.Fatalf("%+v", st)
	}
	// the front gets the credentials the engine reports, not ones remembered beside it
	if ev := w.events(); !strings.HasPrefix(ev, "routes:cleanup engine:telemost@") || !strings.Contains(ev+" ", "front:proxy:socks ") {
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
		if st.State == "up" && st.Line != nil && st.Line.Carrier == "salutejazz" {
			break
		}
		if st.State == "connecting" && st.Line != nil && st.Line.Carrier == "salutejazz" {
			w.mu.Lock()
			w.probeErr = nil
			w.mu.Unlock()
		}
		time.Sleep(10 * time.Millisecond)
	}
	if st.Line == nil || st.Line.Carrier != "salutejazz" {
		t.Fatalf("three probe failures move to the next carrier: %+v", st.Line)
	}
	// the file is written before the state reads "up", so no polling is needed
	// the selection is global (no --subscription), so the key has no list
	lg, _ := store.LoadLastGood(dir)
	if lg["|DE"] != st.Line.ID {
		t.Fatalf("last good %v, line %s", lg, st.Line.ID)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	w.mu.Lock()
	w.engineErr = map[string]error{}
	w.mu.Unlock()
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
		if st.State == "up" && st.Line != nil && st.Line.Country == "FR" {
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
	if len(list.Entries) != 64 {
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
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: "trojan://p@h:443#x"}); resp.OK || resp.Error != "unsupported" {
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

func count(ev, token string) int { return strings.Count(" "+ev+" ", " "+token+" ") }

// Reviewer finding 1: Handle is concurrent (ipc.Serve runs each request in its
// own goroutine) and a refresh can call startConnect too. Two callers within a
// slow engine stop must never leave an engine, a front or the rules behind.
func TestTwoCallersDoNotOverlap(t *testing.T) {
	w, d, _ := newWorld(t)
	w.stopDelay = 60 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun"})
	waitState(t, d, "up")
	var wg sync.WaitGroup
	for _, sel := range []string{"FR", "GB", "IT"} {
		wg.Add(1)
		go func(sel string) {
			defer wg.Done()
			d.Handle(ctx, ipc.Request{Verb: "connect", Selector: sel, Mode: "tun"})
		}(sel)
		time.Sleep(10 * time.Millisecond)
	}
	wg.Wait()
	waitState(t, d, "up")
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	ev := w.events()
	starts := strings.Count(ev, "engine:")
	if stops := count(ev, "engine-stop"); stops != starts {
		t.Fatalf("%d engines started, %d stopped: %s", starts, stops, ev)
	}
	if opens, closes := count(ev, "front:tun:socks"), count(ev, "front-close"); opens != closes {
		t.Fatalf("%d fronts, %d closed: %s", opens, closes, ev)
	}
	if syncs, clears := count(ev, "routes:sync"), count(ev, "routes:clear"); syncs != clears {
		t.Fatalf("%d rule syncs, %d clears: %s", syncs, clears, ev)
	}
	if !strings.HasSuffix(ev, "routes:clear") {
		t.Fatalf("the last thing to happen is the last teardown's clear: %s", ev)
	}
}

// Reviewer finding 2: the address watcher belongs to one connection and stops
// before that connection's rules are cleared.
func TestWatcherStopsBeforeClear(t *testing.T) {
	w, d, _ := newWorld(t)
	w.engineErr["telemost"] = errors.New("room full")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun"})
	waitState(t, d, "up")
	w.mu.Lock()
	w.probeErr = errors.New("dead")
	w.mu.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		if st.State == "connecting" && st.Line != nil && st.Line.Carrier == "salutejazz" {
			w.mu.Lock()
			w.probeErr = nil
			w.mu.Unlock()
		}
		if st.State == "up" && st.Line != nil && st.Line.Carrier == "salutejazz" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	ev := w.events()
	if count(ev, "watch") != 2 || count(ev, "watch-stop") != 2 {
		t.Fatalf("one watcher per connection, each stopped: %s", ev)
	}
	if strings.Count(ev, "watch-stop front-close engine-stop routes:clear") != 2 {
		t.Fatalf("the watcher stops before the teardown clears the rules: %s", ev)
	}
}

// Reviewer finding 9: an engine whose runtime is no longer running ends the
// line at once, not after three probe intervals.
func TestEngineStateEndsLine(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	waitState(t, d, "up")
	w.mu.Lock()
	w.engState = "stopped"
	w.mu.Unlock()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(w.events(), "engine-stop engine:wbstream@") {
			w.mu.Lock()
			w.engState = ""
			w.mu.Unlock()
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("a stopped engine moves to the next carrier: %s", w.events())
}

// Reviewer finding 3: a fetch error carries the URL, and the URL carries the token.
func TestFetchErrorNeverShowsToken(t *testing.T) {
	w, d, _ := newWorld(t)
	secret := listURL + "&token=SECRETTOKEN"
	w.fetchErr = &url.Error{Op: "Get", URL: secret, Err: errors.New("dial tcp: i/o timeout")}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: secret})
	if resp.OK || strings.Contains(resp.Message, "SECRETTOKEN") || strings.Contains(resp.Message, "4gg96") {
		t.Fatalf("add: %+v", resp)
	}
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: secret, IntervalHours: 1}}
	cfg.Selection = &store.Selection{Subscription: secret, Selector: "DE", Mode: "proxy"}
	d.mu.Lock()
	d.cfg = cfg
	d.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	_ = d.startConnect(*cfg.Selection)
	st := waitState(t, d, "failed")
	all := st.LastError
	for _, s := range st.Subscriptions {
		all += " " + s.Error + " " + s.URL
	}
	if strings.Contains(all, "SECRETTOKEN") {
		t.Fatalf("token in status: %+v", st)
	}
}

func unifiedWorld(t *testing.T) (*world, *Daemon, string) {
	t.Helper()
	w, d, dir := newWorld(t)
	fixture, err := os.ReadFile("../links/testdata/proofkit-unified.txt")
	if err != nil {
		t.Fatal(err)
	}
	w.body = fixture
	return w, d, dir
}

func TestNativeLineProxyMode(t *testing.T) {
	w, d, _ := unifiedWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "1", Mode: "proxy"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	st := waitState(t, d, "up")
	if st.Line.Kind != "vless" {
		t.Fatalf("%+v", st.Line)
	}
	ev := w.events()
	if strings.Contains(ev, "engine:") || strings.Contains(ev, "xray:") {
		t.Fatalf("a native line starts no engine: %s", ev)
	}
	if !strings.Contains(ev, "front:proxy:vless") {
		t.Fatalf("%s", ev)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	if !strings.HasSuffix(w.events(), "front-close") {
		t.Fatalf("%s", w.events())
	}
}

// Review Focus 1: in tun mode a native line is proven in a proxy-only front
// before the tun exists; a dead server never gets a tun.
func TestNativeLineTunPreflight(t *testing.T) {
	w, d, _ := unifiedWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "2", Mode: "tun"}) // the hy2 line
	waitState(t, d, "up")
	ev := w.events()
	if !strings.Contains(ev, "front:proxy:hy2 front-close routes:sync front:tun:hy2") {
		t.Fatalf("pre-flight before the rules and the tun: %s", ev)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")

	// now the server is dead: every pre-flight fails, no rules, no tun
	w.mu.Lock()
	w.deadFrom = 3 // the next front is the third one made
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "2", Mode: "tun"})
	st := waitState(t, d, "failed")
	tail := w.events()[len(ev):]
	if strings.Contains(tail, "routes:sync") || strings.Contains(tail, "front:tun") {
		t.Fatalf("a dead server must not get a tun: %s", tail)
	}
	if st.LastError == "" {
		t.Fatalf("%+v", st)
	}
}

func TestXhttpLineUsesXray(t *testing.T) {
	w, d, _ := unifiedWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "3", Mode: "proxy"})
	waitState(t, d, "up")
	ev := w.events()
	if !strings.Contains(ev, "xray:203.0.113.9 front:proxy:socks") {
		t.Fatalf("xhttp runs in Xray behind a socks front: %s", ev)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	if !strings.HasSuffix(w.events(), "front-close xray-stop") {
		t.Fatalf("%s", w.events())
	}
}

// Review Focus 4: one country, four kinds, list order, failover across kinds.
func TestMixedCountryFailover(t *testing.T) {
	w, d, _ := unifiedWorld(t)
	w.deadProbe["front:1"] = true // the Reality pre-flight/front fails
	w.xrayErr = errors.New("xhttp server gone")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	st := waitState(t, d, "up")
	if st.Line.Kind != "hysteria2" {
		t.Fatalf("Reality failed, Hysteria2 next: %+v", st.Line)
	}
	ev := w.events()
	if !strings.Contains(ev, "front:proxy:vless front-close front:proxy:hy2") {
		t.Fatalf("list order, teardown between: %s", ev)
	}
	// now the hy2 line dies: XHTTP fails to start, olcRTC takes over
	w.mu.Lock()
	w.probeErr = errors.New("dead")
	w.mu.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st = d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		if st.State == "connecting" && st.Line != nil && st.Line.Kind == "olcrtc" {
			w.mu.Lock()
			w.probeErr = nil
			w.mu.Unlock()
		}
		if st.State == "up" && st.Line != nil && st.Line.Kind == "olcrtc" {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if st.Line == nil || st.Line.Kind != "olcrtc" {
		t.Fatalf("across kinds: %+v\n%s", st.Line, w.events())
	}
	if !strings.Contains(w.events(), "xray:203.0.113.9 engine:telemost@") {
		t.Fatalf("xhttp tried (and failed) before olcRTC: %s", w.events())
	}
}

func TestListShowsKinds(t *testing.T) {
	w, d, _ := newWorld(t)
	plain, err := os.ReadFile("../links/testdata/partner-plain.txt")
	if err != nil {
		t.Fatal(err)
	}
	w.body = plain
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if !resp.OK || !strings.Contains(resp.Message, "17 entries (17 usable now)") {
		t.Fatalf("%+v", resp)
	}
	list := d.Handle(ctx, ipc.Request{Verb: "list"})
	kinds := map[string]int{}
	for _, e := range list.Entries {
		kinds[e.Kind]++
		if e.Problem != "" {
			t.Fatalf("%+v", e)
		}
	}
	if kinds["vless"] != 9 || kinds["hysteria2"] != 8 {
		t.Fatalf("%v", kinds)
	}
}

// Reviewer finding 3 (spec §5): a single vless:// or hysteria2:// line is a source too.
func TestAddSingleShareLines(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	vless := "vless://00000000-0000-4000-8000-000000000000@203.0.113.9:443?type=tcp&security=reality&sni=yandex.ru&fp=chrome&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=ab12&flow=xtls-rprx-vision#DE via RU"
	hy2 := "hy2://pw@203.0.113.9:38443?sni=s.example&obfs=salamander&obfs-password=salt#DE hy2"
	for _, src := range []string{vless, hy2} {
		if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: src}); !resp.OK {
			t.Fatalf("%s: %+v", src[:12], resp)
		}
	}
	list := d.Handle(ctx, ipc.Request{Verb: "list"})
	if len(list.Entries) != 2 || list.Entries[0].Kind != "vless" || list.Entries[0].Label != "DE via RU" || list.Entries[1].Kind != "hysteria2" || list.Entries[1].Label != "DE hy2" {
		t.Fatalf("%+v", list.Entries)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: "vless://x@h:443?type=grpc"}); resp.OK || !strings.Contains(resp.Message, "grpc") {
		t.Fatalf("an unsupported single line is refused with the reason: %+v", resp)
	}
	// indices count per subscription (a deferred Stage A minor): pick the line by label
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE hy2", Mode: "proxy"})
	if st := waitState(t, d, "up"); st.Line.Kind != "hysteria2" {
		t.Fatalf("%+v", st.Line)
	}
}

// Reviewer finding 4: a probe-inbound failure after the rules went in clears them.
func TestTunRulesClearedWhenProbeInboundFails(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	orig := newProbeInbound
	calls := 0
	newProbeInbound = func() (string, string, string, error) {
		calls++
		if calls == 1 {
			return "", "", "", errors.New("no free port")
		}
		return orig()
	}
	defer func() { newProbeInbound = orig }()
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun"})
	waitState(t, d, "up")
	ev := w.events()
	if !strings.Contains(ev, "routes:sync routes:clear engine-stop") {
		t.Fatalf("rules cleared when the probe inbound cannot be made: %s", ev)
	}
}

// Review Focus 4: indices are continuous across subscriptions.
func TestGlobalIndices(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	line := "vless://00000000-0000-4000-8000-000000000000@203.0.113.9:443?type=tcp&security=reality&sni=s&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=ab12#solo"
	d.Handle(ctx, ipc.Request{Verb: "add", Source: line})
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	list := d.Handle(ctx, ipc.Request{Verb: "list"})
	if len(list.Entries) != 65 || list.Entries[0].Index != 1 || list.Entries[1].Index != 2 || list.Entries[64].Index != 65 {
		t.Fatalf("continuous numbering: %d entries, %d %d %d", len(list.Entries), list.Entries[0].Index, list.Entries[1].Index, list.Entries[len(list.Entries)-1].Index)
	}
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "2", Mode: "proxy"})
	st := waitState(t, d, "up")
	if st.Line.Country != "CA" || st.Line.Carrier != "telemost" {
		t.Fatalf("global index 2 is the rooms list's first line: %+v", st.Line)
	}
}

func TestHttpListRefused(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx := context.Background()
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: "http://sub.example/sub/a/b"}); resp.OK || resp.Error != "insecure" {
		t.Fatalf("%+v", resp)
	}
}

func TestListenChecks(t *testing.T) {
	_, d, dir := newWorld(t)
	cfg := store.Defaults()
	cfg.Proxy.Listen = "localhost"
	_ = store.Save(filepath.Join(dir, "config.yaml"), cfg)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"}); resp.OK || resp.Error != "bad_listen" {
		t.Fatalf("localhost is not an address sing-box takes: %+v", resp)
	}
	d.mu.Lock()
	d.cfg.Proxy.Listen = "::1"
	d.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	if st := waitState(t, d, "up"); st.Proxy == nil || st.Proxy.Socks != "[::1]:1080" {
		t.Fatalf("an IPv6 listen is bracketed: %+v", st.Proxy)
	}
}

func TestInlineLineAddedOnce(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx := context.Background()
	line := "olcrtc://wbstream?vp8channel@room_q#" + strings.Repeat("cd", 32) + "$JP"
	d.Handle(ctx, ipc.Request{Verb: "add", Source: line})
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: line}); !resp.OK || resp.Message != "already added" {
		t.Fatalf("%+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 1 {
		t.Fatalf("%d", len(list.Entries))
	}
}

// A selector that matches nothing today is retried when the list changes.
func TestSelectorRetriedOnRefresh(t *testing.T) {
	w, d, dir := newWorld(t)
	var withoutFR []string
	for _, l := range strings.Split(string(w.body), "\n") {
		if !strings.Contains(l, "FR") {
			withoutFR = append(withoutFR, l)
		}
	}
	full := w.body
	w.body = []byte(strings.Join(withoutFR, "\n"))
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: listURL, IntervalHours: 1}}
	cfg.Selection = &store.Selection{Subscription: listURL, Selector: "FR", Mode: "proxy"}
	_ = store.Save(filepath.Join(dir, "config.yaml"), cfg)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	waitState(t, d, "failed")
	w.mu.Lock()
	w.body = full
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	if st := waitState(t, d, "up"); st.Line.Country != "FR" {
		t.Fatalf("%+v", st.Line)
	}
}

// After every line of a round failed, the next round starts after the one
// that failed last, not with the last-good line that just died.
func TestFailoverContinuesAfterWrap(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	// every line comes up, then its own front's probes die (later fronts stay alive)
	var order []string
	killed := map[int]bool{}
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) && len(order) < 6 {
		st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		if st.State == "up" && st.Line != nil {
			w.mu.Lock()
			n := w.fronts
			if !killed[n] {
				killed[n] = true
				order = append(order, st.Line.Carrier)
				w.deadProbe["front:"+strconv.Itoa(n)] = true
			}
			w.mu.Unlock()
		}
		time.Sleep(5 * time.Millisecond)
	}
	want := []string{"telemost", "wbstream", "salutejazz", "vkcalls", "telemost", "wbstream"}
	if strings.Join(order, " ") != strings.Join(want, " ") {
		t.Fatalf("order of lines brought up: %v, want %v", order, want)
	}
}

func TestRefreshIsParallel(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	for _, u := range []string{"https://a.example/sub/1/x", "https://b.example/sub/1/x", "https://c.example/sub/1/x", "https://d.example/sub/1/x"} {
		d.Handle(ctx, ipc.Request{Verb: "add", Source: u})
	}
	// Four fetches of 200 ms each: about 200 ms in parallel, 800 ms one after
	// another. The line between them sits at 500 ms, far from both: at 100 ms
	// and a 250 ms line, a loaded runner under -race took 290 ms for a parallel
	// refresh and the test called it serial.
	w.mu.Lock()
	w.fetchDelay = 200 * time.Millisecond
	w.mu.Unlock()
	start := time.Now()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	if el := time.Since(start); el > 500*time.Millisecond {
		t.Fatalf("four lists refreshed serially: %s", el)
	}
}

// Review Focus 4 in tun mode: the first kind's pre-flight fails, the next kind
// gets the tun; no tun front for the failed kind.
func TestMixedCountryFailoverTun(t *testing.T) {
	w, d, _ := unifiedWorld(t)
	w.deadProbe["front:1"] = true // the Reality pre-flight
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun"})
	st := waitState(t, d, "up")
	if st.Line.Kind != "hysteria2" {
		t.Fatalf("%+v", st.Line)
	}
	ev := w.events()
	if !strings.Contains(ev, "front:proxy:vless front-close front:proxy:hy2 front-close routes:sync front:tun:hy2") {
		t.Fatalf("pre-flights in order, rules and tun only for the line that passed: %s", ev)
	}
	if strings.Contains(ev, "front:tun:vless") {
		t.Fatalf("no tun for the failed kind: %s", ev)
	}
}

func crypt1World(t *testing.T) (*world, *Daemon, [32]byte) {
	t.Helper()
	w, d, _ := newWorld(t)
	master := crypt1test.Master()
	d.deps.Decrypt = func(blob string) ([]byte, bool) { return crypt1.Decrypt(master, blob) }
	return w, d, master
}

// Review Focus 3: a crypt1 link whose payload is a URL becomes a subscription;
// one whose payload is lines becomes an inline subscription.
func TestCrypt1Links(t *testing.T) {
	_, d, master := crypt1World(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	link := crypt1.LinkPrefix + crypt1test.Encrypt(master, []byte(listURL))
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: link}); !resp.OK || !strings.Contains(resp.Message, "64 entries") {
		t.Fatalf("%+v", resp)
	}
	lines := "olcrtc://wbstream?vp8channel@room_q#" + strings.Repeat("cd", 32) + "$JP · WB\nolcrtc://salutejazz?datachannel@a:b#" + strings.Repeat("cd", 32) + "$JP · SJ\n"
	link = crypt1.LinkPrefix + crypt1test.Encrypt(master, []byte(lines))
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: link}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	if list := d.Handle(ctx, ipc.Request{Verb: "list"}); len(list.Entries) != 66 || list.Entries[65].Label != "JP · SJ" {
		t.Fatalf("%d entries, last %+v", len(list.Entries), list.Entries[len(list.Entries)-1])
	}
	bad := crypt1.LinkPrefix + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: bad}); resp.OK || resp.Error != "bad_line" {
		t.Fatalf("a blob that does not verify is refused: %+v", resp)
	}
}

func TestCrypt1Body(t *testing.T) {
	w, d, master := crypt1World(t)
	w.body = []byte(crypt1test.Encrypt(master, w.body))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL}); !resp.OK || !strings.Contains(resp.Message, "64 entries") {
		t.Fatalf("an encrypted body is a list: %+v", resp)
	}
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	waitState(t, d, "up")
}

func TestCrypt1WithoutKey(t *testing.T) {
	_, d, _ := newWorld(t)
	ctx := context.Background()
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: crypt1.LinkPrefix + "abc"}); resp.OK || !strings.Contains(resp.Message, "crypt1") {
		t.Fatalf("%+v", resp)
	}
}

// The kill switch goes in before the first engine start, stays across a
// failover (the box is closed between lines, not open) and goes away on
// disconnect; the own-address rules stay with it, not with the line.
func TestKillSwitchLifecycle(t *testing.T) {
	w, d, dir := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if resp := d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy", KillSwitch: true}); resp.OK || resp.Error != "bad_mode" {
		t.Fatalf("proxy mode has nothing to kill: %+v", resp)
	}
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun", KillSwitch: true})
	st := waitState(t, d, "up")
	if !st.KillSwitch {
		t.Fatalf("status shows it: %+v", st)
	}
	cfg, _ := store.Load(filepath.Join(dir, "config.yaml"))
	if cfg.Selection == nil || !cfg.Selection.KillSwitch {
		t.Fatalf("persisted with the selection: %+v", cfg.Selection)
	}
	ev := w.events()
	if i, j := strings.Index(ev, "routes:killswitch:977 routes:sync watch"), strings.Index(ev, "engine"); i < 0 || j < i {
		t.Fatalf("installed, with the own-address rules, before any engine: %s", ev)
	}
	// failover: the first line's probes die
	w.mu.Lock()
	w.deadProbe["front:1"] = true
	w.mu.Unlock()
	waitFor(t, func() bool {
		s := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
		return s.State == "up" && s.Line != nil && s.Line.Carrier != "telemost"
	})
	ev = w.events()
	if strings.Contains(ev, "killswitch-off") || strings.Contains(ev, "routes:clear") || strings.Count(ev, "routes:sync") != 1 {
		t.Fatalf("between lines the switch and the own rules stay: %s", ev)
	}
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	ev = w.events()
	if !strings.HasSuffix(strings.TrimSpace(ev), "watch-stop routes:clear routes:killswitch-off") {
		t.Fatalf("disconnect removes the rules after the line: %s", ev)
	}
}

// A stored selection with the switch installs it at start (before the first
// engine start) and a clean stop removes it.
func TestKillSwitchRestoredAtStartRemovedAtStop(t *testing.T) {
	w, d, dir := newWorld(t)
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: listURL, IntervalHours: 1}}
	cfg.Selection = &store.Selection{Selector: "DE", Mode: "tun", KillSwitch: true}
	_ = store.Save(filepath.Join(dir, "config.yaml"), cfg)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = d.Run(ctx); close(done) }()
	waitState(t, d, "up")
	ev := w.events()
	if i, j := strings.Index(ev, "routes:cleanup"), strings.Index(ev, "routes:killswitch:977"); i < 0 || j < i || strings.Index(ev, "engine") < j {
		t.Fatalf("cleanup, then the switch, then the engine: %s", ev)
	}
	cancel()
	<-done
	if ev := w.events(); !strings.Contains(ev, "routes:killswitch-off") {
		t.Fatalf("a clean stop gives the box back: %s", ev)
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("condition not met in time")
}

// Review finding I1: removing the subscription a kill-switch selection was
// made for drops the selection and the switch with it.
func TestRemoveSubscriptionRemovesKillSwitch(t *testing.T) {
	w, d, dir := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "tun", KillSwitch: true, Subscription: listURL})
	waitState(t, d, "up")
	if resp := d.Handle(ctx, ipc.Request{Verb: "remove", Subscription: listURL}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	st := waitState(t, d, "idle")
	if st.KillSwitch || d.killSwitchOn() {
		t.Fatalf("the switch must go with the selection: %+v on=%v", st, d.killSwitchOn())
	}
	if ev := w.events(); !strings.HasSuffix(strings.TrimSpace(ev), "routes:killswitch-off") {
		t.Fatalf("%s", ev)
	}
	cfg, _ := store.Load(filepath.Join(dir, "config.yaml"))
	if cfg.Selection != nil || len(cfg.Subscriptions) != 0 {
		t.Fatalf("%+v", cfg)
	}
}

// Review finding I4: a connect that arrives before Run reaches its stored
// selection must not be restarted by Run (one loop, one engine start).
func TestConnectBeforeRunStartsOneLoop(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	waitState(t, d, "up")
	go func() { _ = d.Run(ctx) }()
	time.Sleep(300 * time.Millisecond)
	st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
	ev := w.events()
	if st.State != "up" || strings.Count(ev, "engine:") != 1 || strings.Contains(ev, "engine-stop") {
		t.Fatalf("Run restarted a live selection: %s / %s", st.State, ev)
	}
}

// Review minor re-graded: a numeric selection names a line, not a position.
// When an earlier list cannot be read later, the stored selection still
// reconnects the same line instead of whatever now sits at that index.
func TestNumericSelectionSticksToItsLine(t *testing.T) {
	w, d, dir := newWorld(t)
	urlA, urlB := "https://a.example/sub/1/x", listURL
	w.bodies[urlA] = []byte("vless://00000000-0000-4000-8000-000000000000@203.0.113.9:443?type=tcp&security=reality&sni=s&pbk=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA&sid=ab12#solo\n")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = d.Run(ctx); close(done) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: urlA})
	d.Handle(ctx, ipc.Request{Verb: "add", Source: urlB})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "2", Mode: "proxy"})
	first := waitState(t, d, "up")
	if first.Line.Carrier != "telemost" {
		t.Fatalf("index 2 is B's first line: %+v", first.Line)
	}
	cancel()
	<-done
	// list A vanishes: no cache, its fetch fails; B is fine
	if err := os.RemoveAll(filepath.Join(dir, "lists")); err != nil {
		t.Fatal(err)
	}
	w.mu.Lock()
	w.fetchErrs[urlA] = errors.New("503")
	w.mu.Unlock()
	d2 := New(d.deps)
	ctx2, cancel2 := context.WithCancel(context.Background())
	defer cancel2()
	go func() { _ = d2.Run(ctx2) }()
	again := waitState(t, d2, "up")
	if again.Line.ID != first.Line.ID {
		t.Fatalf("the stored selection moved to another line: was %s (%s), now %s (%s)", first.Line.ID, first.Line.Label, again.Line.ID, again.Line.Label)
	}
}

// The id is the file the olcRTC engine keeps its own device id in: a UUID v4,
// a newline after it, mode 0600 (the engine's resolveDeviceID). A box where an
// engine already made it keeps that id, and nothing the daemon does rewrites
// it: a provider counts devices by this id, and a new one is a new device.
func TestDeviceIDIsReusedFromTheEngineFile(t *testing.T) {
	v4 := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	t.Run("a file the engine made is read back unchanged", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "device-id")
		const engineID = "6f1d3c2a-5b7e-4a90-8c11-2d3e4f5a6b7c"
		if err := os.WriteFile(path, []byte(engineID+"\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		got, err := deviceID(dir)
		if err != nil || got != engineID {
			t.Fatalf("%q %v", got, err)
		}
		if raw, _ := os.ReadFile(path); string(raw) != engineID+"\n" {
			t.Fatalf("the file was rewritten: %q", raw)
		}
	})
	t.Run("whatever the engine wrote is the id, trimmed", func(t *testing.T) {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, "device-id"), []byte("  install-0123456789abcdef \r\n\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		if got, err := deviceID(dir); err != nil || got != "install-0123456789abcdef" {
			t.Fatalf("%q %v", got, err)
		}
	})
	t.Run("absent: a fresh UUID v4 is written the way the engine writes it, and read again", func(t *testing.T) {
		dir := filepath.Join(t.TempDir(), "state") // not even the directory exists yet
		first, err := deviceID(dir)
		if err != nil || !v4.MatchString(first) {
			t.Fatalf("%q %v", first, err)
		}
		path := filepath.Join(dir, "device-id")
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != 0o600 {
			t.Fatalf("%v %v", info, err)
		}
		if raw, _ := os.ReadFile(path); string(raw) != first+"\n" {
			t.Fatalf("the engine reads the file's first line: %q", raw)
		}
		if again, err := deviceID(dir); err != nil || again != first {
			t.Fatalf("a second start must find the same id: %q %v", again, err)
		}
		other, err := deviceID(filepath.Join(t.TempDir(), "state"))
		if err != nil || other == first {
			t.Fatalf("two boxes, one id: %q %v", other, err)
		}
	})
	t.Run("an empty file is no id, as the engine reads it", func(t *testing.T) {
		dir := t.TempDir()
		path := filepath.Join(dir, "device-id")
		if err := os.WriteFile(path, []byte(" \n"), 0o600); err != nil {
			t.Fatal(err)
		}
		got, err := deviceID(dir)
		if err != nil || !v4.MatchString(got) {
			t.Fatalf("%q %v", got, err)
		}
		if raw, _ := os.ReadFile(path); string(raw) != got+"\n" {
			t.Fatalf("%q", raw)
		}
	})
	t.Run("a place that cannot hold the file is an error, not an invented id", func(t *testing.T) {
		blocked := filepath.Join(t.TempDir(), "file")
		if err := os.WriteFile(blocked, nil, 0o600); err != nil {
			t.Fatal(err)
		}
		if got, err := deviceID(filepath.Join(blocked, "state")); err == nil || got != "" {
			t.Fatalf("a state directory under a file: %q %v", got, err)
		}
		dir := t.TempDir()
		if err := os.Mkdir(filepath.Join(dir, "device-id"), 0o700); err != nil {
			t.Fatal(err)
		}
		if got, err := deviceID(dir); err == nil || got != "" {
			t.Fatalf("a directory where the file should be: %q %v", got, err)
		}
	})
}

// Every list fetch names the device, with the id the daemon read at its start.
func TestTheDaemonNamesItsDeviceOnEveryFetch(t *testing.T) {
	w, d, dir := newWorld(t)
	ctx := context.Background()
	if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	if resp := d.Handle(ctx, ipc.Request{Verb: "refresh"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "device-id"))
	if err != nil {
		t.Fatal(err)
	}
	id := strings.TrimSpace(string(raw))
	w.mu.Lock()
	devices := append([]links.Device(nil), w.devices...)
	w.mu.Unlock()
	if len(devices) < 2 || id == "" {
		t.Fatalf("%d fetches, id %q", len(devices), id)
	}
	for _, dev := range devices {
		if dev != (links.Device{ID: id, OS: "Linux", OSVersion: "6.1.0-test", Model: "PC"}) {
			t.Fatalf("%+v", dev)
		}
	}
	// a restart on the same state directory is the same device
	again := New(d.deps)
	if dev := again.device(); dev.ID != id {
		t.Fatalf("a restart made another device: %q, was %q", dev.ID, id)
	}
}

// Run makes the id at its start, not at the first fetch: with no list yet there
// is nothing to fetch, and the file is the engine's to find.
func TestRunMakesTheDeviceIDAtItsStart(t *testing.T) {
	_, d, dir := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if raw, err := os.ReadFile(filepath.Join(dir, "device-id")); err == nil && strings.TrimSpace(string(raw)) != "" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("Run left no device-id behind")
}

// With no id to send the fetch is made without one, and the daemon says so
// once; an id made up in its place would be a new device at every start.
func TestADeviceIDThatCannotBeMadeIsNotInvented(t *testing.T) {
	blocked := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocked, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var logs []string
	d := New(Deps{
		StateDir: filepath.Join(blocked, "state"),
		Device:   links.Device{OS: "Linux", OSVersion: "6.1.0-test", Model: "PC"},
		Logf: func(format string, args ...any) {
			mu.Lock()
			logs = append(logs, fmt.Sprintf(format, args...))
			mu.Unlock()
		},
	})
	for range 3 {
		if dev := d.device(); dev != (links.Device{OS: "Linux", OSVersion: "6.1.0-test", Model: "PC"}) {
			t.Fatalf("%+v", dev)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if len(logs) != 1 || !strings.Contains(logs[0], "device id") {
		t.Fatalf("%q", logs)
	}
}

const limitSentence = "Acme: this link is already in use on its 2 devices. Ask Acme to free one."

// The host's refusal is its final word on this device: the list is empty, the
// old lines stop working, and the list stays on record with the reason.
func TestARefusedListIsEmptiedAndSaysWhy(t *testing.T) {
	w, d, dir := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if n := len(d.Handle(ctx, ipc.Request{Verb: "list"}).Entries); n != 64 {
		t.Fatalf("%d entries before the refusal", n)
	}

	w.mu.Lock()
	w.refusal = &links.ErrDeviceRefused{Kind: links.RefusalLimit, Announce: limitSentence}
	w.mu.Unlock()
	if resp := d.Handle(ctx, ipc.Request{Verb: "refresh"}); !resp.OK {
		t.Fatalf("%+v", resp)
	}

	list := d.Handle(ctx, ipc.Request{Verb: "list"})
	if len(list.Entries) != 0 {
		t.Fatalf("a refused list keeps no lines: %d", len(list.Entries))
	}
	if !strings.Contains(list.Message, "Partner: device refused: "+limitSentence) {
		t.Fatalf("list says why it is empty: %q", list.Message)
	}
	st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
	if len(st.Subscriptions) != 1 {
		t.Fatalf("%+v", st.Subscriptions)
	}
	if sub := st.Subscriptions[0]; sub.Error != "device refused: "+limitSentence || sub.Title != "Partner" || sub.FetchedAt == "" || sub.NextRefresh == "" {
		t.Fatalf("the list stays on record with its reason: %+v", sub)
	}
	cache, err := store.LoadCache(dir, listURL)
	if err != nil || cache == nil || len(cache.Body) != 0 || cache.Headers.Refusal != links.RefusalLimit || cache.Headers.Announce != limitSentence {
		t.Fatalf("%+v %v", cache, err)
	}
	if !w.logged("device refused: " + limitSentence) {
		t.Fatalf("the refresh is logged: %q", w.logs)
	}
	if w.logged("/sub/a/b") {
		t.Fatalf("the list URL is a credential: %q", w.logs)
	}

	// room is made at the provider: the next refresh serves the list again
	w.mu.Lock()
	w.refusal = nil
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	list = d.Handle(ctx, ipc.Request{Verb: "list"})
	if len(list.Entries) != 64 || list.Message != "" {
		t.Fatalf("%d entries, %q", len(list.Entries), list.Message)
	}
	if sub := d.Handle(ctx, ipc.Request{Verb: "status"}).Status.Subscriptions[0]; sub.Error != "" {
		t.Fatalf("a served list carries no refusal: %+v", sub)
	}
}

// A list whose host refuses the device at the first fetch is still the list
// the person asked for: it is kept, so that the daemon asks again by its
// interval, and the answer says why there is nothing to connect.
func TestAddingARefusedListKeepsItAndSaysWhy(t *testing.T) {
	w, d, dir := newWorld(t)
	ctx := context.Background()
	w.refusal = &links.ErrDeviceRefused{Kind: links.RefusalLimit, Announce: limitSentence}
	resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	if !resp.OK || !strings.Contains(resp.Message, "device refused: "+limitSentence) {
		t.Fatalf("%+v", resp)
	}
	cfg, err := store.Load(filepath.Join(dir, "config.yaml"))
	if err != nil || len(cfg.Subscriptions) != 1 || cfg.Subscriptions[0].URL != listURL {
		t.Fatalf("the list is added: %+v %v", cfg, err)
	}
	if sub := d.Handle(ctx, ipc.Request{Verb: "status"}).Status.Subscriptions[0]; sub.Error != "device refused: "+limitSentence {
		t.Fatalf("%+v", sub)
	}
	// connecting to it says what a list with no lines says
	if resp = d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"}); resp.OK {
		t.Fatalf("nothing to connect: %+v", resp)
	}
	w.mu.Lock()
	w.refusal = nil
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	if n := len(d.Handle(ctx, ipc.Request{Verb: "list"}).Entries); n != 64 {
		t.Fatalf("%d entries once the provider made room", n)
	}
}

// A host that sends no sentence is still understood.
func TestARefusalWithoutAnAnnounceUsesTheBuiltInSentence(t *testing.T) {
	for kind, want := range map[links.Refusal]string{
		links.RefusalLimit:    "device refused: This list is at its device limit. Ask your provider.",
		links.RefusalNoDevice: "device refused: The provider could not read this device's id. Refresh the list; if it stays so, ask your provider.",
	} {
		w, d, _ := newWorld(t)
		w.refusal = &links.ErrDeviceRefused{Kind: kind}
		ctx := context.Background()
		if resp := d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL}); !resp.OK || !strings.Contains(resp.Message, want) {
			t.Fatalf("%s: %+v", kind, resp)
		}
		if sub := d.Handle(ctx, ipc.Request{Verb: "status"}).Status.Subscriptions[0]; sub.Error != want {
			t.Fatalf("%s: %+v", kind, sub)
		}
	}
}

// A refusal empties the list, and only the list: the tunnel a person is using
// stays up through it (the app's aRefusalKeepsTheRunningTunnel).
func TestARefusalLeavesTheRunningLineUp(t *testing.T) {
	w, d, _ := newWorld(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	d.Handle(ctx, ipc.Request{Verb: "add", Source: listURL})
	d.Handle(ctx, ipc.Request{Verb: "connect", Selector: "DE", Mode: "proxy"})
	waitState(t, d, "up")

	w.mu.Lock()
	w.refusal = &links.ErrDeviceRefused{Kind: links.RefusalLimit, Announce: limitSentence}
	w.mu.Unlock()
	d.Handle(ctx, ipc.Request{Verb: "refresh"})
	time.Sleep(100 * time.Millisecond)

	st := d.Handle(ctx, ipc.Request{Verb: "status"}).Status
	if st.State != "up" || st.Line == nil || st.Line.Country != "DE" {
		t.Fatalf("the tunnel stays: %+v", st)
	}
	if ev := w.events(); strings.Contains(ev, "engine-stop") {
		t.Fatalf("a refusal stopped the engine: %s", ev)
	}
	if st.Subscriptions[0].Error != "device refused: "+limitSentence {
		t.Fatalf("%+v", st.Subscriptions[0])
	}
}
