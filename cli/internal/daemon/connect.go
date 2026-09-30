package daemon

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/xray"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

var errNotConnectable = errors.New("this line cannot be connected by this version")

// startConnect cancels any running connection and starts the loop for sel.
func (d *Daemon) startConnect(sel store.Selection) {
	d.connectMu.Lock()
	defer d.connectMu.Unlock()
	d.startConnectLocked(sel)
}

func (d *Daemon) startConnectLocked(sel store.Selection) {
	d.stopConnectLocked()
	d.mu.Lock()
	base := d.runCtx
	if base == nil {
		base = context.Background()
	}
	ctx, cancel := context.WithCancel(base)
	done := make(chan struct{})
	d.cancel, d.loopDone, d.loopLive = cancel, done, true
	d.sel = &sel
	d.state, d.lastErr = "connecting", ""
	d.mu.Unlock()
	go func() {
		defer func() {
			d.mu.Lock()
			d.loopLive = false
			d.mu.Unlock()
			close(done)
		}()
		d.connectLoop(ctx, sel)
	}()
}

// stopConnect ends the loop and waits for it to tear the connection down.
func (d *Daemon) stopConnect() {
	d.connectMu.Lock()
	defer d.connectMu.Unlock()
	d.stopConnectLocked()
}

func (d *Daemon) stopConnectLocked() {
	d.mu.Lock()
	cancel, done := d.cancel, d.loopDone
	d.cancel, d.loopDone = nil, nil
	d.mu.Unlock()
	if cancel != nil {
		cancel()
		<-done
	}
	d.mu.Lock()
	d.state = "idle"
	d.sel = nil
	d.pending = nil
	d.mu.Unlock()
}

func (d *Daemon) setState(state, lastErr string, entry *links.Entry) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.state = state
	if lastErr != "" {
		d.lastErr = Scrub(lastErr)
	}
	d.pending = entry
}

func (d *Daemon) connectLoop(ctx context.Context, sel store.Selection) {
	backoff := d.deps.RetryMin
	round := 0
	lastTried := "" // the id of the line the previous round ended on
	for ctx.Err() == nil {
		all, err := d.allEntries(ctx, sel.Subscription)
		if err != nil {
			d.setState("failed", err.Error(), nil)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, d.deps.RetryMax)
			continue
		}
		cands, err := links.Select(flatten(all), sel.Selector)
		if err != nil {
			// nothing matches today; the next refresh that changes a list retries
			d.setState("failed", err.Error(), nil)
			return
		}
		cands = connectable(cands)
		if len(cands) == 0 {
			d.setState("failed", "no connectable line matches "+sel.Selector, nil)
			return
		}
		if round == 0 {
			cands = d.orderCandidates(sel, cands)
		} else {
			cands = rotateAfter(cands, lastTried)
		}
		round++
		anyUp := false
		for i := range cands {
			cand := cands[i]
			if ctx.Err() != nil {
				return
			}
			lastTried = cand.ID
			d.setState("connecting", "", &cand)
			l, err := d.bringUp(ctx, cand, sel.Mode)
			if err != nil {
				d.logf("%s: %v", cand.Label, err)
				d.setState("connecting", err.Error(), &cand)
				continue
			}
			anyUp = true
			backoff = d.deps.RetryMin
			d.markUp(sel, l)
			reason := d.supervise(ctx, l)
			d.tearDown(l)
			if ctx.Err() != nil {
				return
			}
			d.logf("%s: %s, moving on", cand.Label, reason)
		}
		if !anyUp {
			d.setState("failed", "", nil)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, d.deps.RetryMax)
		}
	}
}

// rotateAfter starts the next round after the line the last one ended on, so
// the line that just failed is tried last, not first.
func rotateAfter(cands []links.Entry, lastID string) []links.Entry {
	for i, c := range cands {
		if c.ID == lastID {
			out := make([]links.Entry, 0, len(cands))
			out = append(out, cands[i+1:]...)
			return append(out, cands[:i+1]...)
		}
	}
	return cands
}

func connectable(in []links.Entry) []links.Entry {
	var out []links.Entry
	for _, e := range in {
		if e.Connectable() {
			out = append(out, e)
		}
	}
	return out
}

func lastGoodKey(sel store.Selection) string { return sel.Subscription + "|" + sel.Selector }

