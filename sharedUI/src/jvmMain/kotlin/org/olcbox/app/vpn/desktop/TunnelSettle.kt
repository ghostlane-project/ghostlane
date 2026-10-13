package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.net.ProbeFailure
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The moments after a Mac's tunnel is called running.
 *
 * The helper answers a start a second after it started the tunnel's process,
 * and what it answers is that the process runs. Whether the tunnel serves is
 * another question, and the session's check, made at once, asked it by being
 * refused: on a Mac in the field (build 1.0.463) a room joined behind a block
 * that stood was followed by a tunnel whose own listener carried nothing to
 * the engine when it was asked, the check failed within seconds without one
 * request having reached the room, and the connect ended there, every time.
 * Where the tunnel comes first and its line behind it, as a core's does behind
 * a block, the same tunnel is asked seconds later, and it answered.
 *
 * So two things are not taken for each other here. A process that runs is not
 * a listener that accepts ([awaitListening]). And a check that is refused at
 * once says something about this machine, where one that goes unanswered for
 * the whole wait says something about the far end ([asksAgain]).
 */
internal object TunnelSettle {
    /** How long a tunnel is given to begin serving, and a refused check to be answered. */
    const val BUDGET_MS = 20_000L

    /** A probe that failed sooner than this was refused; one that took longer went unanswered. */
    const val REFUSED_WITHIN_MS = 2_000L

    /** Between one refused check and the next. */
    const val PAUSE_MS = 1_000L

    private const val POLL_MS = 100L
    private const val CONNECT_MS = 250

    /**
     * Whether a check that found nothing is made again: when every probe of it
     * was refused at once, and the tunnel has not had its time yet
     * ([sinceStartMs], counted from the first check). A check with a probe
     * that waited is an answer, and is not asked for again: a server that is
     * down is as silent the second time, and the connect would only say so a
     * minute later.
     */
    fun asksAgain(failures: List<ProbeFailure>, sinceStartMs: Long): Boolean =
        failures.isNotEmpty() && failures.all { it.tookMs < REFUSED_WITHIN_MS } && sinceStartMs < BUDGET_MS

    /**
     * Waits until something accepts a connection on [port] of this machine.
     * The milliseconds it took, or null when nothing did within [timeoutMs].
     */
    suspend fun awaitListening(port: Int, timeoutMs: Long): Long? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var waited: Long? = null
        while (waited == null) {
            if (accepts(port)) {
                waited = System.currentTimeMillis() - started
            } else if (System.currentTimeMillis() - started >= timeoutMs) {
                break
            } else {
                delay(POLL_MS)
            }
        }
        waited
    }

    private fun accepts(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MS) }
    }.isSuccess

    /**
     * What a tunnel's sing-box said of a trouble, out of all it said
     * ([MacOsTunController.diagnostics]): its errors and warnings, the last
     * [limit] kinds of them, without the colours a terminal would have shown
     * them in.
     *
     * A trouble that differs from another by its numbers alone (the moment,
     * the connection, the port it came from) is the same trouble: it is said
     * once, as it was said last, with how many times. Kept line by line, one
     * that repeats takes every place: on a Mac in the field (1.0.464) another
     * program kept knocking at the tunnel's own listener in a tongue it does
     * not speak, and the eight lines kept were that line eight times, with
     * nothing of what the tunnel had said about the check.
     */
    fun troubles(tail: String, limit: Int = 8): List<String> {
        val kinds = LinkedHashMap<String, Pair<String, Int>>()
        tail.lineSequence()
            .map { COLOURS.replace(it, "").trim() }
            .filter { line -> LEVELS_OF_TROUBLE.any(line::startsWith) }
            .forEach { line ->
                val kind = NUMBERS.replace(line, "#")
                // Taken out and put back, so that it stands where it was last said.
                val times = (kinds.remove(kind)?.second ?: 0) + 1
                kinds[kind] = line to times
            }
        return kinds.values.toList().takeLast(limit).map { (line, times) ->
            if (times > 1) "$line ($times times, the last one shown)" else line
        }
    }

    private val NUMBERS = Regex("[0-9]+")
    private val COLOURS = Regex("\u001B\\[[0-9;]*m")
    private val LEVELS_OF_TROUBLE = listOf("ERROR", "WARN", "FATAL", "PANIC", "panic:")
}
