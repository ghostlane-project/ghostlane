#!/usr/bin/env bash
# A release run that signs nothing must be unable to sign, upload or publish,
# and must start no live leg of the release gate. This reads that off the two
# workflows and runs the two scripts that decide it, so it holds for a step
# nobody has written yet:
#
#   - scripts/release-guard.sh. On main a run is signed unless dry_run is
#     ticked. Off main a run is dry unless signed_head is the full commit being
#     built. A run that signs refuses a core pin the fork's `ghostlane` branch
#     does not hold.
#   - .github/workflows/release.yml. Every job and step that names a secret,
#     but for the short list of harmless ones below, asks for the guard's
#     `signed` output, and so does every job with more than read permissions:
#     none of them is reached in a dry run. A dry run's Play track is none, and
#     it calls the gate dry.
#   - .github/workflows/gate.yml and scripts/gate-resolve.sh. A dry call, and
#     any run of the gate's own on a branch under rust/, take the skipped
#     report with one fixed reason and walk no leg. A push to a branch under
#     rust/ starts no gate at all.
#
# Then it proves itself: each violation is planted in a copy and must be found.
#
#   scripts/test-release-dry.sh [root]     root defaults to the repository
#
# Needs bash, curl, jq, python3 and PyYAML. GitHub is a local server here and
# every commit and name is made up, so nothing leaves the machine.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
python3 -I -c 'import yaml' 2>/dev/null || {
  echo "FAIL PyYAML is missing (python3-yaml)"
  exit 1
}
exec python3 -I - "$root" <<'PY'
import copy
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading

import yaml

ROOT = os.path.abspath(sys.argv[1])
SCRIPTS = os.path.join(ROOT, "scripts")
RELEASE = ".github/workflows/release.yml"
GATE = ".github/workflows/gate.yml"
DRY_WORKFLOW = ".github/workflows/release-dry.yml"

# What a job or a step asks for when it may run only in a run that signs. The
# output is written by scripts/release-guard.sh alone, and an output that was
# never written is not 'true': the question fails closed.
SIGNED = "needs.plan.outputs.signed == 'true'"
# Baked into every build, published with every release, and no key to anything
# outside the app: the crypt1 list key and the hash of the admin unlock.
HARMLESS = ("OLCBOX_CRYPT_KEY_V1", "OLCBOX_ADMIN_PASS_SHA256")
# The gate's rooms. release.yml may hand them to gate.yml and to nothing else,
# and gate.yml must be unable to use them in a dry run.
ROOMS = ("GATE_TELEMOST_ROOMS", "GATE_WBSTREAM_ROOMS", "GATE_WBSTREAM_TOKEN", "GATE_JITSI_HOSTS",
         "GATE_VKCALLS_ROOMS", "GATE_MTSLINK_ROOMS")
GATE_USES = "./.github/workflows/gate.yml"
# The one reason a dry run's skipped report carries (scripts/gate-resolve.sh).
DRY_REASON = "dry run: nothing is signed, uploaded to a store or published, and no live room is joined"
# What every job of gate.yml that holds a secret asks for, each of the three:
# resolve's verdict, the caller's own word, and the ref for a run nobody called.
LEG_GUARDS = ("needs.resolve.outputs.skip != 'true'", "!inputs.dry",
              "!(!inputs.mode && startsWith(github.ref, 'refs/heads/rust/'))")
GATE_PATHS = ["scripts/cores-pins.sh", "scripts/olcrtc-pin.sh", "scripts/gate-*", ".github/workflows/gate.yml"]

EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.S)
# GitHub reads context names in any case, so the search does too. The names
# that are let through are spelled one way, below.
TOKEN = re.compile(r"(?<![A-Za-z0-9_.-])secrets(?![A-Za-z0-9_-])", re.I)
NAME = re.compile(r"\.([A-Za-z_][A-Za-z0-9_]*)")
# Outside the braces this found, only what is shaped like a read counts: the
# word alone is a sentence in a script.
SHAPED = re.compile(r"\.[A-Za-z_]|\[")


def norm(text):
    return " ".join(str(text).split())


def unwrap(cond):
    """An `if:` as a bare expression, with or without its braces."""
    text = str(cond).strip()
    m = re.fullmatch(r"\$\{\{(.*)\}\}", text, re.S)
    return norm(m.group(1) if m else text)


def conjuncts(expr):
    """The terms of a top-level `a && b && c`. None when the expression has an
    `||` outside brackets or does not balance: such a condition can be true
    without any one of its terms, so it guards nothing."""
    terms, cur, depth, quoted, i = [], "", 0, False, 0
    while i < len(expr):
        c = expr[i]
        if quoted:
            cur += c
            if c == "'":
                if expr[i + 1:i + 2] == "'":
                    cur += "'"
                    i += 1
                else:
                    quoted = False
        elif c == "'":
            quoted = True
            cur += c
        elif c in "([":
            depth += 1
            cur += c
        elif c in ")]":
            depth -= 1
            cur += c
        elif depth == 0 and expr.startswith("&&", i):
            terms.append(cur)
            cur = ""
            i += 1
        elif depth == 0 and expr.startswith("||", i):
            return None
        else:
            cur += c
        i += 1
    if quoted or depth != 0:
        return None
    terms.append(cur)
    return [norm(t) for t in terms]


def asks(cond, term):
    if cond is None:
        return False
    terms = conjuncts(unwrap(cond))
    return terms is not None and term in terms


def walk(node, path=()):
    """Every string in the document, keys too, with where it stands."""
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str):
                yield path + (k,), k
            yield from walk(v, path + (k,))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, path + (i,))
    elif isinstance(node, str):
        yield path, node


def expressions(path, text):
    """What GitHub evaluates in a string: each ${{ }}, and an `if:` whole."""
    found = [norm(e) for e in EXPR.findall(text)]
    if path and path[-1] == "if" and not found:
        found.append(norm(text))
    return found


