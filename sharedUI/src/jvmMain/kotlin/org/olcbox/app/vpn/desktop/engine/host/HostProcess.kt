package org.olcbox.app.vpn.desktop.engine.host

import java.nio.file.Path

/** ghostlane-engine as a child process. Not written yet. */
internal class HostProcess private constructor(private val directory: Path) {
    sealed interface Start {
        data class Ready(val inbounds: List<HostEvent.Inbound>) : Start
        data class Ended(val ended: HostEvent.Ended?, val exitCode: Int?) : Start
        data object Waiting : Start
    }

    val child: Process get() = error(NOT_YET)
    val sessionFile: Path get() = directory.resolve(SESSION_FILE)
    val ended: HostEvent.Ended? get() = null
    val stopAsked: Boolean get() = false

    fun isAlive(): Boolean = false
    fun exitCode(): Int? = null
    fun awaitStart(timeoutMs: Long): Start = Start.Waiting
    fun stop() {}

    companion object {
        const val SESSION_FILE = "session.json"
        private const val NOT_YET = "ghostlane-engine's host is not written yet"

        fun run(
            binary: Path,
            session: String,
            onEvent: (HostEvent) -> Unit,
            onStderr: (String) -> Unit,
            parent: Path? = null,
        ): HostProcess = error(NOT_YET)

        fun ask(binary: Path, args: List<String>, timeoutMs: Long = 10_000L): List<HostEvent> = emptyList()

        fun privateDirectory(parent: Path?): Path = error(NOT_YET)

        fun writeSession(directory: Path, text: String): Path = error(NOT_YET)
    }
}
