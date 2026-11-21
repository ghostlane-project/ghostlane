#!/usr/bin/env bash
# K1: no Go in what ships.
#
#   check-no-go.sh [options] FILE...
#
# Reads each FILE (an APK, AAB, IPA, DMG, exe, msi, zip, deb, rpm, apk, Arch
# package, tarball, AppImage, container image tar, JAR, .so, static archive or a
# bare executable), unpacks every container inside it by itself, at any depth,
# and fails when anything inside is Go. A file is Go when it
#   - is named like a Go core: libgojni.so, libsingboxcore.so, libxraycore.so,
#     sing-box, xray, olcrtc, OlcRtcMobile, libolcrtc (a directory too);
#   - holds a Go build id (the text, or the ELF note);
#   - holds the go:buildinfo magic, or a section named for it or for the
#     pclntab or the symbol table of Go;
#   - holds a pclntab header (the magic of Go 1.2, 1.16, 1.18 or 1.20+, two zero
#     bytes, the instruction quantum, the pointer size);
#   - is an object, executable or archive that holds runtime.goexit,
#     runtime.gopanic, go.itab or _cgo_.
# A binary stripped with -ldflags "-s -w -buildid=" has no build id and no
# symbol table, but keeps the buildinfo magic, the pclntab header and the
# function names the pclntab carries (runtime.goexit, runtime.gopanic): those
# are the signals that find it. An archive linked into an app binary is found by
# the same three. The name list is a signal of its own and the only one a Go
# core that was only renamed or packed in an unknown format would leave.
#
# Options:
#   --allow-dir DIR      the allow list (default: no-go-allow.d beside this file)
#   --no-allow           ignore the allow list
#   --summary FILE       append a Markdown report (the job summary)
#   --dmg-not-opened-ok  a DMG that cannot be opened on this host is reported as
#                        NOT OPENED and does not fail the run (macOS opens it)
#   --suggest            print the allow list file name of each artefact
#   --max-depth N        nesting limit (default 10)
#
# The allow list is one file per artefact that may still hold Go today. The
# file is named like the artefact with its version replaced by VERSION and
# ".allow" added (Ghostlane-VERSION-android-release.apk.allow: the suffix keeps
# .gitignore's *.apk, *.exe and the like from swallowing an entry); other files
# are not entries. The list only shrinks: an artefact that was opened completely, is
# clean, and still has a file fails as STALE. Remove the file in the commit
# that flips the artefact.
#
# Exit: 0 every artefact clean or allowed; 1 Go in an artefact that is not on
# the list, or a clean artefact that is; 2 a usage error; 3 something could not
# be opened (the rest was checked, nothing was found there, and it is not a pass).
#
# Needs python3. Opens containers with python3 alone (zip, tar, gzip, xz, bzip2,
# ar and deb); with zstd, 7z (rpm, cpio, cab, msi, 7z, a PE with resources),
# unsquashfs (AppImage, squashfs) and hdiutil (DMG, macOS only) when they exist.
# What it needs and does not find is reported as NOT OPENED, never as clean.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export NOGO_HERE="$here"
exec python3 -I - "$@" <<'PYEOF'
import bz2, gzip, lzma, mmap, os, re, shutil, subprocess, sys, tarfile, tempfile, time, zipfile

HERE = os.environ.get("NOGO_HERE", ".")
GO_NAMES = {"libgojni", "libsingboxcore", "libxraycore", "sing-box", "xray", "olcrtc", "olcrtcmobile"}
GO_PREFIXES = ("libolcrtc", "olcrtcmobile", "libgojni", "libsingboxcore", "libxraycore")
SECTION_NAMES = [b".go.buildinfo", b".note.go.buildid", b".gopclntab", b".gosymtab",
                 b"__go_buildinfo", b"__gopclntab", b"__gosymtab", b"go.buildid"]
SYMBOLS = [b"runtime.goexit", b"runtime.gopanic", b"go.itab.", b"go:itab.", b"_cgo_"]
BUILDINFO_MAGIC = b"\xff Go buildinf:"
BUILDID_TEXT = b'Go build ID: "'
BUILDID_NOTE = re.compile(rb"\x04\x00\x00\x00.{4}\x04\x00\x00\x00Go\x00\x00", re.S)
PCLN_MAGICS = (0xf0, 0xf1, 0xfa, 0xfb)  # last byte of the magic words ff ff ff fX / fb ff ff ff
MAX_TOTAL = 40 * 1024 ** 3


