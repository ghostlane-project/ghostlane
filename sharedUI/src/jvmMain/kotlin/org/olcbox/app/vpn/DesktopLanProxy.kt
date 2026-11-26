package org.olcbox.app.vpn

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.engine.host.HostAssets
import java.nio.file.Path

/**
 * What runs LAN sharing's listener, or stands for it when the session's own
 * core holds it. Only the Go core owns a process and a port of its own; on the
 * Rust engine the listener is CoreInbound.Lan in the line's core, written by
 * HostEngine, and this core is only the app's way to reach its settings.
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
 * The cores LAN sharing can run on. Go stays the default; the engine is
 * chosen by setting [factory], beside the desktop engine's own. Both read the
 * manager's settings through [settings], which the line's core reads too when
 * it is the Rust engine.
 */
internal object LanCores {
    @Volatile
    var factory: (onOutput: (String) -> Unit) -> LanCore = { out -> go(out) }

    /** The manager's current settings, registered by [DesktopLanProxy]. */
    @Volatile
    var settings: () -> DesktopSocksProxySettings = { DesktopSocksProxySettings() }

    fun currentSettings(): DesktopSocksProxySettings = settings()

    fun create(onOutput: (String) -> Unit): LanCore = factory(onOutput)

    /** sing-box, as LAN sharing has always run. */
    fun go(onOutput: (String) -> Unit): LanCore = GoLanCore(onOutput)

    /**
     * ghostlane-engine, whose LAN inbound is in the session's own core: no
     * second program is started and no port is bound here. [binary] names the
     * program for the Windows firewall rule; it is never run by this core.
     */
    fun rust(
        onOutput: (String) -> Unit = {},
        binary: () -> Path = HostAssets::resolve,
    ): LanCore = RustLanCore(onOutput, binary)
}

private class GoLanCore(onOutput: (String) -> Unit) : LanCore {
    private val controller = DesktopSingBoxController(onOutput = onOutput)

    override val name = "sing-box"
    override val ownsListener = true

    override suspend fun start(config: String) = controller.start(config)
    override fun isRunning() = controller.isRunning()
    override fun stopNow() = controller.stopNow()
    override fun program(): Path = DesktopNativeAssets.resolveSingBoxBinary()
}

