package org.olcbox.app.net

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The engine that runs a line in the iOS packet tunnel: D2 of the OpenSpec
 * change `ghostlane-rust-core`, chosen per transport by the app
 * (`IosVpnManager.IOS_ENGINES`) and followed by the extension through the
 * files it finds in the App Group: olcrtc.json, xray.json, core.json, then
 * config.json.
 */
enum class IosEngine {
    /** Phase 0's path: sing-box, Xray for XHTTP or the olcRTC engine, all Go, all behind hev. */
    GO,

    /** `ghostlane-core` (xray-rust) behind hev, started from `core.json` ([XrayConfig.buildIosCore]). */
    RUST,
}

/** What the per-transport choice ([IosEngine]) is made for. */
enum class IosTransport { Reality, RealityGrpc, WebTunnel, Hysteria2, Xhttp, Olcrtc }

/**
 * A line [XrayConfig.buildIosCore] writes `core.json` for. [slug] names its
 * reference configs (`ios-core-<slug>.json`, `bypass-ios-core-<slug>.json`).
 */
enum class IosCoreLine(val slug: String, val transport: IosTransport) {
    /** VLESS over Reality on raw TCP, with Vision or without a flow. */
    RealityVision("reality", IosTransport.Reality),
    RealityGrpc("grpc-reality", IosTransport.RealityGrpc),
    /** WebTunnel: VLESS over WebSocket with ordinary TLS. */
    WebTunnelWs("webtunnel-ws", IosTransport.WebTunnel),
    /** WebTunnel: VLESS over HTTP upgrade with ordinary TLS. */
    WebTunnelHttpUpgrade("webtunnel-httpupgrade", IosTransport.WebTunnel),
    /** Hysteria2, with or without Salamander. */
    Hysteria2("hysteria2", IosTransport.Hysteria2),
    /** VLESS over XHTTP with Reality: ProofKit's packet-up line. */
    XhttpReality("xhttp", IosTransport.Xhttp),
}

/**
 * What [IosCore.choose] decided for a line: the [engine], the core [line] when
 * the line is one, and, when a line the map gives to the Rust core goes to Go
 * after all, the [reason] (never a secret: words about the link's shape).
 */
data class IosEngineChoice(val engine: IosEngine, val line: IosCoreLine?, val reason: String? = null)

/**
 * What the Rust core can run, and the `core.json` it runs it from.
 *
 * The core is xray-rust: it reads the Xray JSON its parser knows and refuses
 * the whole document over one field it does not (`reject_unknown_fields`
 * against its `surface.rs`). So this file writes only what that parser
 * accepts, and a line it would refuse is not given to it at all: [refusal]
 * names why, and [choose] sends the line to Go, where Phase 0 runs it as it
 * always has.
 */
object IosCore {
    /**
     * The fake addresses the core hands out for names bound for the tunnel:
     * the range sing-box's iOS shape hands out ([SingBoxConfig.buildIosSocks]).
     * IPv4 only: hev's tun carries no IPv6.
     */
    const val FAKE_IP_POOL = "198.18.0.0/15"

    /**
     * Go's stream window for XHTTP on HTTP/2 on iOS (`XrayEngine.h2StreamWindow`,
     * 512 KiB), which the lab's iOS-limits rows were measured with. xray-rust
     * reads it as a fixed window per stream; unset, a constrained host opens at
     * 1 MiB and grows to 4 MiB.
     */
    const val XHTTP_H2_STREAM_RECEIVE_WINDOW = 524_288

    /** The resolver tag whose queries go straight out, as in [SingBoxConfig]'s shapes. */
    private const val DNS_DIRECT = "dns-direct"

    /** DNS type HTTPS (RFC 9460), refused as sing-box's iOS shape refuses it. */
    private const val QTYPE_HTTPS = 65

    /** REFUSED: what sing-box's DNS `reject` answers, and a client reads as "no such record". */
    private const val RCODE_REFUSED = 5

    /**
     * The fingerprints sing-box's names map to ([SingBoxConfig.singBoxFingerprint])
     * that xray-rust has no REALITY hello for: android and 360 offer no X25519
     * key share, and the core refuses them under `realitySettings`.
     */
    private val NO_REALITY_HELLO = setOf("android", "360")

    private val VISION_FLOWS = setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")

