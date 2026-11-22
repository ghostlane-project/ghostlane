// The tunnel's process the daemon runs, and where it is allowed to run it from:
// sing-box with a config of the app's (the first four protocols), or hev with a
// config this daemon writes from the fields of a `tun` request (the fifth).
//
// Never from a path the client sends, and never from a directory a non-root user
// can write. /Applications is writable by any admin, so a binary verified there
// and executed there can be swapped between the two; the bundled program is
// therefore copied into a root-owned directory, verified where it then lies,
// and executed from there. Without that, this daemon is a way for any admin to
// run their own code as root.
import Foundation

enum DaemonError: Error {
    case message(String)

    var text: String {
        if case let .message(value) = self { return value }
        return "unknown error"
    }
}

/// What a `tun` request asks of hev, read field by field (protocol 5).
///
/// Never a config: hev's own config can name programs it runs as its tun goes
/// up and down (`post-up-script`, `pre-down-script`), and this daemon runs hev
/// as root. So the app sends fields, each read as what it is (an interface's
/// name, an address, a number, a login), and the daemon writes hev's config
/// from them. A field it does not know is refused, and one that names a
/// script is refused by its name, wherever in the request it stands.
struct HevRequest {
    struct MapDns {
        let address: String
        let port: Int
        let network: String
        let netmask: String
        let cacheSize: Int
    }

    let name: String
    let mtu: Int
    let ipv4: String
    let ipv6: String?
    let socksAddress: String
    let socksPort: Int
    let login: (username: String, password: String)?
    let mapdns: MapDns?

    /// The fields a `tun` request may carry, by the object they stand in.
    static let fields: [String: Set<String>] = [
        "": ["verb", "tun", "socks", "mapdns", "names", "hold"],
        "tun": ["name", "mtu", "ipv4", "ipv6"],
        "socks": ["address", "port", "username", "password"],
        "mapdns": ["address", "port", "network", "netmask", "cacheSize"],
    ]

    static func parse(_ request: [String: Any]) throws -> HevRequest {
        if let script = scriptKey(in: request, at: "") {
            throw DaemonError.message("hev runs no script of anybody's: \(script) is refused")
        }
        try only(in: request, at: "")
        guard let tun = request["tun"] as? [String: Any] else {
            throw DaemonError.message("a tun request names its tun")
        }
        try only(in: tun, at: "tun")
        guard let socks = request["socks"] as? [String: Any] else {
            throw DaemonError.message("a tun request names the SOCKS port hev hands what it takes to")
        }
        try only(in: socks, at: "socks")

        guard let name = tun["name"] as? String, isUtun(name) else {
            throw DaemonError.message("the tunnel's interface is not a utun")
        }
        let mtu = try number(tun["mtu"], or: 1500, in: 1280...65535, "the tun's mtu")
        guard let ipv4 = tun["ipv4"] as? String, isIPv4(ipv4) else {
            throw DaemonError.message("the tun's ipv4 is an IPv4 address")
        }
        var ipv6: String?
        if let value = tun["ipv6"] {
            guard let text = value as? String, isIPv6(text) else {
                throw DaemonError.message("the tun's ipv6 is an IPv6 address")
            }
            ipv6 = text
        }

        // hev is root's and, under the block, in the group the block lets
        // out: what it hands on goes to this machine and nowhere else.
        guard let socksAddress = socks["address"] as? String, isLoopback(socksAddress) else {
            throw DaemonError.message("hev hands what it takes to a SOCKS port on the loopback")
        }
        let socksPort = try number(socks["port"], or: nil, in: 1...65535, "the SOCKS port")
        var login: (username: String, password: String)?
        if socks["username"] != nil || socks["password"] != nil {
            guard let username = socks["username"] as? String, let password = socks["password"] as? String,
                  isLoginText(username), isLoginText(password) else {
                throw DaemonError.message(
                    "a SOCKS login is a username and a password, each of 1 to 255 bytes with no control character"
                )
            }
            login = (username, password)
        }

        var mapdns: MapDns?
        if let value = request["mapdns"] {
            guard let dns = value as? [String: Any] else {
                throw DaemonError.message("mapdns is an object")
            }
            try only(in: dns, at: "mapdns")
            // hev hands out the network's addresses by number, one a name:
            // the network sits on its mask, and the names fit in what is under it.
            guard let address = dns["address"] as? String, isIPv4(address),
                  let network = dns["network"] as? String, let net = ipv4Bits(network),
                  let netmask = dns["netmask"] as? String, let mask = maskBits(netmask),
                  net & ~mask == 0, ~mask >= 255 else {
                throw DaemonError.message(
                    "mapdns answers at an IPv4 address and hands out the addresses of an IPv4 network under its mask"
                )
            }
            let port = try number(dns["port"], or: 53, in: 1...65535, "mapdns's port")
            let room = Int(min(~mask, 1_000_000))
            let cacheSize = try number(dns["cacheSize"], or: min(10_000, room), in: 1...room, "mapdns's cache size")
            mapdns = MapDns(address: address, port: port, network: network, netmask: netmask, cacheSize: cacheSize)
        }

        return HevRequest(
            name: name, mtu: mtu, ipv4: ipv4, ipv6: ipv6,
            socksAddress: socksAddress, socksPort: socksPort, login: login, mapdns: mapdns
        )
    }

