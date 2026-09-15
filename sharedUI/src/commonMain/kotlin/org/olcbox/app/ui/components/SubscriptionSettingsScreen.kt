package org.olcbox.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.olcbox.app.ui.components.kit.PkSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.ui.components.kit.PkSectionLabel
import org.olcbox.app.ui.icons.PkIcons

/**
 * One subscriptions screen for every platform.
 *
 * It lives here, public, rather than inside the iOS settings sheet, because the
 * point of the request was that the platforms match: Android and desktop keep
 * their own settings — they have split tunneling and a connection mode that iOS
 * does not — and reach *this* composable for the part that is common. Two
 * hand-written copies of a screen with eleven controls stay identical for about
 * a week.
 */
@Composable
fun SubscriptionSettingsScreen(
    settings: SubscriptionSettings,
    onChanged: (SubscriptionSettings) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 32.dp)
    ) {
        SubscriptionSettingsHeader(
            title = "Server lists",
            subtitle = settings.hubSummary(),
            onBack = onBack
        )

        Spacer(Modifier.height(20.dp))
        PkSectionLabel("Updating")
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubscriptionToggleRow(
                title = "Auto update",
                checked = settings.autoUpdate,
                onCheckedChange = { onChanged(settings.copy(autoUpdate = it)) }
            )

            // Tapping cycles rather than opening a dialog: seven choices do not
            // earn a screen of their own, and the current one is on the row.
            SubscriptionValueRow(
                title = "Update interval (h)",
                value = settings.updateIntervalHours.toString(),
                enabled = settings.autoUpdate,
                onClick = {
                    val choices = SubscriptionSettings.INTERVAL_CHOICES
                    val next = choices[
                        (choices.indexOf(settings.updateIntervalHours) + 1) % choices.size
                    ]
                    onChanged(settings.copy(updateIntervalHours = next))
                }
            )

            SubscriptionToggleRow(
                title = "Notify about updates",
                checked = settings.notifyOnUpdate,
                onCheckedChange = { onChanged(settings.copy(notifyOnUpdate = it)) }
            )
        }

        SubscriptionSettingsNote(
            "Sets how often server lists refresh themselves while the app is running."
        )

        Spacer(Modifier.height(18.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubscriptionToggleRow(
                title = "Update on open",
                checked = settings.refreshOnOpen,
                onCheckedChange = { onChanged(settings.copy(refreshOnOpen = it)) }
            )
            SubscriptionToggleRow(
                title = "Ping on launch",
                checked = settings.pingOnLaunch,
                onCheckedChange = { onChanged(settings.copy(pingOnLaunch = it)) }
            )
            SubscriptionToggleRow(
                title = "Connect on launch",
                checked = settings.connectOnLaunch,
                onCheckedChange = { onChanged(settings.copy(connectOnLaunch = it)) }
            )
        }

        SubscriptionSettingsNote(
            "Refreshing, connecting and measuring latency happen every time the app starts."
        )

        Spacer(Modifier.height(18.dp))
        PkSectionLabel("Server list")
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubscriptionSort.entries.forEach { option ->
                SubscriptionChoiceRow(
                    title = option.label(),
                    selected = settings.sort == option,
                    onClick = { onChanged(settings.copy(sort = option)) }
                )
            }

            SubscriptionToggleRow(
                title = "Prevent duplicates",
                checked = settings.preventDuplicates,
                onCheckedChange = { onChanged(settings.copy(preventDuplicates = it)) }
            )
        }

        SubscriptionSettingsNote(
            "Importing a server list URL that is already here refreshes the one " +
                "you have instead of adding a second copy of it."
        )

        Spacer(Modifier.height(18.dp))

        SubscriptionToggleRow(
            title = "Collapsible server lists",
            checked = settings.collapsible,
            onCheckedChange = { onChanged(settings.copy(collapsible = it)) }
        )

        SubscriptionSettingsNote(
            "Lets a server list's servers be folded away. Off, every list " +
                "always shows all of them."
        )
    }
}

/** The explanatory line Happ puts under a block of switches. */
@Composable
private fun SubscriptionSettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp)
    )
}

@Composable
private fun SubscriptionToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PkSwitch(checked = checked)
        }
    }
}

/** One of a set. A tick rather than a radio, to match the rest of this sheet. */
@Composable
private fun SubscriptionChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** What the hub row says without opening anything. */
fun SubscriptionSettings.hubSummary(): String = listOfNotNull(
    if (autoUpdate) "Every ${updateIntervalHours}h" else "Manual",
    sort.takeIf { it != SubscriptionSort.None }?.label()
).joinToString(" · ")

/** Back arrow and title, so the screen is self-contained wherever it is shown. */
@Composable
private fun SubscriptionSettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A row that shows a value and changes it when tapped.
 *
 * Its own rather than the settings sheet's, which is private to that file — and
 * rightly so: this screen is shown by Android's sheet too, and a shared screen
 * that reaches into one platform's internals is shared in name only.
 */
@Composable
private fun SubscriptionValueRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = PkIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
