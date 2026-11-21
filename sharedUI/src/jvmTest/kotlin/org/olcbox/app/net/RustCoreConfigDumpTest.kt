package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM-only: writes `core.json` for every case of every kind of line the Rust
 * core runs ([CoreFixtures]), under Global and under Bypass Russia with the
 * stand-in lists ([CoreFixtures.lists]), and once more for the iPhone's
 * Reality line under Bypass Russia with the bundled lists, to
 * `build/rust-core-configs/`, and holds each to its reference config in
 * `src/jvmTest/resources/reference-configs/`, byte for byte. A case's
 * references are `<reference>.json` and `bypass-<reference>.json`:
 * `core-<case>` for a kind added since, `ios-core-<line>` for the six lines
 * the iPhone ships, whose own tests read those names; the last one is
 * `bypass-ios-core-bundled-lists.json`.
 *
 * The references are what `scripts/check-rust-core-configs.sh` hands to the
 * core's own parser (`xray-rust config check`), so a writer change that
 * moves them is a change the core has to be asked about again: rewrite the
 * reference from `build/rust-core-configs/` and run the script. A refresh of
 * the bundled lists moves `bypass-ios-core-bundled-lists.json` alone, and is
 * asked about the same way: a spelling the core refuses fails the whole
 * config on the phone.
 *
 * Which cases there are is the registry's to say ([CoreRegistry]): a new kind
 * of line is written and held here by its fixture alone, with no edit of this
 * test.
 */
class RustCoreConfigDumpTest {
    private val outDir = File("build/rust-core-configs")
    private val references = File("src/jvmTest/resources/reference-configs")
    private val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)

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

    /**
     * Every config the references hold the writer to, by the reference's
     * name. Each is written when it is asked for, so that one a kind cannot
     * write fails under its own name and the others are still written.
     */
    private suspend fun configs(): List<Pair<String, () -> String>> {
        val login = CoreFixtures.login
        val perCase = CoreFixtures.cases.flatMap { case ->
            listOf<Pair<String, () -> String>>(
                case.reference to { CoreConfig.build(case.spec(), login) },
                case.bypassReference to { CoreConfig.build(case.spec(), login, routing = bypass, geodata = CoreFixtures.lists) },
            )
        }
        val bundledLists = XrayGeodata.lists()
        val reality = IosCoreFixtures.spec(IosCoreLine.RealityVision)
        val bundled: Pair<String, () -> String> =
            CoreRegistry.BUNDLED_LISTS to { CoreConfig.build(reality, login, routing = bypass, geodata = bundledLists) }
        return perCase + bundled
    }

    @Test fun dumpCoreConfigs() = runTest {
        val written = mutableListOf<String>()
        // Every config is attempted before any reference is held to it, so
        // one run writes them all.
        val failures = configs().mapNotNull { (name, build) ->
            written += name
            runCatching { dump(name, build()) }.exceptionOrNull()?.let { "$name: ${it.message?.take(400)}" }
        }
        assertEquals(CoreFixtures.cases.size * 2 + 1, written.size)
        assertEquals(written.size, written.toSet().size, "a reference config is written twice: $written")
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * The iPhone's thirteen as its own entry point writes them
     * ([XrayConfig.buildIosCore]), under the names its own tests read: each is
     * its reference config byte for byte, and the shared writer's config for
     * the same case. The parity ledger names this test for the iPhone's lines.
     */
    @Test fun dumpIosCoreConfigs() = runTest {
        val byName = configs().toMap()
        val iphone = IosCoreLine.entries.flatMap { line ->
            val spec = IosCoreFixtures.spec(line)
            listOf(
                "ios-core-${line.slug}" to XrayConfig.buildIosCore(spec, IosCoreFixtures.login),
                "bypass-ios-core-${line.slug}" to XrayConfig.buildIosCore(
                    spec, IosCoreFixtures.login, routing = bypass, geodata = IosCoreFixtures.lists,
                ),
            )
        } + (
            CoreRegistry.BUNDLED_LISTS to XrayConfig.buildIosCore(
                IosCoreFixtures.spec(IosCoreLine.RealityVision), IosCoreFixtures.login, routing = bypass, geodata = XrayGeodata.lists(),
            )
        )
        assertEquals(IosCoreLine.entries.size * 2 + 1, iphone.size)
        assertEquals(iphone.size, byName.keys.count { "ios-core-" in it })
        // Every config is attempted before any reference is held to it, so
        // one run names every one that moved.
        val failures = iphone.mapNotNull { (name, json) ->
            runCatching {
                assertEquals(byName.getValue(name).invoke(), json, "$name: the iPhone's entry point and the shared writer part")
                dump(name, json)
            }.exceptionOrNull()?.let { "$name: ${it.message?.take(400)}" }
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * The comparison above proves itself on every config: one byte more, one
     * less or one changed is a difference, and so is what a comparison of
     * parsed JSON lets through (an editor's newline at the end, a space after
     * a colon).
     */
    @Test fun oneByteOfDifferenceIsSeenInEveryConfig() = runTest {
        for ((name, build) in configs()) {
            val json = build()
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
