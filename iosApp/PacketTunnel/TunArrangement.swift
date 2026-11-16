import Foundation

/// A SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel forwards to, and the
/// login it demands.
///
/// Every engine the extension runs listens on one now, and every one demands
/// a login: olcRTC the pair in its parameters, Xray and sing-box the pair the
/// app wrote into their configs (SocksLogin in the shared Kotlin). On loopback
/// any other app on the phone could reach an open one, leave through the
/// tunnel and read its exit. hev has to send exactly what the inbound demands,
/// so the login is read from the same file rather than assumed.
///
/// Foundation only, like the decision below, so a swift.org toolchain can
/// compile both with their test (scripts/test-ios-tun-arrangement.sh).
struct LoopbackSocks: Equatable {
    let port: Int
    let username: String?
    let password: String?
}

/// What stands behind the tun for one start.
///
/// Decided once, by `decide`, from the files the app left in the shared
/// container. hev-socks5-tunnel owns the tun on every path; the tun's
/// resolvers, hev's UDP mode and the start path all follow from the answer,
/// so they cannot disagree.
///
/// Since step C of ghostlane-rust-core the extension carries no Go at all:
/// no sing-box, no Xray, no Go olcRTC. Both engines live in ghostlane-core,
/// the one Rust library (xray-rust's core and the olcRTC client, one runtime,
/// one allocator, one byte budget). A start whose files only a Go engine
/// could run is refused with a reason (`needsGo`) rather than started on an
/// engine this build does not have.
enum TunArrangement: Equatable {

    /// An engine of ghostlane-core speaks the transport on a loopback SOCKS
    /// port and hev owns the tun in front of it.
    case engineBehindHev(Engine, LoopbackSocks)

    /// The files name a line only a Go engine runs (xray.json for Go's Xray,
    /// config.json alone for sing-box): the start fails with this reason.
    case needsGo(String)

    enum Engine: String, Equatable {
        /// xray-rust's core from core.json (`XrayConfig.buildIosCore`):
        /// Reality, Reality gRPC, WebTunnel, Hysteria2, XHTTP.
        case core = "rust"
        /// The Rust olcRTC client (olc-ffi's `OlcrtcRuntime`) from olcrtc.json.
        case olcrtc
    }

    /// Where a SOCKS inbound listens when the config names no port:
    /// `SingBoxConfig.SINGBOX_SOCKS_PORT`, and Xray's `XRAY_SOCKS_PORT`.
    static let defaultSocksPort = 10810

    static let needsGoXray =
        "this line needs Go's Xray (xray.json), and this build carries no Go engine"
    static let needsGoSingBox =
        "this line needs sing-box (config.json), and this build carries no Go engine"

    /// The arrangement for one start.
    ///
    /// olcrtc.json is asked first, as it always was; then core.json, which
    /// the app writes for every line `IosVpnManager.IOS_ENGINES` gives the
    /// Rust core (and removes otherwise). xray.json and config.json are Go's
    /// files: with neither olcrtc.json nor core.json beside them the line is
    /// one the core refused (`IosCore.refusal`), and it is refused here.
    static func decide(coreConfig: String?, olcrtcSocks: LoopbackSocks?, xrayConfig: String?, config: String) -> TunArrangement {
        if let olcrtcSocks {
            return .engineBehindHev(.olcrtc, olcrtcSocks)
        }
        if let coreConfig, !coreConfig.isEmpty {
            let login = xraySocksLogin(in: coreConfig)
            return .engineBehindHev(.core, LoopbackSocks(
                port: xraySocksPort(in: coreConfig),
                username: login?.username,
                password: login?.password
            ))
        }
        if let xrayConfig, !xrayConfig.isEmpty {
            return .needsGo(needsGoXray)
        }
        return .needsGo(needsGoSingBox)
    }

    /// The SOCKS server hev forwards to, or nil on a refusal.
    var hevSocks: LoopbackSocks? {
        switch self {
        case .engineBehindHev(_, let socks): return socks
        case .needsGo: return nil
        }
    }

