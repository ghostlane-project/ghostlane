import Foundation

/// The rooms of one olcRTC location, read out of a subscription body.
///
/// A server that moves its clients between short-lived rooms advertises the
/// next room under the location's line as a `##rooms:` header - ids that share
/// the line's key, carrier and transport. The app parses the whole subscription
/// in Kotlin; the tunnel extension, which is the process that outlives the app
/// and has to re-read the list after every handover, needs only this: the line
/// whose carrier and key are the running location's, and the rooms beside it.
///
/// The key alone does not name a location. The platform writes one line per
/// carrier for the same origin, all with the same key - Telemost, then WB
/// Stream (`· WB`), then SaluteJazz (`· SJ`) - and a room of one carrier is no
/// room at all to an engine running another: a Telemost engine told to fail
/// over into a WB room id only burns seconds on a join that cannot work.
///
/// Foundation only, so scripts/test-ios-room-list.sh can compile it with its
/// test on any swift.org toolchain.
///
/// ai-generated: the whole file.
enum RoomList {
    struct Parsed: Equatable {
        /// The room the matching line names.
        let primary: String
        /// The other rooms of the group, in the order advertised, primary excluded.
        let extras: [String]

        /// Primary first, then the extras, no duplicates.
        var all: [String] {
            var seen = Set<String>()
            return ([primary] + extras).filter { seen.insert($0).inserted }
        }
    }

    private static let uriPrefix = "olcrtc://"

    /// The rooms of the location whose carrier is `carrier` and whose key is
    /// `keyHex`, or nil when no line in the body carries both. Several such
    /// lines are one group too - a provider that lists each room as its own
    /// line - and their rooms join the extras after the header's. A line with
    /// the same key and another carrier is another location, and so is its
    /// `##rooms` header.
    static func parse(_ body: String, keyHex: String, carrier: String) -> Parsed? {
        let text = decodeBody(body)
        let wantedKey = keyHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let wantedCarrier = normalizedCarrier(carrier)
        guard !wantedKey.isEmpty, !wantedCarrier.isEmpty else { return nil }
        var primary: String?
        var extras: [String] = []
        var lastLineIsOurs = false
        for rawLine in text.split(omittingEmptySubsequences: true, whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix(uriPrefix) {
                lastLineIsOurs = false
                guard let parsed = fields(of: line),
                      parsed.key.lowercased() == wantedKey,
                      parsed.carrier == wantedCarrier
                else { continue }
                lastLineIsOurs = true
                if primary == nil {
                    primary = parsed.room
                } else {
                    extras.append(parsed.room)
                }
            } else if line.hasPrefix("##"), lastLineIsOurs {
                let field = line.dropFirst(2)
                guard let colon = field.firstIndex(of: ":") else { continue }
                let name = field[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                if name == "rooms" {
                    extras += splitRooms(String(field[field.index(after: colon)...]))
                }
            }
        }
        guard let primary else { return nil }
        let unique = Parsed(primary: primary, extras: extras).all
        return Parsed(primary: primary, extras: Array(unique.dropFirst()))
    }

    /// `olcrtc://<carrier>?<transport>@<room>#<key>[%client][$name]`: the
    /// carrier (see `normalizedCarrier`), the room and the key, or nil for a
    /// line that is not shaped like that. Mirrors
    /// LocationsDatasource.parseOlcRtcUri on the Kotlin side, which also
    /// refuses a line with no carrier before the `?`.
    static func fields(of line: String) -> (carrier: String, room: String, key: String)? {
        let payload = line.dropFirst(uriPrefix.count)
        guard let transport = payload.firstIndex(of: "?"),
              let roomMarker = payload[transport...].firstIndex(of: "@"),
              let keyMarker = payload[roomMarker...].firstIndex(of: "#")
        else { return nil }
        let carrier = normalizedCarrier(String(payload[..<transport]))
        let room = payload[payload.index(after: roomMarker)..<keyMarker].trimmingCharacters(in: .whitespaces)
        let afterKey = payload[payload.index(after: keyMarker)...]
        let keyEnd = afterKey.firstIndex { $0 == "%" || $0 == "$" } ?? afterKey.endIndex
        let key = afterKey[..<keyEnd].trimmingCharacters(in: .whitespaces)
        guard !carrier.isEmpty, !room.isEmpty, !key.isEmpty else { return nil }
        return (carrier, room, key)
    }

    /// A carrier as the engine names it, which is how the app hands the
    /// running location's over (`OlcrtcEngine.Parameters.carrierName`). The
    /// aliases are LocationConfig.normalizeProvider's, and a carrier added
    /// there belongs here too. A name it does not know stays itself,
    /// lowercased, where Kotlin falls back to WB Stream: that suits a picker,
    /// but here it would hand a WB engine the rooms of a carrier nobody taught
    /// this file. Such a line joins no group the app runs today.
    static func normalizedCarrier(_ name: String) -> String {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch value {
        case "telemost", "yandex", "yandex_telemost": return "telemost"
        case "wbstream", "wb-stream", "wildberries": return "wbstream"
        case "jitsi", "jitsi-meet", "jitsi_meet", "meet": return "jitsi"
        case "salutejazz", "jazz", "sberjazz", "sber_jazz": return "salutejazz"
        default: return value
        }
    }

    /// Whether two names are one carrier as `normalizedCarrier` reads them. An
    /// empty name is no carrier and matches nothing, itself included.
    static func sameCarrier(_ lhs: String, _ rhs: String) -> Bool {
        let carrier = normalizedCarrier(lhs)
        return !carrier.isEmpty && carrier == normalizedCarrier(rhs)
    }

    /// Comma or whitespace separated ids.
    static func splitRooms(_ value: String) -> [String] {
        value.split { $0 == "," || $0.isWhitespace }
            .map { String($0) }
            .filter { !$0.isEmpty }
    }

    /// A subscription body is either the line list itself or base64 of it
    /// (Happ / v2rayNG style, sometimes wrapped, often unpadded). Mirrors
    /// SubscriptionBodyCodec on the Kotlin side: decoded only when the result
    /// plainly carries scheme links, so plain text is never mangled.
    static func decodeBody(_ body: String) -> String {
        if body.contains("://") { return body }
        let compact = body.filter { !$0.isWhitespace }
        guard compact.count >= 16,
              compact.allSatisfy({ $0.isLetter || $0.isNumber || "+/-_=".contains($0) })
        else { return body }
        let standard = compact.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let padded = standard + String(repeating: "=", count: (4 - standard.count % 4) % 4)
        guard let data = Data(base64Encoded: padded),
              let decoded = String(data: data, encoding: .utf8),
              decoded.contains("://")
        else { return body }
        return decoded
    }
}
