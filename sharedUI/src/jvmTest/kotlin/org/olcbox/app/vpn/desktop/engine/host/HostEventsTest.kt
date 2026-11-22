package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The events ghostlane-engine prints, read as the engine's own contract says
 * them: its `tests/fixtures/events.jsonl`, a line of every kind with every
 * word, of which the app keeps a copy beside these tests with its sha256
 * (`events.jsonl.sha256`, as sha256sum writes it). The pin move's step
 * `scripts/pin-move.d/events-hash` rewrites both from the new commit of the
 * engine, and rust-desktop.yml checks that the fixture at the pin is the copy
 * byte for byte. So the parser is held to the engine's contract both ways:
 * every line of it is read as its kind, and every kind and word the parser
 * knows is in it.
 */
class HostEventsTest {
    private val copy = File("src/jvmTest/resources/ghostlane-engine/events.jsonl")
    private val record = File("src/jvmTest/resources/ghostlane-engine/events.jsonl.sha256")
    private val lines: List<String> by lazy { copy.readLines().filter { it.isNotBlank() } }
    private val objects: List<JsonObject> by lazy { lines.map { Json.parseToJsonElement(it).jsonObject } }

    @Test
    fun theCopyIsTheFixtureWhoseHashIsPinned() {
        assertTrue(copy.isFile, "no copy of the engine's events fixture at $copy")
        val entries = record.readLines().filter { it.isNotBlank() }
        assertEquals(1, entries.size, "$record holds ${entries.size} lines, not one")
        val (pinned, name) = entries.single().split(Regex("\\s+\\*?"), limit = 2).let { it[0] to it.getOrNull(1) }
        assertTrue(HEX64.matches(pinned), "$record does not start with a sha256: ${entries.single()}")
        assertEquals(copy.name, name, "$record names another file")
        val digest = MessageDigest.getInstance("SHA-256").digest(copy.readBytes())
        val actual = digest.joinToString("") { "%02x".format(it) }
        assertEquals(
            pinned, actual,
            "the copy is not the fixture whose hash is pinned: rewrite both with scripts/pin-move.d/events-hash <commit>"
        )
    }

    @Test
    fun everyLineOfTheFixtureIsReadAsItsKind() {
        assertTrue(lines.isNotEmpty(), "the fixture copy is empty")
        for ((line, obj) in lines.zip(objects)) {
            val kind = obj.getValue("event").jsonPrimitive.content
            val event = HostEvents.parse(line)
            assertFalse(event is HostEvent.Unreadable, "not read: $line (${(event as? HostEvent.Unreadable)?.why})")
            assertFalse(event is HostEvent.Unknown, "a kind the parser does not know: $kind")
            assertEquals(kind, kindOf(event), "read as another kind: $line")
        }
    }

    @Test
    fun everyKindTheParserReadsIsInTheFixture() {
        val inFixture = objects.map { it.getValue("event").jsonPrimitive.content }.toSet()
        assertEquals(HostEvents.KINDS.toSet(), inFixture, "the parser's kinds and the fixture's differ")
        assertEquals(HostEvents.KINDS.size, HostEvents.KINDS.toSet().size, "a kind is listed twice")
    }

    /** Each closed set of words, as the fixture spells them in the field that holds them, at any depth. */
    @Test
    fun everyWordTheParserKnowsIsInTheFixture() {
        for ((field, words) in HostEvents.WORDS) {
            val inFixture = objects.flatMap { wordsOf(it, field) }.toSet()
            assertEquals(words.toSet(), inFixture, "the words of `$field` the parser knows and the fixture's differ")
        }
    }

