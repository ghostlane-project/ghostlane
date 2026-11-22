package org.olcbox.app.net.core

import org.olcbox.app.net.XrayGeodata

/**
 * "Only blocked sites" on the Rust core: what Re:filter lists as blocked in
 * Russia goes by the line, everything else straight out, the user's own
 * direct and tunnel rules first. The Go cores' mode (SingBoxConfig's
 * BlockedOnly), from the same Re:filter release.
 *
 * The list is one file, [FILE], read by the core from its geodata directory
 * and named by [NAMES] and [ADDRESSES]. The host puts the file there and says
 * so ([CoreConfig.build]'s `refilterDat`); without it the mode is refused
 * ([CoreConfig.BLOCKED_ONLY_REFUSAL]), since the core refuses the whole config
 * over a rule whose file it cannot find.
 *
 * Routing: what every mode with rules writes first ([putRouting] with no
 * lists: the system's name queries to the core's DNS, dns-direct's own
 * queries out, the local network out, the user's direct and then tunnel
 * rules), then the listed names and the listed addresses by the line, then
 * any other address straight out. `IPIfNonMatch`: a name no rule claims (it
 * reaches the core by name in proxy mode, and on the desktops, whose tun
 * hands the core names) is resolved on the network underneath and its
 * address held to the rules, as the Go cores' `resolve` does, so a site
 * blocked by its address alone still goes by the line. A name the network
 * cannot resolve goes by the line too: what no rule claims rides it
 * ([CoreConfig.LINE] is the first outbound). An address the tun hands the
 * core is never resolved; a fake one has become its name before the rules.
 *
 * DNS (the `dns` outbound): the user's direct and tunnel names as under a
 * bypass ([addDnsOutbound]'s own rules), then a listed name answered as
 * every tunnel name is, a fake A, no AAAA, HTTPS refused, and every other
 * name asked of the network's own resolver, every type. A listed name's
 * other types (SRV, TXT, MX) are refused, where a user's tunnel name has
 * them forwarded through the line: that would be a second rule holding the
 * names. Each rule that names the list counts its 81,000 names against the
 * core's budget of 250,000 a config, and holds about 4 MiB of them once
 * parsed (the core's own measure of an ext: list), so the list is named
 * twice, once in the routing and once in the DNS, and a third would
 * leave the user's own rules almost nothing.
 */
object CoreBlockedOnly {
    /** The file the host puts in the core's geodata directory. */
    val FILE: XrayGeodata.File = XrayGeodata.REFILTER

    /** Re:filter's names, each with every name under it. */
    val NAMES: String = "ext:${FILE.name}:${XrayGeodata.REFILTER_NAMES}"

    /** Re:filter's addresses. */
    val ADDRESSES: String = "ext:${FILE.name}:${XrayGeodata.REFILTER_ADDRESSES}"

    /** Every address: the last routing rule's, which sends what nothing claimed straight out. */
    internal val EVERY_ADDRESS = listOf("0.0.0.0/0", "::/0")

    /** The routing's strategy in this mode: a name no rule claims is resolved and its address asked. */
    internal const val DOMAIN_STRATEGY = "IPIfNonMatch"
}
