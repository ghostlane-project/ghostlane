package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** System-proxy mode on Windows, against a registry that is a map. */
class WindowsSystemProxyTest {
    private class FakeRegistry(val values: MutableMap<String, RegistryValue> = mutableMapOf()) {
        val refreshes = mutableListOf<List<String>>()

        suspend fun run(command: List<String>): String {
            if (command.first() == "powershell.exe") { refreshes += command; return "" }
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

    @Test fun enableSetsThePacAndTurnsTheFixedProxyOffAndRestoreIsTheUsersOwnValues() = runTest {
        val registry = FakeRegistry(mutableMapOf(
            "ProxyEnable" to RegistryValue("REG_DWORD", "0x1"),
            "ProxyServer" to RegistryValue("REG_SZ", "10.0.0.1:3128"),
            "ProxyOverride" to RegistryValue("REG_SZ", "<local>;  *.corp"),
        ))
        val before = registry.values.toMap()
        val proxy = WindowsSystemProxy(registry::run)

        proxy.enable(pac)
        assertEquals(RegistryValue("REG_DWORD", "0x0"), registry.values["ProxyEnable"])
        assertEquals(RegistryValue("REG_SZ", pac), registry.values["AutoConfigURL"])
        assertEquals(before["ProxyServer"], registry.values["ProxyServer"])

        proxy.restore()
        assertEquals(before, registry.values)
        assertTrue(registry.refreshes.size >= 2, "WinINET is told after each change")
    }

    @Test fun aSecondEnableDoesNotTakeOurValuesForTheUsers() = runTest {
        val registry = FakeRegistry(mutableMapOf("AutoConfigURL" to RegistryValue("REG_SZ", "http://corp/wpad.pac")))
        val proxy = WindowsSystemProxy(registry::run)
        proxy.enable(pac)
        proxy.enable("http://127.0.0.1:10810/proxy.pac")
        proxy.restore()
        assertEquals(mapOf("AutoConfigURL" to RegistryValue("REG_SZ", "http://corp/wpad.pac")), registry.values)
    }

    @Test fun aRestoreWithNothingEnabledChangesNothing() = runTest {
        val registry = FakeRegistry()
        WindowsSystemProxy(registry::run).restore()
        assertTrue(registry.values.isEmpty())
        assertTrue(registry.refreshes.isEmpty())
    }

    @Test fun aWriteThatFailsHalfWayPutsBackWhatWasChanged() = runTest {
        val registry = FakeRegistry(mutableMapOf("ProxyEnable" to RegistryValue("REG_DWORD", "0x1")))
        val proxy = WindowsSystemProxy { command ->
            if ("AutoConfigURL" in command && command[1] == "add") error("access denied")
            registry.run(command)
        }
        runCatching { proxy.enable(pac) }.also { assertTrue(it.isFailure) }
        assertEquals(mapOf("ProxyEnable" to RegistryValue("REG_DWORD", "0x1")), registry.values)
    }

    @Test fun reQueryTextIsReadWithItsTypeAndItsSpaces() {
        val text = "\r\nHKEY_CURRENT_USER\\x\r\n    ProxyOverride    REG_SZ    <local>;  *.corp\r\n    ProxyOverrideX    REG_SZ    no\r\n"
        assertEquals(RegistryValue("REG_SZ", "<local>;  *.corp"), WindowsSystemProxy.parseValue("ProxyOverride", text))
        assertEquals(RegistryValue("REG_SZ", ""), WindowsSystemProxy.parseValue("AutoConfigURL", "    AutoConfigURL    REG_SZ    \r\n"))
        assertNull(WindowsSystemProxy.parseValue("ProxyServer", text))
    }

    @Test fun aDwordIsWrittenBackInDecimal() {
        val command = WindowsSystemProxy.restoreCommands(mapOf("ProxyEnable" to RegistryValue("REG_DWORD", "0x1f")))
        assertEquals("31", command.single()[command.single().indexOf("/d") + 1])
    }
}
