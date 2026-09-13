import Cores
import Darwin
import Foundation
import os

/// Records how close the extension is to its memory ceiling, where the record
/// survives the extension being killed.
///
/// A packet tunnel provider gets roughly 50 MB and is terminated for exceeding
/// it without being told anything: the process is simply gone, the app's own
/// stop request arrives afterwards, and the system reports the VPN as inactive.
/// From inside, that is indistinguishable from any other sudden death — which is
/// why the olcRTC-with-UDP build's two-second lifetime has stayed a guess rather
/// than a finding.
///
/// So the measurement is written to the App Group four times a second and the
/// last window of it is kept. Whatever is in the file after the process dies is
/// what was true just before it died:
///
///   * footprint climbing toward ~50 MB, headroom falling to near zero
///     → the memory ceiling, and the three-peer-connections theory is right;
///   * footprint flat and headroom wide when the process disappears
///     → it is not memory, and every minute spent on JetsamEvent reports is
///       a minute spent on the wrong question.
///
/// Either answer is worth more than the report we could not find. This is a
/// debugging facility: set `enabled` to false once it has answered.
enum MemoryWatch {

    /// On, for the speed-test kill.
    ///
    /// It was switched off after answering a different question: a start that
    /// died in two seconds, which turned out to be the eight-second olcRTC
    /// timeout rather than memory. This is the other case it was written for —
    /// the extension disappearing a minute into a speed test, with nothing in
    /// the log but the app's own "the packet tunnel is down".
    ///
    /// Measured on a workstation under that load, olcRTC alone held 48.8 MB of
    /// dirty memory before the windows were shrunk and 36.8 MB after, against a
    /// ceiling of about 50 MB that sing-box and Xray share. Whether the smaller
    /// windows are enough is a question only a phone can answer, and this is
    /// what answers it: the file that survives the kill says whether the
    /// footprint was climbing.
    ///
    /// Switch it off again once it has.
    static let enabled = true

    /// 250 ms, because the window being explained is about two seconds long —
    /// a slower tick could miss the whole climb.
    private static let interval: TimeInterval = 0.25

    /// Fifty seconds of history. Enough to cover a start that dies, small
    /// enough that rewriting the file each tick stays a few kilobytes.
    private static let window = 200

    /// Every mutable field below is touched only from this queue, which is what
    /// makes `nonisolated(unsafe)` true rather than merely quiet. The extension
    /// target still builds in Swift 5 mode, where plain statics would compile;
    /// the app target is already on 6, and a prior bump is not the moment to
    /// discover that the debugging instrument is what fails to build.
    private static let queue = DispatchQueue(label: "org.proofkit.memory")
    nonisolated(unsafe) private static var timer: DispatchSourceTimer?
    nonisolated(unsafe) private static var samples: [String] = []
    nonisolated(unsafe) private static var peak: UInt64 = 0
    nonisolated(unsafe) private static var started = Date()
    nonisolated(unsafe) private static var note = "start"
    nonisolated(unsafe) private static var currentFile: URL?

    private static let log = Logger(subsystem: "org.proofkit.app", category: "memory")

    /// What the provider is doing right now, stamped onto each sample so the
    /// curve can be read against the stages in `stage.txt`.
    static func mark(_ stage: String) {
        guard enabled else { return }
        queue.async { note = stage }
    }

