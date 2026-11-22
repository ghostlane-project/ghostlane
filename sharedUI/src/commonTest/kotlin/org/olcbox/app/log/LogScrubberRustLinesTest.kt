package org.olcbox.app.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The scrubber on what the Rust core and the olcRTC client in Rust write. */
class LogScrubberRustLinesTest {
    private val s = LogScrubber(salt = 12345L)
    private val tag = Regex("node#[0-9a-f]{12}")

    @Test fun theWaivedCheckWarningLosesItsHostAndKeepsItsPath() {
        val out = s.scrub(RustCoreLines.WAIVED_CHECK_HOST)
        assertFalse(out.contains("example-host"), out)
        assertTrue(Regex("certificate of node#[0-9a-f]{12}:443 \\(allowInsecure\\)").containsMatchIn(out), out)
        // The path is what says which outbound the remark is about: it is not a host.
        assertTrue(out.startsWith("\$.outbounds[0].streamSettings.tlsSettings.allowInsecure: outbound `out` "), out)
    }

    @Test fun theWaivedCheckWarningLosesAnAddressOfEitherFamily() {
        val v4 = s.scrub(RustCoreLines.WAIVED_CHECK_V4)
        assertFalse(v4.contains("203.0.113.77"), v4)
        assertTrue(Regex("certificate of node#[0-9a-f]{12}:8443").containsMatchIn(v4), v4)
        val v6 = s.scrub(RustCoreLines.WAIVED_CHECK_V6)
        assertFalse(v6.contains("2001:db8"), v6)
        assertTrue(Regex("certificate of \\[node#[0-9a-f]{12}\\]:443").containsMatchIn(v6), v6)
    }

    @Test fun aRoomInTheFailoverWalkIsMaskedWhateverTheCarrierCalledIt() {
        assertEquals("failover cycle=2 starting room=<id>", s.scrub(RustCoreLines.ROOM_VIDEO_LINK))
        assertEquals("failover cycle=1 room=<id> ended with error: no peer in room", s.scrub(RustCoreLines.ROOM_SALUTE_CODE))
        assertEquals("failover cycle=3 starting room=<id>", s.scrub(RustCoreLines.ROOM_NUMERIC))
        assertEquals("interop-p2p: joined <id> as peer-4", s.scrub(RustCoreLines.ROOM_JOINED))
    }

    @Test fun aDestinationAndAResolverAreTagged() {
        val host = s.scrub(RustCoreLines.TUNNEL_TO_HOST)
        assertFalse(host.contains("example-site"), host)
        assertTrue(host.startsWith("sid=14 tunnel to node#") && host.endsWith(":443"), host)
        val v4 = s.scrub(RustCoreLines.TUNNEL_TO_V4)
        assertFalse(v4.contains("198.51.100.23"), v4)
        val dns = s.scrub(RustCoreLines.DNS_RESOLVER)
        assertFalse(dns.contains("198.51.100.53"), dns)
        assertTrue(dns.endsWith(":53"), dns)
    }

    @Test fun anAddressWrittenInMappedFormLeavesNoTailBehind() {
        val out = s.scrub(RustCoreLines.DIAL_MAPPED)
        assertFalse(out.contains("198.51"), out)
        assertFalse(out.contains(".100.24"), out)
        assertTrue(tag.containsMatchIn(out), out)
        assertTrue(out.endsWith(":443: connection refused"), out)
    }

    @Test fun theSameHostReadsTheSameInTheWarningAndInTheTunnelLine() {
        val a = tag.find(s.scrub("\$.outbounds[0].x: of 203.0.113.9:443 (y)"))!!.value
        val b = tag.find(s.scrub("sid=1 tunnel to 203.0.113.9:443"))!!.value
        assertEquals(a, b)
    }

    @Test fun linesThatNameNothingComeBackWhole() {
        for (line in RustCoreLines.UNTOUCHED) assertEquals(line, s.scrub(line), line)
    }

    @Test fun theLogsHeadNamesTheCorePin() {
        val pin = "add3245c407f4749812fec63eb799fd4e4907fa1"
        assertEquals(
            listOf("Ghostlane application logs", "Core: ghostlane-core add3245c407f", "Entries: 3"),
            LogHead.lines(3, pin)
        )
    }

    @Test fun aBuildWithoutARustCoreNamesNone() {
        assertEquals(listOf("Ghostlane application logs", "Entries: 0"), LogHead.lines(0, null))
        // Not a commit: left out rather than printed into a support report.
        assertEquals(listOf("Ghostlane application logs", "Entries: 0"), LogHead.lines(0, "main; rm -rf"))
    }
}
