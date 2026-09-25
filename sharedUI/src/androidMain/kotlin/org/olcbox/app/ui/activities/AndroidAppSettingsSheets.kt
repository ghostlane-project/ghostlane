package org.olcbox.app.ui.activities

import androidx.compose.foundation.layout.FlowRow
import multiplatform_app.sharedui.generated.resources.router_export
import org.olcbox.app.ui.components.offersRouterExport
import multiplatform_app.sharedui.generated.resources.action_cancel
import multiplatform_app.sharedui.generated.resources.action_check_now
import multiplatform_app.sharedui.generated.resources.action_qr_share
import multiplatform_app.sharedui.generated.resources.action_refresh
import multiplatform_app.sharedui.generated.resources.action_remove
import multiplatform_app.sharedui.generated.resources.action_save
import multiplatform_app.sharedui.generated.resources.action_share
import multiplatform_app.sharedui.generated.resources.always_on_vpn
import multiplatform_app.sharedui.generated.resources.always_on_vpn_value
import multiplatform_app.sharedui.generated.resources.app_logs_title
import multiplatform_app.sharedui.generated.resources.apps_count
import multiplatform_app.sharedui.generated.resources.apps_no_match
import multiplatform_app.sharedui.generated.resources.apps_no_match_hint
import multiplatform_app.sharedui.generated.resources.apps_none_found
import multiplatform_app.sharedui.generated.resources.apps_none_found_hint
import multiplatform_app.sharedui.generated.resources.connection_mode
import multiplatform_app.sharedui.generated.resources.connection_mode_local_socks
import multiplatform_app.sharedui.generated.resources.connection_mode_proxy
import multiplatform_app.sharedui.generated.resources.connection_settings_android_value
import multiplatform_app.sharedui.generated.resources.connection_settings_title
import multiplatform_app.sharedui.generated.resources.default_interval
import multiplatform_app.sharedui.generated.resources.every_hours
import multiplatform_app.sharedui.generated.resources.field_generated_password
import multiplatform_app.sharedui.generated.resources.field_listen_address
import multiplatform_app.sharedui.generated.resources.field_password
import multiplatform_app.sharedui.generated.resources.field_port
import multiplatform_app.sharedui.generated.resources.field_username
import multiplatform_app.sharedui.generated.resources.hours_short
import multiplatform_app.sharedui.generated.resources.https_sources
import multiplatform_app.sharedui.generated.resources.https_sources_none
import multiplatform_app.sharedui.generated.resources.lan_endpoint
import multiplatform_app.sharedui.generated.resources.last_refresh_at
import multiplatform_app.sharedui.generated.resources.listen_address_required
import multiplatform_app.sharedui.generated.resources.locations_count
import multiplatform_app.sharedui.generated.resources.logs_entries
import multiplatform_app.sharedui.generated.resources.logs_no_entries
import multiplatform_app.sharedui.generated.resources.mode_full_tunnel
import multiplatform_app.sharedui.generated.resources.mode_proxy_description
import multiplatform_app.sharedui.generated.resources.mode_proxy_summary
import multiplatform_app.sharedui.generated.resources.mode_tun_description
import multiplatform_app.sharedui.generated.resources.mode_tun_summary
import multiplatform_app.sharedui.generated.resources.no_server_lists
import multiplatform_app.sharedui.generated.resources.no_server_lists_hint
import multiplatform_app.sharedui.generated.resources.not_refreshed_yet
import multiplatform_app.sharedui.generated.resources.password_required
import multiplatform_app.sharedui.generated.resources.port_range_hint
import multiplatform_app.sharedui.generated.resources.port_required
import multiplatform_app.sharedui.generated.resources.proxy_credentials_section
import multiplatform_app.sharedui.generated.resources.regenerate_password
import multiplatform_app.sharedui.generated.resources.remove_server_list_question
import multiplatform_app.sharedui.generated.resources.remove_server_list_text
import multiplatform_app.sharedui.generated.resources.ru_bypass_accuracy
import multiplatform_app.sharedui.generated.resources.ru_bypass_already
import multiplatform_app.sharedui.generated.resources.ru_bypass_apps
import multiplatform_app.sharedui.generated.resources.ru_bypass_auto
import multiplatform_app.sharedui.generated.resources.ru_bypass_auto_manual
import multiplatform_app.sharedui.generated.resources.ru_bypass_matched
import multiplatform_app.sharedui.generated.resources.ru_bypass_no_match
import multiplatform_app.sharedui.generated.resources.ru_bypass_none_selected
import multiplatform_app.sharedui.generated.resources.ru_bypass_on
import multiplatform_app.sharedui.generated.resources.saving_restarts_connection
import multiplatform_app.sharedui.generated.resources.search_apps
import multiplatform_app.sharedui.generated.resources.server_lists_sharing_title
import multiplatform_app.sharedui.generated.resources.settings_app_log_value
import multiplatform_app.sharedui.generated.resources.settings_app_updates_value
import multiplatform_app.sharedui.generated.resources.settings_routing
import multiplatform_app.sharedui.generated.resources.settings_server_list_updates
import multiplatform_app.sharedui.generated.resources.settings_server_lists
import multiplatform_app.sharedui.generated.resources.settings_title
import multiplatform_app.sharedui.generated.resources.show_system_apps
import multiplatform_app.sharedui.generated.resources.socks_proxy
import multiplatform_app.sharedui.generated.resources.split_all_apps
import multiplatform_app.sharedui.generated.resources.split_all_use
import multiplatform_app.sharedui.generated.resources.split_app_list
import multiplatform_app.sharedui.generated.resources.split_applies_on_close
import multiplatform_app.sharedui.generated.resources.split_apps_using
import multiplatform_app.sharedui.generated.resources.split_bypass_selected_apps
import multiplatform_app.sharedui.generated.resources.split_bypassed_apps
import multiplatform_app.sharedui.generated.resources.split_choose_bypassing
import multiplatform_app.sharedui.generated.resources.split_choose_using
import multiplatform_app.sharedui.generated.resources.split_every_app_uses
import multiplatform_app.sharedui.generated.resources.split_mode_all
import multiplatform_app.sharedui.generated.resources.split_mode_bypass
import multiplatform_app.sharedui.generated.resources.split_mode_selected
import multiplatform_app.sharedui.generated.resources.split_n_bypass
import multiplatform_app.sharedui.generated.resources.split_n_bypassed
import multiplatform_app.sharedui.generated.resources.split_n_use
import multiplatform_app.sharedui.generated.resources.split_no_bypassed
import multiplatform_app.sharedui.generated.resources.split_no_list_needed
import multiplatform_app.sharedui.generated.resources.split_none_bypass
import multiplatform_app.sharedui.generated.resources.split_none_selected
import multiplatform_app.sharedui.generated.resources.split_only_n
import multiplatform_app.sharedui.generated.resources.split_only_n_use
import multiplatform_app.sharedui.generated.resources.split_required
import multiplatform_app.sharedui.generated.resources.split_routing_behavior
import multiplatform_app.sharedui.generated.resources.split_same_route
import multiplatform_app.sharedui.generated.resources.split_saved_for_tun
import multiplatform_app.sharedui.generated.resources.split_selected_only
import multiplatform_app.sharedui.generated.resources.split_tun_rule
import multiplatform_app.sharedui.generated.resources.split_tunneling_title
import multiplatform_app.sharedui.generated.resources.system_apps_hidden
import multiplatform_app.sharedui.generated.resources.system_apps_included
import multiplatform_app.sharedui.generated.resources.system_apps_none
import multiplatform_app.sharedui.generated.resources.unsaved_change
import multiplatform_app.sharedui.generated.resources.update_settings
import multiplatform_app.sharedui.generated.resources.updates_check_interval
import multiplatform_app.sharedui.generated.resources.updates_current_version
import multiplatform_app.sharedui.generated.resources.updates_last_check
import multiplatform_app.sharedui.generated.resources.updates_not_checked
import multiplatform_app.sharedui.generated.resources.updates_title
import multiplatform_app.sharedui.generated.resources.username_required
import org.jetbrains.compose.resources.pluralStringResource
import org.olcbox.app.ui.components.localizedHubSummary
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.encrypted_link
import multiplatform_app.sharedui.generated.resources.Res
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.ui.components.kit.PkScreenHeader
import org.olcbox.app.ui.components.kit.PkSwitch
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.ui.components.kit.pkSubscriptionSourceLine
import org.olcbox.app.ui.components.kit.PkSectionLabel
import org.olcbox.app.ui.components.kit.pkVersionLine
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidInstalledApp
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelList
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.ui.components.SubscriptionSettingsScreen
import org.olcbox.app.ui.components.RoutingSettingsScreen
import org.olcbox.app.ui.components.hubSummary
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.AndroidSplitTunnelSettings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(
    initialRoute: AppSettingsInitialRoute = AppSettingsInitialRoute.Hub,
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    logs: List<String>,
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    /**
     * False where the store owns updates (the `play` flavor has no
     * AppUpdateService): the hub then draws no Update Settings row, the way
     * the App Store build already behaves through ApplicationSettingsSheet.
     */
    showUpdates: Boolean = true,
    subscriptions: List<SubscriptionShareItem>,
    subscriptionSettings: SubscriptionSettings = SubscriptionSettings(),
    onSubscriptionSettingsChanged: (SubscriptionSettings) -> Unit = {},
    routingSettings: RoutingSettings = RoutingSettings(),
    onRoutingSettingsChanged: (RoutingSettings) -> Unit = {},
    enabled: Boolean,
    isConnectionActive: Boolean,
    onDismiss: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRouterClick: ((String) -> Unit)? = null,
    onSubscriptionRefreshClick: (String) -> Unit,
    onSubscriptionDeleteClick: (String) -> Unit = {},
    onModeSelected: (AndroidConnectionMode) -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit,
    onSplitTunnelModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onSplitTunnelAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onSplitTunnelAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var route by remember(initialRoute) { mutableStateOf(initialRoute.toRoute()) }
    var autoBypassPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var russianBypassPresetEnabled by remember { mutableStateOf(false) }

    // Still a coroutine, still ordered: several call sites hand it work to run
    // after the screen is gone, and doing that on the spot would run it under a
    // screen that is still on top.
    fun closeSheet(afterClose: () -> Unit = {}) {
        scope.launch {
            onDismiss()
            afterClose()
        }
    }

    BackHandler {
        route = when (route) {
            AppSettingsRoute.Hub -> {
                closeSheet()
                AppSettingsRoute.Hub
            }

            is AppSettingsRoute.AppList -> AppSettingsRoute.SplitTunneling
            AppSettingsRoute.ConnectionMode,
            AppSettingsRoute.SocksProxy,
            AppSettingsRoute.Routing,
            AppSettingsRoute.SplitTunneling -> AppSettingsRoute.ConnectionSettings

            else -> AppSettingsRoute.Hub
        }
    }

    // A screen, not a sheet — the same move the shared settings screen made, for
    // the same reason: this is the longest content in the app and its natural
    // gesture was "flick away". Android's settings are their own file because
    // split tunnelling, the app list and the connection modes exist only here.
    Surface(
        modifier = Modifier.fillMaxSize().then(pkScreenBackground()),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // The 32dp every route pads its own bottom with is not the home
                // indicator, and on an iPhone it is four points short of it.
                .navigationBarsPadding()
        ) {
            if (route == AppSettingsRoute.Hub) {
                PkScreenHeader(title = stringResource(Res.string.settings_title), onBack = { closeSheet() })
            }
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 180,
                            delayMillis = 60,
                            easing = LinearOutSlowInEasing
                        )
                    ).togetherWith(
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 90,
                                easing = FastOutLinearInEasing
                            )
                        )
                    ).using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                tween(
                                    durationMillis = 320,
                                    easing = FastOutSlowInEasing
                                )
                            }
                        )
                    )
                },
                label = "appSettingsRoute"
            ) { currentRoute ->
                when (currentRoute) {
                    AppSettingsRoute.SubscriptionOptions -> SubscriptionSettingsScreen(
                        settings = subscriptionSettings,
                        onChanged = onSubscriptionSettingsChanged,
                        onBack = { route = AppSettingsRoute.Hub }
                    )

                    AppSettingsRoute.Hub -> AppSettingsHubContent(
                        selectedMode = selectedMode,
                        updateSettings = updateSettings,
                        showUpdates = showUpdates,
                        subscriptionsCount = subscriptions.size,
                        enabled = enabled,
                        onConnectionSettingsClick = { route = AppSettingsRoute.ConnectionSettings },
                        onSubscriptionsSharingClick = { route = AppSettingsRoute.SubscriptionsSharing },
                        onSubscriptionOptionsClick = { route = AppSettingsRoute.SubscriptionOptions },
                        subscriptionSettings = subscriptionSettings,
                        onUpdatesClick = { route = AppSettingsRoute.Updates },
                        onApplicationLogsClick = { route = AppSettingsRoute.ApplicationLogs }
                    )

                    AppSettingsRoute.ConnectionSettings -> ConnectionSettingsContent(
                        selectedMode = selectedMode,
                        proxySettings = proxySettings,
                        splitTunnelSettings = splitTunnelSettings,
                        enabled = enabled,
                        onBack = { route = AppSettingsRoute.Hub },
                        onConnectionModeClick = { route = AppSettingsRoute.ConnectionMode },
                        onProxySettingsClick = { route = AppSettingsRoute.SocksProxy },
                        routingSettings = routingSettings,
                        onRoutingClick = { route = AppSettingsRoute.Routing },
                        onSplitTunnelingClick = { route = AppSettingsRoute.SplitTunneling }
                    )

                    AppSettingsRoute.ConnectionMode -> ConnectionModeSettingsContent(
                        selectedMode = selectedMode,
                        enabled = enabled,
                        onBack = { route = AppSettingsRoute.ConnectionSettings },
                        onModeSelected = onModeSelected
                    )

                    AppSettingsRoute.SocksProxy -> SocksProxySettingsContent(
                        proxySettings = proxySettings,
                        enabled = enabled,
                        isConnectionActive = isConnectionActive,
                        onBack = { route = AppSettingsRoute.ConnectionSettings },
                        onProxySettingsSaved = onProxySettingsSaved,
                        onProxyPasswordRegenerated = onProxyPasswordRegenerated
                    )

                    AppSettingsRoute.Routing -> RoutingSettingsScreen(
                        settings = routingSettings,
                        enabled = enabled,
                        onBack = { route = AppSettingsRoute.ConnectionSettings },
                        onChanged = onRoutingSettingsChanged
                    )

                    AppSettingsRoute.SplitTunneling -> SplitTunnelingSettingsContent(
                        settings = splitTunnelSettings,
                        enabled = enabled,
                        isConnectionActive = isConnectionActive,
                        selectedMode = selectedMode,
                        onBack = { route = AppSettingsRoute.ConnectionSettings },
                        onModeSelected = onSplitTunnelModeSelected,
                        onAppListClick = { list -> route = AppSettingsRoute.AppList(list) }
                    )

                    is AppSettingsRoute.AppList -> SplitTunnelingAppListContent(
                        list = currentRoute.list,
                        settings = splitTunnelSettings,
                        installedApps = installedApps,
                        enabled = enabled,
                        onBack = { route = AppSettingsRoute.SplitTunneling },
                        onAppToggled = onSplitTunnelAppToggled,
                        onAppsSelected = onSplitTunnelAppsSelected,
                        autoBypassPackages = autoBypassPackages,
                        onAutoBypassPackagesChanged = { autoBypassPackages = it },
                        russianBypassPresetEnabled = russianBypassPresetEnabled,
                        onRussianBypassPresetEnabledChanged = { russianBypassPresetEnabled = it }
                    )

                    AppSettingsRoute.ApplicationLogs -> ApplicationLogsSettingsContent(
                        logs = logs,
                        onBack = { route = AppSettingsRoute.Hub },
                        onSaveClick = onSaveLogsClick,
                        onShareClick = onShareLogsClick
                    )

                    AppSettingsRoute.SubscriptionsSharing -> SubscriptionsSharingSettingsContent(
                        subscriptions = subscriptions,
                        onBack = { route = AppSettingsRoute.Hub },
                        onShareClick = onSubscriptionShareClick,
                        onRouterClick = onSubscriptionRouterClick,
                        onRefreshClick = onSubscriptionRefreshClick,
                        onDeleteClick = onSubscriptionDeleteClick
                    )

                    AppSettingsRoute.Updates -> UpdatesSettingsContent(
                        settings = updateSettings,
                        statusText = updateStatusText,
                        downloadProgress = updateDownloadProgress,
                        onBack = { route = AppSettingsRoute.Hub },
                        onIntervalSelected = onUpdateIntervalSelected,
                        onCheckUpdatesClick = onCheckUpdatesClick
                    )
                }
            }
        }
    }
}

