// Package routes owns the policy rules the daemon adds beside sing-box's: one
// `from <host address> lookup main` per global address of the host, ahead of
// sing-box's rules, so replies of inbound connections keep their normal route
// while everything the host originates goes through the tun.
package routes

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
)

const (
	OwnAddressPriority = 8990
	singBoxRuleFirst   = singbox.RuleIndex
	singBoxRuleLast    = singbox.RuleIndex + 10
	singBoxTable       = singbox.TableIndex

	// The kill switch sits behind sing-box's rules: while the tun is up they
	// take everything; while it is down, main answers only for what it routes
	// specifically (LAN, Docker, link-local: suppress_prefixlength 0) and for
	// the daemon's own uid (carriers, servers), and all else is unreachable.
	KillSwitchLAN   = 9098
	KillSwitchSelf  = 9099
	KillSwitchDrop  = 9100
	KillSwitchTable = 2023
)

type Manager struct {
	excludeIface string
	mu           sync.Mutex // one rule edit at a time: Sync, Clear and CleanupStale
}

func New(excludeIface string) *Manager { return &Manager{excludeIface: excludeIface} }

func (m *Manager) GlobalAddresses() ([]netip.Addr, error) {
	addrs, err := netlink.AddrList(nil, netlink.FAMILY_ALL)
	if err != nil {
		return nil, err
	}
	exclude := -1
	if link, err := netlink.LinkByName(m.excludeIface); err == nil {
		exclude = link.Attrs().Index
	}
	return filterGlobal(addrs, exclude), nil
}

func filterGlobal(addrs []netlink.Addr, excludeIndex int) []netip.Addr {
	var out []netip.Addr
	for _, a := range addrs {
		if a.Scope != unix.RT_SCOPE_UNIVERSE || a.LinkIndex == excludeIndex || a.IPNet == nil {
			continue
		}
		ip, ok := netip.AddrFromSlice(a.IP)
		if !ok {
			continue
		}
		ip = ip.Unmap()
		if ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		out = append(out, ip)
	}
	return out
}

func diff(have, want []netip.Addr) (add, del []netip.Addr) {
	h := map[netip.Addr]bool{}
	w := map[netip.Addr]bool{}
	for _, a := range have {
		h[a] = true
	}
	for _, a := range want {
		w[a] = true
		if !h[a] {
			add = append(add, a)
		}
	}
	for _, a := range have {
		if !w[a] {
			del = append(del, a)
		}
	}
	return add, del
}

func ownRule(a netip.Addr) *netlink.Rule {
	r := netlink.NewRule()
	r.Priority = OwnAddressPriority
	r.Table = unix.RT_TABLE_MAIN
	bits := 32
	r.Family = unix.AF_INET
	if a.Is6() {
		bits = 128
		r.Family = unix.AF_INET6
	}
	r.Src = &net.IPNet{IP: a.AsSlice(), Mask: net.CIDRMask(bits, bits)}
	return r
}

func (m *Manager) existing() ([]netip.Addr, error) {
	var out []netip.Addr
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		rules, err := netlink.RuleList(fam)
		if err != nil {
			return nil, err
		}
		for _, r := range rules {
			if r.Priority == OwnAddressPriority && r.Src != nil {
				if ip, ok := netip.AddrFromSlice(r.Src.IP); ok {
					out = append(out, ip.Unmap())
				}
			}
		}
	}
	return out, nil
}

// Sync makes the set of own-address rules equal to addrs: missing ones added,
// extra ones removed, nothing duplicated.
func (m *Manager) Sync(addrs []netip.Addr) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.syncLocked(addrs)
}

func (m *Manager) syncLocked(addrs []netip.Addr) error {
	have, err := m.existing()
	if err != nil {
		return err
	}
	add, del := diff(have, addrs)
	var errs []error
	for _, a := range del {
		if e := netlink.RuleDel(ownRule(a)); e != nil {
			errs = append(errs, fmt.Errorf("rule del %s: %w", a, e))
		}
	}
	for _, a := range add {
		if e := netlink.RuleAdd(ownRule(a)); e != nil {
			errs = append(errs, fmt.Errorf("rule add %s: %w", a, e))
		}
	}
	return errors.Join(errs...)
}

func (m *Manager) Clear() error { return m.Sync(nil) }

func killSwitchRules(family, uid int) []*netlink.Rule {
	lan := netlink.NewRule()
	lan.Family, lan.Priority, lan.Table, lan.SuppressPrefixlen = family, KillSwitchLAN, unix.RT_TABLE_MAIN, 0
	self := netlink.NewRule()
	self.Family, self.Priority, self.Table = family, KillSwitchSelf, unix.RT_TABLE_MAIN
	self.UIDRange = netlink.NewRuleUIDRange(uint32(uid), uint32(uid))
	drop := netlink.NewRule()
	drop.Family, drop.Priority, drop.Table = family, KillSwitchDrop, KillSwitchTable
	return []*netlink.Rule{lan, self, drop}
}

func unreachableDefault(family int) *netlink.Route {
	dst := &net.IPNet{IP: net.IPv4zero, Mask: net.CIDRMask(0, 32)}
	if family == unix.AF_INET6 {
		dst = &net.IPNet{IP: net.IPv6zero, Mask: net.CIDRMask(0, 128)}
	}
	return &netlink.Route{Family: family, Table: KillSwitchTable, Type: unix.RTN_UNREACHABLE, Dst: dst}
}

