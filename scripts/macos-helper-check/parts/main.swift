// The tunnel helper's parts that need neither root nor a network, asked on a
// Mac: what a `tun` request is read as and what in one is refused (a script by
// its name, wherever it stands), and the config written for hev from it.
// Built with every file of desktopApp/tunneldaemon but its main, and run with
// the request the app's own test holds the app to:
//
//   helper-parts sharedUI/src/jvmTest/resources/macos-helper/tun-request.json
import Foundation

var failed = 0

func expect(_ what: String, _ found: String, _ pattern: String) {
    if found.range(of: pattern, options: .regularExpression) != nil {
        print("   ok    \(what): \(found.replacingOccurrences(of: "\n", with: "|"))")
    } else {
        print("   FAIL  \(what): found \"\(found)\", expected to match \(pattern)")
        failed = 1
    }
}

/// What the daemon makes of [request]: its config for hev, or the refusal.
func reading(_ request: [String: Any]) -> String {
    do {
        return try HevRequest.parse(request).yaml
    } catch {
        return "refused: " + ((error as? DaemonError)?.text ?? "\(error)")
    }
}

/// [request] with the field at [path] (one or two names, with a dot) set to [value], or taken away for nil.
func with(_ request: [String: Any], _ path: String, _ value: Any?) -> [String: Any] {
    var copy = request
    let names = path.split(separator: ".").map(String.init)
    if names.count == 1 {
        copy[names[0]] = value
    } else {
        var inner = copy[names[0]] as? [String: Any] ?? [:]
        inner[names[1]] = value
        copy[names[0]] = inner
    }
    return copy
}

let data = try! Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let request = try! JSONSerialization.jsonObject(with: data) as! [String: Any]

print("\n== the request the app sends")
let yaml = reading(request)
expect("is read", yaml, "^tunnel:\n")
expect("the tun is the one named, with both addresses",
       yaml, "\n  name: 'utun225'\n  mtu: 1500\n  multi-queue: false\n  ipv4: '172.19.0.1'\n  ipv6: 'fdfe:dcba:9876::1'\n")
expect("hev hands what it takes to the port, with the login, a quote in it doubled",
       yaml, "\nsocks5:\n  address: '127.0.0.1'\n  port: 18821\n  udp: 'udp'\n  pipeline: false\n  username: 'ghostlane'\n  password: 'p4ss w''rd'\n")
expect("and answers names itself",
       yaml, "\nmapdns:\n  address: '172.19.0.2'\n  port: 53\n  network: '198.18.0.0'\n  netmask: '255.254.0.0'\n  cache-size: 10000\n")
expect("it writes to the daemon's own pipe, and runs no script and no daemon of its own",
       "\(yaml.contains("  log-file: stderr\n")) \(yaml.contains("script")) \(yaml.contains("pid-file"))", "^true false false$")
expect("with no login, none", reading(with(with(request, "socks.username", nil), "socks.password", nil)), "\n  pipeline: false\n\nmapdns:")
let bare = reading(with(with(request, "mapdns", nil), "tun.ipv6", nil))
expect("with no mapdns and no IPv6, neither",
       "\(bare.hasPrefix("tunnel:")) \(bare.contains("ipv6")) \(bare.contains("mapdns"))", "^true false false$")

print("\n== a script, wherever it stands, is refused by its name")
expect("in the tun", reading(with(request, "tun.post-up-script", "/tmp/up.sh")), "^refused: hev runs no script of anybody's: tun.post-up-script is refused$")
expect("beside it", reading(with(request, "pre-down-script", "/tmp/down.sh")), "^refused: .* pre-down-script is refused$")
expect("in the SOCKS port's", reading(with(request, "socks.post-up-script", "/tmp/up.sh")), "^refused: .* socks.post-up-script is refused$")
expect("in another case", reading(with(request, "tun.Pre-Down-Script", "/tmp/down.sh")), "^refused: .* tun.Pre-Down-Script is refused$")
expect("in an object of its own", reading(with(request, "misc", ["post-up-script": "/tmp/up.sh"])), "^refused: .* misc.post-up-script is refused$")
expect("in a list", reading(with(request, "extra", [["pre-down-script": "/tmp/down.sh"]])), "^refused: .* extra\\[0\\].pre-down-script is refused$")

print("\n== what else is refused")
expect("a config in place of fields", reading(with(request, "config", "tunnel:\n  name: utun225\n")), "^refused: a tun request has no field config$")
expect("a field it does not know", reading(with(request, "socks.mark", 1)), "^refused: a tun request has no field socks.mark$")
expect("a login that ends a line", reading(with(request, "socks.password", "x\npost-up: /tmp/up.sh")), "^refused: a SOCKS login ")
expect("a username with no password", reading(with(request, "socks.password", nil)), "^refused: a SOCKS login ")
expect("a SOCKS port that is not on the loopback", reading(with(request, "socks.address", "192.168.1.1")), "^refused: .*loopback")
expect("a port that is not a number", reading(with(request, "socks.port", "18821")), "^refused: the SOCKS port is a number")
expect("an interface that is not a utun", reading(with(request, "tun.name", "en0")), "^refused: the tunnel's interface is not a utun$")
expect("a utun written another way", reading(with(request, "tun.name", "utun0225")), "^refused: the tunnel's interface is not a utun$")
expect("an address with more after it", reading(with(request, "tun.ipv4", "172.19.0.1/30")), "^refused: the tun's ipv4 ")
expect("an mtu too small for IPv6", reading(with(request, "tun.mtu", 1000)), "^refused: the tun's mtu ")
expect("a mask with a gap", reading(with(request, "mapdns.netmask", "255.0.255.0")), "^refused: mapdns answers ")
expect("a network off its mask", reading(with(request, "mapdns.network", "198.19.0.0")), "^refused: mapdns answers ")
expect("more names than the network holds", reading(with(request, "mapdns.cacheSize", 200_000)), "^refused: mapdns's cache size ")
expect("no tun", reading(with(request, "tun", nil)), "^refused: a tun request names its tun$")

print()
print(failed == 0 ? "the helper's parts do what the document says" : "the helper's parts do not do what the document says")
exit(Int32(failed))
