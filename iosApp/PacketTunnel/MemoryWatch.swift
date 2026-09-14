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
            samplesSinceSummary = 0
            alarmed = false
            summarizeGoroutines(container: container)
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
    /// Once a minute, what the goroutine count is made of.
    ///
    /// olcbox#26: an eight-hour session reached 700+ goroutines and 7.6 MB of
    /// stacks, against ~100-150 for a live tunnel, and the trace could only
    /// count them. The engine now groups them by where they are blocked, and
    /// that line goes to its own file, appended across runs (a "start" line
    /// per run) and kept for hours, since the leak is slow and the answer is
    /// the composition just before the process dies, not the count.
    private static let summaryEvery = 240 // samples, i.e. one minute at 4 Hz
    private static let summaryWindow = 240 // lines, i.e. four hours
    nonisolated(unsafe) private static var samplesSinceSummary = 0
    nonisolated(unsafe) private static var summaryFile: URL?

    private static func summarizeGoroutines(container: URL) {
        let file = container.appendingPathComponent("goroutines.txt")
        summaryFile = file
        appendSummary("start", to: file)
    }

    /// A summary now, outside the minute cadence: the 1.0.424 xhttp death
    /// took 27 s from start to kill and its first minute line never came.
    /// Called on a memory-pressure event and when the footprint first
    /// crosses the line below, so the export names the goroutines of the
    /// run that is about to die, not of the healthy one that follows it.
    static func summarizeNow(_ why: String) {
        guard enabled else { return }
        queue.async {
            guard let summaryFile else { return }
            appendSummary("\(why): " + MobileGoroutineSummary(), to: summaryFile)
            samplesSinceSummary = 0
        }
    }

    /// Footprint at which one unscheduled summary is written per run. Deaths
    /// have come at 45-50 MB; 38 leaves time for the write.
    private static let alarmFootprint: UInt64 = 38 * 1_048_576
    nonisolated(unsafe) private static var alarmed = false

    private static func appendSummary(_ text: String, to file: URL) {
        let line = String(format: "%7.2fs  %@", Date().timeIntervalSince(started), text)
        var lines = (try? String(contentsOf: file, encoding: .utf8))?
            .split(separator: "\n").map(String.init) ?? []
        lines.append(line)
        if lines.count > summaryWindow { lines.removeFirst(lines.count - summaryWindow) }
        try? Data((lines.joined(separator: "\n") + "\n").utf8).write(to: file, options: .atomic)
    }

    /// Footprint above which idle heap is handed back to the system at once,
    /// and how often at most. The 1.0.427 traces show the runtime holding
    /// 7-10 MB of collected-but-unreleased heap while the footprint sat at
    /// 37-44 MB and the device reported critical pressure; jetsam judges the
    /// footprint, so that reserve is worth returning before the system asks.
    /// FreeOSMemory is a stop-the-world collection - a few milliseconds on a
    /// heap this size - hence the rate limit.
    private static let releaseAbove: UInt64 = 36 * 1_048_576
    private static let releaseEvery: TimeInterval = 5
    nonisolated(unsafe) private static var lastRelease = Date.distantPast

    private static func sample(into file: URL) {
        samplesSinceSummary += 1
        let now = Date()
        if footprintBytes() > releaseAbove, now.timeIntervalSince(lastRelease) > releaseEvery {
            lastRelease = now
            MobileFreeOSMemory()
            note = "released"
        }
        if samplesSinceSummary >= summaryEvery, let summaryFile {
            samplesSinceSummary = 0
            appendSummary(MobileGoroutineSummary(), to: summaryFile)
        }
        let footprint = footprintBytes()
        if !alarmed, footprint >= alarmFootprint, let summaryFile {
            alarmed = true
            appendSummary("footprint \(footprint / 1_048_576) MB: " + MobileGoroutineSummary(), to: summaryFile)
        }
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
