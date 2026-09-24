package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OlcrtcDirectRulesTest {
    // The engine's parser (internal/route) accepts exactly these shapes and
    // refuses the start on anything else, so every bundled line is checked here.
    private val name = Regex("""^(domain|full):[a-z0-9.-]+$""")
    private val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$""")
    private val v6 = Regex("""^[0-9a-f:]+/\d{1,3}$""")

    @Test fun everyLineIsARuleTheEngineTakes() = runTest {
        val lines = OlcrtcDirectRules.text().lines()
        assertEquals("", lines.last(), "the text ends with a newline")
        val rules = lines.dropLast(1)
        assertTrue(rules.size > 12_000, "the three lists together are over twelve thousand rules, got ${rules.size}")
        for (rule in rules) {
            assertTrue(rule.isNotBlank(), "a blank line")
            assertTrue(name.matches(rule) || v4.matches(rule) || v6.matches(rule), "not a rule the engine takes: $rule")
        }
    }

    @Test fun carriesThePrivateRangesAndTheBareTld() = runTest {
        val rules = OlcrtcDirectRules.text().lines().toSet()
        for (range in XrayConfig.PRIVATE_RANGES) assertTrue(range in rules, "$range is missing")
        assertTrue("domain:ru" in rules)
        assertTrue("domain:xn--p1ai" in rules)
    }

    @Test fun orderIsPrivateThenNamesThenAddresses() {
        val text = OlcrtcDirectRules.text(XrayGeodata.Lists(domains = listOf("domain:a.ru"), cidrs = listOf("1.2.3.0/24")))
        assertEquals(XrayConfig.PRIVATE_RANGES + listOf("domain:a.ru", "1.2.3.0/24"), text.lines().dropLast(1))
    }

    @Test fun globalIsNoRules() {
        assertEquals("", OlcrtcDirectRules.NONE)
    }

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

    /**
     * SaluteJazz (the third olcRTC carrier, merged 2026-09-22) reaches
     * `salutejazz.ru` (web origin), `ws.salutejazz.ru` (signaling
     * websocket), `bk.salutejazz.ru` (room creation/preconnect REST) and
     * the TURN hosts `s-t.salutejazz.ru`/`a-t.salutejazz.ru`.
     *
     * This proves the iOS and Android olcRTC paths. `OlcrtcDirectRules`/[XrayGeodata]'s
     * bundle — v2fly's `geosite-tld-ru.txt`, fetched and pinned by
     * `tools/xray-geodata.sh` / [XrayGeodataTest] — already carries a bare
     * `domain:ru` root-domain rule (asserted by
     * [carriesThePrivateRangesAndTheBareTld]), which covers every `.ru`
     * name, these five included: the same shape Telemost's and WB
     * Stream's `.ru` hosts ride on, neither of which is listed by name
     * either. Nothing needed adding here.
     *
     * The desktop proxy does not go through this bundle at all: its Bypass
     * Russia front routes olcRTC with [RuleSets]' sing-box `.srs` files —
     * a *separate* artifact, SagerNet's build rather than v2fly's, fetched
     * and pinned independently (`scripts/rule-sets.lock`, `RuleSetsTest`).
     * That test only checks the `.srs` bytes hash to the pinned value; no
     * in-repo test decodes or asserts on their content, so nothing here
     * covers that path. Decompiling the pinned `geosite-tld-ru.srs`
     * (sha256 `ba979268…`, matching `scripts/rule-sets.lock`) out of band
     * with sing-box v1.13.14's `srs.Read` on 2026-09-22 showed
     * `"domain_suffix":[...,".ru",...]`, so the same five hosts are DIRECT
     * there too today — but that was a one-off check outside this repo,
     * not a standing guarantee: a future regeneration of that `.srs` file
     * that silently dropped `.ru` would only be caught if its hash
     * changed, not by any assertion on what it means.
     */
    @Test fun salutejazzHostsResolveDirect() = runTest {
        val hosts = listOf(
            "salutejazz.ru",
            "ws.salutejazz.ru",
            "bk.salutejazz.ru",
            "s-t.salutejazz.ru",
            "a-t.salutejazz.ru",
        )
        val nameRules = OlcrtcDirectRules.text().lines().dropLast(1)
            .filter { it.startsWith("domain:") || it.startsWith("full:") }
        for (host in hosts) {
            assertTrue(resolvesDirect(host, nameRules), "$host does not resolve direct")
        }
    }

    /**
     * The engine's own root-domain match (`internal/route`): `domain:x`
     * covers `x` and any dot-bounded subdomain of it; `full:x` covers only
     * `x` itself.
     */
    private fun resolvesDirect(host: String, nameRules: List<String>): Boolean = nameRules.any { rule ->
        when {
            rule.startsWith("full:") -> host == rule.removePrefix("full:")
            rule.startsWith("domain:") -> rule.removePrefix("domain:").let { root -> host == root || host.endsWith(".$root") }
            else -> false
        }
    }
}
