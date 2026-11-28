package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** One value of the Internet Settings key as `reg query` prints it: its type and its data, as text. */
internal data class RegistryValue(val type: String, val data: String)

/**
 * System-proxy mode on Windows: the current user's Internet Settings, which
 * WinINET and everything built on it read. Enabling points the user at the
 * PAC and turns the fixed proxy off; restoring puts back each value it
 * changed as it was, with its type, and deletes one that was not there.
 *
 * Only the two values this changes are touched. The fixed proxy's server and
 * its override list are left as the user keeps them.
 *
 * [journal] is a file that holds the values as they were, written before the
 * first value is changed and deleted once they are put back. An app that is
 * killed in between leaves the system pointing at a PAC nobody serves:
 * [recover] reads the file at the next start and puts the user's values back,
 * and [enable] does the same first, so it never takes our own values for the
 * user's. No file, no journal (the tests that do not ask for one).
 *
 * [run] runs one command (`reg`, `powershell`) and returns what it printed or
 * throws; the tests give it a registry of their own.
 */
internal class WindowsSystemProxy(
    private val run: suspend (List<String>) -> String = ::runWindowsCommand,
) {
    /** The file of the values as they were; set by whoever keeps one. Null for no journal. */
    var journal: Path? = null

    private var backup: Map<String, RegistryValue?>? = null

    suspend fun enable(pacUrl: String) {
        // A second enable must not take our own values for the user's.
        if (backup == null) {
            recover()
            val before = snapshot(CHANGED)
            // On disk before the first value is touched.
            writeJournal(before)
            backup = before
        }
        try {
            enableCommands(pacUrl).forEach { run(it) }
        } catch (e: Throwable) {
            restore()
            throw e
        }
        refresh()
    }

    suspend fun restore() {
        val before = backup ?: return
        restoreCommands(before).forEach { command -> runCatching { run(command) } }
        refresh()
        backup = null
        deleteJournal()
    }

    /**
     * Puts back what a run that was killed left changed: the values its journal
     * holds, as they were. Returns whether there was a journal. Does nothing
     * for a run that holds its values in memory: that one restores them itself.
     */
    suspend fun recover(): Boolean {
        if (backup != null) return false
        val before = readJournal() ?: return false
        restoreCommands(before).forEach { command -> runCatching { run(command) } }
        refresh()
        deleteJournal()
        return true
    }

    private suspend fun writeJournal(values: Map<String, RegistryValue?>) {
        val file = journal ?: return
        val text = buildJsonObject {
            for ((name, value) in values) {
                if (value == null) {
                    put(name, JsonNull)
                } else {
                    put(name, buildJsonObject { put("type", value.type); put("data", value.data) })
                }
            }
        }.toString()
        withContext(Dispatchers.IO) {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(file, text)
        }
    }

    private suspend fun readJournal(): Map<String, RegistryValue?>? {
        val file = journal ?: return null
        return withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) return@withContext null
            runCatching {
                val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
                root.mapValues { (_, element) ->
                    if (element is JsonNull) {
                        null
                    } else {
                        val row = element.jsonObject
                        RegistryValue(row.getValue("type").jsonPrimitive.content, row.getValue("data").jsonPrimitive.content)
                    }
                }
            }.getOrNull().also { if (it == null) runCatching { Files.deleteIfExists(file) } }
        }
    }

    private suspend fun deleteJournal() {
        val file = journal ?: return
        withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(file) } }
    }

    /** The values as they stand: null for one that is not set. */
    suspend fun snapshot(names: List<String> = ALL): Map<String, RegistryValue?> =
        names.associateWith { name ->
            runCatching { run(listOf("reg", "query", KEY, "/v", name)) }.getOrNull()?.let { parseValue(name, it) }
        }

    private suspend fun refresh() {
        runCatching { run(DesktopRefresh.command()) }
    }

    internal companion object {
        const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
        val CHANGED = listOf("ProxyEnable", "AutoConfigURL")
        val ALL = listOf("ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL")

        fun enableCommands(pacUrl: String): List<List<String>> = listOf(
            setCommand("ProxyEnable", RegistryValue("REG_DWORD", "0")),
            setCommand("AutoConfigURL", RegistryValue("REG_SZ", pacUrl)),
        )

        fun restoreCommands(before: Map<String, RegistryValue?>): List<List<String>> =
            before.map { (name, value) ->
                if (value == null) listOf("reg", "delete", KEY, "/v", name, "/f") else setCommand(name, value)
            }

        private fun setCommand(name: String, value: RegistryValue): List<String> {
            val data = if (value.type == "REG_DWORD") {
                // reg query prints 0x1; reg add reads decimal.
                value.data.removePrefix("0x").toLongOrNull(16)?.toString() ?: value.data
            } else {
                value.data
            }
            return listOf("reg", "add", KEY, "/v", name, "/t", value.type, "/d", data, "/f")
        }

        /** `    ProxyEnable    REG_DWORD    0x1`, the data being whatever follows the type, spaces included. */
        fun parseValue(name: String, output: String): RegistryValue? {
            val row = Regex("^\\s*${Regex.escape(name)}\\s+(REG_[A-Z_]+)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
            for (line in output.lineSequence()) {
                val match = row.matchEntire(line.trimEnd('\r')) ?: continue
                return RegistryValue(match.groupValues[1].uppercase(), match.groupValues[2].trim())
            }
            return null
        }
    }
}

/** The call that tells WinINET its settings changed, so a running browser reads them again. */
internal object DesktopRefresh {
    fun command(): List<String> {
        val script = """
            ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
            Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
            [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
            [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
        """.trimIndent()
        return listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
    }
}

internal suspend fun runWindowsCommand(command: List<String>): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) error("${command.take(3).joinToString(" ")} failed with code $exitCode: $output")
    output
}
