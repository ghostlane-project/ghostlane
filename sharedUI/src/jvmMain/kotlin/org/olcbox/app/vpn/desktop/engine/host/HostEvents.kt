package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A word of the engine's contract: what a field holds, written as its literal.
 * Each set is closed: a word the app does not know makes the line unreadable,
 * so a new one cannot pass for an old one.
 */
internal interface HostWord {
    val word: String
}

/** Which part of the engine a line is about. */
internal enum class HostSource(override val word: String) : HostWord {
    Core("core"), Room("room"), Engine("engine")
}

/** A log line's level: the core's four, and its access log. */
internal enum class HostLevel(override val word: String) : HostWord {
    Debug("debug"), Info("info"), Warning("warning"), Error("error"), Access("access")
}

/** How bad a finding of `check-config` is. */
internal enum class HostSeverity(override val word: String) : HostWord {
    Warning("warning"), Error("error")
}

/** What kind of failure ended a command. */
internal enum class HostFailure(override val word: String) : HostWord {
    Usage("usage"), Session("session"), Config("config"), Start("start"),
    Room("room"), Bind("bind"), Unsupported("unsupported"), Internal("internal")
}

/** Why `run` ended. */
internal enum class HostEndReason(override val word: String) : HostWord {
    StdinClosed("stdin_closed"), Signal("signal"), Failed("failed")
}

/** The three one-shot questions, each answered by an event of its own name. */
internal enum class HostQuestion(override val word: String) : HostWord {
    Probe("probe"), OlcCheck("olc_check"), OlcPing("olc_ping")
}

/**
 * One line ghostlane-engine printed on stdout. The engine prints one JSON
 * object a line, its kind in `event`, and every field of a kind always (null
 * when it has no value); stderr is for people and is no part of it.
 *
 * The contract is the engine's own `tests/fixtures/events.jsonl`, a line of
 * every kind with every word. The app keeps a copy of it beside its tests,
 * and the copy's hash is pinned: HostEventsTest holds this parser to the copy
 * both ways, and a pin move rewrites the copy and its hash from the new
 * commit of the engine (scripts/pin-move.d/events-hash), so a change of the
 * contract shows in the pull request that moves the pin.
 */
internal sealed interface HostEvent {
    /** `version`: the program, the contract it reads and prints, the commit it was built from, and what it carries. */
    data class Version(
        val engine: String,
        val protocol: Int,
        /** 40 hex digits, or `unknown`. */
        val pin: String,
        val boringssl: Boolean,
        val rooms: Boolean,
        val target: String,
    ) : HostEvent

    /** A finding of `check-config`, or of a session that was accepted with a remark. */
    data class Diagnostic(val severity: HostSeverity, val source: HostSource, val path: String?, val message: String)

    /** `check_config`: whether this build takes the session, and why not. */
    data class CheckConfig(val ok: Boolean, val diagnostics: List<Diagnostic>) : HostEvent

    /** `bound`: the session's sockets leave by [interfaceName]. Before `ready`, and again when the way out moves. */
    data class Bound(val interfaceName: String) : HostEvent

    /** `warning`: the session is accepted with a remark. */
    data class Warning(val source: HostSource, val path: String?, val message: String) : HostEvent

    /** One listener of a running session: its tag ([tag], `room` for a room's own port), and where it listens. */
    data class Inbound(val tag: String?, val address: String)

    /** `ready`: every listener is bound and a room is joined. Once a run. */
    data class Ready(val inbounds: List<Inbound>) : HostEvent

    /** `session_opened`: a room session opened, the first and each after a hop or a reconnect. */
    data class SessionOpened(val room: String, val sessionId: String) : HostEvent

    /** `log`: a line of the core, the room or the engine. */
    data class Log(val source: HostSource, val level: HostLevel, val line: String) : HostEvent

    /** `probe`, `olc_check`, `olc_ping`: the answer of a one-shot. */
    data class Answer(val question: HostQuestion, val ok: Boolean, val millis: Long?, val error: String?) : HostEvent

    /** `error`: a command other than `run` failed; the only line of that run. */
    data class Failed(val code: HostFailure, val message: String) : HostEvent

    /** `ended`: `run` is over, and this is its last line. */
    data class Ended(val reason: HostEndReason, val code: HostFailure?, val message: String?) : HostEvent

    /** A line of a kind this app does not read: a newer engine's, kept as it came. */
    data class Unknown(val kind: String, val line: String) : HostEvent

    /** Not a line of the contract: no JSON object, no kind, or a field missing, of another type or not one of its words. */
    data class Unreadable(val line: String, val why: String) : HostEvent
}

/** Reads the engine's lines. */
internal object HostEvents {
    /** Every kind the parser reads, as `event` names it. */
    val KINDS: List<String> = listOf(
        "version", "check_config", "bound", "warning", "ready", "session_opened", "log",
        "probe", "olc_check", "olc_ping", "error", "ended"
    )

    /** Every closed set of words, by the field that holds it, for the tests. */
    val WORDS: Map<String, List<String>> = mapOf(
        "source" to HostSource.entries.map { it.word },
        "level" to HostLevel.entries.map { it.word },
        "severity" to HostSeverity.entries.map { it.word },
        "code" to HostFailure.entries.map { it.word },
        "reason" to HostEndReason.entries.map { it.word },
    )

    private val json = Json { isLenient = false }

