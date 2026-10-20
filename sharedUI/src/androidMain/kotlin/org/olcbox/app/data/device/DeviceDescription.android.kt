package org.olcbox.app.data.device

import android.os.Build

/** The release the user sees in Settings (`16`) and the model as its maker names it (`Pixel 9 Pro`). */
internal actual fun deviceDescription(): DeviceDescription = DeviceDescription(
    os = "Android",
    osVersion = Build.VERSION.RELEASE.orEmpty(),
    model = Build.MODEL.orEmpty()
)
