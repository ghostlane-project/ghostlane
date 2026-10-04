# Kill switch and failover, stage B: Android holds the tunnel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Android, from the first verified connection until the user disconnects, nothing closes the VPN
interface, and the app reports whether Android's own kill switch is on.

**Architecture:** `reconnectTransport`, which already restarts a transport behind an interface that stays up,
becomes the only path inside a session; `startFullTunnel` keeps the first start and proxy mode. The decisions are
a pure unit (`TunnelSession`) with tests; the service executes them. The interface is replaced only by Android's
documented seamless handover, and only when what it was built with changed or tun2socks will not let go of it.

**Tech Stack:** Kotlin Multiplatform (`sharedUI`: `jvmAndroidMain`, `androidMain`, `commonMain` resources),
Android `VpnService` (minSdk 23; `isAlwaysOn`/`isLockdownEnabled` from API 29), Compose Multiplatform resources in
four languages (`values`, `values-ru`, `values-zh`, `values-fa`).

**Spec:** `docs/superpowers/specs/2026-10-04-kill-switch-failover-design.md` (A1, A2, A4, A5). Builds on stage A
(`TunnelBridge`, `SessionPort`, `ensureBridge`).

## Global Constraints

- English in code and comments; a comment says why (CONTRIBUTING.md). One subject per pull request.
- "No user-visible state that does not work": the kill-switch row states only what a running service read;
  below API 29 and with the service stopped it reads as it does today.
- Compose resource strings: no `\'`, positional arguments as `%1$s`, the same key in all four languages.
- While an interface is held, the status is `Connected` or `Reconnecting`, never `Error` or `Disconnected`.
- The interface closes only on: Disconnect (`ACTION_STOP_VPN`), `onRevoke`, service destruction, a switch to
  proxy mode, and a first connect of the user's that failed.
- No Gradle on the planning box; PR Checks verify (`jvmTest`, `androidApp:assembleDebug`, Apple Kotlin compile).

## Review Focus

- tun2socks that will not stop: nothing may start a second one beside it; the next attempt replaces the
  interface, which ends it, instead of looping at once.
- A user picks another location while the phone has no network: the interface stays, the status is
  `Reconnecting`, and the network callback resumes the start.
- The active location is deleted while connected: the interface stays, nothing is retried, the notification
  says to add a location.
- Split tunnelling set to "proxy selected" with an empty list while connected: the handover is refused by
  `applySplitTunneling`; the old interface stays and the status does not become `Error`.
- A start by Android (always-on) while the server is down: it retries like a session instead of ending in
  `Error`.

---

### Task 1: the session holds the tunnel (branch `feat/android-session-holds-tunnel`)

**Files:**
- Create: `sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/vpn/TunnelSession.kt`
- Create: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/TunnelSessionTest.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt` (`onStartCommand`,
  `startTunnel`, `reconnectTransport`, `startFullTunnel`, `establishSystemVpnTunnel`, `ensureBridge`,
  `cleanupVpnInterface`, `NOTIFICATION_TEXTS`)
- Modify: `sharedUI/src/commonMain/composeResources/values{,-ru,-zh,-fa}/strings.xml` (`notification_holding`)
- Modify: `README.MD` (Features), `docs/release-notes/pending.md`, `docs/roadmap-2026-08-14.md` (item 2)

**Interfaces:**
- Produces:
  `data class TunSpec(val splitMode: String, val proxyApps: Set<String>, val bypassApps: Set<String>)`;
  `TunnelSession.runsBehindTunnel(tunMode, interfaceHeld, isMigration, isRestart): Boolean`;
  `TunnelSession.needsHandover(held: TunSpec?, wanted: TunSpec): Boolean`;
  `TunnelSession.mayFailOpen(behindTunnel, isMigration, startedBySystem): Boolean`.

- [ ] **Step 1: the failing test** (`TunnelSessionTest.kt`)

```kotlin
package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rule the service executes: inside a session nothing closes the interface.
class TunnelSessionTest {
    private val all = TunSpec("all", emptySet(), emptySet())

