package org.olcbox.app.vpn.rooms

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** iosApp/Tests/RoomListTests.swift, case for case: both platforms read one answer the same way. */
class OlcrtcRoomListTest {
    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val d = '$'

    private val body = """
        #name: Israel
        olcrtc://telemost?vp8channel@11115586048655#$key${d}Israel
        ##name: IL-1
        ##rooms: 81055221156696, 52664279650262 11115586048655
        olcrtc://telemost?vp8channel@99990000000000#$otherKey${d}Other
        ##rooms: 12345
    """.trimIndent()

    private fun p(primary: String, vararg extras: String) = OlcrtcRoomList.Parsed(primary, extras.toList())

    @Test fun theHeaderUnderOurLineGivesTheExtrasWithoutThePrimary() {
        val parsed = OlcrtcRoomList.parse(body, key, "telemost")
        assertEquals(p("11115586048655", "81055221156696", "52664279650262"), parsed)
        assertEquals(listOf("11115586048655", "81055221156696", "52664279650262"), parsed?.all)
    }

    @Test fun anotherLocationsHeaderIsNotOurs() {
        assertEquals(listOf("12345"), OlcrtcRoomList.parse(body, otherKey, "telemost")?.extras)
        assertNull(OlcrtcRoomList.parse(body, "c".repeat(64), "telemost"), "a key nobody carries")
    }

    @Test fun sameKeyLinesJoinTheGroup() {
        val lines = "olcrtc://telemost?vp8channel@R1#$key\nolcrtc://telemost?vp8channel@R2#$key%client${d}Name\n##rooms: R3"
        assertEquals(p("R1", "R2", "R3"), OlcrtcRoomList.parse(lines, key, "telemost"))
    }

    @Test fun oneOriginThreeCarriersOneKeyEachSeesItsOwn() {
        val origin = """
            olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/99#$key${d}NL · olcRTC
            ##rooms: https://telemost.yandex.ru/j/98
            olcrtc://wbstream?vp8channel@wbcod#$key${d}NL · olcRTC · WB
            ##rooms: wbnext
            olcrtc://salutejazz?datachannel@sjcode:sjpass1#$key${d}NL · olcRTC · SJ
            olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/97#$key${d}NL · olcRTC
        """.trimIndent()
        assertEquals(
            p("https://telemost.yandex.ru/j/99", "https://telemost.yandex.ru/j/98", "https://telemost.yandex.ru/j/97"),
            OlcrtcRoomList.parse(origin, key, "telemost")
        )
        assertEquals(p("wbcod", "wbnext"), OlcrtcRoomList.parse(origin, key, "wbstream"))
        assertEquals(p("sjcode:sjpass1"), OlcrtcRoomList.parse(origin, key, "salutejazz"))
        assertNull(OlcrtcRoomList.parse(origin, key, "jitsi"))
    }

    @Test fun carrierAliasesAndCase() {
        assertEquals("R1", OlcrtcRoomList.parse("olcrtc://Yandex?vp8channel@R1#$key", key, " TELEMOST ")?.primary)
        assertEquals("S1", OlcrtcRoomList.parse("olcrtc://jazz?datachannel@S1#$key", key, "salutejazz")?.primary)
        assertEquals(
            "https://vk.ru/call/join/V1",
            OlcrtcRoomList.parse("olcrtc://VK?vp8channel@https://vk.ru/call/join/V1#$key", key, "vkcalls")?.primary
        )
        assertNull(OlcrtcRoomList.parse("olcrtc://vkcalls?vp8channel@https://vk.ru/call/join/V1#$key", key, "wbstream"))
        assertEquals(
            "https://my.mts-link.ru/j/1/2",
            OlcrtcRoomList.parse("olcrtc://MTS?vp8channel@https://my.mts-link.ru/j/1/2#$key", key, "mtslink")?.primary
        )
        assertNull(OlcrtcRoomList.parse("olcrtc://mtslink?vp8channel@https://my.mts-link.ru/j/1/2#$key", key, "wbstream"))
    }

    @Test fun anUnknownCarrierIsNotWbStream() {
        val unknown = "olcrtc://wbstream?vp8channel@W1#$key\nolcrtc://newcarrier?datachannel@N1#$key\n##rooms: N2\nolcrtc://wbstream?vp8channel@W2#$key"
        assertEquals(p("W1", "W2"), OlcrtcRoomList.parse(unknown, key, "wbstream"))
        assertNull(OlcrtcRoomList.parse("olcrtc://newcarrier?datachannel@N1#$key", key, "wbstream"))
        assertNull(OlcrtcRoomList.fields("olcrtc://?vp8channel@R1#$key"), "no carrier, no location")
    }

    @Test fun theKeyEndsAtPercentOrDollarAndIgnoresCase() {
        val f = OlcrtcRoomList.fields("olcrtc://jitsi?datachannel@https://meet.example/room#ABC%dev${d}n")
        assertEquals("ABC", f?.key)
        assertEquals("https://meet.example/room", f?.room)
        assertEquals("jitsi", OlcrtcRoomList.fields("olcrtc://jitsi-meet?datachannel@https://meet.example/room#ABC")?.carrier)
        assertNull(OlcrtcRoomList.fields("olcrtc://nothing-here"))
        assertEquals("11115586048655", OlcrtcRoomList.parse(body, key.uppercase(), "telemost")?.primary)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun aBase64BodyIsUnwrappedProseIsLeftAlone() {
        val encoded = Base64.Default.encode(body.encodeToByteArray())
        assertEquals(listOf("81055221156696", "52664279650262"), OlcrtcRoomList.parse(encoded, key, "telemost")?.extras)
        assertEquals("11115586048655", OlcrtcRoomList.parse(encoded.trimEnd('='), key, "telemost")?.primary)
        assertEquals("not a subscription at all", OlcrtcRoomList.decodeBody("not a subscription at all"))
    }

    @Test fun splitAndSameCarrier() {
        assertEquals(listOf("a", "b", "c", "d"), OlcrtcRoomList.splitRooms(" a,b  c\t,d "))
        // Swift splits on any whitespace and any newline: a no-break space and U+2028 too.
        assertEquals(listOf("r1", "r2", "r3"), OlcrtcRoomList.splitRooms("r1\u00A0r2\u2028r3"))
        val d = '$'
        val key = "b".repeat(64)
        assertEquals(
            OlcrtcRoomList.Parsed("P1", listOf("P2")),
            OlcrtcRoomList.parse("olcrtc://vkcalls?vp8channel@P1#$key${d}DE\u2028##rooms: P2", key, "vkcalls"),
            "a line break Swift reads (U+2028) ends a line here too"
        )
        assertTrue(OlcrtcRoomList.sameCarrier("wbstream", "WB-Stream"))
        assertFalse(OlcrtcRoomList.sameCarrier("wbstream", "telemost"))
        assertFalse(OlcrtcRoomList.sameCarrier("", ""))
    }

    @Test fun aDigestIsEightHexCharactersAndNeverTheRoom() {
        val digest = OlcrtcRoomList.digest("https://vk.ru/call/join/V1")
        assertEquals(8, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
        // SHA-256("abc") starts ba7816bf.
        assertEquals("ba7816bf", OlcrtcRoomList.digest("abc"))
    }
}
