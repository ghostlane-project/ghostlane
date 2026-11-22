package org.olcbox.app.vpn.service.engine

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore
import org.olcbox.app.vpn.service.RustCoreBackend

/**
 * What the watchdog and the log are told about the Rust core.
 *
 * The core runs in this process behind a handle the engine keeps, so a held
 * handle does not say the core serves: one whose runtime ended or wedged keeps
 * it. While the tunnel is connected the service publishes the SOCKS port the
 * engine serves ([OlcboxVpnState.channelProxy]: the core's port for a core line,
 * the room client's for a room), and the engine is alive only while that port
 * answers a SOCKS5 greeting. The method reply comes from the engine's own
 * handshake, which a listener whose owner no longer runs never sends (the kernel
 * still completes the TCP handshake into its backlog). The greeting sends no
 * credentials and the connection is closed at the reply: both engines end such
 * a connection without a line at the level the app runs them. Before the tunnel
 * is connected (a start, its check, a reconnect) the held handle is the answer,
 * as before.
 */
internal object RustLiveness {
    /** How long the engine's port may take to answer a greeting, once connected. */
    private const val GREETING_TIMEOUT_MS = 2_000

    /** Greetings before the port is called silent: one more after a short pause. */
    private const val GREETING_TRIES = 2
    private const val GREETING_PAUSE_MS = 250L

    /** SOCKS5, two methods offered: no login and a login (RFC 1928, RFC 1929). */
    private val GREETING = byteArrayOf(5, 2, 0, 2)
    private const val SOCKS5: Int = 5

    /** The prefixes the Rust session puts on the engine's own lines (RustEngineSession). */
    private val ENGINE_LINE_PREFIXES = listOf("core: ", "rtc: ")

    /** The engine's own lines the diagnostics repeat, the newest last. */
    private const val DIAGNOSTICS_LINES = 12

    fun isRunning(backend: RustCoreBackend?): Boolean {
        val engine = backend ?: return false
        if (!engine.isCoreRunning() && !engine.isOlcRunning()) return false
        val channel = OlcboxVpnState.channelProxy ?: return true
        val silence = silence(channel.host, channel.port) ?: return true
        OlcboxVpnState.addLog("Rust core: its SOCKS port did not answer ($silence)")
        return false
    }

    /**
     * The Rust engine's account of itself, for the log: whether the core and the room client run,
     * whether the port they serve answers (asked only once connected), the runtime's memory figures
     * and the engine's last lines. [go] is the seam's (the Go session's own account); the Rust
     * session has no Go core to account for, so it is not read.
     */
    fun diagnostics(@Suppress("UNUSED_PARAMETER") go: EngineSession): String {
        val engine = RustCore.backend ?: return "Rust core: not in this build"
        val core = if (engine.isCoreRunning()) "the core is running" else "no core is running"
        val olc = if (engine.isOlcRunning()) "the olcRTC client is running" else "no olcRTC client is running"
        val port = OlcboxVpnState.channelProxy?.let { channel ->
            silence(channel.host, channel.port)?.let { "its SOCKS port did not answer ($it)" } ?: "its SOCKS port answers"
        } ?: "its SOCKS port is asked only once connected"
        val memory = runCatching { engine.memoryStats() }.getOrElse { "unread (${it::class.simpleName})" }
        val lines = OlcboxVpnState.logs.value
            .filter { line -> ENGINE_LINE_PREFIXES.any { line.startsWith(it) } }
            .takeLast(DIAGNOSTICS_LINES)
        return buildString {
            append("Rust core: $core, $olc; $port; memory $memory")
            if (lines.isEmpty()) {
                append("; the engine wrote no line")
            } else {
                append("; the engine's last ${lines.size} line(s):")
                lines.forEach { append('\n').append(it) }
            }
        }
    }

    /** Null when the port answers a greeting; else what happened, in a few words. */
    private fun silence(host: String, port: Int): String? {
        var why: String? = null
        repeat(GREETING_TRIES) { attempt ->
            if (attempt > 0) Thread.sleep(GREETING_PAUSE_MS)
            why = greet(host, port) ?: return null
        }
        return why
    }

    private fun greet(host: String, port: Int): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), GREETING_TIMEOUT_MS)
            socket.soTimeout = GREETING_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(GREETING)
                flush()
            }
            when (val version = socket.getInputStream().read()) {
                SOCKS5 -> null
                -1 -> "closed without a reply"
                else -> "the reply was not SOCKS5 (first byte $version)"
            }
        }
    } catch (_: ConnectException) {
        "refused"
    } catch (_: SocketTimeoutException) {
        "no reply in ${GREETING_TIMEOUT_MS / 1_000} s"
    } catch (e: IOException) {
        e::class.simpleName ?: "an I/O error"
    }
}
