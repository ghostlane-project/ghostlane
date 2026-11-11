// These end-to-ends run an xray process as the server and our sing-box front
// as the client: the vless-reality and hysteria2 outbound shapes are proven
// against a real server, not only parsed.
//
// The servers: XRAY_SERVER_BIN, built by `make xray-server` from the pinned
// module, and every official release named in XRAY_SERVERS
// ("<version>=<path> ...", fetched and checked by scripts/fetch-xray-servers.sh):
// the versions servers out there run, which is what a Reality hello has to get
// past.
//
// What is fetched is http://203.0.113.10/, never the page's own loopback
// address: the front sends private destinations straight out (config.go), so
// a test fetching 127.0.0.1 passed whether or not the tunnel opened, and did
// for months. The server's freedom outbound redirects that address to the
// page. Each Reality cell also runs with a wrong public key, which must not
// reach the page: a pass that cannot fail proves nothing.
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
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const (
	// realitySNI is the name the hello asks for; the camouflage target is a
	// local TLS 1.3 server (realityTarget), so nothing leaves the machine.
	realitySNI = "reality.e2e.test"
	e2eUUID    = "9f4b2c3a-1111-4222-8333-444455556666"
	// throughTunnel is a TEST-NET-3 address: public to the front's rules, so
	// the request takes the tunnel; the server redirects it to the page.
	throughTunnel = "http://203.0.113.10/"
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

type xrayBin struct{ name, path string }

// pinnedXray is the xray of the pinned module only: an `xray` on PATH is
// whatever the box runs.
func pinnedXray(t *testing.T) xrayBin {
	t.Helper()
	bin := os.Getenv("XRAY_SERVER_BIN")
	if bin == "" {
		t.Skip("set XRAY_SERVER_BIN (make xray-server) for the server end-to-ends")
	}
	return xrayBin{"pinned", bin}
}

// realityServers: the pinned build and every release in XRAY_SERVERS.
func realityServers(t *testing.T) []xrayBin {
	t.Helper()
	bins := []xrayBin{pinnedXray(t)}
	for _, entry := range strings.Fields(os.Getenv("XRAY_SERVERS")) {
		version, path, ok := strings.Cut(entry, "=")
		if !ok || version == "" || path == "" {
			t.Fatalf("XRAY_SERVERS: %q is not <version>=<path>", entry)
		}
		bins = append(bins, xrayBin{"xray-" + version, path})
	}
	return bins
}

func xrayServer(t *testing.T, bin xrayBin, cfg map[string]any, port int) *syncBuffer {
	t.Helper()
	raw, _ := json.Marshal(cfg)
	path := filepath.Join(t.TempDir(), "server.json")
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(bin.path, "run", "-c", path)
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
			t.Fatalf("the %s xray server did not listen:\n%s", bin.name, logs.String())
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// freedomToPage sends whatever the tunnel asks for to the page. Xray blocks
// private targets behind a vless or hysteria inbound by default, and the page
// is on loopback: `finalRules` allows it from v26.7 on, `ipsBlocked: []` up to
// v26.4 (each version ignores the other's key, with a warning at most).
func freedomToPage(page *httptest.Server) map[string]any {
	return map[string]any{"tag": "direct", "protocol": "freedom",
		"settings": map[string]any{
			"redirect":   strings.TrimPrefix(page.URL, "http://"),
			"ipsBlocked": []string{},
			"finalRules": []map[string]any{{"action": "allow", "ip": []string{"127.0.0.0/8"}}},
		}}
}

func page(t *testing.T, body string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, body) }))
	t.Cleanup(srv.Close)
	return srv
}

// realityTarget is the site a Reality server mirrors, as a real one would
// answer: TLS 1.3 with h2 offered and a certificate larger than the server's
// own. Current REALITY pads its records to the target's, and a target whose
// encrypted extensions are shorter than the server's (no ALPN) fails every
// handshake, which is the target's fault, not the client's.
func realityTarget(t *testing.T) string {
	t.Helper()
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "camouflage") }))
	srv.EnableHTTP2 = true
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return srv.Listener.Addr().String()
}

// fetchThroughFront returns the body, or the error when nothing came back.
func fetchThroughFront(t *testing.T, p FrontParams, url string) (string, error) {
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
	hc := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 15 * time.Second}
	resp, err := hc.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	return string(body), err
}

func realityKey(t *testing.T) (privB64, pubB64 string) {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(priv.Bytes()), base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes())
}

