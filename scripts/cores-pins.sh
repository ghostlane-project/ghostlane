#!/usr/bin/env bash
# The pins the release gate and the build checks read, in one place.
#
# OLCRTC_VERSION is the engine's revision, the one the gate tests and every
# build job checks out; GO_VERSION is the toolchain they check themselves
# against; SINGBOX_VERSION and SINGBOX_MODULE_SUM are the sing-box that
# scripts/patched-singbox.sh builds for the config checks of PR Checks.
# scripts/olcrtc-pin.sh reads them, with the variables unset first so an
# inherited value cannot win, for release.yml, pr-checks.yml and the release
# gate. release.yml keeps a copy of GO_VERSION in its env, because a job-level
# value cannot be computed from a sourced shell file; its release_version job
# fails when the copy and this line disagree.
#
# Re-pinning the engine: change OLCRTC_VERSION to a pseudo-version of a commit
# on the fork's `proofkit` branch (GitHub drops a commit no branch reaches, and
# every checkout of it then fails). The pin must carry internal/gate; the gate
# refuses an engine that predates it.
GO_VERSION="${GO_VERSION:-1.26.5}"
SINGBOX_VERSION="${SINGBOX_VERSION:-1.13.14}"
# The module's hash (go.sum's h1) at that version. sing-box is compiled from a
# patched copy of it (scripts/patched-singbox.sh, the Reality hello patch in
# scripts/patches/); the script checks the download against this hash. A new
# SINGBOX_VERSION needs its hash here and the patch re-cut against it.
SINGBOX_MODULE_SUM="${SINGBOX_MODULE_SUM:-h1:p9/eqwilCgzyR/DpKM8hq7ppvzPIq1QMLgZWT3Cbg10=}"
OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20261119213407-42dd44d28883}"