    /// hev's config, every value of it one of the request's fields as read
    /// above, each string quoted: hev reads it with libyaml.
    var yaml: String {
        var lines = [
            "tunnel:",
            "  name: \(Self.quoted(name))",
            "  mtu: \(mtu)",
            "  multi-queue: false",
            "  ipv4: \(Self.quoted(ipv4))",
        ]
        if let ipv6 { lines.append("  ipv6: \(Self.quoted(ipv6))") }
        lines += [
            "",
            "socks5:",
            "  address: \(Self.quoted(socksAddress))",
            "  port: \(socksPort)",
            // Standard UDP ASSOCIATE: hev's 'tcp' is a command of its own,
            // which no engine behind this port speaks.
            "  udp: 'udp'",
            "  pipeline: false",
        ]
        if let login {
            lines += [
                "  username: \(Self.quoted(login.username))",
                "  password: \(Self.quoted(login.password))",
            ]
        }
        if let mapdns {
            lines += [
                "",
                "mapdns:",
                "  address: \(Self.quoted(mapdns.address))",
                "  port: \(mapdns.port)",
                "  network: \(Self.quoted(mapdns.network))",
                "  netmask: \(Self.quoted(mapdns.netmask))",
                "  cache-size: \(mapdns.cacheSize)",
            ]
        }
        // As the Linux desktop and the iPhone run it, and no pid-file: with
        // one hev leaves as a daemon of its own, out of this one's hands.
        lines += [
            "",
            "misc:",
            "  task-stack-size: 24576",
            "  tcp-buffer-size: 4096",
            "  max-session-count: 1200",
            "  connect-timeout: 10000",
            "  tcp-read-write-timeout: 300000",
            "  udp-read-write-timeout: 60000",
            "  log-file: stderr",
            "  log-level: warn",
        ]
        return lines.joined(separator: "\n") + "\n"
    }

    /// A key that names a script, wherever it stands in [value].
    private static func scriptKey(in value: Any, at place: String) -> String? {
        if let object = value as? [String: Any] {
            for key in object.keys.sorted() {
                let here = place.isEmpty ? key : "\(place).\(key)"
                if key.lowercased().contains("script") { return here }
                if let inner = object[key], let found = scriptKey(in: inner, at: here) { return found }
            }
        } else if let list = value as? [Any] {
            for (index, item) in list.enumerated() {
                if let found = scriptKey(in: item, at: "\(place)[\(index)]") { return found }
            }
        }
        return nil
    }

    private static func only(in object: [String: Any], at place: String) throws {
        let known = fields[place] ?? []
        for key in object.keys.sorted() where !known.contains(key) {
            throw DaemonError.message("a tun request has no field \(place.isEmpty ? key : "\(place).\(key)")")
        }
    }

