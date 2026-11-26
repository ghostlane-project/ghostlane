package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
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

/**
 * The twins of [MacOsTunControllerTest] for a line on ghostlane-engine.
 *
 * The helper of the build before hev was handed a sing-box config and ran
 * sing-box; the current one is handed typed fields and runs hev, which gives
 * what it takes to the engine's SOCKS port. Everything the app keeps of the
 * tunnel, the block and the helper is the same, and is asked for the same
 * way: only the verb that starts the tunnel, and what travels with it, are
 * these tests' business.
 */
class MacOsTunControllerEngineTest {

    private val idle = """{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":false}"""
    private val running = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":5,"hold":false}"""
    private val runningHeld = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":5,"hold":true}"""
    private val idleHeld = """{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":true}"""
    private val idleHeldBeforeHev = """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":true}"""
    /** The helper from before lines: it holds a block but runs no engine. */
    private val idleHeldBeforeLines = """{"ok":true,"state":"idle","logTail":"","protocol":3,"hold":true}"""
    private val steppingAside =
        """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","protocol":5,"hold":false,"line":0}"""

    private val addresses = listOf("203.0.113.7", "2001:db8::1")

    private fun controller(
        daemon: FakeDaemon,
        log: MutableList<String> = mutableListOf(),
        resolved: List<String> = addresses,
        onInterface: String? = "en0",
        tunName: String = "utun231",
        askTun: (String) -> List<String> = { emptyList() }
    ) = MacOsTunController(
        log::add,
        TunnelDaemonClient(daemon.path),
        { resolved },
        { onInterface },
        { tunName },
        askTun,
        MacTunDaemonClient(daemon.path),
    )

    private fun MacOsTunController.startEngine(
        corePort: Int = 18821,
        username: String = "",
        password: String = "",
        serverHost: String? = null,
        killSwitch: Boolean = false,
        routing: Routing = Routing.Global,
        ruleFiles: Map<String, String> = emptyMap(),
        namesProgramsFor: String? = null
    ) = runBlocking {
        start(
            corePort = corePort,
            verifyPort = corePort + 1,
            username = username,
            password = password,
            serverHost = serverHost,
            upstreamUdpIsLossy = false,
            routing = routing,
            ruleFiles = ruleFiles,
            killSwitch = killSwitch,
            namesProgramsFor = namesProgramsFor,
            rustEngine = true,
        )
    }

    private fun FakeDaemon.request(index: Int): JsonObject =
        Json.parseToJsonElement(requests[index].trim()).jsonObject

    private fun JsonObject.strings(key: String): List<String> =
        this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun FakeDaemon.tun(index: Int = requests.lastIndex): JsonObject = request(index)

    @Test fun everyResolvedServerAddressBecomesAnExclusion() {
        assertEquals(
            listOf("203.0.113.7/32", "198.51.100.9/32", "2001:db8::1/128"),
            MacOsTunController.excludeCidrs(listOf("203.0.113.7", "198.51.100.9", "2001:db8::1"))
        )
    }

    @Test fun theInterfaceBehindTheDefaultRouteIsNotATun() {
        val route = """route to: default
            destination: default
                  interface: en0"""
        assertEquals("en0", MacOsTunController.defaultInterfaceName(route))
        assertNull(MacOsTunController.defaultInterfaceName(route.replace("en0", "utun7")))
    }

    @Test fun theTunsNameIsOneNoInterfaceHas() {
        assertEquals("utun225", MacOsTunController.freeTunName(setOf("lo0", "en0", "utun0", "utun3")))
    }

