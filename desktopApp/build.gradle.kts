import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

// The native resources each OS's package takes, compared with the recorded
// lists in gradle/native-<os>.resources.txt. A line is "<arch> <kind> <path>":
// "generated" is made by a task of this build under
// build/generated/desktopNativeResources, "bundled" is supplied under
// src/main/resources by whoever runs the build (see verifyBundledDesktopCores).
// The lists of all three systems are computed from what this build declares,
// on any host, so a change to one system's resources shows on the Linux runner.
abstract class CheckNativeResourceListsTask : DefaultTask() {
    @get:Input
    abstract val staged: MapProperty<String, List<String>>

    @get:Input
    abstract val recordedFiles: MapProperty<String, String>

    // What the host's own tasks write under the generated directory, and what
    // the host's list says they should: the list is not a second copy of the
    // truth if the two are compared here.
    @get:Input
    abstract val hostDeclared: ListProperty<String>

    @get:Input
    abstract val hostProduced: ListProperty<String>

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        staged.get().toSortedMap().forEach { (os, lines) ->
            val now = lines.sorted()
            println("Native resources staged for $os:")
            now.forEach { println("  $it") }

            val recordedFile = File(recordedFiles.get().getValue(os))
            val recorded = if (recordedFile.isFile) {
                recordedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } else {
                problems.add("$os: no recorded list at ${recordedFile.path}")
                emptyList()
            }
            if (recorded != recorded.sorted()) {
                problems.add("$os: ${recordedFile.name} is not sorted")
            }
            (now - recorded.toSet()).forEach { problems.add("$os: staged but not recorded: $it") }
            (recorded - now.toSet()).forEach { problems.add("$os: recorded but not staged: $it") }
            if (recorded.size != recorded.toSet().size) {
                problems.add("$os: ${recordedFile.name} repeats a line")
            }
        }

        val declared = hostDeclared.get().sorted()
        val produced = hostProduced.get().sorted()
        if (declared != produced) {
            problems.add("this host's tasks write $produced, its list declares $declared")
        }

        require(problems.isEmpty()) {
            "The desktop native resource lists differ from the recorded ones:\n" +
                    problems.joinToString(separator = "\n") { "- $it" }
        }
    }
}

val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val currentBuildOs = OperatingSystem.current()
// Installed application name (Ghostlane.app / Ghostlane.exe / Ghostlane.AppImage).
// NOT an identity: macOS bundleID and the Windows upgradeUuid below are unchanged,
// so Windows still upgrades in place. DesktopPaths.appDataDir() keeps its own
// hardcoded "Olcbox" directory so existing installs keep their subscriptions.
val desktopPackageName = "Ghostlane"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0").get()
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

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

// The engine embeds its display-name dictionaries since upstream 189d16c and
// the client yaml no longer names a `data:` directory, so nothing is copied
// from the engine repo's data/ any more (it is gone there too).

val desktopNativeAssetTasks = mutableListOf<Any>()
val hostDesktopNativeAssetTasks = mutableListOf<Any>()

val hostDesktopOs = when {
    currentBuildOs.isMacOsX -> "macos"
    currentBuildOs.isWindows -> "windows"
    currentBuildOs.isLinux -> "linux"
    else -> ""
}

// One file per system holds its native resources: the tasks that make them,
// the list the package must find, and the packing that is the system's own.
// Each adds its tasks and its lists to what is handed over here.
val desktopNativeArchs = mutableMapOf<String, List<String>>()
val generatedNativeResourcePathsByOs = mutableMapOf<String, (String) -> List<String>>()
val bundledCorePathsByOs = mutableMapOf<String, List<String>>()

extra["generatedNativeResources"] = generatedNativeResources
extra["hostDesktopOs"] = hostDesktopOs
extra["hostDesktopArch"] = hostDesktopArch
extra["desktopPackageName"] = desktopPackageName
extra["desktopPackageVersion"] = desktopPackageVersion
extra["desktopNativeAssetTasks"] = desktopNativeAssetTasks
extra["hostDesktopNativeAssetTasks"] = hostDesktopNativeAssetTasks
extra["desktopNativeArchs"] = desktopNativeArchs
extra["generatedNativeResourcePathsByOs"] = generatedNativeResourcePathsByOs
extra["bundledCorePathsByOs"] = bundledCorePathsByOs

apply(from = "gradle/native-linux.gradle.kts")
apply(from = "gradle/native-macos.gradle.kts")
apply(from = "gradle/native-windows.gradle.kts")

fun generatedNativeResourcePaths(os: String, arch: String): List<String> =
    generatedNativeResourcePathsByOs[os]?.invoke(arch).orEmpty()

fun bundledCorePaths(os: String): List<String> = bundledCorePathsByOs[os].orEmpty()

fun requiredHostNativeResourcePaths(): List<String> =
    generatedNativeResourcePaths(hostDesktopOs, hostDesktopArch)

fun stagedNativeResourceLines(os: String): List<String> =
    desktopNativeArchs.getValue(os).flatMap { arch ->
        generatedNativeResourcePaths(os, arch).map { "$arch generated $it" } +
                bundledCorePaths(os).map { "$arch bundled $it" }
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
    resourcesDir.set(layout.projectDirectory.dir("src/main/resources"))
    requiredPaths.set(bundledCorePaths(hostDesktopOs))
}

val checkDesktopNativeResourceLists = tasks.register<CheckNativeResourceListsTask>("checkDesktopNativeResourceLists") {
    group = "verification"
    description = "Compares the native resources each OS's package takes with the recorded lists."

    val generatedRoot = generatedNativeResources.get().asFile
    desktopNativeArchs.keys.forEach { os ->
        staged.put(os, stagedNativeResourceLines(os))
        recordedFiles.put(os, layout.projectDirectory.file("gradle/native-$os.resources.txt").asFile.absolutePath)
    }
    hostDeclared.set(requiredHostNativeResourcePaths())
    hostProduced.set(
        hostDesktopNativeAssetTasks.flatMap { (it as TaskProvider<*>).get().outputs.files.files }
            .map { it.relativeTo(generatedRoot).invariantSeparatorsPath }
    )
}

// Proven where pull requests are compiled, which is a Linux runner. On the
// other systems the task is there to be run by hand and holds up no build.
if (currentBuildOs.isLinux) {
    tasks.named("compileKotlin") {
        dependsOn(checkDesktopNativeResourceLists)
    }
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
