#!/usr/bin/env python3
"""Assemble the release gate's verdict from its legs.

    gate-merge-reports.py merge   ...  the legs' reports and plans -> one gate-report.json
    gate-merge-reports.py header  ...  the app's lines above the engine's table (markdown)
    gate-merge-reports.py skipped ...  the report and markdown of a release that skipped the gate
    gate-merge-reports.py rust    ...  add the Rust client's cells (ghostlane-engine on the fakes) to a report
    gate-merge-reports.py decide  ...  exit 1 with every reason the gate is red, 0 when green

gate.yml runs one leg per provider, and each leg runs two go test processes,
the cli flavour (default build) and the mobile flavour (lean build), each with
its own report. The engine writes that report when the test binary exits, so a
go test -timeout or a crash loses it (engine plan, TestMain); what the leg still
has is the plan its dry run printed. merge therefore trusts nothing it did not
see: every planned cell that no valid report accounts for becomes a failed
cell with the reason "did not run: ...", and a flavour that left neither a plan
nor a report becomes one failed marker cell, so zero-skip holds across
processes the way the engine holds it within one (spec section 11).

A report is rejected, and its flavour's planned cells fail, when its schema is
not 1, when it names another engine commit, when its target is not local, or
when a cell in it is repeated, belongs to another leg or flavour, or names its
known issue as anything but text.

Known failures: the engine marks a cell on its known list (internal/gate/known.go,
a bug an open issue tracks) with `known`, the issue's URL. merge keeps it and
counts the failed cells that carry one in `failed_known`, per leg and in all;
`failed` still counts every failed cell. decide fails only on failures that
are not known. A cell merge fails itself ("did not run: ...") never carries
`known`, whatever its id: the engine's docs/gate.md, "Known failures".

The Rust client (decision D12): the shipped ghostlane-engine runs the engine's
suite against the three fake carriers (the fakes script), one report per
carrier, and `rust` folds them into the report as the client `rust`, one leg per
carrier. A carrier that left no usable report becomes a failed marker cell, as
a Go leg does. decide fails a report with no `rust` client unless the gate was
skipped by request; a dry run is not skipped by request, so it needs the
client's cells too, and they are the only cells it has.

Invoked through gate-run.sh merge, which supplies the report's file name (the
engine's name for it lives there and only there). Standard library only.
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

CLIENTS = ("cli", "mobile")
BUILDS = {"cli": "default", "mobile": "lean"}
# Where a leg's files are under --legs-dir. Each leg's artifact (gate.yml)
# carries its provider's directory, and the verdict downloads them all merged
# into one, so a flavour is at <legs-dir>/<provider>/<client>/ however many
# legs uploaded. Unmerged, one artifact alone would lose that directory.
PART_DIR = "{provider}"
PLATFORM = "engine-linux"
# The Rust client: its flavour name in the engine's cell ids, what it is, and
# the fake each carrier's suite runs against (the engine's gate target name).
RUST = "rust"
RUST_BUILD = "ghostlane-engine"
RUST_CARRIERS = {"salutejazz": "fakeroom", "vkcalls": "vkfake", "mtslink": "mtsfake"}
RUST_REPORT = "gate-report.json"
MAX_REASON = 500


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def one_line(text, limit=MAX_REASON):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text[:limit]


def md(text):
    """Text for a markdown table or line: no tags GitHub would swallow, no stray pipes."""
    return (text or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "\\|")


def split_id(cell_id):
    parts = cell_id.split("/")
    if len(parts) != 5 or not all(parts):
        return None
    return dict(zip(("platform", "provider", "transport", "client", "scenario"), parts))


def failed_cell(cell_id, reason):
    fields = split_id(cell_id) or {}
    return {
        "id": cell_id,
        "platform": fields.get("platform", ""),
        "provider": fields.get("provider", ""),
        "transport": fields.get("transport", ""),
        "client": fields.get("client", ""),
        "scenario": fields.get("scenario", ""),
        "status": "fail",
        "metrics": {},
        "thresholds": {},
        "failures": [reason],
        "duration_s": 0,
    }


def read_plan(path):
    if not path.is_file():
        return None
    return [line.strip() for line in path.read_text(errors="replace").splitlines() if line.strip()]


def reject_reason(report, provider, client, expect_commit):
    """Why a leg's report cannot be trusted, or None."""
    if not isinstance(report, dict):
        return "not a JSON object"
    if report.get("schema") != 1:
        return f"schema {report.get('schema')!r}, not 1"
    if report.get("engine_commit") != expect_commit:
        return "it names another engine commit"
    if report.get("target") != "local":
        return f"target {report.get('target')!r}, not local"
    cells = report.get("cells")
    if not isinstance(cells, list):
        return "no cell list"
    seen = set()
    for cell in cells:
        cell_id = cell.get("id") if isinstance(cell, dict) else None
        fields = split_id(cell_id) if isinstance(cell_id, str) else None
        if fields is None:
            return "a cell without a well-formed id"
        if cell_id in seen:
            return "a cell is listed twice"
        seen.add(cell_id)
        if fields["provider"] != provider or fields["client"] != client:
            return "a cell belongs to another leg or flavour"
        if cell.get("status") not in ("pass", "fail"):
            return "a cell ended neither pass nor fail"
        if not isinstance(cell.get("known", ""), str):
            return "a cell's known issue is not text"
    for key in ("planned", "executed", "passed", "failed"):
        if not isinstance(report.get(key), int):
            return f"no {key} count"
    return None


