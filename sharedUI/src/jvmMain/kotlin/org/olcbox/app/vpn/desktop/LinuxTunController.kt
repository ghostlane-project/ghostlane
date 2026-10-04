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
    @Volatile private var routesInstalled = false

    /**
     * hev was last started with the kill switch's scripts, so its pre-down
     * leaves the block alone unless it finds the stop was asked for.
     */
    @Volatile private var startedWithKillSwitch = false

    /**
     * The hev this controller last started, kept after a stop: one that could
     * not be ended still holds the tun, and a tun that is there without it is
     * somebody else's.
     */
    @Volatile private var ownTunnel: Process? = null

    /**
     * The kill switch's block is being kept ([LinuxKillSwitch]): from the
     * death of a verified session's tunnel, from a block found at start, or
     * from a cleanup that was refused, until [endHold]. While it is set no
     * [stop] takes the block away, so a reconnect that fails, a Cancel, a
     * quit, or anything else that tears things down leaves traffic where it
     * was: held.
     */
    @Volatile private var held = false

    /**
     * Whether the block is being held with no session's tunnel in front of it,
     * which is a machine whose traffic goes nowhere. Read off the two flags and
     * not off the routing table: the manager asks on its way to publishing a
     * status.
     */
    val holdsTraffic: Boolean
        get() = held && !routesInstalled

    suspend fun start(
        hevBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        killSwitch: Boolean = false,
        /** The login the server on [socksPort] demands; blank when it demands none. */
        username: String = "",
        password: String = ""
    ): Process {
        // Over a block that is being held the scripts are the kill switch's,
        // whatever the setting says by now (it may have been switched off and
        // the cleanup refused): the plain up script deletes and flushes before
        // it puts back, which would open the block on the way to a tunnel.
        val withSwitch = killSwitch || held
        // A marker left by a stop that never finished would have this tunnel's
        // pre-down take the block away without having been asked. Without the
        // switch nothing reads it, and nothing is touched.
        if (withSwitch) Files.deleteIfExists(stopAskedPath())
        // A tunnel process from before that nobody could end still holds the
        // tun: the app is not root and could not signal it, or the app was
        // killed with the tunnel up. A second one exits on finding the device
        // taken ("Device or resource busy"), so no connect gets through until
        // it is gone. Ending it needs root, which here is one more password,
        // in the one case where nothing else would do.
        if (interfaceExists(TUN_NAME)) {
            addLog("Linux TUN: a tunnel process from before still holds $TUN_NAME; ending it first")
            // Under a kill switch's block only the process is ended, and the
            // rules stay as they are. Otherwise everything that tunnel put in
            // goes with it, as at a stop. Its own pre-down is this file by
            // now and removes nothing when hev runs it, so nothing else would
            // put back the rp_filter values it saved, and the up script would
            // then save the zeroes that are there now in their place: the
            // next stop would "restore" those, until the machine restarts.
            // Not the engines here: the one this session started is running.
            val ending = if (held) {
                writeScript(CLEANUP_SCRIPT_NAME, endTunnelScriptContent())
            } else {
                writeCleanupScript(
                    removeBlockDevice = interfaceExists(KILL_SWITCH_DEVICE),
                    endTunnel = true,
                    endEnginesNaming = null
                )
            }
            runCatching { runPrivilegedScript(ending) }
                .onFailure { addLog("Linux TUN: it could not be ended: ${it.message}") }
            // Still there: the password was not given. A new hev started now
            // would exit on finding the device taken, and until it did the
            // old tun, with its rule and route in place, would pass for the
            // new one being ready: Connected, said over a dialog that is
            // still open.
            if (interfaceExists(TUN_NAME)) {
                error(
                    "A tunnel from before still holds $TUN_NAME and could not be ended: that needs the " +
                        "administrator's password. Connect again and give it"
                )
            }
        }
        val upScript = writeUpScript(withSwitch)
        val downScript = writeDownScript(withSwitch)
        val config = writeConfig(socksPort, upScript, downScript, username, password)
        startedWithKillSwitch = withSwitch
        val process = startPrivilegedProcess(listOf(hevBinary.toString(), config.toString()))
        // Not over one that is still alive: a hev that could not be ended holds
        // the tun, the new one exits on finding it taken, and the one to
        // remember is the one that holds it.
        if (ownTunnel?.isAlive != true) ownTunnel = process
        try {
            waitForTunReady(process)
            routesInstalled = true
            addLog("Linux TUN connected on $TUN_NAME")
            val armed = withSwitch && killSwitchRouteExists()
            // Nothing is held behind a tunnel that came up without the block:
            // the plain up script starts from an empty table, and a kernel
            // with no dummy device never had one.
            if (!armed) held = false
            if (armed) {
                addLog("Linux TUN: kill switch in place; if the tunnel's process stops, traffic is blocked on $KILL_SWITCH_DEVICE")
            } else if (withSwitch) {
                addLog(
                    "Linux TUN: the kill switch could not be put in place (no dummy network device on this " +
                        "system?); the tunnel is up without it"
                )
            }
            return process
        } catch (e: Exception) {
            stop(process)
            throw e
        }
    }

    /**
     * Stops the tunnel's process and, unless the kill switch holds, removes
     * everything the tunnel put in.
     *
     * The removal is the app's own cleanup, as root, and in practice it is
     * that at every stop. hev's pre-down would do it on an orderly stop, and
     * the hev built here stops in an orderly way on SIGINT only, while what
     * [stopProcess] sends ends it at once. Whether it arrives at all when the
     * app is not root itself has not been tried on a desktop: a hev started
     * through pkexec or sudo runs as root, and by kill(2) only root may
     * signal a root process. The marker is written all the same, for the
     * stop that does reach the pre-down: a Ctrl-C in the terminal the app was
     * started from goes to hev as well.
     *
     * Held, the process goes and the block stays, and no marker is written:
     * a pre-down that runs now has not been asked.
     */
    suspend fun stop(process: Process?, endEnginesNaming: String? = null) {
        if (held) {
            stopProcess(process)
            routesInstalled = false
            return
        }
        if (startedWithKillSwitch) {
            // Before the process is told to go: this is what its pre-down
            // looks for. If it cannot be written the pre-down keeps the block,
            // and the cleanup below removes it.
            runCatching { Files.writeString(stopAskedPath(), "") }
        }
        stopProcess(process)

        if (routesInstalled) {
            waitForRoutesRemoved()
        }
        // The tunnel's process runs as root, and an app that does not cannot
        // end it: by kill(2) only root signals a root process, so the stop
        // above never arrived, and hev ignores the pipe the app closed. The
        // process is then still there, holding the tun, and the next connect's
        // would exit on finding the device taken. The cleanup runs as root,
        // so the cleanup ends it. The tun exists exactly as long as a process
        // holds it, which is what is asked here.
        val tunnelStillUp = interfaceExists(TUN_NAME)
        val blockDevice = interfaceExists(KILL_SWITCH_DEVICE)
        if (tunnelStillUp || routeRuleExists() || routeTableExists() ||
            Files.exists(rpFilterStatePath()) || blockDevice
        ) {
            runCatching {
                runPrivilegedScript(
                    writeCleanupScript(
                        removeBlockDevice = blockDevice,
                        endTunnel = tunnelStillUp,
                        endEnginesNaming = endEnginesNaming
                    )
                )
            }.onFailure { addLog("Linux TUN route cleanup failed: ${it.message}") }
        }
        routesInstalled = false
        startedWithKillSwitch = false
        runCatching { Files.deleteIfExists(stopAskedPath()) }
        // The cleanup asks for the administrator, and the dialog can be
        // closed. The block is then still there, and saying "disconnected"
        // over it would leave a machine with no network and no reason given.
        held = blockLeftStanding()
        if (held) {
            addLog(
                "Linux TUN: the kill switch's block is still in place and holds this machine's traffic; " +
                    "connect again, or turn the kill switch off in the connection settings, to remove it"
            )
        }
    }

    /**
     * The hold is over: the user turned the switch off, or a new session is
     * verified and the block is back under a tunnel. From here a [stop] takes
     * everything out again.
     *
     * Nothing else ends it. Not a Cancel, not quitting the app, not a stop
     * some other part of the app asks for on its way to something else (the
     * "Lowest latency" selection stops before it measures): each of them
     * would be traffic let out by something other than the user's saying so.
     */
    fun endHold() {
        held = false
    }

    /**
     * The tunnel's process of a verified session is gone. True when the kill
     * switch's block is in place and is now kept: the [stop] that follows
     * leaves it alone, and so does every later one until [endHold].
     *
     * [wanted] is the setting as it is now. Switched off since the tunnel
     * started, the route is still in the table and still blocks, and the
     * session ends the way it does without the switch: with the cleanup.
     */
    suspend fun holdAfterTunDeath(wanted: Boolean): Boolean {
        held = wanted && startedWithKillSwitch && blockStands()
        if (held) {
            addLog(
                "Linux TUN: the tunnel's process stopped and the kill switch is holding this machine's traffic; " +
                    "it stays blocked until you connect again or turn the kill switch off"
            )
        }
        return held
    }

    /**
     * Asked once, when the app starts: whether a block from a previous run is
     * still in place, with no tunnel in front of it, or with one that leads
     * nowhere. The app was killed, or quit, or its cleanup was refused, while
     * the block stood, and the rules outlived it. It is kept like any other,
     * and the user is told why there is no network.
     */
    suspend fun findLeftoverBlock(): Boolean {
        if (routesInstalled) return false
        held = blockLeftStanding() || (blockStands() && tunnelLeadsNowhere())
        if (held) {
            addLog(
                "Linux TUN: a kill switch block from a previous run is still in place and holds this machine's " +
                    "traffic; connect, or turn the kill switch off in the connection settings, to remove it"
            )
        }
        return held
    }

    private fun writeConfig(
        socksPort: Int,
        upScript: Path,
        downScript: Path,
        username: String,
        password: String
    ): Path {
        val config = configPath()
        Files.writeString(
            config,
            configContent(
                socksPort = socksPort,
                postUpScript = upScript.toString(),
                preDownScript = downScript.toString(),
                username = username,
                password = password
            )
        )
        // It may carry the SOCKS login now, and hev reads it as root: nobody
        // else on the machine has a use for it.
        runCatching {
            config.toFile().setReadable(false, false)
            config.toFile().setReadable(true, true)
        }
        return config
    }

    private fun writeUpScript(killSwitch: Boolean): Path {
        return writeScript(
            name = "linux-tun-up.sh",
            body = upScriptContent(rpFilterStatePath().toString(), killSwitch)
        )
    }

    private fun writeDownScript(killSwitch: Boolean): Path {
        return writeScript(
            name = CLEANUP_SCRIPT_NAME,
            body = downScriptContent(
                rpFilterStatePath().toString(),
                stopAskedPath = if (killSwitch) stopAskedPath().toString() else null
            )
        )
    }

    /**
     * The app's own cleanup. It is written where hev's pre-down is, as it
     * always was: a sudoers or polkit rule that names that path keeps working,
     * and the next start writes the pre-down there again.
     *
     * On a machine where the kill switch's device is not there to remove, it
     * is the plain down script, so that for someone who never turned the
     * switch on, what runs as root at a disconnect is what ran before.
     */
    private fun writeCleanupScript(
        removeBlockDevice: Boolean,
        endTunnel: Boolean,
        endEnginesNaming: String?
    ): Path {
        val statePath = rpFilterStatePath().toString()
        val removal = if (removeBlockDevice) cleanupScriptContent(statePath) else downScriptContent(statePath)
        // The tunnel's process first, then the engines, then the removal: read
        // from the top of the file, since each is put right under the shebang.
        val withEngines = if (endEnginesNaming != null) withEnginesEnded(removal, endEnginesNaming) else removal
        return writeScript(
            name = CLEANUP_SCRIPT_NAME,
            body = if (endTunnel) withTunnelEnded(withEngines) else withEngines
        )
    }

    private fun rpFilterStatePath(): Path {
        return DesktopPaths.appDataDir().resolve("linux-rp-filter.state")
    }

    /**
     * What tells hev's pre-down that the app asked for the stop. It is the
     * app's file, in the app's directory, written and removed by the app as
     * the user; the script, which runs as root, only asks whether it exists.
     */
    private fun stopAskedPath(): Path {
        return DesktopPaths.appDataDir().resolve("linux-tun-stop.asked")
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

    private suspend fun interfaceExists(name: String = TUN_NAME): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", name)
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

    /** Whether the kill switch's own route is in the tun's table, with or without the tun's beside it. */
    private suspend fun killSwitchRouteExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "route", "show", "table", ROUTE_TABLE)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(1, TimeUnit.SECONDS) &&
                    process.exitValue() == 0 &&
                    LinuxKillSwitch.routeStands(output)
        }.getOrDefault(false)
    }

    /**
     * Whether the block is in force: the rule that sends everyone's lookups to
     * the tun's table, and in that table the route that drops them. Either
     * alone blocks nothing.
     */
    private suspend fun blockStands(): Boolean = routeRuleExists() && killSwitchRouteExists()

    /**
     * Whether the block stands with nothing in front of it but, at most, a hev
     * of this controller's own that could not be ended. A tun that is there
     * without one is somebody's running tunnel, a second window of this app
     * or a hev that outlived the app: saying "the tunnel is down" over it
     * would be wrong, and offering to remove the block would take a live
     * tunnel's rules away.
     */
    private suspend fun blockLeftStanding(): Boolean =
        blockStands() && (ownTunnel?.isAlive == true || !interfaceExists())

    /**
     * Whether the tun that is there hands what it takes to nobody. hev runs
     * as root and outlives an app that could not end it, one that quit with
     * the cleanup's dialog closed. The cores it pointed at went with the app,
     * so nothing answers on the port its config names, and the machine behind
     * it has no network: that is a hold, and has to be said. A port that
     * answers is somebody's running line, a second window of this app or
     * cores that outlived a killed one, and is left alone.
     */
    private suspend fun tunnelLeadsNowhere(): Boolean = withContext(Dispatchers.IO) {
        if (!interfaceExists()) return@withContext false
        val port = runCatching { socksPortOf(Files.readString(configPath())) }.getOrNull()
            ?: return@withContext false
        runCatching {
            java.net.Socket().use {
                it.connect(java.net.InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port), PORT_ASK_MS)
            }
            false
        }.getOrDefault(true)
    }

    private fun configPath(): Path = DesktopPaths.appDataDir().resolve("linux-tun.yml")

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

        /** The kill switch's dummy device ([LinuxKillSwitch]). */
        const val KILL_SWITCH_DEVICE = "olcboxks0"

        /** The last metric there is: the tun's own default, at 0, wins for as long as the tun exists. */
        const val KILL_SWITCH_METRIC = "4294967295"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val ROUTE_CLEANUP_TIMEOUT_MS = 2_000L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        /** How long a leftover tunnel's port is given to answer; it is on this machine. */
        const val PORT_ASK_MS = 500

        /** The port hev hands what it takes to, read back from a config [configContent] wrote. */
        internal fun socksPortOf(config: String): Int? =
            config.lineSequence()
                .dropWhile { it.trim() != "socks5:" }
                .drop(1)
                .takeWhile { it.startsWith(" ") }
                .firstOrNull { it.trim().startsWith("port:") }
                ?.substringAfter("port:")
                ?.trim()
                ?.toIntOrNull()

        fun configContent(
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
            postUpScript: String? = null,
            preDownScript: String? = null,
            username: String = "",
            password: String = ""
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
                // The login the server on that port demands, when it demands
                // one. The olcRTC engine started with a SOCKS login refuses a
                // client that offers none, and this file carried none: with a
                // login set in the settings a room in the Linux tunnel said
                // Connected, because the check asks the engine directly, and
                // carried nothing, because hev was turned away. A login is a
                // username, as everywhere else that sends or demands one.
                if (username.isNotBlank()) {
                    appendLine("  username: '${yamlSingleQuoted(username)}'")
                    appendLine("  password: '${yamlSingleQuoted(password)}'")
                }
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
         * Root keeps its direct path for IPv6 as it does for IPv4: the olcRTC
         * engine runs as root here, and without that rule it could not reach a
         * server that only answers over IPv6. A sing-box or Xray core runs as
         * the user and gets out bound to the physical interface instead; a
         * lookup bound to a device passes over this blackhole as it passes over
         * the tun's IPv4 route.
         *
         * Every `ip -6` line tolerates failure: a kernel built without IPv6 has
         * no such tables, and refusing to bring the tunnel up over that would
         * trade a leak nobody has for a tunnel nobody gets.
         *
         * With [killSwitch] the script is another one, [killSwitchUpScript].
         * Without it, it is what it has always been, to the byte.
         */
        fun upScriptContent(
            rpFilterStatePath: String = "/tmp/olcbox-rp-filter.state",
            killSwitch: Boolean = false
        ): String {
            val statePath = shellSingleQuote(rpFilterStatePath)
            if (killSwitch) return killSwitchUpScript(statePath)
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

        /**
         * The up script with the kill switch ([LinuxKillSwitch] says what the
         * route is and why it is that one).
         *
         * It takes nothing away. The plain script starts clean by deleting the
         * rules and flushing the table before it puts them back, and between
         * the two every lookup falls through to the main table. That is
         * harmless when nothing was there, and it is the leak itself when the
         * script runs for a reconnect over a block that is standing: the
         * tunnel died, the block held, and the new tunnel's own script would
         * open it for as long as the lines in between take. So every line here
         * adds what is missing or replaces what is there (`ip route replace`,
         * and `ip rule add`, whose "File exists" is the answer wanted), the
         * dummy's route goes in first, and a second run leaves what the first
         * one did.
         *
         * The dummy's lines tolerate failure, as the `ip -6` ones do: a kernel
         * without the dummy module still gets its tunnel, without the block,
         * and [start] says so in the log.
         *
         * rp_filter is saved once. The plain script writes the file anew at
         * every start; here the file of a session whose block is standing
         * holds the machine's own values, and what `/proc` has by now is the
         * zeroes that session wrote. Only a setting the file does not have is
         * added to it.
         */
        private fun killSwitchUpScript(statePath: String): String {
            return """
                #!/bin/sh
                set -eu
                rp_filter_state=$statePath
                ip link add $KILL_SWITCH_DEVICE type dummy 2>/dev/null || true
                ip link set $KILL_SWITCH_DEVICE up 2>/dev/null || true
                ip route replace default dev $KILL_SWITCH_DEVICE metric $KILL_SWITCH_METRIC table $ROUTE_TABLE 2>/dev/null || true
                [ -e "${'$'}rp_filter_state" ] || : > "${'$'}rp_filter_state"
                for setting in /proc/sys/net/ipv4/conf/*/rp_filter; do
                  if [ -r "${'$'}setting" ]; then
                    if ! grep -qF -- "${'$'}setting=" "${'$'}rp_filter_state"; then
                      value=${'$'}(cat "${'$'}setting")
                      printf '%s=%s\n' "${'$'}setting" "${'$'}value" >> "${'$'}rp_filter_state"
                    fi
                    printf '0\n' > "${'$'}setting" 2>/dev/null || true
                  fi
                done
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip route replace default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip -6 rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 route replace blackhole default table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        /**
         * hev's pre-down. Without [stopAskedPath] it is what it has always
         * been, to the byte, and takes everything down whoever stopped hev.
         *
         * With it, the kill switch is on. hev runs this whenever it stops in
         * an orderly way, also when nobody asked it to; for the hev built
         * here that is a SIGINT, which a Ctrl-C in the terminal the app was
         * started from sends to hev as it does to the app. Taking the rules
         * down then is the leak the switch exists to close, so the script
         * does nothing at all unless the file the app writes just before it
         * stops hev is there; when it is, it does what [cleanupScriptContent]
         * does. The script only asks whether the file exists. It runs as root
         * and the path is the user's, so it neither reads the file nor
         * removes it: the app does that, as the user.
         */
        fun downScriptContent(
            rpFilterStatePath: String = "/tmp/olcbox-rp-filter.state",
            stopAskedPath: String? = null
        ): String {
            if (stopAskedPath != null) return removalScript(rpFilterStatePath, stopAskedPath)
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

        /**
         * The app's own cleanup where the kill switch's device exists, run as
         * root when hev's pre-down has not already done the work: a hev that
         * was killed outright never ran it, and with the kill switch on one
         * that did run it kept the block. It removes everything a tunnel of
         * this app can have put in, the device included, whatever the switch
         * says now: a block outlives the setting that asked for it.
         */
        fun cleanupScriptContent(
            rpFilterStatePath: String = "/tmp/olcbox-rp-filter.state"
        ): String = removalScript(rpFilterStatePath, stopAskedPath = null)

        /**
         * Everything out: the rules, the table, the kill switch's device,
         * resolved's settings for the tun, and rp_filter as it was. With
         * [stopAskedPath], only when that file exists.
         *
         * Two blocks joined by a newline, each trimmed by itself: a piece put
         * into a raw string with its own line breaks would set the indent
         * `trimIndent` takes off to nothing, and leave the shebang indented.
         */
        private fun removalScript(rpFilterStatePath: String, stopAskedPath: String?): String {
            val statePath = shellSingleQuote(rpFilterStatePath)
            val head = if (stopAskedPath == null) {
                """
                    #!/bin/sh
                    rp_filter_state=$statePath
                """.trimIndent()
            } else {
                val askedPath = shellSingleQuote(stopAskedPath)
                """
                    #!/bin/sh
                    rp_filter_state=$statePath
                    stop_asked=$askedPath
                    [ -e "${'$'}stop_asked" ] || exit 0
                """.trimIndent()
            }
            val body = """
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                ip -6 rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip -6 rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip -6 route flush table $ROUTE_TABLE 2>/dev/null || true
                ip link del $KILL_SWITCH_DEVICE 2>/dev/null || true
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
            return head + "\n" + body
        }

        /**
         * Ends whatever process holds the tun. For the scripts the app runs
         * as root, which is the only place it can be done from when the app
         * itself is not root.
         *
         * The process is found by what it holds, not by its name or its
         * binary's path: the kernel says of every open tun descriptor which
         * device it is attached to (`iff:` in `/proc/<pid>/fdinfo`). It is
         * asked to stop the way hev stops in order, on SIGINT, which runs its
         * pre-down; one that has not gone after three seconds is killed.
         *
         * hev runs its own scripts with the tun's name and index as
         * arguments, and the app runs this one with none. The pre-down hev is
         * told of is this same file, so without that test hev, stopping,
         * would be asked by its own pre-down to stop.
         */
        internal fun tunnelEndLines(): String = """
            if [ "${'$'}#" -eq 0 ]; then
              attempt=0
              while [ "${'$'}attempt" -lt 5 ]; do
                holders=${'$'}(grep -rls '^iff:[[:space:]]*$TUN_NAME${'$'}' /proc/[0-9]*/fdinfo 2>/dev/null | cut -d/ -f3 | sort -u)
                [ -n "${'$'}holders" ] || break
                if [ "${'$'}attempt" -lt 3 ]; then sig=INT; else sig=KILL; fi
                for pid in ${'$'}holders; do kill -"${'$'}sig" "${'$'}pid" 2>/dev/null || true; done
                attempt=${'$'}((attempt + 1))
                sleep 1
              done
            fi
        """.trimIndent()

        /** [script] with the tunnel's process ended first: right under its first line, the shebang. */
        internal fun withTunnelEnded(script: String): String {
            val shebang = script.substringBefore('\n')
            return shebang + "\n" + tunnelEndLines() + "\n" + script.substringAfter('\n')
        }

        /**
         * Ends every process whose command line names [naming], for the
         * cleanup the app runs as root. It is for the olcRTC engine, which in
         * the Linux tunnel runs as root like hev, so that the app's own stop
         * does not reach it either: it went on, joined to its room and
         * holding the session's port, until the next line it wrote met the
         * pipe the app had closed. [naming] is what the app's engine configs
         * are called, a directory and the beginning of a file name, which the
         * engine is started with: it finds the engine, and the sudo in front
         * of it where that is what started it, by something no other
         * program's command line carries.
         *
         * The words go to grep on its standard input and not as an argument.
         * As an argument they are on grep's own command line, the list of
         * processes is made in the process that then becomes grep, and grep
         * finds itself: every round would kill a number that no longer
         * belongs to anyone, or by then to someone else.
         *
         * Asked to end, then killed after three seconds, and only when the
         * app runs the script, as [tunnelEndLines].
         */
        internal fun engineEndLines(naming: String): String = """
            if [ "${'$'}#" -eq 0 ]; then
              naming=NAMING
              attempt=0
              while [ "${'$'}attempt" -lt 5 ]; do
                engines=${'$'}(printf '%s\n' "${'$'}naming" | grep -lasF -f - /proc/[0-9]*/cmdline 2>/dev/null | cut -d/ -f3 | sort -u)
                [ -n "${'$'}engines" ] || break
                if [ "${'$'}attempt" -lt 3 ]; then sig=TERM; else sig=KILL; fi
                for pid in ${'$'}engines; do kill -"${'$'}sig" "${'$'}pid" 2>/dev/null || true; done
                attempt=${'$'}((attempt + 1))
                sleep 1
              done
            fi
        """.trimIndent().replace("NAMING", shellSingleQuote(naming))

        /** [script] with the engines ended first: right under its first line, the shebang. */
        internal fun withEnginesEnded(script: String, naming: String): String {
            val shebang = script.substringBefore('\n')
            return shebang + "\n" + engineEndLines(naming) + "\n" + script.substringAfter('\n')
        }

        /** Ends the tunnel's process and removes nothing: for a start over a tunnel left from before. */
        internal fun endTunnelScriptContent(): String = "#!/bin/sh\n" + tunnelEndLines() + "\n"

        /**
         * The one script the app runs as root by itself. Its path is also the
         * pre-down hev is told of, as it always was, so a sudoers or polkit
         * rule that names it keeps covering both.
         */
        const val CLEANUP_SCRIPT_NAME = "linux-tun-down.sh"

        /** Inside single quotes YAML has one escape: a quote is written twice. */
        private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")

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
