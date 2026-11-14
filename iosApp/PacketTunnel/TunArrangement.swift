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

/// What owns the tun for one start, and what stands behind it.
///
/// Decided once, by `decide`, from the files the app left in the shared
/// container. The tun's resolvers and the start path both follow from the
/// answer, so the two cannot disagree.
enum TunArrangement: Equatable {

    /// sing-box owns the tun through libbox, on its gVisor stack, and does
    /// the whole job. What Reality and Hysteria2 ran until 2026-11-13, and
    /// the way back: the extension starts it whenever config.json's inbound
    /// is a tun, which is what `SingBoxConfig.buildTun` writes.
    case singBoxTun

    /// sing-box listens on a loopback SOCKS port and hev owns the tun in
    /// front of it: Reality, Hysteria2 and WebTunnel since 2026-11-13
    /// (`SingBoxConfig.buildIosSocks`). sing-box still answers DNS, sniffs
    /// and applies the Bypass Russia rules; it no longer runs a network stack.
    case singBoxBehindHev(LoopbackSocks)

    /// Xray (xhttp, since 1.0.426) or olcRTC (since 1.0.428) speaks the
    /// transport on a loopback SOCKS port and hev owns the tun. No sing-box.
    case engineBehindHev(Engine, LoopbackSocks)

    enum Engine: String, Equatable {
        case xray
        case olcrtc
    }

    /// Where sing-box's SOCKS inbound listens when the config names no port:
    /// `SingBoxConfig.SINGBOX_SOCKS_PORT`, and Xray's `XRAY_SOCKS_PORT`.
    static let defaultSocksPort = 10810

    /// The arrangement for one start.
    ///
    /// xray.json and olcrtc.json are each present only for the one transport
    /// that needs them (the app deletes the other), and either one means its
    /// engine speaks the transport: config.json is then written but not run.
    /// olcRTC is asked first, as it always was. Otherwise config.json is
    /// sing-box's, and its inbound says which way round: a SOCKS inbound puts
    /// hev in front, a tun (or anything unreadable, which sing-box will then
    /// refuse in words of its own) gives sing-box the tun.
    static func decide(config: String, xrayConfig: String?, olcrtcSocks: LoopbackSocks?) -> TunArrangement {
        if let olcrtcSocks {
            return .engineBehindHev(.olcrtc, olcrtcSocks)
        }
        if let xrayConfig, !xrayConfig.isEmpty {
            let login = xraySocksLogin(in: xrayConfig)
            return .engineBehindHev(.xray, LoopbackSocks(
                port: xraySocksPort(in: xrayConfig),
                username: login?.username,
                password: login?.password
            ))
        }
        if let socks = singBoxSocks(in: config) {
            return .singBoxBehindHev(socks)
        }
        return .singBoxTun
    }

    /// The SOCKS server hev forwards to, or nil when sing-box owns the tun.
    var hevSocks: LoopbackSocks? {
        switch self {
        case .singBoxTun: return nil
        case .singBoxBehindHev(let socks): return socks
        case .engineBehindHev(_, let socks): return socks
        }
    }

    /// Whether sing-box answers the resolver the tun advertises.
    ///
    /// On both sing-box arrangements it does: 172.19.0.2 is an address only
    /// sing-box answers, by hijacking every query on port 53, and behind hev
    /// a query to it reaches sing-box as SOCKS5 UDP and is hijacked just the
    /// same. Behind Xray or olcRTC nothing would answer it, so the tun
    /// advertises resolvers on the internet instead (LibboxPlatform.Tun).
    var singBoxAnswersDns: Bool {
        switch self {
        case .singBoxTun, .singBoxBehindHev: return true
        case .engineBehindHev: return false
        }
    }

    /// The name in the diagnostics and in the app's log: what runs, and
    /// whether hev is in front of it.
    var engineLabel: String {
        switch self {
        case .singBoxTun: return "sing-box"
        case .singBoxBehindHev: return "sing-box+hev"
        case .engineBehindHev(let engine, _): return "\(engine.rawValue)+hev"
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
