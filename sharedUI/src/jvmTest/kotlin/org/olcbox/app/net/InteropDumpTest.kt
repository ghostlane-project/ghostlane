package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreRegistry
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM-only: the builder's half of the run that holds the lines the app writes
 * against Xray servers (`scripts/rust-core-interop.py`, the workflow
 * `rust-core.yml`, check K5).
 *
 * The driver writes one link per line of `scripts/rust-core-interop/lines/`,
 * with the run's own ports, keys and certificate pins, into
 * `build/rust-core-interop/links.json`. This test hands each link to the
 * builder as the app does ([LinkParser.parse], then [CoreConfig.build], under
 * Global, with a login of its own) and writes what comes out into
 * `build/rust-core-interop/dumps/`: `<line>.core.json`, the config the core is
 * started from as the client, byte for byte; nothing for a link the builder
 * refuses. `manifest.json` beside them names the kind and the shape the
 * builder reads in each link, or why it refused it, and every shape the
 * builder writes, so that the driver fails a shape no line carries.
 *
 * links.json also names the run's routing modes, each on a line, with the
 * resolver names bound straight out are asked of: `<line>.<mode>.core.json`
 * is the same link under that mode ([modeConfig]), and the manifest's
 * "modes" names it, or why the builder refused it. The driver fetches pages
 * by name through it, with the mode's list beside it.
 *
 * Where no driver wrote links (every other Gradle run) there is nothing to
 * dump, and only the writer of the dumps is tested, against the kinds'
 * fixtures.
 *
 * The blocked-only mode's reference config (check K12's) is held here too:
 * [modeConfig] writes it, as it writes the run's dump of a line under a mode.
 */
class InteropDumpTest {
    /** One line of a run: its name, the link the driver wrote, and the SOCKS port the core is to listen on. */
    data class RunLine(val name: String, val link: String, val socksPort: Int)

    /** A routing mode of a run: its name, the line it rides, and where names bound straight out are asked. */
    data class RunMode(val mode: String, val line: String, val directDns: String)

