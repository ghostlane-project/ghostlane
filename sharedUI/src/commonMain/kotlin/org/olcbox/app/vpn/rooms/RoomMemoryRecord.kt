package org.olcbox.app.vpn.rooms

import kotlinx.serialization.Serializable
import org.olcbox.app.crypt.PlatformCrypto

/**
 * The last room list a running olcRTC client was handed, kept for the next start of the
 * same location (iosApp/PacketTunnel/RoomMemoryRecord.swift): a restart (the watchdog, a
 * network change) reads the location from the store again, and the store has only what the
 * app's own refresh wrote, which can predate a rotation the running client followed.
 *
 * Rooms live about a day on the carriers this is for, so an older record is left alone.
 * The key is kept as its digest, never as itself.
 */
@Serializable
data class RoomMemoryRecord(
    /** SHA-256 of the lowercased key, in hex ([keyDigest]). */
    val key: String,
    /** The carrier as [OlcrtcRoomList.normalizedCarrier] names it. */
    val carrier: String,
    val primary: String,
    val extras: List<String>,
    /** Seconds since 1970. */
    val at: Long,
) {
    /** The remembered rooms, when this is the memory of [carrier] with [keyDigest] and younger than [MAX_AGE_S]. */
    fun rooms(carrier: String, keyDigest: String, nowS: Long): OlcrtcRoomList.Parsed? {
        if (key != keyDigest || nowS - at >= MAX_AGE_S || nowS < at - MAX_AGE_S) return null
        if (!OlcrtcRoomList.sameCarrier(this.carrier, carrier)) return null
        return OlcrtcRoomList.Parsed.of(primary, extras)
    }

    companion object {
        const val MAX_AGE_S = 24 * 60 * 60L

        fun keyDigest(keyHex: String): String =
            OlcrtcRoomList.hex(PlatformCrypto.sha256(keyHex.trim().lowercase().encodeToByteArray()))

        fun of(rooms: OlcrtcRoomList.Parsed, carrier: String, keyHex: String, nowS: Long) = RoomMemoryRecord(
            key = keyDigest(keyHex),
            carrier = OlcrtcRoomList.normalizedCarrier(carrier),
            primary = rooms.primary,
            extras = rooms.extras,
            at = nowS
        )

        /**
         * The rooms a start begins with: the stored location's, then the remembered ones of the
         * same location (iOS's `initialRooms`), the primary first, each once.
         */
        fun initialRooms(primary: String, failover: List<String>, remembered: OlcrtcRoomList.Parsed?): OlcrtcRoomList.Parsed =
            OlcrtcRoomList.Parsed.of(primary, failover + remembered?.all.orEmpty())
    }
}
