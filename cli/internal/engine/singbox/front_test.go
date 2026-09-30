package singbox

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"testing"
	"time"

	"golang.org/x/net/proxy"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/fakesocks"
)

func TestProxyModeEndToEnd(t *testing.T) {
	upstream := fakesocks.Serve(t, "u", "p", fakesocks.Answer204)
	port, _ := olcrtc.FreePort()
	probePort, _ := olcrtc.FreePort()
	p := base(ModeProxy)
	p.Upstream, p.ProxyPort = Upstream{Socks: &SocksUpstream{Addr: upstream, User: "u", Pass: "p"}}, port
	p.ProbeListen = "127.0.0.1:" + strconv.Itoa(probePort)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f, err := Start(ctx, p)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	addr := p.ProxyListen + ":" + strconv.Itoa(port)
	d, _ := proxy.SOCKS5("tcp", addr, nil, proxy.Direct)
	cd, ok := d.(proxy.ContextDialer)
	if !ok {
		t.Fatal("socks dialer without DialContext")
	}
	c := &http.Client{Transport: &http.Transport{DialContext: cd.DialContext}, Timeout: 5 * time.Second}
	resp, err := c.Get("http://203.0.113.10/anything")
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("through the front: %v %v", resp, err)
	}
	resp.Body.Close()
	// the HTTP half of the mixed inbound
	pu, _ := url.Parse("http://" + addr)
	hc := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 5 * time.Second}
	resp, err = hc.Get("http://203.0.113.11/")
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("http proxy: %v %v", resp, err)
	}
	resp.Body.Close()
	// the probe inbound, with its own credentials
	pd, _ := proxy.SOCKS5("tcp", p.ProbeListen, &proxy.Auth{User: "pu", Password: "pp"}, proxy.Direct)
	pcd, _ := pd.(proxy.ContextDialer)
	pc := &http.Client{Transport: &http.Transport{DialContext: pcd.DialContext}, Timeout: 5 * time.Second}
	resp, err = pc.Get("http://203.0.113.12/probe")
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("probe inbound: %v %v", resp, err)
	}
	resp.Body.Close()
	nd, _ := proxy.SOCKS5("tcp", p.ProbeListen, nil, proxy.Direct)
	ncd, _ := nd.(proxy.ContextDialer)
	nc := &http.Client{Transport: &http.Transport{DialContext: ncd.DialContext}, Timeout: 5 * time.Second}
	if resp, err := nc.Get("http://203.0.113.12/probe"); err == nil {
		resp.Body.Close()
		t.Fatal("the probe inbound refuses clients without its credentials")
	}
}
