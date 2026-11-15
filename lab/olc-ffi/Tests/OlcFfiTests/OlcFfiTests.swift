import Darwin
import Foundation
import HevSocks5Tunnel
import OlcFfi
import XCTest

// The iOS simulator XCTest of the olcRTC port, plan §10.7: OlcrtcRuntime
// (the Rust client through UniFFI, in one library with the xray core and one
// Tokio runtime) driven as OlcrtcEngine.launch drives the Go framework today,
// against the T1 hub and the pinned Go server on the host Mac
// (zz_t1_host_test.go). It checks the API, the callbacks, start, wait_ready
// and stop, a fetch through the SOCKS port with the login, and UDP through
// hev in front: FWD_UDP (hev's `udp: 'tcp'`) and UDP ASSOCIATE (`'udp'`, the
// extension's setting today). RESULT and MEM lines go to the job summary.

/// What zz_t1_host_test.go writes.
struct T1Host: Decodable {
    let room: String
    let key: String
    let transport: String
    let dns: String
    let origin: String
    let echoPort: Int
    let echoIP: String
    let serverLog: String

    enum CodingKeys: String, CodingKey {
        case room, key, transport, dns, origin
        case echoPort = "echo_port"
        case echoIP = "echo_ip"
        case serverLog = "server_log"
    }

    static func load() throws -> T1Host {
        let env = ProcessInfo.processInfo.environment
        let path = env["T1_CONFIG"] ?? "/tmp/olc-t1.json"
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        return try JSONDecoder().decode(T1Host.self, from: data)
    }
}

struct Failure: Error, CustomStringConvertible {
    let description: String
    init(_ d: String) { description = d }
}

func say(_ line: String) {
    print(line)
    fflush(stdout)
}

// MARK: - The callbacks

/// The engine log, as olcrtc.log receives it.
final class Lines: LogWriter, @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [String] = []

    func writeLog(_ msg: String) {
        lock.lock()
        lines.append(msg)
        lock.unlock()
        FileHandle.standardError.write(Data(msg.utf8))
    }

    func all() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return lines
    }

    func count(_ s: String) -> Int { all().filter { $0.contains(s) }.count }
}

/// RoomKeeper's part: each session's room.
final class Keeper: SessionListener, @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [(String, String)] = []

    func onSessionOpened(_ room: String, _ sessionId: String) {
        lock.lock()
        sessions.append((room, sessionId))
        lock.unlock()
    }

    func all() -> [(String, String)] {
        lock.lock()
        defer { lock.unlock() }
        return sessions
    }
}

/// LibboxPlatform.pinToPhysicalInterface's place: counted.
final class Pin: SocketProtector, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var calls = 0

    func protect(_ fd: Int64) -> Bool {
        lock.lock()
        calls += 1
        lock.unlock()
        return fd >= 0
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return calls
    }
}

// MARK: - Sockets

func freePort() throws -> Int {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { throw Failure("socket: \(errno)") }
    defer { close(fd) }
    var addr = loopback(port: 0)
    let bound = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard bound == 0 else { throw Failure("bind: \(errno)") }
    var len = socklen_t(MemoryLayout<sockaddr_in>.size)
    _ = withUnsafeMutablePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
    }
    return Int(UInt16(bigEndian: addr.sin_port))
}

/// Whether something accepts a connection on 127.0.0.1:port.
func accepts(port: Int) -> Bool {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { return false }
    defer { close(fd) }
    var addr = loopback(port: port)
    let rc = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    return rc == 0
}

/// Whether 127.0.0.1:port can be listened on again, as a listener asks:
/// with SO_REUSEADDR (tokio's and Java's set it), since a connection the
/// test made through the port leaves a TIME_WAIT on it, which a plain bind
/// on Darwin refuses (run 43692067127: the next runtime bound the port, this
/// check without the option did not).
func binds(port: Int) -> Bool {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { return false }
    defer { close(fd) }
    var one: Int32 = 1
    guard setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size)) == 0 else { return false }
    var addr = loopback(port: port)
    let rc = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    return rc == 0 && listen(fd, 1) == 0
}

func loopback(port: Int) -> sockaddr_in {
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = in_port_t(UInt16(port).bigEndian)
    addr.sin_addr.s_addr = inet_addr("127.0.0.1")
    return addr
}

