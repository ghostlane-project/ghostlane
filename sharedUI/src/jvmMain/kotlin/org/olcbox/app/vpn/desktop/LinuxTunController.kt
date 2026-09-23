package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

internal class LinuxTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false

    suspend fun start(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT
    ): Process {
        val upScript = writeUpScript()
        val downScript = writeDownScript()
        val config = writeConfig(socksPort, upScript, downScript)
        val process = startPrivilegedProcess(listOf(hevBinary.toString(), config.toString()))
        try {
            waitForTunReady(process)
            routesInstalled = true
            addLog("Linux TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            stop(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        stopProcess(process)

        if (routesInstalled) {
            waitForRoutesRemoved()
        }
        if (routeRuleExists() || routeTableExists() || Files.exists(rpFilterStatePath())) {
            runCatching { runPrivilegedScript(writeDownScript()) }
                .onFailure { addLog("Linux TUN route cleanup failed: ${it.message}") }
        }
        routesInstalled = false
    }

    private fun writeConfig(socksPort: Int, upScript: Path, downScript: Path): Path {
        val config = DesktopPaths.appDataDir().resolve("linux-tun.yml")
        Files.writeString(
            config,
            configContent(
                socksPort = socksPort,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString()
            )
        )
        return config
    }

    private fun writeUpScript(): Path {
        return writeScript(
            name = "linux-tun-up.sh",
            body = upScriptContent(rpFilterStatePath().toString())
        )
    }

    private fun writeDownScript(): Path {
        return writeScript(
            name = "linux-tun-down.sh",
            body = downScriptContent(rpFilterStatePath().toString())
        )
    }

    private fun rpFilterStatePath(): Path {
        return DesktopPaths.appDataDir().resolve("linux-rp-filter.state")
    }

    private fun writeScript(name: String, body: String): Path {
        val script = DesktopPaths.appDataDir().resolve(name)
        Files.writeString(script, body)
        script.toFile().setExecutable(true, true)
        return script
    }

    private suspend fun waitForTunReady(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("hev-socks5-tunnel exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }
            if (interfaceExists() && routeRuleExists() && routeTableExists()) return
            delay(TUN_READY_POLL_MS)
        }
        error("$TUN_NAME routes were not installed")
    }

    private suspend fun interfaceExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", TUN_NAME)
                .redirectErrorStream(true)
                .start()
            process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private suspend fun routeRuleExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "rule", "show")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1, TimeUnit.SECONDS) &&
                    process.exitValue() == 0 &&
                    output.lineSequence().any { line ->
                        val trimmed = line.trim()
                        (
                            trimmed.startsWith("$TUN_RULE_PREF:") ||
                                trimmed.contains("pref $TUN_RULE_PREF")
                            ) &&
                            trimmed.contains("lookup $ROUTE_TABLE")
                    }
        }.getOrDefault(false)
    }

    private suspend fun routeTableExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "route", "show", "table", ROUTE_TABLE)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1, TimeUnit.SECONDS) &&
                    process.exitValue() == 0 &&
                    output.lineSequence().any { line ->
                        line.trim().startsWith("default dev $TUN_NAME")
                    }
        }.getOrDefault(false)
    }

    private suspend fun waitForRoutesRemoved() {
        val deadline = System.currentTimeMillis() + ROUTE_CLEANUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!routeRuleExists()) return
            delay(TUN_READY_POLL_MS)
        }
    }

    private suspend fun runPrivilegedScript(script: Path) {
        runPrivilegedCommand(listOf(script.toString()))
    }

    private suspend fun runPrivilegedCommand(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("${command.joinToString(" ")} failed with code $exitCode: $output")
        }
        output
    }

    private fun startPrivilegedProcess(command: List<String>): Process {
        return ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
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
        const val TUN_NAME = "olcbox0"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val ROUTE_TABLE = "51820"
        const val ROOT_BYPASS_RULE_PREF = "10"
        const val TUN_RULE_PREF = "20"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        fun configContent(
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            postUpScript: String? = null,
            preDownScript: String? = null
        ): String {
            return buildString {
                appendLine("tunnel:")
                appendLine("  name: $TUN_NAME")
                appendLine("  mtu: $TUN_MTU")
                appendLine("  multi-queue: false")
                appendLine("  ipv4: $TUN_IPV4_ADDRESS")
                if (!postUpScript.isNullOrBlank()) {
                    appendLine("  post-up-script: $postUpScript")
                }
                if (!preDownScript.isNullOrBlank()) {
                    appendLine("  pre-down-script: $preDownScript")
                }
                appendLine()
                appendLine("socks5:")
                appendLine("  address: ${PacServer.LOCAL_SOCKS_HOST}")
                appendLine("  port: $socksPort")
                // Standard UDP ASSOCIATE. 'tcp' is hev's own UDP-in-TCP command (0x05),
                // which none of the servers on this port speaks: sing-box, Xray and the
                // olcRTC engine refuse it, so every UDP flow died at its first packet
                // (the Android fix, HevTunnelConfig, has the details).
                appendLine("  udp: 'udp'")
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
            }.trimEnd()
        }

        /**
         * IPv6 is claimed and blackholed, not carried.
         *
         * Claimed because installing IPv4 rules alone leaves the machine's IPv6
         * default route on the physical interface, and a browser — which prefers
         * IPv6 — then reaches every dual-stack site outside the tunnel at the
         * real address, while the tunnel looks perfectly connected and a check
         * against an IPv4-only service keeps reporting the exit. That is not a
         * theory: it is what macOS did until it was fixed, on the same shape.
         *
         * Blackholed rather than relayed because whether the far end has working
         * IPv6 is a property of each operator's node, not of this script. A
         * blackhole answers "unreachable" at once and the caller falls back to
         * IPv4 in milliseconds; relaying into a node without IPv6 would hang for
         * a timeout and arrive at the same place.
         *
         * Root keeps its direct path for IPv6 as it does for IPv4 — the core runs
         * as root here, and without that rule it could not reach a server that
         * only answers over IPv6.
         *
         * Every `ip -6` line tolerates failure: a kernel built without IPv6 has
         * no such tables, and refusing to bring the tunnel up over that would
         * trade a leak nobody has for a tunnel nobody gets.
         */
        fun upScriptContent(
            rpFilterStatePath: String = "/tmp/olcbox-rp-filter.state"
        ): String {
            val statePath = shellSingleQuote(rpFilterStatePath)
            return """
                #!/bin/sh
                set -eu
                rp_filter_state=$statePath
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                : > "${'$'}rp_filter_state"
                for setting in /proc/sys/net/ipv4/conf/*/rp_filter; do
                  if [ -r "${'$'}setting" ]; then
                    value=${'$'}(cat "${'$'}setting")
                    printf '%s=%s\n' "${'$'}setting" "${'$'}value" >> "${'$'}rp_filter_state"
                    printf '0\n' > "${'$'}setting" 2>/dev/null || true
                  fi
                done
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
                ip route add default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
                ip -6 rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip -6 route flush table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 route add blackhole default table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        fun downScriptContent(
            rpFilterStatePath: String = "/tmp/olcbox-rp-filter.state"
        ): String {
            val statePath = shellSingleQuote(rpFilterStatePath)
            return """
                #!/bin/sh
                rp_filter_state=$statePath
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip -6 route flush table $ROUTE_TABLE 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl revert $TUN_NAME >/dev/null 2>&1 || true
                fi
                if [ -r "${'$'}rp_filter_state" ]; then
                  while IFS='=' read -r setting value; do
                    case "${'$'}setting" in
                      /proc/sys/net/ipv4/conf/*/rp_filter)
                        [ -w "${'$'}setting" ] && printf '%s\n' "${'$'}value" > "${'$'}setting" 2>/dev/null || true
                        ;;
                    esac
                  done < "${'$'}rp_filter_state"
                  rm -f "${'$'}rp_filter_state"
                fi
            """.trimIndent()
        }

        private fun shellSingleQuote(value: String): String {
            return "'${value.replace("'", "'\"'\"'")}'"
        }
    }
}

internal object LinuxPrivilege {
    fun command(command: List<String>): List<String> {
        if (isRoot()) return command
        val preferred = System.getenv("OLCBOX_LINUX_PRIVILEGE")?.lowercase()
        return when {
            preferred == "sudo" -> listOf("sudo", "-n") + command
            preferred == "pkexec" -> listOf("pkexec") + command
            executableExists("pkexec") -> listOf("pkexec") + command
            else -> listOf("sudo", "-n") + command
        }
    }

    private fun isRoot(): Boolean {
        return runCatching {
            val process = ProcessBuilder("id", "-u")
                .redirectErrorStream(true)
                .start()
            val uid = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(1, TimeUnit.SECONDS) && uid == "0"
        }.getOrDefault(false)
    }

    private fun executableExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(':')
            .filter { it.isNotBlank() }
            .map { Path(it).resolve(name) }
            .any { it.exists() && Files.isExecutable(it) }
    }
}
