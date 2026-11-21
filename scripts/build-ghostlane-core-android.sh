#!/usr/bin/env bash
# build-ghostlane-core-android.sh <output-dir>
#
# Builds the Android side of ghostlane-core at the pin in
# scripts/ghostlane-core-pin.sh (the same pin as the iOS build):
#   - libghostlane_core.so for arm64-v8a, armeabi-v7a and x86_64 with the
#     `boring` feature (BoringSSL's DTLS: VK Calls' SFU refuses str0m's own),
#     the workspace's release profile as it ships (thin LTO, one codegen
#     unit), linked by the NDK the app pins at minSdk 23 with
#     -Wl,-z,max-page-size=16384, stripped, into <output-dir>/jniLibs/<abi>/;
#   - the UniFFI Kotlin bindings, generated in library mode by olc-ffi's own
#     uniffi-bindgen from the x86_64 static archive (the stripped .so has
#     lost the metadata symbols), into <output-dir>/kotlin/ (never committed:
#     always the library's own);
#   - the report (<output-dir>/report.txt), each line starting with its kind:
#     PIN, BUILD, SIZE, ALIGN (every LOAD segment 16 KiB-aligned), BORING
#     (BoringSSL really linked), IMPORTS (every import in the NDK's API 23
#     stubs of the NEEDED libraries), EXPORTS (the ABIs export the same
#     names), BINDINGS.
# GHOSTLANE_CORE_FAST=1 turns LTO off, for a lab build only.
# Needs rustup, git, cmake, clang (boring-sys's bindgen) and the NDK at
# $ANDROID_HOME/ndk/$ANDROID_NDK_VERSION (androidApp/build.gradle.kts'
# ndkVersion; release.yml's ANDROID_NDK_VERSION).
set -euo pipefail

OUT="${1:?usage: build-ghostlane-core-android.sh <output-dir>}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ghostlane-core-pin.sh
. "$HERE/ghostlane-core-pin.sh"
# A dispatch with pin=<sha> builds another commit of the core: the resolver
# checks it, and an empty value is the pin.
GHOSTLANE_CORE_COMMIT="$(bash "$HERE/ghostlane-core-resolve.sh" "${GHOSTLANE_CORE_PIN_OVERRIDE:-}" |
  sed -n 's/^GHOSTLANE_CORE_COMMIT=//p')"
export PATH="$HOME/.cargo/bin:$PATH"
: "${ANDROID_NDK_VERSION:?the NDK the app pins}"
: "${ANDROID_HOME:?the Android SDK}"
api=23
ndk="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
sysroot="$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
[ -x "$bin/clang" ] || { echo "no NDK $ANDROID_NDK_VERSION at $ndk" >&2; exit 1; }
export ANDROID_NDK_HOME="$ndk" ANDROID_NDK_ROOT="$ndk"
export PATH="$bin:$PATH"
export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5

profile="shipped"
if [ "${GHOSTLANE_CORE_FAST:-0}" = "1" ]; then
    profile="fast"
    export CARGO_PROFILE_RELEASE_LTO=false CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16
fi

src="${GHOSTLANE_CORE_SRC:-$OUT/src}"
if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null || true)" != "$GHOSTLANE_CORE_COMMIT" ]; then
    rm -rf "$src"
    git init -q "$src"
    git -C "$src" fetch -q --depth 1 "https://github.com/${GHOSTLANE_CORE_REPO}.git" "$GHOSTLANE_CORE_COMMIT"
    git -C "$src" checkout -q FETCH_HEAD
fi
test "$(git -C "$src" rev-parse HEAD)" = "$GHOSTLANE_CORE_COMMIT"

rustup toolchain install "$GHOSTLANE_CORE_TOOLCHAIN" --profile minimal --component rustfmt \
    --target aarch64-linux-android --target armv7-linux-androideabi --target x86_64-linux-android >/dev/null
rustup component add --toolchain "$GHOSTLANE_CORE_TOOLCHAIN" rustfmt >/dev/null
# bindgen formats boring-sys's bindings with rustfmt; without one it writes the whole module on one
# line with a space before every colon. boring-sys (5.5.0 to 5.6.0, build/main.rs
# ensure_err_lib_enum_is_named) names ErrLib by finding "ERR_LIB_SSL:" in that text and, when it
# does not, guesses `_bindgen_ty_1`, which on armv7 and x86_64 is Bionic's anonymous REG_* enum
# (sys/ucontext.h) and leaves `pub use _bindgen_ty_1 as ErrLib` unresolved (runs 4-6). arm64's
# ucontext.h has no such enum, so it built by the luck of the numbering. The toolchain's own
# rustfmt, named directly (the rustup proxy fails under `cargo +<toolchain>`):
RUSTFMT="$(rustup which --toolchain "$GHOSTLANE_CORE_TOOLCHAIN" rustfmt)"; export RUSTFMT
cargo_="cargo +$GHOSTLANE_CORE_TOOLCHAIN"

