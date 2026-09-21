# Logs that explain the failure without naming the infrastructure

**Status:** approved design, not yet implemented
**Date:** 2026-08-07

## Problem

The app keeps a rolling log the user can read on the home screen and export with one
tap (`HomeScreenModel.buildLogsExport` → `LogExporter.shareLogs`, written as
`proofkit-logs.txt`). That export is the reason support requests are answerable. It is
also, today, a list of our exit addresses.

Two separate leaks feed it, on all three platforms.

**The engine output goes in verbatim.**

| Platform | Path |
|---|---|
| iOS | `IosVpnManager.kt:352` — `packetTunnelBridge.engineLog()` split into `addLog("engine: …")` |
| Desktop | `DesktopVpnManager.kt:118,121` — `onOutput = { line -> addLog(line) }`, raw stdout of both cores |
| Android | `OlcboxVpnService.kt:331` (`rtc:` lines) and `:723` (`diagnose()`) |

sing-box runs at `level: "info"` (`SingBoxConfig.kt:160` and `:265`, both in
commonMain, so on every platform) — and the third renderer, `render()` at `:322`,
sets **no level at all**, so it runs at sing-box's own default, which is also `info`.
That is the one behind `build()` and `buildOlcrtcSocks()`: the plain socks path, which
is what Android and desktop-without-TUN use. Xray is already at `warning`, which is
better, but a warning still names the server it failed to dial.

**App-level lines name hosts and capabilities directly.** `IosVpnManager.kt:223` logs
the origin host on every ping, deliberately. `MacOsTunController.kt:33` logs
`serverHost` when it cannot be resolved. `DesktopVpnManager.kt:761` logs
`room=${config.id}` — an olcRTC room id, which is a capability, not a name.

An adversary who wants our addresses in bulk does not need to attack anything: they
install the app, tap export, and read them. Worse, the log holds the nodes a failover
walked through, not merely the one in use.

### What this does and does not fix

It does not hide a user's own exit node from that user: the address is in the
`vless://` link they already hold, in the clear. That is inherent.

What it stops is the multiplication — every node a failover touched, the room ids, the
shape of our infrastructure — and the ordinary case that actually spreads addresses:
a log pasted into a support chat, a GitHub issue, or a forum thread.

### The larger problem found on the way

At `info`, sing-box names **every outbound connection the user makes**. So the file we
invite the user to share also carries the domains they visited. We publish a no-logs
commitment; an exportable browsing history contradicts it, and that is a promise to
the user rather than a fact about our addresses. It is fixed here because it has the
same cause and the same one-line fix.

## Scope

In scope: a shared scrubber every log line passes through before it reaches the
buffer; the sing-box log level; the two call sites that leak on their own terms;
tests.

Not in scope: the subscription link, which necessarily contains the server address.
Partner-owned domains — protecting those is the partner's business, not ours. Any
"raw log" debug mode: see below.

## Decisions taken before writing this

1. **Scrub, do not drop.** Replacing a value keeps the line's shape and its meaning —
   the reader still sees *what* failed, only not *where*. Dropping engine output
   entirely was considered and rejected: "why does the tunnel not come up" is usually
   only answerable from those lines.
2. **A stable tag, not a constant.** `node#7f3a` rather than `<redacted>`, so two
   different nodes read differently and "the failover tried three and all refused"
   stays legible.
3. **No raw-log escape hatch.** A debug toggle that restores full addresses is the
   thing that ends up switched on by default, and then the leak is back with a step
   in front of it. If we ever need raw engine output, we get it from a device we hold.

## Design

### 1. `LogScrubber`, in commonMain

`sharedUI/src/commonMain/kotlin/org/olcbox/app/log/LogScrubber.kt`. A class holding a
salt, with one method `scrub(line: String): String`. Pure apart from the salt, which
makes it testable in `commonTest` the way `LinkParser` and `SingBoxConfig` already are.

Replaced:

| Match | Becomes | Why |
|---|---|---|
| Public IPv4 / IPv6 literal | `node#<4 hex>` | The address itself |
| UUID | `<id>` | A VLESS UUID **is** the credential; an olcRTC room id is a capability |
| `crypt1` / `crypt5` blob | `<link>` | Wraps a subscription URL |
| Our own domains (`proofkit.org` and subdomains) | `<host>` | Infrastructure names |

