package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopEngine
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.FrontRequest
import org.olcbox.app.vpn.desktop.engine.LineEngine
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.LineStarted
import org.olcbox.app.vpn.desktop.engine.ProbeEngine
import org.olcbox.app.vpn.desktop.engine.RoomEngine
import org.olcbox.app.vpn.desktop.engine.RoomLaunch
import org.olcbox.app.vpn.desktop.engine.RoomRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.engine.TunCore
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The physical interface watch on a stand-in system and a stand-in engine:
 * the default interface is what a test says it is, the line is a recorder,
 * and the session answers a move as the manager does
 * (`restartBehindTunOnNewWayOut`): what runs of the line is stopped and the
 * line is started again with the request it was started with. Nothing else
 * of the engine is touched, so nothing else of the session is: the tun stands.
 *
 * Each read is a [PhysicalInterfaceWatch.tick] the test makes; the reads the
 * watch makes by itself are an hour apart here, but in the one test of its
 * own clock. The real engine, a real tun and a moved default route are
 * PhysicalInterfaceWatchRealTest's, under scripts/desktop-checks/watch.sh.
 */
class PhysicalInterfaceWatchTest {
    private class RecordingLine : LineEngine {
        val started = mutableListOf<LineRequest>()
        val fronts = mutableListOf<FrontRequest>()
        var stops = 0

        /** What the manager's checks see: a start brings the line up, a stop takes it down. */
        @Volatile var aliveNow = false

        override suspend fun start(request: LineRequest): LineStarted {
            synchronized(started) { started += request }
            aliveNow = true
            return LineStarted(said = "a stand-in line", alive = { aliveNow }, exitCode = { null })
        }

        override suspend fun startFront(request: FrontRequest) {
            synchronized(fronts) { fronts += request }
        }

        override fun alive(spec: OutboundSpec, routing: Routing): () -> Boolean = { aliveNow }
        override val frontAlive: () -> Boolean = { false }
        override fun frontExitCode(): Int? = null
        override fun anyRunning(): Boolean = aliveNow
        override fun exitCodeOrNull(): Int? = null
        override fun runningProcesses(): List<Process> = emptyList()
        override fun binaries(): List<Path> = emptyList()

        override fun stopNow() {
            synchronized(started) { stops += 1 }
            aliveNow = false
        }

        fun lastBound(): String? = synchronized(started) { started.last().bindInterface }
    }

    /** A room the test launches through the engine, as the manager does. */
    private class RecordingRoom : RoomEngine {
        val launches = mutableListOf<RoomRequest>()

        override fun binaries(): List<Path> = emptyList()

        override fun launch(binary: Path, request: RoomRequest): RoomLaunch {
            synchronized(launches) { launches += request }
            return object : RoomLaunch {
                override val provider = "a stand-in room"
                override fun config() = "a stand-in room"
                override fun args(configPath: Path) = listOf("a stand-in room")
                override fun saysReady(line: String) = false
                override fun saysFailed(line: String) = false
            }
        }

        override val rootBesideLinuxTun = false
    }

    /** An engine of one line; its tun and probes are not to be touched. */
    private class StandIn(
        override val line: LineEngine,
        override val room: RoomEngine = RecordingRoom(),
    ) : DesktopEngine {
        val ups = mutableListOf<DesktopMode>()
        var downs = 0

        override val windowsTun: TunCore get() = error("the Windows tun is not the watch's")
        override val probes: ProbeEngine get() = error("a probe is not the watch's")

        override fun sessionUp(mode: DesktopMode) {
            ups += mode
        }

        override fun sessionDown() {
            downs += 1
        }
    }

    /** The system as a test sets it: the default interface, and each interface's index. */
    private class Machine {
        @Volatile var default: String? = "wlan0"
        val indexes = mutableMapOf("wlan0" to 3, "eth0" to 2)
        val reads = AtomicInteger()

        fun read(): String? {
            reads.incrementAndGet()
            return default
        }

        fun indexOf(name: String): Int? = synchronized(indexes) { indexes[name] }
    }

