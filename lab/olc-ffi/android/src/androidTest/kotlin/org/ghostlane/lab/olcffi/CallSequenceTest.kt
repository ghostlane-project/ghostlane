package org.ghostlane.lab.olcffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.ghostlane.olcrtc.OlcrtcException
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.ghostlane.olcrtc.setLogWriter
import org.ghostlane.olcrtc.trim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android twin of the iOS XCTest (lab/olc-ffi, OlcFfiTests.swift): the
 * API, the callbacks in the service's signatures, start, waitReady and stop,
 * a fetch through the SOCKS port with the login, UDP ASSOCIATE (hev's
 * `udp: 'udp'`, HevTunnelConfig's setting), ten stop/starts, Go's error
 * texts and memoryStats().
 */
@RunWith(AndroidJUnit4::class)
class CallSequenceTest {
    private fun text(body: () -> Unit): String = try {
        body()
        "no error"
    } catch (e: OlcrtcException) {
        e.message ?: "(no message)"
    }

    /** The errors carry Go's words in the exception's message, as gomobile's
     * Exception did (the service logs and shows `e.message`). */
    @Test
    fun theErrorsAreGosWords() {
        val rt = OlcrtcRuntime()
        try {
            assertEquals("unsupported provider: \"telemost\"", text { rt.setProvider("telemost") })
            assertEquals("invalid mobile runtime configuration: provider \"\"", text { rt.start() })
            assertEquals("olcRTC runtime has not been started", text { rt.waitReady(10) })
            assertEquals(
                "invalid mobile runtime configuration: SOCKS port must be between 1 and 65535",
                text { rt.setSocksPort(0) },
            )
            assertEquals(
                "invalid mobile runtime configuration: decode key: encoding/hex: invalid byte: U+007A 'z'",
                text { rt.setKey("zz") },
            )
            // No open proxy: a SOCKS front off loopback needs the login.
            rt.setProvider("none")
            rt.setTransport("datachannel")
            rt.setKey("00".repeat(32))
            rt.setSocksListenHost("0.0.0.0")
            assertEquals(
                "invalid mobile runtime configuration: SOCKS credentials are required outside loopback",
                text { rt.start() },
            )
            assertEquals("idle", rt.state())
            say("RESULT errors ok: Go's texts in OlcrtcException.message")
        } finally {
            rt.close()
        }
    }

    @Test
    fun theServicesCallSequence() {
        val t1 = T1.load()
        val lines = Lines()
        val keeper = Keeper()
        val protector = Protector()
        // installMobileCallbacks: the protector and the log writer first.
        val rt = OlcrtcRuntime()
        rt.setProtector(protector)
        setLogWriter(lines)
        rt.setSessionListener(keeper)
        try {
            val port = freePort()
            configure(rt, t1, port)
            assertEquals("idle", rt.state())
            mem("before-start")

            val t0 = System.nanoTime()
            rt.start()
            assertEquals("olcRTC runtime is already active", text { rt.start() })
            rt.waitReady(35_000)
            val readyMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals("running", rt.state())
            assertTrue(rt.isRunning())
            say("RESULT ready in $readyMs ms")
            mem("ready")

            // The log contract: the ready mark handleRtcLine reads, each line
            // whole and with its newline (the service trims it).
            val mark = "SOCKS5 server listening on 127.0.0.1:$port"
            assertEquals(lines.all().takeLast(20).joinToString(""), 1, lines.count(mark.lowercase()))
            assertTrue("every line ends with its newline", lines.all().all { it.endsWith("\n") })
            val deadline = System.currentTimeMillis() + 5_000
            while (keeper.sessions.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals("the session listener names the room", t1.room, keeper.sessions.firstOrNull()?.first)

            // TCP through the SOCKS port with the login; a wrong one refused.
            val (status, body) = fetch(t1.origin, port)
            assertTrue(status, status.startsWith("HTTP/1.") && status.contains(" 200"))
            assertEquals(1024L, body)
            val refused = runCatching { fetch(t1.origin, port, USER, "wrong") }
            assertTrue("a wrong login is refused", refused.isFailure)
            say("RESULT fetch: $status, $body bytes; a wrong login refused (${refused.exceptionOrNull()?.message})")

            // The protector: every socket the engine opened off loopback (the
            // test's own all go to 127.0.0.1) went through it, before connect.
            val open = outboundSockets()
            val unprotected = open.filterKeys { it !in protector.inodes }
            assertTrue("the engine has its sockets off loopback: $open", open.isNotEmpty())
            assertTrue("sockets not protected: $unprotected", unprotected.isEmpty())
            assertTrue("protect came on a socket not yet connected: ${protector.bad}", protector.bad.isEmpty())
            assertEquals(protector.calls.get(), protector.sockets.get())
            say(
                "RESULT callbacks: protect ${protector.calls.get()} call(s), each a socket not yet connected; " +
                    "${open.size} engine socket(s) open off loopback, all protected (${open.values.joinToString()}); " +
                    "${keeper.sessions.size} session(s) in the room; ${lines.all().size} log lines"
            )

            // memoryStats: one line, every layer of §9; this host's runtime
            // and the constrained class's byte budget.
            val s = stats()
            assertEquals(1, s.getInt("v"))
            assertEquals(12 * 1024 * 1024, s.getInt("budget"))
            val layers = s.getJSONObject("layers")
            for (name in listOf("socks", "udp", "smux_rx", "smux_tx", "mux_in", "kcp", "vp8", "rtp", "str0m", "sctp", "signalling", "logs")) {
                assertEquals(name, 3, layers.getJSONArray(name).length())
            }
            assertTrue("the counting allocator is the one in force", s.getJSONObject("heap").getLong("live") > 0)
            val workers = s.getJSONObject("rt").getInt("workers")
            assertTrue("the host profile: 2 to 6 workers, $workers", workers in 2..6)
            assertEquals("a phone's byte budget", 3_000_000, s.getJSONObject("bytes").getInt("cap"))
            say("RESULT memory_stats: ${org.ghostlane.olcrtc.memoryStats()}")

            // UDP as the service's hev asks for it: UDP ASSOCIATE, both ways.
            val dst = t1.echoIp.ifEmpty { "127.0.0.1" }
            for (size in listOf(32, 1400)) {
                val payload = ByteArray(size) { (it * 7 + size).toByte() }
                try {
                    val ms = udpAssociateEcho(port, dst, t1.echoPort, payload)
                    say("RESULT udp associate: $size bytes echoed in $ms ms via $dst")
                } catch (e: Exception) {
                    say("RESULT udp associate $size bytes FAILED: $e")
                    fail("UDP ASSOCIATE, $size bytes: $e")
                }
            }
            mem("after-udp")

            // handleRtcLine's failure marks: none while the line was healthy.
            val failing = listOf(
                "ice connection state changed: failed", "peer connection state changed: failed",
                "ice connection state changed: closed", "peer connection state changed: closed",
                "network is unreachable", "use of closed network connection", "read/write on closed pipe",
            ).associateWith { lines.count(it) }.filterValues { it > 0 }
            assertTrue("failure marks in a healthy run: $failing", failing.isEmpty())
            say("RESULT log marks: the ready mark once, no failure mark of handleRtcLine's")

            val t1s = System.nanoTime()
            rt.stop(5_000)
            say("RESULT stop in ${(System.nanoTime() - t1s) / 1_000_000} ms")
            assertEquals("stopped", rt.state())
            assertFalse(rt.isRunning())
            assertEquals("olcRTC runtime has not been started", text { rt.waitReady(100) })
            mem("stopped")

            // Stop and start as a network change does (the service's restart).
            val readies = mutableListOf<Long>()
            for (i in 0 until 10) {
                val c0 = System.nanoTime()
                rt.start()
                rt.waitReady(35_000)
                readies += (System.nanoTime() - c0) / 1_000_000
                val (st, n) = fetch(t1.origin, port)
                assertEquals("cycle $i: $st", 1024L, n)
                rt.stop(5_000)
                assertEquals("stopped", rt.state())
            }
            assertEquals("a ready mark each start", 11, lines.count(mark.lowercase()))
            val settle = System.currentTimeMillis() + 3_000
            while (stats().getJSONObject("rt").optInt("tasks", 0) > 0 && System.currentTimeMillis() < settle) Thread.sleep(20)
            mem("after-10-cycles")
            say("RESULT 10 stop/starts: ready ${readies.min()}-${readies.max()} ms; tasks left ${stats().getJSONObject("rt").opt("tasks")}")
            val given = trim()
            mem("after-trim")
            say("RESULT trim gave back $given bytes")
        } finally {
            setLogWriter(null)
            rt.close()
        }
    }
}
