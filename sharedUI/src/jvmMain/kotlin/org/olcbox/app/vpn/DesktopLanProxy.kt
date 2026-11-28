package org.olcbox.app.vpn

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreLanChain
import org.olcbox.app.vpn.desktop.WindowsLanFirewall
import org.olcbox.app.vpn.desktop.engine.host.HostAssets
import org.olcbox.app.vpn.desktop.engine.host.HostLanChain
import java.nio.file.Path

/**
 * What runs LAN sharing's listener, or stands for it when the session's own
 * core holds it. Two ways, both ghostlane-engine and neither a Go core:
 *  - the session's core holds the LAN inbound (a core line started with it,
 *    CoreInbound.Lan, written by HostEngine): nothing is started here, and this
 *    core is only the app's way to reach its settings;
 *  - every other session (a room, a line started without the inbound, a LAN
 *    switched on after the connect, a tunnel the helper started): one more
 *    engine run, [HostLanChain], whose only way out is the session's local SOCKS
 *    port with its login. The line or the room is not touched, so a room is not
 *    joined again and its rules, if a front has them, still apply to what a LAN
 *    client sends.
 */
internal interface LanCore {
    /** The program's name, for the log. */
    val name: String

    /** Whether this core binds the LAN port in a process of its own. */
    val ownsListener: Boolean

    /** Starts what this core owns; the session's core is started elsewhere. */
    suspend fun start(config: String)

    fun isRunning(): Boolean

    /** Ends what this core owns. May be called more than once. */
    fun stopNow()

    /** The program, for the rule the Windows firewall is given. */
    fun program(): Path
}

/**
 * What LAN sharing reads: the manager's settings, and the LAN inbound the
 * running line's core actually holds. A line's core is used only when it holds
 * these exact settings; any other session gets the engine run of its own, so
 * the two cannot disagree about who binds the port.
 */
internal object LanCores {
    /** What the running line's core holds: its LAN inbound, or why it holds none. */
    data class LineState(val lan: CoreInbound.Lan?, val reason: String?)

    /** The manager's current settings, registered by [DesktopLanProxy]. */
    @Volatile
    var settings: () -> DesktopSocksProxySettings = { DesktopSocksProxySettings() }

    fun currentSettings(): DesktopSocksProxySettings = settings()

    @Volatile private var lineState: LineState = LineState(null, null)

    /** What a line's core started with, read by the proxy before it binds anything. */
    fun lineStarted(lan: CoreInbound.Lan?, reason: String? = null) {
        lineState = LineState(lan, reason)
    }

    fun lineStopped() {
        lineState = LineState(null, null)
    }

    fun currentLine(): LineState = lineState

    /**
     * Whether the running line's core holds exactly these LAN settings. Lan is
     * not a data class, so its three values are compared here, not its object.
     */
    fun lineHolds(lan: CoreInbound.Lan): Boolean {
        val held = lineState.lan ?: return false
        return held.address == lan.address && held.port == lan.port && held.login == lan.login
    }

    /**
     * The engine run that carries LAN sharing for a session whose core does
     * not hold the inbound: [CoreLanChain]'s core, chained to the session's
     * local SOCKS port.
     */
    fun chain(
        onOutput: (String) -> Unit = {},
        binary: () -> Path = HostAssets::resolve,
        sessionParent: Path? = null,
    ): LanCore = HostLanChain(onOutput, binary, sessionParent)

    /**
     * The session's own core, which holds the LAN inbound: no second program is
     * started and no port is bound here. [binary] names the program for the
     * Windows firewall rule; it is never run by this core.
     */
    fun session(binary: () -> Path = HostAssets::resolve): LanCore = SessionLanCore(binary)
}

private class SessionLanCore(private val binary: () -> Path) : LanCore {
    @Volatile private var active = false

    override val name = "ghostlane-engine"
    override val ownsListener = false

    override suspend fun start(config: String) {
        // The line's core bound the LAN port and is already serving. There is
        // no second process to start, and nothing here may bind that port.
        active = true
    }

    override fun isRunning() = active

    override fun stopNow() {
        active = false
    }

    override fun program(): Path = binary()
}

