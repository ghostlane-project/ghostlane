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
     * A room nobody answers is refused by the engine that joined it (RustCheckE2ETest
     * a1, a2). The carrier `none` reaches the loopback hub only through the Rust core;
     * a Go dial that fails fast joins nothing. The rooms the hub saw name the engine,
     * the time a call took does not. The app's own call passes its context, so the
     * stored switch decides which engine answers.
     */
    @Test
    fun theProductsRoomCheckAndPingFollowTheDeveloperSwitch() {
        val hub = PipeHub()
        val manager = AndroidVpnManager(context)
        fun line(room: String) = LocationConfig(
            id = hub.url(room),
            key = "0123456789abcdef".repeat(4),
            bypassProvider = "none",
        )
        try {
            hub.reset(PipeHub.Room.Ends("export check: nobody here"))
            assertNull("a room with nobody here has no time", runBlocking { manager.checkConnection(line("check")) })
            assertEquals("only the Rust core joins the room", listOf("check", "check"), hub.joined)

            hub.reset(PipeHub.Room.Ends("export ping: nobody here"))
            assertNull("a room with nobody here has no time", runBlocking { manager.ping(line("ping")) })
            assertEquals("only the Rust core joins the room", listOf("ping"), hub.joined)

            runBlocking { context.vpnPrefDataStore.edit { it[RustCore.KEY_DEV_RUST_CORE] = false } }
            hub.reset(PipeHub.Room.Ends("export check with the switch off: unused"))
            runBlocking { manager.checkConnection(line("off-check")) }
            assertEquals("with the switch off the Go runtime answers", emptyList<String>(), hub.joined)
            hub.reset(PipeHub.Room.Ends("export ping with the switch off: unused"))
            runBlocking { manager.ping(line("off-ping")) }
            assertEquals("with the switch off the Go runtime answers", emptyList<String>(), hub.joined)
        } finally {
            hub.stop()
        }
    }
}
