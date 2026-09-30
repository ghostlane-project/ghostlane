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
