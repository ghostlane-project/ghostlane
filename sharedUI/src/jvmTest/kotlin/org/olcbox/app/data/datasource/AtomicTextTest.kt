package org.olcbox.app.data.datasource

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The location bundle has two readers on Android, the app and the VPN service,
// and one of them reads without waiting for the other's lock. A bundle read
// half written is a bundle that does not parse, and a store taken for empty.
class AtomicTextTest {
    // The rename is rename(2). Windows refuses to rename over a file, and the
    // desktop does not use this class.
    private val posix = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun theTextIsReplacedAndNothingIsLeftBeside() {
        if (!posix) return
        val dir = Files.createTempDirectory("olcbox-atomic-text").toFile()
        val target = File(dir, "bundle.json")

        AtomicText.write(target, "one")
        AtomicText.write(target, "two")

        assertEquals("two", target.readText())
        assertEquals(listOf("bundle.json"), dir.list().orEmpty().toList())
    }

    // A process that dies between writing and renaming leaves a whole copy
    // beside the target. The next write sweeps it, but not a file young enough
    // to be another writer's.
    @Test
    fun whatADeadProcessLeftBesideIsSweptOnceItIsOld() {
        if (!posix) return
        val dir = Files.createTempDirectory("olcbox-atomic-text").toFile()
        val target = File(dir, "bundle.json")
        val stale = File(dir, "bundle.json.123.tmp").apply {
            writeText("half")
            setLastModified(System.currentTimeMillis() - 10 * 60_000L)
        }
        val young = File(dir, "bundle.json.456.tmp").apply { writeText("another writer's") }
        val unrelated = File(dir, "other.json.789.tmp").apply {
            writeText("not ours")
            setLastModified(System.currentTimeMillis() - 10 * 60_000L)
        }

        AtomicText.write(target, "one")

        assertEquals("one", target.readText())
        assertFalse(stale.exists())
        assertTrue(young.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun aReaderSeesTheOldTextOrTheNewNeverPartOfOne() {
        if (!posix) return
        val dir = Files.createTempDirectory("olcbox-atomic-text").toFile()
        val target = File(dir, "bundle.json")
        // Large enough that a write in place takes many system calls.
        val first = "a".repeat(2_000_000)
        val second = "b".repeat(2_000_000)
        AtomicText.write(target, first)

        val writing = AtomicBoolean(true)
        val torn = AtomicReference<String?>(null)
        val reader = thread {
            while (writing.get() && torn.get() == null) {
                val seen = target.readText()
                if (seen != first && seen != second) {
                    torn.set("length ${seen.length}, starts ${seen.take(1)}, ends ${seen.takeLast(1)}")
                }
            }
        }
        repeat(40) { round -> AtomicText.write(target, if (round % 2 == 0) second else first) }
        writing.set(false)
        reader.join()

        assertNull(torn.get(), "a reader saw a file that was neither text")
    }
}
