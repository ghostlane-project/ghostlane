import Foundation
import os

/// A bounded, synchronous trace that also survives a failed carrier bootstrap.
/// Callers pass interface names and error codes, never keys, room URLs or IPs.
enum NetworkDiagnostics {
    private static let lock = NSLock()
    private static let logger = Logger(subsystem: "org.proofkit.app", category: "network-diagnostics")
    // Guarded by `lock`; the annotation is for the day this target moves to
    // Swift 6, whose checker cannot see the lock.
    nonisolated(unsafe) private static var entries = 0
    private static let limit = 400
    private static var file: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.org.proofkit.app")?
            .appendingPathComponent("network-diagnostics.log")
    }

    /// The event `stopTunnel` records. A run whose trace does not end with it
    /// was killed without being told.
    static let stopEventPrefix = "stop reason="

    /// Keeps the previous run before starting a new one.
    ///
    /// The last line of a run that ended is `stop reason=<NEProviderStopReason>`,
    /// which says whether the system stopped the tunnel and why — or, by its
    /// absence, that the process was killed outright and never got to run
    /// stopTunnel at all. Truncating here destroyed exactly that: a provider
    /// that dies is followed within seconds by a restart, so every log anyone
    /// exported described the run that replaced the interesting one.
    ///
    /// And one previous run is not enough: a death, the automatic reconnect
    /// and two transport switches later, "the previous run" was a healthy one
    /// (2026-09-13). A run that ended without the stop line is therefore kept
    /// under its own name, `network-diagnostics-crash.log`, which clean
    /// restarts leave alone and only the next death replaces.
    static func reset() {
        lock.lock()
        defer { lock.unlock() }
        entries = 0
        lastEvent = nil
        repeats = 0
        guard let file else { return }
        let files = FileManager.default
        let directory = file.deletingLastPathComponent()
        let previous = directory.appendingPathComponent("network-diagnostics-prev.log")
        let crashed = directory.appendingPathComponent("network-diagnostics-crash.log")
        if files.fileExists(atPath: file.path) {
            let keepAs = endedCleanly(file) ? previous : crashed
            try? files.removeItem(at: keepAs)
            try? files.moveItem(at: file, to: keepAs)
        }
        try? Data().write(to: file, options: .atomic)
    }

    /// Whether the trace at `file` ends with the line `stopTunnel` writes.
    static func endedCleanly(_ file: URL) -> Bool {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return false }
        let last = text.split(separator: "\n").last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard let last else { return false }
        // "<epoch> stop reason=N"
        return last.split(separator: " ", maxSplits: 1).last?.hasPrefix(stopEventPrefix) ?? false
    }

    // Guarded by `lock`. The path monitor repeats the same status line every
    // few seconds on a flapping network, and 400 of those took under half an
    // hour — after which the trace went silent, stop line included, and a
    // clean stop would have read as a death.
    nonisolated(unsafe) private static var lastEvent: String?
    nonisolated(unsafe) private static var repeats = 0

    static func record(_ event: String) {
        lock.lock()
        defer { lock.unlock() }
        if event == lastEvent, !event.hasPrefix(stopEventPrefix) {
            repeats += 1
            return
        }
        if repeats > 0 {
            append("(previous line repeated \(repeats) more times)", force: true)
            repeats = 0
        }
        lastEvent = event
        append(event, force: event.hasPrefix(stopEventPrefix))
    }

    /// Writes one line. The stop line is always written: it is the one that
    /// says whether the run ended on purpose, and a full trace is no reason
    /// to lose it.
    private static func append(_ event: String, force: Bool) {
        guard entries < limit || force else { return }
        entries += 1
        let line = "\(Date().timeIntervalSince1970) \(event)\n"
        logger.info("\(event, privacy: .public)")
        guard let file, let handle = try? FileHandle(forWritingTo: file) else { return }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: Data(line.utf8))
        } catch {
            logger.error("network trace write failed")
        }
    }
}
