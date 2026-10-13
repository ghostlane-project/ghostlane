#!/usr/bin/env bash
# What the desktop's traffic chart is drawn from, asked of the pinned sing-box.
#
# On Windows and macOS the tun is a sing-box, and the app reads what it has
# carried from its local API (SingBoxConfig.LocalApi, vpn/desktop/TunnelTraffic.kt):
# GET /connections, the totals and every open flow with the program behind it.
# The app takes the flows of its own lines' binaries off the totals, because a
# line that leaves through the tun by a process rule is counted there too.
#
# Three things about that answer are not in any schema and are read from it by
# hand: the names of the totals, that a flow says which program it belongs to,
# and how it says it - the path, then whoever runs it in brackets. This asks a
# real sing-box for all three, so that a bump which changes one fails here and
# not as a chart that counts a room's session twice.
#
#   SINGBOX_BIN=/path/to/sing-box scripts/check-singbox-traffic-api.sh
set -euo pipefail

bin="${SINGBOX_BIN:-sing-box}"
"$bin" version | head -1

# With its links resolved, which is how sing-box names a program: on a Mac a
# temporary directory is under /var, and /var is a link to /private/var.
work="$(cd "$(mktemp -d)" && pwd -P)"
pids=()
cleanup() {
  for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT

# Two programs to tell apart: the one the rule names stands for a line's
# binary, its copy for anything the user runs.
line="$(readlink -f "$(command -v curl)")"
user="$work/a-users-program"
cp "$line" "$user"

secret="0f1e2d3c4b5a69788796a5b4c3d2e1f0"
cat > "$work/upstream.json" <<EOF
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"in","listen":"127.0.0.1","listen_port":18821}],
 "outbounds":[{"type":"direct","tag":"direct"}]}
EOF
# The tun's shape without the tun, which needs privileges a runner may not have:
# everything to the line at 18821, the line's own binary straight out.
cat > "$work/tun.json" <<EOF
{"log":{"level":"warn"},
 "inbounds":[{"type":"socks","tag":"tun-in","listen":"127.0.0.1","listen_port":18820}],
 "outbounds":[{"type":"socks","tag":"out","server":"127.0.0.1","server_port":18821,"version":"5"},
              {"type":"direct","tag":"direct"}],
 "route":{"rules":[{"inbound":["tun-in"],"process_path":["$line"],"outbound":"direct"}]},
 "experimental":{"clash_api":{"external_controller":"127.0.0.1:18822","secret":"$secret"}}}
EOF

# Large enough that both flows are still open when the API is asked.
head -c 80000000 /dev/urandom > "$work/payload.bin"
(cd "$work" && exec python3 -m http.server 18823 --bind 127.0.0.1 >/dev/null 2>&1) &
pids+=("$!")
"$bin" run -c "$work/upstream.json" >"$work/upstream.log" 2>&1 &
pids+=("$!")
"$bin" run -c "$work/tun.json" >"$work/tun.log" 2>&1 &
pids+=("$!")

api="http://127.0.0.1:18822/connections"
# Three things were started and none of them is up the moment it is: the API,
# the two listeners a flow passes, and the server at the far end. A flow sent
# before the last of them is refused, and the list of flows is then empty.
for _ in $(seq 1 100); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$api" || true)" = "401" ] &&
     [ "$("$user" -s -o /dev/null -w '%{http_code}' --socks5-hostname 127.0.0.1:18820 http://127.0.0.1:18823/upstream.json || true)" = "200" ]; then
    break
  fi
  sleep 0.1
done

fail() {
  echo "FAIL $1"
  echo "--- the tun's log:"; tail -5 "$work/tun.log" || true
  exit 1
}

[ "$(curl -s -o /dev/null -w '%{http_code}' "$api")" = "401" ] || fail "the API answers without its secret"

"$line" -s --socks5-hostname 127.0.0.1:18820 --limit-rate 2000k -o /dev/null http://127.0.0.1:18823/payload.bin &
pids+=("$!")
"$user" -s --socks5-hostname 127.0.0.1:18820 --limit-rate 2000k -o /dev/null http://127.0.0.1:18823/payload.bin &
pids+=("$!")
sleep 2

curl -s -H "Authorization: Bearer $secret" "$api" > "$work/answer.json" || fail "the API did not answer its secret"
LINE="$line" USER_PROGRAM="$user" python3 - "$work/answer.json" <<'PY' || fail "the answer is not what the app reads"
import json, os, sys

answer = json.load(open(sys.argv[1]))
line, user = os.environ["LINE"], os.environ["USER_PROGRAM"]

def need(condition, what):
    if not condition:
        print("     " + what)
        print("     " + json.dumps(answer)[:1500])
        sys.exit(1)

for total in ("downloadTotal", "uploadTotal"):
    need(isinstance(answer.get(total), int), f"no {total} in the answer")
flows = answer.get("connections")
need(isinstance(flows, list) and len(flows) == 2, "not the two flows that are open")
for flow in flows:
    need(isinstance(flow.get("id"), str) and flow["id"], "a flow with no id")
    need(isinstance(flow.get("download"), int) and isinstance(flow.get("upload"), int), "a flow with no counts")
    need(isinstance(flow.get("metadata", {}).get("processPath"), str), "a flow that does not name its program")

def named(path):
    # The path, then whoever runs it in brackets; the bare path where sing-box knows nobody.
    return [f for f in flows
            if f["metadata"]["processPath"] == path
            or (f["metadata"]["processPath"].startswith(path + " (") and f["metadata"]["processPath"].endswith(")"))]

of_the_line, of_the_user = named(line), named(user)
need(len(of_the_line) == 1, f"no flow named as {line!r} or {line + ' (someone)'!r}")
need(len(of_the_user) == 1, f"no flow named as {user!r} or {user + ' (someone)'!r}")
need(of_the_line[0]["chains"] == ["direct"], "the rule on the line's binary did not send it straight out")
need(of_the_user[0]["chains"] == ["out"], "the user's flow did not go to the line")
# What the app does with it: everything, less the line's own.
carried = answer["downloadTotal"] - of_the_line[0]["download"]
need(carried > 0, "nothing left for the user once the line's flow is taken off")
need(answer["downloadTotal"] >= of_the_line[0]["download"] + of_the_user[0]["download"] - 4_000_000,
     "the totals are not the sum of the flows")
print("ok   totals, flows and their programs: " + of_the_line[0]["metadata"]["processPath"])
PY
echo "ok   the traffic API answers as the app reads it"