func timeouts(_ fd: Int32, seconds: Int) {
    var tv = timeval(tv_sec: seconds, tv_usec: 0)
    let size = socklen_t(MemoryLayout<timeval>.size)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, size)
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, size)
    var one: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
}

func sendAll(_ fd: Int32, _ bytes: [UInt8]) throws {
    var off = 0
    while off < bytes.count {
        let n = bytes[off...].withUnsafeBytes { Darwin.send(fd, $0.baseAddress, $0.count, 0) }
        guard n > 0 else { throw Failure("send: \(errno)") }
        off += n
    }
}

func recvExactly(_ fd: Int32, _ count: Int) throws -> [UInt8] {
    var out = [UInt8](repeating: 0, count: count)
    var off = 0
    while off < count {
        let n = out[off...].withUnsafeMutableBytes { Darwin.recv(fd, $0.baseAddress, $0.count, 0) }
        guard n > 0 else { throw Failure(n == 0 ? "closed after \(off) of \(count) bytes" : "recv: \(errno)") }
        off += n
    }
    return out
}

func recvToEnd(_ fd: Int32) throws -> [UInt8] {
    var out: [UInt8] = []
    var buf = [UInt8](repeating: 0, count: 16 << 10)
    while true {
        let n = buf.withUnsafeMutableBytes { Darwin.recv(fd, $0.baseAddress, $0.count, 0) }
        if n == 0 { return out }
        guard n > 0 else { throw Failure("recv: \(errno)") }
        out += buf[0..<n]
    }
}

/// A SOCKS5 CONNECT by name with the login (RFC 1928, RFC 1929), as hev
/// asks; the connected socket.
func socksConnect(port: Int, user: String, pass: String, host: String, to dstPort: Int) throws -> Int32 {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { throw Failure("socket: \(errno)") }
    timeouts(fd, seconds: 20)
    var addr = loopback(port: port)
    let rc = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    do {
        guard rc == 0 else { throw Failure("connect: \(errno)") }
        try sendAll(fd, [5, 1, 2])
        let method = try recvExactly(fd, 2)
        guard method == [5, 2] else { throw Failure("method \(method)") }
        try sendAll(fd, [1, UInt8(user.utf8.count)] + Array(user.utf8) + [UInt8(pass.utf8.count)] + Array(pass.utf8))
        let auth = try recvExactly(fd, 2)
        guard auth == [1, 0] else { throw Failure("login refused: \(auth)") }
        try sendAll(fd, [5, 1, 0, 3, UInt8(host.utf8.count)] + Array(host.utf8)
            + [UInt8(dstPort >> 8), UInt8(dstPort & 0xff)])
        let head = try recvExactly(fd, 4)
        guard head[1] == 0 else { throw Failure("CONNECT refused: \(head[1])") }
        switch head[3] {
        case 1: _ = try recvExactly(fd, 4 + 2)
        case 4: _ = try recvExactly(fd, 16 + 2)
        case 3: _ = try recvExactly(fd, Int(try recvExactly(fd, 1)[0]) + 2)
        default: throw Failure("reply address type \(head[3])")
        }
        return fd
    } catch {
        close(fd)
        throw error
    }
}

/// GET `url` (plain HTTP) through the SOCKS port; the status line and the
/// body's length.
func fetch(_ url: String, socks: Int, user: String, pass: String) throws -> (String, Int) {
    guard let u = URL(string: url), let host = u.host, let port = u.port else { throw Failure("url \(url)") }
    let fd = try socksConnect(port: socks, user: user, pass: pass, host: host, to: port)
    defer { close(fd) }
    try sendAll(fd, Array("GET \(u.path) HTTP/1.0\r\nHost: \(host):\(port)\r\n\r\n".utf8))
    let all = try recvToEnd(fd)
    let sep: [UInt8] = [13, 10, 13, 10]
    guard let at = (0...max(0, all.count - 4)).first(where: { Array(all[$0..<min($0 + 4, all.count)]) == sep })
    else { throw Failure("no response head in \(all.count) bytes") }
    let status = String(decoding: all[0..<at], as: UTF8.self).components(separatedBy: "\r\n").first ?? ""
    return (status, all.count - at - 4)
}

