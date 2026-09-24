import Darwin

/// A local routing probe, not an end-to-end reachability guarantee.
struct PhysicalInterface: Equatable {
    let name: String
    let index: UInt32
    let routesIPv4: Bool
    let routesIPv6: Bool

    var summary: String {
        "\(name)[\(routesIPv4 ? "4" : "-")\(routesIPv6 ? "6" : "-")]"
    }

    /// Whether a socket of this family bound here can pick a source address
    /// for a public destination.
    func routes(_ family: Int32) -> Bool {
        switch family {
        case AF_INET: return routesIPv4
        case AF_INET6: return routesIPv6
        default: return false
        }
    }

    var reachesInternet: Bool { routesIPv4 || routesIPv6 }

    /// The interface the system itself routes through comes first, when it
    /// has a route for this socket's family: `system` is the provider's path
    /// monitor's list of interfaces, most preferred first, and empty until the
    /// monitor has answered. The route probe is local, so it cannot tell the
    /// internet bearer from a carrier's service bearer that has a default route
    /// of its own: on one LTE network `pdp_ip1` and `pdp_ip0` both passed the
    /// IPv4 probe, `pdp_ip1` was listed first, and every socket bound to it met
    /// "no route to host" while the system's path named `pdp_ip0` (#58).
    ///
    /// Otherwise candidates are in `ordered` order, Wi-Fi before cellular, and
    /// the first with a route for this socket's family wins, so an IPv4 socket
    /// is never pinned to a bearer that only carries IPv6 and vice versa — the
    /// split that produced "no route to host" and "network is unreachable"
    /// together (#17).
    ///
    /// A family nobody routes still gets an interface. Sockets of that family
    /// are not all bound for the internet: a dial to 127.0.0.1 is AF_INET on
    /// an IPv6-only network too, and every core reaches the others through
    /// loopback, so refusing it would refuse the tunnel. Whatever reaches out
    /// at all comes first, else the first candidate: a dial to the internet
    /// then fails fast with "no route" instead of hanging in our own tun.
    static func choose(
        from candidates: [PhysicalInterface],
        family: Int32,
        system: [String] = []
    ) -> PhysicalInterface? {
        guard family == AF_INET || family == AF_INET6 else { return nil }
        for name in system {
            if let used = candidates.first(where: { $0.name == name && $0.routes(family) }) {
                return used
            }
        }
        return candidates.first(where: { $0.routes(family) })
            ?? candidates.first(where: \.reachesInternet)
            ?? candidates.first
    }

    /// The order candidates are tried in when the system has not named one:
    /// Wi-Fi (`en*`) ahead of cellular (`pdp_ip*`), and cellular bearers by
    /// their number rather than the order `getifaddrs` happens to list them.
    /// On both phones we have traces from (#17, #58) `pdp_ip0` was the
    /// internet bearer and the carrier's other bearers took the numbers after
    /// it, while `getifaddrs` listed one of those first. Only a fallback: it
    /// decides a socket opened before the path monitor's first answer, or a
    /// family the system's interface has no route for. Names of any other
    /// kind go last; ties keep their listed order.
    static func ordered(_ names: [String]) -> [String] {
        func rank(_ name: String) -> (kind: Int, number: Int) {
            if name.hasPrefix("en") { return (0, 0) }
            if name.hasPrefix("pdp_ip") { return (1, Int(name.dropFirst("pdp_ip".count)) ?? Int.max) }
            return (2, 0)
        }
        return names.enumerated()
            .sorted { lhs, rhs in
                let (a, b) = (rank(lhs.element), rank(rhs.element))
                return (a.kind, a.number, lhs.offset) < (b.kind, b.number, rhs.offset)
            }
            .map(\.element)
    }
}
