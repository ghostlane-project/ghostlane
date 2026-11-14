package org.olcbox.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fields xray-rust's parser accepts in the objects `core.json` writes,
 * copied from `crates/xray-config/src/surface.rs` of ghostlane-project/xray-rust
 * at 7259d7b2 (the `ghostlane` branch's 4a61b057 with rustls from our fork; no
 * parser change between them). The parser refuses a whole config over one
 * field it does not list (`reject_unknown_fields`), so a builder that grows a
 * field the core does not know fails here, before a phone fails to connect.
 *
 * Narrower than the core where this config is narrower: only the objects and
 * protocols [XrayConfig.buildIosCore] writes are known, and an object anywhere
 * else is reported as unchecked, so a new one has to be added here, from the
 * core's own list, to pass. `realitySettings` is checked against the fields
 * the core reads (it ignores others) and `log` against Xray's own (the core
 * reads none of it).
 */
object XrayRustSurface {
    val ROOT = setOf("log", "inbounds", "outbounds", "routing", "observatory", "dns", "policy", "reverse", "remarks", "meta")
    val LOG = setOf("access", "error", "loglevel", "dnsLog", "maskAddress")
    val DNS = setOf(
        "fakeIp", "servers", "hosts", "tag", "queryStrategy", "disableCache", "serveStale", "serveExpiredTTL",
        "disableFallback", "disableFallbackIfMatch", "clientIp", "enableParallelQuery",
    )
    val FAKE_IP = setOf("enabled", "ipv4Pool", "poolSize", "ttl")
    val DNS_SERVER = setOf(
        "address", "port", "domains", "expectedIPs", "expectIPs", "unexpectedIPs", "tag", "timeoutMs",
        "skipFallback", "queryStrategy", "finalQuery", "clientIp",
    )
    val INBOUND = setOf("tag", "protocol", "listen", "port", "settings", "sniffing")
    val SOCKS_SETTINGS = setOf("auth", "accounts", "users", "udp", "ip", "userLevel", "allowUnauthenticatedLan")
    val SOCKS_ACCOUNT = setOf("user", "pass")
    val SNIFFING = setOf("enabled", "destOverride", "metadataOnly", "routeOnly", "domainsExcluded", "ipsExcluded", "excludedDomains")
    val OUTBOUND = setOf("tag", "protocol", "settings", "streamSettings", "mux", "proxySettings", "sendThrough", "targetStrategy")
    val VLESS = setOf("address", "port", "level", "email", "id", "flow", "seed", "encryption", "reverse", "testpre", "testseed", "vnext")
    val VLESS_SERVER = setOf("address", "port", "users")
    val VLESS_USER = setOf("id", "encryption", "flow", "level", "email", "security", "testpre", "testseed", "reverse")
    val HYSTERIA = setOf("version", "address", "port")
    val FREEDOM = setOf(
        "fragment", "domainStrategy", "targetStrategy", "redirect", "userLevel", "proxyProtocol", "finalRules",
        "ipsBlocked", "noises", "noise",
    )
    val DNS_OUTBOUND = setOf(
        "rewriteNetwork", "rewriteAddress", "rewritePort", "network", "address", "port", "userLevel", "rules",
        "nonIPQuery", "blockTypes",
    )
    val DNS_RULE = setOf("action", "qType", "qtype", "domain", "rCode")
    val STREAM = setOf(
        "address", "port", "method", "network", "security", "tlsSettings", "realitySettings", "tcpSettings",
        "rawSettings", "wsSettings", "httpupgradeSettings", "grpcSettings", "xhttpSettings", "splithttpSettings",
        "kcpSettings", "hysteriaSettings", "masqueSettings", "xdriveSettings", "finalmask", "sockopt",
    )
    val TLS = setOf(
        "serverName", "allowInsecure", "fingerprint", "alpn", "pinnedPeerCertSha256", "verifyPeerCertByName",
        "enableSessionResumption",
    )
    val REALITY = setOf("serverName", "fingerprint", "publicKey", "shortId", "spiderX", "mldsa65Verify")
    val WEBSOCKET = setOf("path", "host", "headers", "acceptProxyProtocol", "heartbeatPeriod")
    val HTTPUPGRADE = setOf("path", "host", "headers", "acceptProxyProtocol")
    val GRPC = setOf(
        "authority", "serviceName", "multiMode", "user_agent", "idle_timeout", "health_check_timeout",
        "permit_without_stream", "initial_windows_size",
    )
    val XHTTP = setOf(
        "host", "path", "mode", "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader",
        "xPaddingPlacement", "xPaddingMethod", "uplinkHTTPMethod", "sessionIDPlacement", "sessionIDKey",
        "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey",
        "uplinkChunkSize", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs",
        "scMaxConcurrentPosts", "scMaxBufferedPosts", "scStreamUpServerSecs", "serverMaxHeaderBytes", "h2Params",
        "xmux", "downloadSettings", "extra", "h2StreamReceiveWindow",
    )
    val HYSTERIA_TRANSPORT = setOf("version", "auth")
    val FINALMASK = setOf("tcp", "udp", "quicParams")
    val FINALMASK_ENTRY = setOf("type", "settings")
    val SALAMANDER = setOf("password", "packetSize")
    val QUIC_PARAMS = setOf(
        "congestion", "debug", "bbrProfile", "brutalUp", "brutalDown", "udpHop", "initStreamReceiveWindow",
        "maxStreamReceiveWindow", "initConnectionReceiveWindow", "maxConnectionReceiveWindow", "maxIdleTimeout",
        "keepAlivePeriod", "disablePathMTUDiscovery", "maxIncomingStreams", "disableChromeParrot", "disableGSO",
        "disableStatelessReset", "brutalDisableLossCompensation",
    )
    val ROUTING = setOf("domainStrategy", "rules", "balancers")
    val ROUTING_RULE = setOf(
        "type", "inboundTag", "network", "port", "domain", "domains", "ip", "sourceIP", "source", "sourcePort",
        "localIP", "localPort", "user", "protocol", "attrs", "localOS", "outboundTag", "balancerTag", "ruleTag",
    )

