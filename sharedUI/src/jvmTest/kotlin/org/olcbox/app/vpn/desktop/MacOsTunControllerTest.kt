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

class MacOsTunControllerTest {

    @Test
    fun everyResolvedServerAddressBecomesAnExclusion() {
        assertEquals(
            listOf("203.0.113.7/32", "198.51.100.9/32", "2001:db8::1/128"),
            MacOsTunController.excludeCidrs(listOf("203.0.113.7", "198.51.100.9", "2001:db8::1"))
        )
    }

    @Test
    fun aHostThatDoesNotResolveIsNotSilentlyLeftUnexcluded() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { emptyList() }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "de1.example.org" in it }, "the log has to name the host: $logs")
    }

    @Test
    fun anAbsentDaemonFailsLoudlyRatherThanReportingAStartedTunnel() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { listOf("203.0.113.7") }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "macOS TUN failed" in it }, "the failure has to reach the log: $logs")
    }
    private val routeOutput = """
           route to: default
        destination: default
               mask: default
            gateway: 192.168.1.1
          interface: en0
              flags: <UP,GATEWAY,DONE,STATIC,PRCLO>
    """.trimIndent()

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

    private val bypass = Routing.Rules("/Library/Application Support/org.olcbox.app/rules", DirectDns.System, "ru")
    private val ruleFiles = mapOf("geoip-ru.srs" to "eA==")
    private val idle = """{"ok":true,"state":"idle","logTail":"","protocol":2}"""
    private val running = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":2}"""

    private fun MacOsTunController.connect(routing: Routing, files: Map<String, String>) = runBlocking {
        start(
            corePort = 10810,
            verifyPort = 10811,
            username = "",
            password = "",
            serverHost = null,
            upstreamUdpIsLossy = false,
            routing = routing,
            ruleFiles = files
        )
    }

    @Test fun anOlderHelperIsRefusedBeforeItIsHandedAConfig() = withFakeDaemon(
        // No protocol number: the daemon from before rule files.
        listOf("""{"ok":true,"state":"idle","logTail":""}""")
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val failure = assertFailsWith<IllegalStateException> { controller.connect(bypass, ruleFiles) }
        assertTrue("launchctl kickstart" in failure.message.orEmpty(), failure.message)
        // Refused on the status alone: a config it cannot honour never reaches it.
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun aHelperSteppingAsideForAnUpdateIsFollowedToTheNewOne() = withFakeDaemon(
        listOf(
            idle,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}""",
            idle,
            running
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connect(bypass, ruleFiles)
        assertEquals(listOf("status", "start", "status", "start"), daemon.verbs)
        assertTrue(log.any { "macOS TUN running" in it }, log.toString())
        assertTrue("\"geoip-ru.srs\":\"eA==\"" in daemon.requests.last())
    }

    // What the helper speaks decides what it is handed, so it is asked first.
    @Test fun aStartAsksTheHelperWhatItSpeaksAndThenStarts() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { null })
        controller.connect(Routing.Global, emptyMap())
        assertEquals(listOf("status", "start"), daemon.verbs)
        assertTrue("files" !in daemon.requests.last())
    }

    @Test fun aBypassWithNoInterfaceToBindToDoesNotStart() = withFakeDaemon(listOf(idle)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { null })
        assertFailsWith<IllegalStateException> { controller.connect(bypass, ruleFiles) }
        assertEquals(emptyList(), daemon.verbs)
    }

    // A session's tun is built once and its line can be changed behind it, so
    // the tun has to let every binary a line can run out of itself, not only
    // the server it started with.
    private val lineBinaries = listOf(
        "/Users/a/Library/Application Support/Ghostlane/bin/sing-box",
        "/Users/a/Library/Application Support/Ghostlane/bin/xray",
        "/Users/a/Library/Application Support/Ghostlane/bin/olcrtc-darwin-arm64"
    )

    private fun MacOsTunController.connectWithLineBinaries(serverHost: String?) = runBlocking {
        start(
            corePort = 10810,
            verifyPort = 10811,
            username = "",
            password = "",
            serverHost = serverHost,
            upstreamUdpIsLossy = false,
            bypassProcessPaths = lineBinaries
        )
    }

    /** The config the daemon was handed, out of the request that carries it as a string. */
    private fun FakeDaemon.configText(): String =
        Json.parseToJsonElement(requests.last().trim()).jsonObject["config"]!!.jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun theLinesBinariesAreLetOutBoundToThePhysicalInterface() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7") }, { "en0" })
        assertTrue(controller.connectWithLineBinaries("de1.example.org"))
        assertEquals(listOf("status", "start"), daemon.verbs)

        val config = Json.parseToJsonElement(daemon.configText()).jsonObject
        val route = config["route"]!!.jsonObject
        val rule = route["rules"]!!.jsonArray.first().jsonObject
        assertEquals(lineBinaries, rule.strings("process_path"))
        assertEquals("direct", rule["outbound"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
        val direct = config["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertEquals("en0", direct["bind_interface"]!!.jsonPrimitive.content)
        // The first line's server is still kept out by address, as it always was.
        val tun = config["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(listOf("203.0.113.7/32"), tun.strings("route_exclude_address"))
    }

    // Under rules the name was already required; the binaries ride the same one.
    @Test fun underABypassTheLinesBinariesAreLetOutAsWell() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val letsEveryLineOut = runBlocking {
            controller.start(
                corePort = 10810,
                verifyPort = 10811,
                username = "",
                password = "",
                serverHost = null,
                upstreamUdpIsLossy = false,
                routing = bypass,
                ruleFiles = ruleFiles,
                bypassProcessPaths = lineBinaries
            )
        }
        assertTrue(letsEveryLineOut)
        val rules = Json.parseToJsonElement(daemon.configText()).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(lineBinaries, rules.first().jsonObject.strings("process_path"))
    }

    // No name for the physical interface, and no rules that need one: a connect
    // that worked before the binaries were let out has to go on working. The
    // tun is the one it always was, and says that it is good for one server.
    @Test fun withNoInterfaceToBindToTheTunStartsAsItAlwaysDid() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { null })
        assertFalse(controller.connectWithLineBinaries(serverHost = null))
        assertEquals(
            SingBoxConfig.buildDesktopTun(
                corePort = 10810,
                verifyPort = 10811,
                cacheFilePath = TunnelDaemonProtocol.CACHE_FILE
            ),
            daemon.configText()
        )
        assertEquals(1, log.count { "another location will be a full restart" in it }, log.toString())
    }

    // --- a helper that points the system's resolver at the tun, and holds the block ---

    private val idle3 = """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":false}"""
    private val running3 = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":3,"hold":false}"""
    private val runningHeld = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":4,"hold":true}"""
    private val idleHeld = """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":true}"""

    /** The helper of the build before lines: it holds a block, and cannot run a room's engine. */
    private val idleHeldBeforeLines = """{"ok":true,"state":"idle","logTail":"","protocol":3,"hold":true}"""

    private fun FakeDaemon.request(index: Int): JsonObject = Json.parseToJsonElement(requests[index].trim()).jsonObject

    private fun FakeDaemon.config(index: Int): JsonObject =
        Json.parseToJsonElement(request(index)["config"]!!.jsonPrimitive.content).jsonObject

    private fun JsonObject.routeActions(): List<String> = this["route"]!!.jsonObject["rules"]!!.jsonArray
        .map { rule -> rule.jsonObject["action"]?.jsonPrimitive?.content ?: "to " + rule.jsonObject["outbound"]!!.jsonPrimitive.content }

    private fun MacOsTunController.connectUnderTheSwitch(killSwitch: Boolean = true, serverHost: String? = "de1.example.org") =
        runBlocking {
            start(
                corePort = 10810,
                verifyPort = 10811,
                username = "",
                password = "",
                serverHost = serverHost,
                upstreamUdpIsLossy = false,
                bypassProcessPaths = lineBinaries,
                killSwitch = killSwitch,
                namesProgramsFor = "/Applications/Ghostlane.app/Contents/MacOS/Ghostlane"
            )
        }

    @Test fun aHelperThatCanIsToldToHaveTheSystemAskTheTun() = withFakeDaemon(listOf(idle3, running3)) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7") }, { "en0" })
        controller.connectUnderTheSwitch(killSwitch = false)
        assertTrue(controller.answersNames)
        assertEquals(SingBoxConfig.DESKTOP_TUN_RESOLVER, daemon.request(1)["names"]!!.jsonPrimitive.content)
        // And the tun it is handed answers what it is then asked, before it
        // lets a line's binary out, on a core's line too.
        assertEquals(listOf("sniff", "hijack-dns", "to direct", "reject"), daemon.config(1).routeActions())
        // No switch, no block and no name of the app's choosing for the tun.
        assertNull(daemon.request(1)["hold"])
        assertNull(daemon.config(1)["inbounds"]!!.jsonArray.first().jsonObject["interface_name"])
        assertFalse(controller.blockStands)
        assertTrue(log.any { "asks the tunnel for names" in it }, log.toString())
    }

    @Test fun aHelperFromBeforeThatIsHandedTheTunItAlwaysWas() = withFakeDaemon(listOf(idle, running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7") }, { "en0" })
        controller.connectUnderTheSwitch(killSwitch = false)
        assertFalse(controller.answersNames)
        assertNull(daemon.request(1)["names"])
        // A core's line: nothing hijacked, the lines' binaries let out, as it was.
        assertEquals(listOf("to direct", "reject"), daemon.config(1).routeActions())
        assertTrue(SingBoxConfig.NAMES_PROGRAMS_PROBE !in daemon.requests[1])
    }

    @Test fun underTheSwitchTheTunIsNamedAndTheBlockLetsItsServerOut() = withFakeDaemon(listOf(idle3, runningHeld)) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(
            log::add, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7", "2001:db8::1") }, { "en0" },
            tunName = { "utun231" }
        )
        controller.connectUnderTheSwitch()
        val hold = daemon.request(1)["hold"]!!.jsonObject
        assertEquals("utun231", hold["interface"]!!.jsonPrimitive.content)
        // The addresses the tun keeps out of itself are the ones the block
        // has to let out: the core dials them from the user's own process.
        assertEquals(listOf("203.0.113.7", "2001:db8::1"), hold.strings("pass"))
        val tun = daemon.config(1)["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("utun231", tun["interface_name"]!!.jsonPrimitive.content)
        assertEquals(listOf("203.0.113.7/32", "2001:db8::1/128"), tun.strings("route_exclude_address"))
        assertTrue(controller.blockStands)
        // Up behind its block, nothing is held: traffic goes through the tunnel.
        assertFalse(controller.holdsTraffic)
        assertTrue(log.any { "kill switch in place" in it }, log.toString())
    }

    @Test fun aHelperFromBeforeTheSwitchDoesNotStartATunnelWithoutIt() = withFakeDaemon(listOf(idle)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7") }, { "en0" })
        val failure = assertFailsWith<IllegalStateException> { controller.connectUnderTheSwitch() }
        assertTrue("launchctl kickstart" in failure.message.orEmpty(), failure.message)
        // A tunnel with no block behind it is not what was asked for, so none is started.
        assertEquals(listOf("status"), daemon.verbs)
    }

    // Whatever the reason, a tunnel that came up with no block behind it under
    // the switch is not the one that was asked for, and a status that said
    // Connected over it would be promising a hold that is not there.
    @Test fun aTunnelStartedUnderTheSwitchWithNoBlockBehindItIsStoppedAndRefused() = withFakeDaemon(
        listOf(idle3, running3, idle3)
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val failure = assertFailsWith<IllegalStateException> { controller.connectUnderTheSwitch(serverHost = null) }
        assertTrue("kill switch" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf("status", "start", "stop"), daemon.verbs)
        assertFalse(controller.holdsTraffic)
        assertFalse(controller.blockStands)
        assertTrue(log.none { "kill switch in place" in it }, log.toString())
    }

    @Test fun aHelperThatSteppedAsideIsAskedAgainWhatItSpeaks() = withFakeDaemon(
        listOf(
            idle,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}""",
            idle3,
            running3
        )
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { listOf("203.0.113.7") }, { "en0" })
        controller.connectUnderTheSwitch(killSwitch = false)
        assertEquals(listOf("status", "start", "status", "start"), daemon.verbs)
        // The old one was handed the old tun, and the new one the tun it can carry.
        assertNull(daemon.request(1)["names"])
        assertEquals(SingBoxConfig.DESKTOP_TUN_RESOLVER, daemon.request(3)["names"]!!.jsonPrimitive.content)
        assertTrue(controller.answersNames)
    }

    @Test fun aSessionThatEndsWithoutADisconnectKeepsTheBlock() = withFakeDaemon(
        listOf(idle3, runningHeld, idleHeld)
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connectUnderTheSwitch(serverHost = null)

        assertTrue(controller.holdAfterSessionEnd(wanted = true))
        runBlocking { controller.stop() }

        assertEquals("keep", daemon.request(2)["hold"]!!.jsonPrimitive.content)
        assertTrue(controller.holdsTraffic)
        assertTrue(controller.blockStands)
        assertTrue(log.any { "holds this Mac's traffic" in it }, log.toString())
    }

    @Test fun aDisconnectTakesTheBlockAway() = withFakeDaemon(listOf(idle3, runningHeld, idle3)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connectUnderTheSwitch(serverHost = null)
        runBlocking { controller.stop() }
        assertNull(daemon.request(2)["hold"])
        assertFalse(controller.holdsTraffic)
        assertFalse(controller.blockStands)
    }

    @Test fun aSwitchTurnedOffSinceTheTunnelStartedHoldsNothing() = withFakeDaemon(
        listOf(idle3, runningHeld, running3, idle3)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connectUnderTheSwitch(serverHost = null)
        // Off: the block goes at once, and the tunnel is left running.
        runBlocking { controller.release() }
        assertEquals("release", daemon.verbs[2])
        assertFalse(controller.blockStands)
        // So when that tunnel dies there is nothing to keep, whatever the setting says by then.
        assertFalse(controller.holdAfterSessionEnd(wanted = true))
        runBlocking { controller.stop() }
        assertNull(daemon.request(3)["hold"])
        assertFalse(controller.holdsTraffic)
    }

    @Test fun aSwitchThatIsOffNowKeepsNothingOfASessionStartedWithIt() = withFakeDaemon(
        listOf(idle3, runningHeld, idle3)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connectUnderTheSwitch(serverHost = null)
        assertFalse(controller.holdAfterSessionEnd(wanted = false))
        runBlocking { controller.stop() }
        assertNull(daemon.request(2)["hold"])
        assertFalse(controller.holdsTraffic)
    }

    @Test fun aBlockLeftByAPreviousRunIsHeld() = withFakeDaemon(listOf(idleHeld)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
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
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val left = runBlocking { controller.findLeftover() }
        assertEquals(MacOsTunController.Leftover(tunnelRuns = true, blockStands = true), left)
        runBlocking { controller.stop() }
        assertEquals("keep", daemon.request(1)["hold"]!!.jsonPrimitive.content)
        assertTrue(controller.holdsTraffic)
    }

    @Test fun aHelpersWordThatTheBlockIsGoneEndsTheHold() = withFakeDaemon(listOf(idleHeld, idle3)) { daemon ->
        // The Mac was restarted, or somebody flushed the rules by hand.
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        runBlocking { controller.findLeftover() }
        assertTrue(controller.holdsTraffic)
        runBlocking { controller.stop() }
        assertFalse(controller.holdsTraffic)
    }

    // A connect asks the helper before it chooses its order: behind a block
    // that stands the tunnel has to come first, and a helper that can hold
    // none is not asked for one.
    @Test fun aConnectLearnsWhetherABlockStandsAndWhetherTheHelperCanHoldOne() {
        withFakeDaemon(listOf(idleHeld)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
            assertEquals(
                MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = true),
                runBlocking { controller.helperNow() }
            )
            assertTrue(controller.blockStands)
        }
        // A helper from before the block that is the one on disk as well: it
        // is told to stop, says it has, and can hold nothing.
        withFakeDaemon(listOf(idle, idle)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
            assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { controller.helperNow() })
            assertEquals(listOf("status", "stop"), daemon.verbs)
        }
        // No helper at all: nothing stands, and nothing can be asked for.
        val absent = MacOsTunController(
            {}, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() }, { "en0" }
        )
        assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { absent.helperNow() })
    }

    // The first connect after an update of the app: the previous build's
    // helper is still the one running. It makes way when it is told to stop,
    // and the connect then chooses its order by what the new one can do.
    @Test fun aHelperLeftFromBeforeAnUpdateMakesWayBeforeTheConnectChoosesItsOrder() = withFakeDaemon(
        listOf(
            idle,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}""",
            idle3,
            idleHeld
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
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
            idleHeld,
            idleHeld
        )
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
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
        listOf(idleHeldBeforeLines, idleHeldBeforeLines)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        assertEquals(
            MacOsTunController.Helper(blockStands = true, canHold = true, runsLines = false),
            runBlocking { controller.helperNow() }
        )
        assertFalse(controller.runsLineUnder(killSwitch = true))
    }

    // A room's engine is the helper's to run under the switch, and where its
    // block stands; with neither it is the app's own child, as ever.
    @Test fun aRoomsEngineIsTheHelpersUnderTheSwitchAndBehindABlock() {
        withFakeDaemon(listOf(idle3)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
            assertFalse(controller.runsLineUnder(killSwitch = true), "not before the helper has been asked")
            runBlocking { controller.helperNow() }
            assertTrue(controller.runsLineUnder(killSwitch = true))
            assertFalse(controller.runsLineUnder(killSwitch = false))
        }
        withFakeDaemon(listOf(idleHeld)) { daemon ->
            val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
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
                val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
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
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { controller.startLine(Path.of("/Users/a/bin/olcrtc-darwin-arm64"), emptyList(), Path.of("/tmp/none")) }
        }
        assertTrue("not signed by this team" in failure.message.orEmpty(), failure.message)
    }

    @Test fun theHelpersCopyOfALinesProgramIsNamedByItsFile() {
        val controller = MacOsTunController({}, TunnelDaemonClient(Path.of("/var/run/none")), { emptyList() }, { "en0" })
        assertEquals(
            "/Library/Application Support/org.olcbox.app.lines/olcrtc-darwin-arm64",
            controller.lineProgram(Path.of("/Users/a/Library/Application Support/Ghostlane/bin/olcrtc-darwin-arm64"))
        )
    }

    // A line's program left running by a run of the app that is gone is ended: nobody reads it.
    @Test fun aLineLeftByAnAppThatIsGoneIsEnded() = withFakeDaemon(
        listOf("""{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":false,"line":4711}""", idle3)
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        runBlocking { controller.findLeftover() }
        assertEquals(2, daemon.requests.size)
        assertEquals("\"line-stop\"", daemon.request(1)["verb"].toString())
    }

    // A helper that is carrying a tunnel is not told to stop to find out how old it is.
    @Test fun aHelperFromBeforeThatIsCarryingATunnelIsLeftCarryingIt() = withFakeDaemon(listOf(running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        assertEquals(MacOsTunController.Helper(blockStands = false, canHold = false), runBlocking { controller.helperNow() })
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun aLineBehindATunWaitsUntilTheTunNamesPrograms() {
        var asked = 0
        val controller = MacOsTunController(
            {}, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() }, { "en0" },
            askTun = { name ->
                assertEquals(SingBoxConfig.NAMES_PROGRAMS_PROBE, name)
                if (++asked < 3) emptyList() else listOf(SingBoxConfig.NAMES_PROGRAMS_ANSWER)
            }
        )
        assertTrue(runBlocking { controller.awaitNamesPrograms(timeoutMs = 5_000) })
        assertEquals(3, asked)
    }

    @Test fun aTunThatNeverNamesAProgramIsNotWaitedOnForEver() {
        val log = mutableListOf<String>()
        val controller = MacOsTunController(
            log::add, TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")), { emptyList() }, { "en0" },
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
