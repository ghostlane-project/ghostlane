#!/usr/bin/env bash
# The iOS closure of the Rust core: what the iPhone's packet tunnel extension
# links from the core's repository at a commit, and whether a pin move changes
# it. No build: it reads the core's tree with git.
#
#   scripts/ios-closure.sh digest <commit> [repo]
#       the closure's members, one per line, then "closure <sha256>"
#   scripts/ios-closure.sh class <old-commit> <new-commit> [repo]
#       what differs between the two closures, then "class: same" or
#       "class: changed" (both exit 0: a changed closure is news, not an error)
#   scripts/ios-closure.sh size-delta <old-bytes> <new-bytes> [max-growth-bytes]
#       prints the change of the library's size; exits 1 when it grew by more
#       than the limit (default 131072 bytes, or IOS_SIZE_MAX_GROWTH)
#
# The closure is what scripts/build-ghostlane-core-ios.sh builds:
# `cargo build -p ghostlane-core --features boring --target aarch64-apple-ios`
# (the simulator slice has the same crates). Its members are
#   - every crate of the repository that build reaches: normal and build
#     dependencies only (never dev-dependencies), optional ones only when a
#     feature that is on turns them on, target tables only when they hold for
#     aarch64-apple-ios; each by a hash of its files, without tests/, benches/,
#     examples/ and documents;
#   - every crate from a registry or git, by name, version and checksum or
#     source, reached through Cargo.lock;
#   - what shapes every crate: the release profile, .cargo/config.toml and
#     rust-toolchain.toml.
# "same" means the library is built from the same sources by the same
# settings. A file that is not a member (a test, a doc, a crate the build never
# reaches) changes nothing.
#
# [repo] is a git directory holding the commit (a partial clone is enough: only
# trees and a few manifests are read). With none, the commits are fetched, with
# no blobs, into IOS_CLOSURE_CACHE (default $TMPDIR/ghostlane-ios-closure) from
# https://github.com/$GHOSTLANE_CORE_REPO.
#
# Needs bash, git and python3 (3.11+ for tomllib).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cmd="${1:-}"

size_delta() {
  local old="${1:-}" new="${2:-}" max="${3:-${IOS_SIZE_MAX_GROWTH:-131072}}"
  [[ "$old" =~ ^[0-9]+$ && "$new" =~ ^[0-9]+$ && "$max" =~ ^[0-9]+$ ]] || {
    echo "usage: $0 size-delta <old-bytes> <new-bytes> [max-growth-bytes]" >&2
    return 2
  }
  local delta=$((new - old))
  echo "ios size: $old -> $new bytes ($(printf '%+d' "$delta") bytes, limit +$max)"
  if [ "$delta" -gt "$max" ]; then
    echo "ios size: FAIL, the library grew by $delta bytes, more than $max" >&2
    return 1
  fi
  echo "ios size: ok"
}

ensure_repo() {
  # ensure_repo <commit> [repo]: prints a git directory that holds the commit.
  local commit="$1" repo="${2:-${IOS_CLOSURE_REPO:-}}"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "a commit is 40 lowercase hex digits: '$commit'" >&2; return 2; }
  if [ -n "$repo" ]; then
    git -C "$repo" cat-file -e "$commit^{commit}" 2> /dev/null || { echo "$commit is not in $repo" >&2; return 1; }
    echo "$repo"
    return
  fi
  # shellcheck source=scripts/ghostlane-core-pin.sh
  . "$here/ghostlane-core-pin.sh"
  repo="${IOS_CLOSURE_CACHE:-${TMPDIR:-/tmp}/ghostlane-ios-closure}"
  [ -d "$repo/.git" ] || [ -f "$repo/HEAD" ] || { mkdir -p "$repo"; git init -q "$repo"; }
  git -C "$repo" remote get-url origin > /dev/null 2>&1 ||
    git -C "$repo" remote add origin "https://github.com/${GHOSTLANE_CORE_REPO}.git"
  if ! git -C "$repo" cat-file -e "$commit^{commit}" 2> /dev/null; then
    git -C "$repo" fetch -q --depth 1 --filter=blob:none origin "$commit" >&2
  fi
  echo "$repo"
}