    /** Which core line [spec] is, by its shape alone; null for anything else. */
    fun lineOf(spec: OutboundSpec): IosCoreLine? = when (spec) {
        is OutboundSpec.Hysteria2 -> IosCoreLine.Hysteria2
        is OutboundSpec.Vless -> when {
            spec.plain -> null
            spec.publicKey.isNotBlank() -> when (spec.transport) {
                TransportSpec.Tcp -> IosCoreLine.RealityVision
                is TransportSpec.Grpc -> IosCoreLine.RealityGrpc
                is TransportSpec.Xhttp -> IosCoreLine.XhttpReality
                else -> null
            }
            else -> when (spec.transport) {
                is TransportSpec.Ws -> IosCoreLine.WebTunnelWs
                is TransportSpec.HttpUpgrade -> IosCoreLine.WebTunnelHttpUpgrade
                else -> null
            }
        }
        else -> null
    }

    /**
     * Why the core would refuse [spec], or null when it takes it. Each is a
     * field xray-rust's parser rejects, or one this config cannot direct:
     *  - a server named by a hostname: the core resolves an outbound's server
     *    with `dns.hosts` and the platform resolver, which inside the
     *    extension is the tun itself, and the config cannot point it at the
     *    network underneath (sing-box's `default_domain_resolver` can, and
     *    `sockopt.domainStrategy` is refused on VLESS and Hysteria);
     *  - `allowInsecure` on ordinary TLS: the core verifies every certificate
     *    (Xray removed the switch; a pin is the way to trust a self-signed one);
     *  - a flow other than Vision;
     *  - a fingerprint with no REALITY hello in the core ([NO_REALITY_HELLO]);
     *  - Reality with no server name;
     *  - Hysteria2 asked to skip verification with no pin to verify instead,
     *    a pin that is not a SHA-256 in hex, a Salamander password outside
     *    4 to 4096 bytes, or a password the core will not send.
     */
    fun refusal(spec: OutboundSpec): String? {
        if (!XrayConfig.isIpLiteral(spec.host)) {
            return "the server is named by a hostname, which the core would resolve through the tunnel's own resolver"
        }
        return when (spec) {
            is OutboundSpec.Vless -> vlessRefusal(spec)
            is OutboundSpec.Hysteria2 -> hysteria2Refusal(spec)
            else -> "the Rust core has no ${spec::class.simpleName} line"
        }
    }

    private fun vlessRefusal(spec: OutboundSpec.Vless): String? {
        val reality = spec.publicKey.isNotBlank()
        return when {
            spec.insecure -> "the link waives certificate checks (allowInsecure), which the core does not do"
            spec.flow != null && spec.flow.trim().lowercase() !in VISION_FLOWS -> "the core knows no flow but Vision"
            fingerprint(spec.fingerprint, reality) == null -> "the core has no REALITY hello for this fingerprint"
            reality && spec.sni.isBlank() -> "a Reality line with no server name"
            else -> null
        }
    }

    private fun hysteria2Refusal(spec: OutboundSpec.Hysteria2): String? {
        val auth = spec.password
        val obfs = spec.obfsPassword
        return when {
            spec.certPinSha256 != null && pin(spec.certPinSha256) == null ->
                "the certificate pin is not a SHA-256 in hex"
            spec.insecure && spec.certPinSha256 == null ->
                "the link waives certificate checks with no pin, which the core does not do"
            auth.isEmpty() || auth.encodeToByteArray().size > 4096 || auth.any { it < ' ' || it == '\u007f' } ->
                "a Hysteria2 password the core does not send"
            obfs != null && obfs.encodeToByteArray().size !in 4..4096 ->
                "a Salamander password outside 4 to 4096 bytes"
            else -> null
        }
    }

    /**
     * The engine for [spec] under [engines], the per-transport map: Rust only
     * for a core line whose transport the map gives to Rust and which the
     * core takes ([refusal]); Go for everything else.
     */
    fun choose(spec: OutboundSpec, engines: Map<IosTransport, IosEngine>): IosEngineChoice {
        val line = lineOf(spec) ?: return IosEngineChoice(IosEngine.GO, null)
        if (engines[line.transport] != IosEngine.RUST) return IosEngineChoice(IosEngine.GO, line)
        refusal(spec)?.let { return IosEngineChoice(IosEngine.GO, line, it) }
        return IosEngineChoice(IosEngine.RUST, line)
    }

