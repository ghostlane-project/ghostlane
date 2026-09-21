# Governance

Ghostlane is a small project with one maintainer and a handful of contributors.
This document describes how it is actually run, not how a larger project might
wish to be run.

## The projects

Two repositories, one project:

- **[ghostlane](https://github.com/ghostlane-project/ghostlane)** is the
  application people install: Android, iOS, macOS, Windows and Linux from one
  Kotlin Multiplatform codebase.
- **[olcrtc](https://github.com/ghostlane-project/olcrtc)** is the transport
  engine the application is built around: a tunnel that travels inside a WebRTC
  media session. It is a Go library and command-line program, useful on its own,
  and it is maintained here because Ghostlane depends on it.

They release separately and are pinned to each other: the application names an
exact engine commit, and a change in the engine reaches users only when that pin
moves. Both are open source and both are in scope for the same funding.

olcRTC is a fork of [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc),
which is now archived; the application began as a fork of
[alananisimov/olcbox](https://github.com/alananisimov/olcbox). Upstream
attribution and licences are preserved in both repositories.

## Roles

**Maintainer.** Reviews and merges changes, cuts releases, holds the signing
keys and the store accounts, and answers for what ships. Currently one person,
listed in [MAINTAINERS.md](MAINTAINERS.md).

**Contributor.** Anyone whose pull request has been merged. Contributors are
credited in the commit history and in release notes; no assignment of copyright
is required, and contributions are accepted under the repository's licence.

There is no separate committer tier today. If the project grows enough to need
one, it will be added here before it is used.

## How decisions are made

- **Ordinary changes** go through a pull request and are merged by a maintainer
  once they are reviewed and the checks pass.
- **Disagreements** are settled in the pull request or the issue, in public. If
  no agreement is reached, the maintainer decides and says why.
- **Changes that alter what users are exposed to** — the wire protocol, the
  default routing, what the app sends and where — are described in the pull
  request in plain language, so that a reader who is not a networking engineer
  can tell what changes for them.
- **Releases** are cut by a maintainer when the release gate is green. The gate
  runs real transfers through real relays; its report is attached to every
  release.

## Adding maintainers

A contributor who has been reviewing and fixing other people's work over months,
and whose judgement the current maintainer trusts on changes they did not write,
can be invited to maintain. The invitation is public, in an issue, and any
existing maintainer can object. This has not happened yet.

## Money

See [FUNDING.md](FUNDING.md). In short: if the project is accepted by Open
Source Collective, that body holds the funds and every expense is public;
maintainers and contributors may be paid for actual work; and Globvent Inc.,
which publishes the mobile applications, is separate from the project treasury
and receives none of it.

## Code of conduct

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) applies to both repositories, their
issues and their pull requests. Reports go to the maintainer.