internal enum class AppSettingsInitialRoute {
    Hub,
    SplitTunneling
}

@Composable
private fun AppSettingsHubContent(
    selectedMode: AndroidConnectionMode,
    updateSettings: AppUpdateSettings,
    showUpdates: Boolean,
    subscriptionsCount: Int,
    subscriptionSettings: SubscriptionSettings,
    enabled: Boolean,
    onConnectionSettingsClick: () -> Unit,
    onSubscriptionsSharingClick: () -> Unit,
    onSubscriptionOptionsClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onApplicationLogsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsNavigationRow(
            title = stringResource(Res.string.connection_settings_title),
            value = stringResource(Res.string.connection_settings_android_value),
            icon = selectedMode.icon(),
            enabled = enabled,
            onClick = onConnectionSettingsClick
        )

        SettingsNavigationRow(
            title = stringResource(Res.string.settings_server_list_updates),
            value = subscriptionSettings.hubSummary(),
            icon = Icons.Outlined.Refresh,
            enabled = enabled,
            onClick = onSubscriptionOptionsClick
        )

        SettingsNavigationRow(
            title = stringResource(Res.string.server_lists_sharing_title),
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            enabled = true,
            onClick = onSubscriptionsSharingClick
        )

        if (showUpdates) {
            SettingsNavigationRow(
                title = stringResource(Res.string.update_settings),
                value = stringResource(Res.string.settings_app_updates_value, updateSettings.intervalHours),
                icon = Icons.Outlined.Refresh,
                enabled = true,
                onClick = onUpdatesClick
            )
        }

        SettingsNavigationRow(
            title = stringResource(Res.string.app_logs_title),
            value = stringResource(Res.string.settings_app_log_value),
            icon = PkIcons.History,
            enabled = true,
            onClick = onApplicationLogsClick
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = pkVersionLine(CurrentAppInfo.value),
                style = MaterialTheme.typography.labelSmall,
                color = LocalPkPalette.current.textMuted
            )
        }
    }
}

