package org.olcbox.app.ui.features.home.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_export
import multiplatform_app.sharedui.generated.resources.action_share
import multiplatform_app.sharedui.generated.resources.logs_debug
import multiplatform_app.sharedui.generated.resources.logs_debug_note
import multiplatform_app.sharedui.generated.resources.logs_scrub_note
import multiplatform_app.sharedui.generated.resources.logs_scrubbed
import multiplatform_app.sharedui.generated.resources.settings_diagnostics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.components.kit.PkCardSunken
import org.olcbox.app.ui.components.kit.PkSwitch
import org.olcbox.app.ui.theme.LocalPkPalette

@Composable
fun LogsSheet(
    logs: List<String>,
    verboseDebugLogs: Boolean,
    onVerboseDebugLogsChanged: (Boolean) -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    onDismiss: () -> Unit
) {
    PkBottomSheet(
        title = stringResource(Res.string.settings_diagnostics),
        subtitle = stringResource(Res.string.logs_scrubbed),
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PkCardSunken(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVerboseDebugLogsChanged(!verboseDebugLogs) }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.logs_debug), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.logs_debug_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalPkPalette.current.textMuted
                        )
                    }
                    PkSwitch(checked = verboseDebugLogs)
                }
            }

            PkCardSunken(modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)) {
                LogLines(
                    logs = logs,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(13.dp)
                )
            }

            // Says what the scrubber does, where a user decides whether to send
            // the file. The guarantee is worth nothing if it is only in the code.
            Text(
                text = stringResource(Res.string.logs_scrub_note),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPkPalette.current.textMuted
            )

            Spacer(Modifier.height(2.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PkSheetButton(
                    label = stringResource(Res.string.action_export),
                    onClick = onSaveClick,
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
                PkSheetButton(
                    label = stringResource(Res.string.action_share),
                    onClick = onShareClick,
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LogLines(
    logs: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        itemsIndexed(
            items = logs,
            key = { index, log -> "$index:$log" }
        ) { _, log ->
            Text(
                text = log,
                style = MaterialTheme.typography.labelSmall,
                color = getLogColor(log),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun getLogColor(log: String): Color {
    val pk = LocalPkPalette.current
    return when {
        log.contains("❌") || log.contains("Error") -> pk.danger
        log.contains("✅") || log.contains("OK") -> pk.success
        log.contains("⚠️") -> pk.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
