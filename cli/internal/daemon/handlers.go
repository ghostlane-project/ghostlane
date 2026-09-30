package daemon

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/ipc"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

func (d *Daemon) Handle(ctx context.Context, req ipc.Request) ipc.Response {
	if err := d.ensureConfig(); err != nil {
		return ipc.Fail("io", err.Error())
	}
	switch req.Verb {
	case "version":
		v := d.deps.Version
		return ipc.Response{OK: true, Version: &v}
	case "status":
		return ipc.Response{OK: true, Status: d.status()}
	case "list":
		return d.list(ctx, req.Subscription)
	case "add":
		return d.add(ctx, req.Source)
	case "remove":
		return d.remove(req.Subscription)
	case "refresh":
		d.refresh(ctx, true)
		return ipc.Response{OK: true, Message: "refreshed"}
	case "connect":
		return d.connect(ctx, req)
	case "disconnect":
		d.stopConnect()
		d.mu.Lock()
		d.cfg.Selection = nil
		cfg := d.cfg.Clone()
		d.mu.Unlock()
		if err := d.saveConfig(cfg); err != nil {
			return ipc.Fail("io", err.Error())
		}
		return ipc.Response{OK: true, Message: "disconnected"}
	}
	return ipc.Fail("bad_verb", "unknown verb "+req.Verb)
}

func entryView(i int, e links.Entry) ipc.EntryView {
	v := ipc.EntryView{Index: i + 1, ID: e.ID, Label: e.Label, Country: e.Country, Kind: e.Kind.String(), Problem: e.Problem}
	if e.Olcrtc != nil {
		v.Carrier, v.Transport = e.Olcrtc.Provider, e.Olcrtc.Transport
	}
	return v
}

func (d *Daemon) status() *ipc.Status {
	d.mu.Lock()
	defer d.mu.Unlock()
	st := &ipc.Status{State: d.state, LastError: d.lastErr}
	if d.sel != nil {
		st.Mode, st.Selector = d.sel.Mode, d.sel.Selector
	}
	switch {
	case d.cur != nil:
		v := entryView(0, d.cur.entry)
		v.Index = 0
		st.Line = &v
		st.Since = d.since.UTC().Format(time.RFC3339)
		if d.cur.mode == "proxy" {
			addr := d.cfg.Proxy.Listen + ":" + strconv.Itoa(d.cfg.Proxy.Port)
			st.Proxy = &ipc.ProxyView{Socks: addr, HTTP: addr}
		}
	case d.pending != nil:
		v := entryView(0, *d.pending)
		v.Index = 0
		st.Line = &v
	}
	for _, s := range d.cfg.Subscriptions {
		sv := ipc.SubscriptionView{URL: store.MaskURL(s.URL), Title: s.Title, IntervalHours: s.IntervalHours, Error: d.subErr[s.URL]}
		if _, inline := inlineBody(s.URL); inline {
			sv.URL = "inline line"
		} else if c, _ := store.LoadCache(d.deps.StateDir, s.URL); c != nil {
			sv.FetchedAt = c.FetchedAt.UTC().Format(time.RFC3339)
			sv.NextRefresh = c.FetchedAt.Add(time.Duration(max(s.IntervalHours, 1)) * time.Hour).UTC().Format(time.RFC3339)
			sv.UserInfo = c.Headers.UserInfo
		}
		st.Subscriptions = append(st.Subscriptions, sv)
	}
	return st
}

func (d *Daemon) list(ctx context.Context, only string) ipc.Response {
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	out := []ipc.EntryView{}
	for _, s := range subs {
		if only != "" && !matchesSub(s.URL, only) {
			continue
		}
		entries, err := d.entriesFor(ctx, s.URL, false)
		if err != nil {
			d.logf("list %s: %v", store.MaskURL(s.URL), err)
			continue
		}
		for i, e := range entries {
			out = append(out, entryView(i, e))
		}
	}
	return ipc.Response{OK: true, Entries: out}
}

func matchesSub(url, needle string) bool {
	return url == needle || strings.HasPrefix(url, needle) || strings.HasPrefix(store.MaskURL(url), needle)
}

