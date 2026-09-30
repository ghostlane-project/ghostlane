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
		Version: ipc.VersionInfo{Version: version, Engine: enginePin, SingBox: singboxPin, Xray: xrayPin},
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
