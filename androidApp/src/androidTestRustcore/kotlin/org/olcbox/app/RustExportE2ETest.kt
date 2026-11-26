package org.olcbox.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.log.CorePin
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.OlcRtcConnectionChecker
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * What the app's export and its room check do through the product path
 * (AndroidVpnManager), with the developer switch on. The build's default is off, so a
 * call that ignores the stored switch runs the Go runtime and shows it.
 */
@RunWith(AndroidJUnit4::class)
class RustExportE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staleLog = File(File(context.cacheDir, "olcbox-xray"), "xray.log")

    @Before
    fun switchOn() {
        runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = true } }
    }

    @After
    fun switchOff() {
        runBlocking { context.vpnPrefDataStore.edit { it.remove(RustCore.KEY_DEV_RUST_CORE) } }
        staleLog.delete()
    }

    @Test
    fun theExportOfARustSessionHasARustSectionAndNoGoFiles() {
        staleLog.parentFile?.mkdirs()
        staleLog.writeText("stale go line from an earlier session\n")
        OlcboxVpnState.addLog("core: Rust core ready on 127.0.0.1")
        val manager = AndroidVpnManager(context)
        val out = runBlocking { manager.diagnosticsLog() }
        assertTrue(out, out.contains("--- Rust core ---"))
        assertTrue(out, out.contains(CorePin.COMMIT.take(12)))
        assertTrue(out, out.contains("Rust core ready"))
        assertFalse(out, out.contains("--- xray core ---"))
        assertFalse(out, out.contains("stale go line"))
        assertEquals(CorePin.COMMIT, runBlocking { manager.corePin() })
    }

    @Test
    fun withTheSwitchOffTheExportIsTheGoFilesAndNamesNoCore() {
        runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = false } }
        staleLog.parentFile?.mkdirs()
        staleLog.writeText("go line\n")
        val manager = AndroidVpnManager(context)
        val out = runBlocking { manager.diagnosticsLog() }
        assertTrue(out, out.contains("--- xray core ---"))
        assertFalse(out, out.contains("--- Rust core ---"))
        assertNull(runBlocking { manager.corePin() })
    }

    /**
     * The manager's check and ping must pass its context: only then can the stored
     * switch pick the Rust core over the build default. A refused carrier is null
     * on either engine, so the test watches the Go runtime instead of the clock.
     * Touching the checker's private runtime marks a file and throws before any
     * dial: RustCheckE2ETest a4 already shows the Rust core refuses this carrier
     * at home, and a4 to a6 cover the real engines. No network, no time limit.
     */
    @Test
    fun theProductsRoomCheckAndPingFollowTheDeveloperSwitch() {
        val goUsed = File(context.cacheDir, "olcbox-go-room-check-used")
        goUsed.delete()
        val delegate = OlcRtcConnectionChecker::class.java.declaredFields
            .first { it.type == Lazy::class.java }
        delegate.isAccessible = true
        val original = delegate.get(null)
        val tripwire = object : Lazy<Any?> {
            override val value: Any?
                get() {
                    goUsed.writeText("the Go runtime answered")
                    throw IllegalStateException("the Go runtime answered")
                }
            override fun isInitialized(): Boolean = false
        }
        val manager = AndroidVpnManager(context)
        val line = LocationConfig(id = "room-x", key = "0123456789abcdef".repeat(4), bypassProvider = "telemost")
        delegate.set(null, tripwire)
        try {
            assertNull("a refused room has no time", runBlocking { manager.checkConnection(line) })
            assertFalse("the check used the Go runtime", goUsed.exists())
            goUsed.delete()
            assertNull("a refused room has no time", runBlocking { manager.ping(line) })
            assertFalse("the ping used the Go runtime", goUsed.exists())

            runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = false } }
            assertNull("a refused room has no time", runBlocking { manager.checkConnection(line) })
            assertTrue("with the switch off the check uses the Go runtime", goUsed.exists())
            goUsed.delete()
            assertNull("a refused room has no time", runBlocking { manager.ping(line) })
            assertTrue("with the switch off the ping uses the Go runtime", goUsed.exists())
        } finally {
            delegate.set(null, original)
            goUsed.delete()
        }
    }
}
