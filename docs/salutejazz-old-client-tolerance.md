# SaluteJazz line vs. already-released clients

Scope: what a client build at `v1.0.438` — the newest release tag as of this
note (App Store / TestFlight / Play, i.e. every install in the field before
this rollout ships) — does when its subscription body gains a third
`olcrtc://salutejazz?...` line alongside the existing Telemost and WB Stream
ones. Every citation below is `git show v1.0.438:<path>` — the shipped source,
not this branch's fix. No build, deploy or connectivity test was run; this is
a static trace of already-released code.

## Verdict

```
old_client_tolerance = breaks
```

An old client does not drop the new line and does not leave it alone: it
imports it as a second, fully usable list entry that **presents itself as WB
Stream** (`LocationConfig.kt:154-162`, `LocationConfig.kt:187-194`) while
actually holding SaluteJazz's identifier and key. Selecting that entry — by
hand, or by the client's own opt-in fallback logic reaching it — dials the WB
Stream service with a reference it never issued, which cannot produce a
working tunnel. Per the brief's own definition this is the "second WB entry
that fails on connect" case and counts as `breaks`, whether reached by a tap
or by the fallback logic. Because a SaluteJazz identifier on the WB Stream
transport is exactly the "second WB entry" shape and the app's own list and
fallback logic can both land a user on it (see below), the flip-order rule —
**an app release that carries this note's fix must ship before
`OLCRTC_SALUTEJAZZ_ORIGINS` names any origin whose users can still run an old
client** — is MANDATORY, not advisory. A DE-only canary does not avoid this:
see "Does a DE-only canary limit exposure?" below — the answer is no, it
reaches every old install, not just DE traffic.

## The trace

### 1. Parsing does not look at the provider token

`LocationsDatasource.parseOlcRtcUri` (`LocationsDatasource.kt:1245-1301`)
slices a line purely on the position of `?`, `@`, `#`, `%` and `$`
(`LocationsDatasource.kt:1248-1264`); the text before the first `?` becomes
the provider string verbatim (`LocationsDatasource.kt:1266`) and is handed
straight into a `LocationConfig` (`LocationsDatasource.kt:1277-1289`). Nothing
here checks the provider token against a known set, and nothing throws:
`salutejazz` rides through exactly like `telemost` or `wbstream` would. This
confirms Task 1's read of the same function — the line format itself is
provider-agnostic.

### 2. `normalized()` silently rewrites the provider

Still inside `parseOlcRtcUri`, the freshly built config is immediately
normalized (`LocationsDatasource.kt:1289` calling `LocationConfig.kt:46-62`).
`normalized()` calls `normalizeProvider(bypassProvider)`
(`LocationConfig.kt:47`), which at `v1.0.438` is a closed allowlist:

```
LocationConfig.kt:154-162
fun normalizeProvider(value: String): String {
    return when (value.trim().lowercase()) {
        PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
        PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
        PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
        PROVIDER_JITSI, "jitsi-meet", "jitsi_meet", "meet" -> PROVIDER_JITSI
        else -> DEFAULT_BYPASS_PROVIDER
    }
}
```

`"salutejazz"` matches none of the four branches, so it falls to
`else -> DEFAULT_BYPASS_PROVIDER`, and `DEFAULT_BYPASS_PROVIDER` is
`PROVIDER_WB_STREAM` (`LocationConfig.kt:121,123`). This happens at import
time, before the entry is ever stored — nothing downstream retains a record
that the line originally said `salutejazz`. This is exactly the bug Task 1
found and fixed on this branch; this note confirms it is genuinely present in
the shipped `v1.0.438` source, not just inferred from the fix.

### 3. The entry is neither hidden nor dropped — it is shown

`LocationConfig.isComplete()` for an olcRTC entry only requires a non-blank
identifier and a non-blank key (`LocationConfig.kt:70-74`); both are present
in a SaluteJazz line, so `parseOlcRtcUri`'s own completeness gate
(`LocationsDatasource.kt:1299`) passes and a full `LocationEntry` is built and
stored (`LocationsDatasource.kt:1214-1237`). The list-building code
(`LocationSelection.kt`, e.g. the grouping/sorting at `LocationSelection.kt:163-187`)
applies no provider allowlist of its own — it treats every complete entry the
same regardless of provider. So the entry is not hidden and the line is not
ignored: it becomes a second, ordinary, selectable row.

What the row shows is where the mislabeling becomes visible in two different,
contradictory ways at once:

- Its **name** (`LocationConfig.kt:88`) comes from whatever text follows the
  line's trailing `$` marker, untouched by provider normalization — so the
  title itself need not say "WB" at all (the brief's own example line keeps
  an "SJ" marker there).
- Its **service badge** (`providerName()`, `LocationConfig.kt:90`, driven by
  `providerDisplayName()` at `LocationConfig.kt:187-194`) always reads **"WB
  Stream"**, because that function re-normalizes the already-collapsed
  provider — identical to a genuine WB Stream row.

