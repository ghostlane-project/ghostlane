package org.olcbox.app.ui.features.home.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_dismiss
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * Why the last attempt failed, or what is blocking a start.
 *
 * Kept as its own element rather than folded into the status strip's meta line:
 * it has to survive being three lines long — an extension's own account of its
 * death is not a phrase — and that line is a single line by design.
 *
 * With [onDismiss] the whole card is the control and a close mark at the
 * trailing edge says so. A failure used to be something the user could only
 * outlive: nothing but a successful connect or a relaunch took it off the
 * screen, so a message about a room that no longer mattered sat there for the
 * rest of the session. The card rather than the mark is what takes the tap
 * because an 18dp mark is a poor target and a 48dp button would make a one-line
 * notice twice as tall as its text.
 */
@Composable
fun RelayNotice(text: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val pk = LocalPkPalette.current
    val shape = RoundedCornerShape(14.dp)
    val color = MaterialTheme.colorScheme.errorContainer
    val border = BorderStroke(1.dp, pk.danger)
    if (onDismiss != null) {
        Surface(
            onClick = onDismiss,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border
        ) {
            NoticeBody(text, dismissible = true)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            NoticeBody(text, dismissible = false)
        }
    }
}

@Composable
private fun NoticeBody(text: String, dismissible: Boolean) {
    val pk = LocalPkPalette.current
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = PkIcons.PriorityHigh,
            contentDescription = null,
            tint = pk.danger,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        if (dismissible) {
            // Same box as the mark on the left, so the two sit on the first line
            // together and a one-line notice is no taller for being dismissible.
            Icon(
                imageVector = PkIcons.Close,
                contentDescription = stringResource(Res.string.action_dismiss),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * `MM:SS` under an hour, `H:MM:SS` above it — the leading unit is never padded,
 * so a session does not read as `01:02:03` on its second hour.
 *
 * A negative span means the clock moved backwards under us (a manual time
 * change, an NTP correction); it shows as zero rather than as a minus sign.
 *
 * Rendered in mono, because a proportional face makes the digits jump sideways
 * every time a 1 becomes a 2 — on a number that changes every second, that reads
 * as the whole screen twitching.
 */
internal fun formatSessionDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${secs.padded()}"
    } else {
        "${minutes.padded()}:${secs.padded()}"
    }
}

private fun Long.padded(): String = if (this < 10) "0$this" else toString()
