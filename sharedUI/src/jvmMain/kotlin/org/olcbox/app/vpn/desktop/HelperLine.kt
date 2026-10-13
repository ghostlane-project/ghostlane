package org.olcbox.app.vpn.desktop

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A file read as a process's output is: what is there, then what is added, and
 * the end once [alive] says the writer is gone and the last of it has been read.
 *
 * A line's program that the Mac's tunnel helper runs is not this app's child,
 * so there is no pipe to it. It appends to a file of the app's instead.
 */
internal class FollowedFile(
    file: Path,
    private val alive: () -> Boolean,
    private val pollMs: Long = POLL_MS
) : InputStream() {
    private val channel = FileChannel.open(file, StandardOpenOption.READ)

    @Volatile private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!closed) {
            val wasAlive = alive()
            val count = channel.read(ByteBuffer.wrap(b, off, len))
            if (count > 0) return count
            // Asked before the read: what a writer added as it ended is read
            // on this turn, and the end is said on the next.
            if (!wasAlive) return -1
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        return -1
    }

    override fun close() {
        closed = true
        channel.close()
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

/**
 * A line's program run by the Mac's tunnel helper, as the rest of the app holds
 * any process: alive or not, read line by line, ended. It runs as this app's
 * user, so it is ended as any process of the user's is; the helper, whose
 * child it is, sees it go.
 */
internal class HelperLine(
    private val pid: Long,
    private val said: InputStream,
    private val handle: ProcessHandle? = ProcessHandle.of(pid).orElse(null)
) : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = said

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        handle?.onExit()?.join()
        return EXIT_NOT_KNOWN
    }

    /** Not known: the program is the helper's child, and only a parent is told how a process ended. */
    override fun exitValue(): Int {
        if (isAlive) throw IllegalThreadStateException("the line's program is running")
        return EXIT_NOT_KNOWN
    }

    override fun destroy() {
        handle?.destroy()
    }

    override fun destroyForcibly(): Process {
        handle?.destroyForcibly()
        return this
    }

    override fun isAlive(): Boolean = handle?.isAlive == true

    override fun pid(): Long = pid

    override fun toHandle(): ProcessHandle = handle ?: throw UnsupportedOperationException("the line's program is gone")

    override fun info(): ProcessHandle.Info = toHandle().info()

    companion object {
        const val EXIT_NOT_KNOWN = -1
    }
}
