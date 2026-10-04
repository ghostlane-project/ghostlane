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

    /**
     * [serverResolver]: see [SingBoxConfig.build]; Android passes the network's own resolvers.
     *
     * [autoDetectInterface] and [bindInterface] are for a probe made while a
     * session's tun is up, where the tun's rule does not let the core out by
     * itself, which is the Linux desktop. They are the binding the session's
     * own core is started with there: `route.auto_detect_interface` for
     * sing-box ([SingBoxConfig.build]) and `sockopt.interface` for Xray, which
     * has to be told the name ([XrayConfig.buildXhttp]). Each core takes its
     * own and ignores the other's. Left as they are, the config is byte for
     * byte what it was before they existed.
     */
    fun coreConfig(
        spec: OutboundSpec,
        port: Int,
        login: SocksLogin,
        serverResolver: DirectDns? = null,
        autoDetectInterface: Boolean = false,
        bindInterface: String? = null
    ): String =
        if (spec is OutboundSpec.Vless && usesXray(spec)) {
            XrayConfig.buildXhttp(spec, socksPort = port, login = login, bindInterface = bindInterface)
        } else {
            SingBoxConfig.build(
                spec,
                socksPort = port,
                login = login,
                serverResolver = serverResolver,
                autoDetectInterface = autoDetectInterface
            )
        }

    suspend fun passes(
        location: LocationConfig,
        serverResolver: DirectDns? = null,
        starter: Starter
    ): Boolean = passes(
        location,
        serverResolver,
        autoDetectInterface = false,
        bindInterface = null,
        starter = starter
    )

    /**
     * The same probe for a core that is started beside a tun and has to be led
     * out of it ([coreConfig] says how).
     *
     * A function of its own and not two more defaults on the one above: there
     * [starter] is the third argument for a caller that names nothing and the
     * last for one that passes it as a lambda, and Android has one of each, so
     * no place is left where a new parameter breaks neither.
     */
    suspend fun passes(
        location: LocationConfig,
        serverResolver: DirectDns?,
        autoDetectInterface: Boolean,
        bindInterface: String?,
        starter: Starter
    ): Boolean = withContext(Dispatchers.IO) {
        val spec = location.rawLink?.let { LinkParser.parse(it) } ?: return@withContext false
        val port = freeLoopbackPort() ?: return@withContext false
        val login = SocksLogin(token(), token())
        var core: Core? = null
        try {
            val config = coreConfig(spec, port, login, serverResolver, autoDetectInterface, bindInterface)
            val started = starter.start(spec, config).also { core = it }
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
