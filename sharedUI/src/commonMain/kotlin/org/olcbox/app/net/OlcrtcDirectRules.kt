package org.olcbox.app.net

/**
 * The Bypass Russia rules in the form the olcRTC engine takes them: one rule
 * per line, `domain:`/`full:` for names and CIDR prefixes for addresses — the
 * syntax of Xray's inline lists, which is why the same three files serve both
 * engines. The engine dials a matching destination itself, over the physical
 * interface, resolves a matching name on the network's own resolver, and
 * sends everything else through the room (engine `internal/route`).
 *
 * Order: the private ranges first — the local network, loopback, link-local,
 * carrier-grade NAT — then the name lists, then the Russian address list. The
 * engine keeps sets and merged ranges, so the order changes nothing about what
 * matches; it only reads well in a log.
 */
object OlcrtcDirectRules {
    /** What the engine is handed under Global: no rules, everything tunnelled. */
    const val NONE = ""

    suspend fun text(): String = text(XrayGeodata.lists())

    fun text(lists: XrayGeodata.Lists): String =
        (XrayConfig.PRIVATE_RANGES + lists.domains + lists.cidrs).joinToString("\n", postfix = "\n")
}
