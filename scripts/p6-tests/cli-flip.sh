#!/usr/bin/env bash
# C7, the Linux CLI's flip: the shipped CLI is the Rust program with the engine
# of the pin beside it, and no Go is left to build, test or ship. Fails while
#   - cli/ holds a Go module, a Go file or a built Go program;
#   - cli/Makefile builds with Go, or does not build the Rust program;
#   - a package spec or the image does not carry ghostlane-engine and the tun's
#     ghostlane-hev beside ghostlane;
#   - the Linux CLI's jobs (PR Checks' `cli`, release.yml's build-cli, rust-cli.yml)
#     set up or run Go, or the install smoke does not read `core`;
#   - an allow-list file of a CLI package is still in scripts/no-go-allow.d.
#
#   scripts/p6-tests/cli-flip.sh [root]     root defaults to the repository
# Needs bash and the usual text tools.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
fail=0
bad() { echo "FAIL $*"; fail=1; }

for f in go.mod go.sum ghostlane cmd internal; do
  [ ! -e "$root/cli/$f" ] || bad "cli/$f is still there; the CLI has no Go"
done
if [ -n "$(find "$root/cli" -name '*.go' -not -path '*/target/*' -print -quit)" ]; then
  bad "a .go file is still under cli/"
fi

make="$root/cli/Makefile"
if grep -nE '\$\(GO\)|singbox|SINGBOX|(^|[^a-z])go (build|test|vet)|XRAY_PIN' "$make"; then
  bad "cli/Makefile still builds or pins Go"
fi
grep -q 'cargo' "$make" || bad "cli/Makefile does not build with cargo"

for f in cli/packaging/nfpm.yaml cli/packaging/docker/stage.sh cli/packaging/docker/Dockerfile cli/Makefile; do
  [ -f "$root/$f" ] || { bad "no $f"; continue; }
  grep -q 'ghostlane-engine' "$root/$f" || bad "$f does not carry ghostlane-engine"
  grep -q 'ghostlane-hev' "$root/$f" || bad "$f does not carry ghostlane-hev"
done

release="$root/.github/workflows/release.yml"
job=$(awk '/^  build-cli:/{p=1;print;next} /^  [a-z][a-z0-9_-]*:/{if(p)exit} p' "$release")
[ -n "$job" ] || bad "release.yml has no build-cli job"
if grep -nE 'setup-go|go install|GO_VERSION|make singbox-src' <<<"$job"; then
  bad "release.yml's build-cli still sets up or runs Go"
fi
grep -q 'ghostlane-engine' <<<"$job" || bad "release.yml's build-cli builds no engine"
grep -q 'check-no-go.sh' <<<"$job" || bad "release.yml's build-cli does not run K1 on the packages"

checks="$root/.github/workflows/pr-checks.yml"
job=$(awk '/^  cli:/{p=1;print;next} /^  [a-z][a-z0-9_-]*:/{if(p)exit} p' "$checks")
[ -n "$job" ] || bad "pr-checks.yml has no cli job"
if grep -nE 'setup-go|(^|[^a-z])go (vet|test)|golangci|make singbox-src|make xray-server' <<<"$job"; then
  bad "PR Checks' cli job still runs Go"
fi
grep -q 'check-no-go.sh' <<<"$job" || bad "PR Checks' cli job does not run K1 on the packages"
grep -qE 'core' <<<"$(grep -n 'version --json' <<<"$job")" || bad "PR Checks' cli job's install smoke does not read core in version --json"

if grep -nE 'setup-go|(^|[^a-z])go (build|test)|make build' "$root/.github/workflows/rust-cli.yml"; then
  bad "rust-cli.yml still builds Go"
fi

for f in "$root"/scripts/no-go-allow.d/ghostlane-cli-*; do
  [ ! -e "$f" ] || bad "${f#"$root"/} is on the allow list; the CLI's packages are clean"
done

exit "$fail"
