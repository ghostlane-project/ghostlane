// Linux: the native resources of the desktop package, the tasks that make
// them and the tasks that pack them. Applied by ../build.gradle.kts, which hands
// over what the three systems share as extra properties. The list this file
// declares is recorded in native-linux.resources.txt and compared with it by
// :desktopApp:checkDesktopNativeResourceLists.

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider

@Suppress("UNCHECKED_CAST")
val registerOlcRtcBuildTask =
    extra["registerOlcRtcBuildTask"] as (String, String, String, String) -> TaskProvider<Exec>
@Suppress("UNCHECKED_CAST")
val registerOlcRtcLibraryBuildTask =
    extra["registerOlcRtcLibraryBuildTask"] as (String, String, String, String) -> TaskProvider<Exec>
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

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    "buildOlcRtcLinuxAmd64",
    "linux",
    "amd64",
    "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    "buildOlcRtcLinuxArm64",
    "linux",
    "arm64",
    "olcrtc-linux-arm64"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    "buildOlcRtcLibLinuxAmd64",
    "linux",
    "amd64",
    "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    "buildOlcRtcLibLinuxArm64",
    "linux",
    "arm64",
    "libolcrtc-linux-arm64.so"
)

desktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
desktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
desktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
desktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)

if (hostDesktopOs == "linux") {
    when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
    }

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
    buildList {
        add("native/olcrtc-linux-$arch")
        add("native/libolcrtc-linux-$arch.so")
        add("native/hev-socks5-tunnel-linux-$arch")
    }
}

// Supplied under src/main/resources by whoever runs the build.
bundledCorePathsByOs["linux"] = listOf(
    "native/sing-box",
    "native/xray"
)

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