    companion object {
        /** Where the driver's `prepare` writes the links and the dumps go, from the module's directory. */
        val RUN = File("build/rust-core-interop")

        private val NAME = Regex("[a-z0-9][a-z0-9-]*")

        /** The lines of the driver's links.json: `{"lines": [{"name", "link", "socksPort"}]}`. */
        fun readLinks(text: String): List<RunLine> {
            val lines = Json.parseToJsonElement(text).jsonObject.getValue("lines").jsonArray.map { element ->
                val line = element.jsonObject
                RunLine(
                    name = line.getValue("name").jsonPrimitive.content,
                    link = line.getValue("link").jsonPrimitive.content,
                    socksPort = line.getValue("socksPort").jsonPrimitive.int,
                )
            }
            require(lines.isNotEmpty()) { "the run names no line" }
            for (line in lines) {
                require(NAME.matches(line.name)) { "the line name '${line.name}' is no file name" }
                require(line.socksPort in 1..65535) { "${line.name}: no port ${line.socksPort}" }
            }
            require(lines.map { it.name }.toSet().size == lines.size) { "a line is named twice" }
            return lines
        }

        /** The routing modes the builder writes a line under, by the names the driver gives them. */
        val MODES: Map<String, Routing.Policy> = mapOf("blocked-only" to Routing.Policy.BlockedOnly)

        /**
         * The modes of the driver's links.json:
         * `{"modes": [{"mode", "line", "directDns"}]}`, none when it names none.
         * A mode the builder does not know is read, and refused in the
         * manifest, where the driver names it.
         */
        fun readModes(text: String, lines: List<RunLine>): List<RunMode> {
            val modes = Json.parseToJsonElement(text).jsonObject["modes"]?.jsonArray.orEmpty().map { element ->
                val mode = element.jsonObject
                RunMode(
                    mode = mode.getValue("mode").jsonPrimitive.content,
                    line = mode.getValue("line").jsonPrimitive.content,
                    directDns = mode.getValue("directDns").jsonPrimitive.content,
                )
            }
            val names = lines.map { it.name }.toSet()
            for (mode in modes) {
                require(NAME.matches(mode.mode)) { "the mode name '${mode.mode}' is no file name" }
                require(mode.line in names) { "the mode ${mode.mode} rides ${mode.line}, which is no line of the run" }
                require(mode.directDns.isNotBlank()) { "the mode ${mode.mode} names no resolver" }
            }
            require(modes.map { it.mode }.toSet().size == modes.size) { "a mode is named twice" }
            return modes
        }

        /**
         * Writes `<line>.core.json` into [out] for every line whose link the
         * builder takes, `<line>.<mode>.core.json` for every mode it writes
         * the link under, and `manifest.json`; returns the manifest.
         */
        fun dump(lines: List<RunLine>, login: SocksLogin, out: File, modes: List<RunMode> = emptyList()): JsonObject {
            out.mkdirs()
            val manifest = buildJsonObject {
                putJsonArray("shapes") { CoreLines.all.flatMap { it.shapes }.sorted().forEach { add(it) } }
                putJsonObject("lines") {
                    for (line in lines) {
                        putJsonObject(line.name) {
                            val spec = LinkParser.parse(line.link)
                            if (spec == null) {
                                put("refusal", "the app reads no link in it")
                                return@putJsonObject
                            }
                            CoreLines.of(spec)?.let { kind ->
                                put("kind", CoreRegistry.name(kind))
                                put("shape", kind.shape(spec))
                            }
                            val refusal = CoreRefusal.of(spec)
                            if (refusal != null) {
                                put("refusal", refusal)
                                return@putJsonObject
                            }
                            val config = runCatching { CoreConfig.build(spec, login, socksPort = line.socksPort) }
                            config.exceptionOrNull()?.let {
                                put("refusal", "the builder threw: ${it.message}")
                                return@putJsonObject
                            }
                            File(out, "${line.name}.core.json").writeText(config.getOrThrow())
                            put("config", "${line.name}.core.json")
                        }
                    }
                }
                putJsonObject("modes") {
                    for (mode in modes) {
                        putJsonObject(mode.mode) {
                            put("line", mode.line)
                            val line = lines.first { it.name == mode.line }
                            val policy = MODES[mode.mode]
                            if (policy == null) {
                                put("refusal", "the builder knows no mode ${mode.mode}")
                                return@putJsonObject
                            }
                            val spec = LinkParser.parse(line.link)
                            if (spec == null) {
                                put("refusal", "the app reads no link in it")
                                return@putJsonObject
                            }
                            val refusal = CoreRefusal.of(spec)
                            if (refusal != null) {
                                put("refusal", refusal)
                                return@putJsonObject
                            }
                            val config = runCatching { modeConfig(spec, login, policy, mode.directDns, line.socksPort) }
                            config.exceptionOrNull()?.let {
                                put("refusal", "the builder threw: ${it.message}")
                                return@putJsonObject
                            }
                            val name = "${mode.line}.${mode.mode}.core.json"
                            File(out, name).writeText(config.getOrThrow())
                            put("config", name)
                        }
                    }
                }
            }
            File(out, "manifest.json").writeText(manifest.toString())
            return manifest
        }

        /** A login of the run's own, as the app makes one for each session. */
        fun login(): SocksLogin {
            val random = SecureRandom()
            fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return SocksLogin("interop-${hex(3)}", hex(16))
        }

        private fun temp(): File = Files.createTempDirectory("interop-dumps").toFile().also { it.deleteOnExit() }

        /** The rule-set directory a [Routing.Rules] names, which the Rust core's writer never reads. */
        private const val NO_RULE_SET_DIR = "unused-on-the-rust-core"

        /**
         * The config the app writes for [spec] under the routing [policy], names
         * bound straight out asked of [directDns], with the host's word that the
         * list the mode names is in the core's geodata directory: the driver
         * copies it beside the client's config, and
         * `scripts/check-rust-core-configs.sh` hands the core a geodata
         * directory that holds it.
         */
        fun modeConfig(spec: OutboundSpec, login: SocksLogin, policy: Routing.Policy, directDns: String, socksPort: Int): String =
            CoreConfig.build(
                spec,
                login,
                routing = Routing.Rules(NO_RULE_SET_DIR, DirectDns.Servers(listOf(directDns)), policy),
                socksPort = socksPort,
                refilterDat = true,
            )

        /**
         * K12's reference of the blocked-only mode. In a directory of its own:
         * the references beside it are the kinds' cases' (CoreRegistry), and
         * this one is no case's.
         */
        val BLOCKED_ONLY_REFERENCE = File("src/jvmTest/resources/reference-configs/blocked-only/core-reality.json")

        /** Where the reference asks names bound straight out, as CoreBlockedOnlyTest's configs do. */
        const val REFERENCE_DNS = "10.20.30.40"
    }

