#!/usr/bin/env bash
# build.sh <bin-dir>
#
# Builds <bin-dir>/harness the way scripts/build-cores-ios.sh builds the
# iPhone extension's Cores.xcframework, minus gomobile: one module holding
# sing-box (the patched tree, scripts/patched-singbox.sh), libXray at its pin
# with the patched copy of the xray-core it pins
# (scripts/patches/xray-core-h2-window.patch) and the olcRTC engine's mobile
# package from the fork at its pin, resolved together by MVS; the same tags
# and -ldflags "-s -w". It refuses to build when a replace did not take, as
# build-cores-ios.sh does. Writes <bin-dir>/harness.versions.txt (what MVS chose).
#
# bash 3.2 safe (macOS runners).
set -euo pipefail

out="${1:?usage: build.sh <bin-dir>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../.." && pwd)"
scripts="${repo}/scripts"

unset GO_VERSION SINGBOX_VERSION SINGBOX_MODULE_SUM LIBXRAY_VERSION OLCRTC_VERSION CORES_BUILD CORES_TAG
# shellcheck source=../../../scripts/cores-pins.sh
. "${scripts}/cores-pins.sh"

OLCRTC_MODULE="github.com/openlibrecommunity/olcrtc"
OLCRTC_FORK="github.com/ghostlane-project/olcrtc"
TAGS="with_gvisor,with_quic,with_utls,with_clash_api,olcrtc_lean"

mkdir -p "${out}"
out="$(cd "${out}" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

go_have="$(go env GOVERSION)"
case "${go_have}" in
  "go${GO_VERSION%.*}."*) ;;
  *)
    echo "Go ${go_have} on PATH, but scripts/cores-pins.sh pins ${GO_VERSION}." >&2
    exit 1
    ;;
esac

mkdir "${work}/harness"
cp "${here}"/*.go "${work}/harness/"
cd "${work}/harness"
cat > go.mod <<EOF
module github.com/ghostlane-project/ghostlane/lab/footprint/harness

go 1.26.3
EOF

export GOFLAGS=-mod=mod
# The order and the commands of build-cores-ios.sh.
go mod edit \
  -require="${OLCRTC_MODULE}@v0.0.0-00010101000000-000000000000" \
  -replace="${OLCRTC_MODULE}=${OLCRTC_FORK}@${OLCRTC_VERSION}"
go get "github.com/sagernet/sing-box@v${SINGBOX_VERSION}"
go get "github.com/xtls/libxray@${LIBXRAY_VERSION}"

xray_pin="$(go list -m -f '{{.Version}}' github.com/xtls/xray-core)"
xray_mod_dir="$(go list -m -f '{{.Dir}}' github.com/xtls/xray-core)"
echo "== xray-core ${xray_pin}: patching a copy of ${xray_mod_dir} =="
xray_src="${work}/xray-core"
cp -R "${xray_mod_dir}" "${xray_src}"
chmod -R u+w "${xray_src}"
patch -p1 -d "${xray_src}" --forward --silent < "${scripts}/patches/xray-core-h2-window.patch"
grep -q "func newH2Transport" "${xray_src}/transport/internet/splithttp/dialer.go"
go mod edit -replace "github.com/xtls/xray-core=${xray_src}"

singbox_src="${work}/sing-box"
bash "${scripts}/patched-singbox.sh" "${singbox_src}"
go mod edit -replace "github.com/sagernet/sing-box=${singbox_src}"

# build-cores-ios.sh also requires gomobile's bind package (gobind finds it
# through the module). Nothing here imports it, but as a requirement it takes
# part in version selection, so it is required here too, at the same version.
gomobile_version="$(sed -n 's/^GOMOBILE_VERSION="\${GOMOBILE_VERSION:-\(.*\)}"$/\1/p' "${scripts}/build-cores-ios.sh")"
if [ -z "${gomobile_version}" ]; then
  echo "GOMOBILE_VERSION not found in scripts/build-cores-ios.sh" >&2
  exit 1
fi
go get "github.com/sagernet/gomobile/bind@${gomobile_version}"

resolved="$(go list -m -f '{{if .Replace}}{{.Replace.Version}}{{else}}{{.Version}}{{end}}' "${OLCRTC_MODULE}")"
if [ "${resolved}" != "${OLCRTC_VERSION}" ]; then
  echo "olcrtc resolved to ${resolved}, but the pin says ${OLCRTC_VERSION}." >&2
  exit 1
fi
if [ "$(go list -m -f '{{if .Replace}}{{.Replace.Path}}{{end}}' github.com/xtls/xray-core)" != "${xray_src}" ]; then
  echo "xray-core did not resolve to the patched copy" >&2
  exit 1
fi
if [ "$(go list -m -f '{{if .Replace}}{{.Replace.Path}}{{end}}' github.com/sagernet/sing-box)" != "${singbox_src}" ]; then
  echo "sing-box did not resolve to the patched tree" >&2
  exit 1
fi

echo "== build: tags ${TAGS}, -ldflags -s -w =="
go build -trimpath -tags "${TAGS}" -ldflags "-s -w" -o "${out}/harness" .

{
  echo "pins: go ${GO_VERSION}, sing-box ${SINGBOX_VERSION} (patched), libXray ${LIBXRAY_VERSION} (xray-core ${xray_pin}, h2-window patch), olcrtc ${OLCRTC_VERSION}, gomobile bind ${gomobile_version} (a requirement only)"
  echo "built with: $(go env GOVERSION) $(go env GOOS)/$(go env GOARCH) CGO_ENABLED=$(go env CGO_ENABLED) tags=${TAGS}"
  go list -m github.com/sagernet/sing-box github.com/sagernet/sing github.com/xtls/libxray github.com/xtls/xray-core \
    "${OLCRTC_MODULE}" github.com/pion/webrtc/v4 github.com/pion/transport/v4 golang.org/x/net golang.org/x/crypto \
    gvisor.dev/gvisor github.com/sagernet/gvisor github.com/apernet/quic-go github.com/sagernet/quic-go 2>/dev/null || true
} | tee "${out}/harness.versions.txt"
ls -l "${out}/harness"
