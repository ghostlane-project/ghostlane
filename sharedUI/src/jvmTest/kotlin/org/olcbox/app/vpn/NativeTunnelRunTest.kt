package org.olcbox.app.vpn

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// hev-socks5-tunnel and its lwIP are one per process, and a run started beside
// one that has not returned aborts the app in netif_add. The service forgot
// the native thread one second after a stop, whether or not the thread had
// ended, and the next start was that second run.
class NativeTunnelRunTest {
    private val release = CountDownLatch(1)

    /** A native run that has not returned: its thread lives until [release] is counted down. */
    private fun liveRun(): Thread = thread(isDaemon = true) { release.await() }

    private fun endedRun(): Thread = thread(isDaemon = true) {}.also { it.join() }

    @AfterTest
    fun letTheRunsEnd() {
        release.countDown()
    }

    @Test
    fun withNoRunYetOneMayStart() {
        val run = NativeTunnelRun()
        assertTrue(run.mayStart())
        assertNull(run.inProgress())
    }

    @Test
    fun aRunThatHasNotReturnedRefusesTheNext() {
        val run = NativeTunnelRun()
        val native = liveRun()
        run.started(native)
        assertFalse(run.mayStart())
        assertSame(native, run.inProgress())
    }

    // hev ended by itself (a configuration it refused, a tun it lost): nobody
    // stopped it and nobody waited for it.
    @Test
    fun aRunThatHasEndedAllowsTheNext() {
        val run = NativeTunnelRun()
        run.started(endedRun())
        assertTrue(run.mayStart())
        assertNull(run.inProgress())
    }

    // The field's case: the stop was asked, the service waited its second, and
    // hev was still inside a session.
    @Test
    fun aWaitThatTimedOutDoesNotForgetTheRun() {
        val run = NativeTunnelRun()
        val native = liveRun()
        run.started(native)
        run.waited(native, ended = false)
        assertFalse(run.mayStart())
        assertSame(native, run.inProgress())
    }

    @Test
    fun aWaitThatSawTheRunEndForgetsIt() {
        val run = NativeTunnelRun()
        val native = liveRun()
        run.started(native)
        release.countDown()
        native.join()
        run.waited(native, ended = true)
        assertTrue(run.mayStart())
        assertNull(run.inProgress())
    }

    // A stop that waited for an older run says so after the next one started.
    @Test
    fun aWaitForAnOlderRunLeavesTheOneThatFollowed() {
        val run = NativeTunnelRun()
        val older = endedRun()
        run.started(older)
        val newer = liveRun()
        run.started(newer)
        run.waited(older, ended = true)
        assertFalse(run.mayStart())
        assertSame(newer, run.inProgress())
    }

    // A stop that found no thread waited for nothing, and "it ended" is then
    // said of nothing.
    @Test
    fun aWaitForNoRunLeavesTheRunInProgress() {
        val run = NativeTunnelRun()
        val native = liveRun()
        run.started(native)
        run.waited(null, ended = true)
        assertFalse(run.mayStart())
        assertSame(native, run.inProgress())
    }
}
