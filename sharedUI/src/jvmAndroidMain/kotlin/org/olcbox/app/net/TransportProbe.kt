package org.olcbox.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Smart connect's probe, the same on Android and the desktop: a location's own core
 * on a loopback port nothing holds, behind a login made up for this probe (an open
 * loopback port is anyone's, see [SocksLogin]), asked through [TransportCheck]
 * before any tunnel exists, and stopped whatever happens. A platform only says how
 * to start a core ([Starter]).
 */
internal object TransportProbe {
    /** A started core, as much of it as the probe needs. */
    interface Core {
        fun isRunning(): Boolean
        suspend fun stop()
    }

    /** Starts [config] (built by [coreConfig]) in the right core for [spec]. */
    fun interface Starter {
        suspend fun start(spec: OutboundSpec, config: String): Core
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val PORT_WAIT_MS = 5_000L

    /** xhttp is Xray's; everything else a probe sees is sing-box's. */
    fun usesXray(spec: OutboundSpec): Boolean = spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp

    fun coreConfig(spec: OutboundSpec, port: Int, login: SocksLogin): String =
        if (spec is OutboundSpec.Vless && usesXray(spec)) {
            XrayConfig.buildXhttp(spec, socksPort = port, login = login)
        } else {
            SingBoxConfig.build(spec, socksPort = port, login = login)
        }

    suspend fun passes(location: LocationConfig, starter: Starter): Boolean = withContext(Dispatchers.IO) {
        val spec = location.rawLink?.let { LinkParser.parse(it) } ?: return@withContext false
        val port = freeLoopbackPort() ?: return@withContext false
        val login = SocksLogin(token(), token())
        var core: Core? = null
        try {
            val started = starter.start(spec, coreConfig(spec, port, login)).also { core = it }
            if (!waitForPort(port, started)) return@withContext false
            val proxy = SubscriptionFetchProxy(LOOPBACK, port, login.username, login.password)
            val client = createProxyHttpClient(
                proxy,
                connectTimeoutMs = 5_000,
                requestTimeoutMs = TransportCheck.TIMEOUT_MS,
                socketTimeoutMs = TransportCheck.TIMEOUT_MS,
                followRedirects = false
            )
            try {
                withProxyAuthentication(proxy) { TransportCheck.passes(client) }
            } finally {
                client.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            withContext(NonCancellable) { runCatching { core?.stop() } }
        }
    }

    private suspend fun waitForPort(port: Int, core: Core): Boolean {
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!core.isRunning()) return false
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 150) }
            }.isSuccess
            if (open) return true
            delay(100)
        }
        return false
    }

    private fun freeLoopbackPort(): Int? = runCatching {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
    }.getOrNull()

    private val random = SecureRandom()

    private fun token(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
