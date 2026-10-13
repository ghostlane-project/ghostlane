package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.olcbox.app.vpn.TrafficCounters
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a desktop session's traffic is counted, and what is made of the count.
 *
 * Reported from a Mac: the chart does not run. Nothing on a desktop counted a
 * session at all. A tunnel is counted now, by whoever owns it: the kernel on
 * Linux, the tun's sing-box on Windows and macOS.
 */
class TunnelTrafficTest {

    // ── Linux: the tun device ──────────────────────────────────────────────

    private fun interfacesWith(device: String, received: String?, sent: String?): Path {
        val interfaces = Files.createTempDirectory("sys-class-net")
        if (received != null || sent != null) {
            val statistics = interfaces.resolve(device).resolve("statistics").createDirectories()
            received?.let { statistics.resolve("rx_bytes").writeText(it) }
            sent?.let { statistics.resolve("tx_bytes").writeText(it) }
        }
        return interfaces
    }

    @Test fun onLinuxTheCountIsTheTunDevicesOwn() = runTest {
        // As the kernel writes them: a number and a line end. What the device
        // received is on its way to the user; what was transmitted to it, away.
        val interfaces = interfacesWith("olcbox0", received = "2600000000\n", sent = "516000000\n")
        assertEquals(
            TrafficCounters(bytesIn = 2_600_000_000L, bytesOut = 516_000_000L),
            LinuxTunTraffic("olcbox0", interfaces).read()
        )
    }

    @Test fun aTunThatIsNotThereIsNoReadingAndNotAZero() = runTest {
        assertNull(LinuxTunTraffic("olcbox0", interfacesWith("olcbox0", received = null, sent = null)).read())
        // Half a reading is none: a direction read as nothing would be a count that dropped.
        assertNull(LinuxTunTraffic("olcbox0", interfacesWith("olcbox0", received = "100\n", sent = null)).read())
        assertNull(LinuxTunTraffic("olcbox0", interfacesWith("olcbox0", received = "100\n", sent = "not a number")).read())
    }

    // ── Windows and macOS: the tun's sing-box ──────────────────────────────

    /** A flow as the API lists it. sing-box names a flow's program by its path and, in brackets, who runs it. */
    private fun flow(
        id: String,
        received: Long,
        sent: Long,
        binary: String = "",
        outbound: String = "out",
        program: String = if (binary.isEmpty()) "" else "$binary (a)"
    ) =
        """{"chains":["$outbound"],"download":$received,"id":"$id","metadata":{"destinationIP":"203.0.113.7",""" +
            """"destinationPort":"443","dnsMode":"normal","host":"","network":"tcp","processPath":"$program",""" +
            """"sourceIP":"172.19.0.1","sourcePort":"52341","type":"tun/tun-in"},"rule":"final","rulePayload":"",""" +
            """"start":"2026-10-13T04:44:42.66589229Z","upload":$sent}"""

    private fun answer(received: Long, sent: Long, vararg flows: String) =
        """{"connections":[${flows.joinToString(",")}],"downloadTotal":$received,"memory":4325376,"uploadTotal":$sent}"""

    @Test fun theAnswerOfTheSingBoxTheAppShipsIsRead() {
        // Word for word what sing-box 1.13.14 answers with two flows open, one
        // to the proxy and one sent straight out by a rule.
        val asAnswered = """{"connections":[{"chains":["out"],"download":15179771,"id":"5271b9f3-8ae6-4c3b-9b36-d79a1b3f1acd",""" +
            """"metadata":{"destinationIP":"127.0.0.1","destinationPort":"19090","dnsMode":"normal","host":"","network":"tcp",""" +
            """"processPath":"","sourceIP":"127.0.0.1","sourcePort":"17582","type":"socks/tun-in"},"rule":"final","rulePayload":"",""" +
            """"start":"2026-10-13T04:44:42.66589229Z","upload":90},{"chains":["direct"],"download":20709530,""" +
            """"id":"15d336dc-123c-4699-bf8c-7573e32ebeb6","metadata":{"destinationIP":"127.0.0.1","destinationPort":"19091",""" +
            """"dnsMode":"normal","host":"","network":"tcp","processPath":"","sourceIP":"127.0.0.1","sourcePort":"17584",""" +
            """"type":"socks/tun-in"},"rule":"port=19091 => route(direct)","rulePayload":"",""" +
            """"start":"2026-10-13T04:44:42.667958228Z","upload":90}],"downloadTotal":35889301,"memory":4325376,"uploadTotal":180}"""
        assertEquals(
            TrafficCounters(bytesIn = 35_889_301L, bytesOut = 180L),
            SingBoxFlows(ownBinaries = setOf(ENGINE)).carried(asAnswered)
        )
        // And with nothing open, as it answers before the first connection.
        assertEquals(
            TrafficCounters(bytesIn = 0L, bytesOut = 0L),
            SingBoxFlows(emptySet()).carried("""{"connections":[],"downloadTotal":0,"memory":3596288,"uploadTotal":0}""")
        )
    }

