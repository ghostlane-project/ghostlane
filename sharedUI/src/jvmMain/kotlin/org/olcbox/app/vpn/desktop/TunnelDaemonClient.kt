package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Talks to the root tunnel daemon over its unix socket.
 *
 * One connection per request, closed after the reply: the daemon holds the
 * tunnel, not the conversation, so a client that dies mid-sentence costs
 * nothing. That is also why nothing here retries — a caller that wants the
 * tunnel back asks for it again, and a retry hidden in here would turn one
 * refused command into several.
 */
internal class TunnelDaemonClient(
    private val socketPath: Path = DEFAULT_SOCKET_PATH
) {
    suspend fun start(
        config: String,
        files: Map<String, String> = emptyMap(),
        names: String? = null,
        hold: DaemonHold? = null
    ): DaemonReply = send(TunnelDaemonProtocol.startRequest(config, files, names, hold))

    suspend fun stop(keepHold: Boolean = false): DaemonReply = send(TunnelDaemonProtocol.stopRequest(keepHold))

    suspend fun release(): DaemonReply = send(TunnelDaemonProtocol.releaseRequest())

    suspend fun status(): DaemonReply = send(TunnelDaemonProtocol.statusRequest())

    suspend fun isReachable(): Boolean = status() is DaemonReply.Ok

    private suspend fun send(request: String): DaemonReply = withContext(Dispatchers.IO) {
        // Absent is the normal state of an install where the daemon has not been
        // approved yet, so it is answered, not thrown about.
        if (!socketPath.exists()) {
            return@withContext DaemonReply.Failure("the tunnel daemon is not installed", "")
        }
        runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                val out = ByteBuffer.wrap(request.toByteArray())
                while (out.hasRemaining()) channel.write(out)
                readLine(channel)
            }
        }.fold(
            onSuccess = { TunnelDaemonProtocol.parseReply(it) },
            onFailure = { DaemonReply.Failure(it.message ?: "the tunnel daemon did not answer", "") }
        )
    }

    private fun readLine(channel: SocketChannel): String {
        val buffer = ByteBuffer.allocate(REPLY_LIMIT)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
            val seen = String(buffer.array(), 0, buffer.position())
            if ('\n' in seen) return seen
        }
        return String(buffer.array(), 0, buffer.position())
    }

    companion object {
        val DEFAULT_SOCKET_PATH: Path = Path.of("/var/run/org.olcbox.app.tunneld.sock")

        /** A reply carries a log tail, not a log. Anything larger is a daemon gone wrong. */
        private const val REPLY_LIMIT = 64 * 1024
    }
}
