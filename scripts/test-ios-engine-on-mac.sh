#!/usr/bin/env bash
# test-ios-engine-on-mac.sh: the packet tunnel extension's engines, run on a Mac.
#
#   test-ios-engine-on-mac.sh plan
#       the extension's Swift files the harness compiles, the ones it leaves
#       out and why, and the memory limit read from the extension. Any host.
#   test-ios-engine-on-mac.sh selftest
#       this script's own parts that need no Mac: the plan, the lines (every
#       case's reference with K5's server for it) and the olcRTC direct rules
#       made from the reference configs, the Xray list. Any host with bash,
#       python3 and openssl; seconds.
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
#   as the extension starts it (resolvers, TunArrangement.decide from
#   core.json alone, DirectResolver.substitute, CoreEngine.start), on a free
#   loopback port as
#   scripts/check-rust-core-configs.sh does; its SOCKS port asks for the login
#   the arrangement read and refuses any other; then stopped and the port let
#   go. One the core must refuse (a field no core.json has) is refused, naming
#   it. OlcrtcEngine with the parameters the app writes, under Global and
#   with the bundled lists' direct rules, joins the room of a stand-in hub
#   (carrier `none`) through the pin; no olcRTC server stands in that room, so
#   its start ends there. A carrier this build does not carry is refused
#   before any room is dialed.
# fetch: every line the app writes a reference for, one per case of every
#   kind (ios-core-<shape>.json and core-<case>.json; no list here names
#   one), against K5's server for it (scripts/rust-core-interop/lines/, the
#   servers scripts/rust-core-interop.py runs the builder's dumps against;
#   iosApp/Tests/EngineHost/lines.py says how a line finds its server and
#   what of it the run sets), on a local Xray server of every version the
#   CLI's end-to-ends use: 2 MiB down and 1 MiB up through the core's SOCKS
#   port, the page reached at a TEST-NET-3 address the server sends to
#   loopback, the body's sha256 checked both ways. No line is excused: one
#   that does not reach the page under the phone's B fails, and the same
#   fetch under the darwin slice's own B is printed beside it as a
#   diagnosis, not a check. Once the line reached the page against a server
#   under either B, its refusals must not reach it (asked where nothing got
#   through, a refusal would prove nothing): another credential (K5's
#   mutant), for REALITY a public key the server does not hold, for TLS a pin
#   of another certificate and no pin, and for a line that waives the
#   certificate checks, the same line without the waiver.
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
# K5's servers, one file a line (scripts/rust-core-interop.py).
LINES_DIR="$ROOT/scripts/rust-core-interop/lines"
# The files of the extension this program does not compile (why_left_out).
LEFT_OUT="PacketTunnelProvider.swift HevTunnel.swift MemoryWatch.swift MemoryPressure.swift"
DOWN_BYTES=2097152
UP_BYTES=1048576
PY="${PYTHON:-python3}"

die() {
  echo "FAIL $*" >&2
  exit 1
}

