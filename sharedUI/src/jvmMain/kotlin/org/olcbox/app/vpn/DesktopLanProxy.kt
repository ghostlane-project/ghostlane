package org.olcbox.app.vpn

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.vpn.desktop.DesktopNativeAssets

/** A credentialed SOCKS5 listener bound to one explicitly selected private adapter. */
internal class DesktopLanProxy(private val onOutput: (String) -> Unit) {
    private val core = DesktopSingBoxController(onOutput = onOutput)
    private var firewallRule: String? = null

    suspend fun start(settings: DesktopSocksProxySettings, upstream: SubscriptionFetchProxy): String {
        val normalized = settings.normalized()
        require(normalized.lanUsername.isNotBlank() && normalized.lanPassword.isNotBlank()) {
            "LAN sharing credentials are missing"
        }
        val available = privateAddresses()
        val host = normalized.lanAddress.takeIf { it in available }
            ?: error("The selected private network interface is no longer available")
        requirePortFree(host, normalized.lanPort)
        val ownershipPort = allocateLoopbackPort()
        val ownershipUsername = DesktopSocksProxySettings.randomToken(16)
        val ownershipPassword = DesktopSocksProxySettings.randomToken(32)

        try {
            if (DesktopPaths.os == DesktopOs.Windows) cleanupStaleWindowsFirewallRules()
            core.start(
                config(
                    host, normalized.lanPort, normalized.lanUsername, normalized.lanPassword, upstream,
                    ownershipPort, ownershipUsername, ownershipPassword
                )
            )
            withTimeout(5_000) {
                while (true) {
                    check(core.isRunning()) { "LAN SOCKS listener exited; check the port and interface" }
                    if (runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", ownershipPort), 200) }
                        }.isSuccess
                    ) break
                    delay(100)
                }
            }
            // This private, random loopback inbound exists in the same config as
            // the LAN listener. If the requested LAN port belonged to another
            // process, sing-box could not bind the config and this check cannot pass.
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

    private fun addWindowsFirewallRule(host: String, port: Int) {
        val rule = "Ghostlane-LAN-${java.util.UUID.randomUUID()}"
        val program = DesktopNativeAssets.resolveSingBoxBinary().toAbsolutePath().normalize().toString()
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
        fun privateAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.hardwareAddress != null }
            .filterNot { isVirtualAdapter("${it.name} ${it.displayName}") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { it.hostAddress }
            .distinct()
            .sortedBy(::ipv4SortKey)

        internal fun isVirtualAdapter(name: String): Boolean = name.contains(
            Regex(
                "(?i)wintun|wireguard|tun2socks|tap-windows|ghostlane|olcbox|" +
                    "docker|(^|[^a-z])br-|vbox|virtualbox|vmnet|vmware|vEthernet|wsl|hyper-v|" +
                    "tailscale|zerotier"
            )
        )

        private fun ipv4SortKey(address: String): Long = address.split('.')
            .fold(0L) { value, octet -> value * 256 + (octet.toLongOrNull() ?: 0L) }

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
