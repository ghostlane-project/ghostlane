import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// A line whose server is named by a hostname, given its address here, in the
/// extension, outside the tunnel.
///
/// The core resolves an outbound's server through the system resolver, which
/// inside the packet tunnel is the tunnel itself. The app cannot resolve the
/// name for it either: while a tunnel is up the app's own questions go
/// through that tunnel and come back as the core's fake addresses, and under
/// the kill switch iOS restarts a tunnel from the same files with no app at
/// all. So the app writes core.json for the address `placeholder`, with the
/// name kept wherever it was also the wire's, as the TLS server name and as
/// the ws or httpupgrade Host (`CoreLines.pinHost` in the shared Kotlin,
/// called by `IosCore.start`), and names the server at the head of the file
/// (`IosCore.handOver`):
///
///     {"meta":{"hostPin":{"server":"<name>","unresolved":"Cannot start <line>: ..."}},"log":...
///
/// `meta` is the root's object the core type-checks and ignores. Here, before
/// the tunnel's routes take the traffic, the name is asked of the network's
/// own resolver (`DirectResolver.pick`) by datagram, from a socket pinned to
/// the physical interface as the core's are, and its address is written over
/// the placeholder: an IPv4 one when the network routes IPv4, an IPv6 one when
/// it routes only IPv6. A name that does not resolve fails the start with the
/// sentence the app wrote, which names the line.
///
/// The address is this start's: a network change later keeps it, as on
/// Android and the desktop, and the next start asks again.
///
/// Foundation and the C library only, so a swift.org toolchain on Linux
/// compiles it with its test, and the exchange with a resolver runs there
/// against a stub (scripts/p6-tests/ios-host-pin.sh).
enum HostPin {

    /// Where the app writes the server's address: TEST-NET-2, one past
    /// `DirectResolver.placeholder`, so a config that was not finished here
    /// dials nowhere. The same text as `IosCore.HOST_PIN_PLACEHOLDER`, which
    /// the script of the test holds them to.
    static let placeholder = "198.51.100.54"

    /// What the app asked for: the server's name, and the sentence the start
    /// fails with when the name does not resolve.
    struct Request: Equatable {
        let server: String
        let unresolved: String
    }

    /// What `apply` made of a config.
    enum Outcome: Equatable {
        /// No server named by a hostname: the config is started as written.
        case unchanged
        /// The config with the server's address in place of the placeholder,
        /// and whether that address is IPv6 (the trace says the family, never
        /// the address).
        case pinned(config: String, ipv6: Bool)
        /// The start fails with this sentence.
        case refused(String)
    }

    /// Why a start is refused whose config the app began for a hostname but
    /// that names no server: never dialled at the placeholder.
    static let unreadable =
        "core.json asks for the server's name to be resolved but does not name it: connect again from the app"

    /// The head of a config the app pinned, and what of it is read.
    private static let metaHead = #"{"meta":"#
    private static let maxMeta = 16 * 1024

    private static var quotedPlaceholder: String { "\"\(placeholder)\"" }

    /// The whole step for one start: the config with its server's address,
    /// the config as it was when it names no server, or the refusal.
    /// `lookup` gives the name's addresses, IPv4 ones first; `routes` says
    /// whether the network has a route to an address.
    static func apply(
        to config: String,
        lookup: (String) -> [String],
        routes: (String) -> Bool
    ) -> Outcome {
        guard config.hasPrefix(metaHead) else { return .unchanged }
        guard let request = request(in: config) else {
            return config.contains(quotedPlaceholder) ? .refused(unreadable) : .unchanged
        }
        guard let address = choose(lookup(request.server), routes: routes) else {
            return .refused(request.unresolved)
        }
        return .pinned(config: pin(config, address: address), ipv6: address.contains(":"))
    }

    /// The server the app named at the head of `config`, or nil when it named
    /// none. Only the head is read, to the end of the `meta` object, so a
    /// config that carries a region's lists inline is not parsed whole for it.
    static func request(in config: String) -> Request? {
        let bytes = config.utf8
        guard bytes.starts(with: metaHead.utf8) else { return nil }
        let start = bytes.index(bytes.startIndex, offsetBy: metaHead.utf8.count)
        guard start < bytes.endIndex, bytes[start] == UInt8(ascii: "{") else { return nil }
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        var seen = 0
        while index < bytes.endIndex, seen < maxMeta {
            let byte = bytes[index]
            if inString {
                if escaped {
                    escaped = false
                } else if byte == UInt8(ascii: "\\") {
                    escaped = true
                } else if byte == UInt8(ascii: "\"") {
                    inString = false
                }
            } else if byte == UInt8(ascii: "\"") {
                inString = true
            } else if byte == UInt8(ascii: "{") {
                depth += 1
            } else if byte == UInt8(ascii: "}") {
                depth -= 1
                if depth == 0 {
                    return decode(Data(bytes[start...index]))
                }
            }
            index = bytes.index(after: index)
            seen += 1
        }
        return nil
    }

