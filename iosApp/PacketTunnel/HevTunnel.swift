import Foundation
import HevSocks5Tunnel
import os

/// hev-socks5-tunnel, the tun2socks in front of every engine: Xray, olcRTC,
/// and since 2026-11-13 sing-box on its own transports too.
///
/// Until 1.0.424 Xray and olcRTC ran behind sing-box: sing-box owned the
/// tun with its gVisor stack and handed every connection over a loopback SOCKS
/// port to the engine that actually speaks the transport. Two Go network
/// stacks in one process that is killed at about 50 MB: every connection
/// buffered twice, served by twice the goroutines, and a speed test's thirty
/// connections took the extension from 30 to 49.8 MB in five seconds
/// (olcbox 1.0.424 trace). Every Xray-based iOS client that passes the same
/// test in the same budget pairs Xray with this library instead — C, lwIP,
/// kilobytes per connection, no collector — and so does this one now.
///
/// sing-box followed for the same reason: even alone, its gVisor tun is the
/// larger half of what the Go runtime holds. In the lab (Linux, sing-box
/// Reality over SOCKS with hev in front) the runtime's sys minus released was
/// 17.6 MB against 38.2 MB with sing-box's own tun, and hev's resident set
/// was 6.5 MB (branch lab/core-footprint).
///
/// The library runs on its own thread and blocks there until told to quit.
/// The tun descriptor is the one the system created for this extension; hev
/// only puts it into non-blocking mode and reads and writes it, so the
/// settings applied through `NEPacketTunnelNetworkSettings` stand.
enum HevTunnel {

    /// The SOCKS server hev forwards everything to: Xray's inbound, olcRTC's
    /// or sing-box's, each on loopback and each with a login
    /// (`LoopbackSocks`, read from the files the app wrote by
    /// `TunArrangement`).
    typealias Socks = LoopbackSocks

    private static let log = Logger(subsystem: "org.proofkit.app", category: "hev")

    /// Everything below is touched only from the provider's own serialisation
    /// (start, then stop), never concurrently; the annotation says so rather
    /// than asks the compiler to prove it.
    nonisolated(unsafe) private static var thread: Thread?
    nonisolated(unsafe) private static var finished = DispatchSemaphore(value: 0)
    nonisolated(unsafe) private static var exitCode: Int32?

    /// How long a start is given to fail. A configuration hev rejects, or a
    /// descriptor it cannot use, comes back within milliseconds; a start that
    /// is still running after this is a tunnel.
    private static let startGrace: TimeInterval = 0.5

    /// How long a stop waits for the thread after asking hev to quit. hev
    /// closes its sessions and returns promptly; if it does not, the process
    /// is ending anyway and the thread goes with it.
    private static let stopGrace: TimeInterval = 3

    /// The configuration, in hev's YAML. The `misc` numbers are sized for an
    /// extension killed at about 50 MB; each says why below, with the
    /// arithmetic against hev's own rules at the commit scripts/hev-pins.sh
    /// pins. lwIP's sizes, which hev compiles in, are in
    /// scripts/patches/hev-lwipopts-ios.patch, and its pools are sized for
    /// `max-session-count`. HevTunnelConfigurationTests holds both: the
    /// lines, the keys hev reads, the stack hev will really use, and the
    /// pools against the session count.
    ///
    /// `tunnel.mtu` must match the tun's: hev sizes its read buffer by it.
    /// No name or addresses: with a descriptor handed in, hev configures no
    /// interface, and the system already brought this one up. Its lwIP takes
    /// TCP and UDP for any destination, IPv4 or IPv6, and hands IPv6 to the
    /// engine as an IPv6 SOCKS address. No `tunnel.icmp`: with it hev would
    /// answer every ping itself, saying an address is up when nothing asked
    /// it; without it ICMP goes nowhere, so ping gets no answer. Logs
    /// go to stderr, which the provider points at engine.log before any
    /// engine starts.
    static func configuration(socks: Socks, mtu: Int) -> String {
        var lines = [
            "tunnel:",
            "  mtu: \(mtu)",
            "socks5:",
            "  port: \(socks.port)",
            "  address: 127.0.0.1",
            "  udp: 'udp'",
        ]
        if let username = socks.username, !username.isEmpty {
            lines.append("  username: '\(yamlQuoted(username))'")
        }
        if let password = socks.password, !password.isEmpty {
            lines.append("  password: '\(yamlQuoted(password))'")
        }
        lines += [
            "misc:",
            // Every session, each TCP connection and each UDP association,
            // runs on a task stack of its own, mmap'd at this size rounded up
            // to the page, plus a guard page. hev raises a smaller value,
            // without a word, to TASK_STACK_SIZE 20,480 + max(tcp-buffer-size,
            // UDP_BUF_SIZE 1,500 x udp-copy-buffer-nums) (src/hev-config.c,
            // hev_config_parse_doc). Until 2026-11-14 the copy buffers were
            // hev's default ten, so the floor was 20,480 + 15,000 = 35,480 and
            // not this 24,576: 48 KiB of stack and a 16 KiB guard per session
            // on the phone's 16 KiB pages. With two copy buffers (below) the
            // floor is 20,480 + max(4,096, 3,000) = 24,576, exactly this, and
            // hev maps 32 KiB + 16 KiB. Only the pages a task touches are
            // resident: one, or two for a deep path.
            "  task-stack-size: 24576",
            // The ring hev copies a download through, alloca'd on that stack.
            // hev hands it to lwIP without copying and frees it only as the
            // app acknowledges, so these 4 KiB are also all of a download that
            // a session holds, whatever lwIP's send buffer.
            "  tcp-buffer-size: 4096",
            // Datagrams moved per wake of a UDP session, each into a 1,500-byte
            // buffer on its stack (a variable-length array in
            // hev-socks5-session-udp.c). 2 x 1,500 = 3,000 B keeps the floor
            // above at the TCP ring's 24,576 (3 would raise it to 24,980), and
            // should fit in the page the task starts on, where ten (15,000 B)
            // spilled into a second. Darwin has no recvmmsg, so hev reads one
            // datagram per call either way: two per wake costs task switches,
            // not syscalls.
            "  udp-copy-buffer-nums: 2",
            // Sessions, TCP and UDP together. When a new one reaches the count
            // hev ends the least recently active (hev-socks5-tunnel.c), so at
            // most 255 are live. Each holds 16-32 KiB of touched stack and, on
            // an upload the engine has not read yet, up to lwIP's window of
            // 32,764 B in 9 KiB pbufs (about 36 KiB): 4-8 MB of stacks at the
            // cap against 12-25 MB at the 768 before 2026-11-14. lwIP's pcb
            // pools are sized for this count (hev-lwipopts-ios.patch). A phone
            // rarely has a hundred open; a speed test's thirty connections and
            // the resolver's associations (30 s each, below) fit.
            "  max-session-count: 256",
            "  connect-timeout: 5000",
            // TCP too: a connection with no bytes either way for a minute is
            // closed. hev's default is five minutes; a minute is what the
            // xhttp and olcRTC paths were measured with, and each session
            // kept holds a task stack here and buffers in the engine behind.
            // sing-box on its own tun had no such cut, so since 2026-11-13
            // Reality, Hysteria2 and WebTunnel have it too
            // (docs/ios-one-go-runtime.md, "sing-box behind hev"). An app
            // that keeps an idle connection open without keepalives (an SSH
            // session, a long poll) sees it closed after a minute and redials.
            "  read-write-timeout: 60000",
            // UDP sessions idle for half a minute are gone: the system's
            // resolver opens a new one per query, and at hev's default of a
            // minute a speed test's lookups alone kept 117 associations
            // alive in the engine behind (olcbox 1.0.428). Apps that keep
            // a UDP binding alive do so with keepalives well inside 30 s.
            // Paired with sing-box's `udp_timeout` of 30 s on its iOS SOCKS
            // inbound (SingBoxConfig.IOS_SOCKS_UDP_TIMEOUT): sing-box does not
            // notice hev closing an association and keeps it until its own
            // timeout, so the two clocks are the same.
            "  udp-read-write-timeout: 30000",
            "  log-file: stderr",
            "  log-level: warn",
        ]
        return lines.joined(separator: "\n") + "\n"
    }

