package org.olcbox.app.net

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.DesktopLanProxy
import org.olcbox.app.vpn.DesktopSocksProxySettings
import kotlin.test.*

class DesktopRoutingRegressionTest {
    private val base = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:2053" +
        "?security=reality&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sni=rutube.ru"
    private val out = File("build/singbox-configs").apply { mkdirs() }

    /** Opt-in operator smoke test. Never contacts a real server in ordinary CI. */
    @Test fun liveGrpcThroughTheActualCoreWhenConfigured() = runBlocking {
        val path = System.getenv("PROOFKIT_LIVE_LOCATIONS")
        org.junit.Assume.assumeTrue("No live profile fixture supplied", !path.isNullOrBlank())
        val locations = Json.parseToJsonElement(File(path!!).readText()).jsonObject["locations"]!!.jsonArray
        val spec = locations.mapNotNull { it.jsonObject["raw_link"]?.jsonPrimitive?.content?.let(LinkParser::parse) }
            .filterIsInstance<OutboundSpec.Vless>().first { it.transport is TransportSpec.Grpc }
        val millis = DesktopChannelProbe.measure(spec)
        assertNotNull(millis, "gRPC outbound did not return an HTTP 204 through the VPN")
        println("Live gRPC HTTP channel: ${millis}ms")
    }

    @Test fun liveLanSocksWithoutCredentialsWhenConfigured() = runBlocking {
        val path = System.getenv("PROOFKIT_LIVE_LOCATIONS")
        org.junit.Assume.assumeTrue("No live profile fixture supplied", !path.isNullOrBlank())
        val host = DesktopLanProxy.privateAddresses().firstOrNull()
        org.junit.Assume.assumeTrue("No private LAN adapter", host != null)
        val locations = Json.parseToJsonElement(File(path!!).readText()).jsonObject["locations"]!!.jsonArray
        val spec = locations.mapNotNull { it.jsonObject["raw_link"]?.jsonPrimitive?.content?.let(LinkParser::parse) }
            .filterIsInstance<OutboundSpec.Vless>().first { it.transport is TransportSpec.Grpc }
        fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
        val corePort = freePort()
        val lanPort = generateSequence { freePort() }.first { it != corePort }
        val core = DesktopSingBoxController()
        val lan = DesktopLanProxy { /* no profile or credential logging in a live test */ }
        try {
            core.start(SingBoxConfig.build(spec, socksPort = corePort))
            withTimeout(5_000) {
                while (runCatching { java.net.Socket("127.0.0.1", corePort).close() }.isFailure) delay(100)
            }
            lan.start(DesktopSocksProxySettings(shareOnLan = true, lanPort = lanPort), SubscriptionFetchProxy("127.0.0.1", corePort))
            val millis = ChannelLatency.measure(SubscriptionFetchProxy(host!!, lanPort))
            assertNotNull(millis, "Unauthenticated LAN SOCKS did not carry HTTP through gRPC")
            println("LAN SOCKS -> gRPC -> HTTP 204: ${millis}ms (same-PC private-address test)")
        } finally {
            lan.stop()
            core.stop()
        }
        assertFalse(lan.isRunning())
    }

