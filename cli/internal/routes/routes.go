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
)

type Manager struct{ excludeIface string }

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
		ip, ok := netip.AddrFromSlice(a.IPNet.IP)
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

// CleanupStale removes what a crashed run left: our rules, sing-box's rules
// (9000–9010) and every route of table 2022. The box has one tun, ours.
func (m *Manager) CleanupStale() error {
	var errs []error
	if err := m.Clear(); err != nil {
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
// removed on any interface, until ctx ends.
func (m *Manager) WatchAddresses(ctx context.Context, onChange func()) error {
	ch := make(chan netlink.AddrUpdate, 64)
	done := make(chan struct{})
	if err := netlink.AddrSubscribe(ch, done); err != nil {
		return err
	}
	go func() {
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
	return nil
}
