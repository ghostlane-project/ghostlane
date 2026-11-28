package org.olcbox.app.vpn.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One route made outside the tunnel's own adapter: it outlives the adapter, so it is written down. */
@Serializable
internal data class WindowsRouteRecord(val prefix: String, val interfaceIndex: Int, val nextHop: String? = null)

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

/**
 * A PowerShell script written with `§` where it means `$`: a Kotlin string
 * reads every `$name` as a template, and a script is mostly variables.
 */
internal fun psScript(script: String): String = script.trimIndent().replace('§', '$')

/**
 * What is left of a run of the Windows tunnel that did not stop itself: the
 * program of a killed app, the routes it made outside its own adapter, the DNS
 * guard's rules. Read from the journal the run wrote before each change, and
 * by name for what a name can find (the guard's rules and name rule), so a
 * journal that is gone does not leave the guard in place.
 *
 * Run at every start, and by the stop itself, which is the same work.
 */
internal class WindowsSweeper(
    private val journal: WindowsTunJournal,
    private val addLog: (String) -> Unit = {},
    private val run: suspend (String) -> String = { windowsPowerShell(it, timeoutSeconds = 90) },
) {
    /** What it removed, one line each; empty when there was nothing. */
    suspend fun sweep(): List<String> {
        val record = journal.read()
        val removed = mutableListOf<String>()
        if (record?.hevPid != null && record.hev != null) {
            if (endProgram(record.hevPid, record.hev)) removed += "the tunnel's program (pid ${record.hevPid})"
        }
        val output = runCatching { run(removeScript(record?.routes.orEmpty())) }
            .onFailure { addLog("Windows TUN sweep: ${it.message}") }
            .getOrDefault("")
        output.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { removed += it }
        if (record != null) {
            if (!adapterGone(record.adapter)) addLog("Windows TUN sweep: the adapter ${record.adapter} is still listed")
            journal.clear()
        }
        removed.forEach { addLog("Windows TUN sweep removed $it") }
        return removed
    }

    /** Ends [pid] when it is still [path]; a process that is not that program is not ours. */
    private suspend fun endProgram(pid: Long, path: String): Boolean = withContext(Dispatchers.IO) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return@withContext false
        val command = handle.info().command().orElse(null) ?: return@withContext false
        if (!command.equals(path, ignoreCase = true)) return@withContext false
        handle.destroyForcibly()
        runCatching { handle.onExit().get(10, TimeUnit.SECONDS) }
        true
    }

    private suspend fun adapterGone(adapter: String): Boolean {
        repeat(30) {
            val there = runCatching {
                run("@(Get-NetAdapter -Name ${adapter.powershellString()} -ErrorAction SilentlyContinue).Count").trim() != "0"
            }.getOrDefault(false)
            if (!there) return true
            delay(500)
        }
        return false
    }

    internal companion object {
        /** What the script prints, a line for each thing it took away. */
        fun removeScript(routes: List<WindowsRouteRecord>): String = psScript(
            buildString {
                appendLine("§ErrorActionPreference = 'SilentlyContinue'")
                for (r in routes) {
                    appendLine("§p = ${r.prefix.powershellString()}; §i = ${r.interfaceIndex}")
                    appendLine(
                        "if (Get-NetRoute -DestinationPrefix §p -InterfaceIndex §i) { " +
                            "Remove-NetRoute -DestinationPrefix §p -InterfaceIndex §i -Confirm:§false; ('route ' + §p) }"
                    )
                }
                append(WindowsDnsGuard.removeLines(announce = true))
            }
        )
    }
}