So an old client shows a row whose title may hint at something new and whose
service label flatly says WB Stream. Nothing about the presentation warns
that connecting it will not reach WB Stream's service, or SaluteJazz's.

### 4. The connect path forwards the mislabeled value verbatim, on every platform

No platform surface re-parses or re-validates the provider before dialing.
Every one takes the value `normalizeProvider` already produced and forwards
it as an opaque string:

- **Desktop** — `OlcRtcCommand.desktopProviderArg` (`OlcRtcCommand.kt:62-69`)
  calls `normalizeProvider` again (idempotent — already `"wbstream"`) and
  writes it straight into the engine's YAML: `auth: provider: 'wbstream'`
  alongside the identifier and key taken from the SaluteJazz line
  (`OlcRtcCommand.kt:19-32`).
- **iOS** — `IosVpnManager` builds `IosOlcRtcStartRequest` with
  `carrierName = config.bypassProvider` (`IosVpnManager.kt:897-899`). That
  request crosses the app-process/Network-Extension boundary as JSON built at
  `OlcboxIosApp.swift:710-729`, with `"carrierName": request.carrierName` at
  line 713 — a field-for-field encode, not a re-validation. On the extension
  side, `OlcrtcEngine.launch` decodes it into `Parameters.carrierName`
  (`OlcrtcEngine.swift:21`) and calls `runtime.setProvider(parameters.carrierName)`
  directly (`OlcrtcEngine.swift:195`); the same pattern appears in
  `SwiftOlcRtcManager.swift:56,120,147`. No Swift-side allowlist exists
  anywhere in this path.
- **Android** — `OlcboxVpnService.startMobile` calls
  `olcrtc.setProvider(config.bypassProvider)` directly
  (`OlcboxVpnService.kt:889-897`). The one provider-keyed branch nearby
  (`OlcboxVpnService.kt:946`) only matches `PROVIDER_JITSI` — a wait/cleanup
  helper scoped to Jitsi's own teardown timing — and is a harmless no-op
  here, not a gate on anything else.

So: shared code (the parser and `normalizeProvider`) does the actual
mislabeling, and it does so once, before any platform-specific code runs.
Neither the iOS extension nor the Android service, nor the desktop command
builder, re-parses the line or second-guesses the provider string they are
handed — they trust it completely.

### 5. What happens when it is dialed: fails, does not crash

A SaluteJazz identifier and key were never issued by WB Stream's own
provisioning path — a different platform-side flow mints them for a
different transport entirely. The client-side olcRTC runtime itself is a
prebuilt binary pulled by version pin (`scripts/cores-pins.sh`'s
`OLCRTC_VERSION`); its source is not in this repository, so this note cannot
cite an exact internal failure point the way it can for the client. What is
verifiable from this repo is the shape of the failure at the call site: on
every platform the actual start/ready calls are wrapped in error handling
that turns a rejection into an ordinary reported failure, not a crash —
`OlcrtcEngine.swift:211-224` catches `runtime.start()`/`runtime.waitReady()`
and turns each into a typed error with a plain message
("olcRTC would not start" / "olcRTC did not become ready"), and
`OlcboxVpnService.kt`'s `startMobile` wraps the equivalent Android calls in a
`catch (e: Exception)` that logs and reports a failure rather than
propagating a crash. So the practical outcome across all three platforms is
the same: the connection attempt ends in a visible, generic failure (no
tunnel comes up), not a hang and not a crash. The failure text itself is
provider-agnostic (`OlcrtcFailure.kt`, `OlcrtcStatus.kt` carry no
provider-specific messaging), so nothing in the UI hints at *why* — the user
just sees an ordinary failed-connection state on what looked like a normal
WB Stream row.

### 6. Does the app auto-pick this row, or only on a tap?

Both, but the automatic path is opt-in and, on inspection, mostly steers
around the new row rather than into it.

**Default (no opt-in): tap only.** The main connect action
(`HomeScreenModel.kt:386-417`) starts whatever is already the active entry
for the current subscription; it does not scan for or prefer a newly
appeared line. A fresh or refreshed subscription import keeps the first
parsed line as its default active entry (`LocationsDatasource.kt:1240`,
`entries.firstOrNull()`), and SaluteJazz is a third, appended line — first
remains whichever entry led the body before (Telemost, per the platform's
existing WB rollout convention), so nothing about adding the line changes
what a returning user's app auto-starts. The only way the mislabeled row
becomes active is a user manually selecting it from the list — plausible
precisely because its title can look new/interesting even though its badge
says WB Stream — or the fallback path below.