@Composable
private fun ConnectionSettingsContent(
    selectedMode: AndroidConnectionMode,
    proxySettings: AndroidSocksProxySettings,
    splitTunnelSettings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    onBack: () -> Unit,
    onConnectionModeClick: () -> Unit,
    onProxySettingsClick: () -> Unit,
    routingSettings: RoutingSettings,
    onRoutingClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.connection_settings_title),
            subtitle = selectedMode.settingsSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsNavigationRow(
                title = stringResource(Res.string.connection_mode),
                value = selectedMode.settingsSummary(),
                icon = selectedMode.icon(),
                enabled = enabled,
                onClick = onConnectionModeClick
            )
            // Android's own always-on: the system starts the VPN at boot and
            // brings it back when it drops, and "Block connections without VPN"
            // sits beside it. Both are the system's to set, on its VPN screen.
            val context = LocalContext.current
            SettingsNavigationRow(
                title = stringResource(Res.string.always_on_vpn),
                value = stringResource(Res.string.always_on_vpn_value),
                icon = PkIcons.PowerSettingsNew,
                enabled = enabled,
                onClick = { openSystemVpnSettings(context) }
            )
            // Editing the local proxy credentials/port is plumbing: admin-only.
            if (AdminState.configuratorVisible) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.socks_proxy),
                    value = "${proxySettings.host}:${proxySettings.port}",
                    icon = PkIcons.Public,
                    enabled = enabled,
                    onClick = onProxySettingsClick
                )
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_routing),
                value = routingSettings.mode.localizedHubSummary(),
                icon = PkIcons.SwapVert,
                enabled = enabled,
                onClick = onRoutingClick
            )
            SettingsNavigationRow(
                title = stringResource(Res.string.split_tunneling_title),
                value = splitTunnelSettings.settingsSummary(),
                icon = PkIcons.Apps,
                enabled = enabled,
                onClick = onSplitTunnelingClick
            )
        }
    }
}

