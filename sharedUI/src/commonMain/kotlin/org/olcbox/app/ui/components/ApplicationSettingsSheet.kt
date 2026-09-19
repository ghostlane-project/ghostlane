package org.olcbox.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.components.kit.pkSubscriptionSourceLine
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.ui.components.kit.PkScreenHeader
import org.olcbox.app.ui.components.kit.PkSectionEyebrow
import org.olcbox.app.ui.components.kit.PkSectionLabel
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.components.kit.pkVersionLine
import org.olcbox.app.ui.features.home.components.LogLines
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import kotlin.time.Instant

/**
 * One way this platform can carry traffic, for the Connection Mode chooser.
 *
 * Platform-neutral on purpose: Android already has `AndroidConnectionMode` and
 * the desktop now has `DesktopConnectionMode`, and both mean the same two
 * things. This is the shape the shared screen renders, so one explanation and
 * one set of words cover every platform that has a choice to offer.
 */
data class ApplicationConnectionModeOption(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean = true,
    /** Shown in place of [summary] when the option cannot be picked yet. */
    val disabledReason: String? = null
)

data class ApplicationSocksProxySettings(
    val host: String = "127.0.0.1",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val password: String = "",
    val lanSharingSupported: Boolean = false,
    val shareOnLan: Boolean = false,
    val lanEndpoint: String? = null
) {
    companion object {
        const val DEFAULT_PORT = 10808
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationSettingsSheet(
    updateSettings: AppUpdateSettings,
    updateStatusText: String?,
    updateDownloadProgress: Float?,
    updateOffer: AppUpdateInfo?,
    subscriptions: List<SubscriptionShareItem>,
    logs: List<String>,
    connectionSummary: String,
    connectionDetails: List<Pair<String, String>>,
    socksProxySettings: ApplicationSocksProxySettings? = null,
    isConnectionActive: Boolean = false,
    /**
     * How this platform actually carries traffic. The defaults describe the
     * in-app SOCKS endpoint the desktop build still uses; a platform that
     * carries the whole device through a tun says so instead, rather than
     * inheriting copy that stopped being true for it.
     */
    connectionModeTitle: String = "Proxy",
    connectionModeSummary: String = "Local SOCKS5 proxy",
    /**
     * The ways this platform can carry traffic, when it has more than one.
     *
     * Empty on a platform with a single mode, and the screen then shows what is
     * in force rather than a choice of one — which is what it always showed,
     * including on machines that had two modes and no way to say which.
     */
    connectionModeOptions: List<ApplicationConnectionModeOption> = emptyList(),
    selectedConnectionModeId: String? = null,
    onConnectionModeSelected: (String) -> Unit = {},
    /**
     * How the platform's own tunnel component is doing, when it has one — today
     * the macOS root daemon, which the user installs and approves rather than
     * receiving with the app. Null everywhere else, and the row is then absent
     * rather than disabled: a platform with no such component has nothing to say
     * about it.
     */
    tunnelDaemonSummary: String? = null,
    onTunnelDaemonClick: () -> Unit = {},
    /** How subscriptions behave. See [SubscriptionSettings]. */
    subscriptionSettings: SubscriptionSettings = SubscriptionSettings(),
    onSubscriptionSettingsChanged: (SubscriptionSettings) -> Unit = {},
    /** What leaves through the tunnel. See [RoutingSettings]. */
    routingSettings: RoutingSettings = RoutingSettings(),
    onRoutingSettingsChanged: (RoutingSettings) -> Unit = {},
    /**
     * Why the choice cannot be made on this platform, when it cannot. Shown
     * under the cards, which are then not selectable. Null where it applies.
     */
    routingUnavailableReason: String? = null,
    adBlockingUnavailableReason: String? = null,
    /**
     * False where the store owns updates.
     *
     * An App Store build must not check a release feed, must not offer an
     * "Update available" sheet, and must not point anyone at a download page:
     * the version numbers do not even correspond, and telling users to install
     * the app from somewhere else is grounds for rejection.
     */
    showUpdates: Boolean = true,
    onDismiss: () -> Unit,
    onSaveLogsClick: () -> Unit,
    onShareLogsClick: () -> Unit,
    onUpdateIntervalSelected: (Int) -> Unit,
    onCheckUpdatesClick: () -> Unit,
    onDownloadUpdateClick: (AppUpdateInfo) -> Unit,
    onLaterUpdateClick: (AppUpdateInfo) -> Unit,
    onSubscriptionShareClick: (String) -> Unit,
    onSubscriptionRefreshClick: (String) -> Unit,
    onSubscriptionDeleteClick: (String) -> Unit = {},
    onSocksProxySettingsSaved: (String, String, Int) -> Unit = { _, _, _ -> },
    onSocksProxyPasswordRegenerated: () -> Unit = {},
    onLanSharingChanged: (Boolean) -> Unit = {},
    /**
     * Clears the note that the first-run walkthrough has been shown.
     *
     * Defaulted so a platform that has not wired it compiles; every platform does
     * wire it, and one that stops will show a button that does nothing, which is
     * the failure mode to watch for rather than a compile error.
     */
    onReplayOnboarding: () -> Unit = {}
) {
    var route by remember { mutableStateOf(SharedSettingsRoute.Hub) }

    // A screen, not a sheet. Settings behind a half-height sheet you can flick
    // away is the shape every client ships, and it left this app's longest
    // content — a server list's options, the log, the connection detail — living
    // inside something whose natural gesture is "dismiss".
    //
    // The name and the parameter list do not change, so no platform call site
    // moves: only what this function draws around them.
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
            // Only the hub. Every sub-route draws its own header, with its own
            // back destination — a second one above them would be two arrows
            // pointing at two different places.
            if (route == SharedSettingsRoute.Hub) {
                PkScreenHeader(title = "Settings", onBack = onDismiss)
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
            label = "sharedApplicationSettingsRoute"
        ) { currentRoute ->
            when (currentRoute) {
                SharedSettingsRoute.Hub -> SharedSettingsHubContent(
                    updateSettings = updateSettings,
                    onReplayOnboarding = onReplayOnboarding,
                    subscriptionSettings = subscriptionSettings,
                    onSubscriptionOptionsClick = { route = SharedSettingsRoute.SubscriptionOptions },
                    showUpdates = showUpdates,
                    connectionSummary = connectionModeSummary,
                    routingSummary = routingSettings.mode.hubSummary() +
                        if (routingSettings.blockAds && adBlockingUnavailableReason == null) " · ads blocked" else "",
                    subscriptionsCount = subscriptions.size,
                    onConnectionClick = { route = SharedSettingsRoute.Connection },
                    onRoutingClick = { route = SharedSettingsRoute.Routing },
                    onSubscriptionsClick = { route = SharedSettingsRoute.Subscriptions },
                    onUpdatesClick = { route = SharedSettingsRoute.Updates },
                    onLogsClick = { route = SharedSettingsRoute.Logs }
                )

                SharedSettingsRoute.Connection -> SharedConnectionSettingsContent(
                    summary = connectionSummary,
                    details = connectionDetails,
                    modeSummary = connectionModeSummary,
                    socksProxySettings = socksProxySettings,
                    tunnelDaemonSummary = tunnelDaemonSummary,
                    onConnectionModeClick = { route = SharedSettingsRoute.ConnectionMode },
                    onSocksProxyClick = { route = SharedSettingsRoute.SocksProxy },
                    onTunnelDaemonClick = onTunnelDaemonClick,
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.ConnectionMode -> SharedConnectionModeSettingsContent(
                    title = connectionModeTitle,
                    summary = connectionModeSummary,
                    options = connectionModeOptions,
                    selectedId = selectedConnectionModeId,
                    onSelected = onConnectionModeSelected,
                    onBack = { route = SharedSettingsRoute.Connection }
                )

                SharedSettingsRoute.Routing -> RoutingSettingsScreen(
                    settings = routingSettings,
                    enabled = routingUnavailableReason == null,
                    adBlockingEnabled = routingUnavailableReason == null && adBlockingUnavailableReason == null,
                    unavailableReason = routingUnavailableReason,
                    adBlockingUnavailableReason = adBlockingUnavailableReason,
                    onChanged = onRoutingSettingsChanged,
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.SocksProxy -> if (socksProxySettings != null) {
                    SharedSocksProxySettingsContent(
                        settings = socksProxySettings,
                        isConnectionActive = isConnectionActive,
                        onBack = { route = SharedSettingsRoute.Connection },
                        onProxySettingsSaved = onSocksProxySettingsSaved,
                        onProxyPasswordRegenerated = onSocksProxyPasswordRegenerated,
                        onLanSharingChanged = onLanSharingChanged
                    )
                }

                SharedSettingsRoute.Subscriptions -> SharedSubscriptionsSettingsContent(
                    subscriptions = subscriptions,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onShareClick = onSubscriptionShareClick,
                    onRefreshClick = onSubscriptionRefreshClick,
                    onDeleteClick = onSubscriptionDeleteClick
                )

                SharedSettingsRoute.SubscriptionOptions -> SubscriptionSettingsScreen(
                    settings = subscriptionSettings,
                    onChanged = onSubscriptionSettingsChanged,
                    onBack = { route = SharedSettingsRoute.Hub }
                )

                SharedSettingsRoute.Updates -> SharedUpdatesSettingsContent(
                    settings = updateSettings,
                    statusText = updateStatusText,
                    downloadProgress = updateDownloadProgress,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onIntervalSelected = onUpdateIntervalSelected,
                    onCheckUpdatesClick = onCheckUpdatesClick
                )

                SharedSettingsRoute.Logs -> SharedLogsSettingsContent(
                    logs = logs,
                    onBack = { route = SharedSettingsRoute.Hub },
                    onSaveClick = onSaveLogsClick,
                    onShareClick = onShareLogsClick
                )
            }
            }
        }
    }
}

@Composable
private fun SharedSettingsHubContent(
    updateSettings: AppUpdateSettings,
    onReplayOnboarding: () -> Unit,
    subscriptionSettings: SubscriptionSettings,
    onSubscriptionOptionsClick: () -> Unit,
    showUpdates: Boolean,
    connectionSummary: String,
    routingSummary: String,
    subscriptionsCount: Int,
    onConnectionClick: () -> Unit,
    onRoutingClick: () -> Unit,
    onSubscriptionsClick: () -> Unit,
    onUpdatesClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    // Grouped under eyebrows rather than run together as one list of five. Three
    // of these rows are about the tunnel, two about where servers come from, and
    // reading which is which off five identical cards is a small task the screen
    // can do for the user.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PkSectionEyebrow("Connection")

        SharedNavigationRow(
            title = "Connection",
            value = connectionSummary,
            icon = PkIcons.Public,
            onClick = onConnectionClick
        )

        SharedNavigationRow(
            title = "Routing & ad blocking",
            value = routingSummary,
            icon = PkIcons.SwapVert,
            onClick = onRoutingClick
        )

        Spacer(Modifier.height(8.dp))
        PkSectionEyebrow("Server lists")

        SharedNavigationRow(
            title = "Server list updates",
            value = subscriptionSettings.hubSummary(),
            icon = Icons.Outlined.Refresh,
            onClick = onSubscriptionOptionsClick
        )

        SharedNavigationRow(
            title = "Server lists and sharing",
            value = subscriptionsCount.subscriptionSummary(),
            icon = Icons.Outlined.Share,
            onClick = onSubscriptionsClick
        )

        Spacer(Modifier.height(8.dp))
        PkSectionEyebrow("Diagnostics")

        if (showUpdates) {
            SharedNavigationRow(
                title = "Application updates",
                value = "Nightly · every ${updateSettings.intervalHours}h",
                icon = Icons.Outlined.Refresh,
                onClick = onUpdatesClick
            )
        }

        SharedNavigationRow(
            title = "Application log",
            value = "Diagnostics and export",
            icon = PkIcons.History,
            onClick = onLogsClick
        )

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "REPLAY FIRST RUN",
                style = pkMono(10, 1.2),
                color = LocalPkPalette.current.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(onClickLabel = "Replay first run") { onReplayOnboarding() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            Text(
                text = pkVersionLine(CurrentAppInfo.value),
                style = MaterialTheme.typography.labelSmall,
                color = LocalPkPalette.current.textMuted
            )
        }
    }
}

@Composable
private fun SharedConnectionSettingsContent(
    summary: String,
    details: List<Pair<String, String>>,
    modeSummary: String,
    socksProxySettings: ApplicationSocksProxySettings?,
    tunnelDaemonSummary: String?,
    onConnectionModeClick: () -> Unit,
    onSocksProxyClick: () -> Unit,
    onTunnelDaemonClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Settings",
            subtitle = summary,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SharedNavigationRow(
                title = "Connection Mode",
                value = modeSummary,
                icon = PkIcons.Public,
                onClick = onConnectionModeClick
            )

            // Not behind the admin gate. macOS asks *the user* to approve this in
            // System Settings, so hiding the only way to start that behind seven
            // taps would hide the app's own instructions from the one person who
            // can follow them.
            if (tunnelDaemonSummary != null) {
                SharedNavigationRow(
                    title = "System-wide tunnel",
                    value = tunnelDaemonSummary,
                    icon = PkIcons.Public,
                    onClick = onTunnelDaemonClick
                )
            }

            // Editing the local proxy credentials/port is plumbing: admin-only.
            if (socksProxySettings != null && AdminState.configuratorVisible) {
                SharedNavigationRow(
                    title = if (socksProxySettings.lanSharingSupported) "Local network sharing" else "SOCKS5 Proxy",
                    value = "${socksProxySettings.host}:${socksProxySettings.port}",
                    icon = PkIcons.Public,
                    onClick = onSocksProxyClick
                )
            }

            details
                .filterNot { (title, _) -> title.equals("Mode", ignoreCase = true) }
                .forEach { (title, value) ->
                    SharedInfoRow(title = title, value = value)
                }
        }
    }
}

