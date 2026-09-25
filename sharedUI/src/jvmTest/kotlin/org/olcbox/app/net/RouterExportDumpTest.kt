package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What a router is handed, for every protocol a link can carry: each Xray outbound
 * and each sing-box outbound is wrapped in the smallest config that uses it and
 * written where CI's `xray run -test` and `sing-box check` read (check-xray-configs.sh,
 * check-singbox-configs.sh), so the pinned binaries are the judge of the schema.
 */
class RouterExportDumpTest {
    private val links = mapOf(
        "vless-reality" to "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#R",
        "vless-xhttp" to "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&type=xhttp&path=%2Fx&mode=packet-up#X",
        "vless-grpc" to "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&type=grpc&serviceName=svc#G",
        "vless-ws-tls" to "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=tls&sni=cdn.example.com&type=ws&path=%2Fv&host=cdn.example.com#W",
        "hysteria2" to "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=0" +
            "&pinSHA256=AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34#H",
        "trojan-ws" to "trojan://secret@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fws&host=cdn.example.com&fp=chrome#T",
        "shadowsocks-2022" to "ss://2022-blake3-aes-256-gcm:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D@127.0.0.1:8388#S",
        "vmess-ws-tls" to "vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"add":"127.0.0.1","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"0","scy":"auto","net":"ws","path":"/vm","host":"cdn.example.com","tls":"tls","sni":"cdn.example.com","ps":"V"}""".toByteArray()
        ),
    )

    private fun write(dir: String, name: String, config: JsonElement) {
        File(dir).mkdirs()
        File(dir, "$name.json").writeText(config.toString())
    }

    @Test fun everyProtocolBecomesAnXrayAndASingBoxOutboundTheBinariesAccept() {
        for ((name, link) in links) {
            val spec = LinkParser.parse(link)
            assertNotNull(spec, name)

            val xray = Json.parseToJsonElement(RouterExport.xrayOutbound(spec)).jsonObject
            assertEquals(RouterExport.TAG, xray["tag"]!!.jsonPrimitive.content, name)
            write("build/xray-configs", "router-$name", buildJsonObject {
                putJsonObject("log") { put("loglevel", "warning") }
                putJsonArray("inbounds") {
                    addJsonObject {
                        put("listen", "127.0.0.1"); put("port", 10999); put("protocol", "socks")
                        putJsonObject("settings") { put("udp", true) }
                    }
                }
                putJsonArray("outbounds") { add(xray) }
            })

            val singBoxText = RouterExport.singBoxOutbound(spec)
            if (name == "vless-xhttp") {
                assertEquals(null, singBoxText, "sing-box has no XHTTP")
                continue
            }
            assertNotNull(singBoxText, name)
            val singBox = Json.parseToJsonElement(singBoxText).jsonObject
            assertEquals(RouterExport.TAG, singBox["tag"]!!.jsonPrimitive.content, name)
            write("build/singbox-configs", "router-$name", buildJsonObject {
                putJsonObject("log") { put("level", "warn") }
                putJsonArray("inbounds") {
                    addJsonObject { put("type", "socks"); put("tag", "in"); put("listen", "127.0.0.1"); put("listen_port", 10998) }
                }
                putJsonArray("outbounds") { add(singBox) }
            })
        }
    }

    @Test fun visionStaysOffAnythingButTcp() {
        val xhttp = RouterExport.xray(LinkParser.parse(links.getValue("vless-xhttp"))!!)
        val user = xhttp["settings"]!!.jsonObject["vnext"]!!.let { it as kotlinx.serialization.json.JsonArray }[0]
            .jsonObject["users"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals(null, user["flow"])
        val tcp = RouterExport.xray(LinkParser.parse(links.getValue("vless-reality"))!!)
        val tcpUser = (tcp["settings"]!!.jsonObject["vnext"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["users"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("xtls-rprx-vision", tcpUser["flow"]!!.jsonPrimitive.content)
    }

    @Test fun hysteria2CarriesItsAuthPinAndSalamanderWhereXrayReadsThem() {
        val stream = RouterExport.xray(LinkParser.parse(links.getValue("hysteria2"))!!)["streamSettings"]!!.jsonObject
        assertEquals("PASSWORD123", stream["hysteriaSettings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        assertEquals("ab12cd34".repeat(8), stream["tlsSettings"]!!.jsonObject["pinnedPeerCertSha256"]!!.jsonPrimitive.content)
        val mask = (stream["finalmask"]!!.jsonObject["udp"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("salamander", mask["type"]!!.jsonPrimitive.content)
        assertEquals("OBFSPW", mask["settings"]!!.jsonObject["password"]!!.jsonPrimitive.content)
    }
}