    @Test fun aHostThatDoesNotResolveIsNotSilentlyLeftUnexcluded() = withFakeDaemon(listOf(idle)) { daemon ->
        val log = mutableListOf<String>()
        val tunnel = controller(daemon, log, resolved = emptyList())
        assertFailsWith<IllegalStateException> { tunnel.startEngine(serverHost = "de1.example.org") }
        assertTrue(log.any { "de1.example.org" in it }, log.toString())
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun anAbsentDaemonFailsLoudlyRatherThanReportingAStartedTunnel() {
        val log = mutableListOf<String>()
        val tunnel = MacOsTunController(
            log::add,
            TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            { addresses },
            { "en0" },
            tunClient = MacTunDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
        )
        assertFailsWith<IllegalStateException> { runBlocking { tunnel.start(rustEngine = true, corePort = 18821, verifyPort = 18822, username = "", password = "", serverHost = null, upstreamUdpIsLossy = false) } }
        assertTrue(log.any { "macOS TUN failed" in it }, log.toString())
    }

    // The helper is asked first, and what it says decides the verb. One that
    // speaks hev is given fields, never a config: the helper writes hev's
    // config itself, and a config could name a script it would run as root.
    @Test fun aStartAsksTheHelperWhatItSpeaksAndThenStartsHev() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val tunnel = controller(daemon)
        tunnel.startEngine()
        assertEquals(listOf("status", "tun"), daemon.verbs)
        val request = daemon.tun(1)
        assertEquals("tun", request["verb"]!!.jsonPrimitive.content)
        assertFalse("config" in request, daemon.requests[1])
        assertFalse("files" in request, daemon.requests[1])
        assertEquals("utun231", request.jsonObject["tun"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(18821, request.jsonObject["socks"]!!.jsonObject["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(SingBoxConfig.DESKTOP_TUN_RESOLVER, request["names"]!!.jsonPrimitive.content)
        assertEquals("172.19.0.2", request["mapdns"]!!.jsonObject["address"]!!.jsonPrimitive.content)
        assertTrue(tunnel.answersNames)
        assertFalse(tunnel.blockStands)
    }

    @Test fun theCorePortAndItsLoginAreWhatHevHandsTo() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val tunnel = controller(daemon)
        tunnel.startEngine(corePort = 19090, username = "ghostlane", password = "p4ss w'rd", serverHost = "de1.example.org")
        val socks = daemon.tun(1)["socks"]!!.jsonObject
        assertEquals(19090, socks["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("127.0.0.1", socks["address"]!!.jsonPrimitive.content)
        assertEquals("ghostlane", socks["username"]!!.jsonPrimitive.content)
        assertEquals("p4ss w'rd", socks["password"]!!.jsonPrimitive.content)
        assertEquals(listOf("203.0.113.7/32", "2001:db8::1/128"), daemon.tun(1).strings("exclude"))
    }

    @Test fun aPortThatAsksForNoLoginIsHandedNone() = withFakeDaemon(listOf(idle, running)) { daemon ->
        controller(daemon).startEngine()
        assertEquals(setOf("address", "port"), daemon.tun(1)["socks"]!!.jsonObject.keys)
    }

    // The app installed over an older build finds that build's helper still
    // running. It knows no `tun`, so it is asked to stop, its block kept where
    // it stands, and the helper launchd brings up is the one asked.
    @Test fun aHelperFromBeforeHevIsReplacedBeforeAnEngineTunnelIsAskedFor() = withFakeDaemon(
        listOf(
            idleHeldBeforeHev,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","hold":true}""",
            idle,
            runningHeld
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val tunnel = controller(daemon, log)
        tunnel.startEngine(killSwitch = true)
        assertEquals(listOf("status", "stop", "status", "tun"), daemon.verbs)
        assertEquals("keep", daemon.request(1)["hold"]!!.jsonPrimitive.content)
        assertEquals("utun231", daemon.tun(3)["hold"]!!.jsonObject["interface"]!!.jsonPrimitive.content)
        assertTrue(tunnel.blockStands)
        assertTrue(log.any { "older build" in it }, log.toString())
    }

    @Test fun aHelperThatStepsAsideForAnUpdateIsAskedAgainForTheTunnel() = withFakeDaemon(
        listOf(idle, steppingAside, idle, running)
    ) { daemon ->
        val log = mutableListOf<String>()
        controller(daemon, log).startEngine()
        assertEquals(listOf("status", "tun", "status", "tun"), daemon.verbs)
        assertEquals(daemon.requests[1], daemon.requests[3], "the same tunnel is asked for again")
        assertTrue(log.any { "the tunnel helper was updated" in it }, log.toString())
    }

    @Test fun underTheSwitchTheTunIsNamedAndTheBlockLetsItsServerOut() = withFakeDaemon(
        listOf(idle, runningHeld)
    ) { daemon ->
        val log = mutableListOf<String>()
        val tunnel = controller(daemon, log)
        tunnel.startEngine(
            killSwitch = true,
            serverHost = "de1.example.org",
            namesProgramsFor = "/Applications/Ghostlane.app/Contents/MacOS/Ghostlane"
        )
        val request = daemon.tun(1)
        assertEquals("utun231", request["tun"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("utun231", request["hold"]!!.jsonObject["interface"]!!.jsonPrimitive.content)
        assertEquals(addresses, request["hold"]!!.jsonObject.strings("pass"))
        assertEquals(listOf("203.0.113.7/32", "2001:db8::1/128"), request.strings("exclude"))
        assertTrue(tunnel.blockStands)
        assertFalse(tunnel.holdsTraffic)
        assertTrue(log.any { "kill switch in place" in it }, log.toString())
    }

    @Test fun aTunnelStartedUnderTheSwitchWithNoBlockBehindItIsStoppedAndRefused() = withFakeDaemon(
        listOf(idle, running, idle)
    ) { daemon ->
        val log = mutableListOf<String>()
        val tunnel = controller(daemon, log)
        val failure = assertFailsWith<IllegalStateException> { tunnel.startEngine(killSwitch = true) }
        assertTrue("kill switch" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf("status", "tun", "stop"), daemon.verbs)
        assertFalse(tunnel.holdsTraffic)
        assertFalse(tunnel.blockStands)
        assertTrue(log.none { "kill switch in place" in it }, log.toString())
    }

    @Test fun aSessionThatEndsWithoutADisconnectKeepsTheBlock() = withFakeDaemon(
        listOf(idle, runningHeld, idleHeld)
    ) { daemon ->
        val log = mutableListOf<String>()
        val tunnel = controller(daemon, log)
        tunnel.startEngine(killSwitch = true)
        assertTrue(tunnel.holdAfterSessionEnd(wanted = true))
        runBlocking { tunnel.stop() }
        assertEquals("keep", daemon.request(2)["hold"]!!.jsonPrimitive.content)
        assertTrue(tunnel.holdsTraffic)
        assertTrue(tunnel.blockStands)
        assertTrue(log.any { "holds this Mac's traffic" in it }, log.toString())
    }

    @Test fun aDisconnectTakesTheBlockAway() = withFakeDaemon(listOf(idle, runningHeld, idle)) { daemon ->
        val tunnel = controller(daemon)
        tunnel.startEngine(killSwitch = true)
        runBlocking { tunnel.stop() }
        assertNull(daemon.request(2)["hold"])
        assertFalse(tunnel.holdsTraffic)
        assertFalse(tunnel.blockStands)
    }

    @Test fun aSwitchTurnedOffSinceTheTunnelStartedHoldsNothing() = withFakeDaemon(
        listOf(idle, runningHeld, running, idle)
    ) { daemon ->
        val tunnel = controller(daemon)
        tunnel.startEngine(killSwitch = true)
        runBlocking { tunnel.release() }
        assertEquals("release", daemon.verbs[2])
        assertFalse(tunnel.holdAfterSessionEnd(wanted = true))
        runBlocking { tunnel.stop() }
        assertFalse(tunnel.holdsTraffic)
    }

    @Test fun aBlockLeftByAPreviousRunIsHeld() = withFakeDaemon(listOf(idleHeld)) { daemon ->
        val tunnel = controller(daemon)
        assertEquals(
            MacOsTunController.Leftover(tunnelRuns = false, blockStands = true),
            runBlocking { tunnel.findLeftover() }
        )
        assertTrue(tunnel.holdsTraffic)
        tunnel.endHold()
        assertFalse(tunnel.holdsTraffic)
    }

    @Test fun aTunnelLeftRunningBehindItsBlockIsStoppedAndTheBlockKept() = withFakeDaemon(
        listOf(runningHeld, idleHeld)
    ) { daemon ->
        val tunnel = controller(daemon)
        assertEquals(
            MacOsTunController.Leftover(tunnelRuns = true, blockStands = true),
            runBlocking { tunnel.findLeftover() }
        )
        runBlocking { tunnel.stop() }
        assertEquals("keep", daemon.request(1)["hold"]!!.jsonPrimitive.content)
        assertTrue(tunnel.holdsTraffic)
    }

    @Test fun aConnectLearnsWhetherABlockStandsAndWhatTheHelperCanDo() = withFakeDaemon(listOf(idleHeld)) { daemon ->
        val tunnel = controller(daemon)
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
            runBlocking { tunnel.helperNow() }
        )
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun aHelperThatCannotRunALineIsNotAskedTo() = withFakeDaemon(
        listOf(
            idleHeldBeforeLines,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":"","hold":true}""",
            idleHeldBeforeLines,
            idleHeldBeforeLines
        )
    ) { daemon ->
        val tunnel = controller(daemon)
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = false),
            runBlocking { tunnel.helperNow() }
        )
        assertFalse(tunnel.runsLineUnder(killSwitch = true))
    }

    @Test fun theRoomsEngineIsTheHelpersUnderTheSwitchAndBehindABlock() {
        withFakeDaemon(listOf(idle)) { daemon ->
            val tunnel = controller(daemon)
            runBlocking { tunnel.helperNow() }
            assertTrue(tunnel.runsLineUnder(killSwitch = true))
            assertFalse(tunnel.runsLineUnder(killSwitch = false))
        }
        withFakeDaemon(listOf(idleHeld)) { daemon ->
            val tunnel = controller(daemon)
            runBlocking { tunnel.helperNow() }
            assertTrue(tunnel.runsLineUnder(killSwitch = false))
        }
    }

    @Test fun aLineTheHelperRunsIsHeldAsAProcessAndReadFromTheAppsFile() {
        val child = ProcessBuilder("sleep", "30").start()
        val said = Files.createTempFile("line-said", ".out")
        try {
            withFakeDaemon(
                listOf("""{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":true,"line":${child.pid()},"program":"/x/olcrtc"}""")
            ) { daemon ->
                val tunnel = controller(daemon)
                val line = runBlocking {
                    tunnel.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), listOf("/tmp/c.yaml"), said)
                }
                assertEquals("line", daemon.request(0)["verb"]!!.jsonPrimitive.content)
                assertEquals(child.pid(), line.pid())
                Files.writeString(said, "SOCKS5 server listening on 127.0.0.1:10808\n")
                assertEquals("SOCKS5 server listening on 127.0.0.1:10808", line.inputStream.bufferedReader().readLine())
                line.destroy()
                assertEquals(HelperLine.EXIT_NOT_KNOWN, line.waitFor())
            }
        } finally {
            child.destroyForcibly()
            Files.deleteIfExists(said)
        }
    }

    @Test fun aLineLeftByAnAppThatIsGoneIsEnded() = withFakeDaemon(
        listOf("""{"ok":true,"state":"idle","logTail":"","protocol":5,"hold":false,"line":4711}""", idle)
    ) { daemon ->
        runBlocking { controller(daemon).findLeftover() }
        assertEquals("line-stop", daemon.request(1)["verb"]!!.jsonPrimitive.content)
    }

    @Test fun aLineBehindATunWaitsUntilTheTunNamesPrograms() {
        var asked = 0
        val tunnel = MacOsTunController(
            {},
            TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            { emptyList() },
            { "en0" },
            askTun = { name ->
                assertEquals(SingBoxConfig.NAMES_PROGRAMS_PROBE, name)
                if (++asked < 3) emptyList() else listOf(SingBoxConfig.NAMES_PROGRAMS_ANSWER)
            },
        )
        assertTrue(runBlocking { tunnel.awaitNamesPrograms(timeoutMs = 5_000) })
        assertEquals(3, asked)
    }

    @Test fun aTunThatNeverNamesAProgramIsNotWaitedOnForEver() {
        val log = mutableListOf<String>()
        val tunnel = MacOsTunController(
            log::add,
            TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            { emptyList() },
            { "en0" },
            askTun = { emptyList() },
        )
        assertFalse(runBlocking { tunnel.awaitNamesPrograms(timeoutMs = 400) })
        assertTrue(log.any { "starting the line anyway" in it }, log.toString())
    }

    // Under rules the engine needs the physical interface for the traffic it
    // sends beside the tunnel. Without one there is no tunnel at all, rather
    // than one that sends the bypass into itself.
    @Test fun aBypassWithNoInterfaceToBindToDoesNotStart() = withFakeDaemon(listOf(idle)) { daemon ->
        val tunnel = controller(daemon, onInterface = null)
        val rules = Routing.Rules("/Library/Application Support/org.olcbox.app/rules", DirectDns.System, "ru")
        assertFailsWith<IllegalStateException> {
            runBlocking {
                tunnel.start(
                    corePort = 18821,
                    verifyPort = 18822,
                    username = "",
                    password = "",
                    serverHost = null,
                    upstreamUdpIsLossy = false,
                    routing = rules,
                    rustEngine = true,
                )
            }
        }
        assertEquals(emptyList(), daemon.verbs)
    }

    /** A scripted daemon on a real unix socket: one reply per request, in order. */
    private class FakeDaemon(val path: Path) {
        val verbs = mutableListOf<String>()
        val requests = mutableListOf<String>()
    }

    private fun withFakeDaemon(replies: List<String>, body: (FakeDaemon) -> Unit) {
        val dir = Files.createTempDirectory("tunneld-engine-test")
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
