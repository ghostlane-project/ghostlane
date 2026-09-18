#!/usr/bin/env python3
"""Scrub a release-gate artifact directory in place, then prove it is clean.

    gate-scrub.py <dir>               scrub, delete what may not be uploaded, verify
    gate-scrub.py --check-only <dir>  verify only (the verdict job, before anything
                                      becomes an artifact, a summary or a release asset)

The repository is public, and so is every workflow artifact and release asset
it makes. What the gate uploads is the report, the plan and the logs of a leg;
this runs over them first (spec section 10, engine plan A9):

1. Secrets, longest first: every entry of GATE_TELEMOST_ROOMS and
   GATE_WBSTREAM_ROOMS, its query-less form and its last path segment (a room
   link's id) become <room>; GATE_JITSI_HOSTS entries become <jitsi-host>;
   GATE_WBSTREAM_TOKEN becomes <token>. Each also in its JSON-escaped forms
   (Go escapes <, > and & as \\u003c, \\u003e, \\u0026) and percent-encoded, and
   matched without regard to case. The values come from the environment, the
   names the leg's steps carry them under; this prints none of them.
2. Runs of 64 or more hex digits become <key>: the suite's session keys. A
   40-hex commit is left alone.
3. gate-<12 hex> becomes gate-<room>: the suite's Jitsi rooms and channel ids.
4. A JSON Web Token becomes <jwt>: the session credentials a relay hands out
   (LiveKit, which serves WB Stream, is one) admit whoever holds them.

Only .json, .md, .log, .csv and .txt files stay; everything else under the
directory is deleted (a server YAML holds a room, a key and the WB token; the
suite's server binary is no artifact). A file whose name carries any of the
above is deleted too.

Then every file is read again. Anything left fails the run (exit 1) with the
file's name and the counts, never the text that matched, and never a name that
itself matched.
"""

import argparse
import json
import os
import re
import shutil
import sys
from pathlib import Path
from urllib.parse import quote, quote_plus

KEEP = {".json", ".md", ".log", ".csv", ".txt"}
# Nothing shorter is a secret worth the damage its mask does (gate-mask.sh).
MIN = 6
LISTS = (
    ("GATE_TELEMOST_ROOMS", b"<room>"),
    ("GATE_WBSTREAM_ROOMS", b"<room>"),
    ("GATE_JITSI_HOSTS", b"<jitsi-host>"),
)
TOKEN = ("GATE_WBSTREAM_TOKEN", b"<token>")
KEY_RE = re.compile(rb"(?<![0-9A-Fa-f])[0-9A-Fa-f]{64,}(?![0-9A-Fa-f])")
ROOM_RE = re.compile(rb"gate-[0-9A-Fa-f]{12}(?![0-9A-Fa-f])")
JWT_RE = re.compile(rb"eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")


def entries(value):
    """Trimmed, non-empty entries; commas and newlines both separate."""
    return [e.strip() for e in re.split(r"[,\r\n]", value or "") if e.strip()]


def forms(entry):
    """The entry, without its query or fragment, and its last path segment.

    A Jitsi host has only itself: gate-resolve.sh check refuses one with a
    scheme, a port or a path, which is what keeps the bare host that a Go
    error prints ("lookup <host>") equal to the entry scrubbed here.
    """
    out = {entry}
    base = re.split(r"[?#]", entry, maxsplit=1)[0]
    out.add(base)
    out.add(base.rstrip("/").rsplit("/", 1)[-1])
    return out


