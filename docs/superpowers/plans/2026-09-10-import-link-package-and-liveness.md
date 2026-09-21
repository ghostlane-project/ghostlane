# Import Link, Android Package Rename and Jitsi Liveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship release 1.0.4xx with a one-tap import link for panels and bots, the Android package renamed to `org.proofkit.app`, main-branch CI, and an engine fix for the Jitsi datachannel liveness teardown under upload load (olcbox #15, #19).

**Architecture:** Three repositories. `olcbox` (this repo) gets a shared `ImportLink` parser and per-platform entry points (Android intent filters, iOS URL types + associated domains, macOS/Linux URI handlers) that all end in the existing `HomeScreenViewModel.onImportFullConfig`. `proofkit-dvpn` gets a static `/add` page, the `.well-known` verification files, an nginx location and an "Open in ProofKit" button in the Telegram bot. `olcrtc` (branch `proofkit-udp-spike`) gets a bounded, blocking bridge send queue in the Jitsi engine so bulk upload back-pressures smux instead of starving control pongs and killing the session.

**Tech Stack:** Kotlin Multiplatform + Compose (olcbox), Swift (iOS host, typechecked on Linux with shims), Go 1.26 (olcrtc), Rust (telegram-bot), nginx + static HTML (site), GitHub Actions (release.yml `all`, ios-frameworks.yml).

**Spec:** the discussion in session `session_01CEfgqJHWsowYtu4VT8PSw5` (2026-09-10), issues ghostlane-project/ghostlane#15 and #19.

## Global Constraints

- No user-visible string may contain the word "subscription" (App Review 3.1.1 history). Use "server list".
- Commit trailer on every commit: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01CEfgqJHWsowYtu4VT8PSw5`.
- olcbox verification available locally: `./gradlew --no-daemon :sharedUI:jvmTest :desktopApp:compileKotlin`; Android compiles only in CI (pr-checks); iOS Kotlin compiles only in CI/Xcode; Swift is typechecked on Linux with `swiftc -typecheck` and Darwin shims (see `feedback_swift_typecheck_on_linux`).
- olcrtc verification: `go test ./...` in `/root/olcrtc-fork` (Go 1.26.x). No wire-format change is allowed in Task 2: old and new peers must keep talking.
- The import link payload is a credential (a server-list URL). It travels in a URL fragment on https links so it never reaches nginx logs, and it is never logged unscrubbed (`LogScrubber` already replaces `import <link>`).
- Android package rename is a one-time reinstall for users; it ships in the same release as the import link and the release notes say so.
- Team ID `3QJG3J7L66`, iOS bundle `org.proofkit.app`, Android release cert SHA-256 `59:DC:54:21:81:20:4C:88:40:2D:6C:71:EA:47:4B:8B:F0:9A:CA:B8:64:C9:00:DC:28:32:E6:FF:62:FC:3E:83`.

---

### Task 1: pr-checks runs on pushes to main

**Files:**
- Modify: `.github/workflows/pr-checks.yml:1-12`

**Interfaces:** none.

- [ ] **Step 1: Change the trigger**

Replace the `on:` block so pushes to `main` are checked too (today `branches-ignore: main` skips exactly the branch that ships):

```yaml
on:
  pull_request:
    branches:
      - main
  push:
```

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/pr-checks.yml
git commit -m "ci: check pushes to main too

pr-checks skipped the one branch that ships; today's desktop work had to
be pushed to a throwaway branch to get the Android build checked."
git push proofkit main
```

- [ ] **Step 3: Verify** the run appears for the main push: `GET /repos/ghostlane-project/ghostlane/actions/workflows/pr-checks.yml/runs?branch=main&per_page=1` shows a run for HEAD and it completes green.

---

### Task 2: olcrtc — bounded, blocking Jitsi bridge send queue (issue #15)

**Files (repo `/root/olcrtc-fork`, branch `proofkit-udp-spike`):**
- Modify: `internal/engine/jitsi/jitsi.go` (constants at 44-49, `enqueueBridgeFrame` / `enqueuePeerBridgeFrame` at ~1100-1140, `sendLoop` at ~1141, session construction at ~213)
- Test: `internal/engine/jitsi/sendqueue_test.go` (new)

