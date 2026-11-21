#!/usr/bin/env bash
# The self-test of scripts/check-no-go.sh (K1).
#
#   test-check-no-go.sh
#
# Part A needs python3 only. It plants one signal at a time in a stand-in binary,
# each name of a Go core, and clean look-alikes (a text with the symbol names, a
# Java class), and expects the check to find the first and pass the last.
# Part B needs a Go toolchain (a test fixture, never a shipped file): it builds
# a hello with -ldflags "-s -w -buildid=" (no build id, no symbol table) and, with
# cgo and a C compiler, a c-archive and a stripped executable that links it, and
# plants each at every nesting the check opens: bare, zip, a zip in a zip (an
# APK in an AAB), tar.gz, tar.zst, a JAR in a deb, 7z, cpio, a container image
# tar, an AppImage, a static archive, and a DMG on macOS. Every one must be found.
# Part C is the allow list: listed and dirty passes, unlisted and dirty fails,
# listed and clean fails as stale, an artefact that cannot be opened is not a pass.
#
# REQUIRE_GO=1 makes a missing Go toolchain a failure instead of a skip, and
# REQUIRE_TOOLS=1 does the same for zstd, 7z, mksquashfs, unsquashfs and cpio.
# bash 3.2 and BSD tools are enough.
set -uo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
check="$here/check-no-go.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/nogo-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
fails=0
n=0
ok() { n=$((n + 1)); echo "ok   $*"; }
bad() { n=$((n + 1)); fails=$((fails + 1)); echo "FAIL $*"; }
need_tool() { # name; REQUIRE_TOOLS=1 turns a missing tool into a failure
  if command -v "$1" > /dev/null 2>&1; then return 0; fi
  if [ "${REQUIRE_TOOLS:-}" = 1 ]; then bad "tool $1 is missing"; else echo "skip $1 is not installed"; fi
  return 1
}

# what the check prints for each planted signal (a function: bash 3.2 cannot
# parse a case with a bare ) inside $( ))
sigword() {
  case "$1" in
    pcln*) echo pclntab ;;
    buildinfo) echo buildinfo ;;
    buildid) echo build-id ;;
    buildnote) echo build-id-note ;;
    section) echo section ;;
    *) echo symbol ;;
  esac
}

# found FILE LABEL [PATTERN]: the check must exit 1 and say GO (and PATTERN)
found() {
  local out rc
  out=$("$check" --no-allow "$1" 2>&1); rc=$?
  if [ $rc -eq 1 ] && printf '%s' "$out" | grep -q 'GO  ' && { [ -z "${3:-}" ] || printf '%s' "$out" | grep -q -- "$3"; }; then
    ok "found: $2"
  else
    bad "not found (exit $rc): $2"; printf '%s\n' "$out" | head -12
  fi
}
clean() {
  local out rc
  out=$("$check" --no-allow "$1" 2>&1); rc=$?
  if [ $rc -eq 0 ] && printf '%s' "$out" | grep -q 'OK: clean'; then ok "clean: $2"; else bad "should be clean (exit $rc): $2"; printf '%s\n' "$out" | head -12; fi
}

# ---------------------------------------------------------------- fixtures
cat > "$tmp/mk.py" <<'PY'
import io, os, shutil, subprocess, sys, tarfile, zipfile, gzip, lzma

def elf(extra=b""):
    return b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 56 + b"hello from rust\x00" * 4 + extra + b"\x00" * 64

SIG = {
    "pcln": b"\x00" * 32 + b"\xf1\xff\xff\xff\x00\x00\x01\x08" + b"\x00" * 64,
    "pcln118": b"\x00" * 32 + b"\xf0\xff\xff\xff\x00\x00\x01\x08" + b"\x00" * 64,
    "pcln4": b"\x00" * 32 + b"\xfb\xff\xff\xff\x00\x00\x04\x04" + b"\x00" * 64,
    "buildinfo": b"\x00" * 16 + b"\xff Go buildinf:" + b"\x08\x02" + b"\x00" * 32,
    "buildid": b"\x00" * 16 + b'\xffGo build ID: "abc/def/ghi/jkl"\n \xff' + b"\x00" * 16,
    "buildnote": b"\x00" * 16 + b"\x04\x00\x00\x00\x53\x00\x00\x00\x04\x00\x00\x00Go\x00\x00" + b"x" * 83,
    "section": b"\x00.shstrtab\x00.go.buildinfo\x00.text\x00",
    "goexit": b"\x00runtime.goexit.abi0\x00",
    "gopanic": b"\x00runtime.gopanic\x00",
    "itab": b"\x00go.itab.*os.File,io.Writer\x00",
    "cgo": b"\x00_cgo_panic\x00",
}

