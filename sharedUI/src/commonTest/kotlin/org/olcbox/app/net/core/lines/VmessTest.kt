package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TlsSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VMess on the Rust core: AEAD only, over raw TCP, WebSocket, gRPC and HTTP
 * upgrade, with TLS and without, as the app's link reader gives the links;
 * what the core does not speak is refused with a sentence before any tunnel.
 */
class VmessTest {
    private fun spec(vararg fields: Pair<String, String>): OutboundSpec.Vmess =
        LinkParser.parse(VmessFixture.link(*fields)) as OutboundSpec.Vmess

    private val raw get() = spec("scy" to "auto", "net" to "tcp")

    private fun outbound(spec: OutboundSpec): JsonObject {
        val config = Json.parseToJsonElement(CoreConfig.build(spec, CoreFixtures.login)).jsonObject
        return config.getValue("outbounds").jsonArray.first().jsonObject
    }

    private fun stream(spec: OutboundSpec): JsonObject = outbound(spec).getValue("streamSettings").jsonObject

    private fun user(spec: OutboundSpec): JsonObject = outbound(spec).getValue("settings").jsonObject.getValue("vnext").jsonArray
        .single().jsonObject.getValue("users").jsonArray.single().jsonObject

    @Test fun everyFixtureLinkIsTheKindsAndTheCoreTakesIt() {
        assertEquals(Vmess.shapes.toSet(), VmessFixture.cases.map { it.name }.toSet())
        for (case in VmessFixture.cases) {
            val spec = case.spec()
            assertTrue(spec is OutboundSpec.Vmess, case.name)
            assertEquals(Vmess, CoreLines.of(spec), case.name)
            assertEquals(case.name, Vmess.shape(spec), case.name)
            assertNull(CoreRefusal.of(spec), case.name)
            assertEquals(emptyList<String>(), XrayRustSurface.violations(CoreConfig.build(spec, CoreFixtures.login)), case.name)
        }
    }

    @Test fun theShapeIsTheTransportAndWhetherThereIsTls() {
        val tls = arrayOf("tls" to "tls", "sni" to VmessFixture.NAME)
        assertEquals(Vmess.RAW, Vmess.shape(spec("net" to "tcp")))
        assertEquals(Vmess.RAW, Vmess.shape(spec()))
        assertEquals(Vmess.WS, Vmess.shape(spec("net" to "ws")))
        assertEquals(Vmess.GRPC, Vmess.shape(spec("net" to "grpc")))
        assertEquals(Vmess.HTTP_UPGRADE, Vmess.shape(spec("net" to "httpupgrade")))
        assertEquals(Vmess.RAW_TLS, Vmess.shape(spec("net" to "tcp", *tls)))
        assertEquals(Vmess.WS_TLS, Vmess.shape(spec("net" to "ws", *tls)))
        assertEquals(Vmess.GRPC_TLS, Vmess.shape(spec("net" to "grpc", *tls)))
        assertEquals(Vmess.HTTP_UPGRADE_TLS, Vmess.shape(spec("net" to "httpupgrade", *tls)))
        assertNull(Vmess.shape(requireNotNull(LinkParser.parse("trojan://pw@127.0.0.1:443#t"))))
    }

    @Test fun anAlterIdAboveZeroIsRefusedWithASentence() {
        for (aid in listOf("1", "2", "64")) {
            val legacy = spec("aid" to aid, "net" to "tcp")
            assertEquals(Vmess.LEGACY, CoreRefusal.of(legacy), aid)
            assertTrue("alterId" in Vmess.LEGACY && "AEAD" in Vmess.LEGACY)
        }
        assertNull(CoreRefusal.of(spec("aid" to "0", "net" to "tcp")))
        // No alterId is written, not even zero: the core reads AEAD from its absence.
        assertNull(user(raw)["alterId"])
    }

    @Test fun aBodyCipherTheCoreDoesNotSpeakIsRefused() {
        for (scy in listOf("none", "zero", "aes-128-cfb", "aes-128-ctr", "chacha20-ietf-poly1305", "aes-256-gcm")) {
            assertEquals(Vmess.CIPHER, CoreRefusal.of(spec("scy" to scy, "net" to "tcp")), scy)
        }
        for ((scy, written) in listOf("auto" to "auto", "" to "auto", "AES-128-GCM" to "aes-128-gcm", " chacha20-poly1305 " to "chacha20-poly1305")) {
            val spec = spec("scy" to scy, "net" to "tcp")
            assertNull(CoreRefusal.of(spec), scy)
            assertEquals(JsonPrimitive(written), user(spec)["security"], scy)
        }
    }

    @Test fun anIdTheCoreCannotReadIsRefused() {
        val uuid = VmessFixture.ID
        for (id in listOf(uuid, "27848739-7E62-4138-9fd3-098A63964B6B", uuid.replace("-", ""), "-" + uuid.replace("-", ""), uuid.replace("-", "") + "----", "a", "x".repeat(30))) {
            assertTrue(Vmess.readsId(id), id)
            assertNull(CoreRefusal.of(spec("id" to id, "net" to "tcp")), id)
        }
        for (id in listOf("x".repeat(31), "x".repeat(32), uuid.replace('4', 'g'), "$uuid-$uuid", "${uuid}x")) {
            assertFalse(Vmess.readsId(id), id)
            assertEquals(Vmess.ID, CoreRefusal.of(spec("id" to id, "net" to "tcp")), id)
        }
    }