    /**
     * K12's reference of the blocked-only mode is the builder's config, byte
     * for byte: the iPhone's Reality line under "only blocked sites", as
     * CoreBlockedOnlyTest reads it, written by [modeConfig] as a run's dump is.
     * `scripts/check-rust-core-configs.sh` hands it to the core with a geodata
     * directory that holds the list, and with one that does not, where the
     * core must refuse it. A writer change that moves it: copy
     * `build/rust-core-configs/blocked-only/core-reality.json` over it and run
     * the script.
     */
    @Test fun theBlockedOnlyReferenceIsTheBuildersConfig() {
        val json = modeConfig(
            IosCoreFixtures.spec(IosCoreLine.RealityVision), CoreFixtures.login, Routing.Policy.BlockedOnly,
            REFERENCE_DNS, XrayConfig.XRAY_SOCKS_PORT,
        )
        val written = File("build/rust-core-configs/blocked-only/core-reality.json")
        written.parentFile.mkdirs()
        written.writeText(json)
        assertEquals(emptyList<String>(), XrayRustSurface.violations(Json.parseToJsonElement(json).jsonObject))
        assertTrue(
            "\"ext:refilter.dat:domains\"" in json && "\"ext:refilter.dat:ips\"" in json,
            "the blocked-only config names no list: the reference would prove nothing of the geodata directory",
        )
        assertTrue(
            BLOCKED_ONLY_REFERENCE.isFile,
            "no ${BLOCKED_ONLY_REFERENCE.path}: copy it from ${written.path} and run scripts/check-rust-core-configs.sh",
        )
        val reference = BLOCKED_ONLY_REFERENCE.readBytes()
        if (!reference.contentEquals(json.encodeToByteArray())) {
            val at = reference.decodeToString().commonPrefixWith(json).length
            fail("${BLOCKED_ONLY_REFERENCE.path} is not the builder's config (they part at character $at); the builder wrote: $json")
        }
    }

    /** The run's own dumps: written when the driver has written its links, and then each line is written or refused. */
    @Test fun dumpsTheLinksOfTheInteropRun() {
        val links = File(RUN, "links.json")
        if (!links.isFile) {
            println("no ${links.path}: not an interop run, nothing to dump")
            return
        }
        val out = File(RUN, "dumps")
        assertTrue(!out.exists() || out.list().orEmpty().isEmpty(), "${out.path} is not empty: a run's dumps go into a directory of their own")
        val text = links.readText()
        val lines = readLinks(text)
        val modes = readModes(text, lines)
        val manifest = dump(lines, login(), out, modes)
        val named = manifest.getValue("lines").jsonObject
        assertEquals(lines.map { it.name }.toSet(), named.keys)
        for (line in lines) {
            val said = named.getValue(line.name).jsonObject
            println("${line.name}: " + (said["refusal"]?.jsonPrimitive?.content?.let { "refused, $it" } ?: "${said["shape"]?.jsonPrimitive?.content}, ${line.name}.core.json"))
        }
        val moded = manifest.getValue("modes").jsonObject
        assertEquals(modes.map { it.mode }.toSet(), moded.keys)
        for (mode in modes) {
            val said = moded.getValue(mode.mode).jsonObject
            println("${mode.line} under ${mode.mode}: " + (said["refusal"]?.jsonPrimitive?.content?.let { "refused, $it" } ?: said.getValue("config").jsonPrimitive.content))
        }
        val written = lines.filter { "refusal" !in named.getValue(it.name).jsonObject }.map { "${it.name}.core.json" } +
            moded.values.mapNotNull { it.jsonObject["config"]?.jsonPrimitive?.content }
        assertEquals((written + "manifest.json").sorted(), out.list().orEmpty().sorted())
    }

