# olcRTC on Android without the sing-box front — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Android, olcRTC under Bypass Russia / Iran / China runs the engine alone with the region's direct rules, in tun and proxy mode; the sing-box front goes.

**Architecture:** The rules reach the engine as text through `Runtime.setDirectRules` (engine `internal/route`, pinned `7b78fd4a753c`), exactly as on iOS. The Iranian and Chinese lists join the Russian ones in the text form `tools/xray-geodata` already produces. The UDP relay stays on under rules so regional UDP still goes direct on rooms with no datagram lane.

**Tech Stack:** Kotlin Multiplatform (`sharedUI`: commonMain, jvmAndroidMain, androidMain), kotlin.test, Go (`tools/xray-geodata`), Gradle.

**Spec:** `docs/superpowers/specs/2026-09-24-olcrtc-engine-rules-android-design.md`

## Global Constraints

- Android only. No change to desktop code, iOS code (`iosMain`, `iosApp/`), `SingBoxConfig` or any sing-box config shape.
- The engine stays at `OLCRTC_VERSION` `v0.0.0-20260923124229-7b78fd4a753c`; no engine change.
- The three Russian text lists stay byte-identical (sha256 `44bef183…`, `725c5c55…`, `124350e0…`); `XrayGeodata.lists()` and `OlcrtcDirectRules.text()` return exactly what they return today.
- Pinned v2fly inputs: `dlc.dat` tag `20260908094002` sha256 `35ed26a24cafa1256bd7261414224b7bcef5c944cea7760e172b030a8b266450`; `geoip.dat` tag `202609050329` sha256 `1cba1f0982cf62502fa079c66047c3d0c608196da5b3305671e68f60e917a482`.
- Regions are the strings `ru`, `ir`, `cn` (`RoutingMode.region`); an unknown one is an error, never an empty list.
- DATA is a shared prod box: run Gradle with `-Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process --no-parallel --max-workers=2`, check `free -g` first (MemAvailable above 3 GiB), and `./gradlew --stop` afterwards.
- Commit messages end with the session's Co-Authored-By / Claude-Session lines.

Gradle test command used below (from the worktree root `/root/olcbox-wt-engine-rules`):

```bash
./gradlew -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process \
  --no-parallel --max-workers=2 :sharedUI:jvmTest --tests '<class>'
```

---

### Task 1: Iranian and Chinese lists in the engine's text form

