#!/usr/bin/env bash
# The iOS closure class and size (scripts/ios-closure.sh, scripts/pin-move.d/ios-size).
#
# Offline, on a small made-up core repository: the closure is the crates the
# iPhone's library build reaches, and only those.
#   - a crate's source, a normal or build dependency, an optional one a feature
#     that is on turns on, a target table that holds for iOS, a registry
#     crate's checksum and the release profile each change the class;
#   - a test or a document of a member, a dev-dependency, an optional crate no
#     feature turns on and a crate for another operating system change nothing;
#   - the same commit against itself is "same".
# The size delta: 200 KiB of growth fails, 100 KiB does not, a shrink does not,
# a limit can be named, a malformed number is refused; and the pin-move step
# prints the class, refuses a growth past the limit and leaves the record as it
# was, appends to it on a pass, and asks for a size when none is given.
#
# Then on the real core, fetched from GitHub with no blobs (skipped when
# IOS_CLOSURE_OFFLINE=1): a8e95224 against itself is "same", f9db68bb against it
# is "changed".
#
#   scripts/p6-tests/ios-closure.sh [root]     root defaults to the repository
# Needs bash, git and python3 3.11+.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
tool="$root/scripts/ios-closure.sh"
step="$root/scripts/pin-move.d/ios-size"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0
bad() { echo "FAIL $*"; fail=1; }

for f in "$tool" "$step"; do
  [ -x "$f" ] || bad "$f is not executable"
  bash -n "$f" || bad "$f does not parse"
done
headpin="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=\([0-9a-f]\{40\}\)$/\1/p' "$root/scripts/ghostlane-core-pin.sh" | head -n 1)"
[ -n "$headpin" ] || bad "no pin in scripts/ghostlane-core-pin.sh"
grep -q "^${headpin:0:12} [0-9][0-9]*\$" "$root/scripts/pin-move.d/ios-size.record" || bad "the size record holds no line for the pin at HEAD (${headpin:0:12})"

# ---- a made-up core repository -------------------------------------------------
repo="$tmp/core"
git init -q "$repo"
git -C "$repo" config user.email t@example.invalid
git -C "$repo" config user.name t
w() { mkdir -p "$(dirname "$repo/$1")"; cat > "$repo/$1"; }

w Cargo.toml << 'T'
[workspace]
members = ["crates/ghostlane-core", "crates/lib-a", "crates/opt-b", "crates/opt-off", "crates/dev-c", "crates/win-d", "crates/ios-e", "crates/build-f"]
resolver = "2"
[workspace.package]
version = "1.0.0"
[profile.release]
lto = "thin"
T
w crates/ghostlane-core/Cargo.toml << 'T'
[package]
name = "ghostlane-core"
version.workspace = true
[dependencies]
lib-a = { path = "../lib-a" }
opt-b = { path = "../opt-b", optional = true }
opt-off = { path = "../opt-off", optional = true }
ext-x = "1"
[build-dependencies]
build-f = { path = "../build-f" }
[dev-dependencies]
dev-c = { path = "../dev-c" }
[target.'cfg(windows)'.dependencies]
win-d = { path = "../win-d" }
[target.'cfg(target_os = "ios")'.dependencies]
ios-e = { path = "../ios-e" }
[features]
boring = ["dep:opt-b"]
other = ["dep:opt-off"]
T
for c in lib-a opt-b opt-off dev-c win-d ios-e build-f; do
  w "crates/$c/Cargo.toml" << T
[package]
name = "$c"
version.workspace = true
T
  w "crates/$c/src/lib.rs" << T
pub fn f() -> u32 { 1 }
T
done
w crates/ghostlane-core/src/lib.rs << 'T'
pub fn f() {}
T
w Cargo.lock << 'T'
version = 4

[[package]]
name = "build-f"
version = "1.0.0"

[[package]]
name = "dev-c"
version = "1.0.0"

[[package]]
name = "ext-x"
version = "1.2.3"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "1111111111111111111111111111111111111111111111111111111111111111"

[[package]]
name = "ghostlane-core"
version = "1.0.0"
dependencies = ["build-f", "dev-c", "ext-x", "ios-e", "lib-a", "opt-b", "opt-off", "win-d"]

[[package]]
name = "ios-e"
version = "1.0.0"

[[package]]
name = "lib-a"
version = "1.0.0"

[[package]]
name = "opt-b"
version = "1.0.0"

[[package]]
name = "opt-off"
version = "1.0.0"

[[package]]
name = "win-d"
version = "1.0.0"
T
git -C "$repo" add -A
git -C "$repo" commit -q -m base
c0="$(git -C "$repo" rev-parse HEAD)"

