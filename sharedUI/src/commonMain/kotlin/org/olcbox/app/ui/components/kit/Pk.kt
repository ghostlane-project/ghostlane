package org.olcbox.app.ui.components.kit

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.olcbox.app.AppInfo
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.ui.theme.LocalPkPalette

/** Single source of in-UI brand strings (GeneratedAppInfo.NAME stays "olcbox"). */
object PkBrand {
    const val name = "Ghostlane"

    /**
     * Short on purpose. This sits in a centre-aligned app bar, whose title slot
     * is whatever is left after the navigation and action icons — narrow enough
     * that the protocol list used to wrap onto a second line at some widths and
     * not others.
     *
     * The protocols were the part that wrapped, and they are the part already on
     * screen: the filter chips directly below name them and count them, which
     * this line could not do. What is left is the thing nothing else says.
     */
    const val tagline = "decentralized VPN"

    /** Where a user with no subscription is sent to get one. */
    const val siteUrl = "https://proofkit.org"
}

/**
 * Hides the secret parts of a subscription URL for display: a path segment long
 * enough to be a bearer token is one, and anyone glancing at the screen could
 * copy it.
 *
 * Every such segment, not merely the last — which is what this used to do, and
 * which is wrong for our own URLs: `/sub/<token>/olcrtc` ends in a six-character
 * literal, so the function returned the string untouched and the row ellipsised
 * it. That looks like masking and is not.
 *
 * The host is never masked. It is not a secret, it is the only part a user can
 * recognise, and it is long enough that masking by length alone would mangle it.
 *
 * `https://proofkit.org/sub/c0e79f…17e95/olcrtc?…` — enough to tell two
 * subscriptions apart, not enough to reuse one.
 */
fun pkMaskSubscriptionUrl(url: String): String {
    val trimmed = url.trim()
    val queryAt = trimmed.indexOf('?')
    val path = if (queryAt >= 0) trimmed.substring(0, queryAt) else trimmed
    val schemeEnd = path.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val masked = path.substring(schemeEnd)
        .split('/')
        // Index 0 is the authority, which stays readable.
        .mapIndexed { index, segment -> if (index == 0) segment else maskUrlSegment(segment) }
        .joinToString("/")
    return path.substring(0, schemeEnd) + masked + (if (queryAt >= 0) "?…" else "")
}

/** Short segments are route names, not secrets, and hiding them only costs legibility. */
private fun maskUrlSegment(segment: String): String =
    if (segment.length <= 12) segment else segment.take(6) + "…" + segment.takeLast(5)

/** `https://proofkit.org:8443/sub/x` → `proofkit.org`. Null when there is no authority to read. */
fun pkSubscriptionHost(url: String): String? {
    val withoutScheme = url.trim().substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isBlank()) return null
    return withoutScheme.substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }
}

/**
 * Whether this subscription arrived as an encrypted link — ours
 * (`olcrtc://crypt1/…`) or a partner's (`happ://crypt5/…`).
 *
 * [originLink] is the recorded answer. The `crypt=1` clause is the retrofit for
 * subscriptions imported before that field existed: only our own encrypted
 * subscriptions ask for an encrypted body, so the marker identifies them.
 */
fun pkSubscriptionIsSecret(url: String, originLink: String?): Boolean =
    !originLink.isNullOrBlank() || url.contains("crypt=1")

/**
 * What the Server lists row prints under a subscription's name.
 *
 * A subscription URL is a bearer credential in every case, so none of these
 * branches prints a whole one. An encrypted subscription prints no endpoint at
 * all — hiding it is the entire purpose of the link it arrived as, and printing
 * what we decrypted out of that link undoes it.
 *
 * [revealed] is the admin gate: `AdminState.plumbingVisible`, which fails closed,
 * rather than `configuratorVisible`, which does not. Forgetting to bake the admin
 * hash must not be what puts credentials back on the screen.
 *
 * [encrypted] is what an encrypted one says instead, in the user's language: a
 * screen passes its string resource, and this stays pure.
 */
