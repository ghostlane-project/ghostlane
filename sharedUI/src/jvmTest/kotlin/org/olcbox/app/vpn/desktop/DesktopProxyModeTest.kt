package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.vpn.OlcRtcConnectionChecker
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // The engine started with a SOCKS login turns away a client that offers
    // none, and hev's config offered none: a room in the Linux tunnel said
    // Connected and carried nothing.
    @Test
    fun linuxTunConfigCarriesTheLoginTheEngineDemands() {
        val config = LinuxTunController.configContent(socksPort = 10808, username = "ghost", password = "it's")

        assertContains(config, "  port: 10808\n  username: 'ghost'\n  password: 'it''s'\n")
    }

    // The app is not root and the tunnel's process is: the app cannot signal
    // it. What the app runs as root ends it, by the tun it holds.
    @Test
    fun theLinuxCleanupEndsTheTunnelsProcessBeforeItRemovesAnything() {
        val plain = LinuxTunController.downScriptContent("/tmp/state")
        val script = LinuxTunController.withTunnelEnded(plain)
        val lines = script.lines()

        assertEquals("#!/bin/sh", lines.first())
        // Only when the app runs it: hev hands its scripts the tun's name.
        assertEquals("if [ \"\$#\" -eq 0 ]; then", lines[1])
        assertContains(script, "grep -rls '^iff:[[:space:]]*olcbox0\$' /proc/[0-9]*/fdinfo")
        assertContains(script, "sig=INT")
        assertContains(script, "sig=KILL")
        // Everything the script did before is still there, after it and unchanged.
        val ended = LinuxTunController.tunnelEndLines().lines()
        assertEquals(plain.lines().drop(1), lines.drop(1 + ended.size))
        assertTrue(script.indexOf("kill -") < script.indexOf("ip rule del"))
    }

    @Test
    fun endingATunnelLeftFromBeforeRemovesNothing() {
        val script = LinuxTunController.endTunnelScriptContent()

        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertContains(script, "kill -")
        assertFalse(script.contains("ip rule"))
        assertFalse(script.contains("ip route"))
        assertFalse(script.contains("ip link"))
    }

    @Test
    fun linuxTunConfigWithNoLoginIsWhatItWas() {
        assertEquals(
            LinuxTunController.configContent(socksPort = 10810),
            LinuxTunController.configContent(socksPort = 10810, username = "", password = "ignored")
        )
        assertFalse(LinuxTunController.configContent(socksPort = 10810).contains("username"))
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

    @Test
    fun olcRtcCommandSelectsVkcallsEngineNameWithTheWholeJoinLink() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "VK",
                id = "https://vk.ru/call/join/AbC-12_xyz",
                key = "f".repeat(64),
                bypassProvider = "vk",
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dnsServer = "192.168.43.1:53"
        ).yaml()

        assertContains(command, "provider: 'vkcalls'")
        // Asked for DataChannel, run over the one lane VK Calls has.
        assertContains(command, "transport: '${LocationConfig.TRANSPORT_VP8CHANNEL}'")
        assertContains(command, "id: 'https://vk.ru/call/join/AbC-12_xyz'")
    }

    // The engine reads `dtls.profile`; the yaml of a default run carries no
    // dtls block at all, so it is the one an engine without the setting read.
    @Test
    fun olcRtcCommandWritesTheDtlsProfileOnlyWhenOneIsChosen() {
        val location = LocationConfig(
            name = "T",
            id = "12345678901234",
            key = "e".repeat(64),
            bypassProvider = LocationConfig.PROVIDER_TELEMOST,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL
        )
        val plain = OlcRtcCommand(binary = Path.of("/tmp/olcrtc"), location = location, dnsServer = "1.1.1.1:53").yaml()
        assertFalse("dtls:" in plain, plain)

        val chrome = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = location,
            dnsServer = "1.1.1.1:53",
            dtlsProfile = OlcrtcDtls.profile(chrome = true)
        ).yaml()
        assertContains(chrome, "dtls:\n  profile: 'chrome-linux-138-compat-v1'\n")
    }

    // The bundled library's Check and Ping take no profile: a probe that needs
    // one runs the binary, which reads it from its yaml.
    @Test
    fun aProbeWithAHandshakeProfileRunsTheBinary() {
        for (os in DesktopOs.entries) {
            assertNull(OlcRtcConnectionChecker.nativeLibFor(OlcrtcDtls.CHROME, os), "$os")
        }
        assertNull(OlcRtcConnectionChecker.nativeLibFor(OlcrtcDtls.OFF, DesktopOs.Linux))
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
        // UDP ASSOCIATE, what every server on the local SOCKS port speaks; hev's
        // UDP-in-TCP ('tcp') is refused by all of them.
        assertContains(config, "udp: 'udp'")
        assertFalse(config.contains("udp: 'tcp'"))
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

    // The kill switch is opt-in. Off, hev is handed the two scripts it has
    // always been handed, to the byte: written out here, not built from the
    // controller's constants, so that nothing done for the switch can reach a
    // machine that never turned it on without this test saying so.
    @Test
    fun linuxTunScriptsAreUnchangedWithoutTheKillSwitch() {
        assertEquals(
            """
            #!/bin/sh
            set -eu
            rp_filter_state='/tmp/olcbox-rp-filter.state'
            ip rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
            ip rule del lookup 51820 pref 20 2>/dev/null || true
            ip route flush table 51820 2>/dev/null || true
            : > "${'$'}rp_filter_state"
            for setting in /proc/sys/net/ipv4/conf/*/rp_filter; do
              if [ -r "${'$'}setting" ]; then
                value=${'$'}(cat "${'$'}setting")
                printf '%s=%s\n' "${'$'}setting" "${'$'}value" >> "${'$'}rp_filter_state"
                printf '0\n' > "${'$'}setting" 2>/dev/null || true
              fi
            done
            ip link set olcbox0 up
            ip rule add uidrange 0-0 lookup main pref 10
            ip route add default dev olcbox0 table 51820
            ip rule add lookup 51820 pref 20
            ip -6 rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
            ip -6 rule del lookup 51820 pref 20 2>/dev/null || true
            ip -6 route flush table 51820 2>/dev/null || true
            ip -6 rule add uidrange 0-0 lookup main pref 10 2>/dev/null || true
            ip -6 route add blackhole default table 51820 2>/dev/null || true
            ip -6 rule add lookup 51820 pref 20 2>/dev/null || true
            if command -v resolvectl >/dev/null 2>&1; then
              resolvectl dns olcbox0 1.1.1.1 >/dev/null 2>&1 || true
              resolvectl domain olcbox0 '~.' >/dev/null 2>&1 || true
              resolvectl default-route olcbox0 yes >/dev/null 2>&1 || true
            fi
            """.trimIndent(),
            LinuxTunController.upScriptContent()
        )
        assertEquals(
            """
            #!/bin/sh
            rp_filter_state='/tmp/olcbox-rp-filter.state'
            ip rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
            ip rule del lookup 51820 pref 20 2>/dev/null || true
            ip route flush table 51820 2>/dev/null || true
            ip -6 rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
            ip -6 rule del lookup 51820 pref 20 2>/dev/null || true
            ip -6 route flush table 51820 2>/dev/null || true
            if command -v resolvectl >/dev/null 2>&1; then
              resolvectl revert olcbox0 >/dev/null 2>&1 || true
            fi
            if [ -r "${'$'}rp_filter_state" ]; then
              while IFS='=' read -r setting value; do
                case "${'$'}setting" in
                  /proc/sys/net/ipv4/conf/*/rp_filter)
                    [ -w "${'$'}setting" ] && printf '%s\n' "${'$'}value" > "${'$'}setting" 2>/dev/null || true
                    ;;
                esac
              done < "${'$'}rp_filter_state"
              rm -f "${'$'}rp_filter_state"
            fi
            """.trimIndent(),
            LinuxTunController.downScriptContent()
        )
        // Off is what leaving the argument out means.
        assertEquals(LinuxTunController.upScriptContent(), LinuxTunController.upScriptContent(killSwitch = false))
        assertEquals(LinuxTunController.downScriptContent(), LinuxTunController.downScriptContent(stopAskedPath = null))
    }

    // The block: a dummy device and a second default route in the tun's table,
    // with the last metric. While the tun is up its own route wins; once hev is
    // gone a lookup lands on the dummy and is dropped, and a socket bound to the
    // physical interface (the cores) passes over both.
    @Test
    fun linuxKillSwitchUpScriptAddsTheDeviceAndItsRouteAndComesUpWithoutThem() {
        val up = LinuxTunController.upScriptContent(killSwitch = true)
        val lines = up.lines()

        assertEquals(listOf("#!/bin/sh", "set -eu"), lines.take(2))
        // `set -eu` ends the script at the first command that fails, so each
        // of the three forgives its own failure: a kernel with no dummy module
        // gets no block, and still gets its tunnel.
        val device = lines.indexOf("ip link add olcboxks0 type dummy 2>/dev/null || true")
        val deviceUp = lines.indexOf("ip link set olcboxks0 up 2>/dev/null || true")
        val block = lines.indexOf(
            "ip route replace default dev olcboxks0 metric 4294967295 table 51820 2>/dev/null || true"
        )
        assertTrue(device >= 0, up)
        assertTrue(deviceUp > device, up)
        assertTrue(block > deviceUp, up)
        // The block is in the table before the tun's route, and before the rule
        // that sends anyone there.
        val tunRoute = lines.indexOf("ip route replace default dev olcbox0 table 51820")
        val rule = lines.indexOf("ip rule add lookup 51820 pref 20 2>/dev/null || true")
        assertTrue(tunRoute > block, up)
        assertTrue(rule > tunRoute, up)
        // Nothing is taken away. Run for a reconnect over a block that stands,
        // a rule deleted or the table flushed is that block opened until the
        // lines after put it back.
        assertTrue(lines.none { " del " in it || "flush" in it }, up)
        // Never an unreachable or blackhole route for IPv4: with one, a socket
        // bound to the physical interface is sent out without its gateway.
        assertTrue(lines.none { it.startsWith("ip route") && ("unreachable" in it || "blackhole" in it) }, up)
        assertContains(lines, "ip -6 route replace blackhole default table 51820 2>/dev/null || true")
        assertContains(lines, "ip -6 rule add lookup 51820 pref 20 2>/dev/null || true")
        // rp_filter as the machine had it is saved once: over a block that
        // stands, what /proc holds is the zeroes the session before wrote.
        assertContains(lines, "[ -e \"${'$'}rp_filter_state\" ] || : > \"${'$'}rp_filter_state\"")
        assertContains(up, "if ! grep -qF -- \"${'$'}setting=\" \"${'$'}rp_filter_state\"; then")
        assertTrue(lines.none { it == ": > \"${'$'}rp_filter_state\"" }, up)
        // The rest is the tunnel's own, as without the switch.
        assertContains(lines, "ip link set olcbox0 up")
        assertContains(lines, "ip rule add uidrange 0-0 lookup main pref 10 2>/dev/null || true")
        assertContains(up, "resolvectl dns olcbox0 1.1.1.1")
    }

    // hev runs its pre-down whenever it stops in an orderly way, also when
    // nobody asked it to. With the switch on that must leave the block alone.
    @Test
    fun linuxKillSwitchPreDownKeepsTheBlockUnlessTheStopWasAskedFor() {
        val down = LinuxTunController.downScriptContent(stopAskedPath = "/home/u/.olcbox/linux-tun-stop.asked")
        val lines = down.lines()

        // Nothing runs before the question, and without the file nothing runs after it.
        assertEquals(
            listOf(
                "#!/bin/sh",
                "rp_filter_state='/tmp/olcbox-rp-filter.state'",
                "stop_asked='/home/u/.olcbox/linux-tun-stop.asked'",
                "[ -e \"${'$'}stop_asked\" ] || exit 0"
            ),
            lines.take(4)
        )
        // Asked for, it is the app's own cleanup, line for line.
        assertEquals(LinuxTunController.cleanupScriptContent().lines().drop(2), lines.drop(4))
        // The script is root and the path is the user's: it asks whether the
        // file exists and does nothing else with it.
        assertEquals(2, lines.count { "stop_asked" in it })
        assertContains(
            LinuxTunController.downScriptContent(stopAskedPath = "/home/o'brien/.olcbox/linux-tun-stop.asked"),
            "stop_asked='/home/o'\"'\"'brien/.olcbox/linux-tun-stop.asked'"
        )
    }

    // When hev is killed outright its pre-down never runs, and with the switch
    // on an orderly stop nobody asked for keeps the block. What is left is the
    // app's to remove, when the user disconnects.
    @Test
    fun linuxCleanupTakesEverythingOutTheKillSwitchDeviceIncluded() {
        val cleanup = LinuxTunController.cleanupScriptContent()
        val lines = cleanup.lines()

        for (line in listOf(
            "ip rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true",
            "ip rule del lookup 51820 pref 20 2>/dev/null || true",
            "ip route flush table 51820 2>/dev/null || true",
            "ip -6 rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true",
            "ip -6 rule del lookup 51820 pref 20 2>/dev/null || true",
            "ip -6 route flush table 51820 2>/dev/null || true",
            "ip link del olcboxks0 2>/dev/null || true"
        )) {
            assertContains(lines, line)
        }
        // Whatever the switch says now, and with no condition: a block
        // outlives the setting that asked for it.
        assertTrue(lines.none { "exit" in it || "stop_asked" in it }, cleanup)
        // It is the plain down script and that one line more.
        assertEquals(
            LinuxTunController.downScriptContent().lines(),
            lines - "ip link del olcboxks0 2>/dev/null || true"
        )
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

    // A core beside the Linux tun is given the machine's own resolvers: the
    // stub is the one that answers with the tun's fake addresses.
    @Test
    fun linuxDirectResolversAreEveryUpstreamButTheStub() {
        assertEquals(
            listOf("192.168.43.1", "2a00:1234::53"),
            DesktopDnsResolver.linuxDnsServers(
                resolvectlOutput = "Link 3 (wlan0): 192.168.43.1 2a00:1234::53",
                nmcliOutput = "192.168.43.1",
                resolvConf = "nameserver 127.0.0.53"
            )
        )
        assertEquals(
            emptyList(),
            DesktopDnsResolver.linuxDnsServers(resolvectlOutput = "", nmcliOutput = "", resolvConf = "nameserver 127.0.0.53")
        )
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
