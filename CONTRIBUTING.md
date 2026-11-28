# Contributing

Patches, bug reports and reviews are all welcome, and none of them requires
permission first.

## Before a large change

Open an issue and describe what you want to do. A short exchange there saves
rewriting a week of work because the project was heading elsewhere. Small fixes
need no ceremony: send the pull request.

## The rules of the house

- **English in code and comments.** Issues and pull requests may be in English
  or Russian.
- **Explain the why, not the what.** A comment that repeats the line below it is
  noise; a comment that says why the obvious approach did not work is the
  reason the file is readable a year later.
- **Tests where behaviour changes.** A bug fix comes with a test that fails
  without it. This is the one thing reviews are strict about.
- **No user-visible state that does not work.** A screen, a toggle or a label
  that says "not available yet" does not ship. Either it works or it is not
  shown.
- **The Linux CLI parses the same lines.** A change to the Kotlin link or list
  parsers (`LocationsDatasource`, `LinkParser`, `ImportLink`) lands with the
  same case in `cli/crates/gl-links` (and `gl-coreconfig` for what a link
  becomes), so the app and the CLI never read one subscription differently.
- **No Go in what ships.** The engine is the Rust core, built at the one commit
  named in `scripts/ghostlane-core-pin.sh`; a change that puts a Go program or
  library into a shipped file fails `scripts/check-no-go.sh`. A change to the
  core, the pin or the branch it moves on follows
  [docs/rust-core.md](docs/rust-core.md).
- **Never commit credentials.** Not a server list, not a subscription URL, not a
  token, not a keystore, not a room id. The history is public and permanent.

## Getting it to build

[docs/building.md](docs/building.md) has the platform matrix and the build
instructions, including what each platform needs installed. The short version:

```bash
./gradlew :sharedUI:jvmTest          # the shared tests, no device needed
./gradlew :desktopApp:run            # the desktop application
./gradlew :androidApp:assembleDebug  # an Android build
```

The iOS and macOS applications need Xcode and a Mac. The engine is the Rust core
at the pin, built with `scripts/build-ghostlane-core-host.sh` (desktop and
CLI), `scripts/build-ghostlane-core-android.sh` or
`scripts/build-ghostlane-core-ios.sh`; each says in its header what it needs
(`rustup`, `git`, `cmake`, a C compiler and libclang; the NDK for Android,
Xcode for the iPhone). A desktop app run from Gradle finds the engine as the
bundled resource `native/ghostlane-engine` or at the path in
`GHOSTLANE_ENGINE_BINARY`. The Rust CLI under `cli/` builds with `cargo`
(`make -C cli build test lint`).

The shared tests also lay every screen out on phones nobody here owns: widths
from 320 points up, text sizes up to twice the default, four languages
(`BoardFitsTest`, `ScreensFitTest`). A change that cuts a text or puts a button
where it cannot be reached fails them. To look at what they measured:

```bash
OLCBOX_RENDER_DIR=/tmp/renders ./gradlew :sharedUI:jvmTest --tests '*RenderTest*'
```

writes a picture of each screen on each of those phones. The pictures are drawn
with the machine's own fonts, so they show a layout, not what a phone's
typeface makes of it.

## Sending a pull request

- One subject per pull request. Six features in one branch cannot be reviewed,
  and will be asked to be split.
- Fill in the template: what changes, why, how you tested it, and what you could
  not test.
- Say what you could not verify. "I have no Windows machine" is useful
  information, and nobody is expected to own every platform.
- Expect review comments. They are about the code.

## Commits

Conventional commit subjects (`fix(scope): ...`, `feat(scope): ...`), written in
English, in the imperative. The body explains the why.

## Licence

Contributions are accepted under the repository's licence (MIT; see `LICENSE`).
No copyright assignment is asked for and no contributor licence agreement has to
be signed.

## Being paid

Contributors may be compensated from project funds for substantial work; see
[FUNDING.md](FUNDING.md). Nothing about contributing depends on it, and no
contribution is treated differently for being unpaid.
