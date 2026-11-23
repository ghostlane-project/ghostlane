import Foundation

/// A line whose server is named by a hostname, given its address in the
/// extension, outside the tunnel. Not written yet: every answer here is the
/// one that pins nothing, so its test is red.
enum HostPin {

    static let placeholder = "198.51.100.54"

    struct Request: Equatable {
        let server: String
        let unresolved: String
    }

    enum Outcome: Equatable {
        case unchanged
        case pinned(config: String, ipv6: Bool)
        case refused(String)
    }

    static let unreadable = ""

    static func apply(
        to config: String,
        lookup: (String) -> [String],
        routes: (String) -> Bool
    ) -> Outcome {
        .unchanged
    }

    static func request(in config: String) -> Request? { nil }

    static func pin(_ config: String, address: String) -> String { config }

    static func choose(_ answers: [String], routes: (String) -> Bool) -> String? { nil }

    static let typeA: UInt16 = 1
    static let typeAAAA: UInt16 = 28

    static func query(_ name: String, type: UInt16, id: UInt16) -> [UInt8]? { nil }

    static func addresses(in response: [UInt8], id: UInt16, type: UInt16) -> [String]? { nil }

    static func lookup(
        _ name: String,
        server: String,
        port: UInt16 = 53,
        timeout: Int32 = 1500,
        attempts: Int = 2,
        pin: (Int32) -> Bool
    ) -> [String] {
        []
    }

    static func routes(_ address: String, pin: (Int32) -> Bool) -> Bool { false }
}
