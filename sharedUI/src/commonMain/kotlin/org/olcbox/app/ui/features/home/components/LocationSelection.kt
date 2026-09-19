package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.home.TransportMismatch
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind
import org.olcbox.app.ui.components.kit.PkDashedAction
import org.olcbox.app.ui.components.kit.PkFilterChip
import org.olcbox.app.ui.components.kit.PkGroupHeader
import org.olcbox.app.ui.components.kit.PkIconButton
import org.olcbox.app.ui.components.kit.PkPlanBar
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.PkSectionEyebrow
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.ui.components.kit.pkSubscriptionHost
import org.olcbox.app.ui.components.kit.pkSubscriptionIsSecret
import org.olcbox.app.ui.components.kit.planFraction
import org.olcbox.app.ui.components.kit.roomIsBlocked
import org.olcbox.app.ui.components.kit.seatCountText
import org.olcbox.app.ui.components.kit.seatDisplay
import org.olcbox.app.ui.components.kit.seatFreeText
import org.olcbox.app.ui.components.kit.transportTag
import org.olcbox.app.ui.components.kit.wireShape
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.ui.features.locations.components.LatencyButton
import org.olcbox.app.ui.features.locations.components.SubscriptionRefreshButton
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.util.formatDate
import org.olcbox.app.util.nowMillis
import org.olcbox.app.util.parseEmojiAndName

/**
 * The board: what is on it, and how it is drawn.
 *
 * Split in two on purpose. The filter chips and the heading are pinned above the
 * list while the rows scroll under them, so what the list *is* has to be computed
 * once, up in the screen, and handed to both halves — otherwise the chip counts
 * and the rows can disagree about the same list.
 */

// ── what is on the board ───────────────────────────────────────────────────

/**
 * A chip in the transport filter. [order] keeps chips in protocol order (Reality,
 * Hysteria2, XHTTP, …) rather than whatever order locations happen to arrive in.
 */
@Immutable
data class TransportFilterOption(
    val key: String,
    val label: String,
    val order: Int
)

/** One server list, with the rows it contributed after filtering and sorting. */
@Immutable
data class BoardGroup(
    val key: String,
    val locations: List<LocationItem>
)

@Immutable
data class BoardModel(
    val filterOptions: List<TransportFilterOption>,
    val filterCounts: Map<String, Int>,
    val totalCount: Int,
    val subscriptionGroups: List<BoardGroup>,
    val customLocations: List<LocationItem>,
    /** Whether anything on the board has seats, which decides what it is called. */
    val hasRooms: Boolean,
    val isEmpty: Boolean
) {
    /** Chips earn their row only from two options up; one chip is noise. */
    val showChips: Boolean get() = filterOptions.size > 1
}

/**
 * [buildBoardModel], kept until its inputs actually move.
 *
 * It filters, sorts and groups the whole location list, and it ran on every
 * recomposition of the home screen — which, while a session is up, was every
 * second. Nothing about the board changes when a clock ticks.
 */
@Composable
fun rememberBoardModel(
    locations: List<LocationItem>,
    activeFilterKey: String?,
    sort: SubscriptionSort,
    lowestSubscriptionUrls: Set<String> = emptySet(),
    pingsState: PingsState
): BoardModel = remember(locations, activeFilterKey, sort, lowestSubscriptionUrls, pingsState) {
    // Keyed on the ping state itself, not on a `(String) -> Int?` built at the call
    // site: that lambda is a fresh object every composition, so remembering on it
    // would never hit and this would be memoisation in name only.
    buildBoardModel(locations, activeFilterKey, sort, lowestSubscriptionUrls) { id -> pingsState.pingFor(id) }
}

/**
 * Filters, sorts and groups in one pass.
 *
 * [activeFilterKey] is resolved rather than repaired: a filter left over from a
 * server list that no longer serves that transport simply reads as "All". Writing
 * the state back here would be a write during composition.
 */
