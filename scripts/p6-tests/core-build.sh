#!/usr/bin/env bash
# The core built once per pin: what can be checked in seconds, with no
# network, no cargo and no compiler.
#   - the four build scripts parse;
#   - the BoringSSL proof reads a symbol table and nothing else: it passes on
#     an ELF and on a Mach-O listing that define BoringSSL's SSL_CTX_new and
#     DTLS_method, and fails on AWS-LC alone (prefixed names), on a reference
#     that is not a definition, on one name of the two, on a stripped file and
#     on a file that is not there;
#   - a dispatch's pin=<sha> is another build: the stamp names the other
#     commit, a short hash is refused, and a musl stamp also names the script
#     that makes its cross compiler; another Rust toolchain is another stamp;
#   - a second run on the same pin compiles nothing: with the first run's
#     output in place, both build scripts keep it, say so, write 0 to
#     compiled.txt and start neither cargo nor rustup (stand-ins that would
#     fail the run are first on PATH). The same output under another commit or
#     another Rust toolchain is not kept, nor for Android under another NDK,
#     and the Android script with no NDK named keeps nothing;
#   - .github/workflows/core-build.yml keeps its shape: pushes to rust/all and
#     pull requests into it, both on its own paths, and a dispatch with `pin`
#     wired to the scripts; read-only permissions and no secret; one macOS
#     job; only the Windows job may fail; every build job restores a cache
#     keyed by the commit, the Rust toolchain (and for Android the NDK), the
#     target and the hash of its scripts, and has the step that fails when a
#     cached build compiled something; every target of
#     the plan is named. Each rule is then planted broken in a copy and must
#     be found.
#
#   scripts/p6-tests/core-build.sh [root]     root defaults to the repository
# Needs bash, git, python3 and PyYAML.
set -euo pipefail
# What the build scripts read from the environment, as a run of core-build.yml
# sets it (a dispatch with pin=<sha> sets the override for every job): the
# test starts from none of it, and names each case's own.
unset GHOSTLANE_CORE_PIN_OVERRIDE GHOSTLANE_CORE_SRC GHOSTLANE_CORE_FAST GHOSTLANE_CORE_KEEP_GOING \
  GHOSTLANE_CORE_LINK_PROOF GHOSTLANE_MUSL_TOOLCHAIN_DIR NM GHOSTLANE_CORE_TOOLCHAIN ANDROID_NDK_VERSION
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
host="$root/scripts/build-ghostlane-core-host.sh"
android="$root/scripts/build-ghostlane-core-android.sh"
hev_linux="$root/scripts/build-hev-linux.sh"
hev_macos="$root/scripts/build-hev-macos.sh"
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
# expect <status> <what> <command>...: the command leaves with that status.
expect() {
  local want="$1" what="$2" got=0
  shift 2
  "$@" > "$tmp/out.txt" 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then ok "$what"; else bad "$what (status $got, wanted $want): $(tail -3 "$tmp/out.txt" | tr '\n' ' ')"; fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for f in "$host" "$android" "$hev_linux" "$hev_macos"; do
  if bash -n "$f"; then ok "$(basename "$f") parses"; else bad "$(basename "$f") does not parse"; fi
done

# --- The BoringSSL proof -----------------------------------------------------
# A stand-in for nm: it prints the listing the test names.
# shellcheck disable=SC2016
printf '#!/bin/sh\ncat "$FAKE_NM_LISTING"\n' > "$tmp/nm"
chmod +x "$tmp/nm"
: > "$tmp/some.a"
proof() { FAKE_NM_LISTING="$tmp/$1" NM="$tmp/nm" bash "$host" --boring-proof "$tmp/some.a"; }
cat > "$tmp/elf.txt" << 'EOF'
libghostlane_core.a(ssl_lib.o):
0000000000001000 T SSL_CTX_new
0000000000001200 t DTLS_method
0000000000001400 T SSL_do_handshake
                 U malloc
0000000000000000 T aws_lc_0_45_0_EVP_sha256
EOF
cat > "$tmp/macho.txt" << 'EOF'
0000000000001000 T _SSL_CTX_new
0000000000001200 T _DTLS_method
0000000000000000 T _aws_lc_0_45_0_EVP_sha256
EOF
cat > "$tmp/awslc.txt" << 'EOF'
0000000000000000 T aws_lc_0_45_0_SSL_CTX_new
0000000000000100 T aws_lc_0_45_0_DTLS_method
0000000000000200 T aws_lc_0_45_0_EVP_sha256
                 U SSL_CTX_new
                 U DTLS_method
0000000000000300 D DTLS_method
0000000000000400 T xray_core_start
EOF
cat > "$tmp/half.txt" << 'EOF'
0000000000001000 T SSL_CTX_new
0000000000000400 T xray_core_start
EOF
: > "$tmp/stripped.txt"
expect 0 "the proof passes on an ELF listing with BoringSSL" proof elf.txt
expect 0 "the proof passes on a Mach-O listing with BoringSSL" proof macho.txt
expect 1 "the proof fails on AWS-LC alone, on references and on data of the same name" proof awslc.txt
expect 1 "the proof fails when one of the two functions is missing" proof half.txt
expect 1 "the proof fails on a stripped file" proof stripped.txt
expect 2 "the proof says so when the file is not there" env NM="$tmp/nm" bash "$host" --boring-proof "$tmp/absent.a"
# A Windows program has no symbol table in the file: MSVC keeps its symbols in
# the .pdb beside it, which the build copies next to the program. The proof reads
# that PDB's public symbols (llvm-pdbutil dump --publics); the stand-in prints
# the listing the test names.
# shellcheck disable=SC2016
printf '#!/bin/sh\ncat "$FAKE_PDB_LISTING"\n' > "$tmp/pdbdump"
chmod +x "$tmp/pdbdump"
mkdir -p "$tmp/win"
: > "$tmp/win/ghostlane-engine.exe"
: > "$tmp/win/ghostlane_engine.pdb"
: > "$tmp/win/lonely.exe"
pdbproof() { FAKE_PDB_LISTING="$tmp/$1" PDB_DUMP="$tmp/pdbdump" NM="$tmp/nm" FAKE_NM_LISTING="$tmp/stripped.txt" bash "$host" --boring-proof "${2:-$tmp/win/ghostlane-engine.exe}"; }
cat > "$tmp/pdb-with.txt" << 'EOF'
                        Public Symbols
============================================================
  Records
         0 | S_PUB32 [size = 40] `SSL_CTX_new`
             flags = function, addr = 0001:4096
        40 | S_PUB32 [size = 40] `DTLS_method`
             flags = function, addr = 0001:4200
        80 | S_PUB32 [size = 36] `xray_core_start`
             flags = function, addr = 0001:4300
EOF
cat > "$tmp/pdb-without.txt" << 'EOF'
                        Public Symbols
============================================================
  Records
         0 | S_PUB32 [size = 36] `xray_core_start`
             flags = function, addr = 0001:4300
        40 | S_PUB32 [size = 40] `aws_lc_0_45_0_SSL_CTX_new`
             flags = function, addr = 0001:4400
        80 | S_PUB32 [size = 40] `SSL_CTX_new`
             flags = none, addr = 0003:0100
EOF
expect 0 "the proof reads BoringSSL from the PDB beside a Windows program" pdbproof pdb-with.txt
expect 1 "the proof fails on a PDB without BoringSSL (a data symbol or a prefixed one is not it)" pdbproof pdb-without.txt
expect 1 "a Windows program with no PDB beside it is read as a file and fails when stripped" pdbproof pdb-with.txt "$tmp/win/lonely.exe"
if grep -q -- '--boring-proof' "$android" && grep -q 'boring_proof "[$]OUT/lib' "$host"; then
  ok "both build scripts ask the proof of what they built"
else
  bad "a build script does not ask the BoringSSL proof of its library"
fi

# --- pin=<sha> is another build ----------------------------------------------
pin="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=//p' "$root/scripts/ghostlane-core-pin.sh")"
other="$(printf '%040d' 0 | tr 0 c)"
s_pin="$(bash "$host" --stamp aarch64-apple-darwin)"
s_other="$(GHOSTLANE_CORE_PIN_OVERRIDE="$other" bash "$host" --stamp aarch64-apple-darwin)"
s_musl="$(bash "$host" --stamp x86_64-unknown-linux-musl)"
case "$s_pin" in "$pin "*) ok "with no override the stamp names the pin" ;; *) bad "the stamp does not start with the pin: $s_pin" ;; esac
case "$s_other" in "$other "*) ok "with pin=<sha> the stamp names that commit" ;; *) bad "an override is not in the stamp: $s_other" ;; esac
case "$s_musl" in *" $(git hash-object "$hev_linux")") ok "a musl stamp names the script that makes its cross compiler" ;; *) bad "a musl stamp does not name build-hev-linux.sh: $s_musl" ;; esac
expect 2 "a short hash is refused as an override" env GHOSTLANE_CORE_PIN_OVERRIDE=abc123 bash "$host" --stamp aarch64-apple-darwin
# The toolchain the pin file names, and one it does not: a rustc of another
# version compiles other code from the same commit.
toolchain="$(bash "$root/scripts/ghostlane-core-resolve.sh" | sed -n 's/^GHOSTLANE_CORE_TOOLCHAIN=//p')"
other_toolchain="$toolchain.1"
s_toolchain="$(GHOSTLANE_CORE_TOOLCHAIN="$other_toolchain" bash "$host" --stamp aarch64-apple-darwin)"
s_musl_toolchain="$(GHOSTLANE_CORE_TOOLCHAIN="$other_toolchain" bash "$host" --stamp x86_64-unknown-linux-musl)"
if [ -n "$toolchain" ] && [ "$s_toolchain" != "$s_pin" ] && [ "$s_musl_toolchain" != "$s_musl" ]; then
  ok "another Rust toolchain is another stamp"