**Files:**
- Modify: `tools/xray-geodata/main.go` (the code lists)
- Modify: `tools/xray-geodata.sh` (header comment, the lock's sha256 list)
- Create (generated): `sharedUI/src/commonMain/composeResources/files/xray/{geosite-category-ir,geosite-cn,geosite-tld-cn,geoip-ir,geoip-cn}.txt`
- Modify (generated): `tools/xray-geodata.lock`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/XrayGeodata.kt`
- Modify: `androidApp/src/androidTest/kotlin/org/olcbox/app/CorePackagingTest.kt:41`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/XrayGeodataTest.kt`

**Interfaces:**
- Produces: `XrayGeodata.bundled: List<File>` (all eight), `XrayGeodata.regional(region: String): List<File>`, `suspend fun XrayGeodata.lists(region: String): Lists`; `lists()` stays and equals `lists("ru")`.

- [ ] **Step 1: Write the failing tests** — in `XrayGeodataTest`, change the hash loop to `XrayGeodata.bundled` and add:

```kotlin
    @Test fun russiaByRegionIsWhatXrayInlines() = runTest {
        // iOS inlines lists() into Xray and hands the same rules to the
        // engine; asking by region must not change a byte of either.
        val byRegion = XrayGeodata.lists("ru")
        val plain = XrayGeodata.lists()
        assertEquals(plain.domains, byRegion.domains)
        assertEquals(plain.cidrs, byRegion.cidrs)
    }

    @Test fun iranAndChinaCarryTheirOwnLists() = runTest {
        val ir = XrayGeodata.lists("ir")
        assertTrue(ir.domains.size > 150, "category-ir is over 150 names, got ${ir.domains.size}")
        assertTrue(ir.cidrs.size > 1_500, "geoip:ir is over 1500 IPv4 prefixes, got ${ir.cidrs.size}")
        assertTrue("domain:ir" in ir.domains)
        assertTrue("domain:digikala.com" in ir.domains)
        val cn = XrayGeodata.lists("cn")
        assertTrue(cn.domains.size > 6_000, "cn and tld-cn are over 6000 names, got ${cn.domains.size}")
        assertTrue(cn.cidrs.size > 8_000, "geoip:cn is over 8000 IPv4 prefixes, got ${cn.cidrs.size}")
        assertTrue("domain:cn" in cn.domains)
        assertTrue("domain:baidu.com" in cn.domains)
        val cidr = Regex("""^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$""")
        val prefixes = listOf("domain:", "full:", "keyword:", "regexp:")
        for ((region, lists) in listOf("ir" to ir, "cn" to cn)) {
            for (rule in lists.domains) assertTrue(prefixes.any { rule.startsWith(it) }, "$region: not a name rule: $rule")
            for (rule in lists.cidrs) assertTrue(cidr.matches(rule), "$region: not an IPv4 prefix: $rule")
        }
    }

    @Test fun anUnknownRegionIsAnError() {
        assertFailsWith<IllegalStateException> { XrayGeodata.regional("future") }
    }
```

(add `import kotlin.test.assertFailsWith`).

- [ ] **Step 2: Run to see it fail** — the test command with `--tests 'org.olcbox.app.net.XrayGeodataTest'`. Expected: compilation fails on `bundled`, `regional`, `lists(String)`.

- [ ] **Step 3: Teach the generator the new codes** — in `tools/xray-geodata/main.go`:

```go
// The lists each bypass region needs: Russia (what iOS inlines into Xray and
// hands the olcRTC engine), and Iran and China for the olcRTC engine on
// Android. geosite-<code>.txt and geoip-<code>.txt each.
var geositeCodes = []string{"category-ru", "tld-ru", "category-ir", "cn", "tld-cn"}

var geoipCodes = []string{"ru", "ir", "cn"}
```

and replace `writeGeoip` so it writes every code in `geoipCodes` (a missing code is an error, as today; the IPv4-only rule is unchanged):

```go
func writeGeoip(path, out string) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var list geodata.GeoIPList
	if err := proto.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	byCode := map[string]*geodata.GeoIP{}
	for _, entry := range list.Entry {
		byCode[strings.ToLower(entry.Code)] = entry
	}
	for _, code := range geoipCodes {
		entry, ok := byCode[code]
		if !ok {
			return fmt.Errorf("%s has no code %q", path, code)
		}
		if entry.ReverseMatch {
			return fmt.Errorf("%s/%s is a reverse-match list, which inline rules cannot express", path, code)
		}
		lines := make([]string, 0, len(entry.Cidr))
		for _, c := range entry.Cidr {
			ip := net.IP(c.Ip)
			switch len(c.Ip) {
			case net.IPv4len:
			case net.IPv6len:
				if !*withIPv6 {
					continue
				}
			default:
				return fmt.Errorf("%s/%s: address of %d bytes", path, code, len(c.Ip))
			}
			lines = append(lines, fmt.Sprintf("%s/%d", ip.String(), c.Prefix))
		}
		if err := writeLines(filepath.Join(out, "geoip-"+code+".txt"), lines); err != nil {
			return err
		}
	}
	return nil
}
```

Update the package comment's first line to "…to the lists the bypass regions need…". In `tools/xray-geodata.sh`, update the header ("Refreshes the Xray-side bypass lists…: geosite category-ru, tld-ru, category-ir, cn and tld-cn out of dlc.dat, geoip ru, ir and cn out of geoip.dat") and list the eight files in the `sha256sum` line, Russian three first:

```bash
  (cd "$dest" && sha256sum geosite-category-ru.txt geosite-tld-ru.txt geoip-ru.txt \
    geosite-category-ir.txt geoip-ir.txt geosite-cn.txt geosite-tld-cn.txt geoip-cn.txt)
```

- [ ] **Step 4: Generate** — `PATH=/usr/local/go/bin:$PATH tools/xray-geodata.sh`. Then `git status --short sharedUI/src/commonMain/composeResources/files/xray tools/xray-geodata.lock` must show five new files and the lock only: the Russian three unchanged.

- [ ] **Step 5: Pin them in `XrayGeodata`** — add five `File` constants whose sha256 values match the new lock lines (these are the values the pinned inputs produce; if the lock says otherwise, the lock wins and the difference is investigated before going on):

```kotlin
    val GEOSITE_CATEGORY_IR = File(
        name = "geosite-category-ir.txt",
        sha256 = "93abdb349b529b51d672134fafa5b44bbd646803d1dd49c518cf6b562934d897"
    )
    val GEOIP_IR = File(
        name = "geoip-ir.txt",
        sha256 = "dff619714e5970d8b175cd10365d5cb93180bb12fd13453c9e367001840e0afe"
    )
    val GEOSITE_CN = File(
        name = "geosite-cn.txt",
        sha256 = "71ee69310fb5939b1bf2a37cd6f4b42b63cdfa919ecbcc761108b1b161fa0ba3"
    )
    val GEOSITE_TLD_CN = File(
        name = "geosite-tld-cn.txt",
        sha256 = "eacf4787727233e709ca4858f3640575adb0a21171a75d773c19b56534455097"
    )
    val GEOIP_CN = File(
        name = "geoip-cn.txt",
        sha256 = "419111c68a2cbbf94236126bc4cd2fd30ac43274b6b65a640e0d2a6f0e05f014"
    )
```

and:

```kotlin
    /** Every list the app bundles, for the hash check and the packaging test. */
    val bundled: List<File> = all + listOf(GEOSITE_CATEGORY_IR, GEOIP_IR, GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)

    /** The files of one bypass region (`ru`, `ir`, `cn`), names first. */
    fun regional(region: String): List<File> = when (region) {
        "ru" -> all
        "ir" -> listOf(GEOSITE_CATEGORY_IR, GEOIP_IR)
        "cn" -> listOf(GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)
        else -> error("Unsupported routing region: $region")
    }

    /** The Russian lists: what [XrayConfig.buildXhttp] inlines on iOS. */
    suspend fun lists(): Lists = lists("ru")

    suspend fun lists(region: String): Lists {
        val files = regional(region)
        return Lists(
            domains = files.filter { it.name.startsWith("geosite-") }.flatMap { parse(bytes(it).decodeToString()) },
            cidrs = files.filter { it.name.startsWith("geoip-") }.flatMap { parse(bytes(it).decodeToString()) },
        )
    }
```

and reword the object's KDoc from "the Bypass Russia lists" to the regional lists (Russia for Xray on iOS and the engine everywhere; Iran and China for the engine on Android).

- [ ] **Step 6: Run the tests** — same command. Expected: `XrayGeodataTest` passes, all tests.

- [ ] **Step 7: Packaging test** — `CorePackagingTest.kt:41`: `XrayGeodata.all.map` → `XrayGeodata.bundled.map`.

- [ ] **Step 8: Commit** — `feat(routing): Iranian and Chinese lists in the olcRTC engine's text form`.

### Task 2: The engine's rules per region

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OlcrtcDirectRules.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/OlcrtcDirectRulesTest.kt`

**Interfaces:**
- Consumes: `XrayGeodata.lists(region: String)` (Task 1).
- Produces: `suspend fun OlcrtcDirectRules.text(region: String): String`, `suspend fun OlcrtcDirectRules.forRouting(routing: Routing): String`; `text()` and `text(lists)` keep their signatures.

- [ ] **Step 1: Write the failing tests** — add to `OlcrtcDirectRulesTest` (existing tests stay as they are):

```kotlin
    @Test fun everyRegionIsRulesTheEngineTakes() = runTest {
        for (region in listOf("ru", "ir", "cn")) {
            val lines = OlcrtcDirectRules.text(region).lines()
            assertEquals("", lines.last(), "$region: the text ends with a newline")
            for (rule in lines.dropLast(1)) {
                assertTrue(name.matches(rule) || v4.matches(rule) || v6.matches(rule), "$region: not a rule the engine takes: $rule")
            }
        }
    }

    @Test fun eachRegionCarriesItsOwnListsOnly() = runTest {
        val ru = OlcrtcDirectRules.text("ru").lines().toSet()
        val ir = OlcrtcDirectRules.text("ir").lines().toSet()
        val cn = OlcrtcDirectRules.text("cn").lines().toSet()
        for (rules in listOf(ru, ir, cn)) {
            for (range in XrayConfig.PRIVATE_RANGES) assertTrue(range in rules, "$range is missing")
        }
        assertTrue("domain:ru" in ru && "domain:ru" !in ir && "domain:ru" !in cn)
        assertTrue("domain:ir" in ir && "domain:ir" !in ru && "domain:ir" !in cn)
        assertTrue("domain:cn" in cn && "domain:cn" !in ru && "domain:cn" !in ir)
    }

    @Test fun shapesTheEngineRefusesAreLeftOut() {
        val text = OlcrtcDirectRules.text(
            XrayGeodata.Lists(
                domains = listOf("domain:a.cn", "regexp:^.+\\.b\\.cn$", "keyword:c", "full:d.cn"),
                cidrs = listOf("1.2.3.0/24")
            )
        )
        assertEquals(XrayConfig.PRIVATE_RANGES + listOf("domain:a.cn", "full:d.cn", "1.2.3.0/24"), text.lines().dropLast(1))
    }

    @Test fun routingPicksTheRegion() = runTest {
        assertEquals(OlcrtcDirectRules.NONE, OlcrtcDirectRules.forRouting(Routing.Global))
        assertEquals(
            OlcrtcDirectRules.text("cn"),
            OlcrtcDirectRules.forRouting(Routing.Rules("/data/rules", DirectDns.Servers(emptyList()), "cn"))
        )
        assertEquals(
            OlcrtcDirectRules.text(),
            OlcrtcDirectRules.forRouting(Routing.BypassRussia(RuleSets.IOS_RELATIVE_DIR, DirectDns.Placeholder))
        )
    }
```

Also update the paragraph of `salutejazzHostsResolveDirect`'s KDoc that says Android does not go through this bundle: Android olcRTC now does; the desktop front still routes olcRTC with the `.srs` files.

- [ ] **Step 2: Run to see it fail** — `--tests 'org.olcbox.app.net.OlcrtcDirectRulesTest'`. Expected: compilation fails on `text(String)` / `forRouting`.

- [ ] **Step 3: Implement**:

```kotlin
object OlcrtcDirectRules {
    /** What the engine is handed under Global: no rules, everything tunnelled. */
    const val NONE = ""

    /** The Russian rules: what iOS hands the engine under Bypass Russia. */
    suspend fun text(): String = text(XrayGeodata.lists())

    /** The rules of one bypass region (`ru`, `ir`, `cn`); another region is an error. */
    suspend fun text(region: String): String = text(XrayGeodata.lists(region))

    /** What the engine is handed for [routing]: its region's rules, none under Global. */
    suspend fun forRouting(routing: Routing): String = when (routing) {
        Routing.Global -> NONE
        is Routing.RuleBased -> text(routing.region)
    }

    /**
     * Only the shapes `internal/route` parses: names by `domain:` or `full:`,
     * addresses and prefixes. Xray's lists may also carry `keyword:` and
     * `regexp:`, which the engine refuses, and with them the whole start; the
     * names they describe ride the tunnel. Today that is three lines of
     * v2fly's `cn` and none of `ru` or `ir`.
     */
    fun text(lists: XrayGeodata.Lists): String =
        (XrayConfig.PRIVATE_RANGES + lists.domains.filter(::engineTakes) + lists.cidrs)
            .joinToString("\n", postfix = "\n")

    private fun engineTakes(rule: String): Boolean =
        rule.startsWith("domain:") || rule.startsWith("full:")
}
```

and reword the object's KDoc from "The Bypass Russia rules" to the rules of the chosen region.

- [ ] **Step 4: Run the tests** — expected: `OlcrtcDirectRulesTest` passes, all tests (the four existing ones unchanged).

- [ ] **Step 5: Commit** — `feat(routing): the olcRTC engine's direct rules for every bypass region`.

### Task 3: The UDP relay stays on under rules

**Files:**
- Modify: `sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/vpn/OlcRtcUdpRelay.kt`
- Test: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/OlcRtcUdpRelayTest.kt`

**Interfaces:**
- Produces: `fun OlcRtcUdpRelay.enabled(provider: String, transport: String, directRules: Boolean = false): Boolean`.

- [ ] **Step 1: Write the failing tests** — add to `OlcRtcUdpRelayTest` (existing tests unchanged):

```kotlin
    // Under a bypass the engine carries the region's UDP itself. A room with
    // no lane keeps the relay then: the engine (olcrtc#49) takes the
    // association for direct flows and DNS and drops what is for the lane at
    // once. Off, a Russian call on a Jitsi room would be refused instead of
    // going direct, as it did behind the sing-box front.
    @Test
    fun directRulesKeepTheRelayOnARoomWithoutALane() {
        assertTrue(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_DATACHANNEL, directRules = true))
        val config = LocationConfig(
            bypassProvider = "jitsi-meet",
            transport = LocationConfig.TRANSPORT_VP8CHANNEL
        ).normalized()
        assertTrue(OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport, directRules = true))
    }

    @Test
    fun withoutRulesTheLaneDecides() {
        assertFalse(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_DATACHANNEL, directRules = false))
        assertTrue(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.TRANSPORT_DATACHANNEL, directRules = false))
        assertTrue(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_VP8CHANNEL, directRules = false))
    }
