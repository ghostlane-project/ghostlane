import Foundation
import SharedUI

/// The app's own olcRTC: the room check and the probe IosVpnManager asks for
/// (a location's latency, whether its room answers), each one room joined in
/// this process through the Rust core (olc-ffi's OlcrtcRuntime, compiled
/// into the app from the bindings the extension's build generates) and left
/// before the call returns. The tunnel runs in the packet tunnel extension
/// and never here, so nothing here starts or keeps an engine, and the app
/// links no Go.
///
/// Each call has a runtime of its own: the handshake profile is the
/// request's, and calls made side by side share only the core's threads and
/// its one log writer. The probe's SOCKS port is the system's choice (port
/// 0), so no port is picked here and then raced for. The settings are the
/// core's mobile defaults, as gomobile's runtime gave them.
final class SwiftOlcRtcManager: NSObject, @unchecked Sendable, IosOlcRtcBridge {
    private let lock = NSLock()
    /// The writer as the core holds it, kept here too until the app takes it away.
    private var relay: EngineLogRelay?

    /// Where the core's lines go: the app's writer, or the core's own default
    /// (standard error) when nil. The core keeps one writer for the process.
    func setLogWriter(writer: IosLogWriter?) {
        lock.lock()
        defer { lock.unlock() }
        relay = writer.map { EngineLogRelay($0) }
        installEngineLogWriter(relay)
    }

    /// Nothing runs between calls: each check and probe ends inside its own.
    func stop() {}

    /// The room joined, then timed by HTTP requests through it.
    func ping(request: IosOlcRtcCheckRequest) -> IosLongResult {
        probe(request) { runtime in
            try runtime.ping(
                request.carrierName,
                request.transportName,
                request.roomId,
                request.clientId,
                request.keyHex,
                0,
                request.timeoutMillis,
                request.pingUrl,
                Int64(request.vp8Fps),
                Int64(request.vp8BatchSize)
            )
        }
    }

    /// The room joined and its SOCKS port up, timed.
    func check(request: IosOlcRtcCheckRequest) -> IosLongResult {
        probe(request) { runtime in
            try runtime.check(
                request.carrierName,
                request.transportName,
                request.roomId,
                request.clientId,
                request.keyHex,
                0,
                request.timeoutMillis,
                Int64(request.vp8Fps),
                Int64(request.vp8BatchSize)
            )
        }
    }

    /// The milliseconds the probe took, or the core's reason (its text, by
    /// OlcrtcError+Localized.swift) and -1.
    private func probe(
        _ request: IosOlcRtcCheckRequest,
        _ run: (OlcrtcRuntime) throws -> Int64
    ) -> IosLongResult {
        do {
            let runtime = OlcrtcRuntime()
            try runtime.setDtlsProfile(request.dtlsProfile)
            let millis = try run(runtime)
            return IosLongResult(success: true, valueMillis: millis, message: nil)
        } catch {
            return IosLongResult(success: false, valueMillis: -1, message: error.localizedDescription)
        }
    }
}

/// Hands the core's lines to the app's writer as they come: one line each,
/// with its newline, as gomobile's writer did (IosVpnManager trims them).
private final class EngineLogRelay: LogWriter, @unchecked Sendable {
    private let writer: IosLogWriter

    init(_ writer: IosLogWriter) {
        self.writer = writer
    }

    func writeLog(_ msg: String) {
        writer.writeLog(message: msg)
    }
}

/// The core's own `setLogWriter`, at file scope: inside the manager the name
/// is the bridge's method.
private func installEngineLogWriter(_ relay: EngineLogRelay?) {
    setLogWriter(relay)
}
