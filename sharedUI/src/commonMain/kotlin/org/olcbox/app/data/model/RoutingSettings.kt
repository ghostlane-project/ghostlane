package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What leaves through the tunnel. */
@Serializable
enum class RoutingMode {
    /** Everything. The exit is the server's, for every connection. */
    @SerialName("global")
    Global,

    /**
     * Russian sites, Russian TLDs and the local network go straight out; the
     * rest rides the tunnel, name resolution included. The lists are bundled
     * (see `RuleSets`), so this needs nothing from the network to work.
     */
    @SerialName("bypass_russia")
    BypassRussia,
    @SerialName("bypass_iran")
    BypassIran,
    @SerialName("bypass_china")
    BypassChina;

    val region: String? get() = when (this) {
        Global -> null
        BypassRussia -> "ru"
        BypassIran -> "ir"
        BypassChina -> "cn"
    }

    fun title(): String = when (this) {
        Global -> "All traffic through the tunnel"
        BypassRussia -> "Bypass Russia"
        BypassIran -> "Bypass Iran"
        BypassChina -> "Bypass China"
    }

    fun summary(): String = when (this) {
        Global -> "Every connection leaves through the tunnel. Your exit is the server's."
        BypassRussia -> "Russian sites, .ru domains and your local network go straight out. Everything else rides the tunnel, DNS included."
        BypassIran -> "Iranian destinations and your local network connect directly. Other traffic uses the VPN."
        BypassChina -> "Chinese destinations and your local network connect directly. Other traffic uses the VPN."
    }

    /** The one line the settings hub shows. */
    fun hubSummary(): String = when (this) {
        Global -> "Everything through the tunnel"
        BypassRussia -> "Russia and local network direct"
        BypassIran -> "Iran and local network direct"
        BypassChina -> "China and local network direct"
    }
}

/**
 * Carried inside [LocationBundleV4] for the reason [SubscriptionSettings] is:
 * one persisted copy per device, identical on every platform. A field with a
 * default reads back cleanly from a bundle written before it existed.
 */
@Serializable
data class RoutingSettings(
    @SerialName("mode")
    val mode: RoutingMode = RoutingMode.Global,
    @SerialName("block_ads")
    val blockAds: Boolean = false,
    /** Do not publish or route IPv6 destinations unless explicitly enabled. */
    @SerialName("disable_ipv6")
    val disableIpv6: Boolean = true,
    /** Include connection and routing decisions in core logs until disabled. */
    @SerialName("verbose_debug_logs")
    val verboseDebugLogs: Boolean = false
)
