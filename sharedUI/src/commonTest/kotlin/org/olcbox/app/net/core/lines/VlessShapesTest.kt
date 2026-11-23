package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * VLESS with no Reality key ([VlessShapes]): the shape each link is read as,
 * the outbound written for it, a waiver of the certificate check written
 * only where the link asks for one, and what is refused, in its own words.
 */
class VlessShapesTest {
    private val tlsId = "44444444-4444-4444-4444-444444444444"
    private val noneId = "55555555-5555-5555-5555-555555555555"
    private val tls = "vless://$tlsId@127.0.0.1:443?security=tls&encryption=none&sni=vless.example.com&fp=chrome"
    private val none = "vless://$noneId@127.0.0.1:80?security=none&encryption=none"
    private val reality = "vless://$tlsId@127.0.0.1:443?security=reality&encryption=none&pbk=${CoreFixtures.PUBLIC_KEY}" +
        "&sid=ab12&fp=chrome&sni=www.example.com"

    private val xhttpTail = "\"scMaxEachPostBytes\":${XrayConfig.XHTTP_MAX_EACH_POST_BYTES}," +
        "\"h2StreamReceiveWindow\":${CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW}"
    private val tlsSettings = """"security":"tls","tlsSettings":{"serverName":"vless.example.com","fingerprint":"chrome"}"""
    private val waivedSettings =
        """"security":"tls","tlsSettings":{"serverName":"vless.example.com","allowInsecure":true,"fingerprint":"chrome"}"""

    private fun spec(link: String): OutboundSpec.Vless =
        requireNotNull(LinkParser.parse(link) as? OutboundSpec.Vless) { "no VLESS link: $link" }

    /** The line's outbound as the whole config is written for [link]. */
    private fun outbound(link: String): JsonObject =
        Json.parseToJsonElement(CoreConfig.build(spec(link), CoreFixtures.login)).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject

    private fun stream(link: String): String = outbound(link).getValue("streamSettings").toString()

    @Test fun eachLinkIsReadAsItsShape() {
        val shapes = mapOf(
            "$tls&type=tcp" to VlessShapes.TLS,
            "$tls&type=raw" to VlessShapes.TLS,
            "$tls&flow=xtls-rprx-vision" to VlessShapes.TLS,
            "$tls&type=grpc&serviceName=g" to VlessShapes.TLS_GRPC,
            "$tls&type=xhttp&path=%2Fx" to VlessShapes.TLS_XHTTP,
            "$none&type=tcp" to VlessShapes.NONE,
            "$none&type=ws&path=%2Fp" to VlessShapes.NONE_WS,
            "$none&type=grpc&serviceName=g" to VlessShapes.NONE_GRPC,
            "$none&type=httpupgrade&path=%2Fu" to VlessShapes.NONE_HTTP_UPGRADE,
            "$none&type=xhttp&path=%2Fx" to VlessShapes.NONE_XHTTP,
            // A link that names no security and carries no Reality key is ordinary TLS, as the Go builders read it.
            "vless://$tlsId@127.0.0.1:443?sni=vless.example.com&type=tcp" to VlessShapes.TLS,
        )
        for ((link, shape) in shapes) {
            val spec = spec(link)
            assertSame(VlessShapes, CoreLines.of(spec), link)
            assertEquals(shape, VlessShapes.shape(spec), link)
        }
        assertEquals(VlessShapes.shapes.toSet(), shapes.values.toSet())
    }

    @Test fun ordinaryTlsOnRawTcp() {
        assertEquals(
            """{"tag":"out","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":443,"users":""" +
                """[{"id":"$tlsId","encryption":"none"}]}]},"streamSettings":{"network":"raw",$tlsSettings}}""",
            outbound("$tls&type=tcp#t").toString(),
        )
        assertNull(CoreRefusal.of(spec("$tls&type=tcp#t")))
    }

