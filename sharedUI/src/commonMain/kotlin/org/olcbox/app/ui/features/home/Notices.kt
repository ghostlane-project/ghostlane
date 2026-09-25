package org.olcbox.app.ui.features.home

import androidx.compose.runtime.Composable
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.olcrtc_failure_key
import multiplatform_app.sharedui.generated.resources.olcrtc_failure_key_gone
import multiplatform_app.sharedui.generated.resources.olcrtc_failure_no_peer
import multiplatform_app.sharedui.generated.resources.olcrtc_failure_protocol
import multiplatform_app.sharedui.generated.resources.olcrtc_failure_silent
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.olcbox.app.vpn.OlcrtcFailure

/**
 * A notice in the user's language.
 *
 * The failures the app classifies itself ([OlcrtcFailure]) are fixed English
 * sentences that the state logic compares against, so they stay the identity and
 * are translated here, where they are shown. Anything else — a platform's own
 * message, an exception's — is shown as it came.
 */
@Composable
internal fun localizedNotice(text: String): String = NOTICES[text]?.let { stringResource(it) } ?: text

internal val NOTICES: Map<String, StringResource> = mapOf(
    OlcrtcFailure.PROTOCOL to Res.string.olcrtc_failure_protocol,
    OlcrtcFailure.KEY to Res.string.olcrtc_failure_key,
    OlcrtcFailure.SILENT to Res.string.olcrtc_failure_silent,
    OlcrtcFailure.KEY_GONE to Res.string.olcrtc_failure_key_gone,
    OlcrtcFailure.NO_PEER to Res.string.olcrtc_failure_no_peer
)
