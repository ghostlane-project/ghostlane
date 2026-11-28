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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.log.CorePin
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * What the app's export and its room check do through the product path
 * (AndroidVpnManager): the export is the core's, and holds no file of a Go core.
 */
@RunWith(AndroidJUnit4::class)
class RustExportE2ETest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staleLog = File(File(context.cacheDir, "olcbox-xray"), "xray.log")

    @After
    fun removeStaleLog() {
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
}