    /**
     * The manager's answer to a way-out change, as `restartBehindTunOnNewWayOut`
     * does it: one restart at a time, every tell dropped while it runs, and an
     * attempt that starts only what is dead. Its first check can be held by a
     * test, so the way out can change again before the attempt reads it.
     */
    private class RestartingManager(
        val line: RecordingLine,
        val start: (LineRequest) -> Unit,
    ) {
        val moves = mutableListOf<String?>()
        val restarting = AtomicBoolean(false)
        var heldCheck = false
        var checkFails = false
        var checks = 0
        var requestForAttempt: () -> LineRequest = { error("no attempt was expected") }

        fun wayOutChanged(interfaceName: String?) {
            synchronized(moves) { moves += interfaceName }
            if (!restarting.compareAndSet(false, true)) return
            if (heldCheck) return
            finishCheck()
        }

        /** The held first check ends; only a dead line is started by its attempt. */
        fun finishCheck(attemptRequest: () -> LineRequest = requestForAttempt) {
            heldCheck = false
            checks++
            if (!line.aliveNow) start(attemptRequest())
            if (!checkFails) restarting.set(false)
        }

        fun moved(): List<String?> = synchronized(moves) { moves.toList() }
    }

    private class Session(val system: Machine, intervalMs: Long = HOUR) {
        val line = RecordingLine()
        val inner = StandIn(line)
        val moves = mutableListOf<String?>()
        lateinit var request: LineRequest

        private val host = DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) {
                    error("no line dies in these tests")
                }

