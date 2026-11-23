package org.olcbox.app.net.core

import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata

/**
 * The core half of a room's session on ghostlane-engine: the core that stands
 * in front of the room in the engine's one process and applies the session's
 * rules. Not written yet: its tests come first.
 */
internal object CoreRoomFront {
    /** The tag of the way to the room, the engine's `front::ROOM_OUTBOUND`. */
    const val ROOM = "room"

    /** A room's login with a blank half. */
    const val ROOM_LOGIN_REFUSAL =
        "the room's SOCKS port demands a login: a username and a password, neither of them blank, the room half's own"

    /** Why the front cannot be written, or null. */
    fun refusal(
        plan: CoreInbound.Plan,
        roomLogin: SocksLogin,
        routing: Routing = Routing.Global,
        refilterDat: Boolean = false,
    ): String? = null

    /** The core half for a room behind its front. */
    fun build(
        plan: CoreInbound.Plan,
        roomLogin: SocksLogin,
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        verboseLogs: Boolean = false,
        refilterDat: Boolean = false,
    ): String = throw NotImplementedError("the room's front is not written yet")
}
