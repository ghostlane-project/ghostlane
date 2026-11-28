package org.olcbox.app.vpn.desktop

/**
 * No name leaves the machine outside the tunnel while it is up.
 *
 * Two pieces, both ours and both named so that they can be found again:
 *  - a catch-all rule in the name resolution policy table (NRPT) sends every
 *    name to the tunnel's resolver [resolver], which hev answers itself
 *    (mapdns). The table outranks the resolvers of the adapters, so the DNS
 *    Client service does not ask the physical adapter's resolver in parallel,
 *    which is how a name leaves a machine whose default route is a tunnel;
 *  - Windows Firewall rules that block the DNS Client service (the one
 *    svchost process that asks for the system) from every resolver but the
 *    tunnel's, on port 53 and 853, over UDP and TCP, IPv4 and IPv6. The
 *    engine is another program and keeps its own way out: it asks direct names
 *    through its own sockets, bound to the physical interface.
 *
 * What a program does on its own, with its own resolver address, follows the
 * default route into the tunnel like any packet. The guard is written in the
 * journal before it is made, and [remove] and the sweeper take it away by
 * name, so a kill leaves nothing that the next start cannot find.
 */
internal class WindowsDnsGuard(
    private val journal: WindowsTunJournal,
    private val addLog: (String) -> Unit = {},
    private val run: suspend (String) -> String = { windowsPowerShell(it, timeoutSeconds = 90) },
) {
    suspend fun apply(tunAdapter: String, resolver: String) {
        journal.update { it.copy(guard = true) }
        run(applyScript(tunAdapter, resolver))
        addLog("Windows TUN: the DNS guard is in place (names go to $resolver in the tunnel)")
    }

    suspend fun remove() {
        run(removeScript())
        journal.update { it.copy(guard = false) }
    }

    companion object {
        const val RULE_PREFIX = "Ghostlane-DNS"
        const val NRPT_NAME = "Ghostlane-DNS"

        /** The addresses a resolver may not be at: everything but [resolver], as ranges. */
        fun otherThan(resolver: String): List<String> {
            val octets = resolver.split('.').map { it.toLong() }
            require(octets.size == 4 && octets.all { it in 0..255 }) { "not an IPv4 address: $resolver" }
            val n = octets.fold(0L) { acc, o -> acc * 256 + o }
            fun text(v: Long) = "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"
            return buildList {
                if (n > 0) add("0.0.0.0-${text(n - 1)}")
                if (n < 0xFFFFFFFFL) add("${text(n + 1)}-255.255.255.255")
            }
        }

        const val ALL_IPV6 = "::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"

        fun applyScript(tunAdapter: String, resolver: String): String {
            val v4 = otherThan(resolver).joinToString(",") { it.powershellString() }
            return psScript(
                buildString {
                    appendLine("§ErrorActionPreference = 'Stop'")
                    append(removeLines(announce = false))
                    appendLine("§ErrorActionPreference = 'Stop'")
                    appendLine("§svchost = Join-Path §env:SystemRoot 'System32\\svchost.exe'")
                    for (proto in listOf("UDP", "TCP")) {
                        for ((family, addresses) in listOf("4" to "@($v4)", "6" to "@('$ALL_IPV6')")) {
                            appendLine(
                                "New-NetFirewallRule -Name '$RULE_PREFIX-Cache-$proto-$family' " +
                                    "-DisplayName 'Ghostlane DNS guard ($proto, IPv$family)' -Group 'Ghostlane DNS guard' " +
                                    "-Direction Outbound -Action Block -Protocol $proto -RemotePort 53,853 " +
                                    "-RemoteAddress $addresses -Program §svchost -Service Dnscache -Profile Any | Out-Null"
                            )
                        }
                    }
                    appendLine(
                        "Add-DnsClientNrptRule -Namespace '.' -NameServers ${resolver.powershellString()} " +
                            "-DisplayName '$NRPT_NAME' -Comment 'Ghostlane tunnel: every name is answered in the tunnel' | Out-Null"
                    )
                    appendLine(
                        "Set-DnsClientServerAddress -InterfaceAlias ${tunAdapter.powershellString()} " +
                            "-ServerAddresses ${resolver.powershellString()}"
                    )
                    appendLine("Clear-DnsClientCache")
                }
            )
        }

        fun removeScript(): String = psScript(
            "§ErrorActionPreference = 'SilentlyContinue'\n" + removeLines(announce = false) + "Clear-DnsClientCache\n"
        )

        /** The lines that take the guard away by name; with [announce] each prints what it took. */
        fun removeLines(announce: Boolean): String = buildString {
            val say = { what: String -> if (announce) "; \"$what\"" else "" }
            appendLine("§ErrorActionPreference = 'SilentlyContinue'")
            appendLine(
                "foreach (§r in @(Get-NetFirewallRule -Name '$RULE_PREFIX-*')) { " +
                    "Remove-NetFirewallRule -Name §r.Name${say("firewall §(§r.Name)")} }"
            )
            appendLine(
                "foreach (§x in @(Get-DnsClientNrptRule | Where-Object { §_.DisplayName -eq '$NRPT_NAME' })) { " +
                    "Remove-DnsClientNrptRule -Name §x.Name -Force${say("name rule §(§x.Name)")} }"
            )
        }
    }
}