    @Test fun aFlowIsALinesByItsProgramWhoeverTheSystemSaysRunsIt() {
        // Word for word how sing-box 1.13.14 names the program of a flow that a
        // process rule sent straight out: "/usr/bin/curl (root)". Compared with
        // the bare path, no flow was ever a line's, and a room's session was
        // counted twice.
        val asAnswered = """{"connections":[{"chains":["direct"],"download":14614682,"id":"0d6cbb1c-6f0b-4f43-9d53-3c6b0b7f8a11",""" +
            """"metadata":{"destinationIP":"127.0.0.1","destinationPort":"19090","dnsMode":"normal","host":"","network":"tcp",""" +
            """"processPath":"/usr/bin/curl (root)","sourceIP":"127.0.0.1","sourcePort":"40112","type":"socks/tun-in"},""" +
            """"rule":"inbound=tun-in process_path=[/usr/bin/curl /usr/bin/python3.12] =\u003e route(direct)","rulePayload":"",""" +
            """"start":"2026-10-13T05:52:10.11589229Z","upload":90}],"downloadTotal":14614682,"memory":4325376,"uploadTotal":90}"""
        assertEquals(
            TrafficCounters(bytesIn = 0L, bytesOut = 0L),
            SingBoxFlows(ownBinaries = setOf("/usr/bin/curl")).carried(asAnswered)
        )

        fun carriedWhenNamed(program: String) = SingBoxFlows(ownBinaries = setOf(ENGINE)).carried(
            answer(received = 500L, sent = 50L, flow("f", received = 500L, sent = 50L, program = program, outbound = "direct"))
        )
        val none = TrafficCounters(bytesIn = 0L, bytesOut = 0L)
        val all = TrafficCounters(bytesIn = 500L, bytesOut = 50L)
        // By name, by number, and bare, which is how a system that knows no user says it.
        assertEquals(none, carriedWhenNamed("$ENGINE (roman)"))
        assertEquals(none, carriedWhenNamed("$ENGINE (501)"))
        assertEquals(none, carriedWhenNamed(ENGINE))
        // Another program, whose path only begins as a line's does, is the user's.
        assertEquals(all, carriedWhenNamed("$ENGINE-helper (roman)"))
        assertEquals(all, carriedWhenNamed("$ENGINE (roman) and more"))
        assertEquals(all, carriedWhenNamed(""))
    }

    @Test fun aRoomsOwnMediaIsNotTheUsersTraffic() {
        // A room's engine reaches its meeting service through the tun it is
        // carrying: the tun counts the user's megabyte, and then the megabyte of
        // video it rode out in.
        val flows = SingBoxFlows(ownBinaries = setOf(ENGINE))
        val carried = flows.carried(
            answer(
                received = 2_100_000L, sent = 130_000L,
                flow("a-users", received = 1_000_000L, sent = 60_000L, binary = BROWSER),
                flow("the-rooms", received = 1_100_000L, sent = 70_000L, binary = ENGINE, outbound = "direct")
            )
        )
        assertEquals(TrafficCounters(bytesIn = 1_000_000L, bytesOut = 60_000L), carried)
    }

    @Test fun whatALinesFlowMovedStaysOffTheCountOnceTheFlowIsGone() {
        // The API lists the flows that are open. One that has closed is still in
        // the totals, and what it had moved when it was last seen still is not
        // the user's.
        val flows = SingBoxFlows(ownBinaries = setOf(ENGINE))
        flows.carried(
            answer(
                received = 2_100_000L, sent = 130_000L,
                flow("the-rooms", received = 1_100_000L, sent = 70_000L, binary = ENGINE, outbound = "direct")
            )
        )
        // The room's stream ended and another began; the user moved 50 KB more.
        val carried = flows.carried(
            answer(
                received = 2_350_000L, sent = 150_000L,
                flow("the-rooms-next", received = 200_000L, sent = 15_000L, binary = ENGINE, outbound = "direct")
            )
        )
        assertEquals(TrafficCounters(bytesIn = 1_050_000L, bytesOut = 65_000L), carried)
    }

