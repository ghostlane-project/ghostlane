package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.SocksLogin

/**
 * What the app and the root daemon say to each other: one JSON object per line,
 * one reply per request, connection closed after it.
 *
 * Line-delimited rather than length-prefixed because every message here is small
 * and because a person debugging this can type one into `nc -U` and read the
 * answer. That has already been worth more than the framing it gives up.
 */
internal sealed interface DaemonReply {
    data class Ok(
        val state: String,
        val pid: Int?,
        val logTail: String,
        /** What the daemon speaks; the first daemons said nothing and are 1. */
        val protocol: Int = TunnelDaemonProtocol.PROTOCOL_FIRST,
        /** Whether the kill switch's block stands (`MacKillSwitch`); a daemon from before it has none. */
        val holds: Boolean = false,
        /** The process of the line's program the daemon runs for the app; 0 for none. */
        val line: Long = 0,
        /** Where the daemon's copy of that program lies, in the reply to the request that started it. */
        val program: String? = null
    ) : DaemonReply

    data class Failure(
        val message: String,
        val logTail: String,
        /** The daemon found a newer binary on disk and is stepping aside for it. */
        val restarting: Boolean = false,
        /** As [Ok.holds]: a start that failed behind a block it had loaded leaves the block. */
        val holds: Boolean = false
    ) : DaemonReply

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"
    }
}

/**
 * The kill switch's block as a start asks the daemon for it (`TrafficHold` in
 * the daemon): the tun's interface, named in the tunnel's config so that a
 * rule can name it before it exists, and the addresses let out beside the
 * tunnel, which are the ones the tun keeps out of itself.
 */
internal data class DaemonHold(val interfaceName: String, val passes: List<String> = emptyList())

/**
 * hev's tun as the fifth daemon runs it (`tun`): fields, never a config. hev's
 * own config can name programs it runs as its tun goes up and down, and the
 * daemon runs hev as root, so the daemon writes that config itself from these
 * and refuses a request with a field it does not know, a script's above all.
 */
internal data class DaemonTun(
    /** The utun hev opens: the name the kill switch's block is written for. */
    val interfaceName: String,
    /** The SOCKS5 port on the loopback hev hands what it takes to. */
    val socksPort: Int,
    /** The login that port asks for; null for one that asks for none. */
    val login: SocksLogin? = null,
    val socksAddress: String = LOOPBACK,
    val mtu: Int = MTU,
    val ipv4: String = IPV4,
    /** Claimed so that no IPv6 leaves beside the tunnel (docs/macos-tunnel-daemon.md, IPv6). */
    val ipv6: String? = IPV6,
    /** hev answers the names asked of it with addresses of its own and asks the SOCKS port by name. */
    val mapDns: DaemonMapDns? = DaemonMapDns(),
    /** The address the system is to ask for names while the tunnel runs; null leaves it asking whom it asks. */
    val names: String? = null,
    val hold: DaemonHold? = null
) {
    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MTU = 1500
        const val IPV4 = "172.19.0.1"
        const val IPV6 = "fdfe:dcba:9876::1"
    }
}

/** hev's own resolver: where it answers, and the network whose addresses it hands out, one a name. */
internal data class DaemonMapDns(
    val address: String = ADDRESS,
    val port: Int = 53,
    val network: String = "198.18.0.0",
    val netmask: String = "255.254.0.0",
    val cacheSize: Int = 10_000
) {
    companion object {
        const val ADDRESS = "172.19.0.2"
    }
}