def secret_reads(path, text):
    """Each read of the secrets context in a string: (name or None for the
    context whole, the expression it stands in or None when it stands in none
    this could parse). A string with ${{ in it is searched whole, not only
    inside the braces found: a `}}` inside a quoted text ends the match early,
    and what follows it is still read by GitHub."""
    spans = [(m.start(1), m.end(1), norm(m.group(1))) for m in EXPR.finditer(text)]
    if path and path[-1] == "if" and not spans:
        spans = [(0, len(text), norm(text))]
    if not spans and "${{" not in text:
        return
    for m in TOKEN.finditer(text):
        inside = next((e for a, b, e in spans if a <= m.start() < b), None)
        if inside is None and not SHAPED.match(text, m.end()):
            continue
        name = NAME.match(text, m.end())
        yield (name.group(1) if name else None), inside


def needs_of(job):
    needs = job.get("needs") or []
    return [needs] if isinstance(needs, str) else list(needs)


def steps_of(job):
    steps = job.get("steps")
    return [s for s in steps if isinstance(s, dict)] if isinstance(steps, list) else []


def signed_job(job):
    return "plan" in needs_of(job) and asks(job.get("if"), SIGNED)


def signed_step(job, step):
    return signed_job(job) or ("plan" in needs_of(job) and asks(step.get("if"), SIGNED))


def where(doc, path):
    if len(path) >= 2 and path[0] == "jobs":
        job = (doc.get("jobs") or {}).get(path[1]) or {}
        if len(path) >= 4 and path[2] == "steps" and isinstance(path[3], int):
            steps = job.get("steps") or []
            step = steps[path[3]] if path[3] < len(steps) and isinstance(steps[path[3]], dict) else {}
            label = step.get("name") or step.get("uses") or "#%d" % path[3]
            return "job %s, step '%s'" % (path[1], label)
        return "job %s" % path[1]
    return "the workflow's own %s" % (path[0] if path else "top")


def wide(permissions):
    """More than reading: any write, or a shorthand (read-all reads more than a build needs)."""
    if permissions is None:
        return False
    if not isinstance(permissions, dict):
        return True
    return any(v not in ("read", "none") for v in permissions.values())


def secret_refusal(doc, name, expr, path, text):
    """Why a dry run could read this secret here; None when it cannot."""
    if name in HARMLESS:
        return None
    if len(path) < 2 or path[0] != "jobs":
        return "is read outside any job, where no guard can stand"
    job = (doc.get("jobs") or {}).get(path[1])
    if not isinstance(job, dict):
        return "is read in something that is not a job"
    if signed_job(job):
        return None
    if len(path) >= 4 and path[2] == "steps" and isinstance(path[3], int):
        steps = job.get("steps") or []
        if path[3] < len(steps) and isinstance(steps[path[3]], dict) and signed_step(job, steps[path[3]]):
            return None
    if "plan" in needs_of(job) and expr == "%s && secrets.%s || ''" % (SIGNED, name):
        return None
    if (name in ROOMS and job.get("uses") == GATE_USES and path[2:] == ("secrets", name)
            and text == "${{ secrets.%s }}" % name):
        return None  # held to a dry gate by the checks of the gate call and of gate.yml
    return "is read where a dry run reaches (ask for %s on the job or the step, or write ${{ %s && secrets.%s || '' }})" % (
        SIGNED, SIGNED, name)


def secret_scan(doc, label):
    bad = []
    for path, text in walk(doc):
        for name, expr in secret_reads(path, text):
            if name is None:
                bad.append("%s, %s: reads the secrets context whole" % (label, where(doc, path)))
                continue
            why = secret_refusal(doc, name, expr, path, text)
            if why:
                bad.append("%s, %s: secrets.%s %s" % (label, where(doc, path), name, why))
    return bad


def triggers(doc):
    on = doc.get(True, doc.get("on"))
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return {k: None for k in on}
    return on if isinstance(on, dict) else {}


