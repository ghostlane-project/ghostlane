package singbox

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/ghostlane-project/ghostlane/cli/internal/links"
)

func base(mode Mode) FrontParams {
	return FrontParams{Mode: mode, Upstream: Upstream{Socks: &SocksUpstream{Addr: "127.0.0.1:10808", User: "u", Pass: "p"}},
		ProxyListen: "127.0.0.1", ProxyPort: 1080, ExcludeUID: 977, InterfaceName: TunName, LogLevel: "warn",
		ProbeListen: "127.0.0.1:10900", ProbeUser: "pu", ProbePass: "pp"}
}

func vlessTCP() *links.VlessLine {
	return &links.VlessLine{UUID: "00000000-0000-4000-8000-000000000000", Host: "203.0.113.9", Port: 443, SNI: "yandex.ru",
		PublicKey: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA", ShortID: "0123", Fingerprint: "chrome", Flow: "xtls-rprx-vision", Transport: links.Transport{Kind: "tcp"}}
}

func TestVlessRealityOutbound(t *testing.T) {
	p := base(ModeProxy)
	p.Upstream = Upstream{Vless: vlessTCP()}
	cfg, err := BuildConfig(p)
	if err != nil {
		t.Fatal(err)
	}
	s := string(cfg)
	for _, want := range []string{`"type":"vless"`, `"tag":"tunnel"`, `"server":"203.0.113.9"`, `"server_port":443`,
		`"uuid":"00000000-0000-4000-8000-000000000000"`, `"packet_encoding":"xudp"`, `"flow":"xtls-rprx-vision"`,
		`"utls":{"enabled":true,"fingerprint":"chrome"}`, `"reality":{"enabled":true,"public_key":"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA","short_id":"0123"}`, `"server_name":"yandex.ru"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("vless outbound lacks %s:\n%s", want, s)
		}
	}
	if strings.Contains(s, `"type":"socks"`) {
		t.Fatal("a native upstream has no socks outbound")
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused: %v", err)
	}
	f, err := New(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	// no flow → no flow key; no pbk → no reality object
	v := vlessTCP()
	v.Flow, v.PublicKey = "", ""
	p.Upstream = Upstream{Vless: v}
	cfg, _ = BuildConfig(p)
	if strings.Contains(string(cfg), `"flow"`) || strings.Contains(string(cfg), `"reality"`) {
		t.Fatalf("%s", cfg)
	}
}

func TestVlessXhttpRefused(t *testing.T) {
	v := vlessTCP()
	v.Transport = links.Transport{Kind: "xhttp", Path: "/pk", Host: "yandex.ru", Mode: "packet-up"}
	p := base(ModeProxy)
	p.Upstream = Upstream{Vless: v}
	if _, err := BuildConfig(p); err == nil {
		t.Fatal("xhttp is Xray's, sing-box must refuse it")
	}
}

func TestHysteria2Outbound(t *testing.T) {
	// Review Focus 3: a pin means insecure, obfs only when given
	h := &links.Hy2Line{Password: "pw", Host: "203.0.113.9", Port: 38443, SNI: "s.example", PinSHA256: "abcd"}
	p := base(ModeTun)
	p.Upstream = Upstream{Hy2: h}
	cfg, err := BuildConfig(p)
	if err != nil {
		t.Fatal(err)
	}
	s := string(cfg)
	for _, want := range []string{`"type":"hysteria2"`, `"password":"pw"`, `"server_port":38443`, `"server_name":"s.example"`, `"insecure":true`} {
		if !strings.Contains(s, want) {
			t.Fatalf("hysteria2 outbound lacks %s:\n%s", want, s)
		}
	}
	if strings.Contains(s, `"obfs"`) {
		t.Fatal("no obfs without a password")
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused: %v", err)
	}
	f, err := New(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	h.PinSHA256, h.ObfsPassword, h.Insecure = "", "salt", false
	cfg, _ = BuildConfig(p)
	s = string(cfg)
	if !strings.Contains(s, `"obfs":{"password":"salt","type":"salamander"}`) || strings.Contains(s, `"insecure":true`) {
		t.Fatalf("%s", s)
	}
	h.Insecure = true
	cfg, _ = BuildConfig(p)
	if !strings.Contains(string(cfg), `"insecure":true`) {
		t.Fatalf("%s", cfg)
	}
}

func TestProbeInbound(t *testing.T) {
	cfg, err := BuildConfig(base(ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	_ = json.Unmarshal(cfg, &m)
	ins := m["inbounds"].([]any)
	if len(ins) != 2 {
		t.Fatalf("proxy inbound and probe inbound: %v", ins)
	}
	probe := ins[1].(map[string]any)
	if probe["type"] != "mixed" || probe["tag"] != "probe-in" || probe["listen"] != "127.0.0.1" || probe["listen_port"] != float64(10900) {
		t.Fatalf("%v", probe)
	}
	if !strings.Contains(string(cfg), `"users":[{"password":"pp","username":"pu"}]`) {
		t.Fatalf("%s", cfg)
	}
	p := base(ModeTun)
	p.ProbeListen = ""
	cfg, _ = BuildConfig(p)
	if strings.Contains(string(cfg), "probe-in") {
		t.Fatal("no probe inbound when not asked")
	}
}

func TestProxyConfigParsesAndBuilds(t *testing.T) {
	cfg, err := BuildConfig(base(ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused the proxy config: %v\n%s", err, cfg)
	}
	f, err := New(context.Background(), base(ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	var m map[string]any
	_ = json.Unmarshal(cfg, &m)
	in := m["inbounds"].([]any)[0].(map[string]any)
	if in["type"] != "mixed" || in["listen"] != "127.0.0.1" || in["listen_port"] != float64(1080) {
		t.Fatalf("inbound %v", in)
	}
	if strings.Contains(string(cfg), `"tun"`) {
		t.Fatal("proxy mode has no tun")
	}
}

func TestTunConfigParsesAndBuildsUnprivileged(t *testing.T) {
	cfg, err := BuildConfig(base(ModeTun))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(cfg); err != nil {
		t.Fatalf("sing-box refused the tun config: %v\n%s", err, cfg)
	}
	f, err := New(context.Background(), base(ModeTun)) // the device opens at Start, so this needs no root
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
	s := string(cfg)
	for _, want := range []string{`"interface_name":"ghostlane0"`, `"auto_route":true`, `"strict_route":false`,
		`"iproute2_table_index":2022`, `"iproute2_rule_index":9000`, `"exclude_uid":[977]`, `"stack":"system"`,
		`"172.19.0.1/30"`, `"fdfe:dcba:9876::1/126"`, `"169.254.0.0/16"`, `"hijack-dns"`, `"ip_version":6`,
		`"default_domain_resolver":"local"`, `"detour":"tunnel"`, `"final":"tunnel"`} {
		if !strings.Contains(s, want) {
			t.Fatalf("tun config lacks %s:\n%s", want, s)
		}
	}
}

func TestProxyWithAuthAndListenAll(t *testing.T) {
	p := base(ModeProxy)
	p.ProxyListen, p.ProxyUser, p.ProxyPass = "0.0.0.0", "a", "b"
	cfg, err := BuildConfig(p)
	if err != nil || !strings.Contains(string(cfg), `"users":[{"password":"b","username":"a"}]`) {
		t.Fatalf("%v\n%s", err, cfg)
	}
	p.ProxyUser = ""
	if _, err := BuildConfig(p); err == nil {
		t.Fatal("listening beyond loopback without credentials is refused")
	}
}
