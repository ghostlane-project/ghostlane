//! The Go CLI's netns cases of `internal/routes` (`netns_test.go`), run in a
//! fresh network namespace: the test binary starts itself again under
//! `unshare -n` and the child does the work. They need root and
//! GHOSTLANE_NETNS=1 (scripts/cli-checks/netns.sh sets both and holds the run
//! to: every case ran, none was skipped). A case that is skipped says
//! `SKIPPED` on stderr.

use std::net::IpAddr;
use std::process::Command;

use gl_routes::{
    Manager, KILL_SWITCH_DROP, KILL_SWITCH_LAN, KILL_SWITCH_SELF, KILL_SWITCH_TABLE,
    OWN_ADDRESS_PRIORITY,
};

/// True in the child, in its own namespace. In the parent it runs the child
/// and returns false, and the case is over.
fn enter(name: &str) -> bool {
    if std::env::var("GHOSTLANE_NETNS").as_deref() != Ok("1") {
        eprintln!("SKIPPED {name}: set GHOSTLANE_NETNS=1 (and run as root) for the netns tests");
        return false;
    }
    // SAFETY: geteuid has no arguments and cannot fail.
    if unsafe { libc::geteuid() } != 0 {
        eprintln!("SKIPPED {name}: netns tests need root");
        return false;
    }
    if std::env::var("GHOSTLANE_IN_NETNS").as_deref() == Ok("1") {
        ip(&["link", "set", "lo", "up"]);
        return true;
    }
    let exe = std::env::current_exe().expect("the test binary's path");
    let out = Command::new("unshare")
        .args(["-n", "--"])
        .arg(exe)
        .args(["--exact", name, "--nocapture", "--test-threads=1"])
        .env("GHOSTLANE_IN_NETNS", "1")
        .output()
        .expect("unshare -n");
    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    print!("{stdout}");
    eprint!("{stderr}");
    assert!(
        out.status.success(),
        "{name} failed inside the netns: {}",
        out.status
    );
    assert!(
        stdout.contains("1 passed"),
        "{name} did not run inside the netns"
    );
    false
}

fn ip(args: &[&str]) -> String {
    let out = Command::new("ip").args(args).output().expect("ip");
    assert!(
        out.status.success(),
        "ip {args:?}: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).into_owned()
}

/// What `ip rule` prints for each rule of one priority, with the spaces
/// squeezed: "from 198.51.100.7 lookup main".
fn rules_with_priority(family: &str, prio: u32) -> Vec<String> {
    let want = format!("{prio}:");
    ip(&[family, "rule", "show"])
        .lines()
        .filter_map(|line| {
            let (head, rest) = line.split_once(':')?;
            (format!("{}:", head.trim()) == want)
                .then(|| rest.split_whitespace().collect::<Vec<_>>().join(" "))
        })
        .collect()
}

fn routes_of_table(family: &str, table: u32) -> Vec<String> {
    ip(&[family, "route", "show", "table", &table.to_string()])
        .lines()
        .map(|l| l.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|l| !l.is_empty())
        .collect()
}

fn addr(text: &str) -> IpAddr {
    text.parse().expect("an address")
}

#[test]
fn netns_own_address_rules_and_cleanup() {
    if !enter("netns_own_address_rules_and_cleanup") {
        return;
    }
    ip(&["link", "add", "gl-dum0", "type", "dummy"]);
    ip(&["link", "set", "gl-dum0", "up"]);
    ip(&["addr", "add", "198.51.100.7/24", "dev", "gl-dum0"]);
    ip(&["addr", "add", "2001:db8::7/64", "dev", "gl-dum0", "nodad"]);

    let m = Manager::new("ghostlane0");
    let addrs = m.global_addresses().expect("global addresses");
    assert_eq!(addrs.len(), 2, "{addrs:?}");
    m.sync(&addrs).expect("sync");
    assert_eq!(
        rules_with_priority("-4", OWN_ADDRESS_PRIORITY),
        ["from 198.51.100.7 lookup main"]
    );
    assert_eq!(
        rules_with_priority("-6", OWN_ADDRESS_PRIORITY),
        ["from 2001:db8::7 lookup main"]
    );
    // a second sync with one address gone and one added
    m.sync(&[addr("198.51.100.8")]).expect("second sync");
    assert_eq!(
        rules_with_priority("-4", OWN_ADDRESS_PRIORITY),
        ["from 198.51.100.8 lookup main"]
    );
    assert!(rules_with_priority("-6", OWN_ADDRESS_PRIORITY).is_empty());
    m.sync(&[addr("198.51.100.8")]).expect("idempotent sync");
    assert_eq!(rules_with_priority("-4", OWN_ADDRESS_PRIORITY).len(), 1);
    m.clear().expect("clear");
    assert!(rules_with_priority("-4", OWN_ADDRESS_PRIORITY).is_empty());

    // A crash leaves the engine's rules and table behind; cleanup_stale removes them.
    ip(&["rule", "add", "priority", "9003", "table", "2022"]);
    ip(&[
        "route",
        "add",
        "203.0.113.0/24",
        "dev",
        "gl-dum0",
        "table",
        "2022",
    ]);
    m.cleanup_stale().expect("cleanup_stale");
    assert!(rules_with_priority("-4", 9003).is_empty());
    assert_eq!(routes_of_table("-4", 2022), Vec::<String>::new());
}

/// The kill switch: 9098 main for what main routes specifically (suppress
/// prefixlength 0), 9099 the daemon's uid to main, 9100 everything else to
/// table 2023 whose only route is an unreachable default; v4 and v6,
/// idempotent.
#[test]
fn netns_kill_switch_rules() {
    if !enter("netns_kill_switch_rules") {
        return;
    }
    let m = Manager::new("ghostlane0");
    for _ in 0..2 {
        m.install_kill_switch(998).expect("install"); // twice: no duplicates
    }
    for family in ["-4", "-6"] {
        assert_eq!(
            rules_with_priority(family, KILL_SWITCH_LAN),
            ["from all lookup main suppress_prefixlength 0"],
            "{family}"
        );
        assert_eq!(
            rules_with_priority(family, KILL_SWITCH_SELF),
            ["from all uidrange 998-998 lookup main"],
            "{family}"
        );
        assert_eq!(
            rules_with_priority(family, KILL_SWITCH_DROP),
            [format!("from all lookup {KILL_SWITCH_TABLE}")],
            "{family}"
        );
        let routes = routes_of_table(family, KILL_SWITCH_TABLE);
        assert!(
            routes.len() == 1 && routes[0].starts_with("unreachable default"),
            "{family} table {KILL_SWITCH_TABLE}: {routes:?}"
        );
    }
    m.remove_kill_switch().expect("remove");
    assert_no_kill_switch();
    m.install_kill_switch(998).expect("install again");
    m.cleanup_stale().expect("cleanup_stale");
    assert_no_kill_switch();
}

fn assert_no_kill_switch() {
    for family in ["-4", "-6"] {
        for prio in [KILL_SWITCH_LAN, KILL_SWITCH_SELF, KILL_SWITCH_DROP] {
            let left = rules_with_priority(family, prio);
            assert!(left.is_empty(), "rule {prio} left: {left:?}");
        }
        let left = routes_of_table(family, KILL_SWITCH_TABLE);
        assert!(left.is_empty(), "table {KILL_SWITCH_TABLE} left: {left:?}");
    }
}