func (d *Daemon) add(ctx context.Context, source string) ipc.Response {
	payload := links.ImportPayload(source)
	var msg string
	switch {
	case strings.HasPrefix(payload, "https://") || strings.HasPrefix(payload, "http://"):
		d.mu.Lock()
		for _, s := range d.cfg.Subscriptions {
			if s.URL == payload {
				d.mu.Unlock()
				return ipc.Response{OK: true, Message: "already added"}
			}
		}
		d.cfg.Subscriptions = append(d.cfg.Subscriptions, store.Subscription{URL: payload, AddedAt: d.deps.Now()})
		d.mu.Unlock()
		c, err := d.fetchInto(ctx, payload)
		if err != nil {
			d.mu.Lock()
			d.cfg.Subscriptions = d.cfg.Subscriptions[:len(d.cfg.Subscriptions)-1]
			d.mu.Unlock()
			return ipc.Fail("fetch", Scrub(err.Error()))
		}
		entries := links.Entries(payload, links.DecodeBody(c.Body))
		groups := links.GroupByCountry(entries)
		countries := make([]string, 0, len(groups))
		for _, g := range groups {
			countries = append(countries, g.Country)
		}
		usable := len(connectable(entries))
		msg = fmt.Sprintf("%s: %d entries (%d usable now); countries: %s", firstNonEmpty(c.Headers.Title, store.MaskURL(payload)), len(entries), usable, strings.Join(countries, " "))
	case strings.HasPrefix(payload, "olcrtc://"):
		line, err := links.ParseOlcrtc(payload)
		if err != nil {
			return ipc.Fail("bad_line", err.Error())
		}
		d.mu.Lock()
		d.cfg.Subscriptions = append(d.cfg.Subscriptions, store.Subscription{URL: inlinePrefix + payload, Title: line.Label, AddedAt: d.deps.Now()})
		d.mu.Unlock()
		msg = "added " + line.Label
	default:
		scheme, _, _ := strings.Cut(payload, "://")
		return ipc.Fail("unsupported", scheme+":// lines are not supported by this version; add a list URL or an olcrtc:// line")
	}
	d.mu.Lock()
	cfg := d.cfg.Clone()
	d.mu.Unlock()
	if err := d.saveConfig(cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	return ipc.Response{OK: true, Message: msg}
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func (d *Daemon) remove(needle string) ipc.Response {
	d.mu.Lock()
	kept := d.cfg.Subscriptions[:0:0]
	removed := 0
	var dropSelection bool
	for _, s := range d.cfg.Subscriptions {
		if matchesSub(s.URL, needle) || s.Title == needle {
			removed++
			if d.sel != nil && d.sel.Subscription == s.URL {
				dropSelection = true
			}
			continue
		}
		kept = append(kept, s)
	}
	d.cfg.Subscriptions = kept
	d.mu.Unlock()
	if removed == 0 {
		return ipc.Fail("no_match", "no subscription matches "+needle)
	}
	if dropSelection {
		d.stopConnect()
		d.mu.Lock()
		d.cfg.Selection = nil
		d.mu.Unlock()
	}
	d.mu.Lock()
	cfg := d.cfg.Clone()
	d.mu.Unlock()
	if err := d.saveConfig(cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	return ipc.Response{OK: true, Message: fmt.Sprintf("removed %d", removed)}
}

func (d *Daemon) connect(ctx context.Context, req ipc.Request) ipc.Response {
	mode := req.Mode
	if mode == "" {
		mode = "proxy"
	}
	if mode != "tun" && mode != "proxy" {
		return ipc.Fail("bad_mode", "mode must be tun or proxy")
	}
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	if len(subs) == 0 {
		return ipc.Fail("no_subscription", "add a list first: ghostlane add <url>")
	}
	var chosen string
	var lastErr error
	for _, s := range subs {
		if req.Subscription != "" && !matchesSub(s.URL, req.Subscription) {
			continue
		}
		entries, err := d.entriesFor(ctx, s.URL, false)
		if err != nil {
			lastErr = err
			continue
		}
		if _, err := links.Select(entries, req.Selector); err == nil {
			chosen = s.URL
			break
		} else {
			lastErr = err
		}
	}
	if chosen == "" {
		if lastErr == nil {
			lastErr = errNoSubscription
		}
		if errors.Is(lastErr, links.ErrNoMatch) {
			return ipc.Fail("no_match", lastErr.Error())
		}
		return ipc.Fail("no_subscription", lastErr.Error())
	}
	sel := store.Selection{Subscription: chosen, Selector: req.Selector, Mode: mode}
	d.mu.Lock()
	d.cfg.Selection = &sel
	cfg := d.cfg.Clone()
	d.mu.Unlock()
	if err := d.saveConfig(cfg); err != nil {
		return ipc.Fail("io", err.Error())
	}
	d.startConnect(sel)
	return ipc.Response{OK: true, Message: fmt.Sprintf("connecting to %s in %s mode", req.Selector, mode)}
}
