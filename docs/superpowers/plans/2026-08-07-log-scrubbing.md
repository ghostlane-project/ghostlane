# Log Scrubbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every line that reaches the user-visible, exportable log passes through one scrubber that removes our addresses, credentials and capabilities while leaving the line's meaning intact.

**Architecture:** One pure class in `commonMain` with a per-process salt, called from the single `addLog` of each platform — after the state machines have already seen the raw line, so reconnect detection cannot break. Plus one config change: sing-box drops from `info` to `warn`, which stops it naming the user's own destinations.

**Tech Stack:** Kotlin Multiplatform (commonMain / androidMain / iosMain / jvmMain), `kotlin.test`, Gradle on GitHub runners.

**Spec:** `docs/superpowers/specs/2026-08-07-log-scrubbing-design.md`

## Global Constraints

- **Never build or test locally.** This dev box has no JDK and must not get one. Verification is `pr-checks.yml` on GitHub runners: `:sharedUI:jvmTest`, `:desktopApp:compileKotlin`, `:androidApp:assembleDebug`. Apple targets compile in `release.yml`.
- Push to the **`proofkit`** remote (`git@github.com:ghostlane-project/ghostlane.git`), never to `origin` — that is upstream `alananisimov/olcbox`.
- `commonMain` may not gain dependencies. The tag hash is hand-rolled for exactly this reason.
- The scrubber runs **inside `addLog`**, never on a line before a parser sees it. `handleRtcLine` (`IosVpnManager.kt:481`), `OlcboxVpnService.kt:1284-1316` and `DesktopVpnManager.kt:796` match raw engine text to decide transport state.
- A scrubber that mangles ordinary text is worse than no scrubber. Timestamps (`15:28:17`), versions (`1.0.270`), local addresses and ports must come through untouched.
- No placeholder implementations. If a step cannot be completed properly, stop and ask.

## File Structure

**New**
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/log/LogScrubber.kt` — the whole policy, in one place, so the three platforms cannot drift apart.
- `sharedUI/src/commonTest/kotlin/org/olcbox/app/log/LogScrubberTest.kt`

**Modified**
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt` — `info` → `warn`, twice.
- `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt` — pin the level.
- `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnState.kt` — scrub in `addLog`.
- `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt` — scrub in `addLog`; drop the room id from `:761`.
- `sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt` — scrub in `addLog`.

---

### Task 1: The scrubber

