package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.UpstreamDns
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Hashtable
import java.util.concurrent.TimeUnit
import javax.naming.Context
import javax.naming.directory.InitialDirContext

internal object DesktopDnsResolver {
    const val FALLBACK_DNS_SERVER = "1.1.1.1:53"

    /**
     * The engine's resolver list (`net.dns`). Linux: the default interface's
     * server. macOS and Windows: the system's servers, then the public operator
     * ([UpstreamDns.list], Android's list), so a network that answers only its
     * own resolver is not first met with the public operators' silence.
     */
    fun current(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> currentLinuxDnsServer() ?: FALLBACK_DNS_SERVER
            DesktopOs.MacOS,
            DesktopOs.Windows -> UpstreamDns.list(systemServers())
            DesktopOs.Other -> FALLBACK_DNS_SERVER
        }
    }

    /**
     * The system's DNS servers as the JDK's own DNS provider reads them: on
     * Windows the servers of the adapters that are up (IP Helper), on macOS
     * `/etc/resolv.conf`, which configd writes from the primary resolver. The
     * provider reports them in its context's provider URL. No subprocess and no
     * localized output. Empty on any failure, a runtime without `jdk.naming.dns`
     * included, which leaves the public operator alone, as before.
     */
    internal fun systemServers(): List<String> = runCatching {
        val environment = Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
        }
        val context = InitialDirContext(environment)
        try {
            context.environment[Context.PROVIDER_URL] as? String
        } finally {
            context.close()
        }
    }.getOrNull()?.let(::providerUrlServers).orEmpty()

    /**
     * The addresses in a DNS provider URL list, `dns://192.168.1.1 dns://[fe80::1%en0]:53`,
     * in its order. A port is dropped (the engine asks port 53), and so is Windows'
     * `fec0::/10` placeholder on an adapter with no IPv6 server of its own.
     */
    internal fun providerUrlServers(url: String): List<String> =
        url.split(' ', '\t', ',')
            .map { it.trim().removePrefix("dns://").substringBefore('/') }
            .mapNotNull { authority ->
                val host = if (authority.startsWith('[')) {
                    authority.substringAfter('[').substringBefore(']')
                } else if (authority.count { it == ':' } == 1) {
                    authority.substringBefore(':')
                } else {
                    authority
                }
                ipLiteralOrNull(host)
            }
            .filterNot(::isSiteLocalIpv6)
            .distinct()

    private fun currentLinuxDnsServer(): String? {
        val defaultRouteOutput = runCommand(listOf("ip", "route", "show", "default")).orEmpty()
        val interfaceName = defaultRouteInterface(defaultRouteOutput)

        val resolvectlOutput = if (interfaceName != null) {
            runCommand(listOf("resolvectl", "dns", interfaceName))
        } else {
            runCommand(listOf("resolvectl", "dns"))
        }
        val nmcliOutput = interfaceName?.let {
            runCommand(listOf("nmcli", "-g", "IP4.DNS,IP6.DNS", "device", "show", it))
        }
        val resolvConf = runCatching {
            Files.readString(Path.of("/etc/resolv.conf"))
        }.getOrDefault("")

        return selectLinuxDnsServer(
            resolvectlOutput = resolvectlOutput.orEmpty(),
            nmcliOutput = nmcliOutput.orEmpty(),
            resolvConf = resolvConf
        )
    }

    private fun runCommand(command: List<String>): String? {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    internal fun defaultRouteInterface(output: String): String? {
        return output.lineSequence()
            .filter { it.trimStart().startsWith("default ") }
            .mapNotNull { line ->
                DEFAULT_ROUTE_DEVICE.find(line)?.groupValues?.getOrNull(1)
            }
            .firstOrNull { it != LinuxTunController.TUN_NAME }
    }

    internal fun selectLinuxDnsServer(
        resolvectlOutput: String,
        nmcliOutput: String,
        resolvConf: String
    ): String? {
        val candidates = buildList {
            addAll(ipAddresses(resolvectlOutput))
            addAll(ipAddresses(nmcliOutput))
            addAll(resolvConfNameservers(resolvConf))
        }.distinct()

        val selected = candidates.firstOrNull { !isLoopback(it) }
            ?: candidates.firstOrNull()
            ?: return null
        return dnsEndpoint(selected)
    }

    private fun ipAddresses(output: String): List<String> {
        return output.lineSequence()
            .flatMap { it.splitToSequence(Regex("\\s+")) }
            .mapNotNull(::ipLiteralOrNull)
            .toList()
    }

    private fun resolvConfNameservers(content: String): List<String> {
        return content.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("nameserver ") }
            .mapNotNull { line -> ipLiteralOrNull(line.substringAfter("nameserver ").trim()) }
            .toList()
    }

    private fun ipLiteralOrNull(token: String): String? {
        val candidate = token
            .trim()
            .trim(',', ';')
            .substringBefore('#')
        if (candidate.isEmpty() || ('.' !in candidate && ':' !in candidate)) return null

        val addressWithoutZone = candidate.substringBefore('%')
        val parsed = runCatching { InetAddress.getByName(addressWithoutZone) }.getOrNull()
            ?: return null
        if (':' in candidate && parsed.hostAddress?.contains(':') != true) return null
        if ('.' in candidate && ':' !in candidate && parsed.hostAddress?.contains('.') != true) return null
        return candidate
    }

    private fun isSiteLocalIpv6(address: String): Boolean = runCatching {
        val parsed = InetAddress.getByName(address.substringBefore('%'))
        parsed is Inet6Address && parsed.isSiteLocalAddress
    }.getOrDefault(false)

    private fun isLoopback(address: String): Boolean {
        return runCatching {
            InetAddress.getByName(address.substringBefore('%')).isLoopbackAddress
        }.getOrDefault(false)
    }

    private fun dnsEndpoint(address: String): String {
        return if (':' in address) "[$address]:53" else "$address:53"
    }

    private val DEFAULT_ROUTE_DEVICE = Regex("(?:^|\\s)dev\\s+(\\S+)")
    private const val COMMAND_TIMEOUT_SECONDS = 2L
}
