package org.olcbox.app.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import org.olcbox.app.data.datasource.IosLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.IosLogExporter
import org.olcbox.app.data.importer.IosConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.subscriptionShareItems
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.net.TransportGroup
import org.olcbox.app.net.transportKind
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.vpn.IosVpnManager
import platform.UIKit.UIViewController
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

class IosAppFactory {
    fun createSession(
        platformBridge: IosPlatformBridge,
        olcRtcBridge: IosOlcRtcBridge,
        packetTunnelBridge: IosPacketTunnelBridge
    ): IosAppSession {
        return IosAppSession(platformBridge, olcRtcBridge, packetTunnelBridge)
    }

    fun createViewController(
        platformBridge: IosPlatformBridge,
        olcRtcBridge: IosOlcRtcBridge,
        packetTunnelBridge: IosPacketTunnelBridge
    ): UIViewController {
        return createSession(platformBridge, olcRtcBridge, packetTunnelBridge).createViewController()
    }
}

class IosAppSession internal constructor(
    private val platformBridge: IosPlatformBridge,
    olcRtcBridge: IosOlcRtcBridge,
    packetTunnelBridge: IosPacketTunnelBridge
) {
    private val dependencies = IosAppDependencies(platformBridge, olcRtcBridge, packetTunnelBridge)

    fun createViewController(): UIViewController {
        return ComposeUIViewController {
            IosApp(platformBridge, dependencies)
        }
    }

    /**
     * A `proofkit://add?url=…` or `https://proofkit.org/add#…` link the system
     * handed to the app: the same import a paste goes through, then the board
     * reloads. The outcome is said out loud either way — a link tapped in a
     * bot is a promise that something will appear.
     */
    fun handleIncomingLink(uri: String) {
        dependencies.homeViewModel.onImportLink(
            uri = uri,
            onComplete = {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig()
                    platformBridge.showMessage("Server list added")
                }
            },
            onError = { message -> platformBridge.showMessage(message) }
        )
    }

    /**
     * Runs the Kotlin/Native collector now, on the caller's thread.
     *
     * The iOS host drops the Compose view controller when the app goes to the
     * background (olcbox#24). Everything the scene owned — the Metal layer,
     * the Skia context, the drawables — is unreachable from that moment, but
     * unreachable is not freed: it stays allocated until the collector runs,
     * and a backgrounded app that allocates nothing gives it no reason to run.
     * The 1.0.423 samples showed exactly that, 180–215 MB still held three and
     * eight seconds into the background, gone only once the foreground began
     * allocating again. This is the host asking for the collection it would
     * otherwise wait for.
     */
    @OptIn(NativeRuntimeApi::class)
    fun collectGarbage() {
        GC.collect()
    }

    fun close() {
        dependencies.close()
    }
}

private class IosAppDependencies(
    platformBridge: IosPlatformBridge,
    olcRtcBridge: IosOlcRtcBridge,
    packetTunnelBridge: IosPacketTunnelBridge
) {
    private val locationsDataSource = IosLocationsDataSourceImpl()
    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val vpnManager = IosVpnManager(locationsRepository, olcRtcBridge, packetTunnelBridge)
    // No AppUpdateService here on purpose — see the comment in IosApp.
    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = IosConfigImporter(platformBridge),
        logExporter = IosLogExporter(platformBridge)
    )
    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        vpnManager.close()
    }
}

