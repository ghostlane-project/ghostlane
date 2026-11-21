#!/usr/bin/env bash
# One pin: the app names exactly one commit of the Rust core, in
# scripts/ghostlane-core-pin.sh. Fails when
#   - the pin file does not hold exactly one 40-hex GHOSTLANE_CORE_COMMIT;
#   - a second 40-hex commit of the core (the pin's own value again, or a hash on
#     a line that names the core: xray-rust, XRAY_RUST, GHOSTLANE_CORE,
#     ghostlane-core) stands anywhere under .github or scripts;
#   - a workflow that reads the pin does not name the pin file in each `paths:`
#     list it has, or has no `pin` input on its workflow_dispatch.
# It then proves itself: each rule is planted in a temp copy and must fail.
#
#   scripts/p6-tests/pin.sh [root]     root defaults to the repository
# Needs bash, python3 and PyYAML.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# The resolver: the pin with no argument or an empty one, an override as given,
# anything that is not 40 lowercase hex refused.
res="$root/scripts/ghostlane-core-resolve.sh"
pin_commit="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=//p' "$root/scripts/ghostlane-core-pin.sh")"
other="$(printf 'c%.0s' $(seq 40))"
fail=0
[ "$(bash "$res" | sed -n 's/^GHOSTLANE_CORE_COMMIT=//p')" = "$pin_commit" ] || { echo "FAIL resolver: no argument is not the pin"; fail=1; }
[ "$(bash "$res" '' | sed -n 's/^GHOSTLANE_CORE_COMMIT=//p')" = "$pin_commit" ] || { echo "FAIL resolver: an empty argument is not the pin"; fail=1; }
[ "$(bash "$res" "$other" | sed -n 's/^GHOSTLANE_CORE_COMMIT=//p')" = "$other" ] || { echo "FAIL resolver: an override is not used"; fail=1; }
if bash "$res" abc123 >/dev/null 2>&1; then echo "FAIL resolver: a short hash was accepted"; fail=1; fi
[ "$fail" -eq 0 ] || exit 1
exec python3 -I - "$root" <<'PY'
import fnmatch, os, re, shutil, sys, tempfile
import yaml

PIN = "scripts/ghostlane-core-pin.sh"
HEX = re.compile(r"(?<![0-9a-fA-F])[0-9a-f]{40}(?![0-9a-fA-F])")
CORE_WORDS = re.compile(r"xray-rust|XRAY_RUST|GHOSTLANE_CORE|ghostlane-core", re.I)
READS = re.compile(r"ghostlane-core-pin|ghostlane-core-resolve|build-ghostlane-core|GHOSTLANE_CORE_")
SKIP_DIRS = ("scripts/p6-tests/", "scripts/patches/")


def files(root):
    for top in (".github", "scripts"):
        for d, _, names in os.walk(os.path.join(root, top)):
            for n in names:
                p = os.path.join(d, n)
                yield os.path.relpath(p, root), p


def read(p):
    try:
        with open(p, encoding="utf-8") as f:
            return f.read()
    except (UnicodeDecodeError, OSError):
        return None


def check(root):
    bad = []
    text = read(os.path.join(root, PIN))
    if text is None:
        return ["%s is missing" % PIN]
    pins = re.findall(r"^GHOSTLANE_CORE_COMMIT=([0-9a-f]{40})\s*$", text, re.M)
    if len(pins) != 1:
        bad.append("%s must hold exactly one 40-hex GHOSTLANE_CORE_COMMIT, found %d" % (PIN, len(pins)))
    if len(HEX.findall(re.sub(r"^\s*#.*$", "", text, flags=re.M))) != 1:
        bad.append("%s must hold one commit and no other 40-hex value outside comments" % PIN)
    pin = pins[0] if pins else None
    for rel, p in files(root):
        if rel == PIN or rel.startswith(SKIP_DIRS):
            continue
        t = read(p)
        if t is None:
            continue
        for i, line in enumerate(t.splitlines(), 1):
            for h in HEX.findall(line):
                if h == pin or CORE_WORDS.search(line):
                    bad.append("%s:%d: a second copy of a core commit (%s...); read %s" % (rel, i, h[:8], PIN))
    wf = os.path.join(root, ".github/workflows")
    for n in sorted(os.listdir(wf)) if os.path.isdir(wf) else []:
        if not n.endswith((".yml", ".yaml")):
            continue
        t = read(os.path.join(wf, n))
        if t is None or not READS.search(t):
            continue
        doc = yaml.safe_load(t) or {}
        on = doc.get(True, doc.get("on")) or {}
        if not isinstance(on, dict):
            on = {k: None for k in ([on] if isinstance(on, str) else on)}
        for ev in ("push", "pull_request"):
            paths = (on.get(ev) or {}).get("paths") if isinstance(on.get(ev), dict) else None
            if paths is not None and not any(fnmatch.fnmatch(PIN, g) for g in paths):
                bad.append("%s reads the pin but its %s paths do not name %s" % (n, ev, PIN))
        wd = on.get("workflow_dispatch", "missing")
        inputs = wd.get("inputs") if isinstance(wd, dict) else None
        if not isinstance(inputs, dict) or "pin" not in inputs:
            bad.append("%s reads the pin but has no `pin` input on workflow_dispatch" % n)
    return bad


def selftest(root):
    """Plant each violation in a copy of the tree: every one must be found."""
    probes = {
        "second commit on a core line": lambda d: open(d + "/scripts/x.sh", "w").write(
            "XRAY_RUST_COMMIT=" + "1" * 40 + "\n"),
        "the pin's value again": lambda d: open(d + "/.github/x.txt", "w").write(
            read(d + "/" + PIN).split("GHOSTLANE_CORE_COMMIT=")[1][:40] + "\n"),
        "no paths entry": lambda d: open(d + "/.github/workflows/z.yml", "w").write(
            "on:\n  push:\n    paths:\n      - a\n  workflow_dispatch:\n    inputs:\n      pin: {}\n"
            "jobs: {}\n# scripts/ghostlane-core-pin.sh\n"),
        "no pin input": lambda d: open(d + "/.github/workflows/z.yml", "w").write(
            "on:\n  push:\n    paths:\n      - scripts/ghostlane-core-pin.sh\n  workflow_dispatch:\n"
            "jobs: {}\n# scripts/ghostlane-core-pin.sh\n"),
    }
    for what, plant in probes.items():
        tmp = tempfile.mkdtemp()
        try:
            for top in (".github", "scripts"):
                shutil.copytree(os.path.join(root, top), os.path.join(tmp, top),
                                ignore=shutil.ignore_patterns("patches", "play", "*.png"))
            plant(tmp)
            if not check(tmp):
                return ["self-test: planted '%s' was not found" % what]
        finally:
            shutil.rmtree(tmp)
    return []


root = sys.argv[1]
bad = check(root)
if not bad:
    bad = selftest(root)
for b in bad:
    print("FAIL " + b)
if bad:
    sys.exit(1)
print("ok   one pin, read by every workflow that needs it (and the test finds each planted violation)")
PY
