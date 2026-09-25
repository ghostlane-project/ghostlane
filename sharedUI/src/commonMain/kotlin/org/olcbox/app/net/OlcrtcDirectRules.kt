package org.olcbox.app.net

/**
 * A bypass region's rules in the form the olcRTC engine takes them: one rule
 * per line, `domain:`/`full:` for names and CIDR prefixes for addresses — the
 * syntax of Xray's inline lists, which is why the same files serve both
 * engines. The engine dials a matching destination itself, over the physical
 * interface, resolves a matching name on the network's own resolver, and
 * sends everything else through the room (engine `internal/route`).
 *
 * Order: the private ranges first — the local network, loopback, link-local,
 * carrier-grade NAT — then the name lists, then the region's address list. The
 * engine keeps sets and merged ranges, so the order changes nothing about what
 * matches; it only reads well in a log.
 */
object OlcrtcDirectRules {
    /** What the engine is handed under Global: no rules, everything tunnelled. */
    const val NONE = ""

    /** The Russian rules: what iOS hands the engine under Bypass Russia. */
    suspend fun text(): String = text(XrayGeodata.lists())

    /** The rules of one bypass region (`ru`, `ir`, `cn`); another region is an error. */
    suspend fun text(region: String): String = text(XrayGeodata.lists(region))

    /**
     * What the engine is handed for [routing]: none under Global; under a bypass its
     * region's rules with the user's own ([text] with custom rules); otherwise only
     * the user's direct rules. The engine takes "these go direct" and nothing else,
     * so under BlockedOnly a room carries everything but those — the rooms stay
     * outside that mode by design.
     */
    suspend fun forRouting(routing: Routing): String = when (routing) {
        Routing.Global -> NONE
        is Routing.RuleBased -> when (val policy = routing.policy) {
            is Routing.Policy.Bypass -> text(XrayGeodata.lists(policy.region), routing.custom)
            Routing.Policy.Tunnel, Routing.Policy.BlockedOnly -> customOnly(routing.custom)
        }
    }

    /** The user's direct rules alone, or [NONE] when there are none. */
    fun customOnly(custom: CustomRules): String =
        engineLines(custom.direct).takeIf { it.isNotEmpty() }?.joinToString("\n", postfix = "\n") ?: NONE

    /**
     * A region's rules with the user's own: their direct rules added, and the region's
     * names equal to or under one of their tunnel domains left out. The engine has no
     * rule that sends a name into the room, so a tunnel rule can only take away a
     * region entry; a name under a whole-TLD entry (`domain:ru`) stays direct in rooms.
     */
    fun text(lists: XrayGeodata.Lists, custom: CustomRules): String {
        val tunnel = custom.tunnel.domains
        fun carved(rule: String): Boolean {
            val name = rule.substringAfter(':')
            return tunnel.any { name == it || name.endsWith(".$it") }
        }
        return (
            XrayConfig.PRIVATE_RANGES + engineLines(custom.direct) +
                lists.domains.filter { engineTakes(it) && !carved(it) } + lists.cidrs
            ).joinToString("\n", postfix = "\n")
    }

    /** The user's rules in the engine's syntax: `domain:` for a name, the prefix for an address. */
    private fun engineLines(rules: List<RoutingRule>): List<String> =
        rules.domains.map { "domain:$it" } + rules.cidrs

    /**
     * Only the shapes `internal/route` parses: names by `domain:` or `full:`,
     * addresses and prefixes. Xray's lists may also carry `keyword:` and
     * `regexp:`, which the engine refuses, and with them the whole start; the
     * names they describe ride the tunnel. Today that is three lines of
     * v2fly's `cn` and none of `ru` or `ir`.
     */
    fun text(lists: XrayGeodata.Lists): String =
        (XrayConfig.PRIVATE_RANGES + lists.domains.filter(::engineTakes) + lists.cidrs)
            .joinToString("\n", postfix = "\n")

    private fun engineTakes(rule: String): Boolean =
        rule.startsWith("domain:") || rule.startsWith("full:")
}
