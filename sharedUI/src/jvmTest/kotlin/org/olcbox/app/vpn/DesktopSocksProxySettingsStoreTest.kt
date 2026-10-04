package org.olcbox.app.vpn

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Linux tunnel's kill switch is kept with the desktop's other settings.
// It costs a machine its network when the tunnel dies, so it is on only for
// someone who turned it on: never by default, and never by an update.
class DesktopSocksProxySettingsStoreTest {
    private fun newStoreFile() =
        Files.createTempDirectory("olcbox-desktop-settings").resolve("desktop_socks_proxy_settings.json")

    @Test
    fun theKillSwitchIsOffUntilTurnedOnAndComesBackAsItWasSaved() {
        runBlocking {
            val store = JvmDesktopSocksProxySettingsStore(newStoreFile())
            assertFalse(store.load().killSwitch, "no file yet")

            store.save(DesktopSocksProxySettings(username = "olcbox-a", password = "secret", killSwitch = true))
            val on = store.load()
            assertTrue(on.killSwitch)
            assertEquals("olcbox-a", on.username)

            store.save(on.copy(killSwitch = false))
            assertFalse(store.load().killSwitch)
        }
    }

    // The file of an install that has never seen the switch: every field of
    // that time, and no kill switch among them.
    @Test
    fun aStoreWrittenBeforeTheSwitchExistedReadsAsOff() {
        runBlocking {
            val file = newStoreFile()
            Files.writeString(
                file,
                """
                {
                    "host": "127.0.0.1",
                    "port": 10811,
                    "username": "olcbox-a",
                    "password": "secret",
                    "shareOnLan": true,
                    "lanAddress": "192.168.1.5",
                    "lanNetworkId": "aa:bb:cc:dd:ee:ff",
                    "lanPort": 10818,
                    "lanUsername": "ghostlane-x",
                    "lanPassword": "y"
                }
                """.trimIndent()
            )

            val loaded = JvmDesktopSocksProxySettingsStore(file).load()
            assertFalse(loaded.killSwitch)
            // And it was the file that was read, not the defaults a file that
            // cannot be read falls back to.
            assertEquals(10811, loaded.port)
            assertTrue(loaded.shareOnLan)
        }
    }

    // Every other change on the settings screen copies the settings it holds
    // and normalizes them; the switch must not be lost on the way.
    @Test
    fun normalizingKeepsTheKillSwitch() {
        assertTrue(DesktopSocksProxySettings(killSwitch = true).normalized().killSwitch)
        assertFalse(DesktopSocksProxySettings().normalized().killSwitch)
    }
}
