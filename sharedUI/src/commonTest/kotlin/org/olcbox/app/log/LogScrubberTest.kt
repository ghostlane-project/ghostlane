package org.olcbox.app.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {
    private val s = LogScrubber(salt = 12345L)

    @Test fun aPublicAddressBecomesATag() {
        val out = s.scrub("engine: dial tcp 95.179.246.109:443: i/o timeout")
        assertFalse(out.contains("95.179.246.109"), out)
        assertTrue(Regex("node#[0-9a-f]{12}").containsMatchIn(out), out)
        assertTrue(out.contains(":443: i/o timeout"), "the port and the error must survive: $out")
    }

    @Test fun aPublicIpv6BecomesATag() {
        val out = s.scrub("engine: dial tcp [2a01:4f8:c17:b8f::1]:443 failed")
        assertFalse(out.contains("2a01"), out)
        assertTrue(Regex("node#[0-9a-f]{12}").containsMatchIn(out), out)
    }

    @Test fun theSameHostReadsTheSameAndDifferentHostsDiffer() {
        val out = s.scrub("tried 95.179.246.109 then 45.32.190.172 then 95.179.246.109")
        val tags = Regex("node#[0-9a-f]{12}").findAll(out).map { it.value }.toList()
        assertEquals(3, tags.size, out)
        assertEquals(tags[0], tags[2], "one host must read the same throughout: $out")
        assertTrue(tags[0] != tags[1], "two hosts must not collapse into one: $out")
    }

    @Test fun localAddressesSurviveVerbatim() {
        // Most of what makes a log readable is local, and none of it is secret.
        for (line in listOf(
            "core ready on 127.0.0.1:1080",
            "socks5 server listening on 0.0.0.0:10808",
            "tun 172.19.0.1/30 up",
            "tun6 fdfe:dcba:9876::1/126 up",
            "gateway 192.168.1.1",
            "wg peer 10.66.66.2",
            "carrier nat 100.64.0.1",
            "self-assigned 169.254.1.1",
        )) {
            assertEquals(line, s.scrub(line), "a local address must not be tagged")
        }
    }

    @Test fun credentialsAndCapabilitiesGo() {
        assertEquals("start room=<id>", s.scrub("start room=a3f9c1e2-4b5d-6789-0abc-def012345678"))
        assertEquals("import <link>", s.scrub("import olcrtc://crypt1/AAAAbbbbCCCCdddd"))
        assertEquals("import <link>", s.scrub("import happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8"))
        assertEquals("GET <host>/sub/x", s.scrub("GET sub.proofkit.org/sub/x"))
        assertEquals("GET <host>/api", s.scrub("GET proofkit.org/api"))
    }

    @Test fun anOrdinaryLineComesBackUntouched() {
        // The regressions that matter are the ones that eat text nobody was hiding.
        for (line in listOf(
            "2026-08-07 15:28:17 INFO tunnel established",
            "olcbox 1.0.270 (270) starting",
            "build 1.2.3.400 is not an address",
            "Packet tunnel up",
        )) {
            assertEquals(line, s.scrub(line), "nothing sensitive here — leave it alone")
        }
    }

    @Test fun destinationNamesBecomeStableSaltedTags() {
        val out = s.scrub("dial www.example.com:443 then www.example.com; fallback dns.google")
        assertFalse(out.contains("example.com"), out)
        assertFalse(out.contains("dns.google"), out)
        val tags = Regex("node#[0-9a-f]{12}").findAll(out).map { it.value }.toList()
        assertEquals(3, tags.size, out)
        assertEquals(tags[0], tags[1], "the same destination must remain correlatable: $out")
        assertTrue(tags[0] != tags[2], out)
    }

    @Test fun fileNamesPathsAndStackSymbolsSurvive() {
        for (line in listOf(
            "launchctl kickstart -k system/org.olcbox.app.desktopApp.tunneld",
            "java.io.FileNotFoundException: config.json",
            "github.com/sagernet/sing-box/route.(*Router).Start",
            "source App.kt and rules.srs then core.exe",
        )) {
            assertEquals(line, s.scrub(line), line)
        }
    }

    @Test fun punycodeAndUnicodeDestinationsAreScrubbedWhole() {
        for (host in listOf("xn--d1acpjx3f.xn--p1ai", "пример.рф", "例子.中国", "مثال.ايران")) {
            val out = s.scrub("dial $host:443")
            assertFalse(out.contains(host), out)
            assertTrue(Regex("node#[0-9a-f]{12}:443").containsMatchIn(out), out)
        }
    }

    @Test fun realitySniAndServerHostsAreScrubbedButRemainCorrelatable() {
        val out = s.scrub("server edge.example.net failed; reality sni www.example.com; retry edge.example.net")
        assertFalse(out.contains("edge.example.net"), out)
        assertFalse(out.contains("www.example.com"), out)
        val tags = Regex("node#[0-9a-f]{12}").findAll(out).map { it.value }.toList()
        assertEquals(3, tags.size, out)
        assertEquals(tags[0], tags[2], out)
    }

    @Test fun mixedCaseUrlAndMultilineDestinationsAreScrubbed() {
        val source = "failed GET https://WWW.Example.com/assets/x\n" +
            "next outbound to Api.Example.net:443\n(stack follows)"
        val out = s.scrub(source)
        assertFalse(out.contains("Example.com", ignoreCase = true), out)
        assertFalse(out.contains("Example.net", ignoreCase = true), out)
        assertFalse(out.contains("assets/x"), out)
        assertTrue(out.contains("https://node#"), out)
        assertTrue(out.contains("/<path>"), out)
        assertTrue(out.contains("\nnext outbound to node#"), out)
    }

    @Test fun uppercaseTopLevelDomainDoesNotBypassScrubbing() {
        val out = s.scrub("dial example.COM:443")
        assertFalse(out.contains("example.COM"), out)
        assertTrue(out.contains("node#"), out)
    }

    @Test fun knownServiceUrlStillLosesItsPath() {
        val out = s.scrub("GET https://api.proofkit.org/private/subscription-token")
        assertFalse(out.contains("subscription-token"), out)
        assertTrue(out.contains("/<path>"), out)
    }

    @Test fun aDifferentSaltGivesADifferentTag() {
        // Otherwise a tag is a confirmation oracle: guess the address, compute the tag.
        val line = "dial 95.179.246.109"
        assertTrue(
            s.scrub(line) != LogScrubber(salt = 999L).scrub(line),
            "tags must not be comparable across installs"
        )
    }

    @Test fun theTransportStateMarkersSurvive() {
        // These exact strings decide reconnect — handleRtcLine (IosVpnManager),
        // OlcboxVpnService and DesktopVpnManager all match on them. The scrubber runs
        // after those parsers today, and this test is what keeps a future refactor
        // from quietly breaking reconnect by moving it earlier.
        for (marker in listOf(
            "socks5 server listening on 127.0.0.1:1080",
            "ice connection state changed: connected",
            "peer connection state changed: connected",
            "ice connection state changed: failed",
            "peer connection state changed: closed",
            "network is unreachable",
            "use of closed network connection",
            "read/write on closed pipe",
        )) {
            assertEquals(marker, s.scrub(marker), "a state marker must pass through intact")
        }
    }
}
