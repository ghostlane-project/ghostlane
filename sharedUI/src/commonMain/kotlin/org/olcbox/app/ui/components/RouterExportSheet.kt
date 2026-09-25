package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.action_close
import multiplatform_app.sharedui.generated.resources.copied
import multiplatform_app.sharedui.generated.resources.router_copy_link
import multiplatform_app.sharedui.generated.resources.router_export_note
import multiplatform_app.sharedui.generated.resources.router_export_title
import multiplatform_app.sharedui.generated.resources.router_none
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.RouterExport
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.components.kit.pkSubscriptionIsSecret
import org.olcbox.app.ui.features.home.components.PkSheetButton
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.theme.LocalPkPalette

/** One server a router can carry: its name, its share link, and what the link parses to. */
data class RouterEntry(val name: String, val link: String, val spec: OutboundSpec)

/**
 * Whether a server list offers "Router" at all. Not an encrypted one: its provider
 * chose that no one reads its servers out of it (owner's decision, 2026-09-25).
 */
fun offersRouterExport(item: SubscriptionShareItem): Boolean = !pkSubscriptionIsSecret(item.url, item.originLink)

/** The servers of the list at [url] a router can carry; an olcRTC room needs the app, so it has none. */
fun routerEntries(locations: List<LocationItem>, url: String): List<RouterEntry> =
    locations
        .filter { it.subscriptionUrl?.trim() == url.trim() }
        .mapNotNull { item ->
            val link = item.config?.rawLink?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val spec = LinkParser.parse(link) ?: return@mapNotNull null
            RouterEntry(item.fullName, link, spec)
        }

/**
 * A server list's servers in the forms a router takes (see [RouterExport]): the link
 * for Podkop's URL mode, a sing-box outbound for its outbound mode, an Xray outbound
 * for XKeen. Each copies to the clipboard through [onCopy].
 */
@Composable
fun RouterExportSheet(entries: List<RouterEntry>, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPkPalette.current
    var copied by remember { mutableStateOf<Pair<Int, String>?>(null) }
    PkBottomSheet(
        title = stringResource(Res.string.router_export_title),
        subtitle = null,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.router_export_note),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textDim
            )
            if (entries.isEmpty()) {
                Text(
                    stringResource(Res.string.router_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                entries.forEachIndexed { index, entry ->
                    Column {
                        Text(
                            entry.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(entry.spec.routerLabel(), fontSize = 12.sp, color = palette.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val forms = listOfNotNull(
                                LINK to entry.link,
                                RouterExport.singBoxOutbound(entry.spec)?.let { SING_BOX to it },
                                XRAY to RouterExport.xrayOutbound(entry.spec)
                            )
                            forms.forEach { (form, text) ->
                                TextButton(onClick = {
                                    onCopy(text)
                                    copied = index to form
                                }) {
                                    Text(
                                        if (copied == index to form) stringResource(Res.string.copied)
                                        else if (form == LINK) stringResource(Res.string.router_copy_link)
                                        else form
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PkSheetButton(label = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}

private const val LINK = "link"
private const val SING_BOX = "sing-box"
private const val XRAY = "Xray"

/** `VLESS · Reality · XHTTP`: what a router's own form will ask for, in its words. */
private fun OutboundSpec.routerLabel(): String = when (this) {
    is OutboundSpec.Vless -> listOf("VLESS", if (publicKey.isNotBlank()) "Reality" else "TLS", transport.routerLabel())
    is OutboundSpec.Hysteria2 -> listOf("Hysteria2")
    is OutboundSpec.Trojan -> listOf("Trojan", transport.routerLabel())
    is OutboundSpec.Shadowsocks -> listOf("Shadowsocks", method)
    is OutboundSpec.Vmess -> listOf("VMess", transport.routerLabel())
}.filter { it.isNotBlank() }.joinToString(" · ")

private fun TransportSpec.routerLabel(): String = when (this) {
    TransportSpec.Tcp -> "TCP"
    is TransportSpec.Grpc -> "gRPC"
    is TransportSpec.Ws -> "WebSocket"
    is TransportSpec.HttpUpgrade -> "HTTPUpgrade"
    is TransportSpec.Xhttp -> "XHTTP"
}
