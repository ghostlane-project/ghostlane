#!/usr/bin/env bash
# The CLI's engine child against the Xray servers (rust-cli-engine.yml).
#
# Every line of scripts/rust-core-interop/lines/ (Reality, XHTTP and
# Hysteria2 among them) against each Xray release of scripts/xray-servers.txt,
# as scripts/rust-core-interop.py runs them for check K5, with one difference:
# the client is not the core's CLI but cli/examples/engine_client, which
# starts the builder's core.json through the CLI's own engine child
# (cli/src/engine.rs) as the core half of a session of ghostlane-engine. A
# line that is carried, refused or mutated is judged as K5 judges it.
#
# Needs, from the workflow: GHOSTLANE_ENGINE (the program at the pin),
# ENGINE_CLIENT (the built example), XRAY_SERVERS ("<version>=<xray> ..."),
# RUN_DIR (the driver's work directory, with its `prepare` run and the
# builder's dumps in $RUN_DIR/dumps).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

: "${GHOSTLANE_ENGINE:?the program: GHOSTLANE_ENGINE}"
: "${ENGINE_CLIENT:?the client: ENGINE_CLIENT}"
: "${XRAY_SERVERS:?the servers: XRAY_SERVERS}"
: "${RUN_DIR:?the work directory: RUN_DIR}"
[ -x "$GHOSTLANE_ENGINE" ] || { echo "GHOSTLANE_ENGINE is no program: $GHOSTLANE_ENGINE" >&2; exit 1; }
[ -x "$ENGINE_CLIENT" ] || { echo "ENGINE_CLIENT is no program: $ENGINE_CLIENT" >&2; exit 1; }
[ -d "$RUN_DIR/dumps" ] || { echo "no dumps in $RUN_DIR: the builder's step did not run" >&2; exit 1; }

# The three kinds this check is named for have a line each, or it checks nothing of them.
for line in reality hysteria2 vless-none-xhttp; do
  [ -f "$root/scripts/rust-core-interop/lines/$line.server.json" ] \
    || { echo "no line file for $line" >&2; exit 1; }
done
for release in $XRAY_SERVERS; do
  [ -x "${release#*=}" ] || { echo "no Xray at ${release#*=}" >&2; exit 1; }
done

"$GHOSTLANE_ENGINE" version | sed -n 1p
export GHOSTLANE_ENGINE
exec python3 "$root/scripts/rust-core-interop.py" run \
  --work "$RUN_DIR" --dumps "$RUN_DIR/dumps" \
  --client "$ENGINE_CLIENT" --servers "$XRAY_SERVERS"
