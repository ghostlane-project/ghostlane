import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Compiled with the production DeviceHeaders by scripts/test-ios-room-list.sh,
/// which passes the repository's root: the Swift copy is held to the Kotlin it
/// copies, so the room keeper's refresh names the device the app's fetch does.
/// No Cores framework, signing identity or device is needed.
///
/// ai-generated: the whole file.
@main
enum DeviceHeadersTests {
    static func check(_ condition: Bool, _ message: String, line: Int = #line) {
        if !condition {
            print("FAIL line \(line): \(message)")
            exit(1)
        }
    }

    static let id = "install-0123456789abcdef0123456789abcdef"
    static let sentence = "VAYUNET: this link is already in use on its 3 devices. Ask VAYUNET to free one."
    static let announce = "base64:" + Data(sentence.utf8).base64EncodedString()

    static func main() {
        guard CommandLine.arguments.count == 2 else {
            print("usage: device-headers-tests <repository root>")
            exit(2)
        }
        cut()
        headers()
        requests()
        refusals()
        answers()
        reasons()
        sameAsTheKotlin(root: CommandLine.arguments[1])
        print("DeviceHeadersTests: all passed")
    }

    /// DeviceDescriptionTest.kt's cases, word for word.
    static func cut() {
        check(DeviceHeaders.asciiHeader(String(repeating: "a", count: 70)) == String(repeating: "a", count: 64), "cut to 64")
        check(DeviceHeaders.asciiHeader(String(repeating: "a", count: 64)) == String(repeating: "a", count: 64), "64 is kept")
        check(DeviceHeaders.maxChars == 64, "the limit is 64")
        check(DeviceHeaders.asciiHeader("Café") == "Caf", "a letter outside ASCII is dropped")
        check(DeviceHeaders.asciiHeader("Pixel\t 7é Pro") == "Pixel 7 Pro", "a tab is dropped, never a space")
        check(DeviceHeaders.asciiHeader("a\nb\r\u{0}c\u{7f}d\u{1b}e") == "abcde", "control characters are dropped")
        check(DeviceHeaders.asciiHeader(String(repeating: "x", count: 60) + "é" + String(repeating: "y", count: 10))
            == String(repeating: "x", count: 60) + "yyyy", "64 counted after the cleaning")
        check(DeviceHeaders.asciiHeader("") == "", "empty stays empty")
        check(DeviceHeaders.asciiHeader("   ") == "", "blank becomes empty")
        check(DeviceHeaders.asciiHeader("Тел") == "", "nothing printable left")
        check(DeviceHeaders.asciiHeader("  iPhone 13  ") == "iPhone 13", "trimmed at the edges")
        // A grapheme of several scalars: each scalar is judged, as Kotlin
        // judges each UTF-16 unit, so the base letter stays.
        check(DeviceHeaders.asciiHeader("e\u{301}x") == "ex", "a combining mark is dropped, its letter kept")
    }

    static let fiveHeaders = DeviceHeaders.forListFetch(
        id: id,
        appVersion: "1.0.467",
        osVersion: OperatingSystemVersion(majorVersion: 18, minorVersion: 0, patchVersion: 1),
        machine: "iPhone15,2"
    )

