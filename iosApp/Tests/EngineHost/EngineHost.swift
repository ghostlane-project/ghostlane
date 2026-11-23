import CryptoKit
import Darwin
import Foundation

/// The packet tunnel extension's engines, run on a Mac.
///
/// scripts/test-ios-engine-on-mac.sh compiles this directory together with
/// the extension's own Swift, unchanged: every file of iosApp/PacketTunnel
/// but the four that need the system's tunnel or a phone
/// (PacketTunnelProvider, HevTunnel, MemoryWatch, MemoryPressure), against
/// the darwin slice of ghostlane-core, the library the extension links,
/// built for the Mac at the same commit. Each command is one process, as a
/// tunnel is one extension process.
///
/// This program stands where the system and hev-socks5-tunnel stand on a
/// phone. It hands the engines what the app writes, in the provider's order
/// (`PacketTunnelProvider.startTunnel`): the network's resolvers
/// (`ResolverSnapshot`), the arrangement (`TunArrangement.decide`), the
/// direct resolver (`DirectResolver.substitute`), then `CoreEngine.start` or
/// `OlcrtcEngine.start`. It then speaks to the engine's SOCKS port as hev
/// does, with the login the arrangement read back from the config.
///
/// What a Mac is not: there is no tun, so hev does not run and its part is
/// played here; the interface the pin binds every engine socket to
/// (`TunPlatform`) is the Mac's; and the library is built for macOS, so its
/// runtime has a desktop's workers and the core sizes its buffers as on a
/// desktop. The byte budget is set to a phone's before an engine starts.
/// The footprint is read as MemoryWatch reads it (`phys_footprint`) and
/// held to the limit the extension states for itself.
@main
enum EngineHost {

    /// B on a phone: what `setByteBudget(0)` returns a phone's process to,
    /// the memory-class formula (MemoryRelief). The darwin slice's formula
    /// is a desktop's.
    static let phoneByteBudget: UInt64 = 3_000_000

    /// B for this process: the phone's, unless ENGINE_HOST_BYTE_BUDGET says
    /// otherwise (0: the darwin slice's own formula), which only the
    /// script's diagnosis of a failed fetch does.
    static let byteBudget: UInt64 = {
        if let text = ProcessInfo.processInfo.environment["ENGINE_HOST_BYTE_BUDGET"], let value = UInt64(text) {
            return value
        }
        return phoneByteBudget
    }()

    static let usage = """
    usage: engine-host start  <core.json> <budget-bytes>
           engine-host refuse <core.json> <word>
           engine-host olcrtc <carrier> <direct-rules-file|-> <budget-bytes> joins|refused
           engine-host fetch  <core.json> <budget-bytes> <down-bytes> <up-bytes> reach|refused <label>
           engine-host x25519
           engine-host pinned-address

    """

    static func main() {
        setvbuf(stdout, nil, _IOLBF, 0)
        let arguments = Array(CommandLine.arguments.dropFirst())
        let rest = Array(arguments.dropFirst())
        do {
            switch arguments.first {
            case "start": try start(rest)
            case "refuse": try refuse(rest)
            case "olcrtc": try olcrtc(rest)
            case "fetch": try fetch(rest)
            case "x25519": x25519()
            case "pinned-address": print(try pinnedAddress())
            default:
                FileHandle.standardError.write(Data(usage.utf8))
                exit(2)
            }
        } catch let failure as HostFailure {
            print("FAIL \(failure.description)")
            exit(1)
        } catch {
            print("FAIL \(error.localizedDescription)")
            exit(1)
        }
        exit(0)
    }

    // MARK: - commands

    /// One core.json, started as the provider starts it; its SOCKS port asks
    /// for the login it names and refuses any other; then stopped, and the
    /// port is let go.
    static func start(_ arguments: [String]) throws {
        let a = try want(arguments, "<core.json>", "<budget-bytes>")
        let name = fileName(a[0])
        let budget = try bytes(a[1])
        let began = Date()
        let socks = try startCore(try read(a[0]))
        let startedIn = Date().timeIntervalSince(began)
        try checkSocks(socks)
        let core = try coreBytes()
        CoreEngine.stop()
        guard !CoreEngine.isRunning else { throw HostFailure("\(name): CoreEngine.stop left a core running") }
        guard waitUntilClosed(socks.port, seconds: 5) else {
            throw HostFailure("\(name): the SOCKS port \(socks.port) still accepts after the stop")
        }
        let memory = try footprint(name, budget: budget)
        try within(name, core)
        print("ok   \(name): started in \(milliseconds(startedIn)), SOCKS on 127.0.0.1:\(socks.port) asks for its login and refuses others; \(memory); \(describe(core))")
    }

