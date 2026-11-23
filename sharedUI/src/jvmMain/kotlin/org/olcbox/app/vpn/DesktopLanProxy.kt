package org.olcbox.app.vpn

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.addInbounds
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.engine.host.HostAssets
import org.olcbox.app.vpn.desktop.engine.host.HostEvent
import org.olcbox.app.vpn.desktop.engine.host.HostEvents
import org.olcbox.app.vpn.desktop.engine.host.HostProcess
import org.olcbox.app.vpn.desktop.engine.host.HostSession
import java.nio.file.Path

/**
 * What runs LAN sharing's listener: a core with a SOCKS5 inbound on the
 * private address and a loopback one of its own, chained to the session's
 * port. One core belongs to one [DesktopLanProxy].
 */
internal interface LanCore {
    /** The program's name, for the log. */
    val name: String

    /** What the core is given: the LAN listener, the ownership listener and the chain to [upstream]. */
    fun config(
        host: String,
        port: Int,
        username: String,
        password: String,
        upstream: SubscriptionFetchProxy,
        ownershipPort: Int,
        ownershipUsername: String,
        ownershipPassword: String
    ): String

    /** Starts the core on [config] and returns once it is started; whether it serves is the caller's to prove. */
    suspend fun start(config: String)

    fun isRunning(): Boolean

    /** Ends the core and returns when it is gone. May be called more than once. */
    fun stopNow()

    /** The program, for the rule the Windows firewall is given. */
    fun program(): Path
}

/**
 * The cores LAN sharing can run on. Go stays the default; the engine is
 * chosen by setting [factory], beside the desktop engine's own.
 */
internal object LanCores {
    @Volatile
    var factory: (onOutput: (String) -> Unit) -> LanCore = { out -> go(out) }

    fun create(onOutput: (String) -> Unit): LanCore = factory(onOutput)

    /** sing-box, as LAN sharing has always run. */
    fun go(onOutput: (String) -> Unit): LanCore = GoLanCore(onOutput)

    /** ghostlane-engine, the program of the Core build, as a child of the app. */
    fun rust(
        onOutput: (String) -> Unit,
        binary: () -> Path = HostAssets::resolve,
        sessionParent: Path? = null
    ): LanCore = RustLanCore(onOutput, binary, sessionParent)
}

private class GoLanCore(onOutput: (String) -> Unit) : LanCore {
    private val controller = DesktopSingBoxController(onOutput = onOutput)

    override val name = "sing-box"

    override fun config(
        host: String, port: Int, username: String, password: String, upstream: SubscriptionFetchProxy,
        ownershipPort: Int, ownershipUsername: String, ownershipPassword: String
    ) = DesktopLanProxy.config(
        host, port, username, password, upstream, ownershipPort, ownershipUsername, ownershipPassword
    )

    override suspend fun start(config: String) = controller.start(config)
    override fun isRunning() = controller.isRunning()
    override fun stopNow() = controller.stopNow()
    override fun program(): Path = DesktopNativeAssets.resolveSingBoxBinary()
}

private class RustLanCore(
    private val onOutput: (String) -> Unit,
    private val binary: () -> Path,
    private val sessionParent: Path?
) : LanCore {
    @Volatile private var current: HostProcess? = null
    @Volatile private var checked: Path? = null

    override val name = "ghostlane-engine"

    override fun config(
        host: String, port: Int, username: String, password: String, upstream: SubscriptionFetchProxy,
        ownershipPort: Int, ownershipUsername: String, ownershipPassword: String
    ) = DesktopLanProxy.rustConfig(
        host, port, username, password, upstream, ownershipPort, ownershipUsername, ownershipPassword
    )

    override suspend fun start(config: String): Unit = withContext(Dispatchers.IO) {
        stopNow()
        val program = binary()
        if (checked != program) {
            val version = HostAssets.version(program)
                ?: error("$name printed no version: $program is not a program this app runs")
            HostAssets.refusal(version)?.let { error(it) }
            onOutput(HostAssets.describe(version))
            checked = program
        }
        val run = HostProcess.run(
            program,
            HostSession.text(config),
            onEvent = { event -> HostEvents.logLine(event)?.let { onOutput("$name (LAN): $it") } },
            onStderr = { text -> onOutput("$name (LAN): $text") },
            parent = sessionParent
        )
        current = run
        when (val start = run.awaitStart(START_WAIT_MS)) {
            is HostProcess.Start.Ended -> {
                run.stop()
                current = null
                val why = start.ended?.let { ended: HostEvent.Ended ->
                    listOfNotNull(ended.code?.word, ended.message).joinToString(": ").ifEmpty { ended.reason.word }
                } ?: "it exited with code ${start.exitCode}"
                error("LAN SOCKS listener exited; check the port and interface ($why)")
            }
            is HostProcess.Start.Ready, HostProcess.Start.Waiting -> Unit
        }
    }

    override fun isRunning() = current?.isAlive() == true

    override fun stopNow() {
        current?.stop()
        current = null
    }

    override fun program(): Path = binary()

    private companion object {
        /** How long a start waits for the engine to say it serves or failed; past it the caller's own wait decides. */
        const val START_WAIT_MS = 10_000L
    }
}

