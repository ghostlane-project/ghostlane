package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig

/** How the desktop names a room's carrier to the engine. */
internal object OlcRtcProvider {
    fun desktopProviderArg(provider: String): String {
        val normalizedProvider = LocationConfig.normalizeProvider(provider)
        return when (normalizedProvider) {
            LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
            else -> normalizedProvider
        }
    }
}
