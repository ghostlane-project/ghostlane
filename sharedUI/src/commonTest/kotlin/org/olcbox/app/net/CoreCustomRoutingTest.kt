package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The user's own direct and tunnel rules on the Rust core's config (Android's Rust path):
 * written inline, ahead of the region's lists, as the Go path writes them for sing-box
 * (SingBoxConfig.addPolicyRouteRules), in the spelling the core accepts.
 */
class CoreCustomRoutingTest {
    private val dns = DirectDns.Servers(listOf("10.20.30.40"))
    private val lists = IosCoreFixtures.lists
    private val login = IosCoreFixtures.login
    private val reality = IosCoreFixtures.spec(IosCoreLine.RealityVision)

    private fun rules(policy: Routing.Policy, direct: List<String> = emptyList(), tunnel: List<String> = emptyList()) =
        Routing.Rules("/unused", dns, policy, CustomRules.of(direct, tunnel))

    private fun build(routing: Routing, geodata: XrayGeodata.Lists? = null, line: IosCoreLine = IosCoreLine.RealityVision): JsonObject =
        Json.parseToJsonElement(XrayConfig.buildIosCore(IosCoreFixtures.spec(line), login, routing, geodata)).jsonObject

    private fun JsonObject.outbounds() = this["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.routingRules() = this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.dnsOut() = outbounds().single { it["tag"]!!.jsonPrimitive.content == "dns-out" }["settings"]!!.jsonObject
    private fun JsonObject.dnsRules() = dnsOut()["rules"]!!.jsonArray.map { it.jsonObject }
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val hijack53 = json("""{"type":"field","inboundTag":["in"],"network":"tcp,udp","port":53,"outboundTag":"dns-out"}""")
    private val dnsDirect = json("""{"type":"field","inboundTag":["dns-direct"],"outboundTag":"direct"}""")
    private val privateNets = json(
        """{"type":"field","ip":[${XrayConfig.PRIVATE_RANGES.joinToString(",") { "\"$it\"" }}],"outboundTag":"direct"}"""
    )
    private val returnHttps = json("""{"action":"return","qType":65,"rCode":5}""")
    private val forward = json(
        """{"action":"forward","qType":"2-27,29-64,66-65535","address":"${XrayConfig.REMOTE_DNS_SERVER}","port":53,"outboundTag":"out"}"""
    )

    @Test fun globalWithADirectRuleOfTheUsersOwn() {
        val config = build(rules(Routing.Policy.Tunnel, direct = listOf("bank.example", "10.8.0.0/16")))
        assertEquals(listOf("out", "direct", "dns-out"), config.outbounds().map { it["tag"]!!.jsonPrimitive.content })
        assertEquals(
            json("""[{"tag":"dns-direct","address":"10.20.30.40","port":53}]"""),
            config["dns"]!!.jsonObject["servers"]
        )
        assertEquals("10.20.30.40", config.dnsOut()["rewriteAddress"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(json("""{"action":"direct","domain":["domain:bank.example"]}"""), returnHttps, forward),
            config.dnsRules()
        )
        assertEquals(
            listOf(
                hijack53, dnsDirect, privateNets,
                json("""{"type":"field","domain":["domain:bank.example"],"outboundTag":"direct"}"""),
                json("""{"type":"field","ip":["10.8.0.0/16"],"outboundTag":"direct"}"""),
            ),
            config.routingRules()
        )
    }

    @Test fun globalWithOnlyTunnelRulesIsGlobalByteForByte() {
        val withTunnelRules = XrayConfig.buildIosCore(reality, login, rules(Routing.Policy.Tunnel, tunnel = listOf("example.org")))
        assertEquals(XrayConfig.buildIosCore(reality, login), withTunnelRules)
        assertFalse("dns-direct" in withTunnelRules, "no resolver of the network is named")
        assertEquals(Routing.Global, IosCore.effective(rules(Routing.Policy.Tunnel, tunnel = listOf("example.org"))))
    }

    @Test fun aBypassWithoutRulesOfTheUsersIsTheSameConfigAsBefore() {
        val plain = XrayConfig.buildIosCore(reality, login, Routing.BypassRussia("/unused", dns), lists)
        assertEquals(plain, XrayConfig.buildIosCore(reality, login, rules(Routing.Policy.Bypass("ru")), lists))
    }

    @Test fun bypassPutsTheUsersRulesAheadOfTheRegion() {
        val config = build(
            rules(Routing.Policy.Bypass("ru"), direct = listOf("example.com"), tunnel = listOf("kinopoisk.ru", "203.0.113.0/24")),
            lists
        )
        val regionNames = JsonArray(lists.domains.map { kotlinx.serialization.json.JsonPrimitive(it) })
        assertEquals(
            listOf(
                json("""{"action":"direct","domain":["domain:example.com"]}"""),
                json(
                    """{"action":"forward","qType":"2-27,29-64,66-65535","domain":["domain:kinopoisk.ru"],""" +
                        """"address":"${XrayConfig.REMOTE_DNS_SERVER}","port":53,"outboundTag":"out"}"""
                ),
                json("""{"action":"hijack","domain":["domain:kinopoisk.ru"],"rCode":5}"""),
                JsonObject(mapOf("action" to kotlinx.serialization.json.JsonPrimitive("direct"), "domain" to regionNames)),
                returnHttps, forward,
            ),
            config.dnsRules()
        )
        val routing = config.routingRules()
        assertEquals(listOf(hijack53, dnsDirect, privateNets), routing.take(3))
        assertEquals(
            listOf(
                json("""{"type":"field","domain":["domain:example.com"],"outboundTag":"direct"}"""),
                json("""{"type":"field","domain":["domain:kinopoisk.ru"],"outboundTag":"out"}"""),
                json("""{"type":"field","ip":["203.0.113.0/24"],"outboundTag":"out"}"""),
            ),
            routing.subList(3, 6)
        )
        assertEquals("direct", routing[6]["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(regionNames, routing[6]["domain"])
        assertEquals(8, routing.size)
    }

    @Test fun anEntryInBothListsIsTheTunnels() {
        val config = build(rules(Routing.Policy.Bypass("ru"), direct = listOf("both.example"), tunnel = listOf("both.example")), lists)
        assertTrue(config.routingRules().none { it["domain"]?.toString()?.contains("both.example") == true && it["outboundTag"]!!.jsonPrimitive.content == "direct" })
        assertTrue(config.routingRules().any { it["domain"]?.toString()?.contains("domain:both.example") == true && it["outboundTag"]!!.jsonPrimitive.content == "out" })
    }

    @Test fun blockedOnlyIsRefusedWithAReason() {
        val e = assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildIosCore(reality, login, rules(Routing.Policy.BlockedOnly), lists)
        }
        assertEquals(IosCore.BLOCKED_ONLY_REFUSAL, e.message)
    }

    @Test fun everyShapeWithTheUsersRulesIsOneTheCoreAccepts() {
        val routings = listOf(
            rules(Routing.Policy.Tunnel, direct = listOf("bank.example", "10.8.0.0/16", "2001:db8::/32")) to null,
            rules(Routing.Policy.Bypass("ru"), direct = listOf("example.com"), tunnel = listOf("kinopoisk.ru", "203.0.113.0/24")) to lists,
            rules(Routing.Policy.Bypass("ru"), tunnel = listOf("203.0.113.7")) to lists,
        )
        for (line in IosCoreLine.entries) for ((routing, geodata) in routings) {
            val config = build(routing, geodata, line)
            assertEquals(emptyList<String>(), XrayRustSurface.violations(config), "$line $routing")
            for (rule in config.routingRules()) {
                assertFalse(rule.containsKey("domain") && rule.containsKey("ip"), "Xray ANDs a rule's fields: $rule")
                listOf("domain", "ip").forEach { key -> rule[key]?.let { assertTrue(it.jsonArray.isNotEmpty(), "an empty $key: $rule") } }
            }
            config.dnsRules().forEach { rule -> rule["domain"]?.let { assertTrue(it.jsonArray.isNotEmpty(), "an empty domain matches every name: $rule") } }
            val text = config.toString()
            listOf("geosite:", "geoip:", "ext:").forEach { assertFalse(it in text, "no $it: the core has no .dat on the phone") }
        }
    }
}
