package org.olcbox.app.ui.render

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind
import org.olcbox.app.ui.components.kit.ThroughputWindow
import org.olcbox.app.ui.components.kit.boardAction
import org.olcbox.app.ui.components.kit.boardHeading
import org.olcbox.app.ui.components.kit.boardWords
import org.olcbox.app.ui.components.kit.roomIsBlocked
import org.olcbox.app.ui.components.kit.sortLabel
import org.olcbox.app.ui.features.home.HomeBoard
import org.olcbox.app.ui.features.home.HomeCallbacks
import org.olcbox.app.ui.features.home.HomeChrome
import org.olcbox.app.ui.features.home.HomeScreenContent
import org.olcbox.app.ui.features.home.components.buildBoardModel
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.features.home.components.pin
import org.olcbox.app.ui.features.home.statusLabel
import org.olcbox.app.ui.features.home.statusMeta
import org.olcbox.app.ui.features.home.statusValue
import org.olcbox.app.ui.features.home.statusWords
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.util.nowMillis

/**
 * A home screen made of fabricated data, for looking at and for measuring.
 *
 * The lists are the shapes a phone really holds: our own encrypted list, whose
 * rows are `CC · olcRTC · carrier`, and a provider's list with flags in its
 * names, a traffic allowance and both of the provider's own links — which is the
 * head with five buttons on it.
 */
internal object BoardFixture {
    /** Our own encrypted list: thirty rooms, `CC · olcRTC · carrier`. */
    const val OUR_LIST = "https://proofkit.org/sub/0123456789abcdef0123456789abcdef/olcrtc?crypt=1"

    /** A provider's list, with flags in its names. */
    const val THEIR_LIST = "https://lists.example.org/sub/partner"
    private const val OURS = OUR_LIST
    private const val THEIRS = THEIR_LIST
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    private data class Carrier(val suffix: String?, val provider: String, val transport: String, val seats: Int)

    private val carriers = listOf(
        Carrier(null, LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_VP8CHANNEL, 16),
        Carrier("MTS", LocationConfig.PROVIDER_MTSLINK, LocationConfig.TRANSPORT_VP8CHANNEL, 9),
        Carrier("SJ", LocationConfig.PROVIDER_SALUTEJAZZ, LocationConfig.TRANSPORT_DATACHANNEL, 8),
        Carrier("VK", LocationConfig.PROVIDER_VKCALLS, LocationConfig.TRANSPORT_VP8CHANNEL, 8),
        Carrier("WB", LocationConfig.PROVIDER_WB_STREAM, LocationConfig.TRANSPORT_VP8CHANNEL, 8)
    )

    private val countries = listOf("CA", "DE", "ES", "FR", "NL", "US")

    /** The room the connected renders sit in. */
    const val ROOM_IN_USE = "ours-DE-WB"

    /** A row whose name opens with a flag, which is two code points and one glyph. */
    const val FLAGGED = "theirs-es"

    private fun rooms(now: Long): List<LocationItem> = countries.flatMap { country ->
        carriers.map { carrier ->
            val name = listOfNotNull("$country · olcRTC", carrier.suffix).joinToString(" · ")
            LocationItem(
                storageId = "ours-$country-${carrier.suffix ?: "TM"}",
                fullName = name,
                config = LocationConfig(
                    name = name,
                    id = "room-$country-${carrier.suffix ?: "tm"}",
                    key = "k".repeat(64),
                    bypassProvider = carrier.provider,
                    transport = carrier.transport
                ).normalized(),
                subscriptionUrl = OURS,
                subscriptionOriginLink = "olcrtc://crypt1/ZmFicmljYXRlZA",
                metadata = LocationMetadata(
                    name = name,
                    subscription = SubscriptionMetadata(lastRefreshAtEpochMs = now - 8 * MINUTE)
                )
            )
        }
    }

    private fun partner(now: Long): List<LocationItem> {
        val subscription = SubscriptionMetadata(
            name = "@Xrayvless_bot",
            used = "44 GB",
            available = "976 GB",
            expiresAtEpochMs = now + 12 * DAY,
            lastRefreshAtEpochMs = now,
            supportUrl = "https://t.me/example",
            webPageUrl = "https://lists.example.org"
        )
        fun row(id: String, name: String, kind: LocationKind, link: String) = LocationItem(
            storageId = id,
            fullName = name,
            config = LocationConfig(name = name, id = id, kind = kind, rawLink = link),
            subscriptionUrl = THEIRS,
            metadata = LocationMetadata(name = name, subscription = subscription)
        )
        val user = "11111111-1111-1111-1111-111111111111"
        val reality = "security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab&sni=www.example.com&fp=chrome"
        return listOf(
            row(FLAGGED, "🇪🇸 ES · Madrid", LocationKind.Vless, "vless://$user@198.51.100.7:443?type=tcp&$reality&flow=xtls-rprx-vision#es"),
            row("theirs-nl", "🇳🇱 Netherlands-Amsterdam-03", LocationKind.Vless, "vless://$user@198.51.100.8:443?type=xhttp&$reality&path=%2Fx&mode=auto#nl"),
            row("theirs-us", "🇺🇸 United States · New York", LocationKind.Hysteria2, "hysteria2://PW@198.51.100.9:443?sni=h&obfs=salamander&obfs-password=x#us")
        )
    }

