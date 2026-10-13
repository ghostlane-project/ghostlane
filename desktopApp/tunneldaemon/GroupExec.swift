// The other thing this binary can be asked to do: take a group and become
// another program.
//
// The kill switch has to let the tunnel's own sing-box out and nobody else, and
// the one thing the packet filter can tell a process by is who owns its
// sockets, a user or a group. The tunnel has to be root's, so it is given a
// group nothing else is in (TrafficHold.group). That is one call between fork
// and exec, and Foundation's Process has no place for it. So the daemon starts
// a copy of itself that makes the call and nothing else:
//
//     ProofKitTunnelDaemon --as-group <gid> <program> <arguments…>
//
// It takes the group and replaces itself with <program>. Run by anybody but
// root it can only take a group its caller already has, and what it becomes
// runs as its caller: there is nothing in it to borrow.
import Foundation

enum GroupExec {
    static let flag = "--as-group"

    /// Does not return when the command line asks for the step.
    static func runIfAsked() {
        let arguments = CommandLine.arguments
        guard arguments.count >= 4, arguments[1] == flag else { return }
        guard let group = gid_t(arguments[2]), group != 0 else {
            die("\(flag) takes a group's number, and not root's")
        }
        // As root this sets the real, the effective and the saved group: the
        // one a socket is created with is the effective one.
        guard setgid(group) == 0 else {
            die("setgid(\(group)): \(String(cString: strerror(errno)))")
        }
        var argv: [UnsafeMutablePointer<CChar>?] = arguments[3...].map { strdup($0) }
        argv.append(nil)
        execv(arguments[3], &argv)
        die("exec \(arguments[3]): \(String(cString: strerror(errno)))")
    }

    private static func die(_ message: String) -> Never {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        exit(64)
    }
}
