package org.olcbox.app.vpn.desktop.engine.host

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ghostlane-engine as a child process.
 *
 * [run] is `run --session <file>`: the session is written into a directory
 * of its own that only the user reads, as a file of mode 0600 (on Unix the
 * engine refuses any other), and nothing secret is ever an argument. The
 * child's stdin is held open as a pipe and nothing is written to it: the
 * engine ends when it closes, so closing it is the stop that is asked for, and
 * an app that dies takes its engine with it. Its stdout is read line by line
 * as [HostEvent]s, its stderr as lines for people.
 *
 * [ask] is a one-shot (`version`, and later the probes): it runs, prints its
 * one line and ends.
 */
internal class HostProcess private constructor(
    private val process: Process,
    private val directory: Path,
) {
    /** What a run came to before it served. */
    sealed interface Start {
        /** It printed `ready`: every listener is bound. */
        data class Ready(val inbounds: List<HostEvent.Inbound>) : Start

        /** It ended first: its last line ([ended], null when it printed none) and its exit status. */
        data class Ended(val ended: HostEvent.Ended?, val exitCode: Int?) : Start

        /** Neither within the wait. */
        data object Waiting : Start
    }

    private val start = CompletableFuture<Start>()

    @Volatile private var lastEnded: HostEvent.Ended? = null

    @Volatile private var stopping = false

    /** The thread that reads stdout; a stop waits for it, so that the last line is read before it returns. */
    @Volatile private var reader: Thread? = null

    /** The process, for a tun that lets the line's own processes out. */
    val child: Process get() = process

    /** The session file while the run lasts; gone once it is stopped or ended. */
    val sessionFile: Path get() = directory.resolve(SESSION_FILE)

    fun isAlive(): Boolean = process.isAlive

    /** The exit status once the engine is gone, null while it runs. */
    fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    /** The `ended` line the engine printed last, if any. */
    val ended: HostEvent.Ended? get() = lastEnded

    /** Whether the end, when it comes, was asked for by [stop]. */
    val stopAsked: Boolean get() = stopping

    /**
     * Waits up to [timeoutMs] for the run to say whether it serves. Blocking:
     * called off the main thread.
     */
    fun awaitStart(timeoutMs: Long): Start = try {
        start.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        Start.Waiting
    }

    /**
     * Ends the run and returns when it is gone: stdin is closed (the engine
     * prints `ended` and leaves within two seconds by its contract), then the
     * process is terminated, then killed. The session's directory is removed.
     * May be called more than once and from any thread.
     */
    fun stop() {
        stopping = true
        runCatching { process.outputStream.close() }
        if (!waitFor(STOP_GRACE_MS)) {
            process.destroy()
            if (!waitFor(TERMINATE_GRACE_MS)) {
                process.destroyForcibly()
                waitFor(KILL_GRACE_MS)
            }
        }
        reader?.takeIf { it !== Thread.currentThread() }?.join(KILL_GRACE_MS)
        removeDirectory()
    }

    private fun waitFor(ms: Long): Boolean = runCatching { process.waitFor(ms, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    private fun removeDirectory() {
        runCatching {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun readEvents(onEvent: (HostEvent) -> Unit) {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val event = HostEvents.parse(line)
                    when (event) {
                        is HostEvent.Ready -> start.complete(Start.Ready(event.inbounds))
                        is HostEvent.Ended -> lastEnded = event
                        else -> Unit
                    }
                    runCatching { onEvent(event) }
                }
            }
        } catch (_: IOException) {
            // The stream closed under us: the process is being torn down.
        }
        // stdout is closed: the engine has ended, or is about to.
        waitFor(KILL_GRACE_MS)
        start.complete(Start.Ended(lastEnded, exitCode()))
        if (!process.isAlive) removeDirectory()
    }

    companion object {
        const val SESSION_FILE = "session.json"

        /** The engine's contract: gone within two seconds of being told. Some room is given for a loaded machine. */
        private const val STOP_GRACE_MS = 3_000L
        private const val TERMINATE_GRACE_MS = 2_000L
        private const val KILL_GRACE_MS = 2_000L

        /**
         * Starts `run` over [session] (the session file's text). [onEvent]
         * and [onStderr] are called on threads of their own, in the order the
         * engine printed. [parent] is where the session's directory goes
         * (the system's temporary directory when null).
         */
        fun run(
            binary: Path,
            session: String,
            onEvent: (HostEvent) -> Unit,
            onStderr: (String) -> Unit,
            parent: Path? = null,
        ): HostProcess {
            val directory = privateDirectory(parent)
            val file = writeSession(directory, session)
            val process = try {
                ProcessBuilder(binary.toString(), "run", "--session", file.toString())
                    .directory(directory.toFile())
                    .start()
            } catch (e: IOException) {
                runCatching { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
                throw IllegalStateException("ghostlane-engine could not be started: ${e.message}", e)
            }
            val run = HostProcess(process, directory)
            run.reader = daemon("ghostlane-engine-events") { run.readEvents(onEvent) }
            daemon("ghostlane-engine-stderr") { readLines(process, onStderr) }
            return run
        }

        /**
         * A one-shot: [args] after the program, its events once it ended, or
         * an empty list when it did not end within [timeoutMs] (it is then
         * killed). Nothing is written to its stdin, which is closed at once.
         */
        fun ask(binary: Path, args: List<String>, timeoutMs: Long = 10_000L): List<HostEvent> {
            val process = ProcessBuilder(listOf(binary.toString()) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            runCatching { process.outputStream.close() }
            val out = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            val lines = runCatching { out.get(KILL_GRACE_MS, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            return lines.filter { it.isNotBlank() }.map { HostEvents.parse(it) }
        }

        /**
         * A new directory under [parent] that only the user may enter
         * (0700 on a file system that has POSIX permissions; elsewhere the
         * user's own profile is its protection).
         */
        fun privateDirectory(parent: Path?): Path {
            val prefix = "ghostlane-engine-"
            return if (posix()) {
                val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                if (parent != null) {
                    Files.createDirectories(parent)
                    Files.createTempDirectory(parent, prefix, mode)
                } else {
                    Files.createTempDirectory(prefix, mode)
                }
            } else {
                if (parent != null) {
                    Files.createDirectories(parent)
                    Files.createTempDirectory(parent, prefix)
                } else {
                    Files.createTempDirectory(prefix)
                }
            }
        }

        /** [text] as `session.json` in [directory], created 0600 where the system has the bits. */
        fun writeSession(directory: Path, text: String): Path {
            val file = directory.resolve(SESSION_FILE)
            if (posix()) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.createFile(file)
            }
            Files.writeString(file, text)
            return file
        }

        private fun posix(): Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

        private fun readLines(process: Process, onLine: (String) -> Unit) {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { runCatching { onLine(it) } }
                }
            } catch (_: IOException) {
                // Closed with the process.
            }
        }

        private fun daemon(name: String, body: () -> Unit): Thread =
            Thread(body, name).apply { isDaemon = true; start() }
    }
}
