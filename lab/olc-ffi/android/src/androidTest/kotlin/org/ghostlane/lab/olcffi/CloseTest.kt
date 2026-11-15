package org.ghostlane.lab.olcffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * close() on a running runtime, with no stop() first: the binding frees the
 * object, and the core stops its run as stop(5_000) would (xray-rust
 * 325697de's Drop). close() returns within stop's bound, the SOCKS port refuses
 * and binds again, no task is left on the core's runtime, and the next
 * runtime in the same room is ready and fetches. Before, the closed one ran
 * on: its task, its port and its session in the room (run 2's "peer v3"
 * came from a session left up).
 */
@RunWith(AndroidJUnit4::class)
class CloseTest {
    private fun tasks(): Int = stats().getJSONObject("rt").optInt("tasks", -1)

    @Test
    fun aRunningRuntimeClosedStopsItsRun() {
        val t1 = T1.load()
        val port = freePort()
        val first = OlcrtcRuntime()
        val keeper = Keeper()
        first.setSessionListener(keeper)
        configure(first, t1, port)
        first.start()
        first.waitReady(35_000)
        val (status, body) = fetch(t1.origin, port)
        assertEquals(status, 1024L, body)
        val busy = tasks()

        val t0 = System.nanoTime()
        first.close()
        val closeMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("close() took $closeMs ms, stop's bound is 5,000", closeMs < 5_500)
        val dial = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) } }
        assertTrue("the SOCKS port $port still accepts after close()", dial.isFailure)
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
        val settle = System.currentTimeMillis() + 3_000
        while (tasks() > 0 && System.currentTimeMillis() < settle) Thread.sleep(20)
        val left = tasks()
        assertEquals("tasks left after close()", 0, left)
        say(
            "RESULT close: a running runtime ($busy tasks, ${keeper.sessions.size} session(s)) closed in $closeMs ms; " +
                "the port refuses and binds again; $left tasks left"
        )

        val next = OlcrtcRuntime()
        try {
            configure(next, t1, port)
            val c0 = System.nanoTime()
            next.start()
            next.waitReady(35_000)
            val readyMs = (System.nanoTime() - c0) / 1_000_000
            val (st, n) = fetch(t1.origin, port)
            assertEquals(st, 1024L, n)
            say("RESULT close: the next runtime in the room ready in $readyMs ms, fetch $st")
            next.stop(5_000)
        } finally {
            next.close()
        }
    }
}
