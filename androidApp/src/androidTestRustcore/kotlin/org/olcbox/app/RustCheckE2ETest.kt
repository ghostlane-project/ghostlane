package org.olcbox.app

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.olcbox.app.vpn.OlcRtcConnectionChecker
import org.olcbox.app.vpn.RoomProbeResult
import org.olcbox.app.vpn.RoomRequest
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.RustCore

/**
 * The app's room check and probe (OlcRtcConnectionChecker) on the Rust core, in the
 * app's own process: the dev switch is turned on in the VPN preferences, the way the
 * service reads it, and each call joins its room through the core's olcRTC client.
 *
 * Every room is a stand-in hub of the carrier `none` ([PipeHub]: the interop pipe,
 * `tcp://127.0.0.1:<port>/<room>`), which shows the room reached and the call ending
 * there, a timeout kept, and a refusal before any room. A check that succeeds needs an
 * olcRTC server in the room, which no test here has. No carrier, no secret, no network.
 *
 * The first test makes the first room check this process makes: the test classes run in
 * one process, in the order of their names, and this class sorts before the service's
 * (RustCoreVpnE2ETest), and its methods run by name. The signalling's TLS is installed by
 * the first call of a process: a check before any start is the case that failed without it.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RustCheckE2ETest {
    companion object {
        private val key = "0123456789abcdef".repeat(4)
        private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var hub: PipeHub

        private fun say(line: String) {
            Log.i("RustCoreE2E", line)
        }

        @BeforeClass
        @JvmStatic
        fun switchOn() {
            runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = true } }
            hub = PipeHub()
        }

        @AfterClass
        @JvmStatic
        fun switchOff() {
            runBlocking { context.vpnPrefDataStore.edit { it.remove(RustCore.KEY_DEV_RUST_CORE) } }
            hub.stop()
        }
    }

    private fun request(
        room: String,
        provider: String = "none",
        timeoutMs: Long = 10_000,
        dtlsProfile: String = "off"
    ) = RoomRequest(
        provider = provider,
        transport = "datachannel",
        room = if (provider == "none") hub.url(room) else room,
        deviceId = "app-room-check-device",
        keyHex = key,
        timeoutMs = timeoutMs,
        vp8Fps = 25,
        vp8Batch = 1,
        dtlsProfile = dtlsProfile,
    )

    private fun timed(what: String, call: () -> RoomProbeResult): Pair<RoomProbeResult, Long> {
        val t0 = System.nanoTime()
        val r = call()
        val ms = (System.nanoTime() - t0) / 1_000_000
        say("RESULT $what: engine ${r.engine}, millis ${r.millis}, in $ms ms: ${r.reason}")
        return r to ms
    }

    private fun assertFailedOnRust(r: RoomProbeResult) {
        assertEquals("the call ran on the Rust core", OlcRtcConnectionChecker.ENGINE_RUST, r.engine)
        assertNull(r.millis)
        assertFalse("a failure says why", r.reason.isNullOrBlank())
    }

    /** The first call this process makes: a check before any start reaches its room. */
    @Test
    fun a1_aCheckBeforeAnyStartReachesTheRoomAndEndsWithIt() {
        hub.reset(PipeHub.Room.Ends("app room check: nobody here"))
        val (r, ms) = timed("first check in the process, in an ending room") {
            runBlocking { OlcRtcConnectionChecker.checkRoom(request("check"), context) }
        }
        assertFailedOnRust(r)
        assertFalse(r.reason.orEmpty(), r.reason.orEmpty().contains("no TLS connector"))
        assertEquals("both attempts joined the room", listOf("check", "check"), hub.joined)
        assertTrue("the room ended; the check waited for its timeout instead ($ms ms)", ms < 8_000)
        assertFalse("nothing is left running", RustCore.backend?.isOlcRunning() ?: false)
    }

    @Test
    fun a2_aPingReachesTheRoomAndEndsWithIt() {
        hub.reset(PipeHub.Room.Ends("app room check: nobody here"))
        val (r, ms) = timed("ping in an ending room") {
            runBlocking { OlcRtcConnectionChecker.pingRoom(request("ping"), context) }
        }
        assertFailedOnRust(r)
        assertEquals(listOf("ping"), hub.joined)
        assertTrue("the room ended; the probe waited for its timeout instead ($ms ms)", ms < 8_000)
    }

    /** Nobody answers in a silent room: the request's timeout ends each attempt. */
    @Test
    fun a3_aCheckInASilentRoomEndsAtTheRequestsTimeout() {
        hub.reset(PipeHub.Room.Silent)
        val (r, ms) = timed("check in a silent room, 2500 ms asked") {
            runBlocking { OlcRtcConnectionChecker.checkRoom(request("silent", timeoutMs = 2_500), context) }
        }
        assertFailedOnRust(r)
        assertTrue(r.reason.orEmpty(), r.reason.orEmpty().contains("timed out"))
        assertEquals(listOf("silent", "silent"), hub.joined)
        assertTrue("two attempts of 2.5 s each took $ms ms", ms in 4_600..13_000)
    }

    /** A carrier this build does not carry is refused naming it, and no room is dialed. */
    @Test
    fun a4_aCarrierTheCoreDoesNotCarryIsRefusedBeforeAnyRoom() {
        hub.reset(PipeHub.Room.Ends("app room check: unused"))
        for (carrier in listOf("telemost", "wbstream")) {
            val (r, _) = timed("check on $carrier") {
                runBlocking { OlcRtcConnectionChecker.checkRoom(request("unused-$carrier", provider = carrier), context) }
            }
            assertFailedOnRust(r)
            assertTrue(r.reason.orEmpty(), r.reason.orEmpty().contains(carrier))
        }
        assertEquals(emptyList<String>(), hub.joined)
    }

    /** The handshake profile is the request's: the app's two settings are taken, a stranger refused. */
    @Test
    fun a5_theHandshakeProfileIsTheRequests() {
        hub.reset(PipeHub.Room.Ends("app room check: nobody here"))
        for ((i, profile) in listOf("off", "chrome").withIndex()) {
            val (r, _) = timed("ping with the profile $profile") {
                runBlocking { OlcRtcConnectionChecker.pingRoom(request("profile-$i", dtlsProfile = profile), context) }
            }
            assertFailedOnRust(r)
            assertFalse(r.reason.orEmpty(), r.reason.orEmpty().contains("dtls profile"))
        }
        assertEquals(listOf("profile-0", "profile-1"), hub.joined)
        val (refused, _) = timed("ping with an unknown profile") {
            runBlocking { OlcRtcConnectionChecker.pingRoom(request("profile-x", dtlsProfile = "no-such-profile"), context) }
        }
        assertFailedOnRust(refused)
        assertTrue(refused.reason.orEmpty(), refused.reason.orEmpty().contains("no-such-profile"))
        assertEquals(listOf("profile-0", "profile-1"), hub.joined)
    }

    /** With the switch off the same call is the Go runtime's, as it was. */
    @Test
    fun a6_withTheSwitchOffTheGoRuntimeAnswers() {
        runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = false } }
        try {
            hub.reset(PipeHub.Room.Silent)
            val r = runBlocking { OlcRtcConnectionChecker.checkRoom(request("go", timeoutMs = 1_000), context) }
            say("RESULT check with the switch off: engine ${r.engine}")
            assertEquals(OlcRtcConnectionChecker.ENGINE_GO, r.engine)
        } finally {
            runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = true } }
        }
    }
}