def write(p, data):
    os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)

def tar_bytes(files, mode="w"):
    b = io.BytesIO()
    with tarfile.open(fileobj=b, mode=mode) as t:
        for name, data in files:
            ti = tarfile.TarInfo(name); ti.size = len(data); ti.mode = 0o755
            t.addfile(ti, io.BytesIO(data))
    return b.getvalue()

def zip_bytes(files):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files:
            z.writestr(name, data)
    return b.getvalue()

def ar_bytes(members):
    out = b"!<arch>\n"
    for name, data in members:
        hdr = ("%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name + "/", 0, 0, 0, "100644", len(data))).encode()
        out += hdr + data + (b"\n" if len(data) & 1 else b"")
    return out

def build(outdir, payload, tag):
    P = payload
    d = os.path.join(outdir, tag)
    write(d + "/bare/hello", P)
    write(d + "/zip/app.apk", zip_bytes([("classes.dex", b"dex\n035\x00" + b"\x00" * 40), ("lib/arm64-v8a/libhello.so", P)]))
    apk = zip_bytes([("AndroidManifest.xml", b"<m/>"), ("lib/x86_64/libhello.so", P)])
    write(d + "/zipzip/app.aab", zip_bytes([("BundleConfig.pb", b"\x0a\x00"), ("base/apks/base.apk", apk)]))
    write(d + "/tgz/pkg.tar.gz", gzip.compress(tar_bytes([("usr/lib/ghost/engine", P)])))
    write(d + "/txz/pkg.tar.xz", lzma.compress(tar_bytes([("usr/lib/ghost/engine", P)])))
    jar = zip_bytes([("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"), ("native/linux/libhello.so", P)])
    data_tar = lzma.compress(tar_bytes([("opt/app/lib/app/app.jar", jar)]))
    ctrl = gzip.compress(tar_bytes([("control", b"Package: x\n")]))
    write(d + "/deb/x_1.0_amd64.deb", ar_bytes([("debian-binary", b"2.0\n"), ("control.tar.gz", ctrl), ("data.tar.xz", data_tar)]))
    layer = tar_bytes([("usr/bin/tool", P)])
    write(d + "/image/image.tar", tar_bytes([("manifest.json", b"[]"), ("abc/layer.tar", layer)]))
    write(d + "/staticlib/libhello.a", ar_bytes([("hello.o", P)]))
    # loose files for the tools that make their own container
    write(d + "/src/opt/app/engine", P)
    write(d + "/zst/pkg.tar", tar_bytes([("usr/lib/ghost/engine", P)]))
    # an AppImage: an ELF with the AI magic, then a squashfs
    write(d + "/appimage/stub", b"\x7fELF\x02\x01\x01\x00AI\x02" + b"\x00" * 4085)

if __name__ == "__main__":
    out, kind = sys.argv[1], sys.argv[2]
    if kind == "single":
        tag, key = sys.argv[3], sys.argv[4]
        build(out, elf(SIG[key]), tag)
    else:
        build(out, open(sys.argv[3], "rb").read(), kind)
PY
mk() { python3 -I "$tmp/mk.py" "$@"; }

# the tools that make a container themselves, for a tree built by mk
extra_containers() { # DIR
  local d="$1"
  if command -v zstd > /dev/null 2>&1; then zstd -q -f -o "$d/zst/pkg.tar.zst" "$d/zst/pkg.tar"; fi
  if command -v 7z > /dev/null 2>&1; then (cd "$d/src" && 7z a -y -bd "$d/x.7z" opt > /dev/null); fi
  if command -v cpio > /dev/null 2>&1; then (cd "$d/src" && find opt -type f | cpio -o -H newc --quiet > "$d/x.cpio" 2>/dev/null); fi
  if command -v mksquashfs > /dev/null 2>&1; then
    mksquashfs "$d/src" "$d/appimage/fs.sqsh" -noappend -quiet > /dev/null 2>&1 && cat "$d/appimage/stub" "$d/appimage/fs.sqsh" > "$d/appimage/App.AppImage"
  fi
  if [ "$(uname -s)" = Darwin ] && command -v hdiutil > /dev/null 2>&1; then
    hdiutil create -quiet -srcfolder "$d/src" -volname T -format UDZO -ov "$d/app.dmg" > /dev/null 2>&1 || true
  fi
}

plant_all() { # DIR LABEL: every container the fixture tree holds must be found
  local d="$1" label="$2" f
  for f in bare/hello zip/app.apk zipzip/app.aab tgz/pkg.tar.gz txz/pkg.tar.xz deb/x_1.0_amd64.deb image/image.tar staticlib/libhello.a \
           zst/pkg.tar.zst x.7z x.cpio appimage/App.AppImage app.dmg; do
    if [ -f "$d/$f" ]; then found "$d/$f" "$label in $f"; fi
  done
}

# ---------------------------------------------------------------- part A
echo "== A. one signal at a time, names, and what is not Go"
for key in pcln pcln118 pcln4 buildinfo buildid buildnote section goexit gopanic itab cgo; do
  mk "$tmp/a" single "s-$key" "$key"
  found "$tmp/a/s-$key/bare/hello" "signal $key, bare" "$(sigword "$key")"
  found "$tmp/a/s-$key/zipzip/app.aab" "signal $key, in an APK in an AAB"
done
mk "$tmp/a" single s-nest pcln
extra_containers "$tmp/a/s-nest"
plant_all "$tmp/a/s-nest" "pclntab only"

# names: an empty file, a zip entry, a directory
for nm in libgojni.so libsingboxcore.so libxraycore.so sing-box xray olcrtc OlcRtcMobile libolcrtc.so libolcrtc-arm64.so xray.exe; do
  mkdir -p "$tmp/n"; : > "$tmp/n/$nm"
  python3 -I - "$tmp/n/z-$nm.zip" "$nm" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z: z.writestr("lib/arm64-v8a/" + sys.argv[2], b"x")
PY
  found "$tmp/n/$nm" "name $nm" "name:"
  found "$tmp/n/z-$nm.zip" "name $nm in a zip" "name:"
done
mkdir -p "$tmp/n/d/OlcRtcMobile.xcframework"; : > "$tmp/n/d/OlcRtcMobile.xcframework/Info.plist"
python3 -I - "$tmp/n/d.zip" "$tmp/n/d" <<'PY'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    for dp, _, fns in os.walk(sys.argv[2]):
        for f in fns:
            p = os.path.join(dp, f); z.write(p, os.path.relpath(p, sys.argv[2]))
PY
found "$tmp/n/d.zip" "a directory named OlcRtcMobile.xcframework" "name:"

# clean look-alikes
mkdir -p "$tmp/c"
python3 -I - "$tmp/c" <<'PY'
import sys, os
sys.path.insert(0, os.environ.get("MKDIR", ""))
d = sys.argv[1]
def w(n, b): open(os.path.join(d, n), "wb").write(b)
elf = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 56 + b"hello from rust\x00" * 8 + b"\x00" * 64
w("rust.elf", elf)
w("notes.txt", b"the parser looks for runtime.goexit and runtime.gopanic and _cgo_ in panics\n")
w("Parser.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x34" + b"\x00" * 20 + b"runtime.gopanic\x00" + b"\x00" * 20)
w("random.bin", bytes(range(256)) * 64)
w("ff.bin", b"\xff" * 100 + b"\x00\x00" + b"\xff" * 100)
PY
for f in rust.elf notes.txt Parser.class random.bin ff.bin; do clean "$tmp/c/$f" "$f"; done
mk "$tmp/c" cleanset "$tmp/c/rust.elf"
for f in bare/hello zip/app.apk zipzip/app.aab tgz/pkg.tar.gz txz/pkg.tar.xz deb/x_1.0_amd64.deb image/image.tar staticlib/libhello.a; do clean "$tmp/c/cleanset/$f" "a clean binary in $f"; done

# a fake DMG: not opened off macOS, and not a pass
python3 -I - "$tmp/c/fake.dmg" <<'PY'
import sys
open(sys.argv[1], "wb").write(b"\x00" * 3000 + b"koly" + b"\x00" * 508)
PY
if [ "$(uname -s)" != Darwin ]; then
  out=$("$check" --no-allow "$tmp/c/fake.dmg" 2>&1); rc=$?
  if [ $rc -eq 3 ] && printf '%s' "$out" | grep -q 'NOT OPENED'; then ok "a DMG off macOS is NOT OPENED (exit 3), not a pass"; else bad "DMG off macOS (exit $rc)"; echo "$out"; fi
  out=$("$check" --no-allow --dmg-not-opened-ok "$tmp/c/fake.dmg" 2>&1); rc=$?
  if [ $rc -eq 0 ] && printf '%s' "$out" | grep -q 'NOT OPENED on this runner'; then ok "--dmg-not-opened-ok says so plainly"; else bad "--dmg-not-opened-ok (exit $rc)"; echo "$out"; fi
fi
# a broken container is not a pass
printf 'PK\003\004 broken' > "$tmp/c/broken.apk"
out=$("$check" --no-allow "$tmp/c/broken.apk" 2>&1); rc=$?
if [ $rc -eq 3 ]; then ok "a broken zip is NOT OPENED (exit 3)"; else bad "broken zip (exit $rc)"; echo "$out"; fi

# ---------------------------------------------------------------- part C
echo "== C. the allow list"
mk "$tmp/l" single dirty pcln
mk "$tmp/l" cleanset "$tmp/c/rust.elf"
mkdir -p "$tmp/allow" "$tmp/art"
cp "$tmp/l/dirty/zip/app.apk" "$tmp/art/Ghostlane-1.0.472-android-release.apk"
cp "$tmp/l/cleanset/zip/app.apk" "$tmp/art/Ghostlane-1.0.473-android-play.apk"
echo "# a task flips it" > "$tmp/allow/README.md"
out=$("$check" --allow-dir "$tmp/allow" "$tmp/art/Ghostlane-1.0.472-android-release.apk" 2>&1); rc=$?
[ $rc -eq 1 ] && ok "dirty and unlisted fails" || { bad "dirty and unlisted (exit $rc)"; echo "$out"; }
: > "$tmp/allow/Ghostlane-VERSION-android-release.apk.allow"
out=$("$check" --allow-dir "$tmp/allow" "$tmp/art/Ghostlane-1.0.472-android-release.apk" 2>&1); rc=$?
[ $rc -eq 0 ] && printf '%s' "$out" | grep -q ALLOWED && ok "dirty and listed passes, and says Go is still in it" || { bad "dirty and listed (exit $rc)"; echo "$out"; }
out=$("$check" --allow-dir "$tmp/allow" "$tmp/art/Ghostlane-1.0.999-android-release.apk" 2>&1); rc=$?
[ $rc -eq 2 ] && ok "a missing file is a usage error" || bad "missing file (exit $rc)"
: > "$tmp/allow/Ghostlane-VERSION-android-play.apk.allow"
out=$("$check" --allow-dir "$tmp/allow" "$tmp/art/Ghostlane-1.0.473-android-play.apk" 2>&1); rc=$?
[ $rc -eq 1 ] && printf '%s' "$out" | grep -q STALE && ok "clean and still listed fails as STALE" || { bad "stale entry (exit $rc)"; echo "$out"; }
rm "$tmp/allow/Ghostlane-VERSION-android-play.apk.allow"
out=$("$check" --allow-dir "$tmp/allow" "$tmp/art/Ghostlane-1.0.473-android-play.apk" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "clean and unlisted passes" || { bad "clean and unlisted (exit $rc)"; echo "$out"; }
out=$("$check" --allow-dir "$tmp/allow" --summary "$tmp/summary.md" "$tmp/art/Ghostlane-1.0.472-android-release.apk" 2>&1) || true
grep -q 'lib/arm64-v8a/libhello.so' "$tmp/summary.md" && ok "the summary names the file inside" || bad "summary"

# every file of the real list is an entry (a stray name would never be read)
stray=0
for f in "$here"/no-go-allow.d/*; do
  case "$(basename "$f")" in README.md|*.allow) ;; *) stray=1; echo "not an entry: $f" ;; esac
done
[ $stray -eq 0 ] && ok "every file of no-go-allow.d is an entry or the README" || bad "stray file in no-go-allow.d"

# ---------------------------------------------------------------- part B
echo "== B. a stripped Go hello, planted at every nesting"
if ! command -v go > /dev/null 2>&1; then
  if [ "${REQUIRE_GO:-}" = 1 ]; then bad "no Go toolchain (REQUIRE_GO=1)"; else echo "skip no Go toolchain: part B not run"; fi
else
  export GOCACHE="$tmp/gocache" GOTOOLCHAIN=local GOFLAGS=-mod=mod GO111MODULE=on CGO_ENABLED=0
  mkdir -p "$tmp/go/hello"
  printf 'module hello\n\ngo 1.21\n' > "$tmp/go/hello/go.mod"
  cat > "$tmp/go/hello/main.go" <<'GO'
package main

import "fmt"

func main() { fmt.Println("hello") }
GO
  (cd "$tmp/go/hello" && go build -trimpath -ldflags "-s -w -buildid=" -o "$tmp/go/hello-stripped" .)
  if [ -f "$tmp/go/hello-stripped" ]; then
    out=$("$check" --no-allow "$tmp/go/hello-stripped" 2>&1) || true
    echo "$out"
    printf '%s' "$out" | grep -q 'build-id' && bad "the fixture still has a build id: the test would prove nothing" || ok "the fixture has no build id"
    for sig in pclntab-header buildinfo-magic 'symbol:runtime.goexit'; do
      printf '%s' "$out" | grep -q "$sig" && ok "stripped hello: signal $sig" || bad "stripped hello: signal $sig missing"
    done
    mk "$tmp/g" real "$tmp/go/hello-stripped"
    extra_containers "$tmp/g/real"
    plant_all "$tmp/g/real" "stripped Go hello"
    # the unstripped one too (build id and symbols)
    (cd "$tmp/go/hello" && go build -o "$tmp/go/hello-full" .)
    found "$tmp/go/hello-full" "an unstripped Go hello" "build-id"
  else
    bad "go build made nothing"
  fi
  if command -v cc > /dev/null 2>&1; then
    mkdir -p "$tmp/go/lib"
    printf 'module lib\n\ngo 1.21\n' > "$tmp/go/lib/go.mod"
    cat > "$tmp/go/lib/lib.go" <<'GO'
package main

import "C"

//export Hello
func Hello() int { return 42 }

func main() {}
GO
    if (cd "$tmp/go/lib" && CGO_ENABLED=1 go build -trimpath -buildmode=c-archive -ldflags "-s -w -buildid=" -o "$tmp/go/libgo.a" .) 2> "$tmp/go/cgo.err"; then
      printf '#include "libgo.h"\nint main(void){return Hello()==42?0:1;}\n' > "$tmp/go/main.c"
      if cc -o "$tmp/go/linked" "$tmp/go/main.c" "$tmp/go/libgo.a" -I"$tmp/go" -lpthread 2>> "$tmp/go/cgo.err"; then
        strip "$tmp/go/linked" 2>/dev/null || true
        found "$tmp/go/libgo.a" "a Go c-archive"
        found "$tmp/go/linked" "a stripped C program with a Go archive linked in"
        mk "$tmp/g" linked "$tmp/go/linked"
        found "$tmp/g/linked/zipzip/app.aab" "a linked-in Go archive inside an APK in an AAB"
        found "$tmp/g/linked/deb/x_1.0_amd64.deb" "a linked-in Go archive in a JAR in a deb"
      else bad "linking the c-archive failed"; cat "$tmp/go/cgo.err"; fi
    else
      if [ "${REQUIRE_GO:-}" = 1 ]; then bad "the c-archive did not build"; cat "$tmp/go/cgo.err"; else echo "skip the c-archive did not build"; fi
    fi
  fi
fi

echo "== $n checks, $fails failed"
[ "$fails" -eq 0 ]
