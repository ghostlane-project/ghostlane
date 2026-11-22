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

print("\n== what a tun keeps out of itself")
/// What the daemon reads [request]'s exclusions as, or the refusal.
func keptOut(_ request: [String: Any]) -> String {
    do {
        return try HevRequest.parse(request).exclude.map(\.text).joined(separator: " ")
    } catch {
        return "refused: " + ((error as? DaemonError)?.text ?? "\(error)")
    }
}
expect("what the app names", keptOut(request), "^203\\.0\\.113\\.7/32 2001:db8::7/128$")
expect("an address alone is that one address, and a prefix drops the bits past it",
       keptOut(with(request, "exclude", ["198.51.100.9", "10.1.2.3/8", "2001:DB8:0:0::1/64"])),
       "^198\\.51\\.100\\.9/32 10\\.0\\.0\\.0/8 2001:db8::/64$")
expect("nothing, when it names none", keptOut(with(request, "exclude", nil)), "^$")
expect("a name is refused", keptOut(with(request, "exclude", ["example.com"])), "^refused: a tun keeps out addresses and networks")
expect("a prefix longer than its address", keptOut(with(request, "exclude", ["10.0.0.0/33"])), "^refused: a tun keeps out addresses and networks")
expect("a prefix written another way", keptOut(with(request, "exclude", ["10.0.0.0/08"])), "^refused: a tun keeps out addresses and networks")
expect("one address that is not in a list", keptOut(with(request, "exclude", "203.0.113.7")), "^refused: a tun keeps out addresses and networks")
expect("more than 64 of them", keptOut(with(request, "exclude", Array(repeating: "203.0.113.7", count: 65))), "^refused: a tun keeps out at most 64 ")
let spread = ["2001:db8::1", "2400:cb00::1", "2606:4700::1", "2a00:1450::1", "2c0f:f248::1", "3000::1", "4000::1", "6000::1", "8000::1"]
expect("eight IPv6 addresses far apart, 996 routes around them", keptOut(with(request, "exclude", Array(spread.prefix(8)))), "^2001:db8::1/128 .* 6000::1/128$")
expect("nine, 1122 routes, too many", keptOut(with(request, "exclude", spread)), "^refused: the routes around what a tun keeps out are at most 1024, and these are 1122$")

print("\n== the routes a tun takes")
let whole = Routes.plan(holes: [])
expect("all of each family but its first /8, in the fewest prefixes",
       whole.map(\.text).joined(separator: " "),
       "^1\\.0\\.0\\.0/8 2\\.0\\.0\\.0/7 4\\.0\\.0\\.0/6 8\\.0\\.0\\.0/5 16\\.0\\.0\\.0/4 32\\.0\\.0\\.0/3 64\\.0\\.0\\.0/2 128\\.0\\.0\\.0/1 "
           + "100::/8 200::/7 400::/6 800::/5 1000::/4 2000::/3 4000::/2 8000::/1$")
let server = Prefix("64.176.32.7")!
let server6 = Prefix("2001:db8::7")!
let around = Routes.plan(holes: [server, server6])
expect("around a server of each family, as many as sing-box laid",
       "\(around.filter { !$0.isV6 }.count) \(around.filter { $0.isV6 }.count)", "^37 132$")
expect("split exactly around it, lowest first",
       around.prefix(10).map(\.text).joined(separator: " "),
       "^1\\.0\\.0\\.0/8 2\\.0\\.0\\.0/7 4\\.0\\.0\\.0/6 8\\.0\\.0\\.0/5 16\\.0\\.0\\.0/4 32\\.0\\.0\\.0/3 64\\.0\\.0\\.0/9 64\\.128\\.0\\.0/11 64\\.160\\.0\\.0/12 64\\.176\\.0\\.0/19$")
let kept = [server, server6, Prefix("0.0.0.0/8")!, Prefix("::/8")!]
expect("none of them holds what is kept out", "\(around.contains { route in kept.contains { route.overlaps($0) } })", "^false$")
let held = around.filter { !$0.isV6 }.reduce(UInt64(0)) { $0 + (UInt64(1) << UInt64(32 - $1.length)) }
expect("and together they hold every other IPv4 address", "\(held)", "^\((UInt64(1) << 32) - (UInt64(1) << 24) - 1)$")
expect("an address in a first /8 costs no route", "\(Routes.plan(holes: [Prefix("0.1.2.3")!, Prefix("::1")!]).count)", "^16$")

print()
print(failed == 0 ? "the helper's parts do what the document says" : "the helper's parts do not do what the document says")
exit(Int32(failed))
