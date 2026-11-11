#!/bin/sh
# install-tools.sh
#
# Puts build-repos.sh's tools (reprepro, createrepo_c, rpmsign, gpg) on a
# Debian or Ubuntu CI runner from the runner's own package archive, when the
# archive answers. It never fails: whatever is missing afterwards,
# build-repos.sh builds in containers (debian:bookworm, rockylinux:9), so a
# runner that cannot reach its archive still publishes.
#
# Why (2026-11-11, release 1.0.471): GitHub's runners could not resolve the
# Ubuntu archive's names for hours. `apt-get update` waited 3 min 20 s and
# exited 0 with warnings, the install then failed, and the repositories job
# failed with it, five attempts in a row. So the update is bounded and asked
# to fail on any index it could not fetch, and a failure here is a warning.
set -u

have() {
  command -v reprepro >/dev/null 2>&1 && command -v createrepo_c >/dev/null 2>&1 &&
    command -v rpmsign >/dev/null 2>&1 && command -v gpg >/dev/null 2>&1
}

if have; then
  echo "repository tools: on this machine already"
  exit 0
fi
if ! command -v apt-get >/dev/null 2>&1; then
  echo "repository tools: no apt-get here; build-repos.sh builds in containers"
  exit 0
fi
as_root=""
[ "$(id -u)" = 0 ] || as_root="sudo"

if $as_root timeout 90 apt-get -o Acquire::Retries=0 -o APT::Update::Error-Mode=any update >/dev/null 2>&1 &&
  $as_root timeout 300 apt-get install -y --no-install-recommends reprepro createrepo-c rpm gnupg >/dev/null 2>&1 &&
  have; then
  echo "repository tools: installed from this machine's archive"
else
  echo "::warning::this runner's package archive did not give reprepro, createrepo-c and rpm (unreachable or slow); build-repos.sh builds what is missing in containers"
fi
exit 0
