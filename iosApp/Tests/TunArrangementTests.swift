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

        // The decision.
        check(TunArrangement.decide(config: singBoxSocks, xrayConfig: nil, olcrtcSocks: nil)
              == .singBoxBehindHev(LoopbackSocks(port: 10810, username: "tun-user", password: "tun-pass")),
              "a socks config and no other engine: sing-box behind hev")
        check(TunArrangement.decide(config: singBoxTun, xrayConfig: nil, olcrtcSocks: nil) == .singBoxTun,
              "a tun config: sing-box owns the tun, the way back")
        check(TunArrangement.decide(config: "not json", xrayConfig: nil, olcrtcSocks: nil) == .singBoxTun,
              "an unreadable config goes to sing-box, which says what is wrong with it")
        check(TunArrangement.decide(config: singBoxSocks, xrayConfig: xray, olcrtcSocks: nil)
              == .engineBehindHev(.xray, LoopbackSocks(port: 10810, username: "xu", password: "xp")),
              "xray.json present wins over sing-box's socks config")
        check(TunArrangement.decide(config: singBoxTunSocks, xrayConfig: xray, olcrtcSocks: nil)
              == .engineBehindHev(.xray, LoopbackSocks(port: 10810, username: "xu", password: "xp")),
              "xray.json beside the config the app writes for it")
        check(TunArrangement.decide(config: singBoxSocks, xrayConfig: "", olcrtcSocks: nil)
              == .singBoxBehindHev(LoopbackSocks(port: 10810, username: "tun-user", password: "tun-pass")),
              "an empty xray.json is no Xray")
        check(TunArrangement.decide(config: singBoxSocks, xrayConfig: nil, olcrtcSocks: olcrtc) == .engineBehindHev(.olcrtc, olcrtc),
              "olcrtc.json present wins over sing-box's socks config")
        check(TunArrangement.decide(config: singBoxTunSocks, xrayConfig: xray, olcrtcSocks: olcrtc) == .engineBehindHev(.olcrtc, olcrtc),
              "olcRTC is asked first, as it always was")

        // What follows from it: who answers the tun's resolver, what hev
        // forwards to, and what the diagnostics call it.
        let behind = TunArrangement.singBoxBehindHev(LoopbackSocks(port: 10810, username: "a", password: "b"))
        check(TunArrangement.singBoxTun.singBoxAnswersDns, "sing-box on the tun answers 172.19.0.2")
        check(behind.singBoxAnswersDns, "sing-box behind hev still answers 172.19.0.2")
        check(!TunArrangement.engineBehindHev(.xray, olcrtc).singBoxAnswersDns, "behind Xray nothing answers 172.19.0.2")
        check(!TunArrangement.engineBehindHev(.olcrtc, olcrtc).singBoxAnswersDns, "behind olcRTC nothing answers 172.19.0.2")
        check(TunArrangement.singBoxTun.hevSocks == nil, "no hev on sing-box's own tun")
        check(behind.hevSocks == LoopbackSocks(port: 10810, username: "a", password: "b"), "hev forwards to sing-box's port")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).hevSocks == olcrtc, "hev forwards to the engine's port")
        check(TunArrangement.singBoxTun.engineLabel == "sing-box", "label: sing-box")
        check(behind.engineLabel == "sing-box+hev", "label: sing-box+hev")
        check(TunArrangement.engineBehindHev(.xray, olcrtc).engineLabel == "xray+hev", "label: xray+hev")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).engineLabel == "olcrtc+hev", "label: olcrtc+hev")

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
                guard case .singBoxBehindHev(let socks) = TunArrangement.decide(config: read(name), xrayConfig: nil, olcrtcSocks: nil) else {
                    check(false, "\(name): sing-box behind hev")
                    return
                }
                check(socks.port == 10810, "\(name): the builder's port")
                check(socks.username?.isEmpty == false && socks.password?.isEmpty == false, "\(name): the builder's login")
            }
            for name in ["ios-tun-reality.json", "bypass-tun-reality.json", "bypass-tun-hysteria2.json"] {
                check(TunArrangement.decide(config: read(name), xrayConfig: nil, olcrtcSocks: nil) == .singBoxTun,
                      "\(name): the way back starts sing-box on the tun")
            }
            for name in ["ios-tun-socks-lossy.json", "bypass-tun-socks.json"] {
                check(TunArrangement.singBoxSocks(in: read(name)) == nil,
                      "\(name): the config written beside xray.json or olcrtc.json is a tun")
            }
        }

        print("ok")
    }
}
