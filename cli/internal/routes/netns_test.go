package routes

import (
	"net"
	"net/netip"
	"testing"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/testutil/netnstest"
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

func TestNetnsOwnAddressRulesAndCleanup(t *testing.T) {
	if !netnstest.Enter(t) {
		return
	}
	dummy := &netlink.Dummy{LinkAttrs: netlink.LinkAttrs{Name: "gl-dum0"}}
	if err := netlink.LinkAdd(dummy); err != nil {
		t.Fatal(err)
	}
	link, _ := netlink.LinkByName("gl-dum0")
	_ = netlink.LinkSetUp(link)
	a, _ := netlink.ParseAddr("198.51.100.7/24")
	_ = netlink.AddrAdd(link, a)
	a6, _ := netlink.ParseAddr("2001:db8::7/64")
	_ = netlink.AddrAdd(link, a6)

	m := New("ghostlane0")
	addrs, err := m.GlobalAddresses()
	if err != nil || len(addrs) != 2 {
		t.Fatalf("%v %v", addrs, err)
	}
	if err := m.Sync(addrs); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 || got[0].Src.IP.String() != "198.51.100.7" || got[0].Table != unix.RT_TABLE_MAIN {
		t.Fatalf("v4 rule: %+v", got)
	}
	if got := rulesWithPriority(t, unix.AF_INET6, OwnAddressPriority); len(got) != 1 {
		t.Fatalf("v6 rule: %+v", got)
	}
	// Review Focus 3: a second Sync with one address gone and one added.
	if err := m.Sync([]netip.Addr{netip.MustParseAddr("198.51.100.8")}); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 || got[0].Src.IP.String() != "198.51.100.8" {
		t.Fatalf("after change: %+v", got)
	}
	if got := rulesWithPriority(t, unix.AF_INET6, OwnAddressPriority); len(got) != 0 {
		t.Fatalf("v6 rule should be gone: %+v", got)
	}
	if err := m.Sync([]netip.Addr{netip.MustParseAddr("198.51.100.8")}); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 1 {
		t.Fatalf("sync is idempotent: %+v", got)
	}
	if err := m.Clear(); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, OwnAddressPriority); len(got) != 0 {
		t.Fatalf("clear: %+v", got)
	}

	// A crash leaves sing-box's rules and table behind; CleanupStale removes them.
	stale := netlink.NewRule()
	stale.Priority, stale.Table, stale.Family = 9003, 2022, unix.AF_INET
	if err := netlink.RuleAdd(stale); err != nil {
		t.Fatal(err)
	}
	_, dst, _ := net.ParseCIDR("203.0.113.0/24")
	if err := netlink.RouteAdd(&netlink.Route{Dst: dst, LinkIndex: link.Attrs().Index, Table: 2022}); err != nil {
		t.Fatal(err)
	}
	if err := m.CleanupStale(); err != nil {
		t.Fatal(err)
	}
	if got := rulesWithPriority(t, unix.AF_INET, 9003); len(got) != 0 {
		t.Fatalf("stale rule: %+v", got)
	}
	routes, _ := netlink.RouteListFiltered(unix.AF_INET, &netlink.Route{Table: 2022}, netlink.RT_FILTER_TABLE)
	if len(routes) != 0 {
		t.Fatalf("table 2022 not flushed: %+v", routes)
	}
}
