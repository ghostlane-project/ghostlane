#!/usr/bin/env bash
# The drafts of the texts only the owner sends (docs/drafts/store-texts-rust.md).
# Fails when
#   - the draft is absent or lacks a section (export notice, keywords, Play line);
#   - a text names a retired carrier, a meeting service or a Go library;
#   - the keywords pass 100 characters or the Play line passes 500.
# It then proves itself: a planted bad draft must fail for each word list.
#
#   scripts/p6-tests/store-drafts.sh [root]
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
draft="docs/drafts/store-texts-rust.md"

check() { # check <root>: prints a line per problem, exit 1 on any
  python3 -I - "$1/$draft" <<'PY'
import re, sys
path = sys.argv[1]
try:
    text = open(path, encoding="utf-8").read()
except OSError:
    print("FAIL draft is absent: " + path); sys.exit(1)
retired = ["telemost", "wb stream", "wbstream", "wb-stream", "seichannel"]
meeting = ["jitsi", "salutejazz", "salute jazz", "vk call", "vkcall", "vk.com",
           "mts link", "mtslink", "zoom", "google meet", "microsoft teams", "webex", "discord"]
golib = ["sing-box", "singbox", "libbox", "gomobile", "xray-core", "golang", "go library", "go core"]
bad = 0
low = text.lower()
for group, words in (("retired carrier", retired), ("meeting service", meeting), ("Go library", golib)):
    for w in words:
        if re.search(r"(?<![a-z0-9])" + re.escape(w) + r"(?![a-z0-9])", low):
            print("FAIL names a %s: %s" % (group, w)); bad = 1
for head in ("## Export-compliance notice", "## Store keywords", '## Play "what\'s new" line'):
    if head not in text:
        print("FAIL section missing: " + head); bad = 1
blocks = re.findall(r"```\n(.*?)```", text, re.S)
if len(blocks) < 3:
    print("FAIL expected 3 text blocks, found %d" % len(blocks)); bad = 1
else:
    kw, play = blocks[1].strip(), blocks[2].strip()
    if len(kw) > 100: print("FAIL keywords are %d characters" % len(kw)); bad = 1
    if " " in kw or ", " in kw: print("FAIL keywords hold a space"); bad = 1
    if len(play) > 500: print("FAIL Play line is %d characters" % len(play)); bad = 1
    if "encrypt" not in blocks[0].lower() or "742.15" not in blocks[0]:
        print("FAIL the notice does not name the encryption rule"); bad = 1
sys.exit(bad)
PY
}

fail=0
check "$root" || fail=1

# Self-test: each planted draft must fail.
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/none" "$tmp/p/docs/drafts"
check "$tmp/none" >/dev/null 2>&1 && { echo "FAIL self-test: an absent draft passed"; fail=1; }
for word in "Telemost" "Jitsi" "sing-box" "libbox" "SaluteJazz" "WB Stream" "gomobile"; do
  sed "s/Ghostlane now runs/Ghostlane via $word now runs/" "$root/$draft" > "$tmp/p/$draft"
  check "$tmp/p" >/dev/null 2>&1 && { echo "FAIL self-test: '$word' passed"; fail=1; }
done
sed 's/^olcrtc,/olcrtc,aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,/' "$root/$draft" > "$tmp/p/$draft"
check "$tmp/p" >/dev/null 2>&1 && { echo "FAIL self-test: long keywords passed"; fail=1; }
[ "$fail" -eq 0 ] && echo "store drafts: ok"
exit "$fail"
