package org.olcbox.app.ui.tv

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.phone_page_title
import multiplatform_app.sharedui.generated.resources.phone_page_label
import multiplatform_app.sharedui.generated.resources.phone_page_send
import multiplatform_app.sharedui.generated.resources.phone_page_sent
import multiplatform_app.sharedui.generated.resources.phone_page_empty
import multiplatform_app.sharedui.generated.resources.phone_import_title
import multiplatform_app.sharedui.generated.resources.phone_import_steps
import multiplatform_app.sharedui.generated.resources.phone_import_no_network
import multiplatform_app.sharedui.generated.resources.qr_code
import multiplatform_app.sharedui.generated.resources.action_close
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.data.importer.PhoneImportPage
import org.olcbox.app.data.importer.PhoneImportServer
import org.olcbox.app.ui.activities.createQrBitmap
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.features.home.components.PkSheetButton
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.ui.components.kit.pkMono
import java.util.Locale

/**
 * "From your phone": a code for the phone to scan and an address to type, open for
 * as long as this sheet is (see [PhoneImportServer]). The first link that arrives is
 * handed to [onLink] and the sheet's owner closes it.
 */
@Composable
fun PhoneImportSheet(onLink: (String) -> Unit, onDismiss: () -> Unit) {
    val page = PhoneImportPage(
        language = Locale.getDefault().language,
        rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl,
        title = stringResource(Res.string.phone_page_title),
        label = stringResource(Res.string.phone_page_label),
        send = stringResource(Res.string.phone_page_send),
        sent = stringResource(Res.string.phone_page_sent),
        empty = stringResource(Res.string.phone_page_empty)
    )
    val currentOnLink by rememberUpdatedState(onLink)
    var url by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        var server: PhoneImportServer? = null
        val job = scope.launch(Dispatchers.IO) {
            val address = PhoneImportServer.homeNetworkAddress()
            if (address == null) {
                withContext(Dispatchers.Main) { noNetwork = true }
                return@launch
            }
            val started = PhoneImportServer(address, page) { link ->
                scope.launch(Dispatchers.Main) { currentOnLink(link) }
            }
            server = started
            started.start(scope)
            withContext(Dispatchers.Main) { url = started.url }
        }
        onDispose {
            job.cancel()
            server?.close()
        }
    }

    val palette = LocalPkPalette.current
    PkBottomSheet(
        title = stringResource(Res.string.phone_import_title),
        subtitle = null,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(Res.string.phone_import_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val shown = url
            when {
                noNetwork -> Text(
                    stringResource(Res.string.phone_import_no_network),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.danger
                )
                shown != null -> {
                    val qr = remember(shown) { createQrBitmap(shown) }
                    Surface(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(Res.string.qr_code),
                            modifier = Modifier.size(220.dp).padding(10.dp)
                        )
                    }
                    Text(
                        shown,
                        style = pkMono(12, 0.2),
                        color = palette.textDim,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
                else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            PkSheetButton(label = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}
