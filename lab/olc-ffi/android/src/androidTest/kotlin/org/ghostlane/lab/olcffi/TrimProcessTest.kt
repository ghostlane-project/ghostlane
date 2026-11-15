package org.ghostlane.lab.olcffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a trim gives back with the core in a process of its own: the same
 * library code (olc-ffi through its app API, on the counting allocator)
 * as xray-rust's olc-ffi-client in its control mode (fp-host's protocol,
 * the Linux footprint harness's host), packaged as
 * lib/x86_64/libolcffidriver.so and run from the app's library directory,
 * as the app runs its other cores. No ART, no zygote pages, so its
 * Private_Dirty and its Scudo mappings are the core's alone. The burst's
 * client threads are in this process; the child holds only its
 * connections. Steps as TrimTest: idle, 256 downloads at once, then a wait,
 * trim(), M_PURGE, M_PURGE_ALL and M_PURGE again, each after a wait.
 */
@RunWith(AndroidJUnit4::class)
class TrimProcessTest {
    private class Driver(val process: Process) {
        val lines = LinkedBlockingQueue<String>()

        @Volatile var tick: String = ""

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith("TICK ")) tick = line else lines.put(line)
                }
                lines.put("EOF")
            }.apply { isDaemon = true }.start()
        }

        fun ask(command: String, answer: String, seconds: Long = 60): String {
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.flush()
            return expect(answer, seconds)
        }

        fun expect(answer: String, seconds: Long): String {
            val until = System.nanoTime() + seconds * 1_000_000_000
            while (true) {
                val left = until - System.nanoTime()
                val line = lines.poll(maxOf(left, 0L), TimeUnit.NANOSECONDS) ?: error("no $answer within $seconds s")
                if (line.startsWith(answer)) return line
                if (line.startsWith("ERR") || line == "EOF") error("$answer: $line")
            }
        }

        /** The child's memory_stats from its last TICK line. */
        fun stats(): JSONObject? = tick.split(' ', limit = 4).getOrNull(3)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun settle(ms: Long = 3_000) = Thread.sleep(ms)

    @Test
    fun theCoreInAProcessOfItsOwn() {
        val run = T1.arg("run", "1")
        val flows = T1.arg("burst", "256").toInt()
        val t1 = T1.load()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libolcffidriver.so")
        assertTrue("$bin", bin.canExecute())
        val port = freePort()
        val cfg = File(ctx.cacheDir, "driver.json")
        cfg.writeText(
            JSONObject()
                .put("url", t1.room).put("key", t1.key).put("transport", t1.transport).put("dns", t1.dns)
                .put("socks", "127.0.0.1:$port").put("device_id", "lab-emulator-driver")
                .put("socks_user", USER).put("socks_pass", PASS)
                .toString()
        )
        val log = File(ctx.cacheDir, "driver.log")
        val pb = ProcessBuilder(bin.path, cfg.path).redirectError(log)
        pb.environment()["OLC_FFI_CONTROL"] = "1"
        val d = Driver(pb.start())
        try {
            val pid = d.expect("LOADED", 20).substringAfter("pid=").trim()
            fun mem(label: String): Mem {
                val m = readMem(pid)
                say(memLine("proc$run $label", m, d.stats()))
                return m
            }
            mem("loaded")
            say("RESULT proc$run ${d.ask("start", "READY", 40)}")
            assertEquals(1024L, fetch(t1.origin, port).second)
            settle(6_000)
            val idle = mem("idle")

            assertEquals("every download whole", flows, burst("proc$run", t1, port, flows))
            val peak = d.stats()?.optJSONObject("heap")?.opt("peak")
            val drained = System.currentTimeMillis() + 10_000
            while ((d.stats()?.optJSONObject("n")?.optInt("conns", 0) ?: 0) > 0 && System.currentTimeMillis() < drained) Thread.sleep(50)
            settle(6_000)
            val after = mem("after-burst")
            val steps = mutableListOf<String>()
            var last = after
            fun step(name: String, command: String, answer: String) {
                settle()
                val waited = mem("before-$name")
                val said = d.ask(command, answer)
                settle(500)
                val done = mem("after-$name")
                steps += "$name ($said): ${delta(waited, done)} [the wait before it: ${delta(last, waited)}]"
                last = done
            }
            step("trim", "trim", "TRIM")
            step("M_PURGE", "mpurge", "MPURGE")
            step("M_PURGE_ALL", "mpurge_all", "MPURGE_ALL")
            step("M_PURGE again", "mpurge", "MPURGE")
            say(
                "RESULT proc$run: idle pss ${idle.pss} kB priv_dirty ${idle.privDirty} kB alloc_priv ${idle.allocPriv} kB; " +
                    "after the burst pss ${after.pss} kB priv_dirty ${after.privDirty} kB alloc_priv ${after.allocPriv} kB " +
                    "(the core's heap peak $peak B)"
            )
            for (s in steps) say("RESULT proc$run $s")
            say("RESULT proc$run ${d.ask("stop", "STOPPED")}")
            settle()
            mem("stopped")
            d.ask("quit", "BYE")
            assertTrue(d.process.waitFor(10, TimeUnit.SECONDS))
        } finally {
            if (d.process.isAlive) d.process.destroyForcibly()
            runCatching { log.readLines().takeLast(15).forEach { say("DRIVER $it") } }
        }
    }
}
