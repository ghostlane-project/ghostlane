#!/usr/bin/env bash
# build-hev-windows.sh <output-dir>
#
# The Windows tun's two programs, for x86_64:
#   <output-dir>/hev.exe       hev-socks5-tunnel (MIT, C, lwIP), upstream's tag
#                              below, built with clang for MSVC
#   <output-dir>/wintun.dll    Wintun from the signed release, sha256 checked
#   <output-dir>/report.txt    one HEV line and one WINTUN line
#
# hev is upstream's own tree at HEV_TAG, not the one under androidApp (that
# tree predates upstream's move to MSVC, and its Cygwin build brings the
# adapter up and carries nothing: docs/spikes/windows-hev.md). Built so, hev.exe
# imports only system DLLs; this script fails if msys-2.0.dll is named in it,
# and the bundle has no Cygwin runtime.
#
# Needs, on PATH: git, make (GNU Make 4.4), clang, an MSVC environment
# (ilammy/msvc-dev-cmd, or a Developer prompt), curl, python3, sha256sum.
# HEV_SRC names a checkout of the tag to use instead of cloning one.
set -euo pipefail

# Upstream hev-socks5-tunnel 2.20.0. The commit is checked: a tag can move.
HEV_TAG="${HEV_TAG:-2.20.0}"
HEV_COMMIT="${HEV_COMMIT:-75dbc1e9ace6c70d3d1b94df7c283efe1de7effc}"
# Wintun 0.14.1, the release zip's sha256 (also in the Windows lab).
WINTUN_VERSION="${WINTUN_VERSION:-0.14.1}"
WINTUN_SHA256="${WINTUN_SHA256:-07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51}"

[ $# -eq 1 ] || { echo "usage: build-hev-windows.sh <output-dir>" >&2; exit 2; }
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"
for tool in git make clang curl python3 sha256sum; do
  command -v "$tool" > /dev/null || { echo "build-hev-windows.sh needs $tool" >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
: > "$OUT/report.txt"

src="${HEV_SRC:-}"
if [ -z "$src" ]; then
  src="$work/hev"
  git clone --quiet --depth 1 --branch "$HEV_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/heiher/hev-socks5-tunnel.git "$src"
fi
head="$(git -C "$src" rev-parse HEAD)"
[ "$head" = "$HEV_COMMIT" ] || { echo "hev tag $HEV_TAG is $head, expected $HEV_COMMIT" >&2; exit 1; }

echo "== hev-socks5-tunnel $HEV_TAG for x86_64 Windows (clang $(clang --version | sed -n 1p))"
make -C "$src" -j"$(nproc 2> /dev/null || echo 2)"
exe="$src/bin/hev-socks5-tunnel.exe"
[ -s "$exe" ] || { echo "make left no $exe" >&2; exit 1; }
cp "$exe" "$OUT/hev.exe"
if grep -qaiF 'msys-2.0.dll' "$OUT/hev.exe"; then
  echo "hev.exe names msys-2.0.dll: it is a Cygwin build, and the Windows bundle takes none" >&2
  exit 1
fi
echo "HEV x86_64-pc-windows-msvc: $(wc -c < "$OUT/hev.exe" | tr -d ' ') bytes, tag $HEV_TAG ($HEV_COMMIT), no msys-2.0.dll" | tee -a "$OUT/report.txt"

zip="$work/wintun.zip"
curl --fail --location --silent --show-error --retry 3 --proto '=https' --tlsv1.2 \
  --output "$zip" "https://www.wintun.net/builds/wintun-$WINTUN_VERSION.zip"
echo "$WINTUN_SHA256  $zip" | sha256sum --check - > /dev/null || { echo "wintun-$WINTUN_VERSION.zip does not match its pinned sha256" >&2; exit 1; }
python3 -I - "$zip" "$OUT/wintun.dll" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    open(sys.argv[2], "wb").write(z.read("wintun/bin/amd64/wintun.dll"))
PY
echo "WINTUN $WINTUN_VERSION amd64: $(wc -c < "$OUT/wintun.dll" | tr -d ' ') bytes, zip sha256 $WINTUN_SHA256" | tee -a "$OUT/report.txt"
