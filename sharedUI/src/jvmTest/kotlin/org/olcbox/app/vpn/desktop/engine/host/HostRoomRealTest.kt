package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assume
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.Routing
import org.olcbox.app.net.toRules
import org.olcbox.app.vpn.desktop.engine.RoomFront
import org.olcbox.app.vpn.desktop.engine.RoomLaunch
import org.olcbox.app.vpn.desktop.engine.RoomRequest
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A room started through the desktop's own room engine ([HostRoom]) on the
 * real ghostlane-engine, the Core build's program at the pin: the command the
 * manager runs, with the session file the manager writes, read as the manager
 * reads it.
 *
 * The only carrier reached is the `none` carrier of the engine's interop pipe,
 * a hub on loopback that reads the participant's JOIN and ends the conference.
 * The app has no `none` carrier of its own, so the session is the app's, with
 * that one field swapped: the carrier and its room. No olcRTC server stands in
 * the hub, so a room can never come ready here. What is proven is that the app's
 * room session is one the engine accepts (`check-config`, the front in it
 * included), that the engine joins the room the session names, and that its
 * failure reaches the app as the manager reads it: the engine's own `room`
 * error, [HostRoom]'s `saysFailed`, and a process that ends. A room that
 * serves, and the front before it, are the room-front check's.
 *
 * Run by scripts/desktop-checks/room.sh (rust-desktop.yml), the program being
 * [HostAssets.ENV]'s. Anywhere else each test is skipped, and room.sh fails a
 * run in which one was skipped or did not run.
 */
class HostRoomRealTest {
    private lateinit var work: Path
    private lateinit var binary: Path

    @BeforeTest
    fun onlyUnderTheCheck() {
        Assume.assumeTrue("set by scripts/desktop-checks/room.sh", !System.getenv(CHECK_ENV).isNullOrBlank())
        binary = Path.of(requireNotNull(System.getenv(HostAssets.ENV)) { "${HostAssets.ENV} names the program" })
        work = Files.createTempDirectory("host-room-real")
    }

    @AfterTest
    fun clean() {
        if (::work.isInitialized) work.toFile().deleteRecursively()
    }

    private val location = LocationConfig(
        id = "tcp://127.0.0.1:1/unused",
        key = KEY,
        bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
        transport = LocationConfig.TRANSPORT_DATACHANNEL,
    ).normalized()

    private fun rules(mode: RoutingMode): Routing.Rules =
        RoutingSettings(mode = mode, tunnelRules = listOf("example.org"))
            .toRules("unused-on-the-rust-core", DirectDns.Servers(listOf("192.0.2.53"))) as Routing.Rules

    private fun request(front: RoomFront?) = RoomRequest(
        location = location,
        socksHost = "127.0.0.1",
        socksPort = 0,
        socksUser = "room-user",
        socksPass = "room-pass",
        dnsServer = "192.0.2.53:53",
        dtlsProfile = OlcrtcDtls.OFF,
        deviceId = "room-check",
        front = front,
    )

    /** The room engine's launch of [front], and the session it writes with the carrier swapped for the hub's room. */
    private fun launched(front: RoomFront?, hubUrl: String): Pair<RoomLaunch, Path> {
        val launch = HostRoom(binary = { binary }).launch(binary, request(front))
        val session = Json.parseToJsonElement(launch.config()).jsonObject
        val room = session.getValue("room").jsonObject
        val swapped = JsonObject(
            (room - "transport" - "dtls_profile") + mapOf(
                "provider" to JsonPrimitive("none"),
                "rooms" to JsonArray(listOf(JsonPrimitive(hubUrl))),
            )
        )
        val directory = HostProcess.privateDirectory(work)
        val file = HostProcess.writeSession(directory, JsonObject(session + ("room" to swapped)).toString())
        return launch to file
    }

    private fun checkConfig(file: Path): HostEvent.CheckConfig {
        val events = HostProcess.ask(binary, listOf("check-config", "--session", file.toString()), 60_000L)
        return assertIs<HostEvent.CheckConfig>(events.firstOrNull { it is HostEvent.CheckConfig }, "check-config printed $events")
    }

    private fun assertAccepted(file: Path, what: String) {
        val verdict = checkConfig(file)
        val errors = verdict.diagnostics.filter { it.severity == HostSeverity.Error }
        assertTrue(verdict.ok && errors.isEmpty(), "$what: check-config refused it: ${errors.map { "${it.path}: ${it.message}" }}")
    }

