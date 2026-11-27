package org.olcbox.app.vpn.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Linux flip, as the published asset and the first start can show it.
 *
 * The AppImage carries ghostlane-engine at the core pin and none of the Go
 * cores the old build staged. The launcher sweeps the copies an earlier
 * version left in the app's own bin directory, and the sweep is a named
 * operation the JVM can be asked about too. The release job checks the
 * finished asset with K1 and reads the engine's own version event back.
 */
class LinuxFlipTest {
    private val root = File("..").canonicalFile

    private fun text(path: String): String = File(root, path).readText()

    private fun releaseLinuxJob(): String {
        val release = text(".github/workflows/release.yml")
        val from = release.indexOf("\n  build-linux:")
        assertTrue(from >= 0, "release.yml has no build-linux job")
        val to = release.indexOf("\n  build-cli:", from + 1)
        assertTrue(to > from, "release.yml's build-linux job has no end")
        return release.substring(from, to)
    }

    private fun appRun(): String {
        val gradle = text("desktopApp/gradle/native-linux.gradle.kts")
        val from = gradle.indexOf("APPRUN")
        assertTrue(from >= 0, "the Linux AppDir writes no AppRun")
        return gradle.substring(from, gradle.indexOf("APPRUN", from + 1))
    }

    @Test
    fun theLinuxResourcesCarryTheEngineAndNoGoCores() {
        val lines = text("desktopApp/gradle/native-linux.resources.txt").lines()
            .map { it.trim() }.filter { "native/" in it }
        for (arch in listOf("amd64", "arm64")) {
            assertTrue(
                "$arch bundled native/ghostlane-engine" in lines,
                "$arch does not bundle ghostlane-engine"
            )
        }
        val go = lines.filter { line ->
            listOf("native/sing-box", "native/xray", "native/olcrtc").any { it in line }
        }
        assertTrue(go.isEmpty(), "the Linux package still stages Go cores: $go")
    }

    @Test
    fun theLinuxReleaseJobBuildsTheEngineAndChecksTheFinishedAsset() {
        val job = releaseLinuxJob()
        assertTrue("build-ghostlane-core-host.sh" in job, "the Linux job does not build ghostlane-engine")
        assertTrue("ghostlane-engine version" in job, "the Linux job does not read the engine's version")
        assertTrue("check-no-go.sh" in job, "the Linux job does not check the AppImage for Go")
        assertFalse("build-singbox.sh" in job, "the Linux job still builds sing-box")
        assertFalse("Xray-linux-64" in job, "the Linux job still downloads Xray")
    }

    @Test
    fun theLinuxAppImageAllowListIsGone() {
        assertFalse(
            File(root, "scripts/no-go-allow.d/Ghostlane-VERSION-linux-amd64.AppImage.allow").exists(),
            "the Linux AppImage is still allowed to hold Go"
        )
    }

    @Test
    fun theLauncherSweepsTheGoCoresOfEarlierBuilds() {
        val script = appRun()
        for (name in listOf("sing-box", "xray", "olcrtc-linux-", "libolcrtc-linux-")) {
            assertTrue(name in script, "AppRun does not sweep $name")
        }
        assertTrue("rm -f" in script, "AppRun does not remove the stale files")
    }

    @Suppress("UNCHECKED_CAST")
    private fun sweep(bin: Path): List<String> {
        val kind = try {
            Class.forName("org.olcbox.app.vpn.desktop.StaleBinarySweep")
        } catch (e: ClassNotFoundException) {
            throw AssertionError("StaleBinarySweep is missing: ${e.message}")
        }
        val method = try {
            kind.getMethod("sweep", Path::class.java)
        } catch (e: NoSuchMethodException) {
            throw AssertionError("StaleBinarySweep has no sweep(Path): ${e.message}")
        }
        return method.invoke(kind.getField("INSTANCE").get(null), bin) as List<String>
    }

    @Test
    fun theSweepRemovesStagedGoCoresAndKeepsTheEngine() {
        val bin = Files.createTempDirectory("linux-flip-sweep")
        try {
            val stale = listOf("sing-box", "xray", "olcrtc-linux-amd64", "libolcrtc-linux-arm64.so")
            for (name in stale + "ghostlane-engine") Files.write(bin.resolve(name), byteArrayOf(1))
            val removed = sweep(bin)
            assertEquals(stale.toSet(), removed.toSet())
            for (name in stale) assertFalse(Files.exists(bin.resolve(name)), "$name was not swept")
            assertTrue(Files.exists(bin.resolve("ghostlane-engine")), "the engine was swept")
            assertTrue(sweep(bin).isEmpty(), "a second sweep is not empty")
        } finally {
            bin.toFile().deleteRecursively()
        }
    }
}
