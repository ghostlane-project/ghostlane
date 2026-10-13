package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

/**
 * macOS TUN: builds the daemon's config and asks the daemon to run it.
 *
 * The routes are deliberately not this class's business. sing-box's `auto_route`
 * installs them and removes them with the process, which is why there is nothing
 * here resembling the up/down scripts the Linux controller needs and the route
 * bookkeeping the Windows one carries — on macOS the core does that job, and does
 * it on the way out too.
 *
 * Two things only root can hold are the daemon's, asked for here: who the system
 * asks for names while the tunnel runs, and the kill switch's block
 * ([MacKillSwitch]). What this class keeps of the block is whether it is being
 * held, which is the app's to decide: the daemon only loads the rules and takes
 * them away.
 */
internal class MacOsTunController(
    private val addLog: (String) -> Unit,
    private val client: TunnelDaemonClient = TunnelDaemonClient(),
    private val resolve: (String) -> List<String> = ::resolveAllAddresses,
    private val defaultInterface: () -> String? = { defaultInterfaceName() },
    /** A name for the tun that no interface has: the block is written for it before it exists. */
    private val tunName: () -> String = ::freeTunName,
    /** The addresses the tun's own resolver gives for a name; empty for none. */
    private val askTun: (String) -> List<String> = ::askTheTun,
) {
    /**
     * Whether the tun that was last started answers the system's names, which
     * a helper from before [TunnelDaemonProtocol.PROTOCOL_HOLD] cannot be asked
     * for. A line that is started behind such a tun asks the tun as well
     * ([SingBoxConfig.DESKTOP_TUN_RESOLVER]).
     */
    @Volatile var answersNames = false
        private set

    /** A tunnel this controller started is up, as far as it has been told. */
    @Volatile private var tunUp = false

    /** That tunnel was started behind the block, so the block is there to keep when it dies. */
    @Volatile private var armed = false

    /**
     * The block is being kept, as on Linux ([LinuxTunController.holdsTraffic]):
     * from the end of a verified session that nobody ended, or from a block
     * found at start, until [endHold]. While it is set no [stop] takes the
     * block away.
     */
    @Volatile private var held = false

    /**
     * Whether the daemon said, the last time it said anything, that the block
     * stands. A line cannot be started before its tunnel then: nothing of it
     * would get out.
     */
    @Volatile var blockStands = false
        private set

    /** The block is held with no tunnel in front of it: a Mac whose traffic goes nowhere. */
    val holdsTraffic: Boolean
        get() = held && !tunUp

    /**
     * True when the tun that was started lets [bypassProcessPaths] out of
     * itself, which is what a line for another server needs in order to be
     * started behind it. False when it lets out only [serverHost], by address,
     * as every macOS tun did before.
     */
    suspend fun start(
        corePort: Int,
        verifyPort: Int,
        username: String,
        password: String,
        serverHost: String?,
        upstreamUdpIsLossy: Boolean,
        routing: Routing = Routing.Global,
        verboseLogs: Boolean = false,
        /** The rule-set files [routing] names, file name → base64, for the daemon to write. */
        ruleFiles: Map<String, String> = emptyMap(),
        /**
         * Every binary a line of the session can run. What they dial is sent
         * straight out by the daemon's sing-box, as the Windows tun does it,
         * so the core of another server than [serverHost], and an olcRTC
         * engine signing in again, leave the tunnel they are there to carry.
         */
        bypassProcessPaths: List<String> = emptyList(),
        /** Where the tun's sing-box is to say what it has carried; null for nowhere. */
        trafficApi: SingBoxConfig.LocalApi? = null,
        /** The tunnel is started behind the kill switch's block ([MacKillSwitch]). */
        killSwitch: Boolean = false,
        /** This app's own program, which the tun is to answer [SingBoxConfig.NAMES_PROGRAMS_PROBE] for. */
        namesProgramsFor: String? = null,
    ): Boolean {
        // Under a bypass, and for the lines' own binaries, the daemon's
        // sing-box dials direct from inside the process that owns the tun, so
        // those sockets are bound to the physical interface by name. No name,
        // no direct socket: one with nothing to bind to enters the tun, and
        // that does not degrade, it loops.
        val wantsDirect = routing is Routing.Rules || bypassProcessPaths.isNotEmpty()
        val bindInterface = if (wantsDirect) defaultInterface() else null
        if (routing is Routing.Rules && bindInterface == null) {
            addLog("cannot tell which interface carries the internet, so the bypass cannot bind to it")
            error("no interface to bind direct traffic to")
        }
        // Without rules the same lack must not fail a connect that worked
        // before the lines' binaries were let out: the tun starts as it always
        // did, and says that it is good for this one server only.
        val lineBinaries = if (bindInterface != null) bypassProcessPaths else emptyList()
        if (bypassProcessPaths.isNotEmpty() && bindInterface == null) {
            addLog(
                "cannot tell which interface carries the internet, so this tunnel lets only its own server out; " +
                    "another location will be a full restart"
            )
        }
        // What the daemon speaks decides what it is handed. One that does not
        // answer is told so by the start below, in its own words.
        var helper = (client.status() as? DaemonReply.Ok)?.protocol
        requireHelperFor(helper, wantsFiles = ruleFiles.isNotEmpty(), wantsHold = killSwitch)

        val addresses = serverHost?.let { resolve(it) }.orEmpty()
        if (serverHost != null && addresses.isEmpty()) {
            // Starting anyway would put the core's own packets into the tunnel the
            // core is building. That does not degrade — it deadlocks, and it reads
            // as a broken server rather than as a missing route.
            addLog("cannot resolve $serverHost, so its traffic cannot be kept out of the tunnel")
            error("cannot resolve $serverHost")
        }

        suspend fun ask(protocol: Int): DaemonReply {
            val names = protocol >= TunnelDaemonProtocol.PROTOCOL_HOLD
            val hold = if (killSwitch) DaemonHold(interfaceName = tunName(), passes = addresses) else null
            val config = SingBoxConfig.buildDesktopTun(
                corePort = corePort,
                verifyPort = verifyPort,
                username = username,
                password = password,
                excludeAddresses = excludeCidrs(addresses),
                directDnsDomains = listOfNotNull(serverHost?.takeIf { !it.isIpLiteral() }),
                upstreamUdpIsLossy = upstreamUdpIsLossy,
                routing = routing,
                verboseLogs = verboseLogs,
                bindInterface = bindInterface,
                cacheFilePath = TunnelDaemonProtocol.CACHE_FILE,
                bypassProcessPaths = lineBinaries,
                interfaceName = hold?.interfaceName,
                trafficApi = trafficApi,
                answersEveryName = names,
                namesProgramsFor = namesProgramsFor.takeIf { names },
            )
            answersNames = names
            return client.start(
                config,
                ruleFiles,
                names = SingBoxConfig.DESKTOP_TUN_RESOLVER.takeIf { names },
                hold = hold
            )
        }

        var reply = ask(helper ?: TunnelDaemonProtocol.PROTOCOL_FIRST)
        if (reply is DaemonReply.Failure && reply.restarting) {
            // An updated app found the previous build's daemon still alive. It
            // steps aside for the one on disk, which launchd starts in its place,
            // and which may speak more than the one that was asked.
            addLog("the tunnel helper was updated; waiting for the new one to come up")
            helper = awaitDaemon()
            if (helper != null) {
                requireHelperFor(helper, wantsFiles = ruleFiles.isNotEmpty(), wantsHold = killSwitch)
                reply = ask(helper)
            }
        }
        if (reply is DaemonReply.Ok && killSwitch && !reply.holds) {
            // Asked for behind the block and up without one. Not the tunnel
            // that was asked for, so not one to call a session over: the
            // status would promise a hold that is not there.
            addLog("macOS TUN: the tunnel came up with no kill switch behind it; stopping it")
            blockStands = (client.stop() as? DaemonReply.Ok)?.holds ?: false
            error("the kill switch could not be put in place, so the tunnel was not started")
        }
        when (reply) {
            is DaemonReply.Ok -> {
                tunUp = true
                armed = reply.holds
                blockStands = reply.holds
                addLog("macOS TUN running (sing-box pid ${reply.pid})")
                if (answersNames) addLog("macOS TUN: the system asks the tunnel for names while it runs")
                if (armed) {
                    addLog("macOS TUN: kill switch in place; if the tunnel's process stops, traffic is blocked")
                }
            }
            is DaemonReply.Failure -> {
                blockStands = reply.holds
                addLog("macOS TUN failed: ${reply.message}")
                if (reply.logTail.isNotBlank()) addLog(reply.logTail)
                error(reply.message)
            }
        }
        return lineBinaries.isNotEmpty()
    }

    /**
     * A daemon from before what is asked of it drops the field and starts a
     * tunnel that is not the one asked for: one that names files nobody wrote,
     * or one with no block behind it. It cannot be replaced from here: launchd
     * keeps it alive, and only root can restart it.
     */
    private fun requireHelperFor(helper: Int?, wantsFiles: Boolean, wantsHold: Boolean) {
        if (helper == null) return
        val lacks = when {
            wantsFiles && helper < TunnelDaemonProtocol.PROTOCOL_FILES -> "does not take rule files"
            wantsHold && helper < TunnelDaemonProtocol.PROTOCOL_HOLD -> "has no kill switch"
            else -> return
        }
        addLog("the tunnel helper is from an older build and $lacks")
        error(
            "restart the tunnel helper (sudo launchctl kickstart -k system/${TunnelDaemonProtocol.LABEL}) " +
                "or the Mac, then connect again"
        )
    }

    /** What the daemon that came up speaks, or null when none did. */
    private suspend fun awaitDaemon(): Int? {
        repeat(RESTART_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RESTART_POLL_MS)
            (client.status() as? DaemonReply.Ok)?.let { return it.protocol }
        }
        return null
    }

    /**
     * Stops the tunnel and, unless the block is being held, takes the block
     * away with it: a stop is a Disconnect unless [holdAfterSessionEnd] or
     * [findLeftover] said otherwise first.
     */
    suspend fun stop() {
        val reply = client.stop(keepHold = held)
        tunUp = false
        armed = false
        when (reply) {
            is DaemonReply.Ok -> {
                blockStands = reply.holds
                addLog("macOS TUN stopped")
            }
            is DaemonReply.Failure -> {
                blockStands = reply.holds
                addLog("macOS TUN stop failed: ${reply.message}")
            }
        }
        // Held, and the daemon says no block is there: somebody took it away,
        // or the Mac restarted. Nothing is held then, whatever was meant.
        if (held && !blockStands) held = false
        if (held) {
            addLog(
                "macOS TUN: the kill switch's block is in place and holds this Mac's traffic; " +
                    "connect again, or turn the kill switch off in the connection settings, to remove it"
            )
        }
    }

    /**
     * A verified session is being taken down without a Disconnect: its tunnel
     * died, or the app is on its way to another one. True when the block was
     * under it and is now kept: the [stop] that follows leaves it alone, and so
     * does every later one until [endHold], so the way to the next tunnel has
     * no moment with the Mac's traffic going out directly.
     *
     * [wanted] is the setting as it is now. Switched off since the tunnel
     * started, the block was taken away then ([release]).
     */
    fun holdAfterSessionEnd(wanted: Boolean): Boolean {
        held = wanted && armed
        return held
    }

    /** The hold is over: the user turned the switch off, or a new session is verified over the block. */
    fun endHold() {
        held = false
    }

    /**
     * Takes the block away and leaves a tunnel that runs running: the switch
     * turned off. The daemon needs no password for it, so unlike on Linux it
     * is done at once, under a session too.
     */
    suspend fun release() {
        held = false
        armed = false
        when (val reply = client.release()) {
            is DaemonReply.Ok -> {
                blockStands = reply.holds
                addLog("macOS TUN: the kill switch's block is removed")
            }
            // A daemon from before the block knows no such verb, and has no block.
            is DaemonReply.Failure -> blockStands = reply.holds
        }
    }

    /** What a previous run of the app left behind. */
    data class Leftover(val tunnelRuns: Boolean, val blockStands: Boolean)

    /**
     * Asked once, when the app starts. A block that stands is held from here,
     * like any other: the app was killed, or quit, while it stood, and what
     * held the Mac's traffic then holds it still.
     */
    suspend fun findLeftover(): Leftover {
        val status = client.status() as? DaemonReply.Ok ?: return Leftover(tunnelRuns = false, blockStands = false)
        blockStands = status.holds
        if (status.holds) held = true
        return Leftover(tunnelRuns = status.state == DaemonReply.STATE_RUNNING, blockStands = status.holds)
    }

    /** What the daemon says of itself, for a connect about to choose its order. */
    data class Helper(
        /** The kill switch's block stands now. */
        val blockStands: Boolean,
        /** It can be asked for a block, and to have the system ask the tun for names. */
        val canHold: Boolean
    )

    /**
     * Asks the daemon, with no tunnel of this app's up. One that does not
     * answer has no block and can hold none.
     *
     * A daemon from before the block is first given the chance to make way.
     * After an update of the app the previous build's daemon is still the one
     * running, and it steps aside for the one on disk when it is told to start
     * or to stop. Left to the start, that would be after the connect had
     * chosen its order by what the old one can do: a room under the switch
     * joined first and then cut off by a block the new one loaded. A stop
     * costs a daemon with no tunnel nothing, and a daemon this old has no
     * block for it to take away.
     */
    suspend fun helperNow(): Helper {
        var status = client.status() as? DaemonReply.Ok ?: return Helper(blockStands = blockStands, canHold = false)
        if (status.protocol < TunnelDaemonProtocol.PROTOCOL_HOLD && status.state != DaemonReply.STATE_RUNNING) {
            val stopped = client.stop()
            if (stopped is DaemonReply.Failure && stopped.restarting) {
                addLog("the tunnel helper was updated; waiting for the new one to come up")
                awaitDaemon()
                status = client.status() as? DaemonReply.Ok ?: return Helper(blockStands = false, canHold = false)
            }
        }
        blockStands = status.holds
        return Helper(blockStands = status.holds, canHold = status.protocol >= TunnelDaemonProtocol.PROTOCOL_HOLD)
    }

    /**
     * Waits until the tun names the program behind a connection, which it does
     * not do in its first moments ([SingBoxConfig.NAMES_PROGRAMS_PROBE]). True
     * when it said so within [timeoutMs]. A line is started either way: a tun
     * that never answers is one whose lines will say what is wrong with it.
     */
    suspend fun awaitNamesPrograms(timeoutMs: Long = NAMES_PROGRAMS_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val answered = withContext(Dispatchers.IO) { askTun(SingBoxConfig.NAMES_PROGRAMS_PROBE) }
            if (SingBoxConfig.NAMES_PROGRAMS_ANSWER in answered) return true
            if (System.currentTimeMillis() >= deadline) {
                addLog("macOS TUN: the tunnel has not begun to tell programs apart; starting the line anyway")
                return false
            }
            delay(NAMES_PROGRAMS_POLL_MS)
        }
    }

    suspend fun isRunning(): Boolean =
        (client.status() as? DaemonReply.Ok)?.state == DaemonReply.STATE_RUNNING

    /** Bounded daemon tail returned over its existing local status protocol. */
    suspend fun diagnostics(): String = when (val reply = client.status()) {
        is DaemonReply.Ok -> reply.logTail
        is DaemonReply.Failure -> reply.logTail.ifBlank { reply.message }
    }

    internal companion object {
        private const val RESTART_ATTEMPTS = 10
        private const val RESTART_POLL_MS = 1_000L
        private const val NAMES_PROGRAMS_TIMEOUT_MS = 12_000L
        private const val NAMES_PROGRAMS_POLL_MS = 300L

        /**
         * Every address the server resolves to, not the first one.
         *
         * A server that answers with four addresses and is excluded on one is a
         * tunnel that works until the core redials and happens to pick another —
         * a failure that looks intermittent and is not.
         */
        fun excludeCidrs(addresses: List<String>): List<String> =
            addresses.map { if (':' in it) "$it/128" else "$it/32" }

        /**
         * The interface behind the default route, from `route -n get default`,
         * which prints a line `interface: en0`. A utun is not an answer: it is
         * the tunnel being replaced, still up from the session before.
         */
        fun defaultInterfaceName(output: String = routeGetDefault()): String? =
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("interface:") }
                ?.substringAfter("interface:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("utun") }

        private fun routeGetDefault(): String = runCatching {
            val process = ProcessBuilder("route", "-n", "get", "default")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrDefault("")

        /**
         * The first of the utun names from [FIRST_TUN_NUMBER] up that [taken]
         * does not hold. Far above the numbers the system hands out by itself,
         * which start at 0 and are taken by its own services and by other VPNs.
         */
        fun freeTunName(taken: Set<String> = interfaceNames()): String =
            (FIRST_TUN_NUMBER until FIRST_TUN_NUMBER + TUN_NUMBERS)
                .map { "utun$it" }
                .firstOrNull { it !in taken }
                ?: "utun$FIRST_TUN_NUMBER"

        private const val FIRST_TUN_NUMBER = 225
        private const val TUN_NUMBERS = 30

        private fun interfaceNames(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().map { it.name }.toSet()
        }.getOrDefault(emptySet())

        /**
         * Asks the tun's own resolver for [name]'s addresses, from this process
         * and by datagram, so that the question is this program's own when the
         * tun looks for who asked. Not the system's resolver: that one asks on
         * every program's behalf, and the tun would see it and not us.
         */
        private fun askTheTun(name: String): List<String> = runCatching {
            val environment = Hashtable<String, String>().apply {
                put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
                put(Context.PROVIDER_URL, "dns://${SingBoxConfig.DESKTOP_TUN_RESOLVER}")
                put("com.sun.jndi.dns.timeout.initial", "400")
                put("com.sun.jndi.dns.timeout.retries", "1")
            }
            val context = InitialDirContext(environment)
            try {
                val answers = context.getAttributes(name, arrayOf("A")).get("A") ?: return@runCatching emptyList()
                (0 until answers.size()).map { answers.get(it).toString() }
            } finally {
                context.close()
            }
        }.getOrDefault(emptyList())
    }
}

private fun String.isIpLiteral(): Boolean =
    ':' in this || split('.').let { parts ->
        parts.size == 4 && parts.all { it.toIntOrNull() != null }
    }

internal fun resolveAllAddresses(host: String): List<String> =
    runCatching {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())
