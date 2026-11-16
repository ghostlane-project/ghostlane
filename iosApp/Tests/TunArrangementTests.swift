import Foundation

/// Compiled with the production TunArrangement by scripts/test-ios-tun-arrangement.sh.
/// No Cores framework, signing identity or device is needed; a swift.org
/// toolchain on Linux runs it as well as Xcode does.
///
/// Given the repository root as its argument, it also reads the configs the
/// shared Kotlin writes (SingBoxConfigDumpTest's reference files), so the
/// parser is held to the builder and not only to a copy of its output here.
@main
enum TunArrangementTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    // sing-box's config as SingBoxConfig.buildIosSocks writes it, trimmed to
    // what the parser reads and one neighbour of each.
    static let singBoxSocks = #"""
    {"log":{"level":"warn"},"inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":10810,"users":[{"username":"tun-user","password":"tun-pass"}]}],"outbounds":[{"type":"vless","tag":"out"}]}
    """#
    // The way back: SingBoxConfig.buildTun.
    static let singBoxTun = #"""
    {"log":{"level":"warn"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"mtu":9000,"auto_route":true,"stack":"gvisor"}],"outbounds":[{"type":"vless","tag":"out"}]}
    """#
    // What the app writes as config.json beside xray.json and olcrtc.json:
    // SingBoxConfig.buildTunSocks, a tun whose outbound is the engine's port.
    static let singBoxTunSocks = #"""
    {"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"mtu":9000,"auto_route":true,"stack":"gvisor"}],"outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":10810,"version":"5","username":"u","password":"p"}]}
    """#
    // Xray's shape (XrayConfig.buildXhttp), which is not sing-box's.
    static let xray = #"""
    {"inbounds":[{"tag":"socks-in","port":10810,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"password","accounts":[{"user":"xu","pass":"xp"}],"udp":true}}],"outbounds":[{"protocol":"vless","tag":"out"}]}
    """#
    static let olcrtc = LoopbackSocks(port: 10810, username: "ou", password: "op")

    static func main() {
        // sing-box's SOCKS inbound: port and login, in sing-box's own field names.
        check(TunArrangement.singBoxSocks(in: singBoxSocks) == LoopbackSocks(port: 10810, username: "tun-user", password: "tun-pass"),
              "the port and login buildIosSocks writes")
        let otherPort = singBoxSocks.replacingOccurrences(of: #""listen_port":10810"#, with: #""listen_port":20810"#)
        check(TunArrangement.singBoxSocks(in: otherPort)?.port == 20810, "the port is read, not assumed")
        let noPort = singBoxSocks.replacingOccurrences(of: #""listen_port":10810,"#, with: "")
        check(TunArrangement.singBoxSocks(in: noPort)?.port == TunArrangement.defaultSocksPort, "no port: the one the builder uses")
        let openInbound = singBoxSocks.replacingOccurrences(of: #","users":[{"username":"tun-user","password":"tun-pass"}]"#, with: "")
        check(TunArrangement.singBoxSocks(in: openInbound) == LoopbackSocks(port: 10810, username: nil, password: nil),
              "an inbound without users is offered no login")
        let halfLogin = singBoxSocks.replacingOccurrences(of: #""password":"tun-pass""#, with: #""password":"""#)
        check(TunArrangement.singBoxSocks(in: halfLogin) == LoopbackSocks(port: 10810, username: nil, password: nil),
              "half a login is sent as none")
        check(TunArrangement.singBoxSocks(in: singBoxTun) == nil, "a tun inbound is sing-box's own tun")
        check(TunArrangement.singBoxSocks(in: singBoxTunSocks) == nil, "a tun with a socks outbound is still a tun")
        check(TunArrangement.singBoxSocks(in: xray) == nil, "Xray's inbound names its protocol, not a type")
        check(TunArrangement.singBoxSocks(in: "not json") == nil, "a config that does not parse")
        check(TunArrangement.singBoxSocks(in: #"{"log":{}}"#) == nil, "a config without inbounds")

        // Xray's parser, moved here unchanged.
        let xrayLogin = TunArrangement.xraySocksLogin(in: xray)
        check(xrayLogin?.username == "xu" && xrayLogin?.password == "xp", "Xray's accounts[].user/pass")
        check(TunArrangement.xraySocksPort(in: xray) == 10810, "Xray's port")
        check(TunArrangement.xraySocksLogin(in: singBoxSocks) == nil, "sing-box's users are not Xray's accounts")
        let noAuth = xray.replacingOccurrences(of: #""auth":"password","#, with: "")
        check(TunArrangement.xraySocksLogin(in: noAuth) == nil, "an Xray inbound without auth demands no login")

        // The decision, with no Go in the extension (step C of
        // ghostlane-rust-core): core.json runs on ghostlane-core's xray core,
        // olcrtc.json on its olcRTC client; a start that only Go could run
        // (xray.json for Go's Xray, config.json for sing-box) is refused with
        // a reason instead of starting an engine this build does not carry.
        let core = xray.replacingOccurrences(of: #""user":"xu","pass":"xp""#, with: #""user":"cu","pass":"cp""#)
        let coreSocks = LoopbackSocks(port: 10810, username: "cu", password: "cp")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: nil, xrayConfig: nil, config: singBoxTunSocks)
              == .engineBehindHev(.core, coreSocks),
              "core.json: the Rust core behind hev, its login read from core.json")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: nil, xrayConfig: xray, config: singBoxSocks)
              == .engineBehindHev(.core, coreSocks),
              "core.json wins over a stale xray.json: Go's Xray is not in this build")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: olcrtc, xrayConfig: nil, config: singBoxTunSocks)
              == .engineBehindHev(.olcrtc, olcrtc),
              "olcRTC is asked first, as it always was")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: olcrtc, xrayConfig: nil, config: singBoxTunSocks)
              == .engineBehindHev(.olcrtc, olcrtc),
              "olcrtc.json: the Rust olcRTC client behind hev")
        check(TunArrangement.decide(coreConfig: "", olcrtcSocks: nil, xrayConfig: nil, config: singBoxSocks)
              == .needsGo(TunArrangement.needsGoSingBox),
              "an empty core.json is no core.json")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil, xrayConfig: xray, config: singBoxTunSocks)
              == .needsGo(TunArrangement.needsGoXray),
              "xray.json alone needs Go's Xray: refused")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil, xrayConfig: nil, config: singBoxSocks)
              == .needsGo(TunArrangement.needsGoSingBox),
              "config.json alone needs sing-box: refused")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil, xrayConfig: nil, config: singBoxTun)
              == .needsGo(TunArrangement.needsGoSingBox),
              "sing-box's own tun needs sing-box: refused")
        check(TunArrangement.needsGoXray.contains("Go") && TunArrangement.needsGoSingBox.contains("Go"),
              "the refusal says that the line needs the Go engine")

        // What follows from it: what hev forwards to and how it carries UDP,
        // and what the diagnostics call it.
        check(TunArrangement.engineBehindHev(.core, coreSocks).hevSocks == coreSocks, "hev forwards to the core's port")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).hevSocks == olcrtc, "hev forwards to olcRTC's port")
        check(TunArrangement.needsGo(TunArrangement.needsGoXray).hevSocks == nil, "nothing to forward to on a refusal")
        check(TunArrangement.engineBehindHev(.core, coreSocks).hevUdp == "tcp",
              "the core: UDP in hev's TCP lane (its password inbound refuses UDP ASSOCIATE for 0.0.0.0:0)")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).hevUdp == "udp", "olcRTC: UDP ASSOCIATE, as before")
        check(TunArrangement.engineBehindHev(.core, coreSocks).engineLabel == "rust+hev", "label: rust+hev, the app's own")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).engineLabel == "olcrtc+hev", "label: olcrtc+hev")
        check(TunArrangement.needsGo(TunArrangement.needsGoXray).engineLabel == "none", "label: none")

        // The builder's own output, when the repository is at hand.
        if CommandLine.arguments.count > 1 {
            let configs = URL(fileURLWithPath: CommandLine.arguments[1])
                .appendingPathComponent("sharedUI/src/jvmTest/resources/reference-configs")
            func read(_ name: String) -> String {
                guard let text = try? String(contentsOf: configs.appendingPathComponent(name), encoding: .utf8) else {
                    check(false, "\(name) is missing under \(configs.path)")
                    return ""
                }
                return text
            }
            for name in ["ios-socks-reality.json", "ios-socks-hysteria2.json",
                         "bypass-ios-socks-reality.json", "bypass-ios-socks-hysteria2.json"] {
                check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil, xrayConfig: nil, config: read(name))
                      == .needsGo(TunArrangement.needsGoSingBox), "\(name): sing-box's, refused without Go")
                check(TunArrangement.singBoxSocks(in: read(name))?.port == 10810, "\(name): the builder's port")
            }
            for slug in ["reality", "grpc-reality", "webtunnel-ws", "webtunnel-httpupgrade", "hysteria2", "xhttp"] {
                for name in ["ios-core-\(slug).json", "bypass-ios-core-\(slug).json"] {
                    guard case .engineBehindHev(.core, let socks) = TunArrangement.decide(
                        coreConfig: read(name), olcrtcSocks: nil, xrayConfig: nil, config: read("ios-tun-socks-lossy.json")
                    ) else {
                        check(false, "\(name): the Rust core behind hev")
                        return
                    }
                    check(socks.port == 10810, "\(name): the builder's port")
                    check(socks.username?.isEmpty == false && socks.password?.isEmpty == false, "\(name): the builder's login")
                }
            }
            for name in ["ios-tun-socks-lossy.json", "bypass-tun-socks.json"] {
                check(TunArrangement.singBoxSocks(in: read(name)) == nil,
                      "\(name): the config written beside xray.json or olcrtc.json is a tun")
            }
        }

        print("ok")
    }
}
