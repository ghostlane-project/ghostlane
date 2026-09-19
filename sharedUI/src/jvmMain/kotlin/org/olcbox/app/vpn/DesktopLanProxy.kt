package org.olcbox.app.vpn

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import kotlinx.serialization.json.*
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.SingBoxConfig

/** An opt-in LAN listener; internal core listeners and their credentials stay private. */
internal class DesktopLanProxy(private val onOutput: (String) -> Unit) {
    private val core = DesktopSingBoxController(onOutput = onOutput)
    private var firewallRule: String? = null

    suspend fun start(settings: DesktopSocksProxySettings, upstream: SubscriptionFetchProxy): String {
        val host = privateAddresses().firstOrNull() ?: error("No private LAN address is available")
        core.start(config(host, settings.lanPort, upstream))
        withTimeout(5_000) {
            while (true) {
                check(core.isRunning()) { "LAN SOCKS listener exited; check the port and interface" }
                if (runCatching { Socket().use { it.connect(InetSocketAddress(host, settings.lanPort), 200) } }.isSuccess) break
                delay(100)
            }
        }
        if (DesktopPaths.os == DesktopOs.Windows) {
            val rule = "Ghostlane-LAN-${java.util.UUID.randomUUID()}"
            val program = core.runningProcess()?.info()?.command()?.orElse(null) ?: error("LAN core exited")
            val script = "New-NetFirewallRule -Name '$rule' -DisplayName 'Ghostlane LAN SOCKS5' " +
                "-Direction Inbound -Action Allow -Protocol TCP -LocalAddress '$host' -LocalPort ${settings.lanPort} " +
                "-RemoteAddress LocalSubnet -Profile Private -Program '${program.replace("'", "''")}' | Out-Null"
            // Keep the name even on timeout: the command may have created the
            // rule before its process was interrupted, so stop must remove it.
            firewallRule = rule
            runCatching { firewall(script) }.onFailure {
                onOutput("LAN sharing: Windows firewall permission was not added. Allow this port on your private network if another device cannot connect.")
            }
        }
        return "$host:${settings.lanPort}"
    }

    fun stop() {
        core.stopNow()
        firewallRule?.let { rule ->
            runCatching { firewall("Remove-NetFirewallRule -Name '$rule' -ErrorAction SilentlyContinue") }
                .onFailure { onOutput("LAN sharing: could not remove firewall rule $rule") }
            firewallRule = null
        }
    }
    fun isRunning(): Boolean = core.isRunning()

    private fun firewall(script: String) {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "\$ErrorActionPreference = 'Stop'; $script").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("Firewall command timed out")
        }
        check(p.exitValue() == 0) { "Firewall command needs administrator privileges" }
    }

    companion object {
        fun privateAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.hardwareAddress != null }
            .filterNot { it.displayName.orEmpty().contains(Regex("(?i)wintun|wireguard|tun2socks|tap-windows")) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .map { it.hostAddress }.distinct().sorted()

        fun config(host: String, port: Int, upstream: SubscriptionFetchProxy): String {
            require(port in 1024..65535 && port != upstream.port) { "LAN port must differ from the core port" }
            // Only numeric RFC1918 addresses: never resolve a hostname or bind all
            // interfaces, which could expose the listener on a public adapter.
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 })
            require(parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168))
            val base = Json.parseToJsonElement(SingBoxConfig.buildSocksChain(
                upstreamPort = upstream.port, socksPort = port,
                username = upstream.username, password = upstream.password
            )).jsonObject
            return JsonObject(base + ("inbounds" to buildJsonArray {
                addJsonObject {
                    put("type", "socks"); put("tag", "lan-in")
                    put("listen", host); put("listen_port", port)
                }
            })).toString()
        }
    }
}
