package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreRoomFront
import org.olcbox.app.net.core.CoreRouting
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.engine.RoomEngine
import org.olcbox.app.vpn.desktop.engine.RoomLaunch
import org.olcbox.app.vpn.desktop.engine.RoomRequest
import java.nio.file.Path

/**
 * A room on ghostlane-engine: the same program that runs a line, given a
 * session with a room half. The manager runs it as it ran the olcRTC engine
 * (it writes the session file, starts the command, reads what it prints and
 * watches it); what differs is what the file holds and how its output is
 * read ([HostEvents]).
 *
 * The front before the room, with the session's rules, is the session's core
 * half ([CoreRoomFront]) and runs in the same process, on the port the
 * request names: [frontInRoom]. The engine's own sockets leave by the way out
 * the session names (`bind`), as the user, so a room needs no root beside the
 * Linux tunnel ([rootBesideLinuxTun]).
 *
 * [binary] is the program ([HostAssets.resolve]); [defaultInterface] the
 * interface a room beside a tunnel is bound to when the request names none.
 */
internal class HostRoom(
    private val binary: () -> Path = HostAssets::resolve,
    private val log: (String) -> Unit = {},
    private val defaultInterface: () -> String? = { null },
) : RoomEngine {
    @Volatile private var checked: Path? = null

    override fun binaries(): List<Path> = listOf(binary())

    override val rootBesideLinuxTun: Boolean = false

    override val frontInRoom: Boolean = true

    override fun launch(binary: Path, request: RoomRequest): RoomLaunch {
        checkProgram(binary)
        val bind = request.bindInterface ?: if (request.bindToWayOut) defaultInterface() else null
        if (request.bindToWayOut && bind == null) {
            log("$NAME: no default interface was found, so the room's sockets are not bound to one")
        }
        // Refused here, before anything is started or written.
        // "Only blocked sites" reads Re:filter's list from the session's geodata directory, as a line does.
        val geodataDir = if (HostRoomSession.blockedOnly(request)) runBlocking { HostGeodata.refilterDir() } else null
        val session = HostRoomSession.text(request, bind, geodataDir)
        return object : RoomLaunch {
            override val provider: String = OlcRtcCommand.desktopProviderArg(request.location.bypassProvider)
            override fun config(): String = session
            override fun args(configPath: Path): List<String> =
                listOf(binary.toString(), "run", "--session", configPath.toString())

            override fun saysReady(line: String): Boolean = HostEvents.parse(line) is HostEvent.Ready
            override fun saysFailed(line: String): Boolean = HostRoomSession.failed(HostEvents.parse(line))
            override fun said(line: String): String = HostRoomSession.said(line)
        }
    }

    /** Once per program: a program of another protocol is refused, and what it says of itself goes into the log. */
    private fun checkProgram(program: Path) {
        if (checked == program) return
        val version = HostAssets.version(program) ?: error("$NAME printed no version: $program is not a program this app runs")
        HostAssets.refusal(version)?.let { error(it) }
        log(HostAssets.describe(version))
        checked = program
    }

    private companion object {
        const val NAME = "ghostlane-engine"
    }
}

/** What a room's session holds, and how the engine's words about it are read. Pure. */
internal object HostRoomSession {
    /**
     * Keeps LAN, link-local and CGNAT traffic off a room that has no front, in
     * the engine's `route.direct` syntax: the room's own half of what the
     * front's rules do for a fronted one.
     */
    const val PRIVATE_DIRECT_RULES =
        "10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n100.64.0.0/10\n169.254.0.0/16\n127.0.0.0/8\nfc00::/7\nfe80::/10\n"

    /**
     * The session file's text for [request]: the room half (its carrier, rooms,
     * key, the resolver and the SOCKS port with its login), the front as the
     * core half when the request has one, and the interface to bind to.
     * [geodataDir] is the session's geodata directory with Re:filter's list in
     * it ([HostGeodata]); a front under "only blocked sites" is refused without it.
     * Throws IllegalStateException with a sentence for a front the core refuses.
     */
    fun text(request: RoomRequest, bind: String?, geodataDir: String? = null): String {
        val roomLogin = SocksLogin(request.socksUser, request.socksPass)
        val core = request.front?.let { front ->
            val plan = CoreInbound.Plan(CoreInbound.Proxy(front.port, front.login))
            CoreRoomFront.refusal(plan, roomLogin, front.routing, refilterDat = geodataDir != null)?.let {
                throw IllegalStateException("${HostSession.REFUSED}: $it")
            }
            CoreRoomFront.build(plan, roomLogin, front.routing, front.geodata, front.verboseLogs, refilterDat = geodataDir != null)
        }
        return HostSession.text(core = core, room = half(request, roomLogin, fronted = core != null).toString(), bind = bind, geodataDir = geodataDir)
    }

    /** Whether [request]'s front asks for "only blocked sites", which needs the geodata directory. */
    fun blockedOnly(request: RoomRequest): Boolean =
        request.front?.let { (CoreRouting.effective(it.routing) as? Routing.RuleBased)?.policy == Routing.Policy.BlockedOnly } ?: false

    /** The room half: what [HostProbe.Room] joins, with the resolver, the SOCKS port and its login. */
    fun half(request: RoomRequest, login: SocksLogin, fronted: Boolean): JsonObject {
        val room = HostProbe.Room.of(request.location, request.deviceId, request.dtlsProfile).half()
        return buildJsonObject {
            room.forEach { (name, value) -> if (name != "device_id" || request.deviceId.isNotBlank()) put(name, value) }
            if (request.dnsServer.isNotBlank()) put("dns", request.dnsServer)
            putJsonObject("socks") {
                put("port", request.socksPort)
                if (login.username.isNotBlank()) {
                    put("user", login.username)
                    put("password", login.password)
                }
            }
            put("udp", true)
            if (!fronted) put("direct_rules", PRIVATE_DIRECT_RULES)
        }
    }

    /** [event] says the start has failed for good: the engine's own `error`, or an `ended` that is a failure. */
    fun failed(event: HostEvent): Boolean =
        event is HostEvent.Failed || (event is HostEvent.Ended && event.reason == HostEndReason.Failed)

    /** [line] as the log says it: the engine's words for an event it printed, else the line as it was. */
    fun said(line: String): String {
        val event = HostEvents.parse(line)
        if (event is HostEvent.Unreadable) return line
        return HostEvents.logLine(event)?.let { "ghostlane-engine: $it" } ?: line
    }

    /** The session text read back, for a test. */
    internal fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
