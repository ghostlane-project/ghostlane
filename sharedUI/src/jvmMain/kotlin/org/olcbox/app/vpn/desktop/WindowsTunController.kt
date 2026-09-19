package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false

    suspend fun start(
        tun2SocksBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT
    ): Process {
        ensureAdministratorOrRequestRestart()

        val process = ProcessBuilder(tun2SocksCommand(tun2SocksBinary, socksPort))
            .directory(tun2SocksBinary.parent.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            waitForAdapter(process)
            installRoutes()
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN partial route cleanup failed: ${it.message}") }
            routesInstalled = false
            stopProcess(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
        }

        stopProcess(process)
    }

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
          Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' } |
          Sort-Object @{Expression={ ${'$'}_.RouteMetric + (Get-NetIPInterface -InterfaceIndex ${'$'}_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric }} |
          Select-Object -First 1
        if (${'$'}null -eq ${'$'}route) { throw 'No physical IPv4 default route' }
        ${'$'}route.InterfaceAlias
    """.trimIndent()).trim().also { require(it.isNotBlank()) { "No physical interface" } }

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

    private suspend fun waitForAdapter(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("tun2socks exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }

            if (adapterExists()) return
            delay(TUN_READY_POLL_MS)
        }

        error("$TUN_NAME adapter was not created")
    }

    private suspend fun adapterExists(): Boolean {
        return runCatching {
            runPowerShell(
                """
                ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}adapter) { 'true' } else { 'false' }
                """.trimIndent()
            ).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    private suspend fun installRoutes() {
        runPowerShell(installRoutesScript())
    }

    private suspend fun removeRoutes() {
        runPowerShell(removeRoutesScript())
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

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "Olcbox"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val TUN_IPV4_PREFIX_LENGTH = 24

        /** The same ULA the macOS tun uses, so one address identifies our tunnel everywhere. */
        const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1"
        const val TUN_IPV6_PREFIX_LENGTH = 126
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        /**
         * The adapter takes an IPv6 address and the IPv6 half of the default
         * route, alongside the IPv4 ones it always took.
         *
         * Without them Windows keeps its IPv6 default on the physical NIC, and a
         * browser — which prefers IPv6 — reaches every dual-stack site outside the
         * tunnel at the machine's real address, while the tunnel looks perfectly
         * connected and a check against an IPv4-only service keeps reporting the
         * exit. macOS had exactly this and it went unnoticed until someone opened
         * a page that shows the address.
         *
         * `::/1` and `8000::/1` for the same reason as their IPv4 counterparts:
         * they beat `::/0` by longest prefix without deleting anyone's default
         * route, so the machine is left as it was found when the adapter goes.
         *
         * Windows has no blackhole route worth the name, so IPv6 is handed to
         * tun2socks rather than refused outright the way macOS refuses it. On a
         * node without IPv6 those connections fail on a timeout instead of at
         * once — slower than the reject, and still not a leak, which is the part
         * that matters.
         */
        fun installRoutesScript(): String = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction Stop
            ${'$'}ifIndex = ${'$'}adapter.ifIndex

            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetIPAddress -InterfaceIndex ${'$'}ifIndex -IPAddress '$TUN_IPV4_ADDRESS' -PrefixLength $TUN_IPV4_PREFIX_LENGTH -AddressFamily IPv4 | Out-Null

            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ServerAddresses '$MAPDNS_ADDRESS'

            # A machine or adapter with IPv6 disabled has none of this, and a
            # tunnel that refused to come up over that would trade a leak nobody
            # has for a tunnel nobody gets.
            try {
                Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv6 -ErrorAction SilentlyContinue |
                  Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV6_ADDRESS' } |
                  Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue

                New-NetIPAddress -InterfaceIndex ${'$'}ifIndex -IPAddress '$TUN_IPV6_ADDRESS' -PrefixLength $TUN_IPV6_PREFIX_LENGTH -AddressFamily IPv6 -ErrorAction Stop | Out-Null

                Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '::/1' -ErrorAction SilentlyContinue |
                  Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
                Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '8000::/1' -ErrorAction SilentlyContinue |
                  Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

                New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '::/1' -NextHop '::' -RouteMetric 1 -ErrorAction Stop | Out-Null
                New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '8000::/1' -NextHop '::' -RouteMetric 1 -ErrorAction Stop | Out-Null
            } catch {
                Write-Host "IPv6 not claimed on ${'$'}ifIndex : ${'$'}(${'$'}_.Exception.Message)"
            }
        """.trimIndent()

        fun removeRoutesScript(): String = """
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}adapter) { exit 0 }
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '::/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '8000::/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv6 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV6_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue
        """.trimIndent()

        fun tun2SocksCommand(
            tun2SocksBinary: Path,
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT
        ): List<String> = listOf(
            tun2SocksBinary.toString(),
            "--device",
            TUN_NAME,
            "--proxy",
            "socks5://${PacServer.LOCAL_SOCKS_HOST}:$socksPort",
            "--mtu",
            TUN_MTU.toString(),
            "--loglevel",
            "warn"
        )

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