fun buildBoardModel(
    locations: List<LocationItem>,
    activeFilterKey: String?,
    sort: SubscriptionSort,
    lowestSubscriptionUrls: Set<String> = emptySet(),
    pingFor: (String) -> Int?
): BoardModel {
    // olcRTC's own carriers (VP8 / SEI / DataChannel) sit one level below the
    // protocol — they describe how data rides inside the call, not how the tunnel
    // is reached. They earn their own chips only when a user actually has more
    // than one, so the usual single olcRTC entry stays one chip.
    val splitOlcrtcCarriers = locations
        .mapNotNull { it.config }
        .filter { it.transportKind() == TransportKind.Olcrtc }
        .map { it.transport }
        .distinct()
        .size > 1

    val optionPerLocation = locations.mapNotNull { it.transportFilterOption(splitOlcrtcCarriers) }
    val counts = optionPerLocation.groupingBy { it.key }.eachCount()
    val options = optionPerLocation.distinctBy { it.key }.sortedBy { it.order }
    val active = activeFilterKey?.let { key -> options.firstOrNull { it.key == key } }

    val visible = active
        ?.let { option ->
            locations.filter { it.transportFilterOption(splitOlcrtcCarriers)?.key == option.key }
        }
        ?: locations

    // Sorted within a group, never across: the grouping is what tells a user which
    // provider a row came from, and ordering the whole list by ping would shuffle
    // two server lists into each other.
    fun List<LocationItem>.sorted(effectiveSort: SubscriptionSort = sort): List<LocationItem> = when (effectiveSort) {
        SubscriptionSort.None -> this
        SubscriptionSort.Alphabetical -> sortedBy { item ->
            (item.metadata?.name?.takeIf { it.isNotBlank() } ?: item.fullName).lowercase()
        }
        // Unmeasured sinks rather than sorting as zero, which would put every row
        // nobody has probed yet at the top as if it were the fastest.
        SubscriptionSort.Ping -> sortedBy { item -> pingFor(item.storageId) ?: Int.MAX_VALUE }
    }

    val fromSubscriptions = visible.filter { !it.subscriptionUrl.isNullOrBlank() }
    val groups = fromSubscriptions
        .groupBy { it.subscriptionGroupKey() }
        .map { (key, items) ->
            val url = items.firstOrNull()?.subscriptionUrl?.trim()
            val groupSort = if (!url.isNullOrEmpty() && url in lowestSubscriptionUrls) {
                SubscriptionSort.Ping
            } else {
                sort
            }
            BoardGroup(key, items.sorted(groupSort))
        }

    return BoardModel(
        filterOptions = options,
        filterCounts = counts,
        totalCount = locations.size,
        subscriptionGroups = groups,
        customLocations = visible.filter { it.subscriptionUrl.isNullOrBlank() }.sorted(),
        hasRooms = locations.any { it.config?.transportKind() == TransportKind.Olcrtc },
        isEmpty = locations.isEmpty()
    )
}

/** The name and flag a row and the action bar both print for one location. */
fun locationDisplayParts(item: LocationItem): Pair<String, String> {
    val metadata = item.metadata
    val raw = metadata?.name?.takeIf { it.isNotBlank() } ?: item.fullName
    val fallbackIcon = metadata?.icon?.takeIf { it.isNotBlank() }
        ?: metadata?.subscription?.icon?.takeIf { it.isNotBlank() }
        ?: ""
    val (emoji, parsed) = parseEmojiAndName(raw, fallbackIcon)
    return emoji to parsed.ifBlank { item.config?.displayName().orEmpty() }
}

// ── the pinned chips ───────────────────────────────────────────────────────

@Composable
fun BoardFilterChips(
    model: BoardModel,
    activeFilterKey: String?,
    onFilterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val active = model.filterOptions.firstOrNull { it.key == activeFilterKey }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // The row scrolls, so the last chip would otherwise sit flush against the
        // screen edge and read as cut off rather than as scrollable.
        PkFilterChip(
            label = "All",
            selected = active == null,
            count = model.totalCount,
            onClick = { onFilterSelected(null) }
        )
        model.filterOptions.forEach { option ->
            PkFilterChip(
                label = option.label,
                selected = active?.key == option.key,
                count = model.filterCounts[option.key],
                onClick = {
                    onFilterSelected(if (active?.key == option.key) null else option.key)
                }
            )
        }
        Spacer(Modifier.width(16.dp))
    }
}

