package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.olcrtc_chrome_dtls
import multiplatform_app.sharedui.generated.resources.olcrtc_chrome_dtls_note
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
 * The experimental switch for olcRTC's Chrome-shaped DTLS handshake
 * ([org.olcbox.app.data.model.RoutingSettings.olcrtcChromeDtls]), drawn as a
 * card like the connection screen's other rows. One composable for the
 * Android connection screen and the shared one desktop and iOS use.
 */
@Composable
fun OlcrtcHandshakeRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
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
                    text = stringResource(Res.string.olcrtc_chrome_dtls),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(Res.string.olcrtc_chrome_dtls_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalPkPalette.current.textDim
                )
            }
            Spacer(Modifier.width(12.dp))
            PkSwitch(checked = checked, enabled = enabled)
        }
    }
}
