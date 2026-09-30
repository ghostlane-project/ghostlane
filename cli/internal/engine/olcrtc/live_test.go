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
	SetLogSink(func(s string) { t.Log("engine: " + s) })
	s, err := Start(context.Background(), Params{Line: *line, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: u, SocksPass: p,
		DNS: HostResolvers("/etc/resolv.conf"), DirectRules: PrivateDirectRules, DeviceIDPath: t.TempDir() + "/device-id", ReadyTimeout: 90 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = s.Stop(10 * time.Second) }()
	d, _ := proxy.SOCKS5("tcp", s.SocksAddr(), &proxy.Auth{User: u, Password: p}, proxy.Direct)
	cd, ok := d.(proxy.ContextDialer)
	if !ok {
		t.Fatal("socks dialer without DialContext")
	}
	c := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 60 * time.Second}
	var resp *http.Response
	for i := 0; i < 10; i++ {
		resp, err = c.Get("http://cp.cloudflare.com/generate_204")
		if err == nil {
			resp.Body.Close()
			break
		}
		t.Logf("attempt %d: %v", i+1, err)
		time.Sleep(3 * time.Second)
	}
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("through the room: %v %v", resp, err)
	}
	t.Logf("204 through %s over %s", line.Label, line.Provider)
}