    /// A core.json the core must refuse, with the core's own sentence naming
    /// `word`: the starts above can fail.
    static func refuse(_ arguments: [String]) throws {
        let a = try want(arguments, "<core.json>", "<word>")
        let name = fileName(a[0])
        do {
            _ = try startCore(try read(a[0]))
        } catch let failure as HostFailure {
            guard failure.description.contains(a[1]) else {
                throw HostFailure("\(name): refused, but the reason does not name \(a[1]): \(failure.description)")
            }
            guard !CoreEngine.isRunning else { throw HostFailure("\(name): refused, and a core runs") }
            print("ok   \(name): refused, naming \(a[1]): \(failure.description)")
            return
        }
        CoreEngine.stop()
        throw HostFailure("\(name): the core started from a config it must refuse")
    }

    /// OlcrtcEngine as the provider starts it, with the parameters the app
    /// writes (olcrtc.json) and the carrier `none`, whose room is a hub's
    /// URL. No olcRTC server stands in the stand-in room, so the start ends
    /// there: `joins` holds that the engine took every setting, started and
    /// dialed the room through the pin; `refused` that a carrier this build
    /// does not carry is refused, naming it, before any room is dialed.
    static func olcrtc(_ arguments: [String]) throws {
        let a = try want(arguments, "<carrier>", "<direct-rules-file|->", "<budget-bytes>", "joins|refused")
        let carrier = a[0]
        var directRules = ""
        if a[1] != "-" { directRules = try read(a[1]) }
        let budget = try bytes(a[2])
        let expect = a[3]
        guard expect == "joins" || expect == "refused" else { throw HostFailure("expect joins or refused, not \(expect)") }
        let label = "olcRTC \(carrier), \(directRules.isEmpty ? "no direct rules" : "\(directRules.split(separator: "\n").count) direct rules")"

        let reason = "engine-host: no olcRTC server stands in this room"
        // On the address the pin's interface holds: a loopback address is
        // not reachable from a socket bound to it (pinnedAddress).
        let host = try pinnedAddress()
        let hub = try HostHub(host: host, endReason: reason)
        hub.serve()
        defer { hub.stop() }
        let room = "engine-host"
        let socksPort = TunArrangement.defaultSocksPort
        // The fields OlcboxIosApp.olcrtcParameters writes, as it writes them.
        let fields: [String: Any] = [
            "carrierName": carrier,
            "transportName": "datachannel",
            "roomId": "tcp://\(host):\(hub.port)/\(room)",
            "clientId": UUID().uuidString.lowercased(),
            "keyHex": String(repeating: "5a", count: 32),
            "socksPort": socksPort,
            "socksUser": "ios-tun-user",
            "socksPass": "ios-tun-password",
            "vp8Fps": 30,
            "vp8BatchSize": 64,
            "directRules": directRules,
            "dtlsProfile": "chrome-linux-138-compat-v1",
        ]
        let parameters = try JSONDecoder().decode(
            OlcrtcEngine.Parameters.self,
            from: JSONSerialization.data(withJSONObject: fields)
        )
        let arrangement = TunArrangement.decide(
            coreConfig: nil,
            olcrtcSocks: LoopbackSocks(port: parameters.socksPort, username: parameters.socksUser, password: parameters.socksPass)
        )
        guard case .engineBehindHev(.olcrtc, let socks) = arrangement, arrangement.hevUdp == "udp" else {
            throw HostFailure("\(label): the arrangement is \(arrangement.engineLabel), not olcRTC behind hev with UDP")
        }

        setByteBudget(byteBudget)
        let resolvers = ResolverSnapshot.servers()
        let began = Date()
        var thrown: String?
        do {
            try OlcrtcEngine.start(parameters, resolvers: resolvers)
        } catch {
            thrown = error.localizedDescription
        }
        let seconds = Date().timeIntervalSince(began)
        let core = try coreBytes()
        OlcrtcEngine.stop()
        guard !OlcrtcEngine.isRunning else { throw HostFailure("\(label): OlcrtcEngine.stop left the engine running") }
        guard !HostConnection.accepts(socks.port) else {
            throw HostFailure("\(label): the SOCKS port \(socks.port) is left open")
        }
        let joined = hub.joined
        guard let thrown else {
            throw HostFailure("\(label): started with no server in its room")
        }
        let memory = try footprint(label, budget: budget)
        try within(label, core)
        switch expect {
        case "joins":
            guard joined.contains(room) else {
                throw HostFailure("\(label): never reached its room (\(joined.isEmpty ? "no JOIN" : "joined \(joined)")); the start said: \(thrown)")
            }
            print("ok   \(label): every setting taken, the room on \(host) dialed through the pin and joined, and the start ended there in \(milliseconds(seconds)) (no server in it): \(thrown); \(memory); \(describe(core))")
        default:
            guard joined.isEmpty else { throw HostFailure("\(label): refused, but only after joining \(joined)") }
            guard thrown.contains(carrier) else {
                throw HostFailure("\(label): refused, but the reason does not name \(carrier): \(thrown)")
            }
            print("ok   \(label): refused before any room, naming it: \(thrown); \(memory)")
        }
    }

