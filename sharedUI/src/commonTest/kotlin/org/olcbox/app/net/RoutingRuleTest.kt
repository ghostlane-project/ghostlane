package org.olcbox.app.net

import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a person types into "Your rules" and what the cores are then told: one
 * spelling per rule — lowercase, `xn--` for a Cyrillic name, a prefix with its host
 * bits cleared — and nothing for what is neither a domain nor an address.
 */
class RoutingRuleTest {
    private fun text(input: String) = RoutingRule.parse(input)?.text

    @Test fun aDomainIsLowercaseWithoutWildcardSchemePathOrPort() {
        assertEquals("example.com", text("Example.COM"))
        assertEquals("example.com", text("  *.example.com "))
        assertEquals("example.com", text(".example.com."))
        assertEquals("example.com", text("https://user@Example.com:8443/path?q=1#top"))
        assertEquals("sub.example.co.uk", text("sub.example.co.uk"))
    }

    @Test fun aCyrillicNameIsSpelledAsOnTheWire() {
        assertEquals("xn--e1afmkfd.xn--p1ai", text("пример.рф"))
        assertEquals("xn--e1afmkfd.xn--p1ai", text("ПРИМЕР.РФ"))
        assertEquals("xn--bcher-kva.de", text("bücher.de"))
        assertEquals("xn--80akhbyknj4f.com", text("испытание.com"))
    }

    @Test fun anAddressBecomesAPrefix() {
        assertEquals("203.0.113.7/32", text("203.0.113.7"))
        assertEquals("10.0.0.0/8", text("10.1.2.3/8"))
        assertEquals("0.0.0.0/0", text("0.0.0.0/0"))
        assertEquals("2001:db8::1/128", text("2001:DB8::1"))
        assertEquals("2001:db8::/64", text("2001:db8:0:0:1::/64"))
        assertEquals("::/0", text("::/0"))
        assertEquals("fe80::/10", text("fe80::1/10"))
        assertEquals("1:2:3:4:5:6:7:8/128", text("1:2:3:4:5:6:7:8"))
    }

    @Test fun whatIsNeitherIsRefused() {
        listOf(
            "", "  ", "localhost", "exa mple.com", "-bad.com", "bad-.com", "1.2.3", "999.1.1.1",
            "10.0.0.0/33", "2001:db8::/129", "1::2::3", "a..com", "example.123", "[2001:db8::1]",
            "x".repeat(64) + ".com"
        ).forEach { assertNull(RoutingRule.parse(it), "\"$it\" should be refused") }
    }

    @Test fun anEntryLivesInOneListOnly() {
        val rule = RoutingRule.parse("example.com")!!
        val direct = RoutingSettings().withDirectRule(rule)
        assertEquals(listOf("example.com"), direct.directRules)
        val moved = direct.withTunnelRule(rule)
        assertEquals(emptyList(), moved.directRules)
        assertEquals(listOf("example.com"), moved.tunnelRules)
        assertEquals(RoutingSettings(), moved.withoutRule("example.com"))
    }

    @Test fun rulesOfTheUsersOwnAskForRulesEvenUnderGlobal() {
        assertEquals(false, RoutingSettings().needsRules)
        assertEquals(true, RoutingSettings(directRules = listOf("example.com")).needsRules)
        assertEquals(true, RoutingSettings(RoutingMode.BlockedOnly).needsRules)
    }

    @Test fun storedRulesAreReadBackAndTheTunnelWinsAnEntryBothClaim() {
        val custom = CustomRules.of(
            direct = listOf("example.com", "10.0.0.0/8", "not a rule", "both.com"),
            tunnel = listOf("both.com", "203.0.113.0/24")
        )
        assertEquals(listOf("example.com", "10.0.0.0/8"), custom.direct.map { it.text })
        assertEquals(listOf("both.com", "203.0.113.0/24"), custom.tunnel.map { it.text })
    }

    @Test fun eachModeAsksForItsPolicy() {
        assertEquals(Routing.Policy.Tunnel, RoutingMode.Global.policy())
        assertEquals(Routing.Policy.Bypass("ru"), RoutingMode.BypassRussia.policy())
        assertEquals(Routing.Policy.Bypass("cn"), RoutingMode.BypassChina.policy())
        assertEquals(Routing.Policy.BlockedOnly, RoutingMode.BlockedOnly.policy())
    }
}
