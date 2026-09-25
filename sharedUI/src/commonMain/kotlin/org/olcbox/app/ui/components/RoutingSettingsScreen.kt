package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.region_blocked_only
import multiplatform_app.sharedui.generated.resources.remove_named
import multiplatform_app.sharedui.generated.resources.routing_blocked_hub
import multiplatform_app.sharedui.generated.resources.routing_blocked_note
import multiplatform_app.sharedui.generated.resources.routing_blocked_summary
import multiplatform_app.sharedui.generated.resources.routing_blocked_title
import multiplatform_app.sharedui.generated.resources.routing_rules_add_direct
import multiplatform_app.sharedui.generated.resources.routing_rules_add_hint
import multiplatform_app.sharedui.generated.resources.routing_rules_add_tunnel
import multiplatform_app.sharedui.generated.resources.routing_rules_direct
import multiplatform_app.sharedui.generated.resources.routing_rules_empty
import multiplatform_app.sharedui.generated.resources.routing_rules_invalid
import multiplatform_app.sharedui.generated.resources.routing_rules_note
import multiplatform_app.sharedui.generated.resources.routing_rules_title
import multiplatform_app.sharedui.generated.resources.routing_rules_tunnel
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
import org.olcbox.app.net.RoutingRule
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
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
    /** What the choice means on this platform, where that differs; shown while it applies. */
    note: String? = null,
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
        CustomRulesCard(settings = settings, enabled = enabled, onChanged = onChanged)
        Spacer(Modifier.height(16.dp))
        if (unavailableReason == null && note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPkPalette.current.textDim
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = unavailableReason
                ?: stringResource(
                    if (settings.mode == RoutingMode.BlockedOnly) Res.string.routing_blocked_note
                    else Res.string.routing_lists_note
                ),
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

/**
 * The user's own rules: two lists and one field. An entry goes into the list its
 * button names and out of the other; what is neither a domain nor an address is
 * refused where it was typed.
 */
@Composable
private fun CustomRulesCard(settings: RoutingSettings, enabled: Boolean, onChanged: (RoutingSettings) -> Unit) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    fun add(toTunnel: Boolean) {
        val rule = RoutingRule.parse(input)
        if (rule == null) {
            invalid = true
            return
        }
        onChanged(if (toTunnel) settings.withTunnelRule(rule) else settings.withDirectRule(rule))
        input = ""
        invalid = false
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.routing_rules_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(Res.string.routing_rules_note),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPkPalette.current.textDim
            )
            RuleList(stringResource(Res.string.routing_rules_direct), settings.directRules, enabled) {
                onChanged(settings.withoutRule(it))
            }
            RuleList(stringResource(Res.string.routing_rules_tunnel), settings.tunnelRules, enabled) {
                onChanged(settings.withoutRule(it))
            }
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    invalid = false
                },
                enabled = enabled,
                singleLine = true,
                isError = invalid,
                label = { Text(stringResource(Res.string.routing_rules_add_hint)) },
                supportingText = if (invalid) {
                    { Text(stringResource(Res.string.routing_rules_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { add(toTunnel = false) },
                    enabled = enabled && input.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(Res.string.routing_rules_add_direct), maxLines = 1) }
                OutlinedButton(
                    onClick = { add(toTunnel = true) },
                    enabled = enabled && input.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(Res.string.routing_rules_add_tunnel), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun RuleList(title: String, entries: List<String>, enabled: Boolean, onRemove: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isEmpty()) {
            Text(
                stringResource(Res.string.routing_rules_empty),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPkPalette.current.textDim
            )
        }
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { onRemove(entry) }, enabled = enabled) {
                    Icon(
                        PkIcons.Close,
                        contentDescription = stringResource(Res.string.remove_named, entry),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingMode.regionLabel(): String = when (this) {
    RoutingMode.Global -> stringResource(Res.string.region_global)
    RoutingMode.BypassRussia -> stringResource(Res.string.region_russia)
    RoutingMode.BypassIran -> stringResource(Res.string.region_iran)
    RoutingMode.BypassChina -> stringResource(Res.string.region_china)
    RoutingMode.BlockedOnly -> stringResource(Res.string.region_blocked_only)
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
    RoutingMode.BlockedOnly -> Res.string.routing_blocked_title
}

internal fun RoutingMode.summaryRes(): StringResource = when (this) {
    RoutingMode.Global -> Res.string.routing_global_summary
    RoutingMode.BypassRussia -> Res.string.routing_russia_summary
    RoutingMode.BypassIran -> Res.string.routing_iran_summary
    RoutingMode.BypassChina -> Res.string.routing_china_summary
    RoutingMode.BlockedOnly -> Res.string.routing_blocked_summary
}

internal fun RoutingMode.hubSummaryRes(): StringResource = when (this) {
    RoutingMode.Global -> Res.string.routing_global_hub
    RoutingMode.BypassRussia -> Res.string.routing_russia_hub
    RoutingMode.BypassIran -> Res.string.routing_iran_hub
    RoutingMode.BypassChina -> Res.string.routing_china_hub
    RoutingMode.BlockedOnly -> Res.string.routing_blocked_hub
}