    @Test fun aPortThatIsNoneIsRefused() {
        assertEquals(Vmess.PORT, CoreRefusal.of(raw.copy(port = 0)))
        assertEquals(Vmess.PORT, CoreRefusal.of(raw.copy(port = 65536)))
        assertNull(CoreRefusal.of(raw.copy(port = 65535)))
    }

    @Test fun vmessOverHttp2IsNoShapeOfTheKindAndIsRefusedWithASentence() {
        for (net in listOf("h2", "http")) {
            val http2 = spec("net" to net, "tls" to "tls", "sni" to VmessFixture.NAME)
            assertTrue(http2.transport is TransportSpec.Http, net)
            assertNull(CoreLines.of(http2), net)
            assertEquals(CoreRefusal.noLine(http2), CoreRefusal.of(http2), net)
        }
    }

    @Test fun withNoTlsTheStreamIsTheTransportAlone() {
        assertEquals("""{"network":"raw"}""", stream(raw).toString())
        assertEquals(
            """{"network":"ws","wsSettings":{"path":"/vm","host":"vm.example.com"}}""",
            stream(spec("net" to "ws", "host" to VmessFixture.NAME, "path" to "/vm")).toString(),
        )
        assertEquals("""{"network":"grpc","grpcSettings":{"serviceName":"svc"}}""", stream(spec("net" to "grpc", "path" to "svc")).toString())
    }

    @Test fun tlsIsTheLinksNameHelloAndAlpn() {
        val ws = spec("net" to "ws", "path" to "/vm", "tls" to "tls", "sni" to "s.example", "fp" to "firefox", "alpn" to "h2,http/1.1")
        assertEquals(
            """{"network":"ws","wsSettings":{"path":"/vm"},"security":"tls","tlsSettings":{"serverName":"s.example","fingerprint":"firefox","alpn":["h2","http/1.1"]}}""",
            stream(ws).toString(),
        )
        // No fp in the link is the core's own default, chrome, written out; a sing-box variant is read as chrome too.
        val bare = spec("net" to "tcp", "tls" to "tls", "sni" to "s.example")
        assertEquals("""{"serverName":"s.example","fingerprint":"chrome"}""", stream(bare).getValue("tlsSettings").toString())
        val variant = spec("net" to "tcp", "tls" to "tls", "sni" to "s.example", "fp" to "chrome_psk")
        assertEquals(JsonPrimitive("chrome"), stream(variant).getValue("tlsSettings").jsonObject["fingerprint"])
        // With no sni, the Host names the server, and with neither the server's own address does.
        assertEquals(JsonPrimitive("h.example"), stream(spec("net" to "ws", "host" to "h.example", "tls" to "tls")).getValue("tlsSettings").jsonObject["serverName"])
        assertEquals(JsonPrimitive("127.0.0.1"), stream(spec("net" to "tcp", "tls" to "tls")).getValue("tlsSettings").jsonObject["serverName"])
    }

    /**
     * The app's VMess link reader never reads a waiver of the checks: a
     * VMess link's TLS is always verified today, and no such link reaches
     * the kind. Should the reader learn one, K5 needs its insecure line.
     */
    @Test fun theAppsVmessLinksWaiveNoCheck() {
        for (key in listOf("allowInsecure", "insecure", "skip-cert-verify")) {
            val spec = spec("net" to "tcp", "tls" to "tls", "sni" to "s.example", key to "1")
            assertEquals(false, assertNotNull(spec.tls).insecure, key)
            assertNull(stream(spec).getValue("tlsSettings").jsonObject["allowInsecure"], key)
        }
    }

    /** A spec that does waive the checks is carried with the flag; the same spec without it is written without. */
    @Test fun aSpecThatWaivesTheChecksIsCarriedWithTheFlag() {
        val verified = spec("net" to "ws", "path" to "/vm", "tls" to "tls", "sni" to "s.example")
        val waived = verified.copy(tls = assertNotNull(verified.tls).copy(insecure = true))
        assertNull(CoreRefusal.of(waived))
        assertEquals(JsonPrimitive(true), stream(waived).getValue("tlsSettings").jsonObject["allowInsecure"])
        assertEquals(emptyList<String>(), XrayRustSurface.violations(CoreConfig.build(waived, CoreFixtures.login)))
        assertNull(stream(verified).getValue("tlsSettings").jsonObject["allowInsecure"])
        assertEquals(
            stream(verified).getValue("tlsSettings").jsonObject.keys + "allowInsecure",
            stream(waived).getValue("tlsSettings").jsonObject.keys,
        )
    }

    @Test fun aHostnameIsPinnedToTheAddressTheCallerResolvedAndKeptOnTheWire() {
        val named = spec("add" to "vm.example.com", "net" to "ws", "path" to "/vm", "tls" to "tls")
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(named))
        val pinned = CoreLines.pinHost(named, "192.0.2.7") as OutboundSpec.Vmess
        assertEquals("192.0.2.7", pinned.host)
        assertEquals(TlsSpec("vm.example.com"), pinned.tls)
        assertEquals(TransportSpec.Ws("/vm", "vm.example.com"), pinned.transport)
        assertNull(CoreRefusal.of(pinned))
        val up = CoreLines.pinHost(spec("add" to "vm.example.com", "net" to "httpupgrade", "host" to "front.example"), "192.0.2.7") as OutboundSpec.Vmess
        assertEquals(TransportSpec.HttpUpgrade("/", "front.example"), up.transport)
        assertNull(up.tls)
    }
}