def check_release(doc):
    bad = []
    label = "release.yml"
    on = triggers(doc)
    if set(on) != {"workflow_dispatch"}:
        bad.append("%s must start by workflow_dispatch alone: any other trigger carries no inputs for the guard to read" % label)
    inputs = (on.get("workflow_dispatch") or {}).get("inputs") or {}
    # The four inputs main dispatches with today keep their defaults; the two
    # new ones default to what main does today.
    for name, kind, default in (("platforms", "choice", "test-macos"), ("play_track", "choice", "internal"),
                                ("gate", "choice", "required"), ("gate_reason", "string", ""),
                                ("dry_run", "boolean", False), ("signed_head", "string", "")):
        got = inputs.get(name) if isinstance(inputs.get(name), dict) else {}
        if got.get("type") != kind or got.get("default") != default or type(got.get("default")) is not type(default):
            bad.append("%s: the input %s must be a %s with the default %r" % (label, name, kind, default))
    if doc.get("permissions") != {"contents": "read"}:
        bad.append("%s: the workflow's own permissions must be contents: read and nothing else" % label)

    jobs = doc.get("jobs") if isinstance(doc.get("jobs"), dict) else {}
    plan = jobs.get("plan") if isinstance(jobs.get("plan"), dict) else {}
    steps = steps_of(plan)
    if "if" in plan or needs_of(plan):
        bad.append("%s: the plan job must run first and always, it holds the guard" % label)
    at = [i for i, s in enumerate(steps) if s.get("id") == "guard"]
    if len(at) != 1:
        bad.append("%s: the plan job needs exactly one step with the id guard, the one that runs scripts/release-guard.sh decide" % label)
    else:
        guard = steps[at[0]]
        want = {"GUARD_REF": "${{ github.ref }}", "GUARD_SHA": "${{ github.sha }}",
                "GUARD_DRY_RUN": "${{ inputs.dry_run }}", "GUARD_SIGNED_HEAD": "${{ inputs.signed_head }}",
                "GUARD_PLAY_TRACK": "${{ inputs.play_track }}"}
        if guard.get("env") != want:
            bad.append("%s: the guard step must read exactly the ref, the commit and the three inputs: %s" % (label, want))
        if "scripts/release-guard.sh decide" not in str(guard.get("run")):
            bad.append("%s: the guard step must run scripts/release-guard.sh decide" % label)
        if "if" in guard or guard.get("continue-on-error"):
            bad.append("%s: the guard step must run always and must be able to fail the run" % label)
        for s in steps[:at[0]]:
            w = s.get("with") or {}
            if not str(s.get("uses", "")).startswith("actions/checkout@") or "ref" in w or "repository" in w:
                bad.append("%s: nothing runs before the guard but the checkout of the commit being built" % label)
        pins = [i for i, s in enumerate(steps) if "scripts/release-guard.sh pin" in str(s.get("run"))]
        if len(pins) != 1 or pins[0] < at[0]:
            bad.append("%s: the plan job needs one step after the guard that runs scripts/release-guard.sh pin" % label)
        else:
            pin = steps[pins[0]]
            if (pin.get("env") or {}).get("GUARD_SIGNED") != "${{ steps.guard.outputs.signed }}":
                bad.append("%s: the pin step must be told whether the run signs (GUARD_SIGNED from the guard's output)" % label)
            if "if" in pin or pin.get("continue-on-error"):
                bad.append("%s: the pin step must run always and must be able to fail the run" % label)
    outputs = plan.get("outputs") or {}
    for key in ("signed", "play_track"):
        if outputs.get(key) != "${{ steps.guard.outputs.%s }}" % key:
            bad.append("%s: plan's output %s must be the guard step's own" % (label, key))

    # The guard's answer is compared with 'true' and nothing else: `== true`
    # is never equal, and a bare output is true for the text 'false' as well.
    for path, text in walk(doc):
        for expr in expressions(path, text):
            rest = re.sub(r"needs\.plan\.outputs\.signed [=!]= 'true'", "", expr)
            if "needs.plan.outputs.signed" in rest:
                bad.append("%s, %s: compare needs.plan.outputs.signed with 'true' (== or !=), nothing else" % (label, where(doc, path)))
            rest = re.sub(r"steps\.guard\.outputs\.signed [=!]= 'true'", "", expr)
            if "steps.guard.outputs.signed" in rest and expr != "steps.guard.outputs.signed":
                bad.append("%s, %s: compare steps.guard.outputs.signed with 'true', or pass it on whole" % (label, where(doc, path)))
        if "inputs.play_track" in text and path != ("jobs", "plan", "steps", at[0] if len(at) == 1 else -1, "env", "GUARD_PLAY_TRACK"):
            bad.append("%s, %s: the Play track is the guard's (needs.plan.outputs.play_track), never the input itself" % (label, where(doc, path)))

    for name, job in jobs.items():
        if not isinstance(job, dict):
            continue
        if any("needs.plan." in text for _, text in walk(job)) and "plan" not in needs_of(job):
            bad.append("%s, job %s: names plan's outputs without needing plan, so they are empty" % (label, name))
        if "secrets" in job and not isinstance(job["secrets"], dict):
            bad.append("%s, job %s: hands every secret to another workflow (secrets: %s)" % (label, name, job["secrets"]))
        if wide(job.get("permissions")) and not signed_job(job):
            bad.append("%s, job %s: has more than read permissions and is reached in a dry run (ask for %s in its if)" % (label, name, SIGNED))
        for step in steps_of(job):
            w = step.get("with") or {}
            if (str(step.get("uses", "")).startswith("actions/checkout@") and "repository" not in w and "ref" in w
                    and not signed_step(job, step)):
                bad.append("%s, job %s: checks out another commit of this repository than the one the guard read" % (label, name))
            if "play-upload.py" in str(step.get("run")) and not (
                    signed_step(job, step) and asks(step.get("if"), "needs.plan.outputs.play_track != 'none'")):
                bad.append("%s, job %s: the upload to Google Play must ask for %s and for a track that is not none" % (label, name, SIGNED))
        if name.startswith("build-"):
            if signed_job(job):
                bad.append("%s, job %s: is not reached in a dry run, so a dry run would build nothing of it" % (label, name))
            if not any(str(s.get("uses", "")).startswith("actions/upload-artifact@") and not signed_step(job, s)
                       for s in steps_of(job)):
                bad.append("%s, job %s: keeps no artifact in a dry run" % (label, name))

    calls = [(n, j) for n, j in jobs.items() if isinstance(j, dict) and j.get("uses") == GATE_USES]
    if len(calls) != 1:
        bad.append("%s: exactly one job calls %s" % (label, GATE_USES))
    for name, job in calls:
        w = job.get("with") or {}
        if w.get("dry") != "${{ needs.plan.outputs.signed != 'true' }}":
            bad.append("%s, job %s: must call the gate dry whenever the run does not sign (dry: ${{ needs.plan.outputs.signed != 'true' }})" % (label, name))
        if w.get("mode") != "release" or "if" in job:
            bad.append("%s, job %s: the gate is called as a release and in every run, a dry one for its skipped report" % (label, name))
    return bad + secret_scan(doc, label)


