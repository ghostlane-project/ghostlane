package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.kill_switch
import multiplatform_app.sharedui.generated.resources.kill_switch_note
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.components.kit.PkSwitch
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The kill switch ([org.olcbox.app.data.model.RoutingSettings.killSwitch]), drawn
 * as a card like the connection screen's other rows. The note under the title is
 * the whole of the warning: what the switch buys and what it costs are one
 * sentence each, and the second has to be read before the first is wanted.
 */
@Composable
fun KillSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.kill_switch),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(Res.string.kill_switch_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalPkPalette.current.textDim
                )
            }
            Spacer(Modifier.width(12.dp))
            PkSwitch(checked = checked, enabled = true)
        }
    }
}
