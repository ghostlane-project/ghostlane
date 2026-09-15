# Release Gate, Plan B: the platform side and the app's release wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the engine suite from Plan A a fleet node to run against and a place in the app's release: a static load file on the APP servers, a CI partner account whose traffic is unbilled and invisible to the fraud detectors, a `gate` job in `release.yml` that no build starts without, the report attached to every release and compared with the previous one.

**Architecture:** The platform changes are two small ones (a generated static file on both APPs; one SQL predicate in two detectors). The app change is one job in `release.yml` plus three edits around it (Android builds from the pinned engine revision, the report becomes a release asset and a section of the notes, a break-glass input). Everything the gate needs from the platform goes through the documented partner API with a repository secret.

**Tech Stack:** GitHub Actions, bash + `curl` + `jq` in workflow steps, the coordinator (Rust, sqlx), nginx on the APPs, the engine's `cmd/gate-report`.

**Spec:** `docs/superpowers/specs/2026-09-15-release-gate-design.md` §5, §8 (app side), §9, §10. **Depends on Plan A** (`docs/superpowers/plans/2026-09-15-release-gate-engine.md`) being merged on the engine's `proofkit` branch: the `gate` job runs `internal/gate` and `cmd/gate-report` from there.

**Repositories:** platform `/opt/proofkit` (GitHub `romanpodpriatov/proofkit-dvpn`; code under `proofkit-dvpn/`, workflow at `.github/workflows/ci.yml`, deploys to both APPs on push to `main`), app `/root/olcbox-fork` (GitHub `romanpodpriatov/olcbox`, push to remote `proofkit`, branch `main`). Commit style and attribution lines as in Plan A.

## Global Constraints

- The partner key, the link, the room ids and the subscriber id never appear in a workflow file, a log, the report or the release. Secrets go through `secrets.*`; every value derived from them is masked with `::add-mask::` before use (spec §10).
- The gate's traffic is unbilled and no operator credit: partner terms `billing_exempt = true`, `operator_credit_exempt = true` (spec §9).
- A release with a failed gate publishes nothing. The only way past a red gate is the `gate: skip` input, which prints a warning into the summary and the release notes.
- Relative regressions are `warn` for the first three releases with a report, then `fail` (spec §6). The switch is one value in the workflow (`GATE_COMPARE_SEVERITY`).
- One engine revision per release: Android, iOS and the gate all use the 12-hex revision from `scripts/cores-pins.sh`.

---

## File structure

```
platform  .github/workflows/ci.yml                       deploy step: gate files on APP-1 and APP-2
platform  proofkit-dvpn/coordinator/src/services/fraud_limits.rs   exclude billing-exempt partners' sessions
platform  proofkit-dvpn/coordinator/src/services/anomaly.rs        same predicate in the hourly snapshot
platform  proofkit-dvpn/docs/release-gate-partner.md      runbook: the ci-gate partner, rooms, secrets
app       .github/workflows/release.yml                    gate input, pinned olcrtc_ref, gate job, notes + assets
app       scripts/gate-link.sh                             partner API → link (masked), used by the gate job
app       scripts/gate-previous-report.sh                  previous release's gate-report.json, if any
app       docs/release-gate.md                             what the gate is, how to read it, how to skip it
```

---

### Task 1: The load files on both APP servers

**Files:**
- Modify: `/opt/proofkit/.github/workflows/ci.yml` (the `Deploy to APP-1` and `Deploy to APP-2` steps)

**Interfaces:** produces `https://proofkit.org/gate/10mb.bin` (10 485 760 random bytes) and `https://proofkit.org/gate/kb` (1024 bytes), served by the existing `location /` from `/opt/proofkit/frontend`. Nothing is committed to git: the files are generated on each server if absent.

- [ ] **Step 1: Add the generation to both deploy steps**

Inside the `ssh root@46.224.160.40 '…'` block of `Deploy to APP-1` (and the same block for APP-2, `46.225.137.200`), before `nginx -s reload`:

```bash
            mkdir -p /opt/proofkit/frontend/gate
            [ -s /opt/proofkit/frontend/gate/10mb.bin ] || head -c 10485760 /dev/urandom > /opt/proofkit/frontend/gate/10mb.bin
            [ -s /opt/proofkit/frontend/gate/kb ] || head -c 1024 /dev/urandom > /opt/proofkit/frontend/gate/kb
```

