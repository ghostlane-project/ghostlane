import Darwin
import Foundation

/// The room the olcRTC carrier `none` dials, standing in for a carrier's:
/// an interop pipe hub on loopback (`tcp://127.0.0.1:<port>/<room>`; frames
/// of `u32 BE length | u8 kind | payload`, the length counting the kind).
/// It keeps the name each participant joins with (JOIN), then either ends
/// the conference at once with a reason (END) or holds the room silent,
/// reading and dropping what arrives. No olcRTC server is ever in the room
/// to answer the client's hello, so a check can only reach the room and end
/// there. The app pins no socket to an interface, so loopback is reachable
/// from the simulator's process.
final class PipeHub: @unchecked Sendable {
    enum Room: Sendable {
        /// The conference ends as soon as a participant joins, for this reason.
        case ends(String)
        /// Nobody answers and nothing ends: only the caller's timeout does.
        case silent
    }

    static let kindJoin: UInt8 = 1
    static let kindEnd: UInt8 = 5
    /// The largest payload a frame carries (the pipe's MAX_PAYLOAD).
    static let maxPayload = 64 * 1024

    let port: Int
    private let fd: Int32
    private let room: Room
    private let lock = NSLock()
    private var rooms: [String] = []
    private var stopped = false
    private let served = DispatchSemaphore(value: 0)
    private let members = DispatchGroup()

    init(_ room: Room) throws {
        // A local descriptor until the end: no closure may capture self
        // before every property is set.
        let listener = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard listener >= 0 else { throw PipeHubFailure("socket: \(String(cString: strerror(errno)))") }
        var on: Int32 = 1
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listener, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(listener, 16) == 0 else {
            let reason = String(cString: strerror(errno))
            Darwin.close(listener)
            throw PipeHubFailure("bind: \(reason)")
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(listener, $0, &length) }
        }
        self.room = room
        fd = listener
        port = Int(UInt16(bigEndian: address.sin_port))
        Thread { [self] in serve() }.start()
    }

    /// The room URL of `name` on this hub.
    func url(_ name: String) -> String {
        "tcp://127.0.0.1:\(port)/\(name)"
    }

    /// The rooms joined so far, in order.
    var joined: [String] {
        lock.lock()
        defer { lock.unlock() }
        return rooms
    }

    /// Stops taking participants, lets go of the ones in, and closes. Once.
    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        served.wait()
        members.wait()
        Darwin.close(fd)
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func serve() {
        while !isStopped {
            var poller = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            guard poll(&poller, 1, 100) > 0 else { continue }
            let member = Darwin.accept(fd, nil, nil)
            guard member >= 0 else { continue }
            members.enter()
            Thread { [self] in
                handle(member)
                members.leave()
            }.start()
        }
        served.signal()
    }

    private func handle(_ member: Int32) {
        defer { Darwin.close(member) }
        var on: Int32 = 1
        setsockopt(member, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        // Short reads, so a member's thread sees the stop.
        var limit = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(member, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        let joinBy = Date().addingTimeInterval(5)
        guard let head = receive(member, 5, by: joinBy), head[4] == PipeHub.kindJoin else { return }
        let length = Int(head[0]) << 24 | Int(head[1]) << 16 | Int(head[2]) << 8 | Int(head[3])
        guard length >= 1, length - 1 <= PipeHub.maxPayload,
              let name = receive(member, length - 1, by: joinBy) else { return }
        lock.lock()
        rooms.append(String(decoding: name, as: UTF8.self))
        lock.unlock()
        switch room {
        case let .ends(reason):
            let text = Array(reason.utf8)
            let size = UInt32(1 + text.count)
            var frame = withUnsafeBytes(of: size.bigEndian) { Array($0) }
            frame.append(PipeHub.kindEnd)
            frame += text
            _ = frame.withUnsafeBytes { Darwin.write(member, $0.baseAddress!, $0.count) }
            // Let the END arrive before the close: stop sending, then read
            // what the participant still sends until it goes.
            shutdown(member, SHUT_WR)
            drain(member, until: Date().addingTimeInterval(2))
        case .silent:
            drain(member, until: .distantFuture)
        }
    }

    /// Reads and drops until the member goes, the hub stops, or `until`.
    private func drain(_ member: Int32, until: Date) {
        var sink = [UInt8](repeating: 0, count: 16 * 1024)
        while !isStopped, Date() < until {
            let got = sink.withUnsafeMutableBytes { Darwin.read(member, $0.baseAddress!, $0.count) }
            if got == 0 { return }
            if got < 0, errno != EAGAIN, errno != EINTR { return }
        }
    }

    /// Exactly `count` bytes, or nil when the member goes, the hub stops or
    /// `by` passes first.
    private func receive(_ member: Int32, _ count: Int, by: Date) -> [UInt8]? {
        var bytes = [UInt8](repeating: 0, count: count)
        var have = 0
        while have < count {
            if isStopped || Date() >= by { return nil }
            let got = bytes.withUnsafeMutableBytes {
                Darwin.read(member, $0.baseAddress!.advanced(by: have), count - have)
            }
            if got > 0 {
                have += got
            } else if got == 0 || (errno != EAGAIN && errno != EINTR) {
                return nil
            }
        }
        return bytes
    }
}

struct PipeHubFailure: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
