import Darwin
import Foundation

/// The room the olcRTC carrier `none` dials: an interop pipe hub
/// (`tcp://host:port/room`; frames of `u32 BE length | u8 kind | payload`,
/// the length counting the kind). It reads each participant's JOIN, keeps
/// the room's name, and ends the conference at once with a reason (END):
/// no olcRTC server is in the room to answer the client's hello.
final class HostHub {
    static let kindJoin: UInt8 = 1
    static let kindEnd: UInt8 = 5

    let port: Int
    private let fd: Int32
    private let endReason: String
    private let lock = NSLock()
    private var rooms: [String] = []
    private var stopped = false
    private let done = DispatchSemaphore(value: 0)

    init(host: String, endReason: String) throws {
        // A local descriptor until the end: no closure may capture self
        // before every property is set.
        let listener = socket(AF_INET, SOCK_STREAM, 0)
        guard listener >= 0 else { throw HostFailure("hub socket: \(String(cString: strerror(errno)))") }
        var on: Int32 = 1
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        var address = HostConnection.address(host, 0)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(listener, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(listener, 16) == 0 else {
            let reason = String(cString: strerror(errno))
            Darwin.close(listener)
            throw HostFailure("hub bind: \(reason)")
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(listener, $0, &length) }
        }
        self.endReason = endReason
        fd = listener
        port = Int(UInt16(bigEndian: address.sin_port))
    }

    /// The rooms joined so far, in order.
    var joined: [String] {
        lock.lock()
        defer { lock.unlock() }
        return rooms
    }

    func serve() {
        let thread = Thread { [self] in
            while !isStopped {
                var poller = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
                guard poll(&poller, 1, 200) > 0 else { continue }
                let member = accept(fd, nil, nil)
                guard member >= 0 else { continue }
                handle(member)
            }
            done.signal()
        }
        thread.start()
    }

    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        done.wait()
        Darwin.close(fd)
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func handle(_ member: Int32) {
        defer { Darwin.close(member) }
        var on: Int32 = 1
        setsockopt(member, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var limit = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(member, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        guard let head = HostHub.read(member, 5) else { return }
        let length = Int(head[0]) << 24 | Int(head[1]) << 16 | Int(head[2]) << 8 | Int(head[3])
        guard head[4] == HostHub.kindJoin, length >= 1, length - 1 <= 64 * 1024,
              let room = HostHub.read(member, length - 1) else { return }
        lock.lock()
        rooms.append(String(decoding: room, as: UTF8.self))
        lock.unlock()
        let reason = Array(endReason.utf8)
        let size = UInt32(1 + reason.count)
        var frame = withUnsafeBytes(of: size.bigEndian) { Array($0) }
        frame.append(HostHub.kindEnd)
        frame += reason
        _ = frame.withUnsafeBytes { Darwin.write(member, $0.baseAddress!, $0.count) }
        // Let the END arrive before the close: stop sending, then read what
        // the participant still sends until it goes.
        shutdown(member, SHUT_WR)
        var drain = [UInt8](repeating: 0, count: 4096)
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            let got = drain.withUnsafeMutableBytes { Darwin.read(member, $0.baseAddress!, $0.count) }
            if got <= 0 { break }
        }
    }

    private static func read(_ fd: Int32, _ count: Int) -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: count)
        var have = 0
        while have < count {
            let got = bytes.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress!.advanced(by: have), count - have) }
            if got <= 0 { return nil }
            have += got
        }
        return bytes
    }
}
