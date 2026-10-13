package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TunnelDaemonProtocolTest {

    @Test
    fun requestsAreOneLineEachBecauseTheDaemonReadsByLine() {
        val start = TunnelDaemonProtocol.startRequest("{\"log\":{\"level\":\"info\"}}")
        assertEquals(1, start.count { it == '\n' })
        assertTrue(start.endsWith("\n"))
        assertTrue(start.startsWith("{"))
        // The config is a JSON *string* inside the request, not spliced in raw:
        // spliced, its own newlines would end the request half-written.
        assertTrue("\\\"level\\\"" in start)
    }

    @Test
    fun aStartCarriesItsRuleFilesByNameUnderFiles() {
        val start = TunnelDaemonProtocol.startRequest("{}", mapOf("geoip-ru.srs" to "AAEC"))
        assertTrue("\"files\":{\"geoip-ru.srs\":\"AAEC\"}" in start)
        // And none at all when there are none: the first daemons never look.
        assertTrue("files" !in TunnelDaemonProtocol.startRequest("{}"))
    }

    // What the third daemon takes beside a config: who the system is to ask
    // for names while the tunnel runs, and the kill switch's block.
    @Test
    fun aStartSaysWhoAnswersNamesAndWhatTheBlockLetsOut() {
        val start = TunnelDaemonProtocol.startRequest(
            "{}",
            names = "172.19.0.2",
            hold = DaemonHold(interfaceName = "utun225", passes = listOf("203.0.113.7", "2001:db8::1"))
        )
        assertTrue("\"names\":\"172.19.0.2\"" in start, start)
        assertTrue("\"hold\":{\"interface\":\"utun225\",\"pass\":[\"203.0.113.7\",\"2001:db8::1\"]}" in start, start)
        // And neither when not asked for: that request is the one every daemon
        // before this was sent, byte for byte.
        assertEquals("{\"verb\":\"start\",\"config\":\"{}\"}\n", TunnelDaemonProtocol.startRequest("{}"))
    }

    @Test
    fun aStopTakesTheBlockAwayUnlessItSaysToKeepIt() {
        // The plain stop is the one older daemons know, and to this one it is
        // a Disconnect: nothing of the session is left, the block included.
        assertEquals("{\"verb\":\"stop\"}\n", TunnelDaemonProtocol.stopRequest())
        assertEquals("{\"verb\":\"stop\",\"hold\":\"keep\"}\n", TunnelDaemonProtocol.stopRequest(keepHold = true))
        assertEquals("{\"verb\":\"release\"}\n", TunnelDaemonProtocol.releaseRequest())
    }

    @Test
    fun everyReplySaysWhetherTheBlockStands() {
        val held = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":"","protocol":3,"hold":true}""")
        assertIs<DaemonReply.Ok>(held)
        assertTrue(held.holds)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_HOLD, held.protocol)
        // A start that failed after the block was loaded leaves it loaded.
        val failed = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"sing-box exited at once","logTail":"","protocol":3,"hold":true}"""
        )
        assertIs<DaemonReply.Failure>(failed)
        assertTrue(failed.holds)
        // A daemon that says nothing of a block has none: it is from before them.
        val older = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":"","protocol":2}""")
        assertIs<DaemonReply.Ok>(older)
        assertTrue(!older.holds)
        assertTrue(!(TunnelDaemonProtocol.parseReply("""{"ok":false,"error":"x","logTail":""}""") as DaemonReply.Failure).holds)
    }

    @Test
    fun aDaemonThatNamesNoProtocolIsTheFirstOne() {
        val first = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":""}""")
        assertIs<DaemonReply.Ok>(first)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_FIRST, first.protocol)
        val current = TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":"","protocol":2}""")
        assertIs<DaemonReply.Ok>(current)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_FILES, current.protocol)
    }

    @Test
    fun aDaemonSteppingAsideForAnUpdateSaysSo() {
        val stepping = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}"""
        )
        assertIs<DaemonReply.Failure>(stepping)
        assertTrue(stepping.restarting)
        val plain = TunnelDaemonProtocol.parseReply("""{"ok":false,"error":"sing-box exited","logTail":""}""")
        assertIs<DaemonReply.Failure>(plain)
        assertTrue(!plain.restarting)
    }

    @Test
    fun aRunningDaemonReportsItsChildPid() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":true,"state":"running","pid":4242,"logTail":"started"}"""
        )
        assertIs<DaemonReply.Ok>(reply)
        assertEquals("running", reply.state)
        assertEquals(4242, reply.pid)
    }

    @Test
    fun anErrorKeepsTheTailBecauseTheReasonIsInSingBoxOutputNotInOurMessage() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"sing-box exited","logTail":"FATAL bind: permission denied"}"""
        )
        assertIs<DaemonReply.Failure>(reply)
        assertEquals("sing-box exited", reply.message)
        assertTrue("permission denied" in reply.logTail)
    }

    @Test
    fun anUnparseableReplyIsAFailureNotACrashAndNeverAnOk() {
        // A daemon that answers garbage is a daemon in an unknown state, and the
        // only safe direction to round an unknown state is down.
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("not json"))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply(""))
        assertIs<DaemonReply.Failure>(TunnelDaemonProtocol.parseReply("""{"state":"running"}"""))
    }

    // The fourth protocol: the daemon runs a line's own program for the app.
    @Test fun aLinesProgramIsNamedWithItsArgumentsAndWhereItWrites() {
        assertEquals(
            """{"verb":"line","program":"/Users/a/bin/olcrtc","arguments":["/tmp/room.yaml"],"output":"/tmp/room.yaml.out"}""" + "\n",
            TunnelDaemonProtocol.lineRequest("/Users/a/bin/olcrtc", listOf("/tmp/room.yaml"), "/tmp/room.yaml.out")
        )
        assertEquals("{\"verb\":\"line-stop\"}\n", TunnelDaemonProtocol.lineStopRequest())
    }

    @Test fun aReplySaysWhichProcessTheLineIsAndWhereItsCopyLies() {
        val started = assertIs<DaemonReply.Ok>(
            TunnelDaemonProtocol.parseReply(
                """{"ok":true,"state":"idle","logTail":"","protocol":4,"hold":true,"line":4711,""" +
                    """"program":"/Library/Application Support/org.olcbox.app.lines/olcrtc-darwin-arm64"}"""
            )
        )
        assertEquals(4711L, started.line)
        assertEquals("${TunnelDaemonProtocol.LINES_DIR}/olcrtc-darwin-arm64", started.program)
        assertEquals(TunnelDaemonProtocol.PROTOCOL_LINE, started.protocol)
        // A daemon from before lines says nothing of one, and runs none.
        val before = assertIs<DaemonReply.Ok>(
            TunnelDaemonProtocol.parseReply("""{"ok":true,"state":"idle","logTail":"","protocol":3,"hold":false}""")
        )
        assertEquals(0L, before.line)
        assertEquals(null, before.program)
    }
}
