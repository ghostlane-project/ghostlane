import CryptoKit
import Darwin
import Foundation

/// A blocking TCP connection to a local port, for the SOCKS5 talk hev has
/// with an engine (RFC 1928, with the RFC 1929 login).
final class HostConnection {
    private let fd: Int32

    init(host: String = "127.0.0.1", port: Int, timeout: Double) throws {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw HostFailure("socket: \(String(cString: strerror(errno)))") }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var limit = timeval(tv_sec: Int(timeout), tv_usec: Int32((timeout - Double(Int(timeout))) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        var address = HostConnection.address(host, port)
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        // deinit closes the descriptor, on this throw too: the object is
        // whole by now. Closing it here as well could close one another
        // thread has opened under the same number since.
        guard connected == 0 else {
            throw HostFailure("connect to \(host):\(port): \(String(cString: strerror(errno)))")
        }
    }

    deinit {
        if fd >= 0 { Darwin.close(fd) }
    }

    /// Whether anything listens on the loopback port.
    static func accepts(_ port: Int) -> Bool {
        (try? HostConnection(port: port, timeout: 1)) != nil
    }

    static func address(_ host: String, _ port: Int) -> sockaddr_in {
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(UInt16(port).bigEndian)
        address.sin_addr = in_addr(s_addr: inet_addr(host))
        return address
    }

    func send(_ bytes: [UInt8]) throws {
        try bytes.withUnsafeBytes { try send($0) }
    }

    func send(_ buffer: UnsafeRawBufferPointer) throws {
        var offset = 0
        while offset < buffer.count {
            let wrote = Darwin.write(fd, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
            if wrote < 0 {
                if errno == EINTR { continue }
                throw HostFailure("write: \(String(cString: strerror(errno)))")
            }
            offset += wrote
        }
    }

    /// Up to `max` bytes; empty at the end of the stream. A reset is the end
    /// too: a server that turns a client away may close either way.
    func receive(max: Int) throws -> [UInt8] {
        var buffer = [UInt8](repeating: 0, count: max)
        while true {
            let got = buffer.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress!, max) }
            if got >= 0 { return Array(buffer[0..<got]) }
            switch errno {
            case EINTR: continue
            case ECONNRESET: return []
            case EAGAIN: throw HostFailure("no answer within the timeout")
            default: throw HostFailure("read: \(String(cString: strerror(errno)))")
            }
        }
    }

    /// Exactly `count` bytes, or nil if the stream ends first.
    func receive(exactly count: Int) throws -> [UInt8]? {
        var bytes: [UInt8] = []
        while bytes.count < count {
            let more = try receive(max: count - bytes.count)
            if more.isEmpty { return nil }
            bytes += more
        }
        return bytes
    }

    /// The greeting with these methods: the method the server chose, or nil
    /// if it closed instead of answering.
    func greet(_ methods: [UInt8]) throws -> UInt8? {
        var hello: [UInt8] = [0x05, UInt8(methods.count)]
        hello += methods
        try send(hello)
        guard let reply = try receive(exactly: 2) else { return nil }
        guard reply[0] == 0x05 else { throw HostFailure("not a SOCKS5 answer: \(reply)") }
        return reply[1]
    }

    /// The username and password login: the server's status (0 is taken),
    /// or nil if it closed instead of answering.
    func login(_ user: String, _ pass: String) throws -> UInt8? {
        let u = Array(user.utf8)
        let p = Array(pass.utf8)
        var request: [UInt8] = [0x01, UInt8(u.count)]
        request += u
        request.append(UInt8(p.count))
        request += p
        try send(request)
        guard let reply = try receive(exactly: 2) else { return nil }
        return reply[1]
    }

    /// CONNECT to an IPv4 address. Throws unless the server says it is
    /// connected.
    func connect(to ip: String, port: Int) throws {
        var address = in_addr()
        guard inet_pton(AF_INET, ip, &address) == 1 else { throw HostFailure("not an IPv4 address: \(ip)") }
        var request: [UInt8] = [0x05, 0x01, 0x00, 0x01]
        request += withUnsafeBytes(of: address.s_addr) { Array($0) }
        request += withUnsafeBytes(of: UInt16(port).bigEndian) { Array($0) }
        try send(request)
        guard let head = try receive(exactly: 4) else { throw HostFailure("SOCKS CONNECT: closed with no reply") }
        guard head[1] == 0x00 else { throw HostFailure("SOCKS CONNECT refused, reply \(head[1])") }
        let rest: Int
        switch head[3] {
        case 0x01: rest = 4 + 2
        case 0x04: rest = 16 + 2
        case 0x03:
            guard let length = try receive(exactly: 1) else { throw HostFailure("SOCKS CONNECT: closed in the reply") }
            rest = Int(length[0]) + 2
        default: throw HostFailure("SOCKS CONNECT reply with address type \(head[3])")
        }
        guard try receive(exactly: rest) != nil else { throw HostFailure("SOCKS CONNECT: closed in the reply") }
    }
}