// MARK: - hev and packets

/// hev on its own thread, as HevTunnel runs it.
final class Hev {
    private var thread: Thread?
    private let done = DispatchSemaphore(value: 0)
    private var code: Int32?

    func start(config: String, fd: Int32) throws {
        let bytes = Array(config.utf8)
        let worker = Thread { [self] in
            let c = bytes.withUnsafeBufferPointer {
                hev_socks5_tunnel_main_from_str($0.baseAddress, UInt32($0.count), fd)
            }
            code = c
            done.signal()
        }
        worker.stackSize = 2 << 20
        worker.start()
        if done.wait(timeout: .now() + 0.5) == .success {
            throw Failure("hev exited at once: \(code.map(String.init) ?? "?")")
        }
        thread = worker
    }

    func stop() {
        guard thread != nil else { return }
        hev_socks5_tunnel_quit()
        _ = done.wait(timeout: .now() + 3)
        thread = nil
    }
}

func hevConfig(port: Int, user: String, pass: String, udp: String) -> String {
    // HevTunnel.configuration, with the UDP mode under test.
    [
        "tunnel:", "  mtu: 1500",
        "socks5:", "  port: \(port)", "  address: 127.0.0.1", "  udp: '\(udp)'",
        "  username: '\(user)'", "  password: '\(pass)'",
        "misc:", "  task-stack-size: 24576", "  tcp-buffer-size: 4096", "  max-session-count: 768",
        "  connect-timeout: 5000", "  read-write-timeout: 60000", "  udp-read-write-timeout: 30000",
        "  log-file: stderr", "  log-level: warn",
    ].joined(separator: "\n") + "\n"
}

func checksum(_ bytes: [UInt8]) -> UInt16 {
    var sum: UInt32 = 0
    var i = 0
    while i + 1 < bytes.count {
        sum += UInt32(bytes[i]) << 8 | UInt32(bytes[i + 1])
        i += 2
    }
    if i < bytes.count { sum += UInt32(bytes[i]) << 8 }
    while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
    return ~UInt16(sum)
}

func be16(_ v: Int) -> [UInt8] { [UInt8((v >> 8) & 0xff), UInt8(v & 0xff)] }

/// One IPv4/UDP datagram as the tun would carry it, behind utun's
/// four-byte AF_INET header (what hev reads on Apple systems).
func udpDatagram(src: [UInt8], sport: Int, dst: [UInt8], dport: Int, payload: [UInt8]) -> [UInt8] {
    let ulen = 8 + payload.count
    var ip: [UInt8] = [0x45, 0] + be16(20 + ulen) + [0x4f, 0x4c, 0x40, 0, 64, 17, 0, 0] + src + dst
    ip.replaceSubrange(10..<12, with: be16(Int(checksum(ip))))
    var udp: [UInt8] = be16(sport) + be16(dport) + be16(ulen) + [0, 0] + payload
    var c = checksum(src + dst + [0, 17] + be16(ulen) + udp)
    if c == 0 { c = 0xffff }
    udp.replaceSubrange(6..<8, with: be16(Int(c)))
    return [0, 0, 0, UInt8(AF_INET)] + ip + udp
}

func ipv4(_ s: String) -> [UInt8] { s.split(separator: ".").compactMap { UInt8($0) } }

/// Sends `payload` from 10.7.0.2:`sport` to `dst`:`dport` into the tun end
/// and waits for the echo; the milliseconds the first answer took.
func udpRoundTrip(tun: Int32, dst: [UInt8], dport: Int, sport: Int, payload: [UInt8]) throws -> Int {
    let packet = udpDatagram(src: [10, 7, 0, 2], sport: sport, dst: dst, dport: dport, payload: payload)
    let t0 = Date()
    var buf = [UInt8](repeating: 0, count: 4096)
    for _ in 0..<15 {
        let sent = packet.withUnsafeBytes { Darwin.send(tun, $0.baseAddress, $0.count, 0) }
        guard sent == packet.count else { throw Failure("tun send: \(errno)") }
        let until = Date().addingTimeInterval(1)
        while Date() < until {
            let n = buf.withUnsafeMutableBytes { Darwin.recv(tun, $0.baseAddress, $0.count, 0) }
            if n <= 0 { break }
            let p = Array(buf[0..<n])
            guard p.count > 4 + 28, p[4] >> 4 == 4, p[4 + 9] == 17 else { continue }
            let ihl = Int(p[4] & 0x0f) * 4
            let u = 4 + ihl
            let dp = Int(p[u + 2]) << 8 | Int(p[u + 3])
            if dp == sport, Array(p[(u + 8)...]) == payload {
                return Int(Date().timeIntervalSince(t0) * 1000)
            }
        }
    }
    throw Failure("no echo within 15 s")
}

