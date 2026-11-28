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
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
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
 * to start the Rust core ([RustStarter]), which is handed its config
 * ([rustConfig]) and never a line it would refuse.
 */
internal object TransportProbe {
    /** A started core, as much of it as the probe needs. */
    interface Core {
        fun isRunning(): Boolean
        suspend fun stop()
    }

    /** Starts [config] in the core for [spec]; only a [RustStarter] is asked. */
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

        /**
         * [host]'s address on the network underneath, or null when there is
         * none to give. [resolver] is the resolver of that network, the one
         * the probe was handed ([passes]' serverResolver): a failover works it out per call, from the
         * session or from the network it fails over on, and a starter is the
         * engine's and cannot. It is asked in place of the system's resolver,
         * which behind a tun is the tun's. Null when the caller named none,
         * where the system's resolver is the network's.
         */
        suspend fun serverAddress(host: String, resolver: DirectDns?): String? = null

        /** [spec] was not started: the core would refuse it, [why] says why. */
        fun refused(spec: OutboundSpec, why: String) {}

        override suspend fun start(spec: OutboundSpec, config: String): Core = start(spec, config, null)
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val PORT_WAIT_MS = 5_000L

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
     * out of it by [bindInterface]. [autoDetectInterface] is read by no core
     * now: it was sing-box's.
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
            // A starter that is not the Rust core's has nothing to start: no
            // other core is in the app.
            val rust = starter as? RustStarter ?: return@withContext false
            val line = rustLine(spec, serverResolver, rust) ?: return@withContext false
            val started = rust.start(line, rustConfig(line, port, login), bindInterface)
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

    /**
     * [spec] as the Rust core is to be started with it, its server pinned to
     * the address [starter] gives on [serverResolver]; null when the core
     * would refuse it.
     */
    private suspend fun rustLine(spec: OutboundSpec, serverResolver: DirectDns?, starter: RustStarter): OutboundSpec? {
        val line = if (XrayConfig.isIpLiteral(spec.host)) {
            spec
        } else {
            starter.serverAddress(spec.host, serverResolver)?.let { CoreLines.pinHost(spec, it) } ?: spec
        }
        val why = CoreRefusal.of(line) ?: return line
        starter.refused(spec, why)
        return null
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
