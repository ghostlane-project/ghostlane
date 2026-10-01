package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/crypt1"
	"github.com/ghostlane-project/ghostlane/cli/internal/daemon"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/routes"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

func runDaemon(args []string, stderr io.Writer) int {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	fs.SetOutput(stderr)
	socket := fs.String("socket", ipc.DefaultSocketPath, "control socket path")
	stateDir := fs.String("state-dir", defaultStateDir(os.Getenv), "state directory (config.yaml lives here; GHOSTLANE_STATE_DIR)")
	subscription := fs.String("subscription", "", "add this list at start (containers)")
	selector := fs.String("connect", "", "connect this selector at start (containers)")
	mode := fs.String("mode", "proxy", "tun | proxy, with --connect")
	killSwitch := fs.Bool("kill-switch", false, "with --mode tun: refuse traffic outside the tunnel while no line is up")
	probeURL := fs.String("probe-url", "http://cp.cloudflare.com/generate_204", "liveness probe through the tunnel")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *mode != "tun" && *mode != "proxy" {
		fmt.Fprintf(stderr, "--mode must be tun or proxy, not %q\n", *mode)
		return 2
	}
	if *killSwitch && *mode != "tun" {
		fmt.Fprintln(stderr, "--kill-switch needs --mode tun")
		return 2
	}
	logger := log.New(stderr, "", log.LstdFlags)
	logf := func(format string, a ...any) { logger.Printf("%s", daemon.Scrub(fmt.Sprintf(format, a...))) }
	olcrtc.SetLogSink(func(s string) { logf("engine: %s", s) })

	// containers: the list, the selection and the proxy come from the
	// arguments and the environment, written into the config before the start
	cfgPath := filepath.Join(*stateDir, "config.yaml")
	cfg, err := store.Load(cfgPath)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	changed, err := applyProxyEnv(cfg, os.Getenv)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 2
	}
	if *subscription != "" {
		found := false
		for _, s := range cfg.Subscriptions {
			found = found || s.URL == *subscription
		}
		if !found {
			cfg.Subscriptions = append(cfg.Subscriptions, store.Subscription{URL: *subscription, AddedAt: time.Now(), IntervalHours: 24})
		}
		if *selector != "" {
			cfg.Selection = &store.Selection{Subscription: *subscription, Selector: *selector, Mode: *mode, KillSwitch: *killSwitch}
		}
		changed = true
	}
	if changed {
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
			return olcrtc.Start(ctx, p)
		},
		StartXray: func(ctx context.Context, p xray.Params) (daemon.Engine, error) {
			return xray.Start(ctx, p)
		},
		StartFront: func(ctx context.Context, p singbox.FrontParams) (daemon.Front, error) {
			return singbox.Start(ctx, p)
		},
		Routes:       routes.New(singbox.TunName),
		Probe:        daemon.HTTPProbe(*probeURL),
		Logf:         logf,
		UID:          os.Getuid(),
		ReadyTimeout: 60 * time.Second, ConfirmTimeout: 45 * time.Second, ProbeInterval: 30 * time.Second,
		ProbeFailures: 3, RetryMin: 10 * time.Second, RetryMax: 5 * time.Minute,
		Version: versionInfo(),
		Decrypt: decryptor(),
	})
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	logf("ghostlane %s starting (uid %d, state %s)", version, os.Getuid(), *stateDir)
	if err := d.Run(ctx); err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return 0
}

func versionInfo() ipc.VersionInfo {
	return ipc.VersionInfo{Version: version, Engine: enginePin, SingBox: singboxPin, Xray: xrayPin, Crypt1: decryptor() != nil}
}

// decryptor is the crypt1 decryptor of this build, nil without a key.
func decryptor() links.Decryptor {
	master, ok := crypt1.ParseKey(cryptKeyV1)
	if !ok {
		return nil
	}
	return func(blob string) ([]byte, bool) { return crypt1.Decrypt(master, blob) }
}

// applyProxyEnv applies GHOSTLANE_PROXY_LISTEN/_PORT/_USER/_PASS to the
// config (containers set the proxy this way). A listen that is not loopback
// needs credentials, or the proxy would be open to whoever reaches the port.
func applyProxyEnv(cfg *store.Config, getenv func(string) string) (bool, error) {
	listen, port, user, pass := getenv("GHOSTLANE_PROXY_LISTEN"), getenv("GHOSTLANE_PROXY_PORT"), getenv("GHOSTLANE_PROXY_USER"), getenv("GHOSTLANE_PROXY_PASS")
	if listen == "" && port == "" && user == "" && pass == "" {
		return false, nil
	}
	if listen != "" {
		if net.ParseIP(listen) == nil {
			return false, fmt.Errorf("GHOSTLANE_PROXY_LISTEN must be an IP address (127.0.0.1, ::1 or 0.0.0.0), not %q", listen)
		}
		cfg.Proxy.Listen = listen
	}
	if port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			return false, fmt.Errorf("GHOSTLANE_PROXY_PORT must be a port number, not %q", port)
		}
		cfg.Proxy.Port = n
	}
	if user != "" || pass != "" {
		cfg.Proxy.User, cfg.Proxy.Pass = user, pass
	}
	if ip := net.ParseIP(cfg.Proxy.Listen); ip != nil && !ip.IsLoopback() && (cfg.Proxy.User == "" || cfg.Proxy.Pass == "") {
		return false, fmt.Errorf("a proxy on %s is reachable from the network: set GHOSTLANE_PROXY_USER and GHOSTLANE_PROXY_PASS", cfg.Proxy.Listen)
	}
	return true, nil
}

// defaultStateDir is GHOSTLANE_STATE_DIR when set (the Docker image sets it to
// /data, the volume), else the package's directory.
func defaultStateDir(getenv func(string) string) string {
	if d := getenv("GHOSTLANE_STATE_DIR"); d != "" {
		return d
	}
	return "/var/lib/ghostlane"
}