// MARK: - Memory

func physFootprint() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? info.phys_footprint : 0
}

func stats() throws -> [String: Any] {
    let line = memoryStats()
    XCTAssertFalse(line.contains("\n"), line)
    guard let obj = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else {
        throw Failure("memory_stats is not an object: \(line)")
    }
    return obj
}

/// The tasks alive on the core's runtime, -1 when unread.
func tasks() -> Int {
    ((try? stats())?["rt"] as? [String: Any])?["tasks"] as? Int ?? -1
}

func mem(_ label: String) {
    let s = (try? stats()) ?? [:]
    let heap = s["heap"] as? [String: Any]
    let rt = s["rt"] as? [String: Any]
    let f = Double(physFootprint()) / 1_048_576
    say(String(format: "MEM %@ footprint=%.2fMiB heap_live=%@ heap_peak=%@ rt_threads=%@ rt_tasks=%@", label, f,
               "\(heap?["live"] ?? "null")", "\(heap?["peak"] ?? "null")", "\(rt?["threads"] ?? "null")",
               "\(rt?["tasks"] ?? "null")"))
}

// MARK: - The tests

final class OlcFfiTests: XCTestCase {
    static let user = "lab"
    static let pass = "lab-\(UInt32.random(in: 1...UInt32.max))"

    /// OlcrtcEngine.launch's order: the provider and engine, the transport,
    /// the resolver, UDP, the rooms, the device, the key, the SOCKS front.
    func configure(_ rt: OlcrtcRuntime, _ host: T1Host, port: Int) throws {
        rt.setDebug(false)
        try rt.setProvider("none")
        rt.setEngine("interop-pipe", "", "")
        try rt.setTransport(host.transport)
        try rt.setDns(host.dns)
        rt.setUdp(true)
        try rt.setRoom(host.room)
        rt.clearFailoverRooms()
        rt.setDeviceId("lab-sim")
        try rt.setKey(host.key)
        try rt.setSocksListenHost("127.0.0.1")
        try rt.setSocksPort(Int64(port))
        try rt.setSocksCredentials(Self.user, Self.pass)
    }

    /// The errors carry Go's words to localizedDescription, as gomobile's
    /// NSError did (OlcrtcFailure.describe reads them).
    func testTheErrorsAreGosWords() throws {
        let rt = OlcrtcRuntime()
        func text(_ body: () throws -> Void) -> String {
            do {
                try body()
                return "no error"
            } catch {
                XCTAssertTrue(error is OlcrtcError, "\(type(of: error))")
                return error.localizedDescription
            }
        }
        XCTAssertEqual(text { try rt.setProvider("telemost") }, "unsupported provider: \"telemost\"")
        XCTAssertEqual(text { try rt.start() }, "invalid mobile runtime configuration: provider \"\"")
        XCTAssertEqual(text { try rt.waitReady(10) }, "olcRTC runtime has not been started")
        XCTAssertEqual(text { try rt.setSocksPort(0) },
                       "invalid mobile runtime configuration: SOCKS port must be between 1 and 65535")
        XCTAssertEqual(text { try rt.setKey("zz") },
                       "invalid mobile runtime configuration: decode key: encoding/hex: invalid byte: U+007A 'z'")
        // No open proxy: a SOCKS front off loopback needs the login.
        try rt.setProvider("none")
        try rt.setTransport("datachannel")
        try rt.setKey(String(repeating: "00", count: 32))
        try rt.setSocksListenHost("0.0.0.0")
        XCTAssertEqual(text { try rt.start() },
                       "invalid mobile runtime configuration: SOCKS credentials are required outside loopback")
        XCTAssertEqual(rt.state(), "idle")
        say("RESULT errors ok: Go's texts in localizedDescription")
    }

