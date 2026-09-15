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
}
