import Foundation
import SharedUI
import XCTest
// @testable as RoomCheckTests imports the app's Swift: a harness that passes
// says the packages are built for testing.
@testable import RoomCheckCore

/// The harness's own parts, whatever the app's Swift is: the app's Kotlin
/// (SharedUI) and the simulator slice of ghostlane-core link into one test
/// bundle with the link flags the app is to use, values cross from the
/// Kotlin, and the core itself, called directly through the UniFFI bindings
/// the app compiles, joins the stand-in hub's room and ends there, its log
/// line delivered. RoomCheckTests asks the same of the app's
/// SwiftOlcRtcManager: when only those fail, the app's Swift is what fails.
final class HarnessTests: XCTestCase {
    func testTheAppsKotlinLinksAndAnswers() {
        let result = IosLongResult(success: true, valueMillis: 42, message: nil)
        XCTAssertTrue(result.success)
        XCTAssertEqual(result.valueMillis, 42)
        XCTAssertNil(result.message)
        XCTAssertEqual(OlcrtcDtls.shared.profile(chrome: false), "off")
    }

    func testTheCoreJoinsTheHubsRoomAndEndsThere() throws {
        let hub = try PipeHub(.ends("harness: nobody here"))
        defer { hub.stop() }
        let lines = CoreLines()
        RoomCheckCore.setLogWriter(lines)
        defer { RoomCheckCore.setLogWriter(nil) }
        let started = Date()
        XCTAssertThrowsError(
            try OlcrtcRuntime().check(
                "none", "datachannel", hub.url("harness"), "harness-device",
                String(repeating: "0123456789abcdef", count: 4), 0, 10_000, 0, 0
            )
        ) { error in
            print("note the core's own answer in an ending room: \(error.localizedDescription)")
        }
        let took = Date().timeIntervalSince(started)
        XCTAssertEqual(hub.joined, ["harness"])
        XCTAssertLessThan(took, 8, "the room ended; the call waited for its timeout instead")
        let seen = lines.all
        XCTAssertTrue(seen.contains { $0.contains("conference end: harness: nobody here") }, "\(seen)")
    }
}

/// The core's log lines, as they come.
final class CoreLines: LogWriter, @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [String] = []

    var all: [String] {
        lock.lock()
        defer { lock.unlock() }
        return lines
    }

    func writeLog(_ msg: String) {
        lock.lock()
        lines.append(msg)
        lock.unlock()
    }
}
