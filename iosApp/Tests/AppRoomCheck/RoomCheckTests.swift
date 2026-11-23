import Foundation
import SharedUI
import XCTest
@testable import AppRoomCheckBridge

/// The app's own olcRTC, in an iPhone simulator: the room check and the
/// probe through SwiftOlcRtcManager, compiled unchanged from iosApp/iosApp
/// with the UniFFI bindings the app compiles, against the simulator slice
/// of ghostlane-core, with the request and the answer in the app's Kotlin
/// (SharedUI), the way IosVpnManager asks. Every room is a stand-in hub of
/// the carrier `none` (PipeHub): it shows the room reached and the call
/// ending there, a request's timeout kept, a refusal before any room, and
/// the engine's lines on the app's writer. A check that succeeds needs an
/// olcRTC server in the room, which no test here has.
final class RoomCheckTests: XCTestCase {
    static let key = String(repeating: "0123456789abcdef", count: 4)

    func request(
        _ room: String,
        carrier: String = "none",
        timeoutMillis: Int64 = 10_000,
        dtlsProfile: String = "off"
    ) -> IosOlcRtcCheckRequest {
        IosOlcRtcCheckRequest(
            carrierName: carrier,
            transportName: "datachannel",
            roomId: room,
            clientId: "app-room-check-device",
            keyHex: Self.key,
            timeoutMillis: timeoutMillis,
            pingUrl: "https://www.google.com/generate_204",
            vp8Fps: 25,
            vp8BatchSize: 1,
            dtlsProfile: dtlsProfile
        )
    }

    /// Runs one call and says what came back, and in how long.
    func timed(_ what: String, _ call: () -> IosLongResult) -> (IosLongResult, TimeInterval) {
        let started = Date()
        let result = call()
        let took = Date().timeIntervalSince(started)
        print("note \(what): success \(result.success), value \(result.valueMillis), in \(Int(took * 1000)) ms: \(result.message ?? "no message")")
        return (result, took)
    }

    func assertFailed(_ result: IosLongResult, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(result.success, file: file, line: line)
        XCTAssertEqual(result.valueMillis, -1, file: file, line: line)
        XCTAssertFalse((result.message ?? "").isEmpty, "a failure says why", file: file, line: line)
    }

    /// The room is reached through the core and the check ends with it: the
    /// hub has the JOIN, the answer is a failure with the engine's reason,
    /// and it came back when the room ended, not at the request's timeout.
    func testACheckReachesTheRoomAndEndsWithIt() throws {
        let hub = try PipeHub(.ends("app room check: nobody here"))
        defer { hub.stop() }
        let (result, took) = timed("check in an ending room") {
            SwiftOlcRtcManager().check(request: request(hub.url("app-check")))
        }
        assertFailed(result)
        XCTAssertFalse(result.message?.contains("unsupported") ?? false)
        XCTAssertEqual(hub.joined, ["app-check"])
        XCTAssertLessThan(took, 8, "the room ended; the check waited for its timeout instead")
    }

    /// The probe (`ping`) joins its room the same way.
    func testAPingReachesTheRoomAndEndsWithIt() throws {
        let hub = try PipeHub(.ends("app room check: nobody here"))
        defer { hub.stop() }
        let (result, took) = timed("ping in an ending room") {
            SwiftOlcRtcManager().ping(request: request(hub.url("app-ping")))
        }
        assertFailed(result)
        XCTAssertEqual(hub.joined, ["app-ping"])
        XCTAssertLessThan(took, 8, "the room ended; the probe waited for its timeout instead")
    }

    /// Nobody answers in a silent room, and the request's timeout is what
    /// ends the check: not at once, and well before the core's own default
    /// of 8 s (the probe's shutdown may add up to 2 s).
    func testACheckInASilentRoomEndsAtTheRequestsTimeout() throws {
        let hub = try PipeHub(.silent)
        defer { hub.stop() }
        let (result, took) = timed("check in a silent room, 2500 ms asked") {
            SwiftOlcRtcManager().check(request: request(hub.url("app-silent"), timeoutMillis: 2_500))
        }
        assertFailed(result)
        XCTAssertTrue(result.message?.contains("timed out") ?? false, result.message ?? "")
        XCTAssertEqual(hub.joined, ["app-silent"])
        XCTAssertGreaterThanOrEqual(took, 2.3)
        XCTAssertLessThan(took, 6.5)
    }

