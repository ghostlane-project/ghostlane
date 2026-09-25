package org.olcbox.app.ui.components

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_accept
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_body
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_decline
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_subtitle
import multiplatform_app.sharedui.generated.resources.vpn_disclosure_title
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkBottomSheet
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.features.home.components.PkSheetButton
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * What the system VPN does here, said once and accepted deliberately.
 *
 * Google Play requires this of anything whose core function is a VPN, and the
 * requirement is narrower than a privacy notice: it has to be in the app rather
 * than the listing or the policy page, it has to appear in the ordinary course
 * of using the app rather than somewhere in settings, it has to name what the
 * VpnService API handles, and it has to be accepted by a deliberate action.
 * It also may not be combined with any other consent — this screen is about the
 * tunnel and nothing else, which is why it is not part of onboarding.
 *
 * Shown on every platform, not only the one that demands it. The behaviour is
 * the same everywhere and a screen that exists on one platform only is a screen
 * that stops matching the others the first time either is touched.
 *
 * Declining is a real outcome, not a dismissal: `dismissible = false` removes the
 * drag handle and refuses a tap outside, because "carry on without answering" is
 * exactly what the policy forbids and what a reviewer looks for.
 */
@Composable
fun VpnDisclosureScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    PkBottomSheet(
        title = stringResource(Res.string.vpn_disclosure_title),
        subtitle = stringResource(Res.string.vpn_disclosure_subtitle),
        onDismiss = onDecline,
        dismissible = false
    ) {
        VpnDisclosureBody(onAccept = onAccept, onDecline = onDecline)
    }
}

/** The sheet's contents, separately so a test can render them. */
@Composable
internal fun VpnDisclosureBody(onAccept: () -> Unit, onDecline: () -> Unit) {
    val palette = LocalPkPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
                // Bounded and scrollable so the whole text can be read on a
                // small screen — and so it can be scrolled through slowly on
                // camera, which is what the Play declaration video has to show.
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            disclosureBlocks(stringResource(Res.string.vpn_disclosure_body)).forEach { block ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.heading?.let { heading ->
                        Text(
                            text = heading,
                            style = pkMono(9, 1.5),
                            color = palette.accent
                        )
                    }
                    block.paragraphs.forEach { paragraph ->
                        Row {
                            Box(
                                Modifier
                                    .padding(top = 7.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        PkSheetButton(label = stringResource(Res.string.vpn_disclosure_accept), onClick = onAccept, primary = true)
        PkSheetButton(label = stringResource(Res.string.vpn_disclosure_decline), onClick = onDecline)
    }
}

/** One section of the disclosure: an optional heading and the lines under it. */
data class DisclosureBlock(val heading: String?, val paragraphs: List<String>)

/**
 * Splits [DISCLOSURE_BODY] into the sections it is already written in.
 *
 * A formatting change, not a wording one. The text is one string with ALL-CAPS
 * headings and blank lines between paragraphs; this reads that structure back so
 * the sheet can set the headings as eyebrows and the paragraphs as bullets,
 * instead of rendering a wall of prose nobody scrolls.
 *
 * Every non-empty line of the source ends up in exactly one block — see the
 * round-trip test. A parser that silently dropped a paragraph would remove part
 * of a legally required notice and look fine doing it.
 */
fun disclosureBlocks(body: String): List<DisclosureBlock> {
    val blocks = mutableListOf<DisclosureBlock>()
    var heading: String? = null
    var paragraphs = mutableListOf<String>()

    fun flush() {
        if (heading != null || paragraphs.isNotEmpty()) {
            blocks += DisclosureBlock(heading, paragraphs.toList())
        }
        heading = null
        paragraphs = mutableListOf()
    }

    body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { chunk ->
        if (chunk.isDisclosureHeading()) {
            flush()
            heading = chunk
        } else {
            paragraphs += chunk
        }
    }
    flush()
    return blocks
}

/**
 * A heading is a short line with no lower-case letter in it, and no sentence's end.
 *
 * The last part is for the scripts that have no case at all — Chinese, Persian —
 * where every line passes the first two tests and a short paragraph would become a
 * heading. A paragraph ends like a sentence; a heading does not.
 */
private fun String.isDisclosureHeading(): Boolean =
    length <= 40 && none { it.isLowerCase() } && any { it.isLetter() } && last() !in SENTENCE_ENDS

private const val SENTENCE_ENDS = ".。!！?？؟…:："

/**
 * Deliberately specific about what the tunnel does rather than about privacy in
 * general. A reviewer is checking that the user was told what happens to their
 * traffic, and a paragraph of reassurance that never mentions it does not answer
 * that.
 *
 * **This is commonMain and iOS renders it too.** It was written for Play's
 * prominent disclosure and said "Android will ask you", which is what an iPhone
 * showed until 2026-08-14 — keep the wording platform-neutral, or split it
 * properly per platform, but do not name one of them here.
 *
 * **Do not write "subscription" in this text.** Every other user-visible string
 * says "server list"; the word means a configuration feed to us and a monthly
 * charge to App Review, and it cost two rejections under Guideline 3.1.1. Wire
 * and storage names keep the old spelling on purpose — this is about what a user
 * reads.
 */
internal const val DISCLOSURE_BODY = """Ghostlane connects using your device's system VPN.

WHAT IT DOES

While connected, network traffic from this device is routed through the server you selected from your own server list. This is what a VPN is, and it is the only reason this app asks for VPN permission.

Your device will ask you to allow this separately, in its own dialog, the first time you connect. You can end the connection at any time from this app or from system settings.

WHAT THIS APP DOES WITH IT

Nothing is collected. The app does not read, record or transmit the contents of your traffic, the addresses you visit, or anything identifying you. There is no account here, no analytics, and no advertising identifier.

Your server lists and the app's own log stay on this device.

WHERE YOUR TRAFFIC GOES

To the server you selected, and nowhere else. That server is operated by whoever gave you the server list, and what it does with your traffic is governed by them — Ghostlane does not run it and does not sell one.

Traffic is encrypted between this device and that server.

Traffic is never redirected for advertising, and never routed anywhere for any purpose other than the connection you asked for."""
