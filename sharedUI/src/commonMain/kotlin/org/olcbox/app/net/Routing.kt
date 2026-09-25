package org.olcbox.app.net

import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings

/**
 * What sing-box does with a connection: everything through the tunnel, or a
 * regional split where matching destinations use the physical network.
 *
 * A builder-level model, deliberately separate from the persisted setting
 * ([org.olcbox.app.data.model.RoutingMode]): the setting is one word, this is
 * what the platform resolved that word into — where it put the rule-set files
 * and which resolver "direct" traffic may use.
 */
sealed interface Routing {
    /** Everything through the tunnel: the shape every builder emitted before routing existed. */
    data object Global : Routing

    /** What the bundled lists mean under a rule-based routing. */
    sealed interface Policy {
        /** No list: everything rides the tunnel but the user's own direct rules. */
        data object Tunnel : Policy

        /** [region]'s lists and the local network go straight out; the rest rides the tunnel. */
        data class Bypass(val region: String) : Policy

        /**
         * The inverse: only the blocked-in-Russia lists ([RuleSets.blocked]) and the
         * user's own tunnel rules ride the tunnel; everything else goes straight out.
         */
        data object BlockedOnly : Policy
    }

    sealed interface RuleBased : Routing {
        val ruleSetDir: String
        val directDns: DirectDns
        val policy: Policy
        val custom: CustomRules
    }

    /** The original iOS routing shape, kept stable for its Xray and olcRTC paths. */
    data class BypassRussia(
        override val ruleSetDir: String,
        override val directDns: DirectDns
    ) : RuleBased {
        override val policy: Policy = Policy.Bypass("ru")
        override val custom: CustomRules = CustomRules.NONE
    }

    /**
     * Android and desktop: [policy]'s lists, with the user's own rules ([custom])
     * ahead of them. Name resolution follows the traffic: a name bound for the
     * tunnel is resolved through it.
     *
     * [ruleSetDir] holds the files in [RuleSets]. Absolute where the app knows
     * the path (Android); relative to the core's working directory where only
     * the extension does (iOS, [RuleSets.IOS_RELATIVE_DIR]).
     */
    data class Rules(
        override val ruleSetDir: String,
        override val directDns: DirectDns,
        override val policy: Policy,
        override val custom: CustomRules = CustomRules.NONE
    ) : RuleBased {
        /** A bypass region and nothing of the user's: what every caller built before custom rules. */
        constructor(ruleSetDir: String, directDns: DirectDns, region: String) :
            this(ruleSetDir, directDns, Policy.Bypass(region))
    }
}

/**
 * The user's own rules, parsed: [direct] goes straight out and [tunnel] rides the
 * tunnel, whatever the policy's lists say. An entry is in one of them only.
 */
data class CustomRules(val direct: List<RoutingRule>, val tunnel: List<RoutingRule>) {
    val isEmpty: Boolean get() = direct.isEmpty() && tunnel.isEmpty()

    companion object {
        val NONE = CustomRules(emptyList(), emptyList())

        /** From the stored texts; the tunnel list wins an entry both claim, as the screen does. */
        fun of(direct: List<String>, tunnel: List<String>): CustomRules {
            val tunnelRules = RoutingRule.parseAll(tunnel)
            return CustomRules(RoutingRule.parseAll(direct) - tunnelRules.toSet(), tunnelRules)
        }
    }
}

internal val List<RoutingRule>.domains: List<String> get() = filterIsInstance<RoutingRule.Domain>().map { it.name }
internal val List<RoutingRule>.cidrs: List<String> get() = filterIsInstance<RoutingRule.Cidr>().map { it.prefix }

/**
 * The resolver for names that go direct. It must not be the tunnel: the whole
 * point of resolving `sberbank.ru` here is that neither the query nor the
 * connection that follows it leaves through the exit.
 */
sealed interface DirectDns {
    /**
     * The operating system's resolver. Only where the core reaches it without
     * looping through its own tun — desktop. Inside an iOS tunnel the system
     * resolver *is* the tun, and sing-box's darwin `local` transport falls back
     * to exactly that once a tun inbound exists.
     */
    data object System : DirectDns

    /**
     * Explicit resolver addresses as the platform lists them — IP literals,
     * with or without a `%zone`. One is used: sing-box has no failover
     * between servers, so [pick] chooses the first IPv4, else the first
     * global IPv6, else a link-local IPv6 that still carries its zone — the
     * router on an IPv6-only Wi-Fi advertises exactly that, and sing-box
     * dials a zoned address as Go does — else the public fallback.
     */
    data class Servers(val addresses: List<String>) : DirectDns {
        fun pick(): String {
            val hosts = addresses.map { it.trim() }
                .filter { it.isNotEmpty() && !isLoopback(it.substringBefore('%')) }
            return hosts.map { it.substringBefore('%') }.firstOrNull { isIPv4(it) }
                ?: hosts.map { it.substringBefore('%') }.firstOrNull { ':' in it && !isLinkLocal(it) }
                ?: hosts.firstOrNull { isLinkLocal(it.substringBefore('%')) && '%' in it }
                ?: SingBoxConfig.DIRECT_DNS_FALLBACK
        }

        private fun isIPv4(host: String): Boolean =
            host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

        private fun isLinkLocal(host: String): Boolean = host.startsWith("fe80:", ignoreCase = true)

        private fun isLoopback(host: String): Boolean = host.startsWith("127.") || host == "::1"
    }

    /**
     * iOS. The extension learns the network's resolver only after the config
     * has been written — it reads it just before the tunnel's settings replace
     * the system resolver with our own tun — so the builder emits
     * [SingBoxConfig.DIRECT_DNS_PLACEHOLDER] and the extension replaces it.
     */
    data object Placeholder : DirectDns
}

/** The policy [RoutingMode] asks of a rule-based routing; Global's is Tunnel (only the user's rules). */
fun RoutingMode.policy(): Routing.Policy = when (this) {
    RoutingMode.Global -> Routing.Policy.Tunnel
    RoutingMode.BlockedOnly -> Routing.Policy.BlockedOnly
    else -> Routing.Policy.Bypass(requireNotNull(region))
}

/** These settings as the builders take them: rules in [ruleSetDir], names direct through [directDns]. */
fun RoutingSettings.toRules(ruleSetDir: String, directDns: DirectDns): Routing.Rules =
    Routing.Rules(
        ruleSetDir = ruleSetDir,
        directDns = directDns,
        policy = mode.policy(),
        custom = CustomRules.of(directRules, tunnelRules)
    )
