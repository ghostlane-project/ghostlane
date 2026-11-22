#!/usr/bin/env bash
# test-ios-engine-on-mac.sh: the packet tunnel extension's engines, run on a Mac.
#
#   test-ios-engine-on-mac.sh plan
#       the extension's Swift files the harness compiles, the ones it leaves
#       out and why, and the memory limit read from the extension. Any host.
#   test-ios-engine-on-mac.sh selftest
#       this script's own parts that need no Mac: the plan, the lines and
#       the olcRTC direct rules made from the reference configs, the Xray
#       list. Any host with bash, python3 and openssl; seconds.
#   test-ios-engine-on-mac.sh bindings <core-dir>
#       olc-ffi's UniFFI Swift bindings for the darwin library in <core-dir>
#       (scripts/build-ghostlane-core-host.sh's output), made as
#       scripts/build-ghostlane-core-ios.sh makes the extension's: olc-ffi's
#       own uniffi-bindgen at the library's commit, in library mode, with
#       olc-ffi's error shim. Kept in <core-dir>/bindings per commit.
#   test-ios-engine-on-mac.sh xray <dir>
#       the official Xray-core macOS arm64 build of every version in
#       scripts/xray-servers.txt, checked against the sha256 in
#       iosApp/Tests/EngineHost/xray-macos.txt, unpacked to <dir>/<version>/xray.
#   test-ios-engine-on-mac.sh run <core-dir> <work-dir> starts|fetch|all [<xray-dir>]
#       compiles the harness and runs it.
#
# The harness (iosApp/Tests/EngineHost) is compiled with the extension's own
# Swift, included from iosApp/PacketTunnel and never copied, so a change
# there is what runs here: every *.swift of that directory but the four that
# need the system's tunnel or a phone (`plan` says which and why), with the
# bindings the extension compiles, against the darwin slice of ghostlane-core
# (aarch64-apple-darwin, the same commit and features as the phone's), with
# the extension target's Swift settings (Swift 5, approachable concurrency,
# member import visibility) and link flags.
#
# starts: every reference core.json the app writes
#   (sharedUI/src/jvmTest/resources/reference-configs/*core-*.json) started
#   as the extension starts it (resolvers, TunArrangement.decide,
#   DirectResolver.substitute, CoreEngine.start), on a free loopback port as
#   scripts/check-rust-core-configs.sh does; its SOCKS port asks for the login
#   the arrangement read and refuses any other; then stopped and the port let
#   go. One the core must refuse (a field no core.json has) is refused, naming
#   it. OlcrtcEngine with the parameters the app writes, under Global and
#   with the bundled lists' direct rules, joins the room of a stand-in hub
#   (carrier `none`) through the pin; no olcRTC server stands in that room, so
#   its start ends there. A carrier this build does not carry is refused
#   before any room is dialed.
# fetch: each iPhone line (ios-core-<line>.json) against a local Xray server
#   of every version the CLI's end-to-ends use: 2 MiB down and 1 MiB up
#   through the core's SOCKS port, the page reached at a TEST-NET-3 address
#   the server sends to loopback, the body's sha256 checked both ways. A
#   line known to fail (known_failure) is excused only for the stall it is
#   listed for: it passes when the same fetch under the darwin slice's own B
#   reaches the page, and fails when neither does. Once the line reached the
#   page against a server under either B, the same line with a key or a pin
#   that server does not answer to must not reach it (asked where nothing
#   got through, a refusal would prove nothing).
# The engines pin every socket they open to the physical interface
#   (TunPlatform), and a pinned socket cannot reach a loopback address on
#   Darwin (EADDRNOTAVAIL): the hub and the Xray servers listen on that
#   interface's own address, which the harness reads back from a socket the
#   pin bound. What only the servers dial (the page, the Reality target)
#   stays on loopback.
# Both: the byte budget B is the phone's (setByteBudget(3,000,000), the memory
#   class formula on a phone), the core's own payload bytes stay within it, and
#   the process's footprint (phys_footprint and the kernel's peak of it, the
#   figure an extension's limit is enforced against) stays under the limit the
#   extension states for itself in MemoryRelief.swift. Resident memory is
#   printed beside it.
#
# Each check prints a line starting `ok   ` or `FAIL `; a failing check does
# not stop the others, and the run leaves with 1 after them. Needs Xcode (on
# an Apple silicon Mac), python3 with TLS 1.3, openssl, curl; `bindings` also
# rustup and git. bash 3.2 safe.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXT="$ROOT/iosApp/PacketTunnel"
HOST="$ROOT/iosApp/Tests/EngineHost"
REFS="$ROOT/sharedUI/src/jvmTest/resources/reference-configs"
DARWIN=aarch64-apple-darwin
# The tunnel's own config.json as the app writes it beside core.json; the
# arrangement asks only that it is there.
TUN_CONFIG="$REFS/ios-tun-socks-lossy.json"
# The files of the extension this program does not compile (why_left_out).
LEFT_OUT="PacketTunnelProvider.swift HevTunnel.swift MemoryWatch.swift MemoryPressure.swift"
LINES="reality grpc-reality webtunnel-ws webtunnel-httpupgrade hysteria2 xhttp"
# known_failure <line>: why a line is known not to reach the page under the
# phone's B, or nothing. Such a line is still run against every server; a
# stall is printed as known, and the same fetch under the darwin slice's own
# B is then its check (line_reached). One that reaches the page under the
# phone's B says so in a note (a stall that depends on timing may pass
# once), and leaves this list once it passes every time.
known_failure() {
  case "$1" in
    grpc-reality) echo "gRPC over REALITY under the phone's byte budget, in the memory class the darwin target picks (Standard; the extension names no runtime profile, and a phone's target picks Constrained): most fetches stall until the client gives up, and only then does the server receive the request, on every Xray version here; a stalled one passes only when the same fetch under the darwin slice's own byte budget reaches the page" ;;
    *) echo "" ;;
  esac
}
# line_reached <known> <reached> <diagnosed>: 1 when a line counts as having
# reached the page against a server, else 0. <known> is known_failure's
# reason (empty for none), <reached> 1 when the fetch under the phone's B
# reached it, <diagnosed> 1 when the same fetch under the darwin slice's own
# B did (0 when that did not run). A line not known to fail must reach it
# under the phone's B; a known one is excused only for a stall that the
# darwin slice's B clears, and one that reaches it under neither is broken.
line_reached() {
  if [ "$2" = 1 ] || { [ -n "$1" ] && [ "$3" = 1 ]; }; then echo 1; else echo 0; fi
}
DOWN_BYTES=2097152
UP_BYTES=1048576
PY="${PYTHON:-python3}"

