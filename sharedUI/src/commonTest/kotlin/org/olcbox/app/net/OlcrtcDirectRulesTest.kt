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

    /**
     * SaluteJazz (the third olcRTC carrier, merged 2026-09-22) reaches
     * `salutejazz.ru` (web origin), `ws.salutejazz.ru` (signaling
     * websocket), `bk.salutejazz.ru` (room creation/preconnect REST) and
     * the TURN hosts `s-t.salutejazz.ru`/`a-t.salutejazz.ru`.
     *
     * None of the five is listed by name anywhere in the bundle, and
     * neither is any Telemost or WB Stream host: `domain:ru`
     * (geosite-tld-ru, asserted by [carriesThePrivateRangesAndTheBareTld])
     * is a root-domain match, so it already covers every name under the
     * `.ru` apex — these five included, the same single-apex-suffix shape
     * both existing carriers ride on. There is nothing to add for
     * SaluteJazz that was not already true for the other two; this test
     * pins that coverage so a bundle regen that ever dropped the apex rule
     * would be caught here, for hosts the app depends on, rather than
     * disappearing as one line among twelve thousand.
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