@Composable
private fun ConnectionModeSettingsContent(
    selectedMode: AndroidConnectionMode,
    enabled: Boolean,
    onBack: () -> Unit,
    onModeSelected: (AndroidConnectionMode) -> Unit
) {
    val options = listOf(AndroidConnectionMode.Tun, AndroidConnectionMode.Proxy)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.connection_mode),
            subtitle = selectedMode.subtitle(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { mode ->
                ConnectionModeOption(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }
    }
}

@Composable
private fun SocksProxySettingsContent(
    proxySettings: AndroidSocksProxySettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit
) {
    var editedHost by remember(proxySettings.host) { mutableStateOf(proxySettings.host) }
    var editedPort by remember(proxySettings.port) { mutableStateOf(proxySettings.port.toString()) }
    var editedUsername by remember(proxySettings.username) { mutableStateOf(proxySettings.username) }
    var editedPassword by remember(proxySettings.password) { mutableStateOf(proxySettings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && AndroidSocksProxySettings.isValidPort(parsedPort)
    val hostChanged = editedHost != proxySettings.host
    val portChanged = parsedPort != null && parsedPort != proxySettings.port
    val usernameChanged = editedUsername != proxySettings.username
    val passwordChanged = editedPassword != proxySettings.password
    val settingsChanged = hostChanged || portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged &&
            enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.socks_proxy),
            subtitle = proxySettings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        SocksProxySettingsForm(
            host = editedHost,
            port = editedPort,
            username = editedUsername,
            password = editedPassword,
            hostValid = hostValid,
            portValid = portValid,
            hostChanged = hostChanged,
            portChanged = portChanged,
            usernameChanged = usernameChanged,
            passwordChanged = passwordChanged,
            canSave = canSave,
            enabled = enabled,
            isConnectionActive = isConnectionActive,
            onHostChanged = { value ->
                editedHost = value
                    .replace("\r", "")
                    .replace("\n", "")
                    .take(AndroidSocksProxySettings.MAX_HOST_LENGTH)
            },
            onPortChanged = { value ->
                editedPort = value.filter { it.isDigit() }.take(MAX_PROXY_PORT_LENGTH)
            },
            onUsernameChanged = { value -> editedUsername = value.take(MAX_PROXY_USERNAME_LENGTH) },
            onPasswordChanged = { value -> editedPassword = value.take(MAX_PROXY_PASSWORD_LENGTH) },
            onSaveSettings = {
                onProxySettingsSaved(
                    editedHost,
                    editedUsername,
                    editedPassword,
                    parsedPort ?: proxySettings.port
                )
            },
            onRegeneratePassword = onProxyPasswordRegenerated
        )
    }
}