    @Test fun everyRestartInsideATunSessionRunsBehindTheInterface() {
        for ((migration, restart) in listOf(true to false, false to true, true to true)) {
            assertTrue(TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = true, migration, restart))
        }
    }

    @Test fun aFirstStartAProxySessionAndAStartWithNoInterfaceDoNot() {
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = true, isMigration = false, isRestart = false))
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = false, interfaceHeld = true, isMigration = true, isRestart = true))
        assertFalse(TunnelSession.runsBehindTunnel(tunMode = true, interfaceHeld = false, isMigration = true, isRestart = true))
    }

    @Test fun theInterfaceIsReplacedOnlyWhenWhatItWasBuiltWithChanged() {
        assertFalse(TunnelSession.needsHandover(held = all, wanted = all))
        assertFalse(TunnelSession.needsHandover(held = null, wanted = all))
        assertTrue(TunnelSession.needsHandover(all, all.copy(splitMode = "bypass", bypassApps = setOf("a.b"))))
        assertTrue(TunnelSession.needsHandover(all.copy(bypassApps = setOf("a.b")), all.copy(bypassApps = setOf("a.c"))))
    }

    @Test fun onlyAFirstConnectOfTheUsersMayEndInAnErrorWithTheInterfaceClosed() {
        assertTrue(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = false, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = true, isMigration = false, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = true, startedBySystem = false))
        assertFalse(TunnelSession.mayFailOpen(behindTunnel = false, isMigration = false, startedBySystem = true))
    }
}
```

- [ ] **Step 2: the pure unit** (`TunnelSession.kt`): the three functions as one-line rules, each with a KDoc
  that says why (A1, A2, A4 of the spec).

- [ ] **Step 3: the service.**
  - Fields `private var tunSpec: TunSpec? = null`, `private var startedBySystem = false`,
    `private var handoverPending = false`. `currentTunSpec()` from `splitTunnelMode.value` and the two app sets.
    `tunSpec` is set wherever `vpnInterface` is assigned and cleared in `cleanupVpnInterface()`.
  - `onStartCommand`: `startedBySystem = startIntent.action == SERVICE_INTERFACE`.
  - `startTunnel`: `val behind = TunnelSession.runsBehindTunnel(connectionMode == Tun, vpnInterface != null,
    isMigration, isRestart)`. A missing location with `behind` keeps the interface: `Reconnecting`, notification
    "Add a location first", `stopMobileAndWait()`, no retry. With `behind`, call
    `reconnectTransport(location, gen, restartBridge = forceFullRestart, replaceInterface = handoverPending ||
    (isRestart && TunnelSession.needsHandover(tunSpec, currentTunSpec())))`. Otherwise today's choice between
    `reconnectTransport` (proxy mode in place) and `startFullTunnel`.
  - `reconnectTransport(location, gen, restartBridge = false, replaceInterface = false)`: after
    `stopMobileAndWait()`, `if (restartBridge || replaceInterface) stopBridge()`; `if (replaceInterface)
    handOverInterface()`. The notification in tun mode is "Reconnecting, VPN apps wait" (`notification_holding`).
    When `ensureBridge()` fails: `handoverPending = true` and `scheduleTransportRetry` (with its backoff), not an
    immediate recovery.
  - `stopBridge(): Boolean` (stop, wait `TUN2SOCKS_RESTART_WAIT_MS`, forget the thread); `ensureBridge` uses it.
  - `handOverInterface(): Boolean`: `establishSystemVpnTunnel()` with the old descriptor open; on null keep the
    old one and put the status back to `Reconnecting`; on success assign, record `tunSpec`, clear
    `handoverPending`, close the old descriptor.
  - `startFullTunnel`: `val retries = isMigration || startedBySystem` replaces `isMigration` in its three
    failure branches (no upstream, transport start, tunnel check); `setErrorOnFailure =
    TunnelSession.mayFailOpen(false, isMigration, startedBySystem)`.

- [ ] **Step 4: strings and docs.** `notification_holding` in four languages and in `NOTIFICATION_TEXTS`.
  README Features: "While a session is up the tunnel is held: a reconnect, a change of server and a lost
  network leave no moment when traffic goes around it." Roadmap item 2: Android's half done, with what is left.

- [ ] **Step 5: commit and push**; PR Checks green. Device pass (owner): with an address-check page open, kill
  the transport, switch location, edit split tunnelling while connected; the home address never appears.

### Task 2: Android's kill switch, as the system reports it (branch `feat/android-system-kill-switch-state`)

**Files:**
- Create: `sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/vpn/SystemVpnMode.kt`
- Create: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/SystemVpnModeTest.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnState.kt`, `OlcboxVpnService.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/ui/activities/AndroidAppSettingsSheets.kt` (:601-610)
- Modify: the four `strings.xml`; `README.MD`; `docs/release-notes/pending.md`