    @Test fun grpcKeepsItsServiceAndDoesNotBecomeTcp() {
        val spec = assertIs<OutboundSpec.Vless>(LinkParser.parse("$base&type=grpc&serviceName=media%2Fsync&flow=xtls-rprx-vision"))
        assertEquals("media/sync", assertIs<TransportSpec.Grpc>(spec.transport).serviceName)
        assertNull(spec.flow)
        val config = SingBoxConfig.build(spec)
        val outbound = Json.parseToJsonElement(config).jsonObject["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("grpc", outbound["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("media/sync", outbound["transport"]!!.jsonObject["service_name"]!!.jsonPrimitive.content)
        assertFalse("flow" in outbound)
        File(out, "grpc-reality.json").writeText(config)
    }

    @Test fun unknownTransportIsRejectedAndPercentEscapesAreDecodedOnce() {
        assertNull(LinkParser.parse("$base&type=unsupported"))
        val spec = assertIs<OutboundSpec.Vless>(LinkParser.parse("$base&type=xhttp&path=%2Fencoded%252Fpart"))
        assertEquals("/encoded%2Fpart", assertIs<TransportSpec.Xhttp>(spec.transport).path)
    }

    @Test fun windowsCarrierBypassIsScopedToTunAndPreservesUpstreamAuth() = runTest {
        val rulesDir = File(out, "rules").apply { mkdirs() }
        for (file in RuleSets.bundled) File(rulesDir, file.name).writeBytes(RuleSets.bytes(file))
        for (region in listOf(null, "ru", "ir", "cn")) {
            val routing = Routing.Rules(rulesDir.absolutePath, DirectDns.System, region, blockAds = true)
            val config = SingBoxConfig.buildDesktopTun(10808, 10811,
                username = "internal", password = "secret", upstreamUdpIsLossy = true,
                routing = routing, bindInterface = "Ethernet", bypassProcessPaths = listOf("C:\\app\\olcrtc.exe"))
            val doc = Json.parseToJsonElement(config).jsonObject
            val route = doc["route"]!!.jsonObject
            assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
            val carrier = route["rules"]!!.jsonArray.first().jsonObject
            assertEquals(listOf("tun-in"), carrier["inbound"]!!.jsonArray.map { it.jsonPrimitive.content })
            val outbound = doc["outbounds"]!!.jsonArray.first().jsonObject
            assertEquals("secret", outbound["password"]!!.jsonPrimitive.content)
            val declared = route["rule_set"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
            assertEquals(RuleSets.selected(routing).map { it.tag }, declared)
            assertTrue(RuleSets.GEOSITE_CATEGORY_ADS_ALL.tag in declared)
            if (region == null) assertFalse(config.contains("ip_is_private"))
            File(out, "windows-${region ?: "global"}-ads.json").writeText(config)
        }
    }

    @Test fun lanIsUnauthenticatedButItsUpstreamKeepsCredentials() {
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10808, "internal", "secret")
        // Use an address owned by the test host so the CI smoke test can start
        // this emitted config instead of failing at bind before sing-box starts.
        val availableHost = DesktopLanProxy.privateAddresses().firstOrNull()
        val host = availableHost ?: "192.168.1.10"
        val config = DesktopLanProxy.config(host, 10818, upstream)
        val doc = Json.parseToJsonElement(config).jsonObject
        val inbound = doc["inbounds"]!!.jsonArray.single().jsonObject
        assertFalse("users" in inbound)
        assertEquals(host, inbound["listen"]!!.jsonPrimitive.content)
        assertEquals("secret", doc["outbounds"]!!.jsonArray.first().jsonObject["password"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { DesktopLanProxy.config("0.0.0.0", 10818, upstream) }
        assertFailsWith<IllegalArgumentException> { DesktopLanProxy.config("203.0.113.1", 10818, upstream) }
        assertFailsWith<IllegalArgumentException> { DesktopLanProxy.config("192.168.1.10", 10808, upstream) }
        // If no private adapter exists, the pure JSON assertions above still
        // run, but there is deliberately no impossible address-bound fixture.
        if (availableHost != null) {
            File(out, "lan-socks.json").writeText(config)
        }
    }

    @Test fun xrayAdsOnlyNeverEmitsAnEmptyDirectMatch() = runTest {
        val spec = assertIs<OutboundSpec.Vless>(LinkParser.parse("$base&type=xhttp&path=%2Fx"))
        val dir = File("build/xray-configs").apply { mkdirs() }
        for (region in listOf(null, "ru", "ir", "cn")) {
            val config = XrayConfig.buildXhttp(spec,
                routing = Routing.Rules("unused", DirectDns.Servers(listOf("192.168.1.1")), region, true),
                geodata = XrayGeodata.lists(region, true), answersDns = true)
            val rules = Json.parseToJsonElement(config).jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray
            rules.forEach { rule ->
                for (key in listOf("domain", "ip")) rule.jsonObject[key]?.let { assertTrue(it.jsonArray.isNotEmpty()) }
            }
            assertTrue(rules.any { it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "blocked" })
            if (region == null) assertFalse(rules.any { "domain" in it.jsonObject && it.jsonObject["outboundTag"]?.jsonPrimitive?.content == "direct" })
            File(dir, "${region ?: "global"}-ads.json").writeText(config)
        }
    }
}