private class RustLanCore(
    @Suppress("UNUSED_PARAMETER") private val onOutput: (String) -> Unit,
    private val binary: () -> Path,
) : LanCore {
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
    private val core: LanCore = LanCores.create(onOutput),
    settings: () -> DesktopSocksProxySettings = LanCores.settings,
    private val availableAddresses: () -> List<String> = Companion::privateAddresses,
    private val readNetworkIdentity: (String) -> String? = Companion::networkIdentity,
) {
    private var firewallRule: String? = null

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
        // Both the Go listener and the Rust line's plan refuse the same shape.
        val plan = CoreInbound.Plan(
            CoreInbound.Proxy(upstream.port),
            CoreInbound.Lan(
                normalized.lanAddress, normalized.lanPort,
                SocksLogin(normalized.lanUsername, normalized.lanPassword),
            ),
        )
        CoreInbound.refusal(plan)?.let { throw IllegalArgumentException(it) }

        try {
            if (DesktopPaths.os == DesktopOs.Windows && core.ownsListener) cleanupStaleWindowsFirewallRules()
            if (core.ownsListener) {
                requirePortFree(host, normalized.lanPort)
                val ownershipPort = allocateLoopbackPort()
                val ownershipUsername = DesktopSocksProxySettings.randomToken(16)
                val ownershipPassword = DesktopSocksProxySettings.randomToken(32)
                core.start(
                    config(
                        host, normalized.lanPort, normalized.lanUsername, normalized.lanPassword, upstream,
                        ownershipPort, ownershipUsername, ownershipPassword
                    )
                )
                // Not withTimeout: its timeout is a CancellationException, and the
                // callers read that as "the start was superseded". A connect with LAN
                // sharing on was then taken down without a word and its status left
                // at Stopping, because a listener was slow to open. As an ordinary
                // failure it costs LAN sharing and nothing else.
                withTimeoutOrNull(5_000) {
                    while (true) {
                        check(core.isRunning()) { "LAN SOCKS listener exited; check the port and interface" }
                        if (runCatching {
                                Socket().use { it.connect(InetSocketAddress("127.0.0.1", ownershipPort), 200) }
                            }.isSuccess
                        ) break
                        delay(100)
                    }
                } ?: error("LAN SOCKS listener did not open in time; check the port and interface")
                // This private, random loopback inbound exists in the same config as
                // the LAN listener. If the requested LAN port belonged to another
                // process, the core could not bind the config and this check cannot pass.
                TunnelVerifier.verify(
                    socksHost = "127.0.0.1",
                    socksPort = ownershipPort,
                    username = ownershipUsername,
                    password = ownershipPassword,
                    timeoutMs = 5_000
                ) ?: error("LAN SOCKS ownership check did not reach the VPN")
            } else {
                // The line's core is already listening on this port. Binding it
                // here too, or starting a second engine chained by SOCKS, is the
                // design D5 dropped: it would race the session's own core.
                core.start("")
            }
            if (DesktopPaths.os == DesktopOs.Windows) addWindowsFirewallRule(host, normalized.lanPort, core.program())
            return "$host:${normalized.lanPort}"
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        core.stopNow()
        firewallRule?.let { rule ->
            runCatching { firewall("Remove-NetFirewallRule -Name '$rule' -ErrorAction SilentlyContinue") }
                .onFailure { onOutput("LAN sharing: could not remove its Windows firewall rule") }
            firewallRule = null
        }
    }

    fun isRunning(): Boolean = core.isRunning()

    fun cleanupStaleFirewallRules() {
        if (DesktopPaths.os == DesktopOs.Windows) cleanupStaleWindowsFirewallRules()
    }

    private fun addWindowsFirewallRule(host: String, port: Int, program: Path) {
        val rule = "Ghostlane-LAN-${java.util.UUID.randomUUID()}"
        val path = program.toAbsolutePath().normalize().toString()
        val script = "New-NetFirewallRule -Name '$rule' -DisplayName 'Ghostlane LAN SOCKS5' " +
            "-Direction Inbound -Action Allow -Protocol TCP -LocalAddress '$host' -LocalPort $port " +
            "-RemoteAddress LocalSubnet -Profile Private -Program '${path.replace("'", "''")}' | Out-Null"
        // Keep the name before executing: PowerShell may create the rule and time out afterwards.
        firewallRule = rule
        firewall(script)
    }

    private fun cleanupStaleWindowsFirewallRules() {
        firewall(
            "Get-NetFirewallRule -Name 'Ghostlane-LAN-*' -ErrorAction SilentlyContinue | " +
                "Remove-NetFirewallRule -ErrorAction Stop"
        )
    }

    private fun firewall(script: String) {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "\$ErrorActionPreference = 'Stop'; $script"
        ).redirectErrorStream(true).start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Firewall command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.exitValue() == 0) {
            output.ifBlank { "Firewall command needs administrator privileges" }
        }
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

        /** What the Go cores' listener is given; the Rust line's core writes CoreInbound itself. */
        fun config(
            host: String,
            port: Int,
            username: String,
            password: String,
            upstream: SubscriptionFetchProxy,
            ownershipPort: Int? = null,
            ownershipUsername: String = "",
            ownershipPassword: String = ""
        ): String {
            require(port in DesktopSocksProxySettings.MIN_PORT..DesktopSocksProxySettings.MAX_PORT && port != upstream.port) {
                "LAN port must differ from the core port"
            }
            require(username.isNotBlank() && password.isNotBlank()) { "LAN credentials are required" }
            require(username.length <= DesktopSocksProxySettings.MAX_CREDENTIAL_LENGTH)
            require(password.length <= DesktopSocksProxySettings.MAX_CREDENTIAL_LENGTH)
            require(ownershipPort == null || ownershipUsername.isNotBlank() && ownershipPassword.isNotBlank())
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 })
            require(parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168))
            val base = Json.parseToJsonElement(
                SingBoxConfig.buildSocksChain(
                    upstreamPort = upstream.port,
                    socksPort = port,
                    username = upstream.username,
                    password = upstream.password
                )
            ).jsonObject
            return JsonObject(base + ("inbounds" to buildJsonArray {
                addJsonObject {
                    put("type", "socks")
                    put("tag", "lan-in")
                    put("listen", host)
                    put("listen_port", port)
                    put("users", buildJsonArray {
                        addJsonObject {
                            put("username", username)
                            put("password", password)
                        }
                    })
                }
                if (ownershipPort != null) addJsonObject {
                    put("type", "socks")
                    put("tag", "owner-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", ownershipPort)
                    put("users", buildJsonArray {
                        addJsonObject {
                            put("username", ownershipUsername)
                            put("password", ownershipPassword)
                        }
                    })
                }
            })).toString()
        }
    }
}
