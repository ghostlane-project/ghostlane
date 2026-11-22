#!/usr/bin/env bash
# Only blocked sites on the Rust core: Re:filter's list as the core reads it,
# sharedUI/src/commonMain/composeResources/files/xray/refilter.dat, named by
# `ext:refilter.dat:domains` and `ext:refilter.dat:ips`. Fails when
#   - the tool that makes it, tools/xray-geodata/refilter.py, fails its own
#     offline checks (what it carries, what it refuses, a file one byte off);
#   - the file is not what the tool makes from the Re:filter release that
#     scripts/rule-sets.lock names, fetched now and checked against the
#     lock's pins of its two downloads: a file edited by hand, a refresh not
#     recorded, or a release that changed under its tag;
#   - the file does not hash to the lock's line for it and to
#     XrayGeodata.REFILTER, the pin the app's own test holds the bundle to.
#
#   scripts/p6-tests/refilter-dat.sh [root]     root defaults to the repository
# Needs bash, python3 and the network (github.com, for the release).
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
tool="$root/tools/xray-geodata/refilter.py"
python3 -I "$tool" selftest
python3 -I "$tool" check --root "$root"
