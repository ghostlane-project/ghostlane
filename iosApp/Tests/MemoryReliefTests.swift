import Foundation

/// Compiled with the production MemoryRelief by scripts/test-ios-memory-relief.sh.
/// Foundation only: no core, no device; a swift.org toolchain runs it too.
@main
enum MemoryReliefTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    static let mib: UInt64 = 1_048_576

    static func main() {
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        func at(_ s: TimeInterval) -> Date { t0.addingTimeInterval(s) }

        // Quiet: a small footprint asks for nothing.
        var relief = MemoryRelief()
        check(relief.on(.sample(footprint: 8 * mib), at: at(0)) == .none, "a small footprint: nothing")

        // Above the line the core is asked to trim, at most once per interval.
        check(relief.on(.sample(footprint: MemoryRelief.trimAbove + 1), at: at(1)) == MemoryRelief.Action(trim: true, byteBudget: nil),
              "above the line: trim(), the budget untouched")
        check(relief.on(.sample(footprint: MemoryRelief.trimAbove + 1), at: at(2)) == .none, "not again inside the interval")
        check(relief.on(.sample(footprint: MemoryRelief.trimAbove + 1), at: at(1 + MemoryRelief.trimEvery + 0.1))
              == MemoryRelief.Action(trim: true, byteBudget: nil), "again after the interval")
        check(MemoryRelief.trimAbove < 24 * mib, "the line is under the extension's 24 MiB target")

        // Pressure: trim at once and lower the byte budget both cores share.
        relief = MemoryRelief()
        check(relief.on(.warning, at: at(0)) == MemoryRelief.Action(trim: true, byteBudget: MemoryRelief.warningBudget),
              "a warning lowers B and trims")
        check(relief.on(.warning, at: at(0.2)) == MemoryRelief.Action(trim: true, byteBudget: nil),
              "a second warning trims; B is already down")
        check(relief.on(.critical, at: at(0.5)) == MemoryRelief.Action(trim: true, byteBudget: MemoryRelief.criticalBudget),
              "critical lowers B further")
        check(relief.on(.warning, at: at(0.7)) == MemoryRelief.Action(trim: true, byteBudget: nil),
              "a warning after critical does not raise B")
        check(MemoryRelief.criticalBudget < MemoryRelief.warningBudget && MemoryRelief.warningBudget < 3_000_000,
              "both under the phone's formula B (3,000,000)")

        // B comes back to the formula (0) once pressure has been quiet a while,
        // never while it is not.
        check(relief.on(.sample(footprint: 8 * mib), at: at(0.7 + MemoryRelief.restoreAfter - 1)) == .none,
              "still inside the quiet period: B stays down")
        check(relief.on(.sample(footprint: 8 * mib), at: at(0.7 + MemoryRelief.restoreAfter + 0.1))
              == MemoryRelief.Action(trim: false, byteBudget: 0), "quiet long enough: B back to the formula")
        check(relief.on(.sample(footprint: 8 * mib), at: at(0.7 + MemoryRelief.restoreAfter + 5)) == .none,
              "restored once, not on every sample")

        // A restore and a trim can come together.
        relief = MemoryRelief()
        _ = relief.on(.warning, at: at(0))
        check(relief.on(.sample(footprint: MemoryRelief.trimAbove + 1), at: at(MemoryRelief.restoreAfter + 1))
              == MemoryRelief.Action(trim: true, byteBudget: 0), "a restore with a trim")

        print("ok")
    }
}
