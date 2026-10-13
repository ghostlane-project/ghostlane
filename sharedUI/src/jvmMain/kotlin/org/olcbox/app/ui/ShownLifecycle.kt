package org.olcbox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A window's content, told the truth about whether anybody can see it.
 *
 * A desktop window closed to the tray is hidden, not destroyed, and Compose goes
 * on calling it started: a window's lifecycle there knows minimised and
 * unfocused, and has nothing for put away. So whatever the content does only
 * while it is started went on behind a window nobody could see — the tick that
 * moves the session's timer and reads its traffic from the tunnel, once a
 * second, for as long as the app sat in the tray connected, which is how a
 * desktop VPN spends its day.
 *
 * Under this the content's lifecycle is the window's own while the window is
 * [shown], and no further than created while it is not.
 */
@Composable
fun WhileShown(shown: Boolean, content: @Composable () -> Unit) {
    val window = LocalLifecycleOwner.current
    val owner = remember(window) { ShownLifecycleOwner(window) }
    // Before the content composes, so that it never sees a window that is put
    // away as one that is not.
    owner.shown = shown
    DisposableEffect(owner) {
        owner.attach()
        onDispose { owner.detach() }
    }
    SideEffect { owner.settle() }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

/** [window]'s lifecycle, held at created while its content is not [shown]. */
internal class ShownLifecycleOwner(private val window: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val follow = LifecycleEventObserver { _, _ -> settle() }
    private var over = false

    override val lifecycle: Lifecycle get() = registry

    var shown: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            settle()
        }

    fun attach() {
        window.lifecycle.addObserver(follow)
        settle()
    }

    /** The content is gone: its lifecycle ends, and no longer follows the window's. */
    fun detach() {
        window.lifecycle.removeObserver(follow)
        over = true
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
    }

    fun settle() {
        if (over) return
        val windows = window.lifecycle.currentState
        val mine = if (shown || !windows.isAtLeast(Lifecycle.State.STARTED)) windows else Lifecycle.State.CREATED
        // A lifecycle that never began cannot be ended; it stays where it is.
        if (mine == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = mine
    }
}
