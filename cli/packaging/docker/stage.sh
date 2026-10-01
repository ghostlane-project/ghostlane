#!/bin/sh
# stage.sh <dist dir> <version>: lays the release tarballs' binaries out as
# dist/docker/<TARGETPLATFORM>/ghostlane for the Dockerfile, so the image holds
# exactly what the release shipped. Every platform must be there.
set -eu
dist="$1"; version="$2"
for pair in "amd64 linux/amd64" "arm64 linux/arm64" "arm7 linux/arm/v7"; do
  arch="${pair%% *}"; platform="${pair#* }"
  tgz="$dist/ghostlane-cli-$version-linux-$arch.tar.gz"
  [ -f "$tgz" ] || { echo "stage.sh: $tgz missing (arch $arch)" >&2; exit 1; }
  out="$dist/docker/$platform"
  rm -rf "$out"; mkdir -p "$out"
  tar -C "$out" -xzf "$tgz" ./ghostlane
  chmod 0755 "$out/ghostlane"
done
echo "staged: $(ls -d "$dist"/docker/linux/* "$dist"/docker/linux/arm/* 2>/dev/null | tr '\n' ' ')"
