package org.olcbox.app.vpn.rooms

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.olcbox.app.data.model.SubscriptionMetadata

/**
 * A host's refusal of a room-list fetch, as the iPhone's tunnel reads it
 * (iosApp/PacketTunnel/DeviceHeaders.swift `refusal`, `reason`): a 2xx answer whose
 * `x-hwid-max-devices-reached` (this device is over the list's limit) or
 * `x-hwid-not-supported` (the host admits only an app that names its device) says
 * `true`. Its body is the host's reason written as a server line, never a room, so a
 * running client keeps the rooms it has.
 */
data class RoomListRefusal(
    /** The flag that said it. */
    val flag: String,
    /** The host's reason from `announce`, as one log line may carry it; null when none reads. */
    val reason: String?
) {
    companion object {
        /** In the order they are asked. */
        val FLAGS = listOf("x-hwid-max-devices-reached", "x-hwid-not-supported")

        /**
         * The refusal in an answer, or null. Only a 2xx can be one; anything else is a
         * failure, whatever its headers say. [header] looks a name up in any case.
         */
        fun of(status: Int, header: (String) -> String?): RoomListRefusal? {
            if (status !in 200..299) return null
            val flag = FLAGS.firstOrNull { header(it)?.trim()?.lowercase() == "true" } ?: return null
            return RoomListRefusal(flag, reason(header("announce")))
        }

        /**
         * The `announce` text as one log line may carry it: the text or, behind `base64:`, the
         * text it encodes (padding optional); every control, format and line-breaking character
         * dropped; trimmed; cut to [SubscriptionMetadata.ANNOUNCE_MAX_CHARS] UTF-16 units, never
         * inside a character. Null for none, for `0` (the host's "take it down") and for base64
         * that does not decode.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun reason(raw: String?): String? {
            var text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: return null
            if (text.lowercase().startsWith("base64:")) {
                val encoded = text.substring("base64:".length).trim()
                text = runCatching {
                    Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(encoded).decodeToString()
                }.getOrNull() ?: return null
            }
            val clean = text.filterNot { it.category in DROPPED || it == '\n' || it == '\r' }.trim()
            if (clean.isEmpty()) return null
            val max = SubscriptionMetadata.ANNOUNCE_MAX_CHARS
            if (clean.length <= max) return clean
            // Not inside a surrogate pair: one character is two units there.
            return clean.substring(0, if (clean[max - 1].isHighSurrogate()) max - 1 else max)
        }

        private val DROPPED = setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR
        )
    }
}
