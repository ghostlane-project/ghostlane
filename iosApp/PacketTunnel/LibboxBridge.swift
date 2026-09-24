import Cores
import Darwin
import Foundation
import NetworkExtension
import os

/// The half of sing-box that has to be written per platform.
///
/// libbox runs the whole engine in-process and asks the host for the few things
/// only the host can do — chiefly a tun to own. On iOS there is no tun device to
/// open: the system hands the extension an `NEPacketTunnelFlow`, and the file
/// descriptor behind it is what libbox needs. That is the one interesting method
/// here; the rest are Android concerns or capabilities iOS does not expose.
final class LibboxPlatform: NSObject, LibboxPlatformInterfaceProtocol {

    /// Fixed on both sides rather than read from libbox's options.
    ///
    /// The app writes the config, so the tun inbound in it and the settings
    /// applied here are two halves of one decision — keeping them as constants
    /// in one place is clearer than parsing them back out of the engine, and it
    /// avoids depending on option types whose shape changes between releases.
    enum Tun {
        static let address = "172.19.0.1"
        static let mask = "255.255.255.252"
        static let mtu = 9000
        // An address only our own sing-box answers — every query to it is
        // hijacked on port 53. It used to be 1.1.1.1 and 8.8.8.8, which iOS
        // knows as encrypted-DNS providers and quietly upgraded to DoT/DoH:
        // those queries left through the tunnel past the hijack, and on a
        // lossy relay each one took twenty seconds. Inside the tun's own /30.
        static let dns = ["172.19.0.2"]
        // For the tun hev-socks5-tunnel owns (xhttp and olcRTC): there is no
        // sing-box to hijack 172.19.0.2, so a query to it would travel through
        // the SOCKS engine to an address nothing answers. These are answered on
        // the internet, reached the way every other packet is, as UDP through
        // SOCKS5 UDP ASSOCIATE. OpenDNS rather than 1.1.1.1 or 8.8.8.8, which
        // iOS knows as encrypted-DNS providers and quietly upgrades to DoT/DoH
        // — the upgrade is what made each query take twenty seconds on a lossy
        // relay before the hijack existed. A plain resolver through a lossy
        // relay is still slower than the hijack was; olcRTC's datagram lane is
        // the case to watch.
        static let dnsThroughSocks = ["208.67.222.222", "208.67.220.220"]
    }

