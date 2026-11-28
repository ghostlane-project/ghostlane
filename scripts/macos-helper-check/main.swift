// The tunnel helper's own handling of requests (desktopApp/tunneldaemon/Daemon.swift),
// driven without its socket: one request a line on standard input, one reply a
// line on standard output.
//
// For a Mac that has no signed app to do the asking and no binary of ours to
// check (a CI runner): hev is the file named on the command line, and whoever
// runs this is taken at their word. Everything else is the helper's code as
// the app's build compiles it (every file of desktopApp/tunneldaemon but its
// main). Built by the callers of scripts/macos-helper-check/hev-tunnel.sh and
// scripts/mac-checks/, and never into the app.
//
//   helper-check <unused> [<uid that asks> [<hev-socks5-tunnel>]]
//
// The first argument is where the sing-box of the helper's old tunnel was
// named; the scripts still pass a path there and nothing reads it.
import Foundation

GroupExec.runIfAsked()
signal(SIGPIPE, SIG_IGN)

// Who a line's program is run as: the socket names its peer, a pipe names nobody.
let asker: uid_t? = CommandLine.arguments.count > 2 ? uid_t(CommandLine.arguments[2]) : nil
let hev: URL? = CommandLine.arguments.count > 3 ? URL(fileURLWithPath: CommandLine.arguments[3]) : nil
let daemon = Daemon(
    child: TunnelChild(hevSource: hev, isTrusted: { _ in true }),
    line: LineChild(isTrusted: { _ in true })
)

signal(SIGTERM, SIG_IGN)
let termination = DispatchSource.makeSignalSource(signal: SIGTERM, queue: DispatchQueue.global())
termination.setEventHandler { daemon.shutDown() }
termination.resume()

while let line = readLine(strippingNewline: true) {
    FileHandle.standardOutput.write(daemon.handle(line, from: asker))
}