    @Test fun visionOverOrdinaryTlsIsTheUdp443Variant() {
        val users = outbound("$tls&flow=xtls-rprx-vision&type=tcp#t").getValue("settings").jsonObject
            .getValue("vnext").jsonArray.single().jsonObject.getValue("users").toString()
        assertEquals("""[{"id":"$tlsId","encryption":"none","flow":"xtls-rprx-vision-udp443"}]""", users)
        assertEquals("""{"network":"raw",$tlsSettings}""", stream("$tls&flow=xtls-rprx-vision&type=tcp#t"))
    }

    @Test fun ordinaryTlsOverGrpcAndXhttp() {
        assertEquals(
            """{"network":"grpc","grpcSettings":{"serviceName":"media/sync"},$tlsSettings}""",
            stream("$tls&type=grpc&serviceName=media%2Fsync#t"),
        )
        // No host and no mode in the link: the server name is the Host and the mode is auto.
        assertEquals(
            """{"network":"xhttp","xhttpSettings":{"path":"/x","host":"vless.example.com","mode":"auto",$xhttpTail},$tlsSettings}""",
            stream("$tls&type=xhttp&path=%2Fx#t"),
        )
        // No ALPN of the link's: the core offers h2 for both itself.
        assertEquals(emptyList<String>(), spec("$tls&type=grpc&serviceName=g&alpn=http%2F1.1").alpn)
    }

    @Test fun noTlsOnEveryTransport() {
        val streams = mapOf(
            "$none&type=tcp" to """{"network":"raw","security":"none"}""",
            "$none&type=ws&path=%2Fp&host=cdn.example.com" to
                """{"network":"ws","wsSettings":{"path":"/p","host":"cdn.example.com"},"security":"none"}""",
            "$none&type=grpc&serviceName=media%2Fsync" to
                """{"network":"grpc","grpcSettings":{"serviceName":"media/sync"},"security":"none"}""",
            "$none&type=httpupgrade&path=%2Fu&host=cdn.example.com" to
                """{"network":"httpupgrade","httpupgradeSettings":{"path":"/u","host":"cdn.example.com"},"security":"none"}""",
            "$none&type=xhttp&path=%2Fx&host=cdn.example.com&mode=packet-up" to
                """{"network":"xhttp","xhttpSettings":{"path":"/x","host":"cdn.example.com","mode":"packet-up",$xhttpTail},"security":"none"}""",
        )
        for ((link, expected) in streams) {
            assertNull(CoreRefusal.of(spec(link)), link)
            assertEquals(expected, stream(link), link)
            // What the link says of TLS beside security=none is not written: there is no TLS to say it of.
            val said = "$link&allowInsecure=1&fp=firefox&alpn=h2%2Chttp%2F1.1"
            assertNull(CoreRefusal.of(spec(said)), said)
            assertEquals(expected, stream(said), said)
        }
    }

    @Test fun aWaiverIsWrittenOnlyWhereTheLinkAsksForIt() {
        val transports = mapOf(
            "&type=tcp" to """{"network":"raw",""",
            "&type=grpc&serviceName=media%2Fsync" to """{"network":"grpc","grpcSettings":{"serviceName":"media/sync"},""",
            "&type=xhttp&path=%2Fx" to """{"network":"xhttp","xhttpSettings":{"path":"/x","host":"vless.example.com","mode":"auto",$xhttpTail},""",
        )
        for ((transport, head) in transports) {
            // Every spelling the app reads asks for it, and the line is carried, not refused.
            for (flag in listOf("allowInsecure=1", "allowInsecure=true", "insecure=1", "insecure=true")) {
                val link = "$tls&$flag$transport#t"
                assertNull(CoreRefusal.of(spec(link)), link)
                assertEquals("$head$waivedSettings}", stream(link), link)
            }
            // The same link without the flag, or with it off, verifies: no allowInsecure at all, not even false.
            for (link in listOf("$tls$transport#t", "$tls&allowInsecure=0$transport#t", "$tls&insecure=false$transport#t")) {
                assertEquals("$head$tlsSettings}", stream(link), link)
                assertTrue("allowInsecure" !in CoreConfig.build(spec(link), CoreFixtures.login), link)
            }
        }
    }

