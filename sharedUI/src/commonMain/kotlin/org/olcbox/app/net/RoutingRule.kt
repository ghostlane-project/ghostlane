package org.olcbox.app.net

/**
 * One of the user's own routing rules: a domain, which covers its subdomains, or an
 * address prefix.
 *
 * [parse] takes what a person types or pastes — `Example.com`, `*.example.com`,
 * `https://example.com/page`, `пример.рф`, `203.0.113.7`, `2001:db8::/32` — and returns
 * the rule in the one spelling the settings store and every builder uses, or null
 * for anything that is neither. The spelling is what the cores match on the wire:
 * lowercase, a Cyrillic name in its `xn--` form, a prefix with its host bits cleared.
 */
sealed interface RoutingRule {
    /** How it is stored and shown: `example.com`, `203.0.113.0/24`, `2001:db8::/32`. */
    val text: String

    data class Domain(val name: String) : RoutingRule {
        override val text: String get() = name
    }

    data class Cidr(val prefix: String) : RoutingRule {
        override val text: String get() = prefix
        val isIpv6: Boolean get() = ':' in prefix
    }

    companion object {
        fun parse(input: String): RoutingRule? {
            val raw = input.trim().lowercase()
            if (raw.isEmpty()) return null
            parseCidr(raw)?.let { return it }
            return parseDomain(raw)
        }

        /** The rules in [texts] that parse, in order; a stored entry that no longer parses is dropped. */
        fun parseAll(texts: List<String>): List<RoutingRule> = texts.mapNotNull(::parse).distinct()

        private fun parseDomain(raw: String): Domain? {
            var host = raw.substringAfter("://")
            host = host.substringBefore('/').substringBefore('?').substringBefore('#')
            host = host.substringAfterLast('@')
            // A port, where there is one: a name has no colon of its own.
            if (host.count { it == ':' } == 1) host = host.substringBefore(':')
            host = host.removePrefix("*.").removePrefix(".").removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return null
            val labels = host.split('.')
            if (labels.size < 2) return null
            val ascii = labels.map { label -> Punycode.toAscii(label) ?: return null }
            if (ascii.any { !isLabel(it) }) return null
            // A last label of digits is an address someone mistyped, not a top-level domain.
            if (ascii.last().all { it.isDigit() }) return null
            val name = ascii.joinToString(".")
            return if (name.length <= 253) Domain(name) else null
        }

        private fun isLabel(label: String): Boolean =
            label.length in 1..63 &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                !label.startsWith('-') && !label.endsWith('-')

        private fun parseCidr(raw: String): Cidr? {
            val address = raw.substringBefore('/')
            val bits = if ('/' in raw) raw.substringAfter('/').toIntOrNull() ?: return null else null
            ipv4(address)?.let { octets ->
                val length = bits ?: 32
                if (length !in 0..32) return null
                return Cidr("${mask(octets, length, 8).joinToString(".")}/$length")
            }
            ipv6(address)?.let { hextets ->
                val length = bits ?: 128
                if (length !in 0..128) return null
                return Cidr("${compress(mask(hextets, length, 16))}/$length")
            }
            return null
        }

        private fun ipv4(address: String): List<Int>? {
            val parts = address.split('.')
            if (parts.size != 4) return null
            return parts.map { part ->
                if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
                part.toInt().takeIf { it in 0..255 } ?: return null
            }
        }

        private fun ipv6(address: String): List<Int>? {
            if (':' !in address || address.count { it == ':' } < 2) return null
            val halves = address.split("::")
            if (halves.size > 2) return null
            fun groups(part: String): List<Int>? =
                if (part.isEmpty()) emptyList() else part.split(':').map { group ->
                    if (group.isEmpty() || group.length > 4) return null
                    group.toIntOrNull(16) ?: return null
                }
            val head = groups(halves[0]) ?: return null
            if (halves.size == 1) return head.takeIf { it.size == 8 }
            val tail = groups(halves[1]) ?: return null
            val missing = 8 - head.size - tail.size
            if (missing < 1) return null
            return head + List(missing) { 0 } + tail
        }

        /** [values] of [width] bits each with every bit past [length] cleared. */
        private fun mask(values: List<Int>, length: Int, width: Int): List<Int> =
            values.mapIndexed { index, value ->
                val kept = (length - index * width).coerceIn(0, width)
                val keep = if (kept == 0) 0 else ((1 shl width) - 1) xor ((1 shl (width - kept)) - 1)
                value and keep
            }

        /** RFC 5952: lowercase, no leading zeros, the longest run of two or more zero groups as `::`. */
        private fun compress(hextets: List<Int>): String {
            var bestStart = -1
            var bestLength = 0
            var index = 0
            while (index < 8) {
                if (hextets[index] == 0) {
                    val start = index
                    while (index < 8 && hextets[index] == 0) index++
                    if (index - start > bestLength) {
                        bestStart = start
                        bestLength = index - start
                    }
                } else {
                    index++
                }
            }
            val text = hextets.map { it.toString(16) }
            if (bestLength < 2) return text.joinToString(":")
            val head = text.subList(0, bestStart).joinToString(":")
            val tail = text.subList(bestStart + bestLength, 8).joinToString(":")
            return "$head::$tail"
        }
    }
}

/**
 * RFC 3492 encoding of one domain label, as IDNA spells a non-ASCII name on the wire:
 * `пример` is `xn--e1afmkfd`. Only the encoder — the app never has to read one back —
 * and only what [RoutingRule] needs: a label of letters, digits and hyphens.
 */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    /** [label] as ASCII: itself when it already is, else `xn--` and its encoding; null when it cannot be one. */
    fun toAscii(label: String): String? {
        if (label.all { it.code < INITIAL_N }) return label
        val encoded = encode(codePoints(label) ?: return null) ?: return null
        return "xn--$encoded"
    }

    private fun codePoints(text: String): List<Int>? {
        val points = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val high = text[index]
            if (high.isHighSurrogate()) {
                val low = text.getOrNull(index + 1)?.takeIf { it.isLowSurrogate() } ?: return null
                points += ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
                index += 2
            } else {
                if (high.isLowSurrogate()) return null
                points += high.code
                index += 1
            }
        }
        return points
    }

    private fun encode(input: List<Int>): String? {
        val output = StringBuilder()
        input.filter { it < INITIAL_N }.forEach { output.append(it.toChar()) }
        val basic = output.length
        var handled = basic
        if (basic > 0) output.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < input.size) {
            val m = input.filter { it >= n }.minOrNull() ?: return null
            delta += (m - n).toLong() * (handled + 1)
            n = m
            for (point in input) {
                if (point < n) delta++
                if (point == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        output.append(digit(t + ((q - t) % (BASE - t)).toInt()))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(digit(q.toInt()))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    private fun adapt(delta: Long, points: Int, first: Boolean): Int {
        var d = if (first) delta / DAMP else delta / 2
        d += d / points
        var k = 0
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= BASE - TMIN
            k += BASE
        }
        return (k + ((BASE - TMIN + 1) * d) / (d + SKEW)).toInt()
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)
}
