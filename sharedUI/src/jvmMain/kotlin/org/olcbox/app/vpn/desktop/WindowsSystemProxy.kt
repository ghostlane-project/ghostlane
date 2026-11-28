package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * [run] runs one command (`reg`, `powershell`) and returns what it printed or
 * throws; the tests give it a registry of their own.
 */
internal class WindowsSystemProxy(
    private val run: suspend (List<String>) -> String = ::runWindowsCommand,
) {
    private var backup: Map<String, RegistryValue?>? = null

    suspend fun enable(pacUrl: String) {
        // A second enable must not take our own values for the user's.
        if (backup == null) backup = snapshot(CHANGED)
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
