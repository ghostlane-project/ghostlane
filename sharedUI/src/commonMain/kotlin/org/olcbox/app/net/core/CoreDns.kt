package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.Routing
import org.olcbox.app.net.XrayConfig

/** How the core answers names: the `dns` object and the `dns` outbound. */
object CoreDns {
    /**
     * The fake addresses the core hands out for names bound for the tunnel:
     * RFC 2544's benchmarking block, which nothing on the internet answers
     * from, and the range sing-box's shapes hand out. IPv4 only: hev's tun
     * carries no IPv6.
     */
    const val FAKE_IP_POOL = "198.18.0.0/15"

    /**
     * What is written where the direct resolver goes on iOS; the extension
     * replaces it with the network's own before starting the core
     * (`DirectResolver.substitute`). TEST-NET-2, so an unreplaced placeholder
     * can only fail loudly, never resolve.
     */
    const val DIRECT_DNS_PLACEHOLDER = "198.51.100.53"

    /**
     * Yandex DNS. What "direct" resolution uses when the platform offered no
     * resolver the core can be given. Russian because the mode is: it is the
     * resolver most likely to answer on a Russian mobile network that meets
     * 1.1.1.1 with silence, and it answers everywhere else too.
     */
    const val DIRECT_DNS_FALLBACK = "77.88.8.8"

    /** The resolver tag whose queries go straight out. */
    internal const val DNS_DIRECT = "dns-direct"

    /** DNS type HTTPS (RFC 9460), refused as sing-box's iOS shape refuses it. */
    internal const val QTYPE_HTTPS = 65

    /** REFUSED: what sing-box's DNS `reject` answers, and a client reads as "no such record". */
    internal const val RCODE_REFUSED = 5

    /**
     * Every query type but A (1), AAAA (28) and HTTPS (65): what the `dns`
     * outbound's forward rule sends through the line. Type 0 is left out too
     * and gets the outbound's empty answer.
     */
    internal const val QTYPES_FORWARDED = "2-27,29-64,66-65535"

    /**
     * Where names bound direct are asked: the placeholder on iOS, which the
     * extension replaces with the network's own resolver before the core
     * starts (`DirectResolver.substitute`, every quoted occurrence: here two).
     * An explicit list elsewhere, but not a zoned link-local address, which
     * the core's parser refuses: the public fallback stands in for it.
     */
    internal fun directResolverAddress(direct: DirectDns): String = when (direct) {
        DirectDns.Placeholder -> DIRECT_DNS_PLACEHOLDER
        is DirectDns.Servers -> direct.pick().let { if ('%' in it) DIRECT_DNS_FALLBACK else it }
        DirectDns.System -> throw IllegalArgumentException("the Rust core cannot name the system resolver: give it an address")
    }
}

/**
 * Fake addresses for every name bound for the tunnel, IPv4 only as in
 * sing-box's iOS shape. Under the bypass one resolver, `dns-direct`, which
 * only names the direct side resolves: a Russian name handed to `direct`
 * after a sniff (sing-box's `default_domain_resolver`). Its own queries
 * are routed by its tag, so the rule below sends them straight out.
 */
internal fun JsonObjectBuilder.putDns(directResolver: String?) {
    putJsonObject("dns") {
        put("queryStrategy", "UseIPv4")
        putJsonObject("fakeIp") {
            put("enabled", true)
            put("ipv4Pool", CoreDns.FAKE_IP_POOL)
        }
        if (directResolver != null) {
            putJsonArray("servers") {
                addJsonObject {
                    put("tag", CoreDns.DNS_DIRECT)
                    put("address", directResolver); put("port", 53)
                }
            }
        }
    }
}

/**
 * Answers every query the routing sends here. An A query gets a
 * fake address and an AAAA none, as sing-box's `fakeip` server answers
 * them; HTTPS is refused, as sing-box's iOS shape refuses it. Every other
 * type (SRV, TXT, MX, PTR, SVCB…) is forwarded unchanged through the line
 * to the resolver the Go engines ask there, [XrayConfig.REMOTE_DNS_SERVER],
 * as sing-box's `dns-remote` answers it: the `forward` rule, which only
 * xray-rust has (Go's Xray refuses the action, so no Go config carries it).
 * Under the bypass a name on the region's lists is first asked of the
 * network's own resolver, every type, as sing-box sends those names to
 * `dns-direct`. The first rule that matches wins, so the forward rule is
 * last.
 */
internal fun JsonArrayBuilder.addDnsOutbound(directResolver: String?, plan: RulePlan?) {
    addJsonObject {
        put("tag", "dns-out"); put("protocol", "dns")
        putJsonObject("settings") {
            if (directResolver != null) {
                // Where a `direct` rule forwards a query; unset, it would
                // go back to the tun's resolver, which is this outbound.
                put("rewriteAddress", directResolver)
                put("rewritePort", 53)
            }
            putJsonArray("rules") {
                if (plan != null) {
                    // The user's own rules ahead of the region's: a direct name is asked of the
                    // network, every type; a tunnel name is answered as every name the lists
                    // leave to the tunnel, even when the region's list holds it: a fake A (AAAA
                    // empty) and every other type through the line, nothing asked of the
                    // network. (`hijack` answers A and AAAA only and returns its rCode for the
                    // rest, so the forward comes first; HTTPS stays refused, as for every name.)
                    // Under Global no tunnel rule: unclaimed names are the tunnel's already.
                    names("direct", plan.directNames)
                    if (plan.policy != Routing.Policy.Tunnel && plan.tunnelNames.isNotEmpty()) {
                        addJsonObject {
                            put("action", "forward")
                            put("qType", CoreDns.QTYPES_FORWARDED)
                            putJsonArray("domain") { plan.tunnelNames.forEach { add(it) } }
                            put("address", XrayConfig.REMOTE_DNS_SERVER); put("port", 53)
                            put("outboundTag", CoreConfig.LINE)
                        }
                        names("hijack", plan.tunnelNames, CoreDns.RCODE_REFUSED)
                    }
                    plan.lists?.let { names("direct", it.domains) }
                }
                addJsonObject {
                    put("action", "return")
                    put("qType", CoreDns.QTYPE_HTTPS)
                    put("rCode", CoreDns.RCODE_REFUSED)
                }
                addJsonObject {
                    put("action", "forward")
                    put("qType", CoreDns.QTYPES_FORWARDED)
                    put("address", XrayConfig.REMOTE_DNS_SERVER); put("port", 53)
                    put("outboundTag", CoreConfig.LINE)
                }
            }
        }
    }
}

/** A dns-out rule for [domains]; none for an empty list, which would be no condition and match every name. */
private fun JsonArrayBuilder.names(action: String, domains: List<String>, rCode: Int? = null) {
    if (domains.isEmpty()) return
    addJsonObject {
        put("action", action)
        putJsonArray("domain") { domains.forEach { add(it) } }
        if (rCode != null) put("rCode", rCode)
    }
}