# edit <name> <command...>: commit the change the command makes in the repository
# (run inside it) on top of the previous one and set $c to the new commit.
edit() {
  local name="$1"
  shift
  (cd "$repo" && "$@")
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "$name"
  c="$(git -C "$repo" rev-parse HEAD)"
}
klass() { bash "$tool" class "$1" "$2" "$repo" | tail -n 1; }
expect() { # expect <same|changed> <commit-a> <commit-b> <what>
  local got
  got="$(klass "$2" "$3")"
  [ "$got" = "class: $1" ] || bad "$4: expected $1, got '$got'"
}

expect same "$c0" "$c0" "a commit against itself"
d0="$(bash "$tool" digest "$c0" "$repo" | tail -n 1)"
[ "$d0" = "$(bash "$tool" digest "$c0" "$repo" | tail -n 1)" ] || bad "the digest is not stable"
bash "$tool" digest "$c0" "$repo" | grep -q '^path lib-a 1.0.0 crates/lib-a ' || bad "the digest does not name lib-a"
bash "$tool" digest "$c0" "$repo" | grep -q '^path ios-e ' || bad "the target table for iOS is not followed"
bash "$tool" digest "$c0" "$repo" | grep -q '^path opt-b ' || bad "the feature's optional crate is not followed"
bash "$tool" digest "$c0" "$repo" | grep -q '^path build-f ' || bad "a build dependency is not followed"
for absent in dev-c win-d opt-off; do
  if bash "$tool" digest "$c0" "$repo" | grep -q "^path $absent "; then bad "$absent is in the closure"; fi
done

prev="$c0"
same_edit() { edit "$@"; expect same "$prev" "$c" "$1"; prev="$c"; }
chg_edit() { edit "$@"; expect changed "$prev" "$c" "$1"; prev="$c"; }

same_edit "a test of a member" bash -c 'mkdir -p crates/lib-a/tests && echo "fn t() {}" > crates/lib-a/tests/t.rs'
same_edit "a document of a member" bash -c 'echo notes > crates/lib-a/README.md'
same_edit "a bench of a member" bash -c 'mkdir -p crates/lib-a/benches && echo "fn b() {}" > crates/lib-a/benches/b.rs'
same_edit "a dev-dependency's source" bash -c 'echo "pub fn f() -> u32 { 2 }" > crates/dev-c/src/lib.rs'
same_edit "a crate for windows" bash -c 'echo "pub fn f() -> u32 { 3 }" > crates/win-d/src/lib.rs'
same_edit "a crate no feature turns on" bash -c 'echo "pub fn f() -> u32 { 4 }" > crates/opt-off/src/lib.rs'
same_edit "a crate outside the closure's tree" bash -c 'mkdir -p crates/ghostlane-core/tests && echo "fn t() {}" > crates/ghostlane-core/tests/t.rs'

chg_edit "the source of a dependency" bash -c 'echo "pub fn f() -> u32 { 5 }" > crates/lib-a/src/lib.rs'
chg_edit "the core's own source" bash -c 'echo "pub fn f() { let _ = 1; }" > crates/ghostlane-core/src/lib.rs'
chg_edit "an optional crate a feature turns on" bash -c 'echo "pub fn f() -> u32 { 6 }" > crates/opt-b/src/lib.rs'
chg_edit "a crate of the iOS target table" bash -c 'echo "pub fn f() -> u32 { 7 }" > crates/ios-e/src/lib.rs'
chg_edit "a build dependency" bash -c 'echo "pub fn f() -> u32 { 8 }" > crates/build-f/src/lib.rs'
chg_edit "a registry crate's checksum" sed -i 's/^checksum = "1111/checksum = "2222/' Cargo.lock
chg_edit "the release profile" sed -i 's/lto = "thin"/lto = "fat"/' Cargo.toml
chg_edit "a workspace dependency's features" bash -c 'printf "[workspace.dependencies]\next-x = { version = \"1\", features = [\"pure\"] }\n" >> Cargo.toml'
chg_edit "a workspace dependency's features, one fewer" sed -i 's/features = \["pure"\]/features = []/' Cargo.toml
chg_edit "the workspace edition" sed -i 's/^version = "1.0.0"$/version = "1.0.0"\nedition = "2021"/' Cargo.toml
chg_edit "a pinned toolchain" bash -c 'printf "[toolchain]\nchannel = \"1.96.0\"\n" > rust-toolchain.toml'
chg_edit "a cargo config" bash -c 'mkdir -p .cargo && printf "[build]\nrustflags = [\"-Cdebuginfo=0\"]\n" > .cargo/config.toml'
# the root crate stops reaching a crate: the features that turn it on go
chg_edit "a feature that no longer turns a crate on" sed -i 's/^boring = \["dep:opt-b"\]/boring = []/' crates/ghostlane-core/Cargo.toml
same_edit "the crate the feature no longer reaches" bash -c 'echo "pub fn f() -> u32 { 9 }" > crates/opt-b/src/lib.rs'

