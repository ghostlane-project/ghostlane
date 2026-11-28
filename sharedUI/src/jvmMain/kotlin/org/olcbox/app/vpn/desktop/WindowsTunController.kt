package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.system.exitProcess

/** How the machine leaves now: the interface of its default route and the next hop. */
internal data class PhysicalRoute(val alias: String, val index: Int, val gateway: String)

/** What the hev tunnel is told. */
internal data class WindowsTunPlan(
    val socksPort: Int,
    val spared: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    val dnsGuard: Boolean = true,
    val adapter: String = WindowsTunController.HEV_ADAPTER,
    /** The adapter's own address; another tunnel's adapter beside it (a check's) needs a different one. */
    val address: String = WindowsTunController.HEV_ADDRESS,
)

/** What a move of the way out does to the spared routes: [keep] stays, [remove] goes, [add] is laid. */
internal data class SparedMove(
    val remove: List<WindowsRouteRecord>,
    val add: List<WindowsRouteRecord>,
    val keep: List<WindowsRouteRecord>,
)

internal class WindowsTunController(
    private val addLog: (String) -> Unit,
    internal val journal: WindowsTunJournal = WindowsTunJournal(Path.of("windows-tun.json")),
) {
    private val sweeper = WindowsSweeper(journal, addLog)
    private val guard = WindowsDnsGuard(journal, addLog)

    @Volatile
    private var running: Process? = null

    /** The way out the spared routes are laid on now; null with no tunnel up. */
    @Volatile
    private var physical: PhysicalRoute? = null
    private val moveLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The interface the machine leaves by, with the address of its next hop.
     * Asked before the tunnel's routes are in: afterwards the tunnel's own
     * adapter is not counted (the filter names ours and the old tun's).
     */
    suspend fun physicalRoute(): PhysicalRoute =
        parsePhysicalRoute(windowsPowerShell(psScript(PHYSICAL_ROUTE_SCRIPT), timeoutSeconds = 60).trim().lines().last())

    /**
     * Brings the tunnel up: what an earlier run left is swept first, then hev
     * makes its Wintun adapter, the default route is split onto it with each of
     * [WindowsTunPlan.spared] left on the physical way, and the DNS guard goes
     * in. Everything made outside the adapter is in the journal before it is
     * made; a failure part way undoes what was done and throws.
     *
     * [hev] is hev.exe with wintun.dll beside it.
     */
    suspend fun startTunnel(hev: Path, plan: WindowsTunPlan): Process {
        sweep()
        val physical = physicalRoute()
        val spared = plan.spared.map { address ->
            require(IPV4.matches(address)) { "a spared address is an IPv4 address: $address" }
            WindowsRouteRecord("$address/32", physical.index, physical.gateway)
        }
        journal.write(WindowsTunRecord(adapter = plan.adapter, hev = hev.toAbsolutePath().toString(), routes = spared))
        val directory = journal.file.toAbsolutePath().parent
        val config = directory.resolve("hev.yml")
        val log = directory.resolve("hev.log")
        withContext(Dispatchers.IO) { java.nio.file.Files.writeString(config, hevConfig(plan)) }
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(hev.toAbsolutePath().toString(), config.toString())
                .directory(hev.toAbsolutePath().parent.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        }
        running = process
        this.physical = physical
        journal.update { it.copy(hevPid = process.pid()) }
        try {
            windowsPowerShell(adapterUpScript(plan), timeoutSeconds = 90)
            check(process.isAlive) { "hev ended" }
            windowsPowerShell(routesUpScript(plan, physical, spared), timeoutSeconds = 60)
            if (plan.dnsGuard) guard.apply(plan.adapter, MAPDNS_ADDRESS)
            check(ownsDefaultRoutes(plan.adapter)) { "the adapter ${plan.adapter} does not carry the default route" }
        } catch (failure: Throwable) {
            val tail = runCatching { java.nio.file.Files.readString(log).takeLast(1500) }.getOrDefault("")
            addLog("Windows TUN: the tunnel did not come up: ${failure.message}\n$tail")
            withContext(kotlinx.coroutines.NonCancellable) { stopTunnel() }
            throw failure
        }
        addLog("Windows TUN: the tunnel is up on ${plan.adapter}, leaving by ${physical.alias}")
        return process
    }

    /** Takes down what [startTunnel] made. May be called with nothing up. */
    suspend fun stopTunnel() {
        running?.destroyForcibly()
        running = null
        physical = null
        sweep()
        forgetTunnelAnswers()
    }

    /**
     * The machine now leaves by another interface: the spared /32 routes are
     * taken from the old one and laid again on the way out as it reads now
     * (its gateway and its interface index). [addresses] are the line's
     * addresses read again; without them, or with none usable, the same
     * addresses are kept. The journal names a new route before it is made, so
     * a stop or a sweep after a failure part way leaves none on the old
     * interface. Null with no tunnel up.
     */
    suspend fun followWayOut(addresses: List<String>? = null): SparedMove? = moveLock.withLock {
        if (running == null) return@withLock null
        val record = journal.read() ?: return@withLock null
        val now = physicalRoute()
        val move = planSparedMove(record.routes, now, addresses)
        if (move.remove.isNotEmpty() || move.add.isNotEmpty()) {
            journal.update { it.copy(routes = it.routes + move.add) }
            windowsPowerShell(moveSparedScript(move, now), timeoutSeconds = 60)
            journal.update { it.copy(routes = move.keep + move.add) }
            addLog("Windows TUN: the spared routes follow the way out to ${now.alias} (${now.gateway}): " +
                "${move.remove.size} taken, ${move.add.size} laid")
        }
        physical = now
        move
    }

    /** What an earlier run left, taken away; what was taken, one line each. */
    suspend fun sweep(): List<String> = sweeper.sweep()

    suspend fun ensureAdministratorOrRequestRestart() {
        if (isAdministrator()) return

        addLog("Requesting Windows administrator privileges for TUN mode")
        requestAdministratorRestart()
        exitProcess(0)
    }

    suspend fun physicalInterface(): String = runPowerShell("""
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        ${'$'}route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' |
          Where-Object {
            ${'$'}_.InterfaceAlias -ne '$TUN_NAME' -and
            ${'$'}_.InterfaceAlias -notlike 'Ghostlane-*'
          } |
          Sort-Object @{Expression={ ${'$'}_.RouteMetric + (Get-NetIPInterface -InterfaceIndex ${'$'}_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric }} |
          Select-Object -First 1
        if (${'$'}null -eq ${'$'}route) { throw 'No physical IPv4 default route' }
        ${'$'}route.InterfaceAlias
    """.trimIndent()).trim().also { require(it.isNotBlank()) { "No physical interface" } }

    /**
     * Whether sing-box's auto-route has taken the machine's IPv4 traffic onto
     * the tun's adapter ([coversDefaultRoute] says what counts as that).
     */
    suspend fun ownsDefaultRoutes(interfaceName: String): Boolean = runCatching {
        coversDefaultRoute(
            runPowerShell("""
                ${'$'}ErrorActionPreference = 'Stop'
                Get-NetRoute -AddressFamily IPv4 -InterfaceAlias ${interfaceName.powershellLiteral()} |
                  Select-Object -ExpandProperty DestinationPrefix
            """.trimIndent()).lines()
        )
    }.getOrDefault(false)

    /**
     * Empties the system's resolver cache, for when a tun has gone.
     *
     * While it was up the tun answered names with addresses that mean
     * something only to it (the tun's fake range), each good for ten
     * minutes by the tun's count. Left in the cache they are handed out
     * after the tun has gone, to connections nothing takes. Whether Windows
     * drops them by itself when the adapter goes was not established, so they
     * are dropped here. Not waited for: the stop this belongs to is not held up.
     */
    fun forgetTunnelAnswers() {
        runCatching {
            ProcessBuilder(flushResolverCacheCommand())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { addLog("Windows TUN: the resolver cache was not emptied: ${it.message}") }
    }

    private suspend fun isAdministrator(): Boolean {
        val isAdmin = runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)

        return isAdmin
    }

    private suspend fun requestAdministratorRestart() {
        val processInfo = ProcessHandle.current().info()
        val currentCommand = processInfo.command().orElse(null)
            ?: error("Ghostlane cannot resolve its Windows launcher for administrator restart")
        val currentArguments = processInfo.arguments().orElse(emptyArray()).toList()
        val restartArguments = if (ELEVATED_START_ARGUMENT in currentArguments) {
            currentArguments
        } else {
            currentArguments + ELEVATED_START_ARGUMENT
        }

        runPowerShell(
            restartAsAdministratorScript(
                command = currentCommand,
                arguments = restartArguments,
                workingDirectory = System.getProperty("user.dir").orEmpty()
            )
        )
    }

    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("PowerShell failed with code $exitCode: $output")
        }
        output
    }

    internal companion object {
        /**
         * The way out, as `physicalRoute` asks for it and the interface watch
         * asks again: the IPv4 default route with the lowest metric whose
         * adapter is neither the tunnel's (the old Olcbox name, nor
         * `Ghostlane-*`). The tunnel's own routes are two halves (/1), so
         * this is the same answer before and after the tunnel is up.
         */
        const val PHYSICAL_ROUTE_SCRIPT = """
            §ErrorActionPreference = 'Stop'
            §route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' |
              Where-Object { §_.InterfaceAlias -ne 'Olcbox' -and §_.InterfaceAlias -notlike 'Ghostlane-*' } |
              Sort-Object { §_.RouteMetric + (Get-NetIPInterface -InterfaceIndex §_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric } |
              Select-Object -First 1
            if (§null -eq §route) { throw 'No physical IPv4 default route' }
            (§route.InterfaceAlias, §route.InterfaceIndex, §route.NextHop) -join '|'
        """

        /**
         * One line for each IPv4 resolver list of an adapter that is up, the
         * adapter first: `alias|1.2.3.4,5.6.7.8`, by interface metric. The
         * adapters are not filtered here: [physicalDnsServers] drops the
         * tunnel's by name.
         */
        const val DNS_BY_ADAPTER_SCRIPT = """
            §ErrorActionPreference = 'Stop'
            Get-DnsClientServerAddress -AddressFamily IPv4 |
              Where-Object { §_.ServerAddresses.Count -gt 0 -and (Get-NetAdapter -InterfaceIndex §_.InterfaceIndex -ErrorAction SilentlyContinue).Status -eq 'Up' } |
              Sort-Object { (Get-NetIPInterface -InterfaceIndex §_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric } |
              ForEach-Object { §_.InterfaceAlias + '|' + (§_.ServerAddresses -join ',') }
        """

        /** The alias of [PHYSICAL_ROUTE_SCRIPT]'s answer, or null when the system gave none. */
        fun physicalAliasNow(): String? = runCatching {
            parsePhysicalRoute(windowsPowerShellBlocking(psScript(PHYSICAL_ROUTE_SCRIPT), timeoutSeconds = 12).lines().last()).alias
        }.getOrNull()

        /** Whether [alias] is an adapter of the tunnel: ours, or the old one's. */
        fun isTunAdapter(alias: String): Boolean =
            alias.equals(TUN_NAME, ignoreCase = true) || alias.startsWith("Ghostlane-", ignoreCase = true)

        /**
         * The resolvers of [DNS_BY_ADAPTER_SCRIPT]'s output without any that
         * belong to a tunnel adapter, by the adapter and not by the address:
         * the tunnel's resolver is whatever the tunnel names, 1.1.1.1 today.
         * In the order read, without repeats.
         */
        fun physicalDnsServers(output: String): List<String> =
            output.lines().map { it.trim() }.filter { '|' in it }
                .filterNot { isTunAdapter(it.substringBefore('|').trim()) }
                .flatMap { it.substringAfter('|').split(',') }
                .map { it.trim() }.filter { IPV4.matches(it) }.distinct()

        /** [physicalDnsServers] of the system now; null when it cannot be read. */
        fun physicalDnsServersNow(): List<String>? = runCatching {
            physicalDnsServers(windowsPowerShellBlocking(psScript(DNS_BY_ADAPTER_SCRIPT), timeoutSeconds = 20))
        }.getOrNull()

        const val TUN_NAME = "Olcbox"
        const val HEV_ADAPTER = "Ghostlane-Tun"
        const val HEV_ADDRESS = "198.18.0.1"
        const val HEV_MTU = 1500

        /** The resolver the tunnel names: hev answers it, and it is the only one the DNS guard lets the system ask. */
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        private val IPV4 = Regex("""(\d{1,3}\.){3}\d{1,3}""")

        fun parsePhysicalRoute(line: String): PhysicalRoute {
            val parts = line.trim().split('|')
            require(parts.size == 3 && parts[0].isNotBlank()) { "No physical interface: '$line'" }
            return PhysicalRoute(alias = parts[0], index = parts[1].toInt(), gateway = parts[2])
        }

        /** hev's file. In front of ghostlane-engine its datagrams go on the FWD UDP lane. */
        fun hevConfig(plan: WindowsTunPlan): String = buildString {
            appendLine("tunnel:")
            appendLine("  name: ${plan.adapter}")
            appendLine("  mtu: $HEV_MTU")
            appendLine("  ipv4: ${plan.address}")
            appendLine()
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: ${plan.socksPort}")
            if (plan.username.isNotBlank()) {
                appendLine("  username: '${plan.username.replace("'", "''")}'")
                appendLine("  password: '${plan.password.replace("'", "''")}'")
            }
            appendLine("  udp: '${LinuxTunController.Udp.InTcp.word}'")
            appendLine("  pipeline: false")
            appendLine()
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS_ADDRESS")
            appendLine("  port: 53")
            appendLine("  network: $MAPDNS_NETWORK")
            appendLine("  netmask: $MAPDNS_NETMASK")
            appendLine("  cache-size: 10000")
            appendLine()
            appendLine("misc:")
            appendLine("  task-stack-size: 24576")
            appendLine("  tcp-buffer-size: 4096")
            appendLine("  max-session-count: 1200")
            appendLine("  connect-timeout: 10000")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-file: stderr")
            appendLine("  log-level: warn")
        }

        /** Waits for hev's adapter to be listed with its address; throws when it is not within 30 s. */
        fun adapterUpScript(plan: WindowsTunPlan): String = psScript(
            """
            §ErrorActionPreference = 'SilentlyContinue'
            for (§i = 0; §i -lt 60; §i++) {
              if ((Get-NetAdapter -Name ${plan.adapter.powershellString()}) -and
                  (Get-NetIPAddress -InterfaceAlias ${plan.adapter.powershellString()} -AddressFamily IPv4 | Where-Object { §_.IPAddress -eq '${plan.address}' })) { 'up'; exit 0 }
              Start-Sleep -Milliseconds 500
            }
            §seen = (Get-NetAdapter | ForEach-Object { §_.Name + '=' + §_.Status }) -join ', '
            §addresses = (Get-NetIPAddress -AddressFamily IPv4 | ForEach-Object { §_.InterfaceAlias + ' ' + §_.IPAddress + ' ' + §_.AddressState }) -join '; '
            [Console]::Error.WriteLine('the adapter ${plan.adapter} did not come up in 30 s. Adapters: ' + §seen + '. Addresses: ' + §addresses)
            exit 1
            """
        )

        /**
         * The default route split in two halves on the adapter (a longer prefix
         * than the machine's own 0.0.0.0/0, so it wins without touching it),
         * IPv6 claimed the same way and carried nowhere, and a /32 on the
         * physical way for each address that must stay outside.
         */
        fun routesUpScript(plan: WindowsTunPlan, physical: PhysicalRoute, spared: List<WindowsRouteRecord>): String = psScript(
            buildString {
                val adapter = plan.adapter.powershellString()
                appendLine("§ErrorActionPreference = 'Stop'")
                appendLine("Set-NetIPInterface -InterfaceAlias $adapter -AddressFamily IPv4 -AutomaticMetric Disabled -InterfaceMetric 1")
                for (route in spared) appendLine(sparedRouteLine(route, physical))
                for (half in listOf("0.0.0.0/1", "128.0.0.0/1")) {
                    appendLine(
                        "New-NetRoute -DestinationPrefix '$half' -InterfaceAlias $adapter -NextHop '0.0.0.0' " +
                            "-RouteMetric 1 -PolicyStore ActiveStore | Out-Null"
                    )
                }
                appendLine(
                    "foreach (§half in '::/1', '8000::/1') { New-NetRoute -DestinationPrefix §half -InterfaceAlias $adapter " +
                        "-NextHop '::' -RouteMetric 1 -PolicyStore ActiveStore -ErrorAction SilentlyContinue | Out-Null }"
                )
            }
        )
        private fun sparedRouteLine(route: WindowsRouteRecord, physical: PhysicalRoute): String =
            "New-NetRoute -DestinationPrefix ${route.prefix.powershellString()} -InterfaceIndex ${route.interfaceIndex} " +
                "-NextHop ${physical.gateway.powershellString()} -RouteMetric 1 -PolicyStore ActiveStore | Out-Null"

        /** Whether [address] is in the range the tunnel's resolver hands out (it is no server's address). */
        fun isMapdnsAddress(address: String): Boolean {
            val octets = address.split('.').mapNotNull { it.toIntOrNull() }
            return octets.size == 4 && octets[0] == 100 && octets[1] in 64..127
        }

        /**
         * The spared routes for a move of the way out to [physical]: the
         * routes of [current] that are already that route stay; the others go;
         * each wanted address not yet on it is laid. The wanted addresses are
         * [addresses] (IPv4, never one of the tunnel's own answers), else the
         * ones [current] holds.
         */
        fun planSparedMove(current: List<WindowsRouteRecord>, physical: PhysicalRoute, addresses: List<String>?): SparedMove {
            val prefixes = addresses.orEmpty()
                .filter { IPV4.matches(it) && !isMapdnsAddress(it) }.map { "$it/32" }.distinct()
                .ifEmpty { current.map { it.prefix }.distinct() }
            val wanted = prefixes.map { WindowsRouteRecord(it, physical.index, physical.gateway) }
            val keep = current.filter { it in wanted }.distinct()
            return SparedMove(remove = current.filter { it !in keep }, add = wanted.filter { it !in keep }, keep = keep)
        }

        /** The old routes out first (a same-index one with another hop shares its key with the new), then the new. */
        fun moveSparedScript(move: SparedMove, physical: PhysicalRoute): String = psScript(
            buildString {
                appendLine("§ErrorActionPreference = 'Stop'")
                appendLine("function Step(§what, [scriptblock]§block) { try { & §block } catch { [Console]::Error.WriteLine(§what + ' failed: ' + §_.Exception.Message); exit 1 } }")
                for (route in move.remove) {
                    appendLine(
                        "Step ${("Remove-NetRoute " + route.prefix + " if " + route.interfaceIndex).powershellString()} { " +
                            "Remove-NetRoute -DestinationPrefix ${route.prefix.powershellString()} -InterfaceIndex ${route.interfaceIndex} " +
                            "-Confirm:§false -ErrorAction SilentlyContinue }"
                    )
                }
                for (route in move.add) {
                    appendLine(
                        "Step ${("New-NetRoute " + route.prefix + " if " + route.interfaceIndex + " via " + physical.gateway).powershellString()} { " +
                            sparedRouteLine(route, physical) + " }"
                    )
                }
            }
        )

        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        /**
         * Whether a line of the tun core's output says the system's filtering
         * engine refused `strict_route`. sing-box names the call that failed,
         * and each of them begins the same way (FwpmEngineOpen0 when the Base
         * Filtering Engine service is not running, FwpmSubLayerAdd0,
         * FwpmFilterAdd0). It then exits: on such a machine a tun that insists
         * on the filters does not start at all.
         *
         * One failure is not told from here: the lookup of the binary's own
         * application id, whose error sing-tun hands on without the call's
         * name. Taking every exit of the tun core for a refusal would cover
         * it, and would also drop the filters after an adapter that was only
         * slow to come up, which is the common reason for a second attempt.
         */
        fun filterEngineRefused(line: String): Boolean = "Fwpm" in line

        /**
         * By its full path: this process is elevated, and a bare name is
         * looked for in the working directory before the system's.
         */
        fun flushResolverCacheCommand(systemRoot: String? = System.getenv("SystemRoot")): List<String> {
            val windows = systemRoot?.trimEnd('\\')?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
            return listOf("$windows\\System32\\ipconfig.exe", "/flushdns")
        }

        fun restartAsAdministratorScript(
            command: String,
            arguments: List<String>,
            workingDirectory: String
        ): String {
            val quotedArguments = arguments
                .joinToString(separator = " ") { it.windowsCommandLineArgument() }
                .powershellLiteral()
            val workingDirectoryLine = workingDirectory
                .takeIf { it.isNotBlank() }
                ?.let { "  WorkingDirectory = ${it.powershellLiteral()}" }
                .orEmpty()

            return """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}startArgs = @{
                  FilePath = ${command.powershellLiteral()}
                  Verb = 'RunAs'
                  ArgumentList = $quotedArguments
                $workingDirectoryLine
                }
                Start-Process @startArgs | Out-Null
            """.trimIndent()
        }

        /**
         * Whether [prefixes], the IPv4 routes on the tun's adapter as
         * `Get-NetRoute` prints them, take the machine's traffic: together
         * they leave out no more than [spared] addresses of the whole space.
         *
         * Not "has `0.0.0.0/0`, or both halves of it", which is what this
         * asked before. That is what sing-box installs for a tun that leaves
         * nothing out. A tun told to leave an address out, and every line a
         * core carries has its server's address left out, gets the whole
         * space minus that address as the shortest list of prefixes that
         * makes it: thirty-two of them for one address, with one of the two
         * halves among them and never both, and no default route at all. The
         * old question could not be answered yes for such a tun, so its
         * session never passed its check however well it carried.
         *
         * What is left out is the servers' own addresses, a handful; the
         * limit is a /24's worth, far above that and far below what a tun
         * whose routes are not in yet leaves out, which is nearly everything.
         * Routes Windows puts on every adapter by itself (its own subnet,
         * multicast, broadcast) overlap the others and are counted once.
         */
        fun coversDefaultRoute(prefixes: List<String>, spared: Long = ROUTES_MAY_SPARE): Boolean {
            val ranges = prefixes.mapNotNull(::ipv4Range).sortedBy { it.first }
            var covered = 0L
            var reached = -1L
            for ((start, end) in ranges) {
                if (end <= reached) continue
                covered += end - maxOf(start, reached + 1) + 1
                reached = end
            }
            return IPV4_ADDRESSES - covered <= spared
        }

        /** `a.b.c.d/n` as its first and last address; null for anything else. */
        private fun ipv4Range(prefix: String): Pair<Long, Long>? {
            val address = prefix.trim().substringBefore('/')
            val bits = prefix.trim().substringAfter('/', "").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val octets = address.split('.').map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
            if (octets.size != 4) return null
            val value = octets.fold(0L) { acc, octet -> acc * 256 + octet }
            val size = 1L shl (32 - bits)
            val start = value / size * size
            return start to start + size - 1
        }

        private const val IPV4_ADDRESSES = 1L shl 32
        const val ROUTES_MAY_SPARE = 256L

        private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"

        private fun String.windowsCommandLineArgument(): String {
            if (isEmpty()) return "\"\""
            if (none { it.isWhitespace() || it == '"' }) return this

            val quoted = StringBuilder("\"")
            var pendingBackslashes = 0
            for (char in this) {
                when (char) {
                    '\\' -> pendingBackslashes++
                    '"' -> {
                        repeat(pendingBackslashes * 2 + 1) { quoted.append('\\') }
                        quoted.append(char)
                        pendingBackslashes = 0
                    }
                    else -> {
                        repeat(pendingBackslashes) { quoted.append('\\') }
                        pendingBackslashes = 0
                        quoted.append(char)
                    }
                }
            }
            repeat(pendingBackslashes * 2) { quoted.append('\\') }
            return quoted.append('"').toString()
        }
    }
}
