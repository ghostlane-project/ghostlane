package org.olcbox.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * What the shipped Android artefact has to carry after the flip.
 *
 * This runs on the JVM: PR Checks needs no emulator to know whether the app is
 * built with the Rust core on by default, and a release can hand it the APK and
 * the AAB it is about to publish (-Pghostlane.apk / -Pghostlane.aab). An
 * instrumented test could only answer the question after a build that no
 * ordinary push makes.
 *
 * The Go engines stay in the tree for A7 to delete. They must not stay in the
 * artefact: K1 reads every shipped file at every nesting, and a libgojni.so or
 * a Go core is enough to fail a release.
 */
class CorePackagingTest {

    private val abis: List<String> = (System.getProperty("ghostlane.abis") ?: DEFAULT_ABIS)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** The switch has no third state: a release is Rust unless a test asks for Go. */
    @Test
    fun rustCoreIsOnByDefault() {
        val flag = System.getProperty("ghostlane.rustCoreDefault")?.toBooleanStrictOrNull()
        assertTrue(
            "the app still defaults to the Go cores: -Polcbox.rustCoreDefault " +
                "must no longer be needed to ship libghostlane_core.so",
            flag == true
        )
    }

    @Test
    fun apkShipsTheRustCoreAndNoGoCores() {
        val path = System.getProperty("ghostlane.apk")?.takeIf { it.isNotBlank() } ?: return
        val apk = File(path)
        assertTrue("no APK at $path", apk.isFile)
        ZipFile(apk).use { zip ->
            val names = zip.entries().toList().map { it.name }
            abis.forEach { abi ->
                assertTrue(
                    "APK holds no lib/$abi/libghostlane_core.so",
                    names.contains("lib/$abi/libghostlane_core.so")
                )
            }
            val go = names.filter { name -> GO_LIBS.any { name.endsWith("/$it") || name == it } }
            assertTrue("Go cores packaged in the APK: $go", go.isEmpty())
        }
    }

    @Test
    fun bundleShipsTheRustCoreAndNoGoCores() {
        val path = System.getProperty("ghostlane.aab")?.takeIf { it.isNotBlank() } ?: return
        val aab = File(path)
        assertTrue("no AAB at $path", aab.isFile)
        ZipFile(aab).use { zip ->
            val names = zip.entries().toList().map { it.name }
            abis.forEach { abi ->
                assertTrue(
                    "AAB holds no base/lib/$abi/libghostlane_core.so",
                    names.contains("base/lib/$abi/libghostlane_core.so")
                )
            }
            val go = names.filter { name -> GO_LIBS.any { name.endsWith("/$it") || name == it } }
            assertTrue("Go cores packaged in the AAB: $go", go.isEmpty())
        }
    }

    /** The unstripped libraries are kept beside the assets, one per ABI, for symbolication. */
    @Test
    fun aSymbolsFileIsKeptForEveryAbi() {
        val dir = System.getProperty("ghostlane.symbols")?.takeIf { it.isNotBlank() } ?: return
        abis.forEach { abi ->
            val file = File(dir, "$abi/libghostlane_core.so")
            assertTrue("no native symbols file at ${file.path}", file.isFile && file.length() > 0)
        }
    }

    private companion object {
        const val DEFAULT_ABIS = "arm64-v8a,armeabi-v7a,x86_64"
        val GO_LIBS = listOf("libgojni.so", "libsingboxcore.so", "libxraycore.so")
    }
}