@Composable
private fun IosApp(
    platformBridge: IosPlatformBridge,
    dependencies: IosAppDependencies
) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var isAppSettingsOpen by remember { mutableStateOf(false) }
    // No update machinery on iOS. The App Store owns updates here: its version
    // numbers are not the release feed's, an "Update available" sheet over a
    // store build is simply wrong, and pointing anyone at a download page to
    // obtain the app is grounds for rejection. The state below exists only
    // because the shared sheet takes it; `showUpdates = false` means none of it
    // is ever displayed and nothing is ever fetched.
    val updateSettings = remember { AppUpdateSettings() }

    fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
        dependencies.locationViewModel.loadLocations {
            dependencies.homeViewModel.loadCurrentConfig(onComplete)
        }
    }

    LaunchedEffect(Unit) {
        dependencies.locationViewModel.loadLocations()
        dependencies.homeViewModel.loadCurrentConfig()
    }

    AppTheme {
        val logs by dependencies.homeViewModel.logs.collectAsState()
        val homeState by dependencies.homeViewModel.state.collectAsState()
        val subscriptionSettings by dependencies.homeViewModel.subscriptionSettings.collectAsState()
        val routingSettings by dependencies.homeViewModel.routingSettings.collectAsState()
        // What the connection actually is, not what it was two rewrites ago.
        // olcRTC ran as an in-app SOCKS endpoint on 127.0.0.1 once; it runs in
        // the packet tunnel extension now, like every other transport, and the
        // port a user could set there is overridden inside the extension. The
        // sheet went on describing "Local SOCKS5 proxy 127.0.0.1:<port>" and
        // offering credentials that changed nothing observable.
        val activeLocation = homeState.selectedLocation?.config
        val connectionSummary = when {
            homeState.isVpnConnected ->
                listOfNotNull(
                    "System VPN",
                    activeLocation?.transportKind()?.label()
                ).joinToString(" · ")

            else -> "Not connected"
        }

        Box(modifier = Modifier.fillMaxSize()) {
            OlcboxAppContent(
                homeViewModel = dependencies.homeViewModel,
                locationViewModel = dependencies.locationViewModel,
                currentScreen = currentScreen,
                onNavigate = { screen -> currentScreen = screen },
                onToggleClick = {
                    dependencies.homeViewModel.ToggleVpn()
                },
                onImportFileRequested = {
                    platformBridge.pickConfigText(object : IosTextCallback {
                        override fun onSuccess(text: String) {
                            dependencies.homeViewModel.onImportFullConfig(text) {
                                reloadLocationsAfterImport {
                                    platformBridge.showMessage("Config imported")
                                }
                            }
                        }

                        override fun onError(message: String) {
                            platformBridge.showMessage(message)
                        }
                    })
                },
                onImportFromClipboardRequested = { onImported, onError ->
                    dependencies.homeViewModel.onPasteFromClipboard(
                        onComplete = {
                            reloadLocationsAfterImport(onImported)
                        },
                        onError = onError
                    )
                },
                onScanQrRequested = {
                    platformBridge.scanQrCode(object : IosTextCallback {
                        override fun onSuccess(text: String) {
                            dependencies.homeViewModel.onImportFullConfig(
                                rawText = text,
                                onComplete = {
                                    reloadLocationsAfterImport {
                                        platformBridge.showMessage("Imported from QR code")
                                    }
                                },
                                onError = platformBridge::showMessage
                            )
                        }

                        override fun onError(message: String) {
                            // Cancelling is not a failure worth an alert.
                            if (message != "Scan cancelled") platformBridge.showMessage(message)
                        }
                    })
                },
                onShareLocationRequested = { config: LocationConfig ->
                    platformBridge.shareText("Location", ConfigShareService.olcRtcUri(config))
                },
                onSaveLogsRequested = { onSaved, onError ->
                    dependencies.homeViewModel.onSaveLogsToFile(
                        target = dependencies.homeViewModel.suggestedLogsFileName(),
                        onSaved = onSaved,
                        onError = onError
                    )
                },
                showAppSettingsButton = true,
                // Guideline 3.1.1. This used to open proofkit.org, where the
                // service is bought, and that is a call to action for a purchase
                // outside In-App Purchase however it is worded — which is what
                // the 2026-08-03 rejection named. The app is unchanged
                // otherwise: a subscription link still arrives by QR, paste or
                // file, exactly as it does in every other client.
                //
                // Nothing here is disabled or greyed out. The row is gone, so
                // there is nothing to explain and nothing to tap.
                showGetSubscription = false,
                // Not on iOS: the app is a client for locations it is given,
                // not an editor for ones typed in by hand.
                showCustomLocation = false,
                onOpenExternalUrl = { url -> platformBridge.openUrl(url) },
                showSplitTunnelingButton = false,
                canScanQr = true,
                onAppSettingsClick = { isAppSettingsOpen = true },
                onSplitTunnelingClick = {}
            )

            if (isAppSettingsOpen) {
                ApplicationSettingsSheet(
                    updateSettings = updateSettings,
                    updateStatusText = null,
                    updateDownloadProgress = null,
                    updateOffer = null,
                    subscriptions = subscriptionShareItems(dependencies.locationViewModel.locations.toList()),
                    logs = logs,
                    connectionSummary = connectionSummary,
                    connectionDetails = listOfNotNull(
                        activeLocation?.transportKind()?.label()?.let { "Transport" to it },
                        activeLocation?.displayName()
                            ?.let { TransportGroup.baseName(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "Exit" to it },
                        "Traffic" to if (homeState.isVpnConnected) {
                            "All apps and system traffic"
                        } else {
                            "Not routed"
                        }
                    ),
                    // No local proxy to configure: the extension carries
                    // everything, and its SOCKS port is internal to it.
                    socksProxySettings = null,
                    isConnectionActive = homeState.isVpnConnected,
                    subscriptionSettings = subscriptionSettings,
                    onSubscriptionSettingsChanged = dependencies.homeViewModel::updateSubscriptionSettings,
                    routingSettings = routingSettings,
                    adBlockingUnavailableReason = if (activeLocation?.kind == org.olcbox.app.net.LocationKind.Olcrtc)
                        "Not available for Telemost/olcRTC on iOS yet. Available for VLESS and XHTTP." else null,
                    onRoutingSettingsChanged = dependencies.homeViewModel::updateRoutingSettings,
                    connectionModeTitle = "System VPN",
                    connectionModeSummary = "All device traffic through the tunnel",
                    showUpdates = false,
                    onDismiss = { isAppSettingsOpen = false },
                    onSaveLogsClick = {
                        dependencies.homeViewModel.onSaveLogsToFile(
                            target = dependencies.homeViewModel.suggestedLogsFileName(),
                            onSaved = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    onShareLogsClick = {
                        dependencies.homeViewModel.onShareLogs(
                            onShared = platformBridge::showMessage,
                            onError = platformBridge::showMessage
                        )
                    },
                    // Unreachable with showUpdates = false; no update UI is built.
                    onUpdateIntervalSelected = {},
                    onCheckUpdatesClick = {},
                    onDownloadUpdateClick = {},
                    onLaterUpdateClick = {},
                    onSubscriptionShareClick = { url ->
                        platformBridge.shareText("Server list", ConfigShareService.subscriptionQrText(url))
                    },
                    onSubscriptionRefreshClick = { url ->
                        dependencies.homeViewModel.refreshSubscription(url) { report ->
                            reloadLocationsAfterImport {
                                dependencies.homeViewModel.restartVpnIfRunning()
                                platformBridge.showMessage(report.singleMessage())
                            }
                        }
                    },
                    onReplayOnboarding = { dependencies.homeViewModel.replayOnboarding() },
                    onSubscriptionDeleteClick = { url ->
                        dependencies.homeViewModel.deleteSubscription(url) { removed ->
                            reloadLocationsAfterImport {
                                platformBridge.showMessage(
                                    if (removed > 0) "Server list removed" else "Server list not found"
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}
