# Kill switch and failover, stage A: the fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four defects fixed before anything is built on them: Android's tun2socks loses the core after a
reconnect in place, a server named by hostname does not resolve under Global routing on Android, a failed tunnel
check says nothing about why, and a link's `type=http`, `security=none` and `allowInsecure` are not read.

**Architecture:** Every rule that can be pure is a small pure unit with a test (`TunnelBridge`, `SessionPort`, the
parser and the config builders); `OlcboxVpnService` only executes them. The config builders stay byte for byte
what they are for every input they already handled, so the reference dumps do not move.

**Tech Stack:** Kotlin Multiplatform (`sharedUI`: `commonMain`, `jvmAndroidMain`, `androidMain`), kotlinx.serialization
JSON, sing-box 1.13.14 (pinned), Go 1.26.5 for `cli/`.

**Spec:** `docs/superpowers/specs/2026-10-04-kill-switch-failover-design.md` (decision A3, architecture 2 and 7;
the parser cases come from ghostlane#82).

## Global Constraints

- English in code and comments; a comment says why, not what (CONTRIBUTING.md).
- One subject per pull request; a behaviour change comes with a test that fails without it.
- A change to the Kotlin link parser lands with the same case in `cli/internal/links`.
- `SingBoxConfig` output is unchanged for every input it handled before: `SingBoxConfigDumpTest` compares with
  `src/jvmTest/resources/reference-configs/`.
- No Gradle on the box this was planned on (a production data host, disk at 96 %). Kotlin is verified by PR
  Checks on push: `jvmTest`, desktop `compileKotlin`, `androidApp:assembleDebug`, `sing-box check` of the dumps,
  Apple Kotlin compile. The Go CLI is verified locally: `cd cli && GOTOOLCHAIN=go1.26.5 go test ./internal/links/
  ./internal/engine/...`.
- Commits are conventional, imperative, English; the body says why.

## Review Focus

- A core restarted on the session's port while the old process still holds it: the bind fails, the start fails
  and the next one must draw another port (`SessionPort.release`), not loop on the taken one.
- tun2socks that does not stop when asked: a second hev must never be started beside it (hev is one instance
  per process); the reconnect fails and is retried instead.
- A hostname server under rule-based routing: the rule-based shape already resolves it; `serverResolver` must
  not add a second `dns` section there.
- `allowInsecure=1` on a Reality link: ignored, Reality has no certificate to waive.
- `alpn=h2,http/1.1` on a VLESS WebSocket link: still ignored, or the handshake negotiates h2 and the
  upgrade fails.
- A stored row whose link says `type=h2` with an empty `host`: `host` is left out of the transport, not written
  as an empty list.

---

### Task 1: tun2socks follows the core (branch `fix/android-tun2socks-follows-core`)

**Files:**
- Create: `sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/vpn/TunnelBridge.kt`
- Create: `sharedUI/src/jvmTest/kotlin/org/olcbox/app/vpn/TunnelBridgeTest.kt`
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt` (`reconnectTransport`
  :548, `startCore` :767, `startTun2socks` :1034, `waitForTun2socksStopped` :1315, `cleanupVpnInterface` :1644)
- Modify: `README.MD` (Features), `docs/release-notes/pending.md`

**Interfaces:**
- Produces: `data class BridgeTarget(address: String, port: Int, username: String, password: String)`;
  `TunnelBridge.needsRestart(running: BridgeTarget?, alive: Boolean, wanted: BridgeTarget): Boolean`;
  `class SessionPort(draw: () -> Int) { fun acquire(): Int; fun release() }`. Stage B folds these into
  `TunnelSessionPolicy`.

- [ ] **Step 1: the failing test** (`TunnelBridgeTest.kt`)

```kotlin
package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// hev reads where its SOCKS server is once, at start. A core that comes back on
// another port is one hev no longer reaches, while the tunnel check, which asks
// the core's port directly, still passes: connected, and nothing loads.
class TunnelBridgeTest {
    private val core = BridgeTarget("127.0.0.1", 40001, "user", "pass")

    @Test fun aLiveBridgeOnTheSameTargetIsLeftAlone() {
        assertFalse(TunnelBridge.needsRestart(running = core, alive = true, wanted = core))
    }

    @Test fun anotherPortRestartsIt() {
        assertTrue(TunnelBridge.needsRestart(core, alive = true, wanted = core.copy(port = 40002)))
    }

    @Test fun anotherLoginRestartsIt() {
        assertTrue(TunnelBridge.needsRestart(core, alive = true, wanted = core.copy(password = "other")))
    }

    @Test fun aDeadOrNeverStartedBridgeRestarts() {
        assertTrue(TunnelBridge.needsRestart(core, alive = false, wanted = core))
        assertTrue(TunnelBridge.needsRestart(null, alive = false, wanted = core))
    }

    @Test fun theSessionKeepsItsPortUntilItIsReleased() {
        val drawn = ArrayDeque(listOf(40001, 40002))
        val port = SessionPort { drawn.removeFirst() }
        assertEquals(40001, port.acquire())
        assertEquals(40001, port.acquire())
        port.release()
        assertEquals(40002, port.acquire())
    }
}
```

- [ ] **Step 2: the pure unit** (`TunnelBridge.kt`): the data class, `needsRestart = !alive || running != wanted`,
  and `SessionPort` holding one nullable port.

- [ ] **Step 3: the service.** In `OlcboxVpnService`:
  - fields `private var bridgeTarget: BridgeTarget? = null` and
    `private val sessionCorePort = SessionPort(::freeLoopbackPort)`;
  - `startCore`: `val port = if (tun) sessionCorePort.acquire() else socksListenPort`; in its `catch (e: Exception)`
    `if (tun) sessionCorePort.release()`;
  - `cleanupVpnInterface()` also calls `sessionCorePort.release()` and clears `bridgeTarget`: a new interface is
    a new session and a new port;
  - `private fun currentBridgeTarget()` builds the target from `socksConnectHost()`,
    `activeCorePort ?: socksListenPort` and `upstreamLogin()`; `startTun2socks` records it in `bridgeTarget`
    after `writeTun2socksConfig()`;
  - `waitForTun2socksStopped(thread, timeoutMs = TUN2SOCKS_STOP_WAIT_MS): Boolean` returns whether it stopped;
  - `private suspend fun ensureBridge(): Boolean`: true at once outside tun mode or when
    `!TunnelBridge.needsRestart(bridgeTarget, tun2socksThread?.isAlive == true, currentBridgeTarget())`;
    otherwise `stopTun2socks()`, wait up to `TUN2SOCKS_RESTART_WAIT_MS = 5_000L`, and only when the old thread
    is gone `startTun2socks(vpnInterface)`; false when it did not stop or there is no interface;
  - `reconnectTransport`: after `startTransport(...)` succeeds and before `verifyTunnel()`, `if (!ensureBridge())`
    → log, `setStatus(VpnStatus.Reconnecting)`, `scheduleTransportRetry(requestedGeneration, "tun2socks restart failed")`,
    return.

- [ ] **Step 4: docs.** README Features: the line "On Android the port also changes with every connect" stays
  true (a connect is a session). `pending.md`: "Android: after a move between Wi-Fi and mobile data a Reality,
  Hysteria2, Trojan, VMess or XHTTP connection said Connected and loaded nothing (since 1.0.443)."

- [ ] **Step 5: commit and push**; PR Checks green.

### Task 2: a hostname server resolves under Global routing; a failed check says why
(branch `fix/android-hostname-resolver`)

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt` (`build` :49, `render` :508)
- Modify: `sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/net/TransportProbe.kt` (`coreConfig`, `passes`)
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/AndroidVpnManager.kt` (`probeTransport` :320)
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt` (`startTransport`
  :739, `startCore` :767, `failUnverifiedTunnel` :714)
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`,
  `sharedUI/src/jvmTest/kotlin/org/olcbox/app/net/SingBoxConfigDumpTest.kt`
- Modify: `docs/release-notes/pending.md`

**Interfaces:**
- Produces: `SingBoxConfig.build(…, serverResolver: DirectDns? = null)`;
  `TransportProbe.coreConfig(spec, port, login, serverResolver: DirectDns? = null)`;
  `TransportProbe.passes(location, serverResolver: DirectDns? = null, starter)`.

- [ ] **Step 1: the failing tests** (`SingBoxConfigTest.kt`)

```kotlin
    // Android has no /etc/resolv.conf, and sing-box's `local` resolver reads nothing else:
    // without a resolver of its own the core never reaches a server named by hostname.
    @Test fun aServerNamedByHostnameGetsTheResolverItIsGiven() {
        val named = vless().copy(host = "vpn.example.com")
        val root = Json.parseToJsonElement(
            SingBoxConfig.build(named, serverResolver = DirectDns.Servers(listOf("192.168.1.1")))
        ).jsonObject
        val server = root["dns"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject
        assertEquals("dns-direct", server["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", server["type"]!!.jsonPrimitive.content)
        assertEquals("192.168.1.1", server["server"]!!.jsonPrimitive.content)
        assertEquals("dns-direct", root["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content)
    }

    @Test fun anAddressOrNoResolverLeavesTheGlobalShapeAsItWas() {
        val resolver = DirectDns.Servers(listOf("192.168.1.1"))
        assertEquals(SingBoxConfig.build(vless()), SingBoxConfig.build(vless(), serverResolver = resolver))
        val named = Json.parseToJsonElement(SingBoxConfig.build(vless().copy(host = "vpn.example.com"))).jsonObject
        assertNull(named["dns"]); assertNull(named["route"])
    }
```

  and in `SingBoxConfigDumpTest.kt`, so CI's `sing-box check` reads the new shape:

```kotlin
    @Test fun dumpVlessNamedByHostnameWithAResolver() {
        val spec = LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@vpn.example.com:443" +
                "?security=reality&pbk=$REALITY_PBK&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#N"
        )
        assertNotNull(spec)
        dump("vless-hostname-resolver", SingBoxConfig.build(spec, serverResolver = DirectDns.Servers(listOf("192.168.1.1"))))
    }
```

  (`REALITY_PBK`: the valid test key the file's other Reality dumps use.)

- [ ] **Step 2: the builder.** `build` passes `serverResolver.takeIf { !XrayConfig.isIpLiteral(outbound.host) }`
  to `render`; `render` writes, only when there is no bypass and the resolver is not null,
  `dns: { servers: [ addDirectDnsServer(resolver) ] }` before the inbounds and
  `route: { default_domain_resolver: "dns-direct" }` after the outbounds.

- [ ] **Step 3: the callers.** `TransportProbe.coreConfig`/`passes` take and pass the resolver.
  `OlcboxVpnService.startTransport` hands `upstream` to `startCore`, which passes
  `serverResolver = DirectDns.Servers(upstreamDnsAddresses(upstream))`. `AndroidVpnManager.probeTransport`
  passes `DirectDns.Servers(...)` of the active network's `LinkProperties.dnsServers`.

- [ ] **Step 4: the log.** `failUnverifiedTunnel`: after its first line,
  `if (activeCorePort != null) addLog(activeCoreDiagnostics())`, before anything stops the core.

- [ ] **Step 5: docs, commit, push**; PR Checks green, including `sing-box check` of
  `vless-hostname-resolver.json`.

### Task 3: `type=http`, `security=none` and `allowInsecure` (branch `fix/links-http-and-vless-security`)

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/OutboundSpec.kt`, `LinkParser.kt`, `SingBoxConfig.kt`,
  `XrayConfig.kt`, `RouterExport.kt`, `TransportSelector.kt`
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt` (:708),
  `ui/components/RouterExportSheet.kt` (:104-109, :144), `ui/components/kit/PkBoardModel.kt` (:31)
- Modify: `cli/internal/links/share.go`, `cli/internal/engine/singbox/config.go`, and the xray builder if it
  writes `security` (`cli/internal/engine/xray/`)
- Test: `LinkParserTest.kt`, `SingBoxConfigTest.kt`, `SingBoxConfigDumpTest.kt`, `cli/internal/links/share_test.go`,
  `cli/internal/engine/singbox/config_test.go`

**Interfaces:**
- Produces: `TransportSpec.Http(path: String, hosts: List<String>)`; `OutboundSpec.Vless` gains, last and
  defaulted, `plain: Boolean = false` and `insecure: Boolean = false`; `TransportKind.Plain` (label "No TLS");
  `RouterExport.xrayOutbound(spec): String?` (null for the HTTP transport, which current Xray no longer has);
  Go `VlessLine.Plain` and `.Insecure`.
- `alpn` stays unread for VLESS: a WebSocket link that lists `h2` first works today only because it is
  ignored, and reading it would break those lines.

- [ ] **Step 1: the failing tests.** `LinkParserTest.kt`:

```kotlin
    @Test fun typeHttpIsSingBoxsHttpTransportNotPlainTcp() {
        val s = LinkParser.parse("vless://u@1.2.3.4:443?type=http&host=a.example%2Cb.example&path=%2Fh2&security=reality&pbk=PBK&sid=ab#H")
        assertIs<OutboundSpec.Vless>(s)
        assertEquals(TransportSpec.Http("/h2", listOf("a.example", "b.example")), s.transport)
        assertNull(s.flow)
        val h2 = LinkParser.parse("vless://u@1.2.3.4:443?type=h2&security=reality&pbk=PBK#H") as OutboundSpec.Vless
        assertEquals(TransportSpec.Http("/", emptyList()), h2.transport)
    }

    @Test fun securityNoneIsNoTlsAtAll() {
        val s = LinkParser.parse("vless://u@1.2.3.4:80?type=ws&path=%2Fws&security=none#P") as OutboundSpec.Vless
        assertTrue(s.plain)
        assertEquals(TransportKind.Plain, org.olcbox.app.data.model.LocationConfig(
            name = "P", id = "1.2.3.4:80", key = "", kind = LocationKind.Vless, rawLink = "vless://u@1.2.3.4:80?type=ws&security=none#P"
        ).transportKind())
    }

    @Test fun allowInsecureIsReadOnOrdinaryTlsAndIgnoredUnderReality() {
        val tls = LinkParser.parse("vless://u@1.2.3.4:443?type=tcp&security=tls&allowInsecure=1&sni=x.example#T") as OutboundSpec.Vless
        assertTrue(tls.insecure); assertFalse(tls.plain)
        val reality = LinkParser.parse("vless://u@1.2.3.4:443?security=none&pbk=PBK&allowInsecure=1#R") as OutboundSpec.Vless
        assertFalse(reality.insecure); assertFalse(reality.plain)
    }

    @Test fun aLinkWithoutSecurityReadsAsItAlwaysDid() {
        val s = LinkParser.parse("vless://u@1.2.3.4:443?type=tcp&sni=x.example#T") as OutboundSpec.Vless
        assertFalse(s.plain); assertFalse(s.insecure)
    }
```

  `SingBoxConfigTest.kt`:

```kotlin
    @Test fun theHttpTransportCarriesItsHostsAndPathAndLeavesAnEmptyHostOut() {
        val spec = vless().copy(flow = null, transport = TransportSpec.Http("/h2", listOf("a.example")))
        val t = outbound(SingBoxConfig.build(spec))["transport"]!!.jsonObject
        assertEquals("http", t["type"]!!.jsonPrimitive.content)
        assertEquals("/h2", t["path"]!!.jsonPrimitive.content)
        assertEquals("a.example", t["host"]!!.jsonArray.single().jsonPrimitive.content)
        val bare = vless().copy(flow = null, transport = TransportSpec.Http("/", emptyList()))
        assertNull(outbound(SingBoxConfig.build(bare))["transport"]!!.jsonObject["host"])
    }

    @Test fun aPlainVlessHasNoTlsBlockAndOrdinaryTlsCanWaiveItsCertificate() {
        val plain = vless().copy(publicKey = "", flow = null, plain = true)
        assertNull(outbound(SingBoxConfig.build(plain))["tls"])
        val waived = vless().copy(publicKey = "", insecure = true)
        assertEquals("true", outbound(SingBoxConfig.build(waived))["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content)
        assertNull(outbound(SingBoxConfig.build(vless().copy(insecure = true)))["tls"]!!.jsonObject["insecure"])
    }
```

  `SingBoxConfigDumpTest.kt`: three dumps for `sing-box check`: `vless-http-reality`, `vless-plain-ws`,
  `vless-tls-insecure`, each `LinkParser.parse(link)` → `SingBoxConfig.build`.

  `cli/internal/links/share_test.go`: `type=http` and `type=h2` are `ErrUnsupportedTransport`;
  `security=none` without `pbk` sets `Plain`; `allowInsecure=1` sets `Insecure` on ordinary TLS and is ignored
  with `pbk`. `cli/internal/engine/singbox/config_test.go`: a `Plain` line has no `tls` key; an `Insecure` one
  has `tls.insecure == true`.

- [ ] **Step 2: Kotlin.**
  - `OutboundSpec.kt`: `data class Http(val path: String, val hosts: List<String>) : TransportSpec`; the three
    `Vless` fields.
  - `LinkParser.parseVless`: `"http", "h2"` → `Http(path or "/", host split on ',' trimmed, blanks dropped)`;
    `plain = security == "none" && pbk blank`; `insecure = pbk blank && (allowInsecure|insecure is "1" or "true")`.
    `streamTransport` gets the same `"http", "h2"` case for Trojan and VMess.
  - `SingBoxConfig`: the `tls` object only when `!spec.plain`; inside it, when `publicKey` is blank,
    `insecure` after `server_name`; `putTransport` writes `{type: "http", host: […]?, path}`.
  - `XrayConfig.buildXhttp`: `security: "none"` and no `tlsSettings` when `spec.plain`.
  - `TransportSelector.kt`: `Plain` in the enum (label "No TLS"), at the end of `DEFAULT_ORDER`;
    `transportKind()` returns it for a `Vless` that is `plain` and neither XHTTP nor gRPC.
  - `LocationConfig.label()` and `RouterExportSheet.routerLabel()`: `is Http -> "HTTP/2"`;
    `PkBoardModel.wireShape`: `TransportKind.Plain -> words.wireStream`.
  - `RouterExport`: `xray()` writes `security: none` for a plain Vless; `xrayOutbound` returns null when the
    spec's transport is `Http`; `RouterExportSheet` builds the Xray form with `?.let`.

- [ ] **Step 3: Go.** `ParseVless` reads the two fields by the same rule; `upstreamOutbound` leaves `tls` out
  for `Plain` and adds `insecure` when `PublicKey == ""`; the Xray builder writes `security: none` for `Plain`.
  Run: `cd cli && GOTOOLCHAIN=go1.26.5 go test ./internal/links/ ./internal/engine/singbox/ -run 'Share|Vless|Config' -count=1`
  Expected: PASS.

- [ ] **Step 4: docs, commit, push**; PR Checks green, including `sing-box check` of the three new dumps.
  README Protocols table: VLESS over the HTTP transport, and without TLS.
