package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A location in the forms a router takes. OpenWrt's Podkop reads a share link (its
 * "URL" mode) or a sing-box outbound (its "outbound" mode); Keenetic's XKeen runs Xray
 * and reads an Xray outbound. Each outbound is one object tagged [TAG], printed to be
 * pasted into a file — the router's own config decides what goes through it.
 *
 * olcRTC has no router form: a room needs the engine, which no router runs.
 */
object RouterExport {
    const val TAG = "proxy"

    private val pretty = Json { prettyPrint = true }

    private fun print(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    /**
     * A sing-box outbound, exactly what the app's own sing-box would dial; null for
     * XHTTP, which sing-box does not speak (Podkop cannot carry it either — XKeen can).
     */
    fun singBoxOutbound(spec: OutboundSpec): String? =
        if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) null
        else print(singBox(spec))

    /**
     * An Xray outbound for the same server; null for the HTTP transport, which
     * Xray dropped in favour of XHTTP and a router's Xray would refuse.
     */
    fun xrayOutbound(spec: OutboundSpec): String? =
        if (transportOf(spec) is TransportSpec.Http) null else print(xray(spec))

    private fun transportOf(spec: OutboundSpec): TransportSpec? = when (spec) {
        is OutboundSpec.Vless -> spec.transport
        is OutboundSpec.Trojan -> spec.transport
        is OutboundSpec.Vmess -> spec.transport
        is OutboundSpec.Hysteria2, is OutboundSpec.Shadowsocks -> null
    }

    /**
     * The oldest Xray that reads [spec]'s outbound, where that is newer than any Xray
     * a router is likely to carry: native Hysteria2 arrived in Xray 26.3. Null when
     * any current Xray does.
     */
    fun xrayMinimumVersion(spec: OutboundSpec): String? = if (spec is OutboundSpec.Hysteria2) "26.3" else null

