import Foundation

/// RED stub: the policy is not written yet.
struct MemoryRelief {
    enum Event: Equatable { case warning, critical, sample(footprint: UInt64) }
    struct Action: Equatable {
        var trim: Bool
        var byteBudget: UInt64?
        static let none = Action(trim: false, byteBudget: nil)
    }
    static let trimAbove: UInt64 = 20 * 1_048_576
    static let trimEvery: TimeInterval = 5
    static let warningBudget: UInt64 = 1_000_000
    static let criticalBudget: UInt64 = 262_144
    static let restoreAfter: TimeInterval = 30
    mutating func on(_ event: Event, at now: Date) -> Action { .none }
}
