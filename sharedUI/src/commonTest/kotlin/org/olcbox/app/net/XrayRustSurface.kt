package org.olcbox.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.index.coreSurfaceIndex

/**
 * How one object of a config is held to the core's field lists: called with
 * the value and its JSON path, it reports through the [SurfaceWalk] it runs in.
 */
typealias SurfaceCheck = SurfaceWalk.(value: JsonElement?, path: String) -> Unit

/** One reading of a config: [found] is every field the core would refuse and every object nothing checked, as JSON paths. */
class SurfaceWalk {
    val found = mutableListOf<String>()

    /** [value], an object at [path], may hold the [accepted] keys and no other. Nothing is said of a value that is absent. */
    fun check(value: JsonElement?, path: String, accepted: Set<String>) {
        val obj = value as? JsonObject ?: run {
            if (value != null) found.add("$path is not an object")
            return
        }
        obj.keys.filterNot { it in accepted }.forEach { found.add("$path.$it") }
    }

    /** Each object of the array at [path], with its own path. */
    fun JsonElement?.each(path: String, block: (JsonObject, String) -> Unit) {
        (this as? JsonArray)?.forEachIndexed { i, item ->
            val obj = item as? JsonObject
            if (obj == null) found.add("$path[$i] is not an object") else block(obj, "$path[$i]")
        }
    }
}

/**
 * What one kind of line adds to the surface: the outbound protocols it writes
 * and the objects it writes under `streamSettings`, each with its check. A
 * kind's fragment is an object in a file `<Kind>Surface.kt` beside its
 * fixture (commonTest `net/core/lines`), and that file is its registration:
 * the build lists the fragments from the directory.
 *
 * A fragment names every object its kind writes, by key, with the check of
 * the field list copied from the core's `surface.rs`. What more than one kind
 * writes has its check in [XrayRustSurface] ([XrayRustSurface.TLS_SETTINGS],
 * [XrayRustSurface.VLESS_OUTBOUND] and the others); what only this kind
 * writes has its list and its check in the fragment's own file.
 */
interface CoreSurfaceFragment {
    val line: CoreLine

    /** `outbounds[].protocol` values the kind writes, each with the check of the outbound's `settings`. */
    val protocols: Map<String, SurfaceCheck>

    /** The objects the kind writes under `streamSettings`, by key. */
    val stream: Map<String, SurfaceCheck>
}

