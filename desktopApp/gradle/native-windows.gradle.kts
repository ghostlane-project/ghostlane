// Windows: the native resources of the desktop package, the tasks that make
// them and the tasks that pack them. Applied by ../build.gradle.kts, which hands
// over what the three systems share as extra properties. The list this file
// declares is recorded in native-windows.resources.txt and compared with it by
// :desktopApp:checkDesktopNativeResourceLists.
//
// The package carries ghostlane-engine.exe (supplied by the release job under
// src/main/resources/native, built at scripts/ghostlane-core-pin.sh), hev.exe
// and wintun.dll (made here by scripts/build-hev-windows.sh). No Go core is
// built or staged: K1 reads the finished assets.

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Zip

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
val desktopPackageName = extra["desktopPackageName"] as String
val desktopPackageVersion = extra["desktopPackageVersion"] as String

// hev.exe and its Wintun, built by scripts/build-hev-windows.sh (upstream's
// tag with clang for MSVC; no msys-2.0.dll; Wintun's release checked by its
// sha256). The script needs make, clang and an MSVC environment. The release
// job runs it first, signs hev.exe, and names its directory in
// GHOSTLANE_HEV_DIR: this task then takes the two files from there and builds
// nothing, so the file that is packed is the file that was signed.
//
// Plain ProcessBuilder and System.getenv, and no reference to `project` in the
// action: the configuration cache this build relies on.
if (hostDesktopOs == "windows") {
    val hevScript = rootProject.layout.projectDirectory.file("scripts/build-hev-windows.sh").asFile
    val hevWork = layout.buildDirectory.dir("tmp/hev-windows")
    val hevExe = generatedNativeResources.map { it.file("native/hev.exe") }
    val wintunDll = generatedNativeResources.map { it.file("native/wintun.dll") }
    val prebuilt = providers.environmentVariable("GHOSTLANE_HEV_DIR").orElse("")
    val buildHevWindows = tasks.register("buildHevSocks5TunnelWindows") {
        group = "build"
        description = "Makes native/hev.exe and native/wintun.dll (scripts/build-hev-windows.sh, or GHOSTLANE_HEV_DIR)."
        inputs.file(hevScript)
        inputs.property("prebuilt", prebuilt)
        outputs.files(hevExe, wintunDll)
        val scriptPath = hevScript.absolutePath.replace('\\', '/')
        val workPath = hevWork.map { it.asFile }
        val exePath = hevExe.map { it.asFile }
        val dllPath = wintunDll.map { it.asFile }
        val prebuiltDir = prebuilt
        doLast {
            val source: java.io.File = if (prebuiltDir.get().isNotBlank()) {
                java.io.File(prebuiltDir.get())
            } else {
                val work = workPath.get()
                work.mkdirs()
                val bash = listOf("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files\\Git\\usr\\bin\\bash.exe")
                    .firstOrNull { java.io.File(it).isFile } ?: "bash"
                val exit = ProcessBuilder(bash, scriptPath, work.absolutePath.replace('\\', '/'))
                    .inheritIO().start().waitFor()
                check(exit == 0) { "scripts/build-hev-windows.sh failed ($exit)" }
                work
            }
            for ((name, target) in listOf("hev.exe" to exePath.get(), "wintun.dll" to dllPath.get())) {
                val from = java.io.File(source, name)
                check(from.isFile) { "$name is not in ${source.absolutePath}" }
                target.parentFile.mkdirs()
                from.copyTo(target, overwrite = true)
            }
        }
    }
    desktopNativeAssetTasks.add(buildHevWindows)
    hostDesktopNativeAssetTasks.add(buildHevWindows)
}

// The architectures a package is built for. Windows names amd64 in its
// resources whatever the host is.
desktopNativeArchs["windows"] = listOf("amd64")

// Made by the tasks above under build/generated/desktopNativeResources.
generatedNativeResourcePathsByOs["windows"] = { _: String ->
    listOf("native/hev.exe", "native/wintun.dll")
}

// Supplied under src/main/resources by the release job, which builds the
// program at scripts/ghostlane-core-pin.sh. Its `version` event carries the
// pin, and no Go core is delivered beside it.
bundledCorePathsByOs["windows"] = listOf("native/ghostlane-engine.exe")

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