// tolerableV6 reports an error that means the kernel has no IPv6 (booted
// with ipv6.disable=1, common on hardened servers): there is nothing to kill
// on that family, so the v6 half of the switch is skipped, nothing else.
func tolerableV6(err error) bool {
	return err != nil && (errors.Is(err, unix.EAFNOSUPPORT) || errors.Is(err, unix.EPFNOSUPPORT) ||
		errors.Is(err, unix.EPROTONOSUPPORT) || errors.Is(err, unix.ENOTSUP))
}

// InstallKillSwitch puts the kill switch in for a daemon running as uid,
// replacing whatever a previous run left at its priorities.
func (m *Manager) InstallKillSwitch(uid int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	var errs []error
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		if err := m.removeKillSwitchRules(fam); err != nil && (fam != unix.AF_INET6 || !tolerableV6(err)) {
			errs = append(errs, err)
		}
		if err := netlink.RouteReplace(unreachableDefault(fam)); err != nil {
			if fam == unix.AF_INET6 && tolerableV6(err) {
				continue
			}
			errs = append(errs, fmt.Errorf("unreachable default (family %d): %w", fam, err))
			continue
		}
		for _, r := range killSwitchRules(fam, uid) {
			if err := netlink.RuleAdd(r); err != nil {
				if fam == unix.AF_INET6 && tolerableV6(err) {
					break
				}
				errs = append(errs, fmt.Errorf("rule %d (family %d): %w", r.Priority, fam, err))
			}
		}
	}
	if err := errors.Join(errs...); err != nil {
		_ = m.removeKillSwitchLocked()
		return err
	}
	return nil
}

// RemoveKillSwitch takes the kill switch out: its rules and table 2023.
func (m *Manager) RemoveKillSwitch() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.removeKillSwitchLocked()
}

func (m *Manager) removeKillSwitchLocked() error {
	var errs []error
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		if err := m.removeKillSwitchRules(fam); err != nil && (fam != unix.AF_INET6 || !tolerableV6(err)) {
			errs = append(errs, err)
		}
		routes, err := netlink.RouteListFiltered(fam, &netlink.Route{Table: KillSwitchTable}, netlink.RT_FILTER_TABLE)
		if err != nil {
			if fam != unix.AF_INET6 || !tolerableV6(err) {
				errs = append(errs, err)
			}
			continue
		}
		for i := range routes {
			if e := netlink.RouteDel(&routes[i]); e != nil {
				errs = append(errs, fmt.Errorf("table %d route: %w", KillSwitchTable, e))
			}
		}
	}
	return errors.Join(errs...)
}

func (m *Manager) removeKillSwitchRules(family int) error {
	rules, err := netlink.RuleList(family)
	if err != nil {
		return err
	}
	var errs []error
	for i := range rules {
		r := rules[i]
		if r.Priority >= KillSwitchLAN && r.Priority <= KillSwitchDrop {
			if e := netlink.RuleDel(&r); e != nil {
				errs = append(errs, fmt.Errorf("rule %d: %w", r.Priority, e))
			}
		}
	}
	return errors.Join(errs...)
}

// CleanupStale removes what a crashed run left: our rules, sing-box's rules
// (9000–9010), every route of table 2022 and the kill switch (the daemon puts
// it back at once when the stored selection asks). The box has one tun, ours.
func (m *Manager) CleanupStale() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	var errs []error
	if err := m.syncLocked(nil); err != nil {
		errs = append(errs, err)
	}
	if err := m.removeKillSwitchLocked(); err != nil {
		errs = append(errs, err)
	}
	for _, fam := range []int{unix.AF_INET, unix.AF_INET6} {
		rules, err := netlink.RuleList(fam)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		for i := range rules {
			r := rules[i]
			if r.Priority >= singBoxRuleFirst && r.Priority <= singBoxRuleLast {
				if e := netlink.RuleDel(&r); e != nil {
					errs = append(errs, fmt.Errorf("stale rule %d: %w", r.Priority, e))
				}
			}
		}
		routes, err := netlink.RouteListFiltered(fam, &netlink.Route{Table: singBoxTable}, netlink.RT_FILTER_TABLE)
		if err != nil {
			errs = append(errs, err)
			continue
		}
		for i := range routes {
			if e := netlink.RouteDel(&routes[i]); e != nil {
				errs = append(errs, fmt.Errorf("stale route: %w", e))
			}
		}
	}
	return errors.Join(errs...)
}

// WatchAddresses calls onChange, debounced, whenever an address is added or
// removed on any interface, until ctx ends or the returned stop is called.
// stop waits for the watcher (and any onChange in flight) to finish, so a
// caller that stops it before clearing the rules knows no Sync follows.
func (m *Manager) WatchAddresses(ctx context.Context, onChange func()) (func(), error) {
	ch := make(chan netlink.AddrUpdate, 64)
	done := make(chan struct{})
	if err := netlink.AddrSubscribe(ch, done); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		defer close(done)
		var timer *time.Timer
		var fire <-chan time.Time
		for {
			select {
			case <-ctx.Done():
				return
			case <-ch:
				if timer == nil {
					timer = time.NewTimer(500 * time.Millisecond)
				} else {
					timer.Reset(500 * time.Millisecond)
				}
				fire = timer.C
			case <-fire:
				fire = nil
				onChange()
			}
		}
	}()
	return func() { cancel(); <-finished }, nil
}
