package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The user's own rules as the olcRTC engine takes them: it only knows "these go
 * direct", so direct rules are added, a tunnel rule can only take a region entry
 * away, and under BlockedOnly a room carries everything but the direct rules.
 */
class OlcrtcCustomRulesTest {
    private val dns = DirectDns.Servers(emptyList())
    private fun rules(policy: Routing.Policy, direct: List<String> = emptyList(), tunnel: List<String> = emptyList()) =
        Routing.Rules("/data/rules", dns, policy, CustomRules.of(direct, tunnel))

    @Test fun blockedOnlyRoomsCarryEverythingButTheUsersDirectRules() = runTest {
        assertEquals(OlcrtcDirectRules.NONE, OlcrtcDirectRules.forRouting(rules(Routing.Policy.BlockedOnly)))
        assertEquals(
            "domain:bank.example\n10.8.0.0/16\n",
            OlcrtcDirectRules.forRouting(
                rules(Routing.Policy.BlockedOnly, direct = listOf("bank.example", "10.8.0.0/16"), tunnel = listOf("x.com"))
            )
        )
    }

    @Test fun globalWithDirectRulesHandsOnlyThose() = runTest {
        assertEquals(
            "domain:xn--e1afmkfd.xn--p1ai\n",
            OlcrtcDirectRules.forRouting(rules(Routing.Policy.Tunnel, direct = listOf("пример.рф")))
        )
    }

    @Test fun aRegionGetsTheUsersDirectRulesAndLosesWhatTheirTunnelRulesTake() {
        val lists = XrayGeodata.Lists(
            domains = listOf("domain:vk.com", "domain:m.vk.com", "full:api.vk.com", "domain:notvk.com", "domain:ok.ru"),
            cidrs = listOf("87.240.128.0/18")
        )
        val text = OlcrtcDirectRules.text(lists, CustomRules.of(direct = listOf("bank.example"), tunnel = listOf("vk.com")))
        assertEquals(
            XrayConfig.PRIVATE_RANGES + listOf("domain:bank.example", "domain:notvk.com", "domain:ok.ru", "87.240.128.0/18"),
            text.lines().dropLast(1)
        )
    }

    @Test fun noRulesOfTheUsersOwnLeaveARegionAsItWas() = runTest {
        val routing = Routing.Rules("/data/rules", dns, "ru")
        assertEquals(OlcrtcDirectRules.text("ru"), OlcrtcDirectRules.forRouting(routing))
        assertTrue(OlcrtcDirectRules.text("ru").startsWith(XrayConfig.PRIVATE_RANGES.first()))
    }
}
