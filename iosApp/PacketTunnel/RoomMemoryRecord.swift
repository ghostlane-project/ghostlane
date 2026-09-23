import Foundation

/// What RoomMemory keeps in the App Group (`olcrtc-rooms.json`), and which
/// start may begin with it.
///
/// Apart from RoomMemory, which needs CryptoKit for the key's digest and the
/// App Group for the file: Foundation only, so scripts/test-ios-room-list.sh
/// compiles it with RoomList and its test on any swift.org toolchain.
///
/// ai-generated: the whole file.
struct RoomMemoryRecord: Codable, Equatable {
    /// The key's digest, never the key; RoomMemory computes it.
    let key: String
    /// The carrier, as `RoomList.normalizedCarrier` names it. Nil in a record
    /// written before carriers were told apart, when the key alone named a
    /// location - see `rooms`.
    let carrier: String?
    let primary: String
    let extras: [String]
    /// Seconds since 1970.
    let at: TimeInterval

    /// Rooms live about a day on the providers this is for; an older memory is
    /// only dead rooms, and it is left alone.
    static let maxAge: TimeInterval = 24 * 60 * 60

    /// The remembered rooms, when this record is the memory of a start of
    /// `carrier` whose key's digest is `keyDigest`; nil when it belongs to
    /// another start or has expired.
    ///
    /// A record without a carrier comes from the build that grouped rooms by
    /// key alone, and what it holds depends on the subscription. The platform
    /// writes one line per carrier with one key - Telemost, `· WB`, `· SJ` -
    /// and no `##` headers, so there the old record may mix carriers: a start
    /// without a `##rooms` group leaves it alone. A server that rotates rooms
    /// and advertises the next in `##rooms` runs one carrier per key, so its
    /// old record is its own, and may be the one live room a device that slept
    /// through a rotation still knows - dropping it on the update would leave
    /// the start with only retired rooms and no tunnel to fetch new ones
    /// through. `startHasRoomsGroup` says which of the two the start is: the
    /// app's list carries failover rooms, which come from `##rooms` alone.
    /// The next save writes the carrier in.
    func rooms(carrier wanted: String, keyDigest: String, startHasRoomsGroup: Bool, now: TimeInterval) -> RoomList.Parsed? {
        guard key == keyDigest, now - at < Self.maxAge else { return nil }
        if let carrier {
            guard RoomList.sameCarrier(carrier, wanted) else { return nil }
        } else {
            guard startHasRoomsGroup else { return nil }
        }
        return RoomList.Parsed(primary: primary, extras: extras)
    }
}
