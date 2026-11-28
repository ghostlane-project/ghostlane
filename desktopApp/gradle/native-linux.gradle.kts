// Linux: the native resources of the desktop package, the tasks that make
// them and the tasks that pack them. Applied by ../build.gradle.kts, which hands
// over what the three systems share as extra properties. The list this file
// declares is recorded in native-linux.resources.txt and compared with it by
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

val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

// The Go cores are gone from the Linux package: a line and a room's front
// run in ghostlane-engine. Nothing here builds olcrtc-linux-* any more, so
// nor can a packaging rule put one in the AppImage. K1 reads the finished
// asset; this is the half that keeps the Go file from being made at all.
if (hostDesktopOs == "linux") {
    val buildHevSocks5TunnelLinux = tasks.register<Exec>("buildHevSocks5TunnelLinux") {
        val outputFile = generatedNativeResources.map {
            it.file("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
        val output = outputFile.get().asFile

        outputs.file(outputFile)
        workingDir = hevSocks5TunnelSourceDir.asFile
        commandLine(
            "sh",
            "-c",
            "mkdir -p ${shellQuote(output.parentFile.absolutePath)} && make clean exec && install -m 0755 bin/hev-socks5-tunnel ${shellQuote(output.absolutePath)}"
        )
    }
    desktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
    hostDesktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
}

// The architectures a package is built for.
desktopNativeArchs["linux"] = listOf("amd64", "arm64")

// Made by the tasks above under build/generated/desktopNativeResources.
generatedNativeResourcePathsByOs["linux"] = { arch: String ->
    listOf("native/hev-socks5-tunnel-linux-$arch")
}

// Supplied under src/main/resources by the release job, which builds the
// program at scripts/ghostlane-core-pin.sh. Its `version` event carries the
// pin, and no Go core is delivered beside it.
bundledCorePathsByOs["linux"] = listOf("native/ghostlane-engine")

if (hostDesktopOs == "linux") {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/$desktopPackageName-$desktopPackageVersion-$hostDesktopArch.AppImage"
    )

    val prepareReleaseLinuxAppDir = tasks.register<Exec>("prepareReleaseLinuxAppDir") {
        group = "distribution"
        description = "Prepares the AppDir layout used by appimagetool."

        dependsOn("packageReleaseAppImage")
        inputs.dir(jpackageAppDir)
        inputs.file(linuxIconFile)
        outputs.dir(appDir)

        commandLine(
            "sh",
            "-c",
            """
            set -eu

            source_dir="${'$'}1"
            target_dir="${'$'}2"
            icon_file="${'$'}3"

            rm -rf "${'$'}target_dir"
            mkdir -p "${'$'}target_dir"
            cp -R "${'$'}source_dir/." "${'$'}target_dir/"

            cat > "${'$'}target_dir/AppRun" <<'APPRUN'
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"

            # An earlier version staged Go cores in the app's own bin directory.
            # This one starts only ghostlane-engine. Take the old copies away
            # on the first start, before anything can run one by hand.
            stale_bin="${'$'}HOME/.olcbox/bin"
            rm -f "${'$'}stale_bin/sing-box" "${'$'}stale_bin/xray"
            rm -f "${'$'}stale_bin"/olcrtc-linux-* "${'$'}stale_bin"/libolcrtc-linux-*.so

            exec "${'$'}HERE/bin/$desktopPackageName" "${'$'}@"
            APPRUN
            chmod +x "${'$'}target_dir/AppRun"

            cat > "${'$'}target_dir/org.olcbox.app.desktopApp.desktop" <<'DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=$desktopPackageName
            Exec=$desktopPackageName %u
            Icon=olcbox
            Categories=Network;Utility;
            MimeType=x-scheme-handler/ghostlane;x-scheme-handler/proofkit;
            Terminal=false
            DESKTOP

            cp "${'$'}icon_file" "${'$'}target_dir/olcbox.png"
            """.trimIndent(),
            "prepareReleaseLinuxAppDir",
            jpackageAppDir.get().asFile.absolutePath,
            appDir.get().asFile.absolutePath,
            linuxIconFile.asFile.absolutePath
        )
    }

    val packageReleaseLinuxAppImage = tasks.register<Exec>("packageReleaseLinuxAppImage") {
        group = "distribution"
        description = "Packages the Linux desktop app as a real .AppImage file."

        dependsOn(prepareReleaseLinuxAppDir)
        inputs.dir(appDir)
        outputs.file(appImageFile)

        commandLine(
            appImageTool.get(),
            appDir.get().asFile.absolutePath,
            appImageFile.get().asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(packageReleaseLinuxAppImage)
    }
}
