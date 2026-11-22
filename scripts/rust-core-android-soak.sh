#!/usr/bin/env bash
# rust-core-android-soak.sh <apk dir> <out dir> [minutes] [max growth MiB]
#
# The soak (rust-core-android.yml, by workflow_dispatch only): one session of
# the app on the Rust core, rust-core-android-session.sh's (armed by
# RustKillE2ETest, started by the debug receiver, on a VLESS REALITY line to
# the official Xray server $XRAY_SERVER on the host), kept up for <minutes>
# (120) under traffic: every minute twenty GETs of 256 KiB by the shell, each
# a connection of its own through the tun, hev, the core and the line, and a
# sample of `dumpsys meminfo` of the app's process.
#
# <out dir>/meminfo.csv holds the samples; <out dir>/summary.txt the curve and
# the verdict of rust-core-android-host.py soak-summary, also appended to the
# job's summary. Non-zero when the session failed or the curve is not flat
# (PSS grew more than <max growth MiB>, 16, from the first twenty minutes after
# a ten-minute warm-up to the last twenty), the process restarted, or more
# than 2 % of the rounds lost a fetch.
set -uo pipefail

apks=${1:?usage: rust-core-android-soak.sh <apk dir> <out dir> [minutes] [max growth MiB]}
minutes=${3:-120}
bound=${4:-16}
case "$minutes" in '' | *[!0-9]*) echo "minutes must be a whole number, not '$minutes'" >&2; exit 2 ;; esac
case "$bound" in '' | *[!0-9.]*) echo "the growth bound must be a number of MiB, not '$bound'" >&2; exit 2 ;; esac
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/rust-core-android-session.sh
. "$here/rust-core-android-session.sh"
session_open "$apks" "${2:?usage: rust-core-android-soak.sh <apk dir> <out dir> [minutes] [max growth MiB]}" SOAK

session_start
shell_fetch soak-first || fail "no GET through the session"
say "the soak's process: $(app_pid), for $minutes minutes"

csv="$out/meminfo.csv"
echo "minute,epoch,pid,pss_kb,rss_kb,native_kb,java_kb,ok,fail,bytes" > "$csv"
start=$(date +%s)
end=$((start + minutes * 60))
round=0
while [ "$(date +%s)" -lt "$end" ]; do
  round_start=$(date +%s)
  round=$((round + 1))
  load=$(shellnet load 192.0.2.1 80 /bytes/262144/soak 20000 20)
  ok=$(echo "$load" | sed -n 's/^RESULT ok=\([0-9]*\) fail=[0-9]* bytes=[0-9]*.*/\1/p')
  failed=$(echo "$load" | sed -n 's/^RESULT ok=[0-9]* fail=\([0-9]*\) bytes=[0-9]*.*/\1/p')
  bytes=$(echo "$load" | sed -n 's/^RESULT ok=[0-9]* fail=[0-9]* bytes=\([0-9]*\).*/\1/p')
  [ -n "$ok" ] || { ok=0; failed=20; bytes=0; echo "round $round: $load" >> "$out/load-failures.txt"; }
  pid=$(app_pid)
  read -r pss rss native java < <(dev dumpsys meminfo "$pkg" | python3 "$here/rust-core-android-host.py" meminfo)
  now=$(date +%s)
  minute=$(awk -v a="$now" -v b="$start" 'BEGIN { printf "%.2f", (a - b) / 60 }')
  echo "$minute,$now,$pid,$pss,$rss,$native,$java,$ok,$failed,$bytes" >> "$csv"
  if [ $((round % 10)) -eq 1 ]; then
    echo "minute $minute: pid ${pid:-none}, PSS ${pss} KiB, native heap ${native} KiB, fetches $ok/$((ok + failed))"
  fi
  wait_for=$((round_start + 60 - $(date +%s)))
  [ "$wait_for" -gt 0 ] && sleep "$wait_for"
done

shell_fetch soak-last || say "the last GET of the soak was not answered"
session_stop
python3 "$here/rust-core-android-host.py" soak-summary --csv "$csv" --max-growth-mib "$bound" > "$out/summary.txt"
verdict=$?
cat "$out/summary.txt" | tee -a "$out/results.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### Soak: $minutes minutes on the Rust core, $(cat "$out/device.txt")"; echo; cat "$out/summary.txt"; } >> "$GITHUB_STEP_SUMMARY"
fi
finish "$verdict"
