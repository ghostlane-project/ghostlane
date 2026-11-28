package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.CoreRouting
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.PhysicalInterfaceWatch
import org.olcbox.app.vpn.desktop.WatchedEngine
import org.olcbox.app.vpn.desktop.engine.host.HostEngine
import java.nio.file.Path

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

    /** A room: what is run for it. The manager runs it. */
    val room: RoomEngine

    /** Cores that try a line beside the session's and are thrown away. */
    val probes: ProbeEngine

    /**
     * The session's first line passed its check: the session is Connected in
     * [mode]. An engine that watches something for a session, the interface
     * the machine leaves by for one, begins here and reports through
     * [SessionHooks].
     */
    fun sessionUp(mode: DesktopMode) {}

    /**
     * The session is being stopped. It may come more than once, and with no
     * session up.
     */
    fun sessionDown() {}
}

/**
 * What an engine may ask of the session it carries. The answer to both is the
 * restart behind the tun: what the session holds, the tun or in proxy mode
 * the system's proxy setting, is not touched, and the line is started again
 * behind it for as long as the session lasts.
 *
 * May be called from any thread and at any time; a call with no session up,
 * or for a session that has ended since, does nothing.
 */
internal interface SessionHooks {
    /**
     * The engine learned by itself that the session's line is gone: its
     * program said so, or ended. [what] is the log's line. The manager also
     * asks [LineEngine.alive] on its own clock, so an engine that only
     * answers that question never needs this.
     */
    fun lineDied(what: String)

    /**
     * The interface the machine leaves by changed ([interfaceName], or null
     * when there is none now). Whatever of the line runs is stopped and
     * started again, bound to what is there now.
     */
    fun wayOutChanged(interfaceName: String?)
}

/** What an engine is given by the manager it serves. */
internal class DesktopEngineHost(
    /** The app's log; what a line's processes print goes there. */
    val log: (String) -> Unit,
    /** Where the Windows tun's core is read: the manager looks for what the system refused. */
    val windowsTunOutput: (String) -> Unit,
    val session: SessionHooks
)

/** Which engine a manager gets. One place, so that changing it touches no manager. */
internal object DesktopEngines {
    @Volatile
    var factory: (DesktopEngineHost) -> DesktopEngine = { host -> defaultFor(DesktopPaths.os, host) }

    /**
     * What a build runs on [os]: ghostlane-engine, whose packages carry no Go
     * core. On Linux and macOS it is watched for a change of the way out. On
     * Windows it is not wrapped in the watch: the watch reads the default
     * interface by `ip route` or `route get` and reads nothing on Windows
     * ([PhysicalInterfaceWatch]'s reader answers null there), so it would
     * report no move, ever. The manager reads the way out itself, before the
     * tun (`WindowsTunController.physicalRoute`), and hands its alias to every
     * line and room as the session's `bind` (IP_UNICAST_IF in the engine). A
     * watch on Windows is the interface-watch task's.
     */
    fun defaultFor(os: DesktopOs, host: DesktopEngineHost): DesktopEngine = when (os) {
        DesktopOs.Linux, DesktopOs.MacOS -> PhysicalInterfaceWatch.around(HostEngine(host), host)
        DesktopOs.Windows, DesktopOs.Other -> HostEngine(host)
    }

    /** Whether [engine] is ghostlane-engine's, watched or not. */
    fun runsOnRust(engine: DesktopEngine): Boolean =
        engine is HostEngine || (engine as? WatchedEngine)?.inner is HostEngine

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

    /** The programs a line may run, for the same tun when a process does not say its path. */
    fun binaries(): List<Path>

    /** Stops the line and the front, and returns when they are gone. Not a coroutine: stop paths are not. */
    fun stopNow()
}

/**
 * A room as the session's endpoint: the SOCKS listener it opens, with its
 * login, and what it is told about the machine.
 */
internal data class RoomRequest(
    val location: LocationConfig,
    val socksHost: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val dnsServer: String,
    val dtlsProfile: String,
    /** The device's identity, for an engine that names it to the carrier. */
    val deviceId: String = "",
    /**
     * The front that stands before the room, in an engine whose room carries
     * its own ([RoomEngine.frontInRoom]); null for a room reached directly.
     */
    val front: RoomFront? = null,
    /**
     * The room's sockets leave by the interface the machine leaves by: beside
     * the Linux tunnel and behind the macOS one, whose tun takes every route
     * they would otherwise follow.
     */
    val bindToWayOut: Boolean = false,
    /**
     * The interface they leave by, when the caller has read it already
     * ([PhysicalInterfaceWatch] does on Linux and macOS; the manager does on
     * Windows, where it is the adapter's alias read before the tun); null lets
     * the engine ask.
     */
    val bindInterface: String? = null
)

/** The front before a room, as the room's own process runs it: it listens on [port], demands [login] and applies [routing]. */
internal class RoomFront(
    val port: Int,
    val login: SocksLogin?,
    val routing: Routing.Rules,
    val verboseLogs: Boolean,
    /** The lists of a bypass's region, which are written into the front inline; null under any other policy. */
    val geodata: XrayGeodata.Lists? = null
) {
    companion object {
        /** A front with the lists [routing]'s policy needs, read here because reading them suspends. */
        suspend fun of(port: Int, login: SocksLogin?, routing: Routing.Rules, verboseLogs: Boolean): RoomFront {
            val effective = CoreRouting.effective(routing) as? Routing.RuleBased
            val lists = (effective?.policy as? Routing.Policy.Bypass)?.let { XrayGeodata.lists(it.region) }
            return RoomFront(port, login, routing, verboseLogs, lists)
        }
    }
}

/**
 * What is run for a room. The manager writes [config] into a file only the
 * user reads, runs [args] (itself, as root, or through the Mac's helper),
 * reads what the program prints and watches it; nothing secret is in [args].
 */
internal interface RoomLaunch {
    /** How the program names the carrier, for the log. */
    val provider: String

    /** The text of the file the program reads. */
    fun config(): String

    /** The command line: the program, then what it is given, with the file's path in it. */
    fun args(configPath: Path): List<String>

    /** [line], printed by the program, says the room's listener is open. */
    fun saysReady(line: String): Boolean

    /** [line] says this start has failed for good. */
    fun saysFailed(line: String): Boolean

    /** [line] as the log and a failure say it; what the program printed when it needs no reading. */
    fun said(line: String): String = line
}

internal interface RoomEngine {
    /** The programs that can carry a room, the preferred one first; the next is tried when one does not start. */
    fun binaries(): List<Path>

    fun launch(binary: Path, request: RoomRequest): RoomLaunch

    /**
     * Whether a room's program has to be root in the Linux tunnel, where only
     * root's traffic keeps the main table. False for one that binds its
     * sockets to the physical interface instead.
     */
    val rootBesideLinuxTun: Boolean

    /**
     * Whether a room's process also runs the front before it ([RoomRequest.front]),
     * so that nothing else is started for it ([LineEngine.startFront]).
     */
    val frontInRoom: Boolean get() = false
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
