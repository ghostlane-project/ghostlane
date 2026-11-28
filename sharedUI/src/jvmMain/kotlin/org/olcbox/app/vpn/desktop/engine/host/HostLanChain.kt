package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.LanCore
import java.nio.file.Path

/**
 * The LAN listener of a session whose own core does not hold it: one more
 * ghostlane-engine, whose session file is [org.olcbox.app.net.core.CoreLanChain]'s
 * core (the LAN inbound, and the session's local SOCKS port as the only way
 * out). It is the only process LAN sharing ever starts, for a room, for a line
 * the helper started, and for a LAN switched on after the connect; a core line
 * that was started with the inbound has no such process.
 *
 * The engine is the same program as the line's and is run as the line's is
 * ([HostProcess]): its stdin is the way to stop it, and an app that dies takes
 * it along.
 */
internal class HostLanChain(
    private val log: (String) -> Unit = {},
    private val binary: () -> Path = HostAssets::resolve,
    private val sessionParent: Path? = null,
) : LanCore {
    @Volatile private var current: HostProcess? = null
    @Volatile private var checked: Path? = null

    override val name = NAME
    override val ownsListener = true

    /** [config] is the core half ([org.olcbox.app.net.core.CoreLanChain.build]); returns once the engine serves or has failed. */
    override suspend fun start(config: String): Unit = withContext(Dispatchers.IO) {
        stopNow()
        val program = binary()
        checkProgram(program)
        val run = HostProcess.run(
            program,
            HostSession.text(core = config),
            onEvent = { event -> HostEvents.logLine(event)?.let { log("$NAME (LAN): $it") } },
            onStderr = { text -> log("$NAME (LAN): $text") },
            parent = sessionParent,
        )
        current = run
        when (val start = run.awaitStart(START_WAIT_MS)) {
            is HostProcess.Start.Ended -> {
                run.stop()
                current = null
                val why = start.ended?.let { ended ->
                    listOfNotNull(ended.code?.word, ended.message).joinToString(": ").ifEmpty { ended.reason.word }
                } ?: "it exited with code ${start.exitCode}"
                error("$NAME could not carry LAN sharing: $why")
            }
            is HostProcess.Start.Ready, HostProcess.Start.Waiting -> Unit
        }
    }

    override fun isRunning(): Boolean = current?.isAlive() == true

    override fun stopNow() {
        current?.stop()
        current = null
    }

    override fun program(): Path = binary()

    /** The engine processes this listener runs: none or one. */
    fun runningProcesses(): List<Process> = listOfNotNull(current?.child?.takeIf { it.isAlive })

    private fun checkProgram(program: Path) {
        if (checked == program) return
        val version = HostAssets.version(program) ?: error("$NAME printed no version: $program is not a program this app runs")
        HostAssets.refusal(version)?.let { error(it) }
        checked = program
    }

    private companion object {
        const val NAME = "ghostlane-engine"
        const val START_WAIT_MS = 10_000L
    }
}