/** A credentialed SOCKS5 listener bound to one explicitly selected private adapter. */
internal class DesktopLanProxy(
    private val onOutput: (String) -> Unit,
    private val core: LanCore = LanCores.create(onOutput)
) {
    private var firewallRule: String? = null

    suspend fun start(settings: DesktopSocksProxySettings, upstream: SubscriptionFetchProxy): String {
        val normalized = settings.normalized()
        require(normalized.lanUsername.isNotBlank() && normalized.lanPassword.isNotBlank()) {
            "LAN sharing credentials are missing"
        }
        val available = privateAddresses()
        val host = normalized.lanAddress.takeIf { it in available }
            ?: error("The selected private network interface is no longer available")
        val currentNetworkId = networkIdentity(host)
            ?: error("The selected LAN gateway could not be identified")
        require(normalized.lanNetworkId.isNotBlank() && normalized.lanNetworkId == currentNetworkId) {
            "This is a different local network; select the LAN interface again before sharing"
        }
        requirePortFree(host, normalized.lanPort)
        val ownershipPort = allocateLoopbackPort()
        val ownershipUsername = DesktopSocksProxySettings.randomToken(16)
        val ownershipPassword = DesktopSocksProxySettings.randomToken(32)

        try {
            if (DesktopPaths.os == DesktopOs.Windows) cleanupStaleWindowsFirewallRules()
            core.start(
                core.config(
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
            if (DesktopPaths.os == DesktopOs.Windows) addWindowsFirewallRule(host, normalized.lanPort)
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

    private fun addWindowsFirewallRule(host: String, port: Int) {
        val rule = "Ghostlane-LAN-${java.util.UUID.randomUUID()}"
        val program = core.program().toAbsolutePath().normalize().toString()
        val script = "New-NetFirewallRule -Name '$rule' -DisplayName 'Ghostlane LAN SOCKS5' " +
            "-Direction Inbound -Action Allow -Protocol TCP -LocalAddress '$host' -LocalPort $port " +
            "-RemoteAddress LocalSubnet -Profile Private -Program '${program.replace("'", "''")}' | Out-Null"
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
         * What ghostlane-engine is given for LAN sharing (the core half of its
         * session file): the listener on [host], demanding the login, and one
         * on loopback demanding the ownership login, both leading to the
         * session's port. Throws IllegalArgumentException for a listener the
         * core would refuse ([CoreInbound.refusal]) and for a port that clashes.
         *
         * The inbounds are the ones the app's builder writes for the core
         * (`CoreInbound`), with the sniffing off: the session's own core
         * names a connection for its rules, and a second one here would only
         * hold a server that speaks first waiting for the client.
         */
        fun rustConfig(
            host: String,
            port: Int,
            username: String,
            password: String,
            upstream: SubscriptionFetchProxy,
            ownershipPort: Int,
            ownershipUsername: String,
            ownershipPassword: String
        ): String {
            require(port != upstream.port && port != ownershipPort) { "LAN port must differ from the core port" }
            require(ownershipPort != upstream.port) { "the ownership port must differ from the core port" }
            val plan = CoreInbound.Plan(
                CoreInbound.Proxy(ownershipPort, SocksLogin(ownershipUsername, ownershipPassword)),
                CoreInbound.Lan(host, port, SocksLogin(username, password))
            )
            CoreInbound.refusal(plan)?.let { throw IllegalArgumentException("the Rust core cannot listen so: $it") }
            val inbounds = buildJsonArray { addInbounds(plan) }.map { inbound ->
                JsonObject(inbound.jsonObject + ("sniffing" to buildJsonObject { put("enabled", false) }))
            }
            return buildJsonObject {
                putJsonObject("log") {
                    put("loglevel", "warning")
                    put("access", "none")
                }
                putJsonArray("inbounds") { inbounds.forEach { add(it) } }
                putJsonArray("outbounds") {
                    addJsonObject {
                        put("tag", CHAIN)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            put("address", upstream.host)
                            put("port", upstream.port)
                            if (upstream.username.isNotEmpty() && upstream.password.isNotEmpty()) {
                                put("user", upstream.username)
                                put("pass", upstream.password)
                            }
                        }
                    }
                }
                putJsonObject("routing") {
                    put("domainStrategy", "AsIs")
                    putJsonArray("rules") {
                        addJsonObject {
                            putJsonArray("inboundTag") {
                                add(CoreInbound.LAN_TAG)
                                add(CoreInbound.TAG)
                            }
                            put("outboundTag", CHAIN)
                        }
                    }
                }
            }.toString()
        }

        private const val CHAIN = "session"

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
            require(port in 1024..65535 && port != upstream.port) { "LAN port must differ from the core port" }
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
