package org.olcbox.app.ui.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.ui.components.VpnDisclosureScreen
import org.olcbox.app.ui.components.kit.ThroughputWindow
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.features.home.components.AddConfigurationSheet
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.onboarding.OnboardingScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.ui.theme.LocalPkPalette
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The App Store's screenshots, drawn from the app's own screens.
 *
 * Each picture is made in two passes. First the screen itself, by the app's own
 * composables, on a phone of the size the picture is for and at the pixels it
 * has on the picture; then that screen as a card on the app's ground under a
 * short caption. The screen is a picture by the second pass, so a sheet and its
 * scrim stay inside the phone they belong to.
 *
 * Written only when `OLCBOX_RENDER_DIR` names a directory. `OLCBOX_RENDER_FONTS`
 * names one with an emoji font in it: the tests are shown the app's own fonts
 * and no others (`sharedUI/build.gradle.kts`), and without it every flag is an
 * empty box:
 *
 *     OLCBOX_RENDER_DIR=/tmp/shots OLCBOX_RENDER_FONTS=/path/to/emoji-font-dir \
 *         ./gradlew :sharedUI:jvmTest --rerun --no-configuration-cache --tests '*StoreScreenshotsRenderTest*'
 *
 * What may be on these pictures, and why each rule is there, is in
 * `docs/app-store-listing.md` under *Screenshots*: the order, the demo list, no
 * purchase and no price, no meeting service's name, the words of the captions.
 */
class StoreScreenshotsRenderTest {
    /** A slot of App Store Connect: the picture's pixels, and the phone whose screen it shows, in points. */
    private class Slot(val name: String, val widthPx: Int, val heightPx: Int, val widthDp: Int, val heightDp: Int)

    private val slots = listOf(
        Slot("6.9in-1320x2868", 1320, 2868, 440, 956),
        Slot("6.3in-1206x2622", 1206, 2622, 402, 874)
    )

    /**
     * [headCut]: the card shows the screen from under its head, drawn larger so
     * that the card is as tall as a whole screen's. An idle board says under
     * NOT CONNECTED which service the chosen room is on, and that line is not
     * for a picture in the store; the rest of the screen is.
     */
    private class Frame(
        val file: String,
        val caption: String,
        val under: String,
        val headCut: Boolean = false,
        val screen: @Composable () -> Unit
    )

    /**
     * The order is the listing's: the first two carry what no other app has, a
     * session in a room and the rooms with their seats. No frame is the empty
     * board, and none shows a purchase, a price, a provider's name or a
     * meeting service's: see `docs/app-store-listing.md`.
     */
    private val frames = listOf(
        Frame("01-in-a-room", "A tunnel inside\na video call", "Your session, its time and its traffic") {
            Board(connected = true)
        },
        // Not "free seats": the listing keeps the word "free" out of everything
        // that is read beside the app, whatever it is said of.
        Frame("02-rooms-and-seats", "Rooms, and\nhow full they are", "See the seats before you take one", headCut = true) {
            Board(connected = false)
        },
        Frame("03-what-it-is", "Carried over\nWebRTC", "The media transport a video call uses") {
            OnboardingScreen(onFinished = {}, onAddServerList = {})
        },
        Frame("04-how-it-works", "How the VPN\nconnection works", "Asked once, before the first connection", headCut = true) {
            // Asked over the board, at the first press of its button.
            Board(connected = false)
            VpnDisclosureScreen(onAccept = {}, onDecline = {})
        },
        Frame("05-add-a-list", "Bring your own\nserver list", "Scan a code, paste a link or import a file") {
            // The sheet over the screen its button is on, with an iPhone's rows:
            // nothing to buy and no custom location (`MainViewController`).
            Board(connected = true)
            AddConfigurationSheet(
                canScanQr = true,
                hasSubscriptions = true,
                onDismiss = {},
                onScanQrClick = {},
                onPasteLinkClick = {},
                onImportFileClick = {},
                onUpdateSubscriptionsClick = {},
                onAddCustomLocationClick = {},
                showGetSubscription = false,
                showCustomLocation = false
            )
        }
    )

