#!/usr/bin/env bash
# G3: the release names the core it was built with. Fails when
#   - scripts/release-core-pin.sh does not print the 12-hex head of
#     GHOSTLANE_CORE_COMMIT as `core_pin=`, or its `check` accepts a notes body
#     that lacks "Rust core pin: `<12 hex>`" or names another commit;
#   - release.yml's release_version job has no `core_pin` output, or the notes
#     step does not write the line from it and run the check on both bodies
#     (the rolling nightly's and the versioned release's) before they are published.
# Needs bash, python3 and PyYAML.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
s="$root/scripts/release-core-pin.sh"
fail=0
bad() { echo "FAIL $*"; fail=1; }
[ -f "$s" ] || { echo "FAIL $s is missing"; exit 1; }
full="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=//p' "$root/scripts/ghostlane-core-pin.sh")"
short="${full:0:12}"
[ "$(bash "$s" | sed -n 's/^core_pin=//p')" = "$short" ] || bad "core_pin is not the first 12 hex of the pin"
t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
printf -- '- Rust core pin: `%s`\n' "$short" > "$t/good.md"
printf -- '- App version: `1.0.1`\n' > "$t/none.md"
printf -- '- Rust core pin: `%s`\n' "$(printf 'a%.0s' $(seq 12))" > "$t/other.md"
printf -- 'Rust core pin: %s\n' "$short" > "$t/bare.md"
bash "$s" check "$t/good.md" "$short" >/dev/null 2>&1 || bad "a body with the pin was refused"
for n in none other bare; do
  if bash "$s" check "$t/$n.md" "$short" >/dev/null 2>&1; then bad "a body ($n) without the pin was accepted"; fi
done
if bash "$s" check "$t/missing.md" "$short" >/dev/null 2>&1; then bad "a missing file was accepted"; fi
if bash "$s" check "$t/good.md" abc >/dev/null 2>&1; then bad "a short expected pin was accepted"; fi
[ "$fail" -eq 0 ] || exit 1

python3 -I - "$root" <<'PY' || exit 1
import re, sys, yaml
root = sys.argv[1]
text = open(root + "/.github/workflows/release.yml", encoding="utf-8").read()
doc = yaml.safe_load(text)
jobs = doc["jobs"]
bad = []
rv = jobs.get("release_version", {})
if "core_pin" not in (rv.get("outputs") or {}):
    bad.append("release_version has no core_pin output")
steps = [s for s in rv.get("steps", []) if "release-core-pin.sh" in (s.get("run") or "")]
if not any(s.get("id") == "core" for s in steps):
    bad.append("release_version has no step `core` that runs scripts/release-core-pin.sh")
pub = [j for j in jobs.values() if any("release-notes.md" in (s.get("run") or "") for s in j.get("steps", []))]
if not pub:
    bad.append("no job writes release-notes.md")
for j in pub:
    env = j.get("env") or {}
    if "core_pin" not in str(env.get("OLCBOX_CORE_PIN", "")):
        bad.append("the notes job has no OLCBOX_CORE_PIN from release_version.core_pin")
    runs = "\n".join(s.get("run") or "" for s in j["steps"])
    if "Rust core pin: " not in runs:
        bad.append("the notes do not write a `Rust core pin:` line")
    for f in ("release-notes.md", "versioned-notes.md"):
        if not re.search(r"release-core-pin\.sh\s+check\s+%s\s+\"?\$\{?OLCBOX_CORE_PIN" % re.escape(f), runs):
            bad.append("%s is not checked against OLCBOX_CORE_PIN" % f)
    # the check on the versioned body runs before it is published
    i = runs.find("check versioned-notes.md")
    k = runs.find("--notes-file versioned-notes.md")
    if i < 0 or k < 0 or i > k:
        bad.append("versioned-notes.md is published before it is checked")
    i = runs.find("check release-notes.md")
    k = runs.find("--notes-file release-notes.md")
    if i < 0 or k < 0 or i > k:
        bad.append("release-notes.md is published before it is checked")
for b in bad:
    print("FAIL", b)
sys.exit(1 if bad else 0)
PY
echo "release-notes-pin: ok"
