package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * sing-box refuses a uTLS fingerprint or a VLESS flow it does not name, and
 * the refusal stops the whole configuration. Links carry Xray's names; these
 * are the names sing-box is handed instead.
 */
class SingBoxNamesTest {
    @Test fun fingerprintsSingBoxKnowsAreKept() {
        for (fp in listOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized", "chrome_pq")) {
            assertEquals(fp, SingBoxConfig.singBoxFingerprint(fp), fp)
        }
    }

    @Test fun xrayNamesBecomeTheNearestSingBoxHas() {
        val expected = mapOf(
            "Chrome" to "chrome",
            " FIREFOX " to "firefox",
            "hellochrome_133" to "chrome",
            "HelloChrome_Auto" to "chrome",
            "chrome_auto" to "chrome",
            "hellofirefox_148" to "firefox",
            "hellosafari_26_3" to "safari",
            "helloios_14" to "ios",
            "helloedge_85" to "edge",
            "hello360_7_5" to "360",
            "helloqq_11_1" to "qq",
            "helloandroid_11_okhttp" to "android",
            "randomizednoalpn" to "randomized",
            "randomizedalpn" to "randomized",
            "unsafe" to "chrome",
            "nonsense" to "chrome",
            "" to "chrome",
            null to "chrome",
        )
        for ((fp, want) in expected) assertEquals(want, SingBoxConfig.singBoxFingerprint(fp), "fp=$fp")
    }

    @Test fun visionWithUdp443IsVisionToSingBox() {
        assertEquals("xtls-rprx-vision", SingBoxConfig.singBoxFlow("xtls-rprx-vision"))
        assertEquals("xtls-rprx-vision", SingBoxConfig.singBoxFlow("xtls-rprx-vision-udp443"))
        assertEquals("xtls-rprx-vision", SingBoxConfig.singBoxFlow(" XTLS-RPRX-VISION "))
        assertNull(SingBoxConfig.singBoxFlow(null))
        assertNull(SingBoxConfig.singBoxFlow("  "))
        // Not Vision: left as written, for sing-box to name what it refuses.
        assertEquals("xtls-rprx-direct", SingBoxConfig.singBoxFlow("xtls-rprx-direct"))
    }

    @Test fun aLinkWithXrayNamesBuildsAConfigWithSingBoxNames() {
        val spec = LinkParser.parse(
            "vless://u@1.2.3.4:443?security=reality&pbk=PBK&sid=ab&sni=sni.x&fp=hellochrome_133" +
                "&flow=xtls-rprx-vision-udp443&type=tcp#DE"
        ) as OutboundSpec.Vless
        val out = Json.parseToJsonElement(SingBoxConfig.build(spec, socksPort = 10809))
            .jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", out["flow"]!!.jsonPrimitive.content)
        val utls = out["tls"]!!.jsonObject["utls"]!!.jsonObject
        assertEquals("chrome", utls["fingerprint"]!!.jsonPrimitive.content)
        // The spec keeps the link's own names: Xray reads them as written.
        assertEquals("hellochrome_133", spec.fingerprint)
        assertEquals("xtls-rprx-vision-udp443", spec.flow)
    }
}
