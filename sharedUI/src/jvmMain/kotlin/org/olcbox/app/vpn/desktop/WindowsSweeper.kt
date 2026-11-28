package org.olcbox.app.vpn.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One route made outside the tunnel's own adapter: it outlives the adapter, so it is written down. */
@Serializable
internal data class WindowsRouteRecord(val prefix: String, val interfaceIndex: Int)

/** What a run of the Windows tunnel owes the machine, written before each change. */
@Serializable
internal data class WindowsTunRecord(
    val adapter: String,
    val hev: String? = null,
    val hevPid: Long? = null,
    val routes: List<WindowsRouteRecord> = emptyList(),
    val guard: Boolean = false,
)

/** The record of what the run in progress owes, a file the next start reads. */
internal class WindowsTunJournal(val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun read(): WindowsTunRecord? = runCatching {
        if (!Files.isRegularFile(file)) null else json.decodeFromString<WindowsTunRecord>(Files.readString(file))
    }.getOrNull()

    @Synchronized
    fun write(record: WindowsTunRecord) {
        file.parent?.let { Files.createDirectories(it) }
        val next = file.resolveSibling("${file.fileName}.new")
        Files.writeString(next, json.encodeToString(record))
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun update(change: (WindowsTunRecord) -> WindowsTunRecord) {
        val now = read() ?: return
        write(change(now))
    }

    @Synchronized
    fun clear() {
        runCatching { Files.deleteIfExists(file) }
    }
}

internal class WindowsSweeper(private val journal: WindowsTunJournal, private val addLog: (String) -> Unit = {}) {
    /** RED stub: written in the next commit. */
    suspend fun sweep(): List<String> = throw NotImplementedError("WindowsSweeper")
}
