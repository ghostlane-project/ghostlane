// macOS: the native resources of the desktop package, the tasks that make
// them and the tasks that pack them. Applied by ../build.gradle.kts, which hands
// over what the three systems share as extra properties. The list this file
// declares is recorded in native-macos.resources.txt and compared with it by
// :desktopApp:checkDesktopNativeResourceLists.

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec

@Suppress("UNCHECKED_CAST")
val generatedNativeResources = extra["generatedNativeResources"] as Provider<Directory>
@Suppress("UNCHECKED_CAST")
val desktopNativeAssetTasks = extra["desktopNativeAssetTasks"] as MutableList<Any>
@Suppress("UNCHECKED_CAST")
val hostDesktopNativeAssetTasks = extra["hostDesktopNativeAssetTasks"] as MutableList<Any>
@Suppress("UNCHECKED_CAST")
val desktopNativeArchs = extra["desktopNativeArchs"] as MutableMap<String, List<String>>
@Suppress("UNCHECKED_CAST")
val generatedNativeResourcePathsByOs =
    extra["generatedNativeResourcePathsByOs"] as MutableMap<String, (String) -> List<String>>
@Suppress("UNCHECKED_CAST")
val bundledCorePathsByOs = extra["bundledCorePathsByOs"] as MutableMap<String, List<String>>
val hostDesktopOs = extra["hostDesktopOs"] as String
val hostDesktopArch = extra["hostDesktopArch"] as String
val desktopPackageName = extra["desktopPackageName"] as String
val desktopPackageVersion = extra["desktopPackageVersion"] as String

// The Go cores are gone from the macOS package: a line and a room's front run
// in ghostlane-engine, and the helper runs hev for the tun. Nothing here builds
// olcrtc-darwin-* or libolcrtc-darwin-* any more, so nor can a packaging rule
// put one in the DMG. K1 reads the finished asset; this is the half that keeps
// the Go file from being made at all.

if (hostDesktopOs == "macos") {
    // The JNA bridge the app uses to register the root tunnel daemon.
    //
    // Small on purpose: SMAppService is the only part of this that has to be
    // Swift, and everything it is asked here returns an integer the Kotlin side
    // names. Polled rather than called back into — a JNA callback arrives on
    // whatever thread the JVM lends, and marshalling one into an Apple main-queue
    // callback is a class of crash worth more than an integer read once a second.
    val buildMacosTunnelDaemonBridge = tasks.register<Exec>("buildMacosTunnelDaemonBridge") {
        val outputFile = generatedNativeResources.map { it.file("native/libolcboxtunneld.dylib") }
        val source = layout.projectDirectory.file("nativebridge/OlcboxTunnelDaemon.swift")

        inputs.file(source)
        outputs.file(outputFile)

        val output = outputFile.get().asFile
        commandLine(
            "bash", "-c",
            """
            set -euo pipefail
            mkdir -p "${'$'}(dirname "${'$'}2")"
            swiftc -O -target "${'$'}(uname -m)-apple-macos13.0" -emit-library \
                -framework ServiceManagement -framework AppKit -framework Foundation \
                -o "${'$'}2" "${'$'}1"
            """.trimIndent(),
            "bash",
            source.asFile.absolutePath,
            output.absolutePath
        )

        // Signed where it is made: this one is produced by the build, travels inside the app's jar, and Apple
        // scans in there. An unsigned Mach-O in a jar has failed notarisation
        // before.
        val signTarget = outputFile.map { it.asFile.absolutePath }
        val entitlementsPath = layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
        doLast {
            val identity = System.getenv("MACOS_SIGN_IDENTITY")
            if (!identity.isNullOrBlank()) {
                val exit = ProcessBuilder(
                    "codesign", "--force", "--timestamp", "--options", "runtime",
                    "--entitlements", entitlementsPath,
                    "--sign", identity, signTarget.get()
                ).inheritIO().start().waitFor()
                check(exit == 0) { "codesign failed for ${signTarget.get()}" }
            }
        }
    }

    desktopNativeAssetTasks.add(buildMacosTunnelDaemonBridge)
    hostDesktopNativeAssetTasks.add(buildMacosTunnelDaemonBridge)
}

// The architectures a package is built for.
desktopNativeArchs["macos"] = listOf("amd64", "arm64")

// Made by the tasks above under build/generated/desktopNativeResources.
generatedNativeResourcePathsByOs["macos"] = { arch: String ->
    buildList {
        // Absent, the settings row for the system-wide tunnel quietly does
        // not appear and nothing anywhere says why — the bridge is loaded by
        // resource and a missing resource is indistinguishable from a
        // platform that has no such component.
        add("native/libolcboxtunneld.dylib")
    }
}

// Supplied under src/main/resources by the release job, which builds the
// program at scripts/ghostlane-core-pin.sh for the runner's own architecture
// and signs it. Its `version` event carries the pin, and no Go core is
// delivered beside it. hev, which the helper runs, is not a resource of the
// jar: the embed step below puts it into the app's own Resources.
bundledCorePathsByOs["macos"] = listOf("native/ghostlane-engine")

