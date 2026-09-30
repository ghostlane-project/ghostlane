//go:build !race

// These end-to-ends run an xray process as the server (XRAY_SERVER_BIN, built
// by `make xray-server` from the pinned module — what ProofKit origins run)
// and our sing-box front as the client: the vless-reality and hysteria2
// outbound shapes are proven against a real server, not only parsed.
package singbox

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
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

const (
	realityDest = "yandex.ru"
	e2eUUID     = "9f4b2c3a-1111-4222-8333-444455556666"
)

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

func xrayServer(t *testing.T, cfg map[string]any, port int) *syncBuffer {
	t.Helper()
	bin := os.Getenv("XRAY_SERVER_BIN")
	if bin == "" {
		if b, err := exec.LookPath("xray"); err == nil {
			bin = b
		} else {
			t.Skip("set XRAY_SERVER_BIN (make xray-server) for the server end-to-ends")
		}
	}
	raw, _ := json.Marshal(cfg)
	path := filepath.Join(t.TempDir(), "server.json")
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(bin, "run", "-c", path)
	logs := &syncBuffer{}
	cmd.Stdout, cmd.Stderr = logs, logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	deadline := time.Now().Add(10 * time.Second)
	for {
		if c, err := net.DialTimeout("tcp", "127.0.0.1:"+strconv.Itoa(port), 200*time.Millisecond); err == nil {
			c.Close()
			return logs
		}
		if u, err := net.DialTimeout("udp", "127.0.0.1:"+strconv.Itoa(port), 200*time.Millisecond); err == nil && cfg["_udp"] == true {
			u.Close()
			time.Sleep(300 * time.Millisecond)
			return logs
		}
		if time.Now().After(deadline) {
			t.Fatalf("the xray server did not listen:\n%s", logs.String())
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// freedomAllowingLoopback: Xray 26 blackholes private targets behind a
// vless/hysteria inbound by default; the tests' target is on loopback.
func freedomAllowingLoopback() map[string]any {
	return map[string]any{"tag": "direct", "protocol": "freedom",
		"settings": map[string]any{"finalRules": []map[string]any{{"action": "allow", "ip": []string{"127.0.0.0/8"}}}}}
}

func fetchThroughFront(t *testing.T, p FrontParams, url string) string {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f, err := Start(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	d, _ := proxy.SOCKS5("tcp", p.ProxyListen+":"+strconv.Itoa(p.ProxyPort), nil, proxy.Direct)
	cd, _ := d.(proxy.ContextDialer)
	hc := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 20 * time.Second}
	resp, err := hc.Get(url)
	if err != nil {
		t.Fatalf("through the front and the server: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return string(body)
}

func TestRealityTCPEndToEnd(t *testing.T) {
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
	serverPort, _ := olcrtc.FreePort()
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "through reality tcp") }))
	defer target.Close()
	logs := xrayServer(t, map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "vless",
			"settings": map[string]any{"clients": []map[string]any{{"id": e2eUUID, "flow": "xtls-rprx-vision"}}, "decryption": "none"},
			"streamSettings": map[string]any{"network": "tcp", "security": "reality",
				"realitySettings": map[string]any{"dest": realityDest + ":443", "serverNames": []string{realityDest}, "privateKey": privB64, "shortIds": []string{"0123"}}},
		}},
		"outbounds": []map[string]any{freedomAllowingLoopback()},
	}, serverPort)
	port, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.ProxyPort, p.ProbeListen = port, ""
	p.Upstream = Upstream{Vless: &links.VlessLine{UUID: e2eUUID, Host: "127.0.0.1", Port: serverPort, SNI: realityDest, PublicKey: pubB64, ShortID: "0123",
		Fingerprint: "chrome", Flow: "xtls-rprx-vision", Transport: links.Transport{Kind: "tcp"}}}
	if got := fetchThroughFront(t, p, target.URL+"/"); got != "through reality tcp" {
		t.Fatalf("%q\nserver log:\n%s", got, logs.String())
	}
}

func selfSignedCert(t *testing.T) (certPEM, keyPEM string, sha string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "hy2.example"}, DNSNames: []string{"hy2.example"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	kb, _ := x509.MarshalECPrivateKey(key)
	sum := sha256.Sum256(der)
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb})), hex.EncodeToString(sum[:])
}

func TestHysteria2EndToEnd(t *testing.T) {
	certPEM, keyPEM, pin := selfSignedCert(t)
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "cert.pem"), []byte(certPEM), 0o600)
	_ = os.WriteFile(filepath.Join(dir, "key.pem"), []byte(keyPEM), 0o600)
	serverPort, _ := olcrtc.FreePort()
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "through hysteria2") }))
	defer target.Close()
	logs := xrayServer(t, map[string]any{
		"_udp": true,
		"log":  map[string]any{"loglevel": "warning"},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "hysteria",
			"settings": map[string]any{"version": 2, "auth": "e2e-password"},
			"streamSettings": map[string]any{"network": "hysteria", "security": "tls",
				"hysteriaSettings": map[string]any{"version": 2, "auth": "e2e-password", "obfs": map[string]any{"type": "salamander", "password": "salt"}},
				"tlsSettings":      map[string]any{"certificates": []map[string]any{{"certificateFile": filepath.Join(dir, "cert.pem"), "keyFile": filepath.Join(dir, "key.pem")}}}},
		}},
		"outbounds": []map[string]any{freedomAllowingLoopback()},
	}, serverPort)
	port, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.ProxyPort, p.ProbeListen = port, ""
	p.Upstream = Upstream{Hy2: &links.Hy2Line{Password: "e2e-password", Host: "127.0.0.1", Port: serverPort, SNI: "hy2.example", ObfsPassword: "salt", PinSHA256: pin}}
	if got := fetchThroughFront(t, p, target.URL+"/"); got != "through hysteria2" {
		t.Fatalf("%q\nserver log:\n%s", got, logs.String())
	}
}
