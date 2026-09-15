package org.olcbox.app.net

import kotlinx.serialization.json.*
import org.olcbox.app.data.model.ConnectionSelection

/** One Xray instance owns all VLESS outbounds; olcRTC is never probed by a group. */
object XrayGroupConfig {
    fun build(
        group: VlessGroup,
        socksPort: Int = XrayConfig.XRAY_SOCKS_PORT,
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        answersDns: Boolean = false,
    ): String {
        require(group.mode != ConnectionSelection.Manual)
        require(group.members.isNotEmpty() && group.members.size <= VlessGroup.MAX_MEMBERS)
        require(group.fallbackIndex in group.members.indices)
        require(socksPort !in VlessGroup.PROBE_PORT until VlessGroup.PROBE_PORT + group.members.size)
        val specs = group.members.map { LinkParser.parse(it.rawLink!!) as OutboundSpec.Vless }
        val base = Json.parseToJsonElement(XrayConfig.buildVless(
            specs.first(), socksPort, routing, geodata = geodata, answersDns = answersDns
        )).jsonObject.toMutableMap()
        val native = specs.mapIndexed { index, spec ->
            val config = Json.parseToJsonElement(XrayConfig.buildVless(spec)).jsonObject
            val fields = config["outbounds"]!!.jsonArray.first().jsonObject.toMutableMap()
            fields["tag"] = JsonPrimitive("member-$index")
            if ((answersDns || routing is Routing.BypassRussia) && !XrayConfig.isIpLiteral(spec.host)) {
                val stream = fields["streamSettings"]!!.jsonObject.toMutableMap()
                stream["sockopt"] = buildJsonObject { put("domainStrategy", "UseIPv4") }
                fields["streamSettings"] = JsonObject(stream)
            }
            JsonObject(fields)
        }
        // Keep the chosen member while observations warm up; no direct fallback.
        val blocked = buildJsonObject { put("tag", "unavailable"); put("protocol", "blackhole") }
        val auxiliaries = base["outbounds"]!!.jsonArray.drop(1)
        base["outbounds"] = JsonArray(listOf(blocked) + native + auxiliaries)
        val originalRules = base["routing"]?.jsonObject?.get("rules")?.jsonArray.orEmpty().map { rule ->
            val fields = rule.jsonObject.toMutableMap()
            if (fields["outboundTag"]?.jsonPrimitive?.content == "out") {
                fields.remove("outboundTag"); fields["balancerTag"] = JsonPrimitive("group")
            }
            JsonObject(fields)
        }
        val probeRules = specs.indices.map { index -> buildJsonObject {
            put("type", "field"); putJsonArray("inboundTag") { add("probe-$index") }
            put("outboundTag", "member-$index")
        } }
        val catchAll = buildJsonObject {
            put("type", "field"); put("network", "tcp,udp"); put("balancerTag", "group")
        }
        base["routing"] = buildJsonObject {
            put("domainStrategy", "AsIs")
            put("rules", JsonArray(probeRules + originalRules + catchAll))
            putJsonArray("balancers") { addJsonObject {
                put("tag", "group"); putJsonArray("selector") { add("member-") }
                put("fallbackTag", "member-${group.fallbackIndex}")
                putJsonObject("strategy") {
                    put("type", if (group.mode == ConnectionSelection.Lowest) "leastPing" else "random")
                }
            } }
        }
        base["observatory"] = buildJsonObject {
            putJsonArray("subjectSelector") { add("member-") }
            put("probeUrl", ChannelLatency.URL)
            put("probeInterval", "1m")
            put("enableConcurrency", true)
        }
        val probes = specs.indices.map { index -> buildJsonObject {
            put("tag", "probe-$index"); put("listen", "127.0.0.1")
            put("port", VlessGroup.PROBE_PORT + index); put("protocol", "socks")
            putJsonObject("settings") { }
        } }
        base["inbounds"] = JsonArray(base["inbounds"]!!.jsonArray + probes)
        // Every member's hostname must resolve below the tunnel, not just the first.
        val names = specs.map { it.host }.filterNot(XrayConfig::isIpLiteral).distinct()
        if (answersDns || routing is Routing.BypassRussia) {
            val dns = base["dns"]!!.jsonObject.toMutableMap()
            val servers = dns["servers"]!!.jsonArray.toMutableList()
            if (names.isNotEmpty()) {
                val existing = servers.indexOfFirst {
                    it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-direct"
                }
                val direct = (servers.getOrNull(existing)?.jsonObject ?: buildJsonObject {
                    put("tag", "dns-direct")
                    put("address", XrayConfig.directDnsAddress(
                        (routing as? Routing.BypassRussia)?.directDns ?: DirectDns.Placeholder))
                    put("port", 53); put("skipFallback", true)
                }).toMutableMap()
                val domains = direct["domains"]?.jsonArray.orEmpty() + names.map { JsonPrimitive("full:$it") }
                direct["domains"] = JsonArray(domains.distinct())
                if (existing >= 0) servers[existing] = JsonObject(direct)
                else servers.add(0, JsonObject(direct))
                if (auxiliaries.none { it.jsonObject["tag"]?.jsonPrimitive?.content == "direct" }) {
                    base["outbounds"] = JsonArray(base["outbounds"]!!.jsonArray + buildJsonObject {
                        put("tag", "direct"); put("protocol", "freedom")
                    })
                }
                val route = base["routing"]!!.jsonObject.toMutableMap()
                val rule = buildJsonObject {
                    put("type", "field"); putJsonArray("inboundTag") { add("dns-direct") }
                    put("outboundTag", "direct")
                }
                route["rules"] = JsonArray(listOf(rule) + route["rules"]!!.jsonArray)
                base["routing"] = JsonObject(route)
            }
            dns["servers"] = JsonArray(servers); base["dns"] = JsonObject(dns)
        }
        return JsonObject(base).toString()
    }
}