    /// One line through CoreEngine to a local server: 2 MiB down and 1 MiB up
    /// through the SOCKS port as hev would send them, with the footprint and
    /// the core's bytes read while they flow. `refused`: the same line with a
    /// credential, a key or a pin the server does not answer to, or without
    /// the waiver its server's certificate needs, must not reach the page.
    static func fetch(_ arguments: [String]) throws {
        let a = try want(arguments, "<core.json>", "<budget-bytes>", "<down-bytes>", "<up-bytes>", "reach|refused", "<label>")
        let budget = try bytes(a[1])
        let down = Int(try bytes(a[2]))
        let up = Int(try bytes(a[3]))
        let expect = a[4]
        let label = a[5]
        guard expect == "reach" || expect == "refused" else { throw HostFailure("expect reach or refused, not \(expect)") }

        let socks = try startCore(try read(a[0]))
        defer { CoreEngine.stop() }
        let sampler = HostSampler()
        sampler.start()
        let began = Date()
        let outcome: Result<(HostResponse, HostResponse), Error>
        do {
            let downloaded = try HostTunnel.get(socks, path: "/download", limit: down + 64 * 1024,
                                                timeout: expect == "reach" ? 30 : 10)
            let uploaded = try HostTunnel.post(socks, path: "/upload", bytes: up, timeout: 30)
            outcome = .success((downloaded, uploaded))
        } catch {
            outcome = .failure(error)
        }
        let seconds = Date().timeIntervalSince(began)
        let sampled = sampler.stop()
        let core = try coreBytes()

        switch (expect, outcome) {
        case ("reach", .failure(let error)):
            throw HostFailure("\(label): did not reach the page: \(describe(error))")
        case ("reach", .success(let (downloaded, uploaded))):
            guard downloaded.status == 200, downloaded.bodyBytes == down,
                  let sum = downloaded.headers["x-body-sha256"], sum == downloaded.bodySha256 else {
                throw HostFailure("\(label): the download came back as status \(downloaded.status), \(downloaded.bodyBytes) of \(down) bytes, sha256 \(downloaded.bodySha256) for \(downloaded.headers["x-body-sha256"] ?? "none")")
            }
            let heard = String(decoding: uploaded.bodyPrefix, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            guard uploaded.status == 200, heard == uploaded.sentSha256 else {
                throw HostFailure("\(label): the page heard \(heard) for an upload of \(up) bytes hashing to \(uploaded.sentSha256 ?? "none") (status \(uploaded.status))")
            }
            let memory = try footprint(label, budget: budget, sampled: sampled)
            try within(label, core)
            print("ok   \(label): \(down) bytes down and \(up) up in \(milliseconds(seconds)); \(memory); \(describe(core))")
        case (_, .success(let (downloaded, _))) where downloaded.status == 200 && downloaded.bodyBytes > 0:
            throw HostFailure("\(label): reached the page (\(downloaded.bodyBytes) bytes)")
        case (_, .success(let (downloaded, _))):
            print("ok   \(label): nothing came back (status \(downloaded.status), \(downloaded.bodyBytes) bytes)")
        case (_, .failure(let error)):
            print("ok   \(label): refused: \(describe(error))")
        }
    }

    /// An X25519 key pair as Xray writes it: base64url without padding,
    /// private then public, for the local Reality servers.
    static func x25519() {
        let key = Curve25519.KeyAgreement.PrivateKey()
        print("\(base64url(key.rawRepresentation)) \(base64url(key.publicKey.rawRepresentation))")
    }

    /// The IPv4 address of the interface the pin binds the engines' sockets
    /// to (TunPlatform.pinToPhysicalInterface, read back from a socket it
    /// pinned): where the local servers listen. A socket bound to that
    /// interface cannot reach a loopback address (EADDRNOTAVAIL: no source
    /// address of the interface is one for 127/8), and the engines pin every
    /// socket they open.
    static func pinnedAddress() throws -> String {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw HostFailure("socket: \(String(cString: strerror(errno)))") }
        defer { close(fd) }
        guard TunPlatform.pinToPhysicalInterface(fd) else { throw HostFailure("the pin found no physical interface") }
        var index: UInt32 = 0
        var length = socklen_t(MemoryLayout<UInt32>.size)
        // IP_BOUND_IF, as TunPlatform sets it.
        guard getsockopt(fd, IPPROTO_IP, 25, &index, &length) == 0, index != 0 else {
            throw HostFailure("the pinned socket names no interface")
        }
        var name = [CChar](repeating: 0, count: Int(IFNAMSIZ))
        guard if_indextoname(index, &name) != nil else { throw HostFailure("no name for interface \(index)") }
        let interface = String(cString: name)
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { throw HostFailure("getifaddrs failed") }
        defer { freeifaddrs(list) }
        var entry: UnsafeMutablePointer<ifaddrs>? = first
        while let current = entry {
            let item = current.pointee
            if String(cString: item.ifa_name) == interface, let raw = item.ifa_addr, raw.pointee.sa_family == sa_family_t(AF_INET) {
                var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                let address = raw.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
                var copy = address
                if inet_ntop(AF_INET, &copy, &text, socklen_t(text.count)) != nil {
                    return String(cString: text)
                }
            }
            entry = item.ifa_next
        }
        throw HostFailure("\(interface), the pin's interface, has no IPv4 address")
    }