**Opt-in fallback ("Lowest"):** off by default per subscription
(`SubscriptionSettings.kt:39-40`, `autoSelectLowest = false`) and only
engaged when the active subscription has it enabled
(`SubscriptionSettings.kt:87-91`, checked at `HomeScreenModel.kt:412-417`
before calling `startLowest()`). Where it is enabled, its candidate ranking
(`LowestConnection.kt:118-142`) explicitly excludes every olcRTC-kind entry
from its ping-based ordering (`LowestConnection.kt:126`,
`entries.filter { it.location.kind != LocationKind.Olcrtc }`) — olcRTC
carriers, including both the real WB Stream row and the mislabeled one, are
never preferred by a latency signal. Unmeasured entries are ordered by tie
(`LowestConnection.kt:140-141`), which preserves their original list order —
and since the SaluteJazz line is appended after the existing carriers, it
sorts behind the genuine Telemost and WB Stream rows for anyone who was
already connecting successfully. The fallback tries up to three candidates
per run (`LowestConnection.kt:59,67,150-152`, `MAX_ATTEMPTS = 3`); the
mislabeled row is only ever reached if *both* real carriers already failed
first in the same run — a state in which the pre-existing two-carrier client
already had nothing left to fall back to either. The one real regression is
cost, not outcome: reaching it burns one of the three attempts and up to a
90-second establishment timeout (`LowestConnection.kt:154`,
`CONNECT_TIMEOUT_MS`) on a guaranteed failure before the fallback gives up
and reports "Lowest could not connect" (`LowestConnection.kt:106`) — the same
message a pre-SaluteJazz client would already show in that situation, just
slower to arrive.

So auto-selection is not the primary way a user lands on the broken row —
manual selection is — but it is not risk-free either: it is one guaranteed
failed attempt sitting at the back of an already-degraded fallback chain.

### 7. Does a DE-only canary limit exposure?

No. Parsing is unconditional over every `olcrtc://` line present in a fetched
subscription body (`LocationsDatasource.kt:1177-1206` loops over all lines
with no per-origin or per-country filter), and subscriptions refresh
automatically by default (`SubscriptionSettings.kt:47-48`,
`autoUpdate = true`, wired to a periodic re-fetch in
`HomeScreenModel.kt:632-634`). Because the DE origin is present in every
user's subscription body regardless of which origin they actually connect
through, turning `OLCRTC_SALUTEJAZZ_ORIGINS` on for DE alone — the smallest,
most conservative canary available — is enough to deliver the mislabeled
third line to every old-client install on its very next refresh, not only to
users who pick DE as an exit. There is no partial-exposure canary available
under the current subscription shape: any non-empty value in
`OLCRTC_SALUTEJAZZ_ORIGINS` reaches the whole old-client install base. The
flip-order rule therefore has to be read literally as "before it lists any
origin at all," with no carve-out for a small or supposedly-contained first
step.

## `PROVIDER_JAZZ`: live, not dead

`PROVIDER_JAZZ = "jazz"` (`LocationConfig.kt:119`) is referenced by released,
reachable code — it is not dead vocabulary:

- It is one of exactly three choices — Jazz, Telemost, WB Stream — in the
  manual "add a location by hand" provider picker; Jitsi is the only option
  filtered out there (`LocationSettingsScreen.kt:343-350`,
  `.filterNot { it == LocationConfig.PROVIDER_JITSI }`). Picking "Jazz"
  (its display label, `LocationConfig.kt:189`) shows its own distinct
  placeholder hint text (`LocationSettingsScreen.kt:593`) — a real,
  user-visible string in the shipped app, reachable without any subscription
  or server involvement at all.
- It is exercised by a genuine legacy-storage migration path, not just a
  parser fixture: `LocationsRepositoryImplTest.kt:107-122`
  (`migratesLegacyLocationsAndPreservesActiveSelection`) feeds the migration
  code a persisted record with `"provider":"jazz"` (line 111) alongside a
  `wbstream`-shaped one, and the migration reads both. A current-format
  `olcrtc://jazz?...` line is also covered directly
  (`LocationsRepositoryImplTest.kt:296`,
  `supportedTransportsForProvider(PROVIDER_JAZZ)` at lines 504-508).

None of this means the platform side currently emits a `jazz` line to real
subscriptions — that is outside this repository and this note does not claim
to have checked it. What this note does establish is narrower and enough to
act on: inside the shipped client, `jazz` is a value real, still-supported
code paths recognize by name (a picker option with its own label and hint
text, and a migration path for a persisted record shaped that way) — not a
constant nothing reads. Removing it without checking for on-device state
first would revive the same collapse-to-WB-Stream failure this note
describes, for a different provider: `normalizeProvider` has exactly one
`else`, and it already goes to WB Stream.

## What this note does not cover

The olcRTC engine's own source is not in this repository (it is pulled as a
prebuilt binary by version pin); this note describes what the client sends
it and how the client surfaces the result, not the engine's internal
handling of a service identifier that was never issued for that service.
Nothing found in this trace suggests the engine call sites themselves can
crash the app or extension — every start/ready call on every platform is
wrapped in error handling that reports failure rather than propagating one —
but that specific claim rests on the client-side call sites cited above, not
on a read of the engine.
