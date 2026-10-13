// The kill switch: what holds the machine's traffic when the tunnel is gone.
//
// A tun that dies takes its interface and its routes with it, and the machine's
// traffic goes straight out by the default route that was under them, at its
// own address, at once. Measured on macOS 26: a request made a second after
// the tunnel's process was killed was answered directly. Nothing in the routing
// table can hold that, because the routes are the tunnel's and go with it.
//
// What stays is the packet filter. Its rules are the kernel's, not a
// process's: loaded once, they stand until somebody takes them away or the
// machine restarts. So the block is a set of pf rules that lets out only what
// belongs to the tunnel, loaded before the tunnel starts and left alone when
// it dies:
//
//   - whatever is routed into the tunnel's interface, which is named in the
//     tunnel's config so that the rule can name it before it exists;
//   - what the tunnel's own sing-box sends, told by the group it runs in
//     (GroupExec). That is every line's way out: a line's binary leaves the tun
//     by sing-box's rule on its program, through sing-box's own socket;
//   - the addresses the tun was told to keep out of itself, the first line's
//     server: its core dials them straight from the user's own process;
//   - the loopback, the lease (DHCP), and the local network, names aside:
//     port 53 is refused beside the tunnel wherever it goes, so no name is
//     asked of the router while the block stands.
//
// The rules live in an anchor under `com.apple/`, which the system's own main
// ruleset hands over to (`anchor "com.apple/*"`), so nothing of the system's is
// rewritten to make them count. Its name sorts before Apple's own anchors: the
// rules are `quick`, and the first one that matches decides.
//
// What takes the block away: a stop that asks for it (Disconnect, or the
// switch turned off), this daemon being told to end (launchd's SIGTERM: a
// daemon that is being removed must not leave a Mac with no network and
// nobody to ask), and a restart of the machine.
import Foundation

struct HoldRequest {
    /// The utun the tunnel's config names for itself.
    let interface: String
    /// Addresses let out beside the tunnel: the server the tun keeps out of itself.
    let passes: [String]

    /// Reads the `hold` of a start request. Everything in it ends up in a pf
    /// rule, so it is an interface's name and addresses, and nothing else is
    /// let through to be one.
    static func parse(_ value: Any?) throws -> HoldRequest? {
        guard let value else { return nil }
        guard let object = value as? [String: Any], let interface = object["interface"] as? String else {
            throw DaemonError.message("a hold names the tunnel's interface")
        }
        guard interface.count <= 8,
              !interface.isEmpty else {
            throw DaemonError.message("the tunnel's interface is not a utun")
        }
        let passes = object["pass"] as? [String] ?? []
        guard passes.count <= 64, passes.allSatisfy(isAddress) else {
            throw DaemonError.message("a hold lets out addresses, written as addresses")
        }
        return HoldRequest(interface: interface, passes: passes)
    }

    /// An IPv4 or IPv6 address as the system itself reads one, and nothing after it.
    static func isAddress(_ text: String) -> Bool {
        var v4 = in_addr()
        var v6 = in6_addr()
        return inet_pton(AF_INET, text, &v4) == 1 || inet_pton(AF_INET6, text, &v6) == 1
    }
}

final class TrafficHold {
    /// The group the tunnel's sing-box runs in. No group has this number; the
    /// rule and the process both name the number.
    static let group: gid_t = 7_302_243
    static let anchor = "com.apple/100.org.olcbox.app.hold"

    /// What `pfctl -E` handed back for the block: pf is switched on by
    /// reference, and switched off again only by whoever holds the last one.
    private var token: String?

    /// Whether the block stands, as far as this process knows. Asked of pf
    /// when the daemon starts: the rules outlive it.
    private(set) var stands: Bool

    init() {
        stands = Self.rulesStand()
    }

    static func rules(for request: HoldRequest) -> String {
        var lines = [
            // Without state: with it, the answer to a connection made over the
            // loopback is weighed as a new packet going out, and refused.
            "pass out quick on lo0 all flags any no state",
            // Both ways and without state: what is inside the tunnel is the
            // tunnel's to track.
            "pass quick on \(request.interface) all flags any no state",
            "pass out quick proto { tcp, udp } from any to any group \(group)",
            "pass out quick inet proto udp from any port 68 to any port 67",
            "pass out quick inet6 proto udp from any port 546 to any port 547",
        ]
        let v4 = request.passes.filter { !$0.contains(":") }
        let v6 = request.passes.filter { $0.contains(":") }
        if !v4.isEmpty { lines.append("pass out quick inet from any to { \(v4.joined(separator: ", ")) }") }
        if !v6.isEmpty { lines.append("pass out quick inet6 from any to { \(v6.joined(separator: ", ")) }") }
        lines += [
            // Before the local network is let out: the router is on it.
            "pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }",
            "pass out quick inet6 from any to { fe80::/10, fc00::/7, ff02::/16 }",
            // `return`, not `drop`: a program that is refused says so at once
            // instead of waiting out its own timeout.
            "block return out quick all",
        ]
        return lines.joined(separator: "\n") + "\n"
    }

    /// Loads the block, in place of whatever block stood before, in one step
    /// of pf's own: there is no moment between the two when neither stands.
    func arm(_ request: HoldRequest) throws {
        if token == nil {
            token = Self.pfctl(["-E"]).output
                .split(separator: "\n")
                .first { $0.contains("Token") }
                .flatMap { $0.split(separator: ":").last }
                .map { $0.trimmingCharacters(in: .whitespaces) }
        }
        let loaded = Self.pfctl(["-a", Self.anchor, "-f", "-"], input: Self.rules(for: request))
        guard loaded.status == 0 else {
            release()
            throw DaemonError.message("the kill switch's rules were refused by pf: \(loaded.output.suffix(300))")
        }
        // Loaded is not in force. pf may be off, and a main ruleset somebody
        // replaced may hand nothing over to this anchor.
        guard Self.pfctl(["-s", "info"]).output.contains("Status: Enabled") else {
            release()
            throw DaemonError.message("the kill switch could not be put in place: pf is not enabled")
        }
        guard Self.pfctl(["-s", "rules"]).output.contains("anchor \"com.apple/*\"") else {
            release()
            throw DaemonError.message(
                "the kill switch could not be put in place: pf's main rules do not hand over to com.apple anchors"
            )
        }
        guard Self.rulesStand() else {
            release()
            throw DaemonError.message("the kill switch could not be put in place: its rules are not there after loading")
        }
        stands = true
    }

    func release() {
        _ = Self.pfctl(["-a", Self.anchor, "-F", "all"])
        if let token {
            _ = Self.pfctl(["-X", token])
            self.token = nil
        }
        stands = false
    }

    /// Asks pf itself, and remembers the answer.
    @discardableResult
    func look() -> Bool {
        stands = Self.rulesStand()
        return stands
    }

    private static func rulesStand() -> Bool {
        pfctl(["-a", anchor, "-s", "rules"]).output
            .split(separator: "\n")
            .contains { $0.hasPrefix("block") }
    }

    private static func pfctl(_ arguments: [String], input: String? = nil) -> (status: Int32, output: String) {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/sbin/pfctl")
        task.arguments = arguments
        let output = Pipe()
        task.standardOutput = output
        task.standardError = output
        let stdin = Pipe()
        task.standardInput = stdin
        do {
            try task.run()
        } catch {
            return (-1, error.localizedDescription)
        }
        if let input {
            stdin.fileHandleForWriting.write(Data(input.utf8))
        }
        stdin.fileHandleForWriting.closeFile()
        let data = output.fileHandleForReading.readDataToEndOfFile()
        task.waitUntilExit()
        return (task.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }
}