# ---- the size delta ------------------------------------------------------------
sd() { bash "$tool" size-delta "$@" > /dev/null 2>&1; }
base=229838024
sd "$base" $((base + 200 * 1024)) && bad "a planted growth of 200 KiB passed the size delta"
sd "$base" $((base + 100 * 1024)) || bad "a growth of 100 KiB failed the size delta"
sd "$base" "$base" || bad "an unchanged size failed"
sd "$base" $((base - 5000000)) || bad "a shrink failed"
sd "$base" $((base + 200 * 1024)) $((300 * 1024)) || bad "a named limit of 300 KiB failed a growth of 200 KiB"
IOS_SIZE_MAX_GROWTH=$((300 * 1024)) bash "$tool" size-delta "$base" $((base + 200 * 1024)) > /dev/null 2>&1 || bad "the limit in the environment was not read"
sd "$base" $((base + 1)) 0 && bad "a growth of one byte passed a limit of 0"
rc=0
bash "$tool" size-delta abc 100 > /dev/null 2>&1 || rc=$?
[ "$rc" -eq 2 ] || bad "a malformed number gave $rc, not 2"

# ---- the pin-move step ---------------------------------------------------------
rec="$tmp/record"
printf '# header\n%s %s\n' "${c0:0:12}" "$base" > "$rec"
run_step() { # run_step <old> <new> [env...]: stdout and stderr in $tmp/out
  local o="$1" n="$2"
  shift 2
  env IOS_OLD_COMMIT="$o" IOS_CLOSURE_REPO="$repo" IOS_SIZE_RECORD="$rec" "$@" "$step" "$n" > "$tmp/out" 2>&1
}
rc=0
run_step "$c0" "$prev" IOS_LIB_BYTES=$((base + 200 * 1024)) || rc=$?
[ "$rc" -ne 0 ] || bad "the step passed a growth of 200 KiB"
grep -q '^class: changed$' "$tmp/out" || bad "the step did not print the class"
[ "$(grep -c "^${prev:0:12} " "$rec")" = 0 ] || bad "a failed step wrote the record"
rc=0
run_step "$c0" "$prev" || rc=$?
[ "$rc" -ne 0 ] || bad "the step ran with no size given"
grep -q 'IOS_LIB_BYTES' "$tmp/out" || bad "the step did not say how to give the size"
printf 'build\nSIZE aarch64-apple-ios-sim: libghostlane_core.a 1 bytes\nSIZE aarch64-apple-ios: libghostlane_core.a %s bytes\n' $((base + 4096)) > "$tmp/report.txt"
run_step "$c0" "$prev" IOS_CORE_REPORT="$tmp/report.txt" || { bad "the step failed on a growth of 4 KiB from a report"; cat "$tmp/out"; }
[ "$(tail -n 1 "$rec")" = "${prev:0:12} $((base + 4096))" ] || bad "the step did not append its line: $(tail -n 1 "$rec")"
# the baseline is the line of the pin the move leaves, not the record's last line
printf '%s %s\n' "${c0:0:12}" "$base" > "$rec"
printf '%s %s\n' "ffffffffffff" "$((base + 50 * 1024 * 1024))" >> "$rec"
run_step "$c0" "$prev" IOS_LIB_BYTES=$((base + 4096)) || { bad "the step used a line that is not the old pin's"; cat "$tmp/out"; }
printf '%s %s\n' "ffffffffffff" "$base" > "$rec"
rc=0
run_step "$c0" "$prev" IOS_LIB_BYTES=$((base + 4096)) || rc=$?
[ "$rc" -ne 0 ] || bad "the step passed with no line for the pin it leaves"
grep -q 'no size for the pin' "$tmp/out" || bad "the step did not say the old pin has no line"
printf '%s %s\n' "${c0:0:12}" "$base" > "$rec"
printf '%s %s\n' "${prev:0:12}" "$((base + 4096))" >> "$rec"
run_step "$prev" "$prev" IOS_LIB_BYTES=$((base + 4096)) || bad "a pin that does not move failed"
grep -q '^class: same$' "$tmp/out" || bad "a pin that does not move is not 'same'"
[ "$(grep -c "^${prev:0:12} " "$rec")" = 1 ] || bad "the record holds a commit twice"

# ---- the real core -------------------------------------------------------------
if [ "${IOS_CLOSURE_OFFLINE:-0}" = "1" ]; then
  echo "skipped: the real pins (IOS_CLOSURE_OFFLINE=1)"
else
  export IOS_CLOSURE_CACHE="$tmp/cache"
  a=a8e95224f099e2ec4b61f6b1b6eee0739c298180
  f=f9db68bb02bc6d97fb52bd9552fe234e2fd57550
  [ "$(bash "$tool" class "$a" "$a" | tail -n 1)" = "class: same" ] || bad "a8e95224 against itself is not same"
  out="$(bash "$tool" class "$a" "$f")" || bad "the class of f9db68bb against a8e95224 failed"
  [ "$(printf '%s\n' "$out" | tail -n 1)" = "class: changed" ] || bad "f9db68bb against a8e95224 is not changed: $out"
fi
[ "$fail" -eq 0 ] || exit 1
echo "ios-closure: ok"
