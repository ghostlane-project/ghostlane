package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TunnelDaemonProtocolTest {

    @Test
    fun requestsAreOneLineEachBecauseTheDaemonReadsByLine() {
        val tun = TunnelDaemonProtocol.tunRequest(DaemonTun(interfaceName = "utun225", socksPort = 10810))
        assertEquals(1, tun.count { it == '\n' })
        assertTrue(tun.endsWith("\n"))
        assertTrue(tun.startsWith("{"))
        assertTrue("\"verb\":\"tun\"" in tun, tun)
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
            """{"ok":false,"error":"hev exited at once","logTail":"","protocol":3,"hold":true}"""
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
        assertEquals(2, current.protocol)
    }

    @Test
    fun aDaemonSteppingAsideForAnUpdateSaysSo() {
        val stepping = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}"""
        )
        assertIs<DaemonReply.Failure>(stepping)
        assertTrue(stepping.restarting)
        val plain = TunnelDaemonProtocol.parseReply("""{"ok":false,"error":"hev exited","logTail":""}""")
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
    fun anErrorKeepsTheTailBecauseTheReasonIsInTheTunnelsOutputNotInOurMessage() {
        val reply = TunnelDaemonProtocol.parseReply(
            """{"ok":false,"error":"hev exited","logTail":"FATAL bind: permission denied"}"""
        )
        assertIs<DaemonReply.Failure>(reply)
        assertEquals("hev exited", reply.message)
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
