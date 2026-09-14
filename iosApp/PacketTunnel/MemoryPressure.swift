import Cores
import Dispatch
import Foundation
import os

/// Answers the system when it says memory is running short.
///
/// This is not part of `MemoryWatch`, which is a debugging instrument meant to
/// be switched off once it has answered its question. This is a mitigation and
/// stays on.
///
/// The case it exists for is the one 1.0.411 produced: the extension died at
/// 39 MB of a 50 MB allowance, with 10.9 MB still reported as available by
/// `os_proc_available_memory`. Its own ceiling was not the thing it hit. A
/// packet tunnel provider sits low in the jetsam band, so when the *device*
/// runs short — a speed test is one of the few things a phone does that makes
/// that happen — the extension is an early choice, well below its own limit.
///
/// iOS warns before it starts killing. Two things are done with the warning:
///
///   * the runtime hands back everything it has collected but not yet released,
///     because a process judged on its footprint is charged for memory it is no
///     longer using;
///   * the event is written to the diagnostics, which is the point that matters
///     for the next failure. If a death is preceded by a pressure warning, the
///     device was short and the extension was chosen; if it is not, something
///     else killed it and memory is the wrong thread to keep pulling.
enum MemoryPressure {

    private static let queue = DispatchQueue(label: "org.proofkit.memory-pressure")
    nonisolated(unsafe) private static var source: DispatchSourceMemoryPressure?
    private static let log = Logger(subsystem: "org.proofkit.app", category: "memory")

    static func start() {
        queue.async {
            guard source == nil else { return }
            let created = DispatchSource.makeMemoryPressureSource(
                eventMask: [.warning, .critical], queue: queue
            )
            created.setEventHandler {
                // Read through the static rather than capturing the source: the
                // handler is owned by the source, and capturing it here is a
                // cycle that outlives cancellation. Both are touched only on
                // `queue`, so the read is safe.
                let event = source?.data ?? []
                let level = event.contains(.critical) ? "critical" : "warning"
                NetworkDiagnostics.record("memory pressure \(level); returning reserved memory")
                MemoryWatch.mark("pressure-\(level)")
                MemoryWatch.summarizeNow("pressure-\(level)")
                // Stop-the-world, and worth it here: this is the moment the
                // footprint decides whether the process survives.
                MobileFreeOSMemory()
            }
            source = created
            created.resume()
            log.info("memory pressure source armed")
        }
    }

    static func stop() {
        queue.async {
            source?.cancel()
            source = nil
        }
    }
}
