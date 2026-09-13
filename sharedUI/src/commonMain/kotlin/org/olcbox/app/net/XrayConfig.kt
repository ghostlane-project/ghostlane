package org.olcbox.app.net

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
     * Build an Xray config for a vless+xhttp+reality location. Requires the spec's
     * transport to be [TransportSpec.Xhttp] (Xray's role here is xhttp only).
     */
    fun buildXhttp(spec: OutboundSpec.Vless, socksPort: Int = XRAY_SOCKS_PORT): String {
        val xhttp = spec.transport as? TransportSpec.Xhttp
            ?: error("XrayConfig.buildXhttp requires an xhttp transport")
        val obj = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "in"); put("listen", "127.0.0.1"); put("port", socksPort)
                    put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true) }
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
                    }
                }
            }
        }
        return obj.toString()
    }
}