    internal fun xray(spec: OutboundSpec): JsonObject = buildJsonObject {
        put("tag", TAG)
        when (spec) {
            is OutboundSpec.Vless -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", spec.host); put("port", spec.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", spec.uuid); put("encryption", "none")
                                    // Vision is a TCP thing; XHTTP with it fails at runtime.
                                    if (!spec.flow.isNullOrBlank() && spec.transport == TransportSpec.Tcp) put("flow", spec.flow)
                                }
                            }
                        }
                    }
                }
                putJsonObject("streamSettings") {
                    putTransport(spec.transport)
                    if (spec.publicKey.isNotBlank()) {
                        put("security", "reality")
                        putJsonObject("realitySettings") {
                            put("serverName", spec.sni)
                            put("fingerprint", spec.fingerprint)
                            put("publicKey", spec.publicKey)
                            put("shortId", spec.shortId)
                        }
                    } else if (spec.plain) {
                        put("security", "none")
                    } else {
                        putTls(TlsSpec(spec.sni, insecure = spec.insecure, fingerprint = spec.fingerprint))
                    }
                }
            }
            is OutboundSpec.Trojan -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject { put("address", spec.host); put("port", spec.port); put("password", spec.password) }
                    }
                }
                putJsonObject("streamSettings") {
                    putTransport(spec.transport)
                    putTls(spec.tls)
                }
            }
            is OutboundSpec.Shadowsocks -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", spec.host); put("port", spec.port)
                            put("method", spec.method); put("password", spec.password)
                        }
                    }
                }
            }
            is OutboundSpec.Vmess -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", spec.host); put("port", spec.port)
                            putJsonArray("users") {
                                addJsonObject { put("id", spec.uuid); put("alterId", spec.alterId); put("security", spec.security) }
                            }
                        }
                    }
                }
                putJsonObject("streamSettings") {
                    putTransport(spec.transport)
                    spec.tls?.let { putTls(it) } ?: put("security", "none")
                }
            }
            // Xray's native Hysteria2: the credential is hysteriaSettings.auth, Salamander
            // lives under finalmask.udp, and the pin is one lowercase-hex string — the
            // same shape ProofKit's own Xray client profile uses.
            is OutboundSpec.Hysteria2 -> {
                put("protocol", "hysteria")
                putJsonObject("settings") { put("version", 2); put("address", spec.host); put("port", spec.port) }
                putJsonObject("streamSettings") {
                    put("network", "hysteria")
                    put("security", "tls")
                    putJsonObject("hysteriaSettings") { put("version", 2); put("auth", spec.password) }
                    putJsonObject("tlsSettings") {
                        put("serverName", spec.sni)
                        putJsonArray("alpn") { add("h3") }
                        if (spec.insecure) put("allowInsecure", true)
                        spec.certPinSha256?.takeIf { it.isNotBlank() }?.let { put("pinnedPeerCertSha256", it.lowercase()) }
                    }
                    spec.obfsPassword?.takeIf { it.isNotBlank() }?.let { password ->
                        putJsonObject("finalmask") {
                            putJsonArray("udp") {
                                addJsonObject {
                                    put("type", "salamander")
                                    putJsonObject("settings") { put("password", password) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putTransport(transport: TransportSpec) {
        when (transport) {
            TransportSpec.Tcp -> put("network", "tcp")
            is TransportSpec.Grpc -> {
                put("network", "grpc")
                putJsonObject("grpcSettings") { put("serviceName", transport.serviceName) }
            }
            is TransportSpec.Ws -> {
                put("network", "ws")
                putJsonObject("wsSettings") {
                    put("path", transport.path.ifBlank { "/" })
                    if (transport.host.isNotBlank()) put("host", transport.host)
                }
            }
            is TransportSpec.HttpUpgrade -> {
                put("network", "httpupgrade")
                putJsonObject("httpupgradeSettings") {
                    put("path", transport.path.ifBlank { "/" })
                    if (transport.host.isNotBlank()) put("host", transport.host)
                }
            }
            is TransportSpec.Xhttp -> {
                put("network", "xhttp")
                putJsonObject("xhttpSettings") {
                    put("path", transport.path)
                    if (transport.host.isNotBlank()) put("host", transport.host)
                    put("mode", transport.mode)
                }
            }
            is TransportSpec.Http -> error("Xray has no HTTP transport: xrayOutbound answers null for it")
        }
    }

    private fun JsonObjectBuilder.putTls(tls: TlsSpec) {
        put("security", "tls")
        putJsonObject("tlsSettings") {
            put("serverName", tls.sni)
            tls.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
            if (tls.alpn.isNotEmpty()) putJsonArray("alpn") { tls.alpn.forEach { add(it) } }
            if (tls.insecure) put("allowInsecure", true)
        }
    }

    // The sing-box outbound, built here so the router export reads nothing of the
    // app's own sing-box config builder. The shapes are the ones that builder wrote
    // for the same specs (RouterExportNoSingBoxTest compares them).
    private fun singBox(spec: OutboundSpec): JsonObject {
        val built = buildJsonArray { addOutbound(spec) }.single().jsonObject
        return JsonObject(built + ("tag" to JsonPrimitive(TAG)))
    }

    private val BROWSER_ALPN = listOf("h2", "http/1.1")

    private fun singBoxFlow(flow: String?): String? {
        val written = flow?.trim().orEmpty()
        return when (written.lowercase()) {
            "" -> null
            "xtls-rprx-vision", "xtls-rprx-vision-udp443" -> "xtls-rprx-vision"
            else -> written
        }
    }

    private fun singBoxFingerprint(fp: String?): String {
        val name = fp?.trim()?.lowercase().orEmpty()
        return when {
            name.isEmpty() -> "chrome"
            name in SINGBOX_FINGERPRINTS -> name
            name.startsWith("chrome") || name.startsWith("hellochrome") -> "chrome"
            name.startsWith("hellofirefox") -> "firefox"
            name.startsWith("hellosafari") -> "safari"
            name.startsWith("helloios") -> "ios"
            name.startsWith("helloedge") -> "edge"
            name.startsWith("hello360") -> "360"
            name.startsWith("helloqq") -> "qq"
            name.startsWith("helloandroid") -> "android"
            name.startsWith("randomized") -> "randomized"
            else -> "chrome"
        }
    }

    private val SINGBOX_FINGERPRINTS = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
        "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
    )

    private fun JsonArrayBuilder.addOutbound(spec: OutboundSpec) {
        when (spec) {
            is OutboundSpec.Vless -> addJsonObject {
                put("type", "vless"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("uuid", spec.uuid); put("packet_encoding", "xudp")
                singBoxFlow(spec.flow)?.let { put("flow", it) }
                putSbTransport(spec.transport)
                // `security=none` is VLESS in the clear, and a `tls` block there is
                // a handshake the server answers with a reset.
                if (!spec.plain) putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    if (spec.insecure && spec.publicKey.isBlank()) put("insecure", true)
                    // sing-box offers `http/1.1` alone on WebSocket and HTTP
                    // upgrade when given no ALPN, the fingerprint ProofKit's
                    // WebTunnel links avoid by asking for a browser's
                    // `h2, http/1.1`. Written for exactly that list, as Xray
                    // and xray-rust keep it; any other stays unwritten, since
                    // sing-box would offer it as given and a server that picks
                    // h2 would end the upgrade.
                    if (spec.alpn == BROWSER_ALPN && spec.publicKey.isBlank() &&
                        (spec.transport is TransportSpec.Ws || spec.transport is TransportSpec.HttpUpgrade)
                    ) {
                        putJsonArray("alpn") { BROWSER_ALPN.forEach { add(it) } }
                    }
                    putJsonObject("utls") { put("enabled", true); put("fingerprint", singBoxFingerprint(spec.fingerprint)) }
                    // Same rule as XrayConfig: a link without a public key is
                    // VLESS over ordinary TLS, and `TransportKind.Tls` exists to
                    // name exactly that case. A reality block with an empty key
                    // is not a weaker handshake, it is a rejected config.
                    if (spec.publicKey.isNotBlank()) {
                        putJsonObject("reality") {
                            put("enabled", true)
                            put("public_key", spec.publicKey)
                            put("short_id", spec.shortId)
                        }
                    }
                }
                // Deliberately loud rather than best-effort. sing-box has no
                // xhttp transport (see XrayConfig), and emitting one anyway
                // produced a config the core silently refused — on iOS that
                // looked like a tunnel that connected and carried nothing.
                // xhttp belongs to Xray; callers route it there.
                require(spec.transport !is TransportSpec.Xhttp) {
                    "sing-box cannot speak xhttp — build this location with the Xray form"
                }
            }
            is OutboundSpec.Hysteria2 -> addJsonObject {
                put("type", "hysteria2"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("password", spec.password)
                if (spec.obfsPassword != null) {
                    putJsonObject("obfs") {
                        put("type", "salamander"); put("password", spec.obfsPassword)
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    // A published pin means the server presents a self-signed
                    // certificate that no CA store can validate. sing-box has no
                    // pinning option, so verifying against the system store would
                    // reject every connection — which is exactly what it did. Skip
                    // verification in that case; the Salamander obfuscation and the
                    // auth password still gate the connection, but note that the
                    // fingerprint the operator published is NOT being checked.
                    put("insecure", spec.insecure || spec.certPinSha256 != null)
                }
            }
            is OutboundSpec.Trojan -> addJsonObject {
                put("type", "trojan"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("password", spec.password)
                putSbTls(spec.tls)
                putSbTransport(spec.transport)
            }
            is OutboundSpec.Shadowsocks -> addJsonObject {
                put("type", "shadowsocks"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("method", spec.method); put("password", spec.password)
            }
            is OutboundSpec.Vmess -> addJsonObject {
                put("type", "vmess"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("uuid", spec.uuid); put("security", spec.security)
                if (spec.alterId > 0) put("alter_id", spec.alterId)
                spec.tls?.let { putSbTls(it) }
                putSbTransport(spec.transport)
            }
        }
    }

    /** Ordinary TLS, as Trojan and VMess links ask for it. */
    private fun JsonObjectBuilder.putSbTls(tls: TlsSpec) {
        putJsonObject("tls") {
            put("enabled", true); put("server_name", tls.sni)
            if (tls.insecure) put("insecure", true)
            if (tls.alpn.isNotEmpty()) putJsonArray("alpn") { tls.alpn.forEach { add(it) } }
            tls.fingerprint?.let { fp ->
                putJsonObject("utls") { put("enabled", true); put("fingerprint", singBoxFingerprint(fp)) }
            }
        }
    }

    /** The stream transport, when it is not plain TCP. xhttp never reaches here (Xray's). */
    private fun JsonObjectBuilder.putSbTransport(transport: TransportSpec) {
        when (transport) {
            TransportSpec.Tcp, is TransportSpec.Xhttp -> Unit
            is TransportSpec.Grpc -> putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", transport.serviceName)
            }
            is TransportSpec.Ws -> putJsonObject("transport") {
                put("type", "ws"); put("path", transport.path)
                if (transport.host.isNotBlank()) putJsonObject("headers") { put("Host", transport.host) }
            }
            is TransportSpec.HttpUpgrade -> putJsonObject("transport") {
                put("type", "httpupgrade"); put("path", transport.path)
                if (transport.host.isNotBlank()) put("host", transport.host)
            }
            is TransportSpec.Http -> putJsonObject("transport") {
                put("type", "http")
                if (transport.hosts.isNotEmpty()) putJsonArray("host") { transport.hosts.forEach { add(it) } }
                put("path", transport.path)
            }
        }
    }
}