The `scp -r proofkit-dvpn/frontend/.` line above it is additive, so the generated directory survives deploys.

- [ ] **Step 2: Push and verify through Cloudflare**

Run after the deploy: `curl -sS -o /dev/null -w '%{http_code} %{size_download}\n' https://proofkit.org/gate/10mb.bin` → `200 10485760`; `curl -sS -o /dev/null -w '%{http_code} %{size_download}\n' https://proofkit.org/gate/kb` → `200 1024`. Both APPs: `curl --resolve proofkit.org:443:46.224.160.40 …` and `…:46.225.137.200 …` → the same.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: the gate's load files on both APPs, generated on deploy, never in git"
```

---

### Task 2: Billing-exempt partners' sessions stay out of the detectors

**Files:**
- Modify: `proofkit-dvpn/coordinator/src/services/fraud_limits.rs` (`check_all_sessions`)
- Modify: `proofkit-dvpn/coordinator/src/services/anomaly.rs` (the hourly snapshot query)
- Test: `proofkit-dvpn/coordinator/src/services/fraud_limits.rs` (unit test of the predicate text) — the repository runs no tests against a database (the only Postgres is production), so the SQL is pinned by a test on the query string and verified by a read-only query after deploy.

**Interfaces:** the predicate, used in both queries:

```sql
AND NOT EXISTS (
    SELECT 1 FROM partners p
    WHERE p.billing_account_id = COALESCE(s.payer_account_id, s.user_id)
      AND p.billing_exempt
)
```

It is the same join `session_enforcer.rs` already uses to find a session's partner.

- [ ] **Step 1: Write the failing test**

In `fraud_limits.rs`, next to the existing tests:

```rust
#[cfg(test)]
mod exempt_partner_tests {
    use super::*;

