# The Rust core on every platform

`rust/all` is the branch on which Ghostlane moves from its Go cores (sing-box, Xray-core and
the olcRTC engine) to one Rust core on every platform: Android, iOS, macOS, Linux, the CLI
and Windows. `main` stays the branch every published build is made from until this one is
merged into it.

## Where the core lives

The core is [ghostlane-project/xray-rust](https://github.com/ghostlane-project/xray-rust), a
fork of xray-rust that also holds the olcRTC client in Rust. The app names exactly one commit
of it, the pin, in `scripts/ghostlane-core-pin.sh`. On the fork, `ghostlane` is the branch the
shipped iPhone tunnel is built from, and `p6/integ` collects the changes this branch needs.
The pin moves by a pull request that changes the pin and nothing else.

## How work joins this branch

- One task, one branch `rust/t/<id>-<slug>`, one pull request into `rust/all`.
- The failing test comes first: it is pushed and seen red in CI before the change that makes
  it green.
- A pull request is merged with a merge commit once PR Checks are green and it has been
  reviewed. A pushed branch is never rebased or force-pushed.
- `main` is merged into `rust/all` after each release from `main`, never into a task branch.

## What this branch must not do

No build from `rust/**` is signed, uploaded to a store or published, and no push to it starts
a release gate run against live rooms. Until the release guard for that is merged here,
`release.yml` is not dispatched from this branch.

## When it is done

Nothing a published build ships contains a Go program or library: `scripts/check-no-go.sh`
(to come on this branch) reads every shipped file, at every nesting, and fails on one. Each
feature of today's builds has a row under `docs/rust-parity/` (to come) that names the test
proving it on the Rust core.