Left alone, deliberately:

- **Loopback, RFC1918, CGNAT, link-local and the TUN addresses.** `core ready on
  127.0.0.1:1080` and `10.x` are most of what makes a log readable, and none of it is
  secret. Tagging them would cost diagnosis and buy nothing.
- **Third-party hostnames** — `dns.google`, the Reality SNI decoys. They are not ours,
  and "could not resolve …" without a name says nothing.
- **Ports, error strings, timings, transport names, country labels.**

### 2. The salt is random per process

A short hash of an IPv4 address is otherwise a confirmation oracle: an adversary who
suspects an address can compute its tag and check. Salted per process, a tag means
something only inside one log — which is all diagnosis asks of it ("same node or a
different one?").

The cost, stated plainly: **we cannot map a tag back to a node either.** Accepted. The
location label survives in the same line (`ping DE: node#7f3a answered in 42ms`), and
that is what support actually uses.

A non-cryptographic hash is enough — the salt does the hiding, the hash only spreads
values across buckets — so this adds no dependency to commonMain.

### 3. sing-box `info` → `warn`, in all three renderers

Two say `info` and one says nothing, which is the same thing — sing-box defaults to
`info`. The silent one is `render()`, behind the plain socks path, so it is the one
most users are actually on. The test asserts the level per **builder**, not per
renderer, so a config that emits no `log` block fails rather than passing by omission.

Nothing in the app parses sing-box output: readiness is a socket probe
(`waitForCoreSocks`), not a log match. Dial failures are warnings and survive.

### 4. The scrubber sits at the sink — never earlier

Inside each platform's `addLog`, and only there:

- `OlcboxVpnState.addLog` (`:49`) — before the `Log.d` too, so logcat and a captured
  bug report get the scrubbed line as well
- `DesktopVpnManager.addLog` (`:1095`)
- `IosVpnManager.addLog` (`:671`)

This placement is the load-bearing part of the design. Transport state is decided by
matching raw engine text — `handleRtcLine` (`IosVpnManager.kt:481`),
`OlcboxVpnService.kt:1284-1316`, `DesktopVpnManager.kt:796` — against markers like
`socks5 server listening` and `ice connection state changed: connected`. Scrubbing a
line before those parsers see it would silently break reconnect. Scrubbing inside
`addLog` is safe by construction: the parsers keep the raw line, the buffer gets the
scrubbed one.

### 5. Two call sites also change on their own terms

- `DesktopVpnManager.kt:761` drops `room=${config.id}` from the message. A capability
  should not be in a log line at all; relying on the UUID rule to catch it is a rule
  away from a leak.
- `IosVpnManager.kt:223` stays as written. It becomes `ping DE: node#7f3a answered in
  42ms`, which still answers the question it was added for.

## Testing

`sharedUI/src/commonTest/kotlin/org/olcbox/app/log/LogScrubberTest.kt`:

1. A public IPv4 and a public IPv6 are replaced by a tag.
2. The same address twice in one line gets the same tag; two addresses get different
   tags.
3. Loopback, `10.0.0.1`, `192.168.1.1`, `172.16.0.1`, `169.254.1.1` and the TUN
   address survive **verbatim**.
4. A UUID, a `crypt1` blob and a `proofkit.org` host are replaced.
5. A line with nothing sensitive comes back byte-identical — a scrubber that mangles
   ordinary text is worse than none.
6. Two scrubbers with different salts produce different tags for the same address.
7. **The readiness markers survive scrubbing.** `socks5 server listening on
   127.0.0.1:1080`, `ice connection state changed: connected` and the failure markers
   must read the same after a pass. This is the test that catches the regression that
   would otherwise show up as "reconnect stopped working" weeks later.

`SingBoxConfigTest` gains an assertion that **every public builder** emits
`log.level == "warn"` — read per builder rather than per renderer, so the config that
sets no level today fails on a missing field instead of quietly passing.

Everything runs on the cloud runners — `pr-checks.yml` runs `:sharedUI:jvmTest`,
`:desktopApp:compileKotlin`, `:androidApp:assembleDebug`. This dev box has no JDK by
design and must not get one.

## Rollout

A branch on `ghostlane-project/ghostlane`, PR to `main` for `pr-checks`, then the ordinary
release build. No migration, no server change, nothing to coordinate with the
coordinator.