def failed_known(cells):
    """The failed cells a known issue tracks: they fail no gate."""
    return sum(1 for c in cells if c.get("status") != "pass" and c.get("known"))


def failed_text(failed, known):
    """A failed count as the tables show it: 3, or 3 (2 known)."""
    return f"{failed} ({known} known)" if known else f"{failed}"


def merge(args):
    legs_dir = Path(args.legs_dir)
    providers = [p for p in args.providers.split(",") if p]
    cells = {}
    legs = []
    executed = 0
    runner = ""
    started = []
    durations = {}
    platform = PLATFORM

    parts = []
    for provider in providers:
        for client in CLIENTS:
            base = legs_dir / PART_DIR.format(provider=provider) / client
            plan = read_plan(base / "plan.txt")
            path = base / args.report_name
            report = None
            state = "missing"
            reason = ""
            if path.is_file():
                try:
                    report = json.loads(path.read_text())
                except ValueError:
                    report, state, reason = None, "rejected", "it is not JSON"
                else:
                    why = reject_reason(report, provider, client, args.expect_commit)
                    if why:
                        report, state, reason = None, "rejected", why
                    else:
                        state = "present"
            parts.append((provider, client, plan, report, state, reason))
            if report and report["cells"]:
                platform = split_id(report["cells"][0]["id"])["platform"]

    for provider, client, plan, report, state, reason in parts:
        leg = f"{provider}/{client}"
        own = []
        if report is not None:
            own = [dict(c) for c in report["cells"]]
            executed += report["executed"]
            runner = runner or report.get("runner", "")
            if report.get("started_at"):
                started.append(report["started_at"])
            durations[provider] = durations.get(provider, 0) + float(report.get("duration_s") or 0)
            listed = {c["id"] for c in own}
            for cell_id in plan or []:
                if cell_id not in listed:
                    own.append(failed_cell(cell_id, f"did not run: not in the {leg} report"))
        elif plan:
            if state == "rejected":
                why = f"did not run: the {leg} report was rejected ({reason})"
            else:
                why = f"did not run: the {leg} run wrote no report (a timeout, a crash or a missing secret; see the leg's log)"
            own = [failed_cell(cell_id, why) for cell_id in plan]
        else:
            what = f"its report was rejected ({reason})" if state == "rejected" else "it left neither a plan nor a report"
            own = [failed_cell(f"{platform}/{provider}/*/{client}/*", f"did not run: the {leg} run never planned, {what}")]
        for cell in own:
            if cell["id"] in cells:
                # Unreachable through reject_reason; kept so a merge never hides a cell.
                cell = failed_cell(cell["id"], f"cell reported twice across legs ({leg})")
            cells[cell["id"]] = cell
        legs.append({
            "provider": provider,
            "client": client,
            "build": BUILDS[client],
            "plan": len(plan) if plan is not None else None,
            "report": state,
            "reason": reason,
            "planned": len(own),
            "passed": sum(1 for c in own if c.get("status") == "pass"),
            "failed": sum(1 for c in own if c.get("status") != "pass"),
            "failed_known": failed_known(own),
        })

    ordered = [cells[k] for k in sorted(cells)]
    passed = sum(1 for c in ordered if c.get("status") == "pass")
    merged = {
        "schema": 1,
        "engine_commit": args.expect_commit,
        "engine_ref": args.engine_ref,
        "app_version": args.app_version,
        "target": "local",
        "mode": args.mode,
        "runner": runner,
        "started_at": min(started) if started else now(),
        # Legs run side by side, the two flavours of a leg one after the other.
        "duration_s": max(durations.values()) if durations else 0,
        "planned": len(ordered),
        "executed": executed,
        "passed": passed,
        "failed": len(ordered) - passed,
        "failed_known": failed_known(ordered),
        "legs": legs,
        "cells": ordered,
    }
    Path(args.out).write_text(json.dumps(merged, indent=2) + "\n")
    print(f"merged {len(legs)} leg flavour(s): {merged['planned']} cells, "
          f"{failed_text(merged['failed'], merged['failed_known'])} failed, "
          f"{merged['planned'] - merged['executed']} did not run")
    return 0


