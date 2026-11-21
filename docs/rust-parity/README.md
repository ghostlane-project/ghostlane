# Parity ledger

Every feature of today's builds, by platform, and the test that proves it on the Rust core.
Ghostlane is moving from its Go cores to one Rust core on every platform, and a platform
switches over only when none of its rows is left behind without a reason.

| File | What it holds |
| --- | --- |
| `inventory.txt` | One line per feature: `id \| platforms \| source \| what`. Read from the tree, not from memory. A platform is listed only where today's build has the feature. |
| `android.txt`, `ios.txt`, `macos.txt`, `linux.txt`, `windows.txt`, `cli.txt` | One row per feature of that platform: `id \| status \| tests \| note`. |
| `sizes.txt` | The size of each shipped artefact beside today's Go build. |

Status of a row:

- `proven`: the tests named prove the feature on the Rust core. A feature that no engine
  touches (a link parser, a setting, a screen) is held by the shared Kotlin tests, and its row says so.
- `partial`: the tests named prove part of it; the note says what is still open.
- `not-proven`: nothing proves it yet. This is the honest state of most rows today and it
  does not fail the check.
- `retired`: the feature is gone on purpose; the note says why.

A test id is a file in the tree, or `file::name`, where `name` has to occur in that file
(a test function, a class, a workflow job or a script).

`scripts/parity-ledger.py` is the check, and `.github/workflows/parity.yml` runs it. It fails on a
feature with no row, a row for a feature (or a platform) the inventory does not list, a test id
that does not exist, a `proven` or `partial` row with no test, and a `retired` or `partial` row with
no note. It also reads the tree for what it names (the link parser's schemes, the routing modes,
the carriers and transports, the settings, the command line's commands, flags and variables) and
fails when the inventory leaves one out. A new feature is a new inventory line, and a row on each
platform it is listed for, in the same change.
