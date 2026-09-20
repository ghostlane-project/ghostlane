package org.olcbox.app.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.olcbox.app.data.repository.SubscriptionFetchProxy

/** Tests a real outbound through an isolated SOCKS listener, without installing routes. */
internal object DesktopChannelProbe {
    // LocationViewModel owns the bounded semaphore. Each probe has an isolated
    // core, config directory and ephemeral SOCKS port, so serialising again here
    // turned a 30-server subscription into minutes of waiting.
    suspend fun measure(spec: OutboundSpec): Long? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                val port = ProbePorts.reserve()
                val usesXray = spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp
                val singBox = if (usesXray) null else DesktopSingBoxController()
                val xray = if (usesXray) DesktopXrayController() else null
                try {
                    if (usesXray) xray!!.start(XrayConfig.buildXhttp(spec, socksPort = port))
                    else singBox!!.start(SingBoxConfig.build(spec, socksPort = port))
                    val alive = { if (usesXray) xray!!.isRunning() else singBox!!.isRunning() }
                    while (alive()) {
                        val ready = runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                        }.isSuccess
                        if (ready) {
                            val measured = ChannelLatency.measure(SubscriptionFetchProxy("127.0.0.1", port))
                            return@withTimeoutOrNull measured.takeIf { alive() }
                        }
                        delay(100)
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    // Cancellation must not leave an orphan proxy or its sockets.
                    withContext(NonCancellable) {
                        singBox?.stop()
                        xray?.stop()
                        ProbePorts.release(port)
                    }
                }
            }
        }

    /** Prevent parallel probes inside this process from recycling one closed port. */
    private object ProbePorts {
        private val reserved = mutableSetOf<Int>()

        @Synchronized
        fun reserve(): Int {
            while (true) {
                val port = ServerSocket().use {
                    it.bind(InetSocketAddress("127.0.0.1", 0))
                    it.localPort
                }
                if (reserved.add(port)) return port
            }
        }

        @Synchronized
        fun release(port: Int) {
            reserved.remove(port)
        }
    }
}