    @Composable
    private fun Board(connected: Boolean) {
        BoardFixture.Home(BoardFixture.Scene(connected = connected, selectedId = ROOM_IN_USE), shop = shop)
    }

    /**
     * The demo list: one room a country, named as a list names them, each with
     * its flag (an iPhone draws the flag of a name that opens with a country's
     * code; a name that brings its own is drawn so everywhere).
     *
     * The room in use is far down the list on purpose. Its card says what the
     * session looks like from outside, "a <service> media session", and no
     * meeting service is named on a picture for the store.
     */
    private val shop = BoardFixture.Shop(
        rows = { now ->
            countries.map { (code, flag) ->
                val name = "$flag $code · olcRTC"
                LocationItem(
                    storageId = "demo-$code",
                    fullName = name,
                    config = LocationConfig(
                        name = name,
                        id = "room-demo-$code",
                        key = "k".repeat(64),
                        bypassProvider = LocationConfig.PROVIDER_JITSI,
                        transport = LocationConfig.TRANSPORT_DATACHANNEL
                    ).normalized(),
                    subscriptionUrl = DEMO_LIST,
                    metadata = LocationMetadata(
                        name = name,
                        subscription = SubscriptionMetadata(name = "Demo list", lastRefreshAtEpochMs = now - 8 * 60_000L)
                    )
                )
            }
        },
        // Eight seats a room; none full, two nearly.
        seats = countries.mapIndexed { index, (code, _) ->
            val free = listOf(5, 2, 8, 6, 3, 7, 1, 4, 8, 6, 5, 3)[index % 12]
            "demo-$code" to OlcrtcSlots(slots_total = 8, slots_free = free)
        }.toMap(),
        traffic = ::session,
        // Connected, the room in use is timed by a request through its own
        // tunnel: the figure the status strip prints as HTTP.
        delayMs = 143
    )

