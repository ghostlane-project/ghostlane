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

    /// Wi-Fi has priority. Among cellular bearers, prefer pdp_ip0 when it
    /// routes this family. On LTE iOS reported pdp_ip0 as its active path,
    /// while a UDP connect probe also accepted pdp_ip1; every TCP dial pinned
    /// to pdp_ip1 then failed with "no route to host". A UDP route probe does
    /// not prove that a bearer carries ordinary internet traffic.
    ///
    /// A family nobody routes still gets an interface. Sockets of that family
    /// are not all bound for the internet: a dial to 127.0.0.1 is AF_INET on
    /// an IPv6-only network too, and every core reaches the others through
    /// loopback, so refusing it would refuse the tunnel. Whatever reaches out
    /// at all comes first, else the first candidate: a dial to the internet
    /// then fails fast with "no route" instead of hanging in our own tun.
    static func choose(from candidates: [PhysicalInterface], family: Int32, preferredName: String? = nil) -> PhysicalInterface? {
        guard family == AF_INET || family == AF_INET6 else { return nil }
        if let preferredName,
           let preferred = candidates.first(where: { $0.name == preferredName && $0.routes(family) }) {
            return preferred
        }
        return candidates.first(where: { $0.name.hasPrefix("en") && $0.routes(family) })
            ?? candidates.first(where: { $0.name == "pdp_ip0" && $0.routes(family) })
            ?? candidates.first(where: { $0.routes(family) })
            ?? candidates.first(where: \.reachesInternet)
            ?? candidates.first
    }
}