    private static func decode(_ meta: Data) -> Request? {
        guard let object = try? JSONSerialization.jsonObject(with: meta) as? [String: Any],
              let pin = object["hostPin"] as? [String: Any],
              let server = pin["server"] as? String, !server.isEmpty,
              let unresolved = pin["unresolved"] as? String, !unresolved.isEmpty
        else { return nil }
        return Request(server: server, unresolved: unresolved)
    }

    /// `config` with `address` wherever the app wrote the placeholder, quoted,
    /// as `DirectResolver.substitute` replaces its own: the placeholder is
    /// written only as the server's address, and nowhere else moves.
    static func pin(_ config: String, address: String) -> String {
        config.replacingOccurrences(of: quotedPlaceholder, with: "\"\(address)\"")
    }

    /// The address to connect to among `answers`: the first IPv4 one when the
    /// network routes it, else the first IPv6 one when it routes that (a
    /// network with only IPv6, where the resolver also answers A), else the
    /// first answer, IPv4 before IPv6, so that the core's dial says what is
    /// wrong. Nil only when nothing answered.
    static func choose(_ answers: [String], routes: (String) -> Bool) -> String? {
        let v4 = answers.first { !$0.contains(":") }
        let v6 = answers.first { $0.contains(":") }
        if let v4, routes(v4) { return v4 }
        if let v6, routes(v6) { return v6 }
        return v4 ?? v6
    }

    // MARK: - the question and the answer

    static let typeA: UInt16 = 1
    static let typeAAAA: UInt16 = 28

    /// A DNS query for `name` of `type` with `id`, recursion asked; nil for a
    /// name no resolver can be asked (an empty or over-long label, a name over
    /// 253 bytes, a byte outside letters, digits, `-` and `_`). A trailing dot
    /// is the root's.
    static func query(_ name: String, type: UInt16, id: UInt16) -> [UInt8]? {
        var trimmed = Substring(name)
        if trimmed.hasSuffix(".") { trimmed = trimmed.dropLast() }
        guard !trimmed.isEmpty, trimmed.utf8.count <= 253 else { return nil }
        var packet: [UInt8] = [UInt8(id >> 8), UInt8(id & 0xff), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]
        for label in trimmed.split(separator: ".", omittingEmptySubsequences: false) {
            let bytes = Array(label.utf8)
            guard (1...63).contains(bytes.count), bytes.allSatisfy(isNameByte) else { return nil }
            packet.append(UInt8(bytes.count))
            packet += bytes
        }
        packet += [0, UInt8(type >> 8), UInt8(type & 0xff), 0, 1]
        return packet
    }

    private static func isNameByte(_ byte: UInt8) -> Bool {
        switch byte {
        case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"),
             UInt8(ascii: "0")...UInt8(ascii: "9"), UInt8(ascii: "-"), UInt8(ascii: "_"):
            return true
        default:
            return false
        }
    }

    /// The addresses of `type` that `response` gives in answer to the query
    /// `id` of that type; empty when it answers with none (no such name, or
    /// none of that type), nil when it is no answer to that query: another
    /// id or question, not a response, a server failure, or cut short before
    /// its question ends. Read to the first record that does not fit.
    static func addresses(in response: [UInt8], id: UInt16, type: UInt16) -> [String]? {
        guard response.count >= 12, word(response, 0) == id else { return nil }
        let flags = word(response, 2)
        guard flags & 0x8000 != 0 else { return nil }
        let rcode = flags & 0x000f
        guard rcode != 2 else { return nil }
        let questions = Int(word(response, 4))
        let answers = Int(word(response, 6))
        var at = 12
        for _ in 0..<questions {
            guard let end = skipName(response, from: at), end + 4 <= response.count,
                  word(response, end) == type
            else { return nil }
            at = end + 4
        }
        guard rcode == 0 else { return [] }
        var found: [String] = []
        for _ in 0..<answers {
            guard let end = skipName(response, from: at), end + 10 <= response.count else { break }
            let kind = word(response, end)
            let dataClass = word(response, end + 2)
            let length = Int(word(response, end + 8))
            let data = end + 10
            guard data + length <= response.count else { break }
            if kind == type, dataClass == 1, let text = address(Array(response[data..<(data + length)])) {
                found.append(text)
            }
            at = data + length
        }
        return found
    }

    private static func word(_ bytes: [UInt8], _ at: Int) -> UInt16 {
        UInt16(bytes[at]) << 8 | UInt16(bytes[at + 1])
    }

