package org.ghostlane.lab.olcffi

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.ghostlane.olcrtc.setLogWriter
import org.ghostlane.olcrtc.trim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a trim gives back on Android: the process's PSS and RSS, and the
 * native allocator's own mappings (Scudo's), at idle, after a burst of 256
 * concurrent downloads through the SOCKS port, after a wait, after
 * `trim()` (the pools; nothing is asked of the allocator off Apple), after
 * `mallopt(M_PURGE, 0)` and, on API 34 and later, `mallopt(M_PURGE_ALL, 0)`.
 * run-emulator-tests.sh runs it twice, each in a process of its own.
 */
@RunWith(AndroidJUnit4::class)
class TrimTest {
    private fun settle(ms: Long = 3_000) {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(ms)
    }

    @Test
    fun aBurstAndWhatTrimAndPurgeGiveBack() {
        val run = T1.arg("run", "1")
        val burst = T1.arg("burst", "256").toInt()
        val t1 = T1.load()
        val rt = OlcrtcRuntime()
        rt.setProtector(Protector())
        setLogWriter(Lines())
        try {
            val port = freePort()
            configure(rt, t1, port)
            rt.start()
            rt.waitReady(35_000)
            assertEquals(1024L, fetch(t1.origin, port).second)
            settle()
            val idle = mem("trim$run idle")

            val pool = Executors.newFixedThreadPool(burst)
            val t0 = System.nanoTime()
            val results = try {
                (1..burst).map { pool.submit(Callable { runCatching { fetch(t1.big, port) } }) }
                    .map { it.get(240, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            val secs = (System.nanoTime() - t0) / 1e9
            val whole = results.count { r -> r.getOrNull()?.second == t1.bigBytes }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }.groupingBy { it }.eachCount()
            say(
                "RESULT trim$run burst: $whole of $burst downloads of ${t1.bigBytes} bytes whole in " +
                    "${"%.1f".format(secs)} s (${"%.0f".format(whole * t1.bigBytes / secs / 1e6 * 8)} Mbit/s)" +
                    if (failed.isEmpty()) "" else "; failed: $failed"
            )
            assertEquals("every download whole", burst, whole)
            val peak = stats()
            val drained = System.currentTimeMillis() + 10_000
            while (stats().getJSONObject("n").optInt("conns", 0) > 0 && System.currentTimeMillis() < drained) Thread.sleep(50)
            settle()
            val after = mem("trim$run after-burst")
            settle()
            val waited = mem("trim$run after-wait")

            val given = trim()
            val trimmed = mem("trim$run after-trim")
            val purge = libc.mallopt(M_PURGE, 0)
            val purged = mem("trim$run after-M_PURGE")
            var all: Mem? = null
            var purgeAll: Int? = null
            if (Build.VERSION.SDK_INT >= 34) {
                purgeAll = libc.mallopt(M_PURGE_ALL, 0)
                all = mem("trim$run after-M_PURGE_ALL")
            }
            fun d(a: Mem, b: Mem) = "pss ${b.pss - a.pss} kB, alloc_pss ${b.allocPss - a.allocPss} kB, rss ${b.rss - a.rss} kB"
            say(
                "RESULT trim$run (API ${Build.VERSION.SDK_INT}): idle pss ${idle.pss} kB, after the burst ${after.pss} kB " +
                    "(alloc_pss ${idle.allocPss} -> ${after.allocPss} kB; heap peak ${peak.getJSONObject("heap").opt("peak")}); " +
                    "a 3 s wait: ${d(after, waited)}; trim() gave $given B: ${d(waited, trimmed)}; " +
                    "M_PURGE rc=$purge: ${d(trimmed, purged)}" +
                    (all?.let { "; M_PURGE_ALL rc=$purgeAll: ${d(purged, it)}" } ?: "; M_PURGE_ALL: API under 34")
            )

            rt.stop(5_000)
            settle()
            val stopped = mem("trim$run stopped")
            trim()
            libc.mallopt(if (Build.VERSION.SDK_INT >= 34) M_PURGE_ALL else M_PURGE, 0)
            val stoppedPurged = mem("trim$run stopped-purged")
            say("RESULT trim$run stopped: pss ${stopped.pss} kB; trim and purge: ${d(stopped, stoppedPurged)}")
            assertTrue(purge == 0 || purge == 1)
        } finally {
            setLogWriter(null)
            rt.close()
        }
    }
}
