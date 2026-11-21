#!/usr/bin/env python3
"""The parity ledger: every feature of today's builds, by platform, and the test that proves it.

    scripts/parity-ledger.py            check docs/rust-parity/ against the tree
    scripts/parity-ledger.py --self-test   plant each fault in a copy and see it caught

docs/rust-parity/ holds:

  inventory.txt   one line per feature:   id | platforms | source | what
  <platform>.txt  one line per feature that platform's build has today:
                                          id | status | tests | note
  sizes.txt       the artefact sizes beside today's Go build

Platforms are android, ios, macos, linux, windows and cli (inventory.txt names
them by their short letters a, i, m, l, w, c). Status is one of

  proven       the tests named prove the feature on the Rust core
  partial      the tests named prove part of it; the note says what is still open
  not-proven   nothing proves it yet (the honest state of most rows today)
  retired      the feature is gone on purpose; the note says why

A test id is a file in the tree, or `file::name` where `name` has to occur in
that file (a test function, a class, a workflow job). A `source` in the
inventory is written the same way and points at where today's build has the
feature, so a line cannot name a feature the tree does not have.

What fails the check: a feature with no row on a platform it is listed for; a
row for a feature (or a platform) the inventory does not list; a row whose test
id does not exist; a proven or partial row with no test; a retired or partial
row with no note; a not-proven or retired row that names a test (it would be
proven, or partial); and a feature the tree knows (a link scheme, a routing
mode, a carrier, a transport, a setting, a CLI command, flag or variable) that
the inventory leaves out.

Needs python 3 and nothing else.
"""
import os
import re
import sys
import tempfile
import shutil

PLATFORMS = {
    "a": "android",
    "i": "ios",
    "m": "macos",
    "l": "linux",
    "w": "windows",
    "c": "cli",
}
STATUSES = ("proven", "partial", "not-proven", "retired")
LEDGER = os.path.join("docs", "rust-parity")
SIZES_HEADER = "artefact | go build (bytes) | rust build (bytes) | note"

NET = "sharedUI/src/commonMain/kotlin/org/olcbox/app/net"
MODEL = "sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model"


