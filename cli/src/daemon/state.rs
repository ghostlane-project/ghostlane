//! The daemon's state machine: what `Handle` does, how a line comes up, how
//! it is supervised, and how a refresh reacts. It is driven one step at a
//! time by `tick`, so the cases move time instead of waiting for it.

use std::net::IpAddr;

use gocompat::time::Timestamp;

use super::links::{self, Entry, FetchError, REFUSAL_NONE};
use super::{
    BringUp, BringUpStage, Daemon, FrontParams, Live, LoopState, OlcrtcParams, Phase, XrayParams,
};
use crate::ipc::{self, EntryView, ProxyView, Request, Response, Status, SubscriptionView};
use crate::store::{self, Cache, Headers, Selection, Subscription};

const INLINE: &str = "inline:";
const CRYPT1_LINK: &str = "olcrtc://crypt1/";

#[derive(Clone, Debug)]
struct Indexed {
    sub: String,
    entry: Entry,
}

fn entry_view(index: i64, entry: &Entry) -> EntryView {
    EntryView {
        index,
        id: entry.id.clone(),
        label: entry.label.clone(),
        country: entry.country.clone(),
        kind: entry.kind.as_str().to_string(),
        carrier: entry
            .olcrtc
            .as_ref()
            .map(|line| line.provider.clone())
            .unwrap_or_default(),
        transport: entry
            .olcrtc
            .as_ref()
            .map(|line| line.transport.clone())
            .unwrap_or_default(),
        problem: entry.problem.clone(),
    }
}

fn matches_sub(url: &str, needle: &str) -> bool {
    url == needle || url.starts_with(needle) || store::mask_url(url).starts_with(needle)
}

fn inline_body(url: &str) -> Option<&str> {
    url.strip_prefix(INLINE)
}

fn last_good_key(selection: &Selection) -> String {
    format!("{}|{}", selection.subscription, selection.selector)
}

impl Daemon {
    fn ensure_config(&mut self) {
        if !self.configured {
            // Loaded once, whichever of Run or Handle comes first.
            if let Ok(cfg) = store::load(&self.deps.config_path) {
                self.cfg = cfg;
            }
            self.configured = true;
        }
        if self.last_good.is_empty() {
            self.last_good = store::load_last_good(&self.deps.state_dir).unwrap_or_default();
        }
    }

    fn save_config(&self) {
        if let Err(err) = store::save(&self.deps.config_path, &self.cfg) {
            self.log(&format!("config: {err}"));
        }
    }

    fn save_last_good(&self) {
        if let Err(err) = store::save_last_good(&self.deps.state_dir, &self.last_good) {
            self.log(&format!("last-good: {err}"));
        }
    }

