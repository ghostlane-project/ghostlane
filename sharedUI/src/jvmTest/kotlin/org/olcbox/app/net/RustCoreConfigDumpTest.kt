package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only: writes `core.json` for every line the Rust core runs on iOS, under
 * Global and under Bypass Russia with the bundled lists, to
 * `build/rust-core-configs/`, and holds each to its reference config in
 * `src/jvmTest/resources/reference-configs/` (`ios-core-<line>.json`,
 * `bypass-ios-core-<line>.json`).
 *
 * The references are what `scripts/check-rust-core-configs.sh` hands to the
 * core's own parser (`xray-rust config check`), so a builder change that
 * moves them is a change the core has to be asked about again: rewrite the
 * reference from `build/rust-core-configs/` and run the script.
 */
class RustCoreConfigDumpTest {
    private val outDir = File("build/rust-core-configs")
    private val references = File("src/jvmTest/resources/reference-configs")

    private fun dump(name: String, json: String) {
        outDir.mkdirs()
        File(outDir, "$name.json").writeText(json)
        val reference = File(references, "$name.json")
        assertTrue(reference.isFile, "no reference config $name.json: copy it from build/rust-core-configs/ and run scripts/check-rust-core-configs.sh")
        assertEquals(
            Json.parseToJsonElement(reference.readText()).toString(),
            Json.parseToJsonElement(json).toString(),
            "$name is not the reference config the core's parser was asked about",
        )
        assertEquals(emptyList<String>(), XrayRustSurface.violations(Json.parseToJsonElement(json).jsonObject), name)
    }

    @Test fun dumpIosCoreConfigs() = runTest {
        val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)
        val lists = XrayGeodata.lists()
        val written = mutableListOf<String>()
        // Every line is attempted before any reference is held to it, so one
        // run writes them all.
        val failures = IosCoreLine.entries.flatMap { line ->
            val spec = IosCoreFixtures.spec(line)
            listOf(
                "ios-core-${line.slug}" to XrayConfig.buildIosCore(spec, IosCoreFixtures.login),
                "bypass-ios-core-${line.slug}" to
                    XrayConfig.buildIosCore(spec, IosCoreFixtures.login, routing = bypass, geodata = lists),
            )
        }.mapNotNull { (name, json) ->
            written += name
            runCatching { dump(name, json) }.exceptionOrNull()?.let { "$name: ${it.message?.take(400)}" }
        }
        assertEquals(IosCoreLine.entries.size * 2, written.size)
        assertEquals(emptyList<String>(), failures)
    }
}