    /** A link the builder takes is written as the builder writes it, with the run's SOCKS port, under its kind and shape. */
    @Test fun everyFixtureCaseIsWrittenAsTheBuilderWritesIt() {
        val out = temp()
        val login = SocksLogin("interop-user", "interop-password")
        val lines = CoreFixtures.cases.mapIndexed { i, case -> RunLine(case.name, case.link, 21000 + i) }
        assertTrue(lines.isNotEmpty())
        val named = dump(lines, login, out).getValue("lines").jsonObject
        for ((line, case) in lines.zip(CoreFixtures.cases)) {
            val spec = case.spec()
            val kind = requireNotNull(CoreLines.of(spec))
            val said = named.getValue(line.name).jsonObject
            assertNull(said["refusal"], line.name)
            assertEquals(CoreRegistry.name(kind), said.getValue("kind").jsonPrimitive.content, line.name)
            assertEquals(kind.shape(spec), said.getValue("shape").jsonPrimitive.content, line.name)
            assertEquals(CoreConfig.build(spec, login, socksPort = line.socksPort), File(out, "${line.name}.core.json").readText(), line.name)
            assertTrue("\"port\":${line.socksPort}," in File(out, "${line.name}.core.json").readText(), "${line.name}: the run's SOCKS port")
        }
    }

    /** A link the builder refuses is named with the builder's own reason, and no config is written for it. */
    @Test fun aLinkTheBuilderRefusesIsNamedAndNotWritten() {
        val out = temp()
        // A server named by a hostname: the core would resolve it through the tunnel's own resolver.
        val byName = CoreFixtures.cases.first().link.replace("@127.0.0.1:", "@server.interop.test:")
        val spec = assertNotNull(LinkParser.parse(byName))
        val reason = assertNotNull(CoreRefusal.of(spec), "the link refused here is one the builder takes")
        val named = dump(
            listOf(RunLine("by-name", byName, 21000), RunLine("no-link", "https://interop.test/", 21001)),
            SocksLogin("interop-user", "interop-password"), out,
        ).getValue("lines").jsonObject
        assertEquals(reason, named.getValue("by-name").jsonObject.getValue("refusal").jsonPrimitive.content)
        assertEquals("the app reads no link in it", named.getValue("no-link").jsonObject.getValue("refusal").jsonPrimitive.content)
        assertEquals(listOf("manifest.json"), out.list().orEmpty().toList())
    }

    /** The manifest names every shape the builder writes, the iPhone's six among them: the driver fails one no line carries. */
    @Test fun theManifestNamesEveryShapeTheBuilderWrites() {
        val shapes = dump(listOf(RunLine("one", CoreFixtures.cases.first().link, 21000)), login(), temp())
            .getValue("shapes").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(CoreLines.all.flatMap { it.shapes }.sorted(), shapes)
        for (line in IosCoreLine.entries) assertTrue(line.slug in shapes, line.slug)
    }