**Diagnosis to keep in the commit message:** `sendQueue` holds 5000 frames of up to 16 KB (80 MB). At the ~5 Mbit/s a JVB relay carries, that is minutes of queue; control pings and pongs on the same smux session wait behind it and liveness fires after 4 misses (15 s). When the queue is full `Send` returns `ErrSendQueueFull`, which reaches smux as a write error and closes the session at once — the "closed pipe" the reporter saw at `missed_pongs=2`. The reporter's Speedtest upload reproduces both.

**Interfaces:**
- Consumes: `engine.Session.Send(data []byte) error`, `SendTo(peerID, data)` (unchanged signatures).
- Produces: `Send`/`SendTo` block while the queue is full instead of failing; a blocked send returns `ErrSessionClosed` when the session closes. `defaultSendQueueSize` becomes 32 frames (≤ 512 KB, ~0.8 s at 5 Mbit/s). Peer queues are per peer so one slow client does not stall the others.

- [ ] **Step 1: Write the failing tests**

```go
package jitsi

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// A session whose sendLoop never runs: whatever is enqueued stays enqueued,
// which is exactly the saturated-link case.
func newQueuedSession(t *testing.T) *Session {
	t.Helper()
	s := newTestSession(t) // existing helper; must NOT start sendLoop
	s.bridgeReady.Store(true)
	return s
}

func TestSendBlocksInsteadOfFailingWhenTheQueueIsFull(t *testing.T) {
	s := newQueuedSession(t)
	for i := 0; i < defaultSendQueueSize; i++ {
		if err := s.Send([]byte("x")); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
	}
	var done atomic.Bool
	go func() {
		_ = s.Send([]byte("one more")) // must block, not return ErrSendQueueFull
		done.Store(true)
	}()
	time.Sleep(100 * time.Millisecond)
	if done.Load() {
		t.Fatal("Send returned on a full queue; it has to wait for room")
	}
	<-s.sendQueue // drain one frame
	deadline := time.Now().Add(2 * time.Second)
	for !done.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !done.Load() {
		t.Fatal("Send did not resume after the queue drained")
	}
}

func TestABlockedSendReturnsWhenTheSessionCloses(t *testing.T) {
	s := newQueuedSession(t)
	for i := 0; i < defaultSendQueueSize; i++ {
		_ = s.Send([]byte("x"))
	}
	errCh := make(chan error, 1)
	go func() { errCh <- s.Send([]byte("blocked")) }()
	time.Sleep(50 * time.Millisecond)
	_ = s.Close()
	select {
	case err := <-errCh:
		if !errors.Is(err, ErrSessionClosed) {
			t.Fatalf("err = %v, want ErrSessionClosed", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("close did not unblock the send")
	}
}

func TestAFullQueueForOnePeerDoesNotBlockAnother(t *testing.T) {
	s := newQueuedSession(t)
	for i := 0; i < defaultSendQueueSize; i++ {
		if err := s.SendTo("peer-a", []byte("x")); err != nil {
			t.Fatalf("peer-a send %d: %v", i, err)
		}
	}
	done := make(chan error, 1)
	go func() { done <- s.SendTo("peer-b", []byte("y")) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("peer-b: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("peer-b waited on peer-a's queue")
	}
}
```

If `newTestSession` does not exist, look at how `jitsi_test.go` builds a `Session` (it constructs one with `newSession(...)` or a literal) and add the helper there; it must not start `sendLoop`.

- [ ] **Step 2: Run the tests, expect failure**

```bash
cd /root/olcrtc-fork && go test ./internal/engine/jitsi/ -run 'TestSendBlocks|TestABlockedSend|TestAFullQueueForOnePeer' -v
```
Expected: `TestSendBlocksInsteadOfFailingWhenTheQueueIsFull` fails (Send returned `ErrSendQueueFull`), `TestAFullQueueForOnePeerDoesNotBlockAnother` fails (shared `peerSendQueue`).

- [ ] **Step 3: Implement**

In `jitsi.go`:

```go
// defaultSendQueueSize bounds what waits for the bridge, per queue. At the
// ~5 Mbit/s a JVB relay carries, 32 frames of 16 KB is under a second of
// queue: control pings and pongs share this path with bulk data, and a
// pong that waits longer than the liveness timeout is a session torn down.
// The old 5000 was minutes of queue, and a full queue was an error that
// closed smux at once (olcbox #15).
defaultSendQueueSize = 32
```

Replace the `default: return ErrSendQueueFull` arms:

