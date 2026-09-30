package routes

import (
	"context"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"
)

func addr(cidr string, scope int, index int) netlink.Addr {
	ip, n, _ := net.ParseCIDR(cidr)
	n.IP = ip
	return netlink.Addr{IPNet: n, Scope: scope, LinkIndex: index}
}

func TestFilterGlobal(t *testing.T) {
	in := []netlink.Addr{
		addr("203.0.113.5/24", unix.RT_SCOPE_UNIVERSE, 2),
		addr("10.0.0.7/8", unix.RT_SCOPE_UNIVERSE, 2),
		addr("127.0.0.1/8", unix.RT_SCOPE_HOST, 1),
		addr("169.254.3.3/16", unix.RT_SCOPE_LINK, 2),
		addr("2001:db8::9/64", unix.RT_SCOPE_UNIVERSE, 2),
		addr("fe80::1/64", unix.RT_SCOPE_LINK, 2),
		addr("172.19.0.1/30", unix.RT_SCOPE_UNIVERSE, 9), // the tun itself
	}
	got := filterGlobal(in, 9)
	want := []netip.Addr{netip.MustParseAddr("203.0.113.5"), netip.MustParseAddr("10.0.0.7"), netip.MustParseAddr("2001:db8::9")}
	if len(got) != len(want) {
		t.Fatalf("%v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("%v", got)
		}
	}
}

func TestDiff(t *testing.T) {
	a, b, c := netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("2.2.2.2"), netip.MustParseAddr("3.3.3.3")
	add, del := diff([]netip.Addr{a, b}, []netip.Addr{b, c})
	if len(add) != 1 || add[0] != c || len(del) != 1 || del[0] != a {
		t.Fatalf("add=%v del=%v", add, del)
	}
	add, del = diff([]netip.Addr{a}, []netip.Addr{a})
	if len(add) != 0 || len(del) != 0 {
		t.Fatal("no change, no work")
	}
}

func TestWatchAddressesStops(t *testing.T) {
	m := New("ghostlane0")
	stop, err := m.WatchAddresses(context.Background(), func() {})
	if err != nil {
		t.Skipf("netlink address subscription unavailable here: %v", err)
	}
	done := make(chan struct{})
	go func() { stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("stop did not return")
	}
}
