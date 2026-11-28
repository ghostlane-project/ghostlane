package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.test.runTest
import org.olcbox.app.vpn.TrafficCounters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Windows tun's counters: what `Get-NetAdapterStatistics` says, read the way the other desktops' are. */
class WindowsTrafficTest {
    @Test fun anAdaptersTwoCountsAreWhatItReceivedAndSent() {
        val counted = WindowsAdapterTraffic.counters("""{"ReceivedBytes":123456789012,"SentBytes":4096}""")
        assertEquals(TrafficCounters(bytesIn = 123_456_789_012L, bytesOut = 4_096L), counted)
    }

    @Test fun aListOfOneIsTheSameAnswer() {
        val counted = WindowsAdapterTraffic.counters("""[{"ReceivedBytes":9,"SentBytes":8}]""")
        assertEquals(TrafficCounters(bytesIn = 9L, bytesOut = 8L), counted)
    }

    @Test fun whatIsNotAnAnswerIsNoReadingAndNeverAZero() {
        for (text in listOf("", "  \r\n", "null", "[]", "{}", """{"ReceivedBytes":5}""", """{"ReceivedBytes":"x","SentBytes":1}""",
            """{"ReceivedBytes":-1,"SentBytes":1}""", "Get-NetAdapterStatistics : No MSFT_NetAdapter objects found")) {
            assertNull(WindowsAdapterTraffic.counters(text), text)
        }
    }

    @Test fun theScriptNamesTheAdapterAsOneQuotedString() {
        val script = WindowsAdapterTraffic.script("Olcbox's 'tun'")
        assertTrue("-Name 'Olcbox''s ''tun'''" in script, script)
        assertTrue("ConvertTo-Json" in script)
    }

    @Test fun aReaderAsksWithItsScriptAndAnswersWhatItWasTold() = runTest {
        val asked = mutableListOf<String>()
        val reader = WindowsAdapterTraffic("Olcbox") { asked += it; """{"ReceivedBytes":1,"SentBytes":2}""" }
        assertEquals(TrafficCounters(bytesIn = 1L, bytesOut = 2L), reader.read())
        assertEquals(listOf(WindowsAdapterTraffic.script("Olcbox")), asked)
        assertNull(WindowsAdapterTraffic("Olcbox") { null }.read())
    }
}
