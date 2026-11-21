package org.olcbox.app.vpn.rooms

import java.io.File
import kotlinx.serialization.json.Json
import org.olcbox.app.data.datasource.AtomicText

/**
 * [RoomMemoryRecord] on disk: one record, the last list a running olcRTC client was handed
 * (iOS keeps the same in the App Group's `olcrtc-rooms.json`). Written whole in one step
 * ([AtomicText], which renames over the file: Android's rename, not Windows'). A file that
 * is missing or does not read is no memory, never an error.
 */
class RoomMemoryFile(private val file: File) {
    fun load(): RoomMemoryRecord? =
        runCatching { json.decodeFromString(RoomMemoryRecord.serializer(), file.readText()) }.getOrNull()

    fun save(record: RoomMemoryRecord) {
        runCatching { AtomicText.write(file, json.encodeToString(RoomMemoryRecord.serializer(), record)) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
