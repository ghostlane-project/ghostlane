// Package daemon is `ghostlane run`: it owns the engine, the front and the
// policy rules, keeps the config and list cache, and answers the control socket.
package daemon

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
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
	// InstallKillSwitch puts the kill switch in for a daemon running as uid
	// (idempotent); RemoveKillSwitch takes it out.
	InstallKillSwitch(uid int) error
	RemoveKillSwitch() error
	// WatchAddresses runs onChange on address changes until ctx ends or the
	// returned stop is called; stop waits for a callback in flight.
	WatchAddresses(ctx context.Context, onChange func()) (stop func(), err error)
}

type Deps struct {
	ConfigPath  string
	StateDir    string
	SocketPath  string
	Fetch       func(ctx context.Context, url string, dev links.Device) ([]byte, links.Headers, error)
	Device      links.Device    // what a list fetch says about this machine; the id is the daemon's own (deviceID)
	Decrypt     links.Decryptor // crypt1 lists and links; nil = this build has no key
	StartEngine func(ctx context.Context, p olcrtc.Params) (Engine, error)
	StartXray   func(ctx context.Context, p xray.Params) (Engine, error)
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
	entry     links.Entry
	mode      string
	engine    Engine // nil for a native line (the front is the engine)
	front     Front
	probeAddr string // the front's probe inbound, what supervise probes through
	probeUser string
	probePass string
	ownRules  bool // this line put the own-address rules in (not the kill switch)
}

type Daemon struct {
	deps Deps

	// connectMu serialises startConnect/stopConnect: Handle runs concurrently
	// (one goroutine per control request) and a refresh reconnects too, and
	// two of them interleaving left an engine nobody could stop.
	connectMu sync.Mutex
	// saveMu serialises the file writes (they share a .tmp path each).
	saveMu sync.Mutex

	mu       sync.Mutex
	cfg      *store.Config
	lastGood map[string]string
	subErr   map[string]string
	state    string
	lastErr  string
	since    time.Time
	sel      *store.Selection
	cur      *live
	pending  *links.Entry // the line being tried while connecting
	cancel   context.CancelFunc
	loopDone chan struct{}
	loopLive bool // a connect loop is running (it may be in backoff)
	runCtx   context.Context
	ksOn     bool   // the kill switch is in; the own-address rules belong to it, not to the line
	ksStop   func() // its address watcher

	// devMu guards devID, this machine's id once it is had (deviceID), and
	// devErr, the last reason it could not be had, which is logged once.
	devMu  sync.Mutex
	devID  string
	devErr string
}

// indexed is one entry with the subscription it came from; the daemon numbers
// entries continuously across subscriptions.
type indexed struct {
	sub   string
	entry links.Entry
}

// allEntries returns every subscription's entries in config order, or only
// those of the subscriptions matching only.
func (d *Daemon) allEntries(ctx context.Context, only string) ([]indexed, error) {
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	var out []indexed
	var lastErr error
	for _, s := range subs {
		if only != "" && !matchesSub(s.URL, only) {
			continue
		}
		entries, err := d.entriesFor(ctx, s.URL, false)
		if err != nil {
			lastErr = err
			d.logf("list %s: %v", store.MaskURL(s.URL), err)
			continue
		}
		for _, e := range entries {
			out = append(out, indexed{sub: s.URL, entry: e})
		}
	}
	if len(out) == 0 && lastErr != nil {
		return nil, lastErr
	}
	return out, nil
}

func flatten(in []indexed) []links.Entry {
	out := make([]links.Entry, 0, len(in))
	for _, i := range in {
		out = append(out, i.entry)
	}
	return out
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
	return &Daemon{deps: d, state: "idle", subErr: map[string]string{}}
}

// ensureConfig loads the config and the last-good map once, whichever of Run
// or Handle comes first; a missing file is the defaults.
func (d *Daemon) ensureConfig() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.cfg != nil {
		return nil
	}
	cfg, err := store.Load(d.deps.ConfigPath)
	if err != nil {
		return err
	}
	lg, err := store.LoadLastGood(d.deps.StateDir)
	if err != nil {
		d.deps.Logf("last-good: %v", err)
		lg = map[string]string{}
	}
	d.cfg, d.lastGood = cfg, lg
	return nil
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
	if err := d.ensureConfig(); err != nil {
		return fmt.Errorf("config: %w", err)
	}
	d.device() // the id is made now, not at the first fetch: an engine that starts finds the same file

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
	d.startStored()
	<-ctx.Done()
	d.stopAll(false) // a clean stop gives the box back; the selection stays for the next start
	if listener != nil {
		_ = listener.Close()
	}
	return nil
}

func (d *Daemon) deviceIDPath() string { return deviceIDPath(d.deps.StateDir) }

// device is what a list fetch says about this machine: Deps.Device and the
// id of deviceID, kept once it is had. While there is no id (the file can be
// neither read nor made) each fetch names the machine without one and asks
// again at the next, and the log says so once for each reason; the host then
// tells the person, where an id made up here would be a new device at every
// start.
func (d *Daemon) device() links.Device {
	d.devMu.Lock()
	defer d.devMu.Unlock()
	if d.devID == "" {
		id, err := deviceID(d.deps.StateDir)
		switch {
		case err == nil:
			d.devID, d.devErr = id, ""
		case err.Error() != d.devErr:
			d.devErr = err.Error()
			d.logf("device id: %v; lists are fetched without one until it can be read or made", err)
		}
	}
	dev := d.deps.Device
	dev.ID = d.devID
	return dev
}

// deviceIDFile is the file the olcRTC engine keeps its own device id in (the
// engine is handed its path), so it is the one place this machine's id lives.
const deviceIDFile = "device-id"

func deviceIDPath(stateDir string) string { return filepath.Join(stateDir, deviceIDFile) }

// deviceID is the id this machine names itself by to a list host, so that a
// provider's device limit counts it as one device: the content of the state
// directory's device-id, trimmed, the way the engine reads it. With no id in
// the file (absent, or empty) a UUID v4 is written, the line and a newline,
// mode 0600, the way the engine writes it. A file the engine made first is the
// id here, a file made here is the one the engine finds when a line starts,
// and a file that exists is never rewritten.
func deviceID(stateDir string) (string, error) {
	path := deviceIDPath(stateDir)
	data, err := os.ReadFile(path)
	if err == nil {
		if id := strings.TrimSpace(string(data)); id != "" {
			return id, nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", fmt.Errorf("read %s: %w", path, err)
	}
	id, err := newUUID()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return "", fmt.Errorf("make %s: %w", stateDir, err)
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return "", fmt.Errorf("write %s: %w", path, err)
	}
	return id, nil
}

// newUUID is a version 4 UUID in its lower-case 36-character form.
func newUUID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("random: %w", err)
	}
	b[6] = b[6]&0x0f | 0x40 // version 4
	b[8] = b[8]&0x3f | 0x80 // variant 10
	h := hex.EncodeToString(b[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:], nil
}

func (d *Daemon) saveConfig(cfg *store.Config) error {
	d.saveMu.Lock()
	defer d.saveMu.Unlock()
	return store.Save(d.deps.ConfigPath, cfg)
}

func (d *Daemon) saveLastGood(m map[string]string) error {
	d.saveMu.Lock()
	defer d.saveMu.Unlock()
	return store.SaveLastGood(d.deps.StateDir, m)
}

func (d *Daemon) saveCache(subURL string, c *store.Cache) error {
	d.saveMu.Lock()
	defer d.saveMu.Unlock()
	return store.SaveCache(d.deps.StateDir, subURL, c)
}