```

- [ ] **Step 2: Run to see it fail** — `--tests 'org.olcbox.app.vpn.OlcRtcUdpRelayTest'`. Expected: compilation fails on the `directRules` argument.

- [ ] **Step 3: Implement**:

```kotlin
/**
 * Whether olcRTC's SOCKS5 UDP ASSOCIATE relay is on for a room: on wherever
 * the room's link has a lane for datagrams, and under a bypass; off on a room
 * with no lane otherwise.
 *
 * hev asks the engine for an association for every UDP socket off the tun
 * (full-cone). Only livekit (WB Stream) and SaluteJazz have a lane behind
 * datachannel; the jitsi engine, whose only transport it is, does not.
 * Off, the engine answers each association at once with host unreachable, so
 * UDP fails fast there. On, the engine (7b78fd4a753c, olcrtc#49) takes the
 * association on a link without the lane for what it carries off the lane —
 * a direct flow, DNS for a name the rules cover, other DNS over the reliable
 * stream — and drops what is for the lane at once; the association ends with
 * its control connection. That only pays when direct rules give it something
 * to carry: under a bypass a Russian call on a Jitsi room goes direct, as it
 * did behind the sing-box front.
 * Without rules it stays off: a refusal fails fast where a drop only times out
 * (and before olcrtc#49 such an association waited for a lane that never
 * opened, holding its SOCKS slot). vp8channel carries datagrams itself,
 * whatever the carrier; seichannel has no datagram methods, so the engine
 * refuses there on its own.
 */
