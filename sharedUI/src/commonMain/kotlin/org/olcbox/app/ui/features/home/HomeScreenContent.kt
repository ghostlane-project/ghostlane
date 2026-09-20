package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.kit.PkAction
import org.olcbox.app.ui.components.kit.PkActionBar
import org.olcbox.app.ui.components.kit.PkBoardHead
import org.olcbox.app.ui.components.kit.PkHairline
import org.olcbox.app.ui.components.kit.PkHeaderRow
import org.olcbox.app.ui.components.kit.PkIconButton
import org.olcbox.app.ui.components.kit.PkStatusStrip
import org.olcbox.app.ui.components.kit.PkVersionFooter
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.features.home.components.BoardFilterChips
import org.olcbox.app.ui.features.home.components.BoardModel
import org.olcbox.app.ui.features.home.components.RelayNotice
import org.olcbox.app.ui.features.home.components.RoomBoard
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.icons.PkIcons

/**
 * The home screen with no view models in it.
 *
 * Split out so the layout can be rendered from a test with fabricated data — a
 * redesign that cannot be looked at until it is on a phone is a redesign built
 * blind — and so the two-pane layout has one body to rearrange rather than two
 * to keep in step.
 */

/** The pinned bands: what the header, the status strip and the board head show. */
@Immutable
data class HomeChrome(
    val tag: String,
    val statusLabel: String,
    /** Deferred: these move every second — see PkStatusStrip. */
    val statusMeta: () -> String,
    val statusValue: () -> String,
    val isActive: Boolean,
    val isBusy: Boolean,
    val trafficTrace: () -> List<Float>,
    val notice: String?,
    /** Whether [notice] is a failure the user may wave away, rather than a block. */
    val noticeDismissible: Boolean,
    val heading: String,
    val sortLabel: String,
    val action: PkAction,
    val showAppSettingsButton: Boolean,
    val showSplitTunnelingButton: Boolean,
    val showLock: Boolean
)

/** Everything the scrolling list needs to draw itself. */
@Immutable
data class HomeBoard(
    val model: BoardModel,
    val selectedLocationId: String?,
    val isConnected: Boolean,
    val pingsState: PingsState,
    val pingsStale: Boolean = false,
    val olcrtcSlots: Map<String, OlcrtcSlots>,
    val occupancyHistory: Map<String, List<Float>>,
    /** Storage ids whose room key the coordinator no longer recognises. */
    val revokedKeys: Set<String>,
    val transportFilter: String?,
    val isRefreshingSubscriptions: Boolean,
    val refreshingSubscriptionUrl: String?,
    /** Server lists whose first row is the virtual Lowest selector. */
    val lowestSubscriptionUrls: Set<String>,
    val collapsible: Boolean,
    val showSettings: Boolean,
    val showCustomLocation: Boolean,
    val showGetSubscription: Boolean
)

/** One place for every callback, so the signature below stays readable. */
@Immutable
data class HomeCallbacks(
    val onBrandTap: () -> Unit,
    val onDiagnosticsClick: () -> Unit,
    val onLockClick: () -> Unit,
    val onSplitTunnelingClick: () -> Unit,
    val onAddClick: () -> Unit,
    val onSettingsClick: () -> Unit,
    val onSortClick: () -> Unit,
    val onFilterSelected: (String?) -> Unit,
    val onActionClick: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onPullToRefresh: () -> Unit,
    val onLocationSelected: (String) -> Unit,
    val onLowestSelected: (subscriptionUrl: String, fallbackLocationId: String) -> Unit,
    val onLocationSettingsClick: (String) -> Unit,
    val onMeasure: (List<String>) -> Unit,
    val onRefreshSubscriptionClick: (String) -> Unit,
    val onDeleteLocationClick: (String) -> Unit,
    val onDeleteSubscriptionClick: (String) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onAddLocationClick: () -> Unit,
    val onGetSubscriptionClick: () -> Unit,
    val canPing: (LocationConfig) -> Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    chrome: HomeChrome,
    board: HomeBoard,
    callbacks: HomeCallbacks,
    scrollState: ScrollState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().then(pkScreenBackground())) {
        // App Review ran on an iPad Air. A phone layout stretched across it reads
        // as a template on its own, whatever is drawn inside — the seat pips grow
        // to a foot wide and the names float alone on the left.
        //
        // A width breakpoint rather than material3-window-size-class: the question
        // is "is there room for two panes", the answer is a number, and the
        // desktop gets the same layout for free without a new dependency.
        val twoPane = maxWidth >= TWO_PANE_BREAKPOINT

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { HomeTopBands(chrome, board, callbacks, twoPane = twoPane) },
            bottomBar = {
                // On one pane the bar spans the screen. On two it belongs under
                // the room it acts on, which is the right one.
                if (!twoPane) {
                    PkActionBar(action = chrome.action, onClick = callbacks.onActionClick)
                }
            }
        ) { innerPadding ->
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    HomeBoardColumn(
                        board = board,
                        callbacks = callbacks,
                        scrollState = scrollState,
                        modifier = Modifier.weight(BOARD_PANE_WEIGHT)
                    )
                    Column(modifier = Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 6.dp)
                        ) {
                            RoomDetailPane(board = board, callbacks = callbacks)
                        }
                        PkActionBar(
                            action = chrome.action,
                            onClick = callbacks.onActionClick
                        )
                    }
                }
            } else {
                HomeBoardColumn(
                    board = board,
                    callbacks = callbacks,
                    scrollState = scrollState,
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                )
            }
        }
    }
}

