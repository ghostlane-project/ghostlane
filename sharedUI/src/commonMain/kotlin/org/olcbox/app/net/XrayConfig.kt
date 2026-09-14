package org.olcbox.app.net

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds an Xray-core config JSON: one SOCKS inbound + one vless outbound over
 * the XHTTP transport with Reality. sing-box cannot speak Xray's XHTTP (verified
 * via `sing-box check`), so xhttp locations are handled by Xray-core instead; the
 * existing tun→SOCKS bridge feeds this SOCKS inbound exactly like the sing-box path.
 *
 * The JSON schema is tied to the pinned Xray release [XRAY_VERSION]; validated in
 * CI with `xray test` against the real binary.
 */
object XrayConfig {
    /** Pinned Xray-core release whose config schema this builder targets. */
    const val XRAY_VERSION = "25.3.6"
    /**
     * Kept off the PAC port 10809.
     *
     * The same number as [SingBoxConfig.SINGBOX_SOCKS_PORT] and safely so: on
     * Android and desktop only one core runs at a time, and on iOS — where both
     * do — sing-box owns the tun and has no SOCKS inbound of its own, so this
     * port has exactly one listener either way.
     */
    const val XRAY_SOCKS_PORT = 10810

    /**
     * Upload chunk size for xhttp packet-up, in bytes. Bounds what Xray buffers
     * per uploading connection (about twice this); see the builder for why.
     */
    const val XHTTP_MAX_EACH_POST_BYTES = 200_000

    /**
     * The tunnel-side resolver under [answersDns], reached through `out`. The
     * same address SingBoxConfig uses for the same job.
     */
    private const val REMOTE_DNS_SERVER = "1.1.1.1"

