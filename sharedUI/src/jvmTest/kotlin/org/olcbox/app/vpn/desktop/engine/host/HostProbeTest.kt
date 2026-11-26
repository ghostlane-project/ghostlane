package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.vpn.OlcRtcConnectionChecker
import org.olcbox.app.net.LinkParser
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's probes and room check on ghostlane-engine, without the engine:
 * what the session file holds for a room, a line and a probe, how each
 * one-shot is asked (`olc-check`, `olc-ping`, `probe`) and read, what a
 * question that was not answered says, and the starter smart connect gets,
 * against stand-in programs that speak the engine's contract (on a system
 * with a POSIX shell). The real engine runs in HostProbeRealTest, under
 * scripts/desktop-checks/probe.sh.
 */
class HostProbeTest {
    private val scratch: Path = Files.createTempDirectory("host-probe-test")
    private val programs: Path = Files.createDirectories(scratch.resolve("programs"))
    private val sessions: Path = Files.createDirectories(scratch.resolve("sessions"))
    private val log: Path = scratch.resolve("calls.log")
    private val said = mutableListOf<String>()

    @AfterTest
    fun clean() {
        OlcRtcConnectionChecker.rooms = null
        scratch.toFile().deleteRecursively()
    }

    private val key = "4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
    private val hub = "tcp://127.0.0.1:7000/room-one"

    private fun room(provider: String = "none", dtls: String = OlcrtcDtls.OFF) =
        HostProbe.Room(provider = provider, rooms = listOf(hub), keyHex = key, deviceId = "device-1", dtlsProfile = dtls)

    private fun probe(program: Path, slack: Long = 10_000L) =
        HostProbe(binary = { program }, sessionParent = sessions, log = { synchronized(said) { said += it } }, slackMs = slack)

