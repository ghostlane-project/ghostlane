package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trojan on the Rust core, as the link parser reads a Trojan link: over raw
 * TCP, WebSocket, gRPC and HTTP upgrade, always with ordinary TLS (Trojan has
 * no plain form, and the parser gives a link TLS whatever its `security`
 * says, Reality aside, which it refuses). A link that waives certificate
 * checks is carried with `allowInsecure`, which the core honours only when
 * the config sets it; a link that does not is written without the key, so
 * the core verifies. HTTP/2 (`type=http`) is refused with a sentence: the
 * core has no such transport.
 */
class TrojanTest {
    private val password = "trojan-test-password"

    private fun link(query: String, host: String = "127.0.0.1", port: Int = 443, pass: String = password): String =
        "trojan://$pass@$host:$port?$query#t"

    private fun spec(link: String): OutboundSpec = assertNotNull(LinkParser.parse(link), link)

    private fun config(spec: OutboundSpec): String = CoreConfig.build(spec, CoreFixtures.login)

    /** The line's outbound of the config the core is started from for [link]. */
    private fun outbound(link: String): JsonObject = outboundOf(config(spec(link)))

    private fun outboundOf(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("outbounds").jsonArray.map { it.jsonObject }
            .single { it["tag"]?.jsonPrimitive?.content == "out" }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.content
    private fun JsonObject.stream(): JsonObject = obj("streamSettings")
    private fun JsonObject.tls(): JsonObject = stream().obj("tlsSettings")
    private fun JsonObject.server(): JsonObject = obj("settings").getValue("servers").jsonArray.single().jsonObject

    /** A spelling of a Trojan link's transport, the shape it is, and what the core is given for it. */
    private class Transport(val query: String, val shape: String, val network: String, val settings: String?)

    private val tls = "security=tls&sni=trojan.example.com"

    /** Every transport the link parser reads for a Trojan link (`type`), in each spelling it takes. */
    private val transports = listOf(
        Transport(tls, "trojan", "raw", null),
        Transport("$tls&type=tcp", "trojan", "raw", null),
        Transport("$tls&type=raw", "trojan", "raw", null),
        Transport("$tls&type=ws&path=%2Ftr&host=cdn.example.com", "trojan-ws", "ws", """{"path":"/tr","host":"cdn.example.com"}"""),
        Transport("$tls&type=ws", "trojan-ws", "ws", """{"path":"/"}"""),
        Transport("$tls&type=grpc&serviceName=tr.grpc", "trojan-grpc", "grpc", """{"serviceName":"tr.grpc"}"""),
        Transport("$tls&type=grpc&servicename=tr.grpc", "trojan-grpc", "grpc", """{"serviceName":"tr.grpc"}"""),
        Transport(
            "$tls&type=httpupgrade&path=%2Fup&host=cdn.example.com", "trojan-httpupgrade", "httpupgrade",
            """{"path":"/up","host":"cdn.example.com"}""",
        ),
    )

    private val settingsKey = mapOf("ws" to "wsSettings", "grpc" to "grpcSettings", "httpupgrade" to "httpupgradeSettings")

    @Test fun everyTransportTheParserGivesTrojanIsALineOfItsShape() {
        for (t in transports) {
            val link = link(t.query)
            val spec = spec(link)
            val kind = assertNotNull(CoreLines.of(spec), "$link: no kind of line takes it")
            assertEquals(t.shape, kind.shape(spec), link)
            assertTrue(t.shape in kind.shapes, link)
            assertNull(CoreRefusal.of(spec), link)

            val json = config(spec)
            assertEquals(emptyList<String>(), XrayRustSurface.violations(json), link)
            val out = outboundOf(json)
            assertEquals("trojan", out.str("protocol"), link)
            assertEquals(
                """{"address":"127.0.0.1","port":443,"password":"$password"}""",
                out.server().toString(),
                link,
            )
            val stream = out.stream()
            assertEquals(t.network, stream.str("network"), link)
            assertEquals("tls", stream.str("security"), link)
            assertEquals("trojan.example.com", out.tls().str("serverName"), link)
            assertNull(out.tls()["allowInsecure"], "$link: nothing is waived that the link did not waive")
            val key = settingsKey[t.network]
            if (t.settings == null) {
                assertTrue(settingsKey.values.none { it in stream }, link)
            } else {
                assertEquals(t.settings, stream.obj(requireNotNull(key)).toString(), link)
            }
        }
    }

    @Test fun aBareLinkIsTlsToItsOwnAddressWithTheCoresHello() {
        val out = outbound("trojan://$password@127.0.0.1:443#bare")
        assertEquals("raw", out.stream().str("network"))
        assertEquals("tls", out.stream().str("security"))
        assertEquals("""{"serverName":"127.0.0.1","fingerprint":"chrome"}""", out.tls().toString())
    }

    @Test fun aTrojanLinkIsTlsWhateverItsSecuritySays() {
        // The parser reads Trojan as TLS by definition; the core refuses Trojan without it.
        for (security in listOf("none", "tls", "")) {
            val out = outbound(link("security=$security&sni=trojan.example.com"))
            assertEquals("tls", out.stream().str("security"), security)
        }
    }

    @Test fun theLinksHelloAndAlpnAreHandedOn() {
        assertEquals("firefox", outbound(link("$tls&fp=firefox")).tls().str("fingerprint"))
        assertEquals("android", outbound(link("$tls&fp=android")).tls().str("fingerprint"))
        assertEquals("chrome", outbound(link("$tls&fp=hellochrome_133")).tls().str("fingerprint"))
        assertEquals("chrome", outbound(link(tls)).tls().str("fingerprint"))
        assertEquals("""["h2","http/1.1"]""", outbound(link("$tls&alpn=h2%2Chttp%2F1.1")).tls().getValue("alpn").toString())
        assertEquals("""["http/1.1"]""", outbound(link("$tls&type=ws&alpn=http%2F1.1")).tls().getValue("alpn").toString())
        assertNull(outbound(link(tls)).tls()["alpn"])
        // `peer` is the older spelling of the server name.
        assertEquals("peer.example.com", outbound(link("security=tls&peer=peer.example.com")).tls().str("serverName"))
    }

    @Test fun aPercentEncodedPasswordIsWrittenAsTheServerKnowsIt() {
        assertEquals("p@ss word", outbound(link(tls, pass = "p%40ss%20word")).server().str("password"))
    }

    @Test fun aLinkThatWaivesChecksIsCarriedWithTheFlag() {
        for (flag in listOf("allowInsecure=1", "allowInsecure=true", "insecure=1", "insecure=true")) {
            for (t in transports) {
                val link = link("${t.query}&$flag")
                val spec = spec(link)
                assertNull(CoreRefusal.of(spec), link)
                val json = config(spec)
                assertEquals(emptyList<String>(), XrayRustSurface.violations(json), link)
                assertEquals(JsonPrimitive(true), outboundOf(json).tls()["allowInsecure"], link)
            }
        }
    }

    @Test fun aLinkThatWaivesNothingIsWrittenWithoutTheFlag() {
        // Not written as false either: the core verifies when the key is absent.
        for (flag in listOf("", "&allowInsecure=0", "&allowInsecure=false", "&insecure=0")) {
            val link = link("$tls$flag")
            assertNull(outbound(link).tls()["allowInsecure"], link)
            assertTrue("allowInsecure" !in config(spec(link)), link)
        }
    }

    @Test fun httpTwoIsRefusedWithASentence() {
        for (type in listOf("http", "h2")) {
            val spec = spec(link("$tls&type=$type&path=%2Fh2&host=cdn.example.com"))
            assertNotNull(CoreLines.of(spec), "type=$type is a Trojan link's shape, refused by its kind")
            val refusal = assertNotNull(CoreRefusal.of(spec), type)
            assertTrue("HTTP/2" in refusal, refusal)
            assertFailsWith<IllegalArgumentException>(type) { config(spec) }
        }
    }

    @Test fun aPasswordTheCoreDoesNotSendIsRefused() {
        // The core sends 1 to 4096 bytes of UTF-8.
        assertNull(CoreRefusal.of(spec(link(tls, pass = "a".repeat(4096)))))
        assertNotNull(CoreRefusal.of(spec(link(tls, pass = "a".repeat(4097)))))
        // Two bytes a letter: 2049 of them are 4098 bytes.
        assertNotNull(CoreRefusal.of(spec(link(tls, pass = "%C3%A9".repeat(2049)))))
        assertNull(CoreRefusal.of(spec(link(tls, pass = "%C3%A9".repeat(2048)))))
    }

    @Test fun aPortTheCoreCannotDialIsRefused() {
        assertNotNull(CoreRefusal.of(spec(link(tls, port = 0))))
        assertNotNull(CoreRefusal.of(spec(link(tls, port = 65536))))
        assertNull(CoreRefusal.of(spec(link(tls, port = 65535))))
    }

    @Test fun xraysManInTheMiddleNamesAreRefused() {
        // `fromMitm` asks for a context only Xray's MITM inbound has; the core refuses it.
        assertNotNull(CoreRefusal.of(spec(link("security=tls&sni=fromMitm"))))
        assertNotNull(CoreRefusal.of(spec(link("$tls&alpn=h2%2CfromMitm"))))
    }

    @Test fun aServerNamedByAHostnameIsPinnedAndItsNameKept() {
        val named = spec(link("security=tls&type=ws&path=%2Ftr", host = "trojan.example.com"))
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(named))
        val pinned = CoreLines.pinHost(named, "192.0.2.7")
        assertNull(CoreRefusal.of(pinned))
        val out = outboundOf(config(pinned))
        assertEquals("192.0.2.7", out.server().str("address"))
        // The name stays the TLS server name and the Host, where the link left them to be the server's own.
        assertEquals("trojan.example.com", out.tls().str("serverName"))
        assertEquals("trojan.example.com", out.stream().obj("wsSettings").str("host"))

        val explicit = spec(link("security=tls&sni=sni.example.com&type=httpupgrade&host=cdn.example.com", host = "trojan.example.com"))
        val out2 = outboundOf(config(CoreLines.pinHost(explicit, "192.0.2.7")))
        assertEquals("sni.example.com", out2.tls().str("serverName"))
        assertEquals("cdn.example.com", out2.stream().obj("httpupgradeSettings").str("host"))
    }
}