/**
 * The room the carrier `none` dials, standing in for a carrier's: an interop pipe hub
 * on loopback (frames of `u32 BE length | u8 kind | payload`, the length counting the
 * kind). It keeps the name each participant joins with (JOIN), then either ends the
 * conference at once with a reason (END) or holds the room silent. No olcRTC server is
 * ever in the room to answer the client's hello.
 */
class PipeHub {
    sealed class Room {
        class Ends(val reason: String) : Room()
        object Silent : Room()
    }

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val rooms = Collections.synchronizedList(mutableListOf<String>())
    private val members = Collections.synchronizedList(mutableListOf<Socket>())

    @Volatile
    private var room: Room = Room.Silent

    @Volatile
    private var stopped = false
    private val acceptor = Thread { serve() }.apply { isDaemon = true; start() }

    val joined: List<String> get() = synchronized(rooms) { rooms.toList() }

    fun url(name: String) = "tcp://127.0.0.1:${server.localPort}/$name"

    /** A new behaviour and no memory of the rooms joined before. */
    fun reset(next: Room) {
        synchronized(members) { members.forEach { runCatching { it.close() } }; members.clear() }
        rooms.clear()
        room = next
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
        synchronized(members) { members.forEach { runCatching { it.close() } } }
        acceptor.join(2_000)
    }

    private fun serve() {
        while (!stopped) {
            val member = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            members.add(member)
            Thread { handle(member) }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(member: Socket) {
        try {
            member.soTimeout = 200
            val input = member.getInputStream()
            val joinBy = System.currentTimeMillis() + 5_000
            val head = receive(input, 5, joinBy) ?: return
            if (head[4].toInt() != KIND_JOIN) return
            val length = (head[0].toInt() and 0xff shl 24) or (head[1].toInt() and 0xff shl 16) or
                (head[2].toInt() and 0xff shl 8) or (head[3].toInt() and 0xff)
            if (length < 1 || length - 1 > MAX_PAYLOAD) return
            val name = receive(input, length - 1, joinBy) ?: return
            rooms.add(String(name, Charsets.UTF_8))
            val mine = room
            if (mine is Room.Ends) {
                val text = mine.reason.toByteArray()
                val size = 1 + text.size
                val frame = byteArrayOf(
                    (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(), KIND_END.toByte()
                ) + text
                member.getOutputStream().apply { write(frame); flush() }
                // Let the END arrive before the close: read what the participant still sends until it goes.
                member.shutdownOutput()
                drain(input, System.currentTimeMillis() + 2_000)
            } else {
                drain(input, Long.MAX_VALUE)
            }
        } catch (e: Exception) {
            // A member that went, or a hub that stopped.
        } finally {
            runCatching { member.close() }
        }
    }

    private fun drain(input: InputStream, until: Long) {
        val sink = ByteArray(16 * 1024)
        while (!stopped && System.currentTimeMillis() < until) {
            try {
                if (input.read(sink) < 0) return
            } catch (e: SocketTimeoutException) {
                // Short reads, so the stop is seen.
            }
        }
    }

    private fun receive(input: InputStream, count: Int, by: Long): ByteArray? {
        val bytes = ByteArray(count)
        var have = 0
        while (have < count) {
            if (stopped || System.currentTimeMillis() >= by) return null
            try {
                val got = input.read(bytes, have, count - have)
                if (got < 0) return null
                have += got
            } catch (e: SocketTimeoutException) {
                // try again until `by`
            }
        }
        return bytes
    }

    private companion object {
        const val KIND_JOIN = 1
        const val KIND_END = 5
        const val MAX_PAYLOAD = 64 * 1024
    }
}
