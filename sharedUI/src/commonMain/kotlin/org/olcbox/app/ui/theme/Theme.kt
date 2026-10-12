package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    AppTheme(useDynamicColor = false) { PkBoundedTextScale(content) }
}

/**
 * The largest text setting the screens are laid out for, and tested at.
 *
 * Android stops at this. An iPhone's accessibility sizes go on to three times
 * the default, where a room's name is six letters to a line and the board is one
 * card to a screen — larger letters, and less that can be read. So the setting is
 * followed as far as twice, and no further.
 */
const val MAX_FONT_SCALE = 2f

/** [content] at the user's text size, held to [MAX_FONT_SCALE]. */
@Composable
fun PkBoundedTextScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= MAX_FONT_SCALE) {
        content()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, MAX_FONT_SCALE),
            content = content
        )
    }
}

@Composable
expect fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
)
