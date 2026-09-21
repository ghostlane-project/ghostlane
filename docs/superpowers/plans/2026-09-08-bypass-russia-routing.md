# Bypass Russia Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A routing mode, "Bypass Russia", under which Russian destinations and the local network leave the device directly while everything else — DNS included — rides the tunnel; on iOS for every transport, on Android for every transport in tun mode.

**Architecture:** sing-box is already the router in front of every transport on iOS and in front of Reality/Hysteria2 on Android; this plan makes it the router in front of olcRTC and xhttp on Android too (a sing-box "front" between hev-socks5-tunnel and the transport's own SOCKS port), and teaches the one config builder every platform shares (`SingBoxConfig`) to emit `route.rule_set` + `route.rules` + a split `dns` section when asked. Three binary rule-sets from SagerNet (`geosite-category-ru`, `geosite-tld-ru`, `geoip-ru`, 59 KB together) ship inside the app as Compose resources and are written to a directory the core can read before each connect. The setting lives in the location bundle, the one thing already persisted identically on every platform.

**Tech Stack:** Kotlin Multiplatform (commonMain builders, kotlinx.serialization JSON), sing-box 1.13.14 (pinned; `rule_set` local/binary, `sniff`/`hijack-dns` route actions, typed `dns.servers`), Compose Multiplatform resources (`Res.readBytes`), Swift (iOS app bridge + PacketTunnel extension), Android `VpnService` + exec'd `libsingboxcore.so`.

**Spec:** the "Design" section below. There is no separate spec file: this was brainstormed in chat on 2026-09-08 and classified bounded; the design is short enough to travel with the plan.

## Global Constraints

- sing-box schema is pinned to **1.13.14** (`SingBoxConfig.SINGBOX_VERSION`); every new shape must pass `sing-box check` on that binary (locally via `scripts/check-singbox-configs.sh`, in CI via pr-checks).
- No user-visible string may contain the word "subscription" (App Review 3.1.1 rename); a string in commonMain renders on every platform, so it must not name one.
- All user-visible text is English.
- Kotlin: `minSdk 23` on Android — no `ProcessHandle`/`isAlive`/`waitFor(timeout)`.
- Nothing in `commonMain` writes files; platform code does, following `olcrtc.json` (iOS), `filesDir` (Android).
- Commit after every task, message style `feat(scope): what it does, in a sentence` (see `git log`), and end each message with the session attribution block below.
- Local verification: `./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.*"` and `:desktopApp:compileKotlin` run on this box; `:androidApp:assembleDebug` and anything Apple run only in CI / Xcode. Read `sharedUI/build/test-results/jvmTest/TEST-<fqcn>.xml` for test counts — "UP-TO-DATE" is not evidence.

Commit trailer for every commit:

```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CEfgqJHWsowYtu4VT8PSw5
```

---

## Design

### What the user sees

Settings → Routing (iOS/desktop shared sheet) and Settings → Split Tunneling → Destinations (Android): two cards.

| Card | Title | Subtitle |
|---|---|---|
| Global | All traffic through the tunnel | Every connection leaves through the tunnel. Your exit is the server's. |
| Bypass Russia | Bypass Russia | Russian sites, .ru domains and your local network go straight out. Everything else rides the tunnel, DNS included. |

Changing it while connected restarts the tunnel (same gesture as changing the connection mode on desktop). On desktop the screen shows the choice greyed with the note "Applies on iOS and Android. Desktop follows in a later build." and nothing else changes there.

### What sing-box gets

For `Routing.BypassRussia(ruleSetDir, directDns)` every builder adds, on top of what it emits today:

```json
"dns": {
  "servers": [
    {"type": "udp" | "tcp", "tag": "dns-remote", "server": "1.1.1.1", "detour": "out"},
    {"type": "udp", "tag": "dns-direct", "server": "<resolver>", "detour": "direct"}   // or {"type":"local","tag":"dns-direct"} for DirectDns.System
  ],
  "rules": [ {"rule_set": ["geosite-ru", "geosite-tld-ru"], "server": "dns-direct"} ],
  "final": "dns-remote",
  "reverse_mapping": true
},
"outbounds": [ {...existing "out"...}, {"type": "direct", "tag": "direct"} ],
"route": {
  "rule_set": [
    {"type": "local", "tag": "geosite-ru",     "format": "binary", "path": "<ruleSetDir>/geosite-category-ru.srs"},
    {"type": "local", "tag": "geosite-tld-ru", "format": "binary", "path": "<ruleSetDir>/geosite-tld-ru.srs"},
    {"type": "local", "tag": "geoip-ru",       "format": "binary", "path": "<ruleSetDir>/geoip-ru.srs"}
  ],
  "rules": [
    {"action": "sniff"},
    {"action": "hijack-dns", "port": 53},
    {"ip_is_private": true, "outbound": "direct"},
    {"rule_set": ["geosite-ru", "geosite-tld-ru", "geoip-ru"], "outbound": "direct"}
  ],
  "final": "out",
  "default_domain_resolver": "dns-direct"
}
```

- `dns-remote` is `tcp` when the upstream's UDP is lossy (olcRTC), `udp` otherwise — the rule `renderTun` already applies.
- `sniff` first so TLS/HTTP/QUIC connections carry their domain; `reverse_mapping` so an IP sing-box itself resolved carries the name too. On Android hev-socks5-tunnel hands sing-box hostnames directly (fake-IP `mapdns`), so both are belt-and-braces there.
- `hijack-dns` before `ip_is_private`: the system's resolvers (1.1.1.1/8.8.8.8 on iOS, `mapdns` 1.1.1.1 on Android) are public, but the order costs nothing and a LAN resolver must still be answered by sing-box rather than dialled.
- `default_domain_resolver: dns-direct` — the server's own hostname (in `out`) and any Russian name the `direct` outbound dials are resolved on the network underneath, never through the tunnel that is being built.
- `geoip-ru` matches raw-IP dials (apps that hardcode addresses) and, on iOS, every hostname connection, since there the connection reaches sing-box as an IP. It never triggers a resolve of its own: sing-box skips IP rules for unresolved names, so nothing about a non-Russian name leaks to the direct resolver.

### Where the direct resolver comes from (`DirectDns`)

| Platform | `DirectDns` | Why |
|---|---|---|
| iOS | `Placeholder` → the extension replaces `198.51.100.53` with the first IPv4 (else IPv6) from `ResolverSnapshot`, else `77.88.8.8` | Inside a tunnel a `local` server resolves through the tunnel's own DNS — sing-box's darwin `local` transport falls back to `net.Resolver` when a tun inbound exists and no DHCP build tag is present (verified in `dns/transport/local/local_darwin.go` of the pinned source). The network's resolver can only be read before `setTunnelNetworkSettings`, which is when `ResolverSnapshot` already runs. |
| Android | `Servers(LinkProperties.dnsServers)` | Known at connect; refreshed by the migration path on network change (`reconnectTransport` restarts the transport). sing-box's `local` on Android has no resolv.conf to read. |
| Desktop | not applied in this iteration | In macOS tun mode the core's `direct` sockets would loop into the daemon's utun; a bypass there belongs in the daemon's own sing-box with root-owned rule files. Deferred. |

Known limitation, documented in the TestFlight notes: on iOS the direct resolver is the one captured at connect; after a Wi-Fi↔cellular switch Russian names may fail to resolve until reconnect.

### Where the rule-set files go

| Platform | Directory | `rule_set.path` |
|---|---|---|
| iOS | App Group `libbox/work/rules/` (written by the Swift bridge from base64 in the start request) | relative `rules/<name>` — libbox resolves relative paths against its working path (`filemanager.BasePath`, verified in sing v0.8.11) |
| Android | `filesDir/rulesets/` (written by the service before each connect) | absolute |

### Android process shape under Bypass (tun mode)

| Transport | Today | Bypass |
|---|---|---|
| Reality / Hysteria2 | hev → sing-box :10810 | same process, routing in its config |
| xhttp | hev → Xray :10810 | hev → sing-box :10810 (`buildSocksChain`) → Xray :10811 |
| olcRTC | hev → olcRTC :10808 (auth) | hev → sing-box :10810 (`buildSocksChain` with the olcRTC credentials) → olcRTC :10808 |

Proxy connection mode: Reality/Hysteria2 get the rules (they already are the SOCKS endpoint); olcRTC and xhttp stay Global and the log says so.

---

## File Structure

| File | Responsibility |
|---|---|
| `sharedUI/src/commonMain/composeResources/files/rules/*.srs` (create, 3 files) | the bundled rule-sets |
| `scripts/update-rule-sets.sh`, `scripts/rule-sets.lock` (create) | refresh + provenance (upstream commit, sha256) |
| `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt` (create) | names, tags, pinned hashes, `bytes()` |
| `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/Routing.kt` (create) | `Routing`, `DirectDns` — the builder-level model |
| `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt` (modify) | emit dns/route/direct for `Routing.BypassRussia`; `buildSocksChain` |
| `sharedUI/src/commonTest/.../net/{RuleSetsTest,RoutingTest}.kt` (create), `SingBoxConfigTest.kt` (modify) | shape tests |
| `sharedUI/src/jvmTest/.../net/SingBoxConfigDumpTest.kt` (modify), `scripts/check-singbox-configs.sh` (create), `.github/workflows/pr-checks.yml` (modify) | real-binary verification |
| `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt` (create), `LocationConfig.kt`, `data/repository/LocationsRepository.kt`, `data/datasource/LocationsDatasource.kt` (modify) | persisted setting |
| `sharedUI/src/commonMain/.../ui/features/home/HomeScreenModel.kt` (modify) | state flow + save + restart |
| `sharedUI/src/commonMain/.../ui/components/ApplicationSettingsSheet.kt` (modify) | iOS/desktop UI |
| `sharedUI/src/iosMain/.../ios/MainViewController.kt`, `desktopApp/src/main/kotlin/main.kt` (modify) | wiring |
| `sharedUI/src/androidMain/.../ui/activities/AndroidAppSettingsSheets.kt`, `AndroidMainScreen.kt` (modify) | Android UI |
| `sharedUI/src/iosMain/.../ios/IosBridge.kt`, `.../vpn/IosVpnManager.kt` (modify) | iOS request |
| `iosApp/iosApp/OlcboxIosApp.swift`, `iosApp/PacketTunnel/PacketTunnelProvider.swift` (modify), `iosApp/PacketTunnel/DirectResolver.swift`, `iosApp/Tests/DirectResolverTests.swift`, `scripts/test-ios-direct-resolver.sh` (create) | iOS hand-over + resolver patch |
| `sharedUI/src/androidMain/.../vpn/service/OlcboxVpnService.kt` (modify) | Android routing, rule files, front |
| `docs/testflight-notes.md` (modify) | what testers should look at |

---

### Task 1: Bundle the rule-sets and pin them

**Files:**
- Create: `sharedUI/src/commonMain/composeResources/files/rules/geosite-category-ru.srs`, `geosite-tld-ru.srs`, `geoip-ru.srs`
- Create: `scripts/update-rule-sets.sh`, `scripts/rule-sets.lock`
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RuleSetsTest.kt`

**Interfaces:**
- Produces: `object RuleSets { class File(name, tag, sha256); val GEOSITE_RU; val GEOSITE_TLD_RU; val GEOIP_RU; val all: List<File>; val domains: List<File>; const val IOS_RELATIVE_DIR = "rules"; suspend fun bytes(file: File): ByteArray }`

- [ ] **Step 1: Write the failing test**

```kotlin
// sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RuleSetsTest.kt
package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import org.olcbox.app.crypt.PlatformCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleSetsTest {
    @Test fun bundledFilesAreThePinnedBuilds() = runTest {
        // The hashes are what scripts/update-rule-sets.sh fetched; a bundle whose
        // bytes differ is either an unrecorded refresh or a corrupted resource, and
        // either one ships a bypass list nobody reviewed.
        for (file in RuleSets.all) {
            val bytes = RuleSets.bytes(file)
            assertTrue(bytes.isNotEmpty(), "${file.name} is empty")
            assertEquals(file.sha256, PlatformCrypto.sha256(bytes).toHex(), "${file.name} is not the pinned build")
        }
    }

    @Test fun tagsAndNamesAreDistinct() {
        assertEquals(RuleSets.all.size, RuleSets.all.map { it.tag }.toSet().size)
        assertEquals(RuleSets.all.size, RuleSets.all.map { it.name }.toSet().size)
    }

    @Test fun domainListsAreASubsetOfAll() {
        assertTrue(RuleSets.all.containsAll(RuleSets.domains))
        assertTrue(RuleSets.GEOIP_RU !in RuleSets.domains, "an IP list has no names for a DNS rule to match")
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.RuleSetsTest"`
Expected: compilation FAILS with `Unresolved reference: RuleSets`.

- [ ] **Step 3: Add the files, the script, the lock and the object**

```bash
# scripts/update-rule-sets.sh
#!/usr/bin/env bash
# Refreshes the rule-sets the app bundles, from SagerNet's `rule-set` branches.
#
# Three files: v2fly's geosite:category-ru and geosite:tld-ru, and geoip:ru, all
# compiled to sing-box's binary format. Pinned to a commit of each branch so a
# re-run reproduces the same bytes; pass GEOSITE_REF / GEOIP_REF to move them.
# Writes scripts/rule-sets.lock with what it fetched. The sha256 lines there
# must then be copied into RuleSets.kt — RuleSetsTest checks the bundle against
# those constants, so a refresh that forgets the copy fails the build rather
# than shipping unreviewed lists.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/sharedUI/src/commonMain/composeResources/files/rules"
lock="$root/scripts/rule-sets.lock"

branch_head() {
  curl -fsSL -H 'User-Agent: olcbox' "https://api.github.com/repos/SagerNet/$1/branches/rule-set" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["commit"]["sha"])'
}

GEOSITE_REF="${GEOSITE_REF:-$(branch_head sing-geosite)}"
GEOIP_REF="${GEOIP_REF:-$(branch_head sing-geoip)}"

fetch() { # repo ref file
  curl -fsSL "https://raw.githubusercontent.com/SagerNet/$1/$2/$3" -o "$dest/$3"
  echo "fetched $3 @ $1/$2"
}

mkdir -p "$dest"
fetch sing-geosite "$GEOSITE_REF" geosite-category-ru.srs
fetch sing-geosite "$GEOSITE_REF" geosite-tld-ru.srs
fetch sing-geoip "$GEOIP_REF" geoip-ru.srs

{
  echo "# Written by scripts/update-rule-sets.sh. Do not edit by hand."
  echo "sing-geosite $GEOSITE_REF"
  echo "sing-geoip $GEOIP_REF"
  (cd "$dest" && sha256sum geosite-category-ru.srs geosite-tld-ru.srs geoip-ru.srs)
} > "$lock"

echo
cat "$lock"
echo
echo "Copy the sha256 values into sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt."
```

Run it pinned to the commits inspected on 2026-09-08 (these produced the hashes below):

```bash
cd /root/olcbox-fork && chmod +x scripts/update-rule-sets.sh && \
GEOSITE_REF=5a5a9abc760d2653948c9549c4cb56cc3279e1aa GEOIP_REF=b9c5e675b4d5359d4b47f4434fa7ae77e9991306 scripts/update-rule-sets.sh
```

Expected lock content:

```
# Written by scripts/update-rule-sets.sh. Do not edit by hand.
sing-geosite 5a5a9abc760d2653948c9549c4cb56cc3279e1aa
sing-geoip b9c5e675b4d5359d4b47f4434fa7ae77e9991306
c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870  geosite-category-ru.srs
ba979268102429754bdbbf306890f91ac4ee0cf1d2f30eb1fff5eba65e0f9e66  geosite-tld-ru.srs
1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7  geoip-ru.srs
```

```kotlin
// sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt
package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The sing-box rule-sets the app ships, and what the configs call them.
 *
 * Three binary rule-sets from SagerNet's `rule-set` branches: the lists v2fly
 * publishes as `geosite:category-ru`, `geosite:tld-ru` and `geoip:ru`, compiled
 * to sing-box's format. The TLD list is a file of its own because SagerNet's
 * build of category-ru leaves the bare TLDs out despite the `include:tld-ru`
 * in the v2fly source — without it `sberbank.ru` is matched and `ozon.ru` is
 * not, which is not a list anyone would recognise as "Russia".
 *
 * Bundled rather than downloaded. The networks this mode exists for are the
 * ones where github.com answers slowly or not at all, and a list that arrives
 * after the first connection is a first connection with no bypass in it.
 * `scripts/update-rule-sets.sh` refreshes them and records what it fetched in
 * `scripts/rule-sets.lock`; [RuleSetsTest] refuses a bundle whose bytes do not
 * hash to the values pinned here.
 */
object RuleSets {
    class File(val name: String, val tag: String, val sha256: String)

    val GEOSITE_RU = File(
        name = "geosite-category-ru.srs",
        tag = "geosite-ru",
        sha256 = "c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"
    )
    val GEOSITE_TLD_RU = File(
        name = "geosite-tld-ru.srs",
        tag = "geosite-tld-ru",
        sha256 = "ba979268102429754bdbbf306890f91ac4ee0cf1d2f30eb1fff5eba65e0f9e66"
    )
    val GEOIP_RU = File(
        name = "geoip-ru.srs",
        tag = "geoip-ru",
        sha256 = "1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7"
    )

    /** Everything the route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)

    /**
     * Where iOS keeps them, relative to libbox's working directory.
     *
     * Relative because only the extension knows the App Group's absolute path,
     * and libbox resolves a relative `rule_set.path` against the working path
     * it was set up with (`filemanager.BasePath`). The app writes the files
     * there through the Swift bridge; the config never needs the full path.
     */
    const val IOS_RELATIVE_DIR = "rules"

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/rules/${file.name}")
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.RuleSetsTest"` then `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' sharedUI/build/test-results/jvmTest/TEST-org.olcbox.app.net.RuleSetsTest.xml`
Expected: `tests="3" skipped="0" failures="0" errors="0"`. If `Res.readBytes` cannot find the file on the JVM test classpath, check `sharedUI/build/processedResources/jvm/main/composeResources/multiplatform_app.sharedui.generated.resources/files/rules/` exists after the run; the plugin copies the whole `composeResources` tree, nested directories included.

- [ ] **Step 5: Commit**

```bash
cd /root/olcbox-fork && git add scripts/update-rule-sets.sh scripts/rule-sets.lock sharedUI/src/commonMain/composeResources/files/rules sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RuleSetsTest.kt
git commit -m "feat(routing): bundle the Russian rule-sets and pin their hashes"
```

---

### Task 2: Teach `SingBoxConfig` the bypass shape

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/Routing.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RoutingTest.kt` (create), `SingBoxConfigTest.kt` (modify)

**Interfaces:**
- Consumes: `RuleSets.all`, `RuleSets.domains` (Task 1)
- Produces:
  - `sealed interface Routing { data object Global; data class BypassRussia(val ruleSetDir: String, val directDns: DirectDns) }`
  - `sealed interface DirectDns { data object System; data class Servers(val addresses: List<String>) { fun pick(): String }; data object Placeholder }`
  - `SingBoxConfig.DIRECT_DNS_PLACEHOLDER = "198.51.100.53"`, `SingBoxConfig.DIRECT_DNS_FALLBACK = "77.88.8.8"`
  - `SingBoxConfig.build(outbound, socksPort, routing = Routing.Global)`, `buildTun(outbound, address, mtu, routing = Routing.Global)`, `buildTunSocks(socksPort, username, password, upstreamUdpIsLossy, address, mtu, routing = Routing.Global)`, `buildSocksChain(upstreamPort, socksPort = SINGBOX_SOCKS_PORT, username = "", password = "", routing = Routing.Global)`; `buildOlcrtcSocks(olcrtcPort, socksPort)` kept, delegating to `buildSocksChain`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RoutingTest.kt
package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingTest {
    @Test fun firstIPv4Wins() {
        assertEquals("192.0.2.5", DirectDns.Servers(listOf("fe80::1%wlan0", "192.0.2.5", "192.0.2.6")).pick())
    }

    @Test fun ipv6WithoutAZoneIsAcceptedWhenThereIsNoIPv4() {
        assertEquals("2001:db8::53", DirectDns.Servers(listOf("2001:db8::53%en0")).pick())
    }

    @Test fun linkLocalAndLoopbackAreNotResolvers() {
        // A link-local address needs a zone sing-box cannot carry, and loopback
        // inside the tunnel process is the tunnel itself.
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, DirectDns.Servers(listOf("fe80::1%wlan0", "127.0.0.1", "::1")).pick())
    }

    @Test fun nothingOfferedFallsBackToThePublicResolver() {
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, DirectDns.Servers(emptyList()).pick())
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, DirectDns.Servers(listOf(" ", "")).pick())
    }
}
```

Append to `SingBoxConfigTest.kt` (inside the class, after the desktop tests; add `import kotlinx.serialization.json.jsonArray`/`jsonObject`/`jsonPrimitive`/`booleanOrNull` as needed — the file already imports the first three):

```kotlin
    // --- Bypass Russia -----------------------------------------------------

    private fun bypass(dns: DirectDns = DirectDns.Servers(listOf("10.20.30.40"))) =
        Routing.BypassRussia(ruleSetDir = "/data/rules", directDns = dns)
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun routeRules(json: String) = obj(json)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun dnsServers(json: String) = obj(json)["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
    private fun str(o: kotlinx.serialization.json.JsonObject, key: String) = o[key]!!.jsonPrimitive.content
    private fun strings(o: kotlinx.serialization.json.JsonObject, key: String) = o[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /** Every shape a platform builds, with the same routing, so a rule is checked once and holds everywhere. */
    private fun shapes(routing: Routing) = mapOf(
        "socks" to SingBoxConfig.build(vless(), routing = routing),
        "socks-chain" to SingBoxConfig.buildSocksChain(10808, username = "u", password = "p", routing = routing),
        "tun" to SingBoxConfig.buildTun(vless(), routing = routing),
        "tun-socks" to SingBoxConfig.buildTunSocks(10810, routing = routing),
        "tun-socks-lossy" to SingBoxConfig.buildTunSocks(10810, username = "u", password = "p", upstreamUdpIsLossy = true, routing = routing),
    )

    @Test fun globalRoutingIsExactlyWhatWasBuiltBeforeRoutingExisted() {
        assertEquals(SingBoxConfig.build(vless()), SingBoxConfig.build(vless(), routing = Routing.Global))
        assertEquals(SingBoxConfig.buildTun(vless()), SingBoxConfig.buildTun(vless(), routing = Routing.Global))
        assertEquals(SingBoxConfig.buildTunSocks(10810, upstreamUdpIsLossy = true), SingBoxConfig.buildTunSocks(10810, upstreamUdpIsLossy = true, routing = Routing.Global))
        assertEquals(SingBoxConfig.buildOlcrtcSocks(10808), SingBoxConfig.buildSocksChain(10808))
        // Global still means: no dns, no route, one outbound, for the shapes that had none.
        for (json in listOf(SingBoxConfig.build(vless()), SingBoxConfig.buildSocksChain(10808))) {
            assertNull(obj(json)["dns"]); assertNull(obj(json)["route"])
            assertEquals(1, obj(json)["outbounds"]!!.jsonArray.size)
        }
    }

    @Test fun bypassDeclaresTheThreeRuleSetsAsLocalBinaryFiles() {
        for ((name, json) in shapes(bypass())) {
            val sets = obj(json)["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
            assertEquals(RuleSets.all.map { it.tag }, sets.map { str(it, "tag") }, name)
            for ((set, file) in sets.zip(RuleSets.all)) {
                assertEquals("local", str(set, "type"), name)
                assertEquals("binary", str(set, "format"), name)
                assertEquals("/data/rules/${file.name}", str(set, "path"), name)
            }
        }
    }

    @Test fun bypassRoutesRussiaAndTheLocalNetworkDirectAndTheRestThroughTheTunnel() {
        for ((name, json) in shapes(bypass())) {
            val rules = routeRules(json)
            assertEquals(4, rules.size, name)
            assertEquals("sniff", str(rules[0], "action"), name)
            assertEquals("hijack-dns", str(rules[1], "action"), name)
            assertEquals(53, rules[1]["port"]!!.jsonPrimitive.content.toInt(), name)
            assertEquals(true, rules[2]["ip_is_private"]!!.jsonPrimitive.content.toBoolean(), name)
            assertEquals("direct", str(rules[2], "outbound"), name)
            assertEquals(RuleSets.all.map { it.tag }, strings(rules[3], "rule_set"), name)
            assertEquals("direct", str(rules[3], "outbound"), name)
            assertEquals("out", str(obj(json)["route"]!!.jsonObject, "final"), name)
            assertEquals("dns-direct", str(obj(json)["route"]!!.jsonObject, "default_domain_resolver"), name)
        }
    }

    @Test fun bypassAddsADirectOutboundAfterTheTunnelOne() {
        for ((name, json) in shapes(bypass())) {
            val outbounds = obj(json)["outbounds"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, outbounds.size, name)
            assertEquals("out", str(outbounds[0], "tag"), name)
            assertEquals("direct", str(outbounds[1], "type"), name)
            assertEquals("direct", str(outbounds[1], "tag"), name)
        }
    }

    @Test fun bypassResolvesRussianNamesOnTheNetworkUnderneathAndTheRestThroughTheTunnel() {
        for ((name, json) in shapes(bypass())) {
            val dns = obj(json)["dns"]!!.jsonObject
            val servers = dnsServers(json)
            assertEquals(listOf("dns-remote", "dns-direct"), servers.map { str(it, "tag") }, name)
            assertEquals("out", str(servers[0], "detour"), name)
            assertEquals("udp", str(servers[1], "type"), name)
            assertEquals("10.20.30.40", str(servers[1], "server"), name)
            assertEquals("direct", str(servers[1], "detour"), name)
            val rules = dns["rules"]!!.jsonArray.map { it.jsonObject }
            assertEquals(1, rules.size, name)
            assertEquals(RuleSets.domains.map { it.tag }, strings(rules[0], "rule_set"), name)
            assertEquals("dns-direct", str(rules[0], "server"), name)
            assertEquals("dns-remote", str(dns, "final"), name)
            assertEquals(true, dns["reverse_mapping"]!!.jsonPrimitive.content.toBoolean(), name)
        }
    }

    @Test fun remoteResolverRidesTcpOnlyWhenTheUpstreamIsLossy() {
        val byShape = shapes(bypass()).mapValues { str(dnsServers(it.value)[0], "type") }
        assertEquals("tcp", byShape["tun-socks-lossy"])
        for (name in listOf("socks", "socks-chain", "tun", "tun-socks")) assertEquals("udp", byShape[name], name)
    }

    @Test fun desktopDirectResolverIsTheSystemOne() {
        val server = dnsServers(SingBoxConfig.build(vless(), routing = bypass(DirectDns.System)))[1]
        assertEquals("local", str(server, "type"))
        assertNull(server["server"]); assertNull(server["detour"])
    }

    @Test fun iosDirectResolverIsAPlaceholderTheExtensionFillsIn() {
        val json = SingBoxConfig.buildTun(vless(), routing = bypass(DirectDns.Placeholder))
        val server = dnsServers(json)[1]
        assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, str(server, "server"))
        assertEquals("direct", str(server, "detour"))
        // Exactly once, quoted: the extension substitutes by string and must not
        // be able to hit anything else.
        assertEquals(1, Regex("\"" + Regex.escape(SingBoxConfig.DIRECT_DNS_PLACEHOLDER) + "\"").findAll(json).count())
    }

    @Test fun bypassKeepsUdpFlowing() {
        for ((name, json) in shapes(bypass())) {
            assertTrue(routeRules(json).none { it["action"]?.jsonPrimitive?.content == "reject" }, name)
        }
    }

    @Test fun socksChainCarriesCredentialsOnlyWhenTheUpstreamAskedForThem() {
        val with = outbound(SingBoxConfig.buildSocksChain(10808, username = "u", password = "p"))
        assertEquals("u", with["username"]!!.jsonPrimitive.content)
        assertEquals("p", with["password"]!!.jsonPrimitive.content)
        assertEquals(10808, with["server_port"]!!.jsonPrimitive.content.toInt())
        val without = outbound(SingBoxConfig.buildSocksChain(10808))
        assertNull(without["username"]); assertNull(without["password"])
    }

    @Test fun bypassShapesKeepTheLogQuietToo() {
        for ((name, json) in shapes(bypass())) {
            assertEquals("warn", str(obj(json)["log"]!!.jsonObject, "level"), name)
        }
    }
```

Also change the existing `olcrtcSocksOutbound` expectation nothing (it checks type/server/port only) — but the outbound tag moves from `"olcrtc"` to `"out"`; add one line to that test: `assertEquals("out", o["tag"]!!.jsonPrimitive.content)`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.RoutingTest" --tests "org.olcbox.app.net.SingBoxConfigTest"`
Expected: compilation FAILS (`Unresolved reference: Routing`, `DirectDns`, `buildSocksChain`).

- [ ] **Step 3: Implement**

```kotlin
// sharedUI/src/commonMain/kotlin/org/olcbox/app/net/Routing.kt
package org.olcbox.app.net

/**
 * What sing-box does with a connection: everything through the tunnel, or the
 * split this app calls Bypass Russia.
 *
 * A builder-level model, deliberately separate from the persisted setting
 * ([org.olcbox.app.data.model.RoutingMode]): the setting is one word, this is
 * what the platform resolved that word into — where it put the rule-set files
 * and which resolver "direct" traffic may use.
 */
sealed interface Routing {
    /** Everything through the tunnel: the shape every builder emitted before routing existed. */
    data object Global : Routing

    /**
     * Russian destinations and the local network go straight out; everything
     * else rides the tunnel, name resolution included.
     *
     * [ruleSetDir] holds the files in [RuleSets]. Absolute where the app knows
     * the path (Android); relative to the core's working directory where only
     * the extension does (iOS, [RuleSets.IOS_RELATIVE_DIR]).
     */
    data class BypassRussia(val ruleSetDir: String, val directDns: DirectDns) : Routing
}

/**
 * The resolver for names that go direct. It must not be the tunnel: the whole
 * point of resolving `sberbank.ru` here is that neither the query nor the
 * connection that follows it leaves through the exit.
 */
sealed interface DirectDns {
    /**
     * The operating system's resolver. Only where the core reaches it without
     * looping through its own tun — desktop. Inside an iOS tunnel the system
     * resolver *is* the tun, and sing-box's darwin `local` transport falls back
     * to exactly that once a tun inbound exists.
     */
    data object System : DirectDns

    /**
     * Explicit resolver addresses as the platform lists them — IP literals, a
     * `%zone` suffix tolerated. One is used: sing-box has no failover between
     * servers, so [pick] chooses the first IPv4, else the first routable IPv6,
     * else the public fallback.
     */
    data class Servers(val addresses: List<String>) : DirectDns {
        fun pick(): String {
            val hosts = addresses.map { it.trim().substringBefore('%') }
                .filter { it.isNotEmpty() && !isLoopback(it) }
            return hosts.firstOrNull { isIPv4(it) }
                ?: hosts.firstOrNull { ':' in it && !it.startsWith("fe80:", ignoreCase = true) }
                ?: SingBoxConfig.DIRECT_DNS_FALLBACK
        }

        private fun isIPv4(host: String): Boolean =
            host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

        private fun isLoopback(host: String): Boolean = host.startsWith("127.") || host == "::1"
    }

    /**
     * iOS. The extension learns the network's resolver only after the config
     * has been written — it reads it just before the tunnel's settings replace
     * the system resolver with our own tun — so the builder emits
     * [SingBoxConfig.DIRECT_DNS_PLACEHOLDER] and the extension replaces it.
     */
    data object Placeholder : DirectDns
}
```

`SingBoxConfig.kt` changes (the full functions; keep the existing doc comments where a function only gains a parameter):

```kotlin
    /** The tunnel-side resolver, reached through `out`; TCP when the upstream's UDP is lossy. */
    private const val REMOTE_DNS_SERVER = "1.1.1.1"

    /**
     * What the iOS builder writes where the direct resolver goes; the extension
     * replaces it with the network's own before starting the core. TEST-NET-2,
     * so an unreplaced placeholder can only fail loudly, never resolve.
     */
    const val DIRECT_DNS_PLACEHOLDER = "198.51.100.53"

    /**
     * Yandex DNS. What "direct" resolution uses when the platform offered no
     * resolver at all. Russian because the mode is: it is the resolver most
     * likely to answer on a Russian mobile network that meets 1.1.1.1 with
     * silence, and it answers everywhere else too.
     */
    const val DIRECT_DNS_FALLBACK = "77.88.8.8"

    fun build(outbound: OutboundSpec, socksPort: Int = SINGBOX_SOCKS_PORT, routing: Routing = Routing.Global): String =
        render(socksPort, routing) { addOutbound(outbound) }

    fun buildTun(
        outbound: OutboundSpec,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
        routing: Routing = Routing.Global,
    ): String = renderTun(address, mtu, resolveOverTcp = false, routing) { addOutbound(outbound) }

    fun buildTunSocks(
        socksPort: Int,
        username: String = "",
        password: String = "",
        upstreamUdpIsLossy: Boolean = false,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
        routing: Routing = Routing.Global,
    ): String = renderTun(address, mtu, resolveOverTcp = upstreamUdpIsLossy, routing) {
        addSocksOutbound(socksPort, username, password)
    }

    /**
     * A SOCKS inbound in front of another core's SOCKS port — the Android shape
     * for olcRTC and xhttp under Bypass Russia, where sing-box has to sit between
     * hev-socks5-tunnel and a transport it does not implement so its rules can
     * decide what goes direct. Credentials only when the upstream demands them,
     * which is olcRTC and only olcRTC.
     */
    fun buildSocksChain(
        upstreamPort: Int,
        socksPort: Int = SINGBOX_SOCKS_PORT,
        username: String = "",
        password: String = "",
        routing: Routing = Routing.Global,
    ): String = render(socksPort, routing) { addSocksOutbound(upstreamPort, username, password) }

    fun buildOlcrtcSocks(olcrtcPort: Int, socksPort: Int = SINGBOX_SOCKS_PORT): String =
        buildSocksChain(olcrtcPort, socksPort)

    private fun JsonArrayBuilder.addSocksOutbound(port: Int, username: String, password: String) {
        addJsonObject {
            put("type", "socks"); put("tag", "out")
            put("server", "127.0.0.1"); put("server_port", port)
            put("version", "5")
            // Sent only when the core on the other end asked for them, which is
            // olcRTC and only olcRTC: it refuses the connection outright when
            // started with a credential pair and offered none, and on iOS it
            // always is — the app generates one on first run. That produced a
            // tunnel that came up, carried its own media perfectly, and passed
            // not one user connection. Xray's inbound has no auth, so for xhttp
            // these stay absent exactly as before.
            if (username.isNotBlank()) put("username", username)
            if (password.isNotBlank()) put("password", password)
        }
    }

    private fun renderTun(
        address: String,
        mtu: Int,
        resolveOverTcp: Boolean,
        routing: Routing,
        outbounds: JsonArrayBuilder.() -> Unit,
    ): String {
        val bypass = routing as? Routing.BypassRussia
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            if (bypass != null) {
                putBypassDns(bypass, remoteOverTcp = resolveOverTcp)
            } else if (resolveOverTcp) {
                // (existing comment block about lossy carriers, unchanged)
                putJsonObject("dns") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("type", "tcp"); put("tag", "dns-remote")
                            put("server", REMOTE_DNS_SERVER); put("detour", "out")
                        }
                    }
                }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add(address) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                }
            }
            putJsonArray("outbounds") {
                outbounds()
                if (bypass != null) addDirectOutbound()
            }
            if (bypass != null) {
                putBypassRoute(bypass)
            } else if (resolveOverTcp) {
                putJsonObject("route") {
                    putJsonArray("rules") {
                        // (existing comment, unchanged)
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                    }
                }
            }
        }
        return obj.toString()
    }

    private fun render(socksPort: Int, routing: Routing, outbounds: JsonArrayBuilder.() -> Unit): String {
        val bypass = routing as? Routing.BypassRussia
        val obj = buildJsonObject {
            // (existing log comment, unchanged)
            putJsonObject("log") { put("level", "warn") }
            if (bypass != null) putBypassDns(bypass, remoteOverTcp = false)
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "in")
                    put("listen", "127.0.0.1"); put("listen_port", socksPort)
                }
            }
            putJsonArray("outbounds") {
                outbounds()
                if (bypass != null) addDirectOutbound()
            }
            if (bypass != null) putBypassRoute(bypass)
        }
        return obj.toString()
    }

    private fun JsonArrayBuilder.addDirectOutbound() {
        addJsonObject { put("type", "direct"); put("tag", "direct") }
    }

    /**
     * The split: names on the Russian lists are resolved on the network
     * underneath, everything else through the tunnel.
     *
     * `dns-remote` first because the first server is what answers when no rule
     * claims a query, and `final` says so explicitly as well. `reverse_mapping`
     * keeps the name of every address sing-box handed out, so a connection to
     * that address is matched by the domain lists even when nothing in it can be
     * sniffed.
     */
    private fun JsonObjectBuilder.putBypassDns(bypass: Routing.BypassRussia, remoteOverTcp: Boolean) {
        putJsonObject("dns") {
            putJsonArray("servers") {
                addJsonObject {
                    put("type", if (remoteOverTcp) "tcp" else "udp"); put("tag", "dns-remote")
                    put("server", REMOTE_DNS_SERVER); put("detour", "out")
                }
                addJsonObject {
                    put("tag", "dns-direct")
                    when (val dns = bypass.directDns) {
                        DirectDns.System -> put("type", "local")
                        is DirectDns.Servers -> {
                            put("type", "udp"); put("server", dns.pick()); put("detour", "direct")
                        }
                        DirectDns.Placeholder -> {
                            put("type", "udp"); put("server", DIRECT_DNS_PLACEHOLDER); put("detour", "direct")
                        }
                    }
                }
            }
            putJsonArray("rules") {
                addJsonObject {
                    putJsonArray("rule_set") { RuleSets.domains.forEach { add(it.tag) } }
                    put("server", "dns-direct")
                }
            }
            put("final", "dns-remote")
            put("reverse_mapping", true)
        }
    }

    /**
     * The rules, in an order that matters:
     *
     * 1. `sniff`, so a TLS or HTTP connection carries its domain and the lists
     *    can match it. Without it every connection arriving by IP — which on
     *    iOS is all of them — could only match the IP list.
     * 2. `hijack-dns`, so the system's queries are answered here and split by
     *    the rules above rather than forwarded as datagrams to a public
     *    resolver through the tunnel.
     * 3. The local network direct: printers, routers, a NAS. Before the lists
     *    only because it is cheaper to match.
     * 4. The Russian lists direct. Domain matches come from the sniff or the
     *    reverse mapping; the IP list matches raw-address dials and, on iOS,
     *    every connection. sing-box skips IP rules for an unresolved name, so
     *    nothing here resolves a foreign name on the network underneath.
     *
     * `default_domain_resolver` is what an outbound uses to dial a *name*: the
     * server's own hostname in `out`, and any Russian name `direct` is handed.
     * Both belong on the network underneath. The tunnel-bound names never reach
     * it — sing-box sends those to the server unresolved.
     */
    private fun JsonObjectBuilder.putBypassRoute(bypass: Routing.BypassRussia) {
        putJsonObject("route") {
            putJsonArray("rule_set") {
                RuleSets.all.forEach { file ->
                    addJsonObject {
                        put("type", "local"); put("tag", file.tag)
                        put("format", "binary"); put("path", "${bypass.ruleSetDir}/${file.name}")
                    }
                }
            }
            putJsonArray("rules") {
                addJsonObject { put("action", "sniff") }
                addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                addJsonObject { put("ip_is_private", true); put("outbound", "direct") }
                addJsonObject {
                    putJsonArray("rule_set") { RuleSets.all.forEach { add(it.tag) } }
                    put("outbound", "direct")
                }
            }
            put("final", "out")
            put("default_domain_resolver", "dns-direct")
        }
    }
```

Also: `buildDesktopTun` keeps using the renamed `REMOTE_DNS_SERVER` (rename the two existing uses of `TCP_DNS_SERVER`); add `import kotlinx.serialization.json.JsonObjectBuilder`; update the class doc comment's list of shapes to mention the bypass variants and `buildSocksChain`.

- [ ] **Step 4: Run all net tests**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.*"` then `for f in sharedUI/build/test-results/jvmTest/TEST-org.olcbox.app.net.*.xml; do grep -o 'name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done`
Expected: every suite `failures="0" errors="0"`; `SingBoxConfigTest` now 40 tests, `RoutingTest` 4.

- [ ] **Step 5: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/Routing.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/RoutingTest.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt
git commit -m "feat(routing): sing-box shapes that send Russia and the local network direct"
```

---

### Task 3: Prove the shapes against the real sing-box

**Files:**
- Modify: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt`
- Create: `scripts/check-singbox-configs.sh`
- Modify: `.github/workflows/pr-checks.yml`

**Interfaces:**
- Consumes: `RuleSets.bytes`, `Routing.BypassRussia`, `DirectDns.*`, the builders from Task 2.

- [ ] **Step 1: Add the dumps (this is the test)**

Append to `SingBoxConfigDumpTest`:

```kotlin
    /**
     * Bypass Russia. Every rule-set is a real file here, because `sing-box check`
     * opens local rule-sets while building the router — a missing file fails the
     * check exactly as it would fail a connect.
     */
    @Test fun dumpBypassShapes() = kotlinx.coroutines.test.runTest {
        val rules = File(outDir, "rules").apply { mkdirs() }
        for (file in RuleSets.all) File(rules, file.name).writeBytes(RuleSets.bytes(file))
        val android = Routing.BypassRussia(rules.absolutePath, DirectDns.Servers(listOf("10.20.30.40")))
        val ios = Routing.BypassRussia(rules.absolutePath, DirectDns.Placeholder)

        val reality = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
                "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
                "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality"
        )
        assertNotNull(reality)
        val hy2 = LinkParser.parse(
            "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=1#RU-hy2"
        )
        assertNotNull(hy2)

        dump("bypass-socks-reality", SingBoxConfig.build(reality, routing = android))
        dump("bypass-socks-chain", SingBoxConfig.buildSocksChain(10808, username = "u", password = "p", routing = android))
        dump("bypass-tun-reality", SingBoxConfig.buildTun(reality, routing = ios))
        dump("bypass-tun-hysteria2", SingBoxConfig.buildTun(hy2, routing = ios))
        dump("bypass-tun-socks", SingBoxConfig.buildTunSocks(10810, routing = ios))
        dump("bypass-tun-socks-lossy", SingBoxConfig.buildTunSocks(10810, username = "u", password = "p", upstreamUdpIsLossy = true, routing = ios))
        assertTrue(File(outDir, "bypass-tun-socks-lossy.json").exists())
    }
```

```bash
# scripts/check-singbox-configs.sh
#!/usr/bin/env bash
# Runs `sing-box check` on every config the dump tests wrote, with the pinned
# release. Locally: SINGBOX_BIN=/path/to/sing-box scripts/check-singbox-configs.sh
# after `./gradlew :sharedUI:jvmTest --tests "org.olcbox.app.net.*"`. CI does the
# same in pr-checks. A shape that passes the Kotlin tests and fails here is a
# schema drift — exactly the class of bug that shipped an iOS tunnel which
# connected and carried nothing.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin="${SINGBOX_BIN:-sing-box}"
"$bin" version | head -1

shopt -s nullglob
configs=("$root"/sharedUI/build/singbox-configs/*.json)
if [ "${#configs[@]}" -eq 0 ]; then
  echo "no configs under sharedUI/build/singbox-configs — run the jvm net tests first" >&2
  exit 1
fi

failed=0
for config in "${configs[@]}"; do
  if "$bin" check -c "$config"; then
    echo "ok   $(basename "$config")"
  else
    echo "FAIL $(basename "$config")"
    failed=1
  fi
done
exit "$failed"
```

- [ ] **Step 2: Run locally**

Run:
```bash
cd /root/olcbox-fork && chmod +x scripts/check-singbox-configs.sh && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.net.*" && SINGBOX_BIN=/tmp/claude-0/-opt-proofkit/e1b4e1ef-7e60-4e50-95c3-472237a711d1/scratchpad/sb/sing-box scripts/check-singbox-configs.sh
```
Expected: `ok` for all 11 configs (5 existing + 6 bypass). A `FAIL` names the config; open `sharedUI/build/singbox-configs/<name>.json` and the sing-box message says which field.

- [ ] **Step 3: Add the check to pr-checks**

In `.github/workflows/pr-checks.yml`, add `SINGBOX_VERSION: "1.13.14"` to the job `env:` and, after the Gradle step, two steps:

```yaml
      - name: Download pinned sing-box
        run: |
          url="https://github.com/SagerNet/sing-box/releases/download/v${SINGBOX_VERSION}/sing-box-${SINGBOX_VERSION}-linux-amd64.tar.gz"
          curl -fsSL "$url" -o sb.tar.gz
          tar xzf sb.tar.gz
          sudo mv "sing-box-${SINGBOX_VERSION}-linux-amd64/sing-box" /usr/local/bin/sing-box
          sing-box version

      # Every shape the builders emit, against the release the app pins. The
      # net tests above wrote them; this is what `sing-box check` says about them.
      - name: sing-box check the emitted configs
        run: scripts/check-singbox-configs.sh
```

(The Gradle step already runs `:sharedUI:jvmTest`, which includes the dump tests.)

- [ ] **Step 4: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt scripts/check-singbox-configs.sh .github/workflows/pr-checks.yml
git commit -m "test(routing): check every bypass shape against the pinned sing-box, locally and on every push"
```

---

### Task 4: Persist the choice

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt` (bundle field), `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/repository/LocationsRepository.kt`, `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt` (uses `FakeLocationsDataSource` from `LocationsRepositoryImplTest.kt`, same package)

**Interfaces:**
- Produces: `@Serializable enum class RoutingMode { Global, BypassRussia; fun title(): String; fun summary(): String }`, `@Serializable data class RoutingSettings(val mode: RoutingMode = RoutingMode.Global)`, `LocationBundleV4.routing: RoutingSettings`, `LocationsRepository.getRoutingSettings(): RoutingSettings` / `saveRoutingSettings(settings: RoutingSettings)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt
package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingSettingsTest {
    @Test fun defaultIsEverythingThroughTheTunnel() = runTest {
        assertEquals(RoutingMode.Global, LocationsRepositoryImpl(FakeLocationsDataSource()).getRoutingSettings().mode)
    }

    @Test fun savedModeComesBack() = runTest {
        val source = FakeLocationsDataSource()
        val repository = LocationsRepositoryImpl(source)
        repository.saveRoutingSettings(RoutingSettings(RoutingMode.BypassRussia))
        assertEquals(RoutingMode.BypassRussia, repository.getRoutingSettings().mode)
        assertEquals(RoutingMode.BypassRussia, source.stored?.routing?.mode)
    }

    @Test fun aBundleWrittenBeforeRoutingExistedReadsAsGlobal() {
        val bundle = Json { ignoreUnknownKeys = true }.decodeFromString<LocationBundleV4>("""{"version":5,"locations":[]}""")
        assertEquals(RoutingMode.Global, bundle.routing.mode)
    }

    @Test fun serialNamesAreStable() {
        // Persisted on every platform; renaming a constant must not silently
        // reset everyone to Global.
        val json = Json.encodeToString(LocationBundleV4.serializer(), LocationBundleV4(routing = RoutingSettings(RoutingMode.BypassRussia)))
        assertTrue("\"routing\":{\"mode\":\"bypass_russia\"}" in json, json)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests "org.olcbox.app.data.datasource.RoutingSettingsTest"`
Expected: compilation FAILS (`Unresolved reference: RoutingMode`).

- [ ] **Step 3: Implement**

```kotlin
// sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt
package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What leaves through the tunnel. */
@Serializable
enum class RoutingMode {
    /** Everything. The exit is the server's, for every connection. */
    @SerialName("global")
    Global,

    /**
     * Russian sites, Russian TLDs and the local network go straight out; the
     * rest rides the tunnel, name resolution included. The lists are bundled
     * (see `RuleSets`), so this needs nothing from the network to work.
     */
    @SerialName("bypass_russia")
    BypassRussia;

    fun title(): String = when (this) {
        Global -> "All traffic through the tunnel"
        BypassRussia -> "Bypass Russia"
    }

    fun summary(): String = when (this) {
        Global -> "Every connection leaves through the tunnel. Your exit is the server's."
        BypassRussia -> "Russian sites, .ru domains and your local network go straight out. Everything else rides the tunnel, DNS included."
    }

    /** The one line the settings hub shows. */
    fun hubSummary(): String = when (this) {
        Global -> "Everything through the tunnel"
        BypassRussia -> "Russia and local network direct"
    }
}

/**
 * Carried inside [LocationBundleV4] for the reason [SubscriptionSettings] is:
 * one persisted copy per device, identical on every platform. A field with a
 * default reads back cleanly from a bundle written before it existed.
 */
@Serializable
data class RoutingSettings(
    @SerialName("mode")
    val mode: RoutingMode = RoutingMode.Global
)
```

In `LocationBundleV4` add, after `settings`:

```kotlin
    /** What leaves through the tunnel. See [RoutingSettings]; kept here for the reason stated on [settings]. */
    @SerialName("routing")
    val routing: RoutingSettings = RoutingSettings(),
```

In `LocationsRepository` interface, after `saveSubscriptionSettings`:

```kotlin
    /** What leaves through the tunnel. Persisted with the bundle, so one copy per device. */
    suspend fun getRoutingSettings(): RoutingSettings
    suspend fun saveRoutingSettings(settings: RoutingSettings)
```

In `LocationsRepositoryImpl` (LocationsDatasource.kt), after `saveSubscriptionSettings`:

```kotlin
    override suspend fun getRoutingSettings(): RoutingSettings = getBundle().routing

    override suspend fun saveRoutingSettings(settings: RoutingSettings) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(bundle.copy(routing = settings))
        }
    }
```

(Imports: `org.olcbox.app.data.model.RoutingSettings` in both files.) Check for other implementations of `LocationsRepository` — `grep -rn ": LocationsRepository" sharedUI/src` — and give any fake the two methods.

- [ ] **Step 4: Run the test and the whole jvmTest**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest` then `grep -L 'failures="0" errors="0"' sharedUI/build/test-results/jvmTest/*.xml`
Expected: the grep prints nothing (every suite has zero failures/errors); `RoutingSettingsTest` has 4 tests.

- [ ] **Step 5: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/RoutingSettings.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/data/repository/LocationsRepository.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt sharedUI/src/commonTest/kotlin/org/olcbox/app/data/datasource/RoutingSettingsTest.kt
git commit -m "feat(routing): a routing mode, persisted with the bundle on every platform"
```

---

### Task 5: The choice on screen — shared sheet, iOS and desktop wiring

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/HomeScreenModel.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/ApplicationSettingsSheet.kt`
- Modify: `sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/MainViewController.kt`, `desktopApp/src/main/kotlin/main.kt`

**Interfaces:**
- Consumes: `RoutingSettings`, `RoutingMode`, repository methods (Task 4)
- Produces: `HomeScreenModel.routingSettings: StateFlow<RoutingSettings>`, `HomeScreenModel.updateRoutingSettings(settings: RoutingSettings)`; `ApplicationSettingsSheet(..., routingSettings: RoutingSettings = RoutingSettings(), onRoutingSettingsChanged: (RoutingSettings) -> Unit = {}, routingUnavailableReason: String? = null, ...)`.

- [ ] **Step 1: HomeScreenModel**

After the `subscriptionSettings` block:

```kotlin
    private val _routingSettings = MutableStateFlow(RoutingSettings())
    val routingSettings = _routingSettings.asStateFlow()

    /**
     * Saves and, if a tunnel is up, restarts it: a routing choice that takes
     * effect at some unannounced later connect is the kind of setting people
     * toggle twice and stop trusting. Same gesture as a connection-mode change.
     */
    fun updateRoutingSettings(settings: RoutingSettings) {
        if (_routingSettings.value == settings) return
        _routingSettings.value = settings
        viewModelScope.launch {
            locationsRepository.saveRoutingSettings(settings)
            restartVpnIfRunning()
        }
    }
```

and in `init`, next to the subscription-settings load:

```kotlin
        viewModelScope.launch {
            _routingSettings.value = locationsRepository.getRoutingSettings()
        }
```

(Import `org.olcbox.app.data.model.RoutingSettings`.)

- [ ] **Step 2: ApplicationSettingsSheet**

Parameters, after `onSubscriptionSettingsChanged`:

```kotlin
    /** What leaves through the tunnel. See [RoutingSettings]. */
    routingSettings: RoutingSettings = RoutingSettings(),
    onRoutingSettingsChanged: (RoutingSettings) -> Unit = {},
    /**
     * Why the choice cannot be made on this platform, when it cannot. Shown
     * under the cards, which are then not selectable. Null where it applies.
     */
    routingUnavailableReason: String? = null,
```

Route enum: add `Routing` after `ConnectionMode`. Hub call gains `routingSummary = routingSettings.mode.hubSummary()` and `onRoutingClick = { route = SharedSettingsRoute.Routing }`; `SharedSettingsHubContent` gains those two parameters and, right after the Connection row (still under the "Connection" eyebrow):

```kotlin
        SharedNavigationRow(
            title = "Routing",
            value = routingSummary,
            icon = PkIcons.SwapVert,
            onClick = onRoutingClick
        )
```

The `when` gains:

```kotlin
                SharedSettingsRoute.Routing -> SharedRoutingSettingsContent(
                    settings = routingSettings,
                    unavailableReason = routingUnavailableReason,
                    onChanged = onRoutingSettingsChanged,
                    onBack = { route = SharedSettingsRoute.Hub }
                )
```

New composable, next to `SharedConnectionModeSettingsContent`:

```kotlin
@Composable
private fun SharedRoutingSettingsContent(
    settings: RoutingSettings,
    unavailableReason: String?,
    onChanged: (RoutingSettings) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Routing",
            subtitle = settings.mode.hubSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoutingMode.entries.forEach { mode ->
                SharedSelectableSettingsCard(
                    selected = settings.mode == mode,
                    icon = if (mode == RoutingMode.Global) PkIcons.Public else PkIcons.SwapVert,
                    title = mode.title(),
                    subtitle = mode.summary(),
                    enabled = unavailableReason == null,
                    onClick = { onChanged(settings.copy(mode = mode)) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // What "Russia" means here, because a list has edges and the person
        // choosing this deserves to know where they are.
        Text(
            text = unavailableReason
                ?: "Russian destinations are matched by lists bundled with the app: " +
                    "v2fly's category-ru, the Russian top-level domains and the Russian IP ranges. " +
                    "Names on those lists are resolved by the network you are on; every other name " +
                    "is resolved through the tunnel. Changing this restarts the connection.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalPkPalette.current.textDim
        )
    }
}
```

`SharedSelectableSettingsCard`'s subtitle is `maxLines = 1`; the Bypass subtitle is long. Change that composable's subtitle `maxLines` to 2 and its `Surface` height from a fixed `82.dp` to `.defaultMinSize(minHeight = 82.dp)` with `Row` padding `vertical = 12.dp`, so a two-line subtitle grows the card instead of being cut. Check the connection-mode screen still reads right on desktop after (`:desktopApp:compileKotlin` is the compile check; the layout is verified on device).

- [ ] **Step 3: iOS and desktop call sites**

`MainViewController.kt`: where `subscriptionSettings` is collected (`grep -n subscriptionSettings sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/MainViewController.kt`), collect `val routingSettings by dependencies.homeViewModel.routingSettings.collectAsState()` the same way, and pass to `ApplicationSettingsSheet`:

```kotlin
                    routingSettings = routingSettings,
                    onRoutingSettingsChanged = dependencies.homeViewModel::updateRoutingSettings,
```

`desktopApp/src/main/kotlin/main.kt`: same collection, and:

```kotlin
                        routingSettings = routingSettings,
                        onRoutingSettingsChanged = dependencies.homeViewModel::updateRoutingSettings,
                        // The desktop cores would dial "direct" into the daemon's
                        // own utun. Until the bypass lives in the daemon, the
                        // choice is shown and not offered.
                        routingUnavailableReason = "Applies on iOS and Android. Desktop follows in a later build.",
```

- [ ] **Step 4: Compile and test**

Run: `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest :desktopApp:compileKotlin`
Expected: BUILD SUCCESSFUL; `grep -L 'failures="0" errors="0"' sharedUI/build/test-results/jvmTest/*.xml` prints nothing.

- [ ] **Step 5: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/HomeScreenModel.kt sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/components/ApplicationSettingsSheet.kt sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/MainViewController.kt desktopApp/src/main/kotlin/main.kt
git commit -m "feat(settings): a Routing screen — everything through the tunnel, or Russia direct"
```

---

### Task 6: The choice on Android's own settings

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidAppSettingsSheets.kt`, `sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidMainScreen.kt`

**Interfaces:**
- Consumes: `HomeScreenModel.routingSettings` / `updateRoutingSettings` (Task 5), `RoutingMode` (Task 4)
- Produces: `AppSettingsSheet(..., routingSettings: RoutingSettings = RoutingSettings(), onRoutingSettingsChanged: (RoutingSettings) -> Unit = {}, ...)`

- [ ] **Step 1: AppSettingsSheet parameters**

After `onSubscriptionSettingsChanged` in `AppSettingsSheet`:

```kotlin
    routingSettings: RoutingSettings = RoutingSettings(),
    onRoutingSettingsChanged: (RoutingSettings) -> Unit = {},
```

Find where `SplitTunnelingSettingsContent(` is called inside `AppSettingsSheet` (`grep -n "SplitTunnelingSettingsContent(" …`) and pass `routingSettings = routingSettings, onRoutingModeSelected = { mode -> onRoutingSettingsChanged(routingSettings.copy(mode = mode)) }`.

- [ ] **Step 2: The Destinations section**

`SplitTunnelingSettingsContent` gains `routingSettings: RoutingSettings, onRoutingModeSelected: (RoutingMode) -> Unit` and, after the `when (settings.mode)` block:

```kotlin
        Spacer(Modifier.height(24.dp))

        // Apps decide *who* uses the tunnel; this decides *where to*. Kept on
        // the same screen because a person looking for "let Sber through" does
        // not know which of the two they want until both are in front of them.
        SettingsSectionLabel("Destinations")

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoutingMode.entries.forEach { mode ->
                SplitTunnelRoutingOption(
                    selected = routingSettings.mode == mode,
                    enabled = enabled,
                    icon = if (mode == RoutingMode.Global) PkIcons.Public else PkIcons.SwapVert,
                    title = mode.title(),
                    subtitle = mode.hubSummary(),
                    onClick = { onRoutingModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Russian destinations are matched by lists bundled with the app: " +
                "v2fly's category-ru, the Russian top-level domains and the Russian IP ranges. " +
                "Names on those lists are resolved by the network you are on; every other name " +
                "is resolved through the tunnel. Changing this restarts the connection.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalPkPalette.current.textDim
        )
```

(Imports: `org.olcbox.app.data.model.RoutingMode`, `RoutingSettings`; `Text`, `MaterialTheme`, `LocalPkPalette` are already used in this file — check with grep and add what is missing.)

- [ ] **Step 3: AndroidMainScreen wiring**

Next to `val subscriptionSettings by viewModel.subscriptionSettings.collectAsState()`:

```kotlin
    val routingSettings by viewModel.routingSettings.collectAsState()
```

and in the `AppSettingsSheet(` call, after `onSubscriptionSettingsChanged`:

```kotlin
            routingSettings = routingSettings,
            onRoutingSettingsChanged = viewModel::updateRoutingSettings,
```

- [ ] **Step 4: Verify what can be verified here**

There is no Android SDK on this box. Run `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest` (compiles commonMain, catches a wrong signature on the shared side) and read the two Android files' diffs once more against `AppSettingsSheet`'s parameter list and the `SplitTunnelingSettingsContent` call site. `:androidApp:assembleDebug` runs in pr-checks on push (Task 9).

- [ ] **Step 5: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidAppSettingsSheets.kt sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidMainScreen.kt
git commit -m "feat(android): Destinations — the routing choice beside the app split"
```

---

### Task 7: iOS — rule files through the App Group, the resolver filled in by the extension

**Files:**
- Modify: `sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/IosBridge.kt`, `sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt`
- Modify: `iosApp/iosApp/OlcboxIosApp.swift`, `iosApp/PacketTunnel/PacketTunnelProvider.swift`
- Create: `iosApp/PacketTunnel/DirectResolver.swift`, `iosApp/Tests/DirectResolverTests.swift`, `scripts/test-ios-direct-resolver.sh`

**Interfaces:**
- Consumes: `Routing`, `DirectDns.Placeholder`, `RuleSets`, `SingBoxConfig.*` (Tasks 1–2), `LocationsRepository.getRoutingSettings` (Task 4)
- Produces: `IosPacketTunnelStartRequest(config, xrayConfig, olcrtc, ruleSets: Map<String, String>)` — file name → base64; Swift `DirectResolver.substitute(in:resolvers:) -> String`, `DirectResolver.pick(_:) -> String`.

- [ ] **Step 1: Write the Swift test first**

```swift
// iosApp/Tests/DirectResolverTests.swift
import Foundation

// Run by scripts/test-ios-direct-resolver.sh. Plain asserts, no XCTest: the
// point is to run wherever there is a Swift compiler, a Mac or not.
func check(_ condition: Bool, _ message: String, line: Int = #line) {
    if !condition {
        print("FAIL line \(line): \(message)")
        exit(1)
    }
}

// pick: first IPv4, then IPv6 without a zone, then the fallback.
check(DirectResolver.pick(["[fe80::1%en0]:53", "10.11.12.13:53", "10.11.12.14:53"]) == "10.11.12.13", "first IPv4")
check(DirectResolver.pick(["[2001:db8::53]:53"]) == "2001:db8::53", "IPv6 without a zone")
check(DirectResolver.pick(["[2001:db8::53%pdp_ip0]:53"]) == "2001:db8::53", "zone stripped")
check(DirectResolver.pick(["[fe80::1%en0]:53"]) == DirectResolver.fallback, "link-local is not a resolver")
check(DirectResolver.pick([]) == DirectResolver.fallback, "nothing offered")

// substitute: only the placeholder, only quoted, every occurrence.
let config = #"{"dns":{"servers":[{"type":"udp","tag":"dns-direct","server":"198.51.100.53","detour":"direct"}]}}"#
check(DirectResolver.substitute(in: config, resolvers: ["10.0.0.1:53"]).contains(#""server":"10.0.0.1""#), "placeholder replaced")
check(!DirectResolver.substitute(in: config, resolvers: ["10.0.0.1:53"]).contains("198.51.100.53"), "placeholder gone")
check(DirectResolver.substitute(in: config, resolvers: []).contains(#""server":"77.88.8.8""#), "fallback when nothing offered")
let global = #"{"log":{"level":"warn"}}"#
check(DirectResolver.substitute(in: global, resolvers: ["10.0.0.1:53"]) == global, "a config without the placeholder is untouched")

print("ok")
```

```bash
# scripts/test-ios-direct-resolver.sh
#!/usr/bin/env bash
# Compiles DirectResolver.swift with its test and runs it. `xcrun` on a Mac;
# any swift.org toolchain's swiftc elsewhere (SWIFTC=/path/to/swiftc).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

swiftc="${SWIFTC:-}"
if [ -z "$swiftc" ]; then
  if command -v xcrun >/dev/null 2>&1; then swiftc="xcrun swiftc"; else swiftc="swiftc"; fi
fi

$swiftc \
  "$root/iosApp/PacketTunnel/DirectResolver.swift" \
  "$root/iosApp/Tests/DirectResolverTests.swift" \
  -o "$test_dir/direct-resolver-tests"
"$test_dir/direct-resolver-tests"
```

- [ ] **Step 2: Get a Swift compiler and watch it fail**

The swift.org ubi9 toolchain runs on this box (see memory `feedback_swift_typecheck_on_linux`). Into the scratchpad:

```bash
cd /tmp/claude-0/-opt-proofkit/e1b4e1ef-7e60-4e50-95c3-472237a711d1/scratchpad && curl -fsSL -o swift.tar.gz https://download.swift.org/swift-6.1.2-release/ubi9/swift-6.1.2-RELEASE/swift-6.1.2-RELEASE-ubi9.tar.gz && mkdir -p swift && tar xzf swift.tar.gz -C swift --strip-components=1 && swift/usr/bin/swiftc --version
```

(If that exact version is gone, take the latest `swift-<ver>-RELEASE-ubi9.tar.gz` listed at https://www.swift.org/install/linux/ .)

Run: `cd /root/olcbox-fork && chmod +x scripts/test-ios-direct-resolver.sh && SWIFTC=/tmp/claude-0/-opt-proofkit/e1b4e1ef-7e60-4e50-95c3-472237a711d1/scratchpad/swift/usr/bin/swiftc scripts/test-ios-direct-resolver.sh`
Expected: FAILS with `cannot find 'DirectResolver' in scope`.

- [ ] **Step 3: DirectResolver.swift**

```swift
// iosApp/PacketTunnel/DirectResolver.swift
import Foundation

/// Fills in the resolver "direct" traffic uses under Bypass Russia.
///
/// The Kotlin builder cannot know it: the network's resolvers can only be read
/// before `setTunnelNetworkSettings`, from inside the extension, after the
/// config was written by another process. So the config carries a placeholder
/// — TEST-NET-2, an address nothing routes to — and this replaces it with the
/// first resolver `ResolverSnapshot` found, or Yandex DNS when it found none.
/// A string substitution, not a JSON edit: the placeholder is quoted and
/// unique, `SingBoxConfigTest` pins that it appears exactly once, and a parser
/// here would be a second implementation of the config's shape.
///
/// The value must match `SingBoxConfig.DIRECT_DNS_PLACEHOLDER` and
/// `SingBoxConfig.DIRECT_DNS_FALLBACK`.
enum DirectResolver {
    static let placeholder = "198.51.100.53"
    static let fallback = "77.88.8.8"

    /// The first IPv4, else the first IPv6 that is not link-local, else the
    /// fallback. `ResolverSnapshot` renders servers as `host:port` and
    /// `[v6%zone]:port`; sing-box wants a bare address, and a zone is
    /// something it cannot carry.
    static func pick(_ servers: [String]) -> String {
        let hosts = servers.map(host(of:)).filter { !$0.isEmpty }
        if let v4 = hosts.first(where: { $0.contains(".") && !$0.contains(":") && !$0.hasPrefix("127.") }) {
            return v4
        }
        if let v6 = hosts.first(where: { $0.contains(":") && !$0.lowercased().hasPrefix("fe80:") && $0 != "::1" }) {
            return v6
        }
        return fallback
    }

    static func substitute(in config: String, resolvers: [String]) -> String {
        let quoted = "\"\(placeholder)\""
        guard config.contains(quoted) else { return config }
        return config.replacingOccurrences(of: quoted, with: "\"\(pick(resolvers))\"")
    }

    private static func host(of server: String) -> String {
        var value = server.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("[") {
            // [v6%zone]:port
            guard let close = value.firstIndex(of: "]") else { return "" }
            value = String(value[value.index(after: value.startIndex)..<close])
        } else if let colon = value.lastIndex(of: ":"), value.filter({ $0 == ":" }).count == 1 {
            // v4:port — a bare IPv6 has more than one colon and no port here.
            value = String(value[..<colon])
        }
        if let percent = value.firstIndex(of: "%") {
            value = String(value[..<percent])
        }
        return value
    }
}
```

Run the test script again. Expected: `ok`.

- [ ] **Step 4: The extension uses it, and snapshots for every transport**

`PacketTunnelProvider.swift`, where the snapshot is taken (currently `let resolvers = olcrtc == nil ? [] : ResolverSnapshot.servers()`):

```swift
        // Before the tunnel's settings go on: from then on the system resolver
        // is our own tun, and the servers of the network underneath can no
        // longer be read. For every transport now, not only olcRTC — under
        // Bypass Russia the config wants one of them too. Counted rather than
        // listed in the trace — they are the network's addresses, and the
        // trace keeps to interface names.
        let resolvers = ResolverSnapshot.servers()
        NetworkDiagnostics.record("resolvers from the network: \(resolvers.count)")
```

and where the config is handed to libbox (`try server.startOrReloadService(config, options: LibboxOverrideOptions())`), substitute first:

```swift
            // Bypass Russia: the config names a placeholder where the direct
            // resolver goes, because only this process could read it. Global
            // configs carry no placeholder and pass through untouched.
            let config = DirectResolver.substitute(in: config, resolvers: resolvers)
            try server.startOrReloadService(config, options: LibboxOverrideOptions())
```

(`config` is a `let` parameter of `startEngine`; shadowing it with a local `let` is fine in Swift. Keep the later `log.info("sing-box started, config \(config.count …` reading the substituted one.)

- [ ] **Step 5: Kotlin request and Swift hand-over**

`IosBridge.kt`:

```kotlin
data class IosPacketTunnelStartRequest(
    val config: String,
    val xrayConfig: String?,
    val olcrtc: IosOlcRtcStartRequest?,
    /**
     * The rule-set files the config refers to, file name → base64 of the
     * bytes, or empty when it refers to none. The bridge writes them where
     * libbox resolves `rules/<name>` — its working directory in the App Group
     * — and removes the directory when there is nothing to write, so a stale
     * list cannot outlive the routing choice that put it there. Base64 because
     * a Kotlin ByteArray crosses to Swift as an object with a getter per byte.
     */
    val ruleSets: Map<String, String> = emptyMap()
)
```

(Extend the doc comment above the class with a sentence on `ruleSets`.)

`IosVpnManager.kt` — in `packetTunnelRequest`, before the olcRTC branch:

```kotlin
        val routing = routing()
        val ruleSets = ruleSetsFor(routing)
```

pass `routing = routing` to `buildTunSocks` (both calls) and `buildTun`, and `ruleSets = ruleSets` to both `IosPacketTunnelStartRequest(...)` constructions. Add:

```kotlin
    /**
     * The persisted routing choice, resolved for the extension: rule files by
     * the relative path libbox resolves against its working directory, and a
     * placeholder where the direct resolver goes, because only the extension
     * can read the network's own before the tunnel replaces it.
     */
    private suspend fun routing(): Routing =
        when (val mode = locationsRepository.getRoutingSettings().mode) {
            RoutingMode.Global -> Routing.Global
            RoutingMode.BypassRussia -> {
                addLog("Routing: ${mode.hubSummary()}")
                Routing.BypassRussia(RuleSets.IOS_RELATIVE_DIR, DirectDns.Placeholder)
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun ruleSetsFor(routing: Routing): Map<String, String> =
        if (routing is Routing.BypassRussia) {
            RuleSets.all.associate { it.name to Base64.encode(RuleSets.bytes(it)) }
        } else {
            emptyMap()
        }
```

Imports: `org.olcbox.app.data.model.RoutingMode`, `org.olcbox.app.net.DirectDns`, `org.olcbox.app.net.Routing`, `org.olcbox.app.net.RuleSets`, `kotlin.io.encoding.Base64`, `kotlin.io.encoding.ExperimentalEncodingApi`.

`OlcboxIosApp.swift` — in `SwiftPacketTunnelBridge.start`, inside the `do` block after `olcrtc.json`:

```swift
            // Written or removed, like the two above: a `rules/` left behind
            // by a Bypass Russia connection is harmless to a Global config,
            // which never names it, but a config that names a file the app
            // failed to write stops libbox with "no such file" — so the write
            // happens here, before the extension is asked for anything.
            try Self.handOverRuleSets(
                request.ruleSets,
                into: container.appendingPathComponent("libbox/work/rules", isDirectory: true)
            )
```

and next to `handOver`:

```swift
    /// Writes each file, or removes the directory when there is nothing to write.
    /// The directory is libbox's working path plus the relative directory the
    /// Kotlin config uses (`RuleSets.IOS_RELATIVE_DIR`); the two must agree.
    private static func handOverRuleSets(_ files: [String: String], into directory: URL) throws {
        let fileManager = FileManager.default
        if files.isEmpty {
            if fileManager.fileExists(atPath: directory.path) {
                try fileManager.removeItem(at: directory)
            }
            return
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        for (name, base64) in files {
            guard let data = Data(base64Encoded: base64) else {
                throw NSError(
                    domain: "org.proofkit.app", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "rule-set \(name) is not base64"]
                )
            }
            try data.write(to: directory.appendingPathComponent(name))
        }
    }
```

- [ ] **Step 6: Typecheck the Swift edits on Linux**

Extract the changed `handOverRuleSets` into a harness with the shims from `feedback_swift_typecheck_on_linux` (it needs only Foundation) and `swiftc -typecheck` it under `-swift-version 5` and `6`; `PacketTunnelProvider.swift` changes are three lines — re-read them against the surrounding code. Kotlin side: `./gradlew --no-daemon :sharedUI:jvmTest` (iosMain does not compile here; it compiles in the user's Xcode build — re-read the two Kotlin edits for imports and names).

- [ ] **Step 7: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/IosBridge.kt sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt iosApp/iosApp/OlcboxIosApp.swift iosApp/PacketTunnel/PacketTunnelProvider.swift iosApp/PacketTunnel/DirectResolver.swift iosApp/Tests/DirectResolverTests.swift scripts/test-ios-direct-resolver.sh
git commit -m "feat(ios): Bypass Russia — rule files through the App Group, the direct resolver filled in by the extension"
```

---

### Task 8: Android — the service routes, and fronts olcRTC and xhttp with sing-box

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`

**Interfaces:**
- Consumes: `Routing`, `DirectDns.Servers`, `RuleSets`, `SingBoxConfig.build/buildSocksChain` (Tasks 1–2), `RoutingMode`, repository (Task 4).

- [ ] **Step 1: State and the resolved routing**

Fields, after `activeCorePort`:

```kotlin
    /** The routing choice read at the last start, so a reconnect in place keeps it. */
    private var routingMode = RoutingMode.Global
```

In `startTunnel`, right after `val location = active?.location?.normalized()` succeeds (the `if (location == null …) return` guard), add:

```kotlin
                    routingMode = repository.getRoutingSettings().mode
```

New functions (near `upstreamDnsList`):

```kotlin
    /**
     * What the builders get for [routingMode]: the rule files on disk and the
     * network's resolvers for direct names. Files are rewritten on every start —
     * 59 KB, and the alternative is a version check that can be wrong.
     */
    private suspend fun routingFor(upstream: Network?): Routing = when (routingMode) {
        RoutingMode.Global -> Routing.Global
        RoutingMode.BypassRussia -> {
            val dir = File(filesDir, RULE_SETS_DIR).apply { mkdirs() }
            for (file in RuleSets.all) File(dir, file.name).writeBytes(RuleSets.bytes(file))
            addLog("Routing: ${routingMode.hubSummary()}")
            Routing.BypassRussia(
                ruleSetDir = dir.absolutePath,
                directDns = DirectDns.Servers(upstreamDnsAddresses(upstream))
            )
        }
    }

    /** The network's resolvers as the system lists them, for the direct DNS server. */
    private fun upstreamDnsAddresses(network: Network?): List<String> =
        network?.let { connectivityManager.getLinkProperties(it)?.dnsServers }
            ?.mapNotNull { it.hostAddress }
            .orEmpty()
```

Constants (in the companion, next to `TUN_MTU`):

```kotlin
        private const val RULE_SETS_DIR = "rulesets"

        /** Where Xray listens when sing-box fronts it, so the front can keep the core port. */
        private const val XRAY_BEHIND_FRONT_PORT = 10811
```

- [ ] **Step 2: startTransport and startCore**

```kotlin
    private suspend fun startTransport(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val routing = routingFor(upstream)
        return if (location.kind == LocationKind.Olcrtc) {
            activeCorePort = null
            val started = startMobile(location, upstream, requestedGeneration, setErrorOnFailure)
            if (started && routing is Routing.BypassRussia) startFront(routing, setErrorOnFailure) else started
        } else {
            startCore(location, setErrorOnFailure, routing)
        }
    }

    /**
     * sing-box between hev-socks5-tunnel and olcRTC, so the routing rules see
     * every connection before the relay does. Only in tun mode: in proxy mode
     * the promised endpoint is olcRTC's own port, and a front there would be a
     * second port nobody was told about.
     */
    private suspend fun startFront(routing: Routing.BypassRussia, setErrorOnFailure: Boolean): Boolean {
        if (connectionMode != AndroidConnectionMode.Tun) {
            addLog("Routing: proxy mode keeps olcRTC global")
            return true
        }
        val port = SingBoxConfig.SINGBOX_SOCKS_PORT
        return try {
            stopCoreProcesses()
            waitForSocksPortReleased(port, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            singBoxCore.start(
                SingBoxConfig.buildSocksChain(
                    upstreamPort = socksListenPort,
                    socksPort = port,
                    username = socksUsername,
                    password = socksPassword,
                    routing = routing
                )
            )
            activeCorePort = port
            if (!waitForSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
                addLog(singBoxCore.diagnostics())
                error("sing-box front SOCKS not ready on $port")
            }
            coroutineContext.ensureActive()
            addLog("sing-box front ready on $socksListenHost:$port")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopCoreProcesses() }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "sing-box front failed"
            addLog("front start failed: $msg")
            stopCoreProcesses()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }
```

In `startCore`, add the parameter `routing: Routing` and replace the core-selection block:

```kotlin
            val fronted = routing is Routing.BypassRussia && connectionMode == AndroidConnectionMode.Tun
            if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) {
                if (fronted) {
                    // Xray does not route; sing-box does, so it goes in front.
                    xrayCore.start(XrayConfig.buildXhttp(spec, socksPort = XRAY_BEHIND_FRONT_PORT))
                    singBoxCore.start(SingBoxConfig.buildSocksChain(XRAY_BEHIND_FRONT_PORT, socksPort = port, routing = routing))
                    label = "sing-box front + Xray/xhttp"
                    diagnose = { singBoxCore.diagnostics() + "\n" + xrayCore.diagnostics() }
                } else {
                    if (routing is Routing.BypassRussia) addLog("Routing: proxy mode keeps xhttp global")
                    xrayCore.start(XrayConfig.buildXhttp(spec, socksPort = port))
                    label = "Xray/xhttp"
                    diagnose = xrayCore::diagnostics
                }
            } else {
                singBoxCore.start(SingBoxConfig.build(spec, socksPort = port, routing = routing))
                label = "sing-box/${location.kind}"
                diagnose = singBoxCore::diagnostics
            }
```

`isActiveTransportRunning()`: read the whole function, and make the olcRTC-with-front case demand both — where it returns `singBoxCore.isRunning() || xrayCore.isRunning()` for `activeCorePort != null`, the olcRTC-fronted case (`lastMobileProvider != null && Mobile.isRunning()` is the engine half) must be `Mobile.isRunning() && singBoxCore.isRunning()`. Implement with a flag `private var frontsOlcrtc = false` set true in `startFront` on success, false in `stopCoreProcesses()`, and:

```kotlin
    private fun isActiveTransportRunning(): Boolean =
        if (frontsOlcrtc) {
            Mobile.isRunning() && singBoxCore.isRunning()
        } else if (activeCorePort != null) {
            singBoxCore.isRunning() || xrayCore.isRunning()
        } else {
            Mobile.isRunning()
        }
```

(Adapt to what the function actually contains — keep its existing branches, add the fronted one first.)

Imports: `org.olcbox.app.data.model.RoutingMode`, `org.olcbox.app.net.DirectDns`, `org.olcbox.app.net.Routing`, `org.olcbox.app.net.RuleSets`, `java.io.File` (check what is already imported).

- [ ] **Step 3: Re-read, then compile what compiles**

`stopMobile()` already calls `stopCoreProcesses()`, so a front started after olcRTC is stopped with it, and `reconnectTransport` → `startTransport` re-reads `routingFor(upstream)` with the new network's resolvers. Confirm both by reading the two functions again after the edit. Run `cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest` for the shared side; `:androidApp:assembleDebug` runs in pr-checks (Task 9).

- [ ] **Step 4: Commit**

```bash
cd /root/olcbox-fork && git add sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt
git commit -m "feat(android): Bypass Russia — sing-box routes every transport in tun mode, fronting olcRTC and xhttp"
```

---

### Task 9: Notes, push, CI

**Files:**
- Modify: `docs/testflight-notes.md`
- Memory: `/root/.claude/projects/-opt-proofkit/memory/project_bypass_russia_routing.md` + index line

- [ ] **Step 1: TestFlight notes**

Replace the "This build is about …" section of `docs/testflight-notes.md` with:

```
This build adds a routing choice. Settings → Routing: "All traffic through the
tunnel" (what every build so far did) or "Bypass Russia". Under Bypass Russia,
Russian sites, .ru domains and your local network go straight out; everything
else — and every name lookup for it — rides the tunnel.

WHAT CHANGED

• Bypass Russia routes by three bundled lists: v2fly's category-ru, the Russian
  top-level domains, and Russian IP ranges. Nothing is downloaded; the lists
  ship with the app.
• Names on those lists are resolved by the resolver of the network you were on
  when you connected. Everything else resolves through the tunnel, over TCP
  when the room is an olcRTC one.
• Changing the choice while connected reconnects.

WHAT TO TEST

1. Bypass Russia on, connect to any location. Open sberbank.ru, gosuslugi.ru,
   ozon.ru — they should open, and 2ip.ru should show your real address.
   ipify.org (or any foreign site showing your IP) should show the exit's.
2. Same, on an olcRTC room: Russian sites should be noticeably quicker than
   foreign ones — they no longer share the room's bandwidth.
3. Wi-Fi → cellular, or back, while connected with Bypass Russia on. Known
   limitation: Russian names may stop resolving until you reconnect (the
   resolver is the one captured at connect). Tell us if it happens and on
   which carrier.
4. Switch back to "All traffic through the tunnel": 2ip.ru should now show the
   exit's address.
5. Memory: nothing should change, but if the tunnel drops on its own under
   Bypass Russia, say so — the extension's ceiling is the one thing the lists
   could push on.
```

- [ ] **Step 2: Memory**

Write `project_bypass_russia_routing.md` (type project): what shipped, the design decisions above (why placeholder on iOS, why desktop deferred, why tld-ru is a separate file, hev mapdns hands hostnames), the known iOS network-switch limitation, and the verification state (jvmTest + local sing-box check green; Android/iOS compiled only in CI/Xcode). Add the index line to `MEMORY.md`.

- [ ] **Step 3: Push the branch and watch pr-checks**

```bash
cd /root/olcbox-fork && git push proofkit feat/bypass-russia
sleep 60 && curl -s "https://api.github.com/repos/ghostlane-project/ghostlane/actions/runs?branch=feat/bypass-russia&per_page=3" | python3 -c 'import sys,json; [print(r["name"], r["status"], r["conclusion"], r["html_url"]) for r in json.load(sys.stdin)["workflow_runs"]]'
```

Expected: "PR Checks" `completed success`. It compiles `androidApp:assembleDebug` (the Android edits of Tasks 6 and 8) and runs `sing-box check` (Task 3). On failure, read the job log through the API and fix on the branch.

- [ ] **Step 4: Commit the notes**

```bash
cd /root/olcbox-fork && git add docs/testflight-notes.md && git commit -m "docs(testflight): what testers should look at in the Bypass Russia build" && git push proofkit feat/bypass-russia
```

---

## Self-review (done while writing)

- Spec coverage: setting (T4), UI on all three platforms (T5, T6), builder shapes (T2), real-binary check (T3), rule files on device (T1 assets; T7 iOS write; T8 Android write), direct resolver per platform (T2 model; T7 iOS patch; T8 Android list), Android front for olcRTC/xhttp (T8), desktop explicitly deferred with UI note (T5), docs (T9).
- Names used across tasks: `RuleSets.all/domains/bytes/IOS_RELATIVE_DIR`, `Routing.Global/BypassRussia(ruleSetDir, directDns)`, `DirectDns.System/Servers(addresses).pick()/Placeholder`, `SingBoxConfig.DIRECT_DNS_PLACEHOLDER/DIRECT_DNS_FALLBACK/buildSocksChain`, `RoutingMode.title()/summary()/hubSummary()`, `RoutingSettings(mode)`, `LocationsRepository.getRoutingSettings/saveRoutingSettings`, `HomeScreenModel.routingSettings/updateRoutingSettings`, `IosPacketTunnelStartRequest.ruleSets`, `DirectResolver.pick/substitute/placeholder/fallback` — consistent.
