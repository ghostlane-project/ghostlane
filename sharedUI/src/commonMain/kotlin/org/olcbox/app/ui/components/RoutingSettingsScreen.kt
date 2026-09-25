package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.routing_china_hub
import multiplatform_app.sharedui.generated.resources.routing_china_summary
import multiplatform_app.sharedui.generated.resources.routing_china_title
import multiplatform_app.sharedui.generated.resources.routing_global_hub
import multiplatform_app.sharedui.generated.resources.routing_global_summary
import multiplatform_app.sharedui.generated.resources.routing_global_title
import multiplatform_app.sharedui.generated.resources.routing_iran_hub
import multiplatform_app.sharedui.generated.resources.routing_iran_summary
import multiplatform_app.sharedui.generated.resources.routing_iran_title
import multiplatform_app.sharedui.generated.resources.routing_russia_hub
import multiplatform_app.sharedui.generated.resources.routing_russia_summary
import multiplatform_app.sharedui.generated.resources.routing_russia_title
import org.jetbrains.compose.resources.StringResource
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_close
import multiplatform_app.sharedui.generated.resources.region_china
import multiplatform_app.sharedui.generated.resources.region_global
import multiplatform_app.sharedui.generated.resources.region_iran
import multiplatform_app.sharedui.generated.resources.region_russia
import multiplatform_app.sharedui.generated.resources.routing_lists_note
import multiplatform_app.sharedui.generated.resources.routing_region
import multiplatform_app.sharedui.generated.resources.routing_region_subtitle
import multiplatform_app.sharedui.generated.resources.settings_routing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.ui.components.kit.PkScreenHeader
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/** The one routing editor used by Android, desktop and Apple clients. */
@Composable
fun RoutingSettingsScreen(
    settings: RoutingSettings,
    enabled: Boolean,
    availableModes: List<RoutingMode> = RoutingMode.entries,
    unavailableReason: String? = null,
    onChanged: (RoutingSettings) -> Unit,
    onBack: () -> Unit
) {
    var selectingRegion by remember { mutableStateOf(false) }
    if (selectingRegion) {
        AlertDialog(
            onDismissRequest = { selectingRegion = false },
            title = { Text(stringResource(Res.string.routing_region)) },
            text = {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column {
                        availableModes.forEachIndexed { index, mode ->
                            RoutingChoiceRow(
                                selected = settings.mode == mode,
                                icon = if (mode == RoutingMode.Global) PkIcons.Public else PkIcons.SwapVert,
                                title = mode.localizedTitle(),
                                subtitle = mode.localizedSummary(),
                                enabled = enabled,
                                onClick = {
                                    onChanged(settings.copy(mode = mode))
                                    selectingRegion = false
                                }
                            )
                            if (index != availableModes.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectingRegion = false }) { Text(stringResource(Res.string.action_close)) } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        PkScreenHeader(title = stringResource(Res.string.settings_routing), subtitle = settings.mode.localizedHubSummary(), onBack = onBack)
        Spacer(Modifier.height(20.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                RoutingValueRow(
                    icon = PkIcons.SwapVert,
                    title = stringResource(Res.string.routing_region),
                    subtitle = stringResource(Res.string.routing_region_subtitle),
                    value = settings.mode.regionLabel(),
                    enabled = enabled,
                    onClick = { selectingRegion = true }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = unavailableReason
                ?: stringResource(Res.string.routing_lists_note),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPkPalette.current.textDim
        )
    }
}

@Composable
private fun RoutingChoiceRow(selected: Boolean, icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = (if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RoutingValueRow(icon: ImageVector, title: String, subtitle: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(subtitle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Spacer(Modifier.width(5.dp))
        Icon(PkIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RoutingMode.regionLabel(): String = when (this) {
    RoutingMode.Global -> stringResource(Res.string.region_global)
    RoutingMode.BypassRussia -> stringResource(Res.string.region_russia)
    RoutingMode.BypassIran -> stringResource(Res.string.region_iran)
    RoutingMode.BypassChina -> stringResource(Res.string.region_china)
}

/**
 * A routing mode's name, description and one-line hub summary on screen. The
 * model's own title(), summary() and hubSummary() stay English, for logs and tests.
 */
@Composable
fun RoutingMode.localizedTitle(): String = stringResource(titleRes())

@Composable
fun RoutingMode.localizedSummary(): String = stringResource(summaryRes())

@Composable
fun RoutingMode.localizedHubSummary(): String = stringResource(hubSummaryRes())

internal fun RoutingMode.titleRes(): StringResource = when (this) {
    RoutingMode.Global -> Res.string.routing_global_title
    RoutingMode.BypassRussia -> Res.string.routing_russia_title
    RoutingMode.BypassIran -> Res.string.routing_iran_title
    RoutingMode.BypassChina -> Res.string.routing_china_title
}

internal fun RoutingMode.summaryRes(): StringResource = when (this) {
    RoutingMode.Global -> Res.string.routing_global_summary
    RoutingMode.BypassRussia -> Res.string.routing_russia_summary
    RoutingMode.BypassIran -> Res.string.routing_iran_summary
    RoutingMode.BypassChina -> Res.string.routing_china_summary
}

internal fun RoutingMode.hubSummaryRes(): StringResource = when (this) {
    RoutingMode.Global -> Res.string.routing_global_hub
    RoutingMode.BypassRussia -> Res.string.routing_russia_hub
    RoutingMode.BypassIran -> Res.string.routing_iran_hub
    RoutingMode.BypassChina -> Res.string.routing_china_hub
}