def read(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as f:
        return f.read()


def table(root, rel):
    """The rows of a pipe table file: (line number, [cells]); comments and blanks skipped."""
    rows = []
    for n, line in enumerate(read(root, rel).splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        rows.append((n, [c.strip() for c in line.split("|")]))
    return rows


def reference_problem(root, ref):
    """Why `ref` (file or file::name) does not exist in the tree, or None."""
    path, _, name = ref.partition("::")
    full = os.path.join(root, path)
    if not os.path.isfile(full):
        return "no such file %s" % path
    if name:
        try:
            with open(full, encoding="utf-8", errors="replace") as f:
                if name not in f.read():
                    return "%s does not occur in %s" % (name, path)
        except OSError as e:
            return "cannot read %s: %s" % (path, e)
    return None


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def balanced(text, start):
    """The text inside the parenthesis that opens at text[start]."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return ""


def data_class_fields(root, rel, name):
    text = strip_comments(read(root, rel))
    m = re.search(r"data class %s\s*\(" % re.escape(name), text)
    if not m:
        return None
    body = balanced(text, m.end() - 1)
    fields, depth = [], 0
    for line in body.split("\n"):
        if depth == 0:
            f = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*val\s+(\w+)\s*:", line)
            if f:
                fields.append(f.group(1))
        depth += line.count("(") + line.count("<") - line.count(")") - line.count(">")
    return fields


def tree_features(root):
    """(why, id) for every feature the tree itself names. Problems are returned as ids of None."""
    found, broken = [], []

    def need(why, ids):
        if not ids:
            broken.append("could not read %s from the tree" % why)
        for i in ids:
            found.append((why, i))

    parser = read(root, NET + "/LinkParser.kt")
    m = re.search(r"val SCHEMES = listOf\(([^)]*)\)", parser)
    need("the link parser's schemes", ["import.link." + s.strip().strip('"').replace("://", "")
                                       for s in m.group(1).split(",")] if m else [])

    settings = read(root, MODEL + "/RoutingSettings.kt")
    m = re.search(r"enum class RoutingMode\s*\{(.*?);", strip_comments(settings), flags=re.S)
    need("the routing modes", ["routing.mode." + v.lower() for v in re.findall(r"\b([A-Z]\w*)\b", re.sub(r"@\w+\([^)]*\)", "", m.group(1)))] if m else [])

    config = read(root, MODEL + "/LocationConfig.kt")
    need("the carriers", ["carrier." + v for v in re.findall(r'const val PROVIDER_\w+ = "(\w+)"', config)])
    need("the olcRTC transports", ["transport." + v for v in re.findall(r'const val TRANSPORT_\w+ = "(\w+)"', config)])

    for rel, cls in ((MODEL + "/SubscriptionSettings.kt", "SubscriptionSettings"),
                     (MODEL + "/RoutingSettings.kt", "RoutingSettings")):
        fields = data_class_fields(root, rel, cls)
        need("the settings of " + cls, ["setting." + f for f in fields or []])

    commands = read(root, "cli/cmd/ghostlane/commands.go")
    m = re.search(r"func run\(.*?\n\}\n", commands, flags=re.S)
    names = []
    if m:
        for case in re.findall(r"^\tcase ([^:\n]+):", m.group(0), flags=re.M):
            names += re.findall(r'"([a-z][a-z-]*)"', case)
    need("the CLI's commands", ["cli.cmd." + n for n in dict.fromkeys(names) if n != "help"])
    flags = re.findall(r'fs\.(?:Bool|String|Int)\("([\w-]+)"',
                       commands + read(root, "cli/cmd/ghostlane/run.go"))
    need("the CLI's flags", ["cli.flag." + f for f in dict.fromkeys(flags)])
    env = re.findall(r'getenv\("(GHOSTLANE_\w+)"\)', read(root, "cli/cmd/ghostlane/run.go"))
    need("the CLI's environment", ["cli.env." + e.lower() for e in dict.fromkeys(env)])
    return found, broken


def check(root):
    """Every problem found, as a list of sentences. Empty means the ledger holds."""
    problems = []
    ledger = os.path.join(root, LEDGER)
    inv_path = os.path.join(LEDGER, "inventory.txt")
    if not os.path.isfile(os.path.join(root, inv_path)):
        return ["%s is missing" % inv_path]

    inventory = {}  # id -> set of platform names
    for n, cells in table(root, inv_path):
        where = "%s:%d" % (inv_path, n)
        if len(cells) != 4 or not all(cells):
            problems.append("%s: a line is `id | platforms | source | what`" % where)
            continue
        fid, letters, source, _what = cells
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]*", fid):
            problems.append("%s: %r is not an id (letters, digits, dots, dashes, underscores)" % (where, fid))
        if fid in inventory:
            problems.append("%s: %s is listed twice" % (where, fid))
            continue
        plats = [p.strip() for p in letters.split(",")]
        bad = [p for p in plats if p not in PLATFORMS]
        if bad or not plats or len(set(plats)) != len(plats):
            problems.append("%s: %s names platforms %r; use each of %s once" %
                            (where, fid, letters, ", ".join(PLATFORMS)))
            continue
        inventory[fid] = {PLATFORMS[p] for p in plats}
        why = reference_problem(root, source)
        if why:
            problems.append("%s: the source of %s: %s" % (where, fid, why))

    found, broken = tree_features(root)
    problems += broken
    for why, fid in found:
        if fid not in inventory:
            problems.append("%s names %s and the inventory has no such feature" % (why, fid))

    for platform in PLATFORMS.values():
        rel = os.path.join(LEDGER, platform + ".txt")
        if not os.path.isfile(os.path.join(root, rel)):
            problems.append("%s is missing" % rel)
            continue
        rows = {}
        for n, cells in table(root, rel):
            where = "%s:%d" % (rel, n)
            if len(cells) != 4 or not cells[0] or not cells[1]:
                problems.append("%s: a row is `id | status | tests | note`" % where)
                continue
            fid, status, tests, note = cells
            if fid in rows:
                problems.append("%s: %s has two rows" % (where, fid))
                continue
            rows[fid] = True
            if fid not in inventory:
                problems.append("%s: %s is not in the inventory" % (where, fid))
                continue
            if platform not in inventory[fid]:
                problems.append("%s: %s is not a feature of %s's build today (the inventory does not list it)" %
                                (where, fid, platform))
                continue
            if status not in STATUSES:
                problems.append("%s: %s has status %r; use one of %s" % (where, fid, status, ", ".join(STATUSES)))
                continue
            ids = [t.strip() for t in tests.split(",") if t.strip() and t.strip() != "-"]
            if status in ("proven", "partial") and not ids:
                problems.append("%s: %s is %s and names no test" % (where, fid, status))
            if status in ("not-proven", "retired") and ids:
                problems.append("%s: %s is %s and names a test; mark it partial or proven" % (where, fid, status))
            if status in ("partial", "retired") and note in ("", "-"):
                problems.append("%s: %s is %s and says nothing in its note" % (where, fid, status))
            for t in ids:
                why = reference_problem(root, t)
                if why:
                    problems.append("%s: the test of %s: %s" % (where, fid, why))
        for fid, plats in inventory.items():
            if platform in plats and fid not in rows:
                problems.append("%s has no row for %s" % (rel, fid))

    sizes = os.path.join(LEDGER, "sizes.txt")
    if not os.path.isfile(os.path.join(root, sizes)):
        problems.append("%s is missing" % sizes)
    else:
        lines = [l for l in read(root, sizes).splitlines() if l.strip() and not l.lstrip().startswith("#")]
        if not lines or lines[0].strip() != SIZES_HEADER:
            problems.append("%s: the first line is the header `%s`" % (sizes, SIZES_HEADER))
        for l in lines[1:]:
            cells = [c.strip() for c in l.split("|")]
            if len(cells) != 4 or not cells[0] or any(c != "-" and not c.isdigit() for c in cells[1:3]):
                problems.append("%s: a size line is `artefact | bytes or - | bytes or - | note`: %r" % (sizes, l))
    return problems


def self_test(root):
    """Plant each fault in a copy of the ledger and see it caught; the real one must be clean first."""
    clean = check(root)
    if clean:
        print("the ledger itself is not clean:")
        for p in clean:
            print("  " + p)
        return 1

    def copy():
        tmp = tempfile.mkdtemp(prefix="parity-")
        shutil.copytree(os.path.join(root, LEDGER), os.path.join(tmp, LEDGER))
        return tmp

    def first_row(tmp, platform, status):
        path = os.path.join(tmp, LEDGER, platform + ".txt")
        lines = open(path, encoding="utf-8").read().split("\n")
        for i, l in enumerate(lines):
            cells = [c.strip() for c in l.split("|")]
            if len(cells) == 4 and cells[1] == status:
                return path, lines, i
        raise SystemExit("self-test: no %s row in %s" % (status, platform))

    faults = []

    def fault(name, mutate, expect):
        faults.append((name, mutate, expect))

    def drop_row(tmp):
        path, lines, i = first_row(tmp, "android", "not-proven")
        del lines[i]
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def dangling(tmp):
        path, lines, i = first_row(tmp, "android", "proven")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[2] = "sharedUI/src/commonTest/kotlin/org/olcbox/app/net/NoSuchTest.kt"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def proven_without_test(tmp):
        path, lines, i = first_row(tmp, "android", "proven")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[2] = "-"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def not_proven_with_test(tmp):
        path, lines, i = first_row(tmp, "android", "not-proven")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[2] = "scripts/parity-ledger.py"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def missing_name(tmp):
        path, lines, i = first_row(tmp, "android", "proven")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[2] = cells[2].split(",")[0].split("::")[0] + "::aNameThatIsNotThere"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def stray_row(tmp):
        path = os.path.join(tmp, LEDGER, "android.txt")
        with open(path, "a", encoding="utf-8") as f:
            f.write("\nnot.a.feature | not-proven | - | -\n")

    def wrong_platform(tmp):
        # ios has no Windows tray
        path = os.path.join(tmp, LEDGER, "ios.txt")
        with open(path, "a", encoding="utf-8") as f:
            f.write("\nwindows.uac-elevation | not-proven | - | -\n")

    def unlisted_scheme(tmp):
        path = os.path.join(tmp, LEDGER, "inventory.txt")
        lines = [l for l in open(path, encoding="utf-8").read().split("\n") if not l.startswith("import.link.vmess ")]
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def bad_status(tmp):
        path, lines, i = first_row(tmp, "ios", "not-proven")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[1] = "mostly"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def retired_without_reason(tmp):
        path, lines, i = first_row(tmp, "android", "retired")
        cells = [c.strip() for c in lines[i].split("|")]
        cells[3] = "-"
        lines[i] = " | ".join(cells)
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    def dangling_source(tmp):
        path = os.path.join(tmp, LEDGER, "inventory.txt")
        text = open(path, encoding="utf-8").read()
        lines = text.split("\n")
        for i, l in enumerate(lines):
            if l.startswith("import.link.vless |"):
                cells = [c.strip() for c in l.split("|")]
                cells[2] = "README.MD::a feature the readme never had"
                lines[i] = " | ".join(cells)
                break
        open(path, "w", encoding="utf-8").write("\n".join(lines))

    fault("a feature with no row", drop_row, "has no row for")
    fault("a test id that does not exist", dangling, "no such file")
    fault("a name that is not in the test file", missing_name, "does not occur in")
    fault("proven without a test", proven_without_test, "names no test")
    fault("not proven with a test", not_proven_with_test, "names a test")
    fault("a row for no feature", stray_row, "is not in the inventory")
    fault("a row for a platform the feature is not on", wrong_platform, "does not list it")
    fault("a link scheme the inventory leaves out", unlisted_scheme, "has no such feature")
    fault("a status that is none of the four", bad_status, "has status")
    fault("a retired row with no reason", retired_without_reason, "says nothing in its note")
    fault("a source the tree does not have", dangling_source, "the source of")

    failed = 0
    for name, mutate, expect in faults:
        tmp = copy()
        try:
            mutate(tmp)
            # the copy keeps its own ledger and reads the sources and tests from the real tree
            problems = check_with_tree(tmp, root)
            if not any(expect in p for p in problems):
                print("self-test FAILED: %s was not caught (wanted %r; got %d problems)" % (name, expect, len(problems)))
                failed += 1
            else:
                print("self-test ok: %s" % name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return 1 if failed else 0


def check_with_tree(ledger_root, tree_root):
    """check() with the ledger files read from ledger_root and everything else from tree_root."""
    tmp = tempfile.mkdtemp(prefix="parity-view-")
    try:
        # a view of the real tree with the planted ledger over it
        for entry in os.listdir(tree_root):
            if entry == ".git":
                continue
            os.symlink(os.path.join(tree_root, entry), os.path.join(tmp, entry))
        os.remove(os.path.join(tmp, "docs"))
        os.makedirs(os.path.join(tmp, "docs"))
        for entry in os.listdir(os.path.join(tree_root, "docs")):
            if entry != "rust-parity":
                os.symlink(os.path.join(tree_root, "docs", entry), os.path.join(tmp, "docs", entry))
        shutil.copytree(os.path.join(ledger_root, LEDGER), os.path.join(tmp, LEDGER))
        return check(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if "--root" in argv:
        root = os.path.abspath(argv[argv.index("--root") + 1])
    if "--self-test" in argv:
        return self_test(root)
    problems = check(root)
    for p in problems:
        print(p)
    if problems:
        print("\nthe parity ledger has %d problem(s)" % len(problems))
        return 1
    print("the parity ledger holds")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