    // MARK: - the provider's start of a core line

    /// The resolvers, the arrangement, the direct resolver, `CoreEngine.start`:
    /// the provider's order up to the engine listening. Returns the SOCKS
    /// server hev would be pointed at.
    static func startCore(_ coreConfig: String) throws -> LoopbackSocks {
        // Before anything else, as the provider reads them before the
        // tunnel's settings go on.
        let resolvers = ResolverSnapshot.servers()
        let arrangement = TunArrangement.decide(coreConfig: coreConfig, olcrtcSocks: nil)
        guard case .engineBehindHev(.core, let socks) = arrangement else {
            throw HostFailure("the arrangement is \(arrangement.engineLabel), not the Rust core behind hev")
        }
        guard arrangement.hevUdp == "tcp", arrangement.engineLabel == "rust+hev" else {
            throw HostFailure("the Rust core's arrangement says udp \(arrangement.hevUdp), label \(arrangement.engineLabel)")
        }
        guard socks.username?.isEmpty == false, socks.password?.isEmpty == false else {
            throw HostFailure("core.json gives hev no login")
        }
        let ready = DirectResolver.substitute(in: coreConfig, resolvers: resolvers)
        guard !ready.contains("\"\(DirectResolver.placeholder)\"") else {
            throw HostFailure("the direct resolver's placeholder is still in core.json")
        }
        setByteBudget(byteBudget)
        do {
            try CoreEngine.start(configJSON: ready)
        } catch {
            throw HostFailure("CoreEngine.start: \(error.localizedDescription)")
        }
        guard CoreEngine.isRunning else { throw HostFailure("CoreEngine.start returned and no core runs") }
        return socks
    }