    fn decrypt(&self) -> links::DecryptRef<'_> {
        self.deps.decrypt.as_deref()
    }

    /// What the daemon does for one control request.
    pub(crate) fn dispatch(&mut self, request: Request) -> Response {
        self.ensure_config();
        match request.verb.as_str() {
            "version" => Response {
                ok: true,
                version: Some(self.deps.version.clone()),
                ..Response::default()
            },
            "status" => Response {
                ok: true,
                status: Some(self.make_status()),
                ..Response::default()
            },
            "list" => self.list(&request.subscription),
            "add" => self.add(&request.source),
            "remove" => self.remove(&request.subscription),
            "refresh" => {
                self.refresh(true);
                let message = with_refusals("refreshed", &self.refusals(""));
                Response {
                    ok: true,
                    message,
                    ..Response::default()
                }
            }
            "connect" => self.connect(&request),
            "disconnect" => {
                self.stop_all(true);
                self.save_config();
                Response {
                    ok: true,
                    message: "disconnected".to_string(),
                    ..Response::default()
                }
            }
            verb => ipc::fail("bad_verb", &format!("unknown verb {verb}")),
        }
    }

    pub(crate) fn make_status(&self) -> Status {
        let mut status = Status {
            state: self.state.clone(),
            last_error: self.last_err.clone(),
            ..Status::default()
        };
        if let Some(sel) = &self.sel {
            status.mode = sel.mode.clone();
            status.selector = sel.selector.clone();
            status.kill_switch = sel.kill_switch;
        }
        if let Some(live) = &self.cur {
            let mut view = entry_view(0, &live.entry);
            view.index = 0;
            status.line = Some(view);
            status.since = self.since.to_rfc3339();
            if live.mode == "proxy" {
                let address = if self.cfg.proxy.listen.contains(':') {
                    // an IPv6 listen is bracketed, as net.JoinHostPort writes it
                    format!("[{}]:{}", self.cfg.proxy.listen, self.cfg.proxy.port)
                } else {
                    format!("{}:{}", self.cfg.proxy.listen, self.cfg.proxy.port)
                };
                status.proxy = Some(ProxyView {
                    socks: address.clone(),
                    http: address,
                });
            }
        } else if let Some(pending) = &self.pending {
            let mut view = entry_view(0, pending);
            view.index = 0;
            status.line = Some(view);
        }
        for sub in &self.cfg.subscriptions {
            let mut view = SubscriptionView {
                url: store::mask_url(&sub.url),
                title: sub.title.clone(),
                interval_hours: sub.interval_hours,
                error: self.sub_err.get(&sub.url).cloned().unwrap_or_default(),
                ..SubscriptionView::default()
            };
            if inline_body(&sub.url).is_some() {
                view.url = "inline line".to_string();
            } else if let Ok(Some(cache)) = store::load_cache(&self.deps.state_dir, &sub.url) {
                view.fetched_at = cache.fetched_at.to_rfc3339();
                let hours = sub.interval_hours.max(1);
                view.next_refresh = Timestamp::from_unix(
                    cache.fetched_at.unix() + hours * 3600,
                    cache.fetched_at.subsec_nanos(),
                )
                .to_rfc3339();
                view.user_info = cache.headers.user_info.clone();
                let why = refusal_of(&cache);
                if !why.is_empty() && view.error.is_empty() {
                    view.error = format!("device refused: {why}");
                }
            }
            status.subscriptions.push(view);
        }
        status
    }

    fn list(&mut self, only: &str) -> Response {
        let entries = self.all_entries(only).unwrap_or_default();
        let views = entries
            .iter()
            .enumerate()
            .map(|(at, indexed)| entry_view(at as i64 + 1, &indexed.entry))
            .collect();
        Response {
            ok: true,
            message: self.refusals(only),
            entries: views,
            ..Response::default()
        }
    }

    fn refusals(&self, only: &str) -> String {
        let mut lines = Vec::new();
        for sub in &self.cfg.subscriptions {
            if inline_body(&sub.url).is_some() || (!only.is_empty() && !matches_sub(&sub.url, only))
            {
                continue;
            }
            if let Ok(Some(cache)) = store::load_cache(&self.deps.state_dir, &sub.url) {
                let why = refusal_of(&cache);
                if !why.is_empty() {
                    let title = if cache.headers.title.is_empty() {
                        store::mask_url(&sub.url)
                    } else {
                        cache.headers.title.clone()
                    };
                    lines.push(format!("{title}: device refused: {why}"));
                }
            }
        }
        lines.join("\n")
    }

    fn add(&mut self, source: &str) -> Response {
        let payload = links::import_payload(source);
        if let Some(blob) = payload.strip_prefix(CRYPT1_LINK) {
            let Some(decrypt) = self.decrypt() else {
                return ipc::fail(
                    "unsupported",
                    "olcrtc://crypt1 links need a build secret and are not supported by this version",
                );
            };
            let Some(plain) = decrypt(blob) else {
                return ipc::fail(
                    "bad_line",
                    "the crypt1 link does not verify (damaged, or made for another key)",
                );
            };
            let text = String::from_utf8_lossy(&plain).trim().to_string();
            if text.starts_with("https://") || text.starts_with("http://") {
                return self.add(&text);
            }
            return self.add_inline(&text, "crypt1 link");
        }
        if payload.starts_with("http://") {
            return ipc::fail(
                "insecure",
                "an http:// list carries its token in plaintext; use https://",
            );
        }
        if payload.starts_with("https://") {
            if self.cfg.subscriptions.iter().any(|s| s.url == payload) {
                return Response {
                    ok: true,
                    message: "already added".to_string(),
                    ..Response::default()
                };
            }
            self.cfg.subscriptions.push(Subscription {
                url: payload.to_string(),
                added_at: millis_to_time(self.now_ms()),
                ..Subscription::default()
            });
            let fetched = self.fetch_into(&payload);
            let cache = match fetched {
                Ok(cache) => cache,
                Err(err) => {
                    self.cfg.subscriptions.pop();
                    return ipc::fail("fetch", scrub_string(&err.to_string()).as_str());
                }
            };
            self.save_config();
            let title = if cache.headers.title.is_empty() {
                store::mask_url(&payload)
            } else {
                cache.headers.title.clone()
            };
            let why = refusal_of(&cache);
            if !why.is_empty() {
                return Response {
                    ok: true,
                    message: format!("{title}: device refused: {why}"),
                    ..Response::default()
                };
            }
            let entries = links::entries(
                &payload,
                &links::decode_body(&cache.body.unwrap_or_default(), self.decrypt()),
            );
            let countries: Vec<String> = links::group_by_country(&entries)
                .into_iter()
                .map(|(country, _)| country)
                .collect();
            let usable = entries.iter().filter(|e| e.connectable()).count();
            return Response {
                ok: true,
                message: format!(
                    "{title}: {} entries ({usable} usable now); countries: {}",
                    entries.len(),
                    countries.join(" ")
                ),
                ..Response::default()
            };
        }
        if payload.starts_with("olcrtc://")
            || payload.starts_with("vless://")
            || payload.starts_with("hysteria2://")
            || payload.starts_with("hy2://")
        {
            return self.add_inline(&payload, "");
        }
        let scheme = payload.split_once("://").map_or("", |(s, _)| s);
        ipc::fail(
            "unsupported",
            &format!("{scheme}:// is not supported by this version; add a list URL, or one olcrtc://, vless:// or hysteria2:// line"),
        )
    }

    fn add_inline(&mut self, text: &str, title: &str) -> Response {
        let lines = links::decode_body(text.as_bytes(), self.decrypt());
        if lines.is_empty() {
            return ipc::fail("bad_line", "no lines");
        }
        let url = format!("{INLINE}{text}");
        let entries = links::entries(&url, &lines);
        if entries.len() == 1 && !entries[0].problem.is_empty() {
            return ipc::fail("bad_line", &entries[0].problem);
        }
        if self.cfg.subscriptions.iter().any(|s| s.url == url) {
            return Response {
                ok: true,
                message: "already added".to_string(),
                ..Response::default()
            };
        }
        let title = if title.is_empty() {
            entries[0].label.clone()
        } else {
            title.to_string()
        };
        self.cfg.subscriptions.push(Subscription {
            url,
            title: title.clone(),
            added_at: millis_to_time(self.now_ms()),
            ..Subscription::default()
        });
        self.save_config();
        let message = if entries.len() == 1 {
            format!("added {}", entries[0].label)
        } else {
            let usable = entries.iter().filter(|e| e.connectable()).count();
            format!("{title}: {} entries ({usable} usable now)", entries.len())
        };
        Response {
            ok: true,
            message,
            ..Response::default()
        }
    }

    fn remove(&mut self, needle: &str) -> Response {
        let mut removed = 0;
        let mut drop_selection = false;
        let mut kept = Vec::new();
        for sub in &self.cfg.subscriptions {
            if matches_sub(&sub.url, needle) || sub.title == needle {
                removed += 1;
                if self
                    .sel
                    .as_ref()
                    .is_some_and(|sel| sel.subscription == sub.url)
                {
                    drop_selection = true;
                }
            } else {
                kept.push(sub.clone());
            }
        }
        if removed == 0 {
            return ipc::fail("no_match", &format!("no subscription matches {needle}"));
        }
        if drop_selection {
            self.stop_all(true);
        }
        self.cfg.subscriptions = kept;
        self.save_config();
        Response {
            ok: true,
            message: format!("removed {removed}"),
            ..Response::default()
        }
    }

    fn connect(&mut self, request: &Request) -> Response {
        let mode = if request.mode.is_empty() {
            "proxy"
        } else {
            request.mode.as_str()
        };
        if mode != "tun" && mode != "proxy" {
            return ipc::fail("bad_mode", "mode must be tun or proxy");
        }
        if self.cfg.subscriptions.is_empty() {
            return ipc::fail("no_subscription", "add a list first: ghostlane add <url>");
        }
        if mode == "proxy" && self.cfg.proxy.listen.parse::<IpAddr>().is_err() {
            return ipc::fail(
                "bad_listen",
                &format!(
                    "proxy.listen must be an IP address (127.0.0.1, ::1 or 0.0.0.0), not {}",
                    self.cfg.proxy.listen
                ),
            );
        }
        if request.kill_switch && mode != "tun" {
            return ipc::fail(
                "bad_mode",
                "the kill switch needs --tun: a proxy routes nothing to kill",
            );
        }
        let all = match self.all_entries(&request.subscription) {
            Ok(all) => all,
            Err(err) => return ipc::fail("no_subscription", &err.to_string()),
        };
        let flat: Vec<Entry> = all.iter().map(|i| i.entry.clone()).collect();
        if let Err(err) = links::select(&flat, &request.selector) {
            let message = with_refusals(&err.to_string(), &self.refusals(&request.subscription));
            if err.contains("no such location") {
                return ipc::fail("no_match", &message);
            }
            return ipc::fail("no_subscription", &message);
        }
        let selection = Selection {
            subscription: request.subscription.clone(),
            selector: request.selector.clone(),
            mode: mode.to_string(),
            kill_switch: request.kill_switch,
            ..Selection::default()
        };
        self.cfg.selection = Some(selection.clone());
        self.save_config();
        self.start_connect(selection);
        let mut message = format!("connecting to {} in {mode} mode", request.selector);
        if request.kill_switch {
            message.push_str(" with the kill switch");
        }
        Response {
            ok: true,
            message,
            ..Response::default()
        }
    }

    // ---- fetching and resolving ------------------------------------------------

    fn entries_for(&mut self, sub_url: &str, force: bool) -> Result<Vec<Entry>, FetchError> {
        if let Some(body) = inline_body(sub_url) {
            return Ok(links::entries(
                sub_url,
                &links::decode_body(body.as_bytes(), self.decrypt()),
            ));
        }
        let cache = store::load_cache(&self.deps.state_dir, sub_url)
            .ok()
            .flatten();
        let cache = match cache {
            Some(cache) if !force => cache,
            _ => self.fetch_into(sub_url)?,
        };
        Ok(links::entries(
            sub_url,
            &links::decode_body(&cache.body.unwrap_or_default(), self.decrypt()),
        ))
    }

    fn fetch_into(&mut self, sub_url: &str) -> Result<Cache, FetchError> {
        let mut headers = Headers::default();
        let device = self.device();
        let (body, error) = match (self.deps.fetch)(sub_url, device) {
            Ok((body, answer)) => {
                headers = answer;
                (Some(body), None)
            }
            Err(FetchError::Refused {
                kind,
                announce,
                headers: answer,
            }) => {
                self.log(&format!(
                    "list {}: device refused: {}",
                    store::mask_url(sub_url),
                    links::refusal_text(&kind, &announce)
                ));
                headers = *answer;
                headers.refusal = kind;
                headers.announce = announce;
                (None, None)
            }
            Err(err) => (None, Some(err)),
        };
        if let Some(err) = error {
            self.sub_err
                .insert(sub_url.to_string(), scrub_string(&err.to_string()));
            return Err(err);
        }
        self.sub_err.remove(sub_url);
        let cache = Cache {
            body,
            headers: headers.clone(),
            fetched_at: millis_to_time(self.now_ms()),
        };
        if let Err(err) = store::save_cache(&self.deps.state_dir, sub_url, &cache) {
            return Err(FetchError::Other(err.to_string()));
        }
        for sub in &mut self.cfg.subscriptions {
            if sub.url == sub_url {
                sub.title = headers.title.clone();
                sub.interval_hours = headers.update_interval_hours;
            }
        }
        self.save_config();
        Ok(cache)
    }

    fn all_entries(&mut self, only: &str) -> Result<Vec<Indexed>, FetchError> {
        let subs: Vec<Subscription> = self.cfg.subscriptions.clone();
        let mut out = Vec::new();
        let mut last_error = None;
        for sub in subs {
            if !only.is_empty() && !matches_sub(&sub.url, only) {
                continue;
            }
            match self.entries_for(&sub.url, false) {
                Ok(entries) => {
                    for entry in entries {
                        out.push(Indexed {
                            sub: sub.url.clone(),
                            entry,
                        });
                    }
                }
                Err(err) => {
                    self.log(&format!("list {}: {err}", store::mask_url(&sub.url)));
                    last_error = Some(err);
                }
            }
        }
        if out.is_empty() {
            if let Some(err) = last_error {
                return Err(err);
            }
        }
        Ok(out)
    }

    fn only_list_in(&self, scope: &str) -> String {
        let mut only = String::new();
        for sub in &self.cfg.subscriptions {
            if !scope.is_empty() && !matches_sub(&sub.url, scope) {
                continue;
            }
            if !only.is_empty() {
                return String::new();
            }
            only = sub.url.clone();
        }
        only
    }

    fn remember(&mut self, selection: &mut Selection, id: &str, sub: &str) {
        if selection.entry_id == id && selection.entry_subscription == sub {
            return;
        }
        selection.entry_id = id.to_string();
        selection.entry_subscription = sub.to_string();
        if let Some(stored) = &mut self.cfg.selection {
            if stored.selector == selection.selector
                && stored.subscription == selection.subscription
            {
                stored.entry_id = id.to_string();
                stored.entry_subscription = sub.to_string();
            }
        }
        self.save_config();
    }

    fn one_named<'a>(
        &self,
        all: &'a [Indexed],
        cands: &[&Entry],
        selector: &str,
    ) -> Option<&'a Indexed> {
        if cands.len() != 1 {
            return None;
        }
        let trimmed = selector.trim();
        if trimmed.parse::<usize>().is_err() && cands[0].label != trimmed {
            return None;
        }
        all.iter().find(|i| i.entry.id == cands[0].id)
    }

    fn in_its_list(
        &mut self,
        all: &[Indexed],
        selection: &mut Selection,
    ) -> Result<Vec<Entry>, String> {
        if selection.entry_subscription.is_empty() {
            selection.entry_subscription = self.only_list_in(&selection.subscription);
            if selection.entry_subscription.is_empty() {
                return Err(format!(
                    "no such location: the line {} named is no longer in its list; pick one from `ghostlane list`",
                    selection.selector
                ));
            }
        }
        if let Ok(Some(cache)) =
            store::load_cache(&self.deps.state_dir, &selection.entry_subscription)
        {
            let why = refusal_of(&cache);
            if !why.is_empty() {
                return Err(format!("device refused: {why}"));
            }
        }
        let mut candidates = Vec::new();
        if let Ok(index) = selection.selector.trim().parse::<usize>() {
            let found = all.get(index.wrapping_sub(1));
            match found {
                Some(found) if found.sub == selection.entry_subscription => {
                    candidates.push(found.entry.clone());
                }
                _ => {
                    return Err(format!(
                        "no such location: line {index} is no longer in its list; pick one from `ghostlane list`"
                    ));
                }
            }
        } else {
            let own: Vec<Entry> = all
                .iter()
                .filter(|i| i.sub == selection.entry_subscription)
                .map(|i| i.entry.clone())
                .collect();
            candidates = links::select(&own, &selection.selector)?
                .into_iter()
                .cloned()
                .collect();
        }
        if let Some(line) = self.one_named(
            all,
            &candidates.iter().collect::<Vec<_>>(),
            &selection.selector,
        ) {
            let (id, sub) = (line.entry.id.clone(), line.sub.clone());
            self.remember(selection, &id, &sub);
        }
        Ok(candidates)
    }

    fn resolve(
        &mut self,
        all: &[Indexed],
        selection: &mut Selection,
    ) -> Result<Vec<Entry>, String> {
        if !selection.entry_id.is_empty() {
            if let Some(found) = all.iter().find(|i| i.entry.id == selection.entry_id) {
                if selection.entry_subscription != found.sub {
                    let (id, sub) = (found.entry.id.clone(), found.sub.clone());
                    self.remember(selection, &id, &sub);
                }
                return Ok(vec![found.entry.clone()]);
            }
            return self.in_its_list(all, selection);
        }
        let flat: Vec<Entry> = all.iter().map(|i| i.entry.clone()).collect();
        let selected = links::select(&flat, &selection.selector)?;
        if let Some(line) = self.one_named(all, &selected, &selection.selector) {
            let (id, sub) = (line.entry.id.clone(), line.sub.clone());
            self.remember(selection, &id, &sub);
        }
        Ok(selected.into_iter().cloned().collect())
    }

    // ---- the connect loop -------------------------------------------------------

    fn start_connect(&mut self, selection: Selection) {
        let was_up = self.cur.is_some() || self.loop_.is_some();
        let selection = self.stop_for(selection, was_up);
        self.sel = Some(selection.clone());
        self.state = "connecting".to_string();
        self.last_err.clear();
        self.pending = None;
        self.begin_loop(selection);
    }

    /// Stop what is running. With a slow fake engine the teardown finishes at
    /// a later step; a connect that arrives in between is remembered, so the
    /// last caller wins and no engine is left behind.
    fn stop_for(&mut self, selection: Selection, was_up: bool) -> Selection {
        self.loop_ = None;
        if was_up && self.deps.stop_delay_ms > 0 {
            self.stop_until = Some(self.now_ms() + self.deps.stop_delay_ms);
            self.pending_start = Some(selection.clone());
            self.next_at = self.stop_until;
            return selection;
        }
        self.teardown_now();
        selection
    }

    fn begin_loop(&mut self, selection: Selection) {
        if selection.mode == "tun" && selection.kill_switch {
            if let Err(err) = self.install_kill_switch() {
                self.state = "failed".to_string();
                self.last_err = format!("kill switch: {err}");
                self.sel = Some(selection);
                self.log(&self.last_err.clone());
                return;
            }
        } else {
            self.remove_kill_switch();
        }
        let backoff = self.deps.retry_min_ms;
        self.loop_ = Some(LoopState {
            sel: selection,
            phase: Phase::Resolve,
            round: 0,
            backoff_ms: backoff,
            last_tried: String::new(),
            candidates: Vec::new(),
            at: 0,
            any_up: false,
            bringing: None,
        });
        self.state = "connecting".to_string();
        self.next_at = Some(self.now_ms());
    }

    fn install_kill_switch(&mut self) -> Result<(), String> {
        if self.ks_on {
            return Ok(());
        }
        (self.deps.routes).install_kill_switch(self.deps.uid)?;
        let addresses = (self.deps.routes).global_addresses()?;
        if let Err(err) = (self.deps.routes).sync(&addresses) {
            let _ = (self.deps.routes).remove_kill_switch();
            return Err(format!("policy rules: {err}"));
        }
        let id = self.next_watcher();
        let _ = (self.deps.routes).watch(id);
        self.ks_watcher = Some(id);
        self.ks_on = true;
        Ok(())
    }

    fn remove_kill_switch(&mut self) {
        let Some(watcher) = self.ks_watcher.take() else {
            return;
        };
        self.ks_on = false;
        let _ = (self.deps.routes).unwatch(watcher);
        if let Err(err) = (self.deps.routes).clear() {
            self.log(&format!("policy rules: {err}"));
        }
        if let Err(err) = (self.deps.routes).remove_kill_switch() {
            self.log(&format!("kill switch: {err}"));
        }
    }

    fn next_watcher(&mut self) -> usize {
        self.next_watcher += 1;
        self.next_watcher
    }

    pub(crate) fn stop_all(&mut self, forget: bool) {
        let selection = self.sel.take().unwrap_or_default();
        self.loop_ = None;
        self.next_at = None;
        if (self.cur.is_some() || self.ks_on) && self.deps.stop_delay_ms > 0 {
            self.stop_until = Some(self.now_ms() + self.deps.stop_delay_ms);
            self.pending_start = None;
            self.pending_forget = forget;
            self.next_at = self.stop_until;
            let _ = selection;
            return;
        }
        self.teardown_now();
        self.remove_kill_switch();
        if forget {
            self.cfg.selection = None;
            self.save_config();
        }
        self.state = "idle".to_string();
        self.sel = None;
        self.pending = None;
    }

    fn finish_stop(&mut self) {
        self.stop_until = None;
        self.teardown_now();
        self.remove_kill_switch();
        if self.pending_forget {
            self.pending_forget = false;
            self.cfg.selection = None;
            self.save_config();
            self.state = "idle".to_string();
            self.sel = None;
            self.pending = None;
            self.next_at = None;
            return;
        }
        if let Some(selection) = self.pending_start.take() {
            self.begin_loop(selection);
        } else {
            self.next_at = None;
        }
    }

    fn teardown_now(&mut self) {
        if let Some(live) = self.cur.take() {
            if let Some(watcher) = live.watcher {
                let _ = (self.deps.routes).unwatch(watcher);
            }
            if let Some(front) = live.front {
                let _ = front.close();
            }
            if let Some(engine) = live.engine {
                let _ = engine.stop();
            }
            if live.own_rules {
                let _ = (self.deps.routes).clear();
            }
        }
    }

    fn set_state(&mut self, state: &str, error: &str, pending: Option<&Entry>) {
        self.state = state.to_string();
        if !error.is_empty() {
            self.last_err = scrub_string(error);
        }
        self.pending = pending.cloned();
    }

    fn rotate_after(candidates: &[Entry], last: &str) -> Vec<Entry> {
        if let Some(at) = candidates.iter().position(|c| c.id == last) {
            let mut out = candidates[at + 1..].to_vec();
            out.extend_from_slice(&candidates[..=at]);
            return out;
        }
        candidates.to_vec()
    }

    fn order_by_last_good(&self, selection: &Selection, mut candidates: Vec<Entry>) -> Vec<Entry> {
        let id = self
            .last_good
            .get(&last_good_key(selection))
            .cloned()
            .unwrap_or_default();
        if let Some(at) = candidates.iter().position(|c| c.id == id) {
            if at > 0 {
                let first = candidates.remove(at);
                candidates.insert(0, first);
            }
        }
        candidates
    }

    /// One step. Every branch leaves `next_at` saying when to step again.
    pub(crate) fn step(&mut self) {
        let now = self.now_ms();
        if let Some(until) = self.stop_until {
            if now >= until {
                self.finish_stop();
            } else {
                self.next_at = Some(until);
            }
            return;
        }
        let Some(mut loop_state) = self.loop_.take() else {
            self.next_at = None;
            return;
        };
        match loop_state.phase.clone() {
            Phase::Resolve => {
                let selection = loop_state.sel.clone();
                match self.all_entries(&selection.subscription) {
                    Err(err) => {
                        self.set_state("failed", &err.to_string(), None);
                        let backoff = loop_state.backoff_ms;
                        self.schedule_backoff(&mut loop_state, now, backoff);
                    }
                    Ok(all) => {
                        let mut selection = loop_state.sel.clone();
                        match self.resolve(&all, &mut selection) {
                            Err(err) => {
                                // Nothing matches today. The next refresh that
                                // changes a list starts the loop again.
                                self.set_state("failed", &err, None);
                                self.next_at = None;
                                return;
                            }
                            Ok(candidates) => {
                                loop_state.sel = selection;
                                let candidates: Vec<Entry> =
                                    candidates.into_iter().filter(|e| e.connectable()).collect();
                                if candidates.is_empty() {
                                    let why = format!(
                                        "no connectable line matches {}",
                                        loop_state.sel.selector
                                    );
                                    self.set_state("failed", &why, None);
                                    self.next_at = None;
                                    return;
                                }
                                loop_state.any_up = false;
                                loop_state.candidates = if loop_state.round == 0 {
                                    self.order_by_last_good(&loop_state.sel, candidates)
                                } else {
                                    Self::rotate_after(&candidates, &loop_state.last_tried)
                                };
                                loop_state.round += 1;
                                loop_state.at = 0;
                                loop_state.phase = Phase::TryNext;
                                self.next_at = Some(now);
                            }
                        }
                    }
                }
            }
            Phase::TryNext => {
                if loop_state.at >= loop_state.candidates.len() {
                    if loop_state.any_up {
                        loop_state.phase = Phase::Resolve;
                        self.next_at = Some(now);
                    } else {
                        self.set_state("failed", "", None);
                        let backoff = loop_state.backoff_ms;
                        self.schedule_backoff(&mut loop_state, now, backoff);
                    }
                } else {
                    let entry = loop_state.candidates[loop_state.at].clone();
                    loop_state.last_tried = entry.id.clone();
                    self.set_state("connecting", "", Some(&entry));
                    loop_state.bringing = Some(self.new_bring_up(entry, &loop_state.sel.mode));
                    loop_state.phase = Phase::Confirm;
                    self.next_at = Some(now);
                }
            }
            Phase::Confirm => {
                let result =
                    self.bring_up_step(loop_state.bringing.as_mut().expect("a bring-up"), now);
                match result {
                    Ok(None) => {
                        self.next_at = Some(
                            loop_state
                                .bringing
                                .as_ref()
                                .expect("a bring-up")
                                .retry_at
                                .max(now),
                        );
                    }
                    Ok(Some(live)) => {
                        let selection = loop_state.sel.clone();
                        self.mark_up(&selection, live);
                        loop_state.any_up = true;
                        loop_state.bringing = None;
                        loop_state.phase = Phase::Up;
                        self.next_at = Some(now + self.deps.probe_interval_ms);
                    }
                    Err(why) => {
                        let bringing = loop_state.bringing.as_mut().expect("a bring-up");
                        if let Some(front) = bringing.front_obj.take() {
                            let _ = front.close();
                        }
                        if bringing.own_rules {
                            let _ = (self.deps.routes).clear();
                        }
                        if let Some(engine) = bringing.engine_obj.take() {
                            let _ = engine.stop();
                        }
                        self.log(&format!("{}: {why}", bringing.entry.label));
                        self.set_state("connecting", &why, Some(&bringing.entry));
                        loop_state.bringing = None;
                        loop_state.at += 1;
                        loop_state.phase = Phase::TryNext;
                        self.next_at = Some(now);
                    }
                }
            }
            Phase::Up => {
                let reason = self.supervise_step(now);
                if let Some(reason) = reason {
                    if let Some(live) = self.cur.take() {
                        if let Some(watcher) = live.watcher {
                            let _ = (self.deps.routes).unwatch(watcher);
                        }
                        if let Some(front) = live.front {
                            let _ = front.close();
                        }
                        if let Some(engine) = live.engine {
                            let _ = engine.stop();
                        }
                        if live.own_rules {
                            let _ = (self.deps.routes).clear();
                        }
                        let label = live.entry.label.clone();
                        self.log(&format!("{label}: {reason}, moving on"));
                    }
                    loop_state.phase = Phase::TryNext;
                    loop_state.at += 1;
                    self.next_at = Some(now);
                } else {
                    self.next_at = Some(now + self.deps.probe_interval_ms);
                }
            }
            Phase::Backoff(until) => {
                if now >= until {
                    loop_state.phase = Phase::Resolve;
                    self.next_at = Some(now);
                } else {
                    self.next_at = Some(until);
                }
            }
        }
        self.loop_ = Some(loop_state);
    }

    fn schedule_backoff(&mut self, loop_state: &mut LoopState, now: i64, backoff: i64) {
        loop_state.phase = Phase::Backoff(now + backoff);
        loop_state.backoff_ms = (backoff * 2).min(self.deps.retry_max_ms);
        self.next_at = Some(now + backoff);
    }

    fn new_bring_up(&self, entry: Entry, mode: &str) -> BringUp {
        BringUp {
            entry,
            mode: mode.to_string(),
            stage: BringUpStage::Start,
            engine_obj: None,
            front_obj: None,
            probe_addr: String::new(),
            probe_user: String::new(),
            probe_pass: String::new(),
            own_rules: false,
            deadline: self.now_ms() + self.deps.confirm_timeout_ms,
            retry_at: self.now_ms(),
        }
    }

    /// One stage of bringing a line up. `Ok(None)` means a probe did not
    /// answer yet and a later step must try again.
    fn bring_up_step(&mut self, bringing: &mut BringUp, now: i64) -> Result<Option<Live>, String> {
        let stage = bringing.stage.clone();
        match stage {
            BringUpStage::Start => {
                if let Some(room) = &bringing.entry.olcrtc {
                    let params = OlcrtcParams {
                        line: room.clone(),
                        socks_port: 30_000,
                        device_id_path: self.deps.state_dir.join("device-id"),
                        device_id: self.dev_id.clone().unwrap_or_default(),
                    };
                    bringing.engine_obj = Some((self.deps.start_engine)(&params)?);
                    bringing.stage = BringUpStage::ConfirmEngine;
                } else if let Some(vless) = &bringing.entry.vless {
                    if vless.transport == "xhttp" {
                        bringing.engine_obj = Some((self.deps.start_xray)(&XrayParams {
                            line: vless.clone(),
                            raw: bringing.entry.raw.clone(),
                        })?);
                        bringing.stage = BringUpStage::ConfirmEngine;
                    } else if bringing.mode == "tun" {
                        bringing.stage = BringUpStage::Preflight;
                    } else {
                        bringing.stage = BringUpStage::ProbeInbound;
                    }
                } else if bringing.entry.hy2.is_some() {
                    if bringing.mode == "tun" {
                        bringing.stage = BringUpStage::Preflight;
                    } else {
                        bringing.stage = BringUpStage::ProbeInbound;
                    }
                } else {
                    return Err("this line cannot be connected by this version".to_string());
                }
                Ok(None)
            }
            BringUpStage::ConfirmEngine => {
                let engine = bringing.engine_obj.as_ref().expect("an engine");
                let (user, pass) = engine.credentials();
                let address = engine.socks_addr();
                if let Some(when) = self.confirm(&address, &user, &pass, bringing, now)? {
                    bringing.retry_at = when;
                    return Ok(None);
                }
                bringing.stage = BringUpStage::SyncRules;
                Ok(None)
            }
            BringUpStage::Preflight => {
                if bringing.front_obj.is_none() {
                    let (probe_addr, probe_user, probe_pass) = (self.deps.probe_inbound)()?;
                    let upstream = self.upstream_kind(&bringing.entry);
                    let front = (self.deps.start_front)(&FrontParams {
                        mode: "proxy".to_string(),
                        upstream,
                        proxy_listen: "127.0.0.1".to_string(),
                        proxy_port: 30_001,
                        probe_listen: probe_addr.clone(),
                        probe_user: probe_user.clone(),
                        probe_pass: probe_pass.clone(),
                        line: bringing.entry.raw.clone(),
                        proxy_user: String::new(),
                        proxy_pass: String::new(),
                        preflight: true,
                        upstream_addr: String::new(),
                        upstream_user: String::new(),
                        upstream_pass: String::new(),
                    })?;
                    bringing.front_obj = Some(front);
                    bringing.probe_addr = probe_addr;
                    bringing.probe_user = probe_user;
                    bringing.probe_pass = probe_pass;
                }
                let address = bringing.probe_addr.clone();
                let user = bringing.probe_user.clone();
                let pass = bringing.probe_pass.clone();
                if let Some(when) = self.confirm(&address, &user, &pass, bringing, now)? {
                    bringing.retry_at = when;
                    return Ok(None);
                }
                if let Some(front) = bringing.front_obj.take() {
                    let _ = front.close();
                }
                bringing.probe_addr.clear();
                bringing.stage = BringUpStage::SyncRules;
                Ok(None)
            }
            BringUpStage::SyncRules => {
                if bringing.mode == "tun" && !self.kill_switch_on() {
                    let addresses = (self.deps.routes).global_addresses()?;
                    (self.deps.routes).sync(&addresses)?;
                    bringing.own_rules = true;
                }
                bringing.stage = BringUpStage::ProbeInbound;
                Ok(None)
            }
            BringUpStage::ProbeInbound => {
                match (self.deps.probe_inbound)() {
                    Ok((addr, user, pass)) => {
                        bringing.probe_addr = addr;
                        bringing.probe_user = user;
                        bringing.probe_pass = pass;
                    }
                    Err(err) => return Err(err),
                }
                bringing.stage = BringUpStage::StartFront;
                Ok(None)
            }
            BringUpStage::StartFront => {
                let upstream = if bringing.engine_obj.is_some() {
                    "socks".to_string()
                } else {
                    self.upstream_kind(&bringing.entry)
                };
                let (upstream_addr, upstream_user, upstream_pass) = match &bringing.engine_obj {
                    Some(engine) => {
                        let (user, pass) = engine.credentials();
                        (engine.socks_addr(), user, pass)
                    }
                    None => (String::new(), String::new(), String::new()),
                };
                let result = (self.deps.start_front)(&FrontParams {
                    mode: bringing.mode.clone(),
                    upstream,
                    proxy_listen: self.cfg.proxy.listen.clone(),
                    proxy_port: self.cfg.proxy.port as u16,
                    probe_listen: bringing.probe_addr.clone(),
                    probe_user: bringing.probe_user.clone(),
                    probe_pass: bringing.probe_pass.clone(),
                    line: bringing.entry.raw.clone(),
                    proxy_user: self.cfg.proxy.user.clone(),
                    proxy_pass: self.cfg.proxy.pass.clone(),
                    preflight: false,
                    upstream_addr,
                    upstream_user,
                    upstream_pass,
                });
                match result {
                    Ok(front) => bringing.front_obj = Some(front),
                    Err(err) => return Err(format!("front: {err}")),
                }
                bringing.stage = BringUpStage::ConfirmFront;
                Ok(None)
            }
            BringUpStage::ConfirmFront => {
                if let Some(when) = self.confirm(
                    &bringing.probe_addr,
                    &bringing.probe_user,
                    &bringing.probe_pass,
                    bringing,
                    now,
                )? {
                    bringing.retry_at = when;
                    return Ok(None);
                }
                let watcher = if bringing.own_rules {
                    let id = self.next_watcher();
                    let _ = (self.deps.routes).watch(id);
                    Some(id)
                } else {
                    None
                };
                Ok(Some(Live {
                    entry: bringing.entry.clone(),
                    mode: bringing.mode.clone(),
                    probe_addr: bringing.probe_addr.clone(),
                    probe_user: bringing.probe_user.clone(),
                    probe_pass: bringing.probe_pass.clone(),
                    own_rules: bringing.own_rules,
                    watcher,
                    engine: bringing.engine_obj.take(),
                    front: bringing.front_obj.take(),
                }))
            }
        }
    }

    fn upstream_kind(&self, entry: &Entry) -> String {
        if entry.vless.is_some() {
            "vless".to_string()
        } else if entry.hy2.is_some() {
            "hy2".to_string()
        } else {
            "socks".to_string()
        }
    }

    /// A probe through a SOCKS address, retried within the confirmation
    /// deadline. `Some(when)` says when the daemon should step again.
    fn confirm(
        &mut self,
        address: &str,
        user: &str,
        pass: &str,
        bringing: &BringUp,
        now: i64,
    ) -> Result<Option<i64>, String> {
        match (self.deps.probe)(address, user, pass) {
            Ok(()) => Ok(None),
            Err(err) => {
                if now >= bringing.deadline {
                    return Err(format!("no traffic through the line: {err}"));
                }
                Ok(Some(now + (self.deps.confirm_timeout_ms / 4).min(3_000)))
            }
        }
    }

    fn mark_up(&mut self, selection: &Selection, live: Live) {
        let note = format!("up: {} ({})", live.entry.label, live.entry.kind.as_str());
        self.last_good
            .insert(last_good_key(selection), live.entry.id.clone());
        self.save_last_good();
        self.cur = Some(live);
        self.state = "up".to_string();
        self.last_err.clear();
        self.since = millis_to_time(self.now_ms());
        self.pending = None;
        self.probe_failures = 0;
        self.log(&note);
    }

    fn supervise_step(&mut self, now: i64) -> Option<String> {
        let Some(live) = self.cur.as_ref() else {
            return Some("stopped".to_string());
        };
        if let Some(engine) = &live.engine {
            let state = engine.state();
            if state != "running" {
                return Some(format!("engine {state}"));
            }
        }
        let address = live.probe_addr.clone();
        let user = live.probe_user.clone();
        let pass = live.probe_pass.clone();
        let label = live.entry.label.clone();
        let failures = self.probe_failures;
        match (self.deps.probe)(&address, &user, &pass) {
            Ok(()) => {
                self.probe_failures = 0;
                None
            }
            Err(err) => {
                let failures = failures + 1;
                self.probe_failures = failures;
                self.log(&format!(
                    "probe {failures}/{} failed: {err}",
                    self.deps.probe_failures
                ));
                if failures >= self.deps.probe_failures {
                    self.probe_failures = 0;
                    Some(format!("{failures} probes failed"))
                } else {
                    let _ = now;
                    let _ = label;
                    None
                }
            }
        }
    }

    // ---- refreshing -------------------------------------------------------------

    fn refresh(&mut self, force: bool) {
        let subs: Vec<Subscription> = self.cfg.subscriptions.clone();
        let mut longest = 0_i64;
        for sub in &subs {
            if inline_body(&sub.url).is_some() {
                continue;
            }
            let cache = store::load_cache(&self.deps.state_dir, &sub.url)
                .ok()
                .flatten();
            let hours = if sub.interval_hours <= 0 {
                24
            } else {
                sub.interval_hours
            };
            let due = force
                || cache.is_none()
                || self.now_ms()
                    >= cache.expect("a cache").fetched_at.unix() * 1000 + hours * 3_600_000;
            if !due {
                continue;
            }
            longest = longest.max(self.deps.fetch_delay_ms);
            if let Err(err) = self.fetch_into(&sub.url) {
                self.log(&format!("refresh {}: {err}", store::mask_url(&sub.url)));
            }
        }
        // Four fetches of the same delay overlap: one wait, not four.
        if longest > 0 {
            (self.deps.advance)(longest);
        }
        self.after_refresh();
    }

    fn after_refresh(&mut self) {
        let Some(stored) = self.cfg.selection.clone() else {
            return;
        };
        if self.loop_.is_none() {
            self.start_connect(stored);
            return;
        }
        let Some(live) = self.cur.as_ref() else {
            return;
        };
        let id = live.entry.id.clone();
        let raw = live.entry.raw.clone();
        let all = match self.all_entries(&stored.subscription) {
            Ok(all) => all,
            Err(_) => return,
        };
        if let Some(found) = all.iter().find(|i| i.entry.id == id) {
            if found.entry.raw != raw {
                self.log(&format!(
                    "{}: the line changed (room, key or server), reconnecting",
                    found.entry.label
                ));
                self.start_connect(stored);
            }
        } else {
            self.last_err =
                "the connected line is no longer in the list; still connected".to_string();
        }
    }

    pub(crate) fn start_run(&mut self) {
        self.run_active = true;
        self.ensure_config();
        if let Err(err) = (self.deps.routes).cleanup_stale() {
            self.log(&format!("stale rules: {err}"));
        }
        let _ = self.device();
        let stored = self.cfg.selection.clone();
        if let Some(selection) = stored {
            if self.loop_.is_none() && self.cur.is_none() {
                self.start_connect(selection);
            }
        }
    }
}

fn refusal_of(cache: &Cache) -> String {
    if cache.headers.refusal == REFUSAL_NONE {
        return String::new();
    }
    links::refusal_text(&cache.headers.refusal, &cache.headers.announce)
}

fn with_refusals(message: &str, refusals: &str) -> String {
    if refusals.is_empty() {
        message.to_string()
    } else {
        format!("{message}\n{refusals}")
    }
}

fn scrub_string(text: &str) -> String {
    super::scrub::scrub(text)
}

fn millis_to_time(at: i64) -> Timestamp {
    Timestamp::from_unix(at / 1000, ((at % 1000) * 1_000_000) as u32)
}