    private fun posix(): Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    private fun calls(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()

    private fun ok(question: String, millis: Int) = """echo '{"event":"$question","ok":true,"millis":$millis,"error":null}'"""
    private fun no(question: String, why: String) =
        """echo '{"event":"$question","ok":false,"millis":null,"error":"$why"}'; exit 1"""

    /**
     * A program that answers `version` with [protocol] and each other command
     * as its entry of [commands] says (shell lines). Every call is appended
     * to the log: its arguments, the session's mode, and a copy of the
     * session beside the log.
     */
    private fun standIn(protocol: Int = 1, commands: Map<String, List<String>>): Path {
        val body = commands.entries.joinToString("\n") { (name, lines) ->
            "  $name)\n" +
                "    [ \"${'$'}2\" = --session ] && [ -s \"${'$'}3\" ] || exit 2\n" +
                "    echo \"ARGS ${'$'}*\" >> '$log'\n" +
                "    echo \"MODE ${'$'}(ls -l \"${'$'}3\" | cut -c1-10)\" >> '$log'\n" +
                "    cp \"${'$'}3\" '$log.session'\n" +
                lines.joinToString("\n") { "    $it" } + "\n    ;;"
        }
        val script = """#!/bin/sh
case "${'$'}1" in
  version)
    echo "VERSION" >> '$log'
    echo '{"event":"version","engine":"0.10.0","protocol":$protocol,"pin":"0123456789abcdef0123456789abcdef01234567","boringssl":true,"rooms":true,"target":"stand-in"}'
    ;;
$body
  *) exit 2 ;;
esac
"""
        val file = programs.resolve("stand-in-$protocol-${commands.keys.joinToString("-")}-${programs.toFile().list()!!.size}").toFile()
        file.writeText(script)
        file.setExecutable(true, true)
        return file.toPath()
    }

    private fun session(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(Files.readString(Path.of("$log.session"))).jsonObject

    // ---- the room half ----

    @Test
    fun aRoomCheckAsksOlcCheckOverASessionOfItsOwn() {
        if (!posix()) return
        val program = standIn(commands = mapOf("olc-check" to listOf(ok("olc_check", 42))))
        val result = probe(program).check(room(), timeoutMs = 8_000)
        assertEquals(HostProbe.Result(42, null), result)

        val args = calls().single { it.startsWith("ARGS ") }.removePrefix("ARGS ").split(" ")
        assertEquals(listOf("olc-check", "--session"), args.take(2))
        assertEquals(listOf("--timeout-ms", "8000"), args.drop(3))
        assertEquals(sessions, Path.of(args[2]).parent.parent, "the session went under the parent it was given")
        assertTrue(args.none { key in it }, "nothing secret is an argument")
        assertEquals("-rw-------", calls().single { it.startsWith("MODE ") }.removePrefix("MODE "))
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList(), "a session outlived its question")

        val s = session()
        assertEquals(1, s.getValue("version").jsonPrimitive.int)
        assertNull(s["core"], "a room check carries no core half")
        val half = s.getValue("room").jsonObject
        assertEquals("none", half.getValue("provider").jsonPrimitive.content)
        assertEquals(hub, half.getValue("rooms").jsonArray.single().jsonPrimitive.content)
        assertEquals(key, half.getValue("key").jsonPrimitive.content)
        assertEquals("device-1", half.getValue("device_id").jsonPrimitive.content)
        assertNull(half["dtls_profile"], "no handshake profile is asked for when it is off")
    }

    @Test
    fun aRoomThatDidNotAnswerSaysWhy() {
        if (!posix()) return
        val program = standIn(commands = mapOf("olc-check" to listOf(no("olc_check", "the room did not answer: timed out"))))
        assertEquals(HostProbe.Result(null, "the room did not answer: timed out"), probe(program).check(room(), 1_000))
    }

    @Test
    fun aQuestionThatCannotBeAskedSaysItsCodeAndSentence() {
        if (!posix()) return
        val error = """echo '{"event":"error","code":"room","message":"${'$'}.key: must be 64 hex digits (32 bytes)"}'; exit 1"""
        val program = standIn(commands = mapOf("olc-check" to listOf(error)))
        assertEquals(
            HostProbe.Result(null, "room: \$.key: must be 64 hex digits (32 bytes)"),
            probe(program).check(room(), 1_000)
        )
    }

    @Test
    fun aProgramThatSaysNothingInTimeIsNoAnswerAndIsKilled() {
        if (!posix()) return
        val program = standIn(commands = mapOf("olc-check" to listOf("exec sleep 30")))
        val started = System.currentTimeMillis()
        val result = probe(program, slack = 500).check(room(), timeoutMs = 300)
        assertNull(result.millis)
        assertEquals("ghostlane-engine gave no answer within 800 ms", result.reason)
        assertTrue(System.currentTimeMillis() - started < 15_000)
    }

    @Test
    fun aPingAsksOlcPingForTheUrl() {
        if (!posix()) return
        val program = standIn(commands = mapOf("olc-ping" to listOf(ok("olc_ping", 120))))
        val result = probe(program).ping(room(), timeoutMs = 20_000, url = "https://probe.example.test/generate_204")
        assertEquals(HostProbe.Result(120, null), result)
        val args = calls().single { it.startsWith("ARGS ") }.removePrefix("ARGS ").split(" ")
        assertEquals("olc-ping", args[0])
        assertEquals(listOf("--url", "https://probe.example.test/generate_204", "--timeout-ms", "20000"), args.drop(3))
    }

    @Test
    fun aProgramOfAnotherProtocolIsNotAsked() {
        if (!posix()) return
        val program = standIn(protocol = 2, commands = mapOf("olc-check" to listOf(ok("olc_check", 1))))
        val result = probe(program).check(room(), 1_000)
        assertEquals(HostProbe.Result(null, "ghostlane-engine reads protocol 2; this app writes protocol 1"), result)
        assertEquals(listOf("VERSION"), calls(), "nothing but its version was asked")
    }

    @Test
    fun noProgramIsNoAnswerWithItsSentence() {
        val probe = HostProbe(binary = { error("this build carries no ghostlane-engine") }, sessionParent = sessions)
        assertEquals(HostProbe.Result(null, "this build carries no ghostlane-engine"), probe.check(room(), 1_000))
    }

    @Test
    fun theProgramIsVersionedOncePerProgram() {
        if (!posix()) return
        val program = standIn(commands = mapOf("olc-check" to listOf(ok("olc_check", 5))))
        val probe = probe(program)
        probe.check(room(), 1_000)
        probe.check(room(), 1_000)
        assertEquals(1, calls().count { it == "VERSION" })
        assertEquals(2, calls().count { it.startsWith("ARGS ") })
    }

    // ---- a location as a room ----

    private fun location(provider: String, transport: String, vp8Fps: Int = 25, vp8Batch: Int = 8) = LocationConfig(
        name = "room", id = "room-id", key = key, bypassProvider = provider, transport = transport,
        vp8Fps = vp8Fps, vp8Batch = vp8Batch, failoverRoomIds = listOf("room-two"),
    )

    @Test
    fun aLocationIsARoomOfItsCarrierWithItsFailoverRooms() {
        val sj = HostProbe.Room.of(location("salutejazz", "datachannel"), "dev", OlcrtcDtls.OFF).half()
        assertEquals("salutejazz", sj.getValue("provider").jsonPrimitive.content)
        assertEquals("datachannel", sj.getValue("transport").jsonPrimitive.content)
        assertEquals(listOf("room-id", "room-two"), sj.getValue("rooms").jsonArray.map { it.jsonPrimitive.content })
        assertNull(sj["vp8"], "only a VP8 carrier is given its frame settings")
        assertEquals("dev", sj.getValue("device_id").jsonPrimitive.content)

        val vk = HostProbe.Room.of(location("vk", "vp8channel"), "dev", OlcrtcDtls.OFF).half()
        assertEquals("vkcalls", vk.getValue("provider").jsonPrimitive.content)
        assertEquals(25, vk.getValue("vp8").jsonObject.getValue("fps").jsonPrimitive.int)
        assertEquals(8, vk.getValue("vp8").jsonObject.getValue("batch").jsonPrimitive.int)

        val wb = HostProbe.Room.of(location("wb-stream", "datachannel"), "dev", OlcrtcDtls.OFF).half()
        assertEquals("wbstream", wb.getValue("provider").jsonPrimitive.content)

        val profiled = HostProbe.Room.of(location("salutejazz", "datachannel"), "dev", OlcrtcDtls.CHROME).half()
        assertEquals(OlcrtcDtls.CHROME, profiled.getValue("dtls_profile").jsonPrimitive.content)
    }

    // ---- a line ----

    @Test
    fun aLineProbeAsksProbeOverTheCoreAndTheInterface() {
        if (!posix()) return
        val program = standIn(commands = mapOf("probe" to listOf(ok("probe", 77))))
        val core = """{"inbounds":[],"outbounds":[{"tag":"proxy","protocol":"freedom"}]}"""
        val result = probe(program).probe(core, "http://probe.example.test/", timeoutMs = 5_000, bindInterface = "eth0")
        assertEquals(HostProbe.Result(77, null), result)
        val args = calls().single { it.startsWith("ARGS ") }.removePrefix("ARGS ").split(" ")
        assertEquals("probe", args[0])
        assertEquals(listOf("--url", "http://probe.example.test/", "--timeout-ms", "5000"), args.drop(3))
        val s = session()
        assertEquals("proxy", s.getValue("core").jsonObject.getValue("outbounds").jsonArray.single().jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals("eth0", s.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        assertNull(s["room"])
    }

    // ---- the starter smart connect gets ----

    private val serves = listOf(
        "echo '{\"event\":\"ready\",\"inbounds\":[{\"tag\":\"probe\",\"address\":\"127.0.0.1:41080\"}]}'",
        "read _ || true",
        "echo '{\"event\":\"ended\",\"reason\":\"stdin_closed\",\"code\":null,\"message\":null}'",
    )

    private fun runStandIn(run: List<String>): Path {
        val file = programs.resolve("run-${programs.toFile().list()!!.size}").toFile()
        file.writeText(
            "#!/bin/sh\ncase \"${'$'}1\" in\n  version)\n" +
                "    echo '{\"event\":\"version\",\"engine\":\"0.10.0\",\"protocol\":1,\"pin\":\"0123456789abcdef0123456789abcdef01234567\",\"boringssl\":true,\"rooms\":true,\"target\":\"stand-in\"}' ;;\n" +
                "  run)\n    [ \"${'$'}2\" = --session ] && [ -s \"${'$'}3\" ] || exit 2\n    cp \"${'$'}3\" '$log.run'\n" +
                run.joinToString("\n") { "    $it" } + "\n    ;;\n  *) exit 2 ;;\nesac\n"
        )
        file.setExecutable(true, true)
        return file.toPath()
    }

    private val spec get() = requireNotNull(
        LinkParser.parse("vless://11111111-1111-1111-1111-111111111111@192.0.2.7:443?security=none&encryption=none&type=tcp#line")
    )

    @Test
    fun theStarterRunsTheProbesCoreAsAThrowAwayEngineAndStopsIt() = runBlocking {
        if (!posix()) return@runBlocking
        val program = runStandIn(serves)
        val starter = probe(program).starter()
        val config = """{"inbounds":[{"tag":"probe","protocol":"socks","listen":"127.0.0.1","port":41080}],"outbounds":[]}"""
        val core = starter.start(spec, config, "eth0")
        assertTrue(core.isRunning())
        val s = Json.parseToJsonElement(Files.readString(Path.of("$log.run"))).jsonObject
        assertEquals(41080, s.getValue("core").jsonObject.getValue("inbounds").jsonArray.single().jsonObject.getValue("port").jsonPrimitive.int)
        assertEquals("eth0", s.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        core.stop()
        assertFalse(core.isRunning())
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList(), "a session outlived its probe")
    }

    @Test
    fun theStarterFailsWithTheEnginesWordsWhenItDoesNotServe() = runBlocking {
        if (!posix()) return@runBlocking
        val program = runStandIn(
            listOf("echo '{\"event\":\"ended\",\"reason\":\"failed\",\"code\":\"start\",\"message\":\"the core could not start: address in use\"}'", "exit 1")
        )
        val starter = probe(program).starter()
        val failed = assertFailsWith<IllegalStateException> { starter.start(spec, "{}", null) }
        assertEquals("ghostlane-engine did not start the probe: start: the core could not start: address in use", failed.message)
        assertEquals(emptyList(), sessions.toFile().listFiles().orEmpty().toList())
    }

    @Test
    fun theKeptStarterIsStoppedWithTheSession() = runBlocking {
        if (!posix()) return@runBlocking
        val program = runStandIn(serves)
        val engine = probe(program).probes()
        val core = engine.kept.start(spec, "{}")
        assertTrue(core.isRunning())
        engine.stopKept()
        assertFalse(core.isRunning())
        val fresh = engine.fresh.start(spec, "{}")
        assertTrue(fresh.isRunning())
        engine.stopKept()
        assertTrue(fresh.isRunning(), "a fresh probe's core is its own probe's to stop")
        fresh.stop()
    }

    @Test
    fun theStarterAsksForAServersAddressOnTheNetworkUnderneath() = runBlocking {
        val probe = HostProbe(binary = { error("no program") }, serverAddress = { host, _ -> if (host == "named.test") "192.0.2.9" else error("no") })
        val starter = probe.starter()
        assertEquals("192.0.2.9", starter.serverAddress("named.test", null))
        assertNull(starter.serverAddress("other.test", null))
    }

    // ---- the checker, switched to the engine ----

    @Test
    fun theCheckerAsksTheEngineTwiceForACheckAndOnceForAPing() = runBlocking {
        if (!posix()) return@runBlocking
        val program = standIn(
            commands = mapOf(
                "olc-check" to listOf(no("olc_check", "the room did not answer")),
                "olc-ping" to listOf(no("olc_ping", "the room did not answer")),
            )
        )
        OlcRtcConnectionChecker.rooms = probe(program)
        val config = location("salutejazz", "datachannel")
        assertNull(OlcRtcConnectionChecker.check(config, deviceId = "dev"))
        assertEquals(2, calls().count { it.startsWith("ARGS olc-check") })
        assertNull(OlcRtcConnectionChecker.ping(config, deviceId = "dev"))
        assertEquals(1, calls().count { it.startsWith("ARGS olc-ping") })
    }

    @Test
    fun theCheckerGivesTheEnginesMillisAndNeverZeroForACheck() = runBlocking {
        if (!posix()) return@runBlocking
        val program = standIn(
            commands = mapOf(
                "olc-check" to listOf(ok("olc_check", 0)),
                "olc-ping" to listOf(ok("olc_ping", 0)),
            )
        )
        OlcRtcConnectionChecker.rooms = probe(program)
        val config = location("salutejazz", "datachannel")
        assertEquals(1L, OlcRtcConnectionChecker.check(config, deviceId = "dev"), "a check's time is positive: callers read 0 as no")
        assertEquals(0L, OlcRtcConnectionChecker.ping(config, deviceId = "dev"))
        assertEquals(1, calls().count { it.startsWith("ARGS olc-check") })
    }

    @Test
    fun anIncompleteLocationIsNotAskedAnywhere() = runBlocking {
        if (!posix()) return@runBlocking
        val program = standIn(commands = mapOf("olc-check" to listOf(ok("olc_check", 5))))
        OlcRtcConnectionChecker.rooms = probe(program)
        assertNull(OlcRtcConnectionChecker.check(LocationConfig(id = "", key = ""), deviceId = "dev"))
        assertEquals(emptyList(), calls())
        assertNotNull(OlcRtcConnectionChecker.rooms)
    }

    @Test
    fun theStartersKindIsTheRustStarterSmartConnectKnows() {
        val starter: TransportProbe.Starter = probe(Path.of("/nonexistent")).starter()
        assertTrue(starter is TransportProbe.RustStarter)
    }
}
