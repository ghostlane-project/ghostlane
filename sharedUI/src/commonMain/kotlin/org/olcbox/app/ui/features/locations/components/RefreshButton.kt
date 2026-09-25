package org.olcbox.app.ui.features.locations.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.measure_latency
import multiplatform_app.sharedui.generated.resources.refresh_server_list
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import org.olcbox.app.ui.components.kit.PkIconButton
import org.olcbox.app.ui.icons.PkIcons

/**
 * Fetch this server list again. Circling arrows, spinning while it runs.
 *
 * This button used to carry the label **"Ping"** while being the only control
 * on the header — which is impossible to argue with when a user says they
 * pressed ping and their server list refreshed. The two actions have separate
 * buttons now and neither has any text: one asks the provider for the current
 * server list, the other times a request, and side by side the icons say which
 * is which.
 *
 * A bordered square rather than a Material `IconButton`, so the group's controls
 * match the ones in the header row above them.
 */
@Composable
fun SubscriptionRefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val rotation by if (isRefreshing) {
        rememberInfiniteTransition(label = "subscriptionRefresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "subscriptionRefreshSpin"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    PkIconButton(
        icon = Icons.Rounded.Refresh,
        contentDescription = stringResource(Res.string.refresh_server_list),
        onClick = onClick,
        modifier = modifier.rotate(rotation),
        size = 32,
        corner = 9,
        tint = tint ?: MaterialTheme.colorScheme.primary,
        enabled = !isRefreshing
    )
}

/**
 * Measure latency. A bolt, because what it reports is speed.
 *
 * Always offered on a group that has anything measurable in it, including where
 * nothing can be measured this instant: a control that appears and disappears is
 * harder to understand than one that always answers. When there is nothing to
 * measure it says so — see `HomeScreen.refreshHttpPings` — rather than quietly
 * doing nothing.
 */
@Composable
fun LatencyButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val pulse by if (isRunning) {
        rememberInfiniteTransition(label = "latencyProbe").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "latencyProbePulse"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    PkIconButton(
        icon = PkIcons.Bolt,
        contentDescription = stringResource(Res.string.measure_latency),
        onClick = onClick,
        modifier = modifier,
        size = 32,
        corner = 9,
        tint = (tint ?: MaterialTheme.colorScheme.primary).copy(alpha = pulse),
        enabled = !isRunning
    )
}
