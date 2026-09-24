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

        // On LTE, both bearers passed the route probe but only pdp_ip0 was
        // reported by iOS as the active cellular path. getifaddrs listed the
        // broken pdp_ip1 first, leading to ENETUNREACH on every TCP dial.
        let misleading4 = PhysicalInterface(name: "pdp_ip1", index: 5, routesIPv4: true, routesIPv6: false)
        precondition(PhysicalInterface.choose(from: [misleading4, cellular4], family: AF_INET) == cellular4)
        precondition(PhysicalInterface.choose(from: [wifi, misleading4, cellular4], family: AF_INET) == wifi)
        precondition(PhysicalInterface.choose(from: [wifi, misleading4, cellular4], family: AF_INET, preferredName: "pdp_ip0") == cellular4)
        precondition(PhysicalInterface.choose(from: [misleading4, cellular4], family: AF_INET, preferredName: "pdp_ip1") == misleading4)
        precondition(PhysicalInterface.choose(from: [cellular6, cellular4], family: AF_INET, preferredName: "pdp_ip1") == cellular4)

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
        print("PhysicalInterface selection: 16 checks passed")
    }
}
