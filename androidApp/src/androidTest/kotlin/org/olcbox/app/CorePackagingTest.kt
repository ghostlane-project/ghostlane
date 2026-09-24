package org.olcbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.RuleSets
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.XrayGeodata
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the packaged core the way the app runs it, on a real Android.
 *
 * The whole Android connect path had only ever been compile-checked: the desktop
 * side, once someone actually ran it, produced a port collision, a config that
 * ignored the port it was given and a core whose errors went to a file nobody read.
 * None of that is visible to a compiler. These checks cover the Android-specific
 * half — packaging, extraction and exec — which no unit test can reach.
 */
@RunWith(AndroidJUnit4::class)
class CorePackagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The core is shipped as a lib*.so so the installer extracts it somewhere exec is allowed. */
    private fun coreBinary(name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name)

    @Test
    fun regionalRoutingFilesArePackaged() {
        val prefix = "composeResources/multiplatform_app.sharedui.generated.resources/files"
        val expected =
            RuleSets.bundled.map { "$prefix/rules/${it.name}" } +
                XrayGeodata.all.map { "$prefix/xray/${it.name}" }

        expected.forEach { path ->
            val hasBytes = runCatching {
                context.assets.open(path).use { it.read() != -1 }
            }.getOrDefault(false)
            assertTrue("Compose resource missing or empty in installed APK: $path", hasBytes)
        }
    }

    @Test
    fun singBoxIsPackagedAndExecutable() {
        val bin = coreBinary("libsingboxcore.so")
        assertTrue(
            "sing-box missing from nativeLibraryDir (${bin.absolutePath}) — packaging regressed",
            bin.exists()
        )
        assertTrue("sing-box is present but not executable", bin.canExecute())
    }

    /**
     * `sing-box version` is the cheapest proof that the binary matches the device ABI
     * and actually starts: a wrong-architecture or truncated binary fails right here,
     * which is exactly what shipping the arm64 build to an x86_64 device would do.
     */
    @Test
    fun singBoxRunsOnThisDevice() {
        val process = ProcessBuilder(coreBinary("libsingboxcore.so").absolutePath, "version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        assertTrue("core did not report a version, output was: $output", output.contains("sing-box"))
    }

    /**
     * The config our builder produces must be accepted by the binary we ship, on the
     * device. `check` validates it against this exact build rather than the one CI
     * happens to have on the runner.
     */
    @Test
    fun generatedConfigIsValidForTheShippedBinary() {
        val spec = LinkParser.parse(SAMPLE_REALITY_LINK)
        assertTrue("sample link no longer parses", spec is OutboundSpec.Vless)

        val config = File(context.cacheDir, "instrumented-config.json").apply {
            writeText(SingBoxConfig.build(spec!!, socksPort = TEST_SOCKS_PORT))
        }

        val process = ProcessBuilder(
            coreBinary("libsingboxcore.so").absolutePath, "check", "-c", config.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()

        assertTrue("sing-box rejected our config (exit $exit): $output", exit == 0)
    }

    /**
     * End of the Android-specific path: the core, started from the packaged binary
     * with our config, actually listens for SOCKS. A port that never opens is what
     * the app waits on before it reports anything, so this is the step that decides
     * whether a connect can succeed at all.
     */
    @Test
    fun coreOpensItsSocksPort() {
        val spec = LinkParser.parse(SAMPLE_REALITY_LINK)!!
        val config = File(context.cacheDir, "instrumented-listen.json").apply {
            writeText(SingBoxConfig.build(spec, socksPort = TEST_SOCKS_PORT))
        }

        val process = ProcessBuilder(
            coreBinary("libsingboxcore.so").absolutePath, "run", "-c", config.absolutePath
        ).redirectErrorStream(true).start()

        try {
            assertTrue(
                "core never opened 127.0.0.1:$TEST_SOCKS_PORT",
                waitForPort(TEST_SOCKS_PORT)
            )
        } finally {
            process.destroy()
            process.waitFor()
        }
    }

    /**
     * The watchdog used to ask olcRTC whether it was running no matter which
     * transport was actually carrying traffic, and treated "no" as a dead tunnel.
     * That is only a sound question for olcRTC connections; this pins the answer it
     * gives for the others — a core connection was being told its tunnel had died
     * fifteen seconds after it came up, and restarted, forever.
     */
    @Test
    fun olcrtcReportsNotRunningWhenItWasNeverStarted() {
        assertFalse(
            "olcRTC claims to be running without being started — the watchdog's " +
                "transport check would need rethinking",
            mobile.Mobile.new_().isRunning()
        )
    }

    private fun waitForPort(port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private companion object {
        /**
         * Same shape the coordinator serves. The server is never contacted — these
         * checks are about packaging and config, so they stay hermetic.
         */
        const val SAMPLE_REALITY_LINK =
            "vless://d67b1637-4fee-4e0d-bc96-000000000000@127.0.0.1:443" +
                "?type=tcp&security=reality&sni=www.zoom.us&fp=chrome" +
                "&pbk=kY3iGmtRRYBnOSlhivFT0DwqEQ8b3cqkFxQgJStpsAo&sid=14090023" +
                "&flow=xtls-rprx-vision#instrumented"

        /** Away from the app's own core port so a running app cannot collide with the test. */
        const val TEST_SOCKS_PORT = 11810
    }
}
