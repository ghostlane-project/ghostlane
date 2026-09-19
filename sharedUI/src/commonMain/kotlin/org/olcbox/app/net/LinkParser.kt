package org.olcbox.app.net

object LinkParser {
    fun parse(line: String): OutboundSpec? {
        val t = line.trim()
        return when {
            t.startsWith("vless://") -> parseVless(t)
            t.startsWith("hysteria2://") -> parseHy2(t, "hysteria2://")
            t.startsWith("hy2://") -> parseHy2(t, "hy2://")
            else -> null
        }
    }

    private data class Parts(
        val userinfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val tag: String,
    )

    private fun splitLink(s: String, scheme: String): Parts? {
        val body = s.removePrefix(scheme)
        val hashIdx = body.indexOf('#')
        val tag = if (hashIdx >= 0) urlDecode(body.substring(hashIdx + 1)) else ""
        val beforeHash = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = beforeHash.indexOf('?')
        val authority = if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash
        val query = if (qIdx >= 0) parseQuery(beforeHash.substring(qIdx + 1)) else emptyMap()
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val userinfo = authority.substring(0, atIdx)
        val hostPort = authority.substring(atIdx + 1)
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return Parts(userinfo, host, port, query, tag)
    }

    private fun parseVless(s: String): OutboundSpec.Vless? {
        val p = splitLink(s, "vless://") ?: return null
        val type = p.query["type"] ?: "tcp"
        val transport = when (type.lowercase()) {
            "xhttp" -> TransportSpec.Xhttp(
                path = p.query["path"] ?: "/",
                host = p.query["host"] ?: p.query["sni"].orEmpty(),
                mode = p.query["mode"] ?: "auto",
            )
            "grpc" -> TransportSpec.Grpc(p.query["serviceName"].orEmpty())
            "tcp", "raw" -> TransportSpec.Tcp
            // Unknown transports cannot be dialled as TCP: that looks like a
            // valid imported profile but never speaks the server's protocol.
            else -> return null
        }
        return OutboundSpec.Vless(
            uuid = p.userinfo,
            host = p.host,
            port = p.port,
            sni = p.query["sni"].orEmpty(),
            publicKey = p.query["pbk"].orEmpty(),
            shortId = p.query["sid"].orEmpty(),
            fingerprint = p.query["fp"] ?: "chrome",
            flow = if (transport is TransportSpec.Tcp) p.query["flow"]?.takeIf { it.isNotBlank() } else null,
            transport = transport,
            tag = p.tag.ifBlank { p.host },
        )
    }

    private fun parseHy2(s: String, scheme: String): OutboundSpec.Hysteria2? {
        val p = splitLink(s, scheme) ?: return null
        return OutboundSpec.Hysteria2(
            password = p.userinfo,
            host = p.host,
            port = p.port,
            sni = p.query["sni"].orEmpty(),
            obfsPassword = p.query["obfs-password"]?.takeIf { it.isNotBlank() },
            // A pinSHA256 in the link means the operator runs a self-signed cert and
            // expects verification by fingerprint. sing-box has no pinning option, so
            // system-CA verification would reject that certificate every time — see
            // SingBoxConfig.build for how this is turned into a TLS setting.
            certPinSha256 = p.query["pinSHA256"]?.takeIf { it.isNotBlank() },
            insecure = p.query["insecure"] == "1" || p.query["insecure"] == "true",
            tag = p.tag.ifBlank { p.host },
        )
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) {
                null
            } else {
                urlDecode(it.substring(0, eq)) to urlDecode(it.substring(eq + 1))
            }
        }.toMap()

    /**
     * Percent-decoding that survives anything outside ASCII.
     *
     * `%XX` is a *byte*, and a character above U+007F is several of them. Turning
     * each escape straight into a Char with that code point is Latin-1 decoding,
     * which is why a subscription named `🇷🇺 EKB · Hy2 → 🌐` — on the wire
     * `%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7…` — arrived on screen as
     * `ð‡· EKB Â· Hy2 â' ð`. Consecutive escapes are
     * gathered and decoded as UTF-8 together; a run that is not valid UTF-8
     * yields replacement characters rather than throwing, because a mangled name
     * must not cost the user the whole subscription.
     */
    private fun urlDecode(s: String): String {
        val out = StringBuilder()
        val pending = mutableListOf<Byte>()

        fun flushPending() {
            if (pending.isEmpty()) return
            out.append(pending.toByteArray().decodeToString())
            pending.clear()
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        pending.add(hex.toByte()); i += 3
                    } else {
                        flushPending(); out.append(c); i++
                    }
                }
                c == '+' -> { flushPending(); out.append(' '); i++ }
                else -> { flushPending(); out.append(c); i++ }
            }
        }
        flushPending()
        return out.toString()
    }
}
