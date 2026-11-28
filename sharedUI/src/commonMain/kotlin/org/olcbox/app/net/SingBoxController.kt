package org.olcbox.app.net

/**
 * Starts/stops the bundled sing-box (SOCKS inbound): what the desktop's LAN
 * listener still runs ([org.olcbox.app.vpn.LanCores]). Every line and room of
 * a session runs on ghostlane-engine and is not started through this.
 */
interface SingBoxController {
    suspend fun start(configJson: String)
    suspend fun stop()
}