    private static func number(_ value: Any?, or fallback: Int?, in range: ClosedRange<Int>, _ what: String) throws -> Int {
        guard let value else {
            if let fallback { return fallback }
            throw DaemonError.message("\(what) is missing")
        }
        guard let number = value as? Int, range.contains(number) else {
            throw DaemonError.message("\(what) is a number from \(range.lowerBound) to \(range.upperBound)")
        }
        return number
    }

    static func isUtun(_ name: String) -> Bool {
        guard name.hasPrefix("utun"), name.count <= 7, let number = Int(name.dropFirst(4)), number >= 0 else {
            return false
        }
        return name == "utun\(number)"
    }

    static func isIPv4(_ text: String) -> Bool { ipv4Bits(text) != nil }

    static func isIPv6(_ text: String) -> Bool {
        var address = in6_addr()
        return inet_pton(AF_INET6, text, &address) == 1
    }

    /// The address as a number, the first octet highest.
    static func ipv4Bits(_ text: String) -> UInt32? {
        var address = in_addr()
        guard inet_pton(AF_INET, text, &address) == 1 else { return nil }
        return UInt32(bigEndian: address.s_addr)
    }

    /// A mask of ones from the top and zeros after them; nil for anything else.
    static func maskBits(_ text: String) -> UInt32? {
        guard let mask = ipv4Bits(text), mask != 0 else { return nil }
        let rest = ~mask
        return rest & (rest &+ 1) == 0 ? mask : nil
    }

    static func isLoopback(_ text: String) -> Bool {
        text == "::1" || (isIPv4(text) && text.hasPrefix("127."))
    }

    /// Bytes enough for hev, and no character that ends a line or a quote for libyaml.
    static func isLoginText(_ text: String) -> Bool {
        let size = text.utf8.count
        guard size >= 1, size <= 255 else { return false }
        return !text.unicodeScalars.contains {
            $0.value < 0x20 || (0x7F...0x9F).contains($0.value) || $0.value == 0x2028 || $0.value == 0x2029
        }
    }

    private static func quoted(_ text: String) -> String {
        "'" + text.replacingOccurrences(of: "'", with: "''") + "'"
    }
}

final class TunnelChild {
    enum Kind: String {
        case singBox = "sing-box"
        case hev
    }

    static let stateDir = URL(
        fileURLWithPath: "/Library/Application Support/org.olcbox.app",
        isDirectory: true
    )

    /// Whether a binary lying in the daemon's own directory is one this team
    /// signed. A parameter because a Mac that is only asked what it does (a CI
    /// runner) has no binary of ours to check; the daemon itself never passes one.
    private let isTrusted: (URL) -> Bool

    /// The programs to copy when they are not the bundle's own; as above, nil in the daemon.
    private let coreSource: URL?
    private let hevSource: URL?

    /// Called, on a thread of its own, when the child ends without having been
    /// told to: the tunnel died.
    var onDeath: (() -> Void)?

    private var process: Process?
    private var running: Kind?
    private var tail: [String] = []
    private let lock = NSLock()

    init(coreSource: URL? = nil, hevSource: URL? = nil, isTrusted: @escaping (URL) -> Bool = PeerAuthority.isTrustedBinary) {
        self.coreSource = coreSource
        self.hevSource = hevSource
        self.isTrusted = isTrusted
    }

    var isRunning: Bool { locked { process?.isRunning ?? false } }

    var pid: Int32? { locked { process?.isRunning == true ? process?.processIdentifier : nil } }

    /// Which of the two runs, if one does.
    var kind: Kind? { locked { process?.isRunning == true ? running : nil } }

    var logTail: String { locked { tail.suffix(40).joined(separator: "\n") } }

