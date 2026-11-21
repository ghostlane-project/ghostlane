// Windows: the native resources of the desktop package, the tasks that make
// them and the tasks that pack them. Applied by ../build.gradle.kts, which hands
// over what the three systems share as extra properties. The list this file
// declares is recorded in native-windows.resources.txt and compared with it by
// :desktopApp:checkDesktopNativeResourceLists.

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import java.net.URI
import java.util.zip.ZipFile
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

val wintunVersion = "0.14.1"

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    "buildOlcRtcWindowsAmd64",
    "windows",
    "amd64",
    "olcrtc-windows-amd64.exe"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    "buildOlcRtcLibWindowsAmd64",
    "windows",
    "amd64",
    "olcrtc-windows-amd64.dll"
)

desktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
desktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)

if (hostDesktopOs == "windows") {
    hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
    hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)

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

// The architectures a package is built for. Windows names amd64 in its
// resources whatever the host is.
desktopNativeArchs["windows"] = listOf("amd64")

// Made by the tasks above under build/generated/desktopNativeResources.
generatedNativeResourcePathsByOs["windows"] = { _: String ->
    buildList {
        add("native/olcrtc-windows-amd64.exe")
        add("native/olcrtc-windows-amd64.dll")
        add("native/wintun.dll")
    }
}

// Supplied under src/main/resources by whoever runs the build.
bundledCorePathsByOs["windows"] = listOf(
    "native/sing-box.exe",
    "native/xray.exe"
)

if (hostDesktopOs == "windows") {
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
