#!/usr/bin/env bash
# Refreshes the rule-sets the app bundles, from SagerNet's `rule-set` branches.
#
# Russia, Iran and China lists compiled to sing-box's binary format. Each
# group is pinned to a commit so a re-run reproduces the same bytes. Override
# the corresponding *_REF variable when deliberately refreshing a group.
# Writes scripts/rule-sets.lock with what it fetched. The sha256 lines there
# must then be copied into RuleSets.kt — RuleSetsTest checks the bundle against
# those constants, so a refresh that forgets the copy fails the build rather
# than shipping unreviewed lists.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/sharedUI/src/commonMain/composeResources/files/rules"
lock="$root/scripts/rule-sets.lock"

branch_head() {
  curl -fsSL -H 'User-Agent: olcbox' "https://api.github.com/repos/SagerNet/$1/branches/rule-set" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["commit"]["sha"])'
}

locked_ref() { awk -v key="$1" '$1 == key { print $2; exit }' "$lock"; }

GEOSITE_REF="${GEOSITE_REF:-$(locked_ref sing-geosite)}"
GEOIP_REF="${GEOIP_REF:-$(locked_ref sing-geoip)}"
REGIONAL_GEOSITE_REF="${REGIONAL_GEOSITE_REF:-$(locked_ref regional-sing-geosite)}"
REGIONAL_GEOIP_REF="${REGIONAL_GEOIP_REF:-$(locked_ref regional-sing-geoip)}"

[[ -n "$GEOSITE_REF" ]] || GEOSITE_REF="$(branch_head sing-geosite)"
[[ -n "$GEOIP_REF" ]] || GEOIP_REF="$(branch_head sing-geoip)"
[[ -n "$REGIONAL_GEOSITE_REF" ]] || REGIONAL_GEOSITE_REF="$GEOSITE_REF"
[[ -n "$REGIONAL_GEOIP_REF" ]] || REGIONAL_GEOIP_REF="$GEOIP_REF"

fetch() { # repo ref file
  curl -fsSL "https://raw.githubusercontent.com/SagerNet/$1/$2/$3" -o "$dest/$3"
  echo "fetched $3 @ $1/$2"
}

mkdir -p "$dest"
fetch sing-geosite "$GEOSITE_REF" geosite-category-ru.srs
fetch sing-geosite "$GEOSITE_REF" geosite-tld-ru.srs
fetch sing-geoip "$GEOIP_REF" geoip-ru.srs
fetch sing-geosite "$REGIONAL_GEOSITE_REF" geosite-category-ir.srs
fetch sing-geosite "$REGIONAL_GEOSITE_REF" geosite-cn.srs
fetch sing-geosite "$REGIONAL_GEOSITE_REF" geosite-tld-cn.srs
fetch sing-geoip "$REGIONAL_GEOIP_REF" geoip-ir.srs
fetch sing-geoip "$REGIONAL_GEOIP_REF" geoip-cn.srs

{
  echo "# Written by scripts/update-rule-sets.sh. Do not edit by hand."
  echo "sing-geosite $GEOSITE_REF"
  echo "sing-geoip $GEOIP_REF"
  echo "regional-sing-geosite $REGIONAL_GEOSITE_REF"
  echo "regional-sing-geoip $REGIONAL_GEOIP_REF"
  (cd "$dest" && sha256sum --text geosite-category-ru.srs geosite-tld-ru.srs geoip-ru.srs \
    geosite-category-ir.srs geosite-cn.srs geosite-tld-cn.srs geoip-ir.srs geoip-cn.srs)
} > "$lock"

echo
cat "$lock"
echo
echo "Copy the sha256 values into sharedUI/src/commonMain/kotlin/org/olcbox/app/net/RuleSets.kt."