    /// The index past the name at `from`: its labels and a zero, or a
    /// pointer, which ends a name and is never followed (nothing here reads a
    /// record's name). Nil when the name runs past the end.
    private static func skipName(_ bytes: [UInt8], from: Int) -> Int? {
        var at = from
        while at < bytes.count {
            let length = bytes[at]
            if length == 0 { return at + 1 }
            if length & 0xc0 == 0xc0 { return at + 2 <= bytes.count ? at + 2 : nil }
            if length & 0xc0 != 0 { return nil }
            at += 1 + Int(length)
        }
        return nil
    }

    /// Four bytes as an IPv4 address, sixteen as an IPv6 one in its short
    /// form; nil for any other length.
    private static func address(_ data: [UInt8]) -> String? {
        switch data.count {
        case 4:
            return data.map { String($0) }.joined(separator: ".")
        case 16:
            var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            let written = data.withUnsafeBytes { raw in
                inet_ntop(AF_INET6, raw.baseAddress, &text, socklen_t(text.count))
            }
            guard written != nil else { return nil }
            return String(cString: text)
        default:
            return nil
        }
    }

    // MARK: - the exchange

    #if canImport(Glibc)
    private static let datagram = Int32(SOCK_DGRAM.rawValue)
    #else
    private static let datagram = SOCK_DGRAM
    #endif

    /// The addresses of `name`, IPv4 ones first, from the resolver at
    /// `server` (an address) on `port`: A and AAAA asked at once from one
    /// datagram socket, which `pin` binds before it is connected, and a
    /// question still unanswered asked again, `attempts` times in all, each
    /// waiting `timeout` milliseconds. Empty when nothing answered.
    static func lookup(
        _ name: String,
        server: String,
        port: UInt16 = 53,
        timeout: Int32 = 1500,
        attempts: Int = 2,
        pin: (Int32) -> Bool
    ) -> [String] {
        let types = [typeA, typeAAAA]
        var questions: [UInt16: (id: UInt16, packet: [UInt8])] = [:]
        for type in types {
            let id = UInt16.random(in: UInt16.min...UInt16.max)
            guard let packet = query(name, type: type, id: id) else { return [] }
            questions[type] = (id, packet)
        }
        let answered: [UInt16: [String]]? = connected(to: server, port: port, pin: pin) { fd in
            var got: [UInt16: [String]] = [:]
            var buffer = [UInt8](repeating: 0, count: 4096)
            for _ in 0..<max(attempts, 1) where got.count < types.count {
                for type in types where got[type] == nil {
                    guard let question = questions[type] else { continue }
                    _ = question.packet.withUnsafeBytes { send(fd, $0.baseAddress, $0.count, 0) }
                }
                let deadline = Date().addingTimeInterval(Double(timeout) / 1000)
                while got.count < types.count {
                    let left = Int32(max(0, deadline.timeIntervalSinceNow * 1000))
                    guard left > 0 else { break }
                    var ready = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
                    let polled = poll(&ready, 1, left)
                    if polled < 0, errno == EINTR { continue }
                    guard polled > 0 else { break }
                    let count = buffer.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
                    if count < 0 {
                        if errno == EINTR { continue }
                        // A refusal the network sent back (an ICMP
                        // unreachable on the connected socket) ends this
                        // try; the next one asks again.
                        break
                    }
                    guard count > 0 else { continue }
                    let response = Array(buffer[0..<count])
                    for type in types where got[type] == nil {
                        guard let question = questions[type],
                              let found = addresses(in: response, id: question.id, type: type)
                        else { continue }
                        got[type] = found
                    }
                }
            }
            return got
        }
        return types.flatMap { answered?[$0] ?? [] }
    }

    /// Whether the network has a route to `address`: a datagram socket,
    /// pinned as the core's are, connected to it, which sends nothing.
    static func routes(_ address: String, pin: (Int32) -> Bool) -> Bool {
        connected(to: address, port: 443, pin: pin) { _ in true } ?? false
    }

    /// `body` with a datagram socket connected to `host` (an address, never a
    /// name: nothing is resolved here) on `port`, `pin` asked first; nil when
    /// `host` is no address or the socket cannot be connected.
    private static func connected<T>(
        to host: String,
        port: UInt16,
        pin: (Int32) -> Bool,
        _ body: (Int32) -> T
    ) -> T? {
        var hints = addrinfo()
        hints.ai_flags = AI_NUMERICHOST
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = datagram
        var found: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &found) == 0, let info = found else { return nil }
        defer { freeaddrinfo(info) }
        let fd = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        // A pin that cannot be made leaves the socket to the routes, which
        // before the tunnel's settings are the network's own.
        _ = pin(fd)
        guard connect(fd, info.pointee.ai_addr, info.pointee.ai_addrlen) == 0 else { return nil }
        return body(fd)
    }
}
