#!/usr/bin/env bash
# The gate's Rust leg: the shipped `ghostlane-engine` as the client of the
# engine's suite, against the three fake carriers (SaluteJazz, VK Calls, MTS
# Link). The harness is the core's own olc/interop of the commit the app pins:
# it starts the Go server at its engine pin and the fake room, and drives the
# engine through a shim that turns the gate's client.json into the engine's
# session file. No carrier service is reached and no secret is read, so this
# runs in every gate run, a dry one too (decision D12, check K4).
#
#   gate-fakes.sh <core dir> <ghostlane-engine binary> <out dir>
#
# <core dir> is a checkout of the core at the pin (scripts/rust-core-checkout.sh).
# For each carrier the suite's report is copied to <out dir>/<carrier>/gate-report.json
# and its log to <out dir>/<carrier>/run.log; a carrier that fails does not stop
# the others, the script then leaves with 1. gate-merge-reports.py rust reads
# <out dir>.
#
# The carriers, their names in the report and the harness script of each are
# spelled here and in gate-merge-reports.py (RUST_CARRIERS) only.
set -euo pipefail

die() {
  echo "::error title=Release gate::$*" >&2
  exit 1
}

core="${1:-}" bin="${2:-}" out="${3:-}"
[ -n "${out}" ] || die "usage: gate-fakes.sh <core dir> <ghostlane-engine binary> <out dir>"
[ -x "${bin}" ] || die "no engine binary at ${bin}"
interop="${core}/olc/interop"
[ -f "${interop}/lib.sh" ] || die "the core at ${core} has no olc/interop (is it a checkout at the pin?)"
case "${out}" in /*) ;; *) die "the out dir must be absolute" ;; esac
mkdir -p "${out}"

# The harness's own names for its scratch space and its shim writer.
# shellcheck source=/dev/null
. "${interop}/lib.sh"
shim="${out}/olc-engine-client"
olc_engine_client "${bin}" "${shim}"

# carrier | the harness script | what it is told after the client and "gate"
carriers=(
  "salutejazz|fakeroom.sh|-olcrtc.gate-flavours=rust"
  "vkcalls|vkcalls.sh|"
  "mtslink|mtslink.sh|"
)

failed=0
for entry in "${carriers[@]}"; do
  IFS='|' read -r carrier script extra <<<"${entry}"
  dir="${out}/${carrier}"
  mkdir -p "${dir}"
  rm -rf "${OLC_WORK}/gate-artifacts"
  echo "::group::${carrier}: ${script} gate (the rust client on the fake)"
  set +e
  # shellcheck disable=SC2086
  bash "${interop}/${script}" "${shim}" gate ${extra} 2>&1 | tee "${dir}/run.log"
  rc=${PIPESTATUS[0]}
  set -e
  echo "::endgroup::"
  report="$(find "${OLC_WORK}/gate-artifacts" -name gate-report.json -print -quit 2>/dev/null || true)"
  if [ -n "${report}" ]; then
    cp "${report}" "${dir}/gate-report.json"
  else
    echo "${carrier}: the suite wrote no gate-report.json" >&2
  fi
  if [ "${rc}" -ne 0 ]; then
    echo "::error title=Release gate::${carrier}: ${script} gate exited ${rc}" >&2
    failed=1
  fi
done
rm -f "${shim}"
exit "${failed}"
