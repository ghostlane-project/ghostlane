package org.olcbox.app.vpn.desktop

import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.XrayConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression: the core SOCKS port used to equal PacServer.PAC_PORT (10809). The
 * core bound it first, the PAC server could not start, and every desktop connect
 * to a reality/hy2/xhttp location died with "Address already in use" — after the
 * log had already said "core ready", which made it look like the core was fine.
 */
class CorePortCollisionTest {

    @Test
    fun coreSocksPortDoesNotCollideWithThePacServer() {
        assertNotEquals(
            PacServer.PAC_PORT,
            XrayConfig.XRAY_SOCKS_PORT,
            "the core would take the PAC port and break system-proxy mode"
        )
    }

    @Test
    fun coreSocksPortDoesNotCollideWithTheOlcrtcSocksPort() {
        assertNotEquals(PacServer.LOCAL_SOCKS_PORT, XrayConfig.XRAY_SOCKS_PORT)
    }

    @Test
    fun xrayConfigListensOnThePortItIsGiven() {
        val spec = LinkParser.parse(
            "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:40023" +
                "?type=xhttp&security=reality&encryption=none&pbk=abc&sid=ff&fp=chrome" +
                "&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#x"
        )
        assertTrue(spec is OutboundSpec.Vless)
        val json = XrayConfig.buildXhttp(spec, socksPort = 12346)
        assertContains(json, "12346")
    }
}