/// An HTTP exchange's answer, as far as the checks need it.
struct HostResponse {
    var status = 0
    var headers: [String: String] = [:]
    var bodyBytes = 0
    var bodySha256 = ""
    /// The first bytes of the body.
    var bodyPrefix: [UInt8] = []
    /// For an upload: the sha256 of what was sent.
    var sentSha256: String?
}

/// HTTP/1.1 through an engine's SOCKS port, as an app's request reaches it
/// through hev: the login, a CONNECT to the page's address, the request.
enum HostTunnel {

    /// The page's address as the client asks for it; the local servers send
    /// it to the page on loopback (their freedom outbound's redirect).
    static let pageAddress = "203.0.113.10"

    static func open(_ socks: LoopbackSocks, timeout: Double) throws -> HostConnection {
        let connection = try HostConnection(port: socks.port, timeout: timeout)
        guard try connection.greet([0x02]) == 0x02 else { throw HostFailure("the SOCKS port did not ask for the login") }
        guard try connection.login(socks.username ?? "", socks.password ?? "") == 0x00 else {
            throw HostFailure("the SOCKS port refused its login")
        }
        try connection.connect(to: pageAddress, port: 80)
        return connection
    }

    static func get(_ socks: LoopbackSocks, path: String, limit: Int, timeout: Double) throws -> HostResponse {
        let connection = try open(socks, timeout: timeout)
        try connection.send(Array("GET \(path) HTTP/1.1\r\nHost: \(pageAddress)\r\nConnection: close\r\n\r\n".utf8))
        return try read(connection, limit: limit)
    }

    /// `bytes` of noise up, in 16 KiB writes; the page answers the sha256 it
    /// heard.
    static func post(_ socks: LoopbackSocks, path: String, bytes: Int, timeout: Double) throws -> HostResponse {
        let connection = try open(socks, timeout: timeout)
        try connection.send(Array(("POST \(path) HTTP/1.1\r\nHost: \(pageAddress)\r\n"
            + "Content-Type: application/octet-stream\r\nContent-Length: \(bytes)\r\nConnection: close\r\n\r\n").utf8))
        var hasher = SHA256()
        var generator = SystemRandomNumberGenerator()
        var chunk = [UInt8](repeating: 0, count: 16 * 1024)
        var left = bytes
        while left > 0 {
            let n = min(left, chunk.count)
            for i in stride(from: 0, to: n, by: 8) {
                withUnsafeBytes(of: generator.next() as UInt64) { word in
                    for j in 0..<min(8, n - i) { chunk[i + j] = word[j] }
                }
            }
            try chunk.withUnsafeBytes { buffer in
                let part = UnsafeRawBufferPointer(rebasing: buffer[0..<n])
                hasher.update(bufferPointer: part)
                try connection.send(part)
            }
            left -= n
        }
        var response = try read(connection, limit: 4096)
        response.sentSha256 = hex(hasher.finalize())
        return response
    }

    /// Reads to the end of the stream or of the body, hashing the body as it
    /// comes and keeping none of it but its first bytes.
    static func read(_ connection: HostConnection, limit: Int) throws -> HostResponse {
        var head: [UInt8] = []
        var body: [UInt8] = []
        while true {
            let more = try connection.receive(max: 16 * 1024)
            if more.isEmpty {
                throw HostFailure(head.isEmpty ? "the connection closed with nothing sent back" : "the connection closed inside the headers")
            }
            head += more
            if let end = find(head, [13, 10, 13, 10]) {
                body = Array(head[(end + 4)...])
                head = Array(head[..<end])
                break
            }
            if head.count > 64 * 1024 { throw HostFailure("no end of headers in 64 KiB") }
        }
        var response = HostResponse()
        let lines = String(decoding: head, as: UTF8.self).components(separatedBy: "\r\n")
        let status = lines.first?.split(separator: " ") ?? []
        guard status.count >= 2, status[0].hasPrefix("HTTP/1."), let code = Int(status[1]) else {
            throw HostFailure("not an HTTP status line: \(lines.first ?? "")")
        }
        response.status = code
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].lowercased()
            response.headers[name] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        let expected = response.headers["content-length"].flatMap { Int($0) }
        var hasher = SHA256()
        func take(_ bytes: [UInt8]) {
            hasher.update(data: bytes)
            response.bodyBytes += bytes.count
            if response.bodyPrefix.count < 256 { response.bodyPrefix += bytes.prefix(256 - response.bodyPrefix.count) }
        }
        take(body)
        while response.bodyBytes < (expected ?? Int.max) && response.bodyBytes <= limit {
            let more = try connection.receive(max: 64 * 1024)
            if more.isEmpty { break }
            take(more)
        }
        response.bodySha256 = hex(hasher.finalize())
        return response
    }

    static func find(_ haystack: [UInt8], _ needle: [UInt8]) -> Int? {
        guard haystack.count >= needle.count else { return nil }
        for i in 0...(haystack.count - needle.count) where haystack[i..<(i + needle.count)].elementsEqual(needle) {
            return i
        }
        return nil
    }

    static func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}
