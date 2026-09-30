package daemon

import (
	"bufio"
	"context"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netns"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/routes"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/fakesocks"
	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/netnstest"
)

const (
	hostAddr = "198.51.100.1"
	peerAddr = "198.51.100.2"
	farAddr  = "192.0.2.5" // the "SSH client on the internet": behind the peer, no route in main
)

func rulesWithPriority(t *testing.T, family, prio int) []netlink.Rule {
	t.Helper()
	all, err := netlink.RuleList(family)
	if err != nil {
		t.Fatal(err)
	}
	var out []netlink.Rule
	for _, r := range all {
		if r.Priority == prio {
			out = append(out, r)
		}
	}
	return out
}

// topology returns the peer namespace after wiring host <-veth-> peer.
func topology(t *testing.T) netns.NsHandle {
	t.Helper()
	runtime.LockOSThread()
	orig, err := netns.Get()
	if err != nil {
		t.Fatal(err)
	}
	peer, err := netns.New() // enters the new namespace
	if err != nil {
		t.Fatal(err)
	}
	if err := netns.Set(orig); err != nil {
		t.Fatal(err)
	}
	runtime.UnlockOSThread()

	veth := &netlink.Veth{LinkAttrs: netlink.LinkAttrs{Name: "gl-veth0"}, PeerName: "gl-veth1"}
	if err := netlink.LinkAdd(veth); err != nil {
		t.Fatal(err)
	}
	v0, _ := netlink.LinkByName("gl-veth0")
	v1, _ := netlink.LinkByName("gl-veth1")
	if err := netlink.LinkSetNsFd(v1, int(peer)); err != nil {
		t.Fatal(err)
	}
	a, _ := netlink.ParseAddr(hostAddr + "/24")
	_ = netlink.AddrAdd(v0, a)
	_ = netlink.LinkSetUp(v0)
	ph, err := netlink.NewHandleAt(peer)
	if err != nil {
		t.Fatal(err)
	}
	plo, _ := ph.LinkByName("lo")
	_ = ph.LinkSetUp(plo)
	pv1, _ := ph.LinkByName("gl-veth1")
	pa, _ := netlink.ParseAddr(peerAddr + "/24")
	_ = ph.AddrAdd(pv1, pa)
	fa, _ := netlink.ParseAddr(farAddr + "/32")
	_ = ph.AddrAdd(pv1, fa)
	_ = ph.LinkSetUp(pv1)
	// default via the peer, so sing-box finds a default interface and the far
	// client's replies have a route in main
	if err := netlink.RouteAdd(&netlink.Route{LinkIndex: v0.Attrs().Index, Gw: net.ParseIP(peerAddr)}); err != nil {
		t.Fatal(err)
	}
	// Strict reverse-path filtering, as on RHEL-family hosts: the reverse lookup
	// for an inbound packet is `from <our address> to <client>`, which the
	// own-address rule sends to main, so the SYN must still be accepted.
	for _, f := range []string{"/proc/sys/net/ipv4/conf/all/rp_filter", "/proc/sys/net/ipv4/conf/gl-veth0/rp_filter"} {
		if err := os.WriteFile(f, []byte("1"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return peer
}

func inNamespace(ns netns.NsHandle, fn func()) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		orig, _ := netns.Get()
		_ = netns.Set(ns)
		defer func() { _ = netns.Set(orig) }()
		fn()
	}()
	<-done
}

func dialFromFar(ns netns.NsHandle, target string, timeout time.Duration) (string, error) {
	var out string
	var err error
	inNamespace(ns, func() {
		d := net.Dialer{Timeout: timeout, LocalAddr: &net.TCPAddr{IP: net.ParseIP(farAddr)}}
		var c net.Conn
		c, err = d.Dial("tcp", target)
		if err != nil {
			return
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(timeout))
		var line string
		line, err = bufio.NewReader(c).ReadString('\n')
		out = strings.TrimSpace(line)
	})
	return out, err
}

func TestNetnsTunEndToEnd(t *testing.T) {
	if !netnstest.Enter(t) {
		return
	}
	peer := topology(t)

	// The inbound service: an "sshd" on the host's public address.
	ln, err := net.Listen("tcp", hostAddr+":2222")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_, _ = c.Write([]byte("hi\n"))
			c.Close()
		}
	}()
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("before the tun, the far client reaches the host: %q %v", got, err)
	}

	upstream := fakesocks.Serve(t, "u", "p", fakesocks.Answer204)
	dir := t.TempDir()
	line := "olcrtc://telemost?vp8channel@room#" + strings.Repeat("ab", 32) + "$DE · olcRTC"
	cfg := store.Defaults()
	cfg.Subscriptions = []store.Subscription{{URL: "inline:" + line}}
	cfg.Selection = &store.Selection{Subscription: "inline:" + line, Selector: "1", Mode: "tun"}
	if err := store.Save(filepath.Join(dir, "config.yaml"), cfg); err != nil {
		t.Fatal(err)
	}
	d := New(Deps{
		ConfigPath: filepath.Join(dir, "config.yaml"), StateDir: dir,
		StartEngine: func(context.Context, olcrtc.Params) (Engine, error) {
			return &fakeEngine{w: &world{}, addr: upstream}, nil
		},
		StartFront:   func(ctx context.Context, p singbox.FrontParams) (Front, error) { return singbox.Start(ctx, p) },
		Routes:       routes.New(singbox.TunName),
		Probe:        HTTPProbe("http://203.0.113.10/probe"),
		Logf:         t.Logf,
		UID:          65534, // nothing of ours is excluded: the rules alone must keep inbound alive
		ReadyTimeout: 5 * time.Second, ConfirmTimeout: 5 * time.Second, ProbeInterval: 500 * time.Millisecond,
		ProbeFailures: 3, RetryMin: 100 * time.Millisecond, RetryMax: time.Second,
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx) }()
	waitState(t, d, "up")

	if _, err := netlink.LinkByName(singbox.TunName); err != nil {
		t.Fatalf("tun device: %v", err)
	}
	own := rulesWithPriority(t, unix.AF_INET, routes.OwnAddressPriority)
	if len(own) != 1 || own[0].Src.IP.String() != hostAddr {
		t.Fatalf("own-address rule: %+v", own)
	}

	// 1. Inbound survives: the far client still reaches the host through the tun's rules.
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("inbound through the tun's rules: %q %v", got, err)
	}
	// 2. Outbound is tunnelled: an address with only a default route lands in the fake upstream.
	c, err := net.DialTimeout("tcp", "203.0.113.10:80", 3*time.Second)
	if err != nil {
		t.Fatalf("tunnelled dial: %v", err)
	}
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = c.Write([]byte("GET / HTTP/1.0\r\nHost: x\r\n\r\n"))
	status, err := bufio.NewReader(c).ReadString('\n')
	c.Close()
	if err != nil || !strings.Contains(status, "204") {
		t.Fatalf("through the tun and the front: %q %v", status, err)
	}
	// 3. IPv6 is refused, not leaked: the tun's stack may accept the SYN before
	// sing-box rejects the connection, so no data may flow either way.
	if c6, err := net.DialTimeout("tcp", "[2001:db8::1]:80", 3*time.Second); err == nil {
		_ = c6.SetDeadline(time.Now().Add(3 * time.Second))
		_, _ = c6.Write([]byte("GET / HTTP/1.0\r\n\r\n"))
		buf := make([]byte, 16)
		if n, err := c6.Read(buf); err == nil && n > 0 {
			t.Fatalf("IPv6 must be refused, got %q", buf[:n])
		}
		c6.Close()
	}
	// 4. The own-address rule is what keeps inbound alive: without it the far client is cut off.
	if err := routes.New(singbox.TunName).Clear(); err != nil {
		t.Fatal(err)
	}
	if _, err := dialFromFar(peer, hostAddr+":2222", 1500*time.Millisecond); err == nil {
		t.Fatal("without the rule the reply enters the tun and the client hangs")
	}
	addrs, _ := routes.New(singbox.TunName).GlobalAddresses()
	if err := routes.New(singbox.TunName).Sync(addrs); err != nil {
		t.Fatal(err)
	}
	if got, err := dialFromFar(peer, hostAddr+":2222", 3*time.Second); err != nil || got != "hi" {
		t.Fatalf("rule restored: %q %v", got, err)
	}
	// 5. Disconnect leaves nothing behind.
	d.Handle(ctx, ipc.Request{Verb: "disconnect"})
	waitState(t, d, "idle")
	if _, err := netlink.LinkByName(singbox.TunName); err == nil {
		t.Fatal("tun device still present")
	}
	if got := rulesWithPriority(t, unix.AF_INET, routes.OwnAddressPriority); len(got) != 0 {
		t.Fatalf("own rules left: %+v", got)
	}
	for p := singbox.RuleIndex; p <= singbox.RuleIndex+10; p++ {
		if got := rulesWithPriority(t, unix.AF_INET, p); len(got) != 0 {
			t.Fatalf("sing-box rule %d left: %+v", p, got)
		}
	}
	rt, _ := netlink.RouteListFiltered(unix.AF_INET, &netlink.Route{Table: singbox.TableIndex}, netlink.RT_FILTER_TABLE)
	if len(rt) != 0 {
		t.Fatalf("table 2022 left: %+v", rt)
	}
}