object OlcRtcUdpRelay {
    private val DATACHANNEL_LANE_PROVIDERS = setOf(
        LocationConfig.PROVIDER_WB_STREAM,
        LocationConfig.PROVIDER_SALUTEJAZZ
    )

    fun enabled(provider: String, transport: String, directRules: Boolean = false): Boolean {
        if (directRules) return true
        val normalizedProvider = LocationConfig.normalizeProvider(provider)
        val normalizedTransport = LocationConfig.normalizeTransport(transport, normalizedProvider)
        return normalizedTransport != LocationConfig.TRANSPORT_DATACHANNEL ||
            normalizedProvider in DATACHANNEL_LANE_PROVIDERS
    }
}
```

- [ ] **Step 4: Run the tests** — expected: `OlcRtcUdpRelayTest` passes.

- [ ] **Step 5: Commit** — `feat(android): olcRTC keeps its UDP relay under a bypass, for direct flows`.

### Task 4: The Android service hands the engine its rules; the front goes

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`

**Interfaces:**
- Consumes: `OlcrtcDirectRules.forRouting(routing: Routing): String` (Task 2), `OlcRtcUdpRelay.enabled(provider, transport, directRules)` (Task 3), `OlcrtcRuntime.setDirectRules(text: String)` (gomobile binding of `mobile.Runtime.SetDirectRules`, throws on a bad rule).

