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

// The kill switch: 9098 main for what main routes specifically (suppress
// prefixlength 0), 9099 the daemon's uid to main, 9100 everything else to
// table 2023 whose only route is unreachable default — v4 and v6, idempotent.
func TestNetnsKillSwitchRules(t *testing.T) {
	if !netnstest.Enter(t) {
		return
	}
	m := New("ghostlane0")
	for i := 0; i < 2; i++ { // twice: no duplicates
		if err := m.InstallKillSwitch(998); err != nil {
			t.Fatal(err)
		}
	}
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		if got := rulesWithPriority(t, fam, KillSwitchLAN); len(got) != 1 || got[0].Table != unix.RT_TABLE_MAIN || got[0].SuppressPrefixlen != 0 {
			t.Fatalf("family %d LAN rule: %+v", fam, got)
		}
		if got := rulesWithPriority(t, fam, KillSwitchSelf); len(got) != 1 || got[0].Table != unix.RT_TABLE_MAIN || got[0].UIDRange == nil || got[0].UIDRange.Start != 998 || got[0].UIDRange.End != 998 {
			t.Fatalf("family %d self rule: %+v", fam, got)
		}
		if got := rulesWithPriority(t, fam, KillSwitchDrop); len(got) != 1 || got[0].Table != KillSwitchTable {
			t.Fatalf("family %d drop rule: %+v", fam, got)
		}
		rt, err := netlink.RouteListFiltered(fam, &netlink.Route{Table: KillSwitchTable}, netlink.RT_FILTER_TABLE)
		if err != nil || len(rt) != 1 || rt[0].Type != unix.RTN_UNREACHABLE {
			t.Fatalf("family %d table %d: %+v %v", fam, KillSwitchTable, rt, err)
		}
	}
	if err := m.RemoveKillSwitch(); err != nil {
		t.Fatal(err)
	}
	assertNoKillSwitch(t)
	if err := m.InstallKillSwitch(998); err != nil {
		t.Fatal(err)
	}
	if err := m.CleanupStale(); err != nil {
		t.Fatal(err)
	}
	assertNoKillSwitch(t)
}

func assertNoKillSwitch(t *testing.T) {
	t.Helper()
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		for _, prio := range []int{KillSwitchLAN, KillSwitchSelf, KillSwitchDrop} {
			if got := rulesWithPriority(t, fam, prio); len(got) != 0 {
				t.Fatalf("rule %d left: %+v", prio, got)
			}
		}
		if rt, _ := netlink.RouteListFiltered(fam, &netlink.Route{Table: KillSwitchTable}, netlink.RT_FILTER_TABLE); len(rt) != 0 {
			t.Fatalf("table %d left: %+v", KillSwitchTable, rt)
		}
	}
}
