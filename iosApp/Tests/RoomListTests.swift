import Foundation

/// Compiled with the production RoomList and RoomMemoryRecord by
/// scripts/test-ios-room-list.sh. No Cores framework, signing identity or device
/// is needed; a swift.org toolchain on Linux runs it as well as Xcode does.
///
/// ai-generated: the whole file.
@main
enum RoomListTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    static let key = String(repeating: "a", count: 64)
    static let otherKey = String(repeating: "b", count: 64)

    static func main() {
        // The header under our line, comma or space separated; the primary is
        // never repeated even when the server lists it again.
        let body = """
        #name: Israel
        olcrtc://telemost?vp8channel@11115586048655#\(key)$Israel
        ##name: IL-1
        ##rooms: 81055221156696, 52664279650262 11115586048655
        olcrtc://telemost?vp8channel@99990000000000#\(otherKey)$Other
        ##rooms: 12345
        """
        let parsed = RoomList.parse(body, keyHex: key, carrier: "telemost")
        check(parsed?.primary == "11115586048655", "primary is the matching line's room")
        check(parsed?.extras == ["81055221156696", "52664279650262"], "extras from the header, primary dropped: \(String(describing: parsed?.extras))")
        check(parsed?.all == ["11115586048655", "81055221156696", "52664279650262"], "all: primary first")

        // Another location's header is not ours.
        check(RoomList.parse(body, keyHex: otherKey, carrier: "telemost")?.extras == ["12345"], "the other line has its own header")
        check(RoomList.parse(body, keyHex: String(repeating: "c", count: 64), carrier: "telemost") == nil, "a key nobody carries")

        // Several lines with one key are one group: the first is the primary.
        let lines = "olcrtc://telemost?vp8channel@R1#\(key)\nolcrtc://telemost?vp8channel@R2#\(key)%client$Name\n##rooms: R3"
        check(RoomList.parse(lines, keyHex: key, carrier: "telemost") == RoomList.Parsed(primary: "R1", extras: ["R2", "R3"]), "same-key lines join the group")

        // One origin, three carriers, one key: the platform's shape. Each
        // location is its own carrier's lines and headers, nothing else - a
        // Telemost engine given a WB room id as a failover only loses time.
        let origin = """
        olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/99#\(key)$NL · olcRTC
        ##rooms: https://telemost.yandex.ru/j/98
        olcrtc://wbstream?vp8channel@wbcod#\(key)$NL · olcRTC · WB
        ##rooms: wbnext
        olcrtc://salutejazz?datachannel@sjcode:sjpass1#\(key)$NL · olcRTC · SJ
        olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/97#\(key)$NL · olcRTC
        """
        check(RoomList.parse(origin, keyHex: key, carrier: "telemost") == RoomList.Parsed(
            primary: "https://telemost.yandex.ru/j/99",
            extras: ["https://telemost.yandex.ru/j/98", "https://telemost.yandex.ru/j/97"]
        ), "Telemost sees its own lines and header only: \(String(describing: RoomList.parse(origin, keyHex: key, carrier: "telemost")))")
        check(RoomList.parse(origin, keyHex: key, carrier: "wbstream") == RoomList.Parsed(primary: "wbcod", extras: ["wbnext"]),
              "WB Stream: its own room first, its own header, no Telemost or SaluteJazz room")
        check(RoomList.parse(origin, keyHex: key, carrier: "salutejazz") == RoomList.Parsed(primary: "sjcode:sjpass1", extras: []),
              "SaluteJazz: its one room, and no WB header leaking in")
        check(RoomList.parse(origin, keyHex: key, carrier: "jitsi") == nil, "a carrier the origin does not offer")

        // The carrier is compared as the engine names it: the app hands over
        // `telemost`, a third-party line may say `yandex` or `JAZZ`.
        check(RoomList.parse("olcrtc://Yandex?vp8channel@R1#\(key)", keyHex: key, carrier: " TELEMOST ")?.primary == "R1", "carrier aliases and case")
        check(RoomList.parse("olcrtc://jazz?datachannel@S1#\(key)", keyHex: key, carrier: "salutejazz")?.primary == "S1", "jazz is SaluteJazz")

        // A carrier this file does not know joins no known carrier's group,
        // where Kotlin's picker would have called it WB Stream.
        let unknown = "olcrtc://wbstream?vp8channel@W1#\(key)\nolcrtc://newcarrier?datachannel@N1#\(key)\n##rooms: N2\nolcrtc://wbstream?vp8channel@W2#\(key)"
        check(RoomList.parse(unknown, keyHex: key, carrier: "wbstream") == RoomList.Parsed(primary: "W1", extras: ["W2"]), "an unknown carrier's line and header are left out")
        check(RoomList.parse("olcrtc://newcarrier?datachannel@N1#\(key)", keyHex: key, carrier: "wbstream") == nil, "an unknown carrier is not WB Stream here")
        check(RoomList.fields(of: "olcrtc://?vp8channel@R1#\(key)") == nil, "no carrier, no location (as in Kotlin)")

        // The key is compared without case, and stops at % or $.
        check(RoomList.fields(of: "olcrtc://jitsi?datachannel@https://meet.example/room#ABC%dev$n")?.key == "ABC", "key ends at %")
        check(RoomList.fields(of: "olcrtc://jitsi?datachannel@https://meet.example/room#ABC%dev$n")?.room == "https://meet.example/room", "a room may be a URL")
        check(RoomList.fields(of: "olcrtc://jitsi-meet?datachannel@https://meet.example/room#ABC")?.carrier == "jitsi", "the carrier, normalized")
        check(RoomList.fields(of: "olcrtc://nothing-here") == nil, "not a location line")
        check(RoomList.parse(body, keyHex: key.uppercased(), carrier: "telemost")?.primary == "11115586048655", "key case does not matter")

        // A base64 body is unwrapped; plain text is left alone.
        let encoded = Data(body.utf8).base64EncodedString()
        check(RoomList.parse(encoded, keyHex: key, carrier: "telemost")?.extras == ["81055221156696", "52664279650262"], "base64 body")
        let unpadded = String(encoded.reversed().drop { $0 == "=" }.reversed())
        check(RoomList.parse(unpadded, keyHex: key, carrier: "telemost")?.primary == "11115586048655", "unpadded base64 body")
        check(RoomList.decodeBody("not a subscription at all") == "not a subscription at all", "prose passes through")

        check(RoomList.splitRooms(" a,b  c\t,d ") == ["a", "b", "c", "d"], "split on comma and whitespace")

        // The carrier check the app's pushed list goes through.
        check(RoomList.sameCarrier("wbstream", "WB-Stream"), "an alias is the same carrier")
        check(!RoomList.sameCarrier("wbstream", "telemost"), "a sibling carrier is not")
        check(!RoomList.sameCarrier("", ""), "no carrier matches nothing, itself included")

        memory()
        print("ok")
    }

    /// RoomMemory's record: which start may begin with it.
    static func memory() {
        let digest = String(repeating: "d", count: 64)
        let otherDigest = String(repeating: "e", count: 64)
        let now: TimeInterval = 1_790_000_000
        let saved = RoomMemoryRecord(key: digest, carrier: "telemost", primary: "T1", extras: ["T2"], at: now - 60)
        let rooms = RoomList.Parsed(primary: "T1", extras: ["T2"])

        check(saved.rooms(carrier: "telemost", keyDigest: digest, startHasRoomsGroup: false, now: now) == rooms, "same carrier and key")
        check(saved.rooms(carrier: " Yandex ", keyDigest: digest, startHasRoomsGroup: false, now: now) == rooms, "an alias of the carrier")
        let handWritten = RoomMemoryRecord(key: digest, carrier: "TELEMOST", primary: "T1", extras: ["T2"], at: now - 60)
        check(handWritten.rooms(carrier: "telemost", keyDigest: digest, startHasRoomsGroup: false, now: now) == rooms, "the stored carrier's case")
        // The bug this record's carrier is for: one origin's WB Stream start
        // must not begin with the rooms its Telemost start learned.
        check(saved.rooms(carrier: "wbstream", keyDigest: digest, startHasRoomsGroup: false, now: now) == nil, "a sibling carrier, same key")
        check(saved.rooms(carrier: "wbstream", keyDigest: digest, startHasRoomsGroup: true, now: now) == nil, "a sibling carrier, with a ##rooms group too")
        check(saved.rooms(carrier: "telemost", keyDigest: otherDigest, startHasRoomsGroup: false, now: now) == nil, "another key")
        check(saved.rooms(carrier: "telemost", keyDigest: digest, startHasRoomsGroup: false, now: now - 60 + RoomMemoryRecord.maxAge) == nil, "expired")

        // What the save writes is what the load reads.
        let encoded = try? JSONEncoder().encode(saved)
        check(encoded.flatMap { try? JSONDecoder().decode(RoomMemoryRecord.self, from: $0) } == saved, "a record survives the file")

        // A record from the key-only build: the same fields, no carrier. It
        // still decodes, and is the start's own only where the app's list has
        // a ##rooms group - a rotating server, one carrier per key. The
        // platform writes no ## headers and three carriers on one key.
        let legacy = #"{"key":"\#(digest)","primary":"T1","extras":["wbroom1"],"at":\#(now - 60)}"#
        let old = try? JSONDecoder().decode(RoomMemoryRecord.self, from: Data(legacy.utf8))
        check(old != nil && old?.carrier == nil, "a record without a carrier decodes")
        check(old?.rooms(carrier: "telemost", keyDigest: digest, startHasRoomsGroup: true, now: now)
            == RoomList.Parsed(primary: "T1", extras: ["wbroom1"]), "old record, start with a ##rooms group: joined")
        check(old?.rooms(carrier: "telemost", keyDigest: digest, startHasRoomsGroup: false, now: now) == nil,
              "old record, start without one: left alone")
        check(old?.rooms(carrier: "telemost", keyDigest: otherDigest, startHasRoomsGroup: true, now: now) == nil, "old record, another key")
    }
}
