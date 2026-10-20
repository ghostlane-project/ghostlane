#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// How the extension names itself to the host of a server list: as the app
/// does, word for word.
///
/// RoomKeeper fetches the list on its own, so a host that holds a list to a
/// number of devices judges each of its refreshes apart from the app's, and it
/// must name the same device. Another id would take a second place on the
/// subscription; no id at all is turned away by a host that admits only an app
/// that names its device, and the rooms stop moving.
///
/// What the app sends in its Identity mode (`LocationsDatasource.downloadTextFromUrl`):
/// - `User-Agent`: `olcbox/<version>`, `CurrentAppInfo.userAgent`. The name is
///   `GeneratedAppInfo.NAME` (sharedUI/build.gradle.kts); the version there is
///   MARKETING_VERSION, which is this bundle's CFBundleShortVersionString.
/// - `x-hwid`: the id made at install (`PersistentDeviceIdentityProvider`),
///   which the app hands this process as `OlcrtcEngine.Parameters.clientId`.
/// - `x-device-os`, `x-ver-os`, `x-device-model`: `DeviceDescription.ios.kt`,
///   the version NSProcessInfo reports and utsname's machine. Never UIDevice: a
///   main-actor class, and nothing here runs on the main actor.
///
/// Foundation only, so scripts/test-ios-room-list.sh compiles it with
/// DeviceHeadersTests, which also holds these spellings to the Kotlin above.
///
/// ai-generated: the whole file.
enum DeviceHeaders {
    /// `GeneratedAppInfo.NAME`: what a host shows as the app.
    static let appName = "olcbox"
    /// The longest a device header's value may be: `DEVICE_HEADER_MAX_CHARS`.
    static let maxChars = 64
    /// The longest reason a log line carries: `SubscriptionMetadata.ANNOUNCE_MAX_CHARS`.
    static let reasonMaxChars = 200
    /// The two flags with which a host turns a device away, in the order the
    /// app reads them.
    static let refusalFlags = ["x-hwid-max-devices-reached", "x-hwid-not-supported"]
    /// What a list fetch accepts, as the app's does.
    static let listAccept = "text/plain, text/markdown, application/octet-stream, */*"