def name_is_go(name):
    n = name.lower()
    stem = n.split(".")[0]
    return stem in GO_NAMES or n.startswith(GO_PREFIXES)


def normalise(base):
    return re.sub(r"(?<![0-9A-Za-z.])\d+\.\d+\.\d+(?:\.\d+)?(?![0-9A-Za-z])", "VERSION", base)


class Scan:
    def __init__(self, max_depth):
        self.hits = []      # (chain, [signals])
        self.unopened = []  # (chain, why)
        self.max_depth = max_depth
        self.total = 0
        self.leaves = 0
        self.opened = []    # containers opened
        self.dmg_unopened = False

    def hit(self, chain, sigs):
        self.hits.append((chain, sigs))

    def no(self, chain, why):
        self.unopened.append((chain, why))


def head(path, n=600):
    try:
        with open(path, "rb") as f:
            return f.read(n)
    except OSError:
        return b""


def which(*names):
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    return None


def kind_of(path):
    h = head(path)
    if h[:4] == b"PK\x03\x04" or h[:4] == b"PK\x05\x06":
        return "zip"
    if h[:2] == b"\x1f\x8b":
        return "gzip"
    if h[:6] == b"\xfd7zXZ\x00":
        return "xz"
    if h[:3] == b"BZh":
        return "bzip2"
    if h[:4] == b"\x28\xb5\x2f\xfd":
        return "zstd"
    if h[:8] == b"!<arch>\n":
        return "ar"
    if h[:4] == b"\xed\xab\xee\xdb":
        return "rpm"
    if h[257:262] == b"ustar":
        return "tar"
    if h[:8] == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1":
        return "ole"
    if h[:4] == b"MSCF":
        return "cab"
    if h[:6] == b"7z\xbc\xaf\x27\x1c":
        return "7z"
    if h[:4] == b"hsqs":
        return "squashfs"
    if h[:6] in (b"070701", b"070702", b"070707"):
        return "cpio"
    if h[:4] == b"\x7fELF":
        return "elf"
    if h[:2] == b"MZ":
        return "pe"
    if h[:4] in (b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf", b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe"):
        return "macho"
    if h[:4] == b"\xca\xfe\xba\xbe" and int.from_bytes(h[4:8], "big") < 45:
        return "macho"
    return "other"


def is_dmg(path):
    try:
        sz = os.path.getsize(path)
        if sz < 512:
            return False
        with open(path, "rb") as f:
            f.seek(sz - 512)
            return f.read(4) == b"koly"
    except OSError:
        return False


def looks_text(path):
    h = head(path, 4096)
    if b"\x00" in h:
        return False
    try:
        h.decode("utf-8")
    except UnicodeDecodeError:
        return not h
    return True


def pclntab_at(mm, size):
    """Offset of the first pclntab header, or -1."""
    pos = 0
    pat = b"\xff\xff\xff\x00\x00"
    while True:
        i = mm.find(pat, pos)
        if i < 0:
            return -1
        pos = i + 1
        if i == 0 or i + 7 > size:
            continue
        if mm[i - 1] in PCLN_MAGICS and mm[i + 5] in (1, 2, 4) and mm[i + 6] in (4, 8):
            return i - 1


def signals_of(path, native):
    """The Go signals in one file's bytes. native: an object, executable or archive."""
    sigs = []
    try:
        size = os.path.getsize(path)
    except OSError:
        return sigs
    if size == 0:
        return sigs
    with open(path, "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            structural = []
            i = mm.find(BUILDINFO_MAGIC)
            if i >= 0:
                structural.append("buildinfo-magic@%#x" % i)
            i = mm.find(BUILDID_TEXT)
            if i >= 0:
                structural.append("build-id@%#x" % i)
            else:
                m = BUILDID_NOTE.search(mm)
                if m:
                    structural.append("build-id-note@%#x" % m.start())
            i = pclntab_at(mm, size)
            if i >= 0:
                structural.append("pclntab-header@%#x" % i)
            for s in SECTION_NAMES:
                i = mm.find(s)
                if i >= 0 and native:
                    structural.append("section:%s" % s.decode())
                    break
            sigs.extend(structural)
            if native or structural:
                for s in SYMBOLS:
                    i = mm.find(s)
                    if i >= 0:
                        sigs.append("symbol:%s@%#x" % (s.decode(), i))
        finally:
            mm.close()
    return sigs


def safe_join(dest, name):
    p = os.path.normpath(os.path.join(dest, name))
    if not (p == dest or p.startswith(dest + os.sep)):
        raise ValueError("path leaves the directory: " + name)
    return p


def run(cmd, **kw):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)