    private fun custom(): List<LocationItem> = listOf(
        LocationItem(
            storageId = "custom-1",
            fullName = "My own room",
            config = LocationConfig(
                name = "My own room",
                id = "room-custom",
                key = "k".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_VP8CHANNEL
            ).normalized()
        ),
        // A link that asked for a transport its service cannot carry: the card
        // that says so, in a line of its own under the name.
        LocationItem(
            storageId = "custom-2",
            fullName = "Jitsi room",
            config = LocationConfig(
                name = "Jitsi room",
                id = "room-jitsi",
                key = "k".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_JITSI,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ).normalized(),
            metadata = LocationMetadata(requestedTransport = LocationConfig.TRANSPORT_VP8CHANNEL)
        )
    )

    /** What the band under the status strip says when a connection could not be made. */
    const val NOTICE = "The room did not answer. It may be full or closed; pick another room or refresh the server list."

    /** A room whose key the coordinator has stopped recognising. */
    const val REVOKED = "ours-FR-VK"

    fun locations(now: Long = nowMillis()): List<LocationItem> = rooms(now) + partner(now) + custom()

    /** What a card prints for a location: its name without the emoji it opens with. */
    fun nameOf(item: LocationItem): String = locationDisplayParts(item).second

    /**
     * A minute of a session that did what sessions do: idled, loaded a page,
     * streamed for a while with its acknowledgements going the other way, and
     * sent something of its own.
     */
    fun session(): ThroughputWindow = ThroughputWindow().apply {
        var received = 0L
        var sent = 0L
        record(received, sent)
        repeat(seconds + 6) { second ->
            val (down, up) = when {
                second < 8 -> 400L to 300L
                second < 12 -> 380_000L + second * 40_000L to 22_000L
                second < 18 -> 2_000L to 900L
                second < 34 -> 1_500_000L + (second % 5) * 260_000L to 48_000L + (second % 3) * 9_000L
                second < 40 -> 30_000L to 640_000L + (second % 4) * 120_000L
                else -> 900L + second * 40L to 500L
            }
            received += down
            sent += up
            record(received, sent)
        }
    }

    /** What the board is drawn in. */
    data class Scene(
        /** The session is up, and on [selectedId]. */
        val connected: Boolean,
        /** The tunnel has been asked for and has not answered: no timer, nothing counted. */
        val connecting: Boolean = false,
        val selectedId: String = ROOM_IN_USE,
        /** The admin's two extra header buttons, which is the header at its widest. */
        val admin: Boolean = false,
        /** Whether the rooms have answered how full they are. */
        val seats: Boolean = true,
        val measured: Boolean = true,
        val lowest: Boolean = false,
        /** No server list at all: the screen a new user opens. */
        val empty: Boolean = false,
        /** Something went wrong, said in the band under the status strip. */
        val notice: String? = null,
        /** How far down the list the screen opens, in points: for a picture of what is under the fold. */
        val scrolledBy: Int = 0,
        /** The lists the settings call folded away, by address. */
        val folded: Set<String> = emptySet(),
        /** The rooms pinned to the top of their lists, by storage id, in the order they were pinned. */
        val pinned: List<String> = emptyList(),
        /** The delays on the cards were measured on another network: the line over the lists that says so. */
        val stalePings: Boolean = false
    )