```go
func (s *Session) enqueueBridgeFrame(framed []byte) error {
	if s.closed.Load() { return ErrSessionClosed }
	if !s.bridgeReady.Load() { return ErrBridgeNotReady }
	if len(framed) > bridgeMaxMessageSize { return ErrSendTooLarge }
	// Block: a full queue is back-pressure for smux, not a failure. The
	// writer slows down; the session stays up.
	select {
	case s.sendQueue <- framed:
		return nil
	case <-s.done:
		return ErrSessionClosed
	}
}
```

Per-peer queues: replace `peerSendQueue chan bridgeOutbound` with

```go
peerQueuesMu sync.Mutex
peerQueues   map[string]chan []byte   // peerID -> bounded queue
peerWake     chan struct{}            // buffered(1): a queue got data
```

`enqueuePeerBridgeFrame` looks up or creates the peer's `make(chan []byte, defaultSendQueueSize)`, then blocks on `case q <- framed` / `case <-s.done`, and non-blockingly signals `peerWake`. `sendLoop` drains `sendQueue` first, then round-robins the peer queues (snapshot the map under the mutex, take at most one frame per peer per pass, loop while any frame was taken); it waits on `select { case <-s.done; case <-s.sendQueue (handle); case <-s.peerWake }` when nothing is pending. Remove a peer's queue when the peer leaves (where `peerDisconnected`/endpoint removal is handled today; if there is no such hook, keep the map bounded by deleting empty queues after a pass).

Keep `ErrSendQueueFull` declared (livekit still uses its own) but nothing in jitsi returns it any more.

- [ ] **Step 4: Run the package tests and the whole tree**

```bash
cd /root/olcrtc-fork && go test ./internal/engine/jitsi/ && go test ./... 2>&1 | tail -20
```
Expected: all green. If `internal/e2e` needs the reporter's JVB or network, run what is hermetic (`-short`) and say so.

- [ ] **Step 5: Commit and push**

```bash
git add internal/engine/jitsi/
git commit -F - <<'EOF'
fix(jitsi): back-pressure the bridge send queue instead of failing it

<diagnosis paragraph above, verbatim>

The queue is now 32 frames per destination and a send waits for room; a
close returns ErrSessionClosed to a waiting sender. Peers get their own
queues, drained round-robin, so a slow client no longer stalls the room.
No wire change: either side alone benefits from its own queue.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CEfgqJHWsowYtu4VT8PSw5
EOF
git push proofkit proofkit-udp-spike
```

- [ ] **Step 6: Roll out**