    @Test fun whatTheRulesSendStraightOutForTheUserIsCountedAsOnAPhone() {
        // Under "only blocked sites" most of a user's traffic leaves beside the
        // tunnel. It went through the tunnel interface all the same, which is
        // what a phone counts, and what goes for the user is not a line's.
        val flows = SingBoxFlows(ownBinaries = setOf(ENGINE))
        val carried = flows.carried(
            answer(
                received = 900_000L, sent = 40_000L,
                flow("a-site-at-home", received = 900_000L, sent = 40_000L, binary = BROWSER, outbound = "direct")
            )
        )
        assertEquals(TrafficCounters(bytesIn = 900_000L, bytesOut = 40_000L), carried)
    }

    @Test fun aSingBoxStartedAgainIsCountedFromItsOwnBeginning() {
        // Less than before is how the session's totals know a counter began
        // again, so it must be said, and what the last one's lines moved has
        // nothing to do with this one.
        val flows = SingBoxFlows(ownBinaries = setOf(ENGINE))
        flows.carried(
            answer(
                received = 9_000_000L, sent = 900_000L,
                flow("the-rooms", received = 4_000_000L, sent = 400_000L, binary = ENGINE, outbound = "direct")
            )
        )
        val carried = flows.carried(answer(received = 30_000L, sent = 2_000L))
        assertEquals(TrafficCounters(bytesIn = 30_000L, bytesOut = 2_000L), carried)
    }

    @Test fun theCountNeverGoesDownWhileOneSingBoxLives() {
        // Its totals and each flow's own count are read one after another. A
        // flow that moved between the two is ahead of the totals it is taken
        // off, and the difference is, for that reading, a little less than it
        // was. Said as it stands, that is a counter that started again.
        val flows = SingBoxFlows(ownBinaries = setOf(ENGINE))
        val first = flows.carried(
            answer(
                received = 1_500_000L, sent = 90_000L,
                flow("the-rooms", received = 500_000L, sent = 30_000L, binary = ENGINE, outbound = "direct")
            )
        )
        val second = flows.carried(
            answer(
                received = 1_500_000L, sent = 90_000L,
                flow("the-rooms", received = 512_000L, sent = 31_400L, binary = ENGINE, outbound = "direct")
            )
        )
        assertEquals(TrafficCounters(bytesIn = 1_000_000L, bytesOut = 60_000L), first)
        assertEquals(first, second)
    }

    @Test fun anAnswerThatCannotBeReadIsNoReading() {
        val flows = SingBoxFlows(emptySet())
        assertNull(flows.carried(""))
        assertNull(flows.carried("<html>502 Bad Gateway</html>"))
        assertNull(flows.carried("""{"connections":"""))
    }

    // ── asking it ──────────────────────────────────────────────────────────

    /** Something on the loopback that answers `/connections` as a sing-box would, to whoever knows [secret]. */
    private fun <T> withAnApi(secret: String, body: String, test: suspend (port: Int) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/connections") { exchange ->
            val known = exchange.requestHeaders.getFirst("Authorization") == "Bearer $secret"
            val bytes = (if (known) body else """{"message":"Unauthorized"}""").toByteArray()
            exchange.sendResponseHeaders(if (known) 200 else 401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // The real clock: the answer comes over a socket, in its own time.
            return runBlocking { test(server.address.port) }
        } finally {
            server.stop(0)
        }
    }

    @Test fun theTunsSingBoxIsAskedOnTheLoopbackWithItsSecret() {
        val counted = withAnApi("s3cr3t", answer(received = 7_000L, sent = 300L)) { port ->
            SingBoxTunTraffic(port, "s3cr3t", ownBinaries = emptyList()).read()
        }
        assertEquals(TrafficCounters(bytesIn = 7_000L, bytesOut = 300L), counted)
    }

    @Test fun anApiThatDoesNotKnowTheSecretGivesNoReading() {
        val counted = withAnApi("s3cr3t", answer(received = 7_000L, sent = 300L)) { port ->
            SingBoxTunTraffic(port, "another", ownBinaries = emptyList()).read()
        }
        assertNull(counted)
    }

    @Test fun withNobodyListeningThereIsNoReading() = runTest {
        // A tunnel that is being built again: its port answers nobody for a moment.
        val free = ServerSocket(0).use { it.localPort }
        assertNull(SingBoxTunTraffic(free, "s3cr3t", ownBinaries = emptyList()).read())
    }

    private companion object {
        const val ENGINE = "/Users/a/Library/Application Support/Ghostlane/bin/olcrtc-darwin-arm64"
        const val BROWSER = "/Applications/Safari.app/Contents/MacOS/Safari"
    }
}
