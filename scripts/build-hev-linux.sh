#!/usr/bin/env bash
# build-hev-linux.sh <hev-socks5-tunnel source> <output-dir> <rust target>...
# build-hev-linux.sh --toolchain <dir> <rust target>
#
# The first form builds hev-socks5-tunnel (MIT, C, lwIP), the tun2socks that
# stands in front of the core, as a static program for Linux, for
#   x86_64-unknown-linux-musl  aarch64-unknown-linux-musl
#   armv7-unknown-linux-musleabihf
# from a source tree with its three libraries under third-part/ (the app keeps
# one, with its own fixes, in androidApp/src/main/jni/hev-socks5-tunnel). The
# tree is copied and built in the copy, with the project's own Makefile:
#   <output-dir>/<target>/hev-socks5-tunnel          stripped
#   <output-dir>/symbols/<target>/hev-socks5-tunnel  as it was linked
#   <output-dir>/report.txt                          one HEV line per target
# The program says which tree it is (hev-socks5-tunnel --version): the id of
# the source directory's git tree, or HEV_REV_ID.
#
# The second form makes the C and C++ cross compiler for one musl target on an
# x86_64 Linux host, checks it, and prints its environment, one NAME=value per
# line (CC_<target>, CXX_<target>, AR_<target>, cargo's linker, bindgen's
# sysroot): the first form uses it, and so does the build of the Rust core for
# the same targets. The compiler is the host's clang with lld; the headers,
# start files, libgcc and a static libstdc++ are Alpine's own packages for the
# architecture, unpacked by apk in a container into <dir>/sysroot-<arch>
# (signatures checked against Alpine's keys). <dir>/bin/<target>-clang and
# <target>-clang++ are the wrappers that name the target and the sysroot.
# A target whose CC_<target> is already set is built with that instead.
#
# Needs make, clang, lld (ld.lld), llvm-ar, llvm-strip, llvm-readelf and
# docker. GHOSTLANE_MUSL_TOOLCHAIN_DIR is where the first form keeps the
# compiler (default: ghostlane-musl-toolchain under $TMPDIR or /tmp);
# GHOSTLANE_MUSL_CLANG names another clang (clang-18).
set -euo pipefail

ALPINE="${GHOSTLANE_MUSL_ALPINE:-3.22}"
CLANG="${GHOSTLANE_MUSL_CLANG:-clang}"

usage() {
  echo "usage: build-hev-linux.sh <hev-socks5-tunnel source> <output-dir> <rust target>..." >&2
  echo "       build-hev-linux.sh --toolchain <dir> <rust target>" >&2
  exit 2
}

# machine_of <rust target>: what llvm-readelf -h calls its machine.
machine_of() {
  case "$1" in
    x86_64-*) echo 'X86-64' ;;
    aarch64-*) echo 'AArch64' ;;
    armv7-*) echo 'ARM' ;;
    *) return 1 ;;
  esac
}

# static_for <rust target> <file>: the file is for that machine and asks for no
# interpreter and no shared library.
static_for() {
  local target="$1" file="$2" want
  want="$(machine_of "$target")"
  llvm-readelf -hW "$file" | grep -E '^ *Machine:' | grep -q "$want" ||
    { echo "$file is not for $want: $(llvm-readelf -hW "$file" | grep -E '^ *Machine:')" >&2; return 1; }
  if llvm-readelf -lW "$file" | grep -q 'INTERP'; then echo "$file asks for an interpreter" >&2; return 1; fi
  if llvm-readelf -dW "$file" | grep -q 'NEEDED'; then echo "$file needs a shared library" >&2; return 1; fi
}