func (d *Daemon) orderCandidates(sel store.Selection, cands []links.Entry) []links.Entry {
	d.mu.Lock()
	id := d.lastGood[lastGoodKey(sel)]
	d.mu.Unlock()
	for i, c := range cands {
		if c.ID == id && i > 0 {
			out := make([]links.Entry, 0, len(cands))
			out = append(out, c)
			out = append(out, cands[:i]...)
			return append(out, cands[i+1:]...)
		}
	}
	return cands
}

// newProbeInbound is swapped by tests.
var newProbeInbound = probeInbound

// probeInbound picks the front's loopback probe inbound: a free port and
// per-start credentials.
func probeInbound() (addr, user, pass string, err error) {
	port, err := olcrtc.FreePort()
	if err != nil {
		return "", "", "", err
	}
	user, pass = olcrtc.RandomCredentials()
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), user, pass, nil
}

func (d *Daemon) frontParams(mode string, up singbox.Upstream, probeAddr, probeUser, probePass string) singbox.FrontParams {
	d.mu.Lock()
	proxy := d.cfg.Proxy
	d.mu.Unlock()
	return singbox.FrontParams{Mode: singbox.Mode(mode), Upstream: up,
		ProxyListen: proxy.Listen, ProxyPort: proxy.Port, ProxyUser: proxy.User, ProxyPass: proxy.Pass,
		ExcludeUID: d.deps.UID, InterfaceName: singbox.TunName,
		ProbeListen: probeAddr, ProbeUser: probeUser, ProbePass: probePass}
}

// bringUp connects one entry by its kind:
//   - olcRTC: the engine, confirmed through its SOCKS, then the front;
//   - xhttp: an Xray instance, likewise;
//   - Reality tcp / Hysteria2: the front's own outbound. In tun mode the line is
//     first proven in a proxy-only front, so a dead server never gets a tun.
//
// Every kind is then confirmed once more through the front's probe inbound,
// which supervise keeps probing.
func (d *Daemon) bringUp(ctx context.Context, e links.Entry, mode string) (*live, error) {
	var eng Engine
	var up singbox.Upstream
	switch {
	case e.Olcrtc != nil:
		port, err := olcrtc.FreePort()
		if err != nil {
			return nil, err
		}
		user, pass := olcrtc.RandomCredentials()
		eng, err = d.deps.StartEngine(ctx, olcrtc.Params{
			Line: *e.Olcrtc, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: user, SocksPass: pass,
			DNS: olcrtc.HostResolvers(d.deps.ResolvConf), DirectRules: olcrtc.PrivateDirectRules, DeviceIDPath: d.deviceIDPath(),
			ReadyTimeout: d.deps.ReadyTimeout,
		})
		if err != nil {
			return nil, err
		}
	case e.Vless != nil && e.Vless.Transport.Kind == "xhttp":
		port, err := olcrtc.FreePort()
		if err != nil {
			return nil, err
		}
		user, pass := olcrtc.RandomCredentials()
		eng, err = d.deps.StartXray(ctx, xray.Params{Line: *e.Vless, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: user, SocksPass: pass})
		if err != nil {
			return nil, err
		}
	case e.Vless != nil:
		up = singbox.Upstream{Vless: e.Vless}
	case e.Hy2 != nil:
		up = singbox.Upstream{Hy2: e.Hy2}
	default:
		return nil, errNotConnectable
	}
	if eng != nil {
		// the engine's own credentials are the truth from here on
		user, pass := eng.Credentials()
		up = singbox.Upstream{Socks: &singbox.SocksUpstream{Addr: eng.SocksAddr(), User: user, Pass: pass}}
		if err := d.confirmVia(ctx, eng.SocksAddr(), user, pass); err != nil {
			_ = eng.Stop(5 * time.Second)
			return nil, err
		}
	} else if mode == "tun" {
		if err := d.preflight(ctx, up); err != nil {
			return nil, err
		}
	}
	if mode == "tun" {
		addrs, err := d.deps.Routes.GlobalAddresses()
		if err == nil {
			err = d.deps.Routes.Sync(addrs)
		}
		if err != nil {
			stopEngine(eng)
			return nil, fmt.Errorf("policy rules: %w", err)
		}
	}
	probeAddr, probeUser, probePass, err := newProbeInbound()
	if err != nil {
		if mode == "tun" {
			_ = d.deps.Routes.Clear()
		}
		stopEngine(eng)
		return nil, err
	}
	front, err := d.deps.StartFront(ctx, d.frontParams(mode, up, probeAddr, probeUser, probePass))
	if err != nil {
		if mode == "tun" {
			_ = d.deps.Routes.Clear()
		}
		stopEngine(eng)
		return nil, fmt.Errorf("front: %w", err)
	}
	l := &live{entry: e, mode: mode, engine: eng, front: front, probeAddr: probeAddr, probeUser: probeUser, probePass: probePass}
	if err := d.confirmVia(ctx, probeAddr, probeUser, probePass); err != nil {
		d.tearDown(l)
		return nil, err
	}
	return l, nil
}

