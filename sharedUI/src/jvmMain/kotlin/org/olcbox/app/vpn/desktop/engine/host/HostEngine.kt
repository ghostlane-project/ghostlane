package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreRouting
import org.olcbox.app.vpn.DesktopLanProxy
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.LanCores
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.engine.DesktopEngine
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.FrontRequest
import org.olcbox.app.vpn.desktop.engine.LineEngine
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.LineStarted
import org.olcbox.app.vpn.desktop.engine.ProbeEngine
import org.olcbox.app.vpn.desktop.engine.RoomEngine
import org.olcbox.app.vpn.desktop.engine.TunCore
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

/**
 * The desktop's engine on the Rust core: ghostlane-engine, one program run as
 * a child of the app ([HostProcess]), is the line of a session. A core line
 * is handed to it as a session file whose core half is the config the app's
 * one builder writes (`net/core`), on the port the session names, demanding
 * the login it names or, in proxy mode, none.
 *
 * A room is the same program with a room half ([HostRoom]), and the front
 * before it, with the session's rules, is the session's core half in that one
 * process; the probes are its one-shots ([HostProbe]). Nothing here starts
 * another program. The Windows tun's core is not on this engine: Windows keeps
 * the Go engines until its own flip, and asking this one for it is an error.
 * This is the desktop's default on Linux and macOS
 * ([org.olcbox.app.vpn.desktop.engine.DesktopEngines.defaultFor]).
 *
 * [binary] is the program ([HostAssets.resolve]); [serverAddress] the
 * address of a server named by a hostname, asked before the engine starts
 * ([HostServerNames]); [defaultInterface] the interface a line beside the
 * Linux tun is bound to; [sessionParent] where the session files go (the
 * system's temporary directory when null). [lanSettings] is the manager's
 * current LAN sharing settings, and [lanNetworkProblem] says when the network
 * they name is no longer the one the machine is on; [lanPortHeld] probes the
 * LAN port before the run. A line whose LAN must be left out for any of these
 * reasons, or whose plan the core refuses, is written and logged without it.
 */
internal class HostEngine(
    host: DesktopEngineHost,
    binary: () -> Path = HostAssets::resolve,
    serverAddress: suspend (String, DirectDns?) -> String = HostServerNames::address,
    defaultInterface: () -> String? = ::linuxDefaultInterface,
    sessionParent: Path? = null,
    lanSettings: () -> DesktopSocksProxySettings = { LanCores.currentSettings() },
    lanNetworkProblem: () -> String? = { DesktopLanProxy.networkProblem(LanCores.currentSettings()) },
    lanPortHeld: (CoreInbound.Lan) -> Boolean = ::lanPortHeldByAnother,
) : DesktopEngine {
    override val line: LineEngine = HostLine(
        host.log, binary, serverAddress, defaultInterface, sessionParent, lanSettings, lanNetworkProblem,
        lanPortHeld,
    )
    override val windowsTun: TunCore = HostWindowsTun
    override val room: RoomEngine = HostRoom(binary, host.log, defaultInterface)
    override val probes: ProbeEngine =
        HostProbe(binary, sessionParent, serverAddress, host.log).probes()
}

/** The Windows tun's core is not ghostlane-engine's yet. */
private object HostWindowsTun : TunCore {
    override suspend fun start(config: String) =
        error("ghostlane-engine does not run the Windows tunnel's core yet; Windows keeps the Go engines")

    override fun isRunning(): Boolean = false
    override fun runningProcess(): Process? = null
    override fun stopNow() = Unit
}

private fun linuxDefaultInterface(): String? =
    if (DesktopPaths.os == DesktopOs.Linux) DesktopDnsResolver.linuxDefaultInterface() else null

/** Whether another program already holds the LAN port the line would bind. */
internal fun lanPortHeldByAnother(lan: CoreInbound.Lan): Boolean = runCatching {
    ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(lan.address, lan.port))
    }
}.isFailure

/**
 * What the engine is told for a line: the session file's halves, written by
 * the app's builder.
 */
internal object HostSession {
    /**
     * A core line as the engine runs it: [spec] is the line with its server
     * pinned to an address when it was named by a hostname ([pinned]), [core]
     * the core half, [bind] the interface every socket but loopback's leaves
     * by, or null, and [lan] the inbound LAN sharing added to that core, or
     * null.
     */
    class Line(
        val spec: OutboundSpec,
        val core: String,
        val bind: String?,
        val pinned: Boolean,
        val lan: CoreInbound.Lan?,
        val geodataDir: String? = null,
    )

    /** The words a refused line is reported in, before what the builder says of it. */
    const val REFUSED = "ghostlane-engine cannot carry this line"

