package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/**
 * What a Shadowsocks line's outbound holds: the `shadowsocks` protocol with
 * one flattened server, and no stream settings. The list is copied from the
 * core's `surface.rs` (SHADOWSOCKS), at the commit [XrayRustSurface] names; the
 * writer uses four of its fields.
 */
object ShadowsocksSurface : CoreSurfaceFragment {
    val SHADOWSOCKS = setOf("address", "port", "password", "level", "email", "method", "uot", "UoTVersion", "servers")

    override val line: CoreLine = Shadowsocks

    override val protocols: Map<String, SurfaceCheck> = mapOf("shadowsocks" to XrayRustSurface.fields(SHADOWSOCKS))

    override val stream: Map<String, SurfaceCheck> = emptyMap()
}