// preflight proves a native line in a proxy-only front before the tun front
// replaces it: the same outbound, a loopback probe inbound, nothing routed.
func (d *Daemon) preflight(ctx context.Context, up singbox.Upstream) error {
	probeAddr, probeUser, probePass, err := newProbeInbound()
	if err != nil {
		return err
	}
	port, err := olcrtc.FreePort()
	if err != nil {
		return err
	}
	fp := d.frontParams("proxy", up, probeAddr, probeUser, probePass)
	fp.ProxyListen, fp.ProxyPort = "127.0.0.1", port
	fp.ProxyUser, fp.ProxyPass = olcrtc.RandomCredentials()
	front, err := d.deps.StartFront(ctx, fp)
	if err != nil {
		return fmt.Errorf("pre-flight front: %w", err)
	}
	err = d.confirmVia(ctx, probeAddr, probeUser, probePass)
	_ = front.Close()
	if err != nil {
		return fmt.Errorf("pre-flight: %w", err)
	}
	return nil
}

func stopEngine(eng Engine) {
	if eng != nil {
		_ = eng.Stop(5 * time.Second)
	}
}

// confirmVia probes through a SOCKS until it answers or ConfirmTimeout passes.
func (d *Daemon) confirmVia(ctx context.Context, addr, user, pass string) error {
	deadline := d.deps.Now().Add(d.deps.ConfirmTimeout)
	for {
		last := d.deps.Probe(ctx, addr, user, pass)
		if last == nil {
			return nil
		}
		if ctx.Err() != nil || !d.deps.Now().Before(deadline) {
			return fmt.Errorf("no traffic through the line: %w", last)
		}
		if !sleepCtx(ctx, min(3*time.Second, d.deps.ConfirmTimeout/4)) {
			return ctx.Err()
		}
	}
}

func (d *Daemon) markUp(sel store.Selection, l *live) {
	d.mu.Lock()
	d.lastGood[lastGoodKey(sel)] = l.entry.ID
	lg := make(map[string]string, len(d.lastGood))
	for k, v := range d.lastGood {
		lg[k] = v
	}
	d.mu.Unlock()
	// persisted before the state says "up": whoever reads "up" finds the file
	if err := d.saveLastGood(lg); err != nil {
		d.logf("last-good: %v", err)
	}
	d.mu.Lock()
	d.cur = l
	d.state, d.lastErr, d.since, d.pending = "up", "", d.deps.Now(), nil
	d.mu.Unlock()
	d.logf("up: %s (%s)", l.entry.Label, l.entry.Kind)
}

// supervise probes every ProbeInterval; ProbeFailures in a row, or a runtime
// that is no longer running, end the line. The address watcher belongs to this
// connection: it is stopped before the caller clears the rules.
func (d *Daemon) supervise(ctx context.Context, l *live) string {
	if l.mode == "tun" {
		stop, err := d.deps.Routes.WatchAddresses(ctx, func() {
			if addrs, err := d.deps.Routes.GlobalAddresses(); err == nil {
				if err := d.deps.Routes.Sync(addrs); err != nil {
					d.logf("policy rules: %v", err)
				}
			}
		})
		if err != nil {
			d.logf("address watch: %v", err)
		} else {
			defer stop()
		}
	}
	failures := 0
	t := time.NewTicker(d.deps.ProbeInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return "stopped"
		case <-t.C:
			if l.engine != nil {
				if st := l.engine.State(); st != "running" {
					return "engine " + st
				}
			}
			if err := d.deps.Probe(ctx, l.probeAddr, l.probeUser, l.probePass); err != nil {
				failures++
				d.logf("probe %d/%d failed: %v", failures, d.deps.ProbeFailures, err)
				if failures >= d.deps.ProbeFailures {
					return fmt.Sprintf("%d probes failed", failures)
				}
			} else {
				failures = 0
			}
		}
	}
}

func (d *Daemon) tearDown(l *live) {
	_ = l.front.Close()
	stopEngine(l.engine)
	if l.mode == "tun" {
		_ = d.deps.Routes.Clear()
	}
	d.mu.Lock()
	if d.cur == l {
		d.cur = nil
	}
	d.mu.Unlock()
}