    func testTheExtensionsCallSequence() throws {
        let host = try T1Host.load()
        let lines = Lines()
        let keeper = Keeper()
        let pin = Pin()
        setLogWriter(lines)
        defer { setLogWriter(nil) }
        let rt = OlcrtcRuntime()
        rt.setSessionListener(keeper)
        rt.setProtector(pin)
        let port = try freePort()
        try configure(rt, host, port: port)
        XCTAssertEqual(rt.state(), "idle")
        mem("before-start")

        let t0 = Date()
        try rt.start()
        XCTAssertThrowsError(try rt.start()) {
            XCTAssertEqual($0.localizedDescription, "olcRTC runtime is already active")
        }
        try rt.waitReady(35_000)
        let readyMS = Int(Date().timeIntervalSince(t0) * 1000)
        XCTAssertEqual(rt.state(), "running")
        XCTAssertTrue(rt.isRunning())
        say("RESULT ready in \(readyMS) ms")
        mem("ready")

        // The log contract: the ready mark the apps read, each line whole.
        XCTAssertEqual(lines.count("SOCKS5 server listening on 127.0.0.1:\(port)"), 1, lines.all().suffix(20).joined())
        XCTAssertTrue(lines.all().allSatisfy { $0.hasSuffix("\n") }, "every line ends with its newline")
        let deadline = Date().addingTimeInterval(5)
        while keeper.all().isEmpty && Date() < deadline { usleep(20_000) }
        XCTAssertEqual(keeper.all().first?.0, host.room, "the session listener names the room")
        XCTAssertGreaterThan(pin.count, 0, "the engine's sockets reach the protector")
        say("RESULT callbacks: \(keeper.all().count) session(s) in the room, \(pin.count) socket(s) protected, \(lines.all().count) log lines")

        // TCP through the SOCKS port with the login; a wrong one is refused.
        let (status, body) = try fetch(host.origin, socks: port, user: Self.user, pass: Self.pass)
        XCTAssertTrue(status.hasPrefix("HTTP/1.") && status.contains(" 200"), status)
        XCTAssertEqual(body, 1024)
        XCTAssertThrowsError(try fetch(host.origin, socks: port, user: Self.user, pass: "wrong"))
        say("RESULT fetch: \(status), \(body) bytes; a wrong login refused")

        // memory_stats: one line, every layer of §9, the phone's runtime.
        let s = try stats()
        XCTAssertEqual(s["v"] as? Int, 1)
        XCTAssertEqual(s["budget"] as? Int, 12 * 1024 * 1024)
        let layers = s["layers"] as? [String: Any] ?? [:]
        for name in ["socks", "udp", "smux_rx", "smux_tx", "mux_in", "kcp", "vp8", "rtp", "str0m", "sctp", "signalling", "logs"] {
            XCTAssertEqual((layers[name] as? [Any])?.count, 3, name)
        }
        let heap = s["heap"] as? [String: Any]
        XCTAssertGreaterThan(heap?["live"] as? Int ?? 0, 0, "the counting allocator is the one in force")
        let rtStats = s["rt"] as? [String: Any]
        #if os(iOS)
        XCTAssertEqual(rtStats?["workers"] as? Int, 1, "the phone runtime: one worker")
        #endif
        say("RESULT memory_stats: \(memoryStats())")

        // UDP through hev in front: FWD_UDP, then UDP ASSOCIATE.
        let dst = host.echoIP.isEmpty ? [127, 0, 0, 1] : ipv4(host.echoIP)
        for (i, mode) in ["tcp", "udp"].enumerated() {
            var fds: [Int32] = [0, 0]
            XCTAssertEqual(socketpair(AF_UNIX, SOCK_DGRAM, 0, &fds), 0)
            timeouts(fds[1], seconds: 1)
            let hev = Hev()
            try hev.start(config: hevConfig(port: port, user: Self.user, pass: Self.pass, udp: mode), fd: fds[0])
            let payload = Array("olc-ffi lab udp \(mode) \(UInt32.random(in: 1...UInt32.max))".utf8)
            do {
                let ms = try udpRoundTrip(tun: fds[1], dst: dst, dport: host.echoPort, sport: 40_000 + i, payload: payload)
                say("RESULT hev udp '\(mode)' (\(mode == "tcp" ? "FWD_UDP" : "UDP ASSOCIATE")): echo in \(ms) ms via \(dst.map(String.init).joined(separator: "."))")
            } catch {
                XCTFail("hev udp '\(mode)': \(error)")
                say("RESULT hev udp '\(mode)' FAILED: \(error)")
            }
            hev.stop()
            close(fds[0])
            close(fds[1])
        }
        mem("after-udp")

        let t1 = Date()
        try rt.stop(5_000)
        say("RESULT stop in \(Int(Date().timeIntervalSince(t1) * 1000)) ms")
        XCTAssertEqual(rt.state(), "stopped")
        XCTAssertFalse(rt.isRunning())
        XCTAssertThrowsError(try rt.waitReady(100)) {
            XCTAssertEqual($0.localizedDescription, "olcRTC runtime has not been started")
        }
        mem("stopped")

        // Stop and start as a link change does.
        var readies: [Int] = []
        for i in 0..<10 {
            let c0 = Date()
            try rt.start()
            try rt.waitReady(35_000)
            readies.append(Int(Date().timeIntervalSince(c0) * 1000))
            let (st, n) = try fetch(host.origin, socks: port, user: Self.user, pass: Self.pass)
            XCTAssertEqual(n, 1024, "cycle \(i): \(st)")
            try rt.stop(5_000)
            XCTAssertEqual(rt.state(), "stopped")
        }
        let settle = Date().addingTimeInterval(3)
        while ((try? stats())?["rt"] as? [String: Any])?["tasks"] as? Int ?? 0 > 0 && Date() < settle { usleep(20_000) }
        mem("after-10-cycles")
        say("RESULT 10 stop/starts: ready \(readies.min() ?? 0)-\(readies.max() ?? 0) ms; tasks left \(((try? stats())?["rt"] as? [String: Any])?["tasks"] ?? "null")")
        let given = trim()
        mem("after-trim")
        say("RESULT trim gave back \(given) bytes")
    }