// ── the scrolling board ────────────────────────────────────────────────────

@Composable
fun RoomBoard(
    model: BoardModel,
    selectedLocationId: String?,
    isConnected: Boolean,
    pingsState: PingsState,
    pingsStale: Boolean = false,
    /** olcRTC occupancy by storage id; a missing entry renders no seats at all. */
    olcrtcSlots: Map<String, OlcrtcSlots>,
    /** How full each room has been, by storage id. Empty until a second poll lands. */
    occupancyHistory: Map<String, List<Float>>,
    /** Storage ids whose room key the coordinator no longer recognises. */
    revokedKeys: Set<String>,
    canPing: (LocationConfig) -> Boolean,
    collapsible: Boolean,
    showSettings: Boolean,
    showCustomLocation: Boolean,
    showGetSubscription: Boolean,
    refreshingSubscriptionUrl: String?,
    lowestSubscriptionUrls: Set<String>,
    onLocationSelected: (String) -> Unit,
    onLowestSelected: (subscriptionUrl: String, fallbackLocationId: String) -> Unit,
    onLocationSettingsClick: (String) -> Unit,
    onMeasure: (List<String>) -> Unit,
    onRefreshSubscriptionClick: (String) -> Unit,
    onDeleteLocationClick: (String) -> Unit,
    onDeleteSubscriptionClick: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAddSubscriptionClick: () -> Unit,
    onAddLocationClick: () -> Unit,
    onGetSubscriptionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (model.isEmpty) {
        RelaySetupCard(
            modifier = modifier,
            onAddLocationClick = onAddLocationClick,
            showCustomLocation = showCustomLocation
        )
        return
    }

    // Which groups are folded away. Two server lists of a dozen exits each is most
    // of a phone screen before a user has scrolled at all, and the one they are not
    // using is pure noise.
    //
    // Saveable, not merely remembered: opening a location's settings and coming
    // back would otherwise unfold everything again.
    val collapsed = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf<String>() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (pingsStale) {
            Text("Previous network measurements · tap Measure to refresh", style = MaterialTheme.typography.labelSmall)
        }
        model.subscriptionGroups.forEach { group ->
            val isCollapsed = collapsible && group.key in collapsed
            val ids = group.locations.map { it.storageId }
            // A group comes from groupBy over at least one location.
            val first = group.locations.first()
            val groupUrl = first.subscriptionUrl?.trim()
            val lowestEnabled = !groupUrl.isNullOrBlank() && groupUrl in lowestSubscriptionUrls
            // Lowest is stored per subscription, but only the list containing
            // the active location is the current selection. Highlighting every
            // enabled preference looked like several live VPN connections.
            val lowestSelected = lowestEnabled &&
                group.locations.any { it.storageId == selectedLocationId }
            val isPinging = pingsState is PingsState.Loading &&
                pingsState.pendingLocationIds.any { it in ids }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                val subscription = first.metadata?.subscription
                val fraction = planFraction(subscription?.used, subscription?.available)

                PkGroupHeader(
                    title = first.subscriptionTitle().ifBlank { "Server list" },
                    // Both the quota and the expiry move into the bar where there
                    // is one, rather than being printed twice in two shapes. What
                    // is left on this line is how stale the list is, which is
                    // short enough to survive four buttons beside it.
                    meta = subscriptionMetaLine(
                        quota = if (fraction == null) first.subscriptionQuota() else null,
                        expiresAtEpochMs = subscription?.expiresAtEpochMs
                            ?.takeIf { fraction == null },
                        lastRefreshAtEpochMs = subscription?.lastRefreshAtEpochMs,
                        nowEpochMs = nowMillis(),
                        formatDate = ::formatDate
                    ),
                    collapsed = isCollapsed,
                    collapsible = collapsible,
                    holdsSelection = group.locations.any { it.storageId == selectedLocationId },
                    onToggle = { if (!collapsed.remove(group.key)) collapsed.add(group.key) }
                ) {
                    // Every control the group has, on the title's line: an i in a
                    // circle for the provider's page, a paper plane for its
                    // support, a bolt that measures and circling arrows that fetch
                    // the list again.
                    subscription?.webPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        PkIconButton(
                            icon = PkIcons.Info,
                            contentDescription = "Open the provider's site",
                            onClick = { onOpenUrl(url) },
                            size = 32,
                            corner = 9
                        )
                    }
                    subscription?.supportUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        PkIconButton(
                            icon = PkIcons.Send,
                            contentDescription = "Contact support",
                            onClick = { onOpenUrl(url) },
                            size = 32,
                            corner = 9
                        )
                    }
                    if (group.locations.any { it.config?.let(canPing) == true }) {
                        LatencyButton(isRunning = isPinging, onClick = { onMeasure(ids) })
                    }
                    if (!groupUrl.isNullOrBlank()) {
                        SubscriptionRefreshButton(
                            isRefreshing = groupUrl == refreshingSubscriptionUrl,
                            onClick = { onRefreshSubscriptionClick(groupUrl) }
                        )
                        // Beside the arrows that fetch the list, the one that
                        // drops it. Removing a list used to live in application
                        // settings, two screens from the list it removes.
                        PkIconButton(
                            icon = PkIcons.Delete,
                            contentDescription = "Remove this server list",
                            onClick = { onDeleteSubscriptionClick(groupUrl) }
                        )
                    }
                }

                if (fraction != null && !isCollapsed) {
                    PkPlanBar(
                        label = planLabel(subscription?.expiresAtEpochMs, nowMillis()),
                        value = first.subscriptionQuota().orEmpty(),
                        fraction = fraction
                    )
                }

                if (!isCollapsed) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (!groupUrl.isNullOrBlank()) {
                            val measurable = group.locations.filter { it.config?.let(canPing) == true }
                            val measuredPings = group.locations.mapNotNull { pingsState.pingFor(it.storageId) }
                            val selectedServer = group.locations
                                .firstOrNull { it.storageId == selectedLocationId }
                                ?.let { locationDisplayParts(it).second }
                            PkRoomCard(
                                title = "Lowest latency",
                                tag = "AUTO",
                                emoji = "⚡",
                                selected = lowestSelected,
                                connectedHere = lowestSelected && isConnected,
                                blocked = false,
                                seats = SeatDisplay.None,
                                seatCountText = null,
                                freeText = null,
                                freeIsFull = false,
                                freeIsTight = false,
                                history = emptyList(),
                                pingMs = measuredPings.minOrNull(),
                                isMeasuring = isPinging,
                                isOffline = measurable.isNotEmpty() && measurable.all {
                                    pingsState.isOffline(it.storageId)
                                },
                                keyGone = false,
                                wire = if (lowestSelected && isConnected && !selectedServer.isNullOrBlank()) {
                                    "CONNECTED VIA $selectedServer"
                                } else {
                                    "MEASURE THIS SERVER LIST AND CONNECT TO ITS FASTEST AVAILABLE SERVER"
                                },
                                onClick = { onLowestSelected(groupUrl, first.storageId) },
                                onMeasure = if (measurable.isNotEmpty()) ({ onMeasure(ids) }) else null
                            )
                        }
                        group.locations.forEach { location ->
                            BoardRoomCard(
                                location = location,
                                selected = !lowestSelected && location.storageId == selectedLocationId,
                                isConnected = isConnected,
                                pingsState = pingsState,
                                slots = olcrtcSlots[location.storageId],
                                history = occupancyHistory[location.storageId].orEmpty(),
                                keyGone = location.storageId in revokedKeys,
                                canPing = canPing,
                                onClick = { onLocationSelected(location.storageId) },
                                onLongClick = if (showSettings) {
                                    { onLocationSettingsClick(location.storageId) }
                                } else {
                                    null
                                },
                                onMeasure = { onMeasure(listOf(location.storageId)) }
                            )
                        }
                    }
                }
            }
        }

        if (model.customLocations.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                val customIds = model.customLocations.map { it.storageId }
                val isCustomPinging = pingsState is PingsState.Loading &&
                    pingsState.pendingLocationIds.any { it in customIds }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PkSectionEyebrow(text = "Custom locations", modifier = Modifier.weight(1f))
                    if (model.customLocations.any { it.config?.let(canPing) == true }) {
                        LatencyButton(
                            isRunning = isCustomPinging,
                            onClick = { onMeasure(customIds) }
                        )
                    }
                }
                model.customLocations.forEach { location ->
                    BoardRoomCard(
                        location = location,
                        selected = location.storageId == selectedLocationId,
                        isConnected = isConnected,
                        pingsState = pingsState,
                        slots = olcrtcSlots[location.storageId],
                        history = occupancyHistory[location.storageId].orEmpty(),
                        keyGone = location.storageId in revokedKeys,
                        canPing = canPing,
                        onClick = { onLocationSelected(location.storageId) },
                        // Always, unlike the rows above. A server list owns its
                        // locations and editing one by hand is plumbing, so that
                        // stays behind the admin gate. A custom location was added
                        // by the person looking at it, and anyone who can add one
                        // must be able to delete it.
                        onLongClick = { onLocationSettingsClick(location.storageId) },
                        onMeasure = { onMeasure(listOf(location.storageId)) },
                        // Shown, not hidden behind a long press: this row exists
                        // because the user typed it in, and until now the only way
                        // to take it back out was a gesture nothing announces and
                        // a settings screen the admin gate can hide entirely.
                        onDelete = { onDeleteLocationClick(location.storageId) }
                    )
                }
            }
        }

        if (showCustomLocation) {
            PkDashedAction(
                label = "Create custom location",
                icon = Icons.Outlined.Add,
                onClick = onAddLocationClick
            )
        }

        PkDashedAction(
            label = "Add server list",
            icon = Icons.Outlined.Add,
            onClick = onAddSubscriptionClick
        )
    }
}

