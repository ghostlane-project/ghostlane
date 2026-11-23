package org.olcbox.app.vpn.rooms

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomMemoryFileTest {
    private val dir: File = Files.createTempDirectory("room-memory").toFile().also { it.deleteOnExit() }

    @Test fun aRecordSurvivesTheFile() {
        val file = RoomMemoryFile(File(dir, "olcrtc-rooms.json"))
        assertNull(file.load(), "no file, no memory")
        val record = RoomMemoryRecord.of(OlcrtcRoomList.Parsed("V1", listOf("V2")), "vkcalls", "a".repeat(64), 1_800_000_000L)
        file.save(record)
        assertEquals(record, file.load())
    }

    @Test fun aFileThatDoesNotReadIsNoMemory() {
        val target = File(dir, "broken.json").apply { writeText("{not json") }
        assertNull(RoomMemoryFile(target).load())
    }
}
