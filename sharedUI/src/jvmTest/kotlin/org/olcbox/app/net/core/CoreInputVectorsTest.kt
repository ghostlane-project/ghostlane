package org.olcbox.app.net.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TlsSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.lines.Reality
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JVM-only: the vectors the CLI's port of this builder is held to
 * (`cli/crates/gl-coreconfig`, Rust, run by rust-cli.yml), held here to the
 * builder itself.
 *
 * `vectors/core-inputs.json` beside the common tests holds, for every case
 * of every kind under `net/core/lines` ([CoreFixtures]), the link, the spec
 * [org.olcbox.app.net.LinkParser] reads from it as JSON (every field, null
 * where it is null), and the reference config this builder writes for it
 * under Global with the fixture login on the default port; then listening
 * plans (each config in `vectors/core-plans/<name>.json`), refusals of lines
 * and of plans, pins of a server named by a hostname, hello names, and what
 * a build refuses in the words of its exception. A kind of line that came
 * with no vector, or a vector this builder no longer writes, fails here.
 *
 * The CLI's tests run when cli/ changes, and these files are not there. So
 * `cli/crates/gl-coreconfig/vectors.sha256` lists every file the vectors are
 * with its SHA-256, and both sides hold it to them: a change to the builder
 * that moves a vector fails here until the list beside the port is written
 * again, and writing it runs the port's tests.
 */
class CoreInputVectorsTest {
    private val vectorsFile = File("src/commonTest/resources/vectors/core-inputs.json")
    private val plansDir = File("src/commonTest/resources/vectors/core-plans")
    private val references = File("src/jvmTest/resources/reference-configs")
    private val digests = File("../cli/crates/gl-coreconfig/vectors.sha256")

    private val vectors: JsonObject by lazy {
        assertTrue(vectorsFile.isFile, "no ${vectorsFile.path}: the test does not run where it thinks")
        Json.parseToJsonElement(vectorsFile.readText()).jsonObject
    }

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String =
        getValue(key).jsonPrimitive.also { require(it.isString) { "$key is not text in $this" } }.content
    private fun JsonObject.textOrNull(key: String): String? = getValue(key).let { if (it is JsonNull) null else text(key) }
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    // A spec as the vectors write it, and back.

    private fun encode(t: TransportSpec): JsonObject = buildJsonObject {
        when (t) {
            TransportSpec.Tcp -> put("type", "tcp")
            is TransportSpec.Grpc -> { put("type", "grpc"); put("serviceName", t.serviceName) }
            is TransportSpec.Xhttp -> { put("type", "xhttp"); put("path", t.path); put("host", t.host); put("mode", t.mode) }
            is TransportSpec.Ws -> { put("type", "ws"); put("path", t.path); put("host", t.host) }
            is TransportSpec.HttpUpgrade -> { put("type", "httpupgrade"); put("path", t.path); put("host", t.host) }
            is TransportSpec.Http -> { put("type", "http"); put("path", t.path); putJsonArray("hosts") { t.hosts.forEach { add(it) } } }
        }
    }

    private fun encode(tls: TlsSpec): JsonObject = buildJsonObject {
        put("sni", tls.sni); put("insecure", tls.insecure); put("fingerprint", tls.fingerprint)
        putJsonArray("alpn") { tls.alpn.forEach { add(it) } }
    }

    private fun encode(spec: OutboundSpec): JsonObject = buildJsonObject {
        when (spec) {
            is OutboundSpec.Vless -> {
                put("kind", "vless"); put("uuid", spec.uuid); put("host", spec.host); put("port", spec.port)
                put("sni", spec.sni); put("publicKey", spec.publicKey); put("shortId", spec.shortId)
                put("fingerprint", spec.fingerprint); put("flow", spec.flow); put("transport", encode(spec.transport))
                put("tag", spec.tag); put("plain", spec.plain); put("insecure", spec.insecure)
                putJsonArray("alpn") { spec.alpn.forEach { add(it) } }
            }
            is OutboundSpec.Hysteria2 -> {
                put("kind", "hysteria2"); put("password", spec.password); put("host", spec.host); put("port", spec.port)
                put("sni", spec.sni); put("obfsPassword", spec.obfsPassword); put("insecure", spec.insecure)
                put("tag", spec.tag); put("certPinSha256", spec.certPinSha256)
            }
            is OutboundSpec.Trojan -> {
                put("kind", "trojan"); put("password", spec.password); put("host", spec.host); put("port", spec.port)
                put("tls", encode(spec.tls)); put("transport", encode(spec.transport)); put("tag", spec.tag)
            }
            is OutboundSpec.Shadowsocks -> {
                put("kind", "shadowsocks"); put("method", spec.method); put("password", spec.password)
                put("host", spec.host); put("port", spec.port); put("tag", spec.tag)
            }
            is OutboundSpec.Vmess -> {
                put("kind", "vmess"); put("uuid", spec.uuid); put("host", spec.host); put("port", spec.port)
                put("alterId", spec.alterId); put("security", spec.security)
                put("tls", spec.tls?.let { encode(it) } ?: JsonNull)
                put("transport", encode(spec.transport)); put("tag", spec.tag)
            }
        }
    }