    /**
     * What `ip_is_private` means to sing-box, spelled out: the local network,
     * loopback, link-local, carrier-grade NAT, multicast and the reserved
     * blocks. Xray has `geoip:private` for this, but that needs geoip.dat on
     * disk, which the iOS extension cannot point the core at (see
     * [XrayGeodata]).
     */
    val PRIVATE_RANGES: List<String> = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8",
    )

    /**
     * Build an Xray config for a vless+xhttp+reality location. Requires the spec's
     * transport to be [TransportSpec.Xhttp] (Xray's role here is xhttp only).
     *
     * [routing] and [directDns] are the same choices [SingBoxConfig] takes, for
     * the platform where Xray is the only router in the path: iOS with a
     * tun2socks in front of it instead of sing-box. Under [Routing.BypassRussia]
     * the config carries the lists in [geodata] inline — Russian names and
     * addresses and the local network go to a `freedom` outbound, everything
     * else through the tunnel — and answers the system's DNS itself, Russian
     * names on the network underneath, the rest through the tunnel.
     *
     * [answersDns] asks for that DNS handling without the bypass: the
     * front-end sends every port-53 datagram here (the tun's resolver address
     * is one only we answer, see `LibboxBridge.Tun.dns`), so someone has to
     * answer. With sing-box in front it does, and this stays false; the
     * default output is then byte-for-byte what it was before routing existed.
     */
    fun buildXhttp(
        spec: OutboundSpec.Vless,
        socksPort: Int = XRAY_SOCKS_PORT,
        routing: Routing = Routing.Global,
        directDns: DirectDns = DirectDns.Placeholder,
        geodata: XrayGeodata.Lists? = null,
        answersDns: Boolean = false,
    ): String {
        val xhttp = spec.transport as? TransportSpec.Xhttp
            ?: error("XrayConfig.buildXhttp requires an xhttp transport")
        val bypass = routing as? Routing.BypassRussia
        require(bypass == null || geodata != null) {
            "Bypass Russia on Xray carries its lists inline: pass XrayGeodata.lists()"
        }
        val direct = bypass?.directDns ?: directDns
        val resolvesNames = answersDns || bypass != null
        // A server named by a hostname has to be resolved before the tunnel
        // exists, on the network underneath, never through the resolver that
        // the tunnel carries. ProofKit's links carry addresses; this is for
        // the ones that do not.
        val serverByName = resolvesNames && !isIpLiteral(spec.host)
        val hasDirect = bypass != null || serverByName
        val obj = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            if (resolvesNames) putDns(direct, bypass?.let { geodata }, spec.host.takeIf { serverByName })
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "in"); put("listen", "127.0.0.1"); put("port", socksPort)
                    put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true) }
                    // The tun hands over addresses, and a rule that matches a
                    // name needs the name: TLS and QUIC carry it in the hello,
                    // HTTP in the Host header. The sniffed name also replaces
                    // the address for the dial, so a connection bound for the
                    // tunnel leaves by name and the exit resolves it — what
                    // sing-box's fake addresses did on this platform, and why
                    // `routeOnly` stays off.
                    if (resolvesNames) {
                        putJsonObject("sniffing") {
                            put("enabled", true)
                            putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                        }
                    }
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", "out"); put("protocol", "vless")
                    putJsonObject("settings") {
                        putJsonArray("vnext") {
                            addJsonObject {
                                put("address", spec.host); put("port", spec.port)
                                putJsonArray("users") {
                                    addJsonObject {
                                        put("id", spec.uuid); put("encryption", "none")
                                    }
                                }
                            }
                        }
                    }
                    putJsonObject("streamSettings") {
                        put("network", "xhttp")
                        // REALITY only where there is a key for it.
                        //
                        // A CDN entry is xhttp over ordinary TLS to a host with a
                        // real certificate — `security=tls`, no `pbk`. Building
                        // REALITY for it anyway handed Xray a config with an
                        // empty key, and it says so exactly:
                        //   Failed to build REALITY config > empty "password"
                        // which reads as a missing credential rather than as the
                        // wrong kind of security entirely.
                        if (spec.publicKey.isBlank()) {
                            put("security", "tls")
                            putJsonObject("tlsSettings") {
                                put("serverName", spec.sni)
                                put("fingerprint", spec.fingerprint)
                                // A CDN presents a certificate that chains; there
                                // is nothing here that should need waiving.
                                put("allowInsecure", false)
                            }
                        } else {
                            put("security", "reality")
                            putJsonObject("realitySettings") {
                                put("serverName", spec.sni)
                                put("fingerprint", spec.fingerprint)
                                put("publicKey", spec.publicKey)
                                put("shortId", spec.shortId)
                            }
                        }
                        putJsonObject("xhttpSettings") {
                            put("path", xhttp.path)
                            put("host", xhttp.host)
                            put("mode", xhttp.mode)
                            // In packet-up mode (what every ProofKit link asks for,
                            // because stream-one does not survive a relay hop) Xray
                            // keeps an upload pipe of scMaxEachPostBytes per
                            // connection and holds one more chunk of that size
                            // while it is being POSTed. At Xray's default of
                            // 1,000,000 that is ~2 MB per uploading connection;
                            // a speed test's upload phase opens ten to twenty of
                            // them at once, which on iOS is the +15 MB step in
                            // 0.75 s that took the tunnel extension from 31 MB to
                            // its ~47 MB kill point (olcbox 1.0.423 trace).
                            //
                            // 200 KB caps that at ~400 KB per connection. The
                            // cost is per-connection upload rate: one chunk per
                            // scMinPostsIntervalMs (30 ms by default) is ~53
                            // Mbit/s per stream, above what a phone's uplink
                            // gives and split across streams anyway. The server
                            // side accepts any size up to its own limit, so
                            // nothing there has to change.
                            put("scMaxEachPostBytes", XHTTP_MAX_EACH_POST_BYTES)
                        }
                        // The server's own name goes through the resolver above
                        // rather than the system's, which inside the extension
                        // is the tun — a lookup that would wait on the tunnel
                        // being dialled. `dns-direct` answers it underneath.
                        if (serverByName) {
                            putJsonObject("sockopt") { put("domainStrategy", "UseIPv4") }
                        }
                    }
                }
                if (hasDirect) {
                    addJsonObject {
                        put("tag", "direct"); put("protocol", "freedom")
                        // A name handed to `direct` — a Russian site after the
                        // sniff — resolves through the resolver above, which
                        // sends Russian names underneath. "AsIs" would ask the
                        // system, which inside the extension is the tun.
                        putJsonObject("settings") { put("domainStrategy", "UseIPv4") }
                    }
                }
                // Answers the datagrams the rule below sends here with the
                // resolver above, instead of forwarding them anywhere.
                if (resolvesNames) addJsonObject { put("tag", "dns-out"); put("protocol", "dns") }
            }
            if (resolvesNames) putRouting(bypass?.let { geodata }, serverByName)
        }
        return obj.toString()
    }

    /**
     * Two resolvers, each tagged so the routing below can send its queries
     * where they belong: `dns-direct` on the network underneath for the
     * Russian lists and the server's own name, `dns-remote` through the tunnel
     * for everything else. Xray tries the servers in order for a name no
     * `domains` entry claims, so the direct one carries `skipFallback` and the
     * remote one comes last. `UseIPv4` because the iOS tun carries no IPv6:
     * an AAAA answer would be an address the phone cannot reach through us.
     */
    private fun JsonObjectBuilder.putDns(direct: DirectDns, geodata: XrayGeodata.Lists?, serverName: String?) {
        putJsonObject("dns") {
            put("queryStrategy", "UseIPv4")
            putJsonArray("servers") {
                val directNames = (geodata?.domains ?: emptyList()) + listOfNotNull(serverName?.let { "full:$it" })
                if (directNames.isNotEmpty()) {
                    addJsonObject {
                        put("tag", "dns-direct")
                        put("address", directDnsAddress(direct)); put("port", 53)
                        putJsonArray("domains") { directNames.forEach { add(it) } }
                        put("skipFallback", true)
                    }
                }
                addJsonObject {
                    put("tag", "dns-remote")
                    put("address", REMOTE_DNS_SERVER); put("port", 53)
                }
            }
        }
    }

    /**
     * Where "direct" resolution goes. Mirrors [SingBoxConfig]: the placeholder
     * on iOS, replaced by the extension with the network's own resolver before
     * the core starts (`DirectResolver.substitute`, a quoted-string
     * substitution — it replaces every occurrence, and this config carries
     * the address once); the platform's explicit servers elsewhere; and
     * `localhost`, Xray's name for the system resolver, only where the core
     * reaches that without looping through its own tun.
     */
    private fun directDnsAddress(direct: DirectDns): String = when (direct) {
        DirectDns.System -> "localhost"
        is DirectDns.Servers -> direct.pick()
        DirectDns.Placeholder -> SingBoxConfig.DIRECT_DNS_PLACEHOLDER
    }

    /**
     * In an order that matters. The hijack first, matched by inbound so the
     * resolvers' own queries — which Xray tags with the resolver's name, not
     * `in` — are never caught by it; then the two resolvers to their sides of
     * the tunnel; then, under the bypass, the local network, the Russian
     * names and the Russian addresses to `direct`. Everything else falls to
     * the first outbound, the tunnel.
     *
     * `AsIs`: a name that matches no domain rule is not resolved just to try
     * the address rules against it. sing-box does the same on this platform,
     * and the alternative costs a lookup through the tunnel per connection.
     */
    private fun JsonObjectBuilder.putRouting(geodata: XrayGeodata.Lists?, serverByName: Boolean) {
        putJsonObject("routing") {
            put("domainStrategy", "AsIs")
            putJsonArray("rules") {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add("in") }
                    put("network", "udp"); put("port", 53)
                    put("outboundTag", "dns-out")
                }
                if (geodata != null || serverByName) {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") { add("dns-direct") }
                        put("outboundTag", "direct")
                    }
                }
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add("dns-remote") }
                    put("outboundTag", "out")
                }
                if (geodata != null) {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { PRIVATE_RANGES.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { geodata.domains.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { geodata.cidrs.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                }
            }
        }
    }

    /** IPv4 dotted quad or anything with a colon, which a hostname never has. */
    fun isIpLiteral(host: String): Boolean =
        ':' in host || host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
}
