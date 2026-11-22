package org.olcbox.app.vpn.olc

import org.olcbox.app.data.model.LocationConfig

/**
 * The questions a connect asks about a room before it makes a tunnel. The
 * platform paths call [refusal] first and show its sentence, instead of
 * handing the engine a carrier it will fail on.
 */
object OlcSession {
    /** Why [location] cannot be connected, or null when it can. */
    fun refusal(location: LocationConfig): String? = RustCarriers.refusal(location)
}
