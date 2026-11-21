package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec

/** What more than one kind of line reads or writes the same way. */
internal object CoreSupport {
    /** The names [helloName] answers as they are; a link's other spellings are read as the nearest of them. */
    private val HELLO_NAMES = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private val VISION_FLOWS = setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")

    /**
     * A link's `fp` as the core is given it: the name sing-box has for it,
     * which is what the Go engines send for the same link, with sing-box's
     * chrome variants (`chrome_psk`, `chrome_pq`, …) read as chrome, the only
     * names of theirs the core lacks. Links carry Xray's names too
     * (`hellochrome_133`, `hellofirefox_148`, `randomizednoalpn`, `unsafe`, or
     * `Chrome`): each is read as the nearest name here, and a name with none,
     * or no `fp` at all, as chrome.
     */
    fun helloName(fp: String?): String {
        val name = fp?.trim()?.lowercase().orEmpty()
        return when {
            name.isEmpty() -> "chrome"
            name in HELLO_NAMES -> name
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

    /**
     * A certificate pin as the core reads `pinnedPeerCertSha256`: compact
     * lower-case hex, colons dropped (OpenSSL's spelling is the usual one in
     * links). Null unless it is 32 bytes of hex.
     */
    fun pin(raw: String): String? {
        val hex = raw.trim().replace(":", "").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Why the core would refuse a VLESS link's [flow], or null: it knows Vision and no other. */
    fun flowRefusal(flow: String?): String? =
        if (flow != null && flow.trim().lowercase() !in VISION_FLOWS) "the core knows no flow but Vision" else null

    /**
     * A VLESS [spec] written with [address] for its server ([CoreLine.pinHost]):
     * the name stays the TLS server name and the Host of the transport where
     * the link left them to be the server's own.
     */
    fun pinVless(spec: OutboundSpec.Vless, address: String): OutboundSpec.Vless {
        val name = spec.host
        return spec.copy(
            host = address,
            sni = spec.sni.ifBlank { name },
            transport = when (val t = spec.transport) {
                is TransportSpec.Ws -> t.copy(host = t.host.ifBlank { name })
                is TransportSpec.HttpUpgrade -> t.copy(host = t.host.ifBlank { name })
                is TransportSpec.Xhttp -> t.copy(host = t.host.ifBlank { name })
                is TransportSpec.Http -> t.copy(hosts = t.hosts.ifEmpty { listOf(name) })
                else -> t
            },
        )
    }
}

/** A VLESS outbound's `protocol` and `settings`: one server, one user. */
internal fun JsonObjectBuilder.putVless(spec: OutboundSpec.Vless) {
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
                        // sing-box's Vision carries over XUDP.
                        if (spec.flow != null) put("flow", "xtls-rprx-vision-udp443")
                    }
                }
            }
        }
    }
}