usage() {
  sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
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
    "$host" start "$work/starts/$name" "$limit" || failed=1
  done
  [ "$n" -gt 0 ] || die "no *core-*.json in $REFS"
  echo "     $n reference core.json files started"
  "$PY" -I "$HOST/lines.py" refuse "$REFS/ios-core-reality.json" "$work/starts/ios-core-reality-unknown-field.json" \
    "$(free_port)" engineHostUnknownField
  "$host" refuse "$work/starts/ios-core-reality-unknown-field.json" engineHostUnknownField || failed=1

  "$PY" -I "$HOST/lines.py" rules "$REFS/bypass-ios-core-bundled-lists.json" "$work/direct-rules.txt"
  "$host" olcrtc none - "$limit" joins || failed=1
  "$host" olcrtc none "$work/direct-rules.txt" "$limit" joins || failed=1
  "$host" olcrtc telemost - "$limit" refused || failed=1
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

# certificate <dir>: the local servers' certificate, made for every name
# the lines send (lines.py names); prints the names.
certificate() {
  local dir="$1" names
  names="$("$PY" -I "$HOST/lines.py" names "$REFS" "$LINES_DIR")" || die "no server names for the local servers' certificate"
  printf '%s\n' "[req]" "distinguished_name = dn" "x509_extensions = san" "prompt = no" \
    "[dn]" "CN = engine-host" \
    "[san]" "subjectAltName = $names" "basicConstraints = critical,CA:FALSE" \
    "keyUsage = critical,digitalSignature,keyEncipherment" "extendedKeyUsage = serverAuth" > "$dir/openssl.cnf"
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 -config "$dir/openssl.cnf" \
    -keyout "$dir/key.pem" -out "$dir/cert.pem" 2> /dev/null || die "openssl made no certificate for $names"
  echo "$names"
}

# fetch <host> <work> <limit-bytes> <xray-dir>
fetch() {
  local host="$1" work="$2" limit="$3" xray="$4" failed=0 page target private public wrong_public names address
  local version line reference server network dir socks port pid ok reached diagnosed refusal what
  mkdir -p "$work/certs" "$work/lines"
  "$PY" -I -c 'import ssl, sys; print("python", sys.version.split()[0], ssl.OPENSSL_VERSION); sys.exit(0 if ssl.HAS_TLSv1_3 else 1)' ||
    die "$PY has no TLS 1.3 for the Reality target: set PYTHON to one that has"
  # The lines, each a case's reference with K5's server for it. A reference
  # with no server, or a shape K5 carries that no line here does, fails;
  # the other lines still run.
  if ! "$PY" -I "$HOST/lines.py" lines "$REFS" "$LINES_DIR" > "$work/lines.txt" 2> "$work/lines.err"; then
    cat "$work/lines.err"
    failed=1
  fi
  [ -s "$work/lines.txt" ] || die "no line to fetch: $(cat "$work/lines.err")"
  echo "     $(wc -l < "$work/lines.txt" | tr -d ' ') lines, each a reference against K5's server for it:"
  while read -r line reference server network; do
    echo "       $line: $reference, scripts/rust-core-interop/lines/$server.server.json ($network)"
  done < "$work/lines.txt"
  names="$(certificate "$work/certs")"
  echo "     the local servers' certificate names $names"
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
    dir="$work/lines/$version"
    mkdir -p "$dir"
    while read -r line reference server network <&3; do
      socks="$(free_port)"
      port="$(free_port)"
      if ! "$PY" -I "$HOST/lines.py" line "$line" "$REFS" "$LINES_DIR" "$dir" "$address" "$socks" "$port" "$target" "$page" \
        "$work/certs/cert.pem" "$work/certs/key.pem" "$private" "$public" "$wrong_public" > "$dir/$line.made.txt" 2>&1; then
        echo "FAIL $line against Xray $version: no configs for it: $(tr '\n' ' ' < "$dir/$line.made.txt")"
        failed=1
        continue
      fi
      "$xray/$version/xray" run -c "$dir/$line.server.json" > "$dir/$line.xray.log" 2>&1 < /dev/null &
      pid=$!
      PIDS="$PIDS $pid"
      ok=1
      if ! xray_up "$dir/$line.xray.log" "$pid" "$network" "$address" "$port"; then
        echo "FAIL $line against Xray $version: the server did not listen on $network $address:$port"
        ok=0
      else
        reached=1
        "$host" fetch "$dir/$line.client.json" "$limit" "$DOWN_BYTES" "$UP_BYTES" reach \
          "$line against Xray $version" < /dev/null || reached=0
        diagnosed=0
        if [ "$reached" -eq 0 ]; then
          ok=0
          # Whether the phone's byte budget is what stops it: the same fetch
          # with the darwin slice's own B, once. Not a check: the line has
          # failed, and this says what to report.
          diagnosed=1
          ENGINE_HOST_BYTE_BUDGET=0 "$host" fetch "$dir/$line.client.json" "$limit" "$DOWN_BYTES" "$UP_BYTES" reach \
            "$line against Xray $version with the darwin slice's own byte budget" > "$dir/$line.diagnosis.txt" 2>&1 < /dev/null ||
            diagnosed=0
          sed -E 's/^(ok   |FAIL )/     diagnosis, not a check: /' "$dir/$line.diagnosis.txt"
        fi
        # A refusal proves something only where the line got through.
        if [ "$reached" -eq 1 ] || [ "$diagnosed" -eq 1 ]; then
          while IFS=$'\t' read -r refusal what <&4; do
            "$host" fetch "$dir/$line.$refusal.json" "$limit" "$DOWN_BYTES" "$UP_BYTES" refused \
              "$line against Xray $version, $what" < /dev/null || ok=0
          done 4< "$dir/$line.refusals"
        else
          echo "     not asked: $line against Xray $version $(cut -f 2 "$dir/$line.refusals" | paste -s -d ';' - | sed 's/;/; /g'), since nothing reached the page against this server"
        fi
      fi
      kill "$pid" 2> /dev/null || true
      wait "$pid" 2> /dev/null || true
      [ "$ok" -eq 1 ] || failed=1
      if [ "$ok" -eq 0 ]; then
        echo "     the server's log:"
        tail -n 30 "$dir/$line.xray.log" | sed 's/^/       /'
      fi
    done 3< "$work/lines.txt"
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
  local work f n out want got
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

  # The lines: every case's reference, as a glob finds them here and as
  # lines.py reads them, each with K5's server for it.
  "$PY" -I "$HOST/lines.py" lines "$REFS" "$LINES_DIR" > "$work/lines.txt" ||
    die "the lines: a reference with no server, or a shape no line carries (above)"
  want="$(for f in "$REFS"/ios-core-*.json "$REFS"/core-*.json; do basename "$f"; done | sort)"
  got="$(awk '{ print $2 }' "$work/lines.txt" | sort)"
  [ "$got" = "$want" ] ||
    die "the fetch's lines are not every case's reference: $(diff <(echo "$want") <(echo "$got") | grep -E '^[<>]' | tr '\n' ' ')"
  [ "$(awk '{ print $1 }' "$work/lines.txt" | sort -u | wc -l)" -eq "$(wc -l < "$work/lines.txt")" ] || die "two references are one line"
  echo "ok   the fetch's lines are every case's reference ($(echo "$want" | wc -l | tr -d ' ') of them), each with K5's server for it"

  # Every file of the tree the Mac job reads reaches it: a change of any one
  # of them starts the engines job, by the room check's scope, whose lists
  # the workflow's paths are held to. A server of K5's changed alone is to
  # be fetched against too, not found out by the next change to the iPhone.
  missed=""
  n=0
  for f in "$LINES_DIR"/* "$REFS"/*core-*.json "$HOST"/* "$EXT"/*.swift \
    "$ROOT/scripts/test-ios-engine-on-mac.sh" "$ROOT/scripts/xray-servers.txt" "$ROOT/scripts/ghostlane-core-pin.sh" \
    "$ROOT/scripts/ghostlane-core-resolve.sh" "$ROOT/scripts/build-ghostlane-core-host.sh"; do
    [ -e "$f" ] || die "no $f"
    case "$f" in */__pycache__) continue ;; esac
    echo "${f#"$ROOT"/}" > "$work/changed"
    out="$(bash "$ROOT/scripts/test-ios-app-room-check.sh" scope "$work/changed")" || die "the room check's scope of ${f#"$ROOT"/}"
    case "$out" in *engines=true*) ;; *) missed="$missed ${f#"$ROOT"/}" ;; esac
    n=$((n + 1))
  done
  [ -z "$missed" ] ||
    die "a change of these does not start the Mac's engines job (ENGINE_PATHS in scripts/test-ios-app-room-check.sh, and the paths of rust-ios.yml):$missed"
  echo "ok   a change of any of the $n files of the tree the Mac's engines job reads starts it"

  # The certificate for every name the lines send, then each line made and
  # read back, and each refusal of a line or a server that does not fit it.
  out="$(certificate "$work")"
  for n in $(echo "$out" | tr ',' ' '); do
    openssl x509 -in "$work/cert.pem" -noout -text | grep -qF "$n" || die "the certificate does not name ${n#DNS:}"
  done
  echo "ok   the local servers' certificate names every server name the lines send: $out"
  "$PY" -I "$HOST/lines.py" selftest "$REFS" "$LINES_DIR" "$work" || die "the lines' own checks (above)"

  "$PY" -I "$HOST/lines.py" refuse "$REFS/ios-core-reality.json" "$work/refuse.json" 20005 engineHostUnknownField
  grep -q '"engineHostUnknownField": true' "$work/refuse.json" || die "the refusal copy lacks its field"
  "$PY" -I "$HOST/lines.py" socks "$REFS/ios-core-reality.json" "$work/socks.json" 20006
  "$PY" -I -c 'import json, sys; c = json.load(open(sys.argv[1])); assert c["inbounds"][0]["port"] == 20006' "$work/socks.json"
  echo "ok   the copies on another SOCKS port, and the one with a field no core.json has"
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
