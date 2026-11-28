package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.LanCores
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine host without the engine: what the session file holds for a
 * line, how a server named by a hostname is asked for and pinned, and how a
 * run is started, read and stopped, against stand-in programs that speak the
 * engine's contract (on a system with a POSIX shell). The real engine runs in
 * HostEngineRealTest, under scripts/desktop-checks/proxy.sh.
 */
class HostEngineTest {
    private val scratch: Path = Files.createTempDirectory("host-engine-test")
    private val programs: Path = Files.createDirectories(scratch.resolve("programs"))
    private val sessions: Path = Files.createDirectories(scratch.resolve("sessions"))

    @AfterTest
    fun clean() {
        scratch.toFile().deleteRecursively()
    }

    private val reality =
        "vless://11111111-1111-1111-1111-111111111111@192.0.2.7:443?security=reality&encryption=none" +
            "&pbk=${CoreFixtures.PUBLIC_KEY}&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#line"
    private val named = reality.replace("@192.0.2.7:", "@server.example.test:")

    private fun request(
        link: String,
        port: Int = 10808,
        login: SocksLogin? = null,
        routing: Routing = Routing.Global,
        resolver: DirectDns? = null,
        autoDetect: Boolean = false,
        bind: String? = null,
    ) = LineRequest(
        spec = requireNotNull(LinkParser.parse(link)),
        kind = "Vless",
        port = port,
        routing = routing,
        verboseLogs = false,
        login = login,
        serverResolver = resolver,
        autoDetectInterface = autoDetect,
        bindInterface = bind,
        anotherPort = { error("a line of one process asks for no second port") },
    )

    private val noName: suspend (String, DirectDns?) -> String = { host, _ -> error("asked for $host") }

    private val log = mutableListOf<String>()

