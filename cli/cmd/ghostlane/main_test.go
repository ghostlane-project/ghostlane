package main

import (
	"bytes"
	"context"
	"io"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
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
	if code := run(nil, &out, &errb); code != 2 || !strings.Contains(errb.String(), "Usage:") {
		t.Fatalf("%d %s", code, errb.String())
	}
	if code := run([]string{"frobnicate"}, &out, &errb); code != 2 {
		t.Fatalf("%d", code)
	}
	errb.Reset()
	if code := run([]string{"status", "--socket", filepath.Join(t.TempDir(), "none.sock")}, &out, &errb); code != 3 || !strings.Contains(errb.String(), "not running") {
		t.Fatalf("%d %s", code, errb.String())
	}
	out.Reset()
	if code := run([]string{"version"}, &out, &errb); code != 0 || !strings.Contains(out.String(), "ghostlane ") || !strings.Contains(out.String(), "sing-box") {
		t.Fatalf("%d %s", code, out.String())
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

func TestHelpIsUsable(t *testing.T) {
	var out, errb bytes.Buffer
	if code := run([]string{"help"}, &out, &errb); code != 0 {
		t.Fatalf("%d %s", code, errb.String())
	}
	for _, want := range []string{"Quick start", "connect DE --tun", "ghostlane add", "docs/cli.md", "help [command]"} {
		if !strings.Contains(out.String(), want) {
			t.Fatalf("help lacks %q:\n%s", want, out.String())
		}
	}
	for _, cmd := range []string{"add", "list", "connect", "status", "disconnect", "refresh", "remove", "version", "run"} {
		out.Reset()
		if code := run([]string{"help", cmd}, &out, &errb); code != 0 || !strings.Contains(out.String(), "ghostlane "+cmd) {
			t.Fatalf("help %s: %d\n%s", cmd, code, out.String())
		}
		out.Reset()
		if code := run([]string{cmd, "--help"}, &out, &errb); code != 0 || !strings.Contains(out.String(), "ghostlane "+cmd) {
			t.Fatalf("%s --help: %d\n%s", cmd, code, out.String())
		}
	}
	if !strings.Contains(commandHelp["connect"], "--tun") || !strings.Contains(commandHelp["connect"], "Examples:") {
		t.Fatal("connect help explains the modes with examples")
	}
	if !strings.Contains(usage, "Hysteria2") || !strings.Contains(commandHelp["connect"], "Xray-core") {
		t.Fatal("help names the kinds and which core carries them")
	}
	if code := run([]string{"help", "frobnicate"}, &out, &errb); code != 2 {
		t.Fatal("unknown command help fails")
	}
}

// A container sets the proxy through the environment; a listen that is not
// loopback needs credentials, or the proxy is open to the network.
func TestProxyEnv(t *testing.T) {
	cfg := store.Defaults()
	env := map[string]string{}
	get := func(k string) string { return env[k] }
	if changed, err := applyProxyEnv(cfg, get); err != nil || changed {
		t.Fatalf("no env, no change: %v %v", changed, err)
	}
	env["GHOSTLANE_PROXY_LISTEN"] = "0.0.0.0"
	if _, err := applyProxyEnv(cfg, get); err == nil || !strings.Contains(err.Error(), "GHOSTLANE_PROXY_USER") {
		t.Fatalf("0.0.0.0 without credentials: %v", err)
	}
	env["GHOSTLANE_PROXY_USER"], env["GHOSTLANE_PROXY_PASS"], env["GHOSTLANE_PROXY_PORT"] = "u", "p", "1085"
	changed, err := applyProxyEnv(cfg, get)
	if err != nil || !changed || cfg.Proxy.Listen != "0.0.0.0" || cfg.Proxy.Port != 1085 || cfg.Proxy.User != "u" || cfg.Proxy.Pass != "p" {
		t.Fatalf("%+v %v", cfg.Proxy, err)
	}
	env["GHOSTLANE_PROXY_PORT"] = "nope"
	if _, err := applyProxyEnv(cfg, get); err == nil {
		t.Fatal("a bad port is refused")
	}
	env["GHOSTLANE_PROXY_PORT"], env["GHOSTLANE_PROXY_LISTEN"] = "1080", "localhost"
	if _, err := applyProxyEnv(cfg, get); err == nil {
		t.Fatal("listen must be an IP")
	}
}

func TestRunFlagChecks(t *testing.T) {
	var stderr bytes.Buffer
	if code := run([]string{"run", "--state-dir", t.TempDir(), "--connect", "DE", "--mode", "proxy", "--kill-switch"}, io.Discard, &stderr); code != 2 || !strings.Contains(stderr.String(), "--mode tun") {
		t.Fatalf("kill switch needs tun: %d %s", code, stderr.String())
	}
	stderr.Reset()
	if code := run([]string{"run", "--state-dir", t.TempDir(), "--mode", "sideways"}, io.Discard, &stderr); code != 2 {
		t.Fatalf("bad mode: %d %s", code, stderr.String())
	}
}

func TestDefaultStateDir(t *testing.T) {
	if got := defaultStateDir(func(string) string { return "" }); got != "/var/lib/ghostlane" {
		t.Fatal(got)
	}
	if got := defaultStateDir(func(k string) string { return map[string]string{"GHOSTLANE_STATE_DIR": "/data"}[k] }); got != "/data" {
		t.Fatal(got)
	}
}
