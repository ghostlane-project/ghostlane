# Custom routing and "only blocked sites through the tunnel" (roadmap item 10)

## What the user gets

1. **Your own rules.** Two lists in Routing: *Always direct* and *Always through the
   tunnel*. An entry is a domain (it covers its subdomains) or an IP address / CIDR
   prefix. They apply in every mode and win over the mode's lists.
2. **A new mode, "Only blocked sites through the tunnel"** (Russia). Destinations on a
   bundled list of sites blocked in Russia ride the tunnel; everything else connects
   directly. It sits beside Global and the Bypass modes.

Decisions made by the owner (2026-09-25):

- The blocked list is **Re:filter** (`1andrevich/Re-filter-lists`, MIT): its sing-box
  rule-sets `ruleset-domain-refilter_domains.srs` (81k domain suffixes) and
  `ruleset-ip-refilter_ipsum.srs` (25k prefixes), bundled like the regional lists and
  pinned by release tag and sha256.
- **olcRTC rooms keep carrying everything** under the new mode. The engine only takes
  "these go direct, the rest into the room"; the inverse would be an engine change, a
  new pin and gate runs. Your own *direct* rules still reach the engine.

Scope: Android and desktop (proxy mode and the macOS tunnel). **iOS is out** until App
Review settles (roadmap item 11): the iOS screens do not offer the mode or the lists,
and its builder refuses them rather than emitting something untested. Linux and
Windows tunnels stay global as today (`routingUnavailableReasonFor`).

## Model

Persisted (`RoutingSettings`, defaults keep old bundles readable):

- `RoutingMode.BlockedOnly` (`"blocked_only"`), `region == null`.
- `directRules: List<String>` (`"direct_rules"`), `tunnelRules: List<String>`
  (`"tunnel_rules"`): normalized entries.
- `RoutingRule.parse(text)`: trims, lowercases, drops a scheme, path, port and a
  leading `*.`/`.`; an IPv4/IPv6 address or prefix becomes `Cidr` (a bare address gets
  `/32` or `/128`); a name with at least one dot and only `[a-z0-9-.]` labels becomes
  `Domain`; anything else is refused (the screen says so). An entry lives in one list
  only: adding it to one removes it from the other.

Builder side (`net.Routing`), replacing `RuleBased.region` with what it meant:

```kotlin
sealed interface Policy {
    data object Tunnel : Policy                    // Global + your rules
    data class Bypass(val region: String) : Policy // region lists go direct
    data object BlockedOnly : Policy               // Re:filter goes through the tunnel
}
data class CustomRules(val direct: List<RoutingRule>, val tunnel: List<RoutingRule>)
sealed interface RuleBased : Routing { ruleSetDir; directDns; policy; custom }
```

`Routing.Rules(dir, dns, region)` stays as a secondary constructor (Bypass, no custom
rules), so existing callers and tests keep compiling. `Routing.Global` remains "no
rules at all": Global with no rules of your own builds exactly what it builds today.

## sing-box (Android socks shape, desktop proxy, macOS daemon)

Route rules after `sniff` / `hijack-dns`, first match wins:

1. private addresses → `direct` (as today, every policy)
2. your direct rules (`domain_suffix`, `ip_cidr`) → `direct`
3. your tunnel rules → `out`
4. Bypass: region lists → `direct`, final `out` (today's shape)
   BlockedOnly: `refilter-domains` → `out`; then `resolve` (server `dns-direct`) so an
   IP-only block is seen; `refilter-ips` → `out`; final **`direct`**
   Tunnel: nothing more, final `out`

Measured with the real sing-box 1.13.14 (the socks shape, a dead `out`): `ya.ru` went
direct and answered, `www.instagram.com` and a user tunnel rule went to `out`. The IP
list is Re:filter's `ipsum`, 0.10% of IPv4, and it holds whole ranges of large
services — all of Cloudflare's 104.16.0.0/13 and 104.24.0.0/14, parts of Google and
Meta — so after `resolve` a site hosted there (example.com, google.com) rides the
tunnel too. That is the list's intent (services blocked in Russia or refusing Russian
users); the mode's note on screen says so rather than hiding it.

DNS (socks shape): your tunnel names → `dns-remote`; your direct names → `dns-direct`;
Bypass: region names → `dns-direct`, final `dns-remote`; BlockedOnly: Re:filter names →
`dns-remote` (an ISP resolver may answer a blocked name with a stub), final
`dns-direct`; Tunnel: final `dns-remote`.

macOS daemon (tun shape, fake addresses for tunnel names): the same order, with
"tunnel names" answered by `dns-fakeip` for A/AAAA (HTTPS refused) instead of
`dns-remote`, and the server's own names direct first as today.

## olcRTC engine (`OlcrtcDirectRules.forRouting`)

- Global without rules: nothing (as today). Tunnel: your direct rules only.
- Bypass: the region's rules plus your direct rules, minus region entries equal to or
  under one of your tunnel domains (the engine has no "tunnel" rule; a site under a
  whole-TLD entry such as `.ru` cannot be carved out, and the screen note says your
  tunnel rules are exact in rooms only where they do not fall under such an entry).
- BlockedOnly: your direct rules only (rooms carry everything else).

## Files and attribution

`scripts/update-rule-sets.sh` also fetches the two Re:filter files from a pinned
release (`REFILTER_TAG`, today `01082026`) into `files/rules/refilter-domains.srs` and
`refilter-ips.srs`, recording tag and sha256 in `rule-sets.lock`; `RuleSets` pins the
hashes and `RuleSetsTest` checks them. `THIRD_PARTY_NOTICES.md` gains Re:filter (MIT).
+645 KB to the app.

## Screens

Routing screen (`RoutingSettingsScreen`, used by Android and desktop): the row that
picked a region picks a *mode* now and lists BlockedOnly beside Global and the
bypasses; below it a "Your rules" card with the two lists (a field, two buttons, a
remove per entry), an invalid entry refused where it was typed. iOS routes through its
own list of two modes (`SharedRoutingSettingsContent`) and shows neither. Strings in
English and Russian.

## macOS daemon

A start under BlockedOnly carries the two lists to the root daemon in base64, about
860 KB. The daemon read a request up to 1 MiB; it now reads up to 4 MiB, and
`BlockedOnlyDaemonRequestTest` keeps a start under 1 MiB − 64 KiB so the daemons still
installed keep working until they update.

## Tests

- `RoutingRuleTest`: parsing (Cyrillic names to `xn--`, prefixes masked), refusal,
  one list per entry, `needsRules`, each mode's policy.
- `CustomRoutingConfigTest`: rule order, finals and DNS for each policy in the socks
  shapes and the Mac daemon; the iOS shape refusing what it has not had.
- `OlcrtcCustomRulesTest`: direct rules added, the tunnel carve-out, BlockedOnly and
  Global with rules.
- `SingBoxConfigDumpTest`: BlockedOnly, Global+rules and Bypass+rules in the socks,
  front and daemon shapes for CI's `sing-box check` (all pass locally on 1.13.14); the
  existing reference configs are unchanged byte for byte.
- `RoutingSettingsTest`: the new mode and rules survive a save, old bundles read.
- `RuleSetsTest`: the Re:filter files hash to their pins (via `RuleSets.bundled`).
