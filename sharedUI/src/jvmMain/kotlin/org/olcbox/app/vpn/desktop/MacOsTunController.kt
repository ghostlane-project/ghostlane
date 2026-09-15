package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.delay
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
import java.net.InetAddress

/**
 * macOS TUN: builds the daemon's config and asks the daemon to run it.
 *
 * The routes are deliberately not this class's business. sing-box's `auto_route`
 * installs them and removes them with the process, which is why there is nothing
 * here resembling the up/down scripts the Linux controller needs and the route
 * bookkeeping the Windows one carries — on macOS the core does that job, and does
 * it on the way out too.
 */
internal class MacOsTunController(
    private val addLog: (String) -> Unit,
    private val client: TunnelDaemonClient = TunnelDaemonClient(),
    private val resolve: (String) -> List<String> = ::resolveAllAddresses,
    private val defaultInterface: () -> String? = { defaultInterfaceName() },
) {
    suspend fun start(
        corePort: Int,
        verifyPort: Int,
        username: String,
        password: String,
        serverHost: String?,
        upstreamUdpIsLossy: Boolean,
        routing: Routing = Routing.Global,
        /** The rule-set files [routing] names, file name → base64, for the daemon to write. */
        ruleFiles: Map<String, String> = emptyMap(),
    ) {
        // Under a bypass the daemon's sing-box dials direct from inside the
        // process that owns the tun, so those sockets are bound to the physical
        // interface by name. No name, no bypass: a direct socket with nothing
        // to bind to enters the tun, and that does not degrade, it loops.
        val bindInterface = if (routing is Routing.BypassRussia) defaultInterface() else null
        if (routing is Routing.BypassRussia && bindInterface == null) {
            addLog("cannot tell which interface carries the internet, so the bypass cannot bind to it")
            error("no interface to bind direct traffic to")
        }
        if (ruleFiles.isNotEmpty()) {
            // A daemon from before rule files drops the field and starts a
            // sing-box that names files nobody wrote. It cannot be replaced from
            // here: launchd keeps it alive, and only root can restart it.
            val status = client.status()
            if (status is DaemonReply.Ok && status.protocol < TunnelDaemonProtocol.PROTOCOL_FILES) {
                addLog("the tunnel helper is from an older build and does not take rule files")
                error(
                    "restart the tunnel helper (sudo launchctl kickstart -k system/${TunnelDaemonProtocol.LABEL}) " +
                        "or the Mac, then connect again"
                )
            }
        }
        val addresses = serverHost?.let { resolve(it) }.orEmpty()
        if (serverHost != null && addresses.isEmpty()) {
            // Starting anyway would put the core's own packets into the tunnel the
            // core is building. That does not degrade — it deadlocks, and it reads
            // as a broken server rather than as a missing route.
            addLog("cannot resolve $serverHost, so its traffic cannot be kept out of the tunnel")
            error("cannot resolve $serverHost")
        }

        val config = SingBoxConfig.buildDesktopTun(
            corePort = corePort,
            verifyPort = verifyPort,
            username = username,
            password = password,
            excludeAddresses = excludeCidrs(addresses),
            directDnsDomains = listOfNotNull(serverHost?.takeIf { !it.isIpLiteral() }),
            upstreamUdpIsLossy = upstreamUdpIsLossy,
            routing = routing,
            bindInterface = bindInterface,
            cacheFilePath = TunnelDaemonProtocol.CACHE_FILE,
        )

        var reply = client.start(config, ruleFiles)
        if (reply is DaemonReply.Failure && reply.restarting) {
            // An updated app found the previous build's daemon still alive. It
            // steps aside for the one on disk, which launchd starts in its place.
            addLog("the tunnel helper was updated; waiting for the new one to come up")
            if (awaitDaemon()) reply = client.start(config, ruleFiles)
        }
        when (reply) {
            is DaemonReply.Ok -> addLog("macOS TUN running (sing-box pid ${reply.pid})")
            is DaemonReply.Failure -> {
                addLog("macOS TUN failed: ${reply.message}")
                if (reply.logTail.isNotBlank()) addLog(reply.logTail)
                error(reply.message)
            }
        }
    }

    private suspend fun awaitDaemon(): Boolean {
        repeat(RESTART_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RESTART_POLL_MS)
            if (client.status() is DaemonReply.Ok) return true
        }
        return false
    }

    suspend fun stop() {
        when (val reply = client.stop()) {
            is DaemonReply.Ok -> addLog("macOS TUN stopped")
            is DaemonReply.Failure -> addLog("macOS TUN stop failed: ${reply.message}")
        }
    }

    suspend fun isRunning(): Boolean =
        (client.status() as? DaemonReply.Ok)?.state == DaemonReply.STATE_RUNNING

    internal companion object {
        private const val RESTART_ATTEMPTS = 10
        private const val RESTART_POLL_MS = 1_000L

        /**
         * Every address the server resolves to, not the first one.
         *
         * A server that answers with four addresses and is excluded on one is a
         * tunnel that works until the core redials and happens to pick another —
         * a failure that looks intermittent and is not.
         */
        fun excludeCidrs(addresses: List<String>): List<String> =
            addresses.map { if (':' in it) "$it/128" else "$it/32" }

        /**
         * The interface behind the default route, from `route -n get default`,
         * which prints a line `interface: en0`. A utun is not an answer: it is
         * the tunnel being replaced, still up from the session before.
         */
        fun defaultInterfaceName(output: String = routeGetDefault()): String? =
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("interface:") }
                ?.substringAfter("interface:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("utun") }

        private fun routeGetDefault(): String = runCatching {
            val process = ProcessBuilder("route", "-n", "get", "default")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrDefault("")
    }
}

private fun String.isIpLiteral(): Boolean =
    ':' in this || split('.').let { parts ->
        parts.size == 4 && parts.all { it.toIntOrNull() != null }
    }

internal fun resolveAllAddresses(host: String): List<String> =
    runCatching {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())
