package org.ghostlane.lab.olcffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The control for a crash seen once on the 16 KiB-page image (run
 * 43681797712): CallSequenceTest's process died of a SIGSEGV in ART's
 * JIT-compiled code of readMem's smaps reader (a call to address 0) while a
 * concurrent mark-compact GC ran, after the ten stop/starts. This does what
 * that process did on the Kotlin side, the smaps readings with the garbage
 * of a test around them and threads coming and going, without loading
 * libghostlane_core.so or JNA: if it dies the same way, the image's runtime
 * does it with no core in the process.
 */
@RunWith(AndroidJUnit4::class)
class ControlTest {
    @Test
    fun theReadingsAndTheGcWithoutTheCore() {
        val t0 = System.nanoTime()
        var sink = 0L
        var rounds = 0
        while (System.nanoTime() - t0 < 25_000_000_000L) {
            // A worker thread's worth of churn, as the engine's callbacks
            // bring threads in and out of the runtime.
            val churn = Thread {
                val junk = ArrayList<ByteArray>()
                repeat(64) { junk.add(ByteArray(16 shl 10)) }
                sink += junk.sumOf { it.size.toLong() }
            }
            churn.start()
            val m = readMem()
            sink += m.pss + m.allocPriv
            churn.join()
            if (rounds % 20 == 0) System.gc()
            rounds++
        }
        say("RESULT control: $rounds readings with GC churn in 25 s, no core loaded (sink $sink)")
        assertTrue(rounds > 0)
    }
}
