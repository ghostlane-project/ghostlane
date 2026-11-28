package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsTunControllerTest {

    @Test fun readsTheInterfaceBehindTheDefaultRoute() {
        assertEquals("en0", MacOsTunController.defaultInterfaceName(routeOutput))
    }

    @Test fun aTunIsNotAnAnswer() {
        // The tunnel of the previous session, still up: binding direct traffic to
        // it would send that traffic into the tunnel being replaced.
        assertNull(MacOsTunController.defaultInterfaceName(routeOutput.replace("en0", "utun7")))
    }

    @Test fun noRouteMeansNoName() {
        assertNull(MacOsTunController.defaultInterfaceName("route: writing to routing socket: not in table"))
        assertNull(MacOsTunController.defaultInterfaceName(""))
    }

    // What a helper of each build says of itself. The number is its protocol.
    private val idle = """{"ok":true,"state":"idle","logTail":"","protocol":2}"""
    private val running = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":2}"""
    private val idle3 = """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":false}"""

    /** The helper the app ships now: it runs hev from a `tun` request (protocol 5). */
    private val idleHeld5 = """{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":true}"""
    private val idle5 = """{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":false}"""
    private val runningHeld = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":4,"hold":true}"""
    private val idleHeld = """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":true}"""

    /** The helper of the build before lines: it holds a block, and cannot run a room's engine. */
    private val idleHeldBeforeLines = """{"ok":true,"state":"idle","logTail":"","protocol":3,"hold":true}"""
    private val steppingAside =
        """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","protocol":4,"hold":false,"line":0}"""

    private fun FakeDaemon.request(index: Int): JsonObject = Json.parseToJsonElement(requests[index].trim()).jsonObject

    @Test fun aBlockLeftByAPreviousRunIsHeld() = withFakeDaemon(listOf(idleHeld)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        val left = runBlocking { controller.findLeftover() }
        assertEquals(MacOsTunController.Leftover(tunnelRuns = false, blockStands = true), left)
        assertTrue(controller.holdsTraffic)
        // Until the user says otherwise.
        controller.endHold()
        assertFalse(controller.holdsTraffic)
    }

    @Test fun aTunnelLeftRunningBehindItsBlockIsStoppedAndTheBlockKept() = withFakeDaemon(
        listOf(runningHeld, idleHeld)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        val left = runBlocking { controller.findLeftover() }
        assertEquals(MacOsTunController.Leftover(tunnelRuns = true, blockStands = true), left)
        runBlocking { controller.stop() }
        assertEquals("keep", daemon.request(1)["hold"]!!.jsonPrimitive.content)
        assertTrue(controller.holdsTraffic)
    }

    @Test fun aHelpersWordThatTheBlockIsGoneEndsTheHold() = withFakeDaemon(listOf(idleHeld, idle3)) { daemon ->
        // The Mac was restarted, or somebody flushed the rules by hand.
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        runBlocking { controller.findLeftover() }
        assertTrue(controller.holdsTraffic)
        runBlocking { controller.stop() }
        assertFalse(controller.holdsTraffic)
    }

    // A connect asks the helper before it chooses its order: behind a block
    // that stands the tunnel has to come first, and a helper that can hold
    // none is not asked for one.
    @Test fun aConnectLearnsWhetherABlockStandsAndWhetherTheHelperCanHoldOne() {
        withFakeDaemon(listOf(idleHeld5)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
            assertEquals(
                MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
                runBlocking { controller.helperNow() }
            )
            assertTrue(controller.blockStands)
        }
        // A helper from before the block that is the one on disk as well: it
        // is told to stop, says it has, and can hold nothing.
        withFakeDaemon(listOf(idle, idle)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
            assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { controller.helperNow() })
            assertEquals(listOf("status", "stop"), daemon.verbs)
        }
        // No helper at all: nothing stands, and nothing can be asked for.
        val absent = MacOsTunController(
            {}, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() }
        )
        assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { absent.helperNow() })
    }

    // A helper from the build before hev cannot be asked for an engine's
    // tunnel: it knows no `tun`. It makes way for the
    // one on disk before a connect chooses what it can be asked for. Its
    // block is the kernel's and outlives the process, so the stop keeps it.
    @Test fun aHelperFromBeforeHevMakesWayForTheCurrentOne() = withFakeDaemon(
        listOf(
            idleHeld,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","hold":true}""",
            idle5,
            idleHeld5
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() })
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
            runBlocking { controller.helperNow() }
        )
        assertEquals(listOf("status", "stop", "status", "status"), daemon.verbs)
        assertEquals("\"keep\"", daemon.request(1)["hold"].toString())
        assertTrue(log.any { "the tunnel helper was updated" in it }, log.toString())
    }

    // The first connect after an update of the app: the previous build's
    // helper is still the one running. It makes way when it is told to stop,
    // and the connect then chooses its order by what the new one can do.
    @Test fun aHelperLeftFromBeforeAnUpdateMakesWayBeforeTheConnectChoosesItsOrder() = withFakeDaemon(
        listOf(
            idle,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}""",
            idle5,
            idleHeld5
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() })
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
            runBlocking { controller.helperNow() }
        )
        assertEquals(listOf("status", "stop", "status", "status"), daemon.verbs)
        assertTrue(log.any { "the tunnel helper was updated" in it }, log.toString())
    }

    // The helper of the build before this one holds a block and cannot run a
    // room's engine. It makes way like any older one, and its block is not
    // taken away to find that out: the stop asks for it to be kept.
    @Test fun aHelperFromBeforeLinesMakesWayAndKeepsItsBlock() = withFakeDaemon(
        listOf(
            idleHeldBeforeLines,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","hold":true}""",
            idleHeld5,
            idleHeld5
        )
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
            runBlocking { controller.helperNow() }
        )
        assertEquals("\"keep\"", daemon.request(1)["hold"].toString())
        assertTrue(controller.runsLineUnder(killSwitch = false), "a block that stands is reason enough")
    }

    // And when the one on disk is no newer, it is what there is: it holds, and
    // a room's engine is not its to run.
    @Test fun aHelperThatCannotRunALineIsNotAskedTo() = withFakeDaemon(
        listOf(
            idleHeldBeforeLines,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","hold":true}""",
            idleHeldBeforeLines,
            idleHeldBeforeLines
        )
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = false),
            runBlocking { controller.helperNow() }
        )
        assertFalse(controller.runsLineUnder(killSwitch = true))
    }

    // A room's engine is the helper's to run under the switch, and where its
    // block stands; with neither it is the app's own child, as ever.
    @Test fun aRoomsEngineIsTheHelpersUnderTheSwitchAndBehindABlock() {
        withFakeDaemon(listOf(idle5)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
            assertFalse(controller.runsLineUnder(killSwitch = true), "not before the helper has been asked")
            runBlocking { controller.helperNow() }
            assertTrue(controller.runsLineUnder(killSwitch = true))
            assertFalse(controller.runsLineUnder(killSwitch = false))
        }
        withFakeDaemon(listOf(idleHeld5)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
            runBlocking { controller.helperNow() }
            assertTrue(controller.runsLineUnder(killSwitch = false))
        }
    }

    // The helper runs the engine and says which process it is; the app holds
    // it as it holds any process, and reads what it prints from its own file.
    @Test fun aLineTheHelperRunsIsHeldAsAProcessAndReadFromTheAppsFile() {
        val child = ProcessBuilder("sleep", "30").start()
        val said = Files.createTempFile("line-said", ".out")
        try {
            withFakeDaemon(
                listOf("""{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":true,"line":${child.pid()},"program":"/x/olcrtc"}""")
            ) { daemon ->
                val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
                val line = runBlocking { controller.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), listOf("/tmp/c.yaml"), said) }
                val asked = daemon.request(0)
                assertEquals("\"line\"", asked["verb"].toString())
                assertEquals("\"/Users/a/bin/olcrtc-darwin-arm64\"", asked["program"].toString())
                assertEquals("[\"/tmp/c.yaml\"]", asked["arguments"].toString())
                assertEquals("\"$said\"", asked["output"].toString())
                assertEquals(child.pid(), line.pid())
                assertTrue(line.isAlive)
                Files.writeString(said, "SOCKS5 server listening on 127.0.0.1:10808\n")
                assertEquals("SOCKS5 server listening on 127.0.0.1:10808", line.inputStream.bufferedReader().readLine())
                line.destroy()
                assertEquals(HelperLine.EXIT_NOT_KNOWN, line.waitFor())
                assertFalse(line.isAlive)
            }
        } finally {
            child.destroyForcibly()
            Files.deleteIfExists(said)
        }
    }

    @Test fun aLineTheHelperWillNotRunIsAFailureThatSaysWhy() = withFakeDaemon(
        listOf("""{"ok":false,"error":"olcrtc-darwin-arm64 is not signed by this team","logTail":"","protocol":4}""")
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { controller.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), emptyList(), Path.of("/tmp/none")) }
        }
        assertTrue("not signed by this team" in failure.message.orEmpty(), failure.message)
    }

    // The first connect to a room after an update of the app, when the build
    // before it could run a line as well: nothing has told the old helper to
    // make way by the time it is asked for the line (helperNow tells only one
    // that cannot run a line), so it makes way then. On 1.0.464 that was the
    // end of the connect, and the first thing an updated app said. The line is
    // asked of the helper that comes up.
    @Test fun aLineAskedOfAHelperThatStepsAsideIsAskedOfTheOneThatComesUp() {
        val child = ProcessBuilder("sleep", "30").start()
        val said = Files.createTempFile("line-said", ".out")
        try {
            withFakeDaemon(
                listOf(
                    steppingAside,
                    idle3,
                    """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":false,"line":${child.pid()},"program":"/x/olcrtc"}"""
                )
            ) { daemon ->
                val log = mutableListOf<String>()
                val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() })
                val line = runBlocking {
                    controller.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), listOf("/tmp/c.yaml"), said)
                }
                assertEquals(listOf("line", "status", "line"), daemon.verbs)
                assertEquals(daemon.requests[0], daemon.requests[2], "the same line is asked for again")
                assertEquals(child.pid(), line.pid())
                assertTrue(log.any { "the tunnel helper was updated" in it }, log.toString())
            }
        } finally {
            child.destroyForcibly()
            Files.deleteIfExists(said)
        }
    }

    // A helper that came up unable to run a line is not asked for one: what
    // it would do with a verb it does not know is its own to say, and it
    // would say it about a room that was never going to be joined.
    @Test fun aLineIsNotAskedOfAHelperThatCameUpUnableToRunOne() = withFakeDaemon(
        listOf(steppingAside, idleHeldBeforeLines)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { controller.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), emptyList(), Path.of("/tmp/none")) }
        }
        assertEquals(listOf("line", "status"), daemon.verbs)
        assertTrue("cannot run a line" in failure.message.orEmpty(), failure.message)
    }

    @Test fun theHelpersCopyOfALinesProgramIsNamedByItsFile() {
        val controller = MacOsTunController({}, TunnelDaemonClient(Path.of("/var/run/none")), { emptyList() })
        assertEquals(
            "/Library/Application Support/org.olcbox.app.lines/olcrtc-darwin-arm64",
            controller.lineProgram(Path.of("/Users/a/Library/Application Support/Ghostlane/bin/olcrtc-darwin-arm64"))
        )
    }

    // A line's program left running by a run of the app that is gone is ended: nobody reads it.
    @Test fun aLineLeftByAnAppThatIsGoneIsEnded() = withFakeDaemon(
        listOf("""{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":false,"line":4711}""", idle3)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        runBlocking { controller.findLeftover() }
        assertEquals(2, daemon.requests.size)
        assertEquals("\"line-stop\"", daemon.request(1)["verb"].toString())
    }

    // A helper that is carrying a tunnel is not told to stop to find out how old it is.
    @Test fun aHelperFromBeforeThatIsCarryingATunnelIsLeftCarryingIt() = withFakeDaemon(listOf(running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() })
        assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { controller.helperNow() })
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun aLineBehindATunWaitsUntilTheTunNamesPrograms() {
        var asked = 0
        val controller = MacOsTunController(
            {}, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() },
            askTun = { name ->
                assertEquals(DesktopTunNames.NAMES_PROGRAMS_PROBE, name)
                if (++asked < 3) emptyList() else listOf(DesktopTunNames.NAMES_PROGRAMS_ANSWER)
            }
        )
        assertTrue(runBlocking { controller.awaitNamesPrograms(timeoutMs = 5_000) })
        assertEquals(3, asked)
    }

    @Test fun aTunThatNeverNamesAProgramIsNotWaitedOnForEver() {
        val log = mutableListOf<String>()
        val controller = MacOsTunController(
            log::add, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() },
            askTun = { emptyList() }
        )
        assertFalse(runBlocking { controller.awaitNamesPrograms(timeoutMs = 400) })
        assertTrue(log.any { "starting the line anyway" in it }, log.toString())
    }

    @Test fun theTunsNameIsOneNoInterfaceHas() {
        assertEquals("utun225", MacOsTunController.freeTunName(setOf("lo0", "en0", "utun0", "utun3")))
        // The tunnel of the session before can still be closing.
        assertEquals("utun227", MacOsTunController.freeTunName(setOf("en0", "utun225", "utun226")))
    }

    /** A scripted daemon on a real unix socket: one reply per request, in order. */
    private class FakeDaemon(val path: Path) {
        val verbs = mutableListOf<String>()
        val requests = mutableListOf<String>()
    }

    private fun withFakeDaemon(replies: List<String>, body: (FakeDaemon) -> Unit) {
        val dir = Files.createTempDirectory("tunneld-test")
        val daemon = FakeDaemon(dir.resolve("sock"))
        val queue = ArrayDeque(replies)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(daemon.path))
        val thread = Thread {
            runCatching {
                while (true) {
                    server.accept().use { channel ->
                        val request = readLine(channel)
                        synchronized(daemon) {
                            daemon.requests += request
                            daemon.verbs += Regex("\"verb\":\"([a-z]+)\"").find(request)?.groupValues?.get(1) ?: "?"
                        }
                        val reply = queue.removeFirstOrNull() ?: """{"ok":false,"error":"unscripted","logTail":""}"""
                        channel.write(ByteBuffer.wrap((reply + "\n").toByteArray()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            body(daemon)
        } finally {
            server.close()
            thread.interrupt()
            daemon.path.deleteIfExists()
        }
    }

    private fun readLine(channel: SocketChannel): String {
        val out = StringBuilder()
        val buffer = ByteBuffer.allocate(16 * 1024)
        while ('\n' !in out) {
            buffer.clear()
            if (channel.read(buffer) < 0) break
            buffer.flip()
            out.append(Charsets.UTF_8.decode(buffer))
        }
        return out.toString()
    }
}
