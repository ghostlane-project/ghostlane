#!/usr/bin/env bash
# The pins the release gate and the build checks read, in one place.
#
# OLCRTC_VERSION is the engine's revision, the one the gate tests and every
# build job checks out; GO_VERSION is the toolchain they check themselves
# against. scripts/olcrtc-pin.sh reads them, with the variables unset first so an
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
OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20261119213407-42dd44d28883}"