    /// The sessions the hourly fraud pass reads must leave out the sessions of a
    /// billing-exempt partner: the release gate pushes hundreds of megabytes
    /// through the DE origin in minutes, which is exactly the shape the rate
    /// limit calls a Sybil. The predicate is pinned here because there is no
    /// test database to run it against.
    #[test]
    fn active_sessions_query_excludes_billing_exempt_partners() {
        assert!(ACTIVE_SESSIONS_FOR_FRAUD.contains("p.billing_exempt"));
        assert!(ACTIVE_SESSIONS_FOR_FRAUD.contains("COALESCE(s.payer_account_id, s.user_id)"));
        assert!(ACTIVE_SESSIONS_FOR_FRAUD.contains("NOT EXISTS"));
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `cd /opt/proofkit/proofkit-dvpn && cargo test -p coordinator exempt_partner_tests` → `ACTIVE_SESSIONS_FOR_FRAUD` not found.

- [ ] **Step 3: Implement**

In `fraud_limits.rs`, lift the query into a constant and add the predicate:

```rust
/// Active sessions the hourly fraud pass looks at. Sessions paid for by a
/// billing-exempt partner are not in it: that is ourselves (the release gate,
/// support), and their traffic is not a Sybil's.
pub(crate) const ACTIVE_SESSIONS_FOR_FRAUD: &str = r#"
    SELECT s.id, s.origin_id, s.bytes_up, s.bytes_down
      FROM sessions s
     WHERE s.status = 'active'
       AND s.bytes_up + s.bytes_down > 100000000
       AND NOT EXISTS (
           SELECT 1 FROM partners p
            WHERE p.billing_account_id = COALESCE(s.payer_account_id, s.user_id)
              AND p.billing_exempt
       )"#;
```

and `check_all_sessions` uses `sqlx::query_as::<_, (Uuid, Uuid, i64, i64)>(ACTIVE_SESSIONS_FOR_FRAUD)`.

In `anomaly.rs`, the snapshot's `LEFT JOIN sessions s ON s.origin_id = n.id AND s.status = 'active'` becomes:

```sql
LEFT JOIN sessions s
       ON s.origin_id = n.id
      AND s.status = 'active'
      AND NOT EXISTS (
          SELECT 1 FROM partners p
           WHERE p.billing_account_id = COALESCE(s.payer_account_id, s.user_id)
             AND p.billing_exempt
      )
```

so the gate's bytes do not move a node's hourly z-score.

- [ ] **Step 4: Run the coordinator tests and clippy**

Run: `cargo test -p coordinator` → all pass; `cargo clippy -p coordinator --tests -- -D warnings` → clean.

- [ ] **Step 5: Verify against production after the deploy (read-only)**

On DATA: `docker exec -i proofkit-postgres-1 psql -U proofkit -d proofkit_dvpn -c "SELECT count(*) FROM sessions s WHERE s.status='active' AND EXISTS (SELECT 1 FROM partners p WHERE p.billing_account_id = COALESCE(s.payer_account_id, s.user_id) AND p.billing_exempt)"` — the number of currently exempt sessions; after a gate run it is ≥ 1 during the run and the anomaly_flags table gains no `session_rate_limit` row for the DE origin (`SELECT count(*) FROM anomaly_flags WHERE flag_type='session_rate_limit' AND created_at > now() - interval '1 hour'` → 0).

- [ ] **Step 6: Commit**

```bash
git add proofkit-dvpn/coordinator/src/services/fraud_limits.rs proofkit-dvpn/coordinator/src/services/anomaly.rs
git commit -m "fix(fraud): a billing-exempt partner's sessions are not a Sybil, keep them out of the detectors"
```

---

### Task 3: The `ci-gate` partner, its rooms and the secrets (runbook)

**Files:**
- Create: `proofkit-dvpn/docs/release-gate-partner.md`

**Interfaces:** repository secrets — olcbox: `GATE_PARTNER_KEY` (the `pk_…` key), `GATE_DE_ORIGIN_ID` (the DE origin's node id from the catalogue), `GATE_TELEMOST_ROOMS`, `GATE_WBSTREAM_ROOMS`; olcrtc: `GATE_TELEMOST_ROOMS`, `GATE_WBSTREAM_ROOMS`.

- [ ] **Step 1: Write the runbook**

```markdown
# The release gate's partner account

The gate runs the app's engine against the DE origin as a user of the platform.
It is a partner so its traffic is unbilled and outside the fraud detectors, and
so the only credential it holds is a partner key that can be rotated.

## Create it once (admin panel, Partners)

1. New partner: name `ci-gate`, no Mini App (bot token unused), notes "release gate".
2. Wholesale terms: `billing_exempt` on, `operator_credit_exempt` on,
   `olcrtc_enabled` on, credit limit 0.
3. Nodes: grant the DE origin (the node whose catalogue entry lists
   `olcrtc_carriers: ["telemost"]`), no price override.
4. Issue an API key. It is shown once; put it in the olcbox repository secret
   `GATE_PARTNER_KEY`. Put the DE origin id in `GATE_DE_ORIGIN_ID`.

The same by API (admin JWT): `POST /api/v1/admin/partners`, then
`PATCH /api/v1/admin/partners/{id}/wholesale` with
`{"billing_exempt":true,"operator_credit_exempt":true,"olcrtc_enabled":true}`,
`POST /api/v1/admin/partners/{id}/api-key`, `POST /api/v1/admin/partners/{id}/nodes`
with `{"node_id":"<DE origin id>"}`.

## What a gate run does with it

`POST /api/v1/partner/subscribers {"external_id":"ci-gate-<run id>"}` →
`POST …/subscribers/ci-gate-<run id>/connect {"origin_id":"<DE>"}` →
`GET …/sessions/<session_id>/configs?qr=false` → `olcrtc_url` → the run →
`DELETE …/subscribers/ci-gate-<run id>`. One subscriber per run, gone after it.

## Rooms for the local target

Telemost and WB Stream rooms cannot be created by the engine; make two of each
by hand (Telemost: telemost.yandex.ru, "create a meeting"; WB Stream: the
stream page's room id) and store them comma-separated in `GATE_TELEMOST_ROOMS`
and `GATE_WBSTREAM_ROOMS` in both repositories (olcrtc for its own CI, olcbox
for the release). A run takes room `run_number mod 2`. Rooms are secrets: a
stranger in one breaks the pairing.

## Rotation

Issue a new key (invalidates the old), update `GATE_PARTNER_KEY`. Replace a room
the same way. Nothing else to rotate.
```

- [ ] **Step 2: Do it**

Create the partner, grant, key and rooms as the runbook says; set the six secrets (`gh secret set NAME --repo romanpodpriatov/olcbox` / `--repo romanpodpriatov/olcrtc`, values from a file, never from the shell history).

- [ ] **Step 3: Commit the runbook**

```bash
git add proofkit-dvpn/docs/release-gate-partner.md
git commit -m "docs: the release gate's partner account, rooms and secrets"
```

---

### Task 4: The link and the previous report, as scripts the job calls

**Files:**
- Create: `scripts/gate-link.sh`, `scripts/gate-previous-report.sh` (olcbox)

**Interfaces:**
- `scripts/gate-link.sh`: env `GATE_PARTNER_KEY`, `GATE_DE_ORIGIN_ID`, `GATE_RUN_ID`; prints nothing secret; writes the link to the file named by `$1`, masks it, and writes the subscriber id to `$1.subscriber` so `gate-link.sh --cleanup <file>` can delete it. Exit 3 when the node reports no free olcRTC slot after one retry (60 s), so the job fails with that reason.
- `scripts/gate-previous-report.sh <out.json>`: downloads `gate-report.json` from the newest `v1.*` release that has one; exit 0 with an empty file when none (the first release).

- [ ] **Step 1: Write `scripts/gate-link.sh`**

```bash
#!/usr/bin/env bash
# gate-link.sh <link-file>            obtain an olcrtc:// link to the DE origin for this run
# gate-link.sh --cleanup <link-file>  delete the run's subscriber
# Env: GATE_PARTNER_KEY, GATE_DE_ORIGIN_ID, GATE_RUN_ID. Nothing secret is printed.
set -euo pipefail
API="${GATE_API:-https://proofkit.org/api/v1/partner}"
mask() { [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$1" || true; }
api() { curl -sS -m 60 -H "X-Partner-Key: $GATE_PARTNER_KEY" -H 'Content-Type: application/json' "$@"; }

if [ "${1:-}" = "--cleanup" ]; then
  sub=$(cat "$2.subscriber" 2>/dev/null || true)
  [ -n "$sub" ] && api -X DELETE "$API/subscribers/$sub" -o /dev/null -w 'cleanup http=%{http_code}\n'
  exit 0
fi

out="$1"; : "${GATE_PARTNER_KEY:?}" "${GATE_DE_ORIGIN_ID:?}" "${GATE_RUN_ID:?}"
mask "$GATE_PARTNER_KEY"; mask "$GATE_DE_ORIGIN_ID"
sub="ci-gate-$GATE_RUN_ID"; echo "$sub" > "$out.subscriber"
api -X POST -d "{\"external_id\":\"$sub\"}" "$API/subscribers" -o /dev/null -w 'subscriber http=%{http_code}\n'

session=""
for attempt in 1 2; do
  resp=$(api -X POST -d "{\"origin_id\":\"$GATE_DE_ORIGIN_ID\"}" "$API/subscribers/$sub/connect")
  session=$(echo "$resp" | jq -r '.session_id // empty')
  [ -n "$session" ] && break
  code=$(echo "$resp" | jq -r '.error.code // .code // empty')
  echo "connect attempt $attempt: no session (${code:-unknown})"
  [ "$attempt" = 1 ] && sleep 60
done
[ -n "$session" ] || { echo "no olcRTC session on the DE origin"; exit 3; }
mask "$session"
link=$(api "$API/subscribers/$sub/sessions/$session/configs?qr=false" | jq -r '.olcrtc_url // empty')
[ -n "$link" ] || { echo "configs carried no olcrtc_url: the origin has no room"; exit 3; }
mask "$link"
room=$(echo "$link" | sed -E 's#^olcrtc://[^?]+\?[^@]+@([^#]+)#.*#\1#'); mask "$room"; mask "${room##*/}"
key=$(echo "$link" | sed -E 's#^.*\#([0-9a-fA-F]{64}).*#\1#'); mask "$key"
printf '%s' "$link" > "$out"; chmod 600 "$out"
echo "link obtained for the DE origin"
```

- [ ] **Step 2: Write `scripts/gate-previous-report.sh`**

```bash
#!/usr/bin/env bash
# gate-previous-report.sh <out.json>: the newest v1.* release's gate-report.json, or an empty file.
set -euo pipefail
out="$1"; : > "$out"
for tag in $(gh release list --limit 20 --json tagName --jq '.[].tagName' | grep -E '^v1\.' ); do
  if gh release download "$tag" --pattern gate-report.json --output "$out" --clobber 2>/dev/null && [ -s "$out" ]; then
    echo "previous report: $tag"; exit 0
  fi
done
echo "no previous report"
```

- [ ] **Step 3: Test the scripts by hand**

With the secrets exported locally (from files, `chmod 600`): `GATE_RUN_ID=local-$RANDOM scripts/gate-link.sh /tmp/gate.link && wc -c /tmp/gate.link && scripts/gate-link.sh --cleanup /tmp/gate.link` → a 200-ish byte file, `cleanup http=204`. `scripts/gate-previous-report.sh /tmp/prev.json` → `no previous report` (until the first release with the gate). `shellcheck scripts/gate-*.sh` → clean.

- [ ] **Step 4: Commit**

```bash
git add scripts/gate-link.sh scripts/gate-previous-report.sh
git commit -m "build(gate): the link from the partner API and the previous release's report, masked"
```

---

### Task 5: The `gate` job in `release.yml`

**Files:**
- Modify: `.github/workflows/release.yml` (olcbox): `workflow_dispatch.inputs`, `env`, `release_version`, new job `gate`, the five `build-*` jobs' `needs`, the publish job

**Interfaces:** consumes Plan A's `internal/gate` flags and `cmd/gate-report`; Task 3's secrets; Task 4's scripts.

- [ ] **Step 1: The break-glass input and the severity**

Under `workflow_dispatch.inputs` add:

```yaml
      gate:
        description: 'Release gate: required (a red gate publishes nothing) or skip (emergency only, noted in the release)'
        type: choice
        default: 'required'
        options:
          - required
          - skip
```

Under the workflow `env` add `GATE_COMPARE_SEVERITY: "warn"` with the comment `# warn for the first three releases with a report, then fail (spec §6)`.

- [ ] **Step 2: One engine revision for everyone**

In `release_version`'s `version` step, replace both `echo "olcrtc_ref=${OLCBOX_OLCRTC_REF}"` lines with:

```bash
            olcrtc_rev="$(bash -c '. scripts/cores-pins.sh; echo "${OLCRTC_VERSION##*-}"')"
            echo "olcrtc_ref=${olcrtc_rev}" >> "$GITHUB_OUTPUT"
```

so Android and the gate check out the 12-hex revision iOS is pinned to. Keep `OLCBOX_OLCRTC_REF` only for the notes line, changed to print the revision: `echo "- olcrtc revision: \`${{ needs.release_version.outputs.olcrtc_ref }}\`"`.

- [ ] **Step 3: The job**

```yaml
  gate:
    name: Release gate (engine under load)
    needs: [plan, release_version]
    runs-on: ubuntu-latest
    timeout-minutes: 45
    concurrency:
      group: gate-${{ github.repository }}
      cancel-in-progress: false
    env:
      OLCRTC_GATE_ENGINE_REF: ${{ needs.release_version.outputs.olcrtc_ref }}
      OLCRTC_GATE_ENGINE_COMMIT: ${{ needs.release_version.outputs.olcrtc_ref }}
      OLCRTC_GATE_APP_VERSION: ${{ needs.release_version.outputs.version }}
    steps:
      - name: Skipped on request
        if: ${{ inputs.gate == 'skip' }}
        run: |
          echo "::warning::release gate skipped by request; the release notes will say so"
          mkdir -p gate-artifacts && echo '{"schema":1,"skipped":true}' > gate-artifacts/gate-report.json
          echo "### Gate: **skipped by request**" > gate-artifacts/gate-report.md

      - uses: actions/checkout@v7
        if: ${{ inputs.gate != 'skip' }}
        with:
          path: olcbox

      - name: Checkout olcrtc at the pinned revision
        if: ${{ inputs.gate != 'skip' }}
        uses: actions/checkout@v7
        with:
          repository: romanpodpriatov/olcrtc
          ref: ${{ needs.release_version.outputs.olcrtc_ref }}
          path: olcrtc
          submodules: recursive

      - name: Set up Go
        if: ${{ inputs.gate != 'skip' }}
        uses: actions/setup-go@v5
        with:
          go-version: ${{ env.GO_VERSION }}
          cache-dependency-path: olcrtc/go.sum

      - name: Mask the room pools
        if: ${{ inputs.gate != 'skip' }}
        run: |
          for r in $(echo "${{ secrets.GATE_TELEMOST_ROOMS }},${{ secrets.GATE_WBSTREAM_ROOMS }}" | tr ',' ' '); do
            [ -n "$r" ] && echo "::add-mask::$r"
          done

      - name: Engine unit tests, lean build
        if: ${{ inputs.gate != 'skip' }}
        working-directory: olcrtc
        run: go test -count=1 -race -timeout 15m -tags olcrtc_lean ./...

      - name: Gate, local target
        if: ${{ inputs.gate != 'skip' }}
        working-directory: olcrtc
        run: |
          go test -count=1 -tags olcrtc_lean -timeout 34m ./internal/gate -run '^TestGate$' -v \
            -olcrtc.gate -olcrtc.gate-target=local -olcrtc.gate-dir="$GITHUB_WORKSPACE/gate-artifacts/local" \
            -olcrtc.gate-telemost-rooms="${{ secrets.GATE_TELEMOST_ROOMS }}" \
            -olcrtc.gate-wbstream-rooms="${{ secrets.GATE_WBSTREAM_ROOMS }}"

      - name: Link to the DE origin
        if: ${{ inputs.gate != 'skip' }}
        env:
          GATE_PARTNER_KEY: ${{ secrets.GATE_PARTNER_KEY }}
          GATE_DE_ORIGIN_ID: ${{ secrets.GATE_DE_ORIGIN_ID }}
          GATE_RUN_ID: ${{ github.run_id }}
        run: olcbox/scripts/gate-link.sh "$RUNNER_TEMP/gate.link"

      - name: Gate, link target
        if: ${{ inputs.gate != 'skip' }}
        working-directory: olcrtc
        run: |
          OLCRTC_GATE_LINK="$(cat "$RUNNER_TEMP/gate.link")" \
          go test -count=1 -tags olcrtc_lean -timeout 15m ./internal/gate -run '^TestGate$' -v \
            -olcrtc.gate -olcrtc.gate-target=link -olcrtc.gate-clients=mobile \
            -olcrtc.gate-dir="$GITHUB_WORKSPACE/gate-artifacts/link"

      - name: Release the subscriber
        if: ${{ always() && inputs.gate != 'skip' }}
        env:
          GATE_PARTNER_KEY: ${{ secrets.GATE_PARTNER_KEY }}
        run: olcbox/scripts/gate-link.sh --cleanup "$RUNNER_TEMP/gate.link"; rm -f "$RUNNER_TEMP/gate.link"*

      - name: Report and comparison
        if: ${{ always() && inputs.gate != 'skip' }}
        working-directory: olcrtc
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -e
          {
            for t in local link; do
              if [ -f "$GITHUB_WORKSPACE/gate-artifacts/$t/gate-report.json" ]; then
                go run ./cmd/gate-report render "$GITHUB_WORKSPACE/gate-artifacts/$t/gate-report.json"
              else
                echo "### Gate: $t target wrote no report"
              fi
              echo
            done
            "$GITHUB_WORKSPACE/olcbox/scripts/gate-previous-report.sh" "$RUNNER_TEMP/previous.json" >/dev/null
            if [ -s "$RUNNER_TEMP/previous.json" ] && [ -f "$GITHUB_WORKSPACE/gate-artifacts/link/gate-report.json" ]; then
              go run ./cmd/gate-report compare -severity "$GATE_COMPARE_SEVERITY" \
                "$RUNNER_TEMP/previous.json" "$GITHUB_WORKSPACE/gate-artifacts/link/gate-report.json" \
                || echo "compare_regressed=true" >> "$GITHUB_ENV"
            fi
          } | tee "$GITHUB_WORKSPACE/gate-artifacts/gate-report.md" >> "$GITHUB_STEP_SUMMARY"
          cp "$GITHUB_WORKSPACE/gate-artifacts/link/gate-report.json" "$GITHUB_WORKSPACE/gate-artifacts/gate-report.json" 2>/dev/null || true
          if [ "${compare_regressed:-}" = "true" ]; then echo "regression against the previous release at severity fail"; exit 1; fi

      - name: Upload the report for the release
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: ProofKit-gate-report
          path: |
            gate-artifacts/gate-report.json
            gate-artifacts/gate-report.md

      - name: Upload the run's logs
        if: ${{ always() && inputs.gate != 'skip' }}
        uses: actions/upload-artifact@v4
        with:
          name: gate-logs
          path: |
            gate-artifacts/local/**/*.log
            gate-artifacts/local/**/*.csv
            gate-artifacts/local/gate-report.json
```

The link target's `gate-report.json` is the one compared between releases (the fleet pairing is the stable one); the local report is in the logs artifact and the summary.

- [ ] **Step 4: No build without the gate**

Each `build-windows`, `build-macos`, `build-linux`, `build-android`, `build-ios` gets `needs: [release_version, plan, gate]`. A `gate: skip` run passes the job (it exits 0 after the warning) so the builds still start.

- [ ] **Step 5: The report in the release**

In the publish job, after `Keep the debug symbols out of the release`, add:

```yaml
      - name: Gate report into the notes
        run: |
          if [ -f dist/ProofKit-gate-report/gate-report.md ]; then
            { echo; echo "## Gate"; echo; cat dist/ProofKit-gate-report/gate-report.md; } > gate-section.md
            rm -f dist/ProofKit-gate-report/gate-report.md
          else
            printf '\n## Gate\n\nno report\n' > gate-section.md
          fi
```

and in the `versioned-notes.md` assembly, after the `pending.md` block and before `cat release-notes.md`, add `cat gate-section.md; echo`. `dist/ProofKit-gate-report/gate-report.json` stays in `dist`, so the existing asset loop uploads it as `gate-report.json` — the file `gate-previous-report.sh` looks for next time.

- [ ] **Step 6: Run a release with `platforms=test-macos`? No — with `platforms=linux`**

Dispatch `release.yml` with `platforms=linux`, `play_track=internal`, `gate=required` (a Linux-only build is the cheapest full pass through the gate and the publish path). Expect: the `gate` job green with two tables in its summary, `build-linux` started after it, the versioned release carrying `gate-report.json` and a "## Gate" section. Then dispatch once with `gate=skip` and `platforms=linux` to see the warning path publish with "skipped by request".

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): no build without the gate; Android from the pinned engine; the report on every release"
```

---

### Task 6: `docs/release-gate.md`

**Files:**
- Create: `docs/release-gate.md` (olcbox)

- [ ] **Step 1: Write it**

```markdown
# The release gate

Before any platform builds, `release.yml` runs the engine under load: the
pinned engine revision, the build the phones ship (`olcrtc_lean`,
`mobile.Runtime`), against the real relays with a server it starts and against
the fleet's DE origin through the partner API. A red gate publishes nothing.

- What runs and what it must meet: `internal/gate` and `internal/gate/thresholds.go`
  in the engine; `docs/gate.md` there explains a report.
- Where to read it: the job summary of `Release gate (engine under load)`, the
  "Gate" section of the release notes, `gate-report.json` among the assets.
- Release to release: the link target's report is compared with the previous
  release's; a throughput drop over 25 % or a memory rise over 25 % is a
  regression. `GATE_COMPARE_SEVERITY` in the workflow is `warn` for the first
  three releases and `fail` after.
- Skipping: dispatch with `gate: skip`. The summary warns and the release notes
  say "skipped by request". For emergencies, not for a red cell you dislike.
- Secrets: `GATE_PARTNER_KEY`, `GATE_DE_ORIGIN_ID`, `GATE_TELEMOST_ROOMS`,
  `GATE_WBSTREAM_ROOMS`; how they are made and rotated is in the platform
  repository, `docs/release-gate-partner.md`. Nothing from them reaches a log,
  a report or a release.
- Adding a scenario: register it in the engine's `internal/gate/scenarios.go`
  with a verdict in `verdict.go`; the report's cell count is how the suite's
  growth shows.
```

- [ ] **Step 2: Commit**

```bash
git add docs/release-gate.md
git commit -m "docs: the release gate, where to read it and how to skip it"
```

---

## Self-review

**Spec coverage.** §5 load endpoints (fleet side) → Task 1; §8 app side (gate job before builds, pinned revision for Android, unit tests lean, local plan, link plan, previous report and compare, assets and notes, nightly through the same job) → Task 5; §9 partner account, flow, cleanup, detector exclusion, slot retry → Tasks 2, 3, 4; §10 secrecy → Tasks 4, 5 (masking, no link logs uploaded — the link target's `gate-dir` is not in the logs artifact); §6 severity switch → Task 5. Rollout §"Rollout" steps 2–4 are Tasks 1–3 then 5.

**Placeholders.** None. The node id and key are secrets by design, not placeholders.

**Type consistency.** Artifact name `ProofKit-gate-report` (Task 5) is what the publish step reads (Task 5, step 5); the file names `gate-report.json` / `gate-report.md` match Plan A's writer and renderer; `gate-link.sh`'s outputs (`<file>`, `<file>.subscriber`) match the job's calls.
