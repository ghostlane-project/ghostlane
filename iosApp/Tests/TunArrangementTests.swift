import Foundation

/// Compiled with the production TunArrangement by scripts/test-ios-tun-arrangement.sh.
/// No Cores framework, signing identity or device is needed; a swift.org
/// toolchain on Linux runs it as well as Xcode does.
///
/// Given the repository root as its argument, it also reads the configs the
/// shared Kotlin writes (RustCoreConfigDumpTest's reference files, every
/// `*ios-core-*.json`: a line of every kind the core runs), so the parser is
/// held to the builder and not only to a copy of its output here.
@main
enum TunArrangementTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    // core.json's inbound as XrayConfig.buildIosCore writes it, trimmed to
    // what the parser reads and one neighbour of each.
    static let core = #"""
    {"inbounds":[{"tag":"in","port":10810,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"password","accounts":[{"user":"cu","pass":"cp"}],"udp":true}}],"outbounds":[{"protocol":"vless","tag":"out"}]}
    """#
    // sing-box's SOCKS inbound, which no build writes any more: its users
    // are not Xray's accounts, and its file decides nothing.
    static let singBox = #"""
    {"inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":10810,"users":[{"username":"u","password":"p"}]}],"outbounds":[{"type":"vless","tag":"out"}]}
    """#
    static let olcrtc = LoopbackSocks(port: 10810, username: "ou", password: "op")

    static func main() {
        // The core's SOCKS inbound: its port and login, in Xray's field names.
        let login = TunArrangement.xraySocksLogin(in: core)
        check(login?.username == "cu" && login?.password == "cp", "Xray's accounts[].user/pass")
        check(TunArrangement.xraySocksPort(in: core) == 10810, "Xray's port")
        let otherPort = core.replacingOccurrences(of: #""port":10810"#, with: #""port":20810"#)
        check(TunArrangement.xraySocksPort(in: otherPort) == 20810, "the port is read, not assumed")
        let noPort = core.replacingOccurrences(of: #""port":10810,"#, with: "")
        check(TunArrangement.xraySocksPort(in: noPort) == TunArrangement.defaultSocksPort, "no port: the one the builder uses")
        let noAuth = core.replacingOccurrences(of: #""auth":"password","#, with: "")
        check(TunArrangement.xraySocksLogin(in: noAuth) == nil, "an inbound without auth demands no login")
        check(TunArrangement.xraySocksLogin(in: "not json") == nil, "a config that does not parse")
        check(TunArrangement.xraySocksPort(in: "not json") == TunArrangement.defaultSocksPort, "a config that does not parse: the builder's port")
        check(TunArrangement.xraySocksLogin(in: singBox) == nil, "sing-box's users are not Xray's accounts")

        // The decision, from core.json and olcrtc.json alone: the core's
        // lines on ghostlane-core's xray core, a room on its olcRTC client,
        // and neither file a refusal. The app writes one of them for every
        // line it starts and refuses, naming the line, any it cannot.
        let coreSocks = LoopbackSocks(port: 10810, username: "cu", password: "cp")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: nil) == .engineBehindHev(.core, coreSocks),
              "core.json: the Rust core behind hev, its login read from core.json")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: olcrtc) == .engineBehindHev(.olcrtc, olcrtc),
              "olcRTC is asked first, as it always was")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: olcrtc) == .engineBehindHev(.olcrtc, olcrtc),
              "olcrtc.json: the Rust olcRTC client behind hev")
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil) == .refused(TunArrangement.noLine),
              "neither file: refused")
        check(TunArrangement.decide(coreConfig: "", olcrtcSocks: nil) == .refused(TunArrangement.noLine),
              "an empty core.json is no core.json")
        check(TunArrangement.noLine.contains("core.json") && TunArrangement.noLine.contains("olcrtc.json"),
              "the refusal names the files it looked for")
        check(!TunArrangement.noLine.contains("Go") && !TunArrangement.noLine.contains("sing-box"),
              "and sends nobody looking for an engine this build does not carry")
        // The spelling the engine host (iosApp/Tests/EngineHost) still calls:
        // xray.json and config.json are not read, whatever they hold.
        check(TunArrangement.decide(coreConfig: nil, olcrtcSocks: nil, xrayConfig: core, config: singBox)
              == .refused(TunArrangement.noLine),
              "xray.json and config.json decide nothing")
        check(TunArrangement.decide(coreConfig: core, olcrtcSocks: nil, xrayConfig: nil, config: singBox)
              == .engineBehindHev(.core, coreSocks),
              "the older spelling gives the same answer")

        // What follows from it: what hev forwards to and how it carries UDP,
        // and what the diagnostics call it.
        let refused = TunArrangement.refused(TunArrangement.noLine)
        check(TunArrangement.engineBehindHev(.core, coreSocks).hevSocks == coreSocks, "hev forwards to the core's port")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).hevSocks == olcrtc, "hev forwards to olcRTC's port")
        check(refused.hevSocks == nil, "nothing to forward to on a refusal")
        check(TunArrangement.engineBehindHev(.core, coreSocks).hevUdp == "tcp",
              "the core: UDP in hev's TCP lane (its password inbound refuses UDP ASSOCIATE for 0.0.0.0:0)")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).hevUdp == "udp", "olcRTC: UDP ASSOCIATE, as before")
        check(TunArrangement.engineBehindHev(.core, coreSocks).engineLabel == "rust+hev", "label: rust+hev, the app's own")
        check(TunArrangement.engineBehindHev(.olcrtc, olcrtc).engineLabel == "olcrtc+hev", "label: olcrtc+hev")
        check(refused.engineLabel == "none", "label: none")

        // The builder's own output, when the repository is at hand: every
        // core.json reference of the iPhone's, whatever kind of line it is.
        var read = 0
        if CommandLine.arguments.count > 1 {
            let configs = URL(fileURLWithPath: CommandLine.arguments[1])
                .appendingPathComponent("sharedUI/src/jvmTest/resources/reference-configs")
            let names = ((try? FileManager.default.contentsOfDirectory(atPath: configs.path)) ?? [])
                .filter { $0.contains("ios-core-") && $0.hasSuffix(".json") }
                .sorted()
            // The first six lines and one of each kind added beside them: the
            // list is the directory's, and these say it is the right one.
            for slug in ["reality", "grpc-reality", "webtunnel-ws", "webtunnel-httpupgrade", "hysteria2", "xhttp",
                         "trojan", "shadowsocks", "shadowsocks-2022", "vmess-raw", "vless-tls", "vless-none"] {
                check(names.contains("ios-core-\(slug).json") && names.contains("bypass-ios-core-\(slug).json"),
                      "ios-core-\(slug).json and its Bypass Russia twin are among the references")
            }
            for name in names {
                guard let text = try? String(contentsOf: configs.appendingPathComponent(name), encoding: .utf8) else {
                    check(false, "\(name) cannot be read under \(configs.path)")
                    return
                }
                guard case .engineBehindHev(.core, let socks) = TunArrangement.decide(coreConfig: text, olcrtcSocks: nil) else {
                    check(false, "\(name): the Rust core behind hev")
                    return
                }
                check(socks.port == 10810, "\(name): the builder's port")
                check(socks.username?.isEmpty == false && socks.password?.isEmpty == false, "\(name): the builder's login")
                read += 1
            }
        }

        print("ok (\(read) reference configs)")
    }
}