    /// RoomKeeper's request for the list at `url`: `Accept`, then every header
    /// of `headers` (`forListFetch`).
    static func listRequest(url: URL, headers: [String: String]) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(listAccept, forHTTPHeaderField: "Accept")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }

    /// The headers of a list fetch from this device, for the app's `id`.
    static func forListFetch(id: String) -> [String: String] {
        forListFetch(
            id: id,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            osVersion: ProcessInfo.processInfo.operatingSystemVersion,
            machine: machine()
        )
    }

    /// The headers of a list fetch, from what they are made of. Each device
    /// header is cut as `asciiHeader` cuts it, and one with nothing left is not
    /// sent. The id goes as the app sends it, and not at all when it is empty
    /// or holds what a header cannot carry: a host reads either as no id.
    static func forListFetch(
        id: String,
        appVersion: String?,
        osVersion: OperatingSystemVersion,
        machine: String
    ) -> [String: String] {
        var headers = ["User-Agent": userAgent(appVersion: appVersion)]
        if !id.isEmpty, id.unicodeScalars.allSatisfy({ (0x20...0x7E).contains($0.value) }) {
            headers["x-hwid"] = id
        }
        let device = [
            ("x-device-os", "iOS"),
            ("x-ver-os", versionText(osVersion)),
            ("x-device-model", machine),
        ]
        for (name, value) in device {
            let kept = asciiHeader(value)
            if !kept.isEmpty { headers[name] = kept }
        }
        return headers
    }

    /// `CurrentAppInfo.userAgent`: `olcbox/<version>`, or the name alone when
    /// the bundle names no version.
    static func userAgent(appVersion: String?) -> String {
        let version = asciiHeader(appVersion ?? "")
        return version.isEmpty ? appName : "\(appName)/\(version)"
    }

    /// As `UIDevice.systemVersion` writes it: `18.0`, `18.0.1`.
    static func versionText(_ version: OperatingSystemVersion) -> String {
        let text = "\(version.majorVersion).\(version.minorVersion)"
        return version.patchVersion > 0 ? "\(text).\(version.patchVersion)" : text
    }

    /// `utsname.machine`, which names the model exactly (`iPhone15,2`); a
    /// simulator answers with its host's architecture.
    static func machine() -> String {
        var system = utsname()
        guard uname(&system) == 0 else { return "" }
        return withUnsafeBytes(of: &system.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// `value` as a device header carries it, cut as ProofKit's server keeps
    /// one (`asciiHeader` in DeviceDescription.kt): only printable ASCII is
    /// kept - anything else is dropped, never turned into a space - then the
    /// first 64 characters, trimmed at the edges. Empty when nothing is left.
    static func asciiHeader(_ value: String) -> String {
        var kept: [Unicode.Scalar] = []
        for scalar in value.unicodeScalars where (0x20...0x7E).contains(scalar.value) {
            if kept.count == maxChars { break }
            kept.append(scalar)
        }
        while kept.first?.value == 0x20 { kept.removeFirst() }
        while kept.last?.value == 0x20 { kept.removeLast() }
        var text = ""
        text.unicodeScalars.append(contentsOf: kept)
        return text
    }

    /// A host's answer that turns this device away, read as the app reads one
    /// (`LocationsDatasource.subscriptionProfile`): HTTP 200 - on a 4xx every
    /// app would keep its old list - whose `x-hwid-max-devices-reached` (the
    /// list is in use on as many devices as it may be, or this one was removed
    /// from it) or `x-hwid-not-supported` (the host admits only an app that
    /// names its device) says `true`. Its body is the host's reason written as
    /// a server line, for the apps that read no header: never a room.
    struct Refusal: Equatable {
        /// The flag that said it.
        let flag: String
        /// The host's reason, from `announce`, as a log line may carry it; nil
        /// when it gave none that reads.
        let reason: String?
    }

    /// The refusal in what a list fetch came back with, or nil. Only an answer
    /// can be one: no error, and a 2xx. Anything else is a failure, whatever
    /// its headers say.
    static func refusal(answer response: URLResponse?, error: Error?) -> Refusal? {
        guard error == nil, let answer = response as? HTTPURLResponse,
              (200..<300).contains(answer.statusCode) else { return nil }
        return refusal(in: answer.allHeaderFields)
    }

    /// The refusal in these response headers, or nil when they carry none.
    /// Names in any case: HTTP/2 writes every name in lower case, HTTP/1.1 as
    /// the host spelled it, and `allHeaderFields` keeps either.
    static func refusal(in headers: [AnyHashable: Any]) -> Refusal? {
        guard let flag = refusalFlags.first(where: { says(header($0, in: headers)) }) else { return nil }
        return Refusal(flag: flag, reason: reason(header("announce", in: headers)))
    }

    /// The value of the header `name`, whatever the case of its name.
    static func header(_ name: String, in headers: [AnyHashable: Any]) -> String? {
        let wanted = name.lowercased()
        for (key, value) in headers {
            guard let key = key.base as? String, key.lowercased() == wanted else { continue }
            return value as? String
        }
        return nil
    }

    /// Whether a flag's value says `true`, whatever its case: the app's `flag`.
    private static func says(_ value: String?) -> Bool {
        value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "true"
    }

    /// The `announce` text as one log line may carry it: the text itself or,
    /// behind `base64:`, the text it encodes (the padding optional, as the app
    /// decodes it), every control and line-breaking character dropped, trimmed,
    /// and cut to 200 UTF-16 units, as the app's `take(200)` counts, never
    /// inside a scalar. Not to 200 characters: a character can hold any number
    /// of combining marks, and the engine's log is bounded by lines, not bytes.
    /// nil for none, for `0` (the host's "take it down"), and for base64 that
    /// does not decode.
    static func reason(_ raw: String?) -> String? {
        guard var text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty, text != "0" else {
            return nil
        }
        if text.lowercased().hasPrefix("base64:") {
            var encoded = text.dropFirst("base64:".count).trimmingCharacters(in: .whitespacesAndNewlines)
            let over = encoded.utf8.count % 4
            if over != 0 { encoded += String(repeating: "=", count: 4 - over) }
            guard let data = Data(base64Encoded: encoded) else { return nil }
            text = String(decoding: data, as: UTF8.self)
        }
        let unwanted = CharacterSet.controlCharacters.union(.newlines)
        let clean = String(text.unicodeScalars.filter { !unwanted.contains($0) })
            .trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return nil }
        var kept = String.UnicodeScalarView()
        var units = 0
        for scalar in clean.unicodeScalars {
            units += scalar.utf16.count
            if units > reasonMaxChars { break }
            kept.append(scalar)
        }
        return String(kept)
    }
}
