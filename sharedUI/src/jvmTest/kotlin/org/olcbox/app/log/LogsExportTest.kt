package org.olcbox.app.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The exported log names the core the build carries, and a Rust session has its own section. */
class LogsExportTest {
    private val pinFile = listOf("../scripts/ghostlane-core-pin.sh", "scripts/ghostlane-core-pin.sh")
        .map(::File).first { it.isFile }

    private fun pinFromFile(): String = pinFile.readLines()
        .first { it.startsWith("GHOSTLANE_CORE_COMMIT=") }
        .substringAfter('=').trim().trim('"')

    @Test fun theConstantIsTheCommitOfThePinFile() {
        val pin = pinFromFile()
        assertTrue(Regex("[0-9a-f]{40}").matches(pin), pin)
        assertEquals(pin, CorePin.COMMIT)
    }

    @Test fun theHeadNamesThePinBetweenTheTitleAndTheEntries() {
        val out = LogsExport.build(listOf("one", "two"), emptyList(), CorePin.COMMIT).lines()
        assertEquals("Ghostlane application logs", out[0])
        assertEquals("Core: ghostlane-core ${pinFromFile().take(12)}", out[1])
        assertEquals("Entries: 2", out[2])
        assertEquals("", out[3])
        assertEquals("1. one", out[4])
    }

    @Test fun aBuildWithNoPinSaysNothingOfOne() {
        val out = LogsExport.build(listOf("one"), emptyList(), null)
        assertFalse(out.contains("Core:"), out)
    }

    @Test fun theDiagnosticsComeLastAndAreScrubbed() {
        val out = LogsExport.build(listOf("one"), listOf("dial 203.0.113.77:443 failed"), CorePin.COMMIT)
        assertTrue(out.contains("--- tunnel diagnostics ---"), out)
        assertFalse(out.contains("203.0.113.77"), out)
        assertTrue(out.indexOf("1. one") < out.indexOf("--- tunnel diagnostics ---"), out)
    }

    @Test fun aRustSessionsSectionHoldsThePinAndTheCoresOwnLinesOnly() {
        val logs = listOf(
            "Starting VPN",
            "core: dial tcp 203.0.113.77:443 refused",
            "rtc: joined",
            "Rust core ready on 127.0.0.1:1080",
            "Network changed",
        )
        val section = RustCoreSection.lines(CorePin.COMMIT, logs)
        assertEquals("--- Rust core ---", section[0])
        assertEquals("ghostlane-core ${pinFromFile().take(12)}", section[1])
        assertEquals(3, section.size - 2)
        assertFalse(section.any { it.contains("Starting VPN") || it.contains("Network changed") }, section.toString())
        assertFalse(section.joinToString("\n").contains("203.0.113.77"))
    }

    @Test fun aRustSessionWithNoLinesStillNamesItsCore() {
        assertEquals(
            listOf("--- Rust core ---", "ghostlane-core ${pinFromFile().take(12)}"),
            RustCoreSection.lines(CorePin.COMMIT, emptyList())
        )
    }

    @Test fun theSectionIsBounded() {
        val logs = (1..500).map { "core: line $it" }
        val section = RustCoreSection.lines(CorePin.COMMIT, logs)
        assertEquals(RustCoreSection.MAX_LINES + 2, section.size)
        assertEquals("core: line 500", section.last())
    }
}