die() {
  echo "FAIL $*" >&2
  exit 1
}

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

why_left_out() {
  case "$1" in
    PacketTunnelProvider.swift) echo "the system's entry point (NEPacketTunnelProvider); this program stands where the system does and starts the engines in its order" ;;
    HevTunnel.swift) echo "hev-socks5-tunnel, which runs on the tun's descriptor; this program plays its part at the engine's SOCKS port" ;;
    MemoryWatch.swift) echo "os_proc_available_memory, which macOS does not have; the footprint is read here as it reads it (TASK_VM_INFO phys_footprint)" ;;
    MemoryPressure.swift) echo "the system's memory events, acted on through MemoryWatch" ;;
    *) echo "" ;;
  esac
}

# The Swift of the extension that is compiled, one path per line.
extension_files() {
  local name f n=0
  for name in $LEFT_OUT; do
    [ -f "$EXT/$name" ] || die "iosApp/PacketTunnel/$name is gone: take it out of LEFT_OUT in this script"
  done
  for f in "$EXT"/*.swift; do
    name="$(basename "$f")"
    case " $LEFT_OUT " in *" $name "*) continue ;; esac
    echo "$f"
    n=$((n + 1))
  done
  [ "$n" -gt 0 ] || die "no Swift in $EXT"
}

# The footprint the whole extension is to stay within, in MiB, as
# MemoryRelief.swift states it.
limit_mib() {
  local n
  n="$(grep -oE 'under the [0-9]+ MiB the whole extension is to stay within' "$EXT/MemoryRelief.swift" |
    grep -oE '[0-9]+' | head -n 1 || true)"
  [ -n "$n" ] || die "MemoryRelief.swift no longer says the MiB the whole extension is to stay within; say here where the limit is stated"
  echo "$n"
}

plan() {
  local name mib
  extension_files > /dev/null
  echo "The extension's own Swift, compiled unchanged:"
  extension_files | sed "s|^$ROOT/|  |"
  echo "Left out:"
  for name in $LEFT_OUT; do
    [ -n "$(why_left_out "$name")" ] || die "no reason for leaving out $name"
    echo "  iosApp/PacketTunnel/$name: $(why_left_out "$name")"
  done
  mib="$(limit_mib)"
  echo "The limit: a footprint under $mib MiB ($((mib * 1048576)) bytes), as MemoryRelief.swift states it."
  echo "B: the phone's 3000000 bytes (setByteBudget, as MemoryRelief.swift says a phone's formula gives), in the memory class the darwin target picks, Standard: the extension names no runtime profile, so the target decides, and a phone's picks Constrained."
}

# The versions in scripts/xray-servers.txt, one per line.
xray_versions() {
  awk '$1 !~ /^#/ && NF >= 2 { print $1 }' "$ROOT/scripts/xray-servers.txt"
}

macos_sum() {
  awk -v v="$1" '$1 == v { print $2 }' "$HOST/xray-macos.txt"
}

xray_fetch() {
  local dir="$1" version sum got out
  [ "$(uname -s) $(uname -m)" = "Darwin arm64" ] || die "the macOS arm64 servers run on an Apple silicon Mac, not $(uname -s) $(uname -m)"
  mkdir -p "$dir"
  for version in $(xray_versions); do
    sum="$(macos_sum "$version")"
    [ -n "$sum" ] || die "iosApp/Tests/EngineHost/xray-macos.txt has no hash for $version, which scripts/xray-servers.txt lists"
    out="$dir/$version"
    mkdir -p "$out"
    if [ ! -x "$out/xray" ]; then
      curl -fsSL --retry 3 -o "$out/xray.zip" \
        "https://github.com/XTLS/Xray-core/releases/download/v${version}/Xray-macos-arm64-v8a.zip"
      got="$(shasum -a 256 "$out/xray.zip" | cut -d' ' -f1)"
      [ "$got" = "$sum" ] || die "Xray v$version for macOS: the archive hashes to $got, xray-macos.txt pins $sum"
      unzip -oq "$out/xray.zip" xray -d "$out"
      rm -f "$out/xray.zip"
      chmod +x "$out/xray"
    fi
    echo "Xray $version: $("$out/xray" version | head -n 1)"
  done
}

# bindings <core-dir>
bindings() {
  local core lib commit out src gen inc f
  # Absolute: uniffi-bindgen runs in the core's checkout.
  core="$(cd "$1" && pwd)"
  lib="$core/lib/$DARWIN/libghostlane_core.a"
  [ -s "$lib" ] || die "no $lib: build it with scripts/build-ghostlane-core-host.sh $core $DARWIN"
  commit="$(awk 'NR == 1 { print $1 }' "$core/stamps/$DARWIN")"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "the stamp of $DARWIN names no commit: $commit"
  out="$core/bindings"
  if [ -f "$out/commit" ] && [ "$(cat "$out/commit")" = "$commit" ] && [ -s "$out/gen/OlcFfi.swift" ] \
     && [ -s "$out/include/OlcFfiFFI/module.modulemap" ]; then
    echo "KEPT bindings for $commit"
    return 0
  fi
  # shellcheck source=scripts/ghostlane-core-pin.sh
  . "$ROOT/scripts/ghostlane-core-pin.sh"
  src="${GHOSTLANE_CORE_SRC:-$out/src}"
  if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
    rm -rf "$src"
    git init -q "$src"
    git -C "$src" fetch -q --depth 1 "https://github.com/${GHOSTLANE_CORE_REPO}.git" "$commit"
    git -C "$src" -c advice.detachedHead=false checkout -q FETCH_HEAD
  fi
  test "$(git -C "$src" rev-parse HEAD)" = "$commit"
  export PATH="$HOME/.cargo/bin:$PATH"
  rustup toolchain install "$GHOSTLANE_CORE_TOOLCHAIN" --profile minimal --no-self-update > /dev/null
  (cd "$src" && CARGO_TERM_COLOR=never cargo "+$GHOSTLANE_CORE_TOOLCHAIN" build --locked -p olc-ffi --features bindgen --bin uniffi-bindgen)
  gen="$out/gen"
  inc="$out/include"
  rm -rf "$gen" "$inc"
  mkdir -p "$gen" "$inc/OlcFfiFFI"
  (cd "$src" && target/debug/uniffi-bindgen generate --library "$lib" --language swift --out-dir "$gen")
  cp "$src/olc/olc-ffi/swift/OlcrtcError+Localized.swift" "$gen/"
  cp "$gen/OlcFfiFFI.h" "$inc/OlcFfiFFI/OlcFfiFFI.h"
  cp "$gen/OlcFfiFFI.modulemap" "$inc/OlcFfiFFI/module.modulemap"
  echo "$commit" > "$out/commit"
  echo "BINDINGS for $commit:"
  for f in "$gen"/*; do echo "  $(basename "$f")"; done
}

# compile <core-dir> <work-dir>: the harness, with the extension's Swift.
compile() {
  local core="$1" work="$2" bind="$1/bindings" f
  local files=()
  [ -s "$bind/gen/OlcFfi.swift" ] || die "no bindings in $bind: run $0 bindings $core"
  [ -s "$core/include/XrayRust/module.modulemap" ] || die "no XrayRust module in $core/include"
  extension_files > /dev/null
  while IFS= read -r f; do files+=("$f"); done < <(extension_files)
  echo "compiling the harness with ${#files[@]} files of iosApp/PacketTunnel ($(xcrun swiftc --version 2>&1 | head -n 1))"
  # The extension target's settings (iosApp.xcodeproj, PacketTunnel):
  # SWIFT_VERSION 5.0, SWIFT_APPROACHABLE_CONCURRENCY (the upcoming features
  # Xcode turns on for it), SWIFT_UPCOMING_FEATURE_MEMBER_IMPORT_VISIBILITY,
  # and its OTHER_LDFLAGS but hev.
  xcrun --sdk macosx swiftc -O -swift-version 5 -parse-as-library \
    -target arm64-apple-macos14.0 \
    -enable-upcoming-feature DisableOutwardActorInference \
    -enable-upcoming-feature GlobalActorIsolatedTypesUsability \
    -enable-upcoming-feature InferIsolatedConformances \
    -enable-upcoming-feature InferSendableFromCaptures \
    -enable-upcoming-feature NonisolatedNonsendingByDefault \
    -enable-upcoming-feature MemberImportVisibility \
    -I "$core/include/XrayRust" -I "$bind/include/OlcFfiFFI" \
    -L "$core/lib/$DARWIN" -lghostlane_core -lresolv -lc++ \
    -framework Security -framework SystemConfiguration -framework NetworkExtension \
    "${files[@]}" "$bind/gen/OlcFfi.swift" "$bind/gen/OlcrtcError+Localized.swift" "$HOST"/*.swift \
    -o "$work/engine-host"
  ls -l "$work/engine-host"
}

free_port() {
  "$PY" -I "$HOST/local_servers.py" free-port
}

# starts <host> <work> <limit-bytes>
starts() {
  local host="$1" work="$2" limit="$3" failed=0 n=0 f name
  mkdir -p "$work/starts"
  for f in "$REFS"/*core-*.json; do
    name="$(basename "$f")"
    n=$((n + 1))
    "$PY" -I "$HOST/lines.py" socks "$f" "$work/starts/$name" "$(free_port)"
    "$host" start "$work/starts/$name" "$TUN_CONFIG" "$limit" || failed=1
  done
  [ "$n" -gt 0 ] || die "no *core-*.json in $REFS"
  echo "     $n reference core.json files started"
  "$PY" -I "$HOST/lines.py" refuse "$REFS/ios-core-reality.json" "$work/starts/ios-core-reality-unknown-field.json" \
    "$(free_port)" engineHostUnknownField
  "$host" refuse "$work/starts/ios-core-reality-unknown-field.json" "$TUN_CONFIG" engineHostUnknownField || failed=1

  "$PY" -I "$HOST/lines.py" rules "$REFS/bypass-ios-core-bundled-lists.json" "$work/direct-rules.txt"
  "$host" olcrtc "$TUN_CONFIG" none - "$limit" joins || failed=1
  "$host" olcrtc "$TUN_CONFIG" none "$work/direct-rules.txt" "$limit" joins || failed=1
  "$host" olcrtc "$TUN_CONFIG" telemost - "$limit" refused || failed=1
  return "$failed"
}

# wait_file <path> <pid>: until the file is there, or the process is gone.
wait_file() {
  for _ in $(seq 1 100); do
    [ ! -s "$1" ] || return 0
    kill -0 "$2" 2> /dev/null || return 1
    sleep 0.1
  done
  return 1
}

# xray_up <log> <pid> <transport> <host> <port>: a TCP server once its port
# takes a connection; a UDP one once it says it started, or has run for 3 s.
xray_up() {
  local i
  for i in $(seq 1 100); do
    kill -0 "$2" 2> /dev/null || return 1
    if [ "$3" = udp ]; then
      if grep -q ' started' "$1" 2> /dev/null || [ "$i" -ge 30 ]; then
        sleep 0.3
        return 0
      fi
    elif (: < "/dev/tcp/$4/$5") 2> /dev/null; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

PIDS=""
stop_servers() {
  local pid
  for pid in $PIDS; do
    kill "$pid" 2> /dev/null || true
  done
  PIDS=""
}

# fetch <host> <work> <limit-bytes> <xray-dir>
fetch() {
  local host="$1" work="$2" limit="$3" xray="$4" failed=0 page target private public wrong_public
  local version line dir transport what socks port pid ok address known reached diagnosed show
  mkdir -p "$work/certs" "$work/lines"
  "$PY" -I -c 'import ssl, sys; print("python", sys.version.split()[0], ssl.OPENSSL_VERSION); sys.exit(0 if ssl.HAS_TLSv1_3 else 1)' ||
    die "$PY has no TLS 1.3 for the Reality target: set PYTHON to one that has"
  cat > "$work/certs/openssl.cnf" << 'CNF'
[req]
distinguished_name = dn
x509_extensions = san
prompt = no
[dn]
CN = www.microsoft.com
[san]
subjectAltName = DNS:www.microsoft.com,DNS:wt.example.com,DNS:rutube.ru
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
CNF
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -config "$work/certs/openssl.cnf" \
    -keyout "$work/certs/key.pem" -out "$work/certs/cert.pem" 2> /dev/null
  "$PY" -I "$HOST/local_servers.py" page "$work/page.port" "$DOWN_BYTES" 2> "$work/page.log" &
  PIDS="$PIDS $!"
  wait_file "$work/page.port" "$!" || die "the page did not listen: $(cat "$work/page.log")"
  "$PY" -I "$HOST/local_servers.py" target "$work/target.port" "$work/certs/cert.pem" "$work/certs/key.pem" 2> "$work/target.log" &
  PIDS="$PIDS $!"
  wait_file "$work/target.port" "$!" || die "the Reality target did not listen: $(cat "$work/target.log")"
  page="$(cat "$work/page.port")"
  target="$(cat "$work/target.port")"
  # The servers listen where the engine's pinned sockets can reach them.
  address="$("$host" pinned-address)"
  echo "     the local servers listen on $address, the address of the pin's interface"
  read -r private public < <("$host" x25519)
  read -r _ wrong_public < <("$host" x25519)
  for version in $(xray_versions); do
    [ -x "$xray/$version/xray" ] || die "no Xray $version in $xray: run $0 xray $xray"
    for line in $LINES; do
      dir="$work/lines/$version"
      mkdir -p "$dir"
      socks="$(free_port)"
      port="$(free_port)"
      transport=""
      read -r transport what < <("$PY" -I "$HOST/lines.py" line "$line" "$REFS" "$dir" "$address" "$socks" "$port" "$target" "$page" \
        "$work/certs/cert.pem" "$work/certs/key.pem" "$private" "$public" "$wrong_public")
      if [ -z "$transport" ]; then
        echo "FAIL $line against Xray $version: no configs for it"
        failed=1
        continue
      fi
      "$xray/$version/xray" run -c "$dir/$line.server.json" > "$dir/$line.xray.log" 2>&1 &
      pid=$!
      PIDS="$PIDS $pid"
      ok=1
      show=0
      if ! xray_up "$dir/$line.xray.log" "$pid" "$transport" "$address" "$port"; then
        echo "FAIL $line against Xray $version: the server did not listen on $transport $address:$port"
        ok=0
      else
        known="$(known_failure "$line")"
        reached=1
        "$host" fetch "$dir/$line.client.json" "$TUN_CONFIG" "$limit" "$DOWN_BYTES" "$UP_BYTES" reach \
          "$line against Xray $version" > "$dir/$line.reach.txt" 2>&1 || reached=0
        if [ -z "$known" ]; then
          cat "$dir/$line.reach.txt"
        elif [ "$reached" -eq 1 ]; then
          sed -E 's/^ok   /note /' "$dir/$line.reach.txt"
          echo "     known: listed as known to fail, and it reached the page this time: $known"
        else
          sed -E 's/^FAIL /known /' "$dir/$line.reach.txt"
          echo "     known: $known"
          show=1
        fi
        diagnosed=0
        if [ "$reached" -eq 0 ]; then
          # Whether the phone's byte budget is what stops it: the same
          # fetch with the darwin slice's own B. The check of a line known
          # to fail (line_reached); for any other line, a diagnosis.
          diagnosed=1
          ENGINE_HOST_BYTE_BUDGET=0 "$host" fetch "$dir/$line.client.json" "$TUN_CONFIG" "$limit" "$DOWN_BYTES" "$UP_BYTES" \
            reach "$line against Xray $version with the darwin slice's own byte budget" > "$dir/$line.diagnosis.txt" 2>&1 ||
            diagnosed=0
          if [ -n "$known" ]; then
            cat "$dir/$line.diagnosis.txt"
            [ "$diagnosed" -eq 1 ] ||
              echo "     broken, not the known stall: it reached the page under neither the phone's B nor the darwin slice's own"
          else
            sed -E 's/^(ok   |FAIL )/     diagnosis, not a check: /' "$dir/$line.diagnosis.txt"
          fi
        fi
        [ "$(line_reached "$known" "$reached" "$diagnosed")" -eq 1 ] || ok=0
        # A refusal proves something only where the line got through.
        if [ "$reached" -eq 1 ] || [ "$diagnosed" -eq 1 ]; then
          "$host" fetch "$dir/$line.wrong.json" "$TUN_CONFIG" "$limit" "$DOWN_BYTES" "$UP_BYTES" refused \
            "$line against Xray $version, with $what" || ok=0
        else
          echo "     not asked: $line against Xray $version with $what, since nothing reached the page against this server"
        fi
      fi
      kill "$pid" 2> /dev/null || true
      wait "$pid" 2> /dev/null || true
      [ "$ok" -eq 1 ] || failed=1
      if [ "$ok" -eq 0 ] || [ "$show" -eq 1 ]; then
        echo "     the server's log:"
        tail -n 30 "$dir/$line.xray.log" | sed 's/^/       /'
      fi
    done
  done
  return "$failed"
}

# run <core-dir> <work-dir> <stage> [<xray-dir>]
run() {
  local core work stage="$3" xray="${4:-}" mib limit failed=0
  [ "$(uname -s) $(uname -m)" = "Darwin arm64" ] || die "the darwin slice runs on an Apple silicon Mac, not $(uname -s) $(uname -m)"
  core="$(cd "$1" && pwd)"
  mkdir -p "$2"
  work="$(cd "$2" && pwd)"
  case "$stage" in
    starts) ;;
    fetch | all) [ -n "$xray" ] || die "the fetch needs the Xray servers: $0 xray <dir> first, then name the dir" ;;
    *) usage ;;
  esac
  mib="$(limit_mib)"
  limit=$((mib * 1048576))
  compile "$core" "$work"
  echo "core: $(head -n 1 "$core/stamps/$DARWIN")"
  echo "limit: a footprint under $mib MiB, as MemoryRelief.swift states it"
  trap stop_servers EXIT
  if [ "$stage" = starts ] || [ "$stage" = all ]; then
    starts "$work/engine-host" "$work" "$limit" || failed=1
  fi
  if [ "$stage" = fetch ] || [ "$stage" = all ]; then
    fetch "$work/engine-host" "$work" "$limit" "$xray" || failed=1
  fi
  stop_servers
  return "$failed"
}

selftest() {
  local work f line n private public out known reached diagnosed want got
  work="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" EXIT
  plan > "$work/plan.txt"
  for f in CoreEngine OlcrtcEngine DirectResolver TunArrangement TunPlatform ResolverSnapshot; do
    grep -q "iosApp/PacketTunnel/$f.swift" "$work/plan.txt" || die "the plan does not compile $f.swift"
  done
  for f in $LEFT_OUT; do
    if grep -q "^  iosApp/PacketTunnel/$f\$" "$work/plan.txt"; then die "the plan compiles $f, which it leaves out"; fi
  done
  echo "ok   the plan: $(extension_files | wc -l | tr -d ' ') files of iosApp/PacketTunnel, $(echo "$LEFT_OUT" | wc -w | tr -d ' ') left out, a limit of $(limit_mib) MiB"

  # A reading that cannot fail proves nothing: without the sentence there is
  # no limit.
  if (EXT="$work" limit_mib > /dev/null 2>&1); then die "a limit was read where none is stated"; fi

  for f in $(xray_versions); do
    [ -n "$(macos_sum "$f")" ] || die "xray-macos.txt has no hash for $f"
  done
  echo "ok   xray-macos.txt has every version of scripts/xray-servers.txt: $(xray_versions | tr '\n' ' ')"

  out="$("$PY" -I "$HOST/lines.py" rules "$REFS/bypass-ios-core-bundled-lists.json" "$work/rules.txt")"
  n="$(wc -l < "$work/rules.txt" | tr -d ' ')"
  [ "$(head -n 1 "$work/rules.txt")" = "0.0.0.0/8" ] || die "the direct rules do not start with the private ranges"
  if grep -vqE '^(domain:|full:|[0-9a-fA-F:.]+/[0-9]+$)' "$work/rules.txt"; then die "a direct rule the engine does not take"; fi
  [ "$n" -gt 1000 ] || die "only $n direct rules"
  echo "ok   the olcRTC direct rules: $out, $n lines"

  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=selftest" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2> /dev/null
  private="mOc5Wn4V5Fj9kqy2W1wKkOq2uhxn8A3x6f1k7Q0qJ0o"
  public="jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"
  for line in $LINES; do
    out="$("$PY" -I "$HOST/lines.py" line "$line" "$REFS" "$work" 192.0.2.7 20001 20002 20003 20004 \
      "$work/cert.pem" "$work/key.pem" "$private" "$public" "wrong-public-key")"
    "$PY" -I - "$work" "$line" "$("$PY" -I "$HOST/lines.py" pin "$work/cert.pem")" << 'PY' || die "the $line line"
import json, sys
work, line, pin = sys.argv[1:4]
server = json.load(open(f"{work}/{line}.server.json"))
client = json.load(open(f"{work}/{line}.client.json"))
wrong = json.load(open(f"{work}/{line}.wrong.json"))
out = next(o for o in client["outbounds"] if o["tag"] == "out")
bad = next(o for o in wrong["outbounds"] if o["tag"] == "out")
assert client["inbounds"][0]["port"] == 20001 == wrong["inbounds"][0]["port"]
inbound = server["inbounds"][0]
assert inbound["port"] == 20002 and inbound["listen"] == "192.0.2.7"
assert server["outbounds"][0]["settings"]["redirect"] == "127.0.0.1:20004"
s = out["settings"]
target = s["vnext"][0] if "vnext" in s else s
assert target["port"] == 20002 and target["address"] == "192.0.2.7"
stream, bad_stream = out["streamSettings"], bad["streamSettings"]
if stream["security"] == "reality":
    assert inbound["streamSettings"]["realitySettings"]["dest"] == "127.0.0.1:20003"
    assert stream["realitySettings"]["publicKey"] != bad_stream["realitySettings"]["publicKey"]
else:
    assert stream["tlsSettings"]["pinnedPeerCertSha256"] == pin
    assert bad_stream["tlsSettings"]["pinnedPeerCertSha256"] != pin
assert out["protocol"] == inbound["protocol"] or (out["protocol"], inbound["protocol"]) == ("hysteria", "hysteria")
PY
    echo "ok   the $line line and its server: $out"
  done

  "$PY" -I "$HOST/lines.py" refuse "$REFS/ios-core-reality.json" "$work/refuse.json" 20005 engineHostUnknownField
  grep -q '"engineHostUnknownField": true' "$work/refuse.json" || die "the refusal copy lacks its field"
  "$PY" -I "$HOST/lines.py" socks "$REFS/ios-core-reality.json" "$work/socks.json" 20006
  "$PY" -I -c 'import json, sys; c = json.load(open(sys.argv[1])); assert c["inbounds"][0]["port"] == 20006' "$work/socks.json"
  echo "ok   the copies on another SOCKS port, and the one with a field no core.json has"

  # A line known to fail is excused only for what it is listed for, a stall
  # under the phone's B that the darwin slice's own B clears: one that
  # reaches the page under neither is broken. One not known to fail must
  # reach it under the phone's B. Columns: known (- for none), reached,
  # reached under the darwin slice's B, the verdict.
  while read -r known reached diagnosed want; do
    [ "$known" != - ] || known=""
    got="$(line_reached "$known" "$reached" "$diagnosed")"
    [ "$got" = "$want" ] ||
      die "line_reached: known '$known', reached $reached, under the darwin slice's B $diagnosed: $got, not $want"
  done << 'CASES'
- 1 0 1
- 0 1 0
- 0 0 0
a-stall 1 0 1
a-stall 0 1 1
a-stall 0 0 0
CASES
  echo "ok   the judgment of a fetch: a line known to fail passes only when the darwin slice's own B reaches the page"
}

[ $# -ge 1 ] || usage
cmd="$1"
shift
case "$cmd" in
  plan) [ $# -eq 0 ] || usage; plan ;;
  selftest) [ $# -eq 0 ] || usage; selftest ;;
  bindings) [ $# -eq 1 ] || usage; bindings "$1" ;;
  xray) [ $# -eq 1 ] || usage; xray_fetch "$1" ;;
  run)
    if [ $# -lt 3 ] || [ $# -gt 4 ]; then usage; fi
    run "$@"
    ;;
  *) usage ;;
esac
