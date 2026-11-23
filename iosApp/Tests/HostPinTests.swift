import Foundation

/// Compiled with the production HostPin and DirectResolver by
/// scripts/p6-tests/ios-host-pin.sh (xcrun on a Mac, any swift.org toolchain
/// elsewhere), and given the port of the stub resolver that script runs on
/// loopback: the exchange with a resolver is tested over a real socket, the
/// rest on stubs.
@main
enum HostPinTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    /// The sentence the app writes for a line whose server's name does not
    /// resolve (`IosCore.HOST_UNRESOLVED`, named as `IosCore.refused` names a line).
    static let unresolved = "Cannot start DE line: the server's name did not resolve on the network underneath the tunnel"

    /// core.json as the app hands over a WebTunnel line whose server is
    /// edge.example.com (`IosCore.handOver`; IosStartHostPinTest holds the
    /// Kotlin to this head): the server at the placeholder, the name kept as
    /// the TLS server name and as the WebSocket Host.
    static let written =
        #"{"meta":{"hostPin":{"server":"edge.example.com","unresolved":"\#(unresolved)"}},"# +
        #""log":{"loglevel":"warning","access":"none"},"# +
        #""inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":10810,"# +
        #""settings":{"auth":"password","accounts":[{"user":"u","pass":"p"}],"udp":true}}],"# +
        #""outbounds":[{"tag":"out","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.54","port":443,"# +
        #""users":[{"id":"33333333-3333-3333-3333-333333333333","encryption":"none"}]}]},"# +
        #""streamSettings":{"network":"ws","wsSettings":{"path":"/v","host":"edge.example.com"},"# +
        #""security":"tls","tlsSettings":{"serverName":"edge.example.com","fingerprint":"chrome"}}}]}"#

    static func main() {
        check(written.contains("\"\(HostPin.placeholder)\""), "the sample is written for the placeholder")
        theRequestIsReadFromTheHead()
        theAddressReplacesThePlaceholderAndTheNameStays()
        ipv4FirstAndIpv6WhenTheNetworkHasOnlyIpv6()
        aNameThatDoesNotResolveIsRefusedWithTheAppsSentence()
        aConfigWithoutAPinIsLeftAlone()
        theDirectResolverAndThePinEachFillTheirOwn()
        queriesAreWrittenAsAResolverReadsThem()
        answersAreReadAndNothingElse()
        guard let port = CommandLine.arguments.dropFirst().first.flatMap({ UInt16($0) }) else {
            print("FAIL: give the stub resolver's port")
            exit(1)
        }
        theStubResolverAnswersOverASocket(port: port)
        print("ok")
    }

    static func theRequestIsReadFromTheHead() {
        check(HostPin.request(in: written) == HostPin.Request(server: "edge.example.com", unresolved: unresolved),
              "the server and the sentence are read from the head")
        // What a scanner could take for the object's end, inside a string.
        let tricky = #"{"meta":{"hostPin":{"server":"edge.example.com","unresolved":"Cannot start My \"best\" {line} \\ }: x"}},"log":{}}"#
        check(HostPin.request(in: tricky)?.unresolved == #"Cannot start My "best" {line} \ }: x"#,
              "quotes, braces and backslashes inside a string do not end the object")
        check(HostPin.request(in: #"{"log":{},"meta":{"hostPin":{"server":"a.example","unresolved":"x"}}}"#) == nil,
              "read only at the head, where the app writes it")
        check(HostPin.request(in: #"{"meta":{"remarks":"a subscription's"},"log":{}}"#) == nil, "a meta of someone else's names no server")
        check(HostPin.request(in: #"{"meta":null,"log":{}}"#) == nil, "a null meta names no server")
        check(HostPin.request(in: #"{"meta":{"hostPin":{"server":"","unresolved":"x"}},"log":{}}"#) == nil, "an empty name is none")
        check(HostPin.request(in: #"{"meta":{"hostPin":{"server":"a.example","unresolved":"x"}"#) == nil, "an object that never ends")
    }

    static func theAddressReplacesThePlaceholderAndTheNameStays() {
        var asked: [String] = []
        let outcome = HostPin.apply(
            to: written,
            lookup: { asked.append($0); return ["203.0.113.7", "2001:db8::7"] },
            routes: { _ in true }
        )
        check(asked == ["edge.example.com"], "the name the app wrote is the one asked: \(asked)")
        guard case .pinned(let config, let ipv6) = outcome else {
            check(false, "pinned: \(outcome)")
            return
        }
        check(!ipv6, "IPv4 first")
        check(config.contains(#""address":"203.0.113.7""#), "the address is where the server goes")
        check(!config.contains(HostPin.placeholder), "no placeholder is left")
        check(config.contains(#""serverName":"edge.example.com""#), "the name stays the TLS server name")
        check(config.contains(#""host":"edge.example.com""#), "the name stays the WebSocket Host")
        check(config.replacingOccurrences(of: #""203.0.113.7""#, with: "\"\(HostPin.placeholder)\"") == written,
              "nothing but the address moved")
        guard let root = try? JSONSerialization.jsonObject(with: Data(config.utf8)) as? [String: Any],
              let outbound = (root["outbounds"] as? [[String: Any]])?.first,
              let settings = outbound["settings"] as? [String: Any],
              let server = (settings["vnext"] as? [[String: Any]])?.first,
              let stream = outbound["streamSettings"] as? [String: Any],
              let tls = stream["tlsSettings"] as? [String: Any],
              let ws = stream["wsSettings"] as? [String: Any]
        else {
            check(false, "the pinned config is JSON of the same shape")
            return
        }
        check(server["address"] as? String == "203.0.113.7", "the outbound's server is the address")
        check(tls["serverName"] as? String == "edge.example.com", "SNI is the name")
        check(ws["host"] as? String == "edge.example.com", "the Host is the name")
    }

    static func ipv4FirstAndIpv6WhenTheNetworkHasOnlyIpv6() {
        let answers = ["203.0.113.7", "203.0.113.8", "2001:db8::7"]
        check(HostPin.choose(answers, routes: { _ in true }) == "203.0.113.7", "IPv4 first")
        check(HostPin.choose(answers, routes: { $0.contains(":") }) == "2001:db8::7",
              "IPv6 when the network routes only IPv6, though the resolver answered A")
        check(HostPin.choose(answers, routes: { _ in false }) == "203.0.113.7",
              "no route claimed: the first IPv4 one, and the core's dial says what is wrong")
        check(HostPin.choose(["2001:db8::7"], routes: { !$0.contains(":") }) == "2001:db8::7", "only IPv6 answered: that")
        check(HostPin.choose([], routes: { _ in true }) == nil, "nothing answered: nothing")
        var probed: [String] = []
        _ = HostPin.choose(answers, routes: { probed.append($0); return true })
        check(probed == ["203.0.113.7"], "one probe when IPv4 routes: \(probed)")
        guard case .pinned(let config, true) = HostPin.apply(to: written, lookup: { _ in answers }, routes: { $0.contains(":") }) else {
            check(false, "an IPv6 pin on a network with only IPv6")
            return
        }
        check(config.contains(#""address":"2001:db8::7""#), "the IPv6 address is written bare, as the core reads it")
    }

    static func aNameThatDoesNotResolveIsRefusedWithTheAppsSentence() {
        check(HostPin.apply(to: written, lookup: { _ in [] }, routes: { _ in true }) == .refused(unresolved),
              "the start fails with the app's sentence, which names the line")
        // The head the app writes, but no server in it, and the placeholder
        // still the address: refused, never dialled at TEST-NET.
        let nameless = written.replacingOccurrences(of: #""server":"edge.example.com","#, with: "")
        var asked = 0
        check(HostPin.apply(to: nameless, lookup: { _ in asked += 1; return ["203.0.113.7"] }, routes: { _ in true })
              == .refused(HostPin.unreadable), "a pin the app began and did not name")
        check(asked == 0, "nothing is asked for it")
    }

    static func aConfigWithoutAPinIsLeftAlone() {
        let plain = #"{"log":{"loglevel":"warning"},"outbounds":[{"tag":"out","protocol":"trojan","settings":{"servers":[{"address":"192.0.2.7","port":443}]}}]}"#
        var asked = 0
        check(HostPin.apply(to: plain, lookup: { _ in asked += 1; return [] }, routes: { _ in true }) == .unchanged,
              "a server given by its address is started as written")
        // A line whose own server sits at the placeholder's address, which
        // the app did not pin: its own business.
        let own = plain.replacingOccurrences(of: "192.0.2.7", with: HostPin.placeholder)
        check(HostPin.apply(to: own, lookup: { _ in asked += 1; return [] }, routes: { _ in true }) == .unchanged,
              "an address the app wrote as the line's own")
        check(asked == 0, "no name is asked for either")
    }

    static func theDirectResolverAndThePinEachFillTheirOwn() {
        check(HostPin.placeholder != DirectResolver.placeholder, "two placeholders, one for each")
        // Under Bypass Russia both are in one core.json; the provider pins
        // the server first and fills the resolver as the core starts.
        let both = written.replacingOccurrences(
            of: #""log":"#,
            with: #""dns":{"servers":[{"address":"198.51.100.53","port":53}]},"log":"#
        )
        guard case .pinned(let pinned, false) = HostPin.apply(to: both, lookup: { _ in ["203.0.113.7"] }, routes: { _ in true }) else {
            check(false, "pinned beside the resolver's placeholder")
            return
        }
        check(pinned.contains("\"\(DirectResolver.placeholder)\""), "the pin leaves the resolver's placeholder alone")
        let ready = DirectResolver.substitute(in: pinned, resolvers: ["10.0.0.1:53"])
        check(ready.contains(#""address":"10.0.0.1""#) && ready.contains(#""address":"203.0.113.7""#),
              "each placeholder holds what is its own")
    }

    static func queriesAreWrittenAsAResolverReadsThem() {
        var expected: [UInt8] = [0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]
        for label in ["Edge", "example", "com"] {
            expected.append(UInt8(label.utf8.count))
            expected += Array(label.utf8)
        }
        expected += [0, 0, 1, 0, 1]
        check(HostPin.query("Edge.example.com", type: HostPin.typeA, id: 0x1234) == expected,
              "id, recursion asked, one question of class IN")
        check(HostPin.query("edge.example.com.", type: HostPin.typeAAAA, id: 7) == HostPin.query("edge.example.com", type: HostPin.typeAAAA, id: 7),
              "a trailing dot is the root's")
        check(HostPin.query("edge.example.com", type: HostPin.typeAAAA, id: 7).map { Array($0.suffix(4)) } == [0, 28, 0, 1], "AAAA")
        let long = Array(repeating: "abcdefghi", count: 26).joined(separator: ".")
        for bad in ["", ".", "a..b", String(repeating: "a", count: 64) + ".example", "ex ample.com", "xn--e1afmkfd.\u{0440}\u{0444}", long] {
            check(HostPin.query(bad, type: HostPin.typeA, id: 1) == nil, "no query for \(bad)")
        }
        check(HostPin.query(String(repeating: "a", count: 63) + ".example", type: HostPin.typeA, id: 1) != nil, "a label of 63 bytes")
        check(HostPin.query("_srv.my-host.example", type: HostPin.typeA, id: 1) != nil, "hyphens and underscores")
    }

    /// A response to `query` (its id and its question) with `records`, each
    /// a name as it is on the wire, a type and the data.
    static func response(to query: [UInt8], rcode: UInt8 = 0, records: [([UInt8], UInt16, [UInt8])]) -> [UInt8] {
        var out = Array(query[0..<2])
        out += [0x81, 0x80 | rcode, 0, 1, 0, UInt8(records.count), 0, 0, 0, 0]
        out += query[12...]
        for (name, type, data) in records {
            out += name
            out += [UInt8(type >> 8), UInt8(type & 0xff), 0, 1, 0, 0, 0x0e, 0x10, UInt8(data.count >> 8), UInt8(data.count & 0xff)]
            out += data
        }
        return out
    }

    static func encoded(_ name: String) -> [UInt8] {
        var out: [UInt8] = []
        for label in name.split(separator: ".") {
            out.append(UInt8(label.utf8.count))
            out += Array(label.utf8)
        }
        return out + [0]
    }

    static func answersAreReadAndNothingElse() {
        guard let query = HostPin.query("edge.example.com", type: HostPin.typeA, id: 0x4242),
              let query6 = HostPin.query("edge.example.com", type: HostPin.typeAAAA, id: 0x4243)
        else {
            check(false, "queries")
            return
        }
        // A CNAME, then the address of its target, whose name is a pointer
        // to the CNAME's data; an AAAA and an A of the wrong length beside.
        let cnameAt = query.count + 12
        let target: [UInt8] = [0xc0 | UInt8(cnameAt >> 8), UInt8(cnameAt & 0xff)]
        let v6: [UInt8] = [0x20, 0x01, 0x0d, 0xb8] + Array(repeating: 0, count: 11) + [7]
        let answer = response(to: query, records: [
            ([0xc0, 0x0c], 5, encoded("cdn.example.net")),
            (target, HostPin.typeA, [203, 0, 113, 7]),
            (target, HostPin.typeAAAA, v6),
            ([0xc0, 0x0c], HostPin.typeA, [1, 2, 3]),
        ])
        check(HostPin.addresses(in: answer, id: 0x4242, type: HostPin.typeA) == ["203.0.113.7"],
              "past a CNAME, the A record and nothing else: \(String(describing: HostPin.addresses(in: answer, id: 0x4242, type: HostPin.typeA)))")
        check(HostPin.addresses(in: answer, id: 0x4243, type: HostPin.typeA) == nil, "another query's id")
        check(HostPin.addresses(in: answer, id: 0x4242, type: HostPin.typeAAAA) == nil, "the answer to A is not the one to AAAA")
        var asked = answer
        asked[2] &= 0x7f
        check(HostPin.addresses(in: asked, id: 0x4242, type: HostPin.typeA) == nil, "a query is no answer")
        check(HostPin.addresses(in: response(to: query, rcode: 3, records: []), id: 0x4242, type: HostPin.typeA) == [],
              "no such name: answered, with nothing")
        check(HostPin.addresses(in: response(to: query, rcode: 2, records: []), id: 0x4242, type: HostPin.typeA) == nil,
              "a server failure is no answer")
        check(HostPin.addresses(in: response(to: query6, records: [([0xc0, 0x0c], HostPin.typeAAAA, v6)]), id: 0x4243, type: HostPin.typeAAAA)
              == ["2001:db8::7"], "AAAA in its short form")
        // Cut anywhere, the answer is read as far as it goes, and never past.
        for length in 0..<answer.count {
            let cut = HostPin.addresses(in: Array(answer[0..<length]), id: 0x4242, type: HostPin.typeA)
            check(cut == nil || cut == [] || cut == ["203.0.113.7"], "cut at \(length): \(String(describing: cut))")
        }
        check(HostPin.addresses(in: Array(answer[0..<query.count]), id: 0x4242, type: HostPin.typeA) == [],
              "the question alone, its answers cut off")
    }

    static func theStubResolverAnswersOverASocket(port: UInt16) {
        let pin: (Int32) -> Bool = { _ in true }
        var pins = 0
        let edge = HostPin.lookup("edge.example.com", server: "127.0.0.1", port: port, pin: { _ in pins += 1; return true })
        check(edge == ["203.0.113.7", "2001:db8::7"], "IPv4 first, past a CNAME and a reply to no question of ours: \(edge)")
        check(pins == 1, "one socket, pinned before it is connected: \(pins)")
        check(HostPin.lookup("v6only.example.com", server: "127.0.0.1", port: port, pin: pin) == ["2001:db8::6"], "a name with only AAAA")
        let asked = Date()
        check(HostPin.lookup("missing.example.com", server: "127.0.0.1", port: port, pin: pin) == [], "no such name")
        check(Date().timeIntervalSince(asked) < 1, "no such name is an answer, not a wait")
        let late = HostPin.lookup("late.example.com", server: "127.0.0.1", port: port, timeout: 300, attempts: 2, pin: pin)
        check(late == ["203.0.113.9"], "a question the resolver dropped is asked again: \(late)")
        let quiet = Date()
        check(HostPin.lookup("silent.example.com", server: "127.0.0.1", port: port, timeout: 200, attempts: 2, pin: pin) == [], "silence")
        let waited = Date().timeIntervalSince(quiet)
        check(waited >= 0.35 && waited < 3, "two tries of 200 ms: \(waited)")
        check(HostPin.lookup("edge.example.com", server: "a.resolver.example", port: port, pin: pin) == [],
              "a resolver given by name is never resolved here")
        check(HostPin.lookup("bad name", server: "127.0.0.1", port: port, pin: pin) == [], "a name that cannot be asked")
        check(HostPin.routes("127.0.0.1", pin: pin), "loopback routes")
        check(!HostPin.routes("edge.example.com", pin: pin), "no route to what is not an address")
        // The whole step as the provider takes it, over the socket.
        let outcome = HostPin.apply(
            to: written,
            lookup: { HostPin.lookup($0, server: "127.0.0.1", port: port, pin: pin) },
            routes: { HostPin.routes($0, pin: pin) }
        )
        guard case .pinned(let config, _) = outcome else {
            check(false, "pinned over the socket: \(outcome)")
            return
        }
        check(config.contains(#""address":"203.0.113.7""#) || config.contains(#""address":"2001:db8::7""#),
              "the stub's address is the server")
        check(config.contains(#""serverName":"edge.example.com""#) && config.contains(#""host":"edge.example.com""#),
              "the name is still the server name and the Host")
        let refused = HostPin.apply(
            to: written.replacingOccurrences(of: "edge.example.com", with: "missing.example.com"),
            lookup: { HostPin.lookup($0, server: "127.0.0.1", port: port, pin: pin) },
            routes: { HostPin.routes($0, pin: pin) }
        )
        check(refused == .refused(unresolved), "a name the resolver does not know fails the start: \(refused)")
    }
}
