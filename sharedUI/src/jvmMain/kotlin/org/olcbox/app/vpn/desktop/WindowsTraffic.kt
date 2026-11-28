package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.olcbox.app.vpn.TrafficCounters
import java.util.concurrent.TimeUnit

/**
 * Windows, the engine's tun: the adapter's own counters, which Windows keeps
 * and any user may read (`Get-NetAdapterStatistics`). As on Linux and macOS
 * they count what crosses the tunnel interface: `ReceivedBytes` is on its way
 * in, `SentBytes` on its way out, and the lines behind the tun, which leave
 * beside it, are in neither.
 *
 * An adapter that is not there, an answer that cannot be read and a negative
 * count are no reading, never a zero: a zero would say a tunnel that started
 * again had carried nothing.
 */
internal class WindowsAdapterTraffic(
    private val adapter: String,
    private val ask: suspend (script: String) -> String? = { script ->
        runCatching { windowsPowerShell(script) }.getOrNull()
    },
) : TunnelTraffic {
    override suspend fun read(): TrafficCounters? = ask(script(adapter))?.let(::counters)

    internal companion object {
        /** One object with the two counts, as JSON; nothing at all for an adapter that is not there. */
        fun script(adapter: String): String =
            "\$ErrorActionPreference = 'Stop'; " +
                "Get-NetAdapterStatistics -Name ${adapter.powershellString()} | Select-Object -First 1 " +
                "ReceivedBytes, SentBytes | ConvertTo-Json -Compress"

        fun counters(output: String): TrafficCounters? {
            val parsed = runCatching { Json.parseToJsonElement(output.trim()) }.getOrNull() ?: return null
            val row = (if (parsed is JsonArray) parsed.firstOrNull() else parsed) as? JsonObject ?: return null
            val received = row["ReceivedBytes"]?.jsonPrimitive?.longOrNull ?: return null
            val sent = row["SentBytes"]?.jsonPrimitive?.longOrNull ?: return null
            if (received < 0 || sent < 0) return null
            return TrafficCounters(bytesIn = received, bytesOut = sent)
        }
    }
}

/** A PowerShell single-quoted string: the only character it treats as special is the quote itself. */
internal fun String.powershellString(): String = "'" + replace("'", "''") + "'"

/**
 * Runs a script in Windows PowerShell and returns what it printed. A non-zero
 * exit and a script that outlives [timeoutSeconds] are failures, said with
 * what the script wrote.
 */
internal suspend fun windowsPowerShell(script: String, timeoutSeconds: Long = 15): String =
    withContext(Dispatchers.IO) { windowsPowerShellBlocking(script, timeoutSeconds) }

internal fun windowsPowerShellBlocking(script: String, timeoutSeconds: Long = 15): String {
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script
    ).redirectErrorStream(true).start()
    process.outputStream.close()
    val output = StringBuilder()
    val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.also { it.start() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("PowerShell timed out after $timeoutSeconds s")
    }
    reader.join(2_000)
    val text = output.toString().trim()
    check(process.exitValue() == 0) { text.ifBlank { "PowerShell failed with code ${process.exitValue()}" } }
    return text
}
