package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.olcbox.app.net.ProbeFailure
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moments after a Mac's tunnel is called running.
 *
 * On a Mac in the field (build 1.0.463) a room was joined behind a block that
 * stood, the helper started the tunnel and called it running, and the app's
 * check, made at once, was refused: no request was handed to the engine, and
 * the connect ended a few seconds later. With the tunnel started first and its
 * line behind it, as a core's is there, the same tunnel was asked seconds
 * later and answered. "The helper says its process runs" was read as "the
 * tunnel serves", and a check that was refused was read as an answer about
 * the far end.
 */
class TunnelSettleTest {

    private fun refused(ms: Long, url: String = "https://1.1.1.1/cdn-cgi/trace") =
        ProbeFailure(url = url, reason = "ConnectException: Connection refused", tookMs = ms)

    @Test
    fun aCheckThatWasRefusedAtOnceIsAskedAgain() {
        assertTrue(TunnelSettle.asksAgain(listOf(refused(12), refused(9)), sinceStartMs = 300))
    }

    @Test
    fun aCheckTheFarEndDidNotAnswerIsAnAnswer() {
        // Eight seconds of silence is what a dead server looks like. Asked again
        // it is silent again, and the connect would end a minute later saying the same.
        assertFalse(TunnelSettle.asksAgain(listOf(refused(8_004), refused(8_001)), sinceStartMs = 16_100))
        assertFalse(TunnelSettle.asksAgain(listOf(refused(14), refused(8_001)), sinceStartMs = 8_100))
    }

    @Test
    fun aTunnelThatNeverServesIsNotAskedForEver() {
        assertTrue(TunnelSettle.asksAgain(listOf(refused(10)), sinceStartMs = TunnelSettle.BUDGET_MS - 2_000))
        assertFalse(TunnelSettle.asksAgain(listOf(refused(10)), sinceStartMs = TunnelSettle.BUDGET_MS))
    }

    @Test
    fun aCheckThatSaidNothingAtAllIsNotARefusal() {
        assertFalse(TunnelSettle.asksAgain(emptyList(), sinceStartMs = 100))
    }

    @Test
    fun aListenerThatOpensLaterIsWaitedFor() {
        // The port is known before anybody listens on it, as a tunnel's is.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        var listener: ServerSocket? = null
        thread(isDaemon = true) {
            Thread.sleep(600)
            listener = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        }
        try {
            val waited = runBlocking { TunnelSettle.awaitListening(port, timeoutMs = 5_000) }

            assertNotNull(waited, "the listener came up within the wait")
            assertTrue(waited >= 400, "it was not there at first: $waited ms")
            assertTrue(waited < 4_000, "and was found soon after it was: $waited ms")
        } finally {
            Thread.sleep(50)
            listener?.close()
        }
    }

    @Test
    fun aListenerThatIsThereIsFoundAtOnce() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { listener ->
            val waited = runBlocking { TunnelSettle.awaitListening(listener.localPort, timeoutMs = 5_000) }

            assertNotNull(waited)
            assertTrue(waited < 1_000, "nothing to wait for: $waited ms")
        }
    }

    @Test
    fun aListenerThatNeverOpensEndsTheWait() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val started = System.currentTimeMillis()
        val waited = runBlocking { TunnelSettle.awaitListening(port, timeoutMs = 700) }

        assertNull(waited)
        assertTrue(System.currentTimeMillis() - started < 3_000, "the wait is the one that was asked for")
    }

    @Test
    fun whatATunnelSaidOfATroubleIsKeptAndTheRestIsNot() {
        val tail = listOf(
            "\u001b[36mINFO\u001b[0m[0000] inbound/tun[tun-in]: started at utun225",
            "\u001b[31mERROR\u001b[0m[0001] [\u001b[38;5;48m883521568\u001b[0m 1ms] connection: open connection to 1.1.1.1:443 " +
                "using outbound/socks[out]: dial tcp 127.0.0.1:10808: connect: connection refused",
            "\u001b[33mWARN\u001b[0m[0002] open interface take too much time to finish!",
            "\u001b[36mINFO\u001b[0m[0003] sing-box started (0.31s)",
            "FATAL[0004] start service: start inbound/tun[tun-in]: configure tun interface: resource busy",
            ""
        ).joinToString("\n")

        assertEquals(
            listOf(
                "ERROR[0001] [883521568 1ms] connection: open connection to 1.1.1.1:443 " +
                    "using outbound/socks[out]: dial tcp 127.0.0.1:10808: connect: connection refused",
                "WARN[0002] open interface take too much time to finish!",
                "FATAL[0004] start service: start inbound/tun[tun-in]: configure tun interface: resource busy"
            ),
            TunnelSettle.troubles(tail)
        )
    }

    @Test
    fun onlyTheLastOfMuchTroubleIsKept() {
        // Forty troubles that are not each other: told apart by more than a number.
        fun named(it: Int) = "host-${'a' + it % 26}${'a' + it / 26}.example"
        val tail = (1..40).joinToString("\n") { "ERROR[00$it] connection: open connection to ${named(it)}:443: refused" }

        val kept = TunnelSettle.troubles(tail, limit = 5)

        assertEquals(5, kept.size)
        assertEquals("ERROR[0040] connection: open connection to ${named(40)}:443: refused", kept.last())
    }

    @Test
    fun aTroubleSaidManyTimesIsSaidOnceWithHowMany() {
        // 1.0.464 on a Mac in the field: something else on the machine kept
        // knocking at the tunnel's own listener in a tongue it does not speak,
        // and the eight lines that were kept were that one line eight times.
        // What the tunnel had said of the check itself was not among them.
        val knocks = (0 until 8).joinToString("\n") {
            "\u001b[31mERROR\u001b[0m[00${10 + it / 3}] [\u001b[38;5;201m${355653305 + it}\u001b[0m 0ms] " +
                "inbound/socks[verify-in]: process connection from 127.0.0.1:${62125 + it}: invalid argument"
        }
        val ofTheCheck = "ERROR[0001] [883521568 1ms] connection: open connection to 1.1.1.1:443 " +
            "using outbound/socks[out]: socks5: request rejected, code=4"

        assertEquals(
            listOf(
                ofTheCheck,
                "ERROR[0012] [355653312 0ms] inbound/socks[verify-in]: process connection from 127.0.0.1:62132: " +
                    "invalid argument (8 times, the last one shown)"
            ),
            TunnelSettle.troubles(ofTheCheck + "\n" + knocks + "\n")
        )
    }

    @Test
    fun aTroubleThatComesBackIsKeptWhereItWasLastSaid() {
        val tail = listOf(
            "WARN[0001] dns: exchange failed for a.example. IN A: context deadline exceeded",
            "ERROR[0002] connection: dial tcp 127.0.0.1:10808: connect: connection refused",
            "WARN[0009] dns: exchange failed for a.example. IN A: context deadline exceeded"
        ).joinToString("\n")

        assertEquals(
            listOf(
                "ERROR[0002] connection: dial tcp 127.0.0.1:10808: connect: connection refused",
                "WARN[0009] dns: exchange failed for a.example. IN A: context deadline exceeded (2 times, the last one shown)"
            ),
            TunnelSettle.troubles(tail)
        )
    }

    @Test
    fun aTunnelThatSaidNothingOfATroubleHasNoneToShow() {
        assertEquals(emptyList(), TunnelSettle.troubles("INFO[0000] sing-box started (0.31s)\n"))
        assertEquals(emptyList(), TunnelSettle.troubles(""))
    }
}
