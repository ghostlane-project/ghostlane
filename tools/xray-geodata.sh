#!/usr/bin/env bash
# Refreshes the Xray-side Bypass Russia lists the app bundles, from v2fly's
# releases: geosite category-ru and tld-ru out of dlc.dat, geoip ru out of
# geoip.dat, written as text rules Xray accepts inline (see
# tools/xray-geodata/main.go for why text and not .dat). Pinned to a release
# tag and a sha256 of each download so a re-run reproduces the same bytes;
# pass GEOSITE_TAG / GEOIP_TAG (and their sums) to move them. Writes
# tools/xray-geodata.lock with what it fetched and produced; the sha256 lines
# there are what XrayGeodata.kt pins - XrayGeodataTest checks the bundle
# against those constants, so a refresh that forgets the copy fails the build.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/sharedUI/src/commonMain/composeResources/files/xray"
lock="$root/tools/xray-geodata.lock"

GEOSITE_TAG="${GEOSITE_TAG:-20260908094002}"
GEOSITE_SHA="${GEOSITE_SHA:-35ed26a24cafa1256bd7261414224b7bcef5c944cea7760e172b030a8b266450}"
GEOIP_TAG="${GEOIP_TAG:-202609050329}"
GEOIP_SHA="${GEOIP_SHA:-1cba1f0982cf62502fa079c66047c3d0c608196da5b3305671e68f60e917a482}"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

fetch() { # url file sha
  curl -fsSL -o "$work/$2" "$1"
  echo "$3  $work/$2" | sha256sum -c --quiet
  echo "fetched $2 ($3)"
}
fetch "https://github.com/v2fly/domain-list-community/releases/download/$GEOSITE_TAG/dlc.dat" dlc.dat "$GEOSITE_SHA"
fetch "https://github.com/v2fly/geoip/releases/download/$GEOIP_TAG/geoip.dat" geoip.dat "$GEOIP_SHA"

mkdir -p "$dest"
(cd "$root/tools/xray-geodata" && go run . -geosite "$work/dlc.dat" -geoip "$work/geoip.dat" -out "$dest")

{
  echo "# Written by tools/xray-geodata.sh. Do not edit by hand."
  echo "domain-list-community $GEOSITE_TAG dlc.dat $GEOSITE_SHA"
  echo "geoip $GEOIP_TAG geoip.dat $GEOIP_SHA"
  (cd "$dest" && sha256sum geosite-category-ru.txt geosite-tld-ru.txt geoip-ru.txt)
} > "$lock"
cat "$lock"
