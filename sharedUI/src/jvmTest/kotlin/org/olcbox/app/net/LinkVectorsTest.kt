package org.olcbox.app.net

import org.olcbox.app.crypt.CryptCodec
import org.olcbox.app.data.device.asciiHeader
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.ImportLink.payloadOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.text.encodeToByteArray
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    private fun JsonObject.text(key: String): String =
        getValue(key).jsonPrimitive.also { require(it.isString) { "$key is not text in $this" } }.content
    private fun JsonObject.textOrNull(key: String): String? = getValue(key).let { if (it is JsonNull) null else text(key) }
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    // Every link case is named, and every case the app parses carries one of
    // the fields below. A case that was dropped from the file is not a case
    // the port can skip: the file is held to the names of the Go tests here.
    @Test fun everyGoLinkCaseHasAVector() {
        val names = links.list("cases").map { it.text("name") }.toSet()
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

    // The parse results are held to the app's own LinkParser wherever the app
    // agrees with the CLI: VLESS over TCP and XHTTP, Hysteria2, labels and
    // fingerprints. The CLI's refusal of transports the app carries (grpc, ws)
    // is a deliberate difference, recorded as a refusal in the vector.
    @Test fun parsedLinksAreTheAppsParser() {
        val failures = mutableListOf<String>()
        for (vector in links.list("cases")) {
            val group = vector.text("group")
            if (group != "vless" && group != "hy2") continue
            val want = vector.obj("expect")
            if ("refusal" in want) continue
            val line = vector.text("line")
            val spec = LinkParser.parse(line)
            if (spec == null) {
                failures += "${vector.text("name")}: the app does not parse this link"
                continue
            }
            val actual = when (spec) {
                is OutboundSpec.Vless -> buildString {
                    append(spec.uuid, '|', spec.host, '|', spec.port, '|', spec.sni, '|', spec.publicKey, '|')
                    append(spec.shortId, '|', spec.fingerprint, '|', (spec.flow ?: ""), '|')
                    append(when (val t = spec.transport) {
                        is TransportSpec.Xhttp -> "xhttp:${t.path}:${t.host}:${t.mode}"
                        is TransportSpec.Tcp -> "tcp"
                        else -> spec.transport.javaClass.simpleName
                    }, '|', spec.tag, '|', spec.plain, '|', spec.insecure)
                }
                is OutboundSpec.Hysteria2 -> buildString {
                    append(spec.password, '|', spec.host, '|', spec.port, '|', spec.sni, '|')
                    append(spec.obfsPassword ?: "", '|', spec.certPinSha256 ?: "", '|', spec.insecure, '|', spec.tag)
                }
                else -> error("not a share link")
            }
            val expected = if (group == "vless") buildString {
                append(want.text("uuid"), '|', want.text("host"), '|', want.text("port"), '|', want.text("sni"), '|')
                append(want.text("publicKey"), '|', want.text("shortID"), '|', want.text("fingerprint"), '|')
                append(want.text("flow"), '|')
                val t = want.obj("transport")
                append(if (t.text("kind") == "xhttp") "xhttp:${t.text("path")}:${t.text("host")}:${t.text("mode")}" else "tcp")
                append('|', want.text("label"), '|', want.flag("plain"), '|', want.flag("insecure"))
            } else buildString {
                append(want.text("password"), '|', want.text("host"), '|', want.text("port"), '|', want.text("sni"), '|')
                append(want.text("obfsPassword"), '|', want.text("pinSHA256"), '|', want.flag("insecure"), '|', want.text("label"))
            }
            if (actual != expected) failures += "${vector.text("name")}: the app parses $actual, the vector says $expected"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun importPayloadsAreTheApps() {
        for (vector in links.list("cases").filter { it.text("group") == "import" }) {
            assertEquals(vector.obj("expect").text("payload"), payloadOf(vector.text("line")), vector.text("name"))
        }
    }

    @Test fun normalizersAndCountriesAreTheApps() {
        for (vector in links.list("cases").filter { it.text("group") == "normalizers" }) {
            val expected = vector.obj("expect")
            for (pair in expected.list("cases")) {
                val input = pair.jsonArray[0].jsonPrimitive.content
                val output = (pair.jsonArray[1] as? JsonPrimitive)?.content
                if (expected.text("function") == "normalizeProvider") {
                    if (output != null) assertEquals(output, LocationConfig.normalizeProvider(input), input)
                    else assertEquals(LocationConfig.normalizeProvider("zoom"), LocationConfig.normalizeProvider(input))
                }
            }
        }
        val countries = links.list("cases").single { it.text("group") == "country" }.obj("expect").list("cases")
        assertEquals(9, countries.size)
    }

    @Test fun everyCrypt1CaseIsTheAppsCodec() {
        val failures = crypt1.list("cases").mapNotNull { vector ->
            runCatching {
                val master = CryptCodec.decodeMaster(vector.text("master"))
                assertNotNull(master, "master key")
                val codec = CryptCodec(master)
                val got = if (vector.text("group") == "link") {
                    val link = vector.text("line")
                    assertTrue(CryptCodec.isCryptLink(link))
                    codec.decryptLink(link)
                } else {
                    codec.decryptBody(vector.text("blob"))
                }
                if (vector.flag("accept")) {
                    if (got != vector.text("plain")) "expected ${vector.text("plain")}, got $got" else null
                } else if (got != null) "a refused blob decoded to $got" else null
            }.exceptionOrNull()?.message?.let { "${vector.text("name")}: $it" }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyFixtureEntryIsParsedByTheAppsLinkParser() {
        for (fixture in lists.list("fixtures")) {
            val path = File("../../cli/internal/links/testdata/" + fixture.text("name"))
            assertTrue(path.isFile, "no ${path.path}")
            val lines = path.readLines().filter { it.isNotBlank() }
            assertEquals(fixture.list("entries").size, lines.size, fixture.text("name"))
            for ((index, entry) in fixture.list("entries").withIndex()) {
                assertEquals(entry.text("raw"), lines[index], "${fixture.text("name")} line $index")
                val kind = entry.text("kind")
                if (kind == "vless" || kind == "hysteria2") {
                    assertNotNull(LinkParser.parse(lines[index]), "${fixture.text("name")} line $index")
                }
            }
        }
    }

    @Test fun asciiHeaderCasesAreTheApps() {
        for (vector in lists.list("cases").filter { it.text("group") == "ascii" }) {
            val expect = vector.obj("expect")
            assertEquals(expect.text("want"), asciiHeader(expect.text("input")), vector.text("name"))
        }
    }

    @Test fun headerRefusalCasesAreTheAppsFlagRule() {
        for (vector in lists.list("cases").filter { it.text("group") == "headers" && "refusal" in it.obj("expect") }) {
            val headers = vector.obj("headers")
            val max = headers.entries.firstOrNull { it.key.equals("x-hwid-max-devices-reached", true) }
                ?.value?.jsonPrimitive?.content?.trim()
            val notSupported = headers.entries.firstOrNull { it.key.equals("x-hwid-not-supported", true) }
                ?.value?.jsonPrimitive?.content?.trim()
            val got = when {
                max?.equals("true", true) == true -> "limit"
                notSupported?.equals("true", true) == true -> "no-device"
                else -> ""
            }
            assertEquals(vector.obj("expect").text("refusal"), got, vector.text("name"))
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