else
  bad "the stamp does not name the Rust toolchain: $s_pin under $toolchain, $s_toolchain under $other_toolchain"
fi

# --- A second run compiles nothing -------------------------------------------
# Stand-ins that fail the run and leave a mark if a build tool is started.
# git stays real for hash-object, and is refused anything else.
mkdir -p "$tmp/bin" "$tmp/home"
real_git="$(command -v git)"
for tool in cargo rustup docker cmake; do
  # shellcheck disable=SC2016
  printf '#!/bin/sh\necho "%s $*" >> "%s/started.txt"\nexit 99\n' "$tool" "$tmp" > "$tmp/bin/$tool"
done
# shellcheck disable=SC2016
printf '#!/bin/sh\ncase "$1" in hash-object) exec "%s" "$@" ;; esac\necho "git $*" >> "%s/started.txt"\nexit 98\n' "$real_git" "$tmp" > "$tmp/bin/git"
chmod +x "$tmp/bin"/*
# sealed <command>...: with the stand-ins first on PATH and a HOME that has no ~/.cargo/bin.
sealed() { env HOME="$tmp/home" PATH="$tmp/bin:$PATH" "$@"; }

out="$tmp/host-out"
for t in aarch64-apple-darwin x86_64-unknown-linux-musl; do
  mkdir -p "$out/stamps" "$out/report" "$out/lib/$t"
  { bash "$host" --stamp "$t"; echo "engine no"; } > "$out/stamps/$t"
  echo "an archive" > "$out/lib/$t/libghostlane_core.a"
  echo "BUILD $t: a first run" > "$out/report/$t.txt"
done
rm -f "$tmp/started.txt"
expect 0 "the host script keeps a build that is already there" sealed bash "$host" "$out" aarch64-apple-darwin x86_64-unknown-linux-musl
if [ "$(cat "$out/compiled.txt" 2> /dev/null)" = 0 ] && grep -q '^KEPT aarch64-apple-darwin' "$tmp/out.txt" &&
  grep -q '^COMPILED 0' "$tmp/out.txt" && [ ! -e "$tmp/started.txt" ] && grep -q '^BUILD aarch64-apple-darwin' "$out/report.txt"; then
  ok "a kept host build compiles nothing: no cargo, no rustup, no fetch, compiled.txt is 0, the report is the first run's"
else
  bad "a kept host build started something or did not say 0: $(head -3 "$tmp/started.txt" 2> /dev/null | tr '\n' ' ')"
fi
rm -f "$tmp/started.txt"
expect 98 "the same output under another commit is not kept: the host script goes to fetch that commit" \
  sealed env GHOSTLANE_CORE_PIN_OVERRIDE="$other" GHOSTLANE_CORE_SRC="$tmp/src" bash "$host" "$out" aarch64-apple-darwin
if grep -q '^git init' "$tmp/started.txt" 2> /dev/null; then ok "and it started by making a checkout"; else bad "another commit did not lead to a checkout"; fi
rm -f "$tmp/started.txt"
expect 98 "the same output under another Rust toolchain is not kept: the host script goes to build it" \
  sealed env GHOSTLANE_CORE_TOOLCHAIN="$other_toolchain" GHOSTLANE_CORE_SRC="$tmp/src" bash "$host" "$out" aarch64-apple-darwin x86_64-unknown-linux-musl
echo "engine yes" >> "$out/stamps/aarch64-apple-darwin"
sed -i.bak '2d' "$out/stamps/aarch64-apple-darwin"
rm -f "$tmp/started.txt"
expect 98 "a build whose stamp promises the desktop program and has none is not kept" \
  sealed env GHOSTLANE_CORE_SRC="$tmp/src" bash "$host" "$out" aarch64-apple-darwin

out="$tmp/android-out"
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$out/jniLibs/$abi" "$out/symbols/$abi"
  echo "a library" > "$out/jniLibs/$abi/libghostlane_core.so"
  echo "a library with symbols" > "$out/symbols/$abi/libghostlane_core.so"
done
mkdir -p "$out/kotlin/org"
echo "// bindings" > "$out/kotlin/org/olc_ffi.kt"
echo "PIN a first run" > "$out/report.txt"
# A first run's record, by the NDK it was linked with: the run names the NDK
# (core-build.yml's ANDROID_NDK_VERSION), the SDK it lives in is not needed to
# keep it.
ndk=28.2.13676358
other_ndk=29.0.14206865
echo "$pin shipped rust $toolchain ndk $ndk $(git hash-object "$android") $(git hash-object "$host")" > "$out/ghostlane-core-android.commit"
rm -f "$tmp/started.txt"
expect 0 "the Android script keeps a build that is already there, with no SDK at hand" \
  sealed env -u ANDROID_HOME ANDROID_NDK_VERSION="$ndk" bash "$android" "$out"
if [ "$(cat "$out/compiled.txt" 2> /dev/null)" = 0 ] && grep -q '^KEPT ' "$tmp/out.txt" && [ ! -e "$tmp/started.txt" ]; then
  ok "a kept Android build compiles nothing: no cargo, no rustup, compiled.txt is 0"
else
  bad "a kept Android build started something or did not say 0"
fi
# Not kept: the script goes on to build, and stops where it needs the SDK.
expect 1 "the same Android output under another commit is not kept" \
  sealed env -u ANDROID_HOME ANDROID_NDK_VERSION="$ndk" GHOSTLANE_CORE_PIN_OVERRIDE="$other" bash "$android" "$out"
echo "$pin shipped rust $toolchain ndk $ndk $(git hash-object "$android") $(git hash-object "$host")" > "$out/ghostlane-core-android.commit"
expect 1 "the same Android output under another NDK is not kept" \
  sealed env -u ANDROID_HOME ANDROID_NDK_VERSION="$other_ndk" bash "$android" "$out"
echo "$pin shipped rust $toolchain ndk $ndk $(git hash-object "$android") $(git hash-object "$host")" > "$out/ghostlane-core-android.commit"
expect 1 "the same Android output under another Rust toolchain is not kept" \
  sealed env -u ANDROID_HOME ANDROID_NDK_VERSION="$ndk" GHOSTLANE_CORE_TOOLCHAIN="$other_toolchain" bash "$android" "$out"
echo "$pin shipped rust $toolchain ndk $ndk $(git hash-object "$android") $(git hash-object "$host")" > "$out/ghostlane-core-android.commit"
expect 1 "with no NDK named the Android script keeps nothing: what it would keep depends on the NDK" \
  sealed env -u ANDROID_HOME -u ANDROID_NDK_VERSION bash "$android" "$out"
if [ -e "$tmp/started.txt" ]; then bad "an Android build that was not kept started a build tool before the SDK check"; fi

# --- The hev scripts refuse what they cannot build ----------------------------
expect 2 "build-hev-linux.sh with no arguments is a usage error" bash "$hev_linux"
expect 2 "build-hev-linux.sh makes no cross compiler for a target that is not musl" \
  sealed bash "$hev_linux" --toolchain "$tmp/tc" x86_64-unknown-linux-gnu
expect 2 "build-hev-macos.sh with no arguments is a usage error" bash "$hev_macos"
mkdir -p "$tmp/not-hev"
expect 1 "build-hev-linux.sh refuses a directory that is not hev's source" \
  sealed bash "$hev_linux" "$tmp/not-hev" "$tmp/hev-out" x86_64-unknown-linux-musl

[ "$fail" -eq 0 ] || exit 1

# --- The workflow's shape ------------------------------------------------------
exec python3 -I - "$root" << 'PY'
import copy, re, sys
import yaml

root = sys.argv[1]
WF = ".github/workflows/core-build.yml"
OWN = [
    ".github/workflows/core-build.yml",
    "scripts/ghostlane-core-pin.sh",
    "scripts/ghostlane-core-resolve.sh",
    "scripts/build-ghostlane-core-host.sh",
    "scripts/build-ghostlane-core-android.sh",
    "scripts/build-hev-linux.sh",
    "scripts/build-hev-macos.sh",
    "androidApp/src/main/jni/hev-socks5-tunnel/**",
]
TARGETS = [
    "aarch64-apple-darwin", "x86_64-apple-darwin", "aarch64-apple-ios", "aarch64-apple-ios-sim",
    "x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl", "armv7-unknown-linux-musleabihf",
    "x86_64-pc-windows-msvc",
]
ACTIONS = {"actions/checkout", "actions/cache/restore", "actions/cache/save",
           "actions/upload-artifact", "android-actions/setup-android"}
# job -> the scripts whose hash must be in the key of the cache its build is kept in
BUILDS = {
    "android": ["scripts/build-ghostlane-core-android.sh", "scripts/build-ghostlane-core-host.sh"],
    "apple": ["scripts/build-ghostlane-core-host.sh"],
    "linux": ["scripts/build-ghostlane-core-host.sh", "scripts/build-hev-linux.sh"],
    "windows": ["scripts/build-ghostlane-core-host.sh"],
}
WIRED = "${{ github.event_name == 'workflow_dispatch' && inputs.pin || '' }}"
# What else decides a build and so must be in its key: the Rust toolchain the
# pin file names (the plan job's output, from scripts/ghostlane-core-resolve.sh),
# and for Android the NDK.
TOOLCHAIN = "needs.plan.outputs.toolchain"
NDK = "env.ANDROID_NDK_VERSION"


def check(doc):
    bad = []
    on = doc.get(True, doc.get("on")) or {}
    if set(on) != {"push", "pull_request", "workflow_dispatch"}:
        bad.append("triggers must be push, pull_request and workflow_dispatch, found %s" % sorted(map(str, on)))
    for ev in ("push", "pull_request"):
        e = on.get(ev) or {}
        if e.get("branches") != ["rust/all"]:
            bad.append("%s must be limited to the branch rust/all, found %s" % (ev, e.get("branches")))
        if sorted(e.get("paths") or []) != sorted(OWN):
            bad.append("%s paths must be the workflow's own files, found %s" % (ev, e.get("paths")))
    inputs = ((on.get("workflow_dispatch") or {}).get("inputs") or {})
    if "pin" not in inputs:
        bad.append("workflow_dispatch has no `pin` input")
    if (doc.get("env") or {}).get("GHOSTLANE_CORE_PIN_OVERRIDE") != WIRED:
        bad.append("GHOSTLANE_CORE_PIN_OVERRIDE is not wired to the dispatch's pin input for every job")
    if doc.get("permissions") != {"contents": "read"}:
        bad.append("permissions must be contents: read and nothing more, found %s" % doc.get("permissions"))
    jobs = doc.get("jobs") or {}
    text = yaml.safe_dump(doc)
    if re.search(r"\bsecrets\s*\.", text) or "secrets:" in text:
        bad.append("the workflow names a secret")
    macs = [n for n, j in jobs.items() if "macos" in str(j.get("runs-on"))]
    if len(macs) != 1:
        bad.append("exactly one job may run on macOS, found %s" % macs)
    for n, j in jobs.items():
        if "permissions" in j:
            bad.append("job %s sets permissions of its own" % n)
        if j.get("continue-on-error") and n != "windows":
            bad.append("job %s may fail without failing the run: only windows may" % n)
        if j.get("env", {}).get("GHOSTLANE_CORE_PIN_OVERRIDE") is not None:
            bad.append("job %s overrides GHOSTLANE_CORE_PIN_OVERRIDE" % n)
        for s in j.get("steps") or []:
            uses = s.get("uses")
            if uses and uses.split("@")[0] not in ACTIONS:
                bad.append("job %s uses %s, which is not on this workflow's list" % (n, uses))
            if (s.get("env") or {}).get("GHOSTLANE_CORE_PIN_OVERRIDE") is not None:
                bad.append("a step of job %s overrides GHOSTLANE_CORE_PIN_OVERRIDE" % n)
    plan = jobs.get("plan") or {}
    plan_run = " ".join(str(s.get("run", "")) for s in plan.get("steps") or [])
    if str((plan.get("outputs") or {}).get("toolchain", "")).replace(" ", "") != "${{steps.core.outputs.toolchain}}" \
            or "toolchain=$GHOSTLANE_CORE_TOOLCHAIN" not in plan_run or "ghostlane-core-resolve.sh" not in plan_run:
        bad.append("the plan job must give the toolchain the resolver names as its output `toolchain`")
    if not jobs.get("windows", {}).get("continue-on-error"):
        bad.append("the windows job must be allowed to fail until the fork compiles there")
    for n, scripts in BUILDS.items():
        j = jobs.get(n)
        if not j:
            bad.append("no job %s" % n)
            continue
        steps = j.get("steps") or []
        restore = [s for s in steps if str(s.get("uses", "")).startswith("actions/cache/restore") and s.get("id") == "cache"]
        if len(restore) != 1:
            bad.append("job %s must restore exactly one cache with the id `cache`" % n)
            continue
        key = str(restore[0].get("with", {}).get("key", ""))
        if "needs.plan.outputs.commit" not in key:
            bad.append("the cache key of job %s does not name the commit of the core" % n)
        if TOOLCHAIN not in key:
            bad.append("the cache key of job %s does not name the Rust toolchain" % n)
        if n == "android" and NDK not in key:
            bad.append("the cache key of job android does not name the NDK")
        if n == "linux" and "matrix.target" not in key:
            bad.append("the cache key of job linux does not name the target")
        m = re.search(r"hashFiles\(([^)]*)\)", key)
        hashed = re.findall(r"'([^']+)'", m.group(1)) if m else []
        if sorted(hashed) != sorted(scripts):
            bad.append("the cache key of job %s must hash %s, found %s" % (n, scripts, hashed))
        guard = [s for s in steps if "compiled.txt" in str(s.get("run", "")) and "exit 1" in str(s.get("run", ""))
                 and str(s.get("if", "")).replace(" ", "") == "steps.cache.outputs.cache-hit=='true'"]
        if len(guard) != 1:
            bad.append("job %s has no step that fails when a cached build compiled something" % n)
        save = [s for s in steps if str(s.get("uses", "")).startswith("actions/cache/save")
                and "steps.cache.outputs.cache-primary-key" in str(s.get("with", {}).get("key", ""))]
        if len(save) != 1 or "cache-hit != 'true'" not in str(save[0].get("if", "")):
            bad.append("job %s must save its build under the restored key, and only on a miss" % n)
        if "needs" not in j or "plan" not in (j["needs"] if isinstance(j["needs"], list) else [j["needs"]]):
            bad.append("job %s does not wait for the plan" % n)
    named = yaml.safe_dump(jobs)
    # Where each target must be named: the job that builds the core for it.
    matrix = (((jobs.get("linux") or {}).get("strategy") or {}).get("matrix") or {}).get("target") or []
    if sorted(matrix) != sorted(t for t in TARGETS if "linux" in t):
        bad.append("the linux job must build the three musl targets, found %s" % matrix)
    for t in TARGETS:
        if "linux" in t:
            continue
        job = "windows" if "windows" in t else "apple"
        runs = " ".join(str(s.get("run", "")) for s in (jobs.get(job) or {}).get("steps") or [])
        if not re.search(r"(^|\s)%s(\s|$)" % re.escape(t), runs):
            bad.append("job %s does not build the target %s" % (job, t))
    if "build-ghostlane-core-android.sh" not in named:
        bad.append("no job runs the Android build script")
    for script in ("build-hev-linux.sh", "build-hev-macos.sh"):
        if script not in named:
            bad.append("no job runs %s" % script)
    return bad


def on_of(d):
    return d[True] if True in d else d["on"]


def selftest(doc):
    def plant(what, change):
        d = copy.deepcopy(doc)
        change(d)
        if not check(d):
            return ["self-test: planted '%s' was not found" % what]
        return []

    def step(d, job, pred):
        return next(s for s in d["jobs"][job]["steps"] if pred(s))

    out = []
    out += plant("every rust/** push", lambda d: on_of(d)["push"].__setitem__("branches", ["rust/**"]))
    out += plant("a pull request into main", lambda d: on_of(d)["pull_request"]["branches"].append("main"))
    out += plant("a path that is not its own", lambda d: on_of(d)["push"]["paths"].append("androidApp/**"))
    out += plant("a schedule", lambda d: on_of(d).__setitem__("schedule", [{"cron": "0 3 * * *"}]))
    out += plant("no pin input", lambda d: on_of(d)["workflow_dispatch"]["inputs"].pop("pin"))
    out += plant("pin not wired", lambda d: d["env"].__setitem__("GHOSTLANE_CORE_PIN_OVERRIDE", ""))
    out += plant("a step that drops the override", lambda d: step(d, "apple", lambda s: s.get("name") == "Build")
                 .setdefault("env", {}).__setitem__("GHOSTLANE_CORE_PIN_OVERRIDE", ""))
    out += plant("wider permissions", lambda d: d["permissions"].__setitem__("actions", "read"))
    out += plant("a job with permissions", lambda d: d["jobs"]["linux"].__setitem__("permissions", {"contents": "write"}))
    out += plant("a secret", lambda d: step(d, "linux", lambda s: s.get("name") == "Build")["env"]
                 .__setitem__("TOKEN", "${{ secrets.SOMETHING }}"))
    out += plant("a second macOS job", lambda d: d["jobs"]["hev-linux"].__setitem__("runs-on", "macos-15"))
    out += plant("linux allowed to fail", lambda d: d["jobs"]["linux"].__setitem__("continue-on-error", True))
    out += plant("windows required", lambda d: d["jobs"]["windows"].pop("continue-on-error"))
    out += plant("a key without the commit", lambda d: step(d, "apple", lambda s: s.get("id") == "cache")["with"]
                 .__setitem__("key", "core-build-apple-${{ hashFiles('scripts/build-ghostlane-core-host.sh') }}"))
    out += plant("a key without the scripts", lambda d: step(d, "android", lambda s: s.get("id") == "cache")["with"]
                 .__setitem__("key", "core-build-android-${{ needs.plan.outputs.commit }}"))
    def without(job, what):
        def change(d):
            w = step(d, job, lambda s: s.get("id") == "cache")["with"]
            w["key"] = w["key"].replace(what, "")
        return change
    out += plant("an Apple key without the toolchain", without("apple", "-rust-${{ needs.plan.outputs.toolchain }}"))
    out += plant("a Linux key without the toolchain", without("linux", "-rust-${{ needs.plan.outputs.toolchain }}"))
    out += plant("a Windows key without the toolchain", without("windows", "-rust-${{ needs.plan.outputs.toolchain }}"))
    out += plant("an Android key without the toolchain", without("android", "-rust-${{ needs.plan.outputs.toolchain }}"))
    out += plant("an Android key without the NDK", without("android", "-ndk-${{ env.ANDROID_NDK_VERSION }}"))
    out += plant("a plan with no toolchain output", lambda d: d["jobs"]["plan"]["outputs"].pop("toolchain"))
    out += plant("a key without the target", lambda d: step(d, "linux", lambda s: s.get("id") == "cache")["with"]
                 .__setitem__("key", "core-build-${{ needs.plan.outputs.commit }}-${{ hashFiles('scripts/build-ghostlane-core-host.sh', 'scripts/build-hev-linux.sh') }}"))
    out += plant("no guard on a cached build", lambda d: d["jobs"]["windows"]["steps"].remove(
        step(d, "windows", lambda s: "compiled.txt" in str(s.get("run", "")))))
    out += plant("a save on a hit", lambda d: step(d, "linux", lambda s: str(s.get("uses", "")).startswith("actions/cache/save"))
                 .pop("if"))
    out += plant("a musl target dropped", lambda d: d["jobs"]["linux"]["strategy"]["matrix"]["target"].remove("armv7-unknown-linux-musleabihf"))

    def drop_x86_64_mac(d):
        s = step(d, "apple", lambda s: s.get("name") == "Build")
        s["run"] = s["run"].replace(" x86_64-apple-darwin", "")
    out += plant("an Apple target dropped", drop_x86_64_mac)
    out += plant("an action from elsewhere", lambda d: d["jobs"]["plan"]["steps"].append({"uses": "someone/else@v1"}))
    return out


with open(root + "/" + WF, encoding="utf-8") as f:
    doc = yaml.safe_load(f)
bad = check(doc)
if not bad:
    bad = selftest(doc)
for b in bad:
    print("FAIL " + b)
if bad:
    sys.exit(1)
print("ok   core-build.yml: rust/all and its own paths, pin wired, read-only, one macOS job, a cache per commit, toolchain (and NDK), target and scripts (and each planted violation is found)")
PY
