package org.olcbox.app.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

/** The head names a core only when the manager says its session ran the Rust core. */
class LogsExportWiringTest {
    private class Fake(private val pin: String?, private val diag: String = "") : VpnManager {
        override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override val connectedSince = MutableStateFlow<Long?>(null)
        override fun needsPermission(): Boolean = false
        override fun startVpn() {}
        override fun stopVpn() {}
        override suspend fun ping(locationConfig: LocationConfig): Long? = null
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
        override suspend fun corePin(): String? = pin
        override suspend fun diagnosticsLog(): String = diag
    }

    @Test fun aManagerWithNoRustSessionLeavesTheCoreOutOfTheHead() {
        val out = runBlocking { LogsExport.build(Fake(null), listOf("one")) }
        assertFalse(out.lines().any { it.startsWith("Core:") }, out)
    }

    @Test fun aManagerThatRanRustNamesItsPin() {
        val out = runBlocking { LogsExport.build(Fake(CorePin.COMMIT), listOf("one")) }
        assertEquals("Core: ghostlane-core ${CorePin.COMMIT.take(12)}", out.lines()[1])
    }

    @Test fun theDefaultManagerRunsNoRustCore() {
        // The interface default, not an override, is what a manager that says nothing gets.
        val bare = object : VpnManager {
            override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
            override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
            override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
            override val connectedSince = MutableStateFlow<Long?>(null)
            override fun needsPermission(): Boolean = false
            override fun startVpn() {}
            override fun stopVpn() {}
            override suspend fun ping(locationConfig: LocationConfig): Long? = null
            override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
        }
        assertTrue(runBlocking { bare.corePin() } == null)
    }
}