page="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
jni="$OUT/jniLibs"; chk="$OUT/checks"
rm -rf "$jni" "$chk" "$OUT/kotlin"; mkdir -p "$jni" "$chk"
report="$OUT/report.txt"; : > "$report"
say() { echo "$*" | tee -a "$report"; }
say "PIN $GHOSTLANE_CORE_COMMIT ($profile, NDK $ANDROID_NDK_VERSION, API $api, features: boring; armv7 and x86_64 fall back to none if their boring build fails, see BORING lines)"
# boring-sys's bindings as rustfmt wrote them, and the enum ErrLib names (see RUSTFMT above).
boring_bindings() {
  local b errlib
  b=$(ls -t "$src/target/$1/release/build"/boring-sys-*/out/bindings.rs 2>/dev/null | head -1)
  [ -n "$b" ] || { echo "no boring-sys bindings for $1"; return 1; }
  errlib=$(grep -E '^pub use +_bindgen_ty_[0-9]+ +as ErrLib;$' "$b" | tr -s ' ' || true)
  [ -n "$errlib" ] && [ "$(wc -l < "$b")" -gt 1000 ] || { echo "bindings not formatted by rustfmt ($(wc -l < "$b") lines)"; return 1; }
  echo "bindings $(wc -l < "$b" | tr -d ' ') lines, $errlib"
}

# <Rust target>:<ABI>:<NDK clang prefix>:<NDK sysroot triple>
for quad in aarch64-linux-android:arm64-v8a:aarch64-linux-android:aarch64-linux-android \
  armv7-linux-androideabi:armeabi-v7a:armv7a-linux-androideabi:arm-linux-androideabi \
  x86_64-linux-android:x86_64:x86_64-linux-android:x86_64-linux-android; do
  IFS=: read -r target abi clang triple <<< "$quad"
  up=$(echo "$target" | tr 'a-z-' 'A-Z_'); low=$(echo "$target" | tr '-' '_')
  export "CARGO_TARGET_${up}_LINKER=$bin/${clang}${api}-clang"
  # cc-rs (aws-lc-sys) looks for arm-linux-androideabi-clang for armv7, a
  # name the NDK does not have (it ships armv7a-linux-androideabi<API>-clang):
  # a shim on PATH that runs the right one. Not CC_<target>: that is what
  # boring-sys would hand to CMake.
  if [ "$target" = armv7-linux-androideabi ]; then
    shim="$OUT/shims"; mkdir -p "$shim"
    for pair in clang:clang clang++:clang++; do
      printf '#!/bin/sh\nexec "%s/%s%s-%s" "$@"\n' "$bin" "$clang" "$api" "${pair%%:*}" > "$shim/arm-linux-androideabi-${pair%%:*}"
      chmod +x "$shim/arm-linux-androideabi-${pair%%:*}"
    done
    export PATH="$shim:$PATH"
  fi
  # BoringSSL is C++: its C++ runtime is linked statically (libc++_static.a
  # and libc++abi.a, from the NDK) so the core stays ONE .so with no
  # libc++_shared.so beside it. boring-sys's own default is -lc++, which
  # lld resolves to the shared one.
  export "BORING_BSSL_RUST_CPPLIB_${low}=c++_static"
  export "CARGO_TARGET_${up}_RUSTFLAGS=$page -C link-arg=-lc++abi"
  # No CC_<target>/CXX_<target> here: boring-sys passes them to CMake as
  # CMAKE_C_COMPILER next to the NDK's toolchain file, which sets the compilers
  # itself, and CMake reset its cache over the conflict (run 44668307401: the
  # second configure's try_run in BoringSSL's benchmark failed). The cc crate
  # finds the NDK's clang on PATH by target triple instead.
  export "AR_${low}=$bin/llvm-ar"
  start=$(date +%s)
  features="--features boring"
  if [ "$target" = armv7-linux-androideabi ] || [ "$target" = x86_64-linux-android ]; then
    # boring-sys's ErrLib on armv7 and x86_64: fixed by RUSTFMT above. Until a run proves it on
    # both, a failure still falls back to no boring so the other ABIs' results stay usable.
    if ! (cd "$src" && $cargo_ build --locked --release -p ghostlane-core --features boring --target "$target"); then
      # One attempt with boring; if it still fails, this ABI ships without it
      # (the report says so) so the other ABIs' results stay usable.
      say "BORING $abi: the build with boring failed, building without it (VK Calls' DTLS is not available on this ABI)"
      features=""
    fi
  fi
  # shellcheck disable=SC2086
  { [ "$target" = armv7-linux-androideabi ] || [ "$target" = x86_64-linux-android ]; } && [ -n "$features" ] && [ -f "$src/target/$target/release/libghostlane_core.so" ] \
    || (cd "$src" && $cargo_ build --locked --release -p ghostlane-core $features --target "$target")
  so="$src/target/$target/release/libghostlane_core.so"
  test -f "$so" || { echo "cargo left no $so" >&2; exit 1; }
  mkdir -p "$jni/$abi"; cp "$so" "$jni/$abi/libghostlane_core.so"
  lib="$jni/$abi/libghostlane_core.so"
  say "BUILD $abi ($target): $(( $(date +%s) - start )) s"

  "$bin/llvm-strip" --strip-unneeded "$lib"
  say "SIZE $abi: $(wc -c < "$lib" | tr -d ' ') bytes stripped, $(gzip -9 -c "$lib" | wc -c | tr -d ' ') gzip -9"

  "$bin/llvm-readelf" -lW "$lib" > "$chk/readelf-$abi.txt"
  aligns=$(awk '$1 == "LOAD" { print $NF }' "$chk/readelf-$abi.txt" | tr '\n' ' ')
  [ -n "$aligns" ] || { echo "no LOAD segment in $abi" >&2; exit 1; }
  for a in $aligns; do
    [ $(( a % 16384 )) -eq 0 ] || { echo "ALIGN $abi: a LOAD segment aligned $a, under 16 KiB" >&2; exit 1; }
  done
  say "ALIGN $abi: every LOAD segment 16 KiB-aligned ($aligns)"

  # BoringSSL linked: the build with the feature succeeded and boring-sys's bindings are rustfmt's
  # with ErrLib named. Its strings alone prove nothing: AWS-LC (a BoringSSL fork, aws-lc-sys)
  # carries them too, and run 5 printed "present" for an armv7 build without boring.
  if [ -n "$features" ]; then
    if bnd=$(boring_bindings "$target"); then
      say "BORING $abi: linked, $bnd"
    else
      echo "BORING $abi: built with boring, but $bnd" >&2; exit 1
    fi
  else
    say "BORING $abi: NOT linked (built without the feature)"
  fi

  "$bin/llvm-nm" -D --defined-only "$lib" | awk '{print $NF}' | sed 's/@.*//' | sort -u > "$chk/exports-$abi.txt"
  "$bin/llvm-nm" -D --undefined-only "$lib" | awk '$1 == "U" {print $2}' | sed 's/@.*//' | sort -u > "$chk/imports-$abi.txt"
  if "$bin/llvm-readelf" -dW "$lib" | grep -q 'NEEDED.*c++_shared'; then
    echo "NEEDED $abi: libc++_shared.so is still a dependency (the C++ runtime must be static)" >&2; exit 1
  fi
  say "NEEDED $abi: $("$bin/llvm-readelf" -dW "$lib" | awk '/NEEDED/ { gsub(/[][]/, "", $NF); printf "%s ", $NF }')(no libc++_shared)"
  : > "$chk/stubs-$abi.txt"
  for needed in $("$bin/llvm-readelf" -dW "$lib" | awk '/NEEDED/ { gsub(/[][]/, "", $NF); print $NF }'); do
    stub="$sysroot/$triple/$api/$needed"
    [ -f "$stub" ] || { echo "IMPORTS $abi: no API $api stub for $needed" >&2; exit 1; }
    "$bin/llvm-nm" -D --defined-only "$stub" | awk '{print $NF}' | sed 's/@.*//' >> "$chk/stubs-$abi.txt"
  done
  sort -u -o "$chk/stubs-$abi.txt" "$chk/stubs-$abi.txt"
  missing=$(comm -23 "$chk/imports-$abi.txt" "$chk/stubs-$abi.txt")
  [ -z "$missing" ] || { echo "IMPORTS $abi: not in the API $api stubs: $(echo "$missing" | tr '\n' ' ')" >&2; exit 1; }
  say "IMPORTS $abi: $(wc -l < "$chk/imports-$abi.txt" | tr -d ' ') symbols, all in the API $api stubs"
