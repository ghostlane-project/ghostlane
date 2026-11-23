package org.olcbox.app.log

/** The text of the exported log: the head, the entries, then the tunnel component's own lines. */
object LogsExport {
    /**
     * [logs] are the app's entries (already scrubbed once, at the top); [diagnostics]
     * are the tunnel component's own files and are scrubbed here. [corePin] is the
     * commit of the Rust core the build carries, or null when it carries none.
     */
    fun build(logs: List<String>, diagnostics: List<String>, corePin: String?): String = buildString {
        LogHead.lines(logs.size, corePin).forEach(::appendLine)
        appendLine()
        logs.forEachIndexed { index, line ->
            appendLine("${index + 1}. $line")
        }
        // Scrubbed like every other line: a debug build's sing-box log names every
        // destination, and a hashed name still shows which rule it hit.
        val lines = diagnostics.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) {
            appendLine()
            appendLine("--- tunnel diagnostics ---")
            lines.forEach { appendLine(LogScrubber.default.scrub(it)) }
        }
    }
}

/**
 * The export's section for a session the Rust core ran: the pin and the core's
 * own lines (the engine's `core:` and `rtc:` lines and the session's own remarks
 * on it), cut to the last [MAX_LINES] and scrubbed as every line is. A Rust
 * session leaves no Go core files, so this is the whole of its tunnel section.
 */
object RustCoreSection {
    const val MAX_LINES = 200

    fun lines(corePin: String?, logs: List<String>): List<String> = buildList {
        add("--- Rust core ---")
        val pin = corePin?.trim()?.lowercase()?.takeIf { Regex("[0-9a-f]{7,40}").matches(it) }
        if (pin != null) add("ghostlane-core ${pin.take(12)}")
        logs.filter(::isCoreLine).takeLast(MAX_LINES).forEach { add(LogScrubber.default.scrub(it)) }
    }

    private fun isCoreLine(line: String): Boolean =
        line.startsWith("core: ") || line.startsWith("rtc: ") ||
            line.contains("Rust core") || line.contains("Rust olcRTC")
}
