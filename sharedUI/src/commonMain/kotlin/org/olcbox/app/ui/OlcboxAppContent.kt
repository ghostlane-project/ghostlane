package org.olcbox.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import org.olcbox.app.admin.AdminState
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.home.HomeScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationSettingsScreen
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen

@Composable
fun OlcboxAppContent(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onToggleClick: () -> Unit,
    onImportFileRequested: () -> Unit,
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit,
    onScanQrRequested: () -> Unit = {},
    /** Where a platform offers it (no camera, no clipboard: a TV), the add sheet's "From your phone". */
    onImportFromPhoneRequested: (() -> Unit)? = null,
    onShareLocationRequested: (LocationConfig) -> Unit = {},
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    showAppSettingsButton: Boolean,
    onGetSubscriptionClick: () -> Unit = {},
    showGetSubscription: Boolean = true,
    /**
     * Whether this platform offers building a location by hand.
     *
     * A platform flag rather than the admin gate, because the two answer
     * different questions: the gate asks whether this person is configuring the
     * app, this asks whether the app configures servers at all. iOS says no.
     */
    showCustomLocation: Boolean = true,
    showSplitTunnelingButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = {}
) {
    val homeScrollState = rememberScrollState()

    AnimatedContent(
        targetState = currentScreen,
        label = "app_screen_transition",
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 240,
                        delayMillis = 30,
                        easing = LinearOutSlowInEasing
                    )
                ),
                initialContentExit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = LinearOutSlowInEasing
                    )
                ),
                sizeTransform = SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        tween(
                            durationMillis = 420,
                            easing = FastOutSlowInEasing
                        )
                    }
                )
            )
        }
    ) { screen ->
        when (screen) {
            AppScreen.Home -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    locationViewModel = locationViewModel,
                    scrollState = homeScrollState,
                    onToggleClick = onToggleClick,
                    onImportFileRequested = onImportFileRequested,
                    onImportFromClipboardRequested = onImportFromClipboardRequested,
                    onScanQrRequested = onScanQrRequested,
                    onImportFromPhoneRequested = onImportFromPhoneRequested,
                    onSaveLogsRequested = onSaveLogsRequested,
                    showAppSettingsButton = showAppSettingsButton,
                    onGetSubscriptionClick = onGetSubscriptionClick,
                    showGetSubscription = showGetSubscription,
                    showCustomLocation = showCustomLocation,
                    showSplitTunnelingButton = showSplitTunnelingButton,
                    canScanQr = canScanQr,
                    onAppSettingsClick = onAppSettingsClick,
                    onSplitTunnelingClick = onSplitTunnelingClick,
                    onOpenLocationSettings = { id ->
                        locationViewModel.startEditing(id)
                        onNavigate(AppScreen.LocationSettings(id))
                    },
                    onAddLocation = {
                        locationViewModel.startEditing(null)
                        onNavigate(AppScreen.LocationSettings(null))
                    },
                    onOpenExternalUrl = onOpenExternalUrl
                )
            }

            is AppScreen.LocationSettings -> {
                // Defense-in-depth: even if something routes here, the per-location
                // settings screen is plumbing — an operator's edge address, SNI and
                // certificate pin — so it needs a gate that is open. Anything else,
                // including a build with no admin hash baked at all, bounces home.
                if (!AdminState.plumbingVisible) {
                    onNavigate(AppScreen.Home)
                } else {
                    LocationSettingsScreen(
                        viewModel = locationViewModel,
                        homeViewModel = homeViewModel,
                        onShareLocationRequested = onShareLocationRequested,
                        onBack = {
                            homeViewModel.loadCurrentConfig()
                            onNavigate(AppScreen.Home)
                        }
                    )
                }
            }
        }
    }
}
