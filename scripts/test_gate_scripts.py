"""Offline checks for the release gate's scripts: the decisions, not the network.

    python3 scripts/test_gate_scripts.py

GitHub is a local HTTP server (GITHUB_API_URL points at it), `go` is a stub on
PATH that records what it was asked and answers like the engine's suite, and
cores-pins.sh is replaced by a fake pin, so none of this depends on the real
pin or on anything outside the machine. Every secret here is made up.
"""

import http.server
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


scrub = load("gate_scrub", "gate-scrub.py")
merge = load("gate_merge_reports", "gate-merge-reports.py")

# Made-up values in the shapes the real ones take.
TELEMOST_URL = "https://telemost.example.invalid/j/10000000000001?from=fake&x=1"
TELEMOST_ID = "10000000000001"
TELEMOST_SPARE = "20000000000002"
WB_ROOM = "fakewbroom0001"
WB_SPARE = "fakewbroom0002"
WB_TOKEN = "fake.wb-token.0123456789abcdefghij"
JITSI_HOST = "jitsi.example.invalid"
SESSION_KEY = "ab" * 32
JITSI_ROOM = "gate-0123456789ab"
FAKE_JWT = "eyJhbGciOiJub25lIn0.eyJmYWtlIjoidGVzdCJ9.ZmFrZS1zaWduYXR1cmU"

# The fake engine pin cores-pins.sh carries in these tests.
PIN_TIME = "20260102030405"
PIN_REV = "0123456789ab"
PIN_VERSION = f"v0.0.0-{PIN_TIME}-{PIN_REV}"
PIN_SHA = "0123456789abcdef0123456789abcdef01234567"
PIN_DATE = "2026-01-02T03:04:05Z"
ENGINE = "/repos/ghostlane-project/olcrtc"


def pin_routes(date=PIN_DATE, sha=PIN_SHA, gate=True):
    routes = {
        f"{ENGINE}/commits/{PIN_REV}": (200, {"sha": sha, "commit": {"committer": {"date": date}}}),
        f"{ENGINE}/compare/{sha}...proofkit": (200, {"status": "ahead", "ahead_by": 2}),
    }
    if gate:
        for path in ("internal/gate", "cmd/gate-report"):
            routes[f"{ENGINE}/contents/{path}?ref={sha}"] = (200, [])
    return routes


class FakeGitHub:
    """api.github.com on 127.0.0.1: exact path (query included) -> (status, body)."""

    def __init__(self, routes):
        self.routes = routes
        self.seen = []
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - http.server's name
                fake.seen.append((self.path, self.headers.get("Authorization")))
                status, body = fake.routes.get(self.path, (404, {"message": "Not Found"}))
                data = body if isinstance(body, bytes) else json.dumps(body).encode()
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
        return f"http://127.0.0.1:{self.server.server_address[1]}"


STUB_GO = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
seen = {k: v for k, v in os.environ.items() if k.startswith(("OLCRTC_GATE_", "GATE_"))}
with open(os.environ["STUB_GO_LOG"], "a") as log:
    log.write(json.dumps({"argv": args, "env": seen, "cwd": os.getcwd()}) + "\n")
if "-olcrtc.gate-dry" in args:
    print("=== RUN   TestGate")
    for line in os.environ.get("STUB_GO_PLAN", "").split(";"):
        print(line)
    print("--- PASS: TestGate (0.00s)")
    sys.exit(0)
gate_dir = next((a.split("=", 1)[1] for a in args if a.startswith("-olcrtc.gate-dir=")), None)
if gate_dir and os.environ.get("STUB_GO_REPORT"):
    with open(os.path.join(gate_dir, "gate-report.json"), "w") as f:
        f.write(os.environ["STUB_GO_REPORT"])
