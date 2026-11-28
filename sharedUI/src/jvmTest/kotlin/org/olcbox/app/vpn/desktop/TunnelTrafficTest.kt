package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.test.runTest
import org.olcbox.app.vpn.TrafficCounters
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
 * Linux and macOS, the adapter's on Windows ([WindowsAdapterTraffic]).
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

    // ── macOS: the engine's tun, counted by the kernel ──────────────────────

    /** `netstat -ib`, as a Mac writes it: the link row carries the byte counts. */
    private val netstat = """
        Name  Mtu   Network       Address            Ibytes    Ierrs  Idrop     Obytes Oerrs  Coll
        lo0   16384 <Link#1>                       56336460        0      0   56336460     0     0
        utun231 1500 <Link#21>                     2600000000      0      0    516000000     0     0
        utun231 1500 198.19/16     198.19.0.1       1200000        0      0      300000     0     0
    """.trimIndent()

    @Test fun onMacTheCountIsTheTunDevicesOwn() = runTest {
        // The link row, not the address row that follows it: the latter counts
        // only what was addressed on that network, not all of the tunnel's.
        val counted = MacTunTraffic("utun231") { netstat }.read()
        assertEquals(TrafficCounters(bytesIn = 2_600_000_000L, bytesOut = 516_000_000L), counted)
    }

    @Test fun aMacTunThatIsNotThereIsNoReadingAndNotAZero() = runTest {
        assertNull(MacTunTraffic("utun9") { netstat }.read())
        assertNull(MacTunTraffic("utun231") { "" }.read())
    }

    @Test fun anAnswerWithoutByteColumnsIsNoReading() = runTest {
        // A Mac that prints packets rather than bytes has no answer for a
        // session's counter: reading those columns as bytes would be a lie.
        val packets = netstat.replace("Ibytes", "Ipkts").replace("Obytes", "Opkts")
        assertNull(MacTunTraffic("utun231") { packets }.read())
        val broken = netstat.replace("2600000000", "not a number")
        assertNull(MacTunTraffic("utun231") { broken }.read())
    }

    private companion object {
        const val ENGINE = "/Users/a/Library/Application Support/Ghostlane/bin/olcrtc-darwin-arm64"
        const val BROWSER = "/Applications/Safari.app/Contents/MacOS/Safari"
    }
}
