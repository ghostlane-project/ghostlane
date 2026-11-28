#!/usr/bin/env bash
# The CLI's installer and package scripts (cli/packaging), as the Go program's
# install_test.go held them: the tarball path installs the program, the engine
# and hev from a mirror once the signature and the checksum verify, and refuses
# a tampered sums file or tarball; --help works from a pipe; a mirror needs
# --version; the package scripts leave the service alone on an upgrade and
# stop it on a removal (systemd and OpenRC); the tarball path installs the
# OpenRC script where openrc-run exists.
#
#   scripts/p6-tests/cli-install.sh [root]     root defaults to the repository
# Needs bash, openssl, python3, tar and sh.
set -euo pipefail
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
pk="$root/cli/packaging"
work="$(mktemp -d)"
server_pid=""
cleanup() { [ -z "$server_pid" ] || kill "$server_pid" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT
fail=0
bad() { echo "FAIL $*"; fail=1; }

# a throwaway release key
openssl genpkey -algorithm ed25519 -out "$work/key.pem" 2>/dev/null
openssl pkey -in "$work/key.pem" -pubout -out "$work/pub.pem" 2>/dev/null

# a tarball as the release makes one
stage="$work/stage"; mkdir -p "$stage"
for f in ghostlane ghostlane-engine ghostlane-hev; do
  printf '#!/bin/sh\necho %s 0.0.1\n' "$f" > "$stage/$f"; chmod 0755 "$stage/$f"
done
printf '[Unit]\nExecStart=/usr/bin/ghostlane run\n' > "$stage/ghostlane.service"
printf '#!/sbin/openrc-run\ncommand=/usr/bin/ghostlane\n' > "$stage/ghostlane.openrc"
for f in README.md LICENSE COPYRIGHT; do echo x > "$stage/$f"; done
site="$work/site"; mkdir -p "$site"
tar -C "$stage" -czf "$site/ghostlane-cli-0.0.1-linux-amd64.tar.gz" .
( cd "$site" && sha256sum ghostlane-cli-0.0.1-linux-amd64.tar.gz > ghostlane-cli-0.0.1-SHA256SUMS )
openssl pkeyutl -sign -inkey "$work/key.pem" -rawin -in "$site/ghostlane-cli-0.0.1-SHA256SUMS" -out "$site/ghostlane-cli-0.0.1-SHA256SUMS.sig"
cp "$site/ghostlane-cli-0.0.1-SHA256SUMS" "$work/sums.good"
cp "$site/ghostlane-cli-0.0.1-linux-amd64.tar.gz" "$work/tgz.good"

python3 -I -u -m http.server 0 --bind 127.0.0.1 --directory "$site" > "$work/server.log" 2>&1 &
server_pid=$!
port=""
for _ in $(seq 100); do
  port="$(sed -n 's/.*port \([0-9]*\).*/\1/p' "$work/server.log" | head -n 1)"
  [ -n "$port" ] && break
  sleep 0.1
done
[ -n "$port" ] || { echo "FAIL the asset server did not start"; cat "$work/server.log"; exit 1; }
base="http://127.0.0.1:$port"

install_into() { # root dir, extra PATH dir
  GHOSTLANE_INSTALL_ROOT="$1" GHOSTLANE_PUBKEY_FILE="$work/pub.pem" GHOSTLANE_ARCH=amd64 PATH="${2:+$2:}$PATH" \
    sh "$pk/install.sh" --version 0.0.1 --base-url "$base" --family tar --no-service 2>&1
}

# the tarball path
r="$work/root1"; mkdir -p "$r"
out="$(install_into "$r")" || { bad "install failed: $out"; }
for f in ghostlane ghostlane-engine ghostlane-hev; do
  grep -q "echo $f" "$r/usr/local/bin/$f" 2>/dev/null || bad "$f not installed in /usr/local/bin"
done
grep -q '/usr/local/bin/ghostlane run' "$r/etc/systemd/system/ghostlane.service" 2>/dev/null || bad "the unit does not point at the installed program"

# a tampered sums file, then a tampered tarball
sed 's/0/1/' "$work/sums.good" > "$site/ghostlane-cli-0.0.1-SHA256SUMS"
if out="$(install_into "$work/root2")"; then bad "a tampered SHA256SUMS was accepted"; else grep -q signature <<<"$out" || bad "the refusal of the sums does not say signature: $out"; fi
cp "$work/sums.good" "$site/ghostlane-cli-0.0.1-SHA256SUMS"
python3 -I - "$site/ghostlane-cli-0.0.1-linux-amd64.tar.gz" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
b[10] ^= 0xFF
open(p, "wb").write(b)
PY
if out="$(install_into "$work/root3")"; then bad "a tampered tarball was accepted"; else grep -q checksum <<<"$out" || bad "the refusal of the tarball does not say checksum: $out"; fi
cp "$work/tgz.good" "$site/ghostlane-cli-0.0.1-linux-amd64.tar.gz"

# the OpenRC script, where openrc-run exists
fake="$work/fakebin"; mkdir -p "$fake"
for n in openrc-run rc-update rc-service systemctl; do
  printf '#!/bin/sh\necho "%s $*" >> "%s/calls.log"\n' "$n" "$fake" > "$fake/$n"; chmod 0755 "$fake/$n"
done
r="$work/root4"; mkdir -p "$r"
out="$(install_into "$r" "$fake")" || bad "install on an OpenRC host failed: $out"
[ -x "$r/etc/init.d/ghostlane" ] || bad "no init script installed"
grep -q 'command=/usr/local/bin/ghostlane' "$r/etc/init.d/ghostlane" 2>/dev/null || bad "the init script does not point at the installed program"

# --help from a pipe; a mirror needs --version
help="$(sh -s -- --help < "$pk/install.sh" 2>&1)" || bad "--help from a pipe failed"
grep -q -- '--base-url' <<<"$help" && grep -q -- '--version' <<<"$help" || bad "--help from a pipe prints no usage"
if out="$(GHOSTLANE_INSTALL_ROOT="$work/root5" GHOSTLANE_ARCH=amd64 sh "$pk/install.sh" --base-url http://127.0.0.1:9 --family tar --no-service 2>&1)"; then
  bad "a mirror without --version did not fail"
else
  grep -q -- '--version' <<<"$out" || bad "a mirror without --version does not say so: $out"
fi

# the package scripts
calls="$fake/calls.log"
run_script() { : > "$calls"; GHOSTLANE_SYSTEMD_DIR="$SYSTEMD" GHOSTLANE_OPENRC_DIR="$OPENRC" GHOSTLANE_INIT_SCRIPT="$pk/openrc/ghostlane" PATH="$fake:$PATH" sh "$pk/scripts/$1" "${@:2}" >/dev/null 2>&1; cat "$calls"; }
mkdir -p "$work/systemd" "$work/openrc"
# systemd host
SYSTEMD="$work/systemd" OPENRC="/nonexistent/openrc"
for arg in 1 upgrade; do
  [ -z "$(run_script preremove.sh "$arg")" ] || bad "preremove on an upgrade ($arg) touched the service"
done
for arg in 0 remove; do
  c="$(run_script preremove.sh "$arg")"
  grep -q 'stop ghostlane.service' <<<"$c" && grep -q 'disable ghostlane.service' <<<"$c" || bad "preremove on a removal ($arg) did not stop and disable: $c"
done
# OpenRC host
SYSTEMD="/nonexistent/systemd" OPENRC="$work/openrc"
c="$(run_script postinstall.sh)"
{ grep -q 'rc-update add ghostlane default' <<<"$c" && grep -q 'rc-service ghostlane restart' <<<"$c" && ! grep -q systemctl <<<"$c"; } || bad "postinstall on OpenRC: $c"
for arg in 1 upgrade; do
  [ -z "$(run_script preremove.sh "$arg")" ] || bad "preremove on an upgrade ($arg) touched the service on OpenRC"
done
c="$(run_script preremove.sh 0)"
{ grep -q 'rc-service ghostlane stop' <<<"$c" && grep -q 'rc-update del ghostlane default' <<<"$c"; } || bad "preremove on OpenRC removal: $c"

sh -n "$pk/openrc/ghostlane" || bad "the OpenRC script does not parse"
for want in '#!/sbin/openrc-run' 'supervisor=supervise-daemon' 'command=/usr/bin/ghostlane' 'command_args="run"' 'command_user="ghostlane:ghostlane"'; do
  grep -qF -- "$want" "$pk/openrc/ghostlane" || bad "the OpenRC script lacks $want"
done
exit "$fail"