**Files:**
- Create: `sharedUI/src/commonMain/kotlin/org/olcbox/app/log/LogScrubber.kt`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/log/LogScrubberTest.kt`

**Interfaces:**
- Produces: `class LogScrubber(salt: Long)` with `fun scrub(line: String): String`, and `LogScrubber.default` — the per-process instance Task 3 calls.

- [ ] **Step 1: Write the failing tests**

Create `sharedUI/src/commonTest/kotlin/org/olcbox/app/log/LogScrubberTest.kt`:

```kotlin
package org.olcbox.app.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {
    private val s = LogScrubber(salt = 12345L)

    @Test fun aPublicAddressBecomesATag() {
        val out = s.scrub("engine: dial tcp 95.179.246.109:443: i/o timeout")
        assertFalse(out.contains("95.179.246.109"), out)
        assertTrue(Regex("node#[0-9a-f]{4}").containsMatchIn(out), out)
        assertTrue(out.contains(":443: i/o timeout"), "the port and the error must survive: $out")
    }

    @Test fun aPublicIpv6BecomesATag() {
        val out = s.scrub("engine: dial tcp [2a01:4f8:c17:b8f::1]:443 failed")
        assertFalse(out.contains("2a01"), out)
        assertTrue(Regex("node#[0-9a-f]{4}").containsMatchIn(out), out)
    }

    @Test fun theSameHostReadsTheSameAndDifferentHostsDiffer() {
        val out = s.scrub("tried 95.179.246.109 then 45.32.190.172 then 95.179.246.109")
        val tags = Regex("node#[0-9a-f]{4}").findAll(out).map { it.value }.toList()
        assertEquals(3, tags.size, out)
        assertEquals(tags[0], tags[2], "one host must read the same throughout: $out")
        assertTrue(tags[0] != tags[1], "two hosts must not collapse into one: $out")
    }

    @Test fun localAddressesSurviveVerbatim() {
        // Most of what makes a log readable is local, and none of it is secret.
        for (line in listOf(
            "core ready on 127.0.0.1:1080",
            "socks5 server listening on 0.0.0.0:10808",
            "tun 172.19.0.1/30 up",
            "tun6 fdfe:dcba:9876::1/126 up",
            "gateway 192.168.1.1",
            "wg peer 10.66.66.2",
            "carrier nat 100.64.0.1",
            "self-assigned 169.254.1.1",
        )) {
            assertEquals(line, s.scrub(line), "a local address must not be tagged")
        }
    }

    @Test fun credentialsAndCapabilitiesGo() {
        assertEquals("start room=<id>", s.scrub("start room=a3f9c1e2-4b5d-6789-0abc-def012345678"))
        assertEquals("import <link>", s.scrub("import olcrtc://crypt1/AAAAbbbbCCCCdddd"))
        assertEquals("import <link>", s.scrub("import happ://crypt5/fzvdQQSl2kKPyNPhAeRV4WSh12xLFV8"))
        assertEquals("GET <host>/sub/x", s.scrub("GET sub.proofkit.org/sub/x"))
        assertEquals("GET <host>/api", s.scrub("GET proofkit.org/api"))
    }

    @Test fun anOrdinaryLineComesBackUntouched() {
        // The regressions that matter are the ones that eat text nobody was hiding.
        for (line in listOf(
            "2026-08-07 15:28:17 INFO tunnel established",
            "olcbox 1.0.270 (270) starting",
            "Packet tunnel up",
            "ping DE: could not resolve dns.google",
            "sni www.microsoft.com fp chrome mtu 1500",
        )) {
            assertEquals(line, s.scrub(line), "nothing sensitive here — leave it alone")
        }
    }

    @Test fun aDifferentSaltGivesADifferentTag() {
        // Otherwise a tag is a confirmation oracle: guess the address, compute the tag.
        val line = "dial 95.179.246.109"
        assertTrue(
            s.scrub(line) != LogScrubber(salt = 999L).scrub(line),
            "tags must not be comparable across installs"
        )
    }

    @Test fun theTransportStateMarkersSurvive() {
        // These exact strings decide reconnect — handleRtcLine (IosVpnManager),
        // OlcboxVpnService and DesktopVpnManager all match on them. The scrubber runs
        // after those parsers today, and this test is what keeps a future refactor
        // from quietly breaking reconnect by moving it earlier.
        for (marker in listOf(
            "socks5 server listening on 127.0.0.1:1080",
            "ice connection state changed: connected",
            "peer connection state changed: connected",
            "ice connection state changed: failed",
            "peer connection state changed: closed",
            "network is unreachable",
            "use of closed network connection",
            "read/write on closed pipe",
        )) {
            assertEquals(marker, s.scrub(marker), "a state marker must pass through intact")
        }
    }
}
```

- [ ] **Step 2: Confirm it fails**

You cannot run this locally. Push the branch and open the PR (Task 4 does this properly); for now note that `LogScrubber` does not exist, so `:sharedUI:jvmTest` would fail to compile with `Unresolved reference: LogScrubber`. Do not install a JDK to check.

- [ ] **Step 3: Write the scrubber**

Create `sharedUI/src/commonMain/kotlin/org/olcbox/app/log/LogScrubber.kt`:

```kotlin
package org.olcbox.app.log

import kotlin.random.Random

/**
 * Rewrites a log line so it still says what happened without saying where.
 *
 * The exported log is what makes a support request answerable, so this removes
 * values rather than lines: an address becomes a tag, and the error, the port and
 * the timing around it survive. Two different hosts get two different tags, because
 * "the failover tried three and all refused" is a different fault from "one host
 * refused three times".
 *
 * The salt is per process on purpose. A short hash of an IPv4 address is otherwise a
 * confirmation oracle — an adversary who suspects an address computes its tag and
 * checks. Salted, a tag means something inside one log and nowhere else, which is all
 * diagnosis asks of it. We cannot map a tag back to a node either; the location label
 * in the same line is what support actually uses.
 */
class LogScrubber(private val salt: Long) {

    fun scrub(line: String): String {
        var out = line
        // Links first: a crypt blob is base64 and could otherwise be picked apart by
        // the narrower rules below.
        out = CRYPT_LINK.replace(out, "<link>")
        out = UUID.replace(out, "<id>")
        out = OUR_HOST.replace(out, "<host>")
        out = IPV6.replace(out) { m -> if (isLocalV6(m.value)) m.value else tag(m.value) }
        out = IPV4.replace(out) { m -> if (isLocalV4(m.value)) m.value else tag(m.value) }
        return out
    }

