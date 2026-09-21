package org.olcbox.app.log

import org.olcbox.app.crypt.PlatformCrypto

/**
 * Rewrites a log line so it still says what happened without saying where.
 *
 * The exported log is what makes a support request answerable, so this removes
 * values rather than lines: an address becomes a tag, and the error, the port and the
 * timing around it survive. Two different hosts get two different tags, because "the
 * failover tried three and all refused" is a different fault from "one host refused
 * three times".
 *
 * The salt is per process on purpose. A short hash of an IPv4 address is otherwise a
 * confirmation oracle — an adversary who suspects an address computes its tag and
 * checks. Salted, a tag means something inside one log and nowhere else, which is all
 * diagnosis asks of it. We cannot map a tag back to a node either; the location label
 * in the same line is what support actually uses.
 *
 * Call it from `addLog` and nowhere earlier: transport state is decided by matching
 * raw engine text, and a scrubbed line must never reach those parsers.
 */
class LogScrubber(private val salt: Long) {

    fun scrub(line: String): String {
        var out = line
        // Links first: a crypt blob is base64 and could otherwise be picked apart by
        // the narrower rules below.
        out = CRYPT_LINK.replace(out, "<link>")
        out = UUID.replace(out, "<id>")
        // URLs need one pass of their own. Replacing only the hostname leaves
        // XHTTP paths and subscription identifiers visible after the host tag.
        out = URL.replace(out) { match ->
            val scheme = match.groupValues[1]
            val host = match.groupValues[2]
            val port = match.groupValues[3]
            val suffix = match.groupValues[4]
            buildString {
                append(scheme)
                append(tag(host.lowercase()))
                if (port.isNotEmpty()) append(':').append(port)
                if (suffix.isNotEmpty()) append("/<path>")
            }
        }
        out = OUR_HOST.replace(out, "<host>")
        // Core debug output includes every destination hostname. Keep repeated
        // destinations correlatable inside one export without publishing browsing history.
        out = HOSTNAME.replace(out) { match ->
            if (isDiagnosticHostname(out, match)) tag(match.value.lowercase()) else match.value
        }
        out = IPV6.replace(out) { tagIfPublicV6(it.value) }
        out = IPV4.replace(out) { tagIfPublicV4(it.value) }
        return out
    }

    /**
     * FNV-1a over the salted value, shown as twelve hex digits. The process salt
     * comes from the platform CSPRNG; the wider tag keeps hundreds of destinations
     * distinct inside a verbose export without making tags reversible.
     */
    private fun tag(value: String): String {
        var h = FNV_OFFSET xor salt
        for (c in value) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME
        }
        val short = ((h ushr 8) and 0xFFFFFFFFFFFFL).toString(16).padStart(12, '0')
        return "node#$short"
    }

    private fun isDiagnosticHostname(source: String, match: MatchResult): Boolean {
        val value = match.value
        if (value.substringAfterLast('.').lowercase() in NON_HOST_SUFFIXES) return false
        if (value.split('.').all { label -> label.all(Char::isDigit) }) return false
        // Java/Kotlin exception and class names conventionally capitalize the
        // final label. Mixed-case DNS labels are valid and must still be scrubbed.
        val lastLabel = value.substringAfterLast('.')
        if (lastLabel.firstOrNull()?.isUpperCase() == true && lastLabel.any(Char::isLowerCase)) return false
        val before = match.range.first.takeIf { it > 0 }?.let { source[it - 1] }
        val after = (match.range.last + 1).takeIf { it < source.length }?.let(source::get)

        // Stack symbols such as route.(*Router).Start are whitespace-delimited
        // tokens containing parentheses. They look domain-shaped in the middle
        // but are code, and replacing them destroys the useful call site.
        val tokenStart = source.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '\r'), match.range.first - 1) + 1
        val tokenEnd = source.indexOfAny(charArrayOf(' ', '\t', '\n', '\r'), match.range.last + 1)
            .let { if (it < 0) source.length else it }
        val token = source.substring(tokenStart, tokenEnd)
        val urlHost = "://" in token
        return '(' !in token && ')' !in token &&
            (urlHost || '/' !in token && '\\' !in token)
    }

    private fun tagIfPublicV4(addr: String): String {
        val o = addr.split('.').mapNotNull { it.toIntOrNull() }
        // Not an address at all — a version, a build number. Leave the text alone;
        // mangling ordinary words is a worse failure than leaving one address in.
        if (o.size != 4 || o.any { it > 255 }) return addr
        val local = when {
            o[0] == 127 || o[0] == 10 || o[0] == 0 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true          // CGNAT
            o.all { it == 255 } -> true
            else -> false
        }
        return if (local) addr else tag(addr)
    }

    private fun tagIfPublicV6(addr: String): String {
        // The pattern over-matches on purpose so it can take a whole address in one
        // bite; this is where a timestamp is turned away. A real address is either
        // compressed — and then it contains "::" — or written in full, which takes
        // seven colons. Two colons and no "::" is 15:28:17, not a host.
        //
        // Anchoring this in the regex instead was the first attempt and it was
        // worse than wrong: it matched from the *last* group, so 2a01:4f8:c17:b8f::1
        // was replaced as b8f::1 and the prefix stayed in the log, while
        // fdfe:dcba:9876::1 matched as 9876::1, no longer looked like a ULA, and a
        // private address got tagged.
        if (!addr.contains("::") && addr.count { it == ':' } < 4) return addr
        val a = addr.lowercase()
        // ULA (fc00::/7) covers the desktop TUN address; fe80::/10 is link-local.
        val local = a == "::1" || a == "::" ||
            a.startsWith("fc") || a.startsWith("fd") ||
            a.startsWith("fe8") || a.startsWith("fe9") ||
            a.startsWith("fea") || a.startsWith("feb")
        return if (local) addr else tag(addr)
    }

    companion object {
        /**
         * One salt for the life of the process, which is the unit a log file covers.
         */
        val default: LogScrubber by lazy {
            val salt = PlatformCrypto.randomBytes(Long.SIZE_BYTES)
                .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xffL) }
            LogScrubber(salt)
        }

        private const val FNV_OFFSET = -3750763034362895579L   // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L           // 0x100000001b3

        private val CRYPT_LINK = Regex("""\b(?:olcrtc|happ)://crypt\d/\S+""")

        private val UUID = Regex(
            """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-""" +
                """[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
        )

        private val OUR_HOST = Regex("""\b(?:[a-zA-Z0-9-]+\.)*proofkit\.org\b""")

        private val URL = Regex(
            """\b([a-zA-Z][a-zA-Z0-9+.-]*://)""" +
                """((?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?\.)+""" +
                """[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?)""" +
                """(?::(\d+))?([/?#][^\s]*)?"""
        )

        private val HOSTNAME = Regex(
            """(?<![\p{L}\p{N}_-])(?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?\.)+""" +
                """[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?(?![\p{L}\p{N}_-])"""
        )

        private val NON_HOST_SUFFIXES = setOf(
            "log", "json", "txt", "kt", "swift", "go", "srs", "exe", "dll", "so", "yaml", "yml", "conf"
        )

        private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

        // Greedy and loose on purpose: it must take the *whole* address, leading
        // groups included, so an empty group is allowed and a match can start at the
        // first group of a compressed address. What this lets through — timestamps,
        // "host:443:" fragments — is rejected in tagIfPublicV6, which is a far more
        // reliable place to say "this is not an address" than a regex is.
        private val IPV6 = Regex("""[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}""")
    }
}
