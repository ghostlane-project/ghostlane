package org.olcbox.app.vpn.desktop.engine.host

import java.nio.file.Path

/** Where ghostlane-engine is found. Not written yet. */
internal object HostAssets {
    const val ENV = "GHOSTLANE_ENGINE_BINARY"
    const val PROTOCOL = 1

    fun resolve(): Path = error("ghostlane-engine's host is not written yet")
}
