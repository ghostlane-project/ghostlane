#!/bin/sh
# build-repos.sh <dist dir> <repo dir> <private key (armored)> <version>
#
# Builds (or updates in place) the apt and dnf repositories the release
# publishes to GitHub Pages: <repo>/apt (reprepro: distribution stable,
# component main, amd64 arm64 armhf) and <repo>/rpm (createrepo_c; packages
# signed with rpmsign), both under one GPG key. The public
# key and the two sources snippets go to the repo root. Runs natively where
# reprepro and createrepo_c exist (the release runner), else in containers
# (debian:bookworm, rockylinux:9) with the same code, re-invoking itself with
# --inner. reprepro (5.3 everywhere) serves the newest version of a package;
# the rpm repository holds every version; older debs are on the release page.
set -eu
here=$(cd "$(dirname "$0")" && pwd)

usage() { echo "usage: $0 <dist> <repo> <private-key.asc> <version>" >&2; exit 2; }

setup_gpg() { # a private GNUPGHOME with the key; sets fpr (no subshell: the export must stick)
  GNUPGHOME=$(mktemp -d)
  export GNUPGHOME
  chmod 700 "$GNUPGHOME"
  gpg --batch --quiet --import "$KEY" 2>/dev/null
  fpr=$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
  [ -n "$fpr" ] || { echo "no secret key in $KEY" >&2; exit 1; }
}

inner_apt() {
  setup_gpg
  mkdir -p "$REPO/apt/conf"
  cat > "$REPO/apt/conf/distributions" <<DIST
Origin: Ghostlane
Label: Ghostlane CLI
Codename: stable
Architectures: amd64 arm64 armhf
Components: main
Description: Ghostlane CLI for Linux
SignWith: $fpr
DIST
  for deb in "$DIST"/ghostlane-cli-"$VERSION"-linux-*.deb; do
    [ -f "$deb" ] || { echo "no deb for $VERSION in $DIST" >&2; exit 1; }
    reprepro -b "$REPO/apt" --ignore=undefinedtarget includedeb stable "$deb"
  done
  reprepro -b "$REPO/apt" export stable
  reprepro -b "$REPO/apt" list stable
}

inner_rpm() {
  setup_gpg
  export GPG_TTY=""
  mkdir -p "$REPO/rpm"
  cat > "$HOME/.rpmmacros" <<MACROS
%_signature gpg
%_gpg_name $fpr
%_gpg_path $GNUPGHOME
%__gpg $(command -v gpg)
MACROS
  # the public half goes into a private rpm keyring (no root needed) so every
  # signature is verified here before it is published
  gpg --batch --armor --export "$fpr" > "$GNUPGHOME/pub.asc"
  rpm --dbpath "$GNUPGHOME/rpmdb" --import "$GNUPGHOME/pub.asc"
  for rpm in "$DIST"/ghostlane-cli-"$VERSION"-linux-*.rpm; do
    [ -f "$rpm" ] || { echo "no rpm for $VERSION in $DIST" >&2; exit 1; }
    cp -f "$rpm" "$REPO/rpm/"
    rpmsign --addsign "$REPO/rpm/$(basename "$rpm")" >/dev/null 2>&1
    rpm --dbpath "$GNUPGHOME/rpmdb" -K "$REPO/rpm/$(basename "$rpm")" | grep -qi 'signatures OK' || { rpm --dbpath "$GNUPGHOME/rpmdb" -Kv "$REPO/rpm/$(basename "$rpm")"; exit 1; }
  done
  createrepo_c --update --quiet "$REPO/rpm"
  rm -f "$REPO/rpm/repodata/repomd.xml.asc"
  gpg --batch --detach-sign --armor "$REPO/rpm/repodata/repomd.xml"
  ls "$REPO/rpm"
}

if [ "${1:-}" = "--inner" ]; then
  shift; which="$1"; DIST="$2"; REPO="$3"; KEY="$4"; VERSION="$5"
  case "$which" in apt) inner_apt ;; rpm) inner_rpm ;; *) usage ;; esac
  exit 0
fi

[ $# -eq 4 ] || usage
DIST=$(cd "$1" && pwd); REPO="$2"; KEY=$(cd "$(dirname "$3")" && pwd)/$(basename "$3"); VERSION="$4"
mkdir -p "$REPO"; REPO=$(cd "$REPO" && pwd)

run_in() { # <image> <packages> <which>
  docker run --rm -v "$DIST:/dist:ro" -v "$REPO:/repo" -v "$KEY:/key.asc:ro" -v "$here/build-repos.sh:/build-repos.sh:ro" "$1" \
    sh -c "{ $2; } >/dev/null 2>&1 && sh /build-repos.sh --inner $3 /dist /repo /key.asc $VERSION"
}
if command -v reprepro >/dev/null 2>&1; then
  sh "$0" --inner apt "$DIST" "$REPO" "$KEY" "$VERSION"
else
  run_in debian:bookworm "apt-get update && apt-get install -y --no-install-recommends reprepro gnupg" apt
fi
if command -v createrepo_c >/dev/null 2>&1 && command -v rpmsign >/dev/null 2>&1; then
  sh "$0" --inner rpm "$DIST" "$REPO" "$KEY" "$VERSION"
else
  run_in rockylinux:9 "dnf install -y createrepo_c rpm-sign gnupg2" rpm
fi
# the public key and the sources snippets at the root; a tiny index
cp -f "$here/ghostlane-repo.gpg.asc" "$REPO/ghostlane-repo.gpg.asc"
cp -f "$here/sources/ghostlane.list" "$REPO/ghostlane.list"
cp -f "$here/sources/ghostlane.repo" "$REPO/ghostlane.repo"
cp -f "$here/index.html" "$REPO/index.html"
touch "$REPO/.nojekyll"
# the containers wrote as root; the caller owns the result
if [ "$(id -u)" != 0 ] && command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$REPO:/repo" alpine:3.20 chown -R "$(id -u):$(id -g)" /repo >/dev/null 2>&1 || true
fi
echo "repositories in $REPO: apt (stable/main) and rpm, signed by the key in ghostlane-repo.gpg.asc"
