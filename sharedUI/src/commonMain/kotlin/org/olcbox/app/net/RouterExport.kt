package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
        else print(SingBoxConfig.outbound(spec, TAG))

    /** An Xray outbound for the same server. */
    fun xrayOutbound(spec: OutboundSpec): String = print(xray(spec))

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
                    } else {
                        putTls(TlsSpec(spec.sni, fingerprint = spec.fingerprint))
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
}