/** The trash on a custom card: the row's own affordance, not a button on it. */
@Composable
private fun RemoveAffordance(label: String, onClick: () -> Unit) {
    val palette = LocalPkPalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, onClickLabel = label, role = Role.Button),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = PkIcons.Delete,
            contentDescription = label,
            tint = palette.textMuted,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun BoardRoomCard(
    location: LocationItem,
    selected: Boolean,
    isConnected: Boolean,
    pingsState: PingsState,
    slots: OlcrtcSlots?,
    history: List<Float>,
    keyGone: Boolean,
    canPing: (LocationConfig) -> Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMeasure: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val (emoji, name) = locationDisplayParts(location)
    // The one thing the app is certain of: it is connected, and to this location.
    val connectedHere = selected && isConnected
    val seats = seatDisplay(slots, mine = connectedHere)
    val config = location.config
    PkRoomCard(
        title = name,
        tag = transportTag(config),
        emoji = emoji,
        selected = selected,
        connectedHere = connectedHere,
        blocked = roomIsBlocked(slots, mine = connectedHere),
        seats = seats,
        seatCountText = seatCountText(slots),
        freeText = seatFreeText(slots),
        freeIsFull = slots != null && slots.slots_free <= 0,
        freeIsTight = slots != null && slots.slots_free in 1..2,
        history = history,
        pingMs = pingsState.pingFor(location.storageId),
        isMeasuring = pingsState.isChecking(location.storageId),
        isOffline = pingsState.isOffline(location.storageId),
        keyGone = keyGone,
        notice = TransportMismatch.caption(config, location.metadata),
        wire = wireShape(config),
        onClick = onClick,
        onLongClick = onLongClick,
        // No MEASURE where the platform says nothing can be measured — a button
        // whose only outcome is a snackbar explaining that it cannot work is
        // worse than no button.
        onMeasure = if (config?.let(canPing) == true) onMeasure else null,
        trailing = onDelete?.let { remove ->
            {
                // Flat, not a PkIconButton: the boxed variant belongs on the group
                // header, where it sits with three others on a line of its own. On
                // a card it read as a widget dropped into the name, between the
                // protocol tag and the latency column.
                RemoveAffordance(label = "Remove ${'$'}name", onClick = remove)
            }
        }
    )
}

