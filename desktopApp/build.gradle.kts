import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jna)
    implementation(libs.zxing.core)
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        URI(sourceUrl.get())
            .toURL()
            .openStream()
            .use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
    }
}

abstract class ExtractZipEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = zipFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence()
                .firstOrNull { it.name.endsWith(entrySuffix.get()) }
                ?: error("${entrySuffix.get()} entry was not found in ${zip.absolutePath}")

            archive.getInputStream(entry).use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}

abstract class VerifyNativeResourcesTask : DefaultTask() {
    // Do not declare this as InputDirectory: Gradle rejects a missing directory
    // before the task can report which bundled resources are absent.
    @get:Internal
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    val resourcesPath: String
        get() = resourcesDir.get().asFile.absolutePath

    @get:Input
    abstract val requiredPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = resourcesDir.get().asFile
        val missing = requiredPaths.get()
            .map { root.resolve(it) }
            .filterNot { it.isFile }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }
    }
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc")
    .absolutePath
val olcrtcRepo = providers.environmentVariable("OLCRTC_REPO")
    .orElse(defaultOlcRtcRepo)
val olcrtcRepoDir = olcrtcRepo.map { rootProject.file(it) }
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
// Installed application name (Ghostlane.app / Ghostlane.exe / Ghostlane.AppImage).
// NOT an identity: macOS bundleID and the Windows upgradeUuid below are unchanged,
// so Windows still upgrades in place. DesktopPaths.appDataDir() keeps its own
// hardcoded "Olcbox" directory so existing installs keep their subscriptions.
val desktopPackageName = "Ghostlane"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0").get()
val wintunVersion = "0.14.1"
val currentBuildTargetFormats = when {
    currentBuildOs.isMacOsX -> arrayOf(TargetFormat.Dmg)
    currentBuildOs.isWindows -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
    currentBuildOs.isLinux -> arrayOf(TargetFormat.AppImage)
    else -> emptyArray()
}

fun desktopArchName(arch: String): String = when (arch.lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported desktop architecture: $arch")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

fun registerOlcRtcBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "0")
    commandLine(
        "go",
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }

    // Notarisation rejected the build over exactly this kind of file: the olcrtc
    // engine is produced here, long after CI has signed sing-box and xray, and it
    // travels inside the app's jar. Apple scans in there and wants a Developer ID
    // signature, a secure timestamp and the hardened runtime on every Mach-O.
    //
    // Signed at execution time with plain ProcessBuilder and System.getenv, and
    // with no reference to `project` — a task action that touches the project
    // breaks the configuration cache this build relies on.
    val signTarget = outputFile.map { it.asFile.absolutePath }
    val entitlementsPath = layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
    val signOnDarwin = goos == "darwin"
    doLast {
        val identity = System.getenv("MACOS_SIGN_IDENTITY")
        if (signOnDarwin && !identity.isNullOrBlank()) {
            val exit = ProcessBuilder(
                "codesign", "--force", "--timestamp", "--options", "runtime",
                "--entitlements", entitlementsPath,
                "--sign", identity, signTarget.get()
            ).inheritIO().start().waitFor()
            check(exit == 0) { "codesign failed for ${signTarget.get()}" }
        }
    }
}

fun registerOlcRtcLibraryBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc-cgo"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }

    // Notarisation rejected the build over exactly this kind of file: the olcrtc
    // engine is produced here, long after CI has signed sing-box and xray, and it
    // travels inside the app's jar. Apple scans in there and wants a Developer ID
    // signature, a secure timestamp and the hardened runtime on every Mach-O.
    //
    // Signed at execution time with plain ProcessBuilder and System.getenv, and
    // with no reference to `project` — a task action that touches the project
    // breaks the configuration cache this build relies on.
    val signTarget = outputFile.map { it.asFile.absolutePath }
    val entitlementsPath = layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
    val signOnDarwin = goos == "darwin"
    doLast {
        val identity = System.getenv("MACOS_SIGN_IDENTITY")
        if (signOnDarwin && !identity.isNullOrBlank()) {
            val exit = ProcessBuilder(
                "codesign", "--force", "--timestamp", "--options", "runtime",
                "--entitlements", entitlementsPath,
                "--sign", identity, signTarget.get()
            ).inheritIO().start().waitFor()
            check(exit == 0) { "codesign failed for ${signTarget.get()}" }
        }
    }
}

val buildOlcRtcDarwinArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "olcrtc-darwin-arm64"
)

val buildOlcRtcDarwinAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "olcrtc-darwin-amd64"
)

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.exe"
)

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "olcrtc-linux-arm64"
)

val buildOlcRtcLibDarwinArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "libolcrtc-darwin-arm64.dylib"
)

val buildOlcRtcLibDarwinAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "libolcrtc-darwin-amd64.dylib"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "libolcrtc-linux-arm64.so"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.dll"
)

// The engine embeds its display-name dictionaries since upstream 189d16c and
// the client yaml no longer names a `data:` directory, so nothing is copied
// from the engine repo's data/ any more (it is gone there too).

val desktopNativeAssetTasks = mutableListOf<Any>(
    buildOlcRtcDarwinArm64,
    buildOlcRtcDarwinAmd64,
    buildOlcRtcWindowsAmd64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    buildOlcRtcLibDarwinArm64,
    buildOlcRtcLibDarwinAmd64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>()

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinArm64)
        }
    }
    currentBuildOs.isWindows -> {
        hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
        hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)
    }
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
    }
}

if (currentBuildOs.isLinux) {
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

if (currentBuildOs.isMacOsX) {
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

        // Same reason the olcRTC libraries are signed where they are made: this
        // one is produced by the build, travels inside the app's jar, and Apple
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

if (currentBuildOs.isWindows) {
    val wintunWindowsOutput = generatedNativeResources.map {
        it.file("native/wintun.dll")
    }

    val downloadWintunWindowsAmd64 = tasks.register<DownloadFileTask>("downloadWintunWindowsAmd64") {
        sourceUrl.set("https://www.wintun.net/builds/wintun-$wintunVersion.zip")
        outputFile.set(layout.buildDirectory.file("tmp/wintun/wintun-$wintunVersion.zip"))
    }

    val extractWintunWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractWintunWindowsAmd64") {
        zipFile.set(downloadWintunWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("/bin/amd64/wintun.dll")
        outputFile.set(wintunWindowsOutput)
    }

    desktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractWintunWindowsAmd64)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    when {
        currentBuildOs.isMacOsX -> {
            add("native/olcrtc-darwin-$hostDesktopArch")
            add("native/libolcrtc-darwin-$hostDesktopArch.dylib")
            // Absent, the settings row for the system-wide tunnel quietly does
            // not appear and nothing anywhere says why — the bridge is loaded by
            // resource and a missing resource is indistinguishable from a
            // platform that has no such component.
            add("native/libolcboxtunneld.dylib")
        }
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-amd64.exe")
            add("native/olcrtc-windows-amd64.dll")
            add("native/wintun.dll")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
    }
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    resourcesDir.set(generatedNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

// sing-box and Xray are supplied as pinned release inputs rather than built by
// Gradle (see the release workflow). Keep their verification separate from the
// generated olcRTC/TUN resources above: otherwise a local jpackage build can
// succeed and only report the missing carrier after the user presses Connect.
// Requiring the exact resource names here makes every runnable desktop package
// fail during processResources when its two connection cores were not staged.
val verifyBundledDesktopCores = tasks.register<VerifyNativeResourcesTask>("verifyBundledDesktopCores") {
    val executableSuffix = if (currentBuildOs.isWindows) ".exe" else ""
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    requiredPaths.set(
        listOf(
            "native/sing-box$executableSuffix",
            "native/xray$executableSuffix"
        )
    )
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
    dependsOn(verifyBundledDesktopCores)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
        resources.srcDir(layout.projectDirectory.dir("appIcons"))
    }
}

if (currentBuildOs.isWindows) {
    val jpackageAppRootDir = layout.buildDirectory.dir("compose/binaries/main-release/app")

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set("$desktopPackageName-$desktopPackageVersion-windows-amd64-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn(verifyDesktopNativeResources)
    dependsOn(verifyBundledDesktopCores)
}

listOf(
    "run",
    "createReleaseDistributable",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseExe",
    "packageReleaseMsi",
    "packageReleaseDmg",
    "packageReleaseAppImage",
    "packageReleasePortableZip"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyDesktopNativeResources)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // java.naming + jdk.naming.dns: the system's DNS servers on macOS and Windows
            // (DesktopDnsResolver.systemServers).
            modules("jdk.httpserver", "java.naming", "jdk.naming.dns")
            targetFormats(*currentBuildTargetFormats)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                menuGroup = "Olcbox"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f0aaf78-dbed-4745-9d95-9e63f10a30de"
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"
                // The one-tap import link, ghostlane:// or proofkit://add?url=…; the app
                // receives it through Desktop.setOpenURIHandler.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>org.olcbox.app.desktopApp.import</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>ghostlane</string>
                                    <string>proofkit</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }

                // Signed only when CI has the Developer ID identity in its
                // keychain; a developer without it still gets a build, exactly
                // like the Windows job behaves without its certificate.
                //
                // Unsigned is not merely "shows a warning" on Apple Silicon —
                // the kernel refuses to execute an unsigned binary at all, which
                // is why the unsigned DMG had to be talked through xattr.
                val signIdentity = providers.environmentVariable("MACOS_SIGN_IDENTITY").orNull
                if (!signIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(signIdentity)
                    }
                    // Hardened runtime is required for notarisation, and a JVM
                    // app cannot start under it without these.
                    entitlementsFile.set(project.file("macos-entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("macos-entitlements.plist"))
                }
            }
        }
    }
}