fun pkSubscriptionSourceLine(
    url: String,
    originLink: String? = null,
    revealed: Boolean = false,
    encrypted: String = "Encrypted link"
): String = when {
    revealed -> pkMaskSubscriptionUrl(url)
    pkSubscriptionIsSecret(url, originLink) -> encrypted
    else -> pkSubscriptionHost(url) ?: pkMaskSubscriptionUrl(url)
}

/**
 * Pure so it is unit-testable: "PROOFKIT · v1.0.273 · 9f3c1ab".
 *
 * The third segment used to name the project this app was forked from, which is
 * not something a ProofKit user needs to read and which occupied the one slot
 * that could tell two binaries apart. It could not: our patch number is the CI
 * run number, so a nightly and a local Xcode archive both say 1.0.273.
 */
fun pkVersionLine(info: AppInfo): String {
    val version = info.version.removePrefix("v")
    return listOfNotNull(
        PkBrand.name.uppercase(),
        "v$version",
        // A build with no id says nothing rather than trailing a separator.
        info.build.trim().takeIf { it.isNotEmpty() }
    ).joinToString(" · ")
}

/** Site `.eyebrow`: mono uppercase label with a lime dot (optionally pulsing). */
@Composable
fun PkSectionLabel(text: String, pulse: Boolean = false) {
    val pk = LocalPkPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PkDot(color = pk.accent, halo = pk.accentSoft, pulse = pulse)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = pk.textDim
        )
    }
}

@Composable
private fun PkDot(color: Color, halo: Color, pulse: Boolean) {
    // No infinite transition here either. It had no caller asking for one, and an
    // animation that runs forever asks Compose for a frame every vsync — which on
    // this toolkit redraws the whole scene. One was enough to heat a phone; see
    // PkPulseDot.
    Box(contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(Modifier.size(14.dp).background(halo, CircleShape))
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** ProofKit card: surface #0F1117, 1dp #1E2030 border, radius 16. */
@Composable
fun PkCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/** Sunken terminal well (#0A0C14) for logs/code content. */
@Composable
fun PkCardSunken(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

/**
 * Mono filter chip. Selected reads as lime-on-dark so the active filter is obvious
 * at a glance in a long list.
 */
@Composable
fun PkFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null
) {
    val pk = LocalPkPalette.current
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) pk.accentSoft else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (selected) pk.accent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) pk.accent else pk.textDim
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) pk.accent else pk.textMuted
                )
            }
        }
    }
}

/** Home-screen footer: PROOFKIT · v<version> · <build>. */
@Composable
fun PkVersionFooter(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = pkVersionLine(CurrentAppInfo.value),
            style = MaterialTheme.typography.labelSmall,
            color = LocalPkPalette.current.textMuted
        )
    }
}

/**
 * Site background: the ground, a faint 48dp grid, and an indigo radial glow at
 * the top.
 *
 * The ground is painted here rather than left to a container colour. It was left
 * to one, and a screen whose scaffold is transparent — which the home screen's is,
 * so this grid can show through it — came out white: every surface that set its
 * own colour looked right, and every gap between them was the host's default.
 */
@Composable
fun pkScreenBackground(): Modifier {
    val grid = LocalPkPalette.current.gridLine
    val ground = MaterialTheme.colorScheme.background
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    // drawWithCache, not drawBehind: this covers the whole screen and re-ran on
    // every invalidation, building a fresh radial-gradient shader each time. On a
    // screen with a pulsing dot on it that is a new shader per frame, for a
    // background that only changes when the window resizes.
    return Modifier.drawWithCache {
        val glowBrush = Brush.radialGradient(
            colors = listOf(glow, Color.Transparent),
            center = Offset(size.width / 2f, -size.height * 0.1f),
            radius = size.width * 0.9f
        )
        val verticals = if (step > 0f) generateSequence(0f) { it + step }
            .takeWhile { it <= size.width }.toList() else emptyList()
        val horizontals = if (step > 0f) generateSequence(0f) { it + step }
            .takeWhile { it <= size.height }.toList() else emptyList()
        onDrawBehind {
            if (size.width <= 0f || size.height <= 0f) return@onDrawBehind
            drawRect(color = ground)
            drawRect(brush = glowBrush)
            verticals.forEach { x ->
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            horizontals.forEach { y ->
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
        }
    }
}
