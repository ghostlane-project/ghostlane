#!/bin/sh
# install-nfpm.sh <dir>: nfpm 2.47.0 (the packager of deb, rpm, apk and Arch
# files) into <dir>, from the release's tarball, checked against the sum below.
# No Go toolchain is needed.
set -eu
dir="${1:?usage: install-nfpm.sh <dir>}"
version=2.47.0
sum=0660ca602b2d2d2ae4781a06c692b3eeb9d437ffea05b831d76e41f4a3188783
mkdir -p "$dir"
curl -fsSL -o "$dir/nfpm.tar.gz" "https://github.com/goreleaser/nfpm/releases/download/v$version/nfpm_${version}_Linux_x86_64.tar.gz"
echo "$sum  $dir/nfpm.tar.gz" | sha256sum -c -
tar -C "$dir" -xzf "$dir/nfpm.tar.gz" nfpm
rm -f "$dir/nfpm.tar.gz"
"$dir/nfpm" --version
