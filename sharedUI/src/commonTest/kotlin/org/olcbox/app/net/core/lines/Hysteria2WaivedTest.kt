package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Hysteria2 link that waives certificate checks with no pin is carried
 * (the core honours `allowInsecure` over QUIC and warns naming the outbound),
 * and a link that does not say so is written without the flag: the core then
 * verifies the certificate against the public roots.
 */
class Hysteria2WaivedTest {
    private val base = "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW"

    private fun spec(query: String): OutboundSpec.Hysteria2 =
        LinkParser.parse("$base$query#t") as OutboundSpec.Hysteria2

    private fun tls(spec: OutboundSpec): JsonObject {
        val config = Json.parseToJsonElement(CoreConfig.build(spec, CoreFixtures.login)).jsonObject
        val out = config.getValue("outbounds").jsonArray.first().jsonObject
        return out.getValue("streamSettings").jsonObject.getValue("tlsSettings").jsonObject
    }

    @Test fun aLinkThatWaivesTheChecksWithNoPinIsCarriedWithTheFlag() {
        val waived = spec("&insecure=1")
        assertNull(CoreRefusal.of(waived))
        assertEquals("true", assertNotNull(tls(waived)["allowInsecure"]).toString())
        assertNull(tls(waived)["pinnedPeerCertSha256"])
    }

    @Test fun aLinkThatDoesNotSayItIsWrittenWithoutTheFlag() {
        for (query in listOf("", "&insecure=0")) {
            val strict = spec(query)
            assertNull(CoreRefusal.of(strict), query)
            assertNull(tls(strict)["allowInsecure"], query)
            assertNull(tls(strict)["pinnedPeerCertSha256"], query)
        }
    }

    @Test fun aPinBesideTheFlagIsVerifiedAndTheFlagIsNotWritten() {
        val both = spec("&insecure=1&pinSHA256=${CoreFixtures.PIN}")
        assertNull(CoreRefusal.of(both))
        assertNull(tls(both)["allowInsecure"])
        assertTrue(tls(both)["pinnedPeerCertSha256"] != null)
    }
}