    @Test fun theScreenshots() {
        val dir = renderDir() ?: return
        // The listing is English only; the language is put back for the tests
        // that run after this one in the same JVM.
        val language = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (slot in slots) for (frame in frames) draw(slot, frame, dir)
        } finally {
            Locale.setDefault(language)
        }
    }

    private fun draw(slot: Slot, frame: Frame, dir: File) {
        // The screen, by the app's own composables, at the pixels the card
        // has: nothing is scaled between the app's drawing and the picture.
        val unit = slot.widthPx / CANVAS_WIDTH
        val cardWidth = if (frame.headCut) ZOOM_WIDTH else CARD_WIDTH
        val density = (cardWidth * unit).roundToInt().toFloat() / slot.widthDp
        val cutDp = if (frame.headCut) HEAD else 0
        val whole = screen(slot.widthDp, slot.heightDp, density, frame.screen)
        val shown = if (cutDp == 0) whole else withoutTop(whole, rows = (cutDp * density).roundToInt())
        val card = org.jetbrains.skia.Image.makeFromEncoded(shown).use { it.toComposeImageBitmap() }
        val cardHeight = cardWidth * (slot.heightDp - cutDp) / slot.widthDp
        val scene = ImageComposeScene(width = slot.widthPx, height = slot.heightPx, density = Density(unit, 1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                AppTheme { Captioned(frame, card, cardWidth, cardHeight) }
            }
        }
        val framed = try {
            for (tick in 0..3) scene.render(tick * 100_000_000L).close()
            scene.render(400_000_000L).use { picture -> picture.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        } finally {
            scene.close()
        }
        write(File(dir, "${slot.name}-${frame.file}.png"), framed)
    }

    private fun withoutTop(png: ByteArray, rows: Int): ByteArray {
        val drawn = ImageIO.read(ByteArrayInputStream(png))
        val rest = drawn.getSubimage(0, rows, drawn.width, drawn.height - rows)
        return ByteArrayOutputStream().also { ImageIO.write(rest, "png", it) }.toByteArray()
    }

    /**
     * A screen of the app on a phone [widthDp] by [heightDp] points. There is no
     * clock and no battery over it: those are the phone's, and nothing is on
     * these pictures that the app does not draw. The room they would take is
     * what keeps the screen clear of the card's corners.
     */
    private fun screen(widthDp: Int, heightDp: Int, density: Float, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(
            width = (widthDp * density).roundToInt(),
            height = (heightDp * density).roundToInt(),
            density = Density(density, 1f)
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                AppTheme {
                    Box(Modifier.fillMaxSize().then(pkScreenBackground()).padding(top = CARD_TOP.dp, bottom = CARD_BOTTOM.dp)) { content() }
                }
            }
        }
        try {
            // A sheet slides up and a colour arrives over frames; each frame is
            // closed as it is made (it is held outside the Java heap).
            for (tick in 0..15) scene.render(tick * 100_000_000L).close()
            return scene.render(1_600_000_000L).use { picture -> picture.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Captioned(frame: Frame, card: ImageBitmap, cardWidth: Float, cardHeight: Float) {
        val palette = LocalPkPalette.current
        val glow = palette.accent.copy(alpha = 0.16f)
        Box(Modifier.fillMaxSize().then(pkScreenBackground())) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(64.dp))
                Text(text = "GHOSTLANE", style = pkMono(11, 3.2), color = palette.accent)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = frame.caption,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 38.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.8).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = frame.under,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = palette.textDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp)
                )
                Spacer(Modifier.height(30.dp))
                Box(
                    Modifier
                        .drawBehind {
                            // The lamp over the card: the app's accent, once.
                            drawRect(
                                Brush.radialGradient(
                                    colors = listOf(glow, Color.Transparent),
                                    center = Offset(size.width / 2f, size.height * 0.18f),
                                    radius = size.width * 0.95f
                                ),
                                topLeft = Offset(-size.width, -size.height * 0.3f),
                                size = androidx.compose.ui.geometry.Size(size.width * 3f, size.height * 1.3f)
                            )
                        }
                ) {
                    Image(
                        bitmap = card,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .size(cardWidth.dp, cardHeight.dp)
                            .clip(RoundedCornerShape(CARD_CORNER.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(CARD_CORNER.dp))
                    )
                }
            }
        }
    }

    /** The store takes no picture with an alpha channel, opaque or not. */
    private fun write(file: File, png: ByteArray) {
        val drawn = ImageIO.read(ByteArrayInputStream(png))
        val flat = BufferedImage(drawn.width, drawn.height, BufferedImage.TYPE_INT_RGB)
        flat.createGraphics().apply { drawImage(drawn, 0, 0, null); dispose() }
        ImageIO.write(flat, "png", file)
    }

    private companion object {
        const val DEMO_LIST = "https://lists.example.org/demo"

        val countries = listOf(
            "DE" to "🇩🇪", "NL" to "🇳🇱", "FR" to "🇫🇷", "SE" to "🇸🇪", "FI" to "🇫🇮", "PL" to "🇵🇱",
            "ES" to "🇪🇸", "IT" to "🇮🇹", "CH" to "🇨🇭", "GB" to "🇬🇧", "CA" to "🇨🇦", "US" to "🇺🇸"
        )

        /** The eleventh room: under the fold of every picture the board is on. */
        const val ROOM_IN_USE = "demo-CA"

        /**
         * Three quarters of a minute of a session that ends in the middle of a
         * video: the newest bars are the tall ones, beside the figures that
         * name them (the shape `StoreArtRenderTest` draws).
         */
        fun session(): ThroughputWindow = ThroughputWindow().apply {
            var received = 0L
            var sent = 0L
            record(received, sent, atMillis = 0L)
            repeat(45) { second ->
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
                record(received, sent, atMillis = (second + 1) * 1_000L)
            }
        }

        /** The drawing's own units across a picture, whatever its pixels. */
        const val CANVAS_WIDTH = 440f

        /** The card, in those units: the whole screen, with the caption over it. */
        const val CARD_WIDTH = 318f
        const val CARD_CORNER = 34

        /** A screen shown from under its head, as wide as makes its card a whole screen's height. */
        const val ZOOM_WIDTH = 380f

        /** The brand line and the status strip, in points: what a card with [Frame.headCut] leaves out. */
        const val HEAD = 150

        /** Clear of the card's corners, in points of the screen. */
        const val CARD_TOP = 26
        const val CARD_BOTTOM = 14
    }
}