@Composable
private fun SplitTunnelingSettingsContent(
    settings: AndroidSplitTunnelSettings,
    enabled: Boolean,
    isConnectionActive: Boolean,
    selectedMode: AndroidConnectionMode,
    onBack: () -> Unit,
    onModeSelected: (AndroidSplitTunnelMode) -> Unit,
    onAppListClick: (AndroidSplitTunnelList) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.split_tunneling_title),
            subtitle = settings.mode.statusTitle(settings),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SplitTunnelStatusCard(
            settings = settings,
            selectedMode = selectedMode,
            isConnectionActive = isConnectionActive
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(stringResource(Res.string.split_routing_behavior))

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AndroidSplitTunnelMode.entries.forEach { mode ->
                SplitTunnelModeOption(
                    mode = mode,
                    settings = settings,
                    selected = settings.mode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (settings.mode) {
            AndroidSplitTunnelMode.AllApps -> SplitTunnelNoListCard()
            AndroidSplitTunnelMode.ProxySelected -> SplitTunnelAppListAction(
                title = stringResource(Res.string.split_apps_using),
                value = settings.proxyPackages.activeListValue(requireSelection = true),
                icon = PkIcons.Shield,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Proxy) }
            )

            AndroidSplitTunnelMode.BypassSelected -> SplitTunnelAppListAction(
                title = stringResource(Res.string.split_bypassed_apps),
                value = settings.bypassPackages.activeListValue(requireSelection = false),
                icon = PkIcons.Apps,
                enabled = enabled,
                onClick = { onAppListClick(AndroidSplitTunnelList.Bypass) }
            )
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitTunnelingAppListContent(
    list: AndroidSplitTunnelList,
    settings: AndroidSplitTunnelSettings,
    installedApps: List<AndroidInstalledApp>,
    enabled: Boolean,
    onBack: () -> Unit,
    onAppToggled: (AndroidSplitTunnelList, String) -> Unit,
    onAppsSelected: (AndroidSplitTunnelList, Set<String>) -> Unit,
    autoBypassPackages: Set<String>,
    onAutoBypassPackagesChanged: (Set<String>) -> Unit,
    russianBypassPresetEnabled: Boolean,
    onRussianBypassPresetEnabledChanged: (Boolean) -> Unit
) {
    var query by remember(list) { mutableStateOf("") }
    var showSystemApps by remember(list) { mutableStateOf(false) }

    // Не просто scrollToItem(0): keyed LazyColumn может успеть сохранить старый visible key
    // и слегка увести список к элементу, который переехал. Для bulk/search-сценариев
    // пересоздаём LazyListState, чтобы список гарантированно начинался сверху.
    var listStateResetVersion by remember(list) { mutableStateOf(0) }
    val listScrollState = key(listStateResetVersion) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current

    fun resetListScrollToTop() {
        listStateResetVersion += 1
    }

    val selectedPackages = settings.packagesFor(list)

    val russianBypassPackages = remember(installedApps) {
        installedApps
            .map { it.packageName }
            .filter { it.matchesRussianBypassPackage() }
            .toSet()
    }

    val russianBypassActive = list == AndroidSplitTunnelList.Bypass &&
            russianBypassPresetEnabled &&
            russianBypassPackages.isNotEmpty()

    val activeAutoBypassPackages = autoBypassPackages
        .intersect(russianBypassPackages)
        .intersect(selectedPackages)

    val selectedRussianBypassPackagesCount = selectedPackages
        .count { it in russianBypassPackages }

    // Это snapshot порядка, а не всегда актуальное состояние selection.
    // Ручной toggle не должен мгновенно двигать строку вверх/вниз — иначе UX ощущается как jump.
    var sortAutoBypassPackages by remember(list) {
        mutableStateOf(activeAutoBypassPackages)
    }

    var sortSelectedPackages by remember(list) {
        mutableStateOf(selectedPackages)
    }

    val normalizedQuery = query.trim().lowercase()
    val appListEntries = remember(installedApps) {
        installedApps.map { app ->
            AndroidAppListEntry(
                app = app,
                labelSortKey = app.label.lowercase(),
                packageSortKey = app.packageName.lowercase()
            )
        }
    }
    val systemAppsCount = remember(installedApps) {
        installedApps.count { it.isSystem }
    }
    val filteredApps = remember(
        appListEntries,
        normalizedQuery,
        selectedPackages,
        showSystemApps,
        sortSelectedPackages,
        sortAutoBypassPackages
    ) {
        appListEntries
            .asSequence()
            .filter { entry ->
                showSystemApps ||
                        !entry.app.isSystem ||
                        entry.app.packageName in selectedPackages
            }
            .filter { entry ->
                normalizedQuery.isBlank() ||
                        entry.labelSortKey.contains(normalizedQuery) ||
                        entry.packageSortKey.contains(normalizedQuery)
            }
            .sortedWith(
                compareBy<AndroidAppListEntry> {
                    when (it.app.packageName) {
                        in sortAutoBypassPackages -> 0
                        in sortSelectedPackages -> 1
                        else -> 2
                    }
                }.thenBy { it.labelSortKey }.thenBy { it.packageSortKey }
            )
            .map { it.app }
            .toList()
    }

    @Composable
    fun showSystemAppsValue(): String {
        return if (systemAppsCount == 0) {
            stringResource(Res.string.system_apps_none)
        } else if (showSystemApps) {
            stringResource(Res.string.system_apps_included, appCount(systemAppsCount))
        } else {
            stringResource(Res.string.system_apps_hidden, appCount(systemAppsCount))
        }
    }

    LaunchedEffect(list, russianBypassPackages, autoBypassPackages, russianBypassPresetEnabled) {
        if (list == AndroidSplitTunnelList.Bypass) {
            val cleanedAutoPackages = autoBypassPackages.intersect(russianBypassPackages)

            if (cleanedAutoPackages != autoBypassPackages) {
                onAutoBypassPackagesChanged(cleanedAutoPackages)
                sortAutoBypassPackages = sortAutoBypassPackages.intersect(cleanedAutoPackages)
            }

            if (russianBypassPackages.isEmpty() && russianBypassPresetEnabled) {
                onRussianBypassPresetEnabledChanged(false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = list.title(),
            subtitle = if (list == AndroidSplitTunnelList.Bypass && russianBypassActive) {
                stringResource(Res.string.ru_bypass_accuracy)
            } else {
                list.selectionSubtitle(selectedPackages.size)
            },
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                if (value != query) {
                    resetListScrollToTop()
                    query = value
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            label = { Text(stringResource(Res.string.search_apps)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        if (systemAppsCount > 0) {
            Spacer(Modifier.height(10.dp))

            SettingsSwitchRow(
                title = stringResource(Res.string.show_system_apps),
                value = showSystemAppsValue(),
                icon = Icons.Outlined.Settings,
                checked = showSystemApps,
                enabled = enabled,
                onCheckedChange = { checked ->
                    focusManager.clearFocus()
                    showSystemApps = checked
                    resetListScrollToTop()
                }
            )
        }

        if (list == AndroidSplitTunnelList.Bypass) {
            Spacer(Modifier.height(10.dp))

            RussianBypassPresetChips(
                active = russianBypassActive,
                enabled = enabled && russianBypassPackages.isNotEmpty(),
                value = russianBypassPackages.russianBypassPresetValue(
                    autoCount = activeAutoBypassPackages.size,
                    selectedMatchedCount = selectedRussianBypassPackagesCount,
                    presetActive = russianBypassActive
                ),
                onClick = {
                    focusManager.clearFocus()

                    val activatingRussianBypass = !russianBypassActive

                    val nextAutoPackages = if (activatingRussianBypass) {
                        russianBypassPackages - selectedPackages
                    } else {
                        emptySet()
                    }

                    val nextPackages = if (activatingRussianBypass) {
                        selectedPackages + nextAutoPackages
                    } else {
                        selectedPackages - activeAutoBypassPackages
                    }

                    onRussianBypassPresetEnabledChanged(activatingRussianBypass)
                    onAutoBypassPackagesChanged(nextAutoPackages)

                    sortAutoBypassPackages = nextAutoPackages
                    sortSelectedPackages = nextPackages

                    query = ""
                    resetListScrollToTop()

                    onAppsSelected(list, nextPackages)
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            EmptyAppsState(
                title = stringResource(if (installedApps.isEmpty()) Res.string.apps_none_found else Res.string.apps_no_match),
                subtitle = stringResource(
                    if (installedApps.isEmpty()) Res.string.apps_none_found_hint else Res.string.apps_no_match_hint
                )
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listScrollState,
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { app -> app.packageName }
                ) { app ->
                    val packageName = app.packageName
                    val selected = packageName in selectedPackages
                    val autoAdded = list == AndroidSplitTunnelList.Bypass &&
                            packageName in activeAutoBypassPackages

                    val russianPresetMatched = list == AndroidSplitTunnelList.Bypass &&
                            russianBypassActive &&
                            selected &&
                            packageName in russianBypassPackages

                    SplitTunnelAppRow(
                        app = app,
                        selected = selected,
                        autoSelected = russianPresetMatched,
                        enabled = enabled,
                        onClick = {
                            focusManager.clearFocus()

                            val removing = packageName in selectedPackages
                            val nextSelectedPackages = if (removing) {
                                selectedPackages - packageName
                            } else {
                                selectedPackages + packageName
                            }
                            val nextSelectedRussianPackages = nextSelectedPackages.intersect(russianBypassPackages)

                            val nextAutoPackages = when {
                                list == AndroidSplitTunnelList.Bypass &&
                                        russianBypassActive &&
                                        nextSelectedRussianPackages.isEmpty() -> {
                                    emptySet()
                                }

                                autoAdded -> autoBypassPackages - packageName

                                !removing &&
                                        russianBypassActive &&
                                        packageName in russianBypassPackages -> {
                                    autoBypassPackages + packageName
                                }

                                else -> autoBypassPackages
                            }

                            if (list == AndroidSplitTunnelList.Bypass &&
                                russianBypassActive &&
                                nextSelectedRussianPackages.isEmpty()
                            ) {
                                onRussianBypassPresetEnabledChanged(false)
                            }

                            if (nextAutoPackages != autoBypassPackages) {
                                onAutoBypassPackagesChanged(nextAutoPackages)
                            }

                            // Важно: не меняем sortSelectedPackages/sortAutoBypassPackages на одиночный toggle.
                            // Иначе строка сразу переезжает между группами, а LazyColumn сохраняет её key
                            // как first visible item — отсюда микроскролл к package, который пользователь убрал.
                            onAppToggled(list, packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationLogsSettingsContent(
    logs: List<String>,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsDetailHeader(
                title = stringResource(Res.string.app_logs_title),
                subtitle = if (logs.isEmpty()) stringResource(Res.string.logs_no_entries) else pluralStringResource(Res.plurals.logs_entries, logs.size, logs.size),
                onBack = onBack,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onSaveClick
            ) {
                Text(stringResource(Res.string.action_save))
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onShareClick
            ) {
                Text(stringResource(Res.string.action_share))
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogLines(
                logs = logs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesSettingsContent(
    settings: AppUpdateSettings,
    statusText: String?,
    downloadProgress: Float?,
    onBack: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.updates_title),
            subtitle = stringResource(Res.string.updates_current_version, CurrentAppInfo.value.version),
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SettingsSectionLabel(stringResource(Res.string.updates_check_interval))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text(stringResource(Res.string.hours_short, hours)) }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(Res.string.updates_last_check),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.formatDateTime() ?: stringResource(Res.string.updates_not_checked),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onCheckUpdatesClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(stringResource(Res.string.action_check_now))
        }
    }
}

@Composable
private fun SubscriptionsSharingSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onShareClick: (String) -> Unit,
    onRouterClick: ((String) -> Unit)?,
    onRefreshClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(Res.string.server_lists_sharing_title),
            subtitle = subscriptions.size.subscriptionSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsSectionLabel(stringResource(Res.string.settings_server_lists))

            if (subscriptions.isEmpty()) {
                EmptyAppsState(
                    title = stringResource(Res.string.no_server_lists),
                    subtitle = stringResource(Res.string.no_server_lists_hint)
                )
            } else {
                subscriptions.forEach { item ->
                    SubscriptionShareRow(
                        item = item,
                        // The encrypted link when there is one; url stays the key
                        // Refresh and Remove use.
                        onShareClick = { onShareClick(item.shareText) },
                        onRouterClick = onRouterClick?.takeIf { offersRouterExport(item) }?.let { open -> { open(item.url) } },
                        onRefreshClick = { onRefreshClick(item.url) },
                        onDeleteClick = { onDeleteClick(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionShareRow(
    item: SubscriptionShareItem,
    onShareClick: () -> Unit,
    onRouterClick: (() -> Unit)?,
    onRefreshClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.remove_server_list_question)) },
            text = {
                Text(stringResource(Res.string.remove_server_list_text, item.name, item.locationCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteClick()
                }) {
                    Text(stringResource(Res.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pkSubscriptionSourceLine(
                            url = item.url,
                            originLink = item.originLink,
                            // Fails closed: a build with no admin hash must not
                            // start printing credentials again.
                            revealed = AdminState.plumbingVisible,
                            encrypted = stringResource(Res.string.encrypted_link)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = item.subscriptionSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Wraps: four buttons in a language with long words do not fit one line.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareClick) {
                    Text(stringResource(Res.string.action_qr_share))
                }
                onRouterClick?.let { onClick ->
                    TextButton(onClick = onClick) {
                        Text(stringResource(Res.string.router_export))
                    }
                }
                TextButton(onClick = onRefreshClick) {
                    Text(stringResource(Res.string.action_refresh))
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(Res.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    // No round icon chip, for the reason given on the shared screen: a column of
    // filled circles is the Material settings signature this redesign is not
    // wearing. The icon stays, small and plain.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalPkPalette.current.textDim,
                modifier = Modifier.size(18.dp)
            )

            Spacer(Modifier.width(13.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = value,
                    style = pkMono(10, 0.5),
                    color = LocalPkPalette.current.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showChevron) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = PkIcons.ChevronRight,
                    contentDescription = null,
                    tint = LocalPkPalette.current.textMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    value: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            PkSwitch(checked = checked, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    PkScreenHeader(title = title, subtitle = subtitle, onBack = onBack, modifier = modifier)
}

@Composable
private fun ConnectionModeOption(
    mode: AndroidConnectionMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SelectableSettingsCard(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.label(),
        subtitle = mode.description(),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelModeOption(
    mode: AndroidSplitTunnelMode,
    settings: AndroidSplitTunnelSettings,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SplitTunnelRoutingOption(
        selected = selected,
        enabled = enabled,
        icon = mode.icon(),
        title = mode.title(),
        subtitle = mode.subtitle(settings),
        onClick = onClick
    )
}

@Composable
private fun SplitTunnelRoutingOption(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "splitTunnelRoutingOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "splitTunnelRoutingOptionBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun SelectableSettingsCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "selectableSettingsCardContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "selectableSettingsCardBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Box(modifier = Modifier.padding(start = 2.dp)) {
        PkSectionLabel(text)
    }
}

@Composable
private fun SplitTunnelStatusCard(
    settings: AndroidSplitTunnelSettings,
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(
                    imageVector = settings.mode.icon(),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = settings.mode.statusTitle(settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = splitTunnelStatusSubtitle(selectedMode, isConnectionActive),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelNoListCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.split_no_list_needed),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(Res.string.split_same_route),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelAppListAction(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsSectionLabel(stringResource(Res.string.split_app_list))

    Spacer(Modifier.height(8.dp))

    SettingsNavigationRow(
        title = title,
        value = value,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun SocksProxySettingsForm(
    host: String,
    port: String,
    username: String,
    password: String,
    hostValid: Boolean,
    portValid: Boolean,
    hostChanged: Boolean,
    portChanged: Boolean,
    usernameChanged: Boolean,
    passwordChanged: Boolean,
    canSave: Boolean,
    enabled: Boolean,
    isConnectionActive: Boolean,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onRegeneratePassword: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(stringResource(Res.string.lan_endpoint))

            SocksProxyTextField(
                value = host,
                onValueChange = onHostChanged,
                label = stringResource(Res.string.field_listen_address),
                placeholder = AndroidSocksProxySettings.DEFAULT_HOST,
                enabled = enabled,
                isError = !hostValid,
                leadingIcon = PkIcons.Public,
                supportingText = when {
                    !hostValid -> stringResource(Res.string.listen_address_required)
                    hostChanged && isConnectionActive -> stringResource(Res.string.saving_restarts_connection)
                    hostChanged -> stringResource(Res.string.unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = port,
                onValueChange = onPortChanged,
                label = stringResource(Res.string.field_port),
                placeholder = AndroidSocksProxySettings.DEFAULT_PORT.toString(),
                enabled = enabled,
                isError = port.isBlank() || !portValid,
                leadingIcon = PkIcons.Public,
                supportingText = when {
                    port.isBlank() -> stringResource(Res.string.port_required)
                    !portValid -> stringResource(Res.string.port_range_hint, AndroidSocksProxySettings.MIN_PORT, AndroidSocksProxySettings.MAX_PORT)
                    portChanged && isConnectionActive -> stringResource(Res.string.saving_restarts_connection)
                    portChanged -> stringResource(Res.string.unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionLabel(stringResource(Res.string.proxy_credentials_section))

            SocksProxyTextField(
                value = username,
                onValueChange = onUsernameChanged,
                label = stringResource(Res.string.field_username),
                placeholder = "olcbox...",
                enabled = enabled,
                isError = username.isBlank(),
                leadingIcon = Icons.Rounded.Person,
                supportingText = when {
                    username.isBlank() -> stringResource(Res.string.username_required)
                    usernameChanged && isConnectionActive -> stringResource(Res.string.saving_restarts_connection)
                    usernameChanged -> stringResource(Res.string.unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            SocksProxyTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = stringResource(Res.string.field_password),
                placeholder = stringResource(Res.string.field_generated_password),
                enabled = enabled,
                isError = password.isBlank(),
                leadingIcon = PkIcons.Key,
                supportingText = when {
                    password.isBlank() -> stringResource(Res.string.password_required)
                    passwordChanged && isConnectionActive -> stringResource(Res.string.saving_restarts_connection)
                    passwordChanged -> stringResource(Res.string.unsaved_change)
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = enabled,
                onClick = onRegeneratePassword
            ) {
                Text(stringResource(Res.string.regenerate_password))
            }

            Spacer(Modifier.width(8.dp))

            Button(
                enabled = canSave,
                onClick = onSaveSettings
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun SocksProxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    isError: Boolean,
    leadingIcon: ImageVector,
    supportingText: String?,
    keyboardOptions: KeyboardOptions
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions
    )
}

@Composable
private fun RussianBypassPresetChips(
    active: Boolean,
    enabled: Boolean,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = active,
            enabled = enabled,
            onClick = onClick,
            label = {
                Text(stringResource(if (active) Res.string.ru_bypass_on else Res.string.ru_bypass_apps))
            },
            leadingIcon = {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else PkIcons.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        RussianBypassInfoPill(value = value)
    }
}

@Composable
private fun RussianBypassInfoPill(value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SplitTunnelAppRow(
    app: AndroidInstalledApp,
    selected: Boolean,
    autoSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val iconBitmap = rememberAppIcon(app.packageName)
    val colors = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHigh
            selected -> colors.secondaryContainer
            else -> colors.surfaceContainer
        },
        label = "splitTunnelAppRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary.copy(alpha = 0.72f)
            selected -> colors.primary
            else -> colors.outlineVariant
        },
        label = "splitTunnelAppRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.surfaceContainerHighest
            selected -> colors.primary
            else -> colors.surfaceVariant
        },
        label = "splitTunnelAppRowIconContainer"
    )
    val iconContentColor by animateColorAsState(
        targetValue = when {
            autoSelected -> colors.tertiary
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        label = "splitTunnelAppRowIconContent"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = app.label.initials(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    color = colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (autoSelected) {
                Spacer(Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colors.surfaceContainerHighest,
                    contentColor = colors.tertiary
                ) {
                    Text(
                        text = "RU",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onClick() }
            )
        }
    }
}

@Composable
private fun EmptyAppsState(
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val iconState = produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toImageBitmap(sizePx = 96)
            }.getOrNull()
        }
    }
    return iconState.value
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val width = intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val height = intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private sealed class AppSettingsRoute(val depth: Int) {
    object Hub : AppSettingsRoute(0)
    object ConnectionSettings : AppSettingsRoute(1)
    object ConnectionMode : AppSettingsRoute(1)
    object SocksProxy : AppSettingsRoute(1)
    object Routing : AppSettingsRoute(1)
    object SplitTunneling : AppSettingsRoute(1)
    object SubscriptionsSharing : AppSettingsRoute(1)
    object SubscriptionOptions : AppSettingsRoute(1)
    object Updates : AppSettingsRoute(1)
    object ApplicationLogs : AppSettingsRoute(1)
    data class AppList(val list: AndroidSplitTunnelList) : AppSettingsRoute(2)
}

private fun AppSettingsInitialRoute.toRoute(): AppSettingsRoute {
    return when (this) {
        AppSettingsInitialRoute.Hub -> AppSettingsRoute.Hub
        AppSettingsInitialRoute.SplitTunneling -> AppSettingsRoute.SplitTunneling
    }
}

@Composable
private fun AndroidConnectionMode.label(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> stringResource(Res.string.connection_mode_proxy)
    }
}

@Composable
private fun Int.subscriptionSummary(): String =
    if (this == 0) stringResource(Res.string.https_sources_none) else pluralStringResource(Res.plurals.https_sources, this, this)

@Composable
private fun SubscriptionShareItem.subscriptionSummary(): String {
    val interval = updateIntervalHours?.let { stringResource(Res.string.every_hours, it) }
        ?: stringResource(Res.string.default_interval)
    val count = pluralStringResource(Res.plurals.locations_count, locationCount, locationCount)
    val refresh = lastRefreshAtEpochMs?.let { stringResource(Res.string.last_refresh_at, it.formatDateTime()) }
        ?: stringResource(Res.string.not_refreshed_yet)
    return "$interval · $count · $refresh"
}

private fun Long.formatDateTime(): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
}

private fun AndroidConnectionMode.shortLabel(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> "TUN"
        AndroidConnectionMode.Proxy -> "SOCKS"
    }
}

@Composable
private fun AndroidConnectionMode.subtitle(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(Res.string.mode_full_tunnel)
        AndroidConnectionMode.Proxy -> stringResource(Res.string.connection_mode_local_socks)
    }
}

@Composable
private fun AndroidConnectionMode.settingsSummary(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(Res.string.mode_tun_summary)
        AndroidConnectionMode.Proxy -> stringResource(Res.string.mode_proxy_summary)
    }
}

@Composable
private fun AndroidConnectionMode.description(): String {
    return when (this) {
        AndroidConnectionMode.Tun -> stringResource(Res.string.mode_tun_description)
        AndroidConnectionMode.Proxy -> stringResource(Res.string.mode_proxy_description)
    }
}

private fun AndroidConnectionMode.icon() = when (this) {
    AndroidConnectionMode.Tun -> PkIcons.Shield
    AndroidConnectionMode.Proxy -> PkIcons.Public
}

@Composable
private fun AndroidSplitTunnelSettings.settingsSummary(): String {
    return when (mode) {
        AndroidSplitTunnelMode.AllApps -> stringResource(Res.string.split_all_apps)
        AndroidSplitTunnelMode.ProxySelected -> if (proxyPackages.isEmpty()) {
            stringResource(Res.string.split_selected_only)
        } else {
            stringResource(Res.string.split_only_n, appCount(proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (bypassPackages.isEmpty()) {
            stringResource(Res.string.split_bypass_selected_apps)
        } else {
            stringResource(Res.string.split_n_bypassed, appCount(bypassPackages.size))
        }
    }
}

private fun AndroidSplitTunnelSettings.packagesFor(list: AndroidSplitTunnelList): Set<String> {
    return when (list) {
        AndroidSplitTunnelList.Proxy -> proxyPackages
        AndroidSplitTunnelList.Bypass -> bypassPackages
    }
}

@Composable
private fun AndroidSplitTunnelMode.title(): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(Res.string.split_mode_all)
        AndroidSplitTunnelMode.ProxySelected -> stringResource(Res.string.split_mode_selected)
        AndroidSplitTunnelMode.BypassSelected -> stringResource(Res.string.split_mode_bypass)
    }
}

@Composable
private fun AndroidSplitTunnelMode.subtitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(Res.string.split_every_app_uses)
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringResource(Res.string.split_choose_using)
        } else {
            stringResource(Res.string.split_n_use, appCount(settings.proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringResource(Res.string.split_choose_bypassing)
        } else {
            stringResource(Res.string.split_n_bypass, appCount(settings.bypassPackages.size))
        }
    }
}

@Composable
private fun AndroidSplitTunnelMode.statusTitle(settings: AndroidSplitTunnelSettings): String {
    return when (this) {
        AndroidSplitTunnelMode.AllApps -> stringResource(Res.string.split_all_use)
        AndroidSplitTunnelMode.ProxySelected -> if (settings.proxyPackages.isEmpty()) {
            stringResource(Res.string.split_none_selected)
        } else {
            stringResource(Res.string.split_only_n_use, appCount(settings.proxyPackages.size))
        }

        AndroidSplitTunnelMode.BypassSelected -> if (settings.bypassPackages.isEmpty()) {
            stringResource(Res.string.split_none_bypass)
        } else {
            stringResource(Res.string.split_n_bypass, appCount(settings.bypassPackages.size))
        }
    }
}

private fun AndroidSplitTunnelMode.icon() = when (this) {
    AndroidSplitTunnelMode.AllApps -> PkIcons.Shield
    AndroidSplitTunnelMode.ProxySelected -> PkIcons.Shield
    AndroidSplitTunnelMode.BypassSelected -> PkIcons.Apps
}

@Composable
private fun AndroidSplitTunnelList.title(): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy -> stringResource(Res.string.split_apps_using)
        AndroidSplitTunnelList.Bypass -> stringResource(Res.string.split_bypassed_apps)
    }
}

@Composable
private fun AndroidSplitTunnelList.selectionSubtitle(count: Int): String {
    return when (this) {
        AndroidSplitTunnelList.Proxy -> stringResource(Res.string.split_n_use, appCount(count))
        AndroidSplitTunnelList.Bypass -> stringResource(Res.string.split_n_bypassed, appCount(count))
    }
}

@Composable
private fun Set<String>.russianBypassPresetValue(
    autoCount: Int,
    selectedMatchedCount: Int,
    presetActive: Boolean
): String {
    return when {
        isEmpty() -> stringResource(Res.string.ru_bypass_no_match)
        !presetActive -> stringResource(Res.string.ru_bypass_matched, appCount(size))
        selectedMatchedCount == 0 -> stringResource(Res.string.ru_bypass_none_selected)
        autoCount == 0 -> stringResource(Res.string.ru_bypass_already, appCount(selectedMatchedCount))
        autoCount == selectedMatchedCount -> stringResource(Res.string.ru_bypass_auto, appCount(autoCount))
        else -> stringResource(Res.string.ru_bypass_auto_manual, autoCount, selectedMatchedCount - autoCount)
    }
}

private fun String.matchesRussianBypassPackage(): Boolean {
    val packageName = lowercase()
    return packageName in RUSSIAN_BYPASS_PACKAGE_NAMES ||
            RUSSIAN_BYPASS_PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}

@Composable
private fun Set<String>.activeListValue(requireSelection: Boolean): String {
    return when {
        isNotEmpty() -> appCount(size)
        requireSelection -> stringResource(Res.string.split_required)
        else -> stringResource(Res.string.split_no_bypassed)
    }
}

@Composable
private fun splitTunnelStatusSubtitle(
    selectedMode: AndroidConnectionMode,
    isConnectionActive: Boolean
): String {
    return when {
        selectedMode == AndroidConnectionMode.Proxy -> stringResource(Res.string.split_saved_for_tun)
        isConnectionActive -> stringResource(Res.string.split_applies_on_close)
        else -> stringResource(Res.string.split_tun_rule)
    }
}

private fun String.initials(): String {
    val words = trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
private fun appCount(count: Int): String = pluralStringResource(Res.plurals.apps_count, count, count)

private data class AndroidAppListEntry(
    val app: AndroidInstalledApp,
    val labelSortKey: String,
    val packageSortKey: String
)

private const val MAX_PROXY_USERNAME_LENGTH = 64
private const val MAX_PROXY_PASSWORD_LENGTH = 64
private const val MAX_PROXY_PORT_LENGTH = 5
private val RUSSIAN_BYPASS_PACKAGE_PREFIXES = listOf(
    "ru.",
    "com.yandex."
)
// The prefixes above catch most Russian apps (ru.sberbankmobile, ru.rostel for
// Gosuslugi, ru.oneme.app for MAX, ru.nspk.mirpay...). These are the ones they
// miss, under their real package names: T-Bank is com.idamob.tinkoff.android,
// not "ru.tinkoff.mb", and Avito is com.avito.android, not "ru.avito", so the
// preset used to leave the biggest bank and the biggest classifieds app on the
// VPN. Checked against Google Play and RuStore on 2026-09-25 where they list
// the app. A name that is not installed matches nothing.
private val RUSSIAN_BYPASS_PACKAGE_NAMES = setOf(
    "com.idamob.tinkoff.android",
    "com.avito.android",
    "com.wildberries.ru",
    "com.vkontakte.android",
)

/**
 * The system's VPN screen, where Android keeps always-on and "Block connections
 * without VPN" per app. A phone whose settings app has no such screen gets the
 * general wireless settings instead of nothing.
 */
private fun openSystemVpnSettings(context: android.content.Context) {
    val attempts = listOf(android.provider.Settings.ACTION_VPN_SETTINGS, android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    for (action in attempts) {
        val opened = runCatching {
            context.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}
