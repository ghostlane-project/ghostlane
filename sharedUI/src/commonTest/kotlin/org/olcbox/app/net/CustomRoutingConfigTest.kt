package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The user's own rules and "only blocked sites through the tunnel", as sing-box is
 * told them on Android (the socks shapes) and on the Mac (the daemon's tun): first
 * match wins, so the order is the behaviour, and it is pinned here rule by rule.
 */
class CustomRoutingConfigTest {
    private val dns = DirectDns.Servers(listOf("10.20.30.40"))
    private fun rules(policy: Routing.Policy, direct: List<String> = emptyList(), tunnel: List<String> = emptyList()) =
        Routing.Rules("/data/rules", dns, policy, CustomRules.of(direct, tunnel))

    private fun vless() = OutboundSpec.Vless(
        "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome",
        "xtls-rprx-vision", TransportSpec.Tcp, "DE"
    )

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun routeOf(json: String) = obj(json)["route"]!!.jsonObject
    private fun routeRules(json: String) = routeOf(json)["rules"]!!.jsonArray.map { it.jsonObject }
    private fun dnsOf(json: String) = obj(json)["dns"]!!.jsonObject
    private fun dnsRules(json: String) = dnsOf(json)["rules"]!!.jsonArray.map { it.jsonObject }
    private fun str(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.content
    private fun strings(o: JsonObject, key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.content }
    private fun declared(json: String) = routeOf(json)["rule_set"]?.jsonArray?.map { str(it.jsonObject, "tag") }

    /** The socks shapes Android and the desktop proxy build. */
    private fun socksShapes(routing: Routing) = mapOf(
        "core" to SingBoxConfig.build(vless(), routing = routing),
        "chain" to SingBoxConfig.buildSocksChain(10808, username = "u", password = "p", routing = routing)
    )

    @Test fun blockedOnlySendsTheListThroughTheTunnelAndTheRestStraightOut() {
        for ((shape, json) in socksShapes(rules(Routing.Policy.BlockedOnly))) {
            assertEquals(listOf("refilter-domains", "refilter-ips"), declared(json), shape)
            val route = routeRules(json)
            assertEquals("sniff", str(route[0], "action"), shape)
            assertEquals("hijack-dns", str(route[1], "action"), shape)
            assertEquals("true", str(route[2], "ip_is_private"), shape)
            assertEquals("direct", str(route[2], "outbound"), shape)
            assertEquals(listOf("refilter-domains"), strings(route[3], "rule_set"), shape)
            assertEquals("out", str(route[3], "outbound"), shape)
            // A site blocked by address alone: resolved on the network underneath, then matched.
            assertEquals("resolve", str(route[4], "action"), shape)
            assertEquals("dns-direct", str(route[4], "server"), shape)
            assertEquals(listOf("refilter-ips"), strings(route[5], "rule_set"), shape)
            assertEquals("out", str(route[5], "outbound"), shape)
            assertEquals(6, route.size, shape)
            assertEquals("direct", str(routeOf(json), "final"), shape)

            // A blocked name is resolved through the tunnel; every other name on the network underneath.
            assertEquals(listOf("refilter-domains"), strings(dnsRules(json).single(), "rule_set"), shape)
            assertEquals("dns-remote", str(dnsRules(json).single(), "server"), shape)
            assertEquals("dns-direct", str(dnsOf(json), "final"), shape)
        }
    }

    @Test fun theUsersRulesComeBeforeEveryList() {
        val routing = rules(
            Routing.Policy.BlockedOnly,
            direct = listOf("instagram.com", "10.8.0.0/16"),
            tunnel = listOf("example.org", "203.0.113.0/24")
        )
        for ((shape, json) in socksShapes(routing)) {
            val route = routeRules(json)
            assertEquals("true", str(route[2], "ip_is_private"), shape)
            assertEquals(listOf("instagram.com"), strings(route[3], "domain_suffix"), shape)
            assertEquals(listOf("10.8.0.0/16"), strings(route[3], "ip_cidr"), shape)
            assertEquals("direct", str(route[3], "outbound"), shape)
            assertEquals(listOf("example.org"), strings(route[4], "domain_suffix"), shape)
            assertEquals(listOf("203.0.113.0/24"), strings(route[4], "ip_cidr"), shape)
            assertEquals("out", str(route[4], "outbound"), shape)
            assertEquals(listOf("refilter-domains"), strings(route[5], "rule_set"), shape)

            val dnsRules = dnsRules(json)
            assertEquals(listOf("example.org"), strings(dnsRules[0], "domain_suffix"), shape)
            assertEquals("dns-remote", str(dnsRules[0], "server"), shape)
            assertEquals(listOf("instagram.com"), strings(dnsRules[1], "domain_suffix"), shape)
            assertEquals("dns-direct", str(dnsRules[1], "server"), shape)
            assertEquals("dns-remote", str(dnsRules[2], "server"), shape)
            // An address has no name for a DNS rule to match.
            dnsRules.forEach { assertNull(it["ip_cidr"], shape) }
        }
    }

    @Test fun aBypassKeepsItsShapeWithTheUsersRulesAhead() {
        val routing = rules(Routing.Policy.Bypass("ru"), direct = listOf("example.com"), tunnel = listOf("vk.com"))
        for ((shape, json) in socksShapes(routing)) {
            assertEquals(RuleSets.all.map { it.tag }, declared(json), shape)
            val route = routeRules(json)
            assertEquals(listOf("example.com"), strings(route[3], "domain_suffix"), shape)
            assertEquals("direct", str(route[3], "outbound"), shape)
            assertEquals(listOf("vk.com"), strings(route[4], "domain_suffix"), shape)
            assertEquals("out", str(route[4], "outbound"), shape)
            assertEquals(RuleSets.all.map { it.tag }, strings(route[5], "rule_set"), shape)
            assertEquals("direct", str(route[5], "outbound"), shape)
            assertEquals("out", str(routeOf(json), "final"), shape)
            val dnsRules = dnsRules(json)
            assertEquals(listOf("vk.com"), strings(dnsRules[0], "domain_suffix"), shape)
            assertEquals("dns-remote", str(dnsRules[0], "server"), shape)
            assertEquals("dns-direct", str(dnsRules[2], "server"), shape)
            assertEquals("dns-remote", str(dnsOf(json), "final"), shape)
        }
    }

    @Test fun globalWithRulesOfTheUsersOwnDeclaresNoListAndKeepsTheTunnelAsFinal() {
        for ((shape, json) in socksShapes(rules(Routing.Policy.Tunnel, direct = listOf("bank.example")))) {
            assertNull(declared(json), shape)
            val route = routeRules(json)
            assertEquals(4, route.size, shape)
            assertEquals(listOf("bank.example"), strings(route[3], "domain_suffix"), shape)
            assertEquals("direct", str(route[3], "outbound"), shape)
            assertEquals("out", str(routeOf(json), "final"), shape)
            assertEquals("dns-direct", str(dnsRules(json).single(), "server"), shape)
            assertEquals("dns-remote", str(dnsOf(json), "final"), shape)
        }
    }

    @Test fun theMacDaemonFakesOnlyTheBlockedNamesUnderBlockedOnly() {
        val json = SingBoxConfig.buildDesktopTun(
            corePort = 10810,
            verifyPort = 10811,
            directDnsDomains = listOf("vpn.example"),
            routing = rules(Routing.Policy.BlockedOnly, tunnel = listOf("example.org")),
            bindInterface = "en0"
        )
        val dnsRules = dnsRules(json)
        assertEquals(listOf("vpn.example"), strings(dnsRules[0], "domain"))
        // The user's tunnel name, then the blocked list: fake A/AAAA, refused HTTPS, the rest remote.
        for ((offset, names) in listOf(1 to "domain_suffix", 4 to "rule_set")) {
            assertEquals(listOf("A", "AAAA"), strings(dnsRules[offset], "query_type"))
            assertEquals("dns-fakeip", str(dnsRules[offset], "server"))
            assertEquals(listOf("HTTPS"), strings(dnsRules[offset + 1], "query_type"))
            assertEquals("reject", str(dnsRules[offset + 1], "action"))
            assertEquals("dns-remote", str(dnsRules[offset + 2], "server"))
            assertEquals(true, dnsRules[offset][names] != null)
        }
        // No catch-all fake address: every other name goes straight out and needs its real one.
        assertEquals(7, dnsRules.size)
        assertEquals("dns-direct", str(dnsOf(json), "final"))
        assertEquals("direct", str(routeOf(json), "final"))
        assertEquals("6", str(routeRules(json).last(), "ip_version"))
    }

    @Test fun theIosShapeRefusesWhatOnlyAndroidAndTheDesktopHaveHad() {
        assertFailsWith<IllegalArgumentException> {
            SingBoxConfig.buildTun(vless(), routing = rules(Routing.Policy.BlockedOnly))
        }
        assertFailsWith<IllegalArgumentException> {
            SingBoxConfig.buildTunSocks(10810, routing = rules(Routing.Policy.Bypass("ru"), direct = listOf("a.com")))
        }
        // A plain bypass still builds there, as before.
        SingBoxConfig.buildTun(vless(), routing = Routing.BypassRussia("rules", DirectDns.Placeholder))
    }

    @Test fun ruleSetsFollowThePolicy() {
        assertEquals(emptyList(), RuleSets.selected(rules(Routing.Policy.Tunnel, direct = listOf("a.com"))))
        assertEquals(RuleSets.regional("ir"), RuleSets.selected(rules(Routing.Policy.Bypass("ir"))))
        assertEquals(RuleSets.blocked, RuleSets.selected(rules(Routing.Policy.BlockedOnly)))
    }
}
