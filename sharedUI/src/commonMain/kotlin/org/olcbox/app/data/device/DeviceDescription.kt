package org.olcbox.app.data.device

/**
 * The device a list fetch comes from, as Happ names its own beside `x-hwid`:
 * `x-device-os` ([os]: `Android`, `iOS`, `macOS`, `Windows` or `Linux`),
 * `x-ver-os` ([osVersion]) and `x-device-model` ([model]).
 *
 * A provider that holds a list to a number of devices counts them by the id
 * and shows these three beside each one, so that whoever frees a place can
 * tell the phone from the laptop.
 */
data class DeviceDescription(
    val os: String,
    val osVersion: String,
    val model: String
)

/**
 * This device, as its platform describes it. The values are the platform's
 * own words; what a header carries of them is [asciiHeader]'s to decide.
 */
internal expect fun deviceDescription(): DeviceDescription

/** The longest a device header's value may be, in characters. */
internal const val DEVICE_HEADER_MAX_CHARS = 64

/**
 * [value] as a device header carries it, cut as ProofKit's server keeps one,
 * so that what is sent is what a provider shows: only printable ASCII is
 * kept — anything else is dropped, never turned into a space — then the first
 * [DEVICE_HEADER_MAX_CHARS] characters, trimmed at the edges. Empty when
 * nothing is left, and an empty value is not sent at all.
 */
internal fun asciiHeader(value: String): String =
    value.filter { it in ' '..'~' }.take(DEVICE_HEADER_MAX_CHARS).trim()
