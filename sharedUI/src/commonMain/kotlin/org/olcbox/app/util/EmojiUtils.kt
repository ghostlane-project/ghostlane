package org.olcbox.app.util

/**
 * Splits a name into the emoji it opens with and the rest.
 *
 * With no emoji in front, it is [defaultEmoji] and the name as it came.
 *
 * The emoji is everything a reader sees as one picture, which is rarely one
 * character and often not one code point. A flag is two regional indicators; a
 * family is four people joined by zero-width joiners; a thumb with a skin tone is
 * a thumb and a modifier. This used to take the first code point and call it the
 * emoji, so `🇪🇸 ES · Madrid` came apart as a lone `🇪` — which a phone draws as
 * a boxed letter E — and a name that began with the other half, a boxed S, on
 * the card, on the button that connects to it and in the status strip.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    if (rawName.isBlank()) return defaultEmoji to ""
    val end = leadingEmojiEnd(rawName)
    if (end == 0) return defaultEmoji to rawName
    return rawName.substring(0, end) to rawName.substring(end).trim()
}

/**
 * How many chars of [text] its leading emoji takes, or 0 where it opens with
 * something else.
 *
 * Not a general grapheme breaker — the common standard library has none — but
 * the whole of what makes an emoji more than one code point: a second regional
 * indicator, variation selectors, skin tones, the keycap mark, the tag
 * characters of a subdivision flag, and anything joined on by a zero-width
 * joiner.
 */
internal fun leadingEmojiEnd(text: String): Int {
    val first = codePointAt(text, 0) ?: return 0
    if (!startsEmoji(first)) return 0
    var index = charCount(first)

    if (isRegionalIndicator(first)) {
        val second = codePointAt(text, index)
        // A flag is exactly two of them. A third begins the next flag.
        return if (second != null && isRegionalIndicator(second)) index + charCount(second) else index
    }

    while (true) {
        val next = codePointAt(text, index) ?: return index
        index += when {
            isEmojiTrailer(next) -> charCount(next)
            next == ZERO_WIDTH_JOINER -> {
                val joined = codePointAt(text, index + 1) ?: return index
                // A joiner with nothing an emoji can be after it is not ours to take.
                if (!startsEmoji(joined)) return index
                1 + charCount(joined)
            }
            else -> return index
        }
    }
}

private const val ZERO_WIDTH_JOINER = 0x200D

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

/** What may follow an emoji and still be part of it. */
private fun isEmojiTrailer(codePoint: Int): Boolean =
    codePoint == 0xFE0E || codePoint == 0xFE0F || // text and emoji presentation
        codePoint == 0x20E3 || // the keycap
        codePoint in 0x1F3FB..0x1F3FF || // skin tones
        codePoint in 0xE0020..0xE007F // tags: 🏴 + "gbsct" + cancel

/**
 * The ranges this always took for an emoji, kept as they were: the symbol blocks
 * of the basic plane, and everything from the first supplementary pictograph up.
 */
private fun startsEmoji(codePoint: Int): Boolean =
    codePoint in 0x2000..0x32FF || codePoint >= 0x1F000

private fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** The code point at [index], reading a surrogate pair as one; null past the end. */
private fun codePointAt(text: String, index: Int): Int? {
    if (index >= text.length) return null
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}
