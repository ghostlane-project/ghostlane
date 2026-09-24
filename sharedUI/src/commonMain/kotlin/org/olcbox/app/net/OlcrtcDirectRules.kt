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

    /** What the engine is handed for [routing]: its region's rules, none under Global. */
    suspend fun forRouting(routing: Routing): String = when (routing) {
        Routing.Global -> NONE
        is Routing.RuleBased -> text(routing.region)
    }

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