    /// Telemost and WB Stream are carriers this build does not carry: the
    /// check is refused naming the carrier, and no room is dialed.
    func testACarrierThisBuildDoesNotCarryIsRefusedBeforeAnyRoom() throws {
        let hub = try PipeHub(.ends("app room check: unused"))
        defer { hub.stop() }
        for carrier in ["telemost", "wbstream"] {
            let (result, _) = timed("check on \(carrier)") {
                SwiftOlcRtcManager().check(request: request(hub.url("app-\(carrier)"), carrier: carrier))
            }
            assertFailed(result)
            XCTAssertTrue(result.message?.contains(carrier) ?? false, result.message ?? "")
        }
        XCTAssertEqual(hub.joined, [])
    }

    /// The handshake profile is the request's: every profile the app's
    /// setting can name is taken (the room is reached), and one the core
    /// does not know is refused before any room.
    func testTheHandshakeProfileIsTheRequests() throws {
        let hub = try PipeHub(.ends("app room check: nobody here"))
        defer { hub.stop() }
        let manager = SwiftOlcRtcManager()
        for (index, chrome) in [false, true].enumerated() {
            let profile = OlcrtcDtls.shared.profile(chrome: chrome)
            let (result, _) = timed("check with the profile \(profile)") {
                manager.check(request: request(hub.url("app-profile-\(index)"), dtlsProfile: profile))
            }
            assertFailed(result)
            XCTAssertFalse(result.message?.contains("dtls profile") ?? false, result.message ?? "")
        }
        XCTAssertEqual(hub.joined, ["app-profile-0", "app-profile-1"])
        let (refused, _) = timed("check with an unknown profile") {
            manager.check(request: request(hub.url("app-profile-unknown"), dtlsProfile: "no-such-profile"))
        }
        assertFailed(refused)
        XCTAssertTrue(refused.message?.contains("no-such-profile") ?? false, refused.message ?? "")
        XCTAssertEqual(hub.joined, ["app-profile-0", "app-profile-1"])
    }

    /// The engine's lines reach the writer the app sets, one line each with
    /// its newline as gomobile's writer had them (IosVpnManager trims), and
    /// stop once the app takes the writer away.
    func testTheEnginesLinesReachTheAppsWriterUntilItIsTakenAway() throws {
        let hub = try PipeHub(.ends("app room check: the log"))
        defer { hub.stop() }
        let manager = SwiftOlcRtcManager()
        let lines = AppLines()
        manager.setLogWriter(writer: lines)
        _ = timed("check with the app's writer") {
            manager.check(request: request(hub.url("app-log")))
        }
        manager.setLogWriter(writer: nil)
        // A line of the first check's teardown may still be on its way.
        Thread.sleep(forTimeInterval: 0.5)
        let seen = lines.all
        XCTAssertTrue(seen.contains { $0.contains("conference end: app room check: the log") }, "\(seen)")
        XCTAssertTrue(seen.allSatisfy { $0.hasSuffix("\n") }, "\(seen)")
        _ = timed("check after the writer was taken away") {
            manager.check(request: request(hub.url("app-log-after")))
        }
        XCTAssertEqual(lines.all.count, seen.count, "\(lines.all)")
        XCTAssertEqual(hub.joined, ["app-log", "app-log-after"])
    }
}

/// The app's writer, as IosVpnManager hands one over.
final class AppLines: NSObject, IosLogWriter, @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [String] = []

    var all: [String] {
        lock.lock()
        defer { lock.unlock() }
        return lines
    }

    func writeLog(message: String) {
        lock.lock()
        lines.append(message)
        lock.unlock()
    }
}