    /** [line] as an event. Never throws: what is not the contract's comes back [HostEvent.Unreadable]. */
    fun parse(line: String): HostEvent {
        val text = line.trim()
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return HostEvent.Unreadable(line, "not a JSON object")
        val kind = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return HostEvent.Unreadable(line, "no `event`")
        return try {
            Fields(obj).read(kind) ?: HostEvent.Unknown(kind, line)
        } catch (e: NotTheContract) {
            HostEvent.Unreadable(line, "`$kind`: ${e.message}")
        }
    }

    private class NotTheContract(message: String) : Exception(message)

    /** The fields of one line; each read requires the field to be there, `null` only where the contract allows it. */
    private class Fields(private val obj: JsonObject) {
        fun read(kind: String): HostEvent? = when (kind) {
            "version" -> HostEvent.Version(
                engine = string("engine"), protocol = int("protocol"), pin = string("pin"),
                boringssl = boolean("boringssl"), rooms = boolean("rooms"), target = string("target"),
            )
            "check_config" -> HostEvent.CheckConfig(boolean("ok"), array("diagnostics").map { diagnostic(it) })
            "bound" -> HostEvent.Bound(string("interface"))
            "warning" -> HostEvent.Warning(word("source", HostSource.entries), stringOrNull("path"), string("message"))
            "ready" -> HostEvent.Ready(array("inbounds").map { inbound(it) })
            "session_opened" -> HostEvent.SessionOpened(string("room"), string("session_id"))
            "log" -> HostEvent.Log(word("source", HostSource.entries), word("level", HostLevel.entries), string("line"))
            "probe", "olc_check", "olc_ping" -> HostEvent.Answer(
                question = HostQuestion.entries.first { it.word == kind },
                ok = boolean("ok"), millis = longOrNull("millis"), error = stringOrNull("error"),
            )
            "error" -> HostEvent.Failed(word("code", HostFailure.entries), string("message"))
            "ended" -> HostEvent.Ended(
                word("reason", HostEndReason.entries), wordOrNull("code", HostFailure.entries), stringOrNull("message")
            )
            else -> null
        }

        private fun field(name: String): JsonElement = obj[name] ?: throw NotTheContract("no `$name`")

        private fun primitive(name: String): JsonPrimitive? = when (val value = field(name)) {
            is JsonNull -> null
            is JsonPrimitive -> value
            else -> throw NotTheContract("`$name` is not a value")
        }

        fun stringOrNull(name: String): String? = primitive(name)?.let {
            if (!it.isString) throw NotTheContract("`$name` is not a string")
            it.content
        }

        fun string(name: String): String = stringOrNull(name) ?: throw NotTheContract("`$name` is null")

        fun boolean(name: String): Boolean {
            val value = primitive(name) ?: throw NotTheContract("`$name` is null")
            if (value.isString) throw NotTheContract("`$name` is not true or false")
            return value.booleanOrNull ?: throw NotTheContract("`$name` is not true or false")
        }

        fun int(name: String): Int {
            val value = primitive(name) ?: throw NotTheContract("`$name` is null")
            if (value.isString) throw NotTheContract("`$name` is not a number")
            return value.intOrNull ?: throw NotTheContract("`$name` is not a whole number")
        }

        fun longOrNull(name: String): Long? = primitive(name)?.let {
            if (it.isString) throw NotTheContract("`$name` is not a number")
            it.longOrNull ?: throw NotTheContract("`$name` is not a whole number")
        }

        fun <W : HostWord> wordOrNull(name: String, words: List<W>): W? = stringOrNull(name)?.let { word ->
            words.firstOrNull { it.word == word } ?: throw NotTheContract("`$name` is `$word`, no word this app knows")
        }

        fun <W : HostWord> word(name: String, words: List<W>): W =
            wordOrNull(name, words) ?: throw NotTheContract("`$name` is null")

        fun array(name: String): List<JsonObject> {
            val value = field(name) as? JsonArray ?: throw NotTheContract("`$name` is not a list")
            return value.map { it as? JsonObject ?: throw NotTheContract("an item of `$name` is not an object") }
        }

        private fun diagnostic(item: JsonObject): HostEvent.Diagnostic = with(Fields(item)) {
            HostEvent.Diagnostic(
                word("severity", HostSeverity.entries), word("source", HostSource.entries),
                stringOrNull("path"), string("message")
            )
        }

        private fun inbound(item: JsonObject): HostEvent.Inbound = with(Fields(item)) {
            HostEvent.Inbound(stringOrNull("tag"), string("address"))
        }
    }

    /** [event] as a line of the app's log, or null for one that says nothing a person reads there. */
    fun logLine(event: HostEvent): String? = when (event) {
        is HostEvent.Log -> "${event.source.word}: ${event.line}"
        is HostEvent.Warning -> "${event.source.word} warning${event.path?.let { " at $it" }.orEmpty()}: ${event.message}"
        is HostEvent.Bound -> "the sockets leave by ${event.interfaceName}"
        is HostEvent.Ready -> "ready: " + event.inbounds.joinToString(", ") { "${it.tag ?: "(no tag)"} on ${it.address}" }
        is HostEvent.SessionOpened -> "a room session opened"
        is HostEvent.Ended -> "ended (${event.reason.word})" +
            (event.code?.let { ": ${it.word}" }.orEmpty()) + (event.message?.let { ": $it" }.orEmpty())
        is HostEvent.Failed -> "failed: ${event.code.word}: ${event.message}"
        is HostEvent.Unknown -> "an event this app does not read: ${event.kind}"
        is HostEvent.Unreadable -> "a line that is not the engine's contract (${event.why})"
        is HostEvent.Version, is HostEvent.CheckConfig, is HostEvent.Answer -> null
    }
}