1. Server: run our `script/srv.sh` on the DE origin (it installs the fork branch tip and checks the container stays up). Tell the reporter the commit so he rebuilds his server too.
2. iOS cores: bump `OLCRTC_VERSION` in `scripts/cores-pins.sh` to the new pseudo-version (`v0.0.0-<yyyymmddhhmmss>-<sha12>`, derive with `go list -m -json github.com/openlibrecommunity/olcrtc@<sha>` against our fork's module path as the previous bumps did) and `CORES_BUILD` 11 → 12; dispatch `ios-frameworks.yml`; verify the run publishes `Cores-ios.zip` with the new tag.
3. Android/desktop build from the branch tip at release time (Task 11).

---

### Task 3: Shared `ImportLink` parser

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/ImportLink.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/ImportLinkTest.kt`

**Interfaces:**
- Produces: `object ImportLink { const val SCHEME = "proofkit"; const val WEB_ORIGIN = "https://proofkit.org"; const val WEB_PATH = "/add"; fun payloadOf(uri: String): String?; fun schemeLink(payload: String): String; fun webLink(payload: String): String }`.
- `payloadOf` returns the trimmed, percent-decoded payload for `proofkit://add?url=<enc>`, `proofkit://add#<enc>`, `https://proofkit.org/add#<enc>`, `https://proofkit.org/add?url=<enc>` (host compared case-insensitively, `www.` accepted); `null` for anything else, including an empty payload.

- [ ] **Step 1: Write the failing tests**

```kotlin
package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportLinkTest {
    private val list = "https://proofkit.org/sub/abc123?transport=auto"

    @Test fun theSchemeLinkCarriesTheListInTheQuery() {
        val link = ImportLink.schemeLink(list)
        assertEquals("proofkit://add?url=https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun theWebLinkKeepsTheListInTheFragmentSoNoServerSeesIt() {
        val link = ImportLink.webLink(list)
        assertEquals("https://proofkit.org/add#https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun otherShapesPeopleWillTypeStillParse() {
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("PROOFKIT://add#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
        assertEquals("happ://crypt5/xyz", ImportLink.payloadOf("https://www.proofkit.org/add?url=happ%3A%2F%2Fcrypt5%2Fxyz"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("  proofkit://add?url=olcrtc%3A%2F%2Fcrypt1%2Fabc \n"))
    }

    @Test fun anythingElseIsNotAnImportLink() {
        assertNull(ImportLink.payloadOf("https://proofkit.org/"))
        assertNull(ImportLink.payloadOf("https://example.org/add#x"))
        assertNull(ImportLink.payloadOf("proofkit://add"))
        assertNull(ImportLink.payloadOf("proofkit://add?url="))
        assertNull(ImportLink.payloadOf("proofkit://other?url=x"))
        assertNull(ImportLink.payloadOf(""))
    }
}
```

- [ ] **Step 2: Run, expect compile failure** (`ImportLink` unresolved):

```bash
cd /root/olcbox-fork && ./gradlew --no-daemon :sharedUI:jvmTest --tests 'org.olcbox.app.net.ImportLinkTest' 2>&1 | grep -E '^e: |BUILD' | head
```

- [ ] **Step 3: Implement** (`ImportLink.kt`; hand-rolled parsing, no `java.net.URI` in commonMain):

```kotlin
package org.olcbox.app.net

/**
 * The one-tap import link a panel or a bot hands to a person.
 *
 * Two spellings of one thing. `proofkit://add?url=…` opens the app directly
 * wherever the scheme is registered. `https://proofkit.org/add#…` is what a
 * Telegram button can carry; a phone with the app opens it in the app, a
 * phone without lands on a page with the downloads. The payload rides the
 * fragment there on purpose: a fragment never leaves the browser, so the
 * server list — a credential — is not in anybody's access log.
 */
object ImportLink {
    const val SCHEME = "proofkit"
    const val HOST = "add"
    const val WEB_ORIGIN = "https://proofkit.org"
    const val WEB_PATH = "/add"

    fun schemeLink(payload: String): String = "$SCHEME://$HOST?url=${encode(payload)}"
    fun webLink(payload: String): String = "$WEB_ORIGIN$WEB_PATH#${encode(payload)}"

    fun payloadOf(uri: String): String? {
        val text = uri.trim()
        val lower = text.lowercase()
        val rest = when {
            lower.startsWith("$SCHEME://$HOST") -> text.substring("$SCHEME://$HOST".length)
            else -> webRest(text, lower) ?: return null
        }
        if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '#' && rest[0] != '/') return null
        val encoded = when {
            rest.startsWith("#") -> rest.substring(1)
            else -> queryValue(rest.substringAfter('?', ""), "url") ?: return null
        }
        return decode(encoded).trim().takeIf { it.isNotEmpty() }
    }

    private fun webRest(text: String, lower: String): String? {
        for (origin in listOf("https://proofkit.org", "https://www.proofkit.org")) {
            val prefix = "$origin$WEB_PATH"
            if (lower.startsWith(prefix)) return text.substring(prefix.length)
        }
        return null
    }

    private fun queryValue(query: String, key: String): String? =
        query.substringBefore('#').split('&').firstNotNullOfOrNull { pair ->
            val k = pair.substringBefore('=')
            if (k == key) pair.substringAfter('=', "") else null
        }

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-._~") append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    private fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) {
                val hi = s[i + 1].digitToIntOrNull(16); val lo = s[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) { out.add(((hi shl 4) or lo).toByte()); i += 3; continue }
            }
            if (ch == '+') { out.add(' '.code.toByte()); i++; continue }
            for (b in ch.toString().encodeToByteArray()) out.add(b)
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
```

- [ ] **Step 4: Run the test class, expect green; then the whole jvmTest.**

- [ ] **Step 5: Commit** `feat(net): parse and build the one-tap import link`.

---

### Task 4: Shared entry point `onImportLink` on the view model

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/home/HomeScreenModel.kt` (next to `onImportFullConfig` at line 366)
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/ui/features/home/HomeScreenModelImportLinkTest.kt` (new; follow the fakes used by `HomeScreenStateTest`)

**Interfaces:**
- Produces: `fun onImportLink(uri: String, onComplete: () -> Unit = {}, onError: (String) -> Unit = {})` — `ImportLink.payloadOf(uri)` null → `onError("Not a ProofKit import link")`; else logs `import link` (scrubbed) and delegates to `onImportFullConfig(payload, onComplete, onError)`.

- [ ] **Step 1: Failing test:** a fake repository records `importText` calls; `onImportLink("proofkit://add?url=olcrtc%3A%2F%2Fcrypt1%2Fabc")` reaches the repository with `olcrtc://crypt1/abc`; `onImportLink("https://example.org")` calls `onError` and never touches the repository.
- [ ] **Step 2: Run, expect failure.**
- [ ] **Step 3: Implement** (delegation only; no new import logic).
- [ ] **Step 4: Run jvmTest, expect green.**
- [ ] **Step 5: Commit** `feat(home): an import link goes through the same import as a paste`.