/** `PLAN · RESETS IN 12D`, or just `PLAN` where no expiry was reported. */
internal fun planLabel(expiresAtEpochMs: Long?, nowEpochMs: Long): String {
    val days = expiresAtEpochMs
        ?.let { (it - nowEpochMs) / DAY_MILLIS }
        ?.takeIf { it >= 0 }
        ?: return "Plan"
    return if (days == 0L) "Plan · resets today" else "Plan · resets in ${days}d"
}

// ── the empty board ────────────────────────────────────────────────────────

/**
 * What a board with nothing on it offers.
 *
 * There is no row here that points at a purchase. It was removed rather than
 * hidden: App Review read the app as a front end for a paid plan, and a control
 * that only a flag stands between us and shipping is not an answer to that.
 * `RoomBoard`'s `showGetSubscription` stays as the guard against one being added
 * back, which is why it is still a parameter nothing reads.
 */
@Composable
private fun RelaySetupCard(
    onAddLocationClick: () -> Unit,
    showCustomLocation: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PkSectionEyebrow("Nothing here yet")

        // What the app is, said once, on the one screen a first-run user and an
        // App Store reviewer both see. The empty state used to be the words
        // "Import a server list to start" and nothing else — which was submitted
        // as screenshot one, and describes every client on the store.
        PkEmptyBoardNote()

        // No "add a server list" affordance here: the action bar at the bottom of
        // this screen already is one, in lime, full width. Two of them a thumb
        // apart is one control too many, not a choice.
        if (showCustomLocation) {
            Spacer(Modifier.height(2.dp))
            PkDashedAction(
                label = "Create custom location",
                icon = Icons.Outlined.Add,
                onClick = onAddLocationClick
            )
        }
    }
}