done

for abi in arm64-v8a armeabi-v7a; do
  cmp -s "$chk/exports-x86_64.txt" "$chk/exports-$abi.txt" \
    || { echo "EXPORTS $abi differs from x86_64:" >&2; diff "$chk/exports-x86_64.txt" "$chk/exports-$abi.txt" | head -20 >&2; exit 1; }
  say "EXPORTS $abi: the same $(wc -l < "$chk/exports-$abi.txt" | tr -d ' ') names as x86_64"
done

# Kotlin bindings: uniffi-bindgen runs on the host.
(cd "$src" && $cargo_ build --locked -p olc-ffi --features bindgen --bin uniffi-bindgen)
gen="$OUT/kotlin"; mkdir -p "$gen"
(cd "$src" && target/debug/uniffi-bindgen generate --library "target/x86_64-linux-android/release/libghostlane_core.a" \
    --language kotlin --out-dir "$gen")
kt=$(find "$gen" -name '*.kt' | head -1)
[ -n "$kt" ] || { echo "no Kotlin bindings in $gen" >&2; exit 1; }
say "BINDINGS $(find "$gen" -name '*.kt' | sed "s#$gen/##" | tr '\n' ' ')($(wc -l < "$kt" | tr -d ' ') lines)"
echo "$GHOSTLANE_CORE_COMMIT $profile" > "$OUT/ghostlane-core-android.commit"
