import Darwin
import Foundation

/// This process's memory as the kernel accounts it (`TASK_VM_INFO`).
///
/// `phys_footprint` is the figure an extension's memory limit is enforced
/// against, and MemoryWatch reads it the same way; the kernel also keeps
/// its peak. The resident size counts every page mapped in, the shared
/// system libraries' among them, and is reported beside it.
struct HostMemory {
    let footprint: UInt64
    let footprintPeak: UInt64
    let resident: UInt64
    let residentPeak: UInt64

    static func read() -> HostMemory? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return HostMemory(
            footprint: info.phys_footprint,
            footprintPeak: UInt64(max(0, info.ledger_phys_footprint_peak)),
            resident: UInt64(info.resident_size),
            residentPeak: UInt64(info.resident_size_peak)
        )
    }
}

/// The footprint every 50 ms while a transfer runs, its highest kept: the
/// kernel's own peak is the judge, this a second reading of it.
final class HostSampler {
    private let lock = NSLock()
    private var highest: UInt64 = 0
    private var running = false
    private let done = DispatchSemaphore(value: 0)

    func start() {
        lock.lock()
        running = true
        lock.unlock()
        let thread = Thread { [self] in
            while true {
                if let now = HostMemory.read() {
                    lock.lock()
                    highest = max(highest, now.footprint)
                    lock.unlock()
                }
                lock.lock()
                let go = running
                lock.unlock()
                if !go { break }
                usleep(50_000)
            }
            done.signal()
        }
        thread.start()
    }

    /// Stops the sampling; the highest footprint it saw.
    func stop() -> UInt64 {
        lock.lock()
        running = false
        lock.unlock()
        done.wait()
        lock.lock()
        defer { lock.unlock() }
        return highest
    }
}
