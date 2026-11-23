package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopEngine
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineEngine
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.LineStarted
import java.net.NetworkInterface

/**
 * The interface the machine leaves by, watched for a session whose line is
 * bound to it.
 *
 * Beside a tun a line gets out of the machine because its sockets are bound
 * to the interface the default route names; a socket that is not bound
 * follows the tun's route back into the tun. ghostlane-engine reads that name
 * once, when its session opens (`bind.interface`), and is never told another:
 * it has no command channel, and binds no socket anew. When the machine moves
 * to another network (a cable plugged in, a Wi-Fi that went away), the line's
 * sockets stay on an interface that carries nothing, and the tun goes on
 * handing the machine's traffic to the line. So the default interface is
 * asked for every [intervalMs], and once another interface has been the
 * answer [SETTLE_READS] times in a row, [onMove] is called with its name.
 * That is [org.olcbox.app.vpn.desktop.engine.SessionHooks.wayOutChanged]: the
 * session stops the line and starts it again behind the tun it holds, which
 * is not touched, and the new start is bound to what is there now.
 *
 * An answer is held to what the session's line was bound to, which every
 * start of a line tells the watch ([bound]), and not to the watch's own last
 * answer: a line started again for any other reason, bound to what is there
 * by then, is not started once more. A line that is not bound (in proxy mode)
 * is not watched, and nothing is asked of the system for it.
 *
 * Two answers are not acted on. No default route at all: a line bound to the
 * old interface carries nothing either way, and one started now would be
 * bound to nothing, which beside the tun leads its sockets into the tun. When
 * a default route comes back on the same interface the line carries again as
 * it was, and when it comes back on another one, that is a move. And one
 * other answer between two of the same: a network that blinks while it
 * changes over has not moved.
 *
 * An interface is its name and, where the system gives one, its index. On
 * macOS the engine binds by the index it read when it started, so an
 * interface that went away and came back under the same name is another one.
 *
 * It runs between [start] and [stop], the session's Connected and its stop,
 * on a thread of its own; [tick] is one read.
 */
internal class PhysicalInterfaceWatch(
    private val onMove: (String) -> Unit,
    private val read: () -> String? = ::systemDefaultInterface,
    private val indexOf: (String) -> Int? = ::systemInterfaceIndex,
    private val intervalMs: Long = INTERVAL_MS,
) {
    /** An interface as the system names it, with its index when one was read. */
    internal data class WayOut(val name: String, val index: Int?) {
        /** The same interface: the same name, and the same index where both are known. */
        fun sameAs(other: WayOut): Boolean =
            name == other.name && (index == null || other.index == null || index == other.index)
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Between [start] and [stop]. */
    private var up = false

    /** What the session's line is bound to, or null when it is bound to nothing. */
    private var expected: WayOut? = null

    /** Another interface the system named, and how many reads in a row it has. */
    private var candidate: WayOut? = null
    private var seen = 0

    private var job: Job? = null

    /**
     * A line was started bound to [name], or to nothing when it is null. Said
     * before every start of the session's line, the first and every one after.
     */
    fun bound(name: String?) {
        val way = name?.let { WayOut(it, indexOf(it)) }
        synchronized(lock) {
            expected = way
            candidate = null
            seen = 0
        }
    }

    /** The session is up: the reads begin. A second call changes nothing. */
    fun start() {
        synchronized(lock) {
            up = true
            if (job?.isActive == true) return
            job = scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    try {
                        // A question that hangs is cut by [stop].
                        runInterruptible { tick() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A read that failed is a read missed; the next one asks again.
                    }
                }
            }
        }
    }

    /**
     * The session is stopped: the reads end, and what the line was bound to
     * is forgotten. It may come more than once, and before any [start].
     */
    fun stop() {
        val running = synchronized(lock) {
            up = false
            expected = null
            candidate = null
            seen = 0
            job.also { job = null }
        }
        running?.cancel()
    }

    /** One read: the system is asked, and a move it has held is told. */
    internal fun tick() {
        val from = synchronized(lock) { if (up) expected else null } ?: return
        val now = read()?.let { WayOut(it, indexOf(it)) }
        val moved = synchronized(lock) {
            // A line started while the system was asked, or the session ended:
            // the answer is to a question nobody asks any more.
            if (!up || expected != from) return
            if (now == null || now.sameAs(from)) {
                candidate = null
                seen = 0
                return
            }
            seen = if (candidate?.sameAs(now) == true) seen + 1 else 1
            candidate = now
            if (seen < SETTLE_READS) return
            // Told once. The start that follows says what it was bound to.
            expected = now
            candidate = null
            seen = 0
            now
        }
        onMove(moved.name)
    }

    companion object {
        /** How often the system is asked: a move is acted on two to four seconds after it. */
        const val INTERVAL_MS = 2_000L

        /** How many reads in a row another interface must be the answer. */
        const val SETTLE_READS = 2

        /**
         * [engine] with the watch: every line it starts that is asked to find
         * its way out is bound to the interface [read] names, the interface
         * the watch then holds the system to, and from the session's Connected
         * to its stop a move is told to [host]'s session. One reader for both,
         * so a line is never bound to one answer and watched against another.
         */
        fun around(
            engine: DesktopEngine,
            host: DesktopEngineHost,
            read: () -> String? = ::systemDefaultInterface,
            indexOf: (String) -> Int? = ::systemInterfaceIndex,
            intervalMs: Long = INTERVAL_MS,
        ): WatchedEngine {
            val watch = PhysicalInterfaceWatch(
                onMove = { name -> host.session.wayOutChanged(name) },
                read = read,
                indexOf = indexOf,
                intervalMs = intervalMs,
            )
            return WatchedEngine(engine, watch, read)
        }
    }
}