def encodings(value):
    """How a value can be spelled in a log or a JSON report."""
    out = {value, quote(value, safe=""), quote_plus(value, safe="")}
    for ascii_only in (True, False):
        escaped = json.dumps(value, ensure_ascii=ascii_only)[1:-1]
        out.add(escaped)
        out.add(escaped.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026"))
        out.add(escaped.replace("/", "\\/"))
    return {v for v in out if len(v) >= MIN}


def secret_table(env):
    """{lower-cased bytes: placeholder} for every spelling of every secret."""
    table = {}
    for name, placeholder in LISTS:
        for entry in entries(env.get(name, "")):
            for form in forms(entry):
                for spelled in encodings(form):
                    table.setdefault(spelled.encode().lower(), placeholder)
    token = (env.get(TOKEN[0]) or "").strip()
    if token:
        for spelled in encodings(token):
            table[spelled.encode().lower()] = TOKEN[1]
    return table


def matcher_for(table):
    if not table:
        return None
    alternatives = sorted(table, key=len, reverse=True)
    return re.compile(b"|".join(re.escape(a) for a in alternatives), re.IGNORECASE)


def scrub(data, matcher, table):
    if matcher is not None:
        data = matcher.sub(lambda m: table[m.group(0).lower()], data)
    data = KEY_RE.sub(b"<key>", data)
    data = ROOM_RE.sub(b"gate-<room>", data)
    return JWT_RE.sub(b"<jwt>", data)


def residue(data, matcher):
    """(secrets, keys, room names, tokens) still in data."""
    secrets = len(matcher.findall(data)) if matcher is not None else 0
    return secrets, len(KEY_RE.findall(data)), len(ROOM_RE.findall(data)), len(JWT_RE.findall(data))


def name_carries_secret(rel, matcher):
    raw = rel.encode(errors="surrogateescape")
    return any(residue(raw, matcher))


def allowed(path):
    return path.suffix.lower() in KEEP and not path.is_symlink()


def scrub_tree(root, matcher, table):
    """Scrub what may stay, delete the rest. Returns (scrubbed, removed)."""
    scrubbed = removed = 0
    for path in sorted(root.rglob("*")):
        if path.is_dir() and not path.is_symlink():
            continue
        rel = path.relative_to(root).as_posix()
        if name_carries_secret(rel, matcher) or not allowed(path):
            path.unlink()
            removed += 1
            continue
        data = path.read_bytes()
        clean = scrub(data, matcher, table)
        if clean != data:
            path.write_bytes(clean)
            scrubbed += 1
    # Every file under a directory named after a secret went above; the names go too.
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir() and not path.is_symlink():
            if name_carries_secret(path.relative_to(root).as_posix(), matcher):
                shutil.rmtree(path)
    return scrubbed, removed


def verify_tree(root, matcher):
    """One line per problem; a name that carries a secret is never printed."""
    problems = []
    hidden = 0
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if name_carries_secret(rel, matcher):
            hidden += 1
            problems.append(f"entry #{hidden} under the directory: its name carries a secret")
            continue
        if path.is_dir() and not path.is_symlink():
            continue
        if not allowed(path):
            problems.append(f"{rel}: may not be uploaded (only {', '.join(sorted(KEEP))} files may)")
            continue
        secrets, keys, rooms, jwts = residue(path.read_bytes(), matcher)
        if secrets or keys or rooms or jwts:
            problems.append(f"{rel}: {secrets} secret value(s), {keys} key(s), {rooms} room name(s), "
                            f"{jwts} token(s) remain")
    return problems


def run(root, check_only, env):
    table = secret_table(env)
    matcher = matcher_for(table)
    root = Path(root)
    if not root.is_dir():
        if check_only:
            print(f"gate-scrub: {root} does not exist; nothing to vouch for", file=sys.stderr)
            return 1
        print(f"gate-scrub: {root} does not exist; nothing to scrub")
        return 0
    what = "checked"
    if not check_only:
        scrubbed, removed = scrub_tree(root, matcher, table)
        what = f"{scrubbed} file(s) scrubbed, {removed} removed"
    problems = verify_tree(root, matcher)
    if problems:
        for p in problems:
            print(f"gate-scrub: {p}", file=sys.stderr)
        print(f"gate-scrub: {len(problems)} problem(s) under {root}; nothing here may be uploaded", file=sys.stderr)
        return 1
    print(f"gate-scrub: {root} is clean ({what})")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check-only", action="store_true", help="verify, change nothing")
    parser.add_argument("dir")
    args = parser.parse_args(argv)
    return run(args.dir, args.check_only, os.environ)


if __name__ == "__main__":
    sys.exit(main())