// ---------------------------------------------------------------------------
// macOS: the root tunnel daemon.
//
// Unlike the NetworkExtension attempt this replaces, nothing here is restricted:
// no provisioning profile, no entitlement that needs Apple's blessing, no App ID
// registered in a portal. A Developer ID signature and notarisation — both of
// which every macOS build already has — are the whole requirement. That is why
// this path can ship on a macOS 26 where sysextd refuses to activate any new
// system extension at all; see docs/macos-tunnel-daemon.md.
// ---------------------------------------------------------------------------
if (hostDesktopOs == "macos") {
    val daemonSourceDir = layout.projectDirectory.dir("tunneldaemon")
    val daemonAppImageDir =
        layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName.app")
    val daemonStageDir = layout.buildDirectory.dir("macos/tunneldaemon")

    // hev for the helper: the universal program scripts/build-hev-macos.sh
    // writes (the Core build keeps one per tree of hev). A loose file in the
    // app's own Resources, because the helper is root's and copies it into its
    // own directory before it runs it; inside the jar it could not be reached.
    val hevProgram = providers.environmentVariable("GHOSTLANE_HEV_MACOS")
        .orElse(rootProject.layout.projectDirectory.file("out/hev-macos/hev-socks5-tunnel").asFile.absolutePath)

    val embedMacosTunnelDaemon = tasks.register<Exec>("embedMacosTunnelDaemon") {
        group = "distribution"
        description = "Builds, signs and embeds the root tunnel daemon and hev into the .app."

        dependsOn("createReleaseDistributable")
        inputs.dir(daemonSourceDir)
        outputs.dir(daemonStageDir)

        commandLine(
            "bash",
            "-c",
            """
            set -euo pipefail

            app_dir="${'$'}1"
            src="${'$'}2"
            stage="${'$'}3"
            hev_src="${'$'}4"
            entitlements="${'$'}5"

            # With no identity the package is signed ad hoc, which is how a dry
            # release run and a developer's build prove that the bundle is whole
            # (codesign --verify --deep --strict). An ad hoc daemon cannot pass
            # its own peer check, so such a package is never one to publish: the
            # release job signs with the team's identity or does not publish.
            identity="${'$'}{MACOS_SIGN_IDENTITY:-}"
            stamp=(--timestamp)
            if [ -z "${'$'}identity" ]; then
                identity="-"
                stamp=(--timestamp=none)
                echo "No signing identity: the package is signed ad hoc."
            fi

            if [ ! -d "${'$'}app_dir" ]; then
                echo "no app image at ${'$'}app_dir — this task ran against the wrong output" >&2
                exit 1
            fi
            if [ ! -f "${'$'}hev_src" ]; then
                echo "no hev at ${'$'}hev_src — scripts/build-hev-macos.sh writes it (GHOSTLANE_HEV_MACOS names another)" >&2
                exit 1
            fi

            rm -rf "${'$'}stage"
            mkdir -p "${'$'}stage"

            # SystemConfiguration is for the system's resolver (SystemNames.swift).
            # Every file of the directory, as the PR check builds it (pr-checks.yml,
            # mac-helper): a file added there is in the daemon without a list to edit.
            swiftc -O -target "${'$'}(uname -m)-apple-macos13.0" \
                -framework Foundation -framework Security -framework SystemConfiguration \
                -o "${'$'}stage/ProofKitTunnelDaemon" \
                "${'$'}src"/*.swift

            # hev has to be a loose file in the bundle. It is otherwise nowhere a
            # root daemon could reach it, and could not verify a signature on it.
            mkdir -p "${'$'}app_dir/Contents/Resources"
            cp "${'$'}hev_src" "${'$'}app_dir/Contents/Resources/hev-socks5-tunnel"
            chmod 0755 "${'$'}app_dir/Contents/Resources/hev-socks5-tunnel"

            mkdir -p "${'$'}app_dir/Contents/Library/LaunchDaemons"
            cp "${'$'}src/org.olcbox.app.desktopApp.tunneld.plist" \
               "${'$'}app_dir/Contents/Library/LaunchDaemons/"
            cp "${'$'}stage/ProofKitTunnelDaemon" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"

            # Inside out. The outer signature seals what is nested, so anything
            # signed after the app is signed invalidates the app.
            codesign --force "${'$'}{stamp[@]}" --options runtime \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/Resources/hev-socks5-tunnel"
            codesign --force "${'$'}{stamp[@]}" --options runtime \
                --identifier "org.olcbox.app.desktopApp.tunneld" \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"
            codesign --force "${'$'}{stamp[@]}" --options runtime \
                --entitlements "${'$'}entitlements" \
                --sign "${'$'}identity" "${'$'}app_dir"
            codesign --verify --deep --strict --verbose=2 "${'$'}app_dir"

            if [ ! -x "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon" ]; then
                echo "the tunnel daemon is not in the app image" >&2
                exit 1
            fi

            # The daemon refuses any peer that does not satisfy PeerAuthority's
            # requirement string. A build whose app signature does not satisfy it
            # ships a daemon nothing can talk to, and the only symptom is the word
            # "unauthorized" with nothing to say which side is wrong. An ad hoc
            # signature names no identifier in its requirement, so only the
            # team's own is read.
            if [ "${'$'}identity" != "-" ]; then
                if ! codesign -dr - "${'$'}app_dir" 2>&1 | grep -q "org.olcbox.app.desktopApp"; then
                    echo "the app's designated requirement does not name the identifier the" >&2
                    echo "daemon pins — every command would be refused as unauthorized." >&2
                    exit 1
                fi
            fi
            echo "embedded: tunnel daemon + hev"
            """.trimIndent(),
            "bash",
            daemonAppImageDir.get().asFile.absolutePath,
            daemonSourceDir.asFile.absolutePath,
            daemonStageDir.get().asFile.absolutePath,
            hevProgram.get(),
            layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
        dependsOn(embedMacosTunnelDaemon)
    }
}
