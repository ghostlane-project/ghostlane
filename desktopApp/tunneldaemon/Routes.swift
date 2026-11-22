// The routes a tunnel under hev takes, laid down and taken away by the daemon.
//
// sing-box laid its routes itself (auto_route) and they went with its process.
// hev brings its utun up with its addresses and does nothing to the routing
// table, so for a tunnel under hev the daemon lays them: the whole of each
// family but its first /8 (0.0.0.0/8 is "this network", ::/8 holds the
// loopback and the mapped forms), less the addresses the tun is told to keep
// out of itself, as the fewest prefixes that cover the rest. That is the
// shape auto_route laid on this system with a server excluded
// (docs/macos-tunnel-daemon.md: `1`, `2/7`, `4/6`, ... split around the
// server's /32), and it needs no gateway: an address kept out is a hole in
// what goes into the tun, so it leaves by whatever route the machine had
// for it, on whichever network the machine is on now. The local network
// stays reachable the same way: its own route is longer than any of these.
//
// The utun takes every route through it when it goes: hev killed outright
// leaves none behind. A stop takes them away before hev is stopped, and the
// daemon forgets them when hev dies by itself.
//
// Each route is one message written to the kernel's routing socket, the one
// route(8) writes for `route -n add -inet <prefix> -interface <utun>`, and the
// kernel's answer is the write's. A request is answered with the daemon's lock
// held while they are laid, and a run of route(8) for each, waited for, cost
// some 0.12 s a route: 170 of them kept every other request waiting 21 to 23 s.
import Foundation

/// An IPv4 or IPv6 network: an address and the length of its prefix, every
/// bit past the prefix zero.
struct Prefix: Equatable {
    let family: Int32
    let bytes: [UInt8]
    let length: Int

    var isV6: Bool { family == AF_INET6 }
    var width: Int { bytes.count * 8 }

    init(family: Int32, bytes: [UInt8], length: Int) {
        var masked = bytes
        for index in 0..<masked.count {
            let kept = max(0, min(8, length - index * 8))
            masked[index] &= kept == 0 ? 0 : UInt8(0xFF) << UInt8(8 - kept)
        }
        self.family = family
        self.bytes = masked
        self.length = length
    }

    /// `a.b.c.d/n`, `x:y::z/n`, or an address alone for the one address. Read
    /// as the system reads an address, and nothing after it.
    init?(_ text: String) {
        let parts = text.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 1 || parts.count == 2 else { return nil }
        var v4 = in_addr()
        var v6 = in6_addr()
        let family: Int32
        let raw: [UInt8]
        if inet_pton(AF_INET, parts[0], &v4) == 1 {
            family = AF_INET
            raw = withUnsafeBytes(of: &v4) { Array($0) }
        } else if inet_pton(AF_INET6, parts[0], &v6) == 1 {
            family = AF_INET6
            raw = withUnsafeBytes(of: &v6) { Array($0) }
        } else {
            return nil
        }
        var length = raw.count * 8
        if parts.count == 2 {
            guard let number = Int(parts[1]), number >= 0, number <= length, String(number) == parts[1] else {
                return nil
            }
            length = number
        }
        self.init(family: family, bytes: raw, length: length)
    }

    /// The system's own way of writing the address, then the length.
    var text: String {
        let size = 64
        var buffer = [CChar](repeating: 0, count: size)
        bytes.withUnsafeBytes { raw in
            _ = inet_ntop(family, raw.baseAddress, &buffer, socklen_t(size))
        }
        return buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) } + "/\(length)"
    }

    /// The netmask of the prefix: its first [length] bits set.
    var netmask: [UInt8] {
        Prefix(family: family, bytes: [UInt8](repeating: 0xFF, count: bytes.count), length: length).bytes
    }

    func bit(_ index: Int) -> Bool {
        bytes[index / 8] & (UInt8(0x80) >> UInt8(index % 8)) != 0
    }

    /// Whether the two share an address: then one of them holds the other.
    func overlaps(_ other: Prefix) -> Bool {
        guard family == other.family else { return false }
        for index in 0..<min(length, other.length) where bit(index) != other.bit(index) {
            return false
        }
        return true
    }

    /// The two halves, one bit longer. Only for a prefix shorter than its address.
    func halves() -> (Prefix, Prefix) {
        var high = bytes
        high[length / 8] |= UInt8(0x80) >> UInt8(length % 8)
        return (
            Prefix(family: family, bytes: bytes, length: length + 1),
            Prefix(family: family, bytes: high, length: length + 1)
        )
    }
}

final class Routes {
    /// The most a tun lays: an IPv6 address kept out costs some 125 routes.
    static let most = 1024

    /// The routes this daemon laid and has not taken away, lowest first.
    private(set) var laid: [Prefix] = []
    private var interface: String?
    private var sequence: Int32 = 0

    /// What is left of [whole] once [holes] are taken out of it: the fewest
    /// prefixes that cover it, lowest first.
    static func complement(of whole: Prefix, without holes: [Prefix]) -> [Prefix] {
        let inside = holes.filter { $0.overlaps(whole) }
        if inside.isEmpty { return [whole] }
        if inside.contains(where: { $0.length <= whole.length }) { return [] }
        let (low, high) = whole.halves()
        return complement(of: low, without: inside) + complement(of: high, without: inside)
    }

