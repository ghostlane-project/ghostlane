import Foundation

/// Compiled with the production HevTunnel.swift and TunArrangement.swift by
/// scripts/test-ios-hev-config.sh, which stands in for hev's C API (and, off
/// Darwin, for os.Logger). Plain checks, no XCTest, like its neighbours.
///
/// hev reads its YAML once, at start, and a setting it reads differently from
/// how it was meant does not fail the start: an unknown key is skipped, and a
/// task stack below hev's floor is raised to the floor. The second happened
/// quietly (24,576 written, 35,480 used, until 2026-11-14), so the lines, the
/// keys and the arithmetic are held here.
///
/// Given the repository root as its argument, it also reads lwIP's sizes from
/// scripts/patches/hev-lwipopts-ios.patch and holds them to the session count.
@main
enum HevTunnelConfigurationTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    // hev-socks5-tunnel at scripts/hev-pins.sh's HEV_COMMIT (941c7581).
    // scripts/build-hev-ios.sh refuses to build a tree where the two constants
    // or the rule below have moved.

    /// src/hev-config-const.h: the stack a task needs besides its buffers.
    static let hevTaskStackSize = 20_480
    /// src/hev-config-const.h: one UDP copy buffer.
    static let hevUdpBufSize = 1_500
    /// src/hev-config.c, hev_config_reset: what hev uses for a key not written.
    static let hevDefaults = [
        "task-stack-size": 86_016, "tcp-buffer-size": 65_536,
        "udp-copy-buffer-nums": 10, "max-session-count": 0,
    ]
    /// The keys hev's parser compares, section by section (src/hev-config.c,
    /// hev_config_parse_*); any other is skipped without a word.
    static let hevKeys: [String: Set<String>] = [
        "tunnel": ["name", "mtu", "multi-queue", "ipv4", "ipv6", "icmp", "post-up-script", "pre-down-script"],
        "socks5": ["port", "address", "udp", "udp-address", "pipeline", "username", "password", "mark",
                   "tcp-fastopen"],
        "mapdns": ["address", "port", "network", "netmask", "cache-size"],
        "misc": ["task-stack-size", "tcp-buffer-size", "udp-recv-buffer-size", "udp-copy-buffer-nums",
                 "max-session-count", "connect-timeout", "read-write-timeout", "tcp-read-write-timeout",
                 "udp-read-write-timeout", "pid-file", "log-file", "log-level", "limit-nofile"],
    ]
    /// lwIP's TCP_MSS in hev's lwipopts.h, which the iOS patch leaves alone.
    static let lwipTcpMss = 8_191
    /// The phone's page: hev's mmap'd task stacks round up to it and add one.
    static let iosPage = 16_384

    /// hev's floor for a task stack (src/hev-config.c, hev_config_parse_doc):
    /// TASK_STACK_SIZE + max(tcp-buffer-size, UDP_BUF_SIZE x udp-copy-buffer-nums).
    /// hev first cuts tcp-buffer-size to lwIP's TCP_SND_BUF, which can only
    /// lower the floor; the patch's check below says it does not cut here.
    static func hevStackFloor(tcpBufferSize: Int, udpCopyBufferNums: Int) -> Int {
        hevTaskStackSize + max(tcpBufferSize, hevUdpBufSize * udpCopyBufferNums)
    }

    /// What hev maps for one task stack (hev-task-stack-mmap.c): the size
    /// rounded up to the page, plus a guard page.
    static func mappedStack(_ size: Int) -> Int {
        (size + iosPage - 1) / iosPage * iosPage + iosPage
    }

    /// The YAML as hev's parser sees it: section, then key, then the scalar,
    /// single quotes taken off and doubled ones undone.
    static func parse(_ yaml: String) -> [String: [String: String]] {
        var sections: [String: [String: String]] = [:]
        var current = ""
        for raw in yaml.split(separator: "\n") {
            let line = String(raw)
            if !line.hasPrefix(" ") {
                check(line.hasSuffix(":"), "a top-level line that is not a section: \(line)")
                current = String(line.dropLast())
                check(sections[current] == nil, "section \(current) written twice")
                sections[current] = [:]
                continue
            }
            let body = line.drop(while: { $0 == " " })
            guard let colon = body.range(of: ": ") else {
                check(false, "not a key: value line: \(line)")
                continue
            }
            let key = String(body[..<colon.lowerBound])
            var value = String(body[colon.upperBound...])
            if value.count >= 2, value.hasPrefix("'"), value.hasSuffix("'") {
                value = String(value.dropFirst().dropLast()).replacingOccurrences(of: "''", with: "'")
            }
            check(sections[current]?[key] == nil, "\(current).\(key) written twice")
            sections[current]?[key] = value
        }
        return sections
    }

    /// A misc setting as hev will hold it: the number written, or hev's own
    /// default when the key is absent.
    static func setting(_ misc: [String: String], _ key: String) -> Int {
        if let text = misc[key] {
            guard let value = Int(text) else {
                check(false, "misc.\(key) is not a number: \(text)")
                return 0
            }
            return value
        }
        return hevDefaults[key] ?? 0
    }

    /// The `#define NAME value` lines the iOS patch adds: a number, or
    /// `(n * TCP_MSS)`.
    static func patchDefines(_ patch: String) -> [String: Int] {
        var defines: [String: Int] = [:]
        for raw in patch.split(separator: "\n") where raw.hasPrefix("+#define ") {
            let words = raw.dropFirst("+#define ".count).split(separator: " ", maxSplits: 1)
            guard words.count == 2 else { continue }
            let name = String(words[0])
            let text = words[1].trimmingCharacters(in: .whitespaces)
            if let value = Int(text) {
                defines[name] = value
            } else if text.hasPrefix("("), text.hasSuffix(")") {
                let product = text.dropFirst().dropLast().split(separator: "*")
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                if product.count == 2, let factor = Int(product[0]), product[1] == "TCP_MSS" {
                    defines[name] = factor * lwipTcpMss
                } else {
                    check(false, "\(name): a value this test cannot read: \(text)")
                }
            } else {
                check(false, "\(name): a value this test cannot read: \(text)")
            }
        }
        return defines
    }

    static func main() {
        let yaml = HevTunnel.configuration(
            socks: LoopbackSocks(port: 10810, username: "tun'user", password: "p w"), mtu: 9000)
        let sections = parse(yaml)

        // The whole misc block, line by line. The timeouts are the ones the
        // engines' own idle cuts were paired with; the rest is sized below.
        let miscLines = yaml.split(separator: "\n").map(String.init)
            .drop(while: { $0 != "misc:" }).dropFirst()
        check(Array(miscLines) == [
            "  task-stack-size: 24576",
            "  tcp-buffer-size: 4096",
            "  udp-copy-buffer-nums: 2",
            "  max-session-count: 256",
            "  connect-timeout: 5000",
            "  read-write-timeout: 60000",
            "  udp-read-write-timeout: 30000",
            "  log-file: stderr",
            "  log-level: warn",
        ], "misc block: \(Array(miscLines))")
        check(sections["tunnel"] == ["mtu": "9000"], "tunnel: \(sections["tunnel"] ?? [:])")
        check(sections["socks5"] == ["port": "10810", "address": "127.0.0.1", "udp": "udp",
                                     "username": "tun'user", "password": "p w"],
              "socks5, with the login read back through YAML's quoting: \(sections["socks5"] ?? [:])")

        // Every key written is one hev reads: a misspelt one would be skipped
        // and hev's default would apply in silence.
        for (section, pairs) in sections {
            guard let known = hevKeys[section] else {
                check(false, "hev has no section \(section)")
                continue
            }
            for key in pairs.keys {
                check(known.contains(key), "hev does not read \(section).\(key); it would be skipped")
            }
        }

        // The stack hev uses is the stack written: never below hev's floor
        // for the buffers configured, or hev raises it and the configuration
        // no longer says what runs.
        let misc = sections["misc"] ?? [:]
        let stack = setting(misc, "task-stack-size")
        let tcpBuffer = setting(misc, "tcp-buffer-size")
        let udpBuffers = setting(misc, "udp-copy-buffer-nums")
        let stackFloor = hevStackFloor(tcpBufferSize: tcpBuffer, udpCopyBufferNums: udpBuffers)
        check(stack >= stackFloor,
              "task-stack-size \(stack) is below hev's floor \(stackFloor) = \(hevTaskStackSize) + max(\(tcpBuffer), "
              + "\(hevUdpBufSize) x \(udpBuffers)): hev would raise it without a word")
        // HevTunnel.swift's arithmetic: the floor is the TCP ring's, exactly
        // the size written, and on the phone's pages hev maps 32 KiB + guard.
        check(stackFloor == 20_480 + 4_096 && stack == stackFloor, "floor \(stackFloor), stack \(stack): 24,576 both")
        check(mappedStack(stack) == 49_152, "mapped per session: \(mappedStack(stack))")
        check(hevStackFloor(tcpBufferSize: tcpBuffer, udpCopyBufferNums: udpBuffers + 1) > stack,
              "a third UDP copy buffer would raise the floor past the stack written")
        // The check above sees what it is for: without udp-copy-buffer-nums,
        // hev's ten put the floor at 35,480, above the 24,576 written, and a
        // session's stack mapped 64 KiB.
        var before = misc
        before["udp-copy-buffer-nums"] = nil
        let floorBefore = hevStackFloor(tcpBufferSize: setting(before, "tcp-buffer-size"),
                                        udpCopyBufferNums: setting(before, "udp-copy-buffer-nums"))
        check(floorBefore == 35_480 && floorBefore > stack && mappedStack(floorBefore) == 65_536,
              "the check above would not have caught the configuration before 2026-11-14: floor \(floorBefore)")

        let sessions = setting(misc, "max-session-count")
        check(sessions > 0, "max-session-count 0 is no cap at all in hev")

        // lwIP's sizes, when the repository is at hand: the pools are sized
        // for this session count, and the ring is not cut by the send buffer.
        if CommandLine.arguments.count > 1 {
            let url = URL(fileURLWithPath: CommandLine.arguments[1])
                .appendingPathComponent("scripts/patches/hev-lwipopts-ios.patch")
            guard let patch = try? String(contentsOf: url, encoding: .utf8) else {
                check(false, "\(url.path) is missing")
                return
            }
            let lwip = patchDefines(patch)
            func value(_ name: String) -> Int {
                guard let v = lwip[name] else {
                    check(false, "the iOS patch defines no \(name)")
                    return 0
                }
                return v
            }
            // A pcb for every live session, with room for connections still
            // in their handshake, and segments and ROM pbufs for every
            // session's queued download. Raising max-session-count without
            // these makes new connections fail or downloads end.
            check(value("MEMP_NUM_TCP_PCB") >= 4 * sessions, "MEMP_NUM_TCP_PCB for \(sessions) sessions")
            check(value("MEMP_NUM_UDP_PCB") >= 2 * sessions, "MEMP_NUM_UDP_PCB for \(sessions) sessions")
            check(value("MEMP_NUM_TCP_SEG") >= 8 * sessions, "MEMP_NUM_TCP_SEG for \(sessions) sessions")
            check(value("MEMP_NUM_PBUF") >= 8 * sessions, "MEMP_NUM_PBUF for \(sessions) sessions")
            // hev-config.c cuts tcp-buffer-size to TCP_SND_BUF: it must not,
            // or the ring and the floor above are not what was written.
            check(value("TCP_SND_BUF") >= tcpBuffer, "TCP_SND_BUF \(value("TCP_SND_BUF")) < tcp-buffer-size")
            // The window HevTunnel.swift's comments count with: four segments.
            check(value("TCP_WND") == 32_764 && value("TCP_SND_BUF") == 32_764, "TCP_WND and TCP_SND_BUF 32,764")
            // Ahead of the compiler, lwIP's sanity checks that these touch
            // (core/init.c), with hev's TCP_SND_QUEUELEN expression.
            let queueLen = (128 * value("TCP_SND_BUF") + lwipTcpMss - 1) / lwipTcpMss
            check(queueLen == 512 && value("MEMP_NUM_TCP_SEG") >= queueLen, "MEMP_NUM_TCP_SEG >= TCP_SND_QUEUELEN")
            check(value("TCP_SND_BUF") >= 2 * lwipTcpMss, "TCP_SND_BUF >= 2 * TCP_MSS")
            check(value("TCP_WND") >= lwipTcpMss && value("TCP_WND") <= 0xffff, "TCP_MSS <= TCP_WND <= 0xffff")
            let poolPayload = (lwipTcpMss + 40 + 16 + 3) / 4 * 4 - (16 + 40 + 20)
            check(value("PBUF_POOL_SIZE") * poolPayload >= value("TCP_WND"), "TCP_WND fits the pbuf pool")
        }

        print("ok")
    }
}
