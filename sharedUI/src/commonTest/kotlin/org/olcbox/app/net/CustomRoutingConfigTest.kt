package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which rule-set files a routing choice takes. What the rules do to a
 * connection is pinned where the core writes them ([CoreCustomRoutingTest]).
 */
class CustomRoutingConfigTest {
    private val dns = DirectDns.Servers(listOf("10.20.30.40"))
    private fun rules(policy: Routing.Policy, direct: List<String> = emptyList(), tunnel: List<String> = emptyList()) =
        Routing.Rules("/data/rules", dns, policy, CustomRules.of(direct, tunnel))

    @Test fun ruleSetsFollowThePolicy() {
        assertEquals(emptyList(), RuleSets.selected(rules(Routing.Policy.Tunnel, direct = listOf("a.com"))))
        assertEquals(RuleSets.regional("ir"), RuleSets.selected(rules(Routing.Policy.Bypass("ir"))))
        assertEquals(RuleSets.blocked, RuleSets.selected(rules(Routing.Policy.BlockedOnly)))
    }
}