    static func headers() {
        check(fiveHeaders == [
            "User-Agent": "olcbox/1.0.467",
            "x-hwid": id,
            "x-device-os": "iOS",
            "x-ver-os": "18.0.1",
            "x-device-model": "iPhone15,2",
        ], "the five headers the app sends: \(fiveHeaders)")

        let version = { (major: Int, minor: Int, patch: Int) in
            DeviceHeaders.versionText(OperatingSystemVersion(majorVersion: major, minorVersion: minor, patchVersion: patch))
        }
        check(version(18, 0, 0) == "18.0", "no patch: major.minor")
        check(version(17, 4, 0) == "17.4", "17.4")
        check(version(26, 1, 2) == "26.1.2", "a patch is written")

        // LocationsRepositoryImplTest.subscriptionDeviceHeadersAreCutAndAnEmptyOneIsNotSent.
        let longModel = DeviceHeaders.forListFetch(
            id: id,
            appVersion: "1.0.467",
            osVersion: OperatingSystemVersion(majorVersion: 18, minorVersion: 0, patchVersion: 0),
            machine: "iPhone17,1 ✓" + String(repeating: "x", count: 70)
        )
        check(longModel["x-device-model"] == "iPhone17,1 " + String(repeating: "x", count: 53),
              "the model is cut: \(String(describing: longModel["x-device-model"]))")
        check(DeviceHeaders.forListFetch(id: id, appVersion: "1", osVersion: OperatingSystemVersion(), machine: "")["x-device-model"] == nil,
              "nothing left: no header, not an empty one")

        // The id goes as the app sends it, or not at all.
        let none = DeviceHeaders.forListFetch(id: "", appVersion: "1", osVersion: OperatingSystemVersion(), machine: "m")
        check(none["x-hwid"] == nil, "an empty id is not sent")
        check(DeviceHeaders.forListFetch(id: "bad\nid", appVersion: "1", osVersion: OperatingSystemVersion(), machine: "m")["x-hwid"] == nil,
              "an id a header cannot carry is not sent")
        check(none["x-device-os"] == "iOS", "the device headers do not depend on the id")

        check(DeviceHeaders.userAgent(appVersion: "1.0.467") == "olcbox/1.0.467", "name/version")
        check(DeviceHeaders.userAgent(appVersion: nil) == "olcbox", "no version: the name alone")
        check(DeviceHeaders.userAgent(appVersion: " ") == "olcbox", "a blank version: the name alone")

        // This machine, as the extension reads it: whatever the platform, the
        // same five names, the system and a version.
        let live = DeviceHeaders.forListFetch(id: id)
        check(live["x-hwid"] == id, "the id given")
        check(live["x-device-os"] == "iOS", "iOS")
        check(!(live["x-ver-os"] ?? "").isEmpty, "a system version")
        check(!DeviceHeaders.machine().isEmpty, "utsname names a machine")
        check(live["User-Agent"]?.hasPrefix("olcbox") == true, "the app's name")
    }

    /// The request RoomKeeper sends, built by the function it calls: a real
    /// URLRequest, read back through Foundation's own lookup.
    static func requests() {
        let url = URL(string: "https://sub.example.test/sub/token?format=ghostlane")!
        let request = DeviceHeaders.listRequest(url: url, headers: fiveHeaders)
        check(request.url == url, "the list's own address")
        check((request.httpMethod ?? "GET") == "GET", "a GET")
        // listAccept is held to the app's own Accept in sameAsTheKotlin.
        check(request.value(forHTTPHeaderField: "Accept") == DeviceHeaders.listAccept, "Accept is listAccept")
        for (name, value) in fiveHeaders {
            check(request.value(forHTTPHeaderField: name) == value, "\(name) on the request: \(String(describing: request.value(forHTTPHeaderField: name)))")
            check(request.value(forHTTPHeaderField: name.uppercased()) == value, "\(name) read in any case")
        }
        check(request.value(forHTTPHeaderField: "x-hwid") == id, "the app's id")
        check(request.value(forHTTPHeaderField: "User-Agent") == "olcbox/1.0.467", "the app's user agent, no browser's")
        check(request.allHTTPHeaderFields?.count == 6, "Accept and the five, nothing else: \(String(describing: request.allHTTPHeaderFields))")

        // An empty id leaves the header off the request, not empty on it.
        let noId = DeviceHeaders.listRequest(
            url: url,
            headers: DeviceHeaders.forListFetch(id: "", appVersion: "1", osVersion: OperatingSystemVersion(), machine: "m")
        )
        check(noId.value(forHTTPHeaderField: "x-hwid") == nil, "no x-hwid at all")
    }