@Composable
private fun PkEmptyBoardNote() {
    val palette = LocalPkPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Rooms with seats",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "An olcRTC relay holds a fixed number of seats, and a full room " +
                "cannot take you. Add a server list and its rooms appear here with " +
                "their occupancy moving as people come and go.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textDim
        )
    }
}

// ── ping state readers ─────────────────────────────────────────────────────

internal fun PingsState.pingFor(locationId: String): Int? = when (this) {
    PingsState.Idle -> null
    is PingsState.Loading ->
        if (currentPings.containsKey(locationId)) currentPings[locationId]
        else lastPings?.get(locationId)
    is PingsState.Success -> pings[locationId]
    is PingsState.Error -> lastPings?.get(locationId)
}

internal fun PingsState.isChecking(locationId: String): Boolean =
    this is PingsState.Loading && locationId in pendingLocationIds

internal fun PingsState.isOffline(locationId: String): Boolean = when (this) {
    PingsState.Idle -> false
    is PingsState.Loading ->
        currentPings.containsKey(locationId) && currentPings[locationId] == null
    is PingsState.Success -> pings.containsKey(locationId) && pings[locationId] == null
    is PingsState.Error -> false
}

// ── naming and metadata ────────────────────────────────────────────────────

private fun LocationItem.transportFilterOption(
    splitOlcrtcCarriers: Boolean
): TransportFilterOption? {
    val config = config ?: return null
    val kind = config.transportKind()
    if (kind == TransportKind.Olcrtc && splitOlcrtcCarriers) {
        return TransportFilterOption(
            key = "${kind.name}:${config.transport}",
            label = "${kind.label()} · ${config.transportName()}",
            order = kind.ordinal
        )
    }
    return TransportFilterOption(key = kind.name, label = kind.label(), order = kind.ordinal)
}

