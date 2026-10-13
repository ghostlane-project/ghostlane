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
//
// A line's own program is started the same way, with two things more
// (LineChild): it is not root's to run, and what it says is the app's to read.
//
//     ProofKitTunnelDaemon --as-group <gid> --as-user <uid> --output <file> <program> <arguments…>
//
// The step takes the user's own groups with <gid> as the first of them, gives
// up being root for good, and only then opens <file>: what it opens, it opens
// as the user would, so the path a request names is never one root follows.
import Foundation

enum GroupExec {
    static let flag = "--as-group"
    static let userFlag = "--as-user"
    static let outputFlag = "--output"

    /// Does not return when the command line asks for the step.
    static func runIfAsked() {
        var arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.count >= 3, arguments[0] == flag else { return }
        guard let group = gid_t(arguments[1]), group != 0 else {
            die("\(flag) takes a group's number, and not root's")
        }
        arguments.removeFirst(2)

        var user: uid_t?
        var output: String?
        // The program is a path, and a path does not begin with two dashes.
        while let option = arguments.first, option.hasPrefix("--") {
            guard arguments.count >= 2 else { die("\(option) takes a value") }
            switch option {
            case userFlag:
                guard let id = uid_t(arguments[1]), id != 0 else {
                    die("\(userFlag) takes a user's number, and not root's")
                }
                user = id
            case outputFlag:
                output = arguments[1]
            default:
                die("unknown option \(option)")
            }
            arguments.removeFirst(2)
        }
        guard let program = arguments.first else { die("nothing to become") }

        if let user {
            guard let entry = getpwuid(user) else { die("no user with the number \(user)") }
            let name = String(cString: entry.pointee.pw_name)
            let home = String(cString: entry.pointee.pw_dir)
            // Before the group and the user: both are root's to give.
            guard initgroups(name, Int32(bitPattern: group)) == 0 else {
                die("initgroups(\(name), \(group)): \(String(cString: strerror(errno)))")
            }
            guard setgid(group) == 0 else { die("setgid(\(group)): \(String(cString: strerror(errno)))") }
            // As root this sets the real, the effective and the saved user: there is no way back.
            guard setuid(user) == 0 else { die("setuid(\(user)): \(String(cString: strerror(errno)))") }
            guard setuid(0) != 0, getuid() == user, geteuid() == user, getegid() == group else {
                die("still root after giving it up")
            }
            setenv("HOME", home, 1)
            setenv("USER", name, 1)
            setenv("LOGNAME", name, 1)
        } else {
            // As root this sets the real, the effective and the saved group: the
            // one a socket is created with is the effective one.
            guard setgid(group) == 0 else {
                die("setgid(\(group)): \(String(cString: strerror(errno)))")
            }
        }

        if let output {
            // A file that is there already, and never a link to one.
            let descriptor = open(output, O_WRONLY | O_APPEND | O_NOFOLLOW)
            guard descriptor >= 0 else { die("open \(output): \(String(cString: strerror(errno)))") }
            guard dup2(descriptor, STDOUT_FILENO) >= 0, dup2(descriptor, STDERR_FILENO) >= 0 else {
                die("dup2: \(String(cString: strerror(errno)))")
            }
            close(descriptor)
        }

        var argv: [UnsafeMutablePointer<CChar>?] = arguments.map { strdup($0) }
        argv.append(nil)
        execv(program, &argv)
        die("exec \(program): \(String(cString: strerror(errno)))")
    }

    private static func die(_ message: String) -> Never {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        exit(64)
    }
}