sys.exit(int(os.environ.get("STUB_GO_EXIT", "0")))
'''


class Sandbox:
    """A copy of the scripts with a fake cores-pins.sh, a stub go and a clean env."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        for name in ("olcrtc-pin.sh", "gate-api.sh", "gate-resolve.sh", "gate-run.sh", "gate-mask.sh",
                     "gate-previous-report.sh", "gate-merge-reports.py", "gate-scrub.py"):
            shutil.copy2(HERE / name, self.scripts / name)
        (self.scripts / "cores-pins.sh").write_text(
            'GO_VERSION="${GO_VERSION:-1.99.9}"\n'
            f'OLCRTC_VERSION="${{OLCRTC_VERSION:-{PIN_VERSION}}}"\n'
        )
        self.bin = self.root / "bin"
        self.bin.mkdir()
        go = self.bin / "go"
        go.write_text(STUB_GO)
        go.chmod(0o755)
        self.go_log = self.root / "go.log"
        self.engine = self.root / "olcrtc"
        self.engine.mkdir()

    def env(self, api=None, **extra):
        env = {
            "PATH": f"{self.bin}:{os.environ.get('PATH', '/usr/bin:/bin')}",
            "HOME": str(self.root),
            "LANG": "C.UTF-8",
            "NO_PROXY": "*",
            "no_proxy": "*",
            "STUB_GO_LOG": str(self.go_log),
            "GATE_ENGINE_DIR": str(self.engine),
            "GITHUB_API_URL": api or "http://127.0.0.1:9",
        }
        env.update({k: v for k, v in extra.items() if v is not None})
        return env

    def run(self, script, *args, env):
        return subprocess.run(["bash", str(self.scripts / script), *args], env=env, capture_output=True,
                              text=True, timeout=120, cwd=self.root)

    def go_calls(self):
        if not self.go_log.exists():
            return []
        return [json.loads(line) for line in self.go_log.read_text().splitlines()]

    def close(self):
        self.tmp.cleanup()


def outputs(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


class Pin(unittest.TestCase):
    def setUp(self):
        self.box = Sandbox()

    def tearDown(self):
        self.box.close()

    def test_the_pin_resolves_to_its_full_commit_whatever_the_env_says(self):
        with FakeGitHub(pin_routes()) as gh:
            r = self.box.run("olcrtc-pin.sh", env=self.box.env(gh.url, OLCRTC_VERSION="v9.9.9", GO_VERSION="0.1"))
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual(PIN_SHA, out["olcrtc_sha"])
        self.assertEqual(PIN_VERSION, out["olcrtc_version"])
        self.assertEqual(PIN_REV, out["olcrtc_rev"])
        self.assertEqual("1.99.9", out["go_version"])
        self.assertEqual("true", out["olcrtc_on_proofkit"])
        self.assertEqual("2", out["olcrtc_proofkit_ahead_by"])
        self.assertEqual("true", out["olcrtc_pinned"])

    def test_a_pseudo_version_whose_time_is_not_the_commits_is_refused(self):
        with FakeGitHub(pin_routes(date="2026-01-02T03:04:06Z")) as gh:
            r = self.box.run("olcrtc-pin.sh", env=self.box.env(gh.url))
        self.assertEqual(1, r.returncode)
        self.assertIn("does not describe this commit", r.stderr)
        self.assertEqual("", r.stdout)

    def test_a_revision_that_resolves_elsewhere_is_refused(self):
        other = "fedcba9876543210fedcba9876543210fedcba98"
        with FakeGitHub(pin_routes(sha=other)) as gh:
            r = self.box.run("olcrtc-pin.sh", env=self.box.env(gh.url))
        self.assertEqual(1, r.returncode)
        self.assertIn("which is not it", r.stderr)

    def test_an_unknown_revision_is_refused(self):
        with FakeGitHub({}) as gh:
            r = self.box.run("olcrtc-pin.sh", env=self.box.env(gh.url))
        self.assertEqual(1, r.returncode)
        self.assertIn("GitHub does not know", r.stderr)

    def test_a_failed_comparison_is_only_a_warning(self):
        routes = pin_routes()
        del routes[f"{ENGINE}/compare/{PIN_SHA}...proofkit"]
        with FakeGitHub(routes) as gh:
            r = self.box.run("olcrtc-pin.sh", env=self.box.env(gh.url))
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual("unknown", outputs(r.stdout)["olcrtc_on_proofkit"])

    def test_a_candidate_ref_is_encoded_and_is_not_the_pin(self):
        routes = {
            f"{ENGINE}/commits/fix%2Ffake-candidate": (200, {"sha": PIN_SHA, "commit": {"committer": {"date": PIN_DATE}}}),
            f"{ENGINE}/compare/{PIN_SHA}...proofkit": (200, {"status": "diverged", "ahead_by": 5}),
        }
        with FakeGitHub(routes) as gh:
            r = self.box.run("olcrtc-pin.sh", "--ref", "fix/fake-candidate", env=self.box.env(gh.url, GH_TOKEN="fake-token-for-tests"))
            seen = list(gh.seen)
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual("false", out["olcrtc_pinned"])
        self.assertEqual("fix/fake-candidate", out["olcrtc_version"])
        self.assertEqual("false", out["olcrtc_on_proofkit"])
        self.assertTrue(all(auth == "Bearer fake-token-for-tests" for _, auth in seen), seen)

    def test_a_ref_that_is_no_ref_is_refused_before_any_request(self):
        with FakeGitHub({}) as gh:
            r = self.box.run("olcrtc-pin.sh", "--ref", "../etc", env=self.box.env(gh.url))
            self.assertEqual([], gh.seen)
        self.assertEqual(1, r.returncode)


class Resolve(unittest.TestCase):
    def setUp(self):
        self.box = Sandbox()

    def tearDown(self):
        self.box.close()

    def resolve(self, routes, **env):
        with FakeGitHub(routes) as gh:
            return self.box.run("gate-resolve.sh", env=self.box.env(
                gh.url, GITHUB_REF_NAME="main", GITHUB_SHA="1234567" + "0" * 33, **env))

    def test_a_scheduled_run_is_a_leg_per_provider_against_the_pin(self):
        r = self.resolve(pin_routes(), GATE_EVENT="schedule")
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual("scheduled", out["mode"])
        self.assertEqual(PIN_SHA, out["engine_sha"])
        self.assertEqual({"include": [{"provider": "jitsi"}, {"provider": "telemost"}, {"provider": "wbstream"},
                                      {"provider": "salutejazz"}]},
                         json.loads(out["legs"]))
        self.assertEqual("jitsi,telemost,wbstream,salutejazz", out["providers"])
        self.assertEqual("false", out["skip"])
        self.assertEqual("false", out["limited"])
        self.assertEqual("warn", out["severity"])
        self.assertEqual("main@1234567", out["app_version"])
        self.assertEqual("", out["current_tag"])

    def test_a_push_is_the_pin_mode(self):
        r = self.resolve(pin_routes(), GATE_EVENT="push")
        self.assertEqual("pin", outputs(r.stdout)["mode"], r.stderr)

    def test_a_release_is_the_caller_mode_even_though_the_event_is_the_callers(self):
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_CALL_MODE="release",
                         GATE_CALL_ENGINE_SHA=PIN_SHA, GATE_CALL_ENGINE_VERSION=PIN_VERSION,
                         GATE_CALL_APP_VERSION="1.0.999", GATE_CALL_SKIP="false", GATE_CALL_SEVERITY="warn")
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual("release", out["mode"])
        self.assertEqual("v1.0.999", out["current_tag"])
        self.assertEqual(4, len(json.loads(out["legs"])["include"]))

    def test_a_release_for_another_engine_than_the_pin_is_refused(self):
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_CALL_MODE="release",
                         GATE_CALL_ENGINE_SHA="f" * 40, GATE_CALL_APP_VERSION="1.0.999")
        self.assertEqual(1, r.returncode)
        self.assertIn("pins 0123456789ab", r.stderr)

    def test_a_pin_without_the_gate_fails_with_the_re_pin_message(self):
        r = self.resolve(pin_routes(gate=False), GATE_EVENT="schedule")
        self.assertEqual(1, r.returncode)
        self.assertIn("has no internal/gate", r.stderr)
        self.assertIn("Re-pin to an engine commit that carries internal/gate", r.stderr)
        self.assertEqual("", r.stdout)

    def test_a_skipped_release_needs_a_reason_and_no_gate(self):
        common = dict(GATE_EVENT="workflow_dispatch", GATE_CALL_MODE="release", GATE_CALL_ENGINE_SHA=PIN_SHA,
                      GATE_CALL_APP_VERSION="1.0.999", GATE_CALL_SKIP="true")
        r = self.resolve(pin_routes(gate=False), GATE_CALL_SKIP_REASON="  ", **common)
        self.assertEqual(1, r.returncode)
        self.assertIn("needs a gate_reason", r.stderr)
        r = self.resolve(pin_routes(gate=False), GATE_CALL_SKIP_REASON="the relay is down today", **common)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual("true", outputs(r.stdout)["skip"])

    def test_a_manual_run_can_narrow_the_legs_and_says_so(self):
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_PROVIDERS="wbstream, jitsi,wbstream",
                         GATE_TRANSPORTS="vp8channel")
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual("manual", out["mode"])
        self.assertEqual(["jitsi", "wbstream"], [leg["provider"] for leg in json.loads(out["legs"])["include"]])
        self.assertEqual("true", out["limited"])
        self.assertEqual("vp8channel", out["transports"])

    def test_a_manual_run_can_gate_salutejazz_alone(self):
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_PROVIDERS=" salutejazz ")
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual({"include": [{"provider": "salutejazz"}]}, json.loads(out["legs"]))
        self.assertEqual("true", out["limited"])

    def test_a_manual_run_refuses_an_unknown_provider_or_none(self):
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_PROVIDERS="zoom")
        self.assertEqual(1, r.returncode)
        self.assertIn("unknown provider", r.stderr)
        r = self.resolve(pin_routes(), GATE_EVENT="workflow_dispatch", GATE_PROVIDERS=" , ,")
        self.assertEqual(1, r.returncode)
        self.assertIn("no provider chosen", r.stderr)

    def test_a_candidate_ref_is_gated_instead_of_the_pin(self):
        routes = pin_routes()
        routes[f"{ENGINE}/commits/fake-candidate"] = routes.pop(f"{ENGINE}/commits/{PIN_REV}")
        r = self.resolve(routes, GATE_EVENT="workflow_dispatch", GATE_ENGINE_REF=" fake-candidate ")
        self.assertEqual(0, r.returncode, r.stderr)
        out = outputs(r.stdout)
        self.assertEqual(("fake-candidate", "false", PIN_SHA), (out["engine_version"], out["engine_pinned"], out["engine_sha"]))


class Check(unittest.TestCase):
    """gate-resolve.sh check: a leg without its secrets fails, naming them, printing no value."""

    def setUp(self):
        self.box = Sandbox()

    def tearDown(self):
        self.box.close()

    def check(self, provider, **secrets):
        return self.box.run("gate-resolve.sh", "check", provider, env=self.box.env(**secrets))

    def assert_no_value(self, r):
        for value in (TELEMOST_URL, TELEMOST_ID, WB_ROOM, WB_TOKEN, JITSI_HOST, "abc"):
            self.assertNotIn(value, r.stdout + r.stderr)

    def test_the_wbstream_leg_needs_its_rooms_and_its_token(self):
        r = self.check("wbstream", GATE_WBSTREAM_ROOMS=WB_ROOM, GATE_TELEMOST_ROOMS=TELEMOST_URL)
        self.assertEqual(1, r.returncode)
        self.assertIn("needs the repository secret GATE_WBSTREAM_TOKEN", r.stderr)
        self.assertIn("never skipped", r.stderr)
        self.assert_no_value(r)
        r = self.check("wbstream", GATE_WBSTREAM_TOKEN=WB_TOKEN)
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_WBSTREAM_ROOMS", r.stderr)
        self.assert_no_value(r)

    def test_the_telemost_leg_needs_its_pool(self):
        r = self.check("telemost", GATE_WBSTREAM_ROOMS=WB_ROOM, GATE_WBSTREAM_TOKEN=WB_TOKEN)
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_TELEMOST_ROOMS", r.stderr)
        self.assert_no_value(r)

    def test_an_entry_too_short_to_mask_is_refused_by_its_position(self):
        r = self.check("telemost", GATE_TELEMOST_ROOMS=f"{TELEMOST_URL}, abc")
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_TELEMOST_ROOMS, entry 2", r.stderr)
        self.assert_no_value(r)

    def test_a_jitsi_host_must_be_a_bare_host_and_is_optional(self):
        self.assertEqual(0, self.check("jitsi").returncode)
        r = self.check("jitsi", GATE_JITSI_HOSTS=f"https://{JITSI_HOST}/room")
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_JITSI_HOSTS, entry 1: not a bare host name", r.stderr)
        self.assert_no_value(r)

    def test_a_jitsi_host_with_a_port_is_refused(self):
        # The mask and the scrubber take an entry as it is, and a Go error
        # prints the host without its port ("lookup <host>"), so host:port
        # would have left the bare host in the log and the report.
        r = self.check("jitsi", GATE_JITSI_HOSTS=f"meet2.example.invalid, {JITSI_HOST}:8443")
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_JITSI_HOSTS, entry 2: not a bare host name (no scheme, no port, no path)", r.stderr)
        self.assert_no_value(r)
        r = self.check("jitsi", GATE_JITSI_HOSTS="ab.io:8443")
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_JITSI_HOSTS, entry 1: not a bare host name", r.stderr)
        self.assertNotIn("ab.io", r.stdout + r.stderr)

    def test_the_salutejazz_leg_needs_no_secret(self):
        # The suite makes a fresh room per pair through Sber's anonymous
        # create call: there is nothing to check, and another leg's secrets
        # in the env change nothing and are never printed.
        for secrets in ({}, dict(GATE_TELEMOST_ROOMS=TELEMOST_URL, GATE_WBSTREAM_TOKEN=WB_TOKEN)):
            r = self.check("salutejazz", **secrets)
            self.assertEqual(0, r.returncode, r.stderr)
            self.assertIn("needs no secret", r.stderr)
            self.assert_no_value(r)

    def test_a_leg_with_what_it_needs_passes(self):
        r = self.check("wbstream", GATE_WBSTREAM_ROOMS=f"{WB_ROOM}\n{WB_SPARE}", GATE_WBSTREAM_TOKEN=WB_TOKEN)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(0, self.check("telemost", GATE_TELEMOST_ROOMS=f"{TELEMOST_URL},{TELEMOST_SPARE}").returncode)
        self.assertEqual(0, self.check("jitsi", GATE_JITSI_HOSTS=f"{JITSI_HOST}, meet2.example.invalid").returncode)


class Mask(unittest.TestCase):
    def setUp(self):
        self.box = Sandbox()

    def tearDown(self):
        self.box.close()

    def test_every_entry_its_id_and_the_token_are_masked_and_nothing_else_is_printed(self):
        r = self.box.run("gate-mask.sh", env=self.box.env(
            GATE_TELEMOST_ROOMS=f" {TELEMOST_URL} ,\n{TELEMOST_SPARE}", GATE_WBSTREAM_ROOMS=WB_ROOM,
            GATE_WBSTREAM_TOKEN=f" {WB_TOKEN} ", GATE_JITSI_HOSTS=f"{JITSI_HOST},abc"))
        self.assertEqual(0, r.returncode, r.stderr)
        lines = r.stdout.splitlines()
        self.assertTrue(all(line.startswith("::add-mask::") for line in lines), lines)
        masked = {line[len("::add-mask::"):] for line in lines}
        self.assertIn(TELEMOST_URL, masked)
        self.assertIn(TELEMOST_URL.split("?")[0], masked)
        self.assertIn(TELEMOST_ID, masked)
        self.assertIn(TELEMOST_SPARE, masked)
        self.assertIn(WB_ROOM, masked)
        self.assertIn(WB_TOKEN, masked)
        self.assertIn(JITSI_HOST, masked)
        self.assertNotIn("abc", masked)
        self.assertEqual("", r.stderr)

    def test_a_percent_is_escaped_so_the_runner_masks_the_value_itself(self):
        r = self.box.run("gate-mask.sh", env=self.box.env(GATE_WBSTREAM_TOKEN="fake%41token-value"))
        self.assertEqual("::add-mask::fake%2541token-value\n", r.stdout)


class Run(unittest.TestCase):
    """gate-run.sh: the engine's flags, and the secrets in the environment only."""

    PLAN = ";".join([
        "engine-linux/jitsi/datachannel/cli/S0",
        "engine-linux/jitsi/datachannel/mobile/S0",
        "engine-linux/jitsi/datachannel/cli/S6",
        "engine-linux/telemost/vp8channel/cli/S0",
        "ok  github.com/example/olcrtc/internal/gate 0.1s",
        "engine-linux/jitsi/datachannel/cli/S0",
    ])

    def setUp(self):
        self.box = Sandbox()
        self.leg = self.box.root / "gate-artifacts"

    def tearDown(self):
        self.box.close()

    def gate_run(self, *args, **env):
        base = dict(GATE_ENGINE_SHA=PIN_SHA, GATE_ENGINE_VERSION=PIN_VERSION, GATE_APP_VERSION="1.0.999",
                    STUB_GO_PLAN=self.PLAN)
        base.update(env)
        return self.box.run("gate-run.sh", *args, env=self.box.env(**base))

    def test_the_plan_is_the_flavours_own_cells_from_a_dry_run_without_secrets(self):
        d = self.leg / "jitsi" / "cli"
        r = self.gate_run("plan", "cli", str(d), GATE_PROVIDER="jitsi", GATE_JITSI_HOSTS=JITSI_HOST,
                          GATE_TELEMOST_ROOMS=TELEMOST_URL)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(["engine-linux/jitsi/datachannel/cli/S0", "engine-linux/jitsi/datachannel/cli/S6"],
                         (d / "plan.txt").read_text().splitlines())
        (call,) = self.box.go_calls()
        self.assertIn("-olcrtc.gate-dry", call["argv"])
        self.assertIn("-olcrtc.gate-clients=cli", call["argv"])
        self.assertNotIn("-tags", call["argv"])
        self.assertFalse([k for k in call["env"] if "ROOMS" in k or "TOKEN" in k or "HOSTS" in k], call["env"])

    def test_an_empty_plan_fails(self):
        r = self.gate_run("plan", "mobile", str(self.leg / "wbstream" / "mobile"), GATE_PROVIDER="wbstream")
        self.assertEqual(1, r.returncode)
        self.assertIn("planned no cells", r.stderr)

    def test_the_mobile_flavour_runs_the_lean_build_with_the_first_room_in_its_env(self):
        d = self.leg / "telemost" / "mobile"
        r = self.gate_run("run", "mobile", str(d), GATE_PROVIDER="telemost",
                          GATE_TELEMOST_ROOMS=f" {TELEMOST_URL} , {TELEMOST_SPARE}")
        self.assertEqual(0, r.returncode, r.stderr)
        (call,) = self.box.go_calls()
        argv = call["argv"]
        self.assertEqual(["test", "-count=1", "-tags", "olcrtc_lean", "-timeout", "30m", "-run", "^TestGate$", "-v",
                          "./internal/gate"], argv[:10])
        for flag in ("-olcrtc.gate", "-olcrtc.gate-target=local", "-olcrtc.gate-providers=telemost",
                     "-olcrtc.gate-clients=mobile", f"-olcrtc.gate-dir={d}"):
            self.assertIn(flag, argv)
        self.assertFalse([a for a in argv if TELEMOST_ID in a or TELEMOST_SPARE in a], argv)
        self.assertEqual(TELEMOST_URL, call["env"]["OLCRTC_GATE_TELEMOST_ROOMS"])
        self.assertEqual(PIN_SHA, call["env"]["OLCRTC_GATE_ENGINE_COMMIT"])
        self.assertEqual(PIN_VERSION, call["env"]["OLCRTC_GATE_ENGINE_REF"])
        self.assertEqual("1.0.999", call["env"]["OLCRTC_GATE_APP_VERSION"])
        self.assertNotIn("GATE_TELEMOST_ROOMS", call["env"])
        self.assertNotIn("OLCRTC_GATE_WBSTREAM_TOKEN", call["env"])

    def test_the_cli_flavour_is_the_default_build_and_the_token_reaches_wbstream_only_by_env(self):
        d = self.leg / "wbstream" / "cli"
        r = self.gate_run("run", "cli", str(d), GATE_PROVIDER="wbstream", GATE_WBSTREAM_ROOMS=f"{WB_ROOM},{WB_SPARE}",
                          GATE_WBSTREAM_TOKEN=WB_TOKEN, GATE_TRANSPORTS="vp8channel")
        self.assertEqual(0, r.returncode, r.stderr)
        (call,) = self.box.go_calls()
        self.assertNotIn("-tags", call["argv"])
        self.assertIn("20m", call["argv"])
        self.assertIn("-olcrtc.gate-transports=vp8channel", call["argv"])
        self.assertFalse([a for a in call["argv"] if WB_TOKEN in a or WB_ROOM in a])
        self.assertEqual(WB_ROOM, call["env"]["OLCRTC_GATE_WBSTREAM_ROOMS"])
        self.assertEqual(WB_TOKEN, call["env"]["OLCRTC_GATE_WBSTREAM_TOKEN"])
        self.assertNotIn(WB_TOKEN, r.stdout + r.stderr)

    def test_jitsi_hosts_pass_as_one_list_and_a_relative_dir_is_refused(self):
        r = self.gate_run("run", "cli", str(self.leg / "jitsi" / "cli"), GATE_PROVIDER="jitsi",
                          GATE_JITSI_HOSTS=f" {JITSI_HOST} ,\nmeet2.example.invalid")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(f"{JITSI_HOST},meet2.example.invalid", self.box.go_calls()[0]["env"]["OLCRTC_GATE_JITSI_HOSTS"])
        r = self.gate_run("run", "cli", "gate-artifacts/jitsi/cli", GATE_PROVIDER="jitsi")
        self.assertEqual(1, r.returncode)
        self.assertIn("must be absolute", r.stderr)

    def test_the_salutejazz_leg_runs_with_no_room_or_token_and_an_unknown_provider_is_refused(self):
        d = self.leg / "salutejazz" / "mobile"
        r = self.gate_run("run", "mobile", str(d), GATE_PROVIDER="salutejazz")
        self.assertEqual(0, r.returncode, r.stderr)
        (call,) = self.box.go_calls()
        self.assertIn("-olcrtc.gate-providers=salutejazz", call["argv"])
        self.assertIn("-olcrtc.gate-clients=mobile", call["argv"])
        self.assertFalse([k for k in call["env"] if k.startswith("OLCRTC_GATE_") and
                          ("ROOMS" in k or "TOKEN" in k or "HOSTS" in k)], call["env"])
        r = self.gate_run("plan", "cli", str(self.leg / "zoom" / "cli"), GATE_PROVIDER="zoom")
        self.assertEqual(1, r.returncode)
        self.assertIn("GATE_PROVIDER must be", r.stderr)

    def test_a_red_run_is_a_red_step(self):
        r = self.gate_run("run", "cli", str(self.leg / "jitsi" / "cli"), GATE_PROVIDER="jitsi", STUB_GO_EXIT="1")
        self.assertEqual(1, r.returncode)

    def test_the_paths_a_revision_must_carry(self):
        r = self.gate_run("paths")
        self.assertEqual(["internal/gate", "cmd/gate-report"], r.stdout.split())

    def test_the_summary_names_each_flavour_and_its_counts(self):
        cli = self.leg / "jitsi" / "cli"
        cli.mkdir(parents=True)
        (cli / "plan.txt").write_text("engine-linux/jitsi/datachannel/cli/S0\n")
        (cli / "gate-report.json").write_text(json.dumps({"planned": 1, "executed": 1, "passed": 1, "failed": 0}))
        r = self.gate_run("summary", str(self.leg / "jitsi"))
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("`jitsi/cli`: 1 planned; report: 1 passed, 0 failed of 1, 1 ran", r.stdout)
        self.assertIn("`jitsi/mobile`: no plan; no report", r.stdout)

    def test_the_summary_says_how_many_failures_are_known(self):
        mobile = self.leg / "jitsi" / "mobile"
        mobile.mkdir(parents=True)
        (mobile / "gate-report.json").write_text(json.dumps(
            {"planned": 25, "executed": 25, "passed": 22, "failed": 3, "failed_known": 2}))
        r = self.gate_run("summary", str(self.leg / "jitsi"))
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("`jitsi/mobile`: no plan; report: 22 passed, 3 failed (2 known) of 25, 25 ran", r.stdout)

    def test_compare_needs_both_reports_and_puts_the_severity_before_them(self):
        prev, cur = self.box.root / "prev.json", self.box.root / "cur.json"
        cur.write_text("{}")
        r = self.gate_run("compare", "warn", str(prev), str(cur))
        self.assertEqual(1, r.returncode)
        self.assertIn("compare needs two report files", r.stderr)
        self.assertEqual([], self.box.go_calls())
        prev.write_text("{}")
        r = self.gate_run("compare", "fail", str(prev), str(cur))
        self.assertEqual(0, r.returncode, r.stderr)
        (call,) = self.box.go_calls()
        self.assertEqual(["run", "./cmd/gate-report", "compare", "-severity", "fail", str(prev), str(cur)], call["argv"])

    def test_the_unit_tests_run_race_under_the_asked_build(self):
        self.assertEqual(0, self.gate_run("unit", "lean").returncode)
        self.assertEqual(0, self.gate_run("unit", "default").returncode)
        lean, default = self.box.go_calls()
        self.assertEqual(["test", "-count=1", "-race", "-timeout", "15m", "-tags", "olcrtc_lean", "./..."], lean["argv"])
        self.assertEqual(["test", "-count=1", "-race", "-timeout", "15m", "./..."], default["argv"])


class Scrub(unittest.TestCase):
    ENV = {
        "GATE_TELEMOST_ROOMS": f"{TELEMOST_URL},{TELEMOST_SPARE}",
        "GATE_WBSTREAM_ROOMS": WB_ROOM,
        "GATE_WBSTREAM_TOKEN": WB_TOKEN,
        "GATE_JITSI_HOSTS": JITSI_HOST,
    }

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "leg"
        (self.dir / "mobile" / "telemost-vp8channel").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def run_scrub(self, *args, env=None):
        return subprocess.run(["python3", str(HERE / "gate-scrub.py"), *args, str(self.dir)],
                              env={"PATH": os.environ.get("PATH", ""), **(env or {})},
                              capture_output=True, text=True, timeout=60)

    def test_a_log_with_the_runs_own_values_comes_out_clean(self):
        commit = "aaffe1e05c3b8ed6ac1a4fcbff33d5e08fab601f"
        log = self.dir / "mobile" / "telemost-vp8channel" / "mobile-S2.log"
        log.write_text(
            f"joining {TELEMOST_URL}\nroom {TELEMOST_ID} and {TELEMOST_ID.upper()} spare {TELEMOST_SPARE}\n"
            f"wb {WB_ROOM} token {WB_TOKEN}\nkey {SESSION_KEY} and {SESSION_KEY.upper()}\n"
            f"jitsi https://{JITSI_HOST.upper()}/{JITSI_ROOM}\nengine {commit}\nlivekit {FAKE_JWT}\n"
            f"dial tcp: lookup {JITSI_HOST} on 127.0.0.53:53: no such host\n"
        )
        report = self.dir / "mobile" / "gate-report.json"
        report.write_text(json.dumps({"engine_commit": commit, "cells": [
            {"failures": [f"client: join {TELEMOST_URL} failed", f"wb {WB_ROOM}", f"<{JITSI_HOST}>"]}]}))
        r = self.run_scrub(env=self.ENV)
        self.assertEqual(0, r.returncode, r.stderr)
        text = log.read_text() + report.read_text()
        for value in (TELEMOST_ID, TELEMOST_SPARE, WB_ROOM, WB_TOKEN, JITSI_HOST, SESSION_KEY, JITSI_ROOM, FAKE_JWT,
                      "telemost.example.invalid/j/1", "&x=1", "\\u0026x=1"):
            self.assertNotIn(value.lower(), text.lower())
        self.assertIn(commit, text)
        for placeholder in ("<room>", "<token>", "<key>", "<jitsi-host>", "gate-<room>", "<jwt>"):
            self.assertIn(placeholder, log.read_text())
        self.assertEqual(0, self.run_scrub("--check-only", env=self.ENV).returncode)

    def test_json_escaped_and_percent_encoded_forms_are_caught(self):
        report = self.dir / "mobile" / "gate-report.json"
        escaped = json.dumps(TELEMOST_URL)[1:-1].replace("&", "\\u0026")
        report.write_text('{"a": "%s", "b": "%s", "c": "%s"}' % (
            escaped, TELEMOST_URL.replace("/", "\\/"), "https%3A%2F%2Ftelemost.example.invalid%2Fj%2F" + TELEMOST_ID))
        self.assertEqual(0, self.run_scrub(env=self.ENV).returncode)
        text = report.read_text()
        self.assertNotIn(TELEMOST_ID, text)
        self.assertNotIn("from=fake", text)

    def test_what_may_not_be_uploaded_is_deleted(self):
        pair = self.dir / "mobile" / "telemost-vp8channel"
        (pair / "srv.yaml").write_text(f"room: {{ id: {TELEMOST_URL} }}\n")
        (self.dir / "olcrtc").write_bytes(b"\x7fELF fake binary")
        (pair / f"{WB_ROOM}.log").write_text("named after a room\n")
        (pair / "mobile-S0.log").write_text("fine\n")
        r = self.run_scrub(env=self.ENV)
        self.assertEqual(0, r.returncode, r.stderr)
        left = sorted(p.relative_to(self.dir).as_posix() for p in self.dir.rglob("*") if p.is_file())
        self.assertEqual(["mobile/telemost-vp8channel/mobile-S0.log"], left)
        self.assertNotIn(WB_ROOM, r.stdout + r.stderr)

    def test_the_check_fails_on_residue_and_never_prints_it(self):
        (self.dir / "gate-report.md").write_text(f"a {WB_ROOM} and a {SESSION_KEY} and {JITSI_ROOM} and {FAKE_JWT}\n")
        r = self.run_scrub("--check-only", env=self.ENV)
        self.assertEqual(1, r.returncode)
        self.assertIn("gate-report.md: 1 secret value(s), 1 key(s), 1 room name(s), 1 token(s) remain", r.stderr)
        for value in (WB_ROOM, SESSION_KEY, JITSI_ROOM, FAKE_JWT):
            self.assertNotIn(value, r.stdout + r.stderr)
        # Without the secrets in its env the check still sees the patterns.
        r = self.run_scrub("--check-only")
        self.assertEqual(1, r.returncode)
        self.assertIn("0 secret value(s), 1 key(s), 1 room name(s), 1 token(s)", r.stderr)

    def test_a_check_of_nothing_vouches_for_nothing(self):
        shutil.rmtree(self.dir)
        self.assertEqual(1, self.run_scrub("--check-only").returncode)
        self.assertEqual(0, self.run_scrub().returncode)

    def test_short_values_do_not_become_patterns(self):
        table = scrub.secret_table({"GATE_TELEMOST_ROOMS": "abc", "GATE_WBSTREAM_TOKEN": ""})
        self.assertEqual({}, table)


# Issues of the engine's known list, as its report names them.
ISSUE9 = "https://github.com/ghostlane-project/olcrtc/issues/9"
ISSUE11 = "https://github.com/ghostlane-project/olcrtc/issues/11"
ISSUE15 = "https://github.com/ghostlane-project/olcrtc/issues/15"


def cell(provider, client, transport, scenario, status="pass", known=None):
    c = {
        "id": f"engine-linux/{provider}/{transport}/{client}/{scenario}", "platform": "engine-linux",
        "provider": provider, "transport": transport, "client": client, "scenario": scenario, "status": status,
        "metrics": {}, "thresholds": {}, "failures": [] if status == "pass" else ["something failed"],
        "duration_s": 1,
    }
    if known is not None:
        c["known"] = known
    return c


def report(cells, executed=None, commit=PIN_SHA, target="local", started="2026-01-02T03:04:05Z", duration=100):
    passed = sum(1 for c in cells if c["status"] == "pass")
    return {
        "schema": 1, "engine_commit": commit, "engine_ref": PIN_VERSION, "app_version": "1.0.999", "target": target,
        "runner": "Linux/fake", "started_at": started, "duration_s": duration, "planned": len(cells),
        "executed": len(cells) if executed is None else executed, "passed": passed, "failed": len(cells) - passed,
        "failed_known": sum(1 for c in cells if c["status"] != "pass" and c.get("known")), "cells": cells,
    }


class Merge(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.legs = self.root / "legs"

    def tearDown(self):
        self.tmp.cleanup()

    def part(self, provider, client, plan=None, rep=None):
        # What the verdict's download makes of the legs' artifacts (gate.yml):
        # merged into one directory, each under its provider.
        d = self.legs / provider / client
        d.mkdir(parents=True, exist_ok=True)
        if plan is not None:
            (d / "plan.txt").write_text("".join(f"{line}\n" for line in plan))
        if rep is not None:
            (d / "gate-report.json").write_text(rep if isinstance(rep, str) else json.dumps(rep))

    def merged(self, providers="jitsi,telemost,wbstream"):
        out = self.root / "gate-report.json"
        with redirect_stdout(StringIO()):
            code = merge.main(["merge", "--report-name", "gate-report.json", "--legs-dir", str(self.legs),
                               "--providers", providers, "--expect-commit", PIN_SHA, "--engine-ref", PIN_VERSION,
                               "--app-version", "1.0.999", "--mode", "release", "--out", str(out)])
        self.assertEqual(0, code)
        return json.loads(out.read_text())

    def by_id(self, rep):
        return {c["id"]: c for c in rep["cells"]}

    def full_leg(self, provider, transport):
        for client, scenarios in (("cli", ["S0"]), ("mobile", ["S0", "S1"])):
            cells = [cell(provider, client, transport, s) for s in scenarios]
            self.part(provider, client, [c["id"] for c in cells], report(cells, duration=50 if client == "cli" else 70))

    def test_three_whole_legs_merge_into_one_report(self):
        self.full_leg("jitsi", "datachannel")
        self.full_leg("telemost", "vp8channel")
        self.full_leg("wbstream", "vp8channel")
        rep = self.merged()
        self.assertEqual((9, 9, 9, 0), (rep["planned"], rep["executed"], rep["passed"], rep["failed"]))
        self.assertEqual(1, rep["schema"])
        self.assertEqual("local", rep["target"])
        self.assertEqual(PIN_SHA, rep["engine_commit"])
        self.assertEqual(120, rep["duration_s"])
        self.assertEqual(6, len(rep["legs"]))
        self.assertEqual(sorted(c["id"] for c in rep["cells"]), [c["id"] for c in rep["cells"]])
        self.assertEqual({"lean"}, {leg["build"] for leg in rep["legs"] if leg["client"] == "mobile"})
        green = self.root / "green.json"
        green.write_text(json.dumps(rep))
        with redirect_stdout(StringIO()):
            code = merge.main(["decide", "--report", str(green), "--unit-result", "success",
                               "--suite-result", "success", "--step", "merge=success"])
        self.assertEqual(0, code)

    def test_a_one_provider_run_is_found_like_a_full_one(self):
        # A manual run for one provider uploads one artifact. Unmerged,
        # download-artifact would have put it straight into legs/ and this
        # green leg would have merged as two "never planned" failures.
        self.full_leg("telemost", "vp8channel")
        rep = self.merged(providers="telemost")
        self.assertEqual((3, 3, 3, 0), (rep["planned"], rep["executed"], rep["passed"], rep["failed"]))
        self.assertEqual(["present", "present"], [leg["report"] for leg in rep["legs"]])

    def test_a_missing_leg_becomes_did_not_run_cells(self):
        self.full_leg("jitsi", "datachannel")
        self.full_leg("telemost", "vp8channel")
        # wbstream: the plans were uploaded, the runs wrote nothing (timeout, crash, missing token).
        self.part("wbstream", "cli", ["engine-linux/wbstream/vp8channel/cli/S0"])
        self.part("wbstream", "mobile", ["engine-linux/wbstream/vp8channel/mobile/S0", "engine-linux/wbstream/vp8channel/mobile/S1"])
        rep = self.merged()
        cells = self.by_id(rep)
        lost = cells["engine-linux/wbstream/vp8channel/mobile/S1"]
        self.assertEqual("fail", lost["status"])
        self.assertTrue(lost["failures"][0].startswith("did not run: the wbstream/mobile run wrote no report"), lost)
        self.assertEqual((9, 6, 6, 3), (rep["planned"], rep["executed"], rep["passed"], rep["failed"]))
        self.assertEqual("missing", [leg for leg in rep["legs"] if leg["provider"] == "wbstream"][0]["report"])

    def test_a_leg_that_left_nothing_fails_as_a_marker_cell(self):
        self.full_leg("jitsi", "datachannel")
        rep = self.merged(providers="jitsi,telemost")
        marker = self.by_id(rep)["engine-linux/telemost/*/cli/*"]
        self.assertEqual("fail", marker["status"])
        self.assertIn("left neither a plan nor a report", marker["failures"][0])
        self.assertEqual(2, rep["failed"])
        self.assertLess(rep["executed"], rep["planned"])

    def test_a_planned_cell_the_report_does_not_carry_did_not_run(self):
        kept = cell("jitsi", "cli", "datachannel", "S0")
        self.part("jitsi", "cli", [kept["id"], "engine-linux/jitsi/datachannel/cli/S6"], report([kept]))
        self.part("jitsi", "mobile", ["engine-linux/jitsi/datachannel/mobile/S0"],
                  report([cell("jitsi", "mobile", "datachannel", "S0")]))
        rep = self.merged(providers="jitsi")
        lost = self.by_id(rep)["engine-linux/jitsi/datachannel/cli/S6"]
        self.assertEqual(["did not run: not in the jitsi/cli report"], lost["failures"])
        self.assertEqual((3, 2, 1), (rep["planned"], rep["executed"], rep["failed"]))

    def test_a_report_for_another_engine_or_target_is_rejected(self):
        c = cell("jitsi", "cli", "datachannel", "S0")
        self.part("jitsi", "cli", [c["id"]], report([c], commit="f" * 40))
        m = cell("jitsi", "mobile", "datachannel", "S0")
        self.part("jitsi", "mobile", [m["id"]], report([m], target="link"))
        rep = self.merged(providers="jitsi")
        cells = self.by_id(rep)
        self.assertIn("rejected (it names another engine commit)", cells[c["id"]]["failures"][0])
        self.assertIn("rejected (target 'link', not local)", cells[m["id"]]["failures"][0])
        self.assertEqual((2, 0, 0, 2), (rep["planned"], rep["executed"], rep["passed"], rep["failed"]))

    def test_repeated_or_foreign_cells_reject_the_report(self):
        c = cell("jitsi", "cli", "datachannel", "S0")
        self.part("jitsi", "cli", [c["id"]], report([c, dict(c)]))
        foreign = cell("jitsi", "cli", "datachannel", "S1")
        self.part("jitsi", "mobile", [], report([foreign]))
        self.part("jitsi", "mobile", ["engine-linux/jitsi/datachannel/mobile/S0"])
        rep = self.merged(providers="jitsi")
        legs = {leg["client"]: leg for leg in rep["legs"]}
        self.assertEqual(("rejected", "a cell is listed twice"), (legs["cli"]["report"], legs["cli"]["reason"]))
        self.assertEqual("a cell belongs to another leg or flavour", legs["mobile"]["reason"])
        self.assertNotIn(foreign["id"], self.by_id(rep))
        self.assertEqual(0, rep["passed"])

    def test_a_report_that_is_not_json_is_rejected(self):
        self.part("jitsi", "cli", ["engine-linux/jitsi/datachannel/cli/S0"], "{not json")
        rep = self.merged(providers="jitsi")
        self.assertIn("rejected (it is not JSON)", self.by_id(rep)["engine-linux/jitsi/datachannel/cli/S0"]["failures"][0])

    def decided(self, rep):
        path = self.root / "decide.json"
        path.write_text(json.dumps(rep))
        buf = StringIO()
        with redirect_stdout(buf):
            code = merge.main(["decide", "--report", str(path), "--unit-result", "success",
                               "--suite-result", "success", "--step", "merge=success"])
        return code, buf.getvalue()

    def test_known_failures_are_kept_counted_per_leg_and_summed_and_fail_no_verdict(self):
        cli = [cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9), cell("jitsi", "cli", "datachannel", "S0")]
        mobile = [cell("jitsi", "mobile", "seichannel", "S0", known=ISSUE9),
                  cell("jitsi", "mobile", "datachannel", "S2", "fail", known=ISSUE15),
                  cell("jitsi", "mobile", "datachannel", "S0")]
        self.part("jitsi", "cli", [c["id"] for c in cli], report(cli))
        self.part("jitsi", "mobile", [c["id"] for c in mobile], report(mobile))
        self.full_leg("telemost", "vp8channel")
        rep = self.merged(providers="jitsi,telemost")
        known = {f"{leg['provider']}/{leg['client']}": leg["failed_known"] for leg in rep["legs"]}
        self.assertEqual({"jitsi/cli": 1, "jitsi/mobile": 1, "telemost/cli": 0, "telemost/mobile": 0}, known)
        self.assertEqual((8, 2, 2), (rep["planned"], rep["failed"], rep["failed_known"]))
        self.assertEqual(rep["failed_known"], sum(known.values()))
        cells = self.by_id(rep)
        self.assertEqual(ISSUE15, cells["engine-linux/jitsi/datachannel/mobile/S2"]["known"])
        self.assertEqual(ISSUE9, cells["engine-linux/jitsi/seichannel/mobile/S0"]["known"], "a known cell that passed")
        self.assertNotIn("known", cells["engine-linux/jitsi/datachannel/cli/S0"])
        code, out = self.decided(rep)
        self.assertEqual(0, code, out)
        self.assertIn("the gate passed (2 known failure(s), tracked by issues)", out)

    def test_a_failure_off_the_known_list_still_fails_the_verdict(self):
        cli = [cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9), cell("jitsi", "cli", "datachannel", "S0", "fail")]
        mobile = [cell("jitsi", "mobile", "datachannel", "S2", "fail", known=ISSUE15)]
        self.part("jitsi", "cli", [c["id"] for c in cli], report(cli))
        self.part("jitsi", "mobile", [c["id"] for c in mobile], report(mobile))
        rep = self.merged(providers="jitsi")
        self.assertEqual((3, 2), (rep["failed"], rep["failed_known"]))
        code, out = self.decided(rep)
        self.assertEqual(1, code)
        self.assertIn("::error title=Release gate::3 of 3 cells failed (2 known, tracked by issues)", out)

    def test_a_cell_the_merge_fails_is_never_known(self):
        # Both lost cells are on the engine's list (jitsi/datachannel/*/S6 and
        # jitsi/datachannel/mobile/S2), and the mobile report marks its other
        # cell known; but the cli run wrote no report and the mobile report
        # lost S2, so the merge fails both as not run, and never as known.
        self.part("jitsi", "cli", ["engine-linux/jitsi/datachannel/cli/S6"])
        kept = cell("jitsi", "mobile", "datachannel", "S5", "fail", known=ISSUE11)
        self.part("jitsi", "mobile", [kept["id"], "engine-linux/jitsi/datachannel/mobile/S2"], report([kept]))
        rep = self.merged(providers="jitsi")
        cells = self.by_id(rep)
        for cell_id in ("engine-linux/jitsi/datachannel/cli/S6", "engine-linux/jitsi/datachannel/mobile/S2"):
            self.assertTrue(cells[cell_id]["failures"][0].startswith("did not run"), cells[cell_id])
            self.assertNotIn("known", cells[cell_id])
        self.assertEqual((3, 1), (rep["failed"], rep["failed_known"]))
        self.assertEqual([0, 1], [leg["failed_known"] for leg in rep["legs"]])
        code, out = self.decided(rep)
        self.assertEqual(1, code)
        self.assertIn("3 of 3 cells failed (1 known, tracked by issues)", out)
        self.assertIn("2 of 3 planned cells did not run", out)

    def test_a_known_issue_that_is_not_text_rejects_the_report(self):
        c = cell("jitsi", "cli", "datachannel", "S0", "fail", known=9)
        self.part("jitsi", "cli", [c["id"]], report([c]))
        rep = self.merged(providers="jitsi")
        leg = rep["legs"][0]
        self.assertEqual(("rejected", "a cell's known issue is not text"), (leg["report"], leg["reason"]))
        self.assertNotIn("known", self.by_id(rep)[c["id"]])
        self.assertEqual(0, rep["failed_known"])

    def test_the_skipped_stub_says_why_and_is_no_baseline(self):
        out_json, out_md = self.root / "s.json", self.root / "s.md"
        with redirect_stdout(StringIO()):
            code = merge.main(["skipped", "--reason", "the relay <is> down\ntoday", "--engine-commit", PIN_SHA,
                               "--engine-ref", PIN_VERSION, "--app-version", "1.0.999",
                               "--run-url", "https://example.invalid/run",
                               "--out-json", str(out_json), "--out-md", str(out_md)])
            decided = merge.main(["decide", "--report", str(out_json), "--skip", "true", "--step", "upload=success"])
        self.assertEqual(0, code)
        rep = json.loads(out_json.read_text())
        self.assertEqual((1, True, "local", 0, []), (rep["schema"], rep["skipped"], rep["target"], rep["planned"], rep["cells"]))
        self.assertEqual("the relay <is> down today", rep["reason"])
        md = out_md.read_text()
        self.assertIn("Gate skipped by request", md)
        self.assertIn("the relay &lt;is&gt; down today", md)
        self.assertEqual(0, decided)

    def test_the_header_lists_the_legs_and_a_limited_run(self):
        self.full_leg("jitsi", "datachannel")
        path = self.root / "m.json"
        path.write_text(json.dumps(self.merged(providers="jitsi")))
        buf = StringIO()
        with redirect_stdout(buf):
            merge.main(["header", "--report", str(path), "--limited", "true", "--providers", "jitsi",
                        "--on-proofkit", "true", "--ahead-by", "3", "--unit-result", "success", "--suite-result", "success"])
        text = buf.getvalue()
        self.assertIn("| `jitsi/mobile` | lean | 2 | present | 2 | 0 |", text)
        self.assertIn("**Limited run**: providers `jitsi`", text)
        self.assertIn("which is 3 commit(s) ahead", text)


    def test_the_header_counts_known_failures_in_the_failed_column(self):
        cli = [cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9), cell("jitsi", "cli", "datachannel", "S0")]
        self.part("jitsi", "cli", [c["id"] for c in cli], report(cli))
        self.part("jitsi", "mobile", ["engine-linux/jitsi/datachannel/mobile/S0"],
                  report([cell("jitsi", "mobile", "datachannel", "S0", "fail")]))
        path = self.root / "m.json"
        path.write_text(json.dumps(self.merged(providers="jitsi")))
        buf = StringIO()
        with redirect_stdout(buf):
            merge.main(["header", "--report", str(path)])
        text = buf.getvalue()
        self.assertIn("| `jitsi/cli` | default | 2 | present | 1 | 1 (1 known) |", text)
        self.assertIn("| `jitsi/mobile` | lean | 1 | present | 0 | 1 |", text)


class Decide(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "r.json"

    def tearDown(self):
        self.tmp.cleanup()

    def decide(self, rep, *extra, unit="success", suite="success"):
        self.path.write_text(json.dumps(rep))
        return subprocess.run(["python3", str(HERE / "gate-merge-reports.py"), "decide", "--report", str(self.path),
                               "--unit-result", unit, "--suite-result", suite, "--step", "merge=success", *extra],
                              capture_output=True, text=True, timeout=60)

    def test_every_reason_is_listed(self):
        rep = report([cell("jitsi", "cli", "datachannel", "S0"), cell("jitsi", "cli", "datachannel", "S6", "fail")], executed=1)
        r = self.decide(rep, "--step", "leak check=failure", unit="failure", suite="cancelled")
        self.assertEqual(1, r.returncode)
        for reason in ("engine unit tests: failure", "suite legs: cancelled", "1 of 2 cells failed",
                       "1 of 2 planned cells did not run", "step 'leak check': failure"):
            self.assertIn(reason, r.stdout)

    def test_green_is_green_and_a_regression_fails_only_at_fail(self):
        rep = report([cell("jitsi", "cli", "datachannel", "S0")])
        self.assertEqual(0, self.decide(rep, "--regression", "true", "--severity", "warn").returncode)
        r = self.decide(rep, "--regression", "true", "--severity", "fail")
        self.assertEqual(1, r.returncode)
        self.assertIn("regression", r.stdout)

    def test_known_failures_alone_do_not_fail_the_gate(self):
        rep = report([cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9),
                      cell("jitsi", "mobile", "datachannel", "S2", "fail", known=ISSUE15),
                      cell("jitsi", "cli", "datachannel", "S0")])
        r = self.decide(rep)
        self.assertEqual(0, r.returncode, r.stdout)
        self.assertIn("the gate passed (2 known failure(s), tracked by issues)", r.stdout)

    def test_an_unknown_failure_fails_the_gate_and_says_how_many_are_known(self):
        rep = report([cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9),
                      cell("jitsi", "mobile", "datachannel", "S2", "fail", known=ISSUE15),
                      cell("jitsi", "mobile", "datachannel", "S3", "fail"), cell("jitsi", "cli", "datachannel", "S0")])
        r = self.decide(rep)
        self.assertEqual(1, r.returncode)
        self.assertIn("3 of 4 cells failed (2 known, tracked by issues)", r.stdout)

    def test_a_report_without_the_known_count_reads_every_failure_as_one(self):
        rep = report([cell("jitsi", "cli", "seichannel", "S0", "fail", known=ISSUE9)])
        del rep["failed_known"]
        r = self.decide(rep)
        self.assertEqual(1, r.returncode)
        self.assertIn("::error title=Release gate::1 of 1 cells failed\n", r.stdout)

    def test_nothing_planned_is_not_a_pass(self):
        self.assertEqual(1, self.decide(report([])).returncode)

    def test_a_skip_needs_a_report_that_says_so(self):
        r = self.decide(report([cell("jitsi", "cli", "datachannel", "S0")]), "--skip", "true")
        self.assertEqual(1, r.returncode)
        self.assertIn("does not say it was skipped", r.stdout)


class PreviousReport(unittest.TestCase):
    def setUp(self):
        self.box = Sandbox()
        self.out = self.box.root / "previous.json"

    def tearDown(self):
        self.box.close()

    def release(self, tag, body=None, draft=False, asset="gate-report.json"):
        rid = abs(hash(tag)) % 100000
        assets = [{"name": "Ghostlane-fake.apk", "url": f"/assets/{rid}0"}]
        if body is not None:
            assets.append({"name": asset, "url": f"/assets/{rid}"})
        return {"tag_name": tag, "draft": draft, "assets": assets}, (f"/assets/{rid}", body)

    def previous(self, releases, bodies, current=""):
        with FakeGitHub({}) as gh:
            for rel in releases:
                for a in rel["assets"]:
                    a["url"] = gh.url + a["url"]
            gh.routes["/repos/fake-owner/fake-app/releases?per_page=100&page=1"] = (200, releases)
            for path, body in bodies:
                if body is not None:
                    gh.routes[path] = (200, json.dumps(body).encode())
            return self.box.run("gate-previous-report.sh", str(self.out), current,
                                env=self.box.env(gh.url, GH_REPO="fake-owner/fake-app"))

    def test_the_newest_comparable_release_report_is_the_baseline(self):
        good = report([cell("jitsi", "cli", "datachannel", "S0")])
        entries = [
            self.release("v1.0.12", good),                                  # the current tag: never itself
            self.release("v1.0.11", {"schema": 1, "skipped": True, "target": "local", "planned": 0}),
            self.release("v1.0.10", report([cell("jitsi", "cli", "datachannel", "S0")], target="link")),
            self.release("v1.0.9", good),
            self.release("v1.0.8", report([cell("jitsi", "cli", "datachannel", "S1")])),
            self.release("nightly", good),
            self.release("ios-cores-fake-b1", good),
            self.release("v1.0.13", good, draft=True),
        ]
        r = self.previous([e[0] for e in entries], [e[1] for e in entries], current="v1.0.12")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual("previous_tag=v1.0.9", r.stdout.strip())
        self.assertEqual(good, json.loads(self.out.read_text()))

    def test_no_report_or_no_listing_is_nothing_and_never_a_failure(self):
        r = self.previous([self.release("v1.0.1")[0]], [])
        self.assertEqual((0, "previous_tag="), (r.returncode, r.stdout.strip()))
        self.assertEqual("", self.out.read_text())
        r = self.box.run("gate-previous-report.sh", str(self.out), env=self.box.env("http://127.0.0.1:9", GH_REPO="fake-owner/fake-app"))
        self.assertEqual((0, "previous_tag="), (r.returncode, r.stdout.strip()))


class Wiring(unittest.TestCase):
    """What the workflows promise, read off their text."""

    workflows = REPO / ".github" / "workflows"
    OWNER = {"GATE_TELEMOST_ROOMS": "telemost", "GATE_WBSTREAM_ROOMS": "wbstream",
             "GATE_WBSTREAM_TOKEN": "wbstream", "GATE_JITSI_HOSTS": "jitsi"}
    ENGINE_NAMES = r"-olcrtc\.gate|OLCRTC_GATE_|olcrtc_lean|cmd/gate-report"

    def read(self, name):
        return (self.workflows / name).read_text()

    def step(self, workflow, name):
        """One step's text, up to the next step or job."""
        text = self.read(workflow)
        rest = text[text.index(f"- name: {name}\n"):]
        end = re.search(r"\n(?:      - name: |  [A-Za-z0-9_-]+:\n)", rest)
        return rest[:end.start()] if end else rest

    def test_gate_hands_a_secret_only_to_its_own_providers_leg(self):
        uses = [line.strip() for line in self.read("gate.yml").splitlines() if "secrets." in line]
        self.assertTrue(uses)
        for line in uses:
            m = re.fullmatch(r"(GATE_[A-Z_]+): \$\{\{ matrix\.provider == '([a-z]+)' && secrets\.(GATE_[A-Z_]+) \|\| '' \}\}", line)
            self.assertIsNotNone(m, line)
            self.assertEqual(m.group(1), m.group(3), line)
            self.assertEqual(self.OWNER[m.group(1)], m.group(2), line)

    def test_the_verdict_finds_every_leg_in_one_layout_however_many_uploaded(self):
        # download-artifact extracts a pattern that matches one artifact
        # straight into its path and several into path/<artifact>/. Merged, with
        # every artifact rooted at gate-artifacts so it carries its provider's
        # directory, the merge's legs/<provider>/<client>/ holds for any count.
        download = self.step("gate.yml", "Download the legs")
        self.assertIn("pattern: gate-leg-*", download)
        self.assertIn("merge-multiple: true", download)
        upload = self.step("gate.yml", "Upload the leg's plans, reports and logs")
        paths = re.findall(r"^ +(gate-artifacts/\S*)$", upload, re.M)
        self.assertEqual(4, len(paths), upload)
        self.assertTrue(all(p.startswith("gate-artifacts/**/") for p in paths), paths)
        self.assertEqual("{provider}", merge.PART_DIR)
        # What is uploaded is what was scrubbed: the same root.
        self.assertIn("GATE_ARTIFACTS: ${{ github.workspace }}/gate-artifacts\n", self.read("gate.yml"))
        self.assertIn('gate-scrub.py "$GATE_ARTIFACTS"', self.step("gate.yml", "Scrub the leg's artifacts"))

    def test_every_provider_is_named_wherever_providers_are_listed(self):
        # One list, gate-resolve.sh's: a provider added there and missing from
        # a list a person or a script reads is a leg that fails or a doc that
        # lies. (salutejazz joined as the fourth.)
        resolve = (HERE / "gate-resolve.sh").read_text()
        providers = re.search(r"^readonly PROVIDERS=\(([^)]*)\)$", resolve, re.M).group(1).split()
        self.assertEqual(["jitsi", "telemost", "wbstream", "salutejazz"], providers)
        run = (HERE / "gate-run.sh").read_text()
        accepted = re.search(r"^ +([a-z| ]+)\) ;;\n +\*\) die \"GATE_PROVIDER must be", run, re.M).group(1)
        self.assertEqual(providers, [p.strip() for p in accepted.split("|")])
        described = re.search(r"description: Providers, comma-separated \(([^)]*)\)", self.read("gate.yml")).group(1)
        self.assertEqual(providers, [p.strip() for p in described.split(",")])
        doc = (REPO / "docs" / "release-gate.md").read_text()
        for p in providers:
            self.assertIn(f"`{p}`", doc, p)
            self.assertIn(p, resolve.split("check() {", 1)[1], f"gate-resolve.sh check has no arm for {p}")

    def test_gate_leaves_the_engine_names_to_gate_run_sh(self):
        self.assertNotRegex(self.read("gate.yml"), self.ENGINE_NAMES)

    def test_release_leaves_the_engine_names_to_gate_run_sh(self):
        self.assertNotRegex(self.read("release.yml"), self.ENGINE_NAMES)

    def test_release_builds_and_publishes_nothing_without_the_gate(self):
        release = self.read("release.yml")
        for job in ("build-windows", "build-macos", "build-linux", "build-android", "build-ios"):
            block = release.split(f"\n  {job}:\n", 1)[1]
            self.assertRegex(block.split("\n", 1)[0], r"needs: \[release_version, plan, gate\]", job)
        publish = release.split("\n  publish-nightly:\n", 1)[1]
        self.assertIn("      - gate\n", publish)
        self.assertIn("needs.gate.result == 'success'", publish)
        self.assertIn("pattern: Ghostlane-*", publish)
        self.assertIn("uses: ./.github/workflows/gate.yml", release)
        self.assertNotIn("secrets: inherit", release)

    def test_release_checks_the_pinned_commit_out_everywhere(self):
        release = self.read("release.yml")
        self.assertNotIn("OLCBOX_OLCRTC_REF", release)
        self.assertIn("olcrtc_ref: ${{ steps.pin.outputs.olcrtc_sha }}", release)
        self.assertEqual(5, release.count("ref: ${{ needs.release_version.outputs.olcrtc_ref }}"))

    def test_pr_checks_build_the_pinned_commit(self):
        checks = self.read("pr-checks.yml")
        self.assertNotIn("ref: proofkit", checks)
        self.assertIn("ref: ${{ steps.pin.outputs.olcrtc_sha }}", checks)
        self.assertIn("python3 scripts/test_gate_scripts.py", checks)


class Scope(unittest.TestCase):
    """pr-checks.yml's "What changed": what a push or a pull request is compared with.

    The step's own script, cut out of the workflow, runs in a scratch
    repository of three commits — README, then sharedUI/src/commonMain, then a
    pin — so what is tested is the shell the runner executes, not a copy of it.
    (2026-09-22: a nine-commit push whose last commit was a pin change skipped
    the Apple compilation, because a push was compared with HEAD~1.)
    """

    ZERO = "0" * 40
    ABSENT = "0123456789abcdef0123456789abcdef01234567"

    @classmethod
    def setUpClass(cls):
        text = (REPO / ".github" / "workflows" / "pr-checks.yml").read_text()
        cls.job = text.split("\n  scope:\n", 1)[1].split("\n  checks:\n", 1)[0]
        rest = text[text.index("      - id: paths\n"):]
        end = re.search(r"\n(?:      [-#] |  [A-Za-z0-9_-]+:\n)", rest)
        cls.text = rest[:end.start()] if end else rest
        lines = cls.text.split("        run: |\n", 1)[1].splitlines()
        assert all(not line or line.startswith(" " * 10) for line in lines), cls.text
        cls.script = "".join(line[10:] + "\n" for line in lines)

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        self.env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                    "GIT_AUTHOR_NAME": "gate", "GIT_AUTHOR_EMAIL": "gate@example.invalid",
                    "GIT_COMMITTER_NAME": "gate", "GIT_COMMITTER_EMAIL": "gate@example.invalid"}
        self.git("init", "-q")
        self.readme = self.commit("README.md")
        self.kotlin = self.commit("sharedUI/src/commonMain/kotlin/Shared.kt")
        self.pin = self.commit("scripts/cores-pins.sh")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, env=self.env, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, path):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(f"{path}\n")
        self.git("add", path)
        self.git("commit", "-q", "-m", path)
        return self.git("rev-parse", "HEAD")

    def shared_kotlin(self, head, pr_base="", before=""):
        """The step's shared_kotlin output with HEAD at `head`."""
        self.git("checkout", "-q", "--detach", head)
        output, summary = self.tmp / "output", self.tmp / "summary"
        output.write_text("")
        summary.write_text("")
        (self.tmp / "paths.sh").write_text(self.script)
        env = {**self.env, "PR_BASE": pr_base, "PUSH_BEFORE": before,
               "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
        # The runner's default shell for a run: block.
        r = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", str(self.tmp / "paths.sh")],
                           cwd=self.repo, env=env, capture_output=True, text=True)
        self.assertEqual(0, r.returncode, r.stderr)
        outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())
        return outputs["shared_kotlin"]

    def test_the_step_reads_the_pushs_before_and_the_prs_base(self):
        self.assertIn("          PR_BASE: ${{ github.event.pull_request.base.sha }}\n", self.text)
        self.assertIn("          PUSH_BEFORE: ${{ github.event_name == 'push' && github.event.before || '' }}\n", self.text)
        self.assertNotIn("${{", self.script)
        # before can be compared with only when the clone has the history.
        self.assertIn("fetch-depth: 0", self.job)

    def test_a_push_is_compared_with_the_tip_before_it_not_with_its_last_commit(self):
        self.assertEqual("true", self.shared_kotlin(self.pin, before=self.readme))
        self.assertEqual("false", self.shared_kotlin(self.pin, before=self.kotlin))

    def test_a_new_branchs_zero_sha_falls_back_to_the_last_commit(self):
        self.assertEqual("true", self.shared_kotlin(self.kotlin, before=self.ZERO))
        self.assertEqual("false", self.shared_kotlin(self.pin, before=self.ZERO))

    def test_a_before_the_clone_does_not_have_falls_back_to_the_last_commit(self):
        # A force push's old tip is not fetched by fetch-depth: 0.
        self.assertEqual("true", self.shared_kotlin(self.kotlin, before=self.ABSENT))
        self.assertEqual("false", self.shared_kotlin(self.pin, before=self.ABSENT))

    def test_a_first_commit_with_nothing_to_compare_with_runs_apple(self):
        self.assertEqual("true", self.shared_kotlin(self.readme, before=self.ZERO))

    def test_a_pull_request_is_compared_with_its_base_whatever_before_says(self):
        self.assertEqual("false", self.shared_kotlin(self.pin, pr_base=self.kotlin, before=self.readme))
        self.assertEqual("true", self.shared_kotlin(self.pin, pr_base=self.readme, before=self.kotlin))


if __name__ == "__main__":
    unittest.main()
