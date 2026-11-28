#!/usr/bin/env bash
# K2 for the Rust CLI: the Go CLI's four netns cases, as root in fresh network
# namespaces of the runner (its own network, routes and rules are not touched).
#   cli/crates/gl-routes/tests/netns.rs   the two cases of internal/routes
#                                         (own-address rules and clean-up, the
#                                         kill switch's rules)
# The test binary is built as the user and run as root: each case starts the
# binary again under `unshare -n`. The run is held to every case run and none
# skipped; a case that is missing from the output fails the check.
#
#   bash scripts/cli-checks/netns.sh
# Needs root or passwordless sudo, iproute2, util-linux (unshare) and the
# toolchain cli/rust-toolchain.toml names.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ "$(uname -s)" != Linux ]; then
  echo "netns.sh: not Linux, nothing to check here"
  exit 0
fi

cd "$root/cli"
cargo test --locked -p ghostlane-routes --test netns --no-run --message-format=json > "$work/build.json"
exe="$(python3 -I - "$work/build.json" <<'PY'
import json, sys
found = []
for line in open(sys.argv[1]):
    try:
        m = json.loads(line)
    except ValueError:
        continue
    if m.get("reason") == "compiler-artifact" and m.get("executable") and m["target"]["name"] == "netns" \
            and m.get("profile", {}).get("test"):
        found.append(m["executable"])
if len(found) != 1:
    sys.exit("expected one test binary named netns, found %d" % len(found))
print(found[0])
PY
)"

sudo=()
if [ "$(id -u)" -ne 0 ]; then
  sudo=(sudo -n)
fi
status=0
"${sudo[@]}" env GHOSTLANE_NETNS=1 "$exe" --test-threads=1 --show-output 2>&1 | tee "$work/run.log" || status=$?

cases="netns_own_address_rules_and_cleanup netns_kill_switch_rules"
for name in $cases; do
  grep -q "^test $name ... ok$" "$work/run.log" || { echo "::error::netns case $name did not pass (missing, skipped or failed)"; status=1; }
done
if grep -q 'SKIPPED' "$work/run.log"; then
  echo "::error::a netns case was skipped"
  status=1
fi
exit "$status"
