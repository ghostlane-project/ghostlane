package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM-only: writes `core.json` for every line the Rust core runs on iOS, under
 * Global and under Bypass Russia with the stand-in lists
 * ([IosCoreFixtures.lists]), and once more for Reality Vision under Bypass
 * Russia with the bundled lists, to `build/rust-core-configs/`, and holds each
 * to its reference config in `src/jvmTest/resources/reference-configs/`
 * (`ios-core-<line>.json`, `bypass-ios-core-<line>.json`,
 * `bypass-ios-core-bundled-lists.json`), byte for byte.
 *
 * The references are what `scripts/check-rust-core-configs.sh` hands to the
 * core's own parser (`xray-rust config check`), so a builder change that
 * moves them is a change the core has to be asked about again: rewrite the
 * reference from `build/rust-core-configs/` and run the script. A refresh of
 * the bundled lists moves `bypass-ios-core-bundled-lists.json` alone, and is
 * asked about the same way: a spelling the core refuses fails the whole
 * config on the phone.
 */
class RustCoreConfigDumpTest {
    private val outDir = File("build/rust-core-configs")
    private val references = File("src/jvmTest/resources/reference-configs")

    /**
     * Null when [json] is [reference] byte for byte, else where the two part.
     * Bytes and not parsed JSON: the reference is the file the core's parser
     * was handed, and a builder that moved (a key's place, a number's
     * spelling, an escape, a space) has to show here, whatever a JSON reader
     * makes of it.
     */
    private fun difference(reference: ByteArray, json: String): String? {
        val written = json.encodeToByteArray()
        if (reference.contentEquals(written)) return null
        val shorter = minOf(reference.size, written.size)
        val at = (0 until shorter).firstOrNull { reference[it] != written[it] } ?: shorter
        return "they part at byte $at (the reference has ${reference.size} bytes, the builder wrote ${written.size})"
    }

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
        // The core's field lists first: a missing or stale reference must not
        // hide a field the core would refuse.
        assertEquals(emptyList<String>(), XrayRustSurface.violations(Json.parseToJsonElement(json).jsonObject), name)
        val reference = File(references, "$name.json")
        assertTrue(reference.isFile, "no reference config $name.json: copy it from build/rust-core-configs/ and run scripts/check-rust-core-configs.sh")
        difference(reference.readBytes(), json)?.let {
            fail("$name is not the reference config the core's parser was asked about: $it")
        }
    }

    /** Every config the references hold the builder to, by the reference's name. */
    private suspend fun configs(): List<Pair<String, String>> {
        val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)
        val perLine = IosCoreLine.entries.flatMap { line ->
            val spec = IosCoreFixtures.spec(line)
            listOf(
                "ios-core-${line.slug}" to XrayConfig.buildIosCore(spec, IosCoreFixtures.login),
                "bypass-ios-core-${line.slug}" to XrayConfig.buildIosCore(
                    spec, IosCoreFixtures.login, routing = bypass, geodata = IosCoreFixtures.lists,
                ),
            )
        }
        val bundled = "bypass-ios-core-bundled-lists" to XrayConfig.buildIosCore(
            IosCoreFixtures.spec(IosCoreLine.RealityVision), IosCoreFixtures.login,
            routing = bypass, geodata = XrayGeodata.lists(),
        )
        return perLine + bundled
    }

    @Test fun dumpIosCoreConfigs() = runTest {
        val written = mutableListOf<String>()
        // Every config is attempted before any reference is held to it, so
        // one run writes them all.
        val failures = configs().mapNotNull { (name, json) ->
            written += name
            runCatching { dump(name, json) }.exceptionOrNull()?.let { "$name: ${it.message?.take(400)}" }
        }
        assertEquals(IosCoreLine.entries.size * 2 + 1, written.size)
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * The comparison above proves itself on every config: one byte more, one
     * less or one changed is a difference, and so is what a comparison of
     * parsed JSON lets through (an editor's newline at the end, a space after
     * a colon).
     */
    @Test fun oneByteOfDifferenceIsSeenInEveryConfig() = runTest {
        for ((name, json) in configs()) {
            val bytes = json.encodeToByteArray()
            assertNull(difference(bytes, json), name)
            assertNotNull(difference(bytes + '\n'.code.toByte(), json), "$name: a newline at the end")
            assertNotNull(difference(json.replaceFirst(":", ": ").encodeToByteArray(), json), "$name: a space after a colon")
            assertNotNull(difference(bytes.copyOf(bytes.size - 1), json), "$name: one byte short")
            val changed = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
            assertNotNull(difference(changed, json), "$name: one byte changed")
        }
    }
}
