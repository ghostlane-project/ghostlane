package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
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
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.engine.TunCore
import java.nio.file.Path
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
        var stops = 0

        override suspend fun start(request: LineRequest): LineStarted {
            synchronized(started) { started += request }
            return LineStarted(said = "a stand-in line", alive = { true }, exitCode = { null })
        }

        override suspend fun startFront(request: FrontRequest) {
            error("no room in these tests")
        }

        override fun alive(spec: OutboundSpec, routing: Routing): () -> Boolean = { true }
        override val frontAlive: () -> Boolean = { false }
        override fun frontExitCode(): Int? = null
        override fun anyRunning(): Boolean = synchronized(started) { started.isNotEmpty() }
        override fun exitCodeOrNull(): Int? = null
        override fun runningProcesses(): List<Process> = emptyList()
        override fun binaries(): List<Path> = emptyList()

        override fun stopNow() {
            synchronized(started) { stops += 1 }
        }

        fun lastBound(): String? = synchronized(started) { started.last().bindInterface }
    }

    /** An engine of one line; its tun, rooms and probes are not to be touched. */
    private class StandIn(override val line: LineEngine) : DesktopEngine {
        val ups = mutableListOf<DesktopMode>()
        var downs = 0

        override val windowsTun: TunCore get() = error("the Windows tun is not the watch's")
        override val room: RoomEngine get() = error("a room is not the watch's")
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
        assertTrue(session.line.stops >= 1, "what ran of the line was stopped before the tell")
        assertEquals(2, session.line.started.size, "and the line started again")
        assertEquals("eth0", session.line.lastBound(), "bound to the interface there now")

        session.reads(5)
        assertEquals(listOf<String?>("eth0"), session.moved(), "a move is told once")
        assertEquals(2, session.line.started.size)

        session.engine.sessionDown()
        assertEquals(1, session.inner.downs, "the engine is told the session is stopped")
    }

    /**
     * The manager drops a tell while a restart is running
     * (`restartBehindTunOnNewWayOut`), and an attempt that runs only starts
     * what is dead. The line then stays bound to the
     * interface that went away. The watch must tell again until a start binds
     * it to the one there now, and stop what runs before each tell so a
     * running attempt sees the core dead.
     */
    @Test
    fun aDroppedMoveIsToldAgainAndTheLineStartsOnTheNewInterface() {
        val system = Machine()
        val line = RecordingLine()
        val inner = StandIn(line)
        val moves = mutableListOf<String?>()
        val dropped = AtomicInteger(1)
        lateinit var lineRequest: LineRequest
        lateinit var engine: WatchedEngine
        val host = DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) {
                    error("no line dies in this test")
                }

                // As the manager's hook while a restart runs: the first tell
                // is dropped, and the line is left where it is.
                override fun wayOutChanged(interfaceName: String?) {
                    synchronized(moves) { moves += interfaceName }
                    if (dropped.getAndDecrement() > 0) return
                    runBlocking {
                        engine.line.stopNow()
                        engine.line.start(lineRequest)
                    }
                }
            },
        )
        lineRequest = request()
        engine = PhysicalInterfaceWatch.around(
            inner,
            host,
            read = system::read,
            indexOf = system::indexOf,
            intervalMs = HOUR,
        )
        runBlocking { engine.line.start(lineRequest) }
        engine.sessionUp(DesktopMode.LinuxTun)
        assertEquals("wlan0", line.lastBound())

        system.default = "eth0"
        repeat(PhysicalInterfaceWatch.SETTLE_READS) { engine.watch.tick() }
        assertEquals(listOf<String?>("eth0"), synchronized(moves) { moves.toList() }, "the manager dropped the first tell")
        assertEquals("wlan0", line.lastBound(), "the line is still bound to the interface that went away")
        assertTrue(line.stops >= 1, "what ran of the line was stopped before the watch told")

        repeat(PhysicalInterfaceWatch.SETTLE_READS) { engine.watch.tick() }
        assertEquals(
            listOf<String?>("eth0", "eth0"),
            synchronized(moves) { moves.toList() },
            "the watch tells again while the line is bound to the old interface",
        )
        assertEquals("eth0", line.lastBound(), "the line started again bound to the interface there now")
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

    @Test
    fun noDefaultRouteIsNoMoveAndItsReturnElsewhereIs() {
        val session = Session(Machine())
        session.connect(request())
        session.system.default = null
        session.reads(4)
        assertEquals(emptyList(), session.moved(), "a line started with no default route would be bound to nothing")
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