    private fun transport(o: JsonObject): TransportSpec = when (val type = o.text("type")) {
        "tcp" -> TransportSpec.Tcp
        "grpc" -> TransportSpec.Grpc(o.text("serviceName"))
        "xhttp" -> TransportSpec.Xhttp(o.text("path"), o.text("host"), o.text("mode"))
        "ws" -> TransportSpec.Ws(o.text("path"), o.text("host"))
        "httpupgrade" -> TransportSpec.HttpUpgrade(o.text("path"), o.text("host"))
        "http" -> TransportSpec.Http(o.text("path"), o.texts("hosts"))
        else -> error("no transport $type")
    }

    private fun tls(o: JsonObject): TlsSpec = TlsSpec(o.text("sni"), o.flag("insecure"), o.textOrNull("fingerprint"), o.texts("alpn"))

    private fun decode(o: JsonObject): OutboundSpec = when (val kind = o.text("kind")) {
        "vless" -> OutboundSpec.Vless(
            uuid = o.text("uuid"), host = o.text("host"), port = o.number("port"), sni = o.text("sni"),
            publicKey = o.text("publicKey"), shortId = o.text("shortId"), fingerprint = o.text("fingerprint"),
            flow = o.textOrNull("flow"), transport = transport(o.getValue("transport").jsonObject), tag = o.text("tag"),
            plain = o.flag("plain"), insecure = o.flag("insecure"), alpn = o.texts("alpn"),
        )
        "hysteria2" -> OutboundSpec.Hysteria2(
            password = o.text("password"), host = o.text("host"), port = o.number("port"), sni = o.text("sni"),
            obfsPassword = o.textOrNull("obfsPassword"), insecure = o.flag("insecure"), tag = o.text("tag"),
            certPinSha256 = o.textOrNull("certPinSha256"),
        )
        "trojan" -> OutboundSpec.Trojan(
            password = o.text("password"), host = o.text("host"), port = o.number("port"),
            tls = tls(o.getValue("tls").jsonObject), transport = transport(o.getValue("transport").jsonObject), tag = o.text("tag"),
        )
        "shadowsocks" -> OutboundSpec.Shadowsocks(
            method = o.text("method"), password = o.text("password"), host = o.text("host"), port = o.number("port"), tag = o.text("tag"),
        )
        "vmess" -> OutboundSpec.Vmess(
            uuid = o.text("uuid"), host = o.text("host"), port = o.number("port"), alterId = o.number("alterId"),
            security = o.text("security"), tls = o.getValue("tls").let { if (it is JsonNull) null else tls(it.jsonObject) },
            transport = transport(o.getValue("transport").jsonObject), tag = o.text("tag"),
        )
        else -> error("no kind $kind")
    }

    /** The vector's spec, which must be whole: written back, it is the vector's JSON (no field missing, none more). */
    private fun spec(o: JsonElement): OutboundSpec {
        val spec = decode(o.jsonObject)
        assertEquals(o, encode(spec), "a vector's spec is not what the vectors write for it")
        return spec
    }

    private fun login(o: JsonElement): SocksLogin = o.jsonObject.let { SocksLogin(it.text("username"), it.text("password")) }

    private fun plan(o: JsonObject): CoreInbound.Plan {
        val own = o.getValue("own").jsonObject
        val ownPlan = when (val kind = own.text("kind")) {
            "tunnel" -> CoreInbound.Tunnel(own.number("port"), login(own.getValue("login")))
            "proxy" -> CoreInbound.Proxy(
                own.number("port"),
                own.getValue("login").let { if (it is JsonNull) null else login(it) },
                own.text("listen"),
            )
            else -> error("no inbound $kind")
        }
        val lan = o.getValue("lan").let {
            if (it is JsonNull) null else it.jsonObject.let { l -> CoreInbound.Lan(l.text("address"), l.number("port"), login(l.getValue("login"))) }
        }
        return CoreInbound.Plan(ownPlan, lan)
    }

    // The registry against the vectors.