    static func refusals() {
        // As ProofKit's server writes a refusal: the flags in lower case.
        let full: [AnyHashable: Any] = [
            "x-hwid-active": "true",
            "x-hwid-max-devices-reached": "true",
            "x-hwid-limit": "true",
            "announce": announce,
            "profile-update-interval": "1",
        ]
        check(DeviceHeaders.refusal(in: full) == DeviceHeaders.Refusal(flag: "x-hwid-max-devices-reached", reason: sentence),
              "a limit reached, with its reason: \(String(describing: DeviceHeaders.refusal(in: full)))")

        // Any case of the name and of the value.
        let shouted: [AnyHashable: Any] = ["X-Hwid-Not-Supported": " TRUE ", "Announce": announce]
        check(DeviceHeaders.refusal(in: shouted) == DeviceHeaders.Refusal(flag: "x-hwid-not-supported", reason: sentence),
              "a name in any case, a value in any case")
        check(DeviceHeaders.refusal(in: ["x-hwid-not-supported": "True"])
            == DeviceHeaders.Refusal(flag: "x-hwid-not-supported", reason: nil), "no announce: a refusal still, with no reason")

        // Both: the app reads the limit first.
        check(DeviceHeaders.refusal(in: ["x-hwid-not-supported": "true", "x-hwid-max-devices-reached": "true"])?.flag
            == "x-hwid-max-devices-reached", "the limit first")

        // Not a refusal: the list is served.
        check(DeviceHeaders.refusal(in: [:]) == nil, "no headers")
        check(DeviceHeaders.refusal(in: ["x-hwid-active": "true", "x-hwid-limit": "true", "announce": announce]) == nil,
              "the two companions alone refuse nothing")
        check(DeviceHeaders.refusal(in: ["x-hwid-max-devices-reached": "false", "x-hwid-not-supported": "0"]) == nil,
              "a flag that does not say true")
        check(DeviceHeaders.refusal(in: ["x-hwid-max-devices-reached": "yes"]) == nil, "only true is true")
    }

    /// What URLSession hands RoomKeeper: a real HTTPURLResponse, its headers
    /// through `allHeaderFields` (bridged keys), in the call the keeper makes.
    static func answers() {
        let url = URL(string: "https://sub.example.test/sub/token")!
        func answer(_ status: Int, _ fields: [String: String]) -> HTTPURLResponse {
            HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: fields)!
        }
        let refused = answer(200, [
            "X-HWID-Active": "true",
            "X-HWID-Max-Devices-Reached": "True",
            "X-Hwid-Limit": "true",
            "ANNOUNCE": announce,
            "Content-Type": "text/plain; charset=utf-8",
        ])
        let expected = DeviceHeaders.Refusal(flag: "x-hwid-max-devices-reached", reason: sentence)
        check(DeviceHeaders.refusal(in: refused.allHeaderFields) == expected,
              "read from allHeaderFields: \(String(describing: DeviceHeaders.refusal(in: refused.allHeaderFields)))")
        check(DeviceHeaders.refusal(answer: refused, error: nil) == expected, "the keeper's call reads it too")
        check(DeviceHeaders.refusal(answer: answer(200, ["x-HWID-not-SUPPORTED": "true"]), error: nil)?.flag == "x-hwid-not-supported",
              "a name in mixed case")

