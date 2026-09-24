import Darwin

/// Compiled with the production selector by scripts/test-ios-interface-selection.sh.
/// No Cores framework, signing identity or device is needed for these policy tests.
@main
enum PhysicalInterfaceSelectionTests {
    static func main() {
        let cellular6 = PhysicalInterface(name: "pdp_ip1", index: 5, routesIPv4: false, routesIPv6: true)
        let cellular4 = PhysicalInterface(name: "pdp_ip0", index: 4, routesIPv4: true, routesIPv6: false)
        let secondary4 = PhysicalInterface(name: "pdp_ip2", index: 6, routesIPv4: true, routesIPv6: false)
        let wifi = PhysicalInterface(name: "en0", index: 3, routesIPv4: true, routesIPv6: true)
        let offline = PhysicalInterface(name: "en0", index: 3, routesIPv4: false, routesIPv6: false)
        let observed = [cellular6, cellular4, secondary4]

        // The device regression: the first IPv6 route must not capture IPv4.
        precondition(PhysicalInterface.choose(from: observed, family: AF_INET) == cellular4)
        precondition(PhysicalInterface.choose(from: observed, family: AF_INET6) == cellular6)

        // Preserve candidate priority within each family, including on Wi-Fi.
        precondition(PhysicalInterface.choose(from: [wifi] + observed, family: AF_INET) == wifi)
        precondition(PhysicalInterface.choose(from: [wifi] + observed, family: AF_INET6) == wifi)
        precondition(PhysicalInterface.choose(from: [offline] + observed, family: AF_INET) == cellular4)

        // A family nobody routes still gets an interface: a socket to 127.0.0.1
        // is AF_INET on an IPv6-only network too, and refusing it would refuse
        // the dial to our own SOCKS port. Prefer whatever reaches out at all,
        // else the first candidate, so a dial to the internet fails fast with
        // "no route" instead of hanging in our own tun unpinned.
        precondition(PhysicalInterface.choose(from: [cellular6], family: AF_INET) == cellular6)
        precondition(PhysicalInterface.choose(from: [cellular4], family: AF_INET6) == cellular4)
        precondition(PhysicalInterface.choose(from: [offline, cellular6], family: AF_INET) == cellular6)
        precondition(PhysicalInterface.choose(from: [offline], family: AF_INET) == offline)
        precondition(PhysicalInterface.choose(from: [], family: AF_INET6) == nil)
        precondition(PhysicalInterface.choose(from: [wifi], family: AF_UNIX) == nil)

        // #58, the device trace: two cellular bearers pass the IPv4 probe,
        // getifaddrs lists the carrier's service bearer first, and the
        // system's path names pdp_ip0 (twice, as NWPath listed it).
        let service4 = PhysicalInterface(name: "pdp_ip1", index: 7, routesIPv4: true, routesIPv6: false)
        let internet4 = PhysicalInterface(name: "pdp_ip0", index: 4, routesIPv4: true, routesIPv6: false)
        let service6 = PhysicalInterface(name: "pdp_ip3", index: 9, routesIPv4: false, routesIPv6: true)
        let lte = [service4, internet4, service6]
        let lteSystem = ["pdp_ip0", "pdp_ip0"]
        precondition(PhysicalInterface.choose(from: lte, family: AF_INET, system: lteSystem) == internet4)
        // The system's interface has no IPv6 route: IPv6 keeps the per-family choice.
        precondition(PhysicalInterface.choose(from: lte, family: AF_INET6, system: lteSystem) == service6)
        // A name that is no candidate (our own utun, say) changes nothing.
        precondition(PhysicalInterface.choose(from: lte, family: AF_INET, system: ["utun4"]) == service4)
        // Before the monitor's first answer: bearers by number, so pdp_ip0 first.
        precondition(PhysicalInterface.ordered(lte.map(\.name)) == ["pdp_ip0", "pdp_ip1", "pdp_ip3"])
        let coldStart = PhysicalInterface.ordered(lte.map(\.name)).compactMap { name in lte.first { $0.name == name } }
        precondition(PhysicalInterface.choose(from: coldStart, family: AF_INET) == internet4)
        precondition(PhysicalInterface.choose(from: coldStart, family: AF_INET6) == service6)

        // The system's order is followed, Wi-Fi included: Wi-Fi when it names
        // Wi-Fi first, cellular when it routes around an associated Wi-Fi.
        precondition(PhysicalInterface.choose(from: [wifi] + lte, family: AF_INET, system: ["en0", "pdp_ip0"]) == wifi)
        precondition(PhysicalInterface.choose(from: [wifi] + lte, family: AF_INET, system: ["pdp_ip0"]) == internet4)
        precondition(PhysicalInterface.choose(from: [offline] + lte, family: AF_INET, system: ["en0", "pdp_ip0"]) == internet4)

        // #17's phone is unchanged, whether or not the system has answered.
        precondition(PhysicalInterface.choose(from: observed, family: AF_INET, system: ["pdp_ip0"]) == cellular4)
        precondition(PhysicalInterface.choose(from: observed, family: AF_INET6, system: ["pdp_ip0"]) == cellular6)
        precondition(PhysicalInterface.ordered(observed.map(\.name)) == ["pdp_ip0", "pdp_ip1", "pdp_ip2"])

        // Ordering: Wi-Fi ahead of cellular, numbers compared as numbers,
        // other kinds last, ties in their listed order.
        precondition(PhysicalInterface.ordered(["pdp_ip10", "pdp_ip2", "en2", "utun0", "en0", "pdp_ipx"])
            == ["en2", "en0", "pdp_ip2", "pdp_ip10", "pdp_ipx", "utun0"])
        precondition(PhysicalInterface.ordered([]) == [])
        print("PhysicalInterface selection: 25 checks passed")
    }
}