    /**
     * What [vectors] lack for [lines] and [fixtures], or hold that is no
     * case's: each kind with its shapes in the order of [lines], a case
     * vector for each shape and for each fixture case, as this builder and
     * the link parser have it now.
     */
    private fun problems(
        vectors: JsonObject,
        lines: List<CoreLine> = CoreLines.all,
        fixtures: List<CoreLineFixture> = CoreFixtures.all,
    ): List<String> {
        val found = mutableListOf<String>()
        val kinds = vectors.list("kinds").map { it.text("name") to it.texts("shapes") }
        for (line in lines) {
            val name = CoreRegistry.name(line)
            when (val entry = kinds.firstOrNull { it.first == name }) {
                null -> found += "the kind $name has no entry in the vectors' kinds"
                else -> if (entry.second != line.shapes) found += "the vectors give $name the shapes ${entry.second}, and it writes ${line.shapes}"
            }
        }
        for ((name, _) in kinds) {
            if (lines.none { CoreRegistry.name(it) == name }) found += "the vectors name a kind $name that is not under lines/"
        }
        if (found.isEmpty() && kinds.map { it.first } != lines.map { CoreRegistry.name(it) }) {
            found += "the vectors' kinds are not in the order of lines/: ${kinds.map { it.first }}"
        }

        val cases = vectors.list("cases")
        for (line in lines) {
            val name = CoreRegistry.name(line)
            for (shape in line.shapes) {
                if (cases.none { it.text("kind") == name && it.text("shape") == shape }) {
                    found += "$name writes the shape $shape and no case vector is of it"
                }
            }
        }
        for (fixture in fixtures) {
            val kind = CoreRegistry.name(fixture.line)
            for (case in fixture.cases) {
                val vector = cases.firstOrNull { it.text("name") == case.name }
                if (vector == null) {
                    found += "the case ${case.name} of $kind has no vector"
                    continue
                }
                val parsed = case.spec()
                val wanted = buildJsonObject {
                    put("name", case.name); put("kind", kind); put("shape", fixture.line.shape(parsed)); put("link", case.link)
                    put("spec", encode(parsed)); put("reference", case.reference); put("helloNote", Reality.helloNote(parsed))
                }
                if (vector != wanted) found += "the vector of the case ${case.name} of $kind is stale: it should read $wanted"
            }
        }
        val named = fixtures.flatMap { f -> f.cases.map { it.name } }.toSet()
        for (vector in cases) {
            if (vector.text("name") !in named) found += "the case vector ${vector.text("name")} is no fixture case's"
        }
        return found
    }

    @Test fun everyRegisteredKindHasItsVectors() = assertEquals(emptyList<String>(), problems(vectors))

    /** The rule above, on vectors that lack a kind, a case, or a shape's every case. */
    @Test fun aKindWithNoVectorIsNamed() {
        fun without(drop: (JsonObject) -> Boolean, dropKind: String? = null): JsonObject = JsonObject(
            vectors + mapOf(
                "cases" to JsonArray(vectors.list("cases").filterNot(drop)),
                "kinds" to JsonArray(vectors.list("kinds").filterNot { it.text("name") == dropKind }),
            )
        )
        val noVmess = problems(without({ it.text("kind") == "Vmess" }, dropKind = "Vmess"))
        assertTrue(noVmess.any { it == "the kind Vmess has no entry in the vectors' kinds" }, "$noVmess")
        for (shape in CoreLines.all.single { CoreRegistry.name(it) == "Vmess" }.shapes) {
            assertTrue(noVmess.any { it == "Vmess writes the shape $shape and no case vector is of it" }, "$shape: $noVmess")
        }
        val noCase = problems(without({ it.text("name") == "trojan-insecure" }))
        assertEquals(listOf("the case trojan-insecure of Trojan has no vector"), noCase)
        val noShape = problems(without({ it.text("shape") == "xhttp" }))
        assertTrue(noShape.contains("Reality writes the shape xhttp and no case vector is of it"), "$noShape")
        val nobodys = buildJsonObject { put("name", "nobodys"); put("kind", "Trojan"); put("shape", "trojan") }
        val stray = problems(JsonObject(vectors + ("cases" to JsonArray(vectors.list("cases") + nobodys))))
        assertEquals(listOf("the case vector nobodys is no fixture case's"), stray)
    }

