package org.olcbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.RustE2eDevice.SHELL_PACKAGE
import org.olcbox.app.RustE2eDevice.UNREACHABLE
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * Per-app tunnelling on the Rust core: the app lists decide whose traffic enters
 * the tun, and so reaches the core behind hev.
 *
 * The shell (uid 2000, com.android.shell) asks for 192.0.2.1:80, TEST-NET-1,
 * which answers nowhere. The test's core.json sends that one address to a
 * server in this process (RustE2eDevice.RedirectServer), so an answer that
 * holds the server's nonce came tun, hev, SOCKS, core, and nothing else; a
 * GET that stays off the tun goes to the real network and is never answered,
 * and the server's count says so too. The other package a list names is the
 * test APK's own: installed, so Android takes the list, and never running.
 *
 *  - only the listed apps go through the tun (proxy_selected);
 *  - a bypassed app stays out of it, everyone else goes in (bypass_selected);
 *  - every app goes in but this one, whose own sockets never enter its tun (all_apps);
 *  - a list edited inside a session is applied there, without a stop.
 *
 * No carrier room, no server of ours.
 */
@RunWith(AndroidJUnit4::class)
class RustSplitE2ETest {
    private val d = RustE2eDevice
    private val login = d.newLogin()
    private lateinit var server: RustE2eDevice.RedirectServer

    @Before
    fun setUp() {
        d.consent()
        server = RustE2eDevice.RedirectServer()
        RustCore.testConfig = { port, tunLogin -> server.config(port, tunLogin ?: login) }
        d.saveProfile(RustE2eDevice.PLACEHOLDER_LINK)
    }

    @After
    fun tearDown() {
        try {
            if (d.serviceRunning() || OlcboxVpnState.status.value !is VpnStatus.Disconnected) d.stopVpnAndWait()
        } finally {
            RustCore.testConfig = null
            server.close()
        }
    }

    /** A start with [split] and its lists, up to Connected, with the lists applied to the interface. */
    private fun connect(split: String, proxy: List<String> = emptyList(), bypass: List<String> = emptyList()) {
        val applied = d.logCount("Split tunneling:")
        d.startVpn(d.startIntent(login, split, proxy, bypass))
        d.waitForConnected()
        d.waitFor("the app lists to be applied", 10_000) { d.logCount("Split tunneling:") > applied }
        assertTrue("the Rust core runs", RustCoreEngine.isCoreRunning())
        d.say("RESULT $split proxy=$proxy bypass=$bypass: connected; ${OlcboxVpnState.logs.value.lastOrNull { it.startsWith("Split tunneling:") }}")
    }

    /**
     * The shell's GET of [UNREACHABLE]: true when the core carried it. An answer is one
     * request at the server and bytes on tun0; no answer is no request at the server.
     */
    private fun shellGoesThroughTheTun(path: String): Boolean {
        val hitsBefore = server.hits.get()
        val bytesBefore = d.tunInterfaceBytes() ?: 0L
        val through = d.shellFetch(UNREACHABLE, 80, path, server.nonce)
        val bytesAfter = d.tunInterfaceBytes() ?: 0L
        val hits = server.hits.get() - hitsBefore
        d.say("RESULT $path: answered=$through, server +$hits, tun0 bytes $bytesBefore -> $bytesAfter")
        if (through) {
            assertEquals("an answer is one request at the server", 1, hits)
            assertTrue("tun0 carried the answer", bytesAfter > bytesBefore)
        } else {
            assertEquals("a GET off the tun reaches no server", 0, hits)
        }
        return through
    }

    @Test
    fun onlyTheListedAppsGoThroughTheTun() {
        connect("proxy_selected", proxy = listOf(SHELL_PACKAGE))
        assertTrue("the shell is listed: its GET goes through the core", shellGoesThroughTheTun("/split-proxy-listed"))
        d.stopVpnAndWait()

        connect("proxy_selected", proxy = listOf(d.testPackage))
        assertFalse("the shell is not listed: its GET stays off the tun", shellGoesThroughTheTun("/split-proxy-unlisted"))
    }

    @Test
    fun aBypassedAppStaysOutOfTheTun() {
        connect("bypass_selected", bypass = listOf(SHELL_PACKAGE))
        assertFalse("the shell is bypassed: its GET stays off the tun", shellGoesThroughTheTun("/split-bypass-listed"))
        d.stopVpnAndWait()

        connect("bypass_selected", bypass = listOf(d.testPackage))
        assertTrue("another app is bypassed: the shell's GET goes through the core", shellGoesThroughTheTun("/split-bypass-unlisted"))
    }

    @Test
    fun everyAppGoesThroughTheTunButThisOne() {
        connect("all_apps")
        assertTrue("all apps: the shell's GET goes through the core", shellGoesThroughTheTun("/split-all"))

        // This app's own socket (the test runs in its process) is never in its own tun:
        // it goes to the real network, where TEST-NET-1 answers nothing.
        val hitsBefore = server.hits.get()
        val own = runCatching {
            Socket().use { s ->
                s.soTimeout = 5_000
                s.connect(InetSocketAddress(UNREACHABLE, 80), 5_000)
                s.getOutputStream().write("GET /split-self HTTP/1.0\r\nHost: $UNREACHABLE\r\n\r\n".toByteArray())
                s.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }
        d.say("RESULT this app's own GET of $UNREACHABLE: ${own.exceptionOrNull() ?: own.getOrNull()?.take(40)}")
        assertFalse("this app's GET was not carried by its own tun", own.getOrNull().orEmpty().contains(server.nonce))
        assertEquals("this app's GET reached no server", hitsBefore, server.hits.get())
    }

    @Test
    fun aListEditedInsideTheSessionIsApplied() {
        connect("proxy_selected", proxy = listOf(SHELL_PACKAGE))
        assertTrue("before the edit the shell's GET goes through the core", shellGoesThroughTheTun("/split-edit-before"))

        // The app's restart for an edited list: another start inside the session.
        val applied = d.logCount("Split tunneling:")
        d.startVpn(d.startIntent(login, "proxy_selected", proxy = listOf(d.testPackage)))
        d.waitFor("the edited list to be applied", 60_000) { d.logCount("Split tunneling:") > applied && d.connected() }
        // The interface is handed over once Android built the new one: until then the old
        // one may still carry the shell. Within half a minute it must not.
        val deadline = System.currentTimeMillis() + 30_000
        var through = true
        while (through && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000)
            through = d.shellFetch(UNREACHABLE, 80, "/split-edit-settling", server.nonce, timeoutMs = 4_000)
        }
        assertFalse("after the edit the shell is not listed: its GET stays off the tun", through)
        assertFalse("and stays off it", shellGoesThroughTheTun("/split-edit-after"))
        assertTrue("the session went on: still connected", d.connected())
    }
}
