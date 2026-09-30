package xray

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

const testKey = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"

func xhttpLine() links.VlessLine {
	return links.VlessLine{UUID: "00000000-0000-4000-8000-000000000000", Host: "203.0.113.9", Port: 8444, SNI: "yandex.ru",
		PublicKey: testKey, ShortID: "0123", Fingerprint: "chrome",
		Transport: links.Transport{Kind: "xhttp", Path: "/pk", Host: "yandex.ru", Mode: "packet-up"}}
}

func params(l links.VlessLine) Params {
	return Params{Line: l, SocksHost: "127.0.0.1", SocksPort: 10850, SocksUser: "u", SocksPass: "p"}
}

func accepted(t *testing.T, cfg []byte) {
	t.Helper()
	pb, err := serial.LoadJSONConfig(bytes.NewReader(cfg))
	if err != nil {
		t.Fatalf("xray refused the config: %v\n%s", err, cfg)
	}
	inst, err := core.New(pb)
	if err != nil {
		t.Fatalf("xray core.New: %v\n%s", err, cfg)
	}
	_ = inst.Close()
}

func TestBuildConfigReality(t *testing.T) {
	cfg, err := BuildConfig(params(xhttpLine()))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(cfg, &m); err != nil {
		t.Fatal(err)
	}
	in := m["inbounds"].([]any)[0].(map[string]any)
	if in["protocol"] != "socks" || in["listen"] != "127.0.0.1" || in["port"] != float64(10850) {
		t.Fatalf("%v", in)
	}
	set := in["settings"].(map[string]any)
	if set["auth"] != "password" || set["udp"] != true || set["accounts"].([]any)[0].(map[string]any)["user"] != "u" {
		t.Fatalf("%v", set)
	}
	out := m["outbounds"].([]any)[0].(map[string]any)
	if out["protocol"] != "vless" {
		t.Fatalf("%v", out)
	}
	s := string(cfg)
	for _, want := range []string{`"network":"xhttp"`, `"security":"reality"`, `"serverName":"yandex.ru"`, `"fingerprint":"chrome"`,
		`"publicKey":"` + testKey + `"`, `"shortId":"0123"`, `"path":"/pk"`, `"host":"yandex.ru"`, `"mode":"packet-up"`, `"scMaxEachPostBytes":200000`,
		`"address":"203.0.113.9"`, `"port":8444`, `"encryption":"none"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("config lacks %s:\n%s", want, s)
		}
	}
	accepted(t, cfg)
}

func TestBuildConfigTLS(t *testing.T) {
	// Review Focus 2: no pbk → plain TLS, host from the link, allowInsecure false
	l := xhttpLine()
	l.PublicKey, l.ShortID, l.Host, l.Port = "", "", "cdn.example", 443
	l.SNI, l.Transport = "cdn.example", links.Transport{Kind: "xhttp", Path: "/xh-de/", Host: "cdn.example", Mode: "stream-one"}
	cfg, err := BuildConfig(params(l))
	if err != nil {
		t.Fatal(err)
	}
	s := string(cfg)
	for _, want := range []string{`"security":"tls"`, `"tlsSettings":{"allowInsecure":false,"fingerprint":"chrome","serverName":"cdn.example"}`, `"mode":"stream-one"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("config lacks %s:\n%s", want, s)
		}
	}
	if strings.Contains(s, "reality") {
		t.Fatal("no reality without a public key")
	}
	accepted(t, cfg)
	l.Transport.Kind = "tcp"
	if _, err := BuildConfig(params(l)); err == nil {
		t.Fatal("a tcp line is sing-box's")
	}
}

func TestStartStopLoopbackSocks(t *testing.T) {
	port, _ := olcrtc.FreePort()
	p := params(xhttpLine())
	p.SocksPort = port
	p.Line.Host, p.Line.Port = "127.0.0.1", 1 // nothing listens there; only the SOCKS side is exercised
	s, err := Start(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	if s.State() != "running" || s.SocksAddr() != net.JoinHostPort("127.0.0.1", itoa(port)) {
		t.Fatalf("%s %s", s.State(), s.SocksAddr())
	}
	c, err := net.DialTimeout("tcp", s.SocksAddr(), 2*time.Second)
	if err != nil {
		t.Fatalf("socks listener: %v", err)
	}
	_, _ = c.Write([]byte{5, 1, 2}) // version 5, one method: user/pass
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 2)
	if _, err := c.Read(buf); err != nil || buf[0] != 5 || buf[1] != 2 {
		t.Fatalf("socks handshake: %v %v", buf, err)
	}
	c.Close()
	if err := s.Stop(time.Second); err != nil || s.State() != "stopped" {
		t.Fatalf("%v %s", err, s.State())
	}
	if _, err := net.DialTimeout("tcp", s.SocksAddr(), 500*time.Millisecond); err == nil {
		t.Fatal("the listener is closed after Stop")
	}
}

func itoa(i int) string { return strconv.Itoa(i) }

// Reviewer finding 2: a start that fails half-way (the SOCKS port is taken)
// closes what it started; the next attempt on a free port works.
func TestStartFailsCleanlyOnTakenPort(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	p := params(xhttpLine())
	p.SocksPort = l.Addr().(*net.TCPAddr).Port
	if _, err := Start(context.Background(), p); err == nil {
		t.Fatal("a taken port must fail the start")
	}
	free, _ := olcrtc.FreePort()
	p.SocksPort = free
	s, err := Start(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	_ = s.Stop(time.Second)
}
