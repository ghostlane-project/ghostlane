package org.olcbox.app.net

/**
 * The engine's DTLS ClientHello profiles (olcrtc#52, `dtls.profile` in the
 * engine's yaml, `Runtime.SetDTLSProfile` on mobile). [OFF] keeps the WebRTC
 * library's own handshake; [CHROME] is Chrome 138's ClientHello less the five
 * cipher suites that library cannot negotiate. The engine refuses any other
 * name before it dials, so these two strings are the whole vocabulary.
 */
object OlcrtcDtls {
    const val OFF = "off"
    const val CHROME = "chrome-linux-138-compat-v1"

    /** The profile the engine is handed for the setting [chrome]. */
    fun profile(chrome: Boolean): String = if (chrome) CHROME else OFF
}