@Composable
private fun SharedConnectionModeSettingsContent(
    title: String,
    summary: String,
    options: List<ApplicationConnectionModeOption>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "Connection Mode",
            subtitle = summary,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        if (options.isEmpty()) {
            // One mode, so nothing to choose. Shown selected because it is what is
            // in force, not because it won a comparison.
            SharedSelectableSettingsCard(
                selected = true,
                icon = PkIcons.Public,
                title = title,
                subtitle = summary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                options.forEach { option ->
                    SharedSelectableSettingsCard(
                        selected = option.id == selectedId,
                        icon = PkIcons.Public,
                        title = option.title,
                        // An option that cannot be picked says why instead of
                        // describing a thing the person cannot have.
                        subtitle = if (option.enabled) {
                            option.summary
                        } else {
                            option.disabledReason ?: option.summary
                        },
                        enabled = option.enabled,
                        onClick = { onSelected(option.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedSocksProxySettingsContent(
    settings: ApplicationSocksProxySettings,
    isConnectionActive: Boolean,
    onBack: () -> Unit,
    onProxySettingsSaved: (String, String, Int) -> Unit,
    onProxyPasswordRegenerated: () -> Unit,
    onLanSharingChanged: (Boolean) -> Unit
) {
    var editedHost by remember(settings.host) { mutableStateOf(settings.host) }
    var editedPort by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var editedUsername by remember(settings.username) { mutableStateOf(settings.username) }
    var editedPassword by remember(settings.password) { mutableStateOf(settings.password) }
    val parsedPort = editedPort.toIntOrNull()
    val hostValid = editedHost.isNotBlank()
    val portValid = parsedPort != null && ApplicationSocksProxySettings.isValidPort(parsedPort)
    val portChanged = parsedPort != null && parsedPort != settings.port
    val usernameChanged = editedUsername != settings.username
    val passwordChanged = editedPassword != settings.password
    val settingsChanged = portChanged || usernameChanged || passwordChanged
    val canSave = hostValid &&
            portValid &&
            editedUsername.isNotBlank() &&
            editedPassword.isNotBlank() &&
            settingsChanged

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        SharedDetailHeader(
            title = "SOCKS5 Proxy",
            subtitle = settings.host,
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))

        if (settings.lanSharingSupported) {
            SharedSelectableSettingsCard(
                selected = settings.shareOnLan,
                icon = PkIcons.Public,
                title = "Share VPN on local network",
                subtitle = settings.lanEndpoint?.let { "SOCKS5 $it — no username or password" }
                    ?: "Connect another device through this PC without a password. Listens on a private LAN address. Reconnects when changed.",
                enabled = true,
                onClick = { onLanSharingChanged(!settings.shareOnLan) }
            )
            Spacer(Modifier.height(16.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedSectionLabel("Endpoint")

                SharedSocksProxyTextField(
                    value = editedHost,
                    onValueChange = { value ->
                        editedHost = value
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim()
                    },
                    label = "Listen address",
                    placeholder = "127.0.0.1",
                    enabled = false,
                    isError = !hostValid,
                    leadingIcon = PkIcons.Public,
                    supportingText = if (!hostValid) "Listen address is required" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                SharedSocksProxyTextField(
                    value = editedPort,
                    onValueChange = { value ->
                        editedPort = value.filter { it.isDigit() }.take(5)
                    },
                    label = "Port",
                    placeholder = ApplicationSocksProxySettings.DEFAULT_PORT.toString(),
                    enabled = true,
                    isError = editedPort.isBlank() || !portValid,
                    leadingIcon = PkIcons.Public,
                    supportingText = when {
                        editedPort.isBlank() -> "Port is required"
                        !portValid -> "Use ${ApplicationSocksProxySettings.MIN_PORT}-${ApplicationSocksProxySettings.MAX_PORT}"
                        portChanged && isConnectionActive -> "Saving restarts the active connection"
                        portChanged -> "Unsaved change"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedSectionLabel("Credentials")

                SharedSocksProxyTextField(
                    value = editedUsername,
                    onValueChange = { editedUsername = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                    label = "Username",
                    placeholder = "olcbox...",
                    enabled = true,
                    isError = editedUsername.isBlank(),
                    leadingIcon = Icons.Rounded.Person,
                    supportingText = when {
                        editedUsername.isBlank() -> "Username is required"
                        usernameChanged && isConnectionActive -> "Saving restarts the active connection"
                        usernameChanged -> "Unsaved change"
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                SharedSocksProxyTextField(
                    value = editedPassword,
                    onValueChange = { editedPassword = it.take(ApplicationSocksProxySettings.MAX_CREDENTIAL_LENGTH) },
                    label = "Password",
                    placeholder = "Generated password",
                    enabled = true,
                    isError = editedPassword.isBlank(),
                    leadingIcon = PkIcons.Key,
                    supportingText = when {
                        editedPassword.isBlank() -> "Password is required"
                        passwordChanged && isConnectionActive -> "Saving restarts the active connection"
                        passwordChanged -> "Unsaved change"
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
                TextButton(onClick = onProxyPasswordRegenerated) {
                    Text("Regenerate password")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        onProxySettingsSaved(
                            editedUsername,
                            editedPassword,
                            parsedPort ?: settings.port
                        )
                    }
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SharedSocksProxyTextField(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedUpdatesSettingsContent(
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
        SharedDetailHeader(
            title = "Updates",
            subtitle = "Current version ${CurrentAppInfo.value.version}",
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        SharedSectionLabel("Check Interval")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppUpdateSettings.INTERVAL_PRESETS.forEach { hours ->
                FilterChip(
                    selected = settings.intervalHours == hours,
                    onClick = { onIntervalSelected(hours) },
                    label = { Text("${hours}h") }
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
                    text = "Last check",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = settings.lastCheckAtEpochMs?.formatEpochMs() ?: "Not checked yet",
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
            Text("Check now")
        }
    }
}

@Composable
private fun SharedSubscriptionsSettingsContent(
    subscriptions: List<SubscriptionShareItem>,
    onBack: () -> Unit,
    onShareClick: (String) -> Unit,
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
        SharedDetailHeader(
            title = "Server lists & sharing",
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
            SharedSectionLabel("Server lists")

            if (subscriptions.isEmpty()) {
                SharedEmptyState(
                    title = "No server lists",
                    subtitle = "Imported HTTPS server lists will appear here."
                )
            } else {
                subscriptions.forEach { item ->
                    SharedSubscriptionRow(
                        item = item,
                        // The encrypted link when there is one; url stays the key
                        // Refresh and Remove use.
                        onShareClick = { onShareClick(item.shareText) },
                        onRefreshClick = { onRefreshClick(item.url) },
                        onDeleteClick = { onDeleteClick(item.url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedLogsSettingsContent(
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
            SharedDetailHeader(
                title = "Application Logs",
                subtitle = if (logs.isEmpty()) "No entries" else "${logs.size} entries",
                onBack = onBack,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onSaveClick
            ) {
                Text("Save")
            }
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = onShareClick
            ) {
                Text("Share")
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

@Composable
private fun SharedUpdateOfferCard(
    offer: AppUpdateInfo,
    onDownload: () -> Unit,
    onLater: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Update available",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${offer.version} · ${offer.asset.name}",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLater) {
                    Text("Later")
                }
                Button(onClick = onDownload) {
                    Text("Download")
                }
            }
        }
    }
}

@Composable
private fun SharedSubscriptionRow(
    item: SubscriptionShareItem,
    onShareClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove server list?") },
            text = {
                Text(
                    "${item.name} and its ${item.locationCount} location(s) will be " +
                        "removed from this device. You can add the server list again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteClick()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
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
                    // Fails closed: a build with no admin hash must not start
                    // printing credentials again.
                    revealed = AdminState.plumbingVisible
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subscriptionSummary(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShareClick) {
                    Text("QR/share")
                }
                TextButton(onClick = onRefreshClick) {
                    Text("Refresh")
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SharedNavigationRow(
    title: String,
    value: String,
    icon: ImageVector,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    // No round icon chip. A column of filled circles down the left of a settings
    // list is the Material signature this redesign is trying not to wear; the icon
    // stays, small and plain, because it is what makes a row findable at a glance.
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
private fun SharedInfoRow(
    title: String,
    value: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SharedSelectableSettingsCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 82.dp)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
                    // Two, not one: the routing cards say what a list contains,
                    // and a subtitle that ends in "…" says nothing.
                    maxLines = 2,
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

/** A radio-style row used when several mutually related choices share one box. */
@Composable
private fun SharedCompactChoiceRow(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SharedCompactValueRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(subtitle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Spacer(Modifier.width(5.dp))
        Icon(PkIcons.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SharedDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The one header shape the app has, so a sub-screen of settings and the
    // settings screen itself do not arrive looking like two different apps.
    PkScreenHeader(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun SharedSectionLabel(text: String) {
    Box(modifier = Modifier.padding(start = 2.dp)) {
        PkSectionLabel(text)
    }
}

@Composable
private fun SharedEmptyState(
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

private enum class SharedSettingsRoute {
    Hub,
    Connection,
    ConnectionMode,
    Routing,
    Subscriptions,
    SubscriptionOptions,
    Updates,
    Logs,
    SocksProxy
}

private fun Int.subscriptionSummary(): String {
    return when (this) {
        0 -> "No HTTPS sources"
        1 -> "1 HTTPS source"
        else -> "$this HTTPS sources"
    }
}

private fun SubscriptionShareItem.subscriptionSummary(): String {
    val interval = updateIntervalHours?.let { "every ${it}h" } ?: "default interval"
    val count = when (locationCount) {
        1 -> "1 location"
        else -> "$locationCount locations"
    }
    val refresh = lastRefreshAtEpochMs?.let { "last refresh ${it.formatEpochMs()}" } ?: "not refreshed yet"
    return "$interval · $count · $refresh"
}

private fun Long.formatEpochMs(): String {
    return runCatching {
        Instant.fromEpochMilliseconds(this).toString()
            .substringBefore('.')
            .replace('T', ' ')
    }.getOrElse {
        toString()
    }
}