// TestRealityTCPEndToEnd: VLESS + Reality + Vision through the front, against
// each server. chrome is the app's default; firefox's hello in this uTLS has
// no X25519MLKEM768 share, so it is the case the patch sends as Chrome's.
func TestRealityTCPEndToEnd(t *testing.T) {
	const want = "through reality tcp"
	target := realityTarget(t)
	pg := page(t, want)
	for _, server := range realityServers(t) {
		t.Run(server.name, func(t *testing.T) {
			privB64, pubB64 := realityKey(t)
			serverPort, _ := olcrtc.FreePort()
			logs := xrayServer(t, server, map[string]any{
				"log": map[string]any{"loglevel": "warning"},
				"inbounds": []map[string]any{{
					"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "vless",
					"settings": map[string]any{"clients": []map[string]any{{"id": e2eUUID, "flow": "xtls-rprx-vision"}}, "decryption": "none"},
					"streamSettings": map[string]any{"network": "tcp", "security": "reality",
						"realitySettings": map[string]any{"dest": target, "serverNames": []string{realitySNI}, "privateKey": privB64, "shortIds": []string{"0123"}}},
				}},
				"outbounds": []map[string]any{freedomToPage(pg)},
			}, serverPort)
			_, wrongPub := realityKey(t)
			for _, fp := range []string{"chrome", "firefox"} {
				line := func(pub string) FrontParams {
					port, _ := olcrtc.FreePort()
					p := base(ModeProxy)
					p.ProxyPort, p.ProbeListen = port, ""
					p.Upstream = Upstream{Vless: &links.VlessLine{UUID: e2eUUID, Host: "127.0.0.1", Port: serverPort, SNI: realitySNI, PublicKey: pub, ShortID: "0123",
						Fingerprint: fp, Flow: "xtls-rprx-vision", Transport: links.Transport{Kind: "tcp"}}}
					return p
				}
				t.Run(fp, func(t *testing.T) {
					if got, err := fetchThroughFront(t, line(pubB64), throughTunnel); got != want {
						t.Fatalf("%q, %v\nserver log:\n%s", got, err, logs.String())
					}
				})
				t.Run(fp+"-wrong-key", func(t *testing.T) {
					if got, _ := fetchThroughFront(t, line(wrongPub), throughTunnel); got == want {
						t.Fatalf("a wrong public key reached the page: the request did not need the tunnel")
					}
				})
			}
		})
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
	const want = "through hysteria2"
	server := pinnedXray(t)
	certPEM, keyPEM, pin := selfSignedCert(t)
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "cert.pem"), []byte(certPEM), 0o600)
	_ = os.WriteFile(filepath.Join(dir, "key.pem"), []byte(keyPEM), 0o600)
	serverPort, _ := olcrtc.FreePort()
	pg := page(t, want)
	logs := xrayServer(t, server, map[string]any{
		"_udp": true,
		"log":  map[string]any{"loglevel": "warning"},
		"inbounds": []map[string]any{{
			"tag": "in", "listen": "127.0.0.1", "port": serverPort, "protocol": "hysteria",
			"settings": map[string]any{"version": 2, "auth": "e2e-password"},
			// Salamander is a finalmask in Xray 26.7 (transport_finalmask.go);
			// under hysteriaSettings it was ignored, which the old fetch of a
			// loopback page never noticed.
			"streamSettings": map[string]any{"network": "hysteria", "security": "tls",
				"hysteriaSettings": map[string]any{"version": 2, "auth": "e2e-password"},
				"finalmask":        map[string]any{"udp": []map[string]any{{"type": "salamander", "settings": map[string]any{"password": "salt"}}}},
				"tlsSettings":      map[string]any{"certificates": []map[string]any{{"certificateFile": filepath.Join(dir, "cert.pem"), "keyFile": filepath.Join(dir, "key.pem")}}}},
		}},
		"outbounds": []map[string]any{freedomToPage(pg)},
	}, serverPort)
	line := func(password string) FrontParams {
		port, _ := olcrtc.FreePort()
		p := base(ModeProxy)
		p.ProxyPort, p.ProbeListen = port, ""
		p.Upstream = Upstream{Hy2: &links.Hy2Line{Password: password, Host: "127.0.0.1", Port: serverPort, SNI: "hy2.example", ObfsPassword: "salt", PinSHA256: pin}}
		return p
	}
	if got, err := fetchThroughFront(t, line("e2e-password"), throughTunnel); got != want {
		t.Fatalf("%q, %v\nserver log:\n%s", got, err, logs.String())
	}
	if got, _ := fetchThroughFront(t, line("not-the-password"), throughTunnel); got == want {
		t.Fatalf("a wrong password reached the page: the request did not need the tunnel")
	}
}