                // As the manager answers it: the line is stopped and started
                // again behind what the session holds.
                override fun wayOutChanged(interfaceName: String?) {
                    synchronized(moves) { moves += interfaceName }
                    runBlocking {
                        engine.line.stopNow()
                        engine.line.start(request)
                    }
                }
            },
        )

        val engine: WatchedEngine = PhysicalInterfaceWatch.around(
            inner,
            host,
            read = system::read,
            indexOf = system::indexOf,
            intervalMs = intervalMs,
        )

        fun connect(request: LineRequest, mode: DesktopMode = DesktopMode.LinuxTun) {
            this.request = request
            runBlocking { engine.line.start(request) }
            engine.sessionUp(mode)
        }

        fun reads(n: Int) = repeat(n) { engine.watch.tick() }

        fun moved(): List<String?> = synchronized(moves) { moves.toList() }
    }

    private fun request(autoDetect: Boolean = true, bind: String? = null) = LineRequest(
        spec = requireNotNull(LinkParser.parse(LINK)) { "the test's link does not parse" },
        kind = "Vless",
        port = 10808,
        routing = Routing.Global,
        verboseLogs = false,
        login = SocksLogin("desktop-user", "desktop-pass"),
        serverResolver = null,
        autoDetectInterface = autoDetect,
        bindInterface = bind,
        anotherPort = { error("a line of one process asks for no second port") },
    )

    private fun roomRequest() = RoomRequest(
        location = LocationConfig(),
        socksHost = "127.0.0.1",
        socksPort = 10808,
        socksUser = "desktop-user",
        socksPass = "desktop-pass",
        dnsServer = "127.0.0.1",
        directRulesFile = null,
        dtlsProfile = "off",
    )

    private fun frontRequest() = FrontRequest(
        enginePort = 10808,
        engineUsername = "desktop-user",
        enginePassword = "desktop-pass",
        port = 10809,
        routing = Routing.Rules("rules", DirectDns.System, Routing.Policy.Tunnel),
        verboseLogs = false,
        login = SocksLogin("desktop-user", "desktop-pass"),
    )

    /** The row's case: the default interface changes, and the line is started again bound to the new one. */
    @Test
    fun aMoveTheSystemHoldsStartsTheLineAgainBoundToTheNewInterface() {
        val session = Session(Machine())
        session.connect(request())
        assertEquals("wlan0", session.line.lastBound(), "a line beside the tun is bound to the default interface")
        assertEquals(listOf(DesktopMode.LinuxTun), session.inner.ups, "the engine is told the session is up")

        session.system.default = "eth0"
        session.reads(1)
        assertEquals(emptyList(), session.moved(), "one read is not yet a move")
        session.reads(1)
        assertEquals(listOf<String?>("eth0"), session.moved(), "a move held for two reads is told to the session")
        assertEquals(
            2,
            session.line.stops,
            "the watch stopped what ran before the tell, and the restart stopped it again",
        )
        assertEquals(2, session.line.started.size, "and the line started again")
        assertEquals("eth0", session.line.lastBound(), "bound to the interface there now")

        session.reads(5)
        assertEquals(listOf<String?>("eth0"), session.moved(), "a move is told once")
        assertEquals(2, session.line.started.size)

        session.engine.sessionDown()
        assertEquals(1, session.inner.downs, "the engine is told the session is stopped")
    }

    /** A session whose manager drops every tell while one restart runs. */
    private class ManagedSession(val system: Machine, val lineRequest: LineRequest) {
        val line = RecordingLine()
        val manager = RestartingManager(line, { runBlocking { engine.line.start(it) } })
        val engine: WatchedEngine

        init {
            val host = DesktopEngineHost(
                log = {},
                windowsTunOutput = {},
                session = object : SessionHooks {
                    override fun lineDied(what: String) {
                        error("no line dies in these tests")
                    }

                    override fun wayOutChanged(interfaceName: String?) = manager.wayOutChanged(interfaceName)
                },
            )
            engine = PhysicalInterfaceWatch.around(
                StandIn(line),
                host,
                read = system::read,
                indexOf = system::indexOf,
                intervalMs = HOUR,
            )
        }

        fun connect() {
            runBlocking { engine.line.start(lineRequest) }
            engine.sessionUp(DesktopMode.LinuxTun)
            assertEquals("wlan0", line.lastBound())
        }

        fun reads(n: Int) = repeat(n) { engine.watch.tick() }
    }

    /**
     * A tell dropped while the restart's first check runs, then Wi-Fi going
     * off: the attempt starts the dead line with no default route. The watch
     * must keep watching that line and tell when the way out comes back.
     */
    @Test
    fun aTellDroppedDuringTheFirstCheckIsFollowedWhenTheWayOutComesBack() {
        val system = Machine()
        val session = ManagedSession(system, request())
        session.connect()

        session.manager.heldCheck = true
        system.default = "eth0"
        session.reads(2)
        assertEquals(listOf<String?>("eth0"), session.manager.moved(), "the first tell set the restart running")
        session.reads(2)
        assertEquals(
            listOf<String?>("eth0", "eth0"),
            session.manager.moved(),
            "every tell while the restart runs is dropped",
        )
        assertEquals("wlan0", session.line.lastBound(), "no start has bound the line anew")

        system.default = null
        session.manager.finishCheck { request() }
        assertEquals(1, session.manager.checks, "the held first check ran")
        assertEquals(null, session.line.lastBound(), "the attempt started the dead line with no default route")

        system.default = "eth0"
        session.reads(2)
        assertEquals(
            listOf<String?>("eth0", "eth0", "eth0"),
            session.manager.moved(),
            "the watch tells the way out that came back",
        )
        assertEquals("eth0", session.line.lastBound(), "the restart's attempt bound the dead line to it")
        val tells = session.manager.moved().size
        val stops = session.line.stops
        session.reads(6)
        assertEquals(tells, session.manager.moved().size, "no move is told once the line is bound there")
        assertEquals(stops, session.line.stops, "and nothing of the line is stopped again")
    }

    /**
     * A restart bound to an interface that never passes its check: the manager
     * goes on dropping tells. A start bound to the interface there now ends it.
     */
    @Test
    fun aRestartBoundToAnInterfaceThatFailsItsCheckStopsTellingAfterABind() {
        val system = Machine()
        val session = ManagedSession(system, request())
        session.connect()
        session.manager.checkFails = true
        session.manager.requestForAttempt = { request(bind = "wlan1") }

        system.default = "eth0"
        session.reads(2)
        assertEquals(listOf<String?>("eth0"), session.manager.moved(), "the manager began one restart")
        assertEquals("wlan1", session.line.lastBound(), "its attempt bound an interface that never passes its check")
        session.reads(2)
        assertEquals(listOf<String?>("eth0", "eth0"), session.manager.moved(), "the later tell was dropped")
        assertEquals(2, session.line.stops, "the watch stopped what ran before each tell")

        // A start that binds the line to the way out there now ends the telling.
        session.engine.watch.bound("eth0")
        val tells = session.manager.moved().size
        val stops = session.line.stops
        session.reads(6)
        assertEquals(tells, session.manager.moved().size, "no further tell follows a start bound to eth0")
        assertEquals(stops, session.line.stops, "no further stop follows it either")
        runBlocking { session.engine.line.start(request(bind = "eth0")) }
        assertTrue(session.line.aliveNow, "the line started again is alive")
    }

    @Test
    fun aNetworkThatBlinksHasNotMoved() {
        val session = Session(Machine())
        session.connect(request())
        for (answer in listOf("eth0", "wlan0", "eth0", "wlan0", "eth0", "wlan0")) {
            session.system.default = answer
            session.reads(1)
        }
        assertEquals(emptyList(), session.moved(), "another interface for one read at a time is no move")
        assertEquals(1, session.line.started.size)
    }

    /**
     * The user changes a session to a room behind the standing tun. The room
     * does not bind sockets to the way out, so the watch must not go on
     * comparing the system to the core line that was there before: that
     * would restart the room every two reads.
     */
    @Test
    fun aRoomLaunchedInASessionIsNotWatchedAgainstTheOldLine() {
        val system = Machine()
        val line = RecordingLine()
        val room = RecordingRoom()
        val moves = mutableListOf<String?>()
        val host = DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) = error("no line dies in this test")
                override fun wayOutChanged(interfaceName: String?) {
                    synchronized(moves) { moves += interfaceName }
                }
            },
        )
        val engine = PhysicalInterfaceWatch.around(
            StandIn(line, room),
            host,
            read = system::read,
            indexOf = system::indexOf,
            intervalMs = HOUR,
        )
        runBlocking { engine.line.start(request()) }
        engine.sessionUp(DesktopMode.LinuxTun)
        assertEquals("wlan0", line.lastBound())

        runBlocking { engine.room.launch(Path.of("room"), roomRequest()) }
        assertEquals(1, room.launches.size, "the room was launched")
        assertEquals(1, system.reads.get(), "the old core line was bound by one read")

        system.default = "eth0"
        repeat(6) { engine.watch.tick() }
        assertEquals(emptyList(), synchronized(moves) { moves.toList() }, "the room is not the old core line bound to wlan0")
        assertEquals(1, system.reads.get(), "nothing is asked of the system for the room")
    }

    /** The front before a room is the session's line too, and reports that it binds to nothing. */
    @Test
    fun aFrontStartedInASessionIsNotWatchedAgainstTheOldLine() {
        val system = Machine()
        val line = RecordingLine()
        val moves = mutableListOf<String?>()
        val host = DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) = error("no line dies in this test")
                override fun wayOutChanged(interfaceName: String?) {
                    synchronized(moves) { moves += interfaceName }
                }
            },
        )
        val engine = PhysicalInterfaceWatch.around(
            StandIn(line),
            host,
            read = system::read,
            indexOf = system::indexOf,
            intervalMs = HOUR,
        )
        runBlocking { engine.line.start(request()) }
        engine.sessionUp(DesktopMode.LinuxTun)
        assertEquals("wlan0", line.lastBound())

        runBlocking { engine.line.startFront(frontRequest()) }
        assertEquals(1, synchronized(line.fronts) { line.fronts.size }, "the front was started")
        system.default = "eth0"
        repeat(6) { engine.watch.tick() }
        assertEquals(emptyList(), synchronized(moves) { moves.toList() }, "the front is not the old core line bound to wlan0")
        assertEquals(1, system.reads.get(), "nothing is asked of the system for the front")
    }

    /**
     * A line that asked to find its way out while there was no default route
     * has no binding yet. It is still watched: an interface that has been the
     * answer for two reads is told to the session.
     */
    @Test
    fun aLineStartedWithNoDefaultRouteIsWatchedUntilOneComesBack() {
        val system = Machine()
        system.default = null
        val session = Session(system)
        session.connect(request())
        assertEquals(null, session.line.lastBound(), "the line was bound to nothing")
        session.reads(2)
        assertEquals(emptyList(), session.moved(), "there is still no way out")

        system.default = "eth0"
        session.reads(1)
        assertEquals(emptyList(), session.moved(), "one read is not a move")
        session.reads(1)
        assertEquals(listOf<String?>("eth0"), session.moved(), "a way out that came back is told")
        assertEquals("eth0", session.line.lastBound(), "the line started again bound to it")

        session.reads(5)
        assertEquals(listOf<String?>("eth0"), session.moved(), "and told no more")
        assertEquals(2, session.line.started.size)
    }

    /** A line that asked for no binding stays unwatched even with no default route. */
    @Test
    fun aProxyLineWithNoDefaultRouteIsNotWatched() {
        val system = Machine()
        system.default = null
        val session = Session(system)
        session.connect(request(autoDetect = false), DesktopMode.SystemProxy)
        system.default = "eth0"
        session.reads(4)
        assertEquals(emptyList(), session.moved())
        assertEquals(0, system.reads.get(), "nothing is asked of the system for a line that binds to nothing")
    }

    @Test
    fun noDefaultRouteIsNoMoveAndItsReturnElsewhereIs() {
        val session = Session(Machine())
        session.connect(request())
        session.system.default = null
        session.reads(4)
        assertEquals(emptyList(), session.moved(), "no way out at all moves a line bound to wlan0")
        session.system.default = "wlan0"
        session.reads(3)
        assertEquals(emptyList(), session.moved(), "back on the same interface, the line carries as it was")
        session.system.default = null
        session.reads(2)
        session.system.default = "eth0"
        session.reads(2)
        assertEquals(listOf<String?>("eth0"), session.moved(), "back on another interface is a move")
        assertEquals("eth0", session.line.lastBound())
    }

    @Test
    fun aLineThatIsNotBoundIsNotWatched() {
        val system = Machine()
        val session = Session(system)
        session.connect(request(autoDetect = false), DesktopMode.SystemProxy)
        assertEquals(null, session.line.lastBound(), "a line in proxy mode is bound to nothing")
        system.default = "eth0"
        session.reads(4)
        assertEquals(emptyList(), session.moved())
        assertEquals(0, system.reads.get(), "nothing is asked of the system for a line that is not bound")
    }

    /** The XHTTP line beside the Linux tun is given its interface by the manager; the watch holds the system to that one. */
    @Test
    fun anInterfaceTheRequestNamesIsKeptAndWatched() {
        val system = Machine()
        val session = Session(system)
        session.connect(request(bind = "wlan0"))
        assertEquals("wlan0", session.line.lastBound())
        assertEquals(0, system.reads.get(), "the line was not given another interface than the one it named")
        system.default = "eth0"
        session.reads(2)
        assertEquals(listOf<String?>("eth0"), session.moved())
    }

    @Test
    fun nothingIsToldOutsideASession() {
        val system = Machine()
        val session = Session(system)
        session.request = request()
        runBlocking { session.engine.line.start(session.request) }
        system.default = "eth0"
        session.reads(3)
        assertEquals(emptyList(), session.moved(), "a line before the session is Connected is the connect's to retry")

        system.default = "wlan0"
        session.engine.sessionUp(DesktopMode.LinuxTun)
        session.engine.sessionDown()
        session.engine.sessionDown()
        system.default = "eth0"
        session.reads(3)
        assertEquals(emptyList(), session.moved(), "a stopped session is told nothing")
        assertEquals(2, session.inner.downs, "each stop reaches the engine")
    }

    /** macOS binds by the index read at the start: the same name with another index is another interface. */
    @Test
    fun theSameNameWithAnotherIndexIsAMove() {
        val system = Machine()
        val session = Session(system)
        session.connect(request())
        synchronized(system.indexes) { system.indexes["wlan0"] = 9 }
        session.reads(2)
        assertEquals(listOf<String?>("wlan0"), session.moved(), "an interface that came back under its name is another one")
    }

    @Test
    fun aLineStartedAgainMeanwhileIsNotStartedOnceMore() {
        val system = Machine()
        val session = Session(system)
        session.connect(request())
        system.default = "eth0"
        session.reads(1)
        // The line died and was started again behind the tun, bound to what is there now.
        session.engine.line.stopNow()
        runBlocking { session.engine.line.start(session.request) }
        assertEquals("eth0", session.line.lastBound())
        session.reads(4)
        assertEquals(emptyList(), session.moved(), "the line is already bound to the interface there")
    }

    /** The watch's own clock: it reads by itself while the session is up, outlives a read that fails, and ends with the session. */
    @Test
    fun theWatchReadsByItselfUntilTheSessionStops() {
        val system = object : Any() {
            @Volatile var default: String? = "wlan0"
            val reads = AtomicInteger()
        }
        val failed = AtomicInteger()
        val moves = mutableListOf<String?>()
        val line = RecordingLine()
        val inner = StandIn(line)
        lateinit var engine: WatchedEngine
        val request = request()
        val host = DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) {
                    error("no line dies in this test")
                }
                override fun wayOutChanged(interfaceName: String?) {
                    runBlocking {
                        engine.line.stopNow()
                        engine.line.start(request)
                    }
                    // After the start: what the test waits for is the line started again.
                    synchronized(moves) { moves += interfaceName }
                }
            },
        )
        engine = PhysicalInterfaceWatch.around(
            inner,
            host,
            read = {
                system.reads.incrementAndGet()
                if (system.default == "eth0" && failed.getAndIncrement() == 0) error("the system did not answer")
                system.default
            },
            indexOf = { null },
            intervalMs = 20,
        )
        runBlocking { engine.line.start(request) }
        engine.sessionUp(DesktopMode.LinuxTun)
        system.default = "eth0"
        val deadline = System.currentTimeMillis() + 10_000
        while (synchronized(moves) { moves.isEmpty() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(listOf<String?>("eth0"), synchronized(moves) { moves.toList() }, "the watch told the move by itself")
        assertTrue(failed.get() >= 1, "a read that failed did not end the watch")
        assertEquals("eth0", line.lastBound())

        engine.sessionDown()
        Thread.sleep(100)
        val after = system.reads.get()
        Thread.sleep(300)
        assertEquals(after, system.reads.get(), "the reads ended with the session")
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val LINK = "vless://00010203-0405-0607-0809-0a0b0c0d0e0f@192.0.2.10:443?security=none&encryption=none&type=tcp#watch"
    }
}
