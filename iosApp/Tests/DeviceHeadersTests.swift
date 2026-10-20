import Foundation

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

    static func main() {
        guard CommandLine.arguments.count == 2 else {
            print("usage: device-headers-tests <repository root>")
            exit(2)
        }
        cut()
        headers()
        refusals()
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

    static func headers() {
        let sent = DeviceHeaders.forListFetch(
            id: id,
            appVersion: "1.0.467",
            osVersion: OperatingSystemVersion(majorVersion: 18, minorVersion: 0, patchVersion: 1),
            machine: "iPhone15,2"
        )
        check(sent == [
            "User-Agent": "olcbox/1.0.467",
            "x-hwid": id,
            "x-device-os": "iOS",
            "x-ver-os": "18.0.1",
            "x-device-model": "iPhone15,2",
        ], "the five headers the app sends: \(sent)")

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

    static func refusals() {
        let sentence = "VAYUNET: this link is already in use on its 3 devices. Ask VAYUNET to free one."
        let announce = "base64:" + Data(sentence.utf8).base64EncodedString()

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
        let long = String(repeating: "ж", count: 250)
        check(DeviceHeaders.reason(encode(long)) == String(repeating: "ж", count: 200), "cut to 200 characters")
        check(DeviceHeaders.reasonMaxChars == 200, "200, as the app keeps an announce")
    }

    /// What the Swift copies, read from the Kotlin that is the source of it.
    static func sameAsTheKotlin(root: String) {
        func source(_ path: String) -> String {
            let url = URL(fileURLWithPath: root).appendingPathComponent(path)
            guard let text = try? String(contentsOf: url, encoding: .utf8) else {
                print("FAIL: cannot read \(path) under \(root)")
                exit(1)
            }
            return text
        }
        let gradle = source("sharedUI/build.gradle.kts")
        check(gradle.contains("const val NAME: String = \"\(DeviceHeaders.appName)\""), "the app's name is GeneratedAppInfo.NAME")
        let appInfo = source("sharedUI/src/commonMain/kotlin/org/olcbox/app/AppInfo.kt")
        check(appInfo.contains("val userAgent: String = \"${value.name}/${value.version}\""), "the user agent is name/version")
        let description = source("sharedUI/src/commonMain/kotlin/org/olcbox/app/data/device/DeviceDescription.kt")
        check(description.contains("DEVICE_HEADER_MAX_CHARS = \(DeviceHeaders.maxChars)"), "the same limit")
        check(description.contains("value.filter { it in ' '..'~' }.take(DEVICE_HEADER_MAX_CHARS).trim()"), "the same cut, in the same order")
        let ios = source("sharedUI/src/iosMain/kotlin/org/olcbox/app/data/device/DeviceDescription.ios.kt")
        check(ios.contains("os = \"iOS\""), "the system's name")
        check(ios.contains("val version = \"$majorVersion.$minorVersion\"")
            && ios.contains("if (patchVersion > 0) \"$version.$patchVersion\" else version"), "the version written the same way")
        check(ios.contains("system.machine.toKString()"), "the model is utsname's machine")
        let fetch = source("sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt")
        for name in ["x-hwid", "x-device-os", "x-ver-os", "x-device-model", "announce"] + DeviceHeaders.refusalFlags {
            check(fetch.contains("\"\(name)\""), "the app names \(name) too")
        }
        check(fetch.contains("append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)"), "the app's Identity fetch sends its user agent")
        let config = source("sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt")
        check(config.contains("const val ANNOUNCE_MAX_CHARS = \(DeviceHeaders.reasonMaxChars)"), "the same reason length")
    }
}
