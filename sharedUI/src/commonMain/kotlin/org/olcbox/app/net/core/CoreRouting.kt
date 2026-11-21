package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.Routing
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.cidrs
import org.olcbox.app.net.domains

/** What goes by the line and what goes straight out. */
object CoreRouting {
    /**
     * [routing] as the core's config builds it. Global's policy with none of the user's direct
     * rules sends everything through the line, whatever its tunnel rules say: it is built as
     * Global, names no resolver of the network, and comes out as Global's config byte for byte.
     */
    fun effective(routing: Routing): Routing =
        if (routing is Routing.RuleBased && routing.policy == Routing.Policy.Tunnel && routing.custom.direct.isEmpty()) {
            Routing.Global
        } else {
            routing
        }
}

/**
 * The user's own rules and the policy's lists, in Xray's inline spelling: `domain:` is the name
 * and every name under it, as sing-box's `domain_suffix` the Go path writes. Each entry is in
 * one of the user's lists only (CustomRules.of: the tunnel's wins). No `geosite:`/`geoip:`: the
 * core is handed no `.dat` file on the phone.
 */
internal class RulePlan(rules: Routing.RuleBased, val lists: XrayGeodata.Lists?) {
    val policy = rules.policy
    val directNames = rules.custom.direct.domains.map { "domain:$it" }
    val directIps = rules.custom.direct.cidrs
    val tunnelNames = rules.custom.tunnel.domains.map { "domain:$it" }
    val tunnelIps = rules.custom.tunnel.cidrs
}

/**
 * In an order that matters, as sing-box's iOS route: the system's queries
 * to the `dns` outbound, TCP and UDP, matched by inbound so dns-direct's
 * own are not caught; then, under the bypass, dns-direct's queries out
 * directly, the local network, the region's names and its addresses.
 * Everything else falls to the line. `AsIs`: no name is resolved just to try
 * the address rules against it.
 */
internal fun JsonObjectBuilder.putRouting(plan: RulePlan?) {
    putJsonObject("routing") {
        put("domainStrategy", "AsIs")
        putJsonArray("rules") {
            addJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(CoreInbound.TAG) }
                put("network", "tcp,udp"); put("port", 53)
                put("outboundTag", "dns-out")
            }
            if (plan != null) {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add(CoreDns.DNS_DIRECT) }
                    put("outboundTag", "direct")
                }
                route("ip", XrayConfig.PRIVATE_RANGES, "direct")
                // The user's rules win over the region's lists, as on the Go path. Xray ANDs a
                // rule's fields, so names and addresses are rules of their own (sing-box ORs
                // them in one).
                route("domain", plan.directNames, "direct")
                route("ip", plan.directIps, "direct")
                route("domain", plan.tunnelNames, CoreConfig.LINE)
                route("ip", plan.tunnelIps, CoreConfig.LINE)
                plan.lists?.let {
                    route("domain", it.domains, "direct")
                    route("ip", it.cidrs, "direct")
                }
            }
        }
    }
}

/** A routing rule on one field; none for an empty list: the core refuses a rule with no condition, and the config with it. */
private fun JsonArrayBuilder.route(field: String, values: List<String>, outbound: String) {
    if (values.isEmpty()) return
    addJsonObject {
        put("type", "field")
        putJsonArray(field) { values.forEach { add(it) } }
        put("outboundTag", outbound)
    }
}