---

### Task 5: Android — receive `proofkit://` and `https://proofkit.org/add`

**Files:**
- Modify: `androidApp/src/main/AndroidManifest.xml` (the `.AppActivity` element)
- Modify: `androidApp/src/main/kotlin/org/olcbox/app/AppActivity.kt` (`onCreate`, add `onNewIntent`)
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidMainScreen.kt` (expose the incoming-link handling next to `reloadLocationsAfterImport` at line 240)

**Interfaces:**
- Consumes: `HomeScreenViewModel.onImportLink`, `reloadLocationsAfterImport`.
- Produces: `AppActivity` forwards `intent.dataString` on create and on `onNewIntent` (launch mode is `singleInstance`, so a link while running arrives there) into a `MutableStateFlow<String?>` `pendingImportLink` that `AndroidMainScreen` collects: when non-null it calls `viewModel.onImportLink(link, onComplete = { reloadLocationsAfterImport(); toast("Server list added") }, onError = { toast(it) })` and clears it.

- [ ] **Step 1: Manifest** — inside `.AppActivity`, after the existing filters:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="proofkit" android:host="add" />
</intent-filter>
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="proofkit.org" android:pathPrefix="/add" />
    <data android:host="www.proofkit.org" />
</intent-filter>
```

- [ ] **Step 2: Activity** — keep the view model in a field (it is built in `onCreate`), add:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.dataString?.let { pendingImportLink.value = it }
}
```
and in `onCreate` after building the screen: `intent?.dataString?.let { pendingImportLink.value = it }`. `pendingImportLink` is a `MutableStateFlow<String?>` passed into `AndroidMainScreen`.

- [ ] **Step 3: Screen** — a `LaunchedEffect` collecting `pendingImportLink`; on a value: call `viewModel.onImportLink(...)` as in Interfaces, then `pendingImportLink.value = null`. Toasts via `Toast.makeText(context, …, LENGTH_SHORT)`.

- [ ] **Step 4: Verify** compile via CI (Task 1 makes a push to main run it) or push the branch; manual test on a phone: `adb shell am start -a android.intent.action.VIEW -d "proofkit://add?url=olcrtc%3A%2F%2Fcrypt1%2F…"` adds the list; the https form works after Task 9 deploys `assetlinks.json` (until then Android opens the browser page, which offers the scheme link).

- [ ] **Step 5: Commit** `feat(android): open proofkit:// and proofkit.org/add links in the app`.

---

### Task 6: iOS — URL scheme and universal link into the app

**Files:**
- Modify: `iosApp/iosApp/Info.plist` (add `CFBundleURLTypes`)
- Modify: `iosApp/iosApp/iosApp.entitlements` (add associated domains)
- Modify: `iosApp/iosApp/OlcboxIosApp.swift` (`WindowGroup` at line 30: add `.onOpenURL` and `.onContinueUserActivity`)
- Modify: `sharedUI/src/iosMain/kotlin/org/olcbox/app/ios/MainViewController.kt` (expose `fun handleIncomingLink(uri: String)` that reaches the same `dependencies.homeViewModel` and `reloadLocationsAfterImport` used at line 163)

**Interfaces:**
- Produces (Kotlin → Swift, gomobile-free, via the Kotlin framework): `MainViewControllerKt.handleIncomingLink(uri:)` — top-level `fun handleIncomingLink(uri: String)` in `MainViewController.kt` that stores the link if the screen is not built yet and otherwise imports at once.

