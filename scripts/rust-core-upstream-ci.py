#!/usr/bin/env python3
"""Runs jobs of xray-rust's own CI (.github/workflows/ci.yml at the pin) on a
checkout of our fork, step by step, as GitHub would run them.

Our fork runs no CI of its own: upstream's ci.yml triggers on upstream's main,
tags and pull requests, never on the fork's branches. So .github/workflows/
rust-core.yml runs the jobs that check the core on a pull request upstream
(rust, go-oracles, supply-chain, secrets) through this driver, reading the
steps from the pinned commit's own ci.yml: a pin that brings new steps runs
them, and no copy of upstream's commands lives here to drift.

What it understands, and refuses anything else (so a step it cannot run is a
red job, never a silent pass):
  - `run:` steps, with the workflow's, the job's and the step's `env`, the
    default shell (`bash -e`) or `shell: bash`, `working-directory`, and the
    files a step writes to $GITHUB_ENV and $GITHUB_PATH;
  - actions/checkout of the repository itself (already checked out at the
    pin; `fetch-depth: 0` fetches every branch and tag, as the action does)
    and of another repository at a full commit, into its `path`;
  - actions/setup-go: Go must already be the version it names (the workflow
    installs it, from `go-version` below, before the driver runs);
  - actions/upload-artifact: not uploaded, but its files must exist when it
    says `if-no-files-found: error`;
  - `if: always()`, and `if: github.event_name == 'schedule'` (skipped: we
    are never upstream's schedule); the job-level condition upstream puts on
    tag pushes (we are never a tag push).
A `${{ }}` expression anywhere in what it would run is refused.

Steps can be skipped only by name, with a reason, from a skips file
(scripts/rust-core-upstream-skips.txt); a skip that names no step is an error,
so a renamed step is never skipped by accident.

  rust-core-upstream-ci.py plan <ci.yml> <job>...
  rust-core-upstream-ci.py go-version <ci.yml> <job>
  rust-core-upstream-ci.py run <ci.yml> <job> --workspace <dir> [--skips <file>]
  rust-core-upstream-ci.py selftest

`run` prints each step in a log group, and writes a table of the steps, their
results and times to $GITHUB_STEP_SUMMARY when that is set. Python 3.9+, with
PyYAML or, failing that, mikefarah's yq (both on GitHub's Ubuntu runners).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

TAG_PUSH_GUARD = "github.event_name != 'push' || github.ref_type != 'tag' || contains(github.ref_name, '-rc.')"
SCHEDULE_ONLY = "github.event_name == 'schedule'"
COMMIT = re.compile(r"^[0-9a-f]{40}$")
STEP_KEYS = {"name", "run", "uses", "with", "env", "if", "id", "shell", "working-directory", "timeout-minutes"}
JOB_KEYS = {"name", "if", "runs-on", "timeout-minutes", "env", "steps", "permissions", "needs"}


class Refused(Exception):
    """A construct the driver does not run."""


def load(path):
    try:
        import yaml  # PyYAML
    except ImportError:
        out = subprocess.run(["yq", "-o=json", ".", path], check=True, capture_output=True, text=True).stdout
        return json.loads(out)
    with open(path) as f:
        return yaml.safe_load(f)


def no_expression(where, value):
    text = json.dumps(value) if not isinstance(value, str) else value
    if "${{" in text:
        raise Refused(f"{where} holds a ${{{{ }}}} expression the driver cannot evaluate")


def step_name(step, index):
    if "name" in step:
        return str(step["name"])
    if "uses" in step:
        return str(step["uses"]).split("@")[0]
    return "step %d" % (index + 1)


def parse_skips(path, job):
    """{step name: reason} for <job> from lines `<job> | <step name> | <reason>`."""
    skips = {}
    if not path:
        return skips
    with open(path) as f:
        for number, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split("|")]
            if len(parts) != 3 or not all(parts):
                raise Refused(f"{path}:{number}: want `<job> | <step name> | <reason>`")
            if parts[0] == job:
                skips[parts[1]] = parts[2]
    return skips


def plan_job(workflow, job, skips):
    """[(name, action, detail, always)] for every step, or Refused."""
    jobs = workflow.get("jobs") or {}
    if job not in jobs:
        raise Refused(f"ci.yml has no job {job!r} (it has {', '.join(sorted(jobs))})")
    spec = jobs[job]
    extra = set(spec) - JOB_KEYS
    if extra:
        raise Refused(f"job {job}: keys the driver does not run: {sorted(extra)}")
    condition = spec.get("if")
    if condition is not None and str(condition).strip() != TAG_PUSH_GUARD:
        raise Refused(f"job {job}: condition {condition!r} is not one the driver knows")
    if not str(spec.get("runs-on", "")).startswith("ubuntu-"):
        raise Refused(f"job {job}: runs on {spec.get('runs-on')!r}, the driver runs Ubuntu jobs only")
    no_expression(f"job {job} env", spec.get("env") or {})
    steps = []
    names = set()
    for index, step in enumerate(spec.get("steps") or []):
        name = step_name(step, index)
        names.add(name)
        where = f"job {job}, step {name!r}"
        extra = set(step) - STEP_KEYS
        if extra:
            raise Refused(f"{where}: keys the driver does not run: {sorted(extra)}")
        always = False
        if name in skips:
            steps.append((name, "skip", skips[name], False))
            continue
        if "if" in step:
            condition = str(step["if"]).strip()
            if condition == "always()":
                always = True
            elif condition == SCHEDULE_ONLY:
                steps.append((name, "skip", "upstream runs it on its weekly schedule only", False))
                continue
            else:
                raise Refused(f"{where}: condition {condition!r} is not one the driver knows")
        with_ = step.get("with") or {}
        no_expression(where, {k: v for k, v in step.items() if k != "name"})
        if "uses" in step and "run" in step:
            raise Refused(f"{where}: both uses and run")
        if "run" in step:
            shell = step.get("shell")
            if shell not in (None, "bash"):
                raise Refused(f"{where}: shell {shell!r}")
            steps.append((name, "run", step, always))
            continue
        action = str(step.get("uses", "")).split("@")[0]
        if action == "actions/checkout":
            allowed = {"persist-credentials", "fetch-depth", "repository", "ref", "path"}
            if set(with_) - allowed:
                raise Refused(f"{where}: checkout inputs {sorted(set(with_) - allowed)}")
            if "repository" in with_:
                ref, path = str(with_.get("ref", "")), str(with_.get("path", ""))
                if not COMMIT.match(ref) or not path or path.startswith("/") or ".." in path.split("/"):
                    raise Refused(f"{where}: another repository must be checked out at a full commit into a relative path")
                steps.append((name, "checkout-other", {"repository": with_["repository"], "ref": ref, "path": path}, always))
            else:
                depth = with_.get("fetch-depth", 1)
                if depth not in (0, 1):
                    raise Refused(f"{where}: fetch-depth {depth!r}")
                steps.append((name, "checkout-self", {"fetch-depth": depth}, always))
        elif action == "actions/setup-go":
            allowed = {"go-version", "cache-dependency-path", "cache"}
            if set(with_) - allowed or "go-version" not in with_:
                raise Refused(f"{where}: setup-go inputs {sorted(with_)}")
            steps.append((name, "setup-go", {"go-version": str(with_["go-version"])}, always))
        elif action == "actions/upload-artifact":
            paths = [p.strip() for p in str(with_.get("path", "")).splitlines() if p.strip()]
            if not paths:
                raise Refused(f"{where}: upload-artifact without a path")
            steps.append((name, "upload", {"paths": paths, "required": with_.get("if-no-files-found", "warn") == "error"}, always))
        else:
            raise Refused(f"{where}: the action {step.get('uses')!r} is not one the driver runs")
    unknown = set(skips) - names
    if unknown:
        raise Refused(f"job {job}: the skips name steps it does not have: {sorted(unknown)}")
    return steps


def describe(action, detail):
    if action == "run":
        return "run"
    if action == "skip":
        return "skipped: " + detail
    if action == "checkout-self":
        return "the pinned checkout" + (", with every branch and tag" if detail["fetch-depth"] == 0 else "")
    if action == "checkout-other":
        return "check out %s at %s into %s" % (detail["repository"], detail["ref"][:12], detail["path"])
    if action == "setup-go":
        return "Go %s (installed by the workflow)" % detail["go-version"]
    if action == "upload":
        return "not uploaded; %s present" % ("must be" if detail["required"] else "may be")
    return action


def git(*args, cwd=None):
    subprocess.run(["git", *args], cwd=cwd, check=True)


def read_env_file(path):
    """KEY=VALUE and KEY<<DELIMITER lines, as GitHub reads $GITHUB_ENV."""
    found = {}
    if not os.path.exists(path):
        return found
    with open(path) as f:
        lines = f.read().split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        i += 1
        if not line:
            continue
        if "<<" in line and ("=" not in line or line.index("<<") < line.index("=")):
            key, delimiter = line.split("<<", 1)
            value = []
            while i < len(lines) and lines[i] != delimiter:
                value.append(lines[i])
                i += 1
            i += 1
            found[key] = "\n".join(value)
        elif "=" in line:
            key, value = line.split("=", 1)
            found[key] = value
    return found


def run_step(name, action, detail, workspace, env, scratch):
    """Runs one step; returns True on success. May update env."""
    if action == "checkout-self":
        if detail["fetch-depth"] == 0:
            shallow = subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=workspace,
                                     capture_output=True, text=True, check=True).stdout.strip() == "true"
            head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=workspace, capture_output=True, text=True, check=True).stdout.strip()
            git("fetch", "--prune", "--quiet", *(["--unshallow"] if shallow else []), "origin",
                "+refs/heads/*:refs/remotes/origin/*", "+refs/tags/*:refs/tags/*", cwd=workspace)
            after = subprocess.run(["git", "rev-parse", "HEAD"], cwd=workspace, capture_output=True, text=True, check=True).stdout.strip()
            if after != head:
                print(f"HEAD moved from {head} to {after}")
                return False
            print(f"every branch and tag of origin fetched; HEAD still {head}")
        else:
            print("already checked out at the pin")
        return True
    if action == "checkout-other":
        path = os.path.join(workspace, detail["path"])
        shutil.rmtree(path, ignore_errors=True)
        os.makedirs(path)
        git("init", "--quiet", cwd=path)
        git("fetch", "--quiet", "--depth", "1", "https://github.com/%s.git" % detail["repository"], detail["ref"], cwd=path)
        git("-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", "FETCH_HEAD", cwd=path)
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=path, capture_output=True, text=True, check=True).stdout.strip()
        print(f"{detail['repository']} at {head} in {detail['path']}")
        return head == detail["ref"]
    if action == "setup-go":
        try:
            got = subprocess.run(["go", "env", "GOVERSION"], capture_output=True, text=True, check=True, env=env).stdout.strip()
        except (OSError, subprocess.CalledProcessError) as e:
            print(f"no usable go: {e}")
            return False
        want = "go" + detail["go-version"]
        print(f"go is {got}; the step asks for {want}")
        return got == want
    if action == "upload":
        missing = [p for p in detail["paths"] if not os.path.exists(os.path.join(workspace, p))]
        for p in detail["paths"]:
            print(("missing " if p in missing else "present ") + p)
        return not (missing and detail["required"])
    # run
    step = detail
    step_env = dict(env)
    step_env.update({k: str(v) for k, v in (step.get("env") or {}).items()})
    env_file = os.path.join(scratch, "env")
    path_file = os.path.join(scratch, "path")
    for f in (env_file, path_file):
        open(f, "w").close()
    step_env["GITHUB_ENV"], step_env["GITHUB_PATH"] = env_file, path_file
    step_env["GITHUB_OUTPUT"] = os.path.join(scratch, "output")
    script = os.path.join(scratch, "step.sh")
    with open(script, "w") as f:
        f.write(step["run"])
    if step.get("shell") == "bash":
        command = ["bash", "--noprofile", "--norc", "-eo", "pipefail", script]
    else:
        command = ["bash", "-e", script]
    cwd = os.path.join(workspace, step.get("working-directory", "."))
    timeout = step.get("timeout-minutes")
    try:
        code = subprocess.run(command, cwd=cwd, env=step_env, timeout=timeout * 60 if timeout else None).returncode
    except subprocess.TimeoutExpired:
        print(f"timed out after {timeout} minutes")
        return False
    env.update(read_env_file(env_file))
    with open(path_file) as f:
        for line in f.read().splitlines():
            if line.strip():
                env["PATH"] = line.strip() + os.pathsep + env.get("PATH", "")
    if code != 0:
        print(f"exit code {code}")
    return code == 0


def run_job(ci, job, workspace, skips_file, summary=None, annotate=True):
    workflow = load(ci)
    steps = plan_job(workflow, job, parse_skips(skips_file, job))
    workspace = os.path.abspath(workspace)
    env = dict(os.environ)
    no_expression("workflow env", workflow.get("env") or {})
    env.update({k: str(v) for k, v in (workflow.get("env") or {}).items()})
    env.update({k: str(v) for k, v in (workflow["jobs"][job].get("env") or {}).items()})
    env["GITHUB_WORKSPACE"] = workspace
    print(f"upstream job {job}: {len(steps)} steps")
    for name, action, detail, always in steps:
        print(f"  {name}: {describe(action, detail)}" + (" (always)" if always else ""))
    results = []
    failed = False
    for name, action, detail, always in steps:
        if action == "skip":
            print(("::notice::" if annotate else "") + f"{job}: skipped {name!r}: {detail}")
            results.append((name, "skipped", 0.0, detail))
            continue
        if failed and not always:
            results.append((name, "not run", 0.0, "an earlier step failed"))
            continue
        print(f"::group::{name}", flush=True)
        start = time.monotonic()
        with tempfile.TemporaryDirectory() as scratch:
            try:
                ok = run_step(name, action, detail, workspace, env, scratch)
            except subprocess.CalledProcessError as e:
                print(f"{e}")
                ok = False
        seconds = time.monotonic() - start
        sys.stdout.flush()
        print("::endgroup::", flush=True)
        if not ok:
            print(("::error::" if annotate else "") + f"{job}: {name!r} failed", flush=True)
            failed = True
        results.append((name, "passed" if ok else "FAILED", seconds, describe(action, detail)))
    lines = [f"### Upstream CI job `{job}`", "", "| step | result | time | how |", "|---|---|---:|---|"]
    for name, result, seconds, how in results:
        lines.append("| %s | %s | %s | %s |" % (name.replace("|", "\\|"), result,
                                                 "%d s" % seconds if seconds else "", how.replace("|", "\\|")))
    text = "\n".join(lines) + "\n"
    print(text)
    target = summary if summary is not None else os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a") as f:
            f.write(text + "\n")
    return 1 if failed else 0


def selftest():
    """The driver against a workflow of its own: what runs, what is refused."""
    import textwrap
    failures = []

    def check(what, ok):
        print(("ok   " if ok else "FAIL ") + what)
        if not ok:
            failures.append(what)

    with tempfile.TemporaryDirectory() as root:
        ws = os.path.join(root, "ws")
        os.makedirs(ws)
        os.makedirs(os.path.join(ws, "docs"))
        open(os.path.join(ws, "docs", "present.json"), "w").close()
        git("init", "--quiet", cwd=ws)
        ci = os.path.join(root, "ci.yml")
        skips = os.path.join(root, "skips.txt")

        def write(text, skip_lines=""):
            with open(ci, "w") as f:
                f.write(textwrap.dedent(text))
            with open(skips, "w") as f:
                f.write(skip_lines)

        def go(job="j"):
            out = os.path.join(root, "summary.md")
            if os.path.exists(out):
                os.remove(out)
            code = run_job(ci, job, ws, skips, summary=out, annotate=False)
            return code, open(out).read() if os.path.exists(out) else ""

        write("""
            env:
              FROM_WORKFLOW: w
            jobs:
              j:
                runs-on: ubuntu-24.04
                env:
                  FROM_JOB: j
                steps:
                  - uses: actions/checkout@abc
                    with: {persist-credentials: false}
                  - name: writes env and path
                    run: |
                      echo "CARRIED=yes" >> "$GITHUB_ENV"
                      printf 'MULTI<<EOF\\nline one\\nline two\\nEOF\\n' >> "$GITHUB_ENV"
                      mkdir -p "$PWD/bin" && printf '#!/bin/sh\\necho tool\\n' > bin/a-tool && chmod +x bin/a-tool
                      echo "$PWD/bin" >> "$GITHUB_PATH"
                  - name: reads them
                    env:
                      FROM_STEP: s
                    run: |
                      test "$FROM_WORKFLOW$FROM_JOB$FROM_STEP" = wjs
                      test "$CARRIED" = yes
                      test "$MULTI" = "$(printf 'line one\\nline two')"
                      test "$(a-tool)" = tool
                      test "$GITHUB_WORKSPACE" = "$PWD"
                  - name: skipped by the file
                    run: exit 1
                  - name: schedule only
                    if: github.event_name == 'schedule'
                    run: exit 1
                  - name: upload
                    uses: actions/upload-artifact@abc
                    with:
                      name: x
                      path: |
                        docs/present.json
                      if-no-files-found: error
            """, "j | skipped by the file | the test says so\nother | x | y\n")
        code, summary = go()
        check("run steps see the workflow's, job's and step's env, $GITHUB_ENV and $GITHUB_PATH", code == 0)
        check("the summary lists skipped steps with their reasons", "| skipped by the file | skipped | " in summary
              and "the test says so" in summary and "| schedule only | skipped |" in summary)

        write("""
            jobs:
              j:
                runs-on: ubuntu-24.04
                steps:
                  - name: fails
                    run: |
                      false
                      echo never
                  - name: after a failure
                    run: exit 0
                  - name: always
                    if: always()
                    run: exit 0
            """)
        code, summary = go()
        check("a failing step fails the job, `bash -e` stops it at the failing line", code == 1 and "| fails | FAILED |" in summary)
        check("later steps do not run, always() steps do", "| after a failure | not run |" in summary
              and "| always | passed |" in summary)

        write("""
            jobs:
              j:
                runs-on: ubuntu-24.04
                steps:
                  - name: upload
                    uses: actions/upload-artifact@abc
                    with:
                      path: docs/absent.json
                      if-no-files-found: error
            """)
        check("an upload whose required file is missing fails", go()[0] == 1)

        refusals = {
            "an expression": "steps:\n      - run: echo ${{ github.sha }}\n",
            "an unknown action": "steps:\n      - uses: someone/action@v1\n",
            "an unknown condition": "steps:\n      - if: github.ref == 'main'\n        run: true\n",
            "another shell": "steps:\n      - shell: python\n        run: print(1)\n",
            "continue-on-error": "steps:\n      - continue-on-error: true\n        run: true\n",
            "a checkout at a branch": "steps:\n      - uses: actions/checkout@v7\n        with: {repository: a/b, ref: main, path: b}\n",
            "a macOS job": None,
        }
        for what, steps_yaml in refusals.items():
            if steps_yaml is None:
                write("jobs:\n  j:\n    runs-on: macos-15\n    steps:\n      - run: true\n")
            else:
                write("jobs:\n  j:\n    runs-on: ubuntu-24.04\n    " + steps_yaml)
            try:
                go()
                check(f"refuses {what}", False)
            except Refused as e:
                check(f"refuses {what} ({e})", True)
        write("jobs:\n  j:\n    runs-on: ubuntu-24.04\n    steps:\n      - name: a\n        run: true\n",
              "j | renamed step | reason\n")
        try:
            go()
            check("refuses a skip that names no step", False)
        except Refused:
            check("refuses a skip that names no step", True)
        write("jobs:\n  j:\n    if: " + json.dumps(TAG_PUSH_GUARD) + "\n    runs-on: ubuntu-24.04\n    steps:\n"
              "      - uses: actions/setup-go@v7\n        with: {go-version: '1.27.3', cache-dependency-path: go.sum}\n")
        check("go-version reads setup-go's version", go_version(load(ci), "j") == "1.27.3")
    if failures:
        print(f"{len(failures)} case(s) failed")
        return 1
    print("all cases passed")
    return 0


def go_version(workflow, job):
    for name, action, detail, _ in plan_job(workflow, job, {}):
        if action == "setup-go":
            return detail["go-version"]
    return ""


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    plan = sub.add_parser("plan")
    plan.add_argument("ci")
    plan.add_argument("jobs", nargs="+")
    plan.add_argument("--skips")
    gv = sub.add_parser("go-version")
    gv.add_argument("ci")
    gv.add_argument("job")
    run = sub.add_parser("run")
    run.add_argument("ci")
    run.add_argument("job")
    run.add_argument("--workspace", required=True)
    run.add_argument("--skips")
    sub.add_parser("selftest")
    a = p.parse_args()
    try:
        if a.command == "selftest":
            return selftest()
        if a.command == "go-version":
            print(go_version(load(a.ci), a.job))
            return 0
        if a.command == "plan":
            workflow = load(a.ci)
            for job in a.jobs:
                print(f"{job}:")
                for name, action, detail, always in plan_job(workflow, job, parse_skips(a.skips, job)):
                    print(f"  {name}: {describe(action, detail)}" + (" (always)" if always else ""))
            return 0
        return run_job(a.ci, a.job, a.workspace, a.skips)
    except Refused as e:
        print(f"::error::{e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
