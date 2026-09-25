package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.olcbox.app.net.RoutingRule

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
    BypassChina,

    /**
     * The inverse of the bypass modes: only destinations on the bundled list of
     * sites blocked in Russia (Re:filter, see `RuleSets`) ride the tunnel, and
     * everything else goes straight out. olcRTC rooms still carry everything: the
     * engine takes "these go direct", not "only these go in".
     */
    @SerialName("blocked_only")
    BlockedOnly;

    val region: String? get() = when (this) {
        Global -> null
        BypassRussia -> "ru"
        BypassIran -> "ir"
        BypassChina -> "cn"
        BlockedOnly -> null
    }

    fun title(): String = when (this) {
        Global -> "All traffic through the tunnel"
        BypassRussia -> "Bypass Russia"
        BypassIran -> "Bypass Iran"
        BypassChina -> "Bypass China"
        BlockedOnly -> "Only blocked sites through the tunnel"
    }

    fun summary(): String = when (this) {
        Global -> "Every connection leaves through the tunnel. Your exit is the server's."
        BypassRussia -> "Russian sites, .ru domains and your local network go straight out. Everything else rides the tunnel, DNS included."
        BypassIran -> "Iranian destinations and your local network connect directly. Other traffic uses the VPN."
        BypassChina -> "Chinese destinations and your local network connect directly. Other traffic uses the VPN."
        BlockedOnly -> "Sites blocked in Russia, and services that shut Russian users out, go through the tunnel; " +
            "everything else connects directly. On Android, olcRTC rooms still carry everything."
    }

    /** The one line the settings hub shows. */
    fun hubSummary(): String = when (this) {
        Global -> "Everything through the tunnel"
        BypassRussia -> "Russia and local network direct"
        BypassIran -> "Iran and local network direct"
        BypassChina -> "China and local network direct"
        BlockedOnly -> "Only blocked sites through the tunnel"
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
    /** Opt-in core diagnostics. Off by default because debug logs name destinations. */
    @SerialName("verbose_debug_logs")
    val verboseDebugLogs: Boolean = false,
    /**
     * Your own "always direct" rules, each in [RoutingRule.text]'s spelling. They win
     * over the mode's lists, and in rooms they are what the engine is told to leave
     * out of the room.
     */
    @SerialName("direct_rules")
    val directRules: List<String> = emptyList(),
    /** Your own "always through the tunnel" rules; they win over the mode's lists too. */
    @SerialName("tunnel_rules")
    val tunnelRules: List<String> = emptyList()
) {
    /** Whether anything here asks for rules at all: a mode other than Global, or a rule of your own. */
    val needsRules: Boolean
        get() = mode != RoutingMode.Global || directRules.isNotEmpty() || tunnelRules.isNotEmpty()

    /** [rule] in the direct list, and out of the tunnel list: an entry lives in one list. */
    fun withDirectRule(rule: RoutingRule): RoutingSettings =
        copy(directRules = directRules - rule.text + rule.text, tunnelRules = tunnelRules - rule.text)

    /** [rule] in the tunnel list, and out of the direct list. */
    fun withTunnelRule(rule: RoutingRule): RoutingSettings =
        copy(tunnelRules = tunnelRules - rule.text + rule.text, directRules = directRules - rule.text)

    fun withoutRule(text: String): RoutingSettings =
        copy(directRules = directRules - text, tunnelRules = tunnelRules - text)
}
