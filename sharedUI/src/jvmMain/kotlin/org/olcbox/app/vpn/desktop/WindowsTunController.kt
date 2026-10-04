package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
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
        const val TUN_NAME = "Olcbox"
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

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
