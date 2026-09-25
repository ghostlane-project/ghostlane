package org.olcbox.app.ui.features.home.components

import multiplatform_app.sharedui.generated.resources.add_from_phone_subtitle
import multiplatform_app.sharedui.generated.resources.add_from_phone
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.add_connection
import multiplatform_app.sharedui.generated.resources.add_connection_subtitle
import multiplatform_app.sharedui.generated.resources.add_custom
import multiplatform_app.sharedui.generated.resources.add_custom_subtitle
import multiplatform_app.sharedui.generated.resources.add_file
import multiplatform_app.sharedui.generated.resources.add_file_subtitle
import multiplatform_app.sharedui.generated.resources.add_paste
import multiplatform_app.sharedui.generated.resources.add_paste_subtitle
import multiplatform_app.sharedui.generated.resources.add_scan_qr
import multiplatform_app.sharedui.generated.resources.add_scan_qr_subtitle
import multiplatform_app.sharedui.generated.resources.add_update_lists
import multiplatform_app.sharedui.generated.resources.add_update_lists_subtitle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

@Composable
fun AddConfigurationSheet(
    canScanQr: Boolean,
    hasSubscriptions: Boolean,
    onDismiss: () -> Unit,
    onScanQrClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
    onImportFileClick: () -> Unit,
    /** "From your phone", where the platform offers it; null leaves the row out. */
    onImportFromPhoneClick: (() -> Unit)? = null,
    onUpdateSubscriptionsClick: () -> Unit,
    onAddCustomLocationClick: () -> Unit,
    onGetSubscriptionClick: () -> Unit = {},
    /**
     * Kept as a guard after the row it gated was removed outright.
     *
     * App Store guideline 3.1.1: a call to action inside the app that sends a
     * user to buy digital content outside In-App Purchase is not allowed. The row
     * that did this is gone from the source, not merely switched off — twice now
     * review has read the app as a front end for a paid plan, and a control that
     * only a boolean stands between us and shipping is not an answer to that.
     * Every platform passes `false`; nothing reads this any more, and a build
     * that wants such a row has to add it back deliberately.
     */
    showGetSubscription: Boolean = true,
    showCustomLocation: Boolean = true
) {
    PkBottomSheet(
        title = stringResource(Res.string.add_connection),
        subtitle = stringResource(Res.string.add_connection_subtitle),
        onDismiss = onDismiss
    ) {
        AddConfigurationBody(
            canScanQr = canScanQr,
            hasSubscriptions = hasSubscriptions,
            showCustomLocation = showCustomLocation,
            onScanQrClick = onScanQrClick,
            onPasteLinkClick = onPasteLinkClick,
            onImportFileClick = onImportFileClick,
            onImportFromPhoneClick = onImportFromPhoneClick,
            onUpdateSubscriptionsClick = onUpdateSubscriptionsClick,
            onAddCustomLocationClick = onAddCustomLocationClick
        )
    }
}


/** The sheet's contents, separately so a test can render them. */
@Composable
internal fun AddConfigurationBody(
    canScanQr: Boolean,
    hasSubscriptions: Boolean,
    showCustomLocation: Boolean,
    onScanQrClick: () -> Unit,
    onPasteLinkClick: () -> Unit,
    onImportFileClick: () -> Unit,
    /** "From your phone", where the platform offers it; null leaves the row out. */
    onImportFromPhoneClick: (() -> Unit)? = null,
    onUpdateSubscriptionsClick: () -> Unit,
    onAddCustomLocationClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // No row here points at a purchase. Someone with nothing yet gets the
        // import actions below and nothing else — where they obtained a server
        // list is not this app's business.

        // First where it is offered: on a TV it is the one way in that works.
        onImportFromPhoneClick?.let { onClick ->
            PkSheetActionRow(
                title = stringResource(Res.string.add_from_phone),
                subtitle = stringResource(Res.string.add_from_phone_subtitle),
                icon = PkIcons.QrCodeScanner,
                accent = true,
                onClick = onClick
            )
        }

        if (canScanQr) {
            PkSheetActionRow(
                title = stringResource(Res.string.add_scan_qr),
                subtitle = stringResource(Res.string.add_scan_qr_subtitle),
                icon = PkIcons.QrCodeScanner,
                accent = true,
                onClick = onScanQrClick
            )
        }

        PkSheetActionRow(
            title = stringResource(Res.string.add_paste),
            subtitle = stringResource(Res.string.add_paste_subtitle),
            icon = PkIcons.Input,
            onClick = onPasteLinkClick
        )

        PkSheetActionRow(
            title = stringResource(Res.string.add_file),
            subtitle = stringResource(Res.string.add_file_subtitle),
            icon = PkIcons.FileOpen,
            onClick = onImportFileClick
        )

        if (hasSubscriptions) {
            PkSheetActionRow(
                title = stringResource(Res.string.add_update_lists),
                subtitle = stringResource(Res.string.add_update_lists_subtitle),
                icon = Icons.Outlined.Refresh,
                showChevron = false,
                onClick = onUpdateSubscriptionsClick
            )
        }

        if (showCustomLocation) {
            PkSheetActionRow(
                title = stringResource(Res.string.add_custom),
                subtitle = stringResource(Res.string.add_custom_subtitle),
                icon = Icons.Outlined.Add,
                onClick = onAddCustomLocationClick
            )
        }
    }
}

/**
 * One thing a sheet offers to do: a round icon, a title, a mono sub, a chevron.
 *
 * [accent] lifts the one action that is the point of the sheet — scanning a code
 * is how a server list arrives in the ordinary case, and the rest are the ways it
 * arrives when that is not possible.
 */
@Composable
fun PkSheetActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    showChevron: Boolean = true
) {
    val palette = LocalPkPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = title, role = Role.Button) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) palette.accent else palette.link,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(13.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = subtitle,
                style = pkMono(10, 0.5),
                color = palette.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = PkIcons.ChevronRight,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** A full-width button inside a sheet. Lime for the one that commits. */
@Composable
fun PkSheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val palette = LocalPkPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(if (primary) palette.accent else Color.Transparent)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(15.dp)
                    )
                }
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) { onClick() }
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            style = pkMono(12, 1.5).copy(fontWeight = FontWeight.SemiBold),
            color = when {
                !enabled -> palette.textMuted
                primary -> MaterialTheme.colorScheme.onTertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1
        )
    }
}
