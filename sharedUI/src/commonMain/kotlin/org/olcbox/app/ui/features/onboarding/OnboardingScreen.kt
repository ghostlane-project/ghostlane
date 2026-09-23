package org.olcbox.app.ui.features.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkScreenBackground
import org.olcbox.app.ui.components.kit.pkTopBarsPadding
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The three things a first-time user has to know, and nothing else.
 *
 * There was no onboarding at all: the app opened on an empty list and the words
 * "import a server list to start", which explains the mechanism and not the
 * point. The point is the seat — that a relay holds a fixed number of people and
 * a full room turns you away — and none of the rest of the screen can teach it,
 * because by the time the board is on screen the user is already choosing.
 *
 * It is not the VPN disclosure and must never absorb it. Play requires that
 * consent stand alone, and a walkthrough that mentioned the tunnel permission in
 * passing would fail exactly the check the disclosure exists to pass.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onAddServerList: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(0) }
    val palette = LocalPkPalette.current
    val current = ONBOARDING_STEPS[step]

    Surface(
        modifier = modifier.fillMaxSize().then(pkScreenBackground()),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pkTopBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "0${step + 1} / 0${ONBOARDING_STEPS.size}",
                    style = pkMono(10, 1.8),
                    color = palette.textMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "SKIP",
                    style = pkMono(10, 1.4),
                    color = palette.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Skip", role = Role.Button) { onFinished() }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SeatRing(taken = current.seatsTaken, mine = current.seatIsMine)

                Spacer(Modifier.height(28.dp))

                Text(
                    text = current.eyebrow,
                    style = pkMono(10, 2.0),
                    color = palette.accent
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = current.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textDim,
                    textAlign = TextAlign.Center
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                ONBOARDING_STEPS.indices.forEach { index ->
                    val width by animateFloatAsState(
                        if (index == step) 22f else 6f,
                        label = "onboardingDot"
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 3.5.dp)
                            .width(width.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                if (index == step) palette.accent
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.accent)
                    .clickable(onClickLabel = current.cta, role = Role.Button) {
                        if (step < ONBOARDING_STEPS.lastIndex) {
                            step++
                        } else {
                            // The last step's promise is the add sheet, so it opens
                            // one rather than dropping the user on an empty board
                            // having just told them what to do.
                            onFinished()
                            onAddServerList()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = current.cta,
                    style = pkMono(13, 1.6).copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onTertiary
                )
            }
        }
    }
}

/**
 * A ring of eight seats, the thing the whole app is about.
 *
 * Drawn rather than illustrated: it is the same model the room card uses, at the
 * size of a hero image, so the first thing a user learns is the thing they will
 * be reading all day.
 */
@Composable
private fun SeatRing(taken: Int, mine: Boolean) {
    val palette = LocalPkPalette.current
    val seatFree = palette.seatFree
    val seatOther = palette.seatOther
    val accent = palette.accent
    Box(modifier = Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f + 6.dp.toPx()
            val diameter = size.minDimension - inset * 2f
            val sweep = 360f / SEATS
            // Wide enough to survive the round caps, which extend half a stroke
            // past each arc: at five degrees the eight seats closed up into one
            // ring with a gradient, which is the opposite of the point.
            val gap = 11f
            repeat(SEATS) { index ->
                drawArc(
                    color = when {
                        index == 0 && mine -> accent
                        index < taken -> seatOther
                        else -> seatFree
                    },
                    startAngle = -90f + index * sweep + gap / 2f,
                    sweepAngle = sweep - gap,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$taken/$SEATS",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = if (mine) accent else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(text = "SEATS TAKEN", style = pkMono(9, 1.4), color = palette.textMuted)
        }
    }
}

private const val SEATS = 8

/** One step. [seatsTaken] and [seatIsMine] drive the ring above the words. */
private data class OnboardingStep(
    val eyebrow: String,
    val title: String,
    val body: String,
    val cta: String,
    val seatsTaken: Int,
    val seatIsMine: Boolean
)

/**
 * Three steps, in the order the questions arrive: what is this, how does it
 * behave, what do I have to bring.
 *
 * The first says "carried over WebRTC" rather than "a call, not a tunnel". Both
 * are true; only one of them describes the app without describing a way around
 * something, which is the distinction the store cares about.
 */
private val ONBOARDING_STEPS = listOf(
    OnboardingStep(
        eyebrow = "WHAT THIS IS",
        title = "Carried over WebRTC",
        body = "olcRTC carries traffic over WebRTC — the same real-time media " +
            "transport a video call uses — so it keeps working where ordinary " +
            "tunnel transports are unreliable.",
        cta = "NEXT",
        seatsTaken = 1,
        seatIsMine = true
    ),
    OnboardingStep(
        eyebrow = "HOW ROOMS WORK",
        title = "Every room has seats",
        body = "A relay holds a fixed number of seats and a full room cannot take " +
            "you. The board shows how full each one is, and it moves as people " +
            "come and go.",
        cta = "NEXT",
        seatsTaken = 6,
        seatIsMine = false
    ),
    OnboardingStep(
        eyebrow = "WHAT YOU BRING",
        title = "You bring the servers",
        body = "No account, nothing to buy, nothing collected. Add a server list " +
            "by link, QR code or file and it becomes rooms you can join.",
        cta = "ADD SERVER LIST",
        seatsTaken = 0,
        seatIsMine = false
    )
)
