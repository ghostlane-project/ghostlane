package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * VLESS over HTTP/2 ([VlessHttp2]): the shape each link is read as, the
 * outbound written for it (`network: "http"`, `httpSettings`, TLS or
 * Reality), and what is refused, in its own words.
 */
class VlessHttp2Test {
    private val id = "44444444-4444-4444-4444-444444444444"
    private val tls = "vless://$id@127.0.0.1:443?security=tls&encryption=none&sni=vless.example.com&fp=chrome"
    private val none = "vless://$id@127.0.0.1:80?security=none&encryption=none"
    private val reality = "vless://$id@127.0.0.1:443?security=reality&encryption=none&pbk=${CoreFixtures.PUBLIC_KEY}" +
        "&sid=ab12&fp=chrome&sni=www.example.com"

    /** What the app wrote for this transport before the line existed, and refused with. */
    private val oldRefusal =
        "the link asks for HTTP/2 (type=http), a transport Xray removed and the core does not carry (XHTTP took its place)"

    private fun spec(link: String): OutboundSpec.Vless =
        requireNotNull(LinkParser.parse(link) as? OutboundSpec.Vless) { "no VLESS link: $link" }

    private fun outbound(link: String): JsonObject =
        Json.parseToJsonElement(CoreConfig.build(spec(link), CoreFixtures.login)).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject

    private fun stream(link: String): String = outbound(link).getValue("streamSettings").toString()

    @Test fun eachLinkIsReadAsItsShape() {
        val shapes = mapOf(
            "$tls&type=http&path=%2Fh" to VlessHttp2.TLS,
            "$tls&type=h2&path=%2Fh" to VlessHttp2.TLS,
            "$reality&type=http&path=%2Fh" to VlessHttp2.REALITY,
            "$none&type=http&path=%2Fh" to VlessHttp2.NONE,
        )
        for ((link, shape) in shapes) {
            val spec = spec(link)
            assertSame(VlessHttp2, CoreLines.of(spec), link)
            assertEquals(shape, VlessHttp2.shape(spec), link)
        }
        assertEquals(listOf(VlessHttp2.TLS, VlessHttp2.REALITY), VlessHttp2.shapes)
    }

    @Test fun overTlsWithItsHostsAndPath() {
        val link = "$tls&type=http&path=%2Fh2&host=a.example.com,b.example.com#t"
        assertEquals(
            """{"network":"http","httpSettings":{"host":["a.example.com","b.example.com"],"path":"/h2"},""" +
                """"security":"tls","tlsSettings":{"serverName":"vless.example.com","fingerprint":"chrome"}}""",
            stream(link),
        )
        assertNull(CoreRefusal.of(spec(link)))
        assertEquals("""{"network":"http","httpSettings":{"path":"/"},"security":"tls","tlsSettings":{"serverName":"vless.example.com","fingerprint":"chrome"}}""",
            stream("$tls&type=h2#t"))
    }

    @Test fun overRealityWithNoHost() {
        val link = "$reality&type=http&path=%2Fh2#r"
        assertEquals(
            """{"network":"http","httpSettings":{"path":"/h2"},"security":"reality","realitySettings":""" +
                """{"serverName":"www.example.com","fingerprint":"chrome","publicKey":"${CoreFixtures.PUBLIC_KEY}","shortId":"ab12"}}""",
            stream(link),
        )
        assertNull(CoreRefusal.of(spec(link)))
    }

    @Test fun theCertificateCheckIsWaivedOnlyWhenTheLinkSaysSo() {
        val link = "$tls&type=http&path=%2Fh2"
        assertTrue("allowInsecure" !in stream("$link#t"))
        assertEquals(
            """{"serverName":"vless.example.com","allowInsecure":true,"fingerprint":"chrome"}""",
            outbound("$link&allowInsecure=1#t").getValue("streamSettings").jsonObject.getValue("tlsSettings").toString(),
        )
        assertNull(CoreRefusal.of(spec("$link&allowInsecure=1#t")))
    }

    @Test fun theConfigIsWithinTheCoresFields() {
        for (link in listOf("$tls&type=http&path=%2Fh&host=a.example.com", "$reality&type=http&path=%2Fh")) {
            val config = Json.parseToJsonElement(CoreConfig.build(spec(link), CoreFixtures.login)).jsonObject
            assertEquals(emptyList<String>(), XrayRustSurface.violations(config), link)
        }
    }

    @Test fun theOldRefusalIsGone() {
        for (link in listOf("$tls&type=http&path=%2Fh", "$tls&type=h2&path=%2Fh", "$reality&type=http&path=%2Fh", "$none&type=http&path=%2Fh")) {
            val spec = spec(link)
            assertTrue(oldRefusal != CoreRefusal.of(spec), link)
            assertTrue(CoreRefusal.of(spec)?.contains("a transport Xray removed") != true, link)
        }
    }

    @Test fun noTlsAndVisionAndWhatRealityRefusesAreRefusedWithSentences() {
        val plain = spec("$none&type=http&path=%2Fh")
        assertEquals(VlessHttp2.NEEDS_TLS, CoreRefusal.of(plain))
        assertFailsWith<IllegalArgumentException> { CoreConfig.build(plain, CoreFixtures.login) }
        // The link parser keeps a flow only on raw TCP, so no link reaches this refusal: a spec that carries one does.
        for (link in listOf("$tls&type=http&path=%2Fh", "$reality&type=http&path=%2Fh")) {
            assertNull(spec("$link&flow=xtls-rprx-vision").flow, link)
            assertEquals(VlessHttp2.NO_FLOW, CoreRefusal.of(spec(link).copy(flow = "xtls-rprx-vision")), link)
        }
        // Nor does a Reality link keep allowInsecure (the parser keeps it on ordinary TLS only): a spec that carries it does.
        val waived = spec("$reality&allowInsecure=1&type=http&path=%2Fh")
        assertFalse(waived.insecure)
        assertEquals(CoreRefusal.WAIVED_CHECKS, CoreRefusal.of(waived.copy(insecure = true)))
        assertEquals(
            "a Reality line with no server name",
            CoreRefusal.of(spec("vless://$id@127.0.0.1:443?security=reality&encryption=none&pbk=${CoreFixtures.PUBLIC_KEY}&sid=ab12&type=http")),
        )
    }
}