    /// What hev meets at the port: a greeting that offers no login is turned
    /// away, the login from the config is taken, a wrong password is not.
    static func checkSocks(_ socks: LoopbackSocks) throws {
        let user = socks.username ?? ""
        let pass = socks.password ?? ""
        let open = try HostConnection(port: socks.port, timeout: 5)
        if let method = try open.greet([0x00]), method != 0xFF {
            throw HostFailure("the SOCKS port \(socks.port) lets a client in with no login (method \(method))")
        }
        let good = try HostConnection(port: socks.port, timeout: 5)
        guard try good.greet([0x02]) == 0x02 else {
            throw HostFailure("the SOCKS port \(socks.port) does not ask for the login")
        }
        guard try good.login(user, pass) == 0x00 else {
            throw HostFailure("the SOCKS port \(socks.port) refuses the login its config names")
        }
        let wrong = try HostConnection(port: socks.port, timeout: 5)
        if try wrong.greet([0x02]) == 0x02, try wrong.login(user, pass + "-not-it") == 0x00 {
            throw HostFailure("the SOCKS port \(socks.port) takes a wrong password")
        }
    }

    // MARK: - memory

    /// The process's footprint, its peak and the resident size, held to the
    /// limit: the figure the system kills an extension for is the footprint
    /// (MemoryWatch), so that is the one judged; resident memory, which on a
    /// Mac counts the shared system libraries' pages, is reported beside it.
    static func footprint(_ label: String, budget: UInt64, sampled: UInt64 = 0) throws -> String {
        guard let now = HostMemory.read() else { throw HostFailure("\(label): task_info gave no reading") }
        let peak = max(now.footprintPeak, sampled, now.footprint)
        let text = "footprint \(mib(now.footprint)), peak \(mib(peak)) of a \(mib(budget)) limit, resident peak \(mib(now.residentPeak))"
        guard peak < budget else { throw HostFailure("\(label): over the extension's memory limit: \(text)") }
        return text
    }

    /// The core's own account of its payload bytes (`memoryStats()`):
    /// B, what is held now and the most ever held.
    struct CoreBytes {
        let cap: UInt64
        let held: UInt64
        let peak: UInt64
    }

    static func coreBytes() throws -> CoreBytes {
        let stats = memoryStats()
        guard let data = stats.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let bytes = object["bytes"] as? [String: Any],
              let cap = (bytes["cap"] as? NSNumber)?.uint64Value,
              let held = (bytes["held"] as? NSNumber)?.uint64Value,
              let peak = (bytes["peak"] as? NSNumber)?.uint64Value
        else { throw HostFailure("memoryStats() has no bytes.cap, held and peak: \(stats)") }
        return CoreBytes(cap: cap, held: held, peak: peak)
    }

    /// B is the one set (the phone's), and the core never took more of it.
    static func within(_ label: String, _ core: CoreBytes) throws {
        guard byteBudget == 0 || core.cap == byteBudget else {
            throw HostFailure("\(label): the byte budget is \(core.cap), not the \(byteBudget) set")
        }
        guard core.peak <= core.cap else {
            throw HostFailure("\(label): the core held \(core.peak) payload bytes, over the phone's byte budget of \(core.cap)")
        }
    }

    static func describe(_ core: CoreBytes) -> String {
        core.cap == phoneByteBudget
            ? "core payload bytes peak \(core.peak) of the phone's \(core.cap)"
            : "core payload bytes peak \(core.peak) of a B of \(core.cap)"
    }

    // MARK: - small things

    static func want(_ arguments: [String], _ names: String...) throws -> [String] {
        guard arguments.count == names.count else {
            throw HostFailure("want \(names.joined(separator: " ")), got \(arguments.count) arguments")
        }
        return arguments
    }

    static func read(_ path: String) throws -> String {
        do {
            return try String(contentsOfFile: path, encoding: .utf8)
        } catch {
            throw HostFailure("cannot read \(path): \(error.localizedDescription)")
        }
    }

    static func bytes(_ text: String) throws -> UInt64 {
        guard let value = UInt64(text), value > 0 else { throw HostFailure("not a byte count: \(text)") }
        return value
    }

    static func fileName(_ path: String) -> String {
        URL(fileURLWithPath: path).lastPathComponent
    }

    static func waitUntilClosed(_ port: Int, seconds: Double) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while HostConnection.accepts(port) {
            if Date() > deadline { return false }
            usleep(100_000)
        }
        return true
    }

    static func milliseconds(_ seconds: TimeInterval) -> String {
        "\(Int((seconds * 1000).rounded())) ms"
    }

    static func mib(_ bytes: UInt64) -> String {
        String(format: "%.1f MiB", Double(bytes) / 1_048_576)
    }

    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func describe(_ error: Error) -> String {
        (error as? HostFailure)?.description ?? error.localizedDescription
    }
}

/// A check that did not hold, in a sentence.
struct HostFailure: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}
