package org.olcbox.app.ui.render

import java.io.File
import kotlin.test.Test

/**
 * Pictures of the home screen on the phones it has to fit, for a person to look at.
 *
 * Written only when `OLCBOX_RENDER_DIR` names a directory:
 *
 *     OLCBOX_RENDER_DIR=/tmp/renders ./gradlew :sharedUI:jvmTest --rerun --tests '*BoardRenderTest*'
 */
class BoardRenderTest {
    private val phones = listOf(
        Phone("se1", 320, 568),
        Phone("zoomed", 320, 693),
        Phone("android", 360, 780),
        Phone("se3", 375, 667),
        Phone("iphone", 393, 852),
        Phone("max", 430, 932)
    )
    private val scales = listOf(1f, 1.3f, 1.6f, 2f)
    private val languages = listOf("ru", "en")

    @Test fun theBoardOnEveryPhone() {
        val dir = renderDir() ?: return
        val scenes = mapOf(
            "in-room" to BoardFixture.Scene(connected = true),
            "idle" to BoardFixture.Scene(connected = false, selectedId = BoardFixture.FLAGGED, seats = false),
            "joining" to BoardFixture.Scene(connected = false, connecting = true),
            "admin" to BoardFixture.Scene(connected = true, admin = true, lowest = true),
            "new" to BoardFixture.Scene(connected = false, empty = true),
            "failed" to BoardFixture.Scene(connected = false, seats = false, notice = BoardFixture.NOTICE),
            // The list itself where the head has scrolled away with it: what a
            // short screen with large text shows once it is scrolled.
            "in-room-scrolled" to BoardFixture.Scene(connected = true, scrolledBy = 560),
            "pinned" to BoardFixture.Scene(
                connected = true,
                pinned = listOf(BoardFixture.ROOM_IN_USE, "ours-ES-VK", "theirs-nl"),
                folded = setOf(BoardFixture.THEIR_LIST)
            ),
            // Every list folded: what is left is what was pinned.
            "pinned-folded" to BoardFixture.Scene(
                connected = true,
                pinned = listOf(BoardFixture.ROOM_IN_USE, "ours-ES-VK", "theirs-nl"),
                folded = setOf(BoardFixture.OUR_LIST, BoardFixture.THEIR_LIST)
            )
        )
        for ((sceneName, scene) in scenes) {
            for (phone in phones) for (scale in scales) for (language in languages) {
                val shaped = phone.copy(fontScale = scale, language = language)
                File(dir, "$sceneName-${shaped.label}.png").writeBytes(renderPng(shaped) { BoardFixture.Home(scene) })
            }
            // Two panes — a tablet on its side, and the desktop window — and the
            // two languages whose lines run differently: right to left, and with
            // no spaces between words.
            val others = listOf(
                Phone("tablet", 1024, 768),
                Phone("tablet", 1024, 768, 1.6f, "ru"),
                Phone("desktop", 800, 640, 1f, "ru"),
                Phone("iphone", 393, 852, 1f, "fa"),
                Phone("zoomed", 320, 693, 1.6f, "fa"),
                Phone("iphone", 393, 852, 1.3f, "zh")
            )
            for (other in others) {
                File(dir, "$sceneName-${other.label}.png").writeBytes(renderPng(other) { BoardFixture.Home(scene) })
            }
        }
    }
}
