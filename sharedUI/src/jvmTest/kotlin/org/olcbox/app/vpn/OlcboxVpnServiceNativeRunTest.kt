package org.olcbox.app.vpn

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// NativeTunnelRunTest drives the type, and nothing on the JVM drives the
// Android service that has to use it: a service that went back to a thread of
// its own would start a second hev beside the first with every test of the
// type still passing. So the service is read as text and held to the places
// where it could. jvmTest names the file as an input (sharedUI/build.gradle.kts):
// without that, a change to the service alone finds these tests up to date, or
// in the build cache, and they do not run.
class OlcboxVpnServiceNativeRunTest {
    // Without its comments: several of them name what is looked for here.
    private val service = File("src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt")
        .readText()
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""(?m)(^|[ \t])//.*$"""), "")

    /** The service's own function [name], from its header to the brace that closes it. */
    private fun member(name: String): String {
        val headers = Regex("""\n    (?:\w+ )*fun $name\(""").findAll(service).toList()
        assertEquals(1, headers.size, "functions of the service named $name")
        val start = headers.single().range.first
        val end = service.indexOf("\n    }\n", start)
        assertTrue(end > start, "$name does not end where a function of the service ends")
        return service.substring(start, end)
    }

    /** What the `val` [name] nearest above [before] is set to. It has to be one of the same function. */
    private fun valueOf(name: String, before: Int): String {
        val declared = Regex("""\bval $name = ([^\n]+)\n""").findAll(service).lastOrNull { it.range.last < before }
            ?: fail("tun2socksRun.waited is told \"$name\", which is no val set above it")
        assertFalse(
            "\n    }\n" in service.substring(declared.range.first, before),
            "tun2socksRun.waited is told \"$name\", which is no val of the function that says it"
        )
        return declared.groupValues[1]
    }

    // Asked first, a start that is refused has taken nothing. Asked after, it
    // holds a duplicate of the tun's descriptor that nobody closes.
    @Test
    fun aStartAsksBeforeItDuplicatesTheDescriptor() {
        val start = member("startTun2socks")
        val asked = start.indexOf("if (!tun2socksRun.mayStart()) {")
        val duplicated = start.indexOf("ParcelFileDescriptor.dup(")
        assertTrue(asked >= 0, "startTun2socks does not ask NativeTunnelRun whether a run may start")
        assertTrue(duplicated >= 0, "startTun2socks duplicates no descriptor: what this test holds has moved")
        assertTrue(asked < duplicated, "startTun2socks duplicates the descriptor before it asks whether a run may start")
        assertTrue(
            "return false" in start.substring(asked, duplicated),
            "a start that may not start does not return before the descriptor is duplicated"
        )
    }

    // The stops cleared the thread after their wait whatever the wait had
    // seen, and one of them without a wait. Each way the answer is made has to
    // be a wait's own result, or the thread's own word that it has ended.
    @Test
    fun aRunIsForgottenOnlyOnWhatAWaitSaw() {
        val calls = Regex("""tun2socksRun\.waited\(\w+, (?:ended = )?([^\n]+)\)\n""").findAll(service).toList()
        assertTrue(calls.isNotEmpty(), "the service tells NativeTunnelRun of no wait")
        assertEquals(
            Regex("""tun2socksRun\.waited\(""").findAll(service).count(),
            calls.size,
            "a call of tun2socksRun.waited is written in a way this test cannot read"
        )
        for (call in calls) {
            val said = call.groupValues[1]
            val made = if (Regex("""\w+""").matches(said)) valueOf(said, before = call.range.first) else said
            for (way in made.split(Regex("""\belse\b"""))) {
                assertTrue(
                    "waitForTun2socksStopped(" in way || ".isAlive" in way,
                    "tun2socksRun.waited is told \"${way.trim()}\", which is neither what a wait returned nor whether the thread lives"
                )
            }
        }
    }

    // The thread of a run goes to NativeTunnelRun and is kept nowhere else,
    // and the NativeTunnelRun is the process's. A field of the service for the
    // thread is what every stop cleared; a NativeTunnelRun of the service
    // would be lost with the service while its run goes on.
    @Test
    fun theThreadOfARunIsKeptForTheProcessAndNowhereElse() {
        val start = member("startTun2socks")
        val started = Regex("""(\S+) = thread\(name = "OlcboxTun2Socks"""").findAll(service).toList()
        assertEquals(1, started.size, "places where the service starts the thread of a native run")
        val thread = Regex("""val (\w+) = thread\(name = "OlcboxTun2Socks"""").find(start)?.groupValues?.get(1)
            ?: fail("startTun2socks does not start the native run's thread into a val of its own")
        assertTrue(
            start.indexOf("tun2socksRun.started($thread)") > start.indexOf("val $thread = thread("),
            "startTun2socks does not give the thread it started to NativeTunnelRun"
        )
        assertEquals(
            emptyList(),
            Regex("""\n    (?:[@\w]+ )*var (\w+)\s*:\s*Thread\b""").findAll(service).map { it.groupValues[1] }.toList(),
            "the service keeps a thread in a var of its own"
        )

        assertEquals(1, Regex("""\bNativeTunnelRun\(\)""").findAll(service).count(), "NativeTunnelRun objects the service makes")
        val companionStart = service.indexOf("\n    companion object {\n")
        assertTrue(companionStart >= 0, "the service has no companion object")
        val companion = service.substring(companionStart, service.indexOf("\n    }\n", companionStart))
        assertTrue(
            "private val tun2socksRun = NativeTunnelRun()" in companion,
            "tun2socksRun is not the companion's: a run would be forgotten with the service that started it"
        )
    }

    // What a stop still has to take down is asked of the service: a run it
    // started and has not told to stop. The process's run can be one this
    // service told to stop and gave up waiting for, or the next service's.
    // Asked of that, a run that outlived its stop made every later stop of the
    // same service a whole stop again, and the stop of a destroyed service
    // said Disconnected over the session of the one after it.
    @Test
    fun aStopAsksOnlyForARunOfItsOwnService() {
        val cleanup = member("cleanup")
        assertFalse("tun2socksThread" in cleanup, "cleanup asks whether the process has a run")
        assertFalse("tun2socksRun" in cleanup, "cleanup asks NativeTunnelRun, which is the process's")
        assertTrue(
            Regex("""\btun2socksToStop\b""").findAll(cleanup).count() >= 2,
            "cleanup asks in two places whether this service has a run to stop: before it begins, and after the start it cancelled has ended"
        )
        val own = Regex("""\n    private val tun2socksToStop: Boolean\n\s+get\(\) = ([^\n]+)\n""").find(service)?.groupValues?.get(1)
            ?: fail("the service does not say what a run of its own still to stop is")
        assertEquals("tun2socksStarted && !tun2socksStopRequested.get()", own)
    }
}