if (currentBuildOs.isLinux) {
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
if (currentBuildOs.isMacOsX) {
    val daemonSourceDir = layout.projectDirectory.dir("tunneldaemon")
    val daemonAppImageDir =
        layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName.app")
    val daemonStageDir = layout.buildDirectory.dir("macos/tunneldaemon")

    val embedMacosTunnelDaemon = tasks.register<Exec>("embedMacosTunnelDaemon") {
        group = "distribution"
        description = "Builds, signs and embeds the root tunnel daemon into the .app."

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
            core_src="${'$'}4"
            entitlements="${'$'}5"

            identity="${'$'}{MACOS_SIGN_IDENTITY:-}"
            if [ -z "${'$'}identity" ]; then
                echo "No signing identity — building without the tunnel daemon."
                echo "An unsigned daemon cannot pass its own peer check and SMAppService will"
                echo "not register it, so shipping one would only move the failure later."
                mkdir -p "${'$'}stage"
                exit 0
            fi

            if [ ! -d "${'$'}app_dir" ]; then
                echo "no app image at ${'$'}app_dir — this task ran against the wrong output" >&2
                exit 1
            fi

            rm -rf "${'$'}stage"
            mkdir -p "${'$'}stage"

            swiftc -O -target "${'$'}(uname -m)-apple-macos13.0" \
                -framework Foundation -framework Security \
                -o "${'$'}stage/ProofKitTunnelDaemon" \
                "${'$'}src/PeerAuthority.swift" "${'$'}src/TunnelChild.swift" "${'$'}src/main.swift"

            # The core the daemon execs has to be a loose file in the bundle. It is
            # otherwise only inside the jar, where a root daemon cannot reach it and
            # could not verify a signature on it if it could.
            mkdir -p "${'$'}app_dir/Contents/Resources"
            cp "${'$'}core_src" "${'$'}app_dir/Contents/Resources/sing-box"
            chmod 0755 "${'$'}app_dir/Contents/Resources/sing-box"

            mkdir -p "${'$'}app_dir/Contents/Library/LaunchDaemons"
            cp "${'$'}src/org.olcbox.app.desktopApp.tunneld.plist" \
               "${'$'}app_dir/Contents/Library/LaunchDaemons/"
            cp "${'$'}stage/ProofKitTunnelDaemon" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"

            # Inside out. The outer signature seals what is nested, so anything
            # signed after the app is signed invalidates the app.
            codesign --force --timestamp --options runtime \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/Resources/sing-box"
            codesign --force --timestamp --options runtime \
                --identifier "org.olcbox.app.desktopApp.tunneld" \
                --sign "${'$'}identity" "${'$'}app_dir/Contents/MacOS/ProofKitTunnelDaemon"
            codesign --force --timestamp --options runtime \
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
            # "unauthorized" with nothing to say which side is wrong.
            if ! codesign -dr - "${'$'}app_dir" 2>&1 | grep -q "org.olcbox.app.desktopApp"; then
                echo "the app's designated requirement does not name the identifier the" >&2
                echo "daemon pins — every command would be refused as unauthorized." >&2
                exit 1
            fi
            echo "embedded: tunnel daemon + core"
            """.trimIndent(),
            "bash",
            daemonAppImageDir.get().asFile.absolutePath,
            daemonSourceDir.asFile.absolutePath,
            daemonStageDir.get().asFile.absolutePath,
            layout.projectDirectory.file("src/main/resources/native/sing-box").asFile.absolutePath,
            layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
        dependsOn(embedMacosTunnelDaemon)
    }
}