def check_gate(doc):
    bad = []
    label = "gate.yml"
    on = triggers(doc)
    push = on.get("push") if isinstance(on.get("push"), dict) else {}
    if push.get("branches") != ["**", "!rust/**"]:
        bad.append("%s: a push starts the gate on every branch but those under rust/ (branches: '**', then '!rust/**')" % label)
    if push.get("paths") != GATE_PATHS:
        bad.append("%s: the push trigger's paths are the pin's and the gate's own: %s" % (label, GATE_PATHS))
    dry = ((on.get("workflow_call") or {}).get("inputs") or {}).get("dry")
    if not isinstance(dry, dict) or dry.get("type") != "boolean" or dry.get("default") is not False:
        bad.append("%s: a caller's input dry must be a boolean that defaults to false" % label)
    jobs = doc.get("jobs") if isinstance(doc.get("jobs"), dict) else {}
    resolve = jobs.get("resolve") if isinstance(jobs.get("resolve"), dict) else {}
    r = [s for s in steps_of(resolve) if s.get("id") == "r"]
    if len(r) != 1 or (r[0].get("env") or {}).get("GATE_CALL_DRY") != "${{ inputs.dry }}":
        bad.append("%s: the resolve step must hand the caller's dry to scripts/gate-resolve.sh (GATE_CALL_DRY)" % label)
    for key in ("skip", "dry", "dry_reason"):
        if (resolve.get("outputs") or {}).get(key) != "${{ steps.r.outputs.%s }}" % key:
            bad.append("%s: resolve's output %s must be the resolve step's own" % (label, key))
    for name, job in jobs.items():
        if not isinstance(job, dict):
            continue
        if not any(True for path, text in walk(job) for _ in secret_reads(path, text)):
            continue
        terms = conjuncts(unwrap(job["if"])) if "if" in job else None
        missing = [g for g in LEG_GUARDS if terms is None or g not in terms]
        if missing or "resolve" not in needs_of(job):
            bad.append("%s, job %s: holds a secret, so its if must ask for each of %s (missing: %s)" % (
                label, name, list(LEG_GUARDS), missing or "needs: resolve"))
    verdict = jobs.get("verdict") if isinstance(jobs.get("verdict"), dict) else {}
    skipped = [s for s in steps_of(verdict) if s.get("id") == "skipped"]
    want = "${{ needs.resolve.outputs.dry == 'true' && needs.resolve.outputs.dry_reason || inputs.skip_reason }}"
    if (len(skipped) != 1 or (skipped[0].get("env") or {}).get("REASON") != want
            or unwrap(skipped[0].get("if")) != "needs.resolve.outputs.skip == 'true'"):
        bad.append("%s: the verdict's skipped report must take a dry run's fixed reason (REASON: %s)" % (label, want))
    return bad


def check_dry_workflow(doc):
    bad = []
    label = "release-dry.yml"
    if doc.get("permissions") != {"contents": "read"}:
        bad.append("%s: its permissions must be contents: read and nothing else" % label)
    for name, job in (doc.get("jobs") or {}).items():
        if isinstance(job, dict) and wide(job.get("permissions")):
            bad.append("%s, job %s: has more than read permissions" % (label, name))
    for path, text in walk(doc):
        if any(True for _ in secret_reads(path, text)):
            bad.append("%s, %s: reads a secret" % (label, where(doc, path)))
    return bad


class FakeGitHub:
    """api.github.com on 127.0.0.1: exact path (query included) -> (status, body)."""

    def __init__(self, routes):
        self.routes = routes
        self.seen = []
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - http.server's name
                fake.seen.append(self.path)
                status, body = fake.routes.get(self.path, (404, {"message": "Not Found"}))
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]


def run(script, *args, env, cwd):
    base = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": cwd, "LANG": "C.UTF-8",
            "NO_PROXY": "*", "no_proxy": "*", "GITHUB_API_URL": "http://127.0.0.1:9"}
    base.update({k: v for k, v in env.items() if v is not None})
    return subprocess.run(["bash", script, *args], env=base, capture_output=True, text=True, timeout=120, cwd=cwd)