- [ ] **Step 1: Info.plist**

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLName</key>
        <string>org.proofkit.app.import</string>
        <key>CFBundleURLSchemes</key>
        <array><string>proofkit</string></array>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
    </dict>
</array>
```

- [ ] **Step 2: Entitlements** (app target only, not the extension):

```xml
<key>com.apple.developer.associated-domains</key>
<array>
    <string>applinks:proofkit.org</string>
    <string>applinks:www.proofkit.org</string>
</array>
```
One-time portal step for the user: enable **Associated Domains** on the App ID `org.proofkit.app` (Certificates, Identifiers & Profiles → Identifiers). `scripts/asc-profiles.py` regenerates the App Store profiles on the next release run; without the capability the profile does not carry the entitlement and the archive fails signing — the run log says so.

- [ ] **Step 3: Swift** — on the `WindowGroup` content:

```swift
.onOpenURL { url in MainViewControllerKt.handleIncomingLink(uri: url.absoluteString) }
.onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
    if let url = activity.webpageURL { MainViewControllerKt.handleIncomingLink(uri: url.absoluteString) }
}
```
Typecheck on Linux with the existing harness pattern (`swiftc -typecheck`, shims for `MainViewControllerKt`).

- [ ] **Step 4: Kotlin** — in `MainViewController.kt`: a file-level `private var pendingLink: String? = null` and `private var linkSink: ((String) -> Unit)? = null`; `fun handleIncomingLink(uri: String) { linkSink?.invoke(uri) ?: run { pendingLink = uri } }`; inside `MainViewController(...)` once `dependencies` and `reloadLocationsAfterImport` exist: `linkSink = { uri -> dependencies.homeViewModel.onImportLink(uri, onComplete = { reloadLocationsAfterImport() }, onError = { dependencies.homeViewModel.addLog("import link: $it") }) }; pendingLink?.let { pendingLink = null; linkSink?.invoke(it) }`.

- [ ] **Step 5: Verify** — CI iOS build (`release.yml` `ios`) compiles the Kotlin framework and Swift; on a phone: Safari → `proofkit://add?url=…` opens the app and adds the list; after Task 9, `https://proofkit.org/add#…` from Notes opens the app directly.

- [ ] **Step 6: Commit** `feat(ios): open proofkit:// and proofkit.org/add links in the app`.

---

### Task 7: Desktop — macOS and Linux URI handlers; Windows documented

**Files:**
- Modify: `desktopApp/src/main/kotlin/main.kt` (`fun main(args)` at line 145; the composition where `reloadLocationsAfterImport` is defined at line 356)
- Modify: `desktopApp/build.gradle.kts` (macOS `infoPlist` next to `bundleID` at line 588; the Linux `.desktop` heredoc at line 652)

**Interfaces:**
- Consumes: `HomeScreenViewModel.onImportLink`, `ImportLink.payloadOf`.
- Produces: macOS receives the URL via `Desktop.getDesktop().setOpenURIHandler`; Linux receives it as `args` (`Exec=… %u`); both feed a `MutableStateFlow<String?>` consumed in the composition like Android.

- [ ] **Step 1: main.kt** — before `application {`: collect `args.firstOrNull { ImportLink.payloadOf(it) != null }` into the flow; inside the composition once: `if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) Desktop.getDesktop().setOpenURIHandler { e -> pendingImportLink.value = e.uri.toString() }`. A `LaunchedEffect` imports as on Android; the result goes to the app log (desktop has no toast; `addLog` lines show in the Logs sheet).
- [ ] **Step 2: macOS plist** — inside `macOS { … }`:

```kotlin
infoPlist {
    extraKeysRawXml = """
        <key>CFBundleURLTypes</key>
        <array><dict>
            <key>CFBundleURLName</key><string>org.olcbox.app.desktopApp.import</string>
            <key>CFBundleURLSchemes</key><array><string>proofkit</string></array>
        </dict></array>
    """.trimIndent()
}
```
- [ ] **Step 3: Linux .desktop** — `Exec=$desktopPackageName %u` and add `MimeType=x-scheme-handler/proofkit;`. AppImage integration registers it when the user installs the desktop entry; document in the release notes that a second instance imports and exits is NOT implemented: the running app is not contacted, so a link opens a new window (acceptable for now).
- [ ] **Step 4: Windows** — no registry work in this release; the `/add` page's "copy link" plus paste covers it. Note it in `docs/` (release notes).
- [ ] **Step 5: Verify** `./gradlew --no-daemon :sharedUI:jvmTest :desktopApp:compileKotlin`; on the Mac after the next DMG: `open "proofkit://add?url=…"` adds the list.
- [ ] **Step 6: Commit** `feat(desktop): open proofkit:// links on macOS and Linux`.

