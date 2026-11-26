#!/usr/bin/env bash
# desktop-check-os: linux macos
#
# The desktop's probes and its room check on ghostlane-engine: the app's own
# HostProbe writes a session, runs the Core build's program at the pin as a
# one-shot child (`olc-check`, `olc-ping`, `probe`) and reads its one line, and
# starts it as the throw-away core of smart connect's probe (HostProbeRealTest,
# which this script runs and holds to: every test of it run, none skipped,
# none failed):
#   - a room check and a ping join the room a session names on the `none`
#     carrier (a hub on loopback, the interop pipe) and say no, with the
#     engine's reason, when nobody answers or the room ends; a key the engine
#     refuses is its own sentence and no join; no session file is left;
#   - a line probe is answered by a VLESS line on loopback and only by it, and
#     a dead line is a no with a reason;
#   - the starter smart connect gets serves the probe's SOCKS5 port behind its
#     login, carries a page through the line, refuses a wrong login, and is
#     gone, its port closed, once stopped; the kept starter is stopped by the
#     session's stop.
#
# No live carrier is reached: the only carrier these checks use is `none`, and
# every server they dial is on loopback. Windows is not named: the engine the
# Core build keeps for it carries no rooms.
#
# Run by rust-desktop.yml on each leg the line above names:
#   bash scripts/desktop-checks/probe.sh
# with GHOSTLANE_ENGINE (the program), GHOSTLANE_CORE_COMMIT (the commit it
# must say it was built from), DESKTOP_CHECK_OS (linux or macos) and
# DESKTOP_CHECK_WORK (an empty directory of its own). Needs Python 3, and Java
# 21 with the Android SDK for the Gradle build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

engine="${GHOSTLANE_ENGINE:?the program to check: GHOSTLANE_ENGINE}"
commit="${GHOSTLANE_CORE_COMMIT:?the pin the program was built from: GHOSTLANE_CORE_COMMIT}"
os="${DESKTOP_CHECK_OS:?linux or macos: DESKTOP_CHECK_OS}"
work="${DESKTOP_CHECK_WORK:?a directory for this check alone: DESKTOP_CHECK_WORK}"
mkdir -p "$work"
work="$(cd "$work" && pwd)"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
say() { echo "probe: $*"; }
die() { echo "probe: $*" >&2; echo "- probe: $*" >> "$summary"; exit 1; }

py=""
for candidate in python3 python; do
  if command -v "$candidate" > /dev/null && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 8))' 2> /dev/null; then
    py="$candidate"
    break
  fi
done
[ -n "$py" ] || die "no Python 3"
[ -x "$engine" ] || die "GHOSTLANE_ENGINE is no program: $engine"

# The program says what it is: the contract this app writes, the pin, and that
# it carries rooms (the room check is nothing without them).
"$engine" version > "$work/version.jsonl" 2> "$work/version.err" || die "\`ghostlane-engine version\` failed: $(head -c 300 "$work/version.err")"
said="$("$py" -I - "$work/version.jsonl" "$commit" 2>&1 <<'PY'
import json, sys
path, commit = sys.argv[1], sys.argv[2]
lines = [l for l in open(path, encoding="utf-8").read().splitlines() if l.strip()]
v = json.loads(lines[0]) if lines else {}
if v.get("event") != "version":
    sys.exit("its first line is no `version` event: %r" % (lines[:1],))
if v.get("protocol") != 1:
    sys.exit("it reads protocol %r; the app writes protocol 1" % v.get("protocol"))
if v.get("pin") != commit:
    sys.exit("it was built from %s, not from the pin %s" % (v.get("pin"), commit))
if not v.get("rooms"):
    sys.exit("it carries no rooms")
print("ghostlane-engine %s at %s (%s; rooms yes, BoringSSL %s)" % (
    v["engine"], v["pin"][:12], v["target"], "yes" if v["boringssl"] else "no"))
PY
)" || die "the program is not the pin's: $said"
say "$said"

# HostProbeRealTest, as the app's build runs its tests. --rerun: what it reads
# is no input Gradle knows of, and a result from the build cache ran nothing.
results="$root/sharedUI/build/test-results/jvmTest"
class=org.olcbox.app.vpn.desktop.engine.host.HostProbeRealTest
rm -f "$results/TEST-$class.xml"
set +e
(
  cd "$root"
  HOST_PROBE_CHECK=1 GHOSTLANE_ENGINE_BINARY="$engine" GHOSTLANE_CORE_COMMIT="$commit" \
    ./gradlew :sharedUI:jvmTest --rerun --tests "$class" --stacktrace
)
gradle=$?
set -e

source="$root/sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/desktop/engine/host/HostProbeRealTest.kt"
verdict="$("$py" -I - "$results/TEST-$class.xml" "$source" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
xml, source = sys.argv[1], sys.argv[2]
written = len(re.findall(r"^\s*@Test\b", open(source, encoding="utf-8").read(), re.M))
try:
    suite = ET.parse(xml).getroot()
except (OSError, ET.ParseError) as e:
    sys.exit("no result of HostProbeRealTest (%s)" % e)
n = {k: int(suite.get(k, "0")) for k in ("tests", "skipped", "failures", "errors")}
bad = []
if n["tests"] != written:
    bad.append("%d tests ran of the %d written" % (n["tests"], written))
for k in ("skipped", "failures", "errors"):
    if n[k]:
        bad.append("%d %s" % (n[k], k))
for case in suite.iter("testcase"):
    for kind in ("failure", "error", "skipped"):
        for e in case.iter(kind):
            bad.append("%s: %s %s" % (case.get("name"), kind, (e.get("message") or "").splitlines()[0][:200] if e.get("message") else ""))
print("; ".join(bad) if bad else "%d of %d tests passed" % (n["tests"], written))
sys.exit(1 if bad else 0)
PY
)" && held=0 || held=1

{
  echo "#### probes and the room check on ghostlane-engine ($os)"
  echo
  echo "- $said"
  echo "- HostProbeRealTest: $verdict"
} >> "$summary"
say "HostProbeRealTest: $verdict"
[ "$gradle" -eq 0 ] || die "Gradle failed ($gradle)"
[ "$held" -eq 0 ] || die "HostProbeRealTest did not pass whole: $verdict"
say "passed"
