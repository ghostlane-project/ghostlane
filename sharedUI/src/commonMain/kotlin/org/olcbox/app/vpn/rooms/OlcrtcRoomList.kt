package org.olcbox.app.vpn.rooms

import org.olcbox.app.crypt.PlatformCrypto
import org.olcbox.app.data.datasource.SubscriptionBodyCodec

/**
 * The room list of one olcRTC location as a subscription states it, read the way the
 * iPhone's tunnel reads it (iosApp/PacketTunnel/RoomList.swift), so that a running
 * client on either platform takes the same rooms from the same answer.
 *
 * A location's group is every `olcrtc://<carrier>?<transport>@<room>#<key>` line with
 * its key and its carrier: the first gives the primary room, later ones and the
 * `##rooms:` header under any of them give the failover rooms. A sibling carrier
 * that shares the key is another location, and so is its `##rooms` header.
 */
object OlcrtcRoomList {

    /** The primary room and the failover rooms after it, without repeats. */
    data class Parsed(val primary: String, val extras: List<String>) {
        /** Every room, the primary first, each once. */
        val all: List<String> get() = (listOf(primary) + extras).distinct()

        companion object {
            /** [primary] and [extras] as the engine is handed them: no repeats, the primary not among the extras. */
            fun of(primary: String, extras: List<String>): Parsed =
                Parsed(primary, (listOf(primary) + extras).distinct().drop(1))
        }
    }

    data class Fields(val carrier: String, val room: String, val key: String)

    private const val URI_PREFIX = "olcrtc://"

    /** The group of [keyHex] and [carrier] in [body] (plain text or base64), or null when it has no line of theirs. */
    fun parse(body: String, keyHex: String, carrier: String): Parsed? {
        val wantedKey = keyHex.trim().lowercase()
        val wantedCarrier = normalizedCarrier(carrier)
        if (wantedKey.isEmpty() || wantedCarrier.isEmpty()) return null
        var primary: String? = null
        val extras = mutableListOf<String>()
        var lastLineIsOurs = false
        for (raw in decodeBody(body).split(*LINE_BREAKS)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(URI_PREFIX)) {
                lastLineIsOurs = false
                val f = fields(line) ?: continue
                if (f.key.lowercase() != wantedKey || f.carrier != wantedCarrier) continue
                lastLineIsOurs = true
                if (primary == null) primary = f.room else extras += f.room
            } else if (line.startsWith("##") && lastLineIsOurs) {
                val field = line.substring(2)
                val colon = field.indexOf(':')
                if (colon < 0) continue
                if (field.substring(0, colon).trim().lowercase() == "rooms") {
                    extras += splitRooms(field.substring(colon + 1))
                }
            }
        }
        return primary?.let { Parsed.of(it, extras) }
    }

    /** The carrier (normalized), room and key of one `olcrtc://` line, or null when a part is missing. */
    fun fields(line: String): Fields? {
        val payload = line.removePrefix(URI_PREFIX)
        val transport = payload.indexOf('?').takeIf { it >= 0 } ?: return null
        val roomMarker = payload.indexOf('@', transport).takeIf { it >= 0 } ?: return null
        val keyMarker = payload.indexOf('#', roomMarker).takeIf { it >= 0 } ?: return null
        val carrier = normalizedCarrier(payload.substring(0, transport))
        val room = payload.substring(roomMarker + 1, keyMarker).trim()
        val afterKey = payload.substring(keyMarker + 1)
        val keyEnd = afterKey.indexOfFirst { it == '%' || it == '$' }.let { if (it < 0) afterKey.length else it }
        val key = afterKey.substring(0, keyEnd).trim()
        if (carrier.isEmpty() || room.isEmpty() || key.isEmpty()) return null
        return Fields(carrier, room, key)
    }

    /**
     * The carrier's one name. Strict: a name it does not know stays itself (lowercased),
     * not WB Stream as `LocationConfig.normalizeProvider` would have it, so an unknown
     * carrier's line never joins a WB Stream location's group.
     */
    fun normalizedCarrier(name: String): String = when (val v = name.trim().lowercase()) {
        "telemost", "yandex", "yandex_telemost" -> "telemost"
        "wbstream", "wb-stream", "wildberries" -> "wbstream"
        "jitsi", "jitsi-meet", "jitsi_meet", "meet" -> "jitsi"
        "salutejazz", "jazz", "sberjazz", "sber_jazz" -> "salutejazz"
        "vkcalls", "vk", "vkcall", "vk_calls", "vk-calls" -> "vkcalls"
        "mtslink", "mts", "mts-link", "mts_link" -> "mtslink"
        else -> v
    }

    fun sameCarrier(a: String, b: String): Boolean =
        normalizedCarrier(a).let { it.isNotEmpty() && it == normalizedCarrier(b) }

    /** Swift's `.newlines`, which RoomList.swift splits a body on (Kotlin's lines() takes CR and LF only). */
    private val LINE_BREAKS = charArrayOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')

    /** The room ids of a `##rooms:` value: split on commas and any whitespace, as RoomList.swift. */
    fun splitRooms(value: String): List<String> {
        val rooms = mutableListOf<String>()
        val room = StringBuilder()
        for (c in value) {
            if (c == ',' || c.isWhitespace()) {
                if (room.isNotEmpty()) rooms += room.toString()
                room.clear()
            } else {
                room.append(c)
            }
        }
        if (room.isNotEmpty()) rooms += room.toString()
        return rooms
    }

    /** The body as text: itself when it carries links, else its base64 when that decodes to links. */
    fun decodeBody(body: String): String =
        if ("://" in body) body else SubscriptionBodyCodec.decodeBase64(body) ?: body

    /** A room as a log line may name it: the first 4 bytes of its SHA-256, in hex (iOS's RoomDigest). */
    fun digest(room: String): String = hex(PlatformCrypto.sha256(room.encodeToByteArray()).copyOf(4))

    /** The digests of [rooms], comma-separated. */
    fun digests(rooms: List<String>): String = rooms.joinToString(",") { digest(it) }

    internal fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