No unit test reaches `VpnService`; the logic is in the units of Tasks 2 and 3, and CI compiles Android (`:androidApp:assembleDebug`).

- [ ] **Step 1: `startTransport`** — olcRTC goes to `startMobile` with the routing; the `startFront` call goes:

```kotlin
    /**
     * Start the transport for [location], branching on its kind: olcrtc runs the
     * engine in this process ([startMobile]), which routes by itself under a
     * bypass; vless/hysteria2/xhttp start a sing-box / Xray core subprocess via
     * [startCore]. Both leave the hev bridge pointed at the right SOCKS port
     * (via [activeCorePort]).
     */
    private suspend fun startTransport(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val routing = routingFor(upstream)
        return if (location.kind == LocationKind.Olcrtc) {
            activeCorePort = null
            startMobile(location, upstream, requestedGeneration, setErrorOnFailure, routing)
        } else {
            startCore(location, setErrorOnFailure, routing)
        }
    }
```

- [ ] **Step 2: `startMobile`** — new last parameter `routing: Routing`; inside the `try`, right before the `configureMobileTransport` call:

```kotlin
            // The engine routes by itself (internal/route), as it does on iOS:
            // the region's lists as rules, or none under Global.
            val directRules = OlcrtcDirectRules.forRouting(routing)
            configureMobileTransport(config, upstream, directRules)
```

