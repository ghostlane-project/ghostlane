package daemon

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/ghostlane-project/ghostlane/cli/internal/engine/olcrtc"
	"github.com/ghostlane-project/ghostlane/cli/internal/engine/singbox"
	"github.com/ghostlane-project/ghostlane/cli/internal/links"
	"github.com/ghostlane-project/ghostlane/cli/internal/store"
)

var errNoSubscription = errors.New("no subscription holds that selector")

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
	d.cancel, d.loopDone = cancel, done
	d.sel = &sel
	d.state, d.lastErr = "connecting", ""
	d.mu.Unlock()
	go func() {
		defer close(done)
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
	for ctx.Err() == nil {
		entries, err := d.entriesFor(ctx, sel.Subscription, false)
		if err != nil {
			d.setState("failed", err.Error(), nil)
			if !sleepCtx(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, d.deps.RetryMax)
			continue
		}
		cands, err := links.Select(entries, sel.Selector)
		if err != nil {
			d.setState("failed", err.Error(), nil)
			return // a selector that matches nothing is not retried
		}
		cands = d.orderCandidates(sel, onlyOlcrtc(cands))
		if len(cands) == 0 {
			d.setState("failed", "no olcRTC line matches "+sel.Selector, nil)
			return
		}
		anyUp := false
		for i := range cands {
			cand := cands[i]
			if ctx.Err() != nil {
				return
			}
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

func onlyOlcrtc(in []links.Entry) []links.Entry {
	var out []links.Entry
	for _, e := range in {
		if e.Kind == links.KindOlcrtc {
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

func (d *Daemon) bringUp(ctx context.Context, e links.Entry, mode string) (*live, error) {
	port, err := olcrtc.FreePort()
	if err != nil {
		return nil, err
	}
	user, pass := olcrtc.RandomCredentials()
	eng, err := d.deps.StartEngine(ctx, olcrtc.Params{
		Line: *e.Olcrtc, SocksHost: "127.0.0.1", SocksPort: port, SocksUser: user, SocksPass: pass,
		DNS: olcrtc.HostResolvers(d.deps.ResolvConf), DirectRules: olcrtc.PrivateDirectRules, DeviceIDPath: d.deviceIDPath(),
		ReadyTimeout: d.deps.ReadyTimeout,
	})
	if err != nil {
		return nil, err
	}
	// From here on the engine's own credentials are the truth, not the ones
	// handed to it: the front and the probes talk to what it reports.
	user, pass = eng.Credentials()
	if err := d.confirm(ctx, eng); err != nil {
		_ = eng.Stop(5 * time.Second)
		return nil, err
	}
	if mode == "tun" {
		addrs, err := d.deps.Routes.GlobalAddresses()
		if err == nil {
			err = d.deps.Routes.Sync(addrs)
		}
		if err != nil {
			_ = eng.Stop(5 * time.Second)
			return nil, fmt.Errorf("policy rules: %w", err)
		}
	}
	d.mu.Lock()
	proxy := d.cfg.Proxy
	d.mu.Unlock()
	fp := singbox.FrontParams{Mode: singbox.Mode(mode), UpstreamAddr: eng.SocksAddr(), UpstreamUser: user, UpstreamPass: pass,
		ProxyListen: proxy.Listen, ProxyPort: proxy.Port, ProxyUser: proxy.User, ProxyPass: proxy.Pass,
		ExcludeUID: d.deps.UID, InterfaceName: singbox.TunName}
	front, err := d.deps.StartFront(ctx, fp)
	if err != nil {
		if mode == "tun" {
			_ = d.deps.Routes.Clear()
		}
		_ = eng.Stop(5 * time.Second)
		return nil, fmt.Errorf("front: %w", err)
	}
	return &live{entry: e, mode: mode, engine: eng, front: front, user: user, pass: pass}, nil
}

// confirm probes through the engine until it answers or ConfirmTimeout passes:
// WaitReady says the SOCKS listens, not that the room carries traffic.
func (d *Daemon) confirm(ctx context.Context, eng Engine) error {
	deadline := d.deps.Now().Add(d.deps.ConfirmTimeout)
	user, pass := eng.Credentials()
	for {
		last := d.deps.Probe(ctx, eng.SocksAddr(), user, pass)
		if last == nil {
			return nil
		}
		if ctx.Err() != nil || !d.deps.Now().Before(deadline) {
			return fmt.Errorf("room carries no traffic: %w", last)
		}
		if !sleepCtx(ctx, min(3*time.Second, d.deps.ConfirmTimeout/4)) {
			return ctx.Err()
		}
	}
}

func (d *Daemon) markUp(sel store.Selection, l *live) {
	d.mu.Lock()
	d.cur = l
	d.state, d.lastErr, d.since, d.pending = "up", "", d.deps.Now(), nil
	d.lastGood[lastGoodKey(sel)] = l.entry.ID
	lg := make(map[string]string, len(d.lastGood))
	for k, v := range d.lastGood {
		lg[k] = v
	}
	d.mu.Unlock()
	if err := d.saveLastGood(lg); err != nil {
		d.logf("last-good: %v", err)
	}
	d.logf("up: %s over %s", l.entry.Label, l.entry.Olcrtc.Provider)
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
			if st := l.engine.State(); st != "running" {
				return "engine " + st
			}
			if err := d.deps.Probe(ctx, l.engine.SocksAddr(), l.user, l.pass); err != nil {
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
	_ = l.engine.Stop(5 * time.Second)
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

func (d *Daemon) refresh(ctx context.Context, force bool) {
	d.mu.Lock()
	subs := append([]store.Subscription(nil), d.cfg.Subscriptions...)
	d.mu.Unlock()
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
		fresh, err := d.fetchInto(ctx, s.URL)
		if err != nil {
			d.logf("refresh %s: %v", store.MaskURL(s.URL), err)
			continue
		}
		d.afterRefresh(s.URL, links.Entries(s.URL, links.DecodeBody(fresh.Body)))
	}
}

func (d *Daemon) afterRefresh(subURL string, fresh []links.Entry) {
	// Under connectMu: a control request must not slip in between reading the
	// selection and restarting it.
	d.connectMu.Lock()
	defer d.connectMu.Unlock()
	d.mu.Lock()
	cur, sel := d.cur, d.sel
	d.mu.Unlock()
	if cur == nil || sel == nil || sel.Subscription != subURL {
		return
	}
	for _, e := range fresh {
		if e.ID != cur.entry.ID || e.Olcrtc == nil {
			continue
		}
		if e.Olcrtc.Room != cur.entry.Olcrtc.Room || e.Olcrtc.Key != cur.entry.Olcrtc.Key {
			d.logf("%s: room or key rotated, reconnecting", e.Label)
			d.startConnectLocked(*sel)
		}
		return
	}
	d.mu.Lock()
	d.lastErr = "the connected line is no longer in the list; still connected"
	d.mu.Unlock()
}