    /**
     * FNV-1a over the salted value, shown as four hex digits. Not a cryptographic
     * hash and does not need to be — the salt does the hiding, this only spreads
     * values across buckets so two hosts rarely read alike. Hand-rolled because
     * commonMain takes no dependencies.
     */
    private fun tag(value: String): String {
        var h = FNV_OFFSET xor salt
        for (c in value) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME
        }
        val short = ((h ushr 16) and 0xFFFF).toString(16).padStart(4, '0')
        return "node#$short"
    }

    private fun isLocalV4(addr: String): Boolean {
        val o = addr.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4 || o.any { it > 255 }) return false
        return when {
            o[0] == 127 || o[0] == 10 || o[0] == 0 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true          // CGNAT
            o.all { it == 255 } -> true
            else -> false
        }
    }

    private fun isLocalV6(addr: String): Boolean {
        val a = addr.lowercase()
        // ULA (fc00::/7) covers the desktop TUN address; fe80::/10 is link-local.
        return a == "::1" || a == "::" || a.startsWith("fc") || a.startsWith("fd") ||
            a.startsWith("fe8") || a.startsWith("fe9") || a.startsWith("fea") ||
            a.startsWith("feb")
    }

    companion object {
        /**
         * One salt for the life of the process, which is the unit a log file covers.
         */
        val default: LogScrubber by lazy { LogScrubber(Random.nextLong()) }

        private const val FNV_OFFSET = -3750763034362895579L   // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L           // 0x100000001b3

        private val CRYPT_LINK = Regex("""\b(?:olcrtc|happ)://crypt\d/\S+""")

        private val UUID = Regex(
            """\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-""" +
                """[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""
        )

        private val OUR_HOST = Regex("""\b(?:[a-zA-Z0-9-]+\.)*proofkit\.org\b""")

        private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

        // Two shapes cover everything that turns up in a log: any compressed address
        // (which always contains "::") and a full one (four colon-separated groups or
        // more). Deliberately not the RFC 4291 grammar — the property that matters is
        // that a timestamp like 15:28:17 must never match, and the tests pin it.
        private val IPV6 = Regex(
            """[0-9a-fA-F]{0,4}::[0-9a-fA-F:]{0,39}""" +
                """|(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}"""
        )
    }
}
```

- [ ] **Step 4: Commit**

```bash
cd /root/olcbox-fork
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/log/LogScrubber.kt \
        sharedUI/src/commonTest/kotlin/org/olcbox/app/log/LogScrubberTest.kt
git commit -m "feat(log): a scrubber that keeps the fault and drops the address

Salted per process so a tag cannot be compared across installs or turned into
a confirmation oracle for a guessed address."
```

---

### Task 2: sing-box stops naming the user's destinations

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt` — `:160`, `:265`, and `render()` at `:322`
- Test: `sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt`

**Interfaces:**
- Consumes: nothing. Produces: nothing. Independent of Task 1.

- [ ] **Step 1: Write the failing assertion**

Add to `SingBoxConfigTest`. It asserts per **builder**, not per renderer: `render()`
emits no `log` block at all today, and a `contains("info")` check would pass it while
sing-box quietly applied its own `info` default.

```kotlin
    @Test fun everyBuilderKeepsTheLogQuiet() {
        // At "info" sing-box names every outbound connection the user makes, and that
        // output lands in the log we invite the user to export — their browsing
        // history in a file, which our no-logs commitment says it must not be. Dial
        // failures are warnings and survive. Nothing parses this stream: readiness is
        // a socket probe (waitForCoreSocks), not a log match.
        //
        // Read the field rather than grepping the string: a config with no "log" block
        // is not quiet, it is on sing-box's default, which is "info".
        for (json in listOf(
            SingBoxConfig.build(vless()),
            SingBoxConfig.buildTun(vless()),
            SingBoxConfig.buildTunSocks(socksPort = 10809),
            SingBoxConfig.buildDesktopTun(corePort = 10809, verifyPort = 10810),
            SingBoxConfig.buildOlcrtcSocks(olcrtcPort = 10808),
        )) {
            val level = Json.parseToJsonElement(json).jsonObject["log"]
                ?.jsonObject?.get("level")?.jsonPrimitive?.content
            assertEquals("warn", level, json)
        }
    }
```

`vless()` is the existing helper in that file; `Json`, `jsonObject` and `jsonPrimitive`
are already imported there.

- [ ] **Step 2: Change all three renderers**

Two of them already have the block — at `:160` (`buildDesktopTun`) and `:265`
(`renderTun`) change the value:

```kotlin
            putJsonObject("log") { put("level", "warn") }
```

The third, `render()` at `:322`, has none. Add it as the first entry of the object, so
`build()` and `buildOlcrtcSocks()` stop running on the default:

```kotlin
    private fun render(socksPort: Int, outbounds: JsonArrayBuilder.() -> Unit): String {
        val obj = buildJsonObject {
            // Without this sing-box applies its own default, which is "info" — and
            // that names every connection the user makes.
            putJsonObject("log") { put("level", "warn") }
            putJsonArray("inbounds") {
```

- [ ] **Step 3: Commit**

```bash
git add sharedUI/src/commonMain/kotlin/org/olcbox/app/net/SingBoxConfig.kt \
        sharedUI/src/commonTest/kotlin/org/olcbox/app/net/SingBoxConfigTest.kt
git commit -m "fix(log): sing-box at info listed every site the user visited

That output lands in the log we ask users to export. Dial failures are
warnings and still appear; nothing parses this stream."
```

---

### Task 3: Wire it into the three sinks

**Files:**
- Modify: `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnState.kt:49`
- Modify: `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt:1095` and `:761`
- Modify: `sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt:671`

**Interfaces:**
- Consumes: `LogScrubber.default.scrub(String): String` from Task 1.

- [ ] **Step 1: Android**

`OlcboxVpnState.kt` — scrub once at the top so logcat and a captured bug report get
the clean line too:

```kotlin
    fun addLog(msg: String) {
        val safe = LogScrubber.default.scrub(msg)
        Log.d(TAG, safe)
        _logs.update { (it + safe).takeLast(MAX_LOG_ENTRIES) }
    }
```

Add `import org.olcbox.app.log.LogScrubber`.

- [ ] **Step 2: Desktop**

`DesktopVpnManager.kt:1095`:

```kotlin
    private fun addLog(message: String) {
        val safe = LogScrubber.default.scrub(message)
        _logs.update {
            (it + safe).takeLast(MAX_LOG_ENTRIES)
        }
    }
```

And at `:761`, drop the room id from the message — a capability does not belong in a
log line, and leaning on the UUID rule to catch it is one rule away from a leak:

```kotlin
        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, port=${socksSettings.port}")
```

Add `import org.olcbox.app.log.LogScrubber`.

- [ ] **Step 3: iOS**

`IosVpnManager.kt:671`:

```kotlin
    private fun addLog(message: String) {
        _logs.value = (_logs.value + LogScrubber.default.scrub(message)).takeLast(MAX_LOG_LINES)
    }
```

Add `import org.olcbox.app.log.LogScrubber`.

- [ ] **Step 4: Check nothing scrubs too early**

Read each of these and confirm the parser still receives the **raw** line, not the
scrubbed one — this is the one way this change can break the app:

- `IosVpnManager.kt:103` — `addLog("rtc: $it")` then `handleRtcLine(it)`; `it` must stay raw.
- `OlcboxVpnService.kt:331` — `addLog("rtc: $line")` and the matcher at `:1284-1316`.
- `DesktopVpnManager.kt:118,121` — `onOutput` feeding `addLog`, and the matcher at `:796`.

If any of them passes the scrubbed value onward, move the scrub back inside `addLog`.

- [ ] **Step 5: Commit**

```bash
git add sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnState.kt \
        sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt \
        sharedUI/src/iosMain/kotlin/org/olcbox/app/vpn/IosVpnManager.kt
git commit -m "feat(log): scrub every line on the way into the buffer

Inside addLog on all three platforms, which is after the state machines have
matched the raw text — scrubbing earlier would break reconnect detection."
```

---

### Task 4: Verify on the runners

**Files:** none.

- [ ] **Step 1: Push the branch to our fork**

```bash
cd /root/olcbox-fork
git push proofkit feat/log-scrubbing
```

**`proofkit`, not `origin`** — `origin` is upstream `alananisimov/olcbox`.

- [ ] **Step 2: Open the PR and let `pr-checks` run**

```bash
gh pr create --repo ghostlane-project/ghostlane --base main --head feat/log-scrubbing \
  --title "Logs that explain the failure without naming the infrastructure" \
  --body "Spec: docs/superpowers/specs/2026-08-07-log-scrubbing-design.md"
gh pr checks --repo ghostlane-project/ghostlane --watch
```

Expected: `:sharedUI:jvmTest` green (LogScrubberTest + SingBoxConfigTest),
`:desktopApp:compileKotlin` and `:androidApp:assembleDebug` green.

If `gh` is unavailable on this host, push the branch and ask the user to open the PR,
then watch the run through the GitHub UI.

- [ ] **Step 3: Read the failures rather than guessing**

The two likely ones, and what they mean:

- `anOrdinaryLineComesBackUntouched` failing on the timestamp case means the IPv6
  pattern is eating `15:28:17`. Tighten the pattern; do not relax the test.
- An Apple-target compile error appears only in `release.yml`, not `pr-checks`. If the
  iOS edit does not compile, it will surface at release time — so after the PR is
  green, trigger a release build before calling this done.

- [ ] **Step 4: Merge and release**

```bash
gh pr merge --repo ghostlane-project/ghostlane --squash --delete-branch
```

Then let the release build run and confirm the Apple targets compiled.

- [ ] **Step 5: Confirm on a real device**

Install the build, connect, then export the log from the home screen and read it. The
check is specific: no public IP, no UUID, no `proofkit.org` host anywhere in the file;
`127.0.0.1` and the local ports still present; and a deliberate failure (pick a
location, kill the network) still produces a line that says what failed.