/**
 * The board's own column, with pull-to-refresh over it.
 *
 * One function so the phone layout and the wide layout's left pane cannot drift
 * apart — they are the same list, at two widths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBoardColumn(
    board: HomeBoard,
    callbacks: HomeCallbacks,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    // Pull down on the list to fetch the server lists again. It was reachable
    // only from a menu item inside the "+" sheet, which is not where anyone
    // looks for it on a list of servers.
    PullToRefreshBox(
        isRefreshing = board.isRefreshingSubscriptions,
        onRefresh = callbacks.onPullToRefresh,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))
            HomeRoomBoard(board, callbacks)
            Spacer(Modifier.height(20.dp))
            PkVersionFooter()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Wide enough for a board and a room side by side. An iPad in either rotation. */
private val TWO_PANE_BREAKPOINT = 720.dp

/** The board earns the larger share: it is the thing being read. */
private const val BOARD_PANE_WEIGHT = 1.25f
private const val DETAIL_PANE_WEIGHT = 1f

@Composable
private fun HomeTopBands(
    chrome: HomeChrome,
    board: HomeBoard,
    callbacks: HomeCallbacks,
    twoPane: Boolean
) {
    Column(modifier = Modifier.statusBarsPadding()) {
        PkHeaderRow(tag = chrome.tag, onBrandTap = callbacks.onBrandTap) {
            PkIconButton(
                icon = PkIcons.History,
                contentDescription = "Diagnostics",
                onClick = callbacks.onDiagnosticsClick
            )
            if (chrome.showLock) {
                PkIconButton(
                    icon = Icons.Outlined.Lock,
                    contentDescription = "Lock admin",
                    onClick = callbacks.onLockClick
                )
            }
            if (chrome.showSplitTunnelingButton) {
                PkIconButton(
                    icon = PkIcons.Shield,
                    contentDescription = "Split tunneling",
                    onClick = callbacks.onSplitTunnelingClick
                )
            }
            PkIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add connection",
                onClick = callbacks.onAddClick,
                tint = MaterialTheme.colorScheme.onSurface
            )
            if (chrome.showAppSettingsButton) {
                PkIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Application settings",
                    onClick = callbacks.onSettingsClick
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PkStatusStrip(
                label = chrome.statusLabel,
                isActive = chrome.isActive,
                isBusy = chrome.isBusy,
                meta = chrome.statusMeta,
                value = chrome.statusValue,
                trafficTrace = chrome.trafficTrace
            )
            chrome.notice?.let { notice ->
                Spacer(Modifier.height(11.dp))
                RelayNotice(
                    text = notice,
                    onDismiss = callbacks.onDismissNotice.takeIf { chrome.noticeDismissible }
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // The head sits over the board, so on two panes it is only as wide as the
        // board is — a heading centred over a room detail it does not describe is
        // the stretched-phone problem in miniature.
        PkBoardHead(
            heading = chrome.heading,
            sortLabel = chrome.sortLabel,
            onSortClick = callbacks.onSortClick,
            showSort = !board.model.isEmpty,
            modifier = if (twoPane) {
                Modifier.fillMaxWidth(BOARD_PANE_WEIGHT / (BOARD_PANE_WEIGHT + DETAIL_PANE_WEIGHT))
            } else {
                Modifier
            },
            chips = if (board.model.showChips) {
                {
                    BoardFilterChips(
                        model = board.model,
                        activeFilterKey = board.transportFilter,
                        onFilterSelected = callbacks.onFilterSelected
                    )
                }
            } else {
                null
            }
        )
        PkHairline()
    }
}

@Composable
private fun HomeRoomBoard(board: HomeBoard, callbacks: HomeCallbacks) {
    RoomBoard(
        model = board.model,
        selectedLocationId = board.selectedLocationId,
        isConnected = board.isConnected,
        pingsState = board.pingsState,
        pingsStale = board.pingsStale,
        olcrtcSlots = board.olcrtcSlots,
        occupancyHistory = board.occupancyHistory,
        revokedKeys = board.revokedKeys,
        canPing = callbacks.canPing,
        collapsible = board.collapsible,
        showSettings = board.showSettings,
        showCustomLocation = board.showCustomLocation,
        showGetSubscription = board.showGetSubscription,
        refreshingSubscriptionUrl = board.refreshingSubscriptionUrl,
        lowestSubscriptionUrls = board.lowestSubscriptionUrls,
        onLocationSelected = callbacks.onLocationSelected,
        onLowestSelected = callbacks.onLowestSelected,
        onLocationSettingsClick = callbacks.onLocationSettingsClick,
        onMeasure = callbacks.onMeasure,
        onRefreshSubscriptionClick = callbacks.onRefreshSubscriptionClick,
        onDeleteLocationClick = callbacks.onDeleteLocationClick,
        onDeleteSubscriptionClick = callbacks.onDeleteSubscriptionClick,
        onOpenUrl = callbacks.onOpenUrl,
        onAddSubscriptionClick = callbacks.onAddClick,
        onAddLocationClick = callbacks.onAddLocationClick,
        onGetSubscriptionClick = callbacks.onGetSubscriptionClick
    )
}
