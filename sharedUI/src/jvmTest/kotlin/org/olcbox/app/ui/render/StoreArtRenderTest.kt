package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.kit.LocalPkBoardColumns
import org.olcbox.app.ui.components.kit.PkBrand
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.PkStatusStrip
import org.olcbox.app.ui.components.kit.ThroughputWindow
import org.olcbox.app.ui.components.kit.boardWords
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.components.kit.rememberBoardColumns
import org.olcbox.app.ui.components.kit.seatDisplay
import org.olcbox.app.ui.components.kit.seatFreeText
import org.olcbox.app.ui.components.kit.transportTag
import org.olcbox.app.ui.components.kit.wireShape
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.ui.theme.LocalPkPalette
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The pictures the App Store asks for beside the screenshots: the header of the
 * product page and the one shown in search results.
 *
 * Drawn by the app's own components — its status strip, its room cards, its
 * ground — so that what a person sees in the store is what they open, and so
 * that the pictures are made again from the code when the board changes rather
 * than redrawn by hand. Written only when `OLCBOX_RENDER_DIR` names a directory:
 *
 *     OLCBOX_RENDER_DIR=/tmp/store ./gradlew :sharedUI:jvmTest --rerun --tests '*StoreArtRenderTest*'
 *
 * What is on them is what the listing already says, and nothing review has ever
 * objected to beside it. The words are the app's name and its subtitle. There
 * is no allowance bar, no list of somebody else's, no price, no address, no
 * other platform — and, on the pictures meant for the store, no meeting
 * service's name: see [Look].
 *
 * Apple publishes the sizes and not the part of each picture that survives
 * every device, so everything that matters is kept to the middle: a 16:9
 * picture used for both places loses its top and bottom in one and its sides in
 * the other.
 */
class StoreArtRenderTest {
    /** One picture: its size in pixels, and how many of them a point of the drawing takes. */
    private class Art(val name: String, val width: Int, val height: Int, val density: Float, val stacked: Boolean)

    /**
     * What the rooms under the session are.
     *
     * [Rooms] is the set for the store. The card of the room a session is in
     * says what the session looks like from outside — "a Jitsi media session" —
     * and that is a third party's name in a picture a reviewer reads with
     * nothing beside it; every round this listing has lost was lost on what
     * stood next to the app, not on the app. So the rooms on it are the others
     * in the list, as they are when the one in use is further down.
     *
     * [Seat] has that card, "YOUR SEAT" and all. It is the better picture and
     * is drawn too, under its own name, for whoever decides the other way.
     */
    private enum class Look(val file: String) { Rooms(""), Seat("seat-") }

    private val arts = listOf(
        // The product page's header, 21:9.
        Art("header-3840x1646", 3840, 1646, density = 3.5f, stacked = false),
        // Search results, 3:2, at the largest size taken.
        Art("search-3840x2560", 3840, 2560, density = 4.6f, stacked = true),
        // 16:9, the one size both places take ("Use header asset in search results").
        // Drawn smaller in its frame than the other two: used for both, it is
        // cut to 21:9 in one place and to 3:2 in the other, and what is on it
        // has to be inside both.
        Art("universal-5244x2950", 5244, 2950, density = 5.2f, stacked = false)
    )

    @Test fun theStorePictures() {
        val dir = renderDir() ?: return
        Locale.setDefault(Locale.ENGLISH)
        for (look in Look.entries) for (art in arts) {
            val scene = ImageComposeScene(width = art.width, height = art.height, density = Density(art.density, 1f)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AppTheme { StoreArt(stacked = art.stacked, look = look) }
                }
            }
            try {
                // The halo's colour and the cards' borders arrive over frames.
                for (frame in 0..12) scene.render(frame * 100_000_000L).close()
                val png = scene.render(1_300_000_000L).use { picture ->
                    picture.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
                // The store takes no picture with an alpha channel, opaque or not.
                val drawn = ImageIO.read(ByteArrayInputStream(png))
                val flat = BufferedImage(drawn.width, drawn.height, BufferedImage.TYPE_INT_RGB)
                flat.createGraphics().apply { drawImage(drawn, 0, 0, null); dispose() }
                ImageIO.write(flat, "png", File(dir, "ghostlane-${look.file}${art.name}.png"))
            } finally {
                scene.close()
            }
        }
    }

