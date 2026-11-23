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
 * the room client's for a room), and the engine is alive while that port
 * answers a SOCKS5 greeting or accepts it and closes it. A refused connect, no
 * connection in time, or a connection that gets no reply in time is silence: a
 * listener whose owner no longer runs sends no reply (the kernel still completes
 * the TCP handshake into its backlog). A port that accepts and closes is full,
 * not dead: the room client's accept loop closes, unanswered, every connection
 * it accepts once it holds its cap of SOCKS connections, and restarting it then
 * would drop every connection it carries. The greeting sends no credentials and
 * the connection is closed at the reply: both engines end such a connection
 * without a line at the level the app runs them. Before the tunnel is connected
 * (a start, its check, a reconnect) the held handle is the answer, as before.
 */
internal object RustLiveness {
    /** How long the engine's port may take to take a connection, and then to answer a greeting. */
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

    /** Whether the log has said, since the port last answered or fell silent, that it closes greetings unanswered. */
    @Volatile private var closingSaid = false

    /** What the engine's port did with a greeting. */
    private sealed interface Greeting {
        /** It replied as SOCKS5: the engine's handshake runs. */
        data object Answered : Greeting

        /** It accepted and then closed or reset the connection with no reply: its accept loop runs. */
        data class Closed(val how: String) : Greeting

        /** Refused, no connection in time, no reply in time, or a reply that is not SOCKS5. */
        data class Silent(val why: String) : Greeting
    }

    fun isRunning(backend: RustCoreBackend?): Boolean {
        val engine = backend ?: return false
        if (!engine.isCoreRunning() && !engine.isOlcRunning()) return false
        val channel = OlcboxVpnState.channelProxy ?: return true
        return when (val greeting = ask(channel.host, channel.port)) {
            Greeting.Answered -> {
                closingSaid = false
                true
            }
            is Greeting.Closed -> {
                if (!closingSaid) {
                    closingSaid = true
                    OlcboxVpnState.addLog(
                        "Rust core: its SOCKS port accepted a greeting and closed it without a reply (${greeting.how}), " +
                            "as a room client at its connection cap does; held as running"
                    )
                }
                true
            }
            is Greeting.Silent -> {
                closingSaid = false
                OlcboxVpnState.addLog("Rust core: its SOCKS port did not answer (${greeting.why})")
                false
            }
        }
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
            when (val greeting = ask(channel.host, channel.port)) {
                Greeting.Answered -> "its SOCKS port answers"
                is Greeting.Closed -> "its SOCKS port accepts a greeting and closes it without a reply (${greeting.how})"
                is Greeting.Silent -> "its SOCKS port did not answer (${greeting.why})"
            }
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

    /** The first greeting that is not silence, or the last silence after [GREETING_TRIES]. */
    private fun ask(host: String, port: Int): Greeting {
        var greeting = greet(host, port)
        repeat(GREETING_TRIES - 1) {
            if (greeting !is Greeting.Silent) return greeting
            Thread.sleep(GREETING_PAUSE_MS)
            greeting = greet(host, port)
        }
        return greeting
    }

    private fun greet(host: String, port: Int): Greeting {
        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress(host, port), GREETING_TIMEOUT_MS)
            } catch (_: ConnectException) {
                return Greeting.Silent("refused")
            } catch (_: SocketTimeoutException) {
                return Greeting.Silent("no connection in ${GREETING_TIMEOUT_MS / 1_000} s")
            } catch (e: IOException) {
                return Greeting.Silent(e::class.simpleName ?: "an I/O error")
            }
            // Connected. A close or a reset from here on is the owner's: it accepted the connection
            // and closed it (a room client at its cap), or closed its listener as it stopped, which
            // the next look finds refused. Only a reply that never comes is silence.
            return try {
                socket.soTimeout = GREETING_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(GREETING)
                    flush()
                }
                when (val version = socket.getInputStream().read()) {
                    SOCKS5 -> Greeting.Answered
                    -1 -> Greeting.Closed("closed")
                    else -> Greeting.Silent("the reply was not SOCKS5 (first byte $version)")
                }
            } catch (_: SocketTimeoutException) {
                Greeting.Silent("no reply in ${GREETING_TIMEOUT_MS / 1_000} s")
            } catch (e: IOException) {
                Greeting.Closed(e::class.simpleName ?: "an I/O error")
            }
        } finally {
            runCatching { socket.close() }
        }
    }
}
