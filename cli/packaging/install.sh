#!/bin/sh
# ghostlane-cli installer for any Linux.
#   curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/download/<tag>/install.sh | sh
# Flags: --version V  --base-url URL  --family deb|rpm|apk|pacman|tar  --no-service
# Every download is checked against SHA256SUMS, and SHA256SUMS against the
# release signing key below; a mismatch stops the install.
set -eu

VERSION="${GHOSTLANE_VERSION:-__VERSION__}"
BASE_URL="${GHOSTLANE_BASE_URL:-}"
FAMILY="${GHOSTLANE_FAMILY:-}"
ROOT="${GHOSTLANE_INSTALL_ROOT:-}"
NO_SERVICE=0
REPO="ghostlane-project/ghostlane"

PUBKEY_PEM='-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAz252wYKYdbLzn/fyN1ZvGt7oTDNZTNJrWosR8/L+S/0=
-----END PUBLIC KEY-----'

usage() {
  cat <<'USAGE'
ghostlane-cli installer for any Linux.
  curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/download/<tag>/install.sh | sh
Flags:
  --version V      the release to install (default: this script's release)
  --base-url URL   a mirror serving that release's assets (needs --version)
  --family F       deb | rpm | apk | pacman | tar (default: from /etc/os-release)
  --no-service     do not enable or start the service
Every download is checked against SHA256SUMS, and SHA256SUMS against the
release signing key embedded in the script; a mismatch stops the install.
USAGE
}

die() { echo "install.sh: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required"; }

fetch() { # url dest
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$2" "$1"
  else die "curl or wget is required"; fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --family) FAMILY="$2"; shift 2 ;;
    --no-service) NO_SERVICE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown flag $1" >&2; exit 2 ;;
  esac
done

arch="${GHOSTLANE_ARCH:-}"
if [ -z "$arch" ]; then
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    armv7l|armv7|armhf) arch=arm7 ;;
    *) die "unsupported architecture $(uname -m)" ;;
  esac
fi

if [ -z "$FAMILY" ]; then
  FAMILY=tar
  if [ -r /etc/os-release ]; then
    # read in a subshell: os-release sets VERSION and would clobber ours
    ids=$(. /etc/os-release 2>/dev/null; echo "${ID:-} ${ID_LIKE:-}")
    case " $ids " in
      *" debian "*|*" ubuntu "*) FAMILY=deb ;;
      *" rhel "*|*" fedora "*|*" centos "*|*" rocky "*|*" almalinux "*|*" suse "*|*" opensuse "*) FAMILY=rpm ;;
      *" alpine "*) FAMILY=apk ;;
      *" arch "*) FAMILY=pacman ;;
    esac
  fi
fi

if [ "$VERSION" = "__VERSION__" ] && [ -n "$BASE_URL" ]; then
  die "a mirror serves one release: pass --version with --base-url"
fi
if [ "$VERSION" = "__VERSION__" ]; then
  need curl
  VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=10" \
    | grep -o '"tag_name": *"v[^"]*"' | head -1 | sed 's/.*"v\([^"]*\)"/\1/') || true
  [ -n "$VERSION" ] || die "could not find a release; pass --version"
fi
if [ -z "$BASE_URL" ]; then
  BASE_URL="https://github.com/$REPO/releases/download/v$VERSION"
fi

case "$FAMILY" in
  deb) asset="ghostlane-cli-$VERSION-linux-$arch.deb" ;;
  rpm) asset="ghostlane-cli-$VERSION-linux-$arch.rpm" ;;
  apk) asset="ghostlane-cli-$VERSION-linux-$arch.apk" ;;
  pacman) asset="ghostlane-cli-$VERSION-linux-$arch.pkg.tar.zst" ;;
  tar) asset="ghostlane-cli-$VERSION-linux-$arch.tar.gz" ;;
  *) die "unknown family $FAMILY" ;;
esac

need openssl
need sha256sum
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "ghostlane-cli $VERSION ($FAMILY, $arch) from $BASE_URL"
fetch "$BASE_URL/ghostlane-cli-$VERSION-SHA256SUMS" "$tmp/SHA256SUMS"
fetch "$BASE_URL/ghostlane-cli-$VERSION-SHA256SUMS.sig" "$tmp/SHA256SUMS.sig"
fetch "$BASE_URL/$asset" "$tmp/$asset"

