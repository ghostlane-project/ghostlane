//go:build !race

// The end-to-end trips the race detector inside Xray-core's own XHTTP client
// (transport/internet/splithttp/client.go, a write at :195 against a read at
// :205), which is upstream's to fix; the test still runs in every non-race run.

package xray

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

// The Reality handshake mirrors a real TLS site; without a way to reach it the
// server cannot answer, so the test skips offline. The server side runs as a
// separate xray process (XRAY_SERVER_BIN, or `xray` on PATH; `make xray-server`
// builds one from the pinned module): two Xray instances in one process share
// globals and cannot talk to each other over XHTTP.
const realityDest = "yandex.ru"

func serverBinary(t *testing.T) string {
	t.Helper()
	if b := os.Getenv("XRAY_SERVER_BIN"); b != "" {
		return b
	}
	if b, err := exec.LookPath("xray"); err == nil {
		return b
	}
	t.Skip("set XRAY_SERVER_BIN (make xray-server) for the Reality end-to-end test")
	return ""
}

// TestRealityXhttpEndToEnd runs an Xray server (vless + xhttp + Reality) beside
// our client engine and fetches a local page through both.
func TestRealityXhttpEndToEnd(t *testing.T) {
	bin := serverBinary(t)
	if c, err := net.DialTimeout("tcp", realityDest+":443", 3*time.Second); err != nil {
		t.Skipf("no route to %s: %v", realityDest, err)
	} else {
		c.Close()
	}
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privB64 := base64.RawURLEncoding.EncodeToString(priv.Bytes())
	pubB64 := base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes())
	uuid := "9f4b2c3a-1111-4222-8333-444455556666"
	serverPort, _ := olcrtc.FreePort()

	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "through xhttp+reality") }))
	defer target.Close()

	serverCfg, _ := json.Marshal(map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "vless",
			"settings": map[string]any{"clients": []map[string]any{{"id": uuid}}, "decryption": "none"},
			"streamSettings": map[string]any{
				"network": "xhttp", "security": "reality",
				"realitySettings": map[string]any{"show": false, "dest": realityDest + ":443", "xver": 0, "serverNames": []string{realityDest},
					"privateKey": privB64, "shortIds": []string{"0123"}},
				"xhttpSettings": map[string]any{"path": "/e2e"},
			},
		}},
		// Xray 26 blackholes private targets behind a vless inbound by default; the
		// test's target is on loopback.
		"outbounds": []map[string]any{{"tag": "direct", "protocol": "freedom",
			"settings": map[string]any{"finalRules": []map[string]any{{"action": "allow", "ip": []string{"127.0.0.0/8"}}}}}},
	})
	cfgPath := filepath.Join(t.TempDir(), "server.json")
	if err := os.WriteFile(cfgPath, serverCfg, 0o600); err != nil {
		t.Fatal(err)
	}
	server := exec.Command(bin, "run", "-c", cfgPath)
	serverLog := &syncBuffer{}
	server.Stdout, server.Stderr = serverLog, serverLog
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = server.Process.Kill(); _ = server.Wait() }()
	deadline := time.Now().Add(10 * time.Second)
	for {
		if c, err := net.DialTimeout("tcp", "127.0.0.1:"+strconv.Itoa(serverPort), 200*time.Millisecond); err == nil {
			c.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the xray server did not listen:\n%s", serverLog.String())
		}
		time.Sleep(100 * time.Millisecond)
	}

	socksPort, _ := olcrtc.FreePort()
	line := links.VlessLine{UUID: uuid, Host: "127.0.0.1", Port: serverPort, SNI: realityDest, PublicKey: pubB64, ShortID: "0123", Fingerprint: "chrome",
		Transport: links.Transport{Kind: "xhttp", Path: "/e2e", Host: realityDest, Mode: "packet-up"}}
	client, err := Start(context.Background(), Params{Line: line, SocksHost: "127.0.0.1", SocksPort: socksPort, SocksUser: "u", SocksPass: "p"})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = client.Stop(time.Second) }()

	d, _ := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(socksPort), &proxy.Auth{User: "u", Password: "p"}, proxy.Direct)
	cd, _ := d.(proxy.ContextDialer)
	hc := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 20 * time.Second}
	resp, err := hc.Get(target.URL + "/hello")
	if err != nil {
		t.Fatalf("through the client, the server and back: %v\nserver log:\n%s", err, serverLog.String())
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || string(body) != "through xhttp+reality" {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
}

// syncBuffer takes the server's stdout and stderr from two copier goroutines.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