    /** Runs the launch's command as the manager does, and returns every line the engine printed until it ended. */
    private fun run(launch: RoomLaunch, file: Path): List<String> {
        val child = ProcessBuilder(launch.args(file)).redirectErrorStream(true).start()
        val lines = LinkedBlockingQueue<String>()
        val reader = Thread({
            runCatching { child.inputStream.bufferedReader().useLines { it.forEach(lines::add) } }
        }, "room-check-reader").apply { isDaemon = true; start() }
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "the engine did not end after a room that ended at once")
            reader.join(5_000)
            assertTrue(child.exitValue() != 0, "a room that failed ended with status 0")
            return lines.toList()
        } finally {
            child.destroyForcibly()
        }
    }

    private fun assertFailsAsTheManagerReadsIt(launch: RoomLaunch, printed: List<String>) {
        assertFalse(printed.any(launch::saysReady), "a room that cannot join said it was ready: $printed")
        val failure = printed.firstOrNull(launch::saysFailed) ?: fail("no line the manager reads as a failure: $printed")
        val event = HostEvents.parse(failure)
        val code = when (event) {
            is HostEvent.Failed -> event.code
            is HostEvent.Ended -> event.code
            else -> null
        }
        assertEquals(HostFailure.Room, code, "the engine's own (room) error: $failure")
        val said = launch.said(failure)
        assertTrue(said.startsWith("ghostlane-engine: "), said)
        assertFalse(KEY in said || "room-pass" in said, "the failure carries a secret: $said")
    }

    @Test
    fun aRoomWithNoFrontJoinsTheRoomTheSessionNamesAndFailsWithTheEnginesRoomError() {
        Hub().use { hub ->
            val (launch, file) = launched(front = null, hubUrl = hub.url("hr-plain"))
            assertAccepted(file, "the room alone")
            val printed = run(launch, file)
            assertEquals("hr-plain", hub.joined().room, "it joined the room")
            assertFailsAsTheManagerReadsIt(launch, printed)
        }
    }

    @Test
    fun aRoomBehindItsFrontIsTheSameSessionWithTheFrontsCoreAndFailsTheSameWay() {
        Hub().use { hub ->
            val front = RoomFront(port = freePort(), login = null, routing = rules(RoutingMode.Global), verboseLogs = false)
            val (launch, file) = launched(front, hub.url("hr-front"))
            val session = Json.parseToJsonElement(Files.readString(file)).jsonObject
            assertNotNull(session["core"], "the front is the session's core half")
            assertAccepted(file, "the room behind its front")
            val printed = run(launch, file)
            assertEquals("hr-front", hub.joined().room, "it joined the room")
            assertFailsAsTheManagerReadsIt(launch, printed)
        }
    }

    @Test
    fun theFrontOfARegionsBypassIsAcceptedWithItsListsInline() {
        val front = runBlocking { RoomFront.of(freePort(), null, rules(RoutingMode.BypassRussia), verboseLogs = false) }
        val (_, file) = launched(front, "tcp://127.0.0.1:1/hr-bypass")
        assertAccepted(file, "the room behind a bypass front")
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    // ---- the hub ----

    /** One participant of the hub: the room its JOIN named. */
    private class Joined(val room: String)

    /**
     * An interop-pipe hub on loopback: frames of `u32 BE length | kind |
     * payload`, the first of a participant's a JOIN (kind 1) naming its room;
     * the conference is ended at once (kind 5).
     */
    private class Hub : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val joins = LinkedBlockingQueue<Joined>()
        private val open = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        init {
            Thread({
                while (true) {
                    val stream = try { listener.accept() } catch (_: IOException) { break }
                    open += stream
                    Thread({ take(stream) }, "hub").apply { isDaemon = true; start() }
                }
            }, "hub-accept").apply { isDaemon = true; start() }
        }

        fun url(name: String) = "tcp://127.0.0.1:${listener.localPort}/$name"

        private fun take(stream: Socket) {
            try {
                val input = DataInputStream(stream.getInputStream())
                val (kind, room) = frame(input) ?: return
                check(kind == KIND_JOIN) { "a participant's first frame is its JOIN" }
                joins.add(Joined(String(room, Charsets.UTF_8)))
                val reason = "the conference ended".toByteArray()
                val out = stream.getOutputStream()
                out.write(java.nio.ByteBuffer.allocate(4).putInt(1 + reason.size).array())
                out.write(KIND_END)
                out.write(reason)
                out.flush()
                while (frame(input) != null) Unit
            } catch (_: IOException) {
                // The engine left.
            }
        }

        private fun frame(input: DataInputStream): Pair<Int, ByteArray>? = try {
            val length = input.readInt()
            val kind = input.readUnsignedByte()
            Pair(kind, ByteArray(length - 1).also { input.readFully(it) })
        } catch (_: EOFException) {
            null
        }

        fun joined(): Joined = joins.poll(20, TimeUnit.SECONDS) ?: fail("nobody joined a room of the hub within 20 s")

        override fun close() {
            runCatching { listener.close() }
            synchronized(open) { open.forEach { runCatching { it.close() } } }
        }

        companion object {
            const val KIND_JOIN = 1
            const val KIND_END = 5
        }
    }

    private companion object {
        const val CHECK_ENV = "HOST_ROOM_CHECK"
        const val KEY = "4f6c635254432d74657374206b65792c206e6f7420612073656372657421212e"
    }
}
