package org.olcbox.app.net

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.*
import kotlin.test.*

class VlessGroupTest {
    private fun vless(host: String = "127.0.0.1", transport: String = "tcp") = LocationConfig(
        name = host, kind = LocationKind.Vless,
        rawLink = "vless://22222222-2222-2222-2222-222222222222@$host:443?security=reality" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=cd34&sni=www.example.com" +
            "&type=$transport&flow=xtls-rprx-vision&path=%2Fdownload&mode=packet-up"
    )
    private fun entry(id: String, config: LocationConfig, url: String? = "https://example.test/list") =
        LocationEntry.from(id, config, subscriptionUrl = url)

    @Test fun onlyVlessFromTheSelectedSubscriptionIsGrouped() {
        val selected = vless()
        val bundle = LocationBundleV4(activeLocationId = "a", settings = SubscriptionSettings(
            connectionSelection = ConnectionSelection.Lowest), locations = listOf(
                entry("a", selected), entry("b", vless("127.0.0.2")),
                entry("room", LocationConfig("room", "room", "a".repeat(64))),
                entry("other", vless("127.0.0.3"), "https://other.test/list"),
                entry("manual", vless("127.0.0.4"), null)
            ))
        assertEquals(listOf(selected, vless("127.0.0.2")), VlessGroup.from(bundle, selected)!!.members)
        assertNull(VlessGroup.from(bundle.copy(settings = SubscriptionSettings()), selected))
        assertNull(VlessGroup.from(bundle, bundle.locations[2].location))
    }

    @Test fun probesCannotFallThroughToAnotherMemberOrTheBalancer() {
        val group = VlessGroup(ConnectionSelection.Lowest, listOf(vless(), vless("127.0.0.2", "xhttp")))
        val config = Json.parseToJsonElement(XrayGroupConfig.build(group)).jsonObject
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("member-0", rules[0].jsonObject["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("member-1", rules[1].jsonObject["outboundTag"]!!.jsonPrimitive.content)
        assertEquals("group", rules.last().jsonObject["balancerTag"]!!.jsonPrimitive.content)
        val outs = config["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertFalse(outs.any { it["protocol"]?.jsonPrimitive?.content == "freedom" })
        val tcp = outs.first { it["tag"]!!.jsonPrimitive.content == "member-0" }
        assertEquals("tcp", tcp["streamSettings"]!!.jsonObject["network"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", tcp["settings"]!!.jsonObject["vnext"]!!.jsonArray[0]
            .jsonObject["users"]!!.jsonArray[0].jsonObject["flow"]!!.jsonPrimitive.content)
        assertEquals(31001, group.probePort(group.members[1]))
    }

    @Test fun balancedUsesHealthAwareRandomAndLowestUsesLeastPing() {
        for ((mode, strategy) in listOf(ConnectionSelection.Lowest to "leastPing", ConnectionSelection.Balanced to "random")) {
            val config = Json.parseToJsonElement(XrayGroupConfig.build(VlessGroup(mode, listOf(vless())))).jsonObject
            val balancer = config["routing"]!!.jsonObject["balancers"]!!.jsonArray[0].jsonObject
            assertEquals(strategy, balancer["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("member-0", balancer["fallbackTag"]!!.jsonPrimitive.content)
            assertNotNull(config["observatory"])
            assertTrue(config["inbounds"]!!.jsonArray.all { it.jsonObject["listen"]!!.jsonPrimitive.content == "127.0.0.1" })
        }
    }

    @Test fun everyNamedMemberGetsBootstrapDnsBelowTheIosTunnel() {
        val group = VlessGroup(ConnectionSelection.Lowest, listOf(vless(), vless("second.example.test")))
        val config = Json.parseToJsonElement(XrayGroupConfig.build(group, answersDns = true)).jsonObject
        val domains = config["dns"]!!.jsonObject["servers"]!!.jsonArray[0].jsonObject["domains"]!!.jsonArray
        assertTrue(domains.any { it.jsonPrimitive.content == "full:second.example.test" })
        val second = config["outbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "member-1" }
        assertEquals("UseIPv4", second["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
    }
    @Test fun groupPreservesExplicitBootstrapDnsAndBypassDomains() {
        val group = VlessGroup(ConnectionSelection.Lowest,
            listOf(vless("first.example.test"), vless("second.example.test")))
        val config = Json.parseToJsonElement(XrayGroupConfig.build(group,
            routing = Routing.BypassRussia("unused", DirectDns.Servers(listOf("9.9.9.9"))),
            geodata = XrayGeodata.Lists(listOf("domain:example.ru"), emptyList()))).jsonObject
        val direct = config["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .filter { it["tag"]?.jsonPrimitive?.content == "dns-direct" }.single()
        assertEquals("9.9.9.9", direct["address"]!!.jsonPrimitive.content)
        assertEquals(setOf("domain:example.ru", "full:first.example.test", "full:second.example.test"),
            direct["domains"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun aliasesShareOneOutboundAndWarmupKeepsTheSelectedExit() {
        val first = vless("127.0.0.1")
        val selected = vless("127.0.0.2")
        val alias = selected.copy(name = "same server", rawLink = selected.rawLink + "#different-label")
        val bundle = LocationBundleV4(activeLocationId = "b", settings = SubscriptionSettings(
            connectionSelection = ConnectionSelection.Lowest), locations = listOf(
            entry("a", first), entry("alias", alias), entry("b", selected)))
        val group = VlessGroup.from(bundle, selected)!!
        assertEquals(2, group.members.size)
        assertEquals(1, group.fallbackIndex)
        assertEquals(group.probePort(selected), group.probePort(alias))
        val config = Json.parseToJsonElement(XrayGroupConfig.build(group)).jsonObject
        assertEquals("member-1", config["routing"]!!.jsonObject["balancers"]!!.jsonArray[0]
            .jsonObject["fallbackTag"]!!.jsonPrimitive.content)
    }

}
