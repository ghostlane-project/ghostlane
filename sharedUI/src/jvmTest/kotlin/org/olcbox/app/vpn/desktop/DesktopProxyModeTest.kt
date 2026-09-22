package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopProxyModeTest {

    @Test
    fun pacRoutesLocalTrafficDirectAndEverythingElseThroughSocks() {
        val pac = PacServer.generatePac("127.0.0.1", 10808)

        assertContains(pac, "isPlainHostName(host)")
        assertContains(pac, "host == \"localhost\"")
        assertContains(pac, "SOCKS 127.0.0.1:10808; SOCKS5 127.0.0.1:10808")
        // Order matters: macOS reads the first entry and gives up on an unknown
        // token, so the standard "SOCKS" must come before the browser-only "SOCKS5".
        assertTrue(
            pac.indexOf("SOCKS 127.0.0.1") < pac.indexOf("SOCKS5 127.0.0.1"),
            "standard SOCKS must precede SOCKS5 or macOS falls back to DIRECT"
        )
    }

    @Test
    fun pacServerUpdatesSocksTargetWhileAlreadyRunning() {
        val server = PacServer(port = 0)

        server.start("127.0.0.1", 10808)
        server.start("127.0.0.1", 10810, "user", "pass")

        val pac = server.currentPacContent()
        assertContains(pac, "SOCKS user:pass@127.0.0.1:10810; SOCKS5 user:pass@127.0.0.1:10810")
        assertTrue("SOCKS5 127.0.0.1:10808" !in pac)

        server.stop()
    }

    @Test
    fun pacEscapesSocksCredentialsInUserInfo() {
        val pac = PacServer.generatePac(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUsername = "user name",
            socksPassword = "p@ss:word"
        )

        assertContains(
            pac,
            "SOCKS user%20name:p%40ss%3Aword@127.0.0.1:10808; " +
                    "SOCKS5 user%20name:p%40ss%3Aword@127.0.0.1:10808"
        )
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val expectedTransport = LocationConfig.normalizeTransport(
                LocationConfig.DEFAULT_TRANSPORT,
                provider
            )
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808,
                dnsServer = "192.168.43.1:53"
            )
            val args = command.args(Path.of("/tmp/client.yaml"))
            val yaml = command.yaml()

            assertEquals(listOf(Path.of("/tmp/olcrtc").toString(), Path.of("/tmp/client.yaml").toString()), args)
            assertContains(yaml, "mode: cnc")
            assertContains(yaml, "provider: '${OlcRtcCommand.desktopProviderArg(provider)}'")
            assertContains(yaml, "transport: '$expectedTransport'")
            assertContains(yaml, "id: 'room-$provider'")
            assertContains(yaml, "port: 10808")
            assertContains(yaml, "dns: '192.168.43.1:53'")
            // The upstream engine rejects unknown keys: the old tls/jitsi
            // blocks must not appear for any provider.
            assertTrue("tls:" !in yaml)
            assertTrue("insecure_skip_verify" !in yaml)
            assertTrue("jitsi:" !in yaml)
            assertTrue("link:" !in yaml)
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(yaml, "vp8:")
                assertContains(yaml, "fps: 60")
                assertContains(yaml, "batch_size: 64")
            }
            assertTrue("client-id" !in yaml)
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dnsServer = "192.168.43.1:53"
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_DATACHANNEL}'")
        assertTrue("vp8:" !in command)
        assertTrue("data:" !in command)
    }

    @Test
    fun olcRtcCommandAddsSeiDefaults() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Telemost",
                id = "room",
                key = "c".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_SEICHANNEL
            ),
            dnsServer = "192.168.43.1:53"
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_SEICHANNEL}'")
        assertContains(command, "sei:")
        assertContains(command, "fps: 60")
        assertContains(command, "batch_size: 64")
        assertContains(command, "fragment_size: 900")
        assertContains(command, "ack_timeout_ms: 2000")
        assertTrue("vp8:" !in command)
    }

    @Test
    fun nativeLibrarySpecSelectsPlatformFiles() {
        assertEquals(
            "libolcrtc-darwin-arm64.dylib",
            olcRtcNativeLibrarySpec("Mac OS X", "aarch64")?.fileName
        )
        assertEquals(
            "libolcrtc-linux-amd64.so",
            olcRtcNativeLibrarySpec("Linux", "x86_64")?.fileName
        )
        assertEquals(
            "olcrtc-windows-amd64.dll",
            olcRtcNativeLibrarySpec("Windows 11", "amd64")?.fileName
        )
    }

    @Test
    fun linuxTunConfigCanRunRouteScriptsInsidePrivilegedTunnelProcess() {
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            postUpScript = "/tmp/olcbox-up.sh",
            preDownScript = "/tmp/olcbox-down.sh"
        )

        assertContains(config, "port: 10810")
        assertContains(config, "post-up-script: /tmp/olcbox-up.sh")
        assertContains(config, "pre-down-script: /tmp/olcbox-down.sh")
    }

    @Test
    fun olcRtcCommandUsesDesktopWbStreamProviderAlias() {
        listOf(LocationConfig.PROVIDER_WB_STREAM, "wbstream").forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig(
                    name = "WB",
                    id = "room-wb",
                    key = "b".repeat(64),
                    bypassProvider = provider
                ),
                dnsServer = "192.168.43.1:53"
            ).yaml()

            assertContains(command, "provider: 'wbstream'")
        }
    }

    // "connect" for olcRTC is building this yaml: `auth.provider` is the only
    // thing that selects which named engine instance (telemost/wbstream/
    // salutejazz) the binary connects as. No process is spawned here, so this
    // is as close to the engine's own provider registry as a JVM test reaches.
    @Test
    fun olcRtcCommandSelectsSalutejazzEngineName() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "SJ",
                id = "zzz999:pw123456",
                key = "e".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_SALUTEJAZZ,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dnsServer = "192.168.43.1:53"
        ).yaml()

        assertContains(command, "provider: 'salutejazz'")
        assertContains(command, "transport: '${LocationConfig.TRANSPORT_DATACHANNEL}'")
        assertContains(command, "id: 'zzz999:pw123456'")
    }

    /**
     * macOS sets the SOCKS proxy natively. A PAC was installed correctly and the
     * tunnel carried traffic when dialled directly, yet the browser still went out
     * untunnelled — CFNetwork stops at the first proxy token it does not recognise.
     * Setting SOCKS directly takes that parsing step out of the path, and the PAC is
     * explicitly turned off so it is never ambiguous which one macOS is honouring.
     */
    @Test
    fun macOsProxyCommandsSetSocksNativelyAndDisablePac() {
        val enable = MacOsProxyController.enableCommands(
            listOf("Wi-Fi"),
            DesktopProxyTarget(
                pacUrl = "http://127.0.0.1:10809/proxy.pac",
                socksHost = "127.0.0.1",
                socksPort = 10810
            )
        )
        assertEquals(
            listOf(
                listOf("networksetup", "-setsocksfirewallproxy", "Wi-Fi", "127.0.0.1", "10810", "off"),
                listOf("networksetup", "-setsocksfirewallproxystate", "Wi-Fi", "on"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "off")
            ),
            enable
        )
    }

    @Test
    fun macOsProxyPassesCredentialsWhenTheSocksNeedsThem() {
        val enable = MacOsProxyController.enableCommands(
            listOf("Wi-Fi"),
            DesktopProxyTarget(
                pacUrl = "http://127.0.0.1:10809/proxy.pac",
                socksHost = "127.0.0.1",
                socksPort = 10808,
                username = "olcbox",
                password = "pw"
            )
        )
        assertEquals(
            listOf("networksetup", "-setsocksfirewallproxy", "Wi-Fi", "127.0.0.1", "10808", "on", "olcbox", "pw"),
            enable.first()
        )
    }

    @Test
    fun macOsRestoreClearsOurSocksAndPutsBackAnyPreviousPac() {
        val restore = MacOsProxyController.restoreCommands(
            listOf(
                MacOsAutoProxyState("Wi-Fi", enabled = true, url = "http://old/proxy.pac"),
                MacOsAutoProxyState("USB", enabled = false, url = null)
            )
        )
        assertEquals(
            listOf(
                listOf("networksetup", "-setsocksfirewallproxystate", "Wi-Fi", "off"),
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://old/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on"),
                listOf("networksetup", "-setsocksfirewallproxystate", "USB", "off"),
                listOf("networksetup", "-setautoproxystate", "USB", "off")
            ),
            restore
        )
    }

    @Test
    fun windowsProxyCommandsBackupShapeIsRestorable() {
        val enable = WindowsProxyController.enableCommands("http://127.0.0.1:10809/proxy.pac")
        assertEquals("reg", enable.first().first())
        assertContains(enable.flatten(), "AutoConfigURL")
        assertContains(enable.flatten(), "http://127.0.0.1:10809/proxy.pac")

        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null
            )
        )

        assertContains(restore.flatten(), "ProxyEnable")
        assertContains(restore.flatten(), "ProxyServer")
        assertContains(restore.flatten(), "ProxyOverride")
        assertContains(restore.flatten(), "AutoConfigURL")
        assertContains(restore.flatten(), "delete")
    }

    @Test
    fun windowsProxyRefreshCommandUsesFullyQualifiedWinInetSignature() {
        val refresh = WindowsProxyController.refreshCommand()
        val script = refresh.last()

        assertEquals("powershell.exe", refresh.first())
        assertContains(script, "System.Runtime.InteropServices.DllImport")
        assertContains(script, "System.IntPtr")
        assertContains(script, "InternetSetOption")
    }

    @Test
    fun linuxTunConfigUsesLocalSocksAndIpv4MapDns() {
        val config = LinuxTunController.configContent()

        assertContains(config, "name: olcbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
    }

    @Test
    fun windowsTunAdministratorRestartUsesRunAsAndPreservesArguments() {
        val script = WindowsTunController.restartAsAdministratorScript(
            command = "C:/Olc's/Olcbox.exe",
            arguments = listOf("--flag", "C:/Path With Space/data"),
            workingDirectory = "C:/Olcbox Data"
        )

        assertContains(script, "FilePath = 'C:/Olc''s/Olcbox.exe'")
        assertContains(script, "Verb = 'RunAs'")
        assertContains(script, "ArgumentList = '--flag \"C:/Path With Space/data\"'")
        assertContains(script, "WorkingDirectory = 'C:/Olcbox Data'")
        assertContains(script, "Start-Process @startArgs")
    }

    @Test
    fun linuxTunScriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev olcbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns olcbox0 1.1.1.1")
        assertContains(up, "for setting in /proc/sys/net/ipv4/conf/*/rp_filter")
        assertContains(up, "printf '0\\n' > \"${'$'}setting\"")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "resolvectl revert olcbox0")
        assertContains(down, "done < \"${'$'}rp_filter_state\"")
    }

    @Test
    fun linuxTunScriptsBlackholeIpv6SoItCannotGoRoundTheTunnel() {
        // Found on macOS and identical here: claiming IPv4 alone leaves the IPv6
        // default route on the physical interface, and a browser — which prefers
        // IPv6 — reaches every dual-stack site at the machine's real address while
        // the tunnel looks perfectly connected.
        //
        // A blackhole rather than a relay: whether the far end has working IPv6 is
        // a property of the operator's node, and an unreachable answer arrives at
        // once where a forwarded one would hang. Root still goes direct, exactly
        // as it does for IPv4, or the core could not reach a v6 server at all.
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip -6 rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip -6 route add blackhole default table 51820")
        assertContains(up, "ip -6 rule add lookup 51820 pref 20")
        assertContains(down, "ip -6 rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip -6 rule del lookup 51820 pref 20")
        assertContains(down, "ip -6 route flush table 51820")
    }

    @Test
    fun linuxDnsResolverUsesActiveUpstreamInsteadOfSystemdStub() {
        val dns = DesktopDnsResolver.selectLinuxDnsServer(
            resolvectlOutput = "Link 3 (wlan0): 192.168.43.1 2a00:1234::53",
            nmcliOutput = "192.168.43.1",
            resolvConf = "nameserver 127.0.0.53"
        )

        assertEquals("192.168.43.1:53", dns)
    }

    @Test
    fun linuxDnsResolverFallsBackToLocalSystemResolver() {
        val dns = DesktopDnsResolver.selectLinuxDnsServer(
            resolvectlOutput = "",
            nmcliOutput = "",
            resolvConf = "nameserver 127.0.0.53"
        )

        assertEquals("127.0.0.53:53", dns)
    }

    @Test
    fun linuxDnsResolverFindsPhysicalDefaultRoute() {
        val interfaceName = DesktopDnsResolver.defaultRouteInterface(
            """
            default dev olcbox0 metric 5
            default via 192.168.43.1 dev wlan0 proto dhcp metric 600
            """.trimIndent()
        )

        assertEquals("wlan0", interfaceName)
    }
}