internal object TunnelDaemonProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The config travels as a JSON *string*, not as a nested object: the daemon
     * writes it to disk byte for byte, and a re-serialised copy is a copy that
     * the `sing-box check` in CI never saw.
     */
    fun startRequest(
        config: String,
        files: Map<String, String> = emptyMap(),
        /** The address the system is to ask for names while this tunnel runs; null leaves it asking whom it asks. */
        names: String? = null,
        hold: DaemonHold? = null
    ): String =
        line(
            buildJsonObject {
                put("verb", "start")
                put("config", config)
                // Rule-set binaries the config refers to, file name → base64. The
                // daemon writes them next to the config, root-owned, and removes
                // the directory when there are none, so a stale list cannot
                // outlive the routing choice that put it there.
                if (files.isNotEmpty()) {
                    putJsonObject("files") { files.forEach { (name, base64) -> put(name, base64) } }
                }
                if (names != null) put("names", names)
                if (hold != null) {
                    putJsonObject("hold") {
                        put("interface", hold.interfaceName)
                        putJsonArray("pass") { hold.passes.forEach { add(it) } }
                    }
                }
            }
        )

    /**
     * The daemon's own directory, as `TunnelChild.stateDir` names it in Swift.
     * Duplicated here because the config the app builds has to name absolute
     * paths inside it — the rule-sets and the cache file — before the daemon
     * ever sees the request. The two literals must agree.
     */
    const val STATE_DIR = "/Library/Application Support/org.olcbox.app"
    const val RULES_DIR = "$STATE_DIR/rules"
    const val CACHE_FILE = "$STATE_DIR/cache.db"

    /**
     * Mirror of the daemon's `protocolVersion` history: 1 said nothing, 2 takes
     * rule files, 3 points the system's resolver at the tun and holds the kill
     * switch's block, 4 runs a line's own program for the app, 5 runs hev.
     */
    const val PROTOCOL_FIRST = 1
    const val PROTOCOL_FILES = 2
    const val PROTOCOL_HOLD = 3

    /**
     * `line` and `line-stop`: the daemon runs a line's own program for the
     * app, as the app's user and in the group the kill switch's block lets
     * out. A room's engine binds its sockets to the address of the interface
     * that carries the internet, and what is bound that way leaves beside any
     * tunnel, in the engine's own name, where the block refuses it.
     */
    const val PROTOCOL_LINE = 4

    /**
     * `tun`: hev in place of sing-box, its config written by the daemon from
     * the request's fields ([tunRequest]). The sing-box verbs stay beside it.
     */
    const val PROTOCOL_HEV = 5

    /** hev's tunnel. One line, as every request; the daemon reads only the fields named here. */
    fun tunRequest(tun: DaemonTun): String =
        line(
            buildJsonObject {
                put("verb", "tun")
                putJsonObject("tun") {
                    put("name", tun.interfaceName)
                    put("mtu", tun.mtu)
                    put("ipv4", tun.ipv4)
                    if (tun.ipv6 != null) put("ipv6", tun.ipv6)
                }
                putJsonObject("socks") {
                    put("address", tun.socksAddress)
                    put("port", tun.socksPort)
                    if (tun.login != null) {
                        put("username", tun.login.username)
                        put("password", tun.login.password)
                    }
                }
                if (tun.mapDns != null) {
                    putJsonObject("mapdns") {
                        put("address", tun.mapDns.address)
                        put("port", tun.mapDns.port)
                        put("network", tun.mapDns.network)
                        put("netmask", tun.mapDns.netmask)
                        put("cacheSize", tun.mapDns.cacheSize)
                    }
                }
                if (tun.names != null) put("names", tun.names)
                if (tun.hold != null) {
                    putJsonObject("hold") {
                        put("interface", tun.hold.interfaceName)
                        putJsonArray("pass") { tun.hold.passes.forEach { add(it) } }
                    }
                }
            }
        )

    /** Where the daemon keeps its copy of a line's program: the path a tun names it by. */
    const val LINES_DIR = "/Library/Application Support/org.olcbox.app.lines"

    /** [program] run with [arguments], what it prints appended to [output], a file of the app's that is there. */
    fun lineRequest(program: String, arguments: List<String>, output: String): String =
        line(
            buildJsonObject {
                put("verb", "line")
                put("program", program)
                putJsonArray("arguments") { arguments.forEach { add(it) } }
                put("output", output)
            }
        )

    fun lineStopRequest(): String = line(buildJsonObject { put("verb", "line-stop") })

    /** Mirror of the plist's `Label`; what `launchctl` calls the daemon. */
    const val LABEL = "org.olcbox.app.desktopApp.tunneld"

    /**
     * A stop ends the session, the block included, unless it says to keep the
     * block: the stop of a session that was not ended by a Disconnect.
     */
    fun stopRequest(keepHold: Boolean = false): String =
        line(
            buildJsonObject {
                put("verb", "stop")
                if (keepHold) put("hold", "keep")
            }
        )

    /** The block alone is taken away; a tunnel that runs goes on running. */
    fun releaseRequest(): String = line(buildJsonObject { put("verb", "release") })

    fun statusRequest(): String = line(buildJsonObject { put("verb", "status") })

    private fun line(obj: JsonObject): String = obj.toString() + "\n"

    fun parseReply(raw: String): DaemonReply {
        val obj = runCatching { json.parseToJsonElement(raw.trim()) as? JsonObject }
            .getOrNull()
            ?: return DaemonReply.Failure(
                "the tunnel daemon replied with something that is not JSON",
                raw.take(TAIL_LIMIT)
            )
        val tail = obj.str("logTail").orEmpty()
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: return DaemonReply.Failure("the tunnel daemon's reply carried no verdict", tail)
        val holds = (obj["hold"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!ok) {
            return DaemonReply.Failure(
                obj.str("error") ?: "the tunnel daemon reported a failure",
                tail,
                restarting = (obj["restarting"] as? JsonPrimitive)?.booleanOrNull ?: false,
                holds = holds
            )
        }
        val state = obj.str("state")
            ?: return DaemonReply.Failure("the tunnel daemon reported success without a state", tail)
        return DaemonReply.Ok(
            state,
            (obj["pid"] as? JsonPrimitive)?.intOrNull,
            tail,
            protocol = (obj["protocol"] as? JsonPrimitive)?.intOrNull ?: PROTOCOL_FIRST,
            holds = holds,
            line = (obj["line"] as? JsonPrimitive)?.longOrNull ?: 0,
            program = obj.str("program")
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Enough of an unparseable reply to recognise it in a log, and no more. */
    private const val TAIL_LIMIT = 400
}
