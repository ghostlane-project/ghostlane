#!/usr/bin/env bash
# test-armv7-qemu.sh <xray-rust checkout> <out dir>
#
# The olcRTC client's crate tests on 32-bit ARM, under qemu-user: the crates
# ghostlane-core carries for armeabi-v7a (olc-wire, olc-smux, olc-muxconn,
# olc-client, olc-ffi) and ghostlane-runtime (relief's tests among them),
# built for armv7-unknown-linux-gnueabihf and run by qemu-arm on this
# runner. That target has armeabi-v7a's word size, alignment and atomics
# (32-bit usize, 64-bit atomics by ldrexd/strexd, NEON), with glibc where the
# phone has bionic: code under cfg(target_os = "android") is not run here
# (build-core-android.sh builds and lints it for armv7-linux-androideabi).
# The dev profile with debug assertions on, so an arithmetic overflow on the
# 32-bit usize panics; opt-level 1, since every instruction is emulated.
# Each crate's log in <out dir>/test-<crate>.log; the QEMU lines are the job
# summary's. Exits non-zero when a crate's tests fail.
set -uo pipefail

src="$(cd "${1:?usage: test-armv7-qemu.sh <xray-rust checkout> <out dir>}" && pwd)"
out=${2:?usage: test-armv7-qemu.sh <xray-rust checkout> <out dir>}
mkdir -p "$out"
out="$(cd "$out" && pwd)"
target=armv7-unknown-linux-gnueabihf
sysroot=/usr/arm-linux-gnueabihf
[ -d "$sysroot/lib" ] || { echo "no $sysroot (libc6-dev-armhf-cross)" >&2; exit 1; }
command -v qemu-arm > /dev/null || { echo "no qemu-arm (qemu-user)" >&2; exit 1; }

export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5
export CARGO_TARGET_ARMV7_UNKNOWN_LINUX_GNUEABIHF_LINKER=arm-linux-gnueabihf-gcc
export CARGO_TARGET_ARMV7_UNKNOWN_LINUX_GNUEABIHF_RUNNER="qemu-arm -L $sysroot"
export CC_armv7_unknown_linux_gnueabihf=arm-linux-gnueabihf-gcc
export CXX_armv7_unknown_linux_gnueabihf=arm-linux-gnueabihf-g++
export AR_armv7_unknown_linux_gnueabihf=arm-linux-gnueabihf-ar
export CARGO_PROFILE_DEV_OPT_LEVEL=1 CARGO_PROFILE_DEV_DEBUG=0 RUST_BACKTRACE=1

crates=(olc-wire olc-smux olc-muxconn olc-client olc-ffi ghostlane-runtime)
args=()
for c in "${crates[@]}"; do args+=(-p "$c"); done

{
  echo "QEMU $(qemu-arm --version | head -1); $(arm-linux-gnueabihf-gcc --version | head -1)"
} | tee "$out/summary.txt"

start=$(date +%s)
# --tests: the library's, the binaries' and the integration tests (doc tests
# and examples are the host's).
if ! (cd "$src" && cargo test --locked --target "$target" "${args[@]}" --tests --no-run) > "$out/build.log" 2>&1; then
  echo "QEMU build: failed" | tee -a "$out/summary.txt"
  grep -E '^(error|warning)' -A8 "$out/build.log" | head -80
  exit 1
fi
echo "QEMU build: ${crates[*]} for $target in $(( $(date +%s) - start )) s; warnings: $(grep -c '^warning' "$out/build.log" || true)" \
  | tee -a "$out/summary.txt"

rc=0
for c in "${crates[@]}"; do
  t0=$(date +%s)
  (cd "$src" && timeout 2400 cargo test --locked --target "$target" -p "$c" --tests --no-fail-fast) > "$out/test-$c.log" 2>&1
  code=$?
  sums=$(grep -E '^test result:' "$out/test-$c.log")
  passed=$(echo "$sums" | awk '{s += $4} END {print s + 0}')
  failed=$(echo "$sums" | awk '{s += $6} END {print s + 0}')
  ignored=$(echo "$sums" | awk '{s += $8} END {print s + 0}')
  verdict=passed
  [ $code -eq 0 ] || { verdict="FAILED (cargo exit $code)"; rc=1; }
  echo "QEMU $c: $verdict, $passed passed, $failed failed, $ignored ignored, $(echo "$sums" | grep -c .) binaries, $(( $(date +%s) - t0 )) s" \
    | tee -a "$out/summary.txt"
  grep -E '^test .* FAILED$|^    [a-z_:]+$' "$out/test-$c.log" | sort -u | head -40 | sed 's/^/  /' | tee -a "$out/summary.txt"
done
exit $rc
