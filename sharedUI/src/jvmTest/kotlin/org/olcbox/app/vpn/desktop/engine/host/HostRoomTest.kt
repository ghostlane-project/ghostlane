package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreRoomFront
import org.olcbox.app.net.toRules
import org.olcbox.app.vpn.desktop.engine.RoomFront
import org.olcbox.app.vpn.desktop.engine.RoomRequest
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A room on ghostlane-engine without the engine: the session file the manager
 * is handed for it, the command it runs, and how the engine's own lines are
 * read, against stand-in programs. The real engine joins a room in
 * HostRoomRealTest, under scripts/desktop-checks/room.sh.
 */
class HostRoomTest {
    private val scratch: Path = Files.createTempDirectory("host-room-test")

    @AfterTest
    fun clean() {
        scratch.toFile().deleteRecursively()
    }

    private val roomName = "tcp://hub.example:7000/room-a"
    private val key = "ab".repeat(32)
    private val location = LocationConfig(
        id = roomName,
        key = key,
        bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
        transport = LocationConfig.TRANSPORT_DATACHANNEL,
    ).normalized()

    private fun request(front: RoomFront? = null, user: String = "room-user", pass: String = "room-pass") = RoomRequest(
        location = location,
        socksHost = "127.0.0.1",
        socksPort = 10808,
        socksUser = user,
        socksPass = pass,
        dnsServer = "192.0.2.53:53",
        directRulesFile = null,
        dtlsProfile = OlcrtcDtls.OFF,
        deviceId = "device-1",
        front = front,
    )

    private fun rules(mode: RoutingMode): Routing.Rules =
        RoutingSettings(mode = mode, tunnelRules = listOf("example.org"))
            .toRules("unused-on-the-rust-core", DirectDns.Servers(listOf("192.0.2.53"))) as Routing.Rules

    private fun session(request: RoomRequest, bind: String? = null, geodataDir: String? = null): JsonObject =
        Json.parseToJsonElement(HostRoomSession.text(request, bind, geodataDir)).jsonObject

