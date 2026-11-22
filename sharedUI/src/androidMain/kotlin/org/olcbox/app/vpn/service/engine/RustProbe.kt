package org.olcbox.app.vpn.service.engine

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore
import java.net.Inet4Address
import java.net.InetAddress

/** A core handle of ghostlane-core started for one probe, beside the session's, and freed by that probe. */
interface RustProbeCore {
    /** Whether the handle still runs: false once [stop] was called. */
    fun isRunning(): Boolean

    /** Stops and frees the handle. Never throws; a second call does nothing. */
    fun stop()
}

/**
 * What a probe needs of ghostlane-core besides the session's one handle
 * ([org.olcbox.app.vpn.service.RustCoreBackend]): a core handle of its own.
 * The engine implements both (RustCoreEngine). xray-ffi runs any number of
 * handles in one process, each with its own inbound and its own socket
 * protector, all inside the process's one byte budget.
 */
interface RustProbeBackend {
    /**
     * Starts [configJson] on a new core handle; the session's handle is not
     * touched, whatever it is doing. [protect] is handed every socket the core
     * opens before it connects (null: none is, the app's own uid being outside
     * its tun). Throws with the core's own message.
     */
    fun startProbeCore(
        configJson: String,
        protect: ((fd: Int) -> Boolean)?,
        log: (level: Int, line: String) -> Unit
    ): RustProbeCore
}

/**
 * Probes of a candidate line on the Rust core: the session failover's in the
 * service and smart connect's in the app (AndroidVpnManager). Each probe is a
 * core handle of its own in the app's process, started from the core.json the
 * probe writes ([TransportProbe.rustConfig]) and freed when the probe ends,
 * beside the session's handle, which it never stops or restarts.
 *
 * Where there is no such handle (a build without the core) a probe is the Go
 * cores', as it is with the switch off.
 */
internal object RustProbe {
    /** Logged for every probe started here. */
    const val STARTED = "Probe on the Rust core, on a core handle of its own"

    /** The probe core's lines that reach the log: its errors and warnings (xray-ffi's levels 1 and 2). */
    private const val MAX_LOGGED_LEVEL = 2

    /**
     * The session failover's starter while the Rust core runs the session.
     * [go] is the service's Go session, which is also the service ([EngineHost]):
     * a probe's sockets are protected and its lines logged as the session's are.
     */
    fun starter(go: EngineSession): TransportProbe.Starter {
        val backend = RustCore.backend as? RustProbeBackend ?: return go.probeStarter
        val host = go as? EngineHost
        return SecondHandle(
            backend,
            context = host?.context,
            protect = host?.let { h -> { fd: Int -> h.protect(fd) } },
            log = { line -> if (host != null) host.addLog(line) else OlcboxVpnState.addLog(line) }
        )
    }

    /**
     * Smart connect's starter in the app: a second handle when the next start
     * from the app would run the Rust core (the switch as the service reads it
     * for such a start: the developer setting, else the build's default), null
     * otherwise, and the caller's Go starter is used. No socket is protected:
     * there is no VpnService here, and the app's uid is outside its own tun.
     */
    suspend fun appStarter(context: Context): TransportProbe.Starter? {
        val backend = RustCore.backend as? RustProbeBackend ?: return null
        val on = withContext(Dispatchers.IO) { RustCore.resolve(Intent(), context) }
        if (!on) return null
        return SecondHandle(backend, context.applicationContext, protect = null, log = OlcboxVpnState::addLog)
    }

    private class SecondHandle(
        private val backend: RustProbeBackend,
        private val context: Context?,
        private val protect: ((Int) -> Boolean)?,
        private val log: (String) -> Unit
    ) : TransportProbe.RustStarter {
        override suspend fun start(spec: OutboundSpec, config: String, bindInterface: String?): TransportProbe.Core {
            val core = try {
                withContext(Dispatchers.IO) {
                    backend.startProbeCore(config, protect) { level, line ->
                        if (level <= RustProbe.MAX_LOGGED_LEVEL) log("probe core: $line")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("The Rust core did not start a probe: ${e.message ?: e}")
                throw e
            }
            log(RustProbe.STARTED)
            return object : TransportProbe.Core {
                override fun isRunning(): Boolean = core.isRunning()
                override suspend fun stop() = withContext(Dispatchers.IO) { core.stop() }
            }
        }

        /**
         * [host]'s address, IPv4 first, from the network [resolver] belongs to:
         * a probe beside a session is outside its tun, and the line is written
         * with an address the core does not look up. The system's resolver when
         * no resolver was named, or no network has it.
         */
        override suspend fun serverAddress(host: String, resolver: DirectDns?): String? = withContext(Dispatchers.IO) {
            val network = resolver?.let { r -> context?.let { underneath(it, r) } }
            val all = runCatching { (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList() }
                .getOrDefault(emptyList())
            (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
        }

        override fun refused(spec: OutboundSpec, why: String) = log("The Rust core does not probe this line: $why")

        /**
         * The network [resolver]'s addresses are the resolvers of: not a VPN, with the
         * internet. Else the default network when it is no VPN, else any such network.
         */
        @Suppress("DEPRECATION") // allNetworks: its replacement is a callback, and a probe asks once
        private fun underneath(context: Context, resolver: DirectDns): Network? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val physical = cm.allNetworks.filter { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            val wanted = (resolver as? DirectDns.Servers)?.addresses.orEmpty().map { it.substringBefore('%') }.toSet()
            return physical.firstOrNull { n ->
                cm.getLinkProperties(n)?.dnsServers.orEmpty().any { it.hostAddress?.substringBefore('%') in wanted }
            } ?: cm.activeNetwork?.takeIf { it in physical } ?: physical.firstOrNull()
        }
    }
}
