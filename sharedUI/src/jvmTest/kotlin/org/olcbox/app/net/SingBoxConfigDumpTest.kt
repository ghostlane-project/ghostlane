package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only: runs the real parser+builder on realistic share links and writes the
 * generated sing-box configs to `build/singbox-configs/`. The singbox-verify CI
 * workflow then runs `sing-box check` on each against the pinned binary — proving
 * the config schema matches the real sing-box release, not just valid JSON.
 */
class SingBoxConfigDumpTest {
    private val outDir = File("build/singbox-configs")

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
    }

    @Test fun dumpVlessRealityTcp() {
        val link = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        dump("vless-reality", SingBoxConfig.build(spec))
    }

    // xhttp is NOT a sing-box transport — it's handled by Xray-core (see
    // XrayConfigDumpTest). sing-box covers reality (tcp) + hy2 + olcrtc-socks.

    @Test fun dumpHysteria2() {
        val link = "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=1#RU-hy2"
        val spec = LinkParser.parse(link)
        assertNotNull(spec)
        dump("hysteria2", SingBoxConfig.build(spec))
    }

    @Test fun dumpOlcrtcSocks() {
        dump("olcrtc-socks", SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808))
        assertTrue(File(outDir, "olcrtc-socks.json").exists())
    }

    /**
     * The shape the macOS root daemon runs. Everything unique to it — the route
     * exclusion, the direct-DNS rule, the second inbound — is schema the other
     * dumps never exercise, and all of it is 1.12+ syntax.
     */
    @Test fun dumpDesktopTun() {
        dumpWithSocksTwin(
            "desktop-tun",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32"),
                directDnsDomains = listOf("de1.example.org"),
                upstreamUdpIsLossy = true,
                cacheFilePath = "/Library/Application Support/org.olcbox.app/cache.db"
            )
        )
        assertTrue(File(outDir, "desktop-tun.json").exists())
    }

    /** The daemon's shape under Bypass Russia: rule-sets, a bound direct outbound, fake addresses. */
    @Test fun dumpDesktopTunBypass() = runTest {
        val rules = File(outDir, "rules").apply { mkdirs() }
        for (file in RuleSets.all) File(rules, file.name).writeBytes(RuleSets.bytes(file))
        dumpWithSocksTwin(
            "desktop-tun-bypass",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32"),
                directDnsDomains = listOf("de1.example.org"),
                routing = Routing.Rules(rules.absolutePath, DirectDns.System),
                bindInterface = "en0",
                cacheFilePath = "/Library/Application Support/org.olcbox.app/cache.db"
            )
        )
        assertTrue(File(outDir, "desktop-tun-bypass-as-socks.json").exists())
    }

    /**
     * The same builder without olcRTC's lossy-carrier handling — no hijack rule,
     * no remote resolver. It is a different config, so it gets its own check: the
     * first version of this shape was rejected by sing-box in exactly the section
     * the two variants differ in.
     */
    @Test fun dumpDesktopTunNative() {
        dump(
            "desktop-tun-native",
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                excludeAddresses = listOf("203.0.113.7/32", "2001:db8::1/128"),
                directDnsDomains = listOf("de1.example.org")
            )
        )
        assertTrue(File(outDir, "desktop-tun-native.json").exists())
    }
    /**
     * Bypass Russia. Every rule-set is a real file here, because `sing-box check`
     * opens local rule-sets while building the router — a missing file fails the
     * check exactly as it would fail a connect.
     */
    /**
     * A tun shape cannot be started on a runner — a tun needs privileges — but
     * everything that failed at *start* so far lived in the dns section, which
     * a socks inbound exercises just as well. So every tun dump also gets a
     * twin with a socks inbound and its cache file pointed into the build
     * directory, and the check script starts the twin.
     */
    private fun dumpWithSocksTwin(name: String, json: String) {
        dump(name, json)
        val obj = Json.parseToJsonElement(json).jsonObject.toMutableMap()
        obj["inbounds"] = buildJsonArray {
            addJsonObject {
                put("type", "socks"); put("tag", "in")
                put("listen", "127.0.0.1"); put("listen_port", 18811)
            }
        }
        val experimental = obj["experimental"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        val cache = experimental["cache_file"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        cache["path"] = JsonPrimitive(File(outDir, "$name.cache.db").absolutePath)
        experimental["cache_file"] = JsonObject(cache)
        obj["experimental"] = JsonObject(experimental)
        // A daemon shape binds its direct outbound to a macOS interface; the twin
        // runs on Linux, so it binds to the loopback there — nothing dials.
        obj["outbounds"] = buildJsonArray {
            obj["outbounds"]!!.jsonArray.forEach { outbound ->
                val fields = outbound.jsonObject.toMutableMap()
                if (fields["bind_interface"] != null) fields["bind_interface"] = JsonPrimitive("lo")
                add(JsonObject(fields))
            }
        }
        dump("$name-as-socks", JsonObject(obj).toString())
    }

    /** The iOS shapes in Global: they answer DNS themselves now, with fake addresses. */
    @Test fun dumpIosShapes() {
        val reality = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
                "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
                "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality"
        )
        assertNotNull(reality)
        dumpWithSocksTwin("ios-tun-reality", SingBoxConfig.buildTun(reality))
        dumpWithSocksTwin(
            "ios-tun-socks-lossy",
            SingBoxConfig.buildTunSocks(10810, username = "u", password = "p", upstreamUdpIsLossy = true)
        )
        assertTrue(File(outDir, "ios-tun-socks-lossy-as-socks.json").exists())
    }

    @Test fun dumpBypassShapes() = runTest {
        val rules = File(outDir, "rules").apply { mkdirs() }
        for (file in RuleSets.all) File(rules, file.name).writeBytes(RuleSets.bytes(file))
        val android = Routing.Rules(rules.absolutePath, DirectDns.Servers(listOf("10.20.30.40")))
        val ios = Routing.Rules(rules.absolutePath, DirectDns.Placeholder)

        val reality = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
                "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
                "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality"
        )
        assertNotNull(reality)
        val hy2 = LinkParser.parse(
            "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=1#RU-hy2"
        )
        assertNotNull(hy2)

        dump("bypass-socks-reality", SingBoxConfig.build(reality, routing = android))
        dump("bypass-socks-chain", SingBoxConfig.buildSocksChain(10808, username = "u", password = "p", routing = android))
        dumpWithSocksTwin("bypass-tun-reality", SingBoxConfig.buildTun(reality, routing = ios))
        dumpWithSocksTwin("bypass-tun-hysteria2", SingBoxConfig.buildTun(hy2, routing = ios))
        dumpWithSocksTwin("bypass-tun-socks", SingBoxConfig.buildTunSocks(10810, routing = ios))
        dumpWithSocksTwin(
            "bypass-tun-socks-lossy",
            SingBoxConfig.buildTunSocks(10810, username = "u", password = "p", upstreamUdpIsLossy = true, routing = ios)
        )
        assertTrue(File(outDir, "bypass-tun-socks-lossy.json").exists())
    }
}