def rust_reject_reason(report, carrier):
    """Why a fakes run's report cannot be trusted, or None."""
    if not isinstance(report, dict):
        return "not a JSON object"
    if report.get("schema") != 1:
        return f"schema {report.get('schema')!r}, not 1"
    want = RUST_CARRIERS[carrier]
    if report.get("target") != want:
        return f"target {report.get('target')!r}, not {want}"
    cells = report.get("cells")
    if not isinstance(cells, list) or not cells:
        return "no cells"
    seen = set()
    for cell in cells:
        cell_id = cell.get("id") if isinstance(cell, dict) else None
        fields = split_id(cell_id) if isinstance(cell_id, str) else None
        if fields is None:
            return "a cell without a well-formed id"
        if cell_id in seen:
            return "a cell is listed twice"
        seen.add(cell_id)
        if fields["client"] != RUST:
            return f"a cell of the client {fields['client']!r}, not {RUST!r}"
        if cell.get("status") not in ("pass", "fail"):
            return "a cell ended neither pass nor fail"
        if not isinstance(cell.get("known", ""), str):
            return "a cell's known issue is not text"
    if not isinstance(report.get("executed"), int):
        return "no executed count"
    return None


def rust_cells_of(report):
    return [c for c in report.get("cells", []) if isinstance(c, dict) and c.get("client") == RUST]


def rust(args):
    """Replace the Rust client's cells and legs of a report by what the fakes' reports hold."""
    path = Path(args.report)
    merged = json.loads(path.read_text())
    carriers = [c for c in args.carriers.split(",") if c]
    for carrier in carriers:
        if carrier not in RUST_CARRIERS:
            print(f"unknown carrier {carrier!r} (one of {', '.join(RUST_CARRIERS)})", file=sys.stderr)
            return 2
    # What an earlier fold left goes first, so a second run changes nothing.
    old = rust_cells_of(merged)
    cells = {c["id"]: c for c in merged.get("cells", []) if c not in old}
    legs = [leg for leg in merged.get("legs", []) if leg.get("client") != RUST]
    # The cells the fakes ran are counted apart, so that a second fold subtracts them.
    executed = int(merged.get("executed") or 0) - int(merged.get("rust_executed") or 0)
    rust_executed = 0
    started = []
    for carrier in carriers:
        base = Path(args.rust_dir) / carrier / RUST_REPORT
        target = RUST_CARRIERS[carrier]
        report, state, reason = None, "missing", ""
        if base.is_file():
            try:
                report = json.loads(base.read_text())
            except ValueError:
                report, state, reason = None, "rejected", "it is not JSON"
            else:
                why = rust_reject_reason(report, carrier)
                if why:
                    report, state, reason = None, "rejected", why
                else:
                    state = "present"
        if report is not None:
            own = [dict(c) for c in report["cells"]]
            rust_executed += report["executed"]
            if report.get("started_at"):
                started.append(report["started_at"])
        else:
            what = f"its report was rejected ({reason})" if state == "rejected" else "it wrote no report"
            own = [failed_cell(f"{target}-linux/{carrier}/*/{RUST}/*",
                               f"did not run: the {carrier}/{RUST} run on the fakes: {what} (see the fakes job's log)")]
        for c in own:
            if c["id"] in cells:
                c = failed_cell(c["id"], f"cell reported twice across the fakes ({carrier}/{RUST})")
            cells[c["id"]] = c
        legs.append({
            "provider": carrier,
            "client": RUST,
            "build": RUST_BUILD,
            "target": target,
            "plan": None,
            "report": state,
            "reason": reason,
            "planned": len(own),
            "passed": sum(1 for c in own if c.get("status") == "pass"),
            "failed": sum(1 for c in own if c.get("status") != "pass"),
            "failed_known": failed_known(own),
        })
    ordered = [cells[k] for k in sorted(cells)]
    passed = sum(1 for c in ordered if c.get("status") == "pass")
    merged.update({
        "planned": len(ordered),
        "executed": executed + rust_executed,
        "rust_executed": rust_executed,
        "passed": passed,
        "failed": len(ordered) - passed,
        "failed_known": failed_known(ordered),
        "legs": legs,
        "cells": ordered,
    })
    if started and not merged.get("started_at"):
        merged["started_at"] = min(started)
    path.write_text(json.dumps(merged, indent=2) + "\n")
    mine = rust_cells_of(merged)
    line = (f"the {RUST} client: {len(mine)} cells on {len(carriers)} fake carrier(s), "
            f"{sum(1 for c in mine if c.get('status') != 'pass')} failed")
    print(line)
    if args.md:
        rows = ["", f"**Rust client** (`{RUST_BUILD}` against the fakes, no live room)", "",
                "| Fake | Report | Cells | Passed | Failed |", "| --- | --- | --- | --- | --- |"]
        for leg in legs:
            if leg["client"] != RUST:
                continue
            state = leg["report"] + (f" ({leg['reason']})" if leg["reason"] else "")
            rows.append(f"| `{md(leg['provider'])}` | {md(state)} | {leg['planned']} | {leg['passed']} | "
                        f"{failed_text(leg['failed'], leg['failed_known'])} |")
        with open(args.md, "a") as out:
            out.write("\n".join(rows) + "\n")
    return 0


