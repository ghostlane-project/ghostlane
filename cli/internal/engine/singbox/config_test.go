package singbox

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func base(mode Mode) FrontParams {
	return FrontParams{Mode: mode, UpstreamAddr: "127.0.0.1:10808", UpstreamUser: "u", UpstreamPass: "p",
		ProxyListen: "127.0.0.1", ProxyPort: 1080, ExcludeUID: 977, InterfaceName: TunName, LogLevel: "warn"}
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
