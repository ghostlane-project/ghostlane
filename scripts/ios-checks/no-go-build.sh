#!/usr/bin/env bash
# I2: the iPhone build has no Go in it. Read off the tree and the release's
# iOS job, so it holds for a step nobody has written yet:
#
#   - the Go frameworks' scripts and workflow are gone, and the files of this
#     task (Gradle, the Xcode project, release.yml) do not name them; other
#     files that still do (a comment, a path list) are printed as notes, since
#     other tasks own them;
#   - sharedUI/build.gradle.kts has no iOS task that runs Go or gomobile;
#   - the Xcode project's shell phases ask Gradle for no Go task and put no
#     Go directory on the PATH;
#   - release.yml's build-ios job installs no Go, checks out no olcrtc, makes
#     no gomobile, puts a Go that fails on the PATH before any build step, and
#     reads the app with scripts/check-no-go.sh (K1) before anything is
#     uploaded; no allow-list file stands for an iOS artefact.
#
#   scripts/ios-checks/no-go-build.sh [root]   root defaults to the repository
#
# Needs bash and python3 with PyYAML (the same as scripts/test-release-dry.sh).
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
python3 -I -c 'import yaml' 2>/dev/null || { echo "FAIL PyYAML is missing (python3-yaml)"; exit 1; }
exec python3 -I - "$root" <<'PY'
import os, re, subprocess, sys
import yaml

ROOT = os.path.abspath(sys.argv[1])
bad = []


def fail(msg):
    bad.append(msg)
    print("FAIL " + msg)


def ok(msg):
    print("ok   " + msg)


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


# 1. The files are gone.
for rel in (".github/workflows/ios-frameworks.yml", "scripts/build-cores-ios.sh", "scripts/fetch-cores-ios.sh"):
    if os.path.exists(os.path.join(ROOT, rel)):
        fail(rel + " is still in the tree")
    else:
        ok(rel + " is gone")

# 2. Nothing live names them. Docs and plans are history and are not read.
names = re.compile(r"ios-frameworks|build-cores-ios|fetch-cores-ios|fetchCoresIos|buildOlcrtcIos")
live_roots = [".github", "scripts", "sharedUI", "iosApp", "androidApp", "desktopApp", "cli", "Makefile", "build.gradle.kts"]
skip = {os.path.join("scripts", "ios-checks", "no-go-build.sh")}
hits = []
for top in live_roots:
    p = os.path.join(ROOT, top)
    if os.path.isfile(p):
        walk = [(os.path.dirname(p), [], [os.path.basename(p)])]
    else:
        walk = os.walk(p)
    for d, dirs, files in walk:
        dirs[:] = [x for x in dirs if x not in ("build", ".gradle", "node_modules", ".git")]
        for f in files:
            full = os.path.join(d, f)
            rel = os.path.relpath(full, ROOT)
            if rel in skip:
                continue
            try:
                text = open(full, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if names.search(line):
                    hits.append("%s:%d" % (rel, i))
owned = ("sharedUI/build.gradle.kts", "iosApp/iosApp.xcodeproj/project.pbxproj", ".github/workflows/release.yml")
mine = [h for h in hits if h.split(":")[0] in owned]
other = [h for h in hits if h.split(":")[0] not in owned]
if mine:
    fail("this task's files still name the Go frameworks: " + ", ".join(mine))
else:
    ok("Gradle, the Xcode project and release.yml name none of the Go frameworks' scripts, workflow or tasks")
if other:
    print("note other files still name them: " + ", ".join(other))

# 3. Gradle: no gomobile and no Go in the iOS tasks.
gradle = read("sharedUI/build.gradle.kts")
for pat in (r"gomobile", r"fetchCoresIos", r"buildOlcrtcIos", r"OlcRtcMobile"):
    if re.search(pat, gradle):
        fail("sharedUI/build.gradle.kts still says " + pat)
    else:
        ok("sharedUI/build.gradle.kts has no " + pat)

# 4. The Xcode project's phases.
pbx = read("iosApp/iosApp.xcodeproj/project.pbxproj")
for pat in (r"go/bin", r"/usr/local/go", r"fetchCoresIos", r"buildOlcrtcIos", r"gomobile"):
    if re.search(pat, pbx):
        fail("project.pbxproj still says " + pat)
    else:
        ok("project.pbxproj has no " + pat)

# 5. release.yml's build-ios job.
wf = yaml.safe_load(read(".github/workflows/release.yml"))
job = wf["jobs"].get("build-ios")
if job is None:
    fail("release.yml has no build-ios job")
    job = {"steps": []}
steps = job["steps"]
text = yaml.safe_dump(job)
for pat in (r"setup-go", r"GO_VERSION", r"gomobile (init|bind)", r"x/mobile", r"OLCRTC_REPO", r"ghostlane-project/olcrtc", r"olcrtc_ref"):
    if re.search(pat, text):
        fail("build-ios still has " + pat)
    else:
        ok("build-ios has no " + pat)


def idx(pred):
    for i, s in enumerate(steps):
        if pred(s):
            return i
    return None


def runs(s):
    return s.get("run", "") or ""


canary = idx(lambda s: "command -v go" in runs(s) and "GITHUB_PATH" not in runs(s) and "shim" not in s.get("name", "").lower())
shim = idx(lambda s: "GITHUB_PATH" in runs(s) and re.search(r"\bgo\b", runs(s)) and "exit 1" in runs(s))
first_build = idx(lambda s: "xcodebuild" in runs(s) or "gradlew" in runs(s))
if shim is None:
    fail("build-ios puts no failing `go` on the PATH (a step that writes GITHUB_PATH with a go that exits 1)")
elif first_build is not None and shim > first_build:
    fail("the failing `go` comes after the first build step")
else:
    ok("a failing `go` is on the PATH before every build step")
if canary is None:
    fail("build-ios has no step that finds the PATH's go to be the failing one")
elif first_build is not None and canary > first_build:
    fail("the PATH's go is checked after the first build step")
else:
    ok("the PATH's go is checked before every build step")

k1 = [i for i, s in enumerate(steps) if "check-no-go.sh" in runs(s)]
if len(k1) < 2:
    fail("build-ios runs check-no-go.sh in %d step(s); want the archive's app and the unsigned IPA" % len(k1))
else:
    ok("build-ios reads the app with check-no-go.sh in %d steps" % len(k1))
    # The signed path's read comes before the upload (the export step).
    export = idx(lambda s: "-exportArchive" in runs(s))
    archive_step = idx(lambda s: "archive" in runs(s) and "xcodebuild" in runs(s) and "-exportArchive" in runs(s))
    if export is not None and "check-no-go.sh" not in runs(steps[export]):
        fail("the archive's K1 read is not in the step that uploads, before the export")
    else:
        txt = runs(steps[export]) if export is not None else ""
        if txt.find("check-no-go.sh") > txt.find("-exportArchive") or "check-no-go.sh" not in txt:
            fail("K1 does not run before xcodebuild -exportArchive uploads")
        else:
            ok("K1 runs before the upload")

# 6. No allow-list entry stands for an iOS artefact.
allow = os.path.join(ROOT, "scripts", "no-go-allow.d")
ios = [f for f in os.listdir(allow) if re.search(r"ios|ipa", f, re.I)]
if ios:
    fail("an iOS allow-list file is still there: " + ", ".join(ios))
else:
    ok("no iOS allow-list file")

print("%d failure(s)" % len(bad))
sys.exit(1 if bad else 0)
PY