    /**
     * A link's `fp` as the core takes it: sing-box's name for it, which is
     * what Phase 0 sends for the same link ([SingBoxConfig.singBoxFingerprint]),
     * with sing-box's chrome variants (`chrome_psk`, `chrome_pq`, …) read as
     * chrome, the only name of theirs the core lacks. Null for a REALITY line
     * whose name has no REALITY hello in the core.
     */
    internal fun fingerprint(fp: String?, reality: Boolean): String? {
        val name = SingBoxConfig.singBoxFingerprint(fp).let { if (it.startsWith("chrome_")) "chrome" else it }
        return if (reality && name in NO_REALITY_HELLO) null else name
    }

    /**
     * A Hysteria2 `pinSHA256` as the core reads `pinnedPeerCertSha256`:
     * compact lower-case hex, colons dropped (OpenSSL's spelling is the
     * usual one in links). Null unless it is 32 bytes of hex.
     */
    internal fun pin(raw: String): String? {
        val hex = raw.trim().replace(":", "").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** See [XrayConfig.buildIosCore]. */
    internal fun build(
        spec: OutboundSpec,
        login: SocksLogin,
        routing: Routing,
        geodata: XrayGeodata.Lists?,
        verboseLogs: Boolean,
        socksPort: Int,
    ): String {
        require(login.username.isNotBlank() && login.password.isNotBlank()) {
            "a loopback SOCKS port other apps can reach needs a login"
        }
        val line = requireNotNull(lineOf(spec)) {
            "the Rust core's config is written for Reality, Reality gRPC, WebTunnel, Hysteria2 and XHTTP Reality lines"
        }
        refusal(spec)?.let { throw IllegalArgumentException("the Rust core cannot run this line: $it") }
        val bypass = routing as? Routing.RuleBased
        // What sing-box's iOS shape builds (SingBoxConfig.renderIos), and no more.
        require(bypass == null || (bypass.policy is Routing.Policy.Bypass && bypass.custom.isEmpty)) {
            "the iOS shape builds bypass routing only"
        }
        require(bypass == null || geodata != null) {
            "Bypass Russia on the Rust core carries its lists inline: pass XrayGeodata.lists()"
        }
        val lists = geodata.takeIf { bypass != null }
        val directResolver = bypass?.let { directResolverAddress(it.directDns) }

        return buildJsonObject {
            // xray-rust reads no `log`: the embedding decides where its lines
            // go. Written for whoever reads the file, as Xray reads it: no
            // access log, which would name every connection the user makes.
            putJsonObject("log") {
                put("loglevel", if (verboseLogs) "debug" else "warning")
                put("access", "none")
            }
            putDns(directResolver)
            putJsonArray("inbounds") { addSocksInbound(socksPort, login) }
            putJsonArray("outbounds") {
                addOutbound(spec, line)
                // No settings: a name handed here is resolved through
                // `dns.servers`, which under the bypass is dns-direct alone.
                if (bypass != null) addJsonObject { put("tag", "direct"); put("protocol", "freedom") }
                addDnsOutbound(directResolver, lists)
            }
            putRouting(lists)
        }.toString()
    }

    /**
     * Where names bound direct are asked: the placeholder on iOS, which the
     * extension replaces with the network's own resolver before the core
     * starts (`DirectResolver.substitute`, every quoted occurrence: here two).
     * An explicit list elsewhere, but not a zoned link-local address, which
     * the core's parser refuses: the public fallback stands in for it.
     */
    private fun directResolverAddress(direct: DirectDns): String = when (direct) {
        DirectDns.Placeholder -> SingBoxConfig.DIRECT_DNS_PLACEHOLDER
        is DirectDns.Servers -> direct.pick().let { if ('%' in it) SingBoxConfig.DIRECT_DNS_FALLBACK else it }
        DirectDns.System -> throw IllegalArgumentException("the Rust core cannot name the system resolver: give it an address")
    }

    /**
     * Fake addresses for every name bound for the tunnel, IPv4 only as in
     * sing-box's iOS shape. Under the bypass one resolver, `dns-direct`, which
     * only names the direct side resolves: a Russian name handed to `direct`
     * after a sniff (sing-box's `default_domain_resolver`). Its own queries
     * are routed by its tag, so the rule below sends them straight out.
     */
    private fun JsonObjectBuilder.putDns(directResolver: String?) {
        putJsonObject("dns") {
            put("queryStrategy", "UseIPv4")
            putJsonObject("fakeIp") {
                put("enabled", true)
                put("ipv4Pool", FAKE_IP_POOL)
            }
            if (directResolver != null) {
                putJsonArray("servers") {
                    addJsonObject {
                        put("tag", DNS_DIRECT)
                        put("address", directResolver); put("port", 53)
                    }
                }
            }
        }
    }

    /**
     * The one inbound: SOCKS5 on loopback with the login hev is given, UDP on
     * (hev forwards it over TCP). The sniff names a connection for the rules
     * only (`routeOnly`): a fake address is turned back into its name before
     * any of this, and a real address stays the address it was dialled at,
     * as with sing-box's `sniff`.
     */
    private fun JsonArrayBuilder.addSocksInbound(port: Int, login: SocksLogin) {
        addJsonObject {
            put("tag", "in"); put("protocol", "socks")
            put("listen", "127.0.0.1"); put("port", port)
            putJsonObject("settings") {
                put("auth", "password")
                putJsonArray("accounts") {
                    addJsonObject {
                        put("user", login.username)
                        put("pass", login.password)
                    }
                }
                put("udp", true)
            }
            putJsonObject("sniffing") {
                put("enabled", true)
                putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                put("routeOnly", true)
            }
        }
    }

    /** The line, tagged `out`: first, so what no rule claims rides the tunnel. */
    private fun JsonArrayBuilder.addOutbound(spec: OutboundSpec, line: IosCoreLine) {
        addJsonObject {
            put("tag", "out")
            when (spec) {
                is OutboundSpec.Vless -> putVless(spec, line)
                is OutboundSpec.Hysteria2 -> putHysteria2(spec)
                else -> error("not a core line: ${spec::class.simpleName}")
            }
        }
    }

    private fun JsonObjectBuilder.putVless(spec: OutboundSpec.Vless, line: IosCoreLine) {
        val reality = spec.publicKey.isNotBlank()
        put("protocol", "vless")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", spec.host); put("port", spec.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", spec.uuid); put("encryption", "none")
                            // Vision as the -udp443 variant: the server is sent
                            // plain Vision either way, and this lets QUIC to
                            // port 443 through, which plain Vision refuses and
                            // sing-box's Vision (Phase 0) carries over XUDP.
                            if (spec.flow != null) put("flow", "xtls-rprx-vision-udp443")
                        }
                    }
                }
            }
        }
        putJsonObject("streamSettings") {
            when (val transport = spec.transport) {
                TransportSpec.Tcp -> put("network", "raw")
                is TransportSpec.Grpc -> {
                    put("network", "grpc")
                    putJsonObject("grpcSettings") { put("serviceName", transport.serviceName) }
                }
                is TransportSpec.Ws -> {
                    put("network", "ws")
                    putJsonObject("wsSettings") {
                        put("path", transport.path)
                        if (transport.host.isNotBlank()) put("host", transport.host)
                    }
                }
                is TransportSpec.HttpUpgrade -> {
                    put("network", "httpupgrade")
                    putJsonObject("httpupgradeSettings") {
                        put("path", transport.path)
                        if (transport.host.isNotBlank()) put("host", transport.host)
                    }
                }
                is TransportSpec.Xhttp -> {
                    put("network", "xhttp")
                    putJsonObject("xhttpSettings") {
                        put("path", transport.path)
                        put("host", transport.host)
                        put("mode", transport.mode)
                        // See XrayConfig.buildXhttp: what an uploading
                        // connection buffers, the jetsam step on iOS.
                        put("scMaxEachPostBytes", XrayConfig.XHTTP_MAX_EACH_POST_BYTES)
                        put("h2StreamReceiveWindow", XHTTP_H2_STREAM_RECEIVE_WINDOW)
                    }
                }
                is TransportSpec.Http -> error("not a core line: $line")
            }
            val hello = requireNotNull(fingerprint(spec.fingerprint, reality))
            if (reality) {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", spec.sni)
                    put("fingerprint", hello)
                    put("publicKey", spec.publicKey)
                    put("shortId", spec.shortId)
                }
            } else {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    // Empty, the server's own name, as sing-box does with an
                    // empty `server_name`.
                    if (spec.sni.isNotBlank()) put("serverName", spec.sni)
                    put("fingerprint", hello)
                    // The link's ALPN as it gives it, which Xray's own client
                    // writes too. On WebSocket and HTTP upgrade the core keeps
                    // exactly `h2, http/1.1` (a browser's offer, ProofKit's
                    // WebTunnel links) and turns any other list, or none, into
                    // `http/1.1` alone, the fingerprint those links avoid.
                    if (spec.alpn.isNotEmpty()) putJsonArray("alpn") { spec.alpn.forEach { add(it) } }
                }
            }
        }
    }

    /**
     * Hysteria2 is the core's `hysteria` protocol at version 2, over its own
     * QUIC transport with TLS. No fingerprint (the core refuses one here) and
     * no ALPN (h3 is its default). A published pin is verified: sing-box
     * cannot pin and skips verification for such a server ([SingBoxConfig]),
     * the core checks the certificate against the pin instead.
     *
     * Under Salamander, ChromeParrot is off (`quicParams.disableChromeParrot`):
     * the parrot shapes the QUIC handshake like Chrome's and with it replaces
     * the core's receive windows by Chrome's 6 and 15 MiB, while Salamander
     * hides that handshake on the wire, so it would cost the extension memory
     * and hide nothing. Every ProofKit Hysteria2 line carries Salamander.
     * Without it the hello is visible, and the parrot stays on.
     */
    private fun JsonObjectBuilder.putHysteria2(spec: OutboundSpec.Hysteria2) {
        put("protocol", "hysteria")
        putJsonObject("settings") {
            put("version", 2)
            put("address", spec.host); put("port", spec.port)
        }
        putJsonObject("streamSettings") {
            put("network", "hysteria")
            put("security", "tls")
            putJsonObject("tlsSettings") {
                if (spec.sni.isNotBlank()) put("serverName", spec.sni)
                spec.certPinSha256?.let { put("pinnedPeerCertSha256", requireNotNull(pin(it))) }
            }
            putJsonObject("hysteriaSettings") {
                put("version", 2)
                put("auth", spec.password)
            }
            spec.obfsPassword?.let { obfs ->
                putJsonObject("finalmask") {
                    putJsonArray("udp") {
                        addJsonObject {
                            put("type", "salamander")
                            putJsonObject("settings") { put("password", obfs) }
                        }
                    }
                    putJsonObject("quicParams") { put("disableChromeParrot", true) }
                }
            }
        }
    }

    /**
     * Answers every query the routing below sends here, itself. With no rule
     * an A query gets a fake address and an AAAA none, as sing-box's `fakeip`
     * server answers them; HTTPS is refused, as sing-box's iOS shape refuses
     * it. Under the bypass a name on the region's lists is first asked of the
     * network's own resolver, every type, as sing-box sends those names to
     * `dns-direct`.
     */
    private fun JsonArrayBuilder.addDnsOutbound(directResolver: String?, lists: XrayGeodata.Lists?) {
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
                    if (lists != null) {
                        addJsonObject {
                            put("action", "direct")
                            putJsonArray("domain") { lists.domains.forEach { add(it) } }
                        }
                    }
                    addJsonObject {
                        put("action", "return")
                        put("qType", QTYPE_HTTPS)
                        put("rCode", RCODE_REFUSED)
                    }
                }
            }
        }
    }

    /**
     * In an order that matters, as sing-box's iOS route: the system's queries
     * to the resolver above, TCP and UDP, matched by inbound so dns-direct's
     * own are not caught; then, under the bypass, dns-direct's queries out
     * directly, the local network, the region's names and its addresses.
     * Everything else falls to `out`. `AsIs`: no name is resolved just to try
     * the address rules against it.
     */
    private fun JsonObjectBuilder.putRouting(lists: XrayGeodata.Lists?) {
        putJsonObject("routing") {
            put("domainStrategy", "AsIs")
            putJsonArray("rules") {
                addJsonObject {
                    put("type", "field")
                    putJsonArray("inboundTag") { add("in") }
                    put("network", "tcp,udp"); put("port", 53)
                    put("outboundTag", "dns-out")
                }
                if (lists != null) {
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("inboundTag") { add(DNS_DIRECT) }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { XrayConfig.PRIVATE_RANGES.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { lists.domains.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { lists.cidrs.forEach { add(it) } }
                        put("outboundTag", "direct")
                    }
                }
            }
        }
    }
}
