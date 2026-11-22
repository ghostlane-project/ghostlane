import Foundation

/// The packet tunnel extension's engines, run on a Mac
/// (scripts/test-ios-engine-on-mac.sh). Not written yet: every check fails.
@main
enum EngineHost {
    static func main() {
        let command = CommandLine.arguments.dropFirst().first ?? "nothing"
        print("FAIL \(command): the engine host does not check this yet")
        exit(1)
    }
}