    @Test fun everyCaseIsItsReferenceConfigByteForByte() {
        val login = login(vectors.getValue("login"))
        val port = vectors.number("socksPort")
        assertEquals(CoreFixtures.login, login, "the vectors' login is the fixtures'")
        val failures = vectors.list("cases").mapNotNull { case ->
            runCatching {
                val spec = spec(case.getValue("spec"))
                val reference = File(references, "${case.text("reference")}.json").readText()
                assertEquals(reference, CoreConfig.build(spec, login, socksPort = port), "the builder's config")
            }.exceptionOrNull()?.let { "${case.text("name")}: ${it.message?.take(4000)}" }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyPlanIsItsConfigByteForByte() {
        val cases = vectors.list("cases")
        val plans = vectors.list("plans")
        val failures = plans.mapNotNull { vector ->
            runCatching {
                val spec = spec(cases.single { it.text("name") == vector.text("case") }.getValue("spec"))
                val plan = plan(vector.getValue("plan").jsonObject)
                val verbose = vector.flag("verboseLogs")
                val expected = File(plansDir, "${vector.text("name")}.json").readText()
                assertEquals(expected, CoreInbound.build(spec, plan, verboseLogs = verbose), "CoreInbound.build")
                val own = plan.own
                if (own is CoreInbound.Tunnel && plan.lan == null) {
                    assertEquals(expected, CoreConfig.build(spec, own.login, verboseLogs = verbose, socksPort = own.port), "CoreConfig.build")
                }
            }.exceptionOrNull()?.let { "${vector.text("name")}: ${it.message?.take(4000)}" }
        }
        assertEquals(emptyList<String>(), failures)
        val files = plansDir.list().orEmpty().toSet()
        assertEquals(plans.map { "${it.text("name")}.json" }.toSet(), files, "every file in core-plans is a plan's")
    }

    @Test fun everyPlanRefusalIsTheBuilders() {
        val failures = vectors.list("planRefusals").mapNotNull { vector ->
            val got = CoreInbound.refusal(plan(vector.getValue("plan").jsonObject))
            if (got == vector.textOrNull("refusal")) null else "${vector.text("name")}: the builder says $got"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyRefusalIsTheBuilders() {
        val failures = vectors.list("refusals").mapNotNull { vector ->
            val got = CoreRefusal.of(spec(vector.getValue("spec")))
            if (got == vector.textOrNull("refusal")) null else "${vector.text("name")}: the builder says $got"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyPinIsTheBuilders() {
        val failures = vectors.list("pins").mapNotNull { vector ->
            val got = CoreLines.pinHost(spec(vector.getValue("spec")), vector.text("address"))
            if (got == spec(vector.getValue("pinned"))) null else "${vector.text("name")}: the builder pins it as ${encode(got)}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyHelloIsNamedAsTheBuilderNamesIt() {
        val failures = vectors.list("hellos").mapNotNull { vector ->
            val fp = vector.textOrNull("fp")
            val (name, reality) = CoreSupport.helloName(fp) to Reality.hello(fp)
            if (name == vector.text("name") && reality == vector.text("reality")) null else "$fp: the builder names it $name, and $reality under Reality"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun everyBuildErrorIsTheBuilders() {
        val failures = vectors.list("buildErrors").mapNotNull { vector ->
            val spec = spec(vector.getValue("spec"))
            val thrown = assertFailsWith<IllegalArgumentException>(vector.text("name")) {
                val planned = vector.getValue("plan")
                if (planned is JsonNull) {
                    CoreConfig.build(spec, login(vector.getValue("login")), socksPort = vector.number("socksPort"))
                } else {
                    CoreInbound.build(spec, plan(planned.jsonObject))
                }
            }
            if (thrown.message == vector.text("message")) null else "${vector.text("name")}: the builder says ${thrown.message}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * What `vectors.sha256` beside the port has to read: every file the
     * vectors are, by its path from the repository's root, in sha256sum's
     * format, sorted. Line ends are read as LF, so that a checkout that
     * writes CRLF lists the same digests.
     */
    private fun listing(): String {
        val inSharedUi = listOf("src/commonTest/resources/vectors/core-inputs.json") +
            vectors.list("plans").map { "src/commonTest/resources/vectors/core-plans/${it.text("name")}.json" } +
            vectors.list("cases").map { "src/jvmTest/resources/reference-configs/${it.text("reference")}.json" }
        return inSharedUi.toSortedSet().joinToString("") { path ->
            val bytes = File(path).readText().replace("\r\n", "\n").encodeToByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "$digest  sharedUI/$path\n"
        }
    }

    @Test fun thePortsListOfDigestsIsTheVectorsAsTheyAre() {
        assertTrue(digests.isFile, "no ${digests.path}: the CLI's port lists the vectors' digests there")
        val wanted = listing()
        assertEquals(wanted, digests.readText().replace("\r\n", "\n"), "write ${digests.path} anew; it should read:\n$wanted")
    }

    /** The JSON helpers above read what they are given, and nothing is taken for a missing field. */
    @Test fun aSpecWithAFieldMissingOrMoreIsNotRead() {
        val whole = encode(CoreFixtures.case("trojan").spec())
        assertEquals(whole, encode(decode(whole)))
        assertFailsWith<NoSuchElementException> { decode(JsonObject(whole - "tag")) }
        assertFailsWith<AssertionError> { spec(JsonObject(whole + ("extra" to JsonPrimitive(1)))) }
    }
}
