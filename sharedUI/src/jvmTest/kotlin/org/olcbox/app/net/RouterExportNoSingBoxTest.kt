package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The router export and the routing model stand without SingBoxConfig (S8). The
 * sing-box outbound a router is handed is compared with what SingBoxConfig builds
 * today, which stays in the tree until G4: the test may read it, the export may not.
 */
class RouterExportNoSingBoxTest {
    private val links = listOf(
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision-udp443&type=tcp#R",
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12cd34&fp=hellofirefox_auto&sni=www.microsoft.com&type=grpc&serviceName=svc#G",
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=tls&sni=cdn.example.com&type=ws&path=%2Fv&host=cdn.example.com&alpn=h2,http%2F1.1#W",
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=tls&sni=cdn.example.com&type=httpupgrade&path=%2Fu&host=cdn.example.com&allowInsecure=1#U",
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?type=http&path=%2Fh2&security=reality" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12&sni=www.example.com#H",
        "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=0" +
            "&pinSHA256=AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34AB12CD34#H",
        "hysteria2://PW@127.0.0.1:443?sni=example.com&insecure=1#H2",
        "trojan://secret@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Fws&host=cdn.example.com&fp=chrome#T",
        "trojan://secret@127.0.0.1:443?security=tls&sni=example.com&type=grpc&serviceName=s&fp=randomized#T2",
        "ss://2022-blake3-aes-256-gcm:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D@127.0.0.1:8388#S",
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"add":"127.0.0.1","port":"443","id":"11111111-1111-1111-1111-111111111111","aid":"2","scy":"auto","net":"ws","path":"/vm","host":"cdn.example.com","tls":"tls","sni":"cdn.example.com","ps":"V"}""".toByteArray()
        ),
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"add":"127.0.0.1","port":"80","id":"11111111-1111-1111-1111-111111111111","aid":"0","scy":"auto","net":"tcp","ps":"V2"}""".toByteArray()
        ),
    )

    private val pretty = Json { prettyPrint = true }

    private fun source(name: String): String {
        val dir = File("src/commonMain/kotlin/org/olcbox/app/net")
        return File(dir, name).readText()
    }

    @Test fun theRouterExportAndRoutingNameNoSingBoxConfig() {
        for (name in listOf("RouterExport.kt", "Routing.kt")) {
            assertFalse(Regex("""\bSingBoxConfig\b""").containsMatchIn(source(name)), "$name mentions SingBoxConfig")
        }
    }

    @Test fun theSingBoxOutboundIsWhatSingBoxConfigBuilds() {
        var checked = 0
        for (link in links) {
            val spec = LinkParser.parse(link)
            assertNotNull(spec, link)
            val text = RouterExport.singBoxOutbound(spec)
            if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) { assertEquals(null, text); continue }
            val expected = pretty.encodeToString(JsonElement.serializer(), SingBoxConfig.outbound(spec, RouterExport.TAG))
            assertEquals(expected, text, link)
            checked++
        }
        assertTrue(checked >= 10, "only $checked links compared")
    }

    @Test fun theRoutingDefaultsAreSingBoxConfigsValues() {
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, RoutingDefaults.DIRECT_DNS_FALLBACK)
        assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, RoutingDefaults.DIRECT_DNS_PLACEHOLDER)
    }
}
