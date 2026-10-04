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

    /** sing-box auto-route must own either a default route or both split defaults. */
    suspend fun ownsDefaultRoutes(interfaceName: String): Boolean = runCatching {
        runPowerShell("""
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}routes = @(Get-NetRoute -AddressFamily IPv4 -InterfaceAlias ${interfaceName.powershellLiteral()} |
              Where-Object { ${'$'}_.DestinationPrefix -in @('0.0.0.0/0', '0.0.0.0/1', '128.0.0.0/1') } |
              Select-Object -ExpandProperty DestinationPrefix)
            if (${'$'}routes -contains '0.0.0.0/0' -or
                ((${ '$' }routes -contains '0.0.0.0/1') -and (${'$'}routes -contains '128.0.0.0/1'))) { 'true' } else { 'false' }
        """.trimIndent()).trim().equals("true", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Empties the system's resolver cache, for when a tun has gone.
     *
     * While it was up the tun answered names with addresses that mean
     * something only to it (SingBoxConfig's fake range), each good for ten
     * minutes by sing-box's count. Left in the cache they are handed out
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
        const val TUN_NAME = "Olcbox"
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        /**
         * Whether a line of the tun core's output says the system's filtering
         * engine refused `strict_route`. sing-box names the call that failed,
         * and each of them begins the same way (FwpmEngineOpen0 when the Base
         * Filtering Engine service is not running, FwpmSubLayerAdd0,
         * FwpmFilterAdd0). It then exits: on such a machine a tun that insists
         * on the filters does not start at all.
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
