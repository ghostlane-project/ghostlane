package org.olcbox.app.net.core.lines

import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
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

class ShadowsocksTest {
    private fun ss(method: String, password: String, host: String = "192.0.2.1") =
        OutboundSpec.Shadowsocks(method, password, host, 8388, "t")

    private val k16 = ShadowsocksFixture.KEY_16
    private val k32 = ShadowsocksFixture.KEY_32

    @Test fun everyFixtureLinkIsTheKindsAndTheCoreTakesIt() {
        for (case in ShadowsocksFixture.cases) {
            val spec = case.spec()
            assertTrue(spec is OutboundSpec.Shadowsocks, case.name)
            assertEquals(Shadowsocks, CoreLines.of(spec), case.name)
            assertNull(CoreRefusal.of(spec), case.name)
        }
    }

    @Test fun aeadAndTheTwentyTwentyTwoCiphersAreTwoShapes() {
        assertEquals("shadowsocks", Shadowsocks.shape(ss("aes-256-gcm", "pw")))
        assertEquals("shadowsocks-2022", Shadowsocks.shape(ss("2022-blake3-aes-256-gcm", k32)))
        assertEquals("shadowsocks-2022", Shadowsocks.shape(ss("2022-BLAKE3-AES-256-GCM", k32)))
        assertNull(Shadowsocks.shape(requireNotNull(LinkParser.parse("trojan://pw@192.0.2.1:443#t"))))
    }

    @Test fun aStreamCipherIsRefusedWithASentence() {
        for (method in listOf("rc4-md5", "aes-256-cfb", "chacha20", "table", "none", "aes-192-gcm")) {
            val why = CoreRefusal.of(ss(method, "pw"))
            assertNotNull(why, method)
            assertTrue(why.contains(method), "the sentence names $method: $why")
            assertFalse(why.contains("pw"), "the sentence quotes no secret")
        }
        assertTrue(CoreRefusal.of(ss("rc4-md5", "pw"))!!.contains("stream cipher"))
    }

    @Test fun anAeadMethodWithNoPasswordIsRefused() {
        assertNotNull(CoreRefusal.of(ss("aes-128-gcm", "")))
    }

    @Test fun aTwentyTwentyTwoKeyThatIsNotTheCiphersIsRefusedWithoutQuotingIt() {
        val wrongLength = listOf(
            ss("2022-blake3-aes-128-gcm", k32),
            ss("2022-blake3-aes-256-gcm", k16),
            ss("2022-blake3-chacha20-poly1305", k16),
        )
        val notBase64 = listOf(
            ss("2022-blake3-aes-128-gcm", "not base64 at all!!"),
            ss("2022-blake3-aes-128-gcm", "AAECAwQFBgcICQoLDA0ODw"),
            ss("2022-blake3-aes-128-gcm", "AAECAwQFBgcICQoLDA0ODx=="),
            ss("2022-blake3-aes-128-gcm", ""),
        )
        for (spec in wrongLength + notBase64) {
            val why = assertNotNull(CoreRefusal.of(spec), spec.password)
            assertFalse(spec.password.isNotEmpty() && why.contains(spec.password), why)
        }
    }

    @Test fun identityKeysOnlyWhereTheCipherTakesThem() {
        assertNull(CoreRefusal.of(ss("2022-blake3-aes-128-gcm", "$k16:$k16")))
        assertNull(CoreRefusal.of(ss("2022-blake3-aes-256-gcm", "$k32:$k32:$k32")))
        assertNotNull(CoreRefusal.of(ss("2022-blake3-chacha20-poly1305", "$k32:$k32")))
        assertNotNull(CoreRefusal.of(ss("2022-blake3-aes-128-gcm", List(9) { k16 }.joinToString(":"))))
        assertNotNull(CoreRefusal.of(ss("2022-blake3-aes-128-gcm", "$k16:")))
    }

    @Test fun aHostnameIsPinnedToTheAddressTheCallerResolved() {
        val named = ss("aes-256-gcm", "pw", host = "ss.example.com")
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(named))
        val pinned = CoreLines.pinHost(named, "192.0.2.7") as OutboundSpec.Shadowsocks
        assertEquals("192.0.2.7", pinned.host)
        assertNull(CoreRefusal.of(pinned))
    }

    @Test fun theOutboundHoldsTheFourFieldsAndNoStreamSettings() {
        for (case in ShadowsocksFixture.cases) {
            val json = CoreConfig.build(case.spec(), CoreFixtures.login)
            assertTrue(json.contains("\"protocol\":\"shadowsocks\",\"settings\":{\"address\":\"192.0.2.1\",\"port\":8388,\"method\":"), case.name)
            val outbound = json.substringAfter("\"outbounds\":[").substringBefore("{\"tag\":\"dns-out\"")
            assertFalse(outbound.contains("streamSettings"), case.name)
        }
    }

    @Test fun aPluginLinkNeverReachesTheKind() {
        assertNull(LinkParser.parse("ss://aes-128-gcm:pw@192.0.2.1:8388/?plugin=obfs-local%3Bobfs%3Dhttp#P"))
    }
}
