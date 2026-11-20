import Foundation

/// When the extension asks ghostlane-core to give memory back, and how far it
/// lowers the core's byte budget (design D11).
///
/// Two levers, both the core's since step C (no Go runtime is left to
/// collect):
///   * `trim()` drops the core's pools and, on Apple targets, asks the system
///     allocator for its free pages (`malloc_zone_pressure_relief`); it
///     returns the bytes given back. On the Mac lab it took a footprint of
///     9-11 MiB after a burst down to 4.9 (p3/m9-ffi.md §9.2).
///   * `setByteBudget(bytes)` sets B, the one process-wide budget xray's
///     relays and h2 windows and olcRTC's copies and UDP take their bytes
///     beyond a 4 KiB floor from. A lower B binds as held bytes come back;
///     nothing held is revoked, no connection is refused, they read less.
///     0 returns B to the memory-class formula (3,000,000 bytes on a phone).
///
/// Foundation only, so a swift.org toolchain runs its test
/// (scripts/test-ios-memory-relief.sh). MemoryWatch feeds it samples,
/// MemoryPressure the system's events; both act on its answer.
struct MemoryRelief {

    enum Event: Equatable {
        case warning
        case critical
        /// MemoryWatch's 4 Hz sample of `phys_footprint`.
        case sample(footprint: UInt64)
    }

    /// What to ask of the core. `byteBudget` nil leaves B as it is.
    struct Action: Equatable {
        var trim: Bool
        var byteBudget: UInt64?
        static let none = Action(trim: false, byteBudget: nil)
    }

    /// Footprint above which the core is asked to trim, and how often at
    /// most: under the 24 MiB the whole extension is to stay within, with
    /// room left for the burst a trim follows.
    static let trimAbove: UInt64 = 20 * 1_048_576
    static let trimEvery: TimeInterval = 5

    /// B under a pressure warning, and under critical pressure.
    static let warningBudget: UInt64 = 1_000_000
    static let criticalBudget: UInt64 = 262_144

    /// Quiet time after the last pressure event before B goes back to the
    /// formula.
    static let restoreAfter: TimeInterval = 30

    private var lastTrim = Date.distantPast
    private var lastPressure = Date.distantPast
    /// The B this policy set, nil while B is the formula's.
    private var lowered: UInt64?

    mutating func on(_ event: Event, at now: Date) -> Action {
        switch event {
        case .warning, .critical:
            lastPressure = now
            lastTrim = now
            let target = event == .critical ? Self.criticalBudget : Self.warningBudget
            if let lowered, lowered <= target {
                return Action(trim: true, byteBudget: nil)
            }
            lowered = target
            return Action(trim: true, byteBudget: target)
        case .sample(let footprint):
            var action = Action.none
            if lowered != nil, now.timeIntervalSince(lastPressure) > Self.restoreAfter {
                lowered = nil
                action.byteBudget = 0
            }
            if footprint > Self.trimAbove, now.timeIntervalSince(lastTrim) > Self.trimEvery {
                lastTrim = now
                action.trim = true
            }
            return action
        }
    }
}