    private val host = DesktopEngineHost(
        log = { line -> synchronized(log) { log += line } },
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) {}
            override fun wayOutChanged(interfaceName: String?) {}
        },
    )

    private fun logText(): String = synchronized(log) { log.joinToString("\n") }

    @Test
    fun aLineIsTheBuildersConfigInASessionOfProtocolOne() = runTest {
        val asked = request(reality)
        val line = HostSession.line(asked, noName) { null }
        assertEquals(CoreInbound.build(asked.spec, CoreInbound.Plan(CoreInbound.Proxy(10808))), line.core)
        assertFalse(line.pinned)
        assertNull(line.bind)

        val session = Json.parseToJsonElement(HostSession.text(line.core, bind = line.bind)).jsonObject
        assertEquals(setOf("version", "core"), session.keys)
        assertEquals(1, session.getValue("version").jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement(line.core), session.getValue("core"))
        // Proxy mode with no login: the port is the device's apps', on loopback.
        val inbound = session.getValue("core").jsonObject.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
        assertEquals(10808, inbound.getValue("port").jsonPrimitive.int)
        assertEquals("noauth", inbound.getValue("settings").jsonObject.getValue("auth").jsonPrimitive.content)
    }

    @Test
    fun theLoginTheSessionNamesIsDemanded() = runTest {
        val login = SocksLogin("session-user", "session-pass")
        val line = HostSession.line(request(reality, login = login), noName) { null }
        val settings = Json.parseToJsonElement(line.core).jsonObject.getValue("inbounds").jsonArray.single()
            .jsonObject.getValue("settings").jsonObject
        assertEquals("password", settings.getValue("auth").jsonPrimitive.content)
        val account = settings.getValue("accounts").jsonArray.single().jsonObject
        assertEquals("session-user", account.getValue("user").jsonPrimitive.content)
        assertEquals("session-pass", account.getValue("pass").jsonPrimitive.content)
    }

    @Test
    fun lanSharingWritesTheLanInboundWithItsLoginIntoTheLinesCoreAndNoneWhenOff() = runTest {
        val sharing = DesktopSocksProxySettings(
            shareOnLan = true,
            lanAddress = "192.168.50.10",
            lanNetworkId = "192.168.50.1|aa:bb:cc:dd:ee:ff",
            lanPort = 10818,
            lanUsername = "ghostlane-guest",
            lanPassword = "generated-lan-password",
        )
        val asked = request(reality)

        val off = HostSession.line(asked, noName, sharing.copy(shareOnLan = false)) { null }
        assertNull(off.lan)
        assertEquals(CoreInbound.build(asked.spec, CoreInbound.Plan(CoreInbound.Proxy(10808))), off.core)
        val offInbounds = Json.parseToJsonElement(off.core).jsonObject.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals(listOf(CoreInbound.TAG), offInbounds.map { it.getValue("tag").jsonPrimitive.content })
        assertFalse("ghostlane-guest" in off.core)
        assertFalse("generated-lan-password" in off.core)

        val on = HostSession.line(asked, noName, sharing) { null }
        assertEquals(
            CoreInbound.build(
                asked.spec,
                CoreInbound.Plan(
                    CoreInbound.Proxy(10808),
                    CoreInbound.Lan("192.168.50.10", 10818, SocksLogin("ghostlane-guest", "generated-lan-password")),
                ),
            ),
            on.core,
        )
        val inbounds = Json.parseToJsonElement(on.core).jsonObject.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals(listOf(CoreInbound.TAG, CoreInbound.LAN_TAG), inbounds.map { it.getValue("tag").jsonPrimitive.content })
        val lan = inbounds.last()
        assertEquals("socks", lan.getValue("protocol").jsonPrimitive.content)
        assertEquals("192.168.50.10", lan.getValue("listen").jsonPrimitive.content)
        assertEquals(10818, lan.getValue("port").jsonPrimitive.int)
        val settings = lan.getValue("settings").jsonObject
        assertEquals("password", settings.getValue("auth").jsonPrimitive.content)
        assertTrue(settings.getValue("udp").jsonPrimitive.boolean)
        assertTrue(settings.getValue("allowUnauthenticatedLan").jsonPrimitive.boolean)
        val account = settings.getValue("accounts").jsonArray.single().jsonObject
        assertEquals("ghostlane-guest", account.getValue("user").jsonPrimitive.content)
        assertEquals("generated-lan-password", account.getValue("pass").jsonPrimitive.content)

        val badAddress = assertFailsWith<IllegalStateException> {
            HostSession.line(asked, noName, sharing.copy(lanAddress = "0.0.0.0")) { null }
        }
        assertEquals("${HostSession.REFUSED}: ${CoreInbound.LAN_ADDRESS_REFUSAL}", badAddress.message)
        val blankLogin = assertFailsWith<IllegalStateException> {
            HostSession.line(asked, noName, sharing.copy(lanPassword = " ")) { null }
        }
        assertEquals("${HostSession.REFUSED}: ${CoreInbound.LAN_LOGIN_REFUSAL}", blankLogin.message)
        val clash = assertFailsWith<IllegalStateException> {
            HostSession.line(request(reality, port = 10818), noName, sharing) { null }
        }
        assertEquals("${HostSession.REFUSED}: ${CoreInbound.LAN_PORT_REFUSAL}", clash.message)
    }

    @Test
    fun theEngineReadsTheSessionsLanSettingsAndSkipsAnUntrustedNetwork() = runTest {
        if (!posix()) return@runTest
        val program = standIn(protocol = 1, run = SERVES)
        val sharing = DesktopSocksProxySettings(
            shareOnLan = true,
            lanAddress = "192.168.50.10",
            lanNetworkId = "192.168.50.1|aa:bb:cc:dd:ee:ff",
            lanPort = 10818,
            lanUsername = "ghostlane-guest",
            lanPassword = "generated-lan-password",
        )
        val trusted = HostEngine(
            host,
            binary = { program },
            serverAddress = noName,
            sessionParent = sessions,
            lanSettings = { sharing },
            lanNetworkProblem = { null },
            lanPortHeld = { false },
        )
        val started = trusted.line.start(request(reality, port = 41080))
        try {
            val written = sessions.toFile().listFiles()!!.single().resolve(HostProcess.SESSION_FILE).readText()
            val tags = Json.parseToJsonElement(written).jsonObject.getValue("core").jsonObject
                .getValue("inbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
            assertEquals(listOf(CoreInbound.TAG, CoreInbound.LAN_TAG), tags)
        } finally {
            trusted.line.stopNow()
        }
        assertFalse(started.alive())

        val untrusted = HostEngine(
            host,
            binary = { program },
            serverAddress = noName,
            sessionParent = sessions,
            lanSettings = { sharing },
            lanNetworkProblem = { "This is a different local network; select the LAN interface again before sharing" },
            lanPortHeld = { false },
        )
        val withoutLan = untrusted.line.start(request(reality, port = 41080))
        try {
            val written = sessions.toFile().listFiles()!!.single().resolve(HostProcess.SESSION_FILE).readText()
            val tags = Json.parseToJsonElement(written).jsonObject.getValue("core").jsonObject
                .getValue("inbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
            assertEquals(listOf(CoreInbound.TAG), tags)
        } finally {
            untrusted.line.stopNow()
        }
        assertFalse(withoutLan.alive())
        assertTrue("different local network" in logText(), logText())
    }

    /**
     * A LAN port held by another program, or a LAN plan the core refuses, costs
     * LAN sharing alone. The line still starts, and the session says why there
     * is no LAN inbound for the proxy to bind.
     */
    @Test
    fun aHeldOrRefusedLanPortLeavesTheLineRunningWithoutLan() = runTest {
        if (!posix()) return@runTest
        val program = standIn(protocol = 1, run = SERVES)
        val sharing = DesktopSocksProxySettings(
            shareOnLan = true,
            lanAddress = "192.168.50.10",
            lanNetworkId = "192.168.50.1|aa:bb:cc:dd:ee:ff",
            lanPort = 10818,
            lanUsername = "ghostlane-guest",
            lanPassword = "generated-lan-password",
        )
        fun writtenTags(): List<String> = sessions.toFile().listFiles()!!.single()
            .resolve(HostProcess.SESSION_FILE).readText()
            .let { Json.parseToJsonElement(it).jsonObject.getValue("core").jsonObject }
            .getValue("inbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }

        val held = HostEngine(
            host,
            binary = { program },
            serverAddress = noName,
            sessionParent = sessions,
            lanSettings = { sharing },
            lanNetworkProblem = { null },
            lanPortHeld = { true },
        )
        val withHeldPort = held.line.start(request(reality, port = 41080))
        try {
            assertEquals(listOf(CoreInbound.TAG), writtenTags(), "the line is written without the LAN inbound")
            assertNull(LanCores.currentLine().lan)
            assertTrue("already in use" in LanCores.currentLine().reason.orEmpty(), "the reason is kept for the proxy")
            assertTrue("LAN sharing was left out" in logText(), logText())
        } finally {
            held.line.stopNow()
        }
        assertFalse(withHeldPort.alive())

        val refused = HostEngine(
            host,
            binary = { program },
            serverAddress = noName,
            sessionParent = sessions,
            lanSettings = { sharing.copy(lanPort = 41080) },
            lanNetworkProblem = { null },
            lanPortHeld = { false },
        )
        val withRefusedPlan = refused.line.start(request(reality, port = 41080))
        try {
            assertEquals(listOf(CoreInbound.TAG), writtenTags(), "the line is written without the LAN inbound")
            assertEquals(CoreInbound.LAN_PORT_REFUSAL, LanCores.currentLine().reason)
        } finally {
            refused.line.stopNow()
        }
        assertFalse(withRefusedPlan.alive())
    }

    /**
     * Asked of the resolver the session names, before anything runs, and the
     * engine is given the address: the core would ask the system's resolver,
     * which behind a tun is the tun's.
     */
    @Test
    fun aServerNamedByAHostnameIsAskedOfTheSessionsResolverAndPinned() = runTest {
        val asked = mutableListOf<Pair<String, DirectDns?>>()
        val resolver = DirectDns.Servers(listOf("198.51.100.53"))
        val request = request(named, resolver = resolver)
        val line = HostSession.line(request, { name, on -> asked += name to on; "192.0.2.9" }) { null }
        assertEquals(listOf<Pair<String, DirectDns?>>("server.example.test" to resolver), asked)
        assertTrue(line.pinned)
        assertEquals(
            CoreInbound.build(CoreLines.pinHost(request.spec, "192.0.2.9"), CoreInbound.Plan(CoreInbound.Proxy(10808))),
            line.core
        )
        assertTrue("\"192.0.2.9\"" in line.core, "the address is not in the core half")
        assertFalse("server.example.test" in line.core, "the core half still names the server by its name")
    }

    @Test
    fun aNameThatDoesNotResolveStartsNoEngine() = runTest {
        var programs = 0
        val engine = HostEngine(
            host,
            binary = { programs++; error("no program in this test") },
            serverAddress = { _, _ -> error("the server's name did not resolve") },
        )
        val failed = assertFailsWith<IllegalStateException> { engine.line.start(request(named)) }
        assertEquals("the server's name did not resolve", failed.message)
        assertEquals(0, programs, "a program was looked for before the line was written")
    }

    @Test
    fun aLineTheCoreRefusesStartsNoEngine() = runTest {
        var programs = 0
        val engine = HostEngine(host, binary = { programs++; error("no program in this test") }, serverAddress = noName)
        val blocked = Routing.Rules(scratch.toString(), DirectDns.System, Routing.Policy.BlockedOnly)
        val onlyBlocked = assertFailsWith<IllegalStateException> { engine.line.start(request(reality, routing = blocked)) }
        // The list is the app's to give (HostGeodata); what the core refuses here is a resolver it cannot name.
        assertEquals(
            "${HostSession.REFUSED}: the Rust core cannot name the system resolver: give it an address",
            onlyBlocked.message
        )
        val noPort = assertFailsWith<IllegalStateException> { engine.line.start(request(reality, port = 0)) }
        assertEquals("${HostSession.REFUSED}: ${CoreInbound.PORT_REFUSAL}", noPort.message)
        assertEquals(0, programs)
    }

    @Test
    fun besideTheLinuxTunTheSocketsAreBound() = runTest {
        val found = HostSession.line(request(reality, autoDetect = true), noName) { "eth7" }
        assertEquals("eth7", found.bind)
        val session = Json.parseToJsonElement(HostSession.text(found.core, bind = found.bind)).jsonObject
        assertEquals("eth7", session.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        assertEquals("wlan3", HostSession.line(request(reality, autoDetect = true, bind = "wlan3"), noName) { "eth7" }.bind)
        assertNull(HostSession.line(request(reality), noName) { "eth7" }.bind)
    }

    @Test
    fun anIpv4AddressIsPreferred() {
        assertEquals("192.0.2.1", HostServerNames.pick(listOf("2001:db8::1", "192.0.2.1")))
        assertEquals("2001:db8::1", HostServerNames.pick(listOf("2001:db8::1")))
        assertEquals("fe80::1", HostServerNames.pick(listOf("fe80::1%en0")))
        assertNull(HostServerNames.pick(emptyList()))
    }

    /** By datagram from this process to the resolver the session names, not through the system's. */
    @Test
    fun theNameIsAskedOfTheNamedResolver() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { resolver ->
            val answering = Thread { answerOneName(resolver, "server.example.test", byteArrayOf(192.toByte(), 0, 2, 44)) }
            answering.isDaemon = true
            answering.start()
            assertEquals(listOf("192.0.2.44"), HostServerNames.ask("127.0.0.1", "server.example.test", resolver.localPort))
        }
    }

    @Test
    fun theSessionFileIsTheUsersAlone() {
        if (!posix()) return
        val directory = HostProcess.privateDirectory(scratch)
        val file = HostProcess.writeSession(directory, "{}")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("{}", file.toFile().readText())
    }

    /** A run as the engine's contract has it: ready, served until its stdin closes, ended, its session gone. */
    @Test
    fun aRunServesUntilItsStdinClosesAndLeavesNoSession() {
        if (!posix()) return
        val program = standIn(protocol = 1, run = SERVES)
        val events = mutableListOf<HostEvent>()
        val run = HostProcess.run(program, """{"version":1,"core":{}}""", { synchronized(events) { events += it } }, {}, sessions)
        try {
            val start = run.awaitStart(10_000)
            assertIs<HostProcess.Start.Ready>(start)
            assertEquals("127.0.0.1:41080", start.inbounds.single().address)
            assertTrue(run.isAlive())
            assertEquals("""{"version":1,"core":{}}""", run.sessionFile.toFile().readText())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(run.sessionFile)))
        } finally {
            run.stop()
        }
        assertFalse(run.isAlive())
        assertEquals(0, run.exitCode())
        assertTrue(run.stopAsked)
        assertEquals(HostEndReason.StdinClosed, run.ended?.reason)
        assertFalse(Files.exists(run.sessionFile.parent), "the session's directory outlived the run")
        assertTrue(synchronized(events) { events.any { it is HostEvent.Log && it.line == "standing in" } })
    }

    @Test
    fun aRunThatEndsAtOnceSaysWhy() {
        if (!posix()) return
        val run = HostProcess.run(standIn(protocol = 1, run = FAILS), """{"version":1,"core":{}}""", {}, {}, sessions)
        val start = assertIs<HostProcess.Start.Ended>(run.awaitStart(10_000))
        assertEquals(HostFailure.Start, start.ended?.code)
        assertEquals("the core could not start: address in use", start.ended?.message)
        assertEquals(1, start.exitCode)
        run.stop()
        assertFalse(Files.exists(run.sessionFile.parent))
    }

    /** The line of a session on the engine, from the start to the stop, with the stand-in as the engine. */
    @Test
    fun aLineIsStartedAndStoppedThroughTheEngine() = runTest {
        if (!posix()) return@runTest
        val program = standIn(protocol = 1, run = SERVES)
        val engine = HostEngine(host, binary = { program }, serverAddress = noName, sessionParent = sessions)
        val started = engine.line.start(request(reality, port = 41080))
        assertTrue(started.alive())
        assertTrue(engine.line.anyRunning())
        assertNull(started.exitCode())
        assertTrue(engine.line.runningProcesses().isNotEmpty())
        assertTrue("ghostlane-engine (Vless) starting on 127.0.0.1:41080" in started.said, started.said)
        engine.line.stopNow()
        assertFalse(started.alive())
        assertFalse(engine.line.anyRunning())
        assertEquals(0, started.exitCode())
        val said = logText()
        assertTrue("ghostlane-engine 0.10.0 at 0123456789ab" in said, said)
        assertTrue("ghostlane-engine: core: standing in" in said, said)
        assertTrue("ghostlane-engine: ended (stdin_closed)" in said, said)
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList(), "a session file outlived its run")
    }

    @Test
    fun aStartTheEngineRefusesFailsWithTheEnginesSentence() = runTest {
        if (!posix()) return@runTest
        val program = standIn(protocol = 1, run = FAILS)
        val engine = HostEngine(host, binary = { program }, serverAddress = noName, sessionParent = sessions)
        val failed = assertFailsWith<IllegalStateException> { engine.line.start(request(reality)) }
        assertEquals("ghostlane-engine did not start the line: start: the core could not start: address in use", failed.message)
        assertFalse(engine.line.anyRunning())
    }

    @Test
    fun aProgramOfAnotherProtocolIsNotRun() = runTest {
        if (!posix()) return@runTest
        val program = standIn(protocol = 2, run = SERVES)
        val engine = HostEngine(host, binary = { program }, serverAddress = noName, sessionParent = sessions)
        val failed = assertFailsWith<IllegalStateException> { engine.line.start(request(reality)) }
        assertEquals("ghostlane-engine reads protocol 2; this app writes protocol 1", failed.message)
        assertFalse(engine.line.anyRunning())
    }

    private fun posix(): Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    /** A program that answers `version` with [protocol] and `run` as [run] says, as the engine prints its lines. */
    private fun standIn(protocol: Int, run: List<String>): Path {
        val name = "stand-in-$protocol-" + (if (run === SERVES) "serves" else "fails")
        val script = listOf(
            "#!/bin/sh",
            "case \"${'$'}1\" in",
            "  version)",
            "    echo '{\"event\":\"version\",\"engine\":\"0.10.0\",\"protocol\":$protocol," +
                "\"pin\":\"0123456789abcdef0123456789abcdef01234567\",\"boringssl\":true,\"rooms\":true,\"target\":\"stand-in\"}'",
            "    ;;",
            "  run)",
            "    [ \"${'$'}2\" = --session ] && [ -s \"${'$'}3\" ] || exit 2",
        ) + run + listOf(
            "    ;;",
            "  *) exit 2 ;;",
            "esac",
        )
        val file = programs.resolve(name).toFile()
        file.writeText(script.joinToString("\n", postfix = "\n"))
        file.setExecutable(true, true)
        return file.toPath()
    }

    /**
     * One answer per question: an A record of [address] for [name], an empty
     * answer for anything else, until the socket is closed.
     */
    private fun answerOneName(socket: DatagramSocket, name: String, address: ByteArray) {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }.getOrElse { return }
            val query = packet.data.copyOf(packet.length)
            // The question: labels from byte 12, then its type and class.
            var at = 12
            val labels = mutableListOf<String>()
            while (query[at].toInt() != 0) {
                val length = query[at].toInt()
                labels += String(query, at + 1, length, Charsets.US_ASCII)
                at += length + 1
            }
            val type = ((query[at + 1].toInt() and 0xff) shl 8) or (query[at + 2].toInt() and 0xff)
            val questionEnd = at + 5
            val answers = labels.joinToString(".").equals(name, ignoreCase = true) && type == 1
            val reply = java.io.ByteArrayOutputStream()
            reply.write(query, 0, 2) // the question's id
            reply.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, if (answers) 1 else 0, 0, 0, 0, 0))
            reply.write(query, 12, questionEnd - 12)
            if (answers) {
                reply.write(byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
                reply.write(address)
            }
            val bytes = reply.toByteArray()
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
        }
    }

    private companion object {
        /** `run` that serves: ready, a log line, then held until stdin closes, then ended. */
        val SERVES = listOf(
            """    echo '{"event":"ready","inbounds":[{"tag":"in","address":"127.0.0.1:41080"}]}'""",
            """    echo '{"event":"log","source":"core","level":"info","line":"standing in"}'""",
            "    cat > /dev/null",
            """    echo '{"event":"ended","reason":"stdin_closed","code":null,"message":null}'""",
        )

        /** `run` that fails at once, as the engine does when its port is taken. */
        val FAILS = listOf(
            """    echo '{"event":"ended","reason":"failed","code":"start","message":"the core could not start: address in use"}'""",
            "    exit 1",
        )
    }
}
