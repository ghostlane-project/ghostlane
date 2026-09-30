package olcrtc

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

type fakeRuntime struct {
	mu        sync.Mutex
	calls     []string
	startErr  error
	readyErr  error
	state     string
	port      int
	user, pwd string
	readyGate chan struct{} // when set, WaitReady blocks until Stop
}

func (f *fakeRuntime) rec(s string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, s)
}

func (f *fakeRuntime) joined() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return strings.Join(f.calls, " ")
}

func (f *fakeRuntime) last() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[len(f.calls)-1]
}
func (f *fakeRuntime) SetProvider(p string) error        { f.rec("provider=" + p); return nil }
func (f *fakeRuntime) SetTransport(t string) error       { f.rec("transport=" + t); return nil }
func (f *fakeRuntime) SetRoom(r string) error            { f.rec("room=" + r); return nil }
func (f *fakeRuntime) SetKey(string) error               { f.rec("key"); return nil }
func (f *fakeRuntime) SetDNS(d string) error             { f.rec("dns=" + d); return nil }
func (f *fakeRuntime) SetSocksListenHost(h string) error { f.rec("host=" + h); return nil }
func (f *fakeRuntime) SetSocksPort(p int) error          { f.port = p; f.rec("port"); return nil }
func (f *fakeRuntime) SetSocksCredentials(u, p string) error {
	f.user, f.pwd = u, p
	f.rec("creds")
	return nil
}
func (f *fakeRuntime) SetUDP(bool)                  { f.rec("udp") }
func (f *fakeRuntime) SetDirectRules(string) error  { f.rec("direct"); return nil }
func (f *fakeRuntime) SetDeviceIDPath(p string)     { f.rec("deviceid=" + p) }
func (f *fakeRuntime) SetVP8Options(int, int) error { f.rec("vp8"); return nil }
func (f *fakeRuntime) Start() error                 { f.rec("start"); f.state = "running"; return f.startErr }
func (f *fakeRuntime) WaitReady(int) error {
	f.rec("ready")
	if f.readyGate != nil {
		<-f.readyGate
		return errors.New("stopped while waiting")
	}
	return f.readyErr
}
func (f *fakeRuntime) Stop(int) error {
	f.rec("stop")
	f.state = "stopped"
	if f.readyGate != nil {
		close(f.readyGate)
	}
	return nil
}
func (f *fakeRuntime) State() string { return f.state }

func params() Params {
	return Params{
		Line:      links.OlcrtcLine{Provider: "wbstream", Transport: "vp8channel", Room: "room_x", Key: strings.Repeat("ab", 32), VP8FPS: 25, VP8Batch: 6},
		SocksHost: "127.0.0.1", SocksPort: 12345, SocksUser: "u", SocksPass: "p",
		DNS: "1.1.1.1:53", DirectRules: PrivateDirectRules, DeviceIDPath: "/tmp/x/device-id",
		ReadyTimeout: time.Second,
	}
}

func TestStartAppliesEverythingInOrder(t *testing.T) {
	f := &fakeRuntime{}
	NewRuntime = func() Runtime { return f }
	s, err := Start(context.Background(), params())
	if err != nil {
		t.Fatal(err)
	}
	want := "provider=wbstream transport=vp8channel room=room_x key vp8 dns=1.1.1.1:53 host=127.0.0.1 port creds udp direct deviceid=/tmp/x/device-id start ready"
	if got := f.joined(); got != want {
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
	if _, err := Start(context.Background(), params()); err == nil || !strings.Contains(err.Error(), "no room") {
		t.Fatalf("%v", err)
	}
	if f.last() != "stop" {
		t.Fatalf("a runtime that is not ready is stopped: %v", f.joined())
	}
}

func TestNoVP8OptionsWhenDefault(t *testing.T) {
	f := &fakeRuntime{}
	NewRuntime = func() Runtime { return f }
	p := params()
	p.Line.VP8FPS, p.Line.VP8Batch = 0, 0
	if _, err := Start(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(" "+f.joined()+" ", " vp8 ") {
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

// Reviewer finding 6: a cancelled connect must not wait out the engine's ready
// timeout; the runtime is stopped and Start returns at once.
func TestStartHonoursCancel(t *testing.T) {
	f := &fakeRuntime{readyGate: make(chan struct{})}
	NewRuntime = func() Runtime { return f }
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(20 * time.Millisecond); cancel() }()
	p := params()
	p.ReadyTimeout = 30 * time.Second
	start := time.Now()
	_, err := Start(ctx, p)
	if err == nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("want a cancel error, got %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("Start waited out the ready timeout")
	}
	if f.last() != "stop" {
		t.Fatalf("the runtime is stopped on cancel: %v", f.joined())
	}
}