    @Test
    fun theFieldsAreReadAsTheyArePrinted() {
        val version = assertIs<HostEvent.Version>(HostEvents.parse(lines.first()))
        assertEquals("0123456789abcdef0123456789abcdef01234567", version.pin)
        assertEquals(1, version.protocol)
        assertTrue(version.boringssl && version.rooms)
        assertEquals("x86_64-unknown-linux-musl", version.target)

        val ready = events<HostEvent.Ready>().first { it.inbounds.size == 2 }
        assertEquals(listOf("socks-in", "http-in"), ready.inbounds.map { it.tag })
        assertEquals("127.0.0.1:41080", ready.inbounds.first().address)
        assertNull(events<HostEvent.Ready>().flatMap { it.inbounds }.first { it.address.startsWith("[") }.tag)

        val refused = events<HostEvent.CheckConfig>().first { !it.ok }
        assertEquals(listOf(HostSeverity.Error, HostSeverity.Error), refused.diagnostics.map { it.severity })
        assertEquals(listOf(HostSource.Core, HostSource.Room), refused.diagnostics.map { it.source })
        assertNull(refused.diagnostics.last().path)

        val access = events<HostEvent.Log>().first { it.level == HostLevel.Access }
        assertEquals(HostSource.Core, access.source)

        val timeout = events<HostEvent.Answer>().first { it.question == HostQuestion.Probe && !it.ok }
        assertNull(timeout.millis)
        assertEquals("timeout", timeout.error)
        assertEquals(184L, events<HostEvent.Answer>().first { it.question == HostQuestion.Probe && it.ok }.millis)

        val closed = events<HostEvent.Ended>().first { it.reason == HostEndReason.StdinClosed }
        assertNull(closed.code)
        assertNull(closed.message)
        val bind = events<HostEvent.Ended>().first { it.code == HostFailure.Bind }
        assertEquals(HostEndReason.Failed, bind.reason)
        assertEquals("interface `eth9` does not exist", bind.message)

        assertEquals("eth0", events<HostEvent.Bound>().single().interfaceName)
        assertEquals(HostFailure.Usage, events<HostEvent.Failed>().first().code)
    }

    @Test
    fun aLineThatIsNotTheContractIsNotReadAsOne() {
        val unreadable = listOf(
            "",
            "not json",
            "[1, 2]",
            """{"kind":"ready"}""",
            // A field missing, though its value would be null.
            """{"event":"ended","reason":"signal","code":null}""",
            // A word the contract does not have.
            """{"event":"log","source":"core","level":"loud","line":"x"}""",
            """{"event":"ended","reason":"failed","code":"no-such-code","message":"x"}""",
            // A value of another type.
            """{"event":"version","engine":"0.10.0","protocol":"1","pin":"unknown","boringssl":false,"rooms":false,"target":"t"}""",
            """{"event":"probe","ok":"yes","millis":null,"error":null}""",
            """{"event":"probe","ok":true,"millis":1.5,"error":null}""",
            """{"event":"bound","interface":null}""",
            """{"event":"ready","inbounds":[{"tag":"in"}]}""",
        )
        for (line in unreadable) {
            assertIs<HostEvent.Unreadable>(HostEvents.parse(line), "read as the contract's: $line")
        }
        val later = HostEvents.parse("""{"event":"a_later_kind","what":1}""")
        assertIs<HostEvent.Unknown>(later)
        assertEquals("a_later_kind", later.kind)
    }

    /** What goes into the app's log names no room: a room's address is a secret of the session. */
    @Test
    fun theLogSaysNoRoomAddress() {
        val opened = events<HostEvent.SessionOpened>().single()
        val said = HostEvents.logLine(opened).orEmpty()
        assertFalse(opened.room in said || opened.sessionId in said, "the log line names the room: $said")
        for (event in lines.map { HostEvents.parse(it) }) {
            val text = HostEvents.logLine(event) ?: continue
            assertFalse("rooms.example" in text, "a log line names a room: $text")
        }
    }

    private inline fun <reified E : HostEvent> events(): List<E> = lines.map { HostEvents.parse(it) }.filterIsInstance<E>()

    private fun kindOf(event: HostEvent): String = when (event) {
        is HostEvent.Version -> "version"
        is HostEvent.CheckConfig -> "check_config"
        is HostEvent.Bound -> "bound"
        is HostEvent.Warning -> "warning"
        is HostEvent.Ready -> "ready"
        is HostEvent.SessionOpened -> "session_opened"
        is HostEvent.Log -> "log"
        is HostEvent.Answer -> event.question.word
        is HostEvent.Failed -> "error"
        is HostEvent.Ended -> "ended"
        is HostEvent.Unknown -> event.kind
        is HostEvent.Unreadable -> "(unreadable)"
    }

    private fun wordsOf(element: JsonElement, field: String): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) ->
            val here = if (key == field && value is JsonPrimitive && value.isString) listOf(value.content) else emptyList()
            here + wordsOf(value, field)
        }
        is JsonArray -> element.flatMap { wordsOf(it, field) }
        else -> emptyList()
    }

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}