closure() {
  python3 -I - "$@" << 'PY'
import hashlib, json, subprocess, sys, tomllib

ROOT_PKG = "ghostlane-core"
ROOT_FEATURES = {"boring"}
TRIPLES = {"aarch64-apple-ios", "aarch64-apple-ios-sim"}
CFG = {"target_os": "ios", "target_family": "unix", "target_vendor": "apple",
       "target_arch": "aarch64", "target_pointer_width": "64", "target_endian": "little",
       "target_env": ""}
CFG_FLAGS = {"unix"}
SKIP_DIRS = {"tests", "benches", "examples"}
SKIP_SUFFIX = (".md", ".diff", ".cargo_vcs_info.json")


def git(repo, *args, data=None):
    r = subprocess.run(["git", "-C", repo, *args], input=data, stdout=subprocess.PIPE, check=True)
    return r.stdout


class Tree:
    def __init__(self, repo, commit):
        self.repo, self.commit = repo, commit
        out = git(repo, "ls-tree", "-r", "-z", commit).split(b"\0")
        self.files = {}
        for ent in out:
            if not ent:
                continue
            meta, path = ent.split(b"\t", 1)
            self.files[path.decode()] = meta.split()[2].decode()
        self._cache = {}

    def blob(self, path):
        if path not in self.files:
            return None
        if path not in self._cache:
            self._cache[path] = git(self.repo, "cat-file", "blob", self.files[path])
        return self._cache[path]

    def toml(self, path):
        b = self.blob(path)
        return None if b is None else tomllib.loads(b.decode())


# ---- cfg(...) for aarch64-apple-ios ------------------------------------------

def cfg_eval(expr):
    toks, i = [], 0
    while i < len(expr):
        c = expr[i]
        if c.isspace():
            i += 1
        elif c in "(),=":
            toks.append(c); i += 1
        elif c == '"':
            j = expr.index('"', i + 1)
            toks.append(('s', expr[i + 1:j])); i = j + 1
        else:
            j = i
            while j < len(expr) and (expr[j].isalnum() or expr[j] in "_-"):
                j += 1
            if j == i:
                raise ValueError("cfg: " + expr)
            toks.append(('i', expr[i:j])); i = j
    pos = [0]

    def peek():
        return toks[pos[0]] if pos[0] < len(toks) else None

    def take():
        t = toks[pos[0]]; pos[0] += 1; return t

    def pred():
        t = take()
        name = t[1]
        nxt = peek()
        if nxt == "(":
            take()
            args = []
            while peek() != ")":
                args.append(pred())
                if peek() == ",":
                    take()
            take()
            if name == "all":
                return all(args)
            if name == "any":
                return any(args)
            if name == "not":
                return not args[0]
            raise ValueError("cfg: " + expr)
        if nxt == "=":
            take()
            val = take()[1]
            if name in CFG:
                return CFG[name] == val
            return True  # a key the table does not know: the wider answer
        return name in CFG_FLAGS

    return pred()


def target_applies(key):
    key = key.strip()
    if key.startswith("cfg(") and key.endswith(")"):
        return cfg_eval(key[4:-1])
    return key in TRIPLES


# ---- the closure --------------------------------------------------------------

def workspace_defs(tree):
    root = tree.toml("Cargo.toml")
    return root, (root.get("workspace", {}).get("dependencies", {}))


def load_manifests(tree):
    pk = {}  # (name, version) -> list of (dir, toml)
    for path in sorted(tree.files):
        if not (path == "Cargo.toml" or path.endswith("/Cargo.toml")):
            continue
        t = tree.toml(path)
        pkg = (t or {}).get("package")
        if not pkg or "name" not in pkg:
            continue
        ver = pkg.get("version")
        if isinstance(ver, dict) and ver.get("workspace"):
            ver = tree_root_version(tree)
        pk.setdefault((pkg["name"], ver), []).append((path[: -len("Cargo.toml")].rstrip("/"), t))
    return pk


def tree_root_version(tree):
    return tree.toml("Cargo.toml").get("workspace", {}).get("package", {}).get("version")


def split_lock_dep(s):
    parts = s.split(" ")
    return parts[0], (parts[1] if len(parts) > 1 else None)


def dep_tables(manifest):
    """(table-key, name, spec) for normal and build dependencies that hold on iOS."""
    out = []
    for tbl in ("dependencies", "build-dependencies"):
        for k, v in manifest.get(tbl, {}).items():
            out.append((k, v))
    for tkey, body in manifest.get("target", {}).items():
        if not target_applies(tkey):
            continue
        for tbl in ("dependencies", "build-dependencies"):
            for k, v in body.get(tbl, {}).items():
                out.append((k, v))
    return out


def feature_closure(manifest, feats, want_default):
    table = manifest.get("features", {})
    on = set(feats)
    if want_default and "default" in table:
        on.add("default")
    dep_on, dep_feats = set(), {}
    explicit_dep = {i[4:] for items in table.values() for i in items if i.startswith("dep:")}
    optional = {k for k, v in dep_tables(manifest) if isinstance(v, dict) and v.get("optional")}
    changed = True
    seen = set()
    while changed:
        changed = False
        for f in list(on):
            if f in seen:
                continue
            seen.add(f); changed = True
            for item in table.get(f, []):
                if item.startswith("dep:"):
                    dep_on.add(item[4:])
                elif "/" in item:
                    d, ft = item.split("/", 1)
                    weak = d.endswith("?")
                    d = d.rstrip("?")
                    dep_feats.setdefault(d, set()).add(ft)
                    if not weak:
                        dep_on.add(d)
                elif item in table:
                    on.add(item)
                elif item in optional and item not in explicit_dep:
                    dep_on.add(item)
        # an optional dependency with no `dep:` anywhere is an implicit feature of its own name
    for f in on:
        if f in optional and f not in explicit_dep and f not in table:
            dep_on.add(f)
    return on, dep_on, dep_feats


def dir_hash(tree, d):
    h = hashlib.sha256()
    prefix = d + "/" if d else ""
    for path in sorted(tree.files):
        if not path.startswith(prefix):
            continue
        rel = path[len(prefix):]
        if rel.split("/")[0] in SKIP_DIRS and "/" in rel:
            continue
        if rel.endswith(SKIP_SUFFIX):
            continue
        h.update(("%s %s\n" % (rel, tree.files[path])).encode())
    return h.hexdigest()


def closure(repo, commit):
    tree = Tree(repo, commit)
    lock = tree.toml("Cargo.lock")
    if lock is None:
        raise SystemExit("no Cargo.lock at " + commit)
    root_toml, ws_deps = workspace_defs(tree)
    manifests = load_manifests(tree)
    packages = lock.get("package", [])
    by_name = {}
    for p in packages:
        by_name.setdefault(p["name"], []).append(p)

    def lock_key(p):
        return (p["name"], p["version"], p.get("source", ""))

    def path_manifest(p):
        c = manifests.get((p["name"], p["version"]), [])
        return c

    members = {}
    feats_seen = {}  # lock key -> (features, default) already walked
    stack = []

    roots = [p for p in by_name.get(ROOT_PKG, []) if "source" not in p]
    if not roots:
        raise SystemExit("%s is not a package of the lock at %s" % (ROOT_PKG, commit))
    stack.append((roots[0], set(ROOT_FEATURES), True))

    def lock_deps_of(p, name):
        res = []
        for s in p.get("dependencies", []):
            n, v = split_lock_dep(s)
            if n != name:
                continue
            cands = [q for q in by_name.get(n, []) if v is None or q["version"] == v]
            if " (" in s:
                src = s.split(" (", 1)[1].rstrip(")")
                cands = [q for q in cands if q.get("source", "") == src] or cands
            res.extend(cands)
        return res

    while stack:
        p, feats, default = stack.pop()
        k = lock_key(p)
        prev = feats_seen.get(k)
        if prev and feats <= prev[0] and (prev[1] or not default):
            continue
        if prev:
            feats = feats | prev[0]
            default = default or prev[1]
        feats_seen[k] = (set(feats), default)
        if "source" in p:
            members["ext %s %s" % (p["name"], p["version"])] = p.get("checksum") or p["source"]
            for s in p.get("dependencies", []):
                n, v = split_lock_dep(s)
                for q in lock_deps_of(p, n):
                    stack.append((q, set(), True))
            continue
        cands = path_manifest(p)
        if not cands:
            raise SystemExit("no manifest for %s %s at %s" % (p["name"], p["version"], commit))
        for d, manifest in cands:
            members["path %s %s %s" % (p["name"], p["version"], d)] = dir_hash(tree, d)
        d, manifest = cands[0]
        on, dep_on, dep_feats = feature_closure(manifest, feats, default)
        for key, spec in dep_tables(manifest):
            if isinstance(spec, str):
                spec = {"version": spec}
            if spec.get("workspace"):
                base = ws_deps.get(key, {})
                if isinstance(base, str):
                    base = {"version": base}
                merged = dict(base)
                merged["features"] = list(base.get("features", [])) + list(spec.get("features", []))
                merged["optional"] = spec.get("optional", False)
                spec = merged
            name = spec.get("package", key)
            if spec.get("optional") and key not in dep_on:
                continue
            want = set(spec.get("features", [])) | dep_feats.get(key, set())
            dflt = spec.get("default-features", spec.get("default_features", True))
            for q in lock_deps_of(p, name):
                stack.append((q, want, dflt))

    # what shapes every crate
    # the whole root manifest: workspace dependency features, the edition,
    # the resolver, patches and the profile reach the build without touching
    # Cargo.lock; any change there is "changed" (the safe direction)
    members["file Cargo.toml"] = tree.files["Cargo.toml"]
    for f in (".cargo/config.toml", ".cargo/config", "rust-toolchain.toml", "rust-toolchain"):
        if f in tree.files:
            members["file " + f] = tree.files[f]
    return members


def digest(members):
    h = hashlib.sha256()
    for k in sorted(members):
        h.update(("%s=%s\n" % (k, members[k])).encode())
    return h.hexdigest()


def main():
    mode = sys.argv[1]
    if mode == "digest":
        m = closure(sys.argv[2], sys.argv[3])
        for k in sorted(m):
            print("%s %s" % (k, m[k][:16]))
        print("closure " + digest(m))
    elif mode == "class":
        repo, old, new = sys.argv[2:5]
        a, b = closure(repo, old), closure(repo, new)
        lines = []
        for k in sorted(set(a) - set(b)):
            lines.append("- " + k)
        for k in sorted(set(b) - set(a)):
            lines.append("+ " + k)
        for k in sorted(set(a) & set(b)):
            if a[k] != b[k]:
                lines.append("~ " + k)
        same = not lines
        print("ios closure: %d members at %s, %d at %s" % (len(a), old[:8], len(b), new[:8]))
        for ln in lines[:60]:
            print(ln)
        if len(lines) > 60:
            print("... and %d more" % (len(lines) - 60))
        print("class: " + ("same" if same else "changed"))
    else:
        raise SystemExit("mode?")


main()
PY
}

case "$cmd" in
  size-delta)
    shift
    size_delta "$@"
    ;;
  digest)
    commit="${2:-}"
    repo="$(ensure_repo "$commit" "${3:-}")"
    closure digest "$repo" "$commit"
    ;;
  class)
    old="${2:-}"
    new="${3:-}"
    repo="$(ensure_repo "$old" "${4:-}")"
    ensure_repo "$new" "${4:-}" > /dev/null
    closure class "$repo" "$old" "$new"
    ;;
  *)
    sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
