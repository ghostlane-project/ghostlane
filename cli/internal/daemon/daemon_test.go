package daemon

import (
	"context"
	"errors"
	"net/netip"
	"net/url"
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
	log       []string         // "engine:<carrier>@<room>", "engine-stop", "front:<mode>", "front-close", "routes:sync", "routes:clear", "routes:cleanup"
	engineErr map[string]error // carrier → error at start
	probeErr  error
	probes    int
	stopDelay time.Duration // how long a fake engine takes to stop
	engState  string        // "" = running
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
	e.w.rec("engine-stop")
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
			w.rec("front:" + string(p.Mode) + ":" + p.UpstreamUser)
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
	if ev := w.events(); !strings.HasPrefix(ev, "routes:cleanup engine:telemost@") || !strings.Contains(ev+" ", "front:proxy:u ") {
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
	lg, _ := store.LoadLastGood(dir)
	if lg[listURL+"|DE"] != st.Line.ID {
		t.Fatalf("last good %v", lg)
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
	if opens, closes := count(ev, "front:tun:u"), count(ev, "front-close"); opens != closes {
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
	d.startConnect(*cfg.Selection)
	st := waitState(t, d, "failed")
	all := st.LastError
	for _, s := range st.Subscriptions {
		all += " " + s.Error + " " + s.URL
	}
	if strings.Contains(all, "SECRETTOKEN") {
		t.Fatalf("token in status: %+v", st)
	}
}
