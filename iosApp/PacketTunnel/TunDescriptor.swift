import Darwin
import Foundation
import NetworkExtension

/// The file descriptor behind the system's `NEPacketTunnelFlow`.
///
/// Every engine that owns the tun in-process needs it — sing-box through
/// libbox's `openTun`, hev-socks5-tunnel through its `tun_fd` argument — and
/// there is exactly one way to get it. The alternative, copying every packet
/// between Swift and the engine, is what the memory budget here rules out.
enum TunDescriptor {

    /// The key path first, the socket scan second.
    ///
    /// `socket.fileDescriptor` is not public API. It is, however, how every
    /// libbox-based iOS client does this. It stopped answering on iOS 26, so
    /// it is tried first and no longer trusted.
    static func find(in flow: NEPacketTunnelFlow) -> Int32? {
        if let value = flow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
            return value
        }
        if let number = flow.value(forKeyPath: "socket.fileDescriptor") as? NSNumber {
            return number.int32Value
        }
        return findUtun()
    }

    /// Finds the tunnel descriptor by asking each open socket what interface
    /// it is, rather than by reaching into a private property.
    ///
    /// The extension owns exactly one utun — the one the system just created
    /// for this tunnel — so the first match is the right one. Slower than a
    /// key path and considerably harder for a system update to take away.
    private static func findUtun() -> Int32? {
        let controlProtocol: Int32 = 2   // SYSPROTO_CONTROL
        let interfaceNameOption: Int32 = 2   // UTUN_OPT_IFNAME

        for fd in Int32(0) ..< Int32(1024) {
            var name = [CChar](repeating: 0, count: Int(IFNAMSIZ))
            var length = socklen_t(name.count)
            let result = getsockopt(fd, controlProtocol, interfaceNameOption, &name, &length)
            guard result == 0 else { continue }
            let interface = String(decoding: name.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            if interface.hasPrefix("utun") {
                return fd
            }
        }
        return nil
    }
}
