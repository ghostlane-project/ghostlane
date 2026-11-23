package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata

/**
 * The core half of a room's session on ghostlane-engine: the core that
 * stands in front of the room in the engine's one process and applies the
 * session's rules to what the room would otherwise carry whole.
 *
 * It is the line's config ([CoreConfig.build]) with the room where the line
 * was, written by the same writers:
 *  - the inbounds of a [CoreInbound.Plan]: proxy mode's on loopback or the
 *    tunnel's, and LAN sharing's beside either (CoreInbound);
 *  - first among the outbounds, so that what no rule claims rides it, the
 *    way to the room, [ROOM]: plain SOCKS5 to the room's port on loopback
 *    with the room's login, one server written flat. The engine points it at
 *    the room's port once the room serves (`front.rs` of ghostlane-engine),
 *    so the address and the port written here only have to parse;
 *  - `direct` and `dns-out` as the line's config writes them, and the
 *    routing: the user's rules ahead of a region's lists (CoreRouting), or
 *    Re:filter's list from the file beside the core (CoreBlockedOnly), with
 *    every rule that sent a connection or a query by the line sending it to
 *    the room.
 *
 * So everything the line's rules do the room's do too, which the olcRTC
 * engine's own direct rules could not: "only blocked sites", and a tunnel
 * rule under a whole-TLD entry such as `.ru`, read before it.
 *
 * What the room is (its carrier, its rooms, its key, its SOCKS port and the
 * login that port asks for) is the room half's, written beside this one;
 * the room half's `socks.user` and `socks.password` are [build]'s
 * `roomLogin`, as the engine demands.
 */
internal object CoreRoomFront {
    /** The tag of the way to the room, the engine's `front::ROOM_OUTBOUND`. */
    const val ROOM = "room"

    /** A room's login with a blank half. */
    const val ROOM_LOGIN_REFUSAL =
        "the room's SOCKS port demands a login: a username and a password, neither of them blank, the room half's own"

    /** Where the way to the room is written to point before the engine points it at the room's port. */
    private const val PLACEHOLDER_ADDRESS = "127.0.0.1"
    private const val PLACEHOLDER_PORT = 1080

    private val LINE_TAG = JsonPrimitive(CoreConfig.LINE)
    private val ROOM_TAG = JsonPrimitive(ROOM)

    /**
     * Why ghostlane-engine cannot run a front listening as [plan] says, in
     * front of a room whose port asks for [roomLogin], under [routing]; null
     * when it can. [refilterDat] says the host puts Re:filter's list in the
     * session's geodata directory, which "only blocked sites" reads.
     */
    fun refusal(
        plan: CoreInbound.Plan,
        roomLogin: SocksLogin,
        routing: Routing = Routing.Global,
        refilterDat: Boolean = false,
    ): String? {
        CoreInbound.refusal(plan)?.let { return it }
        loginRefusal(roomLogin)?.let { return it }
        val rules = CoreRouting.effective(routing) as? Routing.RuleBased ?: return null
        if (rules.policy == Routing.Policy.BlockedOnly && !refilterDat) return CoreConfig.BLOCKED_ONLY_REFUSAL
        return try {
            CoreDns.directResolverAddress(rules.directDns)
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "the Rust core cannot name the resolver for what goes direct"
        }
    }

    /**
     * The core half for a room behind its front: the inbounds of [plan], the
     * way to the room with [roomLogin], and [routing]'s rules. Under a bypass
     * [geodata] carries the region's lists inline; "only blocked sites" is
     * written only when [refilterDat] says the list is beside the core.
     * `log` is at warning (debug with [verboseLogs]) with no access log.
     *
     * Throws IllegalArgumentException with [refusal]'s sentence, and for a
     * bypass without its lists.
     */
    fun build(
        plan: CoreInbound.Plan,
        roomLogin: SocksLogin,
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        verboseLogs: Boolean = false,
        refilterDat: Boolean = false,
    ): String {
        refusal(plan, roomLogin, routing, refilterDat)?.let {
            throw IllegalArgumentException("ghostlane-engine cannot stand a core in front of this room: $it")
        }
        val rules = CoreRouting.effective(routing) as? Routing.RuleBased
        val blockedOnly = rules?.policy == Routing.Policy.BlockedOnly
        require(rules == null || rules.policy !is Routing.Policy.Bypass || geodata != null) {
            "a bypass in front of a room carries its region's lists inline: pass XrayGeodata.lists(region)"
        }
        val rulePlan = rules?.let { RulePlan(it, geodata.takeIf { _ -> it.policy is Routing.Policy.Bypass }) }
        val directResolver = rules?.let { CoreDns.directResolverAddress(it.directDns) }

        val written = buildJsonObject {
            // As CoreConfig.build writes it: no access log, which would name
            // every connection the user makes.
            putJsonObject("log") {
                put("loglevel", if (verboseLogs) "debug" else "warning")
                put("access", "none")
            }
            putDns(directResolver)
            putJsonArray("inbounds") { addInbounds(plan) }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", ROOM)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("address", PLACEHOLDER_ADDRESS)
                        put("port", PLACEHOLDER_PORT)
                        put("user", roomLogin.username)
                        put("pass", roomLogin.password)
                    }
                }
                if (rulePlan != null) addJsonObject { put("tag", "direct"); put("protocol", "freedom") }
                if (blockedOnly) {
                    addBlockedOnlyDnsOutbound(requireNotNull(directResolver), requireNotNull(rulePlan))
                } else {
                    addDnsOutbound(directResolver, rulePlan)
                }
            }
            if (blockedOnly) putBlockedOnlyRouting(requireNotNull(rulePlan)) else putRouting(rulePlan)
        }
        return toTheRoom(written).toString()
    }

    private fun loginRefusal(login: SocksLogin): String? = when {
        login.username.isBlank() || login.password.isBlank() -> ROOM_LOGIN_REFUSAL
        login.username.encodeToByteArray().size > CoreInbound.MAX_CREDENTIAL_BYTES ||
            login.password.encodeToByteArray().size > CoreInbound.MAX_CREDENTIAL_BYTES -> CoreInbound.LONG_LOGIN_REFUSAL
        else -> null
    }

    /**
     * [element] with every `outboundTag` that named the line naming the
     * room: the routing's rules and the `dns` outbound's, wherever the
     * writers put them.
     */
    private fun toTheRoom(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (key == "outboundTag" && value == LINE_TAG) ROOM_TAG else toTheRoom(value)
            }
        )
        is JsonArray -> JsonArray(element.map { toTheRoom(it) })
        else -> element
    }
}