    /// The extension letting go of a running runtime with no stop() first
    /// (its last reference: the binding's deinit frees the object). The core
    /// stops the run as stop(5_000) would (xray-rust 325697de's Drop): the
    /// release returns within that bound, the SOCKS port refuses and binds
    /// again, no task is left, and the next runtime in the same room is ready
    /// and fetches. Before, the released one ran on: its task, its port and
    /// its session in the room.
    func testARunningRuntimeReleasedStopsItsRun() throws {
        let host = try T1Host.load()
        let port = try freePort()
        var first: OlcrtcRuntime? = OlcrtcRuntime()
        try configure(first!, host, port: port)
        try first!.start()
        try first!.waitReady(35_000)
        let (status, body) = try fetch(host.origin, socks: port, user: Self.user, pass: Self.pass)
        XCTAssertEqual(body, 1024, status)
        let busy = tasks()

        let t0 = Date()
        first = nil
        let releaseMS = Int(Date().timeIntervalSince(t0) * 1000)
        XCTAssertLessThan(releaseMS, 5_500, "the release took \(releaseMS) ms, stop's bound is 5,000")
        XCTAssertFalse(accepts(port: port), "the SOCKS port \(port) still accepts after the release")
        XCTAssertTrue(binds(port: port), "the SOCKS port \(port) is still held after the release")
        let settle = Date().addingTimeInterval(3)
        while tasks() > 0 && Date() < settle { usleep(20_000) }
        let left = tasks()
        XCTAssertEqual(left, 0, "tasks left after the release")
        say("RESULT release: a running runtime (\(busy) tasks) released in \(releaseMS) ms; the port refuses and binds again; \(left) tasks left")

        let next = OlcrtcRuntime()
        try configure(next, host, port: port)
        let c0 = Date()
        try next.start()
        try next.waitReady(35_000)
        let readyMS = Int(Date().timeIntervalSince(c0) * 1000)
        let (st, n) = try fetch(host.origin, socks: port, user: Self.user, pass: Self.pass)
        XCTAssertEqual(n, 1024, st)
        say("RESULT release: the next runtime in the room ready in \(readyMS) ms, fetch \(st)")
        try next.stop(5_000)
    }
}
