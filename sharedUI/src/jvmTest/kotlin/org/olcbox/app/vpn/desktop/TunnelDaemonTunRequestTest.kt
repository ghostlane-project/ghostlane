package org.olcbox.app.vpn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.SocksLogin

/**
 * The fifth daemon's `tun` request. The same request lies in
 * macos-helper/tun-request.json, which the Mac's checks of the daemon read
 * (scripts/macos-helper-check/): what the app sends is what was asked of the
 * daemon on a Mac, field by field.
 */
class TunnelDaemonTunRequestTest {
    private val fixture = File("src/jvmTest/resources/macos-helper/tun-request.json")

    private fun everyField() = DaemonTun(
        interfaceName = "utun225",
        socksPort = 18821,
        login = SocksLogin("ghostlane", "p4ss w'rd"),
        names = DaemonMapDns.ADDRESS,
        hold = DaemonHold(interfaceName = "utun225", passes = listOf("203.0.113.7"))
    )

    @Test
    fun theRequestIsTheOneTheMacChecksSend() {
        val request = TunnelDaemonProtocol.tunRequest(everyField())
        assertEquals(Json.parseToJsonElement(fixture.readText()), Json.parseToJsonElement(request))
    }

    @Test
    fun itIsOneLineAsEveryRequest() {
        val request = TunnelDaemonProtocol.tunRequest(everyField())
        assertEquals(1, request.count { it == '\n' })
        assertTrue(request.endsWith("}\n"), request)
    }

    // The daemon refuses a field it does not know and a script by name: the
    // app sends none, and no config that could hold one.
    @Test
    fun noFieldNamesAScriptOrCarriesAConfig() {
        val request = TunnelDaemonProtocol.tunRequest(everyField())
        assertFalse("script" in request, request)
        assertFalse("\"config\"" in request, request)
        assertFalse("post-up" in request || "pre-down" in request, request)
    }

    @Test
    fun aPortThatAsksForNoLoginIsSentNone() {
        val request = Json.parseToJsonElement(
            TunnelDaemonProtocol.tunRequest(DaemonTun(interfaceName = "utun226", socksPort = 18822))
        ).jsonObject
        val socks = request["socks"] as JsonObject
        assertEquals(setOf("address", "port"), socks.keys)
        // Neither names nor a block unless asked for.
        assertEquals(setOf("verb", "tun", "socks", "mapdns"), request.keys)
    }

    @Test
    fun aTunWithoutIpv6OrMapdnsSaysNeither() {
        val request = Json.parseToJsonElement(
            TunnelDaemonProtocol.tunRequest(DaemonTun(interfaceName = "utun226", socksPort = 18822, ipv6 = null, mapDns = null))
        ).jsonObject
        assertEquals(setOf("name", "mtu", "ipv4"), (request["tun"] as JsonObject).keys)
        assertFalse("mapdns" in request.keys)
    }

    @Test
    fun hevIsTheFifthProtocol() {
        assertEquals(5, TunnelDaemonProtocol.PROTOCOL_HEV)
        assertTrue(TunnelDaemonProtocol.PROTOCOL_HEV > TunnelDaemonProtocol.PROTOCOL_LINE)
    }
}