private fun LocationItem.subscriptionGroupKey(): String = listOfNotNull(
    metadata?.subscription?.name?.takeIf { it.isNotBlank() },
    subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
).joinToString("|").ifBlank { storageId }

internal fun LocationItem.subscriptionTitle(): String {
    val subscription = metadata?.subscription
    // Falling back to a literal labelled every unnamed server list identically, so
    // two of them read as the same heading twice. Identify by host instead —
    // except for an encrypted one, whose host is a thing its link was hiding.
    val secret = subscriptionUrl?.let { pkSubscriptionIsSecret(it, subscriptionOriginLink) } == true
    val name = subscription?.name?.takeIf { it.isNotBlank() }
        ?: subscriptionUrl?.takeUnless { secret }?.let { pkSubscriptionHost(it) }
        ?: if (secret) "Encrypted list" else "Server list"

    return listOfNotNull(subscription?.icon?.takeIf { it.isNotBlank() }, name).joinToString(" ")
}

private fun LocationItem.subscriptionQuota(): String? {
    val subscription = metadata?.subscription ?: return null
    return quotaText(subscription.used, subscription.available)
}

/**
 * The one line a server list gets under its name: what is left of the plan, when
 * it runs out, and how stale the list is.
 *
 * All of it comes from the provider's own response headers. It used to be two
 * lines *inside* the title's own column, which is why it truncated — four icon
 * buttons sat beside it and left it about half the width. Compacting it is the
 * other half of that fix: `Auto-update 12h` is gone because it is a setting
 * rather than a status and the Server lists screen states it, and the last
 * refresh became an age because "2h" is both shorter and the actual question.
 *
 * Pure, so it can be tested without a device clock or a platform formatter.
 */
internal fun subscriptionMetaLine(
    quota: String?,
    expiresAtEpochMs: Long?,
    lastRefreshAtEpochMs: Long?,
    nowEpochMs: Long,
    formatDate: (Long) -> String
): String? = listOfNotNull(
    quota?.takeIf { it.isNotBlank() },
    // The year stays. "exp 09.09" reads as expired for a plan that runs to 2027,
    // and four characters are not worth a wrong answer.
    expiresAtEpochMs?.let { "exp ${formatDate(it)}" },
    lastRefreshAtEpochMs?.let { "upd ${subscriptionAge(it, nowEpochMs)}" }
).joinToString(" · ").takeIf { it.isNotBlank() }

/** `now` / `12m` / `2h` / `3d`. A device whose clock ran backwards reads as `now`. */
internal fun subscriptionAge(lastRefreshAtEpochMs: Long, nowEpochMs: Long): String {
    val delta = (nowEpochMs - lastRefreshAtEpochMs).coerceAtLeast(0L)
    return when {
        delta < MINUTE_MILLIS -> "now"
        delta < HOUR_MILLIS -> "${delta / MINUTE_MILLIS}m"
        delta < DAY_MILLIS -> "${delta / HOUR_MILLIS}h"
        else -> "${delta / DAY_MILLIS}d"
    }
}

internal fun quotaText(used: String?, available: String?): String? = when {
    // "6.3/300 GB" when both sides are in the same unit, "9.4 MB / 300 GB" when
    // they are not. Saying GB twice costs five characters on a line that has to
    // fit a phone, and says nothing the once did not.
    !used.isNullOrBlank() && !available.isNullOrBlank() -> compactQuota(used, available)
    !used.isNullOrBlank() -> "$used used"
    !available.isNullOrBlank() -> "$available available"
    else -> null
}

private fun compactQuota(used: String, available: String): String {
    val usedUnit = used.trim().substringAfterLast(' ', missingDelimiterValue = "")
    val availableUnit = available.trim().substringAfterLast(' ', missingDelimiterValue = "")
    if (usedUnit.isBlank() || !usedUnit.equals(availableUnit, ignoreCase = true)) {
        return "$used / $available"
    }
    return "${used.trim().substringBeforeLast(' ')}/${available.trim().substringBeforeLast(' ')} " +
        availableUnit
}

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
