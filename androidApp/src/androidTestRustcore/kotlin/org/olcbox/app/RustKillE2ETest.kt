package org.olcbox.app

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.RustE2eDevice.SHELL_PACKAGE
import org.olcbox.app.RustE2eDevice.UNREACHABLE
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.RustCore

/**
 * kill -9 of the VPN service's process on the Rust core, and Android's restart of it.
 *
 * The kill cannot be watched from inside an instrumentation: the instrumentation
 * runs in the app's process, and when that process dies Android ends the
 * instrumentation and force-stops the package, which cancels the service's
 * restart. So scripts/rust-core-android-kill.sh makes the kill from the shell,
 * on a session this class arms and the app's debug receiver starts outside any
 * instrumentation, against a VLESS REALITY server the script runs on the host
 * (Xray, reached at 10.0.2.2), then waits for Android to bring the service back
 * and fetches through the tun again. This class holds the device's two halves:
 *
 *  - [aBareStartComesUpOnTheRustCoreFromWhatTheAppSaved], in every run of the
 *    package: what Android redelivers after the kill (the service answers
 *    START_REDELIVER_INTENT) is the intent the session began with, and the
 *    receiver's names nothing but the action. The engine, the mode, the login
 *    and the app lists come from what the app saved, and such a start comes up
 *    on the Rust core and carries the shell's GET through the tun.
 *  - [armTheSessionForTheShell], only when the script asks
 *    (`-e rustKill arm -e rustKillLink <link>`): consents to the VPN and saves
 *    those settings with the script's line as the profile.
 */
@RunWith(AndroidJUnit4::class)
class RustKillE2ETest {
    private val d = RustE2eDevice

    private fun saveSettings(login: SocksLogin) = runBlocking {
        d.context.vpnPrefDataStore.edit {
            it[RustCore.KEY_DEV_RUST_CORE] = true
            it[KEY_ANDROID_CONNECTION_MODE] = "tun"
            it[KEY_ANDROID_SOCKS_USERNAME] = login.username
            it[KEY_ANDROID_SOCKS_PASSWORD] = login.password
            it[KEY_ANDROID_SPLIT_TUNNEL_MODE] = "proxy_selected"
            it[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS] = setOf(SHELL_PACKAGE)
        }
    }

    private fun forgetSettings() = runBlocking {
        d.context.vpnPrefDataStore.edit {
            it.remove(RustCore.KEY_DEV_RUST_CORE)
            it.remove(KEY_ANDROID_CONNECTION_MODE)
            it.remove(KEY_ANDROID_SOCKS_USERNAME)
            it.remove(KEY_ANDROID_SOCKS_PASSWORD)
            it.remove(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            it.remove(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS)
        }
    }

    @Test
    fun aBareStartComesUpOnTheRustCoreFromWhatTheAppSaved() {
        d.consent()
        val login = d.newLogin()
        val seen = AtomicReference<SocksLogin?>()
        val server = RustE2eDevice.RedirectServer()
        RustCore.testConfig = { port, tunLogin ->
            seen.set(tunLogin)
            server.config(port, tunLogin ?: login)
        }
        d.saveProfile(RustE2eDevice.PLACEHOLDER_LINK)
        saveSettings(login)
        val onRust = d.logCount("Rust core ready on")
        try {
            // The debug receiver's start, and what Android redelivers: the action and nothing else.
            d.startVpn(d.serviceIntent(OlcboxVpnActions.ACTION_START_VPN))
            d.waitForConnected()
            assertTrue("the session came up on the Rust core", d.logCount("Rust core ready on") > onRust)
            assertTrue("the Rust core runs", RustCoreEngine.isCoreRunning())
            assertEquals("the saved login is the one the core was given", login, seen.get())
            assertTrue(
                "the saved list put the shell in the tun: its GET came through the core",
                d.shellFetch(UNREACHABLE, 80, "/kill-bare-start", server.nonce)
            )
            assertEquals("the server saw that one request", 1, server.hits.get())
            d.stopVpnAndWait()
            assertFalse("the core stopped with the session", RustCoreEngine.isCoreRunning())
            d.say("RESULT a bare start came up on the Rust core from the saved settings and carried the shell's GET")
        } finally {
            if (d.serviceRunning()) runCatching { d.stopVpnAndWait() }
            RustCore.testConfig = null
            server.close()
            forgetSettings()
        }
    }

    @Test
    fun armTheSessionForTheShell() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "only scripts/rust-core-android-kill.sh and -soak.sh arm a session (-e rustKill arm)",
            args.getString("rustKill") == "arm"
        )
        val link = args.getString("rustKillLink").orEmpty()
        assertTrue("the script names its line (-e rustKillLink)", link.startsWith("vless://"))
        d.consent()
        d.saveProfile(link)
        saveSettings(d.newLogin())
        d.say("RESULT armed: the Rust core, tun mode, the shell's traffic alone, the script's line")
    }
}