def outputs(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def check_decide(scripts):
    """scripts/release-guard.sh decide: when a run may sign."""
    guard = os.path.join(scripts, "release-guard.sh")
    if not os.path.isfile(guard):
        return ["scripts/release-guard.sh is missing: nothing decides whether a run signs"]
    bad = []
    this, other = "a" * 40, "b" * 40
    main, topic = "refs/heads/main", "refs/heads/rust/all"
    # what, ref, commit, dry_run, signed_head, play_track -> signed (None: the run is refused), track
    cases = (
        ("main with today's inputs signs", main, this, "false", "", "internal", "true", "internal"),
        ("main keeps the track it was asked for", main, this, "false", "", "production", "true", "production"),
        ("main keeps a track of none", main, this, "false", "", "none", "true", "none"),
        ("main with dry_run is dry and its track is none", main, this, "true", "", "internal", "false", "none"),
        ("off main is dry", topic, this, "false", "", "internal", "false", "none"),
        ("off main is dry on a topic branch", "refs/heads/rust/t/x", this, "false", "", "production", "false", "none"),
        ("off main signs when signed_head is the commit", topic, this, "false", this, "internal", "true", "internal"),
        ("dry_run wins over signed_head", topic, this, "true", this, "internal", "false", "none"),
        ("signed_head for another commit refuses the run", topic, this, "false", other, "internal", None, None),
        ("signed_head for another commit refuses on main too", main, this, "false", other, "internal", None, None),
        ("a short signed_head refuses the run", topic, this, "false", this[:12], "internal", None, None),
        ("an upper-case signed_head refuses the run", topic, this, "false", this.upper(), "internal", None, None),
        ("a tag is off main", "refs/tags/v1.0.1", this, "false", "", "internal", "false", "none"),
        ("a branch that starts with main is off main", "refs/heads/main-2", this, "false", "", "internal", "false", "none"),
        ("a branch that ends with main is off main", "refs/heads/x/main", this, "false", "", "internal", "false", "none"),
        ("a pull request's ref is off main", "refs/pull/1/merge", this, "false", "", "internal", "false", "none"),
        ("no commit refuses the run", main, "", "false", "", "internal", None, None),
        ("no ref refuses the run", "", this, "false", "", "internal", None, None),
        ("an empty dry_run refuses the run", main, this, "", "", "internal", None, None),
        ("a dry_run that is neither true nor false refuses the run", main, this, "yes", "", "internal", None, None),
        ("an unknown track refuses the run", main, this, "false", "", "beta", None, None),
    )
    with tempfile.TemporaryDirectory() as tmp:
        for what, ref, sha, dry_run, head, track, signed, want_track in cases:
            r = run(guard, "decide", cwd=tmp, env={
                "GUARD_REF": ref, "GUARD_SHA": sha, "GUARD_DRY_RUN": dry_run, "GUARD_SIGNED_HEAD": head,
                "GUARD_PLAY_TRACK": track})
            out = outputs(r.stdout)
            if signed is None:
                if r.returncode == 0 or r.stdout.strip():
                    bad.append("release-guard.sh decide: %s (exit %d, wrote %r)" % (what, r.returncode, r.stdout))
            elif r.returncode != 0 or out.get("signed") != signed or out.get("play_track") != want_track:
                bad.append("release-guard.sh decide: %s (exit %d, wrote %r, said %r)" % (
                    what, r.returncode, r.stdout, r.stderr.strip()))
    return bad


def check_pin(scripts):
    """scripts/release-guard.sh pin: a run that signs builds only what the store line holds."""
    guard = os.path.join(scripts, "release-guard.sh")
    if not os.path.isfile(guard):
        return []
    bad = []
    pin = "c0de" * 10
    with tempfile.TemporaryDirectory() as tmp:
        box = os.path.join(tmp, "scripts")
        os.mkdir(box)
        for name in ("release-guard.sh", "gate-api.sh"):
            shutil.copy2(os.path.join(scripts, name), os.path.join(box, name))
        with open(os.path.join(box, "ghostlane-core-pin.sh"), "w") as f:
            f.write('GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-fake-owner/fake-core}"\n' + "GHOSTLANE_CORE_COMMIT=" + pin + "\n")
        route = "/repos/fake-owner/fake-core/compare/%s...ghostlane" % pin
        # what, GUARD_SIGNED, the comparison GitHub answers (None: 404) -> exit
        cases = (
            ("a pin that is the store line's head passes", "true", {"status": "identical"}, 0),
            ("a pin the store line is ahead of passes", "true", {"status": "ahead", "ahead_by": 3}, 0),
            ("a pin ahead of the store line refuses a run that signs", "true", {"status": "behind", "behind_by": 2}, 1),
            ("a pin beside the store line refuses a run that signs", "true", {"status": "diverged"}, 1),
            ("a comparison GitHub does not give refuses a run that signs", "true", None, 1),
            ("an answer without a status refuses a run that signs", "true", {}, 1),
            ("a dry run only reports a pin off the store line", "false", {"status": "diverged"}, 0),
            ("a dry run only reports a comparison GitHub does not give", "false", None, 0),
            ("a run that was not told it signs only reports", "", {"status": "behind"}, 0),
        )
        for what, signed, answer, want in cases:
            with FakeGitHub({route: (200, answer)} if answer is not None else {}) as gh:
                r = run(os.path.join(box, "release-guard.sh"), "pin", cwd=tmp, env={
                    "GITHUB_API_URL": gh.url, "GUARD_SIGNED": signed, "GH_TOKEN": "fake-token",
                    "GHOSTLANE_CORE_REPO": "another-owner/another-core"})
                if r.returncode != want:
                    bad.append("release-guard.sh pin: %s (exit %d, said %r)" % (what, r.returncode, r.stderr.strip()))
                if gh.seen != [route]:
                    bad.append("release-guard.sh pin: %s: it must ask GitHub for the pin file's commit against `ghostlane` and nothing else (asked %r)" % (what, gh.seen))
                if want == 1 and "ghostlane" not in r.stderr:
                    bad.append("release-guard.sh pin: %s: the refusal must name the branch" % what)
                if "fake-token" in r.stdout + r.stderr:
                    bad.append("release-guard.sh pin: %s: the token is printed" % what)
    return bad


def check_resolve(scripts):
    """scripts/gate-resolve.sh: which runs of the gate walk no leg."""
    bad = []
    rev = "0123456789ab"
    sha = rev + "c" * 28
    engine = "/repos/ghostlane-project/olcrtc"
    with tempfile.TemporaryDirectory() as tmp:
        box = os.path.join(tmp, "scripts")
        os.mkdir(box)
        for name in ("olcrtc-pin.sh", "gate-api.sh", "gate-resolve.sh", "gate-run.sh"):
            shutil.copy2(os.path.join(scripts, name), os.path.join(box, name))
        with open(os.path.join(box, "cores-pins.sh"), "w") as f:
            f.write('GO_VERSION="${GO_VERSION:-1.99.9}"\nOLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20260102030405-%s}"\n' % rev)
        paths = run(os.path.join(box, "gate-run.sh"), "paths", cwd=tmp, env={}).stdout.split()
        if not paths:
            return ["gate-run.sh paths names nothing, so the offline engine cannot be made up"]
        pin_only = {
            "%s/commits/%s" % (engine, rev): (200, {"sha": sha, "commit": {"committer": {"date": "2026-01-02T03:04:05Z"}}}),
            "%s/compare/%s...proofkit" % (engine, sha): (200, {"status": "ahead", "ahead_by": 2}),
        }
        with_gate = dict(pin_only)
        for path in paths:
            with_gate["%s/contents/%s?ref=%s" % (engine, path, sha)] = (200, [])
        release = {"GATE_EVENT": "workflow_dispatch", "GATE_CALL_MODE": "release", "GATE_CALL_ENGINE_SHA": sha,
                   "GATE_CALL_APP_VERSION": "1.0.999", "GATE_CALL_SKIP": "false"}
        main, topic = "refs/heads/main", "refs/heads/rust/all"
        live = "jitsi,salutejazz,vkcalls,mtslink"
        # what, routes, env -> dry, skip
        cases = (
            ("a push to main walks the legs", with_gate, {"GATE_EVENT": "push", "GITHUB_REF": main}, "false", "false"),
            ("the daily run walks the legs", with_gate, {"GATE_EVENT": "schedule", "GITHUB_REF": main}, "false", "false"),
            ("a manual run on main walks the legs", with_gate, {"GATE_EVENT": "workflow_dispatch", "GITHUB_REF": main}, "false", "false"),
            ("a release that signs walks the legs", with_gate, dict(release, GATE_CALL_DRY="false", GITHUB_REF=main), "false", "false"),
            ("a release that signs off main walks the legs", with_gate, dict(release, GATE_CALL_DRY="false", GITHUB_REF=topic), "false", "false"),
            ("a branch that only starts with rust is no branch under rust/", with_gate,
             {"GATE_EVENT": "push", "GITHUB_REF": "refs/heads/rustacean"}, "false", "false"),
            ("a push to rust/all walks no leg", pin_only, {"GATE_EVENT": "push", "GITHUB_REF": topic}, "true", "true"),
            ("a push to a topic under rust/ walks no leg", pin_only,
             {"GATE_EVENT": "push", "GITHUB_REF": "refs/heads/rust/t/R0-dry-runs"}, "true", "true"),
            ("a manual run under rust/ walks no leg", pin_only,
             {"GATE_EVENT": "workflow_dispatch", "GITHUB_REF": topic, "GATE_PROVIDERS": "vkcalls"}, "true", "true"),
            ("a dry release walks no leg", pin_only, dict(release, GATE_CALL_DRY="true", GITHUB_REF=topic), "true", "true"),
            ("a dry release on main walks no leg", pin_only, dict(release, GATE_CALL_DRY="true", GITHUB_REF=main), "true", "true"),
            ("a dry that is not plainly false is dry", pin_only, dict(release, GATE_CALL_DRY="yes", GITHUB_REF=main), "true", "true"),
            ("a break-glass skip is a skip and not a dry run", pin_only,
             dict(release, GATE_CALL_SKIP="true", GATE_CALL_SKIP_REASON="the relay is down", GATE_CALL_DRY="false", GITHUB_REF=main),
             "false", "true"),
        )
        for what, routes, env, dry, skip in cases:
            with FakeGitHub(routes) as gh:
                r = run(os.path.join(box, "gate-resolve.sh"), cwd=tmp, env=dict(
                    env, GITHUB_API_URL=gh.url, GITHUB_REF_NAME=env["GITHUB_REF"].split("/", 2)[2], GITHUB_SHA="1234567" + "0" * 33))
            out = outputs(r.stdout)
            reason = DRY_REASON if dry == "true" else ""
            if r.returncode != 0 or (out.get("dry"), out.get("skip"), out.get("dry_reason")) != (dry, skip, reason):
                bad.append("gate-resolve.sh: %s (exit %d; dry %r, skip %r, dry_reason %r; said %r)" % (
                    what, r.returncode, out.get("dry"), out.get("skip"), out.get("dry_reason"), r.stderr.strip()[-300:]))
            elif skip == "false" and out.get("providers") != live:
                bad.append("gate-resolve.sh: %s: the legs changed (%r)" % (what, out.get("providers")))
    return bad


def check_report(scripts):
    """The skipped report a dry run takes carries the fixed reason, and the verdict passes on it."""
    bad = []
    merge = os.path.join(scripts, "gate-merge-reports.py")
    with tempfile.TemporaryDirectory() as tmp:
        report = os.path.join(tmp, "gate-report.json")
        r = subprocess.run(["python3", "-I", merge, "skipped", "--reason", DRY_REASON, "--engine-commit", "f" * 40,
                            "--engine-ref", "v0.0.0", "--app-version", "1.0.999", "--mode", "release",
                            "--out-json", report, "--out-md", os.path.join(tmp, "gate-report.md")],
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            return ["gate-merge-reports.py skipped failed: %s" % r.stderr.strip()]
        with open(report) as f:
            rep = json.load(f)
        if rep.get("skipped") is not True or rep.get("reason") != DRY_REASON:
            bad.append("the skipped report does not carry the dry run's reason whole: %r" % rep.get("reason"))
        r = subprocess.run(["python3", "-I", merge, "decide", "--report", report, "--skip", "true",
                            "--unit-result", "skipped", "--suite-result", "skipped", "--step", "upload=success"],
                           capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            bad.append("the verdict fails on a dry run's skipped report: %s" % r.stdout.strip())
    return bad


def planted(doc, check, plants):
    """Each plant changes a copy of the document; the check must then say `expect`."""
    bad = []
    for what, plant, expect in plants:
        mutant = copy.deepcopy(doc)
        try:
            plant(mutant)
        except (KeyError, IndexError, TypeError, StopIteration) as e:
            bad.append("self-test: '%s' could not be planted (%s: %s)" % (what, type(e).__name__, e))
            continue
        if not any(expect in line for line in check(mutant)):
            bad.append("self-test: planted '%s' and the check did not say '%s'" % (what, expect))
    return bad


def step_named(job, name):
    return next(s for s in job["steps"] if s.get("name") == name)


def selftest(release, gate, scripts):
    j = lambda d: d["jobs"]  # noqa: E731
    leak = {"name": "A new step", "env": {"KEY": "${{ secrets.NEW_SIGNING_KEY }}"}, "run": "true"}
    release_plants = (
        ("an unguarded secret in a new step of a build job",
         lambda d: j(d)["build-linux"]["steps"].append(dict(leak)), "secrets.NEW_SIGNING_KEY is read where a dry run reaches"),
        ("an unguarded secret in a new job",
         lambda d: j(d).update({"deploy": {"runs-on": "ubuntu-latest", "steps": [dict(leak)]}}), "job deploy, step 'A new step': secrets.NEW_SIGNING_KEY"),
        ("an unguarded secret in a job's env",
         lambda d: j(d)["build-android"]["env"].update({"NEW": "${{ secrets.NEW_STORE_KEY }}"}), "job build-android: secrets.NEW_STORE_KEY"),
        ("an unguarded secret in a step's with",
         lambda d: j(d)["build-linux"]["steps"].append({"uses": "some/action@v1", "with": {"token": "${{ secrets.NEW_TOKEN }}"}}), "secrets.NEW_TOKEN is read"),
        ("an unguarded secret inside a script",
         lambda d: j(d)["build-linux"]["steps"].append({"run": 'curl -H "Authorization: ${{ secrets.NEW_TOKEN }}" https://example.invalid'}), "secrets.NEW_TOKEN is read"),
        ("an unguarded secret in a step's if",
         lambda d: j(d)["build-linux"]["steps"].append({"if": "secrets.NEW_TOKEN != ''", "run": "true"}), "secrets.NEW_TOKEN is read"),
        ("a secret in the workflow's own env",
         lambda d: d["env"].update({"NEW": "${{ secrets.NEW_TOKEN }}"}), "the workflow's own env: secrets.NEW_TOKEN is read outside any job"),
        ("a guard with a way round it",
         lambda d: j(d)["build-linux"]["steps"].append(dict(leak, **{"if": SIGNED + " || always()"})), "secrets.NEW_SIGNING_KEY is read where a dry run reaches"),
        ("a guard inside a bracket",
         lambda d: j(d)["build-linux"]["steps"].append(dict(leak, **{"if": "always() && (" + SIGNED + " || failure())"})), "secrets.NEW_SIGNING_KEY is read where a dry run reaches"),
        ("a guard compared with true and not with 'true'",
         lambda d: j(d)["build-linux"]["steps"].append(dict(leak, **{"if": "needs.plan.outputs.signed == true"})), "compare needs.plan.outputs.signed with 'true'"),
        ("a guard that is the bare output",
         lambda d: j(d)["build-linux"]["steps"].append(dict(leak, **{"if": "needs.plan.outputs.signed"})), "compare needs.plan.outputs.signed with 'true'"),
        ("a guarded value that falls back to the secret",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"K": "${{ %s && secrets.NEW || secrets.NEW }}" % SIGNED}}), "secrets.NEW is read where a dry run reaches"),
        ("the whole secrets context",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"ALL": "${{ toJSON(secrets) }}"}}), "reads the secrets context whole"),
        ("a secret by index",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"K": "${{ secrets['NEW'] }}"}}), "reads the secrets context whole"),
        ("a secret spelled in another case",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"K": "${{ SECRETS.NEW }}"}}), "secrets.NEW is read where a dry run reaches"),
        ("a secret behind a brace in a quoted text",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"K": "${{ format('}}{0}', secrets.NEW) }}"}}), "secrets.NEW is read where a dry run reaches"),
        ("a guarded value in another case, which is not the guard",
         lambda d: j(d)["build-linux"]["steps"].append({"run": "true", "env": {"K": "${{ %s && Secrets.NEW || '' }}" % SIGNED}}), "secrets.NEW is read where a dry run reaches"),
        ("secrets: inherit",
         lambda d: j(d)["gate"].update({"secrets": "inherit"}), "hands every secret to another workflow"),
        ("a room handed to another job",
         lambda d: j(d)["build-linux"].update({"env": {"ROOM": "${{ secrets.GATE_VKCALLS_ROOMS }}"}}), "job build-linux: secrets.GATE_VKCALLS_ROOMS"),
        ("a job with write permissions and no guard",
         lambda d: j(d).update({"tag": {"runs-on": "ubuntu-latest", "needs": ["plan"], "permissions": {"contents": "write"}, "steps": [{"run": "true"}]}}),
         "job tag: has more than read permissions"),
        ("a publish job without the guard",
         lambda d: j(d)["publish-nightly"].update({"if": "needs.plan.outputs.publish == 'true'"}), "job publish-nightly: has more than read permissions"),
        ("a guarded job that does not need plan",
         lambda d: j(d).update({"tag": {"runs-on": "ubuntu-latest", "if": SIGNED, "permissions": {"contents": "write"}, "steps": [{"run": "true"}]}}),
         "job tag: names plan's outputs without needing plan"),
        ("wider permissions for the whole workflow",
         lambda d: d.update({"permissions": {"contents": "write"}}), "the workflow's own permissions"),
        ("a gate that is not told the run is dry",
         lambda d: j(d)["gate"]["with"].pop("dry"), "must call the gate dry"),
        ("a gate told dry the wrong way round",
         lambda d: j(d)["gate"]["with"].update({"dry": "${{ needs.plan.outputs.signed == 'true' }}"}), "must call the gate dry"),
        ("a Play upload on the input's track",
         lambda d: next(s for s in j(d)["build-android"]["steps"] if s.get("id") == "play_upload").update(
             {"if": SIGNED + " && env.PLAY_SERVICE_ACCOUNT_JSON != '' && inputs.play_track != 'none'"}),
         "the Play track is the guard's"),
        ("a Play upload without the guard",
         lambda d: next(s for s in j(d)["build-android"]["steps"] if s.get("id") == "play_upload").update(
             {"if": "env.PLAY_SERVICE_ACCOUNT_JSON != '' && needs.plan.outputs.play_track != 'none'"}),
         "the upload to Google Play must ask"),
        ("a second trigger",
         lambda d: d[True if True in d else "on"].update({"push": {"branches": ["main"]}}), "must start by workflow_dispatch alone"),
        ("dry_run that defaults to true",
         lambda d: d[True if True in d else "on"]["workflow_dispatch"]["inputs"]["dry_run"].update({"default": True}), "the input dry_run"),
        ("a guard step that may fail quietly",
         lambda d: next(s for s in j(d)["plan"]["steps"] if s.get("id") == "guard").update({"continue-on-error": True}), "the guard step must run always"),
        ("a pin step that may fail quietly",
         lambda d: next(s for s in j(d)["plan"]["steps"] if "release-guard.sh pin" in str(s.get("run"))).update({"continue-on-error": True}),
         "the pin step must run always"),
        ("a signed output from another step",
         lambda d: j(d)["plan"]["outputs"].update({"signed": "${{ steps.p.outputs.signed }}"}), "plan's output signed"),
        ("a build job that only a signed run reaches",
         lambda d: j(d)["build-linux"].update({"if": SIGNED + " && needs.plan.outputs.linux == 'true'"}), "is not reached in a dry run"),
        ("a build of another commit",
         lambda d: j(d)["build-linux"]["steps"][0]["with"].update({"ref": "main"}), "checks out another commit"),
    )
    gate_plants = (
        ("a push trigger without the rust/ exclusion",
         lambda d: d[True if True in d else "on"]["push"].update({"branches": ["**"]}), "a push starts the gate on every branch but those under rust/"),
        ("the exclusion before the match",
         lambda d: d[True if True in d else "on"]["push"].update({"branches": ["!rust/**", "**"]}), "a push starts the gate on every branch but those under rust/"),
        ("legs that do not ask the caller",
         lambda d: j(d)["suite"].update({"if": " && ".join(g for g in LEG_GUARDS if g != "!inputs.dry")}), "job suite: holds a secret"),
        ("legs that do not ask the ref",
         lambda d: j(d)["suite"].update({"if": " && ".join(LEG_GUARDS[:2])}), "job suite: holds a secret"),
        ("legs that run when resolve skipped or anything else holds",
         lambda d: j(d)["suite"].update({"if": " && ".join(LEG_GUARDS) + " || always()"}), "job suite: holds a secret"),
        ("a new job that holds a room",
         lambda d: j(d).update({"probe": {"runs-on": "ubuntu-latest", "steps": [{"run": "true", "env": {"R": "${{ secrets.GATE_VKCALLS_ROOMS }}"}}]}}),
         "job probe: holds a secret"),
        ("a dry input that defaults to true",
         lambda d: d[True if True in d else "on"]["workflow_call"]["inputs"]["dry"].update({"default": True}), "a caller's input dry"),
        ("a resolve that is not told",
         lambda d: next(s for s in j(d)["resolve"]["steps"] if s.get("id") == "r")["env"].pop("GATE_CALL_DRY"), "must hand the caller's dry"),
        ("a skipped report with the caller's reason only",
         lambda d: next(s for s in j(d)["verdict"]["steps"] if s.get("id") == "skipped")["env"].update({"REASON": "${{ inputs.skip_reason }}"}),
         "must take a dry run's fixed reason"),
    )
    bad = planted(release, check_release, release_plants) + planted(gate, check_gate, gate_plants)

    # The two scripts too: with the rule cut out of a copy, the check must go red.
    cuts = (
        ("gate-resolve.sh", "refs/heads/rust/*) dry=true ;;", "refs/heads/rust/*) ;;", check_resolve, "walks no leg"),
        ("release-guard.sh", '[ "${ref}" = refs/heads/main ]', '[ -n "${ref}" ]', check_decide, "off main is dry"),
    )
    for name, old, new, check, expect in cuts:
        with tempfile.TemporaryDirectory() as tmp:
            box = os.path.join(tmp, "scripts")
            shutil.copytree(scripts, box, ignore=shutil.ignore_patterns("patches", "play", "*.png", "macos-helper-check", "android-hev-restart"))
            path = os.path.join(box, name)
            with open(path) as f:
                text = f.read()
            if old not in text:
                bad.append("self-test: %s no longer holds the line '%s'; plant the cut where the rule now is" % (name, old))
                continue
            with open(path, "w") as f:
                f.write(text.replace(old, new))
            if not any(expect in line for line in check(box)):
                bad.append("self-test: cut the rule out of %s and the check did not say '%s'" % (name, expect))
    return bad