    static func start(container: URL) {
        guard enabled else { return }
        queue.async {
            guard timer == nil else { return }
            started = Date()
            samples.removeAll(keepingCapacity: true)
            peak = 0
            let file = container.appendingPathComponent("memory.txt")
            // The run that died is the one worth reading, and this is the
            // moment its trace would be lost: a provider that was killed is
            // followed by a restart, and the restart wrote over the evidence.
            // The app's own death message read the file in time; an exported
            // log, which is what a tester actually sends, arrived afterwards
            // and carried five seconds of a healthy new process instead.
            //
            // One previous run was not enough either. On 2026-09-13 the olcRTC
            // run died under a speed test, the app reconnected, the user then
            // switched transport twice, and by the time the log was exported
            // "the run that ended" was a healthy Hysteria2 session; the trace
            // of the death had been rotated out three restarts earlier. So a
            // run that ended without `stop()` — killed, never told — is kept
            // under its own name and survives every clean restart after it,
            // until the next death replaces it.
            let previous = container.appendingPathComponent("memory-prev.txt")
            let crashed = container.appendingPathComponent("memory-crash.txt")
            let files = FileManager.default
            if files.fileExists(atPath: file.path) {
                let keepAs = endedCleanly(file) ? previous : crashed
                try? files.removeItem(at: keepAs)
                try? files.moveItem(at: file, to: keepAs)
            }
            currentFile = file
            let source = DispatchSource.makeTimerSource(queue: queue)
            source.schedule(deadline: .now(), repeating: interval)
            source.setEventHandler { sample(into: file) }
            timer = source
            source.resume()
            log.info("memory watch started, writing \(file.path, privacy: .public)")
        }
    }

    /// The last line a run that stopped on purpose writes. Its absence from a
    /// trace is the whole finding: the process was killed without being told.
    static let stoppedMarker = "stopped cleanly"

    static func stop() {
        guard enabled else { return }
        queue.async {
            timer?.cancel()
            timer = nil
            // Written last, so a restart can tell this run from one that was
            // killed. The file this belongs to is the one `sample` writes.
            guard let file = currentFile else { return }
            samples.append(String(
                format: "%7.2fs  %@", Date().timeIntervalSince(started), stoppedMarker
            ))
            if samples.count > window { samples.removeFirst(samples.count - window) }
            let text = samples.joined(separator: "\n") + "\n"
            try? Data(text.utf8).write(to: file, options: .atomic)
        }
    }

    /// Whether the trace at `file` ends with the marker `stop()` writes.
    static func endedCleanly(_ file: URL) -> Bool {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return false }
        let last = text.split(separator: "\n").last { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return last?.contains(stoppedMarker) ?? false
    }

    /// One sample: what the system charges the process, and what the Go runtime
    /// admits to holding.
    ///
    /// Both halves are needed and neither is enough. The footprint is what the
    /// process is killed for, but it counts stacks, the allocator's spans and
    /// every byte the process holds that Go never allocated, so it cannot say
    /// whether a climb is the engine's at all. The runtime's own figures say
    /// exactly that, and say whether the ceiling set by `MobileSetMemoryLimit`
    /// is binding — a collection count racing upward is a limit doing work, and
    /// a limit doing too much work is throughput spent on staying alive.
    private static func sample(into file: URL) {
        let footprint = footprintBytes()
        // Bytes the process may still allocate before the system kills it. This
        // is the number that matters: the cap is not a documented constant and
        // differs by device and OS, so headroom is measured, not assumed.
        let headroom = UInt64(os_proc_available_memory())
        peak = max(peak, footprint)

        let line = String(
            format: "%7.2fs  footprint %6.1f MB  headroom %6.1f MB  peak %6.1f MB  %@  |  %@",
            Date().timeIntervalSince(started),
            Double(footprint) / 1_048_576,
            Double(headroom) / 1_048_576,
            Double(peak) / 1_048_576,
            note,
            MobileMemoryStats()
        )
        samples.append(line)
        if samples.count > window { samples.removeFirst(samples.count - window) }

        // Rewritten whole rather than appended: an append that is interrupted
        // by the kill can leave a torn last line, and the last line is the one
        // this exists to read.
        let text = samples.joined(separator: "\n") + "\n"
        try? Data(text.utf8).write(to: file, options: .atomic)
    }

    /// `phys_footprint` is the figure the memory limit is enforced against —
    /// resident size is not, and reading resident size is how a process that is
    /// about to be killed can look comfortable.
    private static func footprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return info.phys_footprint
    }
}