    /// Single-quoted YAML escapes a quote by doubling it, and nothing else.
    private static func yamlQuoted(_ value: String) -> String {
        value.replacingOccurrences(of: "'", with: "''")
    }

    /// Starts the tunnel on its own thread. Throws when hev returns within the
    /// grace, which is the only way it reports a configuration or descriptor
    /// it will not run with; otherwise the tunnel is up and stays up until
    /// `stop()`.
    static func start(socks: Socks, mtu: Int, tunFd: Int32) throws {
        guard thread == nil else {
            throw failure("hev-socks5-tunnel is already running")
        }
        let config = Array(configuration(socks: socks, mtu: mtu).utf8)
        let done = DispatchSemaphore(value: 0)
        finished = done
        exitCode = nil

        let worker = Thread {
            let code = config.withUnsafeBufferPointer { buffer in
                hev_socks5_tunnel_main_from_str(buffer.baseAddress, UInt32(buffer.count), tunFd)
            }
            exitCode = code
            done.signal()
        }
        worker.name = "org.proofkit.hev"
        // hev's tasks run on stacks it allocates itself; this is only the
        // thread its scheduler and lwIP run on. Generous, because a secondary
        // thread's default is half a megabyte.
        worker.stackSize = 2 << 20
        worker.start()

        if done.wait(timeout: .now() + startGrace) == .success {
            let code = exitCode.map(String.init) ?? "?"
            log.error("hev-socks5-tunnel exited at once, code \(code, privacy: .public)")
            throw failure("hev-socks5-tunnel would not start (exit \(code)); see engine.log")
        }
        thread = worker
        log.info("hev-socks5-tunnel up: socks 127.0.0.1:\(socks.port, privacy: .public) fd \(tunFd, privacy: .public)")
    }

    /// Asks hev to quit and waits, bounded. Safe to call when it never ran:
    /// every tunnel stop calls this, including stops of tunnels sing-box owned.
    static func stop() {
        guard thread != nil else { return }
        hev_socks5_tunnel_quit()
        if finished.wait(timeout: .now() + stopGrace) != .success {
            log.error("hev-socks5-tunnel did not stop within \(Int(stopGrace), privacy: .public) s")
        }
        thread = nil
    }

    static var isRunning: Bool { thread != nil }

    /// What crossed the tun since start, as hev counts it.
    static func stats() -> (txPackets: Int, txBytes: Int, rxPackets: Int, rxBytes: Int) {
        var txPackets = 0, txBytes = 0, rxPackets = 0, rxBytes = 0
        hev_socks5_tunnel_stats(&txPackets, &txBytes, &rxPackets, &rxBytes)
        return (txPackets, txBytes, rxPackets, rxBytes)
    }

    private static func failure(_ reason: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 12,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }
}