    @Composable
    fun Home(
        scene: Scene,
        onFold: (String) -> Unit = {},
        onPin: (String) -> Unit = {},
        onSelect: (String) -> Unit = {}
    ) {
        val now = remember { nowMillis() }
        val locations = remember { if (scene.empty) emptyList() else locations(now) }
        val pings: Map<String, Int?> = remember {
            if (!scene.measured) {
                emptyMap()
            } else {
                locations.mapIndexed { index, item ->
                    item.storageId to when (index % 7) {
                        3 -> null
                        5 -> 751
                        else -> 90 + index * 9
                    }
                }.toMap()
            }
        }
        val pingsState: PingsState = if (scene.measured) PingsState.Success(pings) else PingsState.Idle
        val lowestUrls = if (scene.lowest) setOf(OURS) else emptySet()
        val model = remember {
            val pinned = scene.pinned.mapNotNull { id -> locations.firstOrNull { it.storageId == id }?.pin() }
            buildBoardModel(
                locations,
                activeFilterKey = null,
                sort = SubscriptionSort.None,
                lowestSubscriptionUrls = lowestUrls,
                pinned = pinned
            ) { pings[it] }
        }
        val slots: Map<String, OlcrtcSlots> = remember {
            if (!scene.seats) {
                emptyMap()
            } else {
                locations.filter { it.config?.kind == LocationKind.Olcrtc }.mapIndexed { index, item ->
                    val total = carriers.firstOrNull { item.storageId.endsWith("-${it.suffix ?: "TM"}") }?.seats ?: 8
                    val used = when {
                        item.storageId == scene.selectedId && scene.connected -> 3
                        index % 9 == 4 -> total
                        index % 4 == 1 -> total - 2
                        else -> index % 3
                    }
                    item.storageId to OlcrtcSlots(slots_total = total, slots_free = total - used)
                }.toMap()
            }
        }
        val history = remember {
            slots.mapValues { (_, value) ->
                val level = value.used.toFloat() / value.slots_total
                List(8) { step -> (level * (0.6f + 0.05f * step)).coerceIn(0f, 1f) }
            }
        }

        // A session that has only been asked for has moved nothing yet.
        val traffic = remember { if (scene.connecting) ThroughputWindow() else session() }

        val selected = locations.firstOrNull { it.storageId == scene.selectedId }
        val selectedConfig = selected?.config
        val selectedSlots = slots[scene.selectedId]
        val selectedName = selected?.let { locationDisplayParts(it).second }
        val boardWords = boardWords()
        val statusWords = statusWords()

        HomeScreenContent(
            chrome = HomeChrome(
                tag = "OLCRTC CORE",
                statusLabel = statusLabel(
                    isConnected = scene.connected,
                    isConnecting = scene.connecting,
                    requiresSetup = scene.empty,
                    hasSeats = selectedSlots != null,
                    transportLabel = selectedConfig?.transportKind()?.label(),
                    words = statusWords
                ),
                statusMeta = {
                    statusMeta(
                        isConnected = scene.connected,
                        bytesLine = if (scene.connected) "↓ 2.6 GB   ↑ 516 MB" else "",
                        requiresSetup = scene.empty,
                        isFull = roomIsBlocked(selectedSlots, mine = scene.connected),
                        protocolLine = selectedConfig?.protocolLabels()?.joinToString(" · "),
                        words = statusWords
                    ) + if (scene.connected) " · HTTP 143ms" else ""
                },
                statusValue = {
                    statusValue(
                        isConnected = scene.connected,
                        connectedSince = now - (6 * 3600 + 51 * 60 + 24) * 1000L,
                        nowEpochMs = now,
                        exitName = selectedName
                    )
                },
                isActive = scene.connected,
                isBusy = scene.connecting,
                traffic = traffic,
                notice = scene.notice,
                noticeDismissible = scene.notice != null,
                heading = boardHeading(model.hasRooms, boardWords),
                sortLabel = sortLabel(SubscriptionSort.None, boardWords),
                action = boardAction(
                    requiresSetup = scene.empty,
                    isConnected = scene.connected,
                    isConnecting = scene.connecting,
                    selectedIsRoom = selectedConfig?.transportKind() == TransportKind.Olcrtc,
                    selectedIsFull = roomIsBlocked(selectedSlots, mine = scene.connected),
                    exitName = selectedName,
                    words = boardWords
                ),
                showAppSettingsButton = true,
                showSplitTunnelingButton = scene.admin,
                showLock = scene.admin
            ),
            board = HomeBoard(
                model = model,
                selectedLocationId = scene.selectedId,
                isConnected = scene.connected,
                pingsState = pingsState,
                pingsStale = scene.stalePings,
                olcrtcSlots = slots,
                occupancyHistory = history,
                revokedKeys = setOf(REVOKED),
                transportFilter = null,
                isRefreshingSubscriptions = false,
                refreshingSubscriptionUrl = null,
                lowestSubscriptionUrls = lowestUrls,
                foldedSubscriptionUrls = scene.folded,
                collapsible = true,
                showSettings = scene.admin,
                showCustomLocation = scene.admin,
                showGetSubscription = false
            ),
            callbacks = HomeCallbacks(
                onBrandTap = {},
                onDiagnosticsClick = {},
                onLockClick = {},
                onSplitTunnelingClick = {},
                onAddClick = {},
                onSettingsClick = {},
                onSortClick = {},
                onFilterSelected = {},
                onActionClick = {},
                onDismissNotice = {},
                onPullToRefresh = {},
                onLocationSelected = onSelect,
                onLowestSelected = { _, _ -> },
                onFoldToggle = onFold,
                onPinToggle = onPin,
                onLocationSettingsClick = {},
                onMeasure = {},
                onRefreshSubscriptionClick = {},
                onDeleteLocationClick = {},
                onDeleteSubscriptionClick = {},
                onOpenUrl = {},
                onAddLocationClick = {},
                onGetSubscriptionClick = {},
                canPing = { it.kind == LocationKind.Olcrtc }
            ),
            scrollState = rememberScrollState(initial = with(LocalDensity.current) { scene.scrolledBy.dp.roundToPx() }),
            snackbarHostState = remember { SnackbarHostState() }
        )
    }
}