---

### Task 8: Android package `org.proofkit.app`

**Files:**
- Modify: `androidApp/build.gradle.kts:49` (`applicationId = "org.proofkit.app"`; `namespace` stays `org.olcbox.app`)
- Modify: `androidApp/src/debug/AndroidManifest.xml`, `androidApp/src/debug/kotlin/org/olcbox/app/DebugVpnControlReceiver.kt`, `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnActions.kt`, `sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/QrScannerActivity.kt` — rename the action/extra string constants from `org.olcbox.app.*` to `org.proofkit.app.*` (class names such as `org.olcbox.app.vpn.service.OlcboxVpnService` stay: they are Kotlin package paths, not the application id)
- Modify: `docs/testflight-notes.md` is iOS-only; add the Android note to the release notes generated by `release.yml` (the "What's new" body) — a paragraph in `.github/workflows/release.yml` notes template, or a `docs/release-notes/1.0.4xx.md` referenced from it.

- [ ] **Step 1: Change the id and the constants** (grep: `grep -rn '"org\.olcbox\.app\.' sharedUI/src/androidMain androidApp/src --include=*.kt --include=*.xml` must show only class names afterwards).
- [ ] **Step 2: Release-note text** (English, no "subscription"):

> Android: ProofKit now installs as `org.proofkit.app`, the same identifier the iOS app uses, so it no longer collides with other apps built from olcbox. This build installs next to the old one; your server lists do not carry over. Open the old app once, share or copy each list link, add them in the new app (or tap the link from your bot or panel), then uninstall the old app.

- [ ] **Step 3: Verify** in CI: `assembleDebug` green; `aapt dump badging` on the release APK shows `package: name='org.proofkit.app'` (the release job can print it).
- [ ] **Step 4: Commit** `feat(android)!: the app is org.proofkit.app`.

---

### Task 9: proofkit-dvpn — `/add` page, `.well-known` files, nginx

**Files (repo `/opt/proofkit/proofkit-dvpn`):**
- Create: `frontend/add/index.html`
- Create: `frontend/.well-known/apple-app-site-association`, `frontend/.well-known/assetlinks.json`
- Modify: `deploy/nginx/proofkit.conf` (a `location = /.well-known/apple-app-site-association` block before `location /` at line 75)
- Test: `frontend/tests/add-page.test.js` if the frontend has a test harness (see `feedback_spa_local_verify`); otherwise verify with the checklist in Step 5.

**Interfaces:**
- Produces: `GET /add` → static page; `GET /.well-known/apple-app-site-association` → JSON, no redirect, `Content-Type: application/json`; `GET /.well-known/assetlinks.json` → JSON.

- [ ] **Step 1: AASA**

```json
{"applinks":{"details":[{"appIDs":["3QJG3J7L66.org.proofkit.app"],"components":[{"/":"/add","comment":"one-tap import"},{"/":"/add/*"}]}]}}
```

- [ ] **Step 2: assetlinks.json**

```json
[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"org.proofkit.app","sha256_cert_fingerprints":["59:DC:54:21:81:20:4C:88:40:2D:6C:71:EA:47:4B:8B:F0:9A:CA:B8:64:C9:00:DC:28:32:E6:FF:62:FC:3E:83"]}}]
```

- [ ] **Step 3: nginx** — in the `proofkit.org` server block:

```nginx
location = /.well-known/apple-app-site-association {
    root /opt/proofkit/frontend;
    default_type application/json;
    add_header Cache-Control "max-age=3600";
}
```
(`assetlinks.json` has an extension and is served by `location /` as JSON already.)

- [ ] **Step 4: Page** — `frontend/add/index.html`, self-contained, `<meta name="robots" content="noindex">`, the site's dark tokens inline. Script: `const p = decodeURIComponent(location.hash.slice(1) || new URLSearchParams(location.search).get('url') || '')`; if empty show "This link carries nothing to add"; else: primary button "Open in ProofKit" → `location.href = 'proofkit://add?url=' + encodeURIComponent(p)`; secondary "Copy link" (`navigator.clipboard.writeText(p)`); download row: App Store (when announced; until then TestFlight public link if the user has one), Android APK (`https://github.com/ghostlane-project/ghostlane/releases/latest`), macOS/Windows/Linux (same releases page). No fetch, no analytics on this page, the payload never leaves the browser.