    /// A program the bundle carries, found relative to this binary: the daemon
    /// lives at `ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon` and the
    /// programs at `ProofKit.app/Contents/Resources/<name>`.
    ///
    /// `Bundle.main.executableURL` rather than `CommandLine.arguments[0]`: launchd
    /// does not promise argv[0] is an absolute path, and a relative one walked two
    /// directories up lands somewhere arbitrary — where the copy step would fail
    /// with a path that says nothing about why.
    private func bundled(_ name: String) -> URL {
        let executable = Bundle.main.executableURL
            ?? URL(fileURLWithPath: CommandLine.arguments[0])
        return executable
            .resolvingSymlinksInPath()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/\(name)")
    }

    /// This binary, for the step it makes on the child's way to a group of its own (GroupExec).
    private var ownExecutable: URL {
        (Bundle.main.executableURL ?? URL(fileURLWithPath: CommandLine.arguments[0])).resolvingSymlinksInPath()
    }

    /// The daemon's own copy of this binary, in its own directory: the step a
    /// child takes on its way to a group (GroupExec). For the tunnel here, and
    /// for a line's program (LineChild).
    func step() throws -> URL {
        let binDir = try binDirectory()
        return try install(ownExecutable, as: "as-group", in: binDir)
    }

    /// [group] is the group the child runs in, for a tunnel started under the
    /// kill switch's block (TrafficHold); nil leaves it in root's own, as ever.
    func start(config: String, files: [String: String] = [:], group: gid_t? = nil) throws {
        stop()

        let source = coreSource ?? bundled("sing-box")
        guard FileManager.default.isReadableFile(atPath: source.path) else {
            throw DaemonError.message("no bundled sing-box at \(source.path)")
        }

        let binDir = try binDirectory()
        let core = try install(source, as: "sing-box", in: binDir)

        // The rule-sets a Bypass Russia config names, root-owned like the
        // config, at the paths the app wrote into it. Written or removed, never
        // left behind: a config naming a file that is not there stops sing-box
        // with "no such file", and a file nothing names is dead weight.
        let rulesDir = Self.stateDir.appendingPathComponent("rules", isDirectory: true)
        if files.isEmpty {
            try? FileManager.default.removeItem(at: rulesDir)
        } else {
            try makeRootOnlyDirectory(rulesDir)
            for (name, base64) in files {
                guard !name.contains("/"), !name.hasPrefix("."),
                      let data = Data(base64Encoded: base64) else {
                    throw DaemonError.message("rule-set \(name) is not a plain file name with base64 content")
                }
                let url = rulesDir.appendingPathComponent(name)
                try data.write(to: url, options: .atomic)
                try FileManager.default.setAttributes(
                    [.posixPermissions: 0o600, .ownerAccountID: 0],
                    ofItemAtPath: url.path
                )
            }
        }

        let configURL = Self.stateDir.appendingPathComponent("tun.json")
        try writeRootOnly(config, to: configURL)
        try run(core, ["run", "-c", configURL.path], group: group, from: binDir, as: .singBox)
    }

    /// hev, with the config [request] reads as. It opens the utun the request
    /// names and gives it its addresses; the routes are the daemon's.
    func startHev(_ request: HevRequest, group: gid_t? = nil) throws {
        stop()

        let source = hevSource ?? bundled("hev-socks5-tunnel")
        guard FileManager.default.isReadableFile(atPath: source.path) else {
            throw DaemonError.message("no bundled hev-socks5-tunnel at \(source.path)")
        }

        let binDir = try binDirectory()
        let hev = try install(source, as: "hev-socks5-tunnel", in: binDir)
        // Root's and nobody else's to read: it holds the SOCKS port's login.
        let configURL = Self.stateDir.appendingPathComponent("hev.yml")
        try writeRootOnly(request.yaml, to: configURL)
        try run(hev, [configURL.path], group: group, from: binDir, as: .hev)
    }