    /**
     * [request] as the engine is to run it. A server named by a hostname is
     * resolved here, by [serverAddress] on the resolver the session names
     * (the network's, read before any tunnel), and the line is written with
     * the address ([CoreLines.pinHost]): the engine's core would ask the
     * system's resolver, which behind a tun is the tun's. [lanSettings] is
     * written into the same core as one more inbound when sharing is on, so a
     * LAN client follows the line's rules over the same line, datagrams too.
     * Throws IllegalStateException with a sentence for a line the core
     * refuses, before anything is started.
     */
    suspend fun line(
        request: LineRequest,
        serverAddress: suspend (String, DirectDns?) -> String,
        lanSettings: DesktopSocksProxySettings = DesktopSocksProxySettings(),
        geodataDir: (suspend () -> String)? = null,
        defaultInterface: () -> String?,
    ): Line {
        val asked = request.spec
        CoreLines.of(asked) ?: refuse(CoreRefusal.noLine(asked))
        val named = !XrayConfig.isIpLiteral(asked.host)
        val spec = if (named) CoreLines.pinHost(asked, serverAddress(asked.host, request.serverResolver)) else asked
        CoreRefusal.of(spec)?.let { refuse(it) }
        val rules = CoreRouting.effective(request.routing) as? Routing.RuleBased
        // "Only blocked sites" reads Re:filter's list from the engine's geodata directory.
        val blockedOnly = rules?.policy == Routing.Policy.BlockedOnly
        val geodataPath = if (blockedOnly) (geodataDir ?: refuse(CoreConfig.BLOCKED_ONLY_REFUSAL))() else null
        val geodata = (rules?.policy as? Routing.Policy.Bypass)?.let { XrayGeodata.lists(it.region) }
        val lan = if (lanSettings.shareOnLan) {
            CoreInbound.Lan(
                lanSettings.lanAddress, lanSettings.lanPort,
                SocksLogin(lanSettings.lanUsername, lanSettings.lanPassword),
            )
        } else null
        val plan = CoreInbound.Plan(CoreInbound.Proxy(request.port, request.login), lan)
        CoreInbound.refusal(plan)?.let { refuse(it) }
        val core = try {
            CoreInbound.build(spec, plan, request.routing, geodata, request.verboseLogs, refilterDat = blockedOnly)
        } catch (e: IllegalArgumentException) {
            refuse(e.message ?: "the core refuses this config")
        }
        val bind = request.bindInterface ?: if (request.autoDetectInterface) defaultInterface() else null
        return Line(spec, core, bind, named, lan, geodataPath)
    }

    /** The session file: protocol 1, the halves given, and the interface to bind to. */
    fun text(core: String?, room: String? = null, bind: String? = null, geodataDir: String? = null): String = buildJsonObject {
        put("version", HostAssets.PROTOCOL)
        core?.let { put("core", Json.parseToJsonElement(it)) }
        room?.let { put("room", Json.parseToJsonElement(it)) }
        bind?.let { name -> putJsonObject("bind") { put("interface", name) } }
        geodataDir?.let { put("geodata_dir", it) }
    }.toString()

    private fun refuse(why: String): Nothing = throw IllegalStateException("$REFUSED: $why")
}

/**
 * A server's name asked outside any tunnel: of the resolver the session
 * names (the machine's own, read before the tun came up), directly by
 * datagram from this process, or, when it names none, of the system's,
 * which in proxy mode is the network's. An IPv4 address is preferred: the
 * desktop's tunnels carry no IPv6.
 */
internal object HostServerNames {
    suspend fun address(host: String, resolver: DirectDns?): String = withContext(Dispatchers.IO) {
        val answers = when (resolver) {
            is DirectDns.Servers -> ask(resolver.pick(), host)
            else -> runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
        }
        pick(answers) ?: error("the server's name did not resolve")
    }

    /** The first IPv4 address of [answers], else the first, without a zone. */
    fun pick(answers: List<String>): String? {
        val bare = answers.map { it.substringBefore('%') }.filter { it.isNotBlank() }
        return bare.firstOrNull { runCatching { InetAddress.getByName(it) is Inet4Address }.getOrDefault(false) }
            ?: bare.firstOrNull()
    }

