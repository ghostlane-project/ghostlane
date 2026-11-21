package org.olcbox.app.vpn.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iosApp/Tests/RoomListTests.swift's memory cases, for the records Android writes (always with a carrier). */
class RoomMemoryRecordTest {
    private val key = "A".repeat(64)
    private val rooms = OlcrtcRoomList.Parsed("T1", listOf("T2", "T3"))
    private val now = 1_800_000_000L

    @Test fun theSameCarrierAndKeyWithinADay() {
        val saved = RoomMemoryRecord.of(rooms, "Yandex", key, now)
        val digest = RoomMemoryRecord.keyDigest(key.lowercase())
        assertEquals("telemost", saved.carrier)
        assertEquals(rooms, saved.rooms("telemost", digest, now))
        assertEquals(rooms, saved.rooms(" TELEMOST ", digest, now + RoomMemoryRecord.MAX_AGE_S - 1))
    }

    @Test fun anotherCarrierAnotherKeyOrADayLaterIsNothing() {
        val saved = RoomMemoryRecord.of(rooms, "telemost", key, now)
        val digest = RoomMemoryRecord.keyDigest(key)
        assertNull(saved.rooms("wbstream", digest, now), "a sibling carrier, same key")
        assertNull(saved.rooms("telemost", RoomMemoryRecord.keyDigest("b".repeat(64)), now), "another key")
        assertNull(saved.rooms("telemost", digest, now + RoomMemoryRecord.MAX_AGE_S), "expired")
        assertNull(saved.rooms("telemost", digest, now - 2 * RoomMemoryRecord.MAX_AGE_S), "a clock that went far back")
    }

    @Test fun theKeyIsKeptAsItsDigest() {
        val saved = RoomMemoryRecord.of(rooms, "telemost", key, now)
        assertEquals(64, saved.key.length)
        assertEquals(false, saved.key.equals(key, ignoreCase = true))
    }

    @Test fun aStartBeginsWithTheStoreThenTheMemory() {
        val start = RoomMemoryRecord.initialRooms("T1", listOf("T2"), OlcrtcRoomList.Parsed("T9", listOf("T2", "T1")))
        assertEquals(OlcrtcRoomList.Parsed("T1", listOf("T2", "T9")), start)
        assertEquals(OlcrtcRoomList.Parsed("T1", listOf("T2")), RoomMemoryRecord.initialRooms("T1", listOf("T2", "T1"), null))
    }
}