    @Test fun tlsOverWebSocketAndHttpUpgradeStaysWebTunnels() {
        for (link in listOf("$tls&type=ws&path=%2Fv", "$tls&type=httpupgrade&path=%2Fu")) {
            assertSame(WebTunnel, CoreLines.of(spec(link)), link)
            assertNull(VlessShapes.shape(spec(link)), link)
            // WebTunnel's rule for a waiver, not this kind's.
            assertSame(WebTunnel, CoreLines.of(spec("$link&allowInsecure=1")), link)
            assertEquals(WebTunnel.refusal(spec("$link&allowInsecure=1")), CoreRefusal.of(spec("$link&allowInsecure=1")), link)
        }
        for (link in listOf("$reality&type=tcp", "$reality&type=grpc&serviceName=g", "$reality&type=xhttp&path=%2Fx")) {
            assertSame(Reality, CoreLines.of(spec(link)), link)
            assertNull(VlessShapes.shape(spec(link)), link)
        }
    }

    @Test fun http2IsRefusedWithASentence() {
        val links = listOf(
            "$tls&type=http&path=%2Fh&host=a.example.com,b.example.com",
            "$tls&type=h2&path=%2Fh",
            "$reality&type=http&path=%2Fh",
            "$none&type=http&path=%2Fh",
        )
        for (link in links) {
            val spec = spec(link)
            assertSame(VlessShapes, CoreLines.of(spec), link)
            assertEquals(VlessShapes.HTTP2, VlessShapes.shape(spec), link)
            assertEquals(VlessShapes.HTTP2_REFUSAL, CoreRefusal.of(spec), link)
            val thrown = assertFailsWith<IllegalArgumentException>(link) { CoreConfig.build(spec, CoreFixtures.login) }
            assertTrue(VlessShapes.HTTP2_REFUSAL in thrown.message.orEmpty(), link)
        }
        // A shape this kind names but never writes.
        assertTrue(VlessShapes.HTTP2 !in VlessShapes.shapes)
    }

    @Test fun visionNeedsTlsAndNoOtherFlowIsKnown() {
        val plainVision = spec("$none&flow=xtls-rprx-vision&type=tcp")
        assertEquals(VlessShapes.NONE, VlessShapes.shape(plainVision))
        assertEquals(VlessShapes.VISION_NEEDS_TLS, CoreRefusal.of(plainVision))
        assertFailsWith<IllegalArgumentException> { CoreConfig.build(plainVision, CoreFixtures.login) }
        assertEquals("the core knows no flow but Vision", CoreRefusal.of(spec("$tls&flow=xtls-rprx-direct&type=tcp")))
        assertNull(CoreRefusal.of(spec("$tls&flow=xtls-rprx-vision-udp443&type=tcp")))
    }