    /// The routes a tun takes that keeps [holes] out of itself, IPv4 first.
    static func plan(holes: [Prefix]) -> [Prefix] {
        let v4 = [UInt8](repeating: 0, count: 4)
        let v6 = [UInt8](repeating: 0, count: 16)
        let ownV4 = [Prefix(family: AF_INET, bytes: v4, length: 8)] + holes.filter { !$0.isV6 }
        let ownV6 = [Prefix(family: AF_INET6, bytes: v6, length: 8)] + holes.filter { $0.isV6 }
        return complement(of: Prefix(family: AF_INET, bytes: v4, length: 0), without: ownV4)
            + complement(of: Prefix(family: AF_INET6, bytes: v6, length: 0), without: ownV6)
    }

    /// Lays the routes of [plan(holes:)] through [interface], IPv6 too when the
    /// tun has an address of that family. All or none: the first refused one
    /// takes the others away again and says which it was.
    @discardableResult
    func lay(on interface: String, holes: [Prefix], ipv6: Bool) throws -> Int {
        takeAway()
        guard let link = Self.linkAddress(of: interface) else {
            throw DaemonError.message("there is no \(interface) to lay routes through")
        }
        guard let channel = Self.openChannel() else {
            throw DaemonError.message("the routing socket did not open: \(Self.words(errno))")
        }
        defer { close(channel) }
        self.interface = interface
        for prefix in Self.plan(holes: holes) where ipv6 || !prefix.isV6 {
            let refused = change(RTM_ADD, prefix, through: link, on: channel)
            guard refused == 0 else {
                takeAway()
                throw DaemonError.message("the route to \(prefix.text) through \(interface) was refused: \(Self.words(refused))")
            }
            laid.append(prefix)
        }
        return laid.count
    }

    /// Takes away what [lay] laid. Not when the utun has gone: its routes went
    /// with it, and a route of the same prefix that stands now is somebody
    /// else's (a delete is matched by the prefix, not by the interface).
    func takeAway() {
        defer {
            self.laid = []
            self.interface = nil
        }
        guard let name = interface, !laid.isEmpty,
              let link = Self.linkAddress(of: name),
              let channel = Self.openChannel() else { return }
        defer { close(channel) }
        for prefix in laid.reversed() {
            _ = change(RTM_DELETE, prefix, through: link, on: channel)
        }
    }

    /// A routing socket to write to, or nil with errno set. Nothing is read
    /// from it: the kernel's answer to each message is its write's.
    private static func openChannel() -> Int32? {
        let channel = socket(AF_ROUTE, SOCK_RAW, 0)
        guard channel >= 0 else { return nil }
        // As route(8) does, so that the kernel queues no message of anybody's for it.
        _ = shutdown(channel, SHUT_RD)
        return channel
    }

    /// One RTM_ADD or RTM_DELETE for [prefix] through the interface whose link
    /// address is [link], as route(8) writes it with `-interface`: the
    /// destination, that link address as the gateway, with no RTF_GATEWAY, and
    /// the netmask, but for an IPv6 host, which it writes as a host route.
    /// 0 when the kernel took it, else what it said (an errno).
    private func change(_ type: Int32, _ prefix: Prefix, through link: [UInt8], on channel: Int32) -> Int32 {
        let host = prefix.isV6 && prefix.length == prefix.width
        var addresses = Self.padded(Self.socketAddress(prefix.family, prefix.bytes)) + Self.padded(link)
        var present = RTA_DST | RTA_GATEWAY
        if !host {
            addresses += Self.padded(Self.socketAddress(prefix.family, prefix.netmask))
            present |= RTA_NETMASK
        }
        sequence &+= 1
        var header = rt_msghdr()
        header.rtm_msglen = UInt16(MemoryLayout<rt_msghdr>.size + addresses.count)
        header.rtm_version = UInt8(RTM_VERSION)
        header.rtm_type = UInt8(type)
        header.rtm_flags = RTF_UP | RTF_STATIC | (host ? RTF_HOST : 0)
        header.rtm_addrs = present
        header.rtm_seq = sequence
        var message = withUnsafeBytes(of: &header) { [UInt8]($0) }
        message += addresses
        let written = message.withUnsafeBytes { write(channel, $0.baseAddress, $0.count) }
        if written < 0 { return errno }
        return written == message.count ? 0 : EIO
    }

    /// [bytes] as a sockaddr of [family]: its length, its family, a port of
    /// 0, then the address (after the flow label for IPv6).
    private static func socketAddress(_ family: Int32, _ bytes: [UInt8]) -> [UInt8] {
        var raw: [UInt8]
        if family == AF_INET6 {
            raw = [UInt8](repeating: 0, count: MemoryLayout<sockaddr_in6>.size)
            raw.replaceSubrange(8..<24, with: bytes)
        } else {
            raw = [UInt8](repeating: 0, count: MemoryLayout<sockaddr_in>.size)
            raw.replaceSubrange(4..<8, with: bytes)
        }
        raw[0] = UInt8(raw.count)
        raw[1] = UInt8(family)
        return raw
    }

    /// A sockaddr in a routing message fills whole 32-bit words.
    private static func padded(_ raw: [UInt8]) -> [UInt8] {
        raw + [UInt8](repeating: 0, count: (4 - raw.count % 4) % 4)
    }

    /// [name]'s own link address, which route(8) names as the gateway for
    /// `-interface <name>`; nil when there is no such interface.
    private static func linkAddress(of name: String) -> [UInt8]? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return nil }
        defer { freeifaddrs(first) }
        var next = first
        while let entry = next {
            next = entry.pointee.ifa_next
            guard let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == sa_family_t(AF_LINK),
                  String(cString: entry.pointee.ifa_name) == name else { continue }
            return [UInt8](UnsafeRawBufferPointer(start: UnsafeRawPointer(address), count: Int(address.pointee.sa_len)))
        }
        return nil
    }

    private static func words(_ code: Int32) -> String {
        String(cString: strerror(code))
    }
}
