package org.olcbox.app.vpn.rooms

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iosApp/Tests/DeviceHeadersTests.swift's refusals and reasons, on the Kotlin port. */
class RoomListRefusalTest {
    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.associate { it.first.lowercase() to it.second }
        return { name -> map[name.lowercase()] }
    }

    @Test fun aFlagSaysTrueInAnyCase() {
        assertEquals(
            RoomListRefusal("x-hwid-max-devices-reached", null),
            RoomListRefusal.of(200, headers("X-HWID-Max-Devices-Reached" to " TRUE "))
        )
        assertEquals(
            RoomListRefusal("x-hwid-not-supported", null),
            RoomListRefusal.of(204, headers("x-hwid-not-supported" to "true"))
        )
        assertNull(RoomListRefusal.of(200, headers("x-hwid-not-supported" to "false")))
        assertNull(RoomListRefusal.of(200, headers()))
    }

    @Test fun onlyA2xxCanBeOne() {
        assertNull(RoomListRefusal.of(403, headers("x-hwid-max-devices-reached" to "true")))
        assertNull(RoomListRefusal.of(503, headers("x-hwid-not-supported" to "true")))
    }

    @Test fun maxDevicesIsAskedFirst() {
        val both = headers("x-hwid-not-supported" to "true", "x-hwid-max-devices-reached" to "true")
        assertEquals("x-hwid-max-devices-reached", RoomListRefusal.of(200, both)?.flag)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun theReasonFromAnnounce() {
        assertEquals("Too many devices", RoomListRefusal.reason("  Too many devices  "))
        assertNull(RoomListRefusal.reason("0"), "0 takes it down")
        assertNull(RoomListRefusal.reason("   "))
        assertNull(RoomListRefusal.reason(null))
        val encoded = Base64.Default.encode("Слишком много устройств".encodeToByteArray()).trimEnd('=')
        assertEquals("Слишком много устройств", RoomListRefusal.reason("base64:$encoded"), "base64 without its padding")
        assertEquals("Слишком много устройств", RoomListRefusal.reason("BASE64: $encoded"))
        assertNull(RoomListRefusal.reason("base64:%%%"), "base64 that does not decode")
        assertEquals("ab c", RoomListRefusal.reason("a\u0000b​ c\n\r "), "control, format and line breaks dropped")
    }

    @Test fun theCutIs200UnitsAndNeverSplitsACharacter() {
        assertEquals("x".repeat(200), RoomListRefusal.reason("x".repeat(250)))
        val emoji = "😀"
        val cut = RoomListRefusal.reason("x".repeat(199) + emoji + "tail")
        assertEquals("x".repeat(199), cut, "the pair would straddle unit 200: it goes whole")
        assertEquals("x".repeat(198) + emoji, RoomListRefusal.reason("x".repeat(198) + emoji + "tail"))
    }

    @Test fun announceComesWithTheFlag() {
        val r = RoomListRefusal.of(200, headers("x-hwid-max-devices-reached" to "true", "Announce" to "Free a place"))
        assertEquals(RoomListRefusal("x-hwid-max-devices-reached", "Free a place"), r)
    }
}
