import Foundation

/// A SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel forwards to, and the
/// login it demands.
///
/// Every engine the extension runs listens on one, and every one demands a
/// login: olcRTC the pair in its parameters, the core the pair the app wrote
/// into core.json (SocksLogin in the shared Kotlin). On loopback any other
/// app on the phone could reach an open one, leave through the tunnel and
/// read its exit. hev has to send exactly what the inbound demands, so the
/// login is read from the same file rather than assumed.
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
/// one allocator, one byte budget), and two files decide between them:
/// core.json and olcrtc.json. The app writes one of them for every start, and
/// refuses a start it cannot write one for before it asks for the tunnel,
/// naming the line (`IosCore.start` in the shared Kotlin). With neither in
/// the container the start is refused here too (`refused`).
enum TunArrangement: Equatable {

    /// An engine of ghostlane-core speaks the transport on a loopback SOCKS
    /// port and hev owns the tun in front of it.
    case engineBehindHev(Engine, LoopbackSocks)

    /// Nothing to start: the start fails with this reason.
    case refused(String)

    enum Engine: String, Equatable {
        /// xray-rust's core from core.json (`XrayConfig.buildIosCore`): every
        /// kind of line the builder writes (Reality, WebTunnel, Hysteria2,
        /// Trojan, Shadowsocks, VMess, VLESS over TLS and the rest).
        case core = "rust"
        /// The Rust olcRTC client (olc-ffi's `OlcrtcRuntime`) from olcrtc.json.
        case olcrtc
    }

    /// Where the core's SOCKS inbound listens when core.json names no port:
    /// `XrayConfig.XRAY_SOCKS_PORT`.
    static let defaultSocksPort = 10810

    /// Why a start with neither file is refused. The app refuses a line it
    /// writes no file for with that line's own reason before it asks for the
    /// tunnel, so this is a start the app did not prepare: one from the
    /// system's VPN settings with nothing written, or a container emptied
    /// under it.
    static let noLine =
        "no core.json or olcrtc.json in the shared container: the app wrote no line for this start"

    /// The arrangement for one start, from core.json and olcrtc.json alone.
    ///
    /// olcrtc.json is asked first, as it always was; then core.json. The app
    /// writes one and removes the other, so a stale file cannot start a
    /// second engine behind a tunnel that does not use it.
    static func decide(coreConfig: String?, olcrtcSocks: LoopbackSocks?) -> TunArrangement {
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
        return .refused(noLine)
    }

    /// The older spelling, which the Mac engine host still calls. xray.json
    /// and config.json were Go's files: nothing writes them now, and they
    /// decide nothing.
    static func decide(
        coreConfig: String?,
        olcrtcSocks: LoopbackSocks?,
        xrayConfig: String?,
        config: String?
    ) -> TunArrangement {
        decide(coreConfig: coreConfig, olcrtcSocks: olcrtcSocks)
    }

    /// The SOCKS server hev forwards to, or nil on a refusal.
    var hevSocks: LoopbackSocks? {
        switch self {
        case .engineBehindHev(_, let socks): return socks
        case .refused: return nil
        }
    }

    /// hev's `socks5.udp`. The core's password inbound refuses a UDP
    /// ASSOCIATE for 0.0.0.0:0 by design, so its UDP rides hev's TCP lane
    /// (the fork's hev-udp); olcRTC takes UDP ASSOCIATE, as it did on Go.
    var hevUdp: String {
        switch self {
        case .engineBehindHev(.core, _): return "tcp"
        case .engineBehindHev(.olcrtc, _), .refused: return "udp"
        }
    }

    /// The name in the diagnostics and in the app's log: what runs, and
    /// that hev is in front of it.
    var engineLabel: String {
        switch self {
        case .engineBehindHev(let engine, _): return "\(engine.rawValue)+hev"
        case .refused: return "none"
        }
    }

    // MARK: - reading core.json

    /// The login the core's SOCKS inbound demands, or nil when it demands none.
    ///
    /// Xray's shape (`XrayConfig.buildIosCore`): `protocol` "socks",
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

    /// The port the core's SOCKS inbound listens on, or 10810 when the config
    /// cannot be read: the constant XrayConfig.kt builds with.
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