def header(args):
    report = json.loads(Path(args.report).read_text())
    sha = report.get("engine_commit", "")
    ref = report.get("engine_ref", "")
    where = []
    where.append("the pin" if args.pinned == "true" else "a candidate, not the pin")
    if args.on_proofkit == "true":
        ahead = args.ahead_by if args.ahead_by not in ("", "0", "unknown") else ""
        where.append("on `proofkit`" + (f", which is {md(ahead)} commit(s) ahead" if ahead else ""))
    elif args.on_proofkit == "false":
        where.append("NOT on `proofkit`")
    lines = [
        f"**Release gate** · mode `{md(report.get('mode', ''))}` · app `{md(report.get('app_version', ''))}` · "
        f"engine `{md(sha[:12])}` (`{md(ref)}`; {', '.join(where)})",
        "",
    ]
    if args.limited == "true":
        lines += [
            f"**Limited run**: providers `{md(args.providers)}`"
            + (f", transports `{md(args.transports)}`" if args.transports else "")
            + ". Not a release gate.",
            "",
        ]
    lines += ["| Leg | Build | Planned | Report | Passed | Failed |", "| --- | --- | --- | --- | --- | --- |"]
    for leg in report.get("legs", []):
        state = leg.get("report", "")
        if leg.get("reason"):
            state += f" ({leg['reason']})"
        lines.append(
            f"| `{md(leg.get('provider', ''))}/{md(leg.get('client', ''))}` | {md(leg.get('build', ''))} | "
            f"{leg.get('planned', 0)} | {md(state)} | {leg.get('passed', 0)} | "
            f"{failed_text(leg.get('failed', 0), leg.get('failed_known', 0))} |"
        )
    lines += [
        "",
        f"Engine unit tests (default and lean builds): **{md(args.unit_result)}** · "
        f"suite legs: **{md(args.suite_result)}**" + (f" · [run]({args.run_url})" if args.run_url else ""),
        "",
    ]
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


def skipped(args):
    reason = one_line(args.reason) or "skipped by request"
    report = {
        "schema": 1,
        "skipped": True,
        "reason": reason,
        "engine_commit": args.engine_commit,
        "engine_ref": args.engine_ref,
        "app_version": args.app_version,
        "target": "local",
        "mode": args.mode,
        "runner": "",
        "started_at": now(),
        "duration_s": 0,
        "planned": 0,
        "executed": 0,
        "passed": 0,
        "failed": 0,
        "cells": [],
    }
    Path(args.out_json).write_text(json.dumps(report, indent=2) + "\n")
    text = (
        f"**Gate skipped by request**: {md(reason)}. Engine `{md(args.engine_commit[:12])}` "
        f"(`{md(args.engine_ref)}`) was not gated; every platform in this release was built from it untested."
    )
    if args.run_url:
        text += f" [Run]({args.run_url})."
    Path(args.out_md).write_text(text + "\n")
    print("wrote the skipped-by-request report")
    return 0


