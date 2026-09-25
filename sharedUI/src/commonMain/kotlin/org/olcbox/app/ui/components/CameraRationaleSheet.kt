package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_continue
import multiplatform_app.sharedui.generated.resources.camera_reason_decide
import multiplatform_app.sharedui.generated.resources.camera_reason_nothing_kept
import multiplatform_app.sharedui.generated.resources.camera_reason_use
import multiplatform_app.sharedui.generated.resources.camera_subtitle
import multiplatform_app.sharedui.generated.resources.camera_title
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.features.home.components.PkSheetButton
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * Why this app wants a camera, before the system asks.
 *
 * The scanner used to be launched straight from the add sheet, so the first
 * thing a user saw was the operating system's own permission prompt with no
 * explanation attached — and on iOS that prompt is the only chance there is: a
 * declined camera permission cannot be asked for twice.
 *
 * It is also the honest answer to a reasonable suspicion. A VPN client asking for
 * a camera looks like a VPN client asking for a camera, and the reason is narrow
 * enough to state in three lines.
 *
 * The shape is App Review's, not ours (guideline 5.1.1(iv), which rejected
 * 1.0.392): a message before a permission request may explain, and may not
 * gate. One button, worded neutrally — "Continue", not "Allow and scan" — and
 * no way to wave the message away, because a "Not now" that skips the system
 * prompt is "delaying the permission request". The decision itself is made in
 * the system prompt that follows, where it belongs.
 */
@Composable
fun CameraRationaleSheet(onContinue: () -> Unit) {
    PkBottomSheet(
        title = stringResource(Res.string.camera_title),
        subtitle = stringResource(Res.string.camera_subtitle),
        onDismiss = {},
        dismissible = false
    ) {
        CameraRationaleBody(onContinue = onContinue)
    }
}

/** The sheet's contents, separately so a test can render them. */
@Composable
internal fun CameraRationaleBody(onContinue: () -> Unit) {
    val palette = LocalPkPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            CAMERA_REASONS.forEach { line ->
                Row {
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        PkSheetButton(label = stringResource(Res.string.action_continue), onClick = onContinue, primary = true)
    }
}

/**
 * Three sentences, and no fourth.
 *
 * Each answers a question somebody actually has: what for, what happens to the
 * picture, and where the decision is made and what it costs to say no.
 */
internal val CAMERA_REASONS: List<StringResource> = listOf(
    Res.string.camera_reason_use,
    Res.string.camera_reason_nothing_kept,
    Res.string.camera_reason_decide
)