def load(rel, bad):
    path = os.path.join(ROOT, rel)
    try:
        with open(path, encoding="utf-8") as f:
            doc = yaml.safe_load(f)
    except OSError:
        bad.append("%s is missing" % rel)
        return None
    except yaml.YAMLError as e:
        bad.append("%s is not YAML: %s" % (rel, e))
        return None
    if not isinstance(doc, dict):
        bad.append("%s is not a workflow" % rel)
        return None
    return doc


bad = []
release = load(RELEASE, bad)
gate = load(GATE, bad)
dry_workflow = load(DRY_WORKFLOW, bad)
if release is not None:
    bad += check_release(release)
if gate is not None:
    bad += check_gate(gate)
if dry_workflow is not None:
    bad += check_dry_workflow(dry_workflow)
bad += check_decide(SCRIPTS)
bad += check_pin(SCRIPTS)
bad += check_resolve(SCRIPTS)
bad += check_report(SCRIPTS)
if not bad:
    bad += selftest(release, gate, SCRIPTS)
bad = list(dict.fromkeys(bad))
for line in bad:
    print("FAIL " + line)
if bad:
    print("%d failure(s)" % len(bad))
    sys.exit(1)
print("ok   a dry run reads no signing, store or publish secret, has no write permission, uploads to no Play track and calls the gate dry")
print("ok   the gate walks no leg in a dry run or on a branch under rust/, and a push there starts none")
print("ok   off main a run is dry unless signed_head is its commit; a run that signs refuses a core pin off `ghostlane`")
print("ok   every planted violation was found")
PY
