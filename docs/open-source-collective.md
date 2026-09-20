# The project, and how it would be funded

This document exists for anyone assessing the project from outside: a potential
funder, a fiscal host, a packager, or a contributor deciding whether this is a
serious place to spend an evening. It says what the project is, who runs it,
and what would happen to money given to it.

## One project, two repositories

**[Ghostlane](https://github.com/romanpodpriatov/ghostlane)** is the
application people install. Kotlin Multiplatform and Compose, one codebase for
Android, iOS, macOS, Windows and Linux. MIT.

**[olcRTC](https://github.com/romanpodpriatov/olcrtc)** is the transport engine
the application is built around: an encrypted tunnel that travels inside a
WebRTC media session, so that on a network which recognises and drops tunnels by
their shape, what remains on the wire is an ordinary video call. It is a Go
library and command-line program, useful on its own and used by other clients.
Apache-2.0, with an explicit patent grant, because it is meant to be embedded.

They are released separately and pinned to each other: the application names an
exact engine commit, so a change in the engine reaches users only when that pin
is moved deliberately. Both are in scope for the same funding, the same
governance and the same code of conduct.

Both are forks, and both say so: the engine of the archived
[openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc), the
application of [alananisimov/olcbox](https://github.com/alananisimov/olcbox).
Upstream licences and copyrights are preserved; see each repository's `NOTICE`
and `LICENSE`.

## That it is actually maintained

- Releases are frequent and versioned, with builds for every platform produced
  by public CI and published with checksums and build provenance.
- Every release is gated on a suite that moves real traffic through real relays
  on every supported provider, and the report of that run is attached to the
  release. A release whose gate is red does not ship.
- Issues from users are answered, and outside contributions are reviewed in
  public and merged; several have shipped.
- The engine's own repository carries the gate, the documentation of every
  transport, and the release history the application pins against.

## Who runs it

One maintainer today, named in
[MAINTAINERS.md](https://github.com/romanpodpriatov/ghostlane/blob/main/MAINTAINERS.md),
with contributors credited in the history and the release notes.
[GOVERNANCE.md](https://github.com/romanpodpriatov/ghostlane/blob/main/GOVERNANCE.md)
describes how decisions are made, how disagreements end, and how a contributor
becomes a maintainer.

## The money

Open Source Collective would be the fiscal host: it holds the funds, handles
compliance, and pays approved expenses, all of it public and itemised. The
project is not a company and has no bank account.

What funds pay for, and the rules the maintainers hold themselves to when they
are the ones being paid, are in
[FUNDING.md](https://github.com/romanpodpriatov/ghostlane/blob/main/FUNDING.md).
In short: development, infrastructure, testing, security, documentation, and
compensation for actual work that exists and can be inspected.

## Globvent Inc.

A mobile application cannot be distributed without someone holding a developer
account. **Globvent Inc.** holds those accounts and publishes Ghostlane on the
App Store and Google Play. That is its entire role.

- Project funds are not received, held, controlled by, or paid to Globvent Inc.
- Publishing the applications gives it no claim on the funds and no control over
  how they are spent.
- The treasuries are separate: Open Source Collective holds the project's,
  Globvent Inc. holds the store accounts.
- The source stays open under its licences regardless, and anyone may build and
  distribute their own copy under them.

## Contact

Through the repositories: issues for anything public, and the private security
channel in each repository's `SECURITY.md` for anything that should not be.
