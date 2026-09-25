package org.olcbox.app.desktop

import androidx.compose.runtime.Composable
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.action_close
import multiplatform_app.sharedui.generated.resources.action_copy
import multiplatform_app.sharedui.generated.resources.action_start
import multiplatform_app.sharedui.generated.resources.action_stop
import multiplatform_app.sharedui.generated.resources.connection_mode_proxy
import multiplatform_app.sharedui.generated.resources.connection_system_tunnel
import multiplatform_app.sharedui.generated.resources.copied
import multiplatform_app.sharedui.generated.resources.copied_to_clipboard
import multiplatform_app.sharedui.generated.resources.desktop_mode_install_first
import multiplatform_app.sharedui.generated.resources.desktop_mode_proxy
import multiplatform_app.sharedui.generated.resources.desktop_mode_tunnel_linux
import multiplatform_app.sharedui.generated.resources.desktop_mode_tunnel_mac
import multiplatform_app.sharedui.generated.resources.desktop_mode_tunnel_windows
import multiplatform_app.sharedui.generated.resources.desktop_routing_linux_rooms
import multiplatform_app.sharedui.generated.resources.desktop_routing_unavailable
import multiplatform_app.sharedui.generated.resources.dialog_import_config
import multiplatform_app.sharedui.generated.resources.dialog_save_logs
import multiplatform_app.sharedui.generated.resources.lan_credentials_regenerated
import multiplatform_app.sharedui.generated.resources.lan_disabled
import multiplatform_app.sharedui.generated.resources.lan_enabled
import multiplatform_app.sharedui.generated.resources.lan_select_interface_first
import multiplatform_app.sharedui.generated.resources.location_qr
import multiplatform_app.sharedui.generated.resources.open_in_browser
import multiplatform_app.sharedui.generated.resources.password_regenerated
import multiplatform_app.sharedui.generated.resources.scan_or_copy
import multiplatform_app.sharedui.generated.resources.server_list_added
import multiplatform_app.sharedui.generated.resources.server_list_not_found
import multiplatform_app.sharedui.generated.resources.server_list_qr
import multiplatform_app.sharedui.generated.resources.server_list_removed
import multiplatform_app.sharedui.generated.resources.settings_title
import multiplatform_app.sharedui.generated.resources.socks_saved
import multiplatform_app.sharedui.generated.resources.tray_open
import multiplatform_app.sharedui.generated.resources.tray_quit
import multiplatform_app.sharedui.generated.resources.unknown_error
import multiplatform_app.sharedui.generated.resources.update_already_downloaded
import multiplatform_app.sharedui.generated.resources.update_check_failed
import multiplatform_app.sharedui.generated.resources.update_checking
import multiplatform_app.sharedui.generated.resources.update_download_failed
import multiplatform_app.sharedui.generated.resources.update_downloading
import multiplatform_app.sharedui.generated.resources.update_found_version
import multiplatform_app.sharedui.generated.resources.update_up_to_date
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.vpn.LINUX_TUNNEL_ROOMS_ONLY
import org.olcbox.app.vpn.WINDOWS_TUNNEL_CARRIES_ALL

/**
 * The desktop app's own words. Its module cannot see this one's resources (the
 * generated `Res` is internal), so it names them here and reads them in the
 * user's language: [text] in composition, [load] in a coroutine.
 */
enum class DesktopText(internal val resource: StringResource) {
    UpdateChecking(Res.string.update_checking),
    UpdateAlreadyDownloaded(Res.string.update_already_downloaded),
    UpdateFound(Res.string.update_found_version),
    UpToDate(Res.string.update_up_to_date),
    UpdateCheckFailed(Res.string.update_check_failed),
    Downloading(Res.string.update_downloading),
    DownloadFailed(Res.string.update_download_failed),
    UnknownError(Res.string.unknown_error),
    Open(Res.string.tray_open),
    Start(Res.string.action_start),
    Stop(Res.string.action_stop),
    Settings(Res.string.settings_title),
    Quit(Res.string.tray_quit),
    ServerListAdded(Res.string.server_list_added),
    LocationQr(Res.string.location_qr),
    ServerListQr(Res.string.server_list_qr),
    OpenInBrowser(Res.string.open_in_browser),
    ServerListRemoved(Res.string.server_list_removed),
    ServerListNotFound(Res.string.server_list_not_found),
    SocksSaved(Res.string.socks_saved),
    LanEnabled(Res.string.lan_enabled),
    LanSelectInterfaceFirst(Res.string.lan_select_interface_first),
    LanDisabled(Res.string.lan_disabled),
    LanCredentialsRegenerated(Res.string.lan_credentials_regenerated),
    PasswordRegenerated(Res.string.password_regenerated),
    Copied(Res.string.copied),
    CopiedToClipboard(Res.string.copied_to_clipboard),
    ScanOrCopy(Res.string.scan_or_copy),
    Close(Res.string.action_close),
    Copy(Res.string.action_copy),
    ImportConfigDialog(Res.string.dialog_import_config),
    SaveLogsDialog(Res.string.dialog_save_logs)
}

/** [this] in the user's language, with [args] in its `%1$s`… places. */
@Composable
fun DesktopText.text(vararg args: Any): String = stringResource(resource, *args)

/** The same, from a coroutine. */
suspend fun DesktopText.load(vararg args: Any): String = getString(resource, *args)

/**
 * A connection-mode option's text, or a routing note, in the user's language.
 * They are English in [org.olcbox.app.vpn.DesktopConnectionModePreference], where
 * they are also the identity; anything else is shown as it is.
 */
@Composable
fun localizedDesktopText(text: String): String = DESKTOP_MODE_TEXTS[text]?.let { stringResource(it) } ?: text

internal val DESKTOP_MODE_TEXTS: Map<String, StringResource> = mapOf(
    "System-wide tunnel" to Res.string.connection_system_tunnel,
    "Every app on this Mac, through a utun" to Res.string.desktop_mode_tunnel_mac,
    "Install the system-wide tunnel below first" to Res.string.desktop_mode_install_first,
    "Proxy" to Res.string.connection_mode_proxy,
    "Local SOCKS5 proxy — only apps that follow the system proxy" to Res.string.desktop_mode_proxy,
    "Every app on this PC — Ghostlane restarts as administrator" to Res.string.desktop_mode_tunnel_windows,
    "Every app on this machine, through a TUN device" to Res.string.desktop_mode_tunnel_linux,
    WINDOWS_TUNNEL_CARRIES_ALL to Res.string.desktop_routing_unavailable,
    LINUX_TUNNEL_ROOMS_ONLY to Res.string.desktop_routing_linux_rooms
)