def decide(args):
    reasons = []
    for step in args.step:
        name, _, outcome = step.partition("=")
        if outcome != "success":
            reasons.append(f"step '{name}': {outcome or 'did not run'}")
    report = None
    try:
        report = json.loads(Path(args.report).read_text())
    except (OSError, ValueError):
        reasons.append("no readable gate report")
    # A gate skipped by request needs no client: it is not a gate. A dry run
    # walks no live leg, but the Rust client on the fakes is its leg.
    need = args.rust_client if (args.skip != "true" or args.dry == "true") else ""
    if args.skip == "true":
        if report is not None and report.get("skipped") is not True:
            reasons.append("the report of a skipped gate does not say it was skipped")
    else:
        if args.unit_result != "success":
            reasons.append(f"engine unit tests: {args.unit_result or 'did not run'}")
        if args.suite_result != "success":
            reasons.append(f"suite legs: {args.suite_result or 'did not run'}")
        if report is not None:
            planned = int(report.get("planned") or 0)
            executed = int(report.get("executed") or 0)
            failed = int(report.get("failed") or 0)
            # Known failures fail no gate; a report without the count, an
            # older engine's, reads every failure as one.
            known = int(report.get("failed_known") or 0)
            if planned == 0:
                reasons.append("the gate planned no cells")
            if failed - known > 0:
                reasons.append(f"{failed} of {planned} cells failed"
                               + (f" ({known} known, tracked by issues)" if known else ""))
            if executed < planned:
                reasons.append(f"{planned - executed} of {planned} planned cells did not run")
        if args.regression == "true" and args.severity == "fail":
            reasons.append("a regression against the previous release, at severity fail")
    if need:
        if args.fakes_result != "success":
            reasons.append(f"the {need} client on the fakes: {args.fakes_result or 'did not run'}")
        if report is not None:
            mine = [c for c in report.get("cells", []) if isinstance(c, dict) and c.get("client") == need]
            if not mine:
                reasons.append(f"the gate report has no '{need}' client")
            elif args.skip == "true":
                # A skipped report has no cell counts of its own to fail on.
                bad = [c for c in mine if c.get("status") != "pass" and not c.get("known")]
                if bad:
                    reasons.append(f"{len(bad)} of {len(mine)} cells of the '{need}' client failed")
    if reasons:
        for reason in reasons:
            print(f"::error title=Release gate::{reason}")
        return 1
    if args.skip == "true":
        print("the gate was skipped by request")
    else:
        known = int(report.get("failed_known") or 0)
        print("the gate passed" + (f" ({known} known failure(s), tracked by issues)" if known else ""))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    m = sub.add_parser("merge")
    m.add_argument("--report-name", required=True, help="the engine's report file name (from gate-run.sh)")
    m.add_argument("--legs-dir", required=True)
    m.add_argument("--providers", required=True, help="comma list: the legs that were meant to run")
    m.add_argument("--expect-commit", required=True)
    m.add_argument("--engine-ref", default="")
    m.add_argument("--app-version", default="")
    m.add_argument("--mode", default="")
    m.add_argument("--out", required=True)
    m.set_defaults(func=merge)

    h = sub.add_parser("header")
    h.add_argument("--report", required=True)
    h.add_argument("--pinned", default="true")
    h.add_argument("--on-proofkit", default="unknown")
    h.add_argument("--ahead-by", default="")
    h.add_argument("--limited", default="false")
    h.add_argument("--providers", default="")
    h.add_argument("--transports", default="")
    h.add_argument("--unit-result", default="")
    h.add_argument("--suite-result", default="")
    h.add_argument("--run-url", default="")
    h.set_defaults(func=header)

    s = sub.add_parser("skipped")
    s.add_argument("--reason", default="")
    s.add_argument("--engine-commit", required=True)
    s.add_argument("--engine-ref", default="")
    s.add_argument("--app-version", default="")
    s.add_argument("--mode", default="release")
    s.add_argument("--run-url", default="")
    s.add_argument("--out-json", required=True)
    s.add_argument("--out-md", required=True)
    s.set_defaults(func=skipped)

    r = sub.add_parser("rust")
    r.add_argument("--report", required=True, help="the report to fold the fakes' cells into, in place")
    r.add_argument("--rust-dir", required=True, help="<dir>/<carrier>/gate-report.json, one per fake carrier")
    r.add_argument("--carriers", default=",".join(RUST_CARRIERS))
    r.add_argument("--md", default="", help="a markdown file to append the Rust client's table to")
    r.set_defaults(func=rust)

    d = sub.add_parser("decide")
    d.add_argument("--report", required=True)
    d.add_argument("--skip", default="false")
    d.add_argument("--unit-result", default="")
    d.add_argument("--suite-result", default="")
    d.add_argument("--dry", default="false", help="the run is a dry one: the skipped report still needs the Rust client")
    d.add_argument("--fakes-result", default="", help="the fakes job's result")
    d.add_argument("--rust-client", default=RUST, help="the client every gate report must carry; empty asks for none")
    d.add_argument("--regression", default="false")
    d.add_argument("--severity", default="warn")
    d.add_argument("--step", action="append", default=[], help="name=outcome of a verdict step that must have succeeded")
    d.set_defaults(func=decide)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
