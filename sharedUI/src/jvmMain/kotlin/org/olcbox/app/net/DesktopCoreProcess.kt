package org.olcbox.app.net

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Spawns and supervises a bundled core binary (sing-box / xray) running in SOCKS
 * mode with a generated config file. Mirrors the process handling in
 * WindowsTunController (redirectErrorStream + destroy/destroyForcibly teardown).
 * The tun→SOCKS bridge / PAC points at the core's SOCKS port; this class only owns
 * the child process.
 *
 * The core's own output used to go to a file in a temp directory that nothing ever
 * read, so an outbound that failed — with the core reporting exactly why in its
 * first lines — surfaced to the user as nothing more than a dead SOCKS port. It is
 * now streamed to [onOutput] as it arrives, and the desktop VPN manager forwards
 * that into the app log.
 */
internal class DesktopCoreProcess(
    private val binaryPath: () -> Path,
    private val label: String,
    /** Builds the argv given the binary path and the written config file path. */
    private val argv: (bin: String, config: Path) -> List<String>,
    /** Receives the core's stdout/stderr, line by line, as it is produced. */
    private val onOutput: (String) -> Unit = {},
) {
    @Volatile private var process: Process? = null
    private val workDir: Path = Files.createTempDirectory("olcbox-$label")

    @Synchronized
    fun start(configJson: String) {
        stop()
        val config = workDir.resolve("config.json")
        Files.writeString(config, configJson)
        val started = ProcessBuilder(argv(binaryPath().toString(), config))
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        process = started
        Thread {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) onOutput("$label: $trimmed")
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "olcbox-$label-log"
            start()
        }
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        p.toHandle().descendants().forEach { it.destroy() }
        p.destroy()
        if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.toHandle().descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
            p.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        // A latency test owns a short-lived core. Do not leave its UUID or
        // upstream credentials in a temp config once the process is gone.
        if (!p.isAlive) runCatching { Files.deleteIfExists(workDir.resolve("config.json")) }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    internal fun runningProcess(): Process? = process?.takeIf { it.isAlive }

    /** Exit code once the core has finished; null while it is still running. */
    fun exitCodeOrNull(): Int? = process?.let { if (it.isAlive) null else it.exitValue() }

    private companion object {
        const val STOP_TIMEOUT_MS = 3_000L
        const val KILL_TIMEOUT_MS = 2_000L
    }
}
