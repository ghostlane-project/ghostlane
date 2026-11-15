import Darwin
import Foundation
import os

// fp-host: the host process of the olcRTC port's footprint harness (plan
// §9.2; lab/olc-ffi/footprint/footprint.py drives it). A plain macOS
// command-line process, not XCTest: it links ghostlane-core in-process, as
// the packet-tunnel extension will, and runs OlcrtcRuntime the way
// OlcrtcEngine.launch runs the Go framework today, against the T1 host's
// hub and the pinned Go server. Beside the engine it does what the
// extension does around it, so their cost lands in the same footprint:
// EngineLog's head and tail windows, rewritten whole at most every 250 ms,
// and MemoryWatch's 4 Hz sample (phys_footprint plus memory_stats(), a
// 200-line window rewritten whole, trim() above 36 MiB at most every 5 s).
// No hev and no xray here: the rows are the engine's.
//
// Commands, one per line on stdin, each answered by one line on stdout:
//   start        start, wait_ready(35000)    READY ms=<n> | ERR start <text>
//   stats        memory_stats()              STATS <json>
//   trim         trim()                      TRIM <bytes>
//   cycle <n>    n × (stop, start, ready)    CYCLED n=<n> ready_min=<ms> ready_max=<ms>
//   stop         stop(5000)                  STOPPED ms=<n>
//   quit         stop when running, exit     BYE
// End of input is a quit. The first line is LOADED pid=<pid>. With --watch,
// each of the watch's samples is also a line of its own on stdout,
// TICK <unix ms> <phys_footprint> <memory_stats json>, between the answers.

struct T1Host: Decodable {
    let room: String
    let key: String
    let transport: String
    let dns: String
}

private let sayLock = NSLock()

/// One whole line on stdout; the watch's ticks and the answers share it.
func say(_ line: String) {
    sayLock.lock()
    print(line)
    fflush(stdout)
    sayLock.unlock()
}

func argument(_ name: String) -> String? {
    let args = CommandLine.arguments
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    return args[i + 1]
}

func physFootprint() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return kr == KERN_SUCCESS ? info.phys_footprint : 0
}

/// EngineLog (OlcrtcEngine.swift): the opening and the latest lines, kept
/// whole, the file rewritten at most every 250 ms.
final class EngineLog: LogWriter, @unchecked Sendable {
    private let file: URL?
    private let queue = DispatchQueue(label: "lab.fp.olcrtc-log")
    private let log = Logger(subsystem: "lab.olc-ffi.footprint", category: "olcrtc")
    private var head: [String] = []
    private var tail: [String] = []
    private var dropped = 0
    private var flushScheduled = false
    private static let headWindow = 150
    private static let tailWindow = 250

    init(file: URL?) { self.file = file }

    func writeLog(_ msg: String) {
        log.info("\(msg, privacy: .public)")
        guard file != nil else { return }
        queue.async { [self] in
            if head.count < Self.headWindow {
                head.append(msg)
            } else {
                tail.append(msg)
                if tail.count > Self.tailWindow {
                    let over = tail.count - Self.tailWindow
                    tail.removeFirst(over)
                    dropped += over
                }
            }
            guard !flushScheduled else { return }
            flushScheduled = true
            queue.asyncAfter(deadline: .now() + 0.25) { [self] in
                flushScheduled = false
                persist()
            }
        }
    }

    func flush() { queue.sync { persist() } }

    private func persist() {
        guard let file else { return }
        var out = head
        if dropped > 0 { out.append("… \(dropped) lines dropped …") }
        out += tail
        try? Data(out.joined().utf8).write(to: file, options: .atomic)
    }
}

/// MemoryWatch (MemoryWatch.swift) with the Rust core's calls in the Go
/// framework's places: memory_stats() for MobileMemoryStats, trim() for
/// MobileFreeOSMemory.
enum Watch {
    private static let interval: TimeInterval = 0.25
    private static let window = 200
    private static let releaseAbove: UInt64 = 36 * 1_048_576
    private static let releaseEvery: TimeInterval = 5
    private static let queue = DispatchQueue(label: "lab.fp.memory")
    nonisolated(unsafe) private static var timer: DispatchSourceTimer?
    nonisolated(unsafe) private static var samples: [String] = []
    nonisolated(unsafe) private static var peak: UInt64 = 0
    nonisolated(unsafe) private static var started = Date()
    nonisolated(unsafe) private static var lastRelease = Date.distantPast
    nonisolated(unsafe) private static var releases = 0

    static func start(file: URL) {
        queue.sync {
            started = Date()
            let source = DispatchSource.makeTimerSource(queue: queue)
            source.schedule(deadline: .now(), repeating: interval)
            source.setEventHandler { sample(into: file) }
            source.resume()
            timer = source
        }
    }

    static func released() -> Int { queue.sync { releases } }

