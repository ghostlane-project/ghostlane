package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportProbe

/**
 * What carries the lines of a desktop session.
 *
 * The manager decides what a session is: its mode, its endpoint, what it
 * holds (a tun or the system's proxy setting), when a line is restarted and
 * what the user is told. Everything a line runs to be that endpoint is asked
 * of an engine, so another engine is another implementation of this file and
 * not another manager.
 *
 * One engine belongs to one manager and lives as long as it does. Whatever it
 * started is stopped by the manager's own stop paths, through the same calls.
 */
internal interface DesktopEngine {
    /** The line of the session: a core line, or the front before a room. */
    val line: LineEngine

    /** The core that owns the Windows tun, apart from the session's line. */
    val windowsTun: TunCore

    /** Cores that try a line beside the session's and are thrown away. */
    val probes: ProbeEngine
}

/** What an engine is given by the manager it serves. */
internal class DesktopEngineHost(
    /** The app's log; what a line's processes print goes there. */
    val log: (String) -> Unit,
    /** Where the Windows tun's core is read: the manager looks for what the system refused. */
    val windowsTunOutput: (String) -> Unit
)

/** Which engine a manager gets. One place, so that changing it touches no manager. */
internal object DesktopEngines {
    @Volatile
    var factory: (DesktopEngineHost) -> DesktopEngine = { host -> GoEngines(host) }

    fun create(host: DesktopEngineHost): DesktopEngine = factory(host)
}

/**
 * A core line as the session's endpoint: what listens on [port] and demands
 * [login], and how it leaves the machine.
 */
internal class LineRequest(
    val spec: OutboundSpec,
    /** How the location names its kind, for the log. */
    val kind: String,
    val port: Int,
    val routing: Routing,
    val verboseLogs: Boolean,
    val login: SocksLogin?,
    val serverResolver: DirectDns?,
    val autoDetectInterface: Boolean,
    val bindInterface: String?,
    /**
     * A free loopback port that is not the one given. Asked only by an engine
     * whose line is more than one listener, and only when it is.
     */
    val anotherPort: (Int) -> Int
)

/** The front before a room: it listens on [port], demands [login] and hands on to the room's own port. */
internal class FrontRequest(
    val enginePort: Int,
    val engineUsername: String,
    val enginePassword: String,
    val port: Int,
    val routing: Routing.Rules,
    val verboseLogs: Boolean,
    val login: SocksLogin?
)

/** A line that was started. Started is not ready: the manager waits for the port and then asks [alive]. */
internal class LineStarted(
    /** What the log says of the start. */
    val said: String,
    /** Whether everything this start began still runs. */
    val alive: () -> Boolean,
    /** How the part that owns the port ended, or null while it runs. */
    val exitCode: () -> Int?
)

internal interface LineEngine {
    /** Starts the line; whatever of an earlier one runs is stopped by the caller first ([stopNow]). */
    suspend fun start(request: LineRequest): LineStarted

    /** Starts the front before a room that is already running. */
    suspend fun startFront(request: FrontRequest)

    /**
     * Whether everything runs that a line of this shape is made of. Asked for
     * a line that is not started yet, and kept by the session's watcher.
     */
    fun alive(spec: OutboundSpec, routing: Routing): () -> Boolean

    /** Whether the front before a room runs. */
    val frontAlive: () -> Boolean

    /** How the front ended, or null while it runs. */
    fun frontExitCode(): Int?

    /** Whether anything of a core line runs. */
    fun anyRunning(): Boolean

    /** How a dead part of the line ended, or null. */
    fun exitCodeOrNull(): Int?

    /** The line's own processes, for a tun that lets them out by their paths. */
    fun runningProcesses(): List<Process>

    /** Stops the line and the front, and returns when they are gone. Not a coroutine: stop paths are not. */
    fun stopNow()
}

/** The core of the Windows tun. */
internal interface TunCore {
    suspend fun start(config: String)
    fun isRunning(): Boolean
    fun runningProcess(): Process?
    fun stopNow()
}

internal interface ProbeEngine {
    /** A core of its own for every probe, started for it and stopped by it. */
    val fresh: TransportProbe.Starter

    /**
     * The core of a line a session probes while it looks for another one: a
     * candidate's own, beside the session's, which is not touched.
     *
     * Kept, where [fresh] makes one for every probe. A look tries one line at
     * a time, so one is enough, and a session that is stopped can then stop a
     * probe that is running with everything else it stops ([stopKept]). Left
     * to the probe's own coroutine, the core is stopped when that coroutine
     * next runs, and after Quit it never does: the process would outlive the
     * app.
     */
    val kept: TransportProbe.Starter

    fun stopKept()
}