func sleepCtx(ctx context.Context, dur time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(dur):
		return true
	}
}

// entriesFor returns the parsed entries of a subscription from the cache,
// fetching when there is no cache or force is set. An inline: subscription is
// its own body.
func (d *Daemon) entriesFor(ctx context.Context, subURL string, force bool) ([]links.Entry, error) {
	if body, ok := inlineBody(subURL); ok {
		return links.Entries(subURL, links.DecodeBody(body)), nil
	}
	cache, err := store.LoadCache(d.deps.StateDir, subURL)
	if err != nil {
		return nil, err
	}
	if cache == nil || force {
		cache, err = d.fetchInto(ctx, subURL)
		if err != nil {
			return nil, err
		}
	}
	return links.Entries(subURL, links.DecodeBody(cache.Body)), nil
}

func (d *Daemon) fetchInto(ctx context.Context, subURL string) (*store.Cache, error) {
	body, headers, err := d.deps.Fetch(ctx, subURL)
	d.mu.Lock()
	if err != nil {
		d.subErr[subURL] = Scrub(err.Error())
	} else {
		delete(d.subErr, subURL)
	}
	d.mu.Unlock()
	if err != nil {
		return nil, err
	}
	c := &store.Cache{Body: body, Headers: headers, FetchedAt: d.deps.Now()}
	if err := d.saveCache(subURL, c); err != nil {
		return nil, err
	}
	d.mu.Lock()
	for i := range d.cfg.Subscriptions {
		if d.cfg.Subscriptions[i].URL == subURL {
			d.cfg.Subscriptions[i].Title = headers.Title
			d.cfg.Subscriptions[i].IntervalHours = headers.UpdateIntervalHours
		}
	}
	cfg := d.cfg.Clone()
	d.mu.Unlock()
	if err := d.saveConfig(cfg); err != nil {
		d.logf("config: %v", err)
	}
	return c, nil
}

const inlinePrefix = "inline:"

func inlineBody(subURL string) ([]byte, bool) {
	if len(subURL) > len(inlinePrefix) && subURL[:len(inlinePrefix)] == inlinePrefix {
		return []byte(subURL[len(inlinePrefix):]), true
	}
	return nil, false
}

// refreshLoop refreshes due lists every minute and reconnects when the running
// line's room or key changed.
func (d *Daemon) refreshLoop(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			d.refresh(ctx, false)
		}
	}
}

// refresh fetches the due lists, four at a time, and then lets afterRefresh
// react to what changed.
func (d *Daemon) refresh(ctx context.Context, force bool) {
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for _, s := range subs {
		if _, inline := inlineBody(s.URL); inline {
			continue
		}
		cache, _ := store.LoadCache(d.deps.StateDir, s.URL)
		hours := s.IntervalHours
		if hours <= 0 {
			hours = 24
		}
		if !force && cache != nil && d.deps.Now().Before(cache.FetchedAt.Add(time.Duration(hours)*time.Hour)) {
			continue
		}
		wg.Add(1)
		go func(url string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if _, err := d.fetchInto(ctx, url); err != nil {
				d.logf("refresh %s: %v", store.MaskURL(url), err)
			}
		}(s.URL)
	}
	wg.Wait()
	d.afterRefresh(ctx)
}

// afterRefresh reacts to refreshed lists: a stored selection with no loop
// running (its selector matched nothing) is retried; a running line whose raw
// text changed is reconnected; a vanished line is noted. Under connectMu: a
// control request must not slip in between reading the selection and acting.
func (d *Daemon) afterRefresh(ctx context.Context) {
	d.connectMu.Lock()
	defer d.connectMu.Unlock()
	d.mu.Lock()
	cur, live := d.cur, d.loopLive
	var stored *store.Selection
	if d.cfg != nil && d.cfg.Selection != nil {
		s := *d.cfg.Selection
		stored = &s
	}
	d.mu.Unlock()
	if stored == nil {
		return
	}
	if !live {
		d.startConnectLocked(*stored)
		return
	}
	if cur == nil {
		return
	}
	all, err := d.allEntries(ctx, stored.Subscription)
	if err != nil {
		return
	}
	for _, e := range all {
		if e.entry.ID != cur.entry.ID {
			continue
		}
		if e.entry.Raw != cur.entry.Raw {
			d.logf("%s: the line changed (room, key or server), reconnecting", e.entry.Label)
			d.startConnectLocked(*stored)
		}
		return
	}
	d.mu.Lock()
	d.lastErr = "the connected line is no longer in the list; still connected"
	d.mu.Unlock()
}
