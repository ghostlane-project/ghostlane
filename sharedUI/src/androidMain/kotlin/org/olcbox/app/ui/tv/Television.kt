package org.olcbox.app.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.ui.theme.LocalPkPalette

/** A television: the UI mode says so, or the device runs the TV launcher. */
fun Context.isTelevision(): Boolean =
    getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** Any camera at all, front or back: without one there is nothing to scan a code with. */
fun Context.hasCamera(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * On a television every control is reached with a D-pad, and the focused one has to
 * be seen from across a room. Compose already moves focus between clickables; what a
 * dark theme lacks is a mark that shows where it is. On a TV, [content] gets
 * [TvFocusIndication] — an accent ring and a light fill on the focused control — in
 * place of the ripple, which on a phone stays as it was.
 */
@Composable
fun TelevisionAware(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val television = remember(context) { context.isTelevision() }
    if (!television) {
        content()
        return
    }
    val accent = LocalPkPalette.current.accent
    val indication = remember(accent) { TvFocusIndication(accent) }
    CompositionLocalProvider(LocalIndication provides indication, content = content)
}

private class TvFocusIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = TvFocusNode(interactionSource, color)
    override fun equals(other: Any?): Boolean = other is TvFocusIndication && other.color == color
    override fun hashCode(): Int = color.hashCode()
}

private class TvFocusNode(
    private val interactionSource: InteractionSource,
    private val color: Color
) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused && !pressed) return
        val radius = CornerRadius(12.dp.toPx())
        drawRoundRect(color.copy(alpha = if (pressed) 0.24f else 0.12f), cornerRadius = radius)
        if (focused) {
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(stroke)
            )
        }
    }
}
