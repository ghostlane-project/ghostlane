package org.olcbox.app.net

import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.device.asciiHeader
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.ImportLink.payloadOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vectors the CLI's link, list and crypt1 port is held to (`cli/crates/
 * gl-links`, Rust, run by rust-cli.yml), held here to the app's own code.
 *
 * `links.json` writes every case of the CLI's Go link tests: VLESS over TCP
 * and XHTTP, Reality keys and fingerprints, Hysteria2, olcRTC rooms and their
 * refusals, import links, the two normalizers and the country from a label.
 * `crypt1.json` writes blobs made from a fixed key, both accepted and refused,
 * that this codec and the Rust port both open. `lists.json` writes the body,
 * header, entry, selection and refusal cases of the Go list tests, with the
 * three subscription fixtures the entries are counted from.
 *
 * A vector file that no longer matches the app's parsers fails here. A stale
 * file also fails here and in Rust: `cli/crates/gl-links/vectors.sha256` lists
 * every file the vectors are, with its digest. The CLI's tests do not run on
 * a Kotlin change, so both sides hold the list to the files.
 */
class LinkVectorsTest {
    private val dir = File("src/commonTest/resources/vectors")
    private val digests = File("../cli/crates/gl-links/vectors.sha256")

    private fun read(name: String): JsonObject {
        val file = File(dir, name)
        assertTrue(file.isFile, "no ${file.path}")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private val links by lazy { read("links.json") }
    private val crypt1 by lazy { read("crypt1.json") }
    private val lists by lazy { read("lists.json") }

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String = getValue(key).let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error("$key is not text in $this")
    }
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    @Test fun everyGoLinkCaseHasAVector() {
        val names = links.list("cases").map { it.str("name") }.toSet()
        val expected = setOf(
            "reality-tcp-full", "reality-tcp-defaults", "xhttp-packet-up", "xhttp-tls-cdn",
            "xhttp-defaults", "label-plus-is-space", "security-none-plain",
            "allow-insecure-ordinary-tls", "keys-ignore-security-options", "security-silent-defaults",
            "refuse-grpc", "refuse-ws", "refuse-httpupgrade", "refuse-http", "refuse-h2",
            "refuse-no-userinfo", "refuse-no-port", "refuse-no-host", "refuse-bad-port", "refuse-not-vless",
            "hy2-full", "hy2-insecure-no-obfs", "hy2-refuse-no-password", "hy2-refuse-not-hy2",
            "partner-telemost", "partner-wbstream", "partner-salutejazz", "partner-vkcalls",
            "partner-mtslink", "proofkit-label", "no-label", "client-segment",
            "vp8-options", "vp8-prefixed-options", "refuse-not-olcrtc", "refuse-crypt1",
            "refuse-no-transport", "refuse-no-room", "refuse-no-key", "refuse-short-key",
            "refuse-unknown-carrier", "refuse-unknown-transport",
            "query-url", "add-path", "import-path", "plain-url", "olcrtc-unchanged",
            "providers", "transports", "labels",
        )
        assertEquals(emptySet<String>(), expected - names, "a Go link case is missing from links.json")
        assertEquals(50, names.size, "the number of exported link cases")
    }

    @Test fun theAppsParserOpensEveryParsedShareLink() {
        for (vector in links.list("cases")) {
            val group = vector.str("group")
            if (group != "vless" && group != "hy2") continue
            if ("refusal" in vector.getValue("expect").jsonObject) continue
            assertNotNull(LinkParser.parse(vector.str("line")), vector.str("name"))
        }
    }

    @Test fun importPayloadsAreTheApps() {
        for (vector in links.list("cases").filter { it.str("group") == "import" }) {
            val line = vector.str("line")
            val want = vector.getValue("expect").jsonObject.str("payload")
            if (want == line && !want.startsWith("ghostlane://")) {
                assertNull(payloadOf(line), vector.str("name"))
            } else {
                assertEquals(want, payloadOf(line), vector.str("name"))
            }
        }
    }

    @Test fun normalizersAndCountriesAreReadable() {
        for (vector in links.list("cases").filter { it.str("group") == "normalizers" }) {
            val expected = vector.getValue("expect").jsonObject
            assertTrue(expected.list("cases").isNotEmpty(), vector.str("name"))
            if (expected.str("function") == "normalizeProvider") {
                assertEquals("telemost", LocationConfig.normalizeProvider("yandex_telemost"))
                assertEquals("wbstream", LocationConfig.normalizeProvider("wildberries"))
                assertEquals("mtslink", LocationConfig.normalizeProvider("MTSLink"))
            }
        }
        val countries = links.list("cases").single { it.str("group") == "country" }
            .getValue("expect").jsonObject.getValue("cases").jsonArray
        assertEquals(9, countries.size)
    }

    @Test fun crypt1BlobsAreReadableByTheAppsCodec() {
        val cases = crypt1.list("cases")
        assertEquals(14, cases.size)
        val master = CryptCodec.decodeMaster(cases.first().str("master"))
        assertNotNull(master)
        val codec = CryptCodec(master)
        assertEquals("https://proofkit.org/sub/tok/unified", codec.decryptBody(cases.first().str("blob")))
        val link = cases.first { it.str("name") == "link-payload" }.str("blob")
        assertTrue(CryptCodec.isCryptLink("olcrtc://crypt1/$link"))
        for (case in cases.filter { it.flag("accept").not() }) {
            assertNull(codec.decryptBody(case.str("blob")), case.str("name"))
        }
    }

    @Test fun everyFixtureEntryIsParsedByTheAppsLinkParser() {
        for (fixture in lists.list("fixtures")) {
            val path = File("../cli/internal/links/testdata/" + fixture.str("name"))
            assertTrue(path.isFile, "no ${path.path}")
            val lines = path.readLines().filter { it.isNotBlank() }
            assertEquals(fixture.list("entries").size, lines.size, fixture.str("name"))
            for ((index, entry) in fixture.list("entries").withIndex()) {
                assertEquals(entry.str("raw"), lines[index], "${fixture.str("name")} line $index")
                if (entry.str("kind") == "vless" || entry.str("kind") == "hysteria2") {
                    assertNotNull(LinkParser.parse(lines[index]), "${fixture.str("name")} line $index")
                }
            }
        }
    }

    @Test fun asciiHeaderCasesAreTheApps() {
        for (vector in lists.list("cases").filter { it.str("group") == "ascii" }) {
            val expect = vector.getValue("expect").jsonObject
            assertEquals(expect.str("want"), asciiHeader(expect.str("input")), vector.str("name"))
        }
    }

    @Test fun headerRefusalCasesAreTheAppsFlagRule() {
        for (vector in lists.list("cases").filter { it.str("group") == "headers" && "refusal" in it.getValue("expect").jsonObject }) {
            val headers = vector.getValue("headers").jsonObject
            val max = headers.entries.firstOrNull { it.key.equals("x-hwid-max-devices-reached", true) }
                ?.value?.toString()?.trim('"')?.trim()
            val notSupported = headers.entries.firstOrNull { it.key.equals("x-hwid-not-supported", true) }
                ?.value?.toString()?.trim('"')?.trim()
            val got = when {
                max?.equals("true", true) == true -> "limit"
                notSupported?.equals("true", true) == true -> "no-device"
                else -> ""
            }
            assertEquals(vector.getValue("expect").jsonObject.str("refusal"), got, vector.str("name"))
        }
    }

    /**
     * What `vectors.sha256` beside the port has to read: the three vector
     * files, by their path from the repository's root, in sha256sum's format,
     * sorted. Line ends are read as LF.
     */
    private fun listing(): String = listOf("links.json", "crypt1.json", "lists.json")
        .map { "src/commonTest/resources/vectors/$it" }
        .sorted()
        .joinToString("") { path ->
            val bytes = File(path).readText().replace("\r\n", "\n").encodeToByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "$digest  sharedUI/$path\n"
        }

    @Test fun thePortsListOfDigestsIsTheVectorsAsTheyAre() {
        assertTrue(digests.isFile, "no ${digests.path}")
        val wanted = listing()
        assertEquals(wanted, digests.readText().replace("\r\n", "\n"), "write ${digests.path} anew; it should read:\n$wanted")
    }
}
