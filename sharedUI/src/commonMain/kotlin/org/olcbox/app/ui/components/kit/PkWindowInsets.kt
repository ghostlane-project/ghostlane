package org.olcbox.app.ui.components.kit

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The height of a title bar the window draws over the top of the app.
 *
 * On macOS the desktop window runs the app up under a transparent title bar, so
 * the traffic lights sit on the app's own background instead of on a light
 * strip of system chrome. The bar has not gone anywhere: AppKit still answers a
 * press on it by dragging the window, so nothing a person has to click may be
 * laid out underneath it.
 *
 * Zero everywhere else. A phone reports its status bar through WindowInsets,
 * which [pkTopBarsPadding] already reads, and the other desktop windows keep
 * their title bar above the content.
 */
val LocalWindowTitleBarInset = staticCompositionLocalOf { 0.dp }

/**
 * Where a screen's first row may start: below the status bar on a phone, below
 * the title bar on a desktop window that draws one over the content.
 *
 * Only the content moves. The screen's background still runs to the top edge,
 * which is the whole point — a background that stopped short would put back the
 * band of a different colour this exists to remove.
 */
@Composable
fun Modifier.pkTopBarsPadding(): Modifier =
    statusBarsPadding().padding(top = LocalWindowTitleBarInset.current)