# toolchain <dir> <rust target>: see the header. Everything it says goes to
# stderr; stdout is the environment.
toolchain() {
  local dir="$1" target="$2" arch triple cflags root low up tool check gcc_files wrapper
  case "$target" in
    x86_64-unknown-linux-musl) arch=x86_64 triple=x86_64-alpine-linux-musl cflags="" ;;
    aarch64-unknown-linux-musl) arch=aarch64 triple=aarch64-alpine-linux-musl cflags="" ;;
    armv7-unknown-linux-musleabihf)
      # Rust's armv7 musl target: hard float, VFPv3-D16, no NEON taken for granted.
      arch=armv7 triple=armv7-alpine-linux-musleabihf cflags="-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=hard"
      ;;
    *) echo "no musl cross compiler for $target" >&2; return 2 ;;
  esac
  for tool in "$CLANG" "${CLANG/clang/clang++}" ld.lld llvm-ar llvm-readelf docker; do
    command -v "$tool" > /dev/null || { echo "build-hev-linux.sh --toolchain needs $tool" >&2; return 1; }
  done
  mkdir -p "$dir/bin"
  dir="$(cd "$dir" && pwd)"
  root="$dir/sysroot-$arch"
  if [ ! -f "$root/.ready" ]; then
    echo "== Alpine $ALPINE packages for $arch into $root" >&2
    # apk runs in a container of the host's architecture and unpacks the other
    # architecture's packages into a root of their own. No package script runs
    # (--no-scripts): nothing of that architecture is ever executed.
    # shellcheck disable=SC2016
    docker run --rm -v "$dir:/out" "${GHOSTLANE_MUSL_ALPINE_IMAGE:-alpine:$ALPINE}" sh -euc '
      arch="$1" owner="$2" repo="$3" root="/out/sysroot-$1"
      rm -rf "$root"
      mkdir -p "$root/etc/apk/keys"
      cp /etc/apk/keys/* "$root/etc/apk/keys/"
      cp /usr/share/apk/keys/*.pub "$root/etc/apk/keys/" 2> /dev/null || true
      if [ -d "/usr/share/apk/keys/$arch" ]; then cp -L "/usr/share/apk/keys/$arch"/* "$root/etc/apk/keys/"; fi
      apk add --quiet --no-progress --no-cache --no-scripts --initdb --root "$root" --arch "$arch" -X "$repo" \
        musl-dev gcc libstdc++-dev linux-headers
      chown -R "$owner" "$root"
    ' sh "$arch" "$(id -u):$(id -g)" "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE/main" >&2
    test -f "$root/usr/lib/libc.a" || { echo "no libc.a in $root" >&2; return 1; }
    touch "$root/.ready"
  fi
  # The target and the sysroot come last, after whatever the caller passed: cc
  # and CMake name Rust's triple, and it is Alpine's that finds the start
  # files, libgcc and libstdc++ in the sysroot.
  # shellcheck disable=SC2016
  wrapper='#!/bin/sh\nexec %s -Qunused-arguments %s -fuse-ld=lld "$@" --target=%s --sysroot="%s"\n'
  # shellcheck disable=SC2059
  printf "$wrapper" "$CLANG" "$cflags" "$triple" "$root" > "$dir/bin/$target-clang"
  # shellcheck disable=SC2059
  printf "$wrapper" "${CLANG/clang/clang++}" "$cflags" "$triple" "$root" > "$dir/bin/$target-clang++"
  chmod +x "$dir/bin/$target-clang" "$dir/bin/$target-clang++"

  # The compiler is checked before anything long is built with it: a C and a
  # C++17 program, static, for the right machine; run where the host can.
  check="$(mktemp -d)"
  printf '#include <stdio.h>\nint main(void) { puts("c ok"); return 0; }\n' > "$check/a.c"
  printf '#include <optional>\n#include <string>\n#include <cstdio>\nint main() { std::optional<std::string> s("c++ ok"); std::puts(s->c_str()); return s->size() == 6 ? 0 : 1; }\n' > "$check/b.cc"
  "$dir/bin/$target-clang" -O2 -static -o "$check/a" "$check/a.c" >&2
  "$dir/bin/$target-clang++" -O2 -std=c++17 -static -o "$check/b" "$check/b.cc" >&2
  static_for "$target" "$check/a"
  static_for "$target" "$check/b"
  if [ "$arch" = "$(uname -m)" ]; then
    "$check/a" >&2
    "$check/b" >&2
  fi
  rm -rf "$check"
  gcc_files=""
  for check in "$root/usr/lib/gcc/$triple"/*; do gcc_files="$(basename "$check")"; done
  echo "== $target: $("$CLANG" --version | sed -n 1p), $(ld.lld --version | sed -n 1p), libgcc and libstdc++ $gcc_files from Alpine $ALPINE" >&2

  low="$(echo "$target" | tr '-' '_')"
  up="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  echo "CC_${low}=$dir/bin/$target-clang"
  echo "CXX_${low}=$dir/bin/$target-clang++"
  echo "AR_${low}=$(command -v llvm-ar)"
  echo "CARGO_TARGET_${up}_LINKER=$dir/bin/$target-clang"
  echo "BINDGEN_EXTRA_CLANG_ARGS_${low}=--sysroot=$root"
}

if [ "${1:-}" = "--toolchain" ]; then
  [ $# -eq 3 ] || usage
  toolchain "$2" "$3"
  exit 0
fi

[ $# -ge 3 ] || usage
case "$1" in -*) usage ;; esac
SRC="$(cd "$1" && pwd)"
mkdir -p "$2"
OUT="$(cd "$2" && pwd)"
shift 2
test -f "$SRC/src/hev-main.h" || { echo "$SRC is not a hev-socks5-tunnel source tree" >&2; exit 1; }
test -f "$SRC/third-part/lwip/Makefile" || { echo "$SRC has no third-part/lwip: its libraries are missing" >&2; exit 1; }
for tool in make llvm-ar llvm-strip llvm-readelf; do
  command -v "$tool" > /dev/null || { echo "build-hev-linux.sh needs $tool" >&2; exit 1; }
done

rev="${HEV_REV_ID:-}"
if [ -z "$rev" ]; then
  prefix="$(git -C "$SRC" rev-parse --show-prefix 2> /dev/null || true)"
  rev="$(git -C "$SRC" rev-parse --short=12 "HEAD:${prefix%/}" 2> /dev/null || true)"
fi
[ -n "$rev" ] || rev=unknown

tools="${GHOSTLANE_MUSL_TOOLCHAIN_DIR:-${TMPDIR:-/tmp}/ghostlane-musl-toolchain}"
report="$OUT/report.txt"
: > "$report"
for target in "$@"; do
  machine_of "$target" > /dev/null || { echo "build-hev-linux.sh: no build for $target" >&2; exit 2; }
  low="$(echo "$target" | tr '-' '_')"
  cc="$(eval "echo \"\${CC_${low}:-}\"")"
  ar="$(eval "echo \"\${AR_${low}:-}\"")"
  if [ -z "$cc" ]; then
    env_file="$(mktemp)"
    toolchain "$tools" "$target" > "$env_file"
    cc="$(sed -n "s/^CC_${low}=//p" "$env_file")"
    ar="$(sed -n "s/^AR_${low}=//p" "$env_file")"
    rm -f "$env_file"
  fi
  [ -n "$ar" ] || ar="$(command -v llvm-ar)"

  work="$(mktemp -d)"
  cp -R "$SRC/." "$work/"
  rm -rf "$work/.git"
  echo "== hev-socks5-tunnel for $target (tree $rev)"
  make -C "$work" clean > /dev/null
  # STRIP=true: the Makefile strips what it links; here the program is kept
  # as linked and stripped as a copy.
  make -C "$work" -j"$(nproc)" exec CC="$cc" PP="$cc" AR="$ar" STRIP=true ENABLE_STATIC=1 REV_ID="$rev"
  static_for "$target" "$work/bin/hev-socks5-tunnel"
  mkdir -p "$OUT/$target" "$OUT/symbols/$target"
  cp "$work/bin/hev-socks5-tunnel" "$OUT/symbols/$target/hev-socks5-tunnel"
  cp "$work/bin/hev-socks5-tunnel" "$OUT/$target/hev-socks5-tunnel"
  llvm-strip --strip-all "$OUT/$target/hev-socks5-tunnel"
  static_for "$target" "$OUT/$target/hev-socks5-tunnel"
  said=""
  if [ "$(machine_of "$target")" = 'X86-64' ] && [ "$(uname -m)" = x86_64 ]; then
    # It prints its version and leaves with a status that is not 0.
    said="$("$OUT/$target/hev-socks5-tunnel" --version 2>&1 | grep -E '^Version: ' || true)"
    [ -n "$said" ] || { echo "the program built for $target does not say its version" >&2; exit 1; }
    said=", says \"$said\""
  fi
  echo "HEV $target: $(wc -c < "$OUT/$target/hev-socks5-tunnel" | tr -d ' ') bytes stripped, $(wc -c < "$OUT/symbols/$target/hev-socks5-tunnel" | tr -d ' ') as linked, static, tree $rev$said" | tee -a "$report"
  rm -rf "$work"
done
