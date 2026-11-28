#!/usr/bin/env bash
# A desktop check runs when what it checks changes. Rust desktop
# (.github/workflows/rust-desktop.yml) is the one workflow that runs the
# scripts of scripts/desktop-checks/, and only when a changed file matches its
# `paths:` lists. A script names the files it holds on lines
#   # desktop-check-paths: <path or pattern>...
# (patterns as a workflow's: `*` within one directory, `**` across them).
# Fails when
#   - a named path or pattern matches no file of the tree (a rename left it);
#   - a file a script names, or the script itself, is matched by no pattern of
#     the push `paths:` list of rust-desktop.yml: a
#     change to it alone would start no run of the check;
#   - a `# desktop-check-paths:` line names nothing.
# A script that names no file is held to its own path alone.
# It then proves itself: each rule is planted and must fail.
#
#   scripts/p6-tests/desktop-check-paths.sh [root]   root defaults to the repository
# Needs bash, python3 and PyYAML.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
exec python3 -I - "$root" <<'PY'
import copy, os, re, subprocess, sys
import yaml

WORKFLOW = ".github/workflows/rust-desktop.yml"
CHECKS = "scripts/desktop-checks/"
EVENTS = ("push",)
LINE = re.compile(r"^#[ \t]*desktop-check-paths:(.*)$", re.M)


def pattern(p):
    """A workflow path pattern as a regex: `**/` any run of directories, `**`
    any run of characters, `*` any run without a slash. The filter syntax's
    other signs (`?`, `+`, `[`, `]`, a leading `!`) are refused, not guessed."""
    if p.startswith("!") or re.search(r"[?+\[\]]", p):
        raise ValueError("the pattern %r uses a sign this test does not read" % p)
    out, i = [], 0
    while i < len(p):
        if p.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif p.startswith("**", i):
            out.append(".*")
            i += 2
        elif p[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(p[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


def triggers(on):
    """Per event: None when the event starts no run, True when every change
    starts one, else the compiled `paths:` patterns."""
    if isinstance(on, str):
        on = {on: None}
    elif isinstance(on, list):
        on = {k: None for k in on}
    elif not isinstance(on, dict):
        on = {}
    got = {}
    for ev in EVENTS:
        if ev not in on:
            got[ev] = None
            continue
        e = on.get(ev) or {}
        if "paths-ignore" in e:
            raise ValueError("%s: %s has `paths-ignore`, which this test does not read" % (WORKFLOW, ev))
        paths = e.get("paths")
        got[ev] = True if paths is None else [pattern(str(p)) for p in paths]
    return got


def check(on, scripts, tree):
    bad = []
    try:
        lists = triggers(on)
    except ValueError as e:
        return [str(e)]
    for ev in EVENTS:
        if lists[ev] is None:
            bad.append("%s has no %s trigger: no desktop check runs on one" % (WORKFLOW, ev))
    for rel in sorted(scripts):
        named = [rel]
        for m in LINE.finditer(scripts[rel]):
            words = m.group(1).split()
            if not words:
                bad.append("%s: a `# desktop-check-paths:` line names nothing" % rel)
            named += words
        for name in named:
            try:
                rx = pattern(name)
            except ValueError as e:
                bad.append("%s: %s" % (rel, e))
                continue
            hits = sorted(f for f in tree if rx.match(f))
            if not hits:
                bad.append("%s names %s, which matches no file of the tree" % (rel, name))
                continue
            for ev in EVENTS:
                pats = lists[ev]
                if pats is None or pats is True:
                    continue
                missed = [f for f in hits if not any(p.match(f) for p in pats)]
                if missed:
                    more = " (and %d more files)" % (len(missed) - 1) if len(missed) > 1 else ""
                    bad.append("%s: no pattern of the %s paths matches %s%s, which %s checks: "
                               "a change to it alone starts no run"
                               % (WORKFLOW, ev, missed[0], more, rel))
    return bad


def tree_files(root):
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z"], check=True,
                             capture_output=True).stdout.decode("utf-8", "replace")
        files = {f for f in out.split("\0") if f}
        if files:
            return files
    except (OSError, subprocess.CalledProcessError):
        pass
    files = set()
    for d, dirs, names in os.walk(root):
        dirs[:] = [x for x in dirs if x != ".git"]
        for n in names:
            files.add(os.path.relpath(os.path.join(d, n), root).replace(os.sep, "/"))
    return files


def load(root):
    with open(os.path.join(root, WORKFLOW), encoding="utf-8") as f:
        doc = yaml.safe_load(f) or {}
    on = doc.get(True, doc.get("on"))
    tree = tree_files(root)
    scripts = {}
    for rel in sorted(tree):
        if rel.startswith(CHECKS) and "/" not in rel[len(CHECKS):]:
            with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as f:
                scripts[rel] = f.read()
    return on, scripts, tree


def without(on, ev, entry):
    on = copy.deepcopy(on)
    on[ev]["paths"] = [p for p in on[ev]["paths"] if p != entry]
    return on


def selftest(on, scripts, tree):
    """Each rule planted beside the real workflow and scripts: every one must
    add a failure of its own to what the real tree has."""
    planted = CHECKS + "planted.sh"
    checks_glob = CHECKS + "**"
    for ev in EVENTS:
        if checks_glob not in ((on.get(ev) or {}).get("paths") or []):
            return ["self-test: the %s paths do not list %s to take away" % (ev, checks_glob)]
    probes = {
        "a named file no pattern matches": (
            on, dict(scripts, **{planted: "# desktop-check-paths: planted/subject.kt\n"}),
            tree | {planted, "planted/subject.kt"}),
        "a named pattern that matches no file": (
            on, dict(scripts, **{planted: "# desktop-check-paths: planted/gone/**\n"}),
            tree | {planted}),
        "an empty line": (
            on, dict(scripts, **{planted: "# desktop-check-paths:\n"}), tree | {planted}),
        "the push list without the checks": (without(on, "push", checks_glob), scripts, tree),
        "no push trigger": (
            {k: v for k, v in on.items() if k != "push"}, scripts, tree),
        "a `*` taken across a slash": (
            dict(copy.deepcopy(on), push={"paths": ["scripts/*"]}), scripts, tree),
    }
    base = set(check(on, scripts, tree))
    for what, args in probes.items():
        if not set(check(*args)) - base:
            return ["self-test: planted '%s' was not found" % what]
    covered = CHECKS + "deep/one/two.sh"
    wide = dict(copy.deepcopy(on))
    for ev in EVENTS:
        wide[ev] = {"paths": ["scripts/desktop-checks/**"]}
    if check(wide, {covered: "# desktop-check-paths: scripts/desktop-checks/deep/**\n"}, {covered}):
        return ["self-test: `**` did not match across directories"]
    return []


root = sys.argv[1]
on, scripts, tree = load(root)
if not scripts:
    print("FAIL no script in %s" % CHECKS)
    sys.exit(1)
bad = check(on, scripts, tree) + selftest(on, scripts, tree)
for b in bad:
    print("FAIL " + b)
if bad:
    sys.exit(1)
print("ok   every file a desktop check names starts Rust desktop on push "
      "(%d scripts; the test finds each planted violation)" % len(scripts))
PY
