import Foundation

/// Compiled with the production RoomList by scripts/test-ios-room-list.sh.
/// No Cores framework, signing identity or device is needed; a swift.org toolchain
/// on Linux runs it as well as Xcode does.
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
        let parsed = RoomList.parse(body, keyHex: key)
        check(parsed?.primary == "11115586048655", "primary is the matching line's room")
        check(parsed?.extras == ["81055221156696", "52664279650262"], "extras from the header, primary dropped: \(String(describing: parsed?.extras))")
        check(parsed?.all == ["11115586048655", "81055221156696", "52664279650262"], "all: primary first")

        // Another location's header is not ours.
        check(RoomList.parse(body, keyHex: otherKey)?.extras == ["12345"], "the other line has its own header")
        check(RoomList.parse(body, keyHex: String(repeating: "c", count: 64)) == nil, "a key nobody carries")

        // Several lines with one key are one group: the first is the primary.
        let lines = "olcrtc://telemost?vp8channel@R1#\(key)\nolcrtc://telemost?vp8channel@R2#\(key)%client$Name\n##rooms: R3"
        check(RoomList.parse(lines, keyHex: key) == RoomList.Parsed(primary: "R1", extras: ["R2", "R3"]), "same-key lines join the group")

        // The key is compared without case, and stops at % or $.
        check(RoomList.roomAndKey(of: "olcrtc://jitsi?datachannel@https://meet.example/room#ABC%dev$n")?.key == "ABC", "key ends at %")
        check(RoomList.roomAndKey(of: "olcrtc://jitsi?datachannel@https://meet.example/room#ABC%dev$n")?.room == "https://meet.example/room", "a room may be a URL")
        check(RoomList.roomAndKey(of: "olcrtc://nothing-here") == nil, "not a location line")
        check(RoomList.parse(body, keyHex: key.uppercased())?.primary == "11115586048655", "key case does not matter")

        // A base64 body is unwrapped; plain text is left alone.
        let encoded = Data(body.utf8).base64EncodedString()
        check(RoomList.parse(encoded, keyHex: key)?.extras == ["81055221156696", "52664279650262"], "base64 body")
        let unpadded = String(encoded.reversed().drop { $0 == "=" }.reversed())
        check(RoomList.parse(unpadded, keyHex: key)?.primary == "11115586048655", "unpadded base64 body")
        check(RoomList.decodeBody("not a subscription at all") == "not a subscription at all", "prose passes through")

        check(RoomList.splitRooms(" a,b  c\t,d ") == ["a", "b", "c", "d"], "split on comma and whitespace")
        print("ok")
    }
}