- [ ] **Step 3: `configureMobileTransport`**:

```kotlin
    private fun configureMobileTransport(location: LocationConfig, upstream: Network?, directRules: String) {
        val config = location.normalized()
        olcrtc.setTransport(config.transport)
        // The upstream network's own resolvers first, the public operator
        // behind them: some mobile networks answer only their own (olcbox#16).
        // Direct names under a bypass are resolved on the same list.
        olcrtc.setDNS(upstreamDnsList(upstream))
        olcrtc.setSocksListenHost(socksListenHost)
        olcrtc.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
        // Set on every start, the empty text included: the one Runtime keeps
        // its rules across Starts, so Global after a bypass would keep them.
        // Parsed here, so a list the engine refuses fails this start loudly.
        olcrtc.setDirectRules(directRules)
        if (directRules.isNotEmpty()) {
            addLog("Routing: olcRTC takes ${directRules.count { it == '\n' }} direct rules")
        }
        // Off on a room with no datagram lane unless a bypass gives the relay
        // direct flows to carry (OlcRtcUdpRelay). Set on every start, for the
        // same reason as the rules: a WB room after a Jitsi one would
        // otherwise run with no UDP.
        olcrtc.setUDP(
            OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport, directRules = directRules.isNotEmpty())
        )
    }
```

- [ ] **Step 4: Delete the front** — `startFront`, the `frontsOlcrtc` field and its KDoc, `FRONT_ALTERNATE_PORT` and its KDoc; `stopCoreProcesses` no longer resets `frontsOlcrtc`; `isActiveTransportRunning` and `activeTransportLabel` return to their pre-front shape:

```kotlin
    private fun isActiveTransportRunning(): Boolean =
        if (activeCorePort != null) {
            singBoxCore.isRunning() || xrayCore.isRunning()
        } else {
            olcrtc.isRunning()
        }

    private fun activeTransportLabel(): String =
        if (activeCorePort != null) "core transport" else "olcRTC"
```

and the `routingFor` comment about "an extra sing-box front for a plain Global connection" becomes "Global writes no rule files and hands the engine no rules".

- [ ] **Step 5: Check the leftovers** — `grep -n -E 'frontsOlcrtc|startFront|FRONT_ALTERNATE_PORT|proxy mode keeps olcRTC' OlcboxVpnService.kt` prints nothing; `import org.olcbox.app.net.OlcrtcDirectRules` is present.

- [ ] **Step 6: Commit** — `feat(android): olcRTC routes by itself under a bypass; the sing-box front goes (#33)`.

### Task 5: Release note

**Files:**
- Create: `docs/release-notes/pending.md`

- [ ] **Step 1: Write the note** (Android only, user's words, no internals):

```markdown
### Android: bypass modes on olcRTC, from one process

With a bypass mode on (Russia, Iran or China), an olcRTC connection no longer starts a second routing process in front of itself: the connection sends the chosen country's sites and your local network straight out by itself, as the iPhone app does. One process less to start and keep alive, and the bypass now also works in proxy mode, where olcRTC used to send everything through the tunnel.
```

- [ ] **Step 2: Commit** — `docs(release-notes): Android bypass modes on olcRTC run in the connection itself`.

### Task 6: Verify and hand over

- [ ] **Step 1:** `free -g`; run the three test classes together; then `./gradlew --stop`.
- [ ] **Step 2:** Push the branch (`git push proofkit feat/olcrtc-engine-rules-android`); `pr-checks` runs on push: Gradle checks (JVM tests, desktop, `:androidApp:assembleDebug`, APK asset check, `sing-box check`), Apple Kotlin compilation, gate scripts.
- [ ] **Step 3:** Open the PR against `main`, stacked on #46 (merge #46 first), with the device checks from the spec as its test plan; wait for CI green.