    /** The `streamSettings` objects this walker knows, by key. */
    private val STREAM_OBJECTS = mapOf(
        "tlsSettings" to TLS,
        "realitySettings" to REALITY,
        "wsSettings" to WEBSOCKET,
        "httpupgradeSettings" to HTTPUPGRADE,
        "grpcSettings" to GRPC,
        "xhttpSettings" to XHTTP,
        "hysteriaSettings" to HYSTERIA_TRANSPORT,
    )

    /**
     * Every field of [config] the core would refuse, and every object this
     * walker does not know, as JSON paths. Empty for a config the core's
     * field lists accept.
     */
    fun violations(config: JsonObject): List<String> {
        val found = mutableListOf<String>()
        fun check(value: JsonElement?, path: String, accepted: Set<String>) {
            val obj = value as? JsonObject ?: run {
                if (value != null) found.add("$path is not an object")
                return
            }
            obj.keys.filterNot { it in accepted }.forEach { found.add("$path.$it") }
        }
        fun JsonElement?.each(path: String, block: (JsonObject, String) -> Unit) {
            (this as? JsonArray)?.forEachIndexed { i, item ->
                val obj = item as? JsonObject
                if (obj == null) found.add("$path[$i] is not an object") else block(obj, "$path[$i]")
            }
        }

        check(config, "$", ROOT)
        check(config["log"], "$.log", LOG)
        config["dns"]?.let { dns ->
            check(dns, "$.dns", DNS)
            val fields = dns as? JsonObject
            check(fields?.get("fakeIp"), "$.dns.fakeIp", FAKE_IP)
            fields?.get("servers").each("$.dns.servers") { server, path -> check(server, path, DNS_SERVER) }
            if (fields?.get("hosts") != null) found.add("$.dns.hosts is not checked here")
        }
        config["inbounds"].each("$.inbounds") { inbound, path ->
            check(inbound, path, INBOUND)
            if (inbound["protocol"]?.jsonPrimitive?.content != "socks") found.add("$path.protocol is not socks")
            inbound["settings"]?.let { settings ->
                check(settings, "$path.settings", SOCKS_SETTINGS)
                (settings as? JsonObject)?.get("accounts").each("$path.settings.accounts") { account, at -> check(account, at, SOCKS_ACCOUNT) }
            }
            check(inbound["sniffing"], "$path.sniffing", SNIFFING)
        }
        config["outbounds"].each("$.outbounds") { outbound, path ->
            check(outbound, path, OUTBOUND)
            val settings = outbound["settings"]
            when (val protocol = outbound["protocol"]?.jsonPrimitive?.content) {
                "vless" -> settings?.let { s ->
                    check(s, "$path.settings", VLESS)
                    (s as? JsonObject)?.get("vnext").each("$path.settings.vnext") { server, at ->
                        check(server, at, VLESS_SERVER)
                        server["users"].each("$at.users") { user, userAt -> check(user, userAt, VLESS_USER) }
                    }
                }
                "hysteria" -> check(settings, "$path.settings", HYSTERIA)
                "freedom" -> check(settings, "$path.settings", FREEDOM)
                "dns" -> settings?.let { s ->
                    check(s, "$path.settings", DNS_OUTBOUND)
                    (s as? JsonObject)?.get("rules").each("$path.settings.rules") { rule, at -> check(rule, at, DNS_RULE) }
                }
                else -> found.add("$path.protocol $protocol is not checked here")
            }
            listOf("mux", "proxySettings").forEach { key ->
                if (outbound[key] != null) found.add("$path.$key is not checked here")
            }
            outbound["streamSettings"]?.let { stream ->
                val at = "$path.streamSettings"
                check(stream, at, STREAM)
                (stream as? JsonObject)?.forEach { (key, value) ->
                    val accepted = STREAM_OBJECTS[key]
                    when {
                        accepted != null -> check(value, "$at.$key", accepted)
                        key == "finalmask" -> {
                            check(value, "$at.finalmask", FINALMASK)
                            (value as? JsonObject)?.get("udp").each("$at.finalmask.udp") { mask, maskAt ->
                                check(mask, maskAt, FINALMASK_ENTRY)
                                if (mask["type"]?.jsonPrimitive?.content != "salamander") {
                                    found.add("$maskAt.type is not checked here")
                                }
                                check(mask["settings"], "$maskAt.settings", SALAMANDER)
                            }
                            (value as? JsonObject)?.get("quicParams")?.let { check(it, "$at.finalmask.quicParams", QUIC_PARAMS) }
                            if ((value as? JsonObject)?.get("tcp") != null) found.add("$at.finalmask.tcp is not checked here")
                        }
                        value is JsonObject || value is JsonArray -> found.add("$at.$key is not checked here")
                        else -> Unit
                    }
                }
            }
        }
        config["routing"]?.let { routing ->
            check(routing, "$.routing", ROUTING)
            (routing as? JsonObject)?.get("rules").each("$.routing.rules") { rule, at -> check(rule, at, ROUTING_RULE) }
            if ((routing as? JsonObject)?.get("balancers") != null) found.add("$.routing.balancers is not checked here")
        }
        listOf("observatory", "policy", "reverse", "meta").forEach { key ->
            if (config[key] != null) found.add("$.$key is not checked here")
        }
        return found
    }

    /** [violations] of a JSON text. */
    fun violations(json: String): List<String> =
        violations(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)
}
