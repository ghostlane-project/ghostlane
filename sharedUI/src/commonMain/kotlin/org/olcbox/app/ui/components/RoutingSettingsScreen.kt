package org.olcbox.app.ui.components

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
            title = { Text("Region") },
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
                                title = mode.title(),
                                subtitle = mode.summary(),
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
            confirmButton = { TextButton(onClick = { selectingRegion = false }) { Text("Close") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        PkScreenHeader(title = "Routing", subtitle = settings.mode.hubSummary(), onBack = onBack)
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
                    title = "Region",
                    subtitle = "Choose which country's sites and local network connect directly",
                    value = settings.mode.regionLabel(),
                    enabled = enabled,
                    onClick = { selectingRegion = true }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = unavailableReason
                ?: "Regional site and IP lists are bundled for offline use. Local destinations " +
                    "go directly; other traffic and DNS use the VPN. Changes reconnect the tunnel.",
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

private fun RoutingMode.regionLabel(): String = when (this) {
    RoutingMode.Global -> "Global"
    RoutingMode.BypassRussia -> "Russia"
    RoutingMode.BypassIran -> "Iran"
    RoutingMode.BypassChina -> "China"
}