    private fun posix(): Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    @Test
    fun aRoomWithoutAFrontIsARoomHalfAloneAndKeepsPrivateAddressesOffTheRoom() {
        val text = session(request())
        assertEquals(setOf("version", "room"), text.keys)
        assertEquals(1, text.getValue("version").jsonPrimitive.int)
        val room = text.getValue("room").jsonObject
        assertEquals("wbstream", room.getValue("provider").jsonPrimitive.content)
        assertEquals(listOf(roomName), room.getValue("rooms").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(key, room.getValue("key").jsonPrimitive.content)
        assertEquals("device-1", room.getValue("device_id").jsonPrimitive.content)
        assertEquals("192.0.2.53:53", room.getValue("dns").jsonPrimitive.content)
        assertTrue(room.getValue("udp").jsonPrimitive.boolean)
        val socks = room.getValue("socks").jsonObject
        assertEquals(10808, socks.getValue("port").jsonPrimitive.int)
        assertEquals("room-user", socks.getValue("user").jsonPrimitive.content)
        assertEquals("room-pass", socks.getValue("password").jsonPrimitive.content)
        assertTrue("10.0.0.0/8" in room.getValue("direct_rules").jsonPrimitive.content)
    }

    @Test
    fun theFrontIsTheSessionsCoreHalfOnItsOwnPortWithTheRoomBehindIt() {
        val front = RoomFront(port = 20808, login = null, routing = rules(RoutingMode.Global), verboseLogs = false)
        val text = session(request(front), bind = "eth0")
        assertEquals(setOf("version", "core", "room", "bind"), text.keys)
        assertEquals("eth0", text.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        val core = text.getValue("core").jsonObject
        val inbound = core.getValue("inbounds").jsonArray.single().jsonObject
        assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
        assertEquals(20808, inbound.getValue("port").jsonPrimitive.int)
        val toTheRoom = core.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals(CoreRoomFront.ROOM, toTheRoom.getValue("tag").jsonPrimitive.content)
        val settings = toTheRoom.getValue("settings").jsonObject
        assertEquals("room-user", settings.getValue("user").jsonPrimitive.content)
        assertEquals("room-pass", settings.getValue("pass").jsonPrimitive.content)
        // The room keeps its own port and login behind the front, and the front
        // does what a direct list would have done.
        val room = text.getValue("room").jsonObject
        assertEquals(10808, room.getValue("socks").jsonObject.getValue("port").jsonPrimitive.int)
        assertNull(room["direct_rules"], "a fronted room carries no rules of its own")
    }

    @Test
    fun aFrontThatTheCoreRefusesIsRefusedBeforeAnythingIsWritten() {
        val blocked = RoomFront(20808, null, rules(RoutingMode.BlockedOnly), verboseLogs = false)
        val said = assertFailsWith<IllegalStateException> { HostRoomSession.text(request(blocked), null) }
        assertTrue(CoreConfig.BLOCKED_ONLY_REFUSAL in said.message.orEmpty(), said.message)
        assertTrue(HostSession.REFUSED in said.message.orEmpty(), said.message)

        // With Re:filter's list in a geodata directory (LR) the same front is carried, and the session names the directory.
        val withList = session(request(blocked), null, geodataDir = "/data/geodata")
        assertEquals("/data/geodata", withList.getValue("geodata_dir").jsonPrimitive.content)
        assertTrue(HostRoomSession.blockedOnly(request(blocked)))
        assertFalse(HostRoomSession.blockedOnly(request(RoomFront(20808, null, rules(RoutingMode.Global), verboseLogs = false))))
        assertFalse(HostRoomSession.blockedOnly(request()))

        val front = RoomFront(20808, null, rules(RoutingMode.Global), verboseLogs = false)
        val blank = assertFailsWith<IllegalStateException> { HostRoomSession.text(request(front, user = "", pass = ""), null) }
        assertTrue(CoreRoomFront.ROOM_LOGIN_REFUSAL in blank.message.orEmpty(), blank.message)
    }

    @Test
    fun aRoomOnThisEngineRunsAsTheUserAndCarriesItsFront() {
        val room = HostRoom(binary = { Path.of("unused") })
        assertFalse(room.rootBesideLinuxTun)
        assertTrue(room.frontInRoom)
    }

    @Test
    fun theCommandIsRunWithTheSessionFileAndNothingSecretIsAnArgument() {
        if (!posix()) return
        val program = standIn(protocol = 1)
        val engine = HostRoom(binary = { program }, defaultInterface = { "eth9" })
        val launch = engine.launch(program, request(bindToWayOut = true))
        val path = scratch.resolve("session.json")
        assertEquals(listOf(program.toString(), "run", "--session", path.toString()), launch.args(path))
        for (word in launch.args(path)) assertFalse(key in word || "room-pass" in word, "a secret is an argument: $word")
        val text = Json.parseToJsonElement(launch.config()).jsonObject
        assertEquals("eth9", text.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        assertEquals("wbstream", launch.provider)
    }

    @Test
    fun aProgramOfAnotherProtocolIsNotLaunched() {
        if (!posix()) return
        val program = standIn(protocol = 2)
        val failed = assertFailsWith<IllegalStateException> { HostRoom(binary = { program }).launch(program, request()) }
        assertEquals("ghostlane-engine reads protocol 2; this app writes protocol 1", failed.message)
    }

    @Test
    fun theEnginesLinesAreReadAsReadyAsFailedAndAsTheLogSaysThem() {
        if (!posix()) return
        val program = standIn(protocol = 1)
        val launch = HostRoom(binary = { program }).launch(program, request())
        val ready = """{"event":"ready","inbounds":[{"tag":"room","address":"127.0.0.1:10808"}]}"""
        val log = """{"event":"log","source":"room","level":"info","line":"joined"}"""
        val failed = """{"event":"error","code":"room","message":"the room did not answer the handshake"}"""
        val endedWell = """{"event":"ended","reason":"stdin_closed","code":null,"message":null}"""
        val endedBadly = """{"event":"ended","reason":"failed","code":"room","message":"gone"}"""
        assertTrue(launch.saysReady(ready))
        assertFalse(launch.saysReady(log) || launch.saysReady(failed) || launch.saysReady("not json"))
        assertTrue(launch.saysFailed(failed) && launch.saysFailed(endedBadly))
        assertFalse(launch.saysFailed(ready) || launch.saysFailed(log) || launch.saysFailed(endedWell) || launch.saysFailed("Segmentation fault"))
        assertEquals("ghostlane-engine: failed: room: the room did not answer the handshake", launch.said(failed))
        assertEquals("ghostlane-engine: room: joined", launch.said(log))
        assertEquals("Segmentation fault", launch.said("Segmentation fault"), "what is not an event is printed as it came")
    }

    @Test
    fun theStartTheManagerRunsReachesReadyOnAStandInThatServes() {
        if (!posix()) return
        val program = standIn(protocol = 1)
        val launch = HostRoom(binary = { program }).launch(program, request())
        val file = scratch.resolve("room-session.json")
        Files.writeString(file, launch.config())
        val child = ProcessBuilder(launch.args(file)).redirectErrorStream(true).start()
        try {
            val first = child.inputStream.bufferedReader().readLine()
            assertTrue(launch.saysReady(first), first)
        } finally {
            child.destroyForcibly()
        }
    }

    private fun request(bindToWayOut: Boolean) = request().copy(bindToWayOut = bindToWayOut)

    /** A program that answers `version` with [protocol], and `run` with a `ready` line when it is given a session file. */
    private fun standIn(protocol: Int): Path {
        val file = scratch.resolve("stand-in-$protocol").toFile()
        file.writeText(
            listOf(
                "#!/bin/sh",
                "case \"${'$'}1\" in",
                "  version)",
                "    echo '{\"event\":\"version\",\"engine\":\"0.10.0\",\"protocol\":$protocol," +
                    "\"pin\":\"0123456789abcdef0123456789abcdef01234567\",\"boringssl\":true,\"rooms\":true,\"target\":\"stand-in\"}'",
                "    ;;",
                "  run)",
                "    [ \"${'$'}2\" = --session ] && [ -s \"${'$'}3\" ] || exit 2",
                "    echo '{\"event\":\"ready\",\"inbounds\":[{\"tag\":\"room\",\"address\":\"127.0.0.1:10808\"}]}'",
                "    cat > /dev/null",
                "    ;;",
                "  *) exit 2 ;;",
                "esac",
            ).joinToString("\n", postfix = "\n")
        )
        file.setExecutable(true, true)
        return file.toPath()
    }
}