        // Only an answer is a refusal: anything else takes the failure path.
        let flagged = ["X-HWID-Max-Devices-Reached": "true", "Announce": announce]
        check(DeviceHeaders.refusal(answer: answer(403, flagged), error: nil) == nil, "a 4xx is a failure, whatever it says")
        check(DeviceHeaders.refusal(answer: answer(500, flagged), error: nil) == nil, "a 5xx is a failure")
        check(DeviceHeaders.refusal(answer: answer(200, flagged), error: NSError(domain: "test", code: 1)) == nil,
              "an error is a failure")
        check(DeviceHeaders.refusal(answer: nil, error: nil) == nil, "no response at all")
        let served = answer(200, ["X-Hwid-Active": "true", "Subscription-Userinfo": "upload=0; download=0; total=0"])
        check(DeviceHeaders.refusal(answer: served, error: nil) == nil, "a served list is no refusal")
    }

    static func reasons() {
        let encode = { (text: String) in "base64:" + Data(text.utf8).base64EncodedString() }
        check(DeviceHeaders.reason("Plain words") == "Plain words", "plain text is the text")
        check(DeviceHeaders.reason(encode("Сервис: лимит устройств")) == "Сервис: лимит устройств", "base64 of any language")
        check(DeviceHeaders.reason("BASE64:" + Data("Upper".utf8).base64EncodedString()) == "Upper", "the prefix in any case")
        // "abcd" is "YWJjZA==": the padding is optional, as Kotlin decodes it.
        check(DeviceHeaders.reason("base64:YWJjZA") == "abcd", "padding optional")
        check(DeviceHeaders.reason("base64: YWJjZA== ") == "abcd", "spaces around the payload")
        check(DeviceHeaders.reason("base64:%%%") == nil, "base64 that does not decode")
        check(DeviceHeaders.reason("0") == nil, "0 takes the note down")
        check(DeviceHeaders.reason(nil) == nil, "none")
        check(DeviceHeaders.reason("  ") == nil, "blank")
        // One log line: no control, line-breaking or direction-changing
        // character reaches it.
        check(DeviceHeaders.reason(encode("a\u{7}b\nc\r\u{2028}d\u{202E}e\u{0}f")) == "abcdef", "controls and breaks dropped")
        check(DeviceHeaders.reason(encode("\n\t free one \n")) == "free one", "trimmed after the cleaning")
        check(DeviceHeaders.reasonMaxChars == 200, "200, as the app keeps an announce")

        // Bounded in UTF-16 units, as the app's take(200) counts, never inside
        // a scalar: one character of five hundred combining marks is cut too.
        let long = String(repeating: "ж", count: 250)
        check(DeviceHeaders.reason(encode(long)) == String(repeating: "ж", count: 200), "cut to 200 units")
        let marks = DeviceHeaders.reason(encode("e" + String(repeating: "\u{301}", count: 500))) ?? ""
        check(marks.utf16.count == 200 && marks.unicodeScalars.count == 200, "one huge character is cut: \(marks.utf16.count) units")
        let faces = DeviceHeaders.reason(encode(String(repeating: "😀", count: 150))) ?? ""
        check(faces == String(repeating: "😀", count: 100), "a pair of units is kept whole or dropped whole: \(faces.utf16.count) units")
        let odd = DeviceHeaders.reason(encode("a" + String(repeating: "😀", count: 100))) ?? ""
        check(odd == "a" + String(repeating: "😀", count: 99), "never half a pair: \(odd.utf16.count) units")
    }

    /// What the Swift copies, read from the Kotlin that is the source of it.
    /// Values, not lines of code: a rename, a moved constant or another
    /// spelling of the same template still passes; another value does not.
    static func sameAsTheKotlin(root: String) {
        let base = URL(fileURLWithPath: root)
        func read(_ path: String) -> String {
            guard let text = try? String(contentsOf: base.appendingPathComponent(path), encoding: .utf8) else {
                print("FAIL: cannot read \(path) under \(root)")
                exit(1)
            }
            return text
        }
        /// Every .kt file of a source set, by its path.
        func sources(_ set: String) -> [String: String] {
            let dir = "sharedUI/src/\(set)/kotlin"
            guard let walk = FileManager.default.enumerator(atPath: base.appendingPathComponent(dir).path) else {
                print("FAIL: cannot list \(dir) under \(root)")
                exit(1)
            }
            var found: [String: String] = [:]
            while let path = walk.nextObject() as? String {
                if path.hasSuffix(".kt") { found[path] = read("\(dir)/\(path)") }
            }
            check(!found.isEmpty, "\(dir) holds Kotlin")
            return found
        }
        /// The first group of every match of `pattern` in `text`.
        func captures(_ pattern: String, in text: String) -> [String] {
            guard let regex = try? NSRegularExpression(pattern: pattern) else {
                print("FAIL: the pattern \(pattern) does not compile")
                exit(1)
            }
            return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { match in
                guard match.numberOfRanges > 1, let range = Range(match.range(at: 1), in: text) else { return nil }
                return String(text[range])
            }
        }
        /// The one capture of `pattern` in `text`. The Kotlin of a source set is
        /// joined in no fixed order (a dictionary's), so a value found twice
        /// could be either: none or more than one fails, with the count.
        func one(_ pattern: String, in text: String, _ what: String, line: Int = #line) -> String? {
            let found = captures(pattern, in: text)
            check(found.count == 1, "\(what): \(found.count) matches, want 1: \(found)", line: line)
            return found.first
        }
        func has(_ pattern: String, in text: String) -> Bool {
            (try? NSRegularExpression(pattern: pattern))?
                .firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        }

        let common = sources("commonMain")
        let all = common.values.joined(separator: "\n")

        // The app's name in its user agent, and the user agent's shape.
        let name = one(#"\bNAME\b\s*(?::\s*String)?\s*=\s*"([^"]*)""#, in: read("sharedUI/build.gradle.kts"), "GeneratedAppInfo.NAME")
        check(name == DeviceHeaders.appName, "GeneratedAppInfo.NAME is \(String(describing: name))")
        let agent = one(#"\buserAgent\b\s*(?::\s*String)?\s*=\s*"([^"]*)""#, in: all, "the user agent's template") ?? ""
        check(has(#"(?i)^\$\{?[\w.]*name\}?/\$\{?[\w.]*version\}?$"#, in: agent), "the user agent is name/version: \(agent)")

        // The cut: its length and its range of characters.
        let limit = one(#"\bDEVICE_HEADER_MAX_CHARS\b\s*(?::\s*Int)?\s*=\s*(\d+)"#, in: all, "DEVICE_HEADER_MAX_CHARS")
        check(limit == String(DeviceHeaders.maxChars), "DEVICE_HEADER_MAX_CHARS is \(String(describing: limit))")
        check(has(#"' '\s*\.\.\s*'~'"#, in: all), "the cut keeps ' '..'~', printable ASCII")
        let announceLimit = one(#"\bANNOUNCE_MAX_CHARS\b\s*(?::\s*Int)?\s*=\s*(\d+)"#, in: all, "ANNOUNCE_MAX_CHARS")
        check(announceLimit == String(DeviceHeaders.reasonMaxChars), "ANNOUNCE_MAX_CHARS is \(String(describing: announceLimit))")

        // The list fetch itself, the one file that has downloadTextFromUrl: the
        // names on the wire, the Accept and the user agent are read where the
        // request is made, not anywhere in the Kotlin. (The update check sent
        // x-hwid too until 2026-10-20, and a name found there passed.)
        let fetches = common.filter { has(#"fun\s+downloadTextFromUrl\b"#, in: $0.value) }
        check(fetches.count == 1, "one list fetch: \(fetches.keys.sorted())")
        let fetch = fetches.first?.value ?? ""
        for header in ["x-hwid", "x-device-os", "x-ver-os", "x-device-model", "announce"] + DeviceHeaders.refusalFlags {
            check(fetch.contains("\"\(header)\""), "the list fetch names \(header) too")
        }
        check(fetch.contains("CurrentAppInfo.userAgent"), "the list fetch sends CurrentAppInfo.userAgent beside the device headers")
        let accept = one(#"HttpHeaders\.Accept,\s*"([^"]*)""#, in: fetch, "the list fetch's Accept")
        check(accept == DeviceHeaders.listAccept, "Accept is the app's: \(String(describing: accept))")

        // iOS's description: the system's name, the version's form, the model.
        let ios = sources("iosMain").values.filter { has(#"actual\s+fun\s+deviceDescription\b"#, in: $0) }
        check(ios.count == 1, "one iOS deviceDescription: \(ios.count)")
        let description = ios.first ?? ""
        check(one(#"\bos\s*=\s*"([^"]*)""#, in: description, "the iOS description's os") == "iOS", "the system's name is iOS")
        check(has(#"\$\{?majorVersion\}?\.\$\{?minorVersion\}?"#, in: description)
            && has(#"patchVersion\s*>\s*0"#, in: description), "major.minor, and the patch only when it is not 0")
        check(has(#"\.machine\b"#, in: description), "the model is utsname's machine")
    }
}
