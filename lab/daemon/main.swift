// The daemon's own handling of requests (Daemon.swift), driven without its
// socket: one request a line on standard input, one reply a line on standard
// output. For a Mac that has no signed app to do the asking and no binary of
// ours to check: the core is the file named on the command line, and whoever
// runs this is taken at their word. Never built into the app.
import Foundation

GroupExec.runIfAsked()
signal(SIGPIPE, SIG_IGN)

let core = URL(fileURLWithPath: CommandLine.arguments[1])
let daemon = Daemon(child: TunnelChild(coreSource: core, isTrusted: { _ in true }))

signal(SIGTERM, SIG_IGN)
let termination = DispatchSource.makeSignalSource(signal: SIGTERM, queue: DispatchQueue.global())
termination.setEventHandler { daemon.shutDown() }
termination.resume()

while let line = readLine(strippingNewline: true) {
    FileHandle.standardOutput.write(daemon.handle(line))
}
