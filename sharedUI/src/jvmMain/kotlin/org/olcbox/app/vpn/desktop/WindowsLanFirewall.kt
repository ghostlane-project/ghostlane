package org.olcbox.app.vpn.desktop

import java.nio.file.Path
import java.util.UUID

/**
 * The Windows firewall's one inbound rule for LAN sharing: it lets the engine
 * (the program the rule names) take TCP on the shared address and port from
 * the Private network's own subnet, and nothing else.
 *
 * The rule is named `Ghostlane-LAN-<id>`. It is removed at stop, and [sweep]
 * removes every rule of that name prefix, which is what a start does first so
 * a rule a killed app left is not there for the next run to find open.
 *
 * [run] runs one PowerShell script and returns what it printed or throws.
 */
internal class WindowsLanFirewall(
    private val run: (script: String) -> String = { windowsPowerShellBlocking(it, timeoutSeconds = 8) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The rule this object added, kept before the script runs: PowerShell can create it and time out after. */
    var rule: String? = null
        private set

    /** Adds the rule for [program] on [host]:[port] and returns its name. */
    fun add(host: String, port: Int, program: Path): String {
        val name = PREFIX + newId()
        val script = addScript(name, host, port, program)
        rule = name
        run(script)
        return name
    }

    /** Removes the rule this object added. False when it could not: the name is kept for [sweep]. */
    fun remove(): Boolean {
        val name = rule ?: return true
        return runCatching { run(removeScript(name)) }.isSuccess.also { if (it) rule = null }
    }

    /** Removes every rule of ours, whoever added it. */
    fun sweep() {
        run(sweepScript())
        rule = null
    }

    fun exists(name: String): Boolean = run(existsScript(name)).trim() == "yes"

    /** The program the named rule is for. */
    fun programOf(name: String): String = run(programScript(name)).trim()

    internal companion object {
        const val PREFIX = "Ghostlane-LAN-"
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

        fun addScript(name: String, host: String, port: Int, program: Path): String {
            require(IPV4.matches(host)) { "The LAN address is not an IPv4 address: $host" }
            require(port in 1..65535) { "The LAN port is not a port: $port" }
            val path = program.toAbsolutePath().normalize().toString()
            return "\$ErrorActionPreference = 'Stop'; New-NetFirewallRule -Name ${name.powershellString()} " +
                "-DisplayName 'Ghostlane LAN SOCKS5' -Direction Inbound -Action Allow -Protocol TCP " +
                "-LocalAddress ${host.powershellString()} -LocalPort $port -RemoteAddress LocalSubnet " +
                "-Profile Private -Program ${path.powershellString()} | Out-Null"
        }

        fun removeScript(name: String): String =
            "\$ErrorActionPreference = 'Stop'; Remove-NetFirewallRule -Name ${name.powershellString()} -ErrorAction SilentlyContinue"

        fun sweepScript(): String =
            "\$ErrorActionPreference = 'Stop'; Get-NetFirewallRule -Name '$PREFIX*' -ErrorAction SilentlyContinue | " +
                "Remove-NetFirewallRule -ErrorAction Stop"

        fun existsScript(name: String): String =
            "if (Get-NetFirewallRule -Name ${name.powershellString()} -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }"

        fun programScript(name: String): String =
            "(Get-NetFirewallRule -Name ${name.powershellString()} | Get-NetFirewallApplicationFilter).Program"
    }
}
