package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Windows flip as the first start after it can show it: the Go cores an
 * earlier version staged are swept and the programs of this one are kept, and
 * the system's proxy values a killed run left are put back from the journal.
 */
class WindowsFlipTest {
    @Test fun theSweepTakesTheGoCoresOfEarlierWindowsBuildsAndKeepsOurPrograms() {
        val bin: Path = Files.createTempDirectory("windows-flip-sweep")
        try {
            val stale = listOf(
                "sing-box.exe", "xray.exe", "olcrtc-windows-amd64.exe", "olcrtc-windows-amd64.dll", "windows-tun-cache.db"
            )
            val ours = listOf("ghostlane-engine.exe", "hev.exe", "wintun.dll")
            for (name in stale + ours) Files.write(bin.resolve(name), byteArrayOf(1))
            val removed = StaleBinarySweep.sweep(bin)
            assertEquals(stale.toSet(), removed.toSet())
            for (name in stale) assertFalse(Files.exists(bin.resolve(name)), "$name was not swept")
            for (name in ours) assertTrue(Files.exists(bin.resolve(name)), "$name was swept")
            assertTrue(StaleBinarySweep.sweep(bin).isEmpty(), "a second sweep is not empty")
        } finally {
            bin.toFile().deleteRecursively()
        }
    }

    /** A registry that is a map, as WindowsSystemProxyTest has it. */
    private class Registry(val values: MutableMap<String, RegistryValue> = mutableMapOf()) {
        suspend fun run(command: List<String>): String {
            if (command.first() == "powershell.exe") return ""
            check(command.first() == "reg")
            val name = command[command.indexOf("/v") + 1]
            when (command[1]) {
                "query" -> {
                    val v = values[name] ?: error("not found")
                    return "\r\nHKEY_CURRENT_USER\\...\\Internet Settings\r\n    $name    ${v.type}    ${v.data}\r\n\r\n"
                }
                "add" -> {
                    val type = command[command.indexOf("/t") + 1]
                    var data = command[command.indexOf("/d") + 1]
                    if (type == "REG_DWORD") data = "0x" + data.toLong().toString(16)
                    values[name] = RegistryValue(type, data)
                }
                "delete" -> values.remove(name) ?: error("not found")
                else -> error(command.toString())
            }
            return ""
        }
    }

    private val pac = "http://127.0.0.1:10809/proxy.pac"

    private fun proxyOf(registry: Registry, journal: Path) =
        WindowsSystemProxy(registry::run).also { it.journal = journal }

    @Test fun aKilledRunsValuesAreRestoredFromItsJournalAtTheNextStart() = runTest {
        val dir = Files.createTempDirectory("windows-proxy-journal")
        try {
            val journal = dir.resolve("windows-proxy.json")
            val registry = Registry(mutableMapOf(
                "ProxyEnable" to RegistryValue("REG_DWORD", "0x1"),
                "ProxyServer" to RegistryValue("REG_SZ", "10.0.0.1:3128"),
            ))
            val before = registry.values.toMap()

            // The run that is killed: it enables, and never restores.
            proxyOf(registry, journal).enable(pac)
            assertTrue(Files.isRegularFile(journal), "the journal is written")
            assertEquals(RegistryValue("REG_SZ", pac), registry.values["AutoConfigURL"])

            // The next start.
            assertTrue(proxyOf(registry, journal).recover(), "the journal was found")
            assertEquals(before, registry.values, "the user's values are back, and AutoConfigURL, which was not there, is gone")
            assertFalse(Files.exists(journal), "the journal is spent")
            assertFalse(proxyOf(registry, journal).recover(), "a second recovery has nothing to do")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun anEnableAfterAKillDoesNotTakeTheDeadRunsValuesForTheUsers() = runTest {
        val dir = Files.createTempDirectory("windows-proxy-journal")
        try {
            val journal = dir.resolve("windows-proxy.json")
            val registry = Registry(mutableMapOf("AutoConfigURL" to RegistryValue("REG_SZ", "http://corp/wpad.pac")))
            val before = registry.values.toMap()
            proxyOf(registry, journal).enable(pac)

            val next = proxyOf(registry, journal)
            next.enable("http://127.0.0.1:10810/proxy.pac")
            next.restore()
            assertEquals(before, registry.values)
            assertFalse(Files.exists(journal))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun aRestoreSpendsTheJournalAndARunWithNoJournalWritesNone() = runTest {
        val dir = Files.createTempDirectory("windows-proxy-journal")
        try {
            val journal = dir.resolve("windows-proxy.json")
            val registry = Registry(mutableMapOf("ProxyEnable" to RegistryValue("REG_DWORD", "0x1")))
            val proxy = proxyOf(registry, journal)
            proxy.enable(pac)
            proxy.restore()
            assertFalse(Files.exists(journal))

            val plain = WindowsSystemProxy(registry::run)
            plain.enable(pac)
            assertFalse(Files.exists(journal), "no journal was asked for")
            plain.restore()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
