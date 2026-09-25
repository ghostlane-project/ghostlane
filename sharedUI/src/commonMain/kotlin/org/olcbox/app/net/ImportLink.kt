package org.olcbox.app.net

/**
 * The one-tap import link a panel or a bot hands to a person.
 *
 * Two spellings of one thing. `ghostlane://add?url=…` (or the older
 * `proofkit://add?url=…`) opens the app directly wherever the scheme is
 * registered. `https://proofkit.org/add#…` is what a Telegram button can carry:
 * a phone with the app opens it in the app, a phone without lands on a page
 * with the downloads. The payload rides the fragment there on purpose — a
 * fragment never leaves the browser, so the server list, which is a
 * credential, is in nobody's access log.
 *
 * The app's schemes also take the shape provider subscription pages write for
 * every client, `ghostlane://add/<list URL>` (and `import/`), the list raw or
 * percent-encoded. Never on the web origin: there the path reaches the server.
 *
 * The payload is whatever a paste accepts: a list URL, an `olcrtc://crypt1/…`
 * link of ours, a partner's `happ://crypt5/…`.
 */
object ImportLink {
    /**
     * The scheme links already handed out carry; it stays registered, and
     * [schemeLink] keeps writing it while installed apps that know only it exist.
     */
    const val SCHEME = "proofkit"
    /** The app's own name for the same link. */
    const val GHOSTLANE_SCHEME = "ghostlane"
    const val HOST = "add"
    /** What v2RayTun, Streisand and Hiddify call it; subscription pages write both. */
    const val IMPORT_HOST = "import"
    const val WEB_ORIGIN = "https://proofkit.org"
    const val WEB_PATH = "/add"

    private val schemePrefixes = listOf(GHOSTLANE_SCHEME, SCHEME).flatMap { scheme ->
        listOf("$scheme://$HOST", "$scheme://$IMPORT_HOST")
    }
    private val webPrefixes = listOf("$WEB_ORIGIN$WEB_PATH", "https://www.proofkit.org$WEB_PATH")

    fun schemeLink(payload: String): String = "$SCHEME://$HOST?url=${encode(payload)}"

    fun webLink(payload: String): String = "$WEB_ORIGIN$WEB_PATH#${encode(payload)}"

    /** The payload of an import link, or null for anything that is not one. */
    fun payloadOf(uri: String): String? {
        val text = uri.trim()
        val lower = text.lowercase()
        val scheme = schemePrefixes.firstOrNull { lower.startsWith(it) }
        val prefix = scheme ?: webPrefixes.firstOrNull { lower.startsWith(it) } ?: return null
        var rest = text.substring(prefix.length)
        val slashed = rest.startsWith("/")
        if (slashed) rest = rest.substring(1)
        val encoded = when {
            rest.isEmpty() -> return null
            rest.startsWith("#") -> rest.substring(1)
            rest.startsWith("?") -> queryValue(rest.substring(1), "url") ?: return null
            // add/<list URL>: raw as pages write it, whose own `?` and `#` belong
            // to the list, or percent-encoded whole. Decoding a raw one would eat
            // an escape the list itself carries, so only an encoded one is decoded.
            slashed && scheme != null -> return (if ("://" in rest) rest else decode(rest)).trim().takeIf { it.isNotEmpty() }
            else -> return null
        }
        return decode(encoded).trim().takeIf { it.isNotEmpty() }
    }

    private fun queryValue(query: String, key: String): String? =
        query.substringBefore('#').split('&').firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=') == key) pair.substringAfter('=', "") else null
        }

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    /**
     * Percent-decoding that leaves a stray `%` alone: a list URL pasted raw
     * into the fragment is still a list URL, not a decoding error.
     */
    private fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            for (b in ch.toString().encodeToByteArray()) out.add(b)
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