    private weak var provider: NEPacketTunnelProvider?
    private let log = Logger(subsystem: "org.proofkit.app", category: "libbox")

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
        super.init()
    }

    // MARK: - the one that matters

    /// Applies the tunnel settings and hands libbox the descriptor behind
    /// `packetFlow`.
    ///
    /// `socket.fileDescriptor` is not public API. It is, however, how every
    /// libbox-based iOS client does this — the alternative is copying every
    /// packet between Swift and Go, which the memory budget here does not allow.
    func openTun(_ options: LibboxTunOptionsProtocol?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        guard let provider else {
            throw NSError(domain: "org.proofkit.tunnel", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "provider went away"])
        }

        // Nothing slow happens here on purpose. libbox calls this synchronously
        // from its own start-up path and warns when it blocks — the first version
        // applied the tunnel settings here and waited on the callback, which is
        // what produced "open interface take too much time". The settings are
        // applied before the engine starts now, so this only hands over the
        // descriptor.
        guard let fd = TunDescriptor.find(in: provider.packetFlow) else {
            throw NSError(domain: "org.proofkit.tunnel", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "no descriptor behind packetFlow"])
        }
        log.info("tun opened, fd=\(fd, privacy: .public)")
        ret0_?.pointee = fd
    }

    /// The settings the tun is brought up with. `dns` names the resolvers the
    /// system is told to use: `Tun.dns` when sing-box owns the tun and answers
    /// them itself, `Tun.dnsThroughSocks` when hev-socks5-tunnel does and every
    /// query travels through the SOCKS engine to a resolver on the internet.
    static func tunnelSettings(dns: [String] = Tun.dns) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: Tun.address)
        let ipv4 = NEIPv4Settings(addresses: [Tun.address], subnetMasks: [Tun.mask])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        settings.mtu = NSNumber(value: Tun.mtu)

        let dnsSettings = NEDNSSettings(servers: dns)
        dnsSettings.matchDomains = [""]
        settings.dnsSettings = dnsSettings
        return settings
    }

    // MARK: - things iOS answers plainly

    func underNetworkExtension() -> Bool { true }

    /// procfs is a Linux notion; there is nothing to read here.
    func useProcFS() -> Bool { false }

    /// Every outbound socket has to be pinned to the physical interface.
    ///
    /// The default route now points into our own tun, so a socket left to the
    /// system's judgement comes straight back to us and dials forever. sing-box
    /// said so plainly: "open outbound connection: dial tcp …: i/o timeout"
    /// while its inbound side was happily receiving the same connection.
    func usePlatformAutoDetectControl() -> Bool { true }

    /// Only meaningful with a multipath configuration we do not use.
    func includeAllNetworks() -> Bool { false }

    // The command server owns sing-box logs in 1.13. Interface diagnostics
    // also go to a dedicated shared-container file, independent of Go startup.

    func clearDNSCache() {
        // The system resolver is bypassed entirely — the engine does its own DNS.
    }

    // MARK: - Android-only, and unsupported capabilities

    func autoDetectControl(_ fd: Int32) throws {
        let code = Self.bindToPhysicalInterface(fd)
        guard code == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(code),
                          userInfo: [NSLocalizedDescriptionKey: "physical interface binding failed (errno=\(code))"])
        }
    }

    /// Binds one socket to the interface that actually reaches the internet.
    ///
    /// Shared with olcRTC, which needs exactly this and for exactly the same
    /// reason: any core running beside sing-box dials out from inside the
    /// extension, where the default route now points at our own tun. A second
    /// copy of this would be a second thing to get wrong.
    ///
    /// Returns false only when the socket cannot be inspected, there is no
    /// physical interface at all, or the kernel refused the bind — in which
    /// case there is nothing to dial out of either, so callers that must answer
    /// a boolean can report it and callers that cannot simply proceed.
    static func pinToPhysicalInterface(_ fd: Int32) -> Bool {
        bindToPhysicalInterface(fd) == 0
    }

    /// Return the actual bind error to both engines instead of reporting success
    /// after two unchecked setsockopt calls. Only configure the socket's family.
    private static func bindToPhysicalInterface(_ fd: Int32) -> Int32 {
        var address = sockaddr_storage()
        var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
        let inspected = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard inspected == 0 else {
            let code = errno
            NetworkDiagnostics.record("bind getsockname failed errno=\(code)")
            return code
        }
        let family = Int32(address.ss_family)
        guard family == AF_INET || family == AF_INET6 else {
            NetworkDiagnostics.record("bind unsupported family=\(family)")
            return EAFNOSUPPORT
        }
        guard let pin = currentPhysicalInterface(family: family) else {
            NetworkDiagnostics.record("bind \(familyLabel(family)): no physical interface")
            return ENETUNREACH
        }
        var scope = pin.index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        let result = family == AF_INET
            ? setsockopt(fd, IPPROTO_IP, 25, &scope, size)
            : setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
        let code: Int32 = result == 0 ? 0 : errno
        // Every socket passes through here and the trace is bounded, so the
        // ordinary case stays quiet: the interface it would name is already on
        // record from `report`. A bind the kernel refused is the news.
        if code != 0 {
            NetworkDiagnostics.record("bind \(familyLabel(family)) \(pin.summary) errno=\(code)")
            invalidatePinCache()
        }
        return code
    }

    private static func familyLabel(_ family: Int32) -> String {
        family == AF_INET6 ? "v6" : "v4"
    }

    static func tracePhysicalInterfaces(_ stage: String) {
        let candidates = probePhysicalInterfaces()
        let system = systemPath.interfaces
        let v4 = PhysicalInterface.choose(from: candidates, family: AF_INET, system: system)
        let v6 = PhysicalInterface.choose(from: candidates, family: AF_INET6, system: system)
        NetworkDiagnostics.record("\(stage) ipv4=\(v4?.summary ?? "none") ipv6=\(v6?.summary ?? "none") system=\(systemLabel(system)) candidates=\(candidates.map(\.summary).joined(separator: ","))")
    }

    /// Records the interfaces the system routes through, most preferred first,
    /// from the provider's path monitor; an empty list when the path is not
    /// satisfied, and before the monitor's first answer. The pin prefers them
    /// over its own probe (see `PhysicalInterface.choose`).
    ///
    /// Only names: the extension imports both Network and NetworkExtension,
    /// which each have a type called `NWPath`, and the pin needs nothing else.
    static func noteSystemPath(_ interfaces: [String]) {
        var unique: [String] = []
        for name in interfaces where !unique.contains(name) {
            unique.append(name)
        }
        systemPath.interfaces = unique
    }

    private static func systemLabel(_ system: [String]) -> String {
        system.isEmpty ? "none" : system.joined(separator: ",")
    }

    /// Forgets the interface last pinned to, so the next socket looks again.
    ///
    /// The provider calls this whenever the system says the path changed. The
    /// cache is short-lived regardless; this makes a handover count on the very
    /// next dial rather than the next few.
    static func invalidatePinCache() {
        ipv4PinCache.invalidate()
        ipv6PinCache.invalidate()
    }

    // MARK: - choosing the interface

    private static let pinLog = Logger(subsystem: "org.proofkit.app", category: "pin")
    private static let ipv4PinCache = PinCache()
    private static let ipv6PinCache = PinCache()
    private static let systemPath = SystemPath()

    /// Written from the path monitor's queue, read by whichever thread is
    /// dialing, so behind a lock of its own.
    private final class SystemPath: @unchecked Sendable {
        private let lock = NSLock()
        private var names: [String] = []

        var interfaces: [String] {
            get {
                lock.lock()
                defer { lock.unlock() }
                return names
            }
            set {
                lock.lock()
                names = newValue
                lock.unlock()
            }
        }
    }

    /// Every outbound socket asks. `getifaddrs` plus a route probe per family
    /// per candidate on each dial would be a cost of its own, so the answer is
    /// kept briefly — short enough that a handover is noticed within a dial or
    /// two, and the provider drops it outright when the path changes.
    private static let pinCacheLifetime: TimeInterval = 5

    /// The last answer, behind a lock: Go calls `protect` from whichever
    /// goroutine is dialing, and libbox from threads of its own.
    ///
    /// "No interface at all" is an answer too, and it is kept for the same
    /// lifetime. It used to be probed afresh on every socket, and a phone
    /// that has just lost its network is exactly where the engine opens a
    /// hundred of them in two seconds: a hundred interface walks and a
    /// hundred lines in the diagnostics for one fact (olcbox#37). Nothing
    /// waits on the lifetime to notice the network coming back — the
    /// provider drops the cache the moment the path changes.
    private final class PinCache: @unchecked Sendable {
        private let lock = NSLock()
        private var value: PhysicalInterface?
        private var stamp: TimeInterval = 0
        private var answered = false

        func invalidate() {
            lock.lock()
            value = nil
            answered = false
            lock.unlock()
        }

        /// The cached answer while it is fresh, else a new one from `refresh` —
        /// under the lock, so two sockets arriving together probe once.
        func current(
            lifetime: TimeInterval,
            refresh: (_ previous: PhysicalInterface?) -> PhysicalInterface?
        ) -> PhysicalInterface? {
            lock.lock()
            defer { lock.unlock() }
            let now = Date().timeIntervalSinceReferenceDate
            if answered, now - stamp < lifetime {
                return value
            }
            let fresh = refresh(value)
            value = fresh
            stamp = now
            answered = true
            return fresh
        }
    }

    private static func currentPhysicalInterface(family: Int32) -> PhysicalInterface? {
        let cache: PinCache
        switch family {
        case AF_INET: cache = ipv4PinCache
        case AF_INET6: cache = ipv6PinCache
        default: return nil
        }
        return cache.current(lifetime: pinCacheLifetime) { previous in
            let probed = probePhysicalInterfaces()
            let system = systemPath.interfaces
            let chosen = PhysicalInterface.choose(from: probed, family: family, system: system)
            // Once per change, and every refresh while this family has no way
            // out — the second case is rare and is the one worth a line each time.
            if chosen != previous || chosen?.routes(family) != true {
                report(chosen, among: probed, system: system, family: family)
            }
            return chosen
        }
    }

    private static func report(
        _ chosen: PhysicalInterface?,
        among probed: [PhysicalInterface],
        system: [String],
        family: Int32
    ) {
        let candidates = probed.isEmpty ? "no candidates" : probed.map(\.summary).joined(separator: " ")
        let seen = "\(candidates); system \(systemLabel(system))"
        let line: String
        if let chosen, chosen.routes(family) {
            line = "pin \(familyLabel(family)): \(chosen.name) (\(seen))"
        } else if let chosen {
            line = "pin \(familyLabel(family)): NO ROUTE for this family, pinning \(chosen.name) (\(seen))"
        } else {
            line = "pin \(familyLabel(family)): NO ROUTE (\(seen))"
        }
        pinLog.info("\(line, privacy: .public)")
        NetworkDiagnostics.record(line)
        // Also retain stderr output for attached debugging sessions. The file
        // exported by the app is written independently by NetworkDiagnostics.
        try? FileHandle.standardError.write(contentsOf: Data((line + "\n").utf8))
    }

    /// Every interface that could carry traffic out, probed.
    private static func probePhysicalInterfaces() -> [PhysicalInterface] {
        candidateInterfaces().map { candidate in
            PhysicalInterface(
                name: candidate.name,
                index: candidate.index,
                routesIPv4: hasRoute(index: candidate.index, family: AF_INET),
                routesIPv6: hasRoute(index: candidate.index, family: AF_INET6)
            )
        }
    }

    private struct Candidate {
        let name: String
        let index: UInt32
    }

    /// Interfaces that are up, not loopback, and carry a real address, in
    /// `PhysicalInterface.ordered` order: Wi-Fi ahead of cellular, cellular
    /// bearers by number. Ordering only: whether an interface can reach
    /// anything is the probe's question, not this one's.
    ///
    /// This used to *return* the first `en*`, else the first `pdp_ip*`, and that
    /// answer was final. A phone on cellular has several `pdp_ip` interfaces up
    /// at once — the internet bearer and the VoLTE one at least — and the VoLTE
    /// bearer carries a perfectly global address with no route to anything but
    /// the carrier's IMS core. Whichever `getifaddrs` listed first won, and on
    /// one carrier that was the wrong one: every socket bound to it, "no route
    /// to host" on both families, while the system's own resolver — never
    /// pinned — answered fine beside it.
    private static func candidateInterfaces() -> [Candidate] {
        var addresses: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addresses) == 0, let first = addresses else { return [] }
        defer { freeifaddrs(addresses) }

        var found: [String: Candidate] = [:]
        var listed: [String] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard entry.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  entry.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0,
                  let address = entry.pointee.ifa_addr,
                  carriesTraffic(address)
            else { continue }

            let name = String(cString: entry.pointee.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("pdp_ip"), found[name] == nil else { continue }
            let index = if_nametoindex(name)
            guard index != 0 else { continue }
            found[name] = Candidate(name: name, index: index)
            listed.append(name)
        }
        return PhysicalInterface.ordered(listed).compactMap { found[$0] }
    }

    /// Whether a socket bound to `index` can pick a source address for a public
    /// destination in `family`. Connecting a UDP socket assigns a route and
    /// sends nothing — the engine's own probe, done here in Swift so the choice
    /// is made before any Go socket exists.
    private static func hasRoute(index: UInt32, family: Int32) -> Bool {
        let fd = socket(family, SOCK_DGRAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }

        var scope = index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        let bound = family == AF_INET
            ? setsockopt(fd, IPPROTO_IP, 25, &scope, size)
            : setsockopt(fd, IPPROTO_IPV6, 125, &scope, size)
        guard bound == 0 else { return false }

        // The resolver the engine is already pointed at, on each family.
        return family == AF_INET
            ? connectProbe(fd, v4: "1.1.1.1")
            : connectProbe(fd, v6: "2606:4700:4700::1111")
    }

    private static func connectProbe(_ fd: Int32, v4 host: String) -> Bool {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(53).bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { return false }
        return withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { generic in
                connect(fd, generic, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0
            }
        }
    }

    private static func connectProbe(_ fd: Int32, v6 host: String) -> Bool {
        var addr = sockaddr_in6()
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_port = UInt16(53).bigEndian
        guard inet_pton(AF_INET6, host, &addr.sin6_addr) == 1 else { return false }
        return withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { generic in
                connect(fd, generic, socklen_t(MemoryLayout<sockaddr_in6>.size)) == 0
            }
        }
    }

    /// Whether an interface address is one traffic can leave the device by.
    ///
    /// This used to be `sa_family == AF_INET` and nothing else, which asks "does
    /// it have an address" only of a network that has IPv4. On an IPv6-only
    /// carrier `pdp_ip0` carries no AF_INET address at all, so no interface
    /// matched, the lookup returned nil, and `pinToPhysicalInterface` then
    /// answered false for *every* socket - the cores could not dial at all, and
    /// olcRTC's own account of it was an unrelated "network is unreachable" from
    /// the IPv4 half of a dual-stack dial (olcrtc#1). Apple runs App Review on
    /// an IPv6-only NAT64 network, so this is the review path, not an edge case.
    ///
    /// Link-local is excluded deliberately. `fe80::/10` sits on every interface
    /// whether or not it reaches anything, and `169.254/16` means IPv4 gave up;
    /// counting either would pin to an associated-but-dead Wi-Fi in front of a
    /// working cellular, which is the bug this function already existed to avoid.
    private static func carriesTraffic(_ address: UnsafeMutablePointer<sockaddr>) -> Bool {
        switch Int32(address.pointee.sa_family) {
        case AF_INET:
            let v4 = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                $0.pointee.sin_addr
            }
            return withUnsafeBytes(of: v4) { raw in
                !(raw[0] == 169 && raw[1] == 254)
            }
        case AF_INET6:
            let v6 = address.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
                $0.pointee.sin6_addr
            }
            return withUnsafeBytes(of: v6) { raw in
                !(raw[0] == 0xfe && raw[1] & 0xc0 == 0x80)
            }
        default:
            return false
        }
    }

    /// 1.13 returns a whole owner record rather than a uid, and folded the two
    /// Android package-name lookups into it — so the pair of methods that used to
    /// sit here is gone with them.
    func findConnectionOwner(
        _ ipProtocol: Int32,
        sourceAddress: String?,
        sourcePort: Int32,
        destinationAddress: String?,
        destinationPort: Int32
    ) throws -> LibboxConnectionOwner {
        throw Self.unsupported("connection ownership is not visible on iOS")
    }

    /// Only Android has a DNS resolver worth borrowing. Answering nil leaves the
    /// engine on its own resolver, which is what the config already asks for.
    func localDNSTransport() -> LibboxLocalDNSTransportProtocol? { nil }

    /// New in 1.13, and only meaningful where the platform holds a trust store
    /// the engine cannot read. iOS pins nothing here: outbound TLS is either
    /// verified against the system roots the engine already reaches or, for a
    /// published certificate pin, deliberately unverified.
    func systemCertificates() -> LibboxStringIteratorProtocol? { nil }

    /// Reported as unavailable rather than faked: sing-box only asks when a rule
    /// depends on it, and a wrong answer would route traffic on a false premise.
    func getInterfaces() throws -> LibboxNetworkInterfaceIteratorProtocol {
        throw Self.unsupported("interface enumeration is not implemented")
    }

    func readWIFIState() -> LibboxWIFIState? { nil }

    func send(_ notification: LibboxNotification?) throws {
        // The extension has no UI; anything worth saying goes to the log.
    }

    func startDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
        // No monitor yet. The cost is that a Wi-Fi/cellular switch is not noticed
        // until the connection itself fails, which is worth fixing once the
        // transports work but would only obscure the first bring-up.
    }

    func closeDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
    }

    private static func unsupported(_ what: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 3,
                userInfo: [NSLocalizedDescriptionKey: what])
    }
}