pub="$tmp/pub.pem"
if [ -n "${GHOSTLANE_PUBKEY_FILE:-}" ]; then cp "$GHOSTLANE_PUBKEY_FILE" "$pub"; else printf '%s\n' "$PUBKEY_PEM" > "$pub"; fi
openssl pkeyutl -verify -pubin -inkey "$pub" -rawin -in "$tmp/SHA256SUMS" -sigfile "$tmp/SHA256SUMS.sig" >/dev/null 2>&1 \
  || die "signature of SHA256SUMS does not verify; refusing to install"
expected=$(grep " $asset\$" "$tmp/SHA256SUMS" | awk '{print $1}')
[ -n "$expected" ] || die "$asset is not in SHA256SUMS"
actual=$(sha256sum "$tmp/$asset" | awk '{print $1}')
[ "$expected" = "$actual" ] || die "checksum mismatch for $asset; refusing to install"
echo "signature and checksum verified"

as_root() { if [ "$(id -u)" -eq 0 ] || [ -n "$ROOT" ]; then "$@"; else sudo "$@"; fi; }

case "$FAMILY" in
  deb) as_root dpkg -i "$tmp/$asset" ;;
  rpm) as_root rpm -U --replacepkgs "$tmp/$asset" ;;
  apk) as_root apk add --allow-untrusted "$tmp/$asset" ;;
  pacman) as_root pacman -U --noconfirm "$tmp/$asset" ;;
  tar)
    stage="$tmp/stage"; mkdir -p "$stage"; tar -C "$stage" -xzf "$tmp/$asset"
    as_root install -d "$ROOT/usr/local/bin" "$ROOT/etc/systemd/system" "$ROOT/usr/local/share/doc/ghostlane-cli"
    as_root install -m 0755 "$stage/ghostlane" "$ROOT/usr/local/bin/ghostlane"
    sed 's#/usr/bin/ghostlane#/usr/local/bin/ghostlane#' "$stage/ghostlane.service" > "$tmp/unit"
    as_root install -m 0644 "$tmp/unit" "$ROOT/etc/systemd/system/ghostlane.service"
    for f in README.md LICENSE COPYRIGHT; do
      [ -f "$stage/$f" ] && as_root install -m 0644 "$stage/$f" "$ROOT/usr/local/share/doc/ghostlane-cli/$f"
    done
    # an OpenRC host (Alpine) gets the init script, pointed at the installed binary
    if command -v openrc-run >/dev/null 2>&1 && [ -f "$stage/ghostlane.openrc" ]; then
      sed 's#command=/usr/bin/ghostlane#command=/usr/local/bin/ghostlane#' "$stage/ghostlane.openrc" > "$tmp/openrc"
      as_root install -d "$ROOT/etc/init.d"
      as_root install -m 0755 "$tmp/openrc" "$ROOT/etc/init.d/ghostlane"
    fi
    if [ -z "$ROOT" ]; then
      getent group ghostlane >/dev/null 2>&1 || as_root groupadd -r ghostlane 2>/dev/null || as_root addgroup -S ghostlane
      getent passwd ghostlane >/dev/null 2>&1 || as_root useradd -r -g ghostlane -d /var/lib/ghostlane -s /sbin/nologin ghostlane 2>/dev/null \
        || as_root adduser -S -G ghostlane -h /var/lib/ghostlane -s /sbin/nologin ghostlane
      if [ "$NO_SERVICE" -eq 0 ] && [ -d /run/systemd/system ]; then
        as_root systemctl daemon-reload; as_root systemctl enable --now ghostlane.service
      elif [ "$NO_SERVICE" -eq 0 ] && [ -d /run/openrc ] && [ -f /etc/init.d/ghostlane ]; then
        as_root rc-update add ghostlane default; as_root rc-service ghostlane start
      fi
    fi
    ;;
esac
echo "done. Next: ghostlane add <list-url>; ghostlane connect DE --tun (or --proxy)"
