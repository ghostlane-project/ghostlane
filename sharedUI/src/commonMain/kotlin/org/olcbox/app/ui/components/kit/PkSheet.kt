package org.olcbox.app.ui.components.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * Sheets and the rows inside them.
 *
 * One shape for every sheet the app has, so the add sheet, the log, the update
 * offer and the disclosure all read as the same object arriving from the same
 * place — rather than four dialogs that happen to share a colour scheme.
 */

/**
 * The house style of a sheet: a grab handle, a title, a mono subtitle, content.
 *
 * Split from [PkBottomSheet] so it can be rendered on its own. `ModalBottomSheet`
 * puts itself in a separate platform window, which a headless `ImageComposeScene`
 * does not capture — so with the two fused, every sheet in the app was a layout
 * nobody could look at until it was on a phone.
 */
@Composable
fun PkSheetSurface(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    showHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPkPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (showHandle) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
        } else {
            Spacer(Modifier.height(22.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The sheet is told to ignore window insets so its own surface
                // runs to the bottom edge; its content still has to clear the
                // home indicator, and the last thing on every sheet is a button.
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle.uppercase(),
                    style = pkMono(10, 1.0),
                    color = palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** [PkSheetSurface], presented as a modal sheet over whatever is behind it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PkBottomSheet(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False for a sheet that has to be answered rather than waved away — the VPN
     * disclosure, where "carry on without deciding" is exactly what the policy
     * forbids.
     */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = sheetState,
        // Ahead of the sheet's own anchors in the chain, so a tall sheet (the
        // log) stops under a macOS window's title bar instead of running up
        // behind the traffic lights. Zero on a phone.
        modifier = modifier.padding(top = LocalWindowTitleBarInset.current),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        // The handle lives in the surface, so the surface is complete on its own.
        dragHandle = null
    ) {
        PkSheetSurface(title = title, subtitle = subtitle, showHandle = dismissible, content = content)
    }
}

/** An eyebrow over a group of settings rows. */
@Composable
fun PkSectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = pkMono(9, 1.6),
        color = LocalPkPalette.current.textMuted,
        modifier = modifier
    )
}

/** The bordered container settings rows sit in. Rows draw their own separators. */
@Composable
fun PkSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        content = content
    )
}

/** The hairline between two rows of one card. Never above the first. */
@Composable
private fun PkRowDivider(show: Boolean) {
    if (!show) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun PkRowScaffold(
    label: String,
    sub: String?,
    enabled: Boolean,
    showDivider: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit
) {
    val palette = LocalPkPalette.current
    Column {
        PkRowDivider(showDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = label) { onClick() }
                    } else {
                        Modifier
                    }
                )
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 1f else 0.45f
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!sub.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = sub,
                        style = pkMono(10, 0.5),
                        color = palette.textDim.copy(alpha = if (enabled) 1f else 0.45f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A switch drawn rather than borrowed.
 *
 * Material's `Switch` is the single most identifiable control in the toolkit; a
 * settings screen full of them is a settings screen that looks like every other
 * Compose app, which is the thing this redesign is answering.
 */
@Composable
fun PkToggleRow(
    label: String,
    sub: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    PkRowScaffold(
        label = label,
        sub = sub,
        enabled = enabled,
        showDivider = showDivider,
        onClick = { if (enabled) onCheckedChange(!checked) }
    ) {
        PkSwitch(checked = checked, enabled = enabled, modifier = modifier)
    }
}

@Composable
fun PkSwitch(checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val palette = LocalPkPalette.current
    val track by animateColorAsState(
        targetValue = if (checked) palette.accent.copy(alpha = 0.16f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "switchTrack"
    )
    val trackBorder by animateColorAsState(
        targetValue = if (checked) palette.accent else MaterialTheme.colorScheme.outline,
        label = "switchTrackBorder"
    )
    val knob by animateColorAsState(
        targetValue = if (checked) palette.accent else palette.textMuted,
        label = "switchKnob"
    )
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(track)
            .border(1.dp, trackBorder, RoundedCornerShape(99.dp))
            .padding(2.dp)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(99.dp)).background(knob))
    }
}

/** A row that opens something else: an optional value, then a chevron. */
@Composable
fun PkLinkRow(
    label: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    icon: ImageVector? = null
) {
    val palette = LocalPkPalette.current
    PkRowScaffold(
        label = label,
        sub = sub,
        enabled = enabled,
        showDivider = showDivider,
        onClick = if (enabled) onClick else null
    ) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = pkMono(10, 0.4),
                    color = valueColor ?: palette.textDim,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = icon ?: PkIcons.ChevronRight,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