    @Composable
    private fun StoreArt(stacked: Boolean, look: Look) {
        Box(Modifier.fillMaxSize().then(pkScreenBackground()), contentAlignment = Alignment.Center) {
            if (stacked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Words(centered = true)
                    Spacer(Modifier.height(34.dp))
                    // A card fewer under the words than beside them: one more
                    // leaves the picture no margin, and a search result is drawn
                    // with rounded corners.
                    Rooms(width = 400.dp, look = look, more = false)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Words(centered = false)
                    Spacer(Modifier.width(48.dp))
                    Rooms(width = 372.dp, look = look, more = true)
                }
            }
        }
    }

    /** The app's name, set as the app sets it, and the line the listing has under it. */
    @Composable
    private fun Words(centered: Boolean) {
        val palette = LocalPkPalette.current
        Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            Text(
                text = "OLCRTC CORE",
                style = pkMono(11, 2.4),
                color = palette.accent
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = PkBrand.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 62.sp,
                    lineHeight = 66.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = SUBTITLE,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = palette.textDim
            )
        }
    }

    /** A session in a room, and the rooms beside it: the screen the app opens on, at reading size. */
    @Composable
    private fun Rooms(width: Dp, look: Look, more: Boolean) {
        CompositionLocalProvider(LocalPkBoardColumns provides rememberBoardColumns()) {
            Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PkStatusStrip(
                    label = "in a room · olcRTC",
                    isActive = true,
                    isBusy = false,
                    meta = { "↓ 2.6 GB   ↑ 516 MB · HTTP 143ms" },
                    value = { "6:51:24" },
                    traffic = session()
                )
                val mine = look == Look.Seat
                Room("DE · olcRTC", OlcrtcSlots(slots_total = 8, slots_free = 5), ping = 128, mine = mine, pinned = true)
                // The chosen card is twice the height of the others, so it
                // stands for one of them.
                if (!mine || more) {
                    Room("NL · olcRTC", OlcrtcSlots(slots_total = 8, slots_free = 2), ping = 164, mine = false, pinned = false)
                }
                if (!mine && more) {
                    Room("FR · olcRTC", OlcrtcSlots(slots_total = 8, slots_free = 8), ping = 96, mine = false, pinned = false)
                }
            }
        }
    }

    /**
     * Three quarters of a minute of a session that ends in the middle of a
     * video: the newest bars are the tall ones, beside the figures that name
     * them, where a session that ends idle is a chart of what has stopped.
     */
    private fun session(): ThroughputWindow = ThroughputWindow().apply {
        var received = 0L
        var sent = 0L
        record(received, sent)
        repeat(seconds) { second ->
            val (down, up) = when {
                second < 6 -> 500L to 350L
                second < 10 -> 420_000L + second * 30_000L to 26_000L
                second < 17 -> 2_400L to 1_100L
                second < 23 -> 60_000L to 780_000L + (second % 3) * 110_000L
                second < 27 -> 1_800L to 900L
                else -> 1_650_000L + (second % 5) * 240_000L to 52_000L + (second % 3) * 8_000L
            }
            received += down
            sent += up
            record(received, sent)
        }
    }

    @Composable
    private fun Room(name: String, slots: OlcrtcSlots, ping: Int, mine: Boolean, pinned: Boolean) {
        val words = boardWords()
        val config = LocationConfig(
            name = name,
            id = "room",
            key = "k".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_JITSI,
            transport = LocationConfig.TRANSPORT_DATACHANNEL
        ).normalized()
        val level = slots.used.toFloat() / slots.slots_total
        PkRoomCard(
            title = name,
            tag = transportTag(config),
            emoji = null,
            selected = mine,
            connectedHere = mine,
            blocked = false,
            seats = seatDisplay(slots, mine = mine),
            freeText = seatFreeText(slots, words),
            freeIsFull = false,
            freeIsTight = slots.slots_free in 1..2,
            history = List(8) { step -> (level * (0.6f + 0.05f * step)).coerceIn(0f, 1f) },
            pingMs = ping,
            isMeasuring = false,
            isOffline = false,
            keyGone = false,
            wire = wireShape(config, words),
            onClick = {},
            onMeasure = {},
            pinned = pinned,
            onPinToggle = {}
        )
    }

    private companion object {
        /** The listing's own subtitle, word for word: `docs/app-store-listing.md`. */
        const val SUBTITLE = "A tunnel inside a video call"
    }
}