    /**
     * A line under a mode is written as [modeConfig] writes it, with the line's
     * SOCKS port and the run's resolver; a mode the builder does not know, and
     * a link it refuses, are named in the manifest and not written.
     */
    @Test fun aLineUnderAModeIsWrittenAsTheBuilderWritesIt() {
        val out = temp()
        val login = SocksLogin("interop-user", "interop-password")
        val reality = IosCoreFixtures.link(IosCoreLine.RealityVision)
        val byName = reality.replace("@127.0.0.1:", "@server.interop.test:")
        val lines = listOf(RunLine("reality", reality, 21000), RunLine("by-name", byName, 21001))
        val modes = listOf(RunMode("blocked-only", "reality", "10.1.2.3"), RunMode("no-such-mode", "reality", "10.1.2.3"))
        val moded = dump(lines, login, out, modes).getValue("modes").jsonObject
        val said = moded.getValue("blocked-only").jsonObject
        assertEquals("reality", said.getValue("line").jsonPrimitive.content)
        assertEquals("reality.blocked-only.core.json", said.getValue("config").jsonPrimitive.content)
        val written = File(out, "reality.blocked-only.core.json").readText()
        val spec = assertNotNull(LinkParser.parse(reality))
        assertEquals(modeConfig(spec, login, Routing.Policy.BlockedOnly, "10.1.2.3", 21000), written)
        assertTrue("\"port\":21000," in written, "the line's SOCKS port")
        val dns = Json.parseToJsonElement(written).jsonObject.getValue("dns").jsonObject.getValue("servers").jsonArray
        assertEquals("10.1.2.3", dns.single().jsonObject.getValue("address").jsonPrimitive.content, "the run's resolver")
        assertTrue("\"ext:refilter.dat:domains\"" in written, "the list the driver puts beside it")
        assertEquals(
            "the builder knows no mode no-such-mode",
            moded.getValue("no-such-mode").jsonObject.getValue("refusal").jsonPrimitive.content,
        )
        assertEquals(
            listOf("manifest.json", "reality.blocked-only.core.json", "reality.core.json"),
            out.list().orEmpty().sorted(),
        )
        val refusedOut = temp()
        val refused = dump(lines, login, refusedOut, listOf(RunMode("blocked-only", "by-name", "10.1.2.3")))
            .getValue("modes").jsonObject.getValue("blocked-only").jsonObject
        assertEquals("by-name", refused.getValue("line").jsonPrimitive.content)
        assertEquals(CoreRefusal.of(assertNotNull(LinkParser.parse(byName))), refused.getValue("refusal").jsonPrimitive.content)
        assertNull(refused["config"])
        assertEquals(listOf("manifest.json", "reality.core.json"), refusedOut.list().orEmpty().sorted())
    }

    /** A run's modes are read as the driver writes them; none is none, and a mode on no line, or twice, is refused. */
    @Test fun theRunsModesAreReadAsTheDriverWritesThem() {
        val lines = listOf(RunLine("reality", "vless://a@127.0.0.1:20001?x=1", 20002))
        val text = """{"lines": [], "modes": [{"mode": "blocked-only", "line": "reality", "directDns": "10.1.2.3"}]}"""
        assertEquals(listOf(RunMode("blocked-only", "reality", "10.1.2.3")), readModes(text, lines))
        assertEquals(emptyList<RunMode>(), readModes("""{"lines": []}""", lines))
        assertFailsWith<IllegalArgumentException> { readModes(text.replace("\"line\": \"reality\"", "\"line\": \"other\""), lines) }
        assertFailsWith<IllegalArgumentException> { readModes(text.replace("\"blocked-only\"", "\"../blocked-only\""), lines) }
        assertFailsWith<IllegalArgumentException> { readModes(text.replace("10.1.2.3", " "), lines) }
        val twice = """{"modes": [{"mode": "m", "line": "reality", "directDns": "10.1.2.3"}, {"mode": "m", "line": "reality", "directDns": "10.1.2.4"}]}"""
        assertFailsWith<IllegalArgumentException> { readModes(twice, lines) }
        assertTrue("blocked-only" in MODES, "the driver's mode is one the builder writes")
    }

    /** The links file is read as the driver writes it, and a name that is no file name, or a name twice, is refused. */
    @Test fun theRunsLinksAreReadAsTheDriverWritesThem() {
        val text = """{"lines": [
             {"name": "reality", "link": "vless://a@127.0.0.1:20001?x=1", "socksPort": 20002},
             {"name": "hysteria2-wrong-pin", "link": "hysteria2://b@127.0.0.1:20003", "socksPort": 20004}
            ]}"""
        assertEquals(
            listOf(RunLine("reality", "vless://a@127.0.0.1:20001?x=1", 20002), RunLine("hysteria2-wrong-pin", "hysteria2://b@127.0.0.1:20003", 20004)),
            readLinks(text),
        )
        assertFailsWith<IllegalArgumentException> { readLinks(text.replace("\"reality\"", "\"../reality\"")) }
        assertFailsWith<IllegalArgumentException> { readLinks(text.replace("hysteria2-wrong-pin", "reality")) }
        assertFailsWith<IllegalArgumentException> { readLinks("""{"lines": []}""") }
        assertFalse(login().password == login().password, "every run's login is its own")
    }
}
