package org.olcbox.app.net.core

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.SocksLogin

/**
 * The core of a LAN listener that is not in the session's own core: one
 * ghostlane-engine run whose only way out is the session's local SOCKS port.
 *
 * A session's core holds the LAN inbound itself when it is a core line
 * ([CoreInbound.Plan]). Any other endpoint cannot: a room's SOCKS port is the
 * olcRTC engine's, a room with no rules has no front to put an inbound in, and
 * a line that was started without the inbound (a tunnel the helper started, a
 * LAN switched on after the connect) would have to be started again, a room
 * joined again. What those have in common is a SOCKS port on loopback, with
 * a login or without one, and that is all this core needs: its LAN inbound
 * ([CoreInbound.Lan], the same one a core line gets) hands every connection
 * and every datagram to that port. What the session's rules do, its front or
 * its core does, so a LAN client follows them as the device's own apps do.
 *
 * The inbound the plan needs of its own is a loopback one with a login the
 * caller draws; it is how the caller proves that this run, and not another
 * program, holds the LAN port.
 *
 * The outbound is written as [CoreRoomFront] writes the way to a room, the
 * one the engine runs in front of a room: plain SOCKS5 with UDP ASSOCIATE,
 * one server written flat.
 */
internal object CoreLanChain {
    /** The tag of the way to the session's port. */
    const val UPSTREAM = CoreConfig.LINE

    /** Why this core cannot be written for [plan] to [upstreamPort], or null when it can. */
    fun refusal(plan: CoreInbound.Plan, upstreamPort: Int, upstreamLogin: SocksLogin?): String? {
        CoreInbound.refusal(plan)?.let { return it }
        if (upstreamPort !in 1..65535) return CoreInbound.PORT_REFUSAL
        if (plan.lan?.port == upstreamPort) return CoreInbound.LAN_PORT_REFUSAL
        if (upstreamLogin != null && upstreamLogin.username.isBlank() != upstreamLogin.password.isBlank()) {
            return CoreInbound.HALF_LOGIN_REFUSAL
        }
        if (upstreamLogin != null && (
                upstreamLogin.username.encodeToByteArray().size > CoreInbound.MAX_CREDENTIAL_BYTES ||
                    upstreamLogin.password.encodeToByteArray().size > CoreInbound.MAX_CREDENTIAL_BYTES
                )
        ) {
            return CoreInbound.LONG_LOGIN_REFUSAL
        }
        return null
    }

    /**
     * core.json: [plan]'s inbounds, then the one way out, SOCKS5 to
     * [upstreamHost]:[upstreamPort] with [upstreamLogin] (none when null or
     * blank), and no rule: everything takes it. `log` is at warning (debug
     * with [verboseLogs]) with no access log, as every core of the app.
     *
     * Throws IllegalArgumentException with [refusal]'s sentence.
     */
    fun build(
        plan: CoreInbound.Plan,
        upstreamHost: String,
        upstreamPort: Int,
        upstreamLogin: SocksLogin?,
        verboseLogs: Boolean = false,
    ): String {
        val login = upstreamLogin?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
        refusal(plan, upstreamPort, upstreamLogin)?.let {
            throw IllegalArgumentException("ghostlane-engine cannot carry LAN sharing so: $it")
        }
        return buildJsonObject {
            putJsonObject("log") {
                put("loglevel", if (verboseLogs) "debug" else "warning")
                put("access", "none")
            }
            putDns(null)
            putJsonArray("inbounds") { addInbounds(plan) }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", UPSTREAM)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("address", upstreamHost)
                        put("port", upstreamPort)
                        if (login != null) {
                            put("user", login.username)
                            put("pass", login.password)
                        }
                    }
                }
                addDnsOutbound(null, null)
            }
            putRouting(null)
        }.toString()
    }
}
