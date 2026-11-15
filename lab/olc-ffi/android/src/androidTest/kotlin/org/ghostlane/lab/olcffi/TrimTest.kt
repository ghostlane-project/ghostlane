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
import org.junit.Test
import org.junit.runner.RunWith

/** 256 downloads of the big pull at once through the SOCKS port on
 * `port`, each on a thread of its own; the RESULT line and the count of
 * whole ones. */
fun burst(label: String, t1: T1, port: Int, flows: Int): Int {
    val pool = Executors.newFixedThreadPool(flows)
    val t0 = System.nanoTime()
    val results = try {
        (1..flows).map { pool.submit(Callable { runCatching { fetch(t1.big, port) } }) }
            .map { it.get(240, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }
    val secs = (System.nanoTime() - t0) / 1e9
    val whole = results.count { r -> r.getOrNull()?.second == t1.bigBytes }
    val failed = results.mapNotNull { it.exceptionOrNull()?.message }.groupingBy { it }.eachCount()
    say(
        "RESULT $label burst: $whole of $flows downloads of ${t1.bigBytes} bytes whole in " +
            "${"%.1f".format(secs)} s (${"%.0f".format(whole * t1.bigBytes / secs / 1e6 * 8)} Mbit/s)" +
            if (failed.isEmpty()) "" else "; failed: $failed"
    )
    return whole
}

/**
 * What a trim gives back with the core inside an app's process (the
 * service's case: one ART process forked from the zygote): its PSS and
 * Private_Dirty, and the native allocator's own mappings (Scudo's), at
 * idle, after 256 concurrent downloads, then after a wait, `trim()` (the
 * pools; nothing is asked of the allocator off Apple), `mallopt(M_PURGE,
 * 0)` and, on API 34 and later, `mallopt(M_PURGE_ALL, 0)`, each with a wait
 * of the same length before it, so the drift of a process that is mostly
 * not the core's shows beside each step. run-emulator-tests.sh runs it
 * twice, each in a process of its own.
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
        val flows = T1.arg("burst", "256").toInt()
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
            val idle = mem("app$run idle")

            assertEquals("every download whole", flows, burst("app$run", t1, port, flows))
            val peak = stats().getJSONObject("heap").opt("peak")
            val drained = System.currentTimeMillis() + 10_000
            while (stats().getJSONObject("n").optInt("conns", 0) > 0 && System.currentTimeMillis() < drained) Thread.sleep(50)
            settle()
            val after = mem("app$run after-burst")
            val steps = mutableListOf<String>()
            var last = after
            fun step(name: String, action: () -> String) {
                settle()
                val waited = mem("app$run before-$name")
                val said = action()
                val done = mem("app$run after-$name")
                steps += "$name ($said): ${delta(waited, done)} [the wait before it: ${delta(last, waited)}]"
                last = done
            }
            step("trim") { "gave ${trim()} B" }
            step("M_PURGE") { "rc=${libc.mallopt(M_PURGE, 0)}" }
            if (Build.VERSION.SDK_INT >= 34) step("M_PURGE_ALL") { "rc=${libc.mallopt(M_PURGE_ALL, 0)}" }
            step("M_PURGE again") { "rc=${libc.mallopt(M_PURGE, 0)}" }
            say(
                "RESULT app$run (API ${Build.VERSION.SDK_INT}): idle pss ${idle.pss} kB priv_dirty ${idle.privDirty} kB; " +
                    "after the burst pss ${after.pss} kB priv_dirty ${after.privDirty} kB (the core's heap peak $peak B)"
            )
            for (s in steps) say("RESULT app$run $s")
            rt.stop(5_000)
            settle()
            mem("app$run stopped")
        } finally {
            runCatching { if (rt.isRunning()) rt.stop(5_000) }
            setLogWriter(null)
            rt.close()
        }
    }
}
