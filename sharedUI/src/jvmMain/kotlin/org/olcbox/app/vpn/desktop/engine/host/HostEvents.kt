package org.olcbox.app.vpn.desktop.engine.host

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

/** Reads the engine's lines. Not written yet: every line is unreadable. */
internal object HostEvents {
    val KINDS: List<String> = emptyList()

    val WORDS: Map<String, List<String>> = emptyMap()

    fun parse(line: String): HostEvent = HostEvent.Unreadable(line, "the parser is not written yet")

    fun logLine(event: HostEvent): String? = null
}
