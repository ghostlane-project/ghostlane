import Foundation

/// The opt-in detailed sing-box log — every sniffed name, every route decision —
/// written into libbox's working directory, where the app's scrubbed export picks
/// it up. The Kotlin config decides whether sing-box writes it.
///
/// The engine's stderr redirect does not carry these lines (libbox 1.13 uses
/// it for crash output alone), so without this a routing question on a phone
/// has no answer at all: the first Bypass Russia build sent 2ip.ru through
/// the tunnel and nothing on the device could say which rule had decided that.
/// It is cleared at each session and remains absent while the switch is off.
enum SingBoxDebugLog {
    /// Relative to libbox's working path; sing-box opens it through its file
    /// manager, so this lands next to the rule-sets in `libbox/work`.
    static let fileName = "sing-box.log"

    /// Starts each session on an empty file: sing-box appends, and a debug log
    /// that survived sessions would grow without bound.
    static func reset(workingPath: String) {
        let path = (workingPath as NSString).appendingPathComponent(fileName)
        try? FileManager.default.removeItem(atPath: path)
    }
}
