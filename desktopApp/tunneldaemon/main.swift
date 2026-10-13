// ProofKitTunnelDaemon — the only part of ProofKit that runs as root.
//
// It runs the bundled sing-box with a tun inbound, on behalf of a caller whose
// code signature it has verified, and holds what only root can hold for that
// tunnel: the system's resolver while it runs (SystemNames) and the kill
// switch's block (TrafficHold). It parses no share links and speaks none of our
// protocols; what the tunnel is to do is in a config the app writes and this
// daemon passes on byte for byte. Everything else stays in the app, where it
// runs as the user — the smaller this is, the smaller the thing running as root.
import Foundation

// Before anything else: started by itself to take a group, this is not the
// daemon and must not open its socket.
GroupExec.runIfAsked()

let socketPath = "/var/run/org.olcbox.app.tunneld.sock"
let daemon = Daemon()

// A client that hangs up before its reply is written must cost that reply, not
// the daemon.
signal(SIGPIPE, SIG_IGN)

// launchd stops a daemon with SIGTERM: at a shutdown, when the user switches it
// off in Login Items, when the app that owns it is deleted. The main thread
// sits in accept(), so the signal is taken as an event on a queue of its own.
signal(SIGTERM, SIG_IGN)
let termination = DispatchSource.makeSignalSource(signal: SIGTERM, queue: DispatchQueue.global())
termination.setEventHandler { daemon.shutDown() }
termination.resume()

/// Read until the newline that ends a request.
///
/// Not one `recv`: a stream socket may hand over a request in pieces, and a
/// config with a dozen route exclusions is comfortably large enough for that to
/// happen. A single read would parse half a request as a whole one and answer
/// "unparseable" to something perfectly well formed.
///
/// Up to 4 MiB: a start under "only blocked sites through the tunnel" carries the
/// blocked lists, about 860 KB in base64 today, and they grow. Older daemons stop
/// at 1 MiB, which the app keeps a start under (MacOsTunControllerTest).
func readRequest(_ fd: Int32) -> String? {
    var collected = Data()
    var chunk = [UInt8](repeating: 0, count: 16 * 1024)
    while collected.count < 4 * 1_048_576 {
        let read = recv(fd, &chunk, chunk.count, 0)
        if read <= 0 { break }
        collected.append(contentsOf: chunk[0..<read])
        if collected.last == UInt8(ascii: "\n") { break }
        if collected.contains(UInt8(ascii: "\n")) { break }
    }
    guard !collected.isEmpty else { return nil }
    return String(data: collected, encoding: .utf8)
}

func send(_ data: Data, to fd: Int32) {
    data.withUnsafeBytes { buffer in
        var offset = 0
        while offset < buffer.count {
            let written = write(fd, buffer.baseAddress!.advanced(by: offset), buffer.count - offset)
            if written <= 0 { return }
            offset += written
        }
    }
}

// launchd does not remove a socket left behind by a killed daemon, and bind()
// on an existing path fails with EADDRINUSE — which reads as "already running"
// and is not.
unlink(socketPath)

let listener = socket(AF_UNIX, SOCK_STREAM, 0)
guard listener >= 0 else { exit(1) }

var address = sockaddr_un()
address.sun_family = sa_family_t(AF_UNIX)
withUnsafeMutablePointer(to: &address.sun_path) { pointer in
    pointer.withMemoryRebound(to: CChar.self, capacity: MemoryLayout.size(ofValue: address.sun_path)) { path in
        _ = socketPath.withCString { strncpy(path, $0, MemoryLayout.size(ofValue: address.sun_path) - 1) }
    }
}
let addressSize = socklen_t(MemoryLayout<sockaddr_un>.size)
let bound = withUnsafePointer(to: &address) { pointer in
    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, addressSize) }
}
guard bound == 0, listen(listener, 8) == 0 else { exit(1) }

// 0666, deliberately. The check that matters is the code-signature one below,
// and a mode excluding non-admin users would only make the app fail differently
// for them — with a permission error instead of an honest refusal.
chmod(socketPath, 0o666)

while true {
    let client = accept(listener, nil, nil)
    if client < 0 { continue }

    guard PeerAuthority.isTrusted(fd: client) else {
        send(Data("{\"ok\":false,\"error\":\"unauthorized\",\"logTail\":\"\"}\n".utf8), to: client)
        close(client)
        continue
    }

    if let line = readRequest(client) {
        send(daemon.handle(line.trimmingCharacters(in: .whitespacesAndNewlines)), to: client)
    }
    close(client)
    // KeepAlive: launchd starts the binary now on disk in this one's place.
    if daemon.quitAfterReply { exit(0) }
}
