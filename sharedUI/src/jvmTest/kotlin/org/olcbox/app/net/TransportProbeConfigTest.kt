package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The probe's config is built in one place for Android and the desktop. A
// desktop session that looks for another line probes beside its tun, and on
// Linux the probe's core has to be bound to the physical interface to get out
// of it. These pin that what was added for that changes nothing for a caller
// that does not ask for it, and what it writes for one that does.
class TransportProbeConfigTest {
    private val login = SocksLogin("probe-user", "probe-pass")
    private val port = 20_001
    private val resolver = DirectDns.Servers(listOf("192.168.1.1"))

    private fun reality(host: String) = LinkParser.parse(
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:443" +
            "?type=tcp&security=reality&sni=www.zoom.us&fp=chrome" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ff&flow=xtls-rprx-vision#x"
    ) ?: error("the test's own link does not parse")

    private fun hysteria2(host: String) = LinkParser.parse(
        "hysteria2://pass@$host:30023?sni=www.zoom.us&obfs=salamander&obfs-password=secret&insecure=0#x"
    ) ?: error("the test's own link does not parse")

    private fun xhttp(host: String) = LinkParser.parse(
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:40023" +
            "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ff" +
            "&fp=chrome&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#x"
    ) as? OutboundSpec.Vless ?: error("the test's own link does not parse")

    private val singBoxLines = listOf(
        reality("1.2.3.4"), reality("vpn.example.com"), hysteria2("1.2.3.4"), hysteria2("vpn.example.com")
    )
    private val xrayLines = listOf(xhttp("1.2.3.4"), xhttp("vpn.example.com"))

    // The right-hand sides are the calls the probe made before it could be
    // bound: with no resolver, as the desktop's probe before a connect makes
    // it, and with one, as Android's two callers do.
    @Test
    fun withNothingAskedForTheConfigIsByteForByteWhatItWas() {
        for (spec in singBoxLines) {
            assertEquals(
                SingBoxConfig.build(spec, socksPort = port, login = login, serverResolver = null),
                TransportProbe.coreConfig(spec, port, login),
                spec.host
            )
            assertEquals(
                SingBoxConfig.build(spec, socksPort = port, login = login, serverResolver = resolver),
                TransportProbe.coreConfig(spec, port, login, resolver),
                spec.host
            )
        }
        for (spec in xrayLines) {
            assertTrue(TransportProbe.usesXray(spec))
            val before = XrayConfig.buildXhttp(spec, socksPort = port, login = login)
            assertEquals(before, TransportProbe.coreConfig(spec, port, login), spec.host)
            // Xray never took the resolver, and still does not.
            assertEquals(before, TransportProbe.coreConfig(spec, port, login, resolver), spec.host)
        }
    }

    // Said outright as well, for a reader who does not want to follow the
    // defaults through three functions.
    @Test
    fun theDefaultsBindNothing() {
        for (spec in singBoxLines + xrayLines) {
            val config = TransportProbe.coreConfig(spec, port, login, resolver)
            assertFalse("auto_detect_interface" in config, spec.host)
            assertFalse("\"interface\"" in config, spec.host)
        }
    }

    // The Linux tunnel. sing-box finds the interface itself and is given the
    // resolver for a name; Xray is told the interface's name and nothing else.
    // Each takes its own and is not handed the other's.
    @Test
    fun besideATunSingBoxFindsTheInterfaceAndXrayIsToldItsName() {
        for (spec in singBoxLines) {
            val config = TransportProbe.coreConfig(
                spec, port, login, resolver, autoDetectInterface = true, bindInterface = "wlan0"
            )
            assertEquals(
                SingBoxConfig.build(
                    spec, socksPort = port, login = login, serverResolver = resolver, autoDetectInterface = true
                ),
                config,
                spec.host
            )
            val route = Json.parseToJsonElement(config).jsonObject["route"]!!.jsonObject
            assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean, spec.host)
            assertFalse("wlan0" in config, spec.host)
        }
        for (spec in xrayLines) {
            val config = TransportProbe.coreConfig(
                spec, port, login, resolver, autoDetectInterface = true, bindInterface = "wlan0"
            )
            assertEquals(
                XrayConfig.buildXhttp(spec, socksPort = port, login = login, bindInterface = "wlan0"),
                config,
                spec.host
            )
            assertContains(config, "\"interface\":\"wlan0\"")
        }
    }

    // What a probe beside the Linux tun is started with, written out for the
    // pinned binaries: `sing-box check`, a start of it, and `xray -test` in PR
    // Checks read these as they read every other shape the app builds.
    @Test
    fun dumpTheConfigsOfAProbeBesideATun() {
        val singBox = File("build/singbox-configs").apply { mkdirs() }
        val xray = File("build/xray-configs").apply { mkdirs() }
        File(singBox, "desktop-probe-beside-tun.json").writeText(
            TransportProbe.coreConfig(
                reality("vpn.example.com"), port, login, resolver, autoDetectInterface = true, bindInterface = "eth0"
            )
        )
        File(xray, "desktop-probe-xhttp-beside-tun.json").writeText(
            TransportProbe.coreConfig(
                xhttp("127.0.0.1"), port, login, resolver, autoDetectInterface = true, bindInterface = "eth0"
            )
        )
        assertTrue(File(singBox, "desktop-probe-beside-tun.json").exists())
        assertTrue(File(xray, "desktop-probe-xhttp-beside-tun.json").exists())
    }
}