    /// Waits until [name] is up holding [ipv4], which hev sees to before it
    /// runs, for as long as hev runs and [seconds] at most.
    func waitUntilUp(_ name: String, holding ipv4: String, seconds: Double) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while isRunning && Date() < deadline {
            if Self.isUp(name, holding: ipv4) { return true }
            usleep(50_000)
        }
        return isRunning && Self.isUp(name, holding: ipv4)
    }

    static func isUp(_ name: String, holding ipv4: String) -> Bool {
        var wanted = in_addr()
        guard inet_pton(AF_INET, ipv4, &wanted) == 1 else { return false }
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return false }
        defer { freeifaddrs(first) }
        var next = first
        while let entry = next {
            next = entry.pointee.ifa_next
            guard String(cString: entry.pointee.ifa_name) == name,
                  entry.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let found = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            if found == wanted.s_addr { return true }
        }
        return false
    }

    private func run(_ program: URL, _ arguments: [String], group: gid_t?, from binDir: URL, as kind: Kind) throws {
        let task = Process()
        if let group {
            let step = try install(ownExecutable, as: "as-group", in: binDir)
            task.executableURL = step
            task.arguments = [GroupExec.flag, String(group), program.path] + arguments
        } else {
            task.executableURL = program
            task.arguments = arguments
        }
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let self else { return }
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            self.locked {
                self.tail.append(contentsOf: text.split(separator: "\n").map(String.init))
                if self.tail.count > 200 { self.tail.removeFirst(self.tail.count - 200) }
            }
        }
        task.terminationHandler = { [weak self] ended in self?.ended(ended) }
        // Before the run: what a child says in its first moment (hev, that
        // its utun is taken) is what a failed start has to report.
        locked { self.tail = [] }
        try task.run()
        locked {
            self.process = task
            self.running = kind
        }
    }

    /// A child is gone. Unless a stop took it first, nobody asked it to go.
    private func ended(_ task: Process) {
        let unasked: Bool = locked {
            guard process === task else { return false }
            process = nil
            running = nil
            return true
        }
        if unasked { onDeath?() }
    }

    /// Copies [source] into the daemon's own directory and only then asks
    /// whose it is: what was checked is then the file that is run, in a place
    /// nobody but root can put another. A link is not followed into one: what
    /// lies there afterwards has to be a file.
    private func install(_ source: URL, as name: String, in directory: URL) throws -> URL {
        let target = directory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.copyItem(at: source, to: target)
        let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            try? FileManager.default.removeItem(at: target)
            throw DaemonError.message("\(source.path) is not a file")
        }
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: target.path
        )
        guard isTrusted(target) else {
            try? FileManager.default.removeItem(at: target)
            throw DaemonError.message("\(source.lastPathComponent) is not signed by this team")
        }
        return target
    }

    func stop() {
        let stopping: (task: Process?, kind: Kind?) = locked {
            defer {
                process = nil
                running = nil
            }
            return process?.isRunning == true ? (process, running) : (nil, nil)
        }
        guard let task = stopping.task else { return }
        // A stop, never SIGKILL first. sing-box takes its routes out itself on
        // SIGTERM. hev ends on SIGINT, closing its utun (its SIGTERM is the
        // default one, an end with no step of its own). Killed outright either
        // leaves nothing behind (the utun and every route through it go with
        // the process, measured on macOS 26), but that is the kernel tidying up
        // after a death, and a stop should not look like one.
        if stopping.kind == .hev {
            task.interrupt()
        } else {
            task.terminate()
        }
        let deadline = Date().addingTimeInterval(5)
        while task.isRunning && Date() < deadline { usleep(100_000) }
        if task.isRunning { kill(task.processIdentifier, SIGKILL) }
    }

    private func binDirectory() throws -> URL {
        try makeRootOnlyDirectory(Self.stateDir)
        let binDir = Self.stateDir.appendingPathComponent("bin", isDirectory: true)
        try makeRootOnlyDirectory(binDir)
        return binDir
    }

    private func writeRootOnly(_ text: String, to url: URL) throws {
        try text.write(to: url, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600, .ownerAccountID: 0],
            ofItemAtPath: url.path
        )
    }

    private func makeRootOnlyDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(
            at: url,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .ownerAccountID: 0]
        )
        // createDirectory leaves an existing directory's attributes alone, and an
        // existing one is the common case after the first run.
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: url.path
        )
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