def decompress_to(kind, path, out):
    if kind == "zstd":
        z = which("zstd", "unzstd")
        if not z:
            raise RuntimeError("zstd is not installed")
        r = run([z, "-q", "-d", "-f", "-o", out, path])
        if r.returncode:
            raise RuntimeError("zstd: " + r.stderr.decode(errors="replace")[:200])
        return
    op = {"gzip": gzip.open, "xz": lzma.open, "bzip2": bz2.open}[kind]
    with op(path, "rb") as src, open(out, "wb") as dst:
        shutil.copyfileobj(src, dst, 1 << 20)


def read_ar(path, dest):
    names = []
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise RuntimeError("not an ar archive")
        longnames = b""
        n = 0
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[:16].decode("latin1").strip()
            size = int(hdr[48:58].decode().strip() or 0)
            data_at = f.tell()
            if name == "//":
                longnames = f.read(size)
            else:
                if name.startswith("/") and name[1:].isdigit() and longnames:
                    off = int(name[1:])
                    end = longnames.find(b"\n", off)
                    name = longnames[off:end].decode("latin1").rstrip("/")
                else:
                    name = name.rstrip("/")
                names.append((name, data_at, size))
            f.seek(data_at + size + (size & 1))
        for name, at, size in names:
            n += 1
            if name in ("", "/") or name.startswith("__.SYMDEF"):
                continue
            out = safe_join(dest, "%d-%s" % (n, os.path.basename(name) or "member"))
            f.seek(at)
            with open(out, "wb") as o:
                left = size
                while left:
                    b = f.read(min(left, 1 << 20))
                    if not b:
                        break
                    o.write(b)
                    left -= len(b)
    return [n for n, _, _ in names]


def ar_member_names(path):
    out = []
    try:
        with open(path, "rb") as f:
            f.seek(8)
            while True:
                hdr = f.read(60)
                if len(hdr) < 60:
                    break
                out.append(hdr[:16].decode("latin1").strip().rstrip("/"))
                size = int(hdr[48:58].decode().strip() or 0)
                f.seek(size + (size & 1), 1)
                if len(out) > 8:
                    break
    except (OSError, ValueError):
        pass
    return out


def un_tar(path, dest):
    with tarfile.open(path, "r:*") as t:
        try:
            t.extractall(dest, filter="data")
        except TypeError:
            for m in t.getmembers():
                safe_join(dest, m.name)
            t.extractall(dest)


def un_zip(path, dest):
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            safe_join(dest, info.filename)
        z.extractall(dest)


def un_7z(path, dest):
    z = which("7z", "7zz", "7za")
    if not z:
        raise RuntimeError("7z is not installed")
    r = run([z, "x", "-y", "-snl", "-o" + dest, path])
    if r.returncode not in (0, 1) or not os.listdir(dest):
        raise RuntimeError("7z: " + (r.stdout + r.stderr).decode(errors="replace").strip()[-200:])


def squashfs_offset(path):
    with open(path, "rb") as f:
        data = f.read(8 * 1024 * 1024)
    pos = 0
    while True:
        i = data.find(b"hsqs", pos)
        if i < 0:
            return -1
        pos = i + 1
        sb = data[i:i + 96]
        if len(sb) >= 32 and int.from_bytes(sb[28:30], "little") == 4 and int.from_bytes(sb[30:32], "little") == 0:
            return i


def un_squashfs(path, dest, offset):
    u = which("unsquashfs")
    if not u:
        raise RuntimeError("unsquashfs is not installed")
    shutil.rmtree(dest, ignore_errors=True)
    r = run([u, "-no-progress", "-force", "-o", str(offset), "-d", dest, path])
    if r.returncode:
        raise RuntimeError("unsquashfs: " + (r.stdout + r.stderr).decode(errors="replace").strip()[-200:])


def attach_dmg(path):
    if sys.platform != "darwin" or not which("hdiutil"):
        raise RuntimeError("a DMG is opened with hdiutil on macOS only")
    mnt = tempfile.mkdtemp(prefix="nogo-dmg-")
    for attempt in range(4):
        r = run(["hdiutil", "attach", "-nobrowse", "-readonly", "-noverify", "-noautoopen", "-mountpoint", mnt, path])
        if not r.returncode:
            break
        time.sleep(3 * (attempt + 1))  # a runner's hdiutil answers "Resource temporarily unavailable" now and then
    if r.returncode:
        raise RuntimeError("hdiutil: " + r.stderr.decode(errors="replace").strip()[-200:])
    return mnt


