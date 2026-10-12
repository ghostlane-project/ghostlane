package org.olcbox.app.ui.render

import androidx.compose.runtime.Composable
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.net.LinkParser
import org.olcbox.app.ui.components.AdminPasswordDialog
import org.olcbox.app.ui.components.ApplicationConnectionModeOption
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.components.CameraRationaleSheet
import org.olcbox.app.ui.components.RouterEntry
import org.olcbox.app.ui.components.RouterExportSheet
import org.olcbox.app.ui.components.RoutingSettingsScreen
import org.olcbox.app.ui.components.SubscriptionSettingsScreen
import org.olcbox.app.ui.components.VpnDisclosureScreen
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.onboarding.OnboardingScreen
import org.olcbox.app.update.AppUpdateAsset
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.ReleaseChannel
import org.olcbox.app.util.nowMillis

/**
 * The screens that are not the board, each with enough fabricated state to be
 * drawn: what a person meets on the way in, the sheets the header's buttons
 * open, and the settings behind them.
 */
internal object Screens {
    private const val REALITY =
        "vless://11111111-1111-1111-1111-111111111111@198.51.100.7:443?type=tcp&security=reality" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab&sni=www.example.com&fp=chrome&flow=xtls-rprx-vision#Madrid"

    private val update = AppUpdateInfo(
        channel = ReleaseChannel.Stable,
        version = "1.0.470",
        htmlUrl = "https://example.org/releases/v1.0.470",
        publishedAt = "2026-10-12T10:00:00Z",
        asset = AppUpdateAsset(name = "Ghostlane-1.0.470.dmg", downloadUrl = "https://example.org/g.dmg", sizeBytes = 84_000_000L),
        isUpdateAvailable = true
    )

    private val lists = listOf(
        SubscriptionShareItem(
            url = "https://proofkit.org/sub/0123456789abcdef0123456789abcdef/olcrtc?crypt=1",
            name = "",
            updateIntervalHours = 12,
            lastRefreshAtEpochMs = nowMillis() - 8 * 60_000L,
            locationCount = 30,
            originLink = "olcrtc://crypt1/ZmFicmljYXRlZA"
        ),
        SubscriptionShareItem(
            url = "https://lists.example.org/sub/partner-with-a-long-address",
            name = "@Xrayvless_bot",
            updateIntervalHours = 24,
            lastRefreshAtEpochMs = nowMillis(),
            locationCount = 3
        )
    )

    private val log = List(40) { line ->
        "12:0${line % 10}:1$line olcrtc: room de-wb joined, link up in 0.4 s, relay answers, stream $line opened to 203.0.113.$line:443"
    }

    val all: Map<String, @Composable () -> Unit> = linkedMapOf(
        "onboarding" to { OnboardingScreen(onFinished = {}, onAddServerList = {}) },
        "disclosure" to { VpnDisclosureScreen(onAccept = {}, onDecline = {}) },
        "add" to {
            AddConfigurationSheet(
                canScanQr = true,
                hasSubscriptions = true,
                onDismiss = {},
                onScanQrClick = {},
                onPasteLinkClick = {},
                onImportFileClick = {},
                onImportFromPhoneClick = {},
                onUpdateSubscriptionsClick = {},
                onAddCustomLocationClick = {},
                onGetSubscriptionClick = {},
                showGetSubscription = false,
                showCustomLocation = true
            )
        },
        "camera" to { CameraRationaleSheet(onContinue = {}) },
        "lists" to { SubscriptionSettingsScreen(settings = SubscriptionSettings(), onChanged = {}, onBack = {}) },
        "routing" to { RoutingSettingsScreen(settings = RoutingSettings(), enabled = true, onChanged = {}, onBack = {}) },
        "logs" to {
            LogsSheet(
                logs = log,
                verboseDebugLogs = false,
                onVerboseDebugLogsChanged = {},
                onSaveClick = {},
                onShareClick = {},
                onDismiss = {}
            )
        },
        "settings" to {
            ApplicationSettingsSheet(
                updateSettings = AppUpdateSettings(),
                updateStatusText = "Up to date",
                updateDownloadProgress = null,
                updateOffer = null,
                subscriptions = lists,
                logs = log,
                connectionSummary = "Connected · DE · olcRTC · WB",
                connectionDetails = listOf("Room" to "de-wb", "Carrier" to "WB Stream", "Transport" to "VP8"),
                socksProxySettings = ApplicationSocksProxySettings(username = "ghost", password = "0123456789abcdef"),
                isConnectionActive = true,
                connectionModeOptions = listOf(
                    ApplicationConnectionModeOption(id = "vpn", title = "VPN", summary = "The whole device goes through the tunnel"),
                    ApplicationConnectionModeOption(id = "proxy", title = "Proxy", summary = "Only apps pointed at the local SOCKS5 port")
                ),
                selectedConnectionModeId = "vpn",
                killSwitch = true,
                onDismiss = {},
                onSaveLogsClick = {},
                onShareLogsClick = {},
                onUpdateIntervalSelected = {},
                onCheckUpdatesClick = {},
                onDownloadUpdateClick = {},
                onLaterUpdateClick = {},
                onSubscriptionShareClick = {},
                onSubscriptionRouterClick = {},
                onSubscriptionRefreshClick = {}
            )
        },
        "update" to { ApplicationUpdateOfferSheet(info = update, downloadProgress = 0.4f, onLater = {}, onDownload = {}) },
        "router" to {
            RouterExportSheet(
                entries = listOf(
                    RouterEntry("🇪🇸 ES · Madrid", REALITY, LinkParser.parse(REALITY)!!),
                    RouterEntry("🇳🇱 Netherlands-Amsterdam-03", REALITY, LinkParser.parse(REALITY)!!)
                ),
                onCopy = {},
                onDismiss = {}
            )
        },
        "admin" to { AdminPasswordDialog(onDismiss = {}, onSubmit = { false }) }
    )
}
