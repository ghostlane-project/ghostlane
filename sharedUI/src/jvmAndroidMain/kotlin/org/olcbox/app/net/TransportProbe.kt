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
import org.olcbox.app.net.core.CoreConfig
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
 * to start a core ([Starter]): one of the Go cores, which is handed their config
 * ([coreConfig]), or the Rust core ([RustStarter]), which is handed its own
 * ([rustConfig]) and never a line it would refuse.
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

    /**
     * A starter of the Rust core: a second core handle in the app on Android,
     * a throw-away engine process on the desktop. The probe hands it core.json
     * ([rustConfig]): the line alone, behind one SOCKS5 inbound on the probe's
     * loopback port that demands the probe's login.
     *
     * What core.json does not say comes beside it. `bindInterface` is the
     * interface a probe beside the Linux tun leaves by, which the engine is
     * told in its session and not in the core's config; null everywhere
     * else. A server named by a hostname is asked of [serverAddress], on the
     * network underneath, and the line is written with the address
     * ([CoreLines.pinHost]): the core would ask its own resolver, which
     * behind a tun is the tun's. A line the core would refuse, a hostname
     * with no address among them, is never started: [refused] is told why,
     * and the probe does not pass.
     */
    interface RustStarter : Starter {
        suspend fun start(spec: OutboundSpec, config: String, bindInterface: String?): Core

        /** [host]'s address on the network underneath, or null when there is none to give. */
        suspend fun serverAddress(host: String): String? = null

        /** [spec] was not started: the core would refuse it, [why] says why. */
        fun refused(spec: OutboundSpec, why: String) {}

        override suspend fun start(spec: OutboundSpec, config: String): Core = start(spec, config, null)
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

    /**
     * The Rust core's config for a probe of [spec], a line it takes: the
     * session's writer ([CoreConfig.build]) under Global, on 127.0.0.1:[port]
     * demanding [login]. Nothing of a tun is in it: a resolver and an
     * interface reach the Rust core by its [RustStarter].
     */
    fun rustConfig(spec: OutboundSpec, port: Int, login: SocksLogin): String =
        CoreConfig.build(spec, login, socksPort = port)

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
    ): Boolean = passesWith(location, serverResolver, autoDetectInterface, bindInterface, starter) { throughTransportCheck(it) }

    /**
     * The probe, with what it asks through the core's port given: [ask] is
     * handed the port and the login, and says whether the line passed.
     * [TransportCheck] in every probe the app makes; a test's own question
     * where a fake core cannot reach the internet.
     */
    internal suspend fun passesWith(
        location: LocationConfig,
        serverResolver: DirectDns?,
        autoDetectInterface: Boolean,
        bindInterface: String?,
        starter: Starter,
        ask: suspend (SubscriptionFetchProxy) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val spec = location.rawLink?.let { LinkParser.parse(it) } ?: return@withContext false
        val port = freeLoopbackPort() ?: return@withContext false
        val login = SocksLogin(token(), token())
        var core: Core? = null
        try {
            val started = starter.start(spec, coreConfig(spec, port, login, serverResolver, autoDetectInterface, bindInterface))
            core = started
            if (!waitForPort(port, started)) return@withContext false
            ask(SubscriptionFetchProxy(LOOPBACK, port, login.username, login.password))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            withContext(NonCancellable) { runCatching { core?.stop() } }
        }
    }

    private suspend fun throughTransportCheck(proxy: SubscriptionFetchProxy): Boolean {
        val client = createProxyHttpClient(
            proxy,
            connectTimeoutMs = 5_000,
            requestTimeoutMs = TransportCheck.TIMEOUT_MS,
            socketTimeoutMs = TransportCheck.TIMEOUT_MS,
            followRedirects = false
        )
        return try {
            withProxyAuthentication(proxy) { TransportCheck.passes(client) }
        } finally {
            client.close()
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
