package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemVpnModeTest {
    @Test
    fun theRowSaysOnlyWhatARunningServiceRead() {
        assertEquals(SystemKillSwitch.Unknown, (null as SystemVpnMode?).killSwitch())
        assertEquals(SystemKillSwitch.Off, SystemVpnMode(alwaysOn = false, lockdown = false).killSwitch())
        assertEquals(SystemKillSwitch.On, SystemVpnMode(alwaysOn = true, lockdown = false).killSwitch())
        assertEquals(SystemKillSwitch.Blocking, SystemVpnMode(alwaysOn = true, lockdown = true).killSwitch())
    }

    @Test
    fun underLockdownTheAppsOutsideTheVpnHaveNoNetwork() {
        val blocking = SystemVpnMode(alwaysOn = true, lockdown = true)
        assertEquals(LockdownNote.BypassedAppsOffline, lockdownNote(blocking, proxySelected = false, bypassedApps = 2))
        assertEquals(LockdownNote.UnselectedAppsOffline, lockdownNote(blocking, proxySelected = true, bypassedApps = 0))
        assertEquals(LockdownNote.None, lockdownNote(blocking, proxySelected = false, bypassedApps = 0))
        assertEquals(LockdownNote.None, lockdownNote(SystemVpnMode(true, false), proxySelected = true, bypassedApps = 3))
        assertEquals(LockdownNote.None, lockdownNote(null, proxySelected = true, bypassedApps = 3))
    }
}