    /** [host]'s addresses from the resolver at [server] (an address), by datagram to its [port]; empty when it gave none. */
    internal fun ask(server: String, host: String, port: Int = 53): List<String> {
        val authority = (if (':' in server) "[$server]" else server) + ":$port"
        val environment = Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
            put(Context.PROVIDER_URL, "dns://$authority")
            put("com.sun.jndi.dns.timeout.initial", "1000")
            put("com.sun.jndi.dns.timeout.retries", "2")
        }
        return runCatching {
            val context = InitialDirContext(environment)
            try {
                listOf("A", "AAAA").flatMap { type ->
                    val values = runCatching { context.getAttributes(host, arrayOf(type)).get(type) }.getOrNull()
                    if (values == null) emptyList() else (0 until values.size()).map { values.get(it).toString() }
                }
            } finally {
                context.close()
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * A core line on ghostlane-engine. The front before a room is not a line of
 * its own: it is in the room's process ([HostRoom]).
 */
private class HostLine(
    private val log: (String) -> Unit,
    private val binary: () -> Path,
    private val serverAddress: suspend (String, DirectDns?) -> String,
    private val defaultInterface: () -> String?,
    private val sessionParent: Path?,
    private val lanSettings: () -> DesktopSocksProxySettings,
    private val lanNetworkProblem: () -> String?,
    private val lanPortHeld: (CoreInbound.Lan) -> Boolean,
) : LineEngine {
    @Volatile private var current: HostProcess? = null
    @Volatile private var checked: Path? = null

    /**
     * What the line can be given without losing it. LAN sharing is one inbound
     * of the core: a port another program holds, or a plan the core refuses,
     * costs that inbound and nothing else. The reason is kept for the proxy,
     * which otherwise binds the port itself.
     */
    private fun withoutLan(
        request: LineRequest,
        sharing: DesktopSocksProxySettings,
    ): Pair<DesktopSocksProxySettings, String?> {
        if (!sharing.shareOnLan) return sharing to null
        val problem = lanNetworkProblem()
        if (problem != null) {
            log("$NAME: LAN sharing was left out: $problem")
            return sharing.copy(shareOnLan = false) to problem
        }
        val own = CoreInbound.Proxy(request.port, request.login)
        // A line the core refuses for its own sake is still refused below:
        // only what the LAN inbound adds is left out here.
        if (CoreInbound.refusal(CoreInbound.Plan(own)) == null) {
            val lan = CoreInbound.Lan(
                sharing.lanAddress, sharing.lanPort, SocksLogin(sharing.lanUsername, sharing.lanPassword)
            )
            CoreInbound.refusal(CoreInbound.Plan(own, lan))?.let { refused ->
                log("$NAME: LAN sharing was left out: $refused")
                return sharing.copy(shareOnLan = false) to refused
            }
            if (lanPortHeld(lan)) {
                val reason = "LAN port ${lan.address}:${lan.port} is already in use"
                log("$NAME: LAN sharing was left out: $reason")
                return sharing.copy(shareOnLan = false) to reason
            }
        }
        return sharing to null
    }

    override suspend fun start(request: LineRequest): LineStarted = withContext(Dispatchers.IO) {
        LanCores.lineStopped()
        val (effective, lanProblem) = withoutLan(request, lanSettings())
        val line = HostSession.line(request, serverAddress, effective, HostGeodata::refilterDir, defaultInterface)
        stopEngine()
        val program = binary()
        checkProgram(program)
        if (line.pinned) log("$NAME: the server's name resolved to an address, which the engine is given")
        if (request.autoDetectInterface && line.bind == null) {
            log("$NAME: no default interface was found, so the line's sockets are not bound to one")
        }
        val run = HostProcess.run(
            program,
            HostSession.text(line.core, bind = line.bind, geodataDir = line.geodataDir),
            onEvent = { event -> HostEvents.logLine(event)?.let { log("$NAME: $it") } },
            onStderr = { text -> log("$NAME: $text") },
            parent = sessionParent,
        )
        current = run
        when (val start = run.awaitStart(START_WAIT_MS)) {
            is HostProcess.Start.Ended -> {
                run.stop()
                LanCores.lineStopped()
                val why = start.ended?.let { ended ->
                    listOfNotNull(ended.code?.word, ended.message).joinToString(": ").ifEmpty { ended.reason.word }
                } ?: "it exited with code ${start.exitCode}"
                error("$NAME did not start the line: $why")
            }
            is HostProcess.Start.Ready, HostProcess.Start.Waiting -> Unit
        }
        LanCores.lineStarted(line.lan, lanProblem)
        LineStarted(
            said = "$NAME (${request.kind}) starting on 127.0.0.1:${request.port}" +
                (line.bind?.let { ", its sockets bound to $it" }.orEmpty()),
            alive = { run.isAlive() },
            exitCode = { run.exitCode() },
        )
    }

    /** Once per program: what it says of itself goes into the log, and a program of another protocol is refused. */
    private fun checkProgram(program: Path) {
        if (checked == program) return
        val version = HostAssets.version(program) ?: error("$NAME printed no version: $program is not a program this app runs")
        HostAssets.refusal(version)?.let { error(it) }
        log(HostAssets.describe(version))
        checked = program
    }

    private fun stopEngine() {
        current?.stop()
        current = null
        LanCores.lineStopped()
    }

    override suspend fun startFront(request: FrontRequest) =
        error("$NAME runs a room's front inside the room's own process; no front is started on its own")

    override fun alive(spec: OutboundSpec, routing: Routing): () -> Boolean = { current?.isAlive() == true }

    override val frontAlive: () -> Boolean get() = { false }

    override fun frontExitCode(): Int? = null

    override fun anyRunning(): Boolean = current?.isAlive() == true

    override fun exitCodeOrNull(): Int? = current?.exitCode()

    override fun runningProcesses(): List<Process> = listOfNotNull(current?.child?.takeIf { it.isAlive })

    override fun binaries(): List<Path> = listOfNotNull(runCatching { binary() }.getOrNull())

    override fun stopNow() = stopEngine()

    private companion object {
        const val NAME = "ghostlane-engine"

        /** How long a start waits for the engine to say it serves or failed; past it the manager's own wait decides. */
        const val START_WAIT_MS = 10_000L
    }
}