class Walker:
    def __init__(self, scan):
        self.s = scan

    def file(self, path, chain, depth):
        s = self.s
        base = os.path.basename(chain.split("!/")[-1]) or chain
        sigs = []
        if name_is_go(base):
            sigs.append("name:%s" % base)
        if os.path.islink(path):
            if sigs:
                s.hit(chain, sigs)
            return
        try:
            size = os.path.getsize(path)
        except OSError:
            return
        s.total += size
        if s.total > MAX_TOTAL:
            s.no(chain, "more than %d GiB unpacked" % (MAX_TOTAL >> 30))
            return
        kind = kind_of(path)
        container = None
        if kind in ("zip", "gzip", "xz", "bzip2", "zstd", "tar", "rpm", "ole", "cab", "7z", "squashfs", "cpio"):
            container = kind
        elif kind == "ar":
            container = "deb" if "debian-binary" in ar_member_names(path) else None
        elif kind == "pe":
            container = "pe"
        elif kind == "elf":
            container = "appimage" if head(path, 16)[8:10] == b"AI" and squashfs_offset(path) >= 0 else None
        elif kind == "other" and is_dmg(path):
            container = "dmg"
        if container == "dmg":
            self.dmg(path, chain, depth, sigs)
            return
        leaf_scan = container in (None, "appimage", "pe")
        if leaf_scan:
            s.leaves += 1
            native = kind in ("elf", "pe", "macho", "ar")
            sigs.extend(signals_of(path, native))
        if sigs:
            s.hit(chain, sigs)
        if container:
            if depth >= s.max_depth:
                s.no(chain, "nested deeper than %d" % s.max_depth)
                return
            self.unpack(path, chain, depth, container)

    def unpack(self, path, chain, depth, container):
        s = self.s
        tmp = tempfile.mkdtemp(prefix="nogo-")
        try:
            try:
                if container == "zip":
                    un_zip(path, tmp)
                elif container == "tar":
                    un_tar(path, tmp)
                elif container in ("gzip", "xz", "bzip2", "zstd"):
                    out = os.path.join(tmp, "unpacked")
                    decompress_to(container, path, out)
                    # a gzip that is a tar (an Alpine package is several)
                    if kind_of(out) == "tar":
                        t = os.path.join(tmp, "tree")
                        os.mkdir(t)
                        un_tar(out, t)
                        os.unlink(out)
                elif container == "deb":
                    read_ar(path, tmp)
                elif container in ("rpm", "ole", "cab", "7z", "squashfs", "cpio", "pe"):
                    if container == "squashfs" and which("unsquashfs"):
                        un_squashfs(path, os.path.join(tmp, "tree"), 0)
                    else:
                        un_7z(path, tmp)
                elif container == "appimage":
                    un_squashfs(path, os.path.join(tmp, "tree"), squashfs_offset(path))
            except Exception as e:  # noqa: BLE001
                if container == "pe" and "not installed" not in str(e):
                    # a PE with nothing 7z can open is a plain executable, already scanned
                    return
                s.no(chain, "%s: %s" % (container, str(e).replace("\n", " ")[:240]))
                return
            if container == "pe":
                # 7z lists a PE's own sections as files: they are the bytes already read
                for sec in (".text", ".data", ".rdata", ".idata", ".pdata", ".reloc", ".bss", ".tls", ".edata", ".CRT", ".xdata"):
                    try:
                        os.unlink(os.path.join(tmp, sec))
                    except OSError:
                        pass
            s.opened.append((chain, container))
            self.tree(tmp, chain, depth + 1)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def dmg(self, path, chain, depth, sigs):
        s = self.s
        s.leaves += 1
        sigs.extend(signals_of(path, False))
        if sigs:
            s.hit(chain, sigs)
        try:
            mnt = attach_dmg(path)
        except Exception as e:  # noqa: BLE001
            s.no(chain, "NOT OPENED: %s" % str(e)[:240])
            s.dmg_unopened = True
            return
        try:
            s.opened.append((chain, "dmg"))
            self.tree(mnt, chain, depth + 1)
        finally:
            run(["hdiutil", "detach", "-force", mnt])
            shutil.rmtree(mnt, ignore_errors=True)

    def tree(self, root, chain, depth):
        for dp, dns, fns in os.walk(root):
            rel = os.path.relpath(dp, root)
            for d in list(dns):
                if name_is_go(d):
                    c = chain + "!/" + (d if rel == "." else rel + "/" + d)
                    self.s.hit(c, ["name:%s/" % d])
            for fn in fns:
                p = os.path.join(dp, fn)
                c = chain + "!/" + (fn if rel == "." else rel + "/" + fn)
                self.file(p, c, depth)