    @Test fun aServerNamedByAHostnameKeepsItsNameOnTheWire() {
        val named = spec("vless://$tlsId@vless.example.net:443?security=tls&encryption=none&type=tcp#h")
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(named))
        val pinned = CoreLines.pinHost(named, "192.0.2.7")
        assertNull(CoreRefusal.of(pinned))
        val out = Json.parseToJsonElement(CoreConfig.build(pinned, CoreFixtures.login)).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject.toString()
        assertTrue(""""address":"192.0.2.7"""" in out, out)
        assertTrue(""""tlsSettings":{"serverName":"vless.example.net","fingerprint":"chrome"}""" in out, out)

        val plainWs = spec("vless://$noneId@cdn.example.net:80?security=none&type=ws&path=%2Fp#h")
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(plainWs))
        for (address in listOf("10.0.0.8", "fd00::8")) {
            val plainPinned = CoreLines.pinHost(plainWs, address)
            assertNull(CoreRefusal.of(plainPinned), address)
            assertTrue(
                """"wsSettings":{"path":"/p","host":"cdn.example.net"},"security":"none"""" in
                    Json.parseToJsonElement(CoreConfig.build(plainPinned, CoreFixtures.login)).jsonObject.toString(),
                address,
            )
        }
        // The same name resolved to a public address: no TLS there is the core's refusal, said before any tunnel.
        for (address in listOf("192.0.2.8", "203.0.113.5", "2001:db8::8")) {
            assertEquals(VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER, CoreRefusal.of(CoreLines.pinHost(plainWs, address)), address)
        }
    }

    /**
     * Addresses the core sends VLESS in the clear to: private, loopback and
     * link-local ones (Go's IsPrivate, IsLoopback and IsLinkLocalUnicast,
     * an IPv4-mapped address read as IPv4), the fork's own table.
     */
    private val privateServers = listOf(
        "10.42.0.1", "127.0.0.1", "127.255.255.254", "169.254.1.1", "172.16.0.1", "172.31.255.254", "192.168.1.1",
        "[::1]", "[fc00::1]", "[fdff::1]", "[FD00::1]", "[fe80::1]", "[febf::1]", "[::ffff:10.0.0.1]", "[::ffff:127.0.0.1]",
    )

    /**
     * Addresses it refuses VLESS in the clear to: public ones, and the shared,
     * reserved, documentation, multicast and unspecified ranges with them.
     */
    private val publicServers = listOf(
        "0.0.0.0", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
        "255.255.255.255", "1.1.1.1", "11.0.0.1", "169.253.255.255", "172.15.255.255", "172.32.0.1", "192.169.0.1",
        "[::]", "[::2]", "[ff02::1]", "[::ffff:192.0.2.1]", "[::10.0.0.1]", "[2001:db8::1]", "[fe7f::1]", "[fec0::1]",
        // The core reads no address with a leading zero, so this is a name to it, and not one it exempts.
        "010.0.0.1",
    )

    @Test fun noTlsIsCarriedOnlyToAPrivateServer() {
        val transports =
            listOf("&type=tcp", "&type=ws&path=%2Fp", "&type=grpc&serviceName=g", "&type=httpupgrade&path=%2Fu", "&type=xhttp&path=%2Fx")
        for (transport in transports) {
            for (server in privateServers) {
                val link = "vless://$noneId@$server:80?security=none&encryption=none$transport#p"
                assertNull(CoreRefusal.of(spec(link)), link)
                assertTrue(""""address":"$server"""" in outbound(link).toString(), link)
            }
            for (server in publicServers) {
                val link = "vless://$noneId@$server:80?security=none&encryption=none$transport#p"
                assertEquals(VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER, CoreRefusal.of(spec(link)), link)
                val thrown = assertFailsWith<IllegalArgumentException>(link) { CoreConfig.build(spec(link), CoreFixtures.login) }
                assertTrue(VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER in thrown.message.orEmpty(), link)
            }
        }
        // The rule is for no TLS alone: over ordinary TLS a public server is carried.
        for (server in listOf("192.0.2.8", "203.0.113.5", "1.1.1.1", "[2001:db8::1]")) {
            for (transport in listOf("&type=tcp", "&type=grpc&serviceName=g", "&type=xhttp&path=%2Fx")) {
                assertNull(CoreRefusal.of(spec("vless://$tlsId@$server:443?security=tls&sni=vless.example.com$transport#t")), server)
            }
        }
    }

    @Test fun theHelloIsTheLinksAsTheCoreNamesIt() {
        // Ordinary TLS keeps the hellos REALITY has no key share for.
        for ((fp, hello) in listOf("firefox" to "firefox", "android" to "android", "360" to "360", "hellochrome_133" to "chrome")) {
            val written = stream("vless://$tlsId@127.0.0.1:443?security=tls&sni=vless.example.com&fp=$fp&type=tcp#t")
            assertEquals("""{"network":"raw","security":"tls","tlsSettings":{"serverName":"vless.example.com","fingerprint":"$hello"}}""", written, fp)
        }
        // No fp at all is chrome, as the parser reads it.
        assertEquals(
            """{"network":"raw","security":"tls","tlsSettings":{"serverName":"vless.example.com","fingerprint":"chrome"}}""",
            stream("vless://$tlsId@127.0.0.1:443?security=tls&sni=vless.example.com&type=tcp#t"),
        )
    }
}