/**
 * An engine whose session's line is watched ([PhysicalInterfaceWatch]). It is
 * [inner] in everything else, call for call.
 */
internal class WatchedEngine(
    private val inner: DesktopEngine,
    val watch: PhysicalInterfaceWatch,
    read: () -> String?,
) : DesktopEngine by inner {
    override val line: LineEngine = WatchedLine(inner.line, watch, read)

    override fun sessionUp(mode: DesktopMode) {
        inner.sessionUp(mode)
        watch.start()
    }

    override fun sessionDown() {
        watch.stop()
        inner.sessionDown()
    }
}

/**
 * [inner], with every start told to the watch. A line asked to find its way
 * out by itself ([LineRequest.autoDetectInterface]) and given no interface is
 * given the one the watch reads; one given an interface keeps it.
 */
private class WatchedLine(
    private val inner: LineEngine,
    private val watch: PhysicalInterfaceWatch,
    private val read: () -> String?,
) : LineEngine by inner {
    override suspend fun start(request: LineRequest): LineStarted {
        val way = withContext(Dispatchers.IO) {
            val name = request.bindInterface ?: if (request.autoDetectInterface) read() else null
            watch.bound(name)
            name
        }
        val asked = if (way != null && request.bindInterface == null) request.boundTo(way) else request
        return inner.start(asked)
    }
}

/** [this] with its sockets bound to [name]. */
private fun LineRequest.boundTo(name: String) = LineRequest(
    spec = spec,
    kind = kind,
    port = port,
    routing = routing,
    verboseLogs = verboseLogs,
    login = login,
    serverResolver = serverResolver,
    autoDetectInterface = autoDetectInterface,
    bindInterface = name,
    anotherPort = anotherPort,
)

/**
 * The interface the default route leaves by, the tun's own aside, read as
 * the desktop reads it for a line beside the tun: `ip route` on Linux
 * ([DesktopDnsResolver.linuxDefaultInterface]), `route get` on macOS. None
 * elsewhere: Windows binds no line to an interface.
 */
private fun systemDefaultInterface(): String? = when (DesktopPaths.os) {
    DesktopOs.Linux -> DesktopDnsResolver.linuxDefaultInterface()
    DesktopOs.MacOS -> MacOsTunController.defaultInterfaceName()
    else -> null
}

/** [name]'s index, or null when the system gives none. */
private fun systemInterfaceIndex(name: String): Int? =
    runCatching { NetworkInterface.getByName(name)?.index }.getOrNull()?.takeIf { it > 0 }