    private static func sample(into file: URL) {
        var note = ""
        let now = Date()
        if physFootprint() > releaseAbove, now.timeIntervalSince(lastRelease) > releaseEvery {
            lastRelease = now
            releases += 1
            _ = trim()
            note = "released"
        }
        let footprint = physFootprint()
        // os_proc_available_memory() is iOS's alone: no headroom on a Mac.
        peak = max(peak, footprint)
        let stats = memoryStats()
        say("TICK \(Int64(Date().timeIntervalSince1970 * 1000)) \(footprint) \(stats)")
        let line = String(
            format: "%7.2fs  footprint %6.1f MB  headroom    n/a  peak %6.1f MB  %@  |  %@",
            Date().timeIntervalSince(started), Double(footprint) / 1_048_576, Double(peak) / 1_048_576, note,
            stats)
        samples.append(line)
        if samples.count > window { samples.removeFirst(samples.count - window) }
        try? Data((samples.joined(separator: "\n") + "\n").utf8).write(to: file, options: .atomic)
    }
}

/// LibboxPlatform.pinToPhysicalInterface's place: there is one interface
/// that matters on the runner, so it only counts.
final class Pin: SocketProtector, @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    func protect(_ fd: Int64) -> Bool {
        lock.lock()
        calls += 1
        lock.unlock()
        return fd >= 0
    }
}

/// RoomKeeper's part: the session's room, counted.
final class Keeper: SessionListener, @unchecked Sendable {
    private let lock = NSLock()
    private var opened = 0
    func onSessionOpened(_ room: String, _ sessionId: String) {
        lock.lock()
        opened += 1
        lock.unlock()
    }
}

func millis(since t: Date) -> Int { Int(Date().timeIntervalSince(t) * 1000) }

func text(_ error: Error) -> String { error.localizedDescription.replacingOccurrences(of: "\n", with: " ") }

guard let t1Path = argument("--t1"), let portText = argument("--port"), let port = Int64(portText),
      let user = argument("--user"), let pass = argument("--pass")
else {
    FileHandle.standardError.write(Data("usage: fp-host --t1 <t1.json> --port <n> --user <u> --pass <p> [--log <file>] [--watch <file>]\n".utf8))
    exit(2)
}
let host: T1Host = {
    do {
        return try JSONDecoder().decode(T1Host.self, from: Data(contentsOf: URL(fileURLWithPath: t1Path)))
    } catch {
        say("ERR load \(text(error))")
        exit(1)
    }
}()

let engineLog = EngineLog(file: argument("--log").map { URL(fileURLWithPath: $0) })
setLogWriter(engineLog)
if let watch = argument("--watch") { Watch.start(file: URL(fileURLWithPath: watch)) }
let runtime = OlcrtcRuntime()
let pin = Pin()
let keeper = Keeper()
runtime.setSessionListener(keeper)
runtime.setProtector(pin)
do {
    // OlcrtcEngine.launch's order, as the simulator XCTest has it.
    runtime.setDebug(false)
    try runtime.setProvider("none")
    runtime.setEngine("interop-pipe", "", "")
    try runtime.setTransport(host.transport)
    try runtime.setDns(host.dns)
    runtime.setUdp(true)
    try runtime.setRoom(host.room)
    runtime.clearFailoverRooms()
    runtime.setDeviceId("lab-footprint")
    try runtime.setKey(host.key)
    try runtime.setSocksListenHost("127.0.0.1")
    try runtime.setSocksPort(port)
    try runtime.setSocksCredentials(user, pass)
} catch {
    say("ERR configure \(text(error))")
    exit(1)
}

func startReady() throws -> Int {
    let t0 = Date()
    try runtime.start()
    try runtime.waitReady(35_000)
    return millis(since: t0)
}

say("LOADED pid=\(getpid())")
while let line = readLine() {
    let words = line.split(separator: " ").map(String.init)
    guard let command = words.first else { continue }
    switch command {
    case "start":
        do {
            let ms = try startReady()
            say("READY ms=\(ms)")
        } catch { say("ERR start \(text(error))") }
    case "stats":
        say("STATS \(memoryStats())")
    case "trim":
        say("TRIM \(trim())")
    case "cycle":
        let n = Int(words.dropFirst().first ?? "") ?? 1
        var readies: [Int] = []
        do {
            for _ in 0..<n {
                try runtime.stop(5_000)
                let ms = try startReady()
                readies.append(ms)
            }
            say("CYCLED n=\(n) ready_min=\(readies.min() ?? 0) ready_max=\(readies.max() ?? 0)")
        } catch {
            say("ERR cycle \(readies.count) \(text(error))")
        }
    case "stop":
        let t0 = Date()
        do {
            try runtime.stop(5_000)
            say("STOPPED ms=\(millis(since: t0))")
        } catch { say("ERR stop \(text(error))") }
    case "quit":
        break
    default:
        say("ERR unknown \(command)")
        continue
    }
    if command == "quit" { break }
}
if runtime.isRunning() { try? runtime.stop(5_000) }
engineLog.flush()
say("BYE releases=\(Watch.released())")
exit(0)
