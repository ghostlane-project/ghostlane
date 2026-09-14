package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bypass Russia on Xray: the shape the iOS extension runs when a tun2socks
 * fronts Xray and there is no sing-box to route. The lists are stand-ins
 * here; [XrayGeodataTest] checks the bundled ones.
 */
class XrayConfigRoutingTest {
    private val lists = XrayGeodata.Lists(
        domains = listOf("domain:sberbank.ru", "full:ozon.ru", "domain:ru"),
        cidrs = listOf("5.8.0.0/19", "77.88.0.0/18"),
    )

    private fun xhttp(host: String = "1.2.3.4") = OutboundSpec.Vless(
        "u", host, 443, "sni.x", "PBK", "sid", "chrome", null,
        TransportSpec.Xhttp("/dl", "sni.x", "packet-up"), "T"
    )

    private fun build(
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        answersDns: Boolean = false,
        host: String = "1.2.3.4",
    ): JsonObject = Json.parseToJsonElement(
        XrayConfig.buildXhttp(xhttp(host), routing = routing, geodata = geodata, answersDns = answersDns)
    ).jsonObject

    private fun bypass() = Routing.BypassRussia("unused-on-xray", DirectDns.Placeholder)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }
    private fun JsonObject.rules(): List<JsonObject> = this["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.outboundTags() = this["outbounds"]!!.jsonArray.map { it.jsonObject.str("tag") }

    @Test fun globalIsTheShapeItAlwaysWas() {
        // No dns, no routing, no sniffing, one outbound: the config every
        // Android and desktop xhttp session starts with, unchanged.
        val json = build()
        assertNull(json["dns"]); assertNull(json["routing"])
        assertNull(json["inbounds"]!!.jsonArray[0].jsonObject["sniffing"])
        assertEquals(listOf("out"), json.outboundTags())
        assertEquals(
            XrayConfig.buildXhttp(xhttp()),
            XrayConfig.buildXhttp(xhttp(), routing = Routing.Global, answersDns = false),
            "the defaults are the plain shape"
        )
    }

    @Test fun bypassNeedsItsLists() {
        val failed = runCatching { XrayConfig.buildXhttp(xhttp(), routing = bypass()) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun bypassSplitsTheResolvers() {
        val dns = build(bypass(), lists)["dns"]!!.jsonObject
        assertEquals("UseIPv4", dns.str("queryStrategy"))
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dns-direct", "dns-remote"), servers.map { it.str("tag") })
        // The direct one answers the Russian lists, on the network underneath,
        // and only them: skipFallback keeps it out of the fallback chain for
        // every other name.
        assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, servers[0].str("address"))
        assertEquals(lists.domains, servers[0]["domains"]!!.jsonArray.strings())
        assertEquals("true", servers[0].str("skipFallback"))
        // The remote one, last, claims nothing and therefore gets the rest.
        assertEquals("1.1.1.1", servers[1].str("address"))
        assertNull(servers[1]["domains"])
    }

    @Test fun thePlaceholderAppearsOnceForTheExtensionToReplace() {
        // DirectResolver.substitute replaces the quoted placeholder wherever it
        // appears; one occurrence is what keeps that a substitution rather
        // than a parser.
        val config = XrayConfig.buildXhttp(xhttp(), routing = bypass(), geodata = lists)
        assertEquals(1, Regex("\"${SingBoxConfig.DIRECT_DNS_PLACEHOLDER}\"").findAll(config).count())
    }

    @Test fun bypassRoutesInTheOrderThatMatters() {
        val json = build(bypass(), lists)
        assertEquals(listOf("out", "direct", "dns-out"), json.outboundTags())
        val direct = json["outbounds"]!!.jsonArray[1].jsonObject
        assertEquals("freedom", direct.str("protocol"))
        assertEquals("UseIPv4", direct["settings"]!!.jsonObject.str("domainStrategy"))
        assertEquals("AsIs", json["routing"]!!.jsonObject.str("domainStrategy"))

        val rules = json.rules()
        assertEquals(6, rules.size)
        // 1. the system's DNS datagrams, from our inbound only, to the resolver
        assertEquals(listOf("in"), rules[0]["inboundTag"]!!.jsonArray.strings())
        assertEquals("udp", rules[0].str("network")); assertEquals("53", rules[0].str("port"))
        assertEquals("dns-out", rules[0].str("outboundTag"))
        // 2/3. each resolver's own queries to its side of the tunnel
        assertEquals(listOf("dns-direct"), rules[1]["inboundTag"]!!.jsonArray.strings())
        assertEquals("direct", rules[1].str("outboundTag"))
        assertEquals(listOf("dns-remote"), rules[2]["inboundTag"]!!.jsonArray.strings())
        assertEquals("out", rules[2].str("outboundTag"))
        // 4. the local network, 5. the Russian names, 6. the Russian addresses
        assertEquals(XrayConfig.PRIVATE_RANGES, rules[3]["ip"]!!.jsonArray.strings())
        assertEquals(lists.domains, rules[4]["domain"]!!.jsonArray.strings())
        assertEquals(lists.cidrs, rules[5]["ip"]!!.jsonArray.strings())
        for (rule in rules.drop(3)) assertEquals("direct", rule.str("outboundTag"))
    }

    @Test fun bypassSniffsSoARuleCanMatchAName() {
        val sniffing = build(bypass(), lists)["inbounds"]!!.jsonArray[0].jsonObject["sniffing"]!!.jsonObject
        assertEquals("true", sniffing.str("enabled"))
        assertEquals(listOf("http", "tls", "quic"), sniffing["destOverride"]!!.jsonArray.strings())
        assertNull(sniffing["routeOnly"], "the sniffed name replaces the address for the dial, as sing-box's fake addresses did")
    }

    @Test fun answeringDnsWithoutABypassIsTheTunnelOnly() {
        // A tun2socks in front sends every port-53 datagram to Xray, so it has
        // to answer even in Global mode — through the tunnel, with nothing
        // going direct.
        val json = build(answersDns = true)
        assertEquals(listOf("out", "dns-out"), json.outboundTags())
        val servers = json["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dns-remote"), servers.map { it.str("tag") })
        assertEquals(listOf("dns-out", "out"), json.rules().map { it.str("outboundTag") })
        assertTrue(json["inbounds"]!!.jsonArray[0].jsonObject["sniffing"] != null)
        assertFalse(XrayConfig.buildXhttp(xhttp(), answersDns = true).contains(SingBoxConfig.DIRECT_DNS_PLACEHOLDER))
    }

    @Test fun aServerNamedByHostnameResolvesUnderneath() {
        // The server's own name cannot be resolved through the tunnel it is
        // about to carry: it gets the direct resolver, and the outbound is
        // told to resolve through Xray's dns rather than the system's, which
        // inside the extension is the tun.
        val json = build(answersDns = true, host = "edge.example.org")
        val servers = json["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("dns-direct", "dns-remote"), servers.map { it.str("tag") })
        assertEquals(listOf("full:edge.example.org"), servers[0]["domains"]!!.jsonArray.strings())
        assertEquals(listOf("out", "direct", "dns-out"), json.outboundTags())
        val sockopt = json["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertEquals("UseIPv4", sockopt.str("domainStrategy"))
        assertEquals(listOf("dns-out", "direct", "out"), json.rules().map { it.str("outboundTag") })
        // An address needs none of that.
        assertNull(build(answersDns = true)["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject["sockopt"])
    }

    @Test fun ipLiterals() {
        assertTrue(XrayConfig.isIpLiteral("1.2.3.4"))
        assertTrue(XrayConfig.isIpLiteral("2001:db8::1"))
        assertFalse(XrayConfig.isIpLiteral("edge.example.org"))
        assertFalse(XrayConfig.isIpLiteral("1.2.3"))
        assertFalse(XrayConfig.isIpLiteral("999.1.1.1"))
    }
}