- [ ] **Step 5: Verify after CI deploys** (push to `main` deploys both APPs):

```bash
curl -sI https://proofkit.org/.well-known/apple-app-site-association | grep -i "content-type\|HTTP/"
curl -s https://proofkit.org/.well-known/assetlinks.json | python3 -m json.tool | head -5
curl -s https://proofkit.org/add | grep -c "Open in ProofKit"
```
Expected: `200`, `application/json`, the page contains the button. Apple's CDN fetches the AASA within a day; Android verifies on install.

- [ ] **Step 6: Commit** (proofkit-dvpn) `feat(site): one-tap import page and app-link verification files`.

---

### Task 10: Telegram bot — "Open in ProofKit" button

**Files (repo `/opt/proofkit/proofkit-dvpn`):**
- Modify: `telegram-bot/src/handlers.rs` (the two places `sub_url` is built and sent, lines ~512-535 and ~630-640; reuse the URL-button helper in `telegram.rs:221`)
- Create/modify: a `pub fn proofkit_add_link(list_url: &str) -> String` in `handlers.rs` (or a tiny `links.rs`), with a unit test.

- [ ] **Step 1: Failing test** (Rust):

```rust
#[test]
fn add_link_keeps_the_list_in_the_fragment() {
    let l = proofkit_add_link("https://proofkit.org/sub/abc?transport=auto");
    assert_eq!(l, "https://proofkit.org/add#https%3A%2F%2Fproofkit.org%2Fsub%2Fabc%3Ftransport%3Dauto");
}
```
- [ ] **Step 2:** `cargo test -p telegram-bot add_link` → fails (function missing).
- [ ] **Step 3: Implement** with `percent_encoding::utf8_percent_encode(list_url, NON_ALPHANUMERIC)` minus `-._~` (or the crate already in the tree; check `Cargo.toml`), and attach an inline keyboard button `{"text": "Open in ProofKit", "url": link}` to both messages that contain `sub_url`.
- [ ] **Step 4:** `cargo test -p telegram-bot` and `cargo clippy -p telegram-bot --tests -- -D warnings` green.
- [ ] **Step 5: Commit** `feat(bot): one-tap "Open in ProofKit" button under the server-list link`. CI deploys the bot to DATA on push to main.

---

### Task 11: Release 1.0.4xx and notes

- [ ] **Step 1:** `docs/testflight-notes.md`: replace the "What to Test" body with the import link (open `proofkit://add?url=…` from Notes; tap the bot's button) on top of the routing items; keep under 4000 chars, no "subscription".
- [ ] **Step 2:** Dispatch `release.yml` with `all` from `main`; watch; confirm the GitHub release has only installers + `SHA256SUMS.txt`, TestFlight has the build, `aapt`/badging shows `org.proofkit.app`.
- [ ] **Step 3:** After the 1.0.393 App Store verdict, create the next App Store version with this build; "What's New" text from the session, plus one line: "Links from your provider's bot or panel now open straight in the app."
- [ ] **Step 4:** Comment on #19 (link + package shipped, engine port scheduled) and on #15 (queue fix, commit id, "rebuild your server from `proofkit-udp-spike` at or after <sha>").
- [ ] **Step 5:** Memory: update `project_bypass_russia_routing` (release), add `project_import_link_and_package_rename`, update `project_olcrtc_jitsi_throughput_cap` with the queue fix.

---

## Self-review notes

- Spec coverage: #19 items 1 (engine, scheduled — out of scope here except the #15 fix that ships on the same branch), 2 (answered in the issue), 3 (Tasks 3–7, 9, 10), 4 (Task 8). #15: Task 2. Main CI gap: Task 1.
- Placeholders: Task 2 Step 1 depends on an existing test helper; if absent the step says what to build. Task 9 tests depend on the frontend harness; the curl checklist is the fallback.
- Type consistency: `ImportLink.payloadOf(String): String?` used in Tasks 4, 5, 7; `HomeScreenViewModel.onImportLink(uri, onComplete, onError)` used in Tasks 5, 6, 7; `handleIncomingLink(uri:)` in Task 6 only.