/**
 * The fields xray-rust's parser accepts in the objects `core.json` writes,
 * copied from `crates/xray-config/src/surface.rs` of ghostlane-project/xray-rust
 * at 77f14eb8 (the head of `gl/vision-padding-copy` on the `ghostlane` branch
 * after its third integration and its CI fixes, 1d60607f; all of them left
 * `crates/xray-config` as the second integration, e8503168, had it;
 * the parser changes since 4a61b057 are `log`, now read for the embedding's
 * log lines, which also takes Xray-core's `logLevel` spelling this config
 * never writes, and the `dns` outbound rule's `address`, `port` and
 * `outboundTag`, which only its `forward` action takes). The parser refuses a
 * whole config over one field it does not list (`reject_unknown_fields`), so a
 * builder that grows a field the core does not know fails here, before a phone
 * fails to connect.
 *
 * Narrower than the core where this config is narrower: only the objects and
 * protocols the writer in `net/core` writes are known, and an object anywhere
 * else is reported as unchecked, so a new one has to be added, from the
 * core's own list, to pass. What every config holds (the root, `log`, `dns`,
 * the inbound, the `freedom` and `dns` outbounds, the routing) is checked
 * here; what a kind of line writes is checked by that kind's
 * [CoreSurfaceFragment], which adds its own protocol and its own objects
 * without an edit of this file. `realitySettings` is checked against the
 * fields the core reads (it ignores others) and `log` against the core's `LOG`.
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
    val FREEDOM = setOf(
        "fragment", "domainStrategy", "targetStrategy", "redirect", "userLevel", "proxyProtocol", "finalRules",
        "ipsBlocked", "noises", "noise",
    )
    val DNS_OUTBOUND = setOf(
        "rewriteNetwork", "rewriteAddress", "rewritePort", "network", "address", "port", "userLevel", "rules",
        "nonIPQuery", "blockTypes",
    )
    val DNS_RULE = setOf("action", "qType", "qtype", "domain", "rCode", "address", "port", "outboundTag")
    val STREAM = setOf(
        "address", "port", "method", "network", "security", "tlsSettings", "realitySettings", "tcpSettings",
        "rawSettings", "wsSettings", "httpupgradeSettings", "grpcSettings", "xhttpSettings", "splithttpSettings",
        "kcpSettings", "hysteriaSettings", "masqueSettings", "xdriveSettings", "finalmask", "sockopt",
    )
    val ROUTING = setOf("domainStrategy", "rules", "balancers")
    val ROUTING_RULE = setOf(
        "type", "inboundTag", "network", "port", "domain", "domains", "ip", "sourceIP", "source", "sourcePort",
        "localIP", "localPort", "user", "protocol", "attrs", "localOS", "outboundTag", "balancerTag", "ruleTag",
    )

    // What more than one kind of line writes (CoreSupport's VLESS settings, CoreStream's
    // transports, TLS and Reality): a kind's fragment names the ones its kind writes.
    val VLESS = setOf("address", "port", "level", "email", "id", "flow", "seed", "encryption", "reverse", "testpre", "testseed", "vnext")
    val VLESS_SERVER = setOf("address", "port", "users")
    val VLESS_USER = setOf("id", "encryption", "flow", "level", "email", "security", "testpre", "testseed", "reverse")
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

    /** An object that may hold the [accepted] keys and no other, with nothing checked inside it. */
    fun fields(accepted: Set<String>): SurfaceCheck = { value, path -> check(value, path, accepted) }

    // The checks of those objects, for the fragments of the kinds that write them.
    val TLS_SETTINGS: SurfaceCheck = fields(TLS)
    val REALITY_SETTINGS: SurfaceCheck = fields(REALITY)
    val WS_SETTINGS: SurfaceCheck = fields(WEBSOCKET)
    val HTTPUPGRADE_SETTINGS: SurfaceCheck = fields(HTTPUPGRADE)
    val GRPC_SETTINGS: SurfaceCheck = fields(GRPC)
    val XHTTP_SETTINGS: SurfaceCheck = fields(XHTTP)

    /** A VLESS outbound's `settings`: its one server and that server's users. */
    val VLESS_OUTBOUND: SurfaceCheck = { settings, path ->
        if (settings != null) {
            check(settings, path, VLESS)
            (settings as? JsonObject)?.get("vnext").each("$path.vnext") { server, at ->
                check(server, at, VLESS_SERVER)
                server["users"].each("$at.users") { user, userAt -> check(user, userAt, VLESS_USER) }
            }
        }
    }

    private val DNS_OUTBOUND_SETTINGS: SurfaceCheck = { settings, path ->
        if (settings != null) {
            check(settings, path, DNS_OUTBOUND)
            (settings as? JsonObject)?.get("rules").each("$path.rules") { rule, at -> check(rule, at, DNS_RULE) }
        }
    }

    /** The outbounds every config may hold beside its line. */
    private val SHARED_PROTOCOLS: Map<String, SurfaceCheck> = mapOf(
        "freedom" to fields(FREEDOM),
        "dns" to DNS_OUTBOUND_SETTINGS,
    )

    /**
     * The checks each key is declared with, in the order they are declared.
     * Two kinds that write one object name one check (the vals above) and it
     * runs once. Two that declare a key each with a check of its own are both
     * held to both: the core has one list for an object, so where two copies
     * of it disagree a config fails here and is not let through by whichever
     * happened to come last.
     */
    private fun declared(maps: List<Map<String, SurfaceCheck>>): Map<String, List<SurfaceCheck>> =
        maps.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.distinct() }

    /**
     * Every field of [config] the core would refuse, and every object this
     * walker does not know, as JSON paths. Empty for a config the core's
     * field lists accept. [fragments] are the kinds whose protocols and
     * objects are known: every registered one, or one kind's alone, to hold
     * its config to what that kind declares.
     */
    fun violations(config: JsonObject, fragments: List<CoreSurfaceFragment> = coreSurfaceIndex): List<String> {
        val protocols = declared(listOf(SHARED_PROTOCOLS) + fragments.map { it.protocols })
        val streamObjects = declared(fragments.map { it.stream })
        val walk = SurfaceWalk()
        with(walk) {
            check(config, "$", ROOT)
            check(config["log"], "$.log", LOG)
            config["dns"]?.let { dns ->
                check(dns, "$.dns", DNS)
                val dnsFields = dns as? JsonObject
                check(dnsFields?.get("fakeIp"), "$.dns.fakeIp", FAKE_IP)
                dnsFields?.get("servers").each("$.dns.servers") { server, path -> check(server, path, DNS_SERVER) }
                if (dnsFields?.get("hosts") != null) found.add("$.dns.hosts is not checked here")
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
                val protocol = outbound["protocol"]?.jsonPrimitive?.content
                val settings = protocol?.let { protocols[it] }
                if (settings != null) {
                    settings.forEach { it.invoke(walk, outbound["settings"], "$path.settings") }
                } else {
                    found.add("$path.protocol $protocol is not checked here")
                }
                listOf("mux", "proxySettings").forEach { key ->
                    if (outbound[key] != null) found.add("$path.$key is not checked here")
                }
                outbound["streamSettings"]?.let { stream ->
                    val at = "$path.streamSettings"
                    check(stream, at, STREAM)
                    (stream as? JsonObject)?.forEach { (key, value) ->
                        val known = streamObjects[key]
                        when {
                            known != null -> known.forEach { it.invoke(walk, value, "$at.$key") }
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
        }
        // One path once, when two checks of one object both name it.
        return walk.found.distinct()
    }

    /** [violations] of a JSON text. */
    fun violations(json: String): List<String> =
        violations(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)
}
