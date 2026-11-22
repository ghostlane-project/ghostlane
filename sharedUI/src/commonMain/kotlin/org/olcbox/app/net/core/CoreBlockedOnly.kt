package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.net.Routing
import org.olcbox.app.net.XrayGeodata

/**
 * "Only blocked sites" on the Rust core: what Re:filter lists as blocked in
 * Russia goes by the line, everything else straight out, the user's own
 * direct and tunnel rules first. The mode as the Go cores route it, from
 * the same Re:filter release.
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

/**
 * The routing of the blocked-only mode: [putRouting]'s for [plan] (whose
 * policy is BlockedOnly, so it carries no region's lists), then the list's
 * rules and the last one.
 */
internal fun JsonObjectBuilder.putBlockedOnlyRouting(plan: RulePlan) {
    require(plan.policy == Routing.Policy.BlockedOnly && plan.lists == null) { "not a blocked-only plan" }
    val shared = buildJsonObject { putRouting(plan) }.getValue("routing").jsonObject
    val rules = buildJsonArray {
        shared.getValue("rules").jsonArray.forEach { add(it) }
        routeTo("domain", CoreBlockedOnly.NAMES, CoreConfig.LINE)
        routeTo("ip", CoreBlockedOnly.ADDRESSES, CoreConfig.LINE)
        addJsonObject {
            put("type", "field")
            putJsonArray("ip") { CoreBlockedOnly.EVERY_ADDRESS.forEach { add(it) } }
            put("outboundTag", "direct")
        }
    }
    put("routing", JsonObject(shared + ("domainStrategy" to JsonPrimitive(CoreBlockedOnly.DOMAIN_STRATEGY)) + ("rules" to rules)))
}

/**
 * The `dns` outbound of the blocked-only mode: [addDnsOutbound]'s for [plan]
 * up to the rules every config ends with (what it writes with no plan at
 * all), which here would send every other name through the line; then the
 * listed names, faked, and every other name asked of the network.
 */
internal fun JsonArrayBuilder.addBlockedOnlyDnsOutbound(directResolver: String, plan: RulePlan) {
    require(plan.policy == Routing.Policy.BlockedOnly && plan.lists == null) { "not a blocked-only plan" }
    val shared = buildJsonArray { addDnsOutbound(directResolver, plan) }.single().jsonObject
    val settings = shared.getValue("settings").jsonObject
    val all = settings.getValue("rules").jsonArray
    val tail = buildJsonArray { addDnsOutbound(directResolver, null) }.single().jsonObject
        .getValue("settings").jsonObject.getValue("rules").jsonArray
    check(all.size >= tail.size && all.takeLast(tail.size) == tail) {
        "the dns outbound no longer ends with the rules it writes with no plan: write the blocked-only rules anew"
    }
    val rules = buildJsonArray {
        all.dropLast(tail.size).forEach { add(it) }
        addJsonObject {
            put("action", "hijack")
            putJsonArray("domain") { add(CoreBlockedOnly.NAMES) }
            put("rCode", CoreDns.RCODE_REFUSED)
        }
        // No condition: every name and every type the rules above left.
        addJsonObject { put("action", "direct") }
    }
    add(JsonObject(shared + ("settings" to JsonObject(settings + ("rules" to rules)))))
}

private fun JsonArrayBuilder.routeTo(field: String, value: String, outbound: String) {
    addJsonObject {
        put("type", "field")
        putJsonArray(field) { add(value) }
        put("outboundTag", outbound)
    }
}