/** A credentialed SOCKS5 listener bound to one explicitly selected private adapter. */
internal class DesktopLanProxy(
    private val onOutput: (String) -> Unit,
    private val core: LanCore = LanCores.chain(onOutput),
    settings: () -> DesktopSocksProxySettings = LanCores.settings,
    private val availableAddresses: () -> List<String> = Companion::privateAddresses,
    private val readNetworkIdentity: (String) -> String? = Companion::networkIdentity,
    private val proveOwnership: suspend (LanCore, Int, String, String) -> Unit = { listener, port, user, password ->
        DesktopLanProxy.proveOwnershipOf(listener, port, user, password)
    },
) {
    /** Stands for the session's own core when that core holds the LAN inbound. */
    private val lineCore: LanCore = LanCores.session()
    private var active: LanCore = core

    /** The Windows firewall's one inbound rule for the listener: removed at stop, and swept after a kill. */
    private val windowsFirewall = WindowsLanFirewall()

    init {
        // The line's core reads the same settings when it writes its plan.
        LanCores.settings = settings
    }

    suspend fun start(settings: DesktopSocksProxySettings, upstream: SubscriptionFetchProxy): String {
        val normalized = settings.normalized()
        require(normalized.lanUsername.isNotBlank() && normalized.lanPassword.isNotBlank()) {
            "LAN sharing credentials are missing"
        }
        val available = availableAddresses()
        val host = normalized.lanAddress.takeIf { it in available }
            ?: error("The selected private network interface is no longer available")
        val currentNetworkId = readNetworkIdentity(host)
            ?: error("The selected LAN gateway could not be identified")
        require(normalized.lanNetworkId.isNotBlank() && normalized.lanNetworkId == currentNetworkId) {
            "This is a different local network; select the LAN interface again before sharing"
        }
        // The line's own plan and the engine run of [core] refuse the same shape.
        val lan = CoreInbound.Lan(
            normalized.lanAddress, normalized.lanPort,
            SocksLogin(normalized.lanUsername, normalized.lanPassword),
        )
        CoreInbound.refusal(CoreInbound.Plan(CoreInbound.Proxy(upstream.port), lan))
            ?.let { throw IllegalArgumentException(it) }
        // The running session, not a switch set elsewhere, owns the port. A
        // line that left LAN out, a room, a line started by the helper and a
        // LAN switched on after the connect get the engine run of [core],
        // chained to the session's port; a refusal there is a sentence in
        // the log and in the LAN block, never a listener that is not there.
        val listener = if (LanCores.lineHolds(lan)) lineCore else core
        active = listener
        if (listener.ownsListener) {
            onOutput("LAN sharing: the session's core holds no LAN inbound; ${listener.name} carries it, chained to the session's port")
        }

        try {
            // Whoever holds the listener, a rule a killed run left is not there to find it open.
            if (DesktopPaths.os == DesktopOs.Windows) windowsFirewall.sweep()
            if (listener.ownsListener) {
                requirePortFree(host, normalized.lanPort)
                val ownershipPort = allocateLoopbackPort()
                val ownershipUsername = DesktopSocksProxySettings.randomToken(16)
                val ownershipPassword = DesktopSocksProxySettings.randomToken(32)
                listener.start(
                    chainConfig(
                        host, normalized.lanPort, normalized.lanUsername, normalized.lanPassword, upstream,
                        ownershipPort, ownershipUsername, ownershipPassword
                    )
                )
                proveOwnership(listener, ownershipPort, ownershipUsername, ownershipPassword)
            } else {
                // The line's core is already listening on this port. Binding it
                // here too, or chaining a second engine to it, would race the
                // session's own core.
                listener.start("")
            }
            if (DesktopPaths.os == DesktopOs.Windows) windowsFirewall.add(host, normalized.lanPort, listener.program())
            return "$host:${normalized.lanPort}"
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        active.stopNow()
        if (!windowsFirewall.remove()) onOutput("LAN sharing: could not remove its Windows firewall rule")
    }

    fun isRunning(): Boolean = active.isRunning()

    fun cleanupStaleFirewallRules() {
        if (DesktopPaths.os == DesktopOs.Windows) windowsFirewall.sweep()
    }

    private fun requirePortFree(host: String, port: Int) {
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(host, port))
            }
        }.getOrElse { error("LAN port $host:$port is already in use") }
    }

    private fun allocateLoopbackPort(): Int = ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        socket.localPort
    }

    companion object {
        /**
         * The run opened its listener, and it is this run's: the private loopback
         * inbound that exists in the same config as the LAN listener answers with
         * its login and reaches the VPN. If the requested LAN port belonged to
         * another process, the core could not bind the config and this cannot pass.
         */
        suspend fun proveOwnershipOf(
            listener: LanCore,
            ownershipPort: Int,
            username: String,
            password: String,
            reach: suspend (Int, String, String) -> Boolean = { port, user, pass ->
                TunnelVerifier.verify(
                    socksHost = "127.0.0.1",
                    socksPort = port,
                    username = user,
                    password = pass,
                    timeoutMs = 5_000
                ) != null
            },
        ) {
            // Not withTimeout: its timeout is a CancellationException, and the
            // callers read that as "the start was superseded". A connect with LAN
            // sharing on was then taken down without a word and its status left
            // at Stopping, because a listener was slow to open. As an ordinary
            // failure it costs LAN sharing and nothing else.
            withTimeoutOrNull(5_000) {
                while (true) {
                    check(listener.isRunning()) { "LAN SOCKS listener exited; check the port and interface" }
                    if (runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", ownershipPort), 200) }
                        }.isSuccess
                    ) break
                    delay(100)
                }
            } ?: error("LAN SOCKS listener did not open in time; check the port and interface")
            if (!reach(ownershipPort, username, password)) error("LAN SOCKS ownership check did not reach the VPN")
        }

        fun privateAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.hardwareAddress != null }
            .filterNot { isVirtualAdapter("${it.name} ${it.displayName}") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .distinct()
            .sortedBy(::ipv4SortKey)
        }.getOrDefault(emptyList())

        /** Default-gateway address and link-layer identity for the selected interface. */
        fun networkIdentity(address: String): String? = runCatching {
            val command = when (DesktopPaths.os) {
                DesktopOs.Windows -> listOf(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "\$ErrorActionPreference='Stop'; \$ip=Get-NetIPAddress -AddressFamily IPv4 " +
                        "-IPAddress '${address.replace("'", "''")}' | Select-Object -First 1; " +
                        "\$r=Get-NetRoute -AddressFamily IPv4 -InterfaceIndex \$ip.InterfaceIndex " +
                        "-DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1; " +
                        "\$n=Get-NetNeighbor -InterfaceIndex \$ip.InterfaceIndex -IPAddress \$r.NextHop " +
                        "-ErrorAction SilentlyContinue | Select-Object -First 1; " +
                        "Write-Output (\$r.NextHop + '|' + \$n.LinkLayerAddress)"
                )
                DesktopOs.Linux -> listOf("sh", "-c", "gw=\$(ip route show default | awk '/dev/ {print \$3; exit}'); mac=\$(ip neigh show \"\$gw\" | awk '{print \$5; exit}'); printf '%s|%s' \"\$gw\" \"\$mac\"")
                DesktopOs.MacOS -> listOf("sh", "-c", "gw=\$(route -n get default | awk '/gateway:/ {print \$2; exit}'); mac=\$(arp -n \"\$gw\" | awk 'NR==1 {print \$4}'); printf '%s|%s' \"\$gw\" \"\$mac\"")
                DesktopOs.Other -> return null
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)
            process.inputStream.bufferedReader().use { it.readText() }.trim()
                .lowercase().takeIf { it.contains('|') && !it.endsWith('|') }
        }.getOrNull()

        /**
         * Why a line's core must leave LAN sharing out of its plan, or null
         * when the network the settings name is still the one the machine is
         * on. A line with no sharing asks nothing of the network.
         */
        fun networkProblem(
            settings: DesktopSocksProxySettings,
            availableAddresses: () -> List<String> = ::privateAddresses,
            readNetworkIdentity: (String) -> String? = ::networkIdentity,
        ): String? {
            if (!settings.shareOnLan) return null
            if (settings.lanAddress !in availableAddresses()) {
                return "The selected private network interface is no longer available"
            }
            val identity = readNetworkIdentity(settings.lanAddress)
                ?: return "The selected LAN gateway could not be identified"
            if (settings.lanNetworkId.isBlank() || settings.lanNetworkId != identity) {
                return "This is a different local network; select the LAN interface again before sharing"
            }
            return null
        }

        internal fun isVirtualAdapter(name: String): Boolean = name.contains(
            Regex(
                "(?i)wintun|wireguard|tun2socks|tap-windows|ghostlane|olcbox|" +
                    "docker|(^|[^a-z])br-|vbox|virtualbox|vmnet|vmware|vEthernet|wsl|hyper-v|" +
                    "tailscale|zerotier"
            )
        )

        private fun ipv4SortKey(address: String): Long = address.split('.')
            .fold(0L) { value, octet -> value * 256 + (octet.toLongOrNull() ?: 0L) }

        /**
         * What the engine run of [HostLanChain] is given: the LAN inbound on
         * [host]:[port] with its login, an inbound of its own on loopback
         * ([ownershipPort], with its login, the one the caller checks the
         * run through), and the session's port [upstream] as the one way out.
         */
        fun chainConfig(
            host: String,
            port: Int,
            username: String,
            password: String,
            upstream: SubscriptionFetchProxy,
            ownershipPort: Int,
            ownershipUsername: String,
            ownershipPassword: String,
        ): String {
            require(port in DesktopSocksProxySettings.MIN_PORT..DesktopSocksProxySettings.MAX_PORT && port != upstream.port) {
                "LAN port must differ from the core port"
            }
            require(username.isNotBlank() && password.isNotBlank()) { "LAN credentials are required" }
            require(username.length <= DesktopSocksProxySettings.MAX_CREDENTIAL_LENGTH)
            require(password.length <= DesktopSocksProxySettings.MAX_CREDENTIAL_LENGTH)
            require(ownershipUsername.isNotBlank() && ownershipPassword.isNotBlank())
            val plan = CoreInbound.Plan(
                CoreInbound.Tunnel(ownershipPort, SocksLogin(ownershipUsername, ownershipPassword)),
                CoreInbound.Lan(host, port, SocksLogin(username, password)),
            )
            return CoreLanChain.build(
                plan, upstream.host, upstream.port,
                SocksLogin(upstream.username, upstream.password).takeIf { upstream.username.isNotBlank() },
            )
        }
    }
}