**Interfaces:**
- Produces: `data class SystemVpnMode(val alwaysOn: Boolean, val lockdown: Boolean)`;
  `enum class SystemKillSwitch { Unknown, Off, On, Blocking }`; `SystemVpnMode?.killSwitch(): SystemKillSwitch`;
  `enum class LockdownNote { None, BypassedAppsOffline, UnselectedAppsOffline }`;
  `lockdownNote(mode: SystemVpnMode?, proxySelected: Boolean, bypassedApps: Int): LockdownNote`;
  `OlcboxVpnState.systemVpnMode: StateFlow<SystemVpnMode?>`.

- [ ] **Step 1: the failing test** (`SystemVpnModeTest.kt`)

```kotlin
package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemVpnModeTest {
    @Test fun theRowSaysOnlyWhatARunningServiceRead() {
        assertEquals(SystemKillSwitch.Unknown, (null as SystemVpnMode?).killSwitch())
        assertEquals(SystemKillSwitch.Off, SystemVpnMode(alwaysOn = false, lockdown = false).killSwitch())
        assertEquals(SystemKillSwitch.On, SystemVpnMode(alwaysOn = true, lockdown = false).killSwitch())
        assertEquals(SystemKillSwitch.Blocking, SystemVpnMode(alwaysOn = true, lockdown = true).killSwitch())
    }

    @Test fun underLockdownTheAppsOutsideTheVpnHaveNoNetwork() {
        val blocking = SystemVpnMode(alwaysOn = true, lockdown = true)
        assertEquals(LockdownNote.BypassedAppsOffline, lockdownNote(blocking, proxySelected = false, bypassedApps = 2))
        assertEquals(LockdownNote.UnselectedAppsOffline, lockdownNote(blocking, proxySelected = true, bypassedApps = 0))
        assertEquals(LockdownNote.None, lockdownNote(blocking, proxySelected = false, bypassedApps = 0))
        assertEquals(LockdownNote.None, lockdownNote(SystemVpnMode(true, false), proxySelected = true, bypassedApps = 3))
        assertEquals(LockdownNote.None, lockdownNote(null, proxySelected = true, bypassedApps = 3))
    }
}
```

- [ ] **Step 2: the pure unit** (`SystemVpnMode.kt`).

- [ ] **Step 3: the service and the state.** `OlcboxVpnState.systemVpnMode` with `setSystemVpnMode`. The
  service publishes `SystemVpnMode(isAlwaysOn, isLockdownEnabled)` on API 29+ (null below) in `onStartCommand`
  and in `setStatus`, and null when it ends (`Disconnected`, `onDestroy`).

- [ ] **Step 4: the row.** `ConnectionSettingsContent` collects the flow; the row's value is one of
  `always_on_vpn_value` (Unknown), `always_on_vpn_value_off`, `always_on_vpn_value_on`,
  `always_on_vpn_value_blocking`; under it, when `lockdownNote` is not `None`, one line of
  `lockdown_bypass_note` or `lockdown_unselected_note`. All five keys in four languages.

- [ ] **Step 5: docs, commit, push**; PR Checks green. Device pass (owner): the row with always-on off, on,
  and on with "Block connections without VPN"; the note with an app excluded.
