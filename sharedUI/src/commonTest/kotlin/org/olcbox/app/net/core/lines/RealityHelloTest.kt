package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Reality with `fp=android` or `fp=360`: the core has no REALITY hello for
 * them (they offer no X25519 key share), so the line is carried with the
 * Chrome hello and the swap is said in a sentence for the caller's log.
 */
class RealityHelloTest {
    private fun spec(fp: String?): OutboundSpec.Vless {
        val fpPart = if (fp == null) "" else "&fp=$fp"
        return LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
                "?security=reality&encryption=none&pbk=${CoreFixtures.PUBLIC_KEY}&sid=ab12cd34$fpPart" +
                "&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#t",
        ) as OutboundSpec.Vless
    }

    private fun fingerprint(spec: OutboundSpec): String {
        val config = Json.parseToJsonElement(CoreConfig.build(spec, CoreFixtures.login)).jsonObject
        val out = config.getValue("outbounds").jsonArray.first().jsonObject
        return out.getValue("streamSettings").jsonObject.getValue("realitySettings").jsonObject
            .getValue("fingerprint").jsonPrimitive.content
    }

    @Test fun androidAndThreeSixtyAreCarriedWithTheChromeHello() {
        for (fp in listOf("android", "360", "Android", "hello360_11_0")) {
            assertNull(CoreRefusal.of(spec(fp)), fp)
            assertEquals("chrome", fingerprint(spec(fp)), fp)
            assertEquals("chrome", Reality.hello(fp), fp)
        }
    }

    @Test fun theSwapIsSaidInASentenceNamingTheHello() {
        for (fp in listOf("android", "360")) {
            val note = assertNotNull(Reality.helloNote(spec(fp)), fp)
            assertTrue("Chrome" in note && fp in note, note)
            assertTrue("\u2014" !in note && "\u2013" !in note, note)
        }
    }

    @Test fun anyOtherHelloIsKeptAndSaysNothing() {
        for (fp in listOf(null, "chrome", "firefox", "safari", "ios", "randomized", "hellochrome_133")) {
            assertNull(Reality.helloNote(spec(fp)), "$fp")
            assertEquals(Reality.hello(fp), fingerprint(spec(fp)), "$fp")
        }
        assertEquals("firefox", fingerprint(spec("firefox")))
    }
}