def allow_entries(d):
    out = set()
    if d and os.path.isdir(d):
        for n in os.listdir(d):
            if n.endswith(".allow") and not n.startswith("."):
                out.add(n[:-len(".allow")])
    return out


def main(argv):
    allow_dir = os.path.join(HERE, "no-go-allow.d")
    use_allow = True
    summary = None
    dmg_ok = False
    suggest = False
    max_depth = 10
    files = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--allow-dir":
            allow_dir = argv[i + 1]; i += 1
        elif a == "--no-allow":
            use_allow = False
        elif a == "--summary":
            summary = argv[i + 1]; i += 1
        elif a == "--dmg-not-opened-ok":
            dmg_ok = True
        elif a == "--suggest":
            suggest = True
        elif a == "--max-depth":
            max_depth = int(argv[i + 1]); i += 1
        elif a.startswith("--"):
            print("check-no-go: unknown option " + a, file=sys.stderr)
            return 2
        else:
            files.append(a)
        i += 1
    if not files:
        print("usage: check-no-go.sh [options] FILE...", file=sys.stderr)
        return 2
    allow = allow_entries(allow_dir) if use_allow else set()
    worst = 0
    md = []
    for f in files:
        if not os.path.isfile(f):
            print("check-no-go: no such file: " + f, file=sys.stderr)
            return 2
        name = os.path.basename(f)
        key = normalise(name)
        sc = Scan(max_depth)
        Walker(sc).file(f, name, 0)
        listed = key in allow
        gos = sc.hits
        unop = sc.unopened
        dmg_skip = bool(dmg_ok and sc.dmg_unopened and not gos)
        if gos:
            status = "ALLOWED (Go still in it, on the list)" if listed else "FAIL: Go"
            code = 0 if listed else 1
        elif unop and not dmg_skip:
            status = "NOT OPENED (nothing found in what was opened; this is not a pass)"
            code = 3
        elif unop and dmg_skip:
            status = "NOT OPENED on this runner (a DMG is opened on macOS only; only its raw bytes and name were read)"
            code = 0
        elif listed:
            status = "FAIL: STALE allow list entry, the artefact is clean: remove no-go-allow.d/" + key + ".allow"
            code = 1
        else:
            status = "OK: clean"
            code = 0
        if code == 1 and worst != 1:
            worst = 1
        elif code == 3 and worst == 0:
            worst = 3
        lines = ["%s: %s" % (name, status),
                 "  opened %d container(s), read %d file(s)%s" % (len(sc.opened), sc.leaves,
                                                                  ", allow list file: " + key + ".allow" if (listed or suggest) else "")]
        for ch, sg in gos[:25]:
            lines.append("  GO  %s\n      %s" % (ch, "; ".join(sg[:6])))
        if len(gos) > 25:
            lines.append("  ... and %d more files" % (len(gos) - 25))
        for ch, why in unop[:10]:
            lines.append("  NOT OPENED  %s\n      %s" % (ch, why))
        print("\n".join(lines))
        sys.stdout.flush()
        md.append((name, status, key, len(sc.opened), sc.leaves, gos, unop))
    if summary:
        with open(summary, "a") as o:
            for name, status, key, nopen, nleaf, gos, unop in md:
                o.write("### %s\n\n%s\n\nOpened %d container(s), read %d file(s).\n\n" % (name, status, nopen, nleaf))
                if gos:
                    o.write("| where | what was found |\n|---|---|\n")
                    for ch, sg in gos[:25]:
                        o.write("| `%s` | %s |\n" % (ch.replace("|", "\\|"), "; ".join(sg[:6]).replace("|", "\\|")))
                    if len(gos) > 25:
                        o.write("\n%d more files.\n" % (len(gos) - 25))
                    o.write("\n")
                for ch, why in unop[:10]:
                    o.write("- not opened: `%s`: %s\n" % (ch, why.replace("|", "\\|")))
                o.write("\n")
    return worst


sys.exit(main(sys.argv[1:]))
PYEOF