    /// hev's `socks5.udp`. The core's password inbound refuses a UDP
    /// ASSOCIATE for 0.0.0.0:0 by design, so its UDP rides hev's TCP lane
    /// (the fork's hev-udp); olcRTC takes UDP ASSOCIATE, as it did on Go.
    var hevUdp: String {
        switch self {
        case .engineBehindHev(.core, _): return "tcp"
        case .engineBehindHev(.olcrtc, _), .needsGo: return "udp"
        }
    }

    /// The name in the diagnostics and in the app's log: what runs, and
    /// that hev is in front of it.
    var engineLabel: String {
        switch self {
        case .engineBehindHev(let engine, _): return "\(engine.rawValue)+hev"
        case .needsGo: return "none"
        }
    }

    // MARK: - reading the configs the app wrote

    /// sing-box's SOCKS inbound in `configJSON`, or nil when sing-box is to
    /// own the tun: a tun inbound anywhere, no SOCKS inbound, or a config that
    /// does not parse.
    ///
    /// sing-box's shape (`SingBoxConfig.buildIosSocks`): `type` "socks",
    /// `listen_port`, and `users[0]` with `username` and `password`. A login
    /// is a pair: half of one is sent as none, which the inbound refuses
    /// loudly rather than hev failing quietly.
    static func singBoxSocks(in configJSON: String) -> LoopbackSocks? {
        guard let inbounds = inbounds(in: configJSON),
              !inbounds.contains(where: { $0["type"] as? String == "tun" }),
              let inbound = inbounds.first(where: { $0["type"] as? String == "socks" })
        else { return nil }
        let user = (inbound["users"] as? [[String: Any]])?.first
        let username = (user?["username"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let password = (user?["password"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let paired = username != nil && password != nil
        return LoopbackSocks(
            port: portNumber(inbound["listen_port"]) ?? defaultSocksPort,
            username: paired ? username : nil,
            password: paired ? password : nil
        )
    }

    /// The login Xray's SOCKS inbound demands, or nil when it demands none.
    ///
    /// Xray's shape (`XrayConfig`), not sing-box's: `protocol` "socks",
    /// `settings.auth` "password" and `settings.accounts[0]` with `user` and
    /// `pass`.
    static func xraySocksLogin(in configJSON: String) -> (username: String, password: String)? {
        guard let inbounds = inbounds(in: configJSON) else { return nil }
        for inbound in inbounds where inbound["protocol"] as? String == "socks" {
            guard let settings = inbound["settings"] as? [String: Any],
                  settings["auth"] as? String == "password",
                  let account = (settings["accounts"] as? [[String: Any]])?.first,
                  let username = account["user"] as? String, !username.isEmpty,
                  let password = account["pass"] as? String, !password.isEmpty
            else { return nil }
            return (username, password)
        }
        return nil
    }

    /// The port Xray's SOCKS inbound listens on, or 10810 when the config
    /// cannot be read — the constant XrayConfig.kt builds with.
    static func xraySocksPort(in configJSON: String) -> Int {
        guard let inbounds = inbounds(in: configJSON) else { return defaultSocksPort }
        for inbound in inbounds where inbound["protocol"] as? String == "socks" {
            if let port = portNumber(inbound["port"]) { return port }
        }
        return defaultSocksPort
    }

    private static func inbounds(in configJSON: String) -> [[String: Any]]? {
        guard let data = configJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return object["inbounds"] as? [[String: Any]]
    }

    /// A port as JSONSerialization hands it over, which is an NSNumber on
    /// Darwin and may be an Int elsewhere; nil for anything that is not one.
    private static func portNumber(_ value: Any?) -> Int? {
        let candidate: Int
        if let int = value as? Int {
            candidate = int
        } else if let number = value as? NSNumber {
            candidate = number.intValue
        } else {
            return nil
        }
        return (1...65535).contains(candidate) ? candidate : nil
    }
}
