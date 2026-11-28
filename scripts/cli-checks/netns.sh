#!/usr/bin/env bash
# K2 for the Rust CLI: the Go CLI's four netns cases, as root in fresh network
# namespaces of the runner (its own network, routes and rules are not touched).
#   cli/crates/gl-routes/tests/netns.rs   the two cases of internal/routes
#                                         (own-address rules and clean-up, the
#                                         kill switch's rules)
#   cli/tests/netns.rs                    the two cases of internal/daemon (the
#                                         tun end to end, the kill switch's
#                                         life), with the Rust daemon, its real
#                                         rules and the real hev, built here
#                                         from the app's tree
# The test binaries are built as the user and run as root: each case starts
# its binary again under `unshare -n`. The run is held to every case run and
# none skipped; a case that is missing from the output fails the check.
#
#   bash scripts/cli-checks/netns.sh
# Needs root or passwordless sudo, iproute2, util-linux (unshare), make and a C
# compiler (hev) and the toolchain cli/rust-toolchain.toml names.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ "$(uname -s)" != Linux ]; then
  echo "netns.sh: not Linux, nothing to check here"
  exit 0
fi

# ---- hev, from the app's tree, as the Linux build makes it ---------------------------
hev_tree="androidApp/src/main/jni/hev-socks5-tunnel"
hev_rev="$(git -C "$root" rev-parse "HEAD:$hev_tree" 2> /dev/null || echo unknown)"
cp -a "$root/$hev_tree" "$work/hev-src"
make -C "$work/hev-src" -j"$(nproc)" exec REV_ID="${hev_rev:0:12}" > "$work/hev-build.log" 2>&1 ||
  { echo "::error::hev did not build"; tail -c 1500 "$work/hev-build.log"; exit 1; }
export GHOSTLANE_HEV="$work/hev-src/bin/hev-socks5-tunnel"
[ -x "$GHOSTLANE_HEV" ] || { echo "::error::the build wrote no hev-socks5-tunnel"; exit 1; }
echo "hev-socks5-tunnel built from the app's tree ${hev_rev:0:12}"

# ---- the test binaries ----------------------------------------------------------------
cd "$root/cli"
cargo test --locked -p ghostlane-routes -p ghostlane-cli --test netns --no-run --message-format=json > "$work/build.json"
python3 -I - "$work/build.json" "$work/exes" <<'PY'
import json, sys
found = {}
for line in open(sys.argv[1]):
    try:
        m = json.loads(line)
    except ValueError:
        continue
    if m.get("reason") == "compiler-artifact" and m.get("executable") and m["target"]["name"] == "netns" \
            and m.get("profile", {}).get("test"):
        pkg = m["package_id"]
        key = "routes" if "ghostlane-routes" in pkg else "cli" if "ghostlane-cli" in pkg else pkg
        found.setdefault(key, []).append(m["executable"])
if sorted(found) != ["cli", "routes"] or any(len(v) != 1 for v in found.values()):
    sys.exit("expected one netns test binary in each of ghostlane-routes and ghostlane-cli, found %r" % found)
open(sys.argv[2], "w").write("".join("%s=%s\n" % (k, v[0]) for k, v in sorted(found.items())))
PY

sudo=()
if [ "$(id -u)" -ne 0 ]; then
  sudo=(sudo -n)
fi
status=0
while IFS== read -r which exe; do
  echo "::group::$which netns cases"
  "${sudo[@]}" env GHOSTLANE_NETNS=1 GHOSTLANE_HEV="$GHOSTLANE_HEV" "$exe" --test-threads=1 --show-output 2>&1 | tee "$work/run-$which.log" || status=$?
  echo "::endgroup::"
done < "$work/exes"

cases="routes:netns_own_address_rules_and_cleanup routes:netns_kill_switch_rules cli:netns_tun_end_to_end cli:netns_kill_switch"
for entry in $cases; do
  which="${entry%%:*}"
  name="${entry#*:}"
  grep -q "^test $name ... ok$" "$work/run-$which.log" || { echo "::error::netns case $name did not pass (missing, skipped or failed)"; status=1; }
done
if grep -q 'SKIPPED' "$work"/run-*.log; then
  echo "::error::a netns case was skipped"
  status=1
fi
exit "$status"
