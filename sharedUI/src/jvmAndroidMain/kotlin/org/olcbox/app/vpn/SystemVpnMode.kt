package org.olcbox.app.vpn

/**
 * What Android says about its own always-on VPN while our service runs.
 *
 * The app holds the tunnel for as long as its process lives; when the process
 * dies the interface goes with it, and only Android can keep traffic in then:
 * "Always-on VPN" with "Block connections without VPN". No API lets an app turn
 * that on, and only a running VpnService can ask whether it is (API 29). So the
 * app reports it, and reports nothing when it has not been able to ask.
 */
data class SystemVpnMode(val alwaysOn: Boolean, val lockdown: Boolean)

enum class SystemKillSwitch {
    /** Not asked: the service is not running, or the phone is older than Android 10. */
    Unknown,
    Off,
    /** Always-on without "Block connections without VPN": the VPN comes back, traffic is not held meanwhile. */
    On,
    /** Always-on with lockdown: Android itself blocks what does not go through the VPN. */
    Blocking
}

fun SystemVpnMode?.killSwitch(): SystemKillSwitch = when {
    this == null -> SystemKillSwitch.Unknown
    !alwaysOn -> SystemKillSwitch.Off
    lockdown -> SystemKillSwitch.Blocking
    else -> SystemKillSwitch.On
}

enum class LockdownNote { None, BypassedAppsOffline, UnselectedAppsOffline }

/**
 * Under lockdown Android cuts off every app that does not use the VPN, and split
 * tunnelling is exactly a list of such apps: the ones excluded, or, when only
 * chosen apps use the VPN, all the others. The app cannot exempt them, so it
 * says which ones have no network.
 */
fun lockdownNote(mode: SystemVpnMode?, proxySelected: Boolean, bypassedApps: Int): LockdownNote = when {
    mode.killSwitch() != SystemKillSwitch.Blocking -> LockdownNote.None
    proxySelected -> LockdownNote.UnselectedAppsOffline
    bypassedApps > 0 -> LockdownNote.BypassedAppsOffline
    else -> LockdownNote.None
}
