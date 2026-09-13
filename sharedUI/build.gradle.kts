import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
}

val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionValue = olcboxVersion.get()

// Which build a user is looking at. The version cannot say: the patch number is
// the CI run number, so a nightly and a local Xcode archive both call themselves
// 1.0.273. The short SHA tells them apart, and the `*` says the tree had
// uncommitted edits — the most useful thing this line can tell whoever is editing.
//
// Eager, like olcboxVersionValue above: this is a build input, not a lazy output.
// runCatching because a source tree with no git, or no git binary, is a build that
// should still succeed and simply not know its SHA.
fun gitOutput(vararg args: String): String? = runCatching {
    val output = providers.exec {
        workingDir = rootProject.layout.projectDirectory.asFile
        commandLine(listOf("git") + args)
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue != 0) {
        null
    } else {
        output.standardOutput.asText.get().trim().ifBlank { null }
    }
}.getOrNull()

val olcboxBuildIdValue: String = providers.gradleProperty("olcbox.buildId").orNull
    ?: gitOutput("rev-parse", "--short=7", "HEAD")?.let { sha ->
        if (gitOutput("status", "--porcelain").isNullOrBlank()) sha else "$sha*"
    }
    ?: "local"
// Machine-local build inputs, read from local.properties.
//
// Deliberately NOT a gradle property: this repository is public and its own
// gradle.properties is tracked, so the file a person is most likely to reach for
// is the one that would publish the value. local.properties is gitignored.
//
// It exists because the environment variable is not a local route at all —
// Xcode's build phases do not inherit the shell's environment, so exporting one
// in a terminal never reaches the gradle that Xcode runs.
//
// Read plainly, the way this file already reads OLCRTC_REPO. A `fileContents`
// provider was tried first and silently yielded nothing — the task ran, the key
// stayed empty — and a build input that can fail without saying so is worth less
// than the laziness it buys.
val localBuildProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localBuildProperty(name: String): String? =
    localBuildProperties.getProperty(name)?.takeIf { it.isNotBlank() }

// The same value in gradle.properties would be committed and published. Say so
// loudly rather than let it happen quietly. Only the tracked file is checked,
// so this cannot misfire on a location that would have been safe.
require(!rootProject.file("gradle.properties").readText().contains("olcbox.cryptKeyV1")) {
    "olcbox.cryptKeyV1 belongs in local.properties (gitignored), not gradle.properties " +
        "(tracked, and this repository is public)."
}

// crypt1 client key + admin unlock hash: baked at build time (absent ⇒ features
// off, so a crypt1 link imports as "No valid ProofKit config found").
val olcboxCryptKeyV1 = providers.environmentVariable("OLCBOX_CRYPT_KEY_V1")
    .orElse(localBuildProperty("olcbox.cryptKeyV1").orEmpty())
val olcboxAdminPassSha256 = providers.environmentVariable("OLCBOX_ADMIN_PASS_SHA256")
    .orElse(localBuildProperty("olcbox.adminPassSha256").orEmpty())
// Where the app asks what a partner's opaque link points at. Not a secret —
// overridable only so a test build can aim at something other than production.
val olcboxResolverBase = providers.environmentVariable("OLCBOX_RESOLVER_BASE")
    .orElse(localBuildProperty("olcbox.resolverBase") ?: "https://proofkit.org/api/v1")
val generatedAppInfoDir = layout.buildDirectory.dir("generated/source/olcboxAppInfo/commonMain")

val olcrtcRepoPath = providers.environmentVariable("OLCRTC_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = rootProject.file(olcrtcRepoPath.get())
val olcrtcAndroidAar = layout.buildDirectory.file("generated/olcrtc/olcrtc.aar")
val olcrtcAndroidAarFile = olcrtcAndroidAar.get().asFile
val olcrtcIosXcframework = layout.buildDirectory.dir("generated/olcrtc/ios/OlcRtcMobile.xcframework")
val olcrtcIosXcframeworkDir = olcrtcIosXcframework.get().asFile

abstract class GenerateAppInfoTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** Short git SHA (`*` when the tree was dirty), or "local". */
    @get:Input
    abstract val buildId: Property<String>

    @get:Input
    abstract val cryptKeyV1: Property<String>

    @get:Input
    abstract val adminPassSha256: Property<String>

    @get:Input
    abstract val resolverBase: Property<String>

    /** True for an Xcode Release build — an archive headed for the App Store. */
    @get:Input
    abstract val shippingBuild: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // Never the values: one is a secret, and the other is a hash of one. The
        // point is only that a build which quietly disabled a feature says so.
        logger.lifecycle(
            "olcbox app info: crypt1 " +
                (if (cryptKeyV1.get().isBlank()) "OFF (no key — crypt1 links will not import)" else "ON") +
                ", admin gate " + (if (adminPassSha256.get().isBlank()) "OFF" else "ON")
        )

        // A line in a build log is enough for a debug build and not nearly enough
        // for an archive: Xcode buries it, nobody reads it, and the app ships with
        // a feature quietly missing. release.yml already refuses to build without
        // these; an Xcode archive is the same release by another route, so it
        // refuses too. Debug builds stay permissive — a simulator run needs
        // neither.
        if (shippingBuild.get()) {
            // Only the crypt key stops a release. Without it, a crypt1 link
            // reports "No valid ProofKit config found" — a feature that looks
            // broken rather than absent, and nothing in the app says why.
            require(cryptKeyV1.get().isNotBlank()) {
                "Release build is missing olcbox.cryptKeyV1 — crypt1 links would not " +
                    "import, and the app would give no reason. Put it in " +
                    "local.properties (gitignored) or export OLCBOX_CRYPT_KEY_V1."
            }

            // The admin hash is a warning, not a failure.
            //
            // It reads like the more dangerous omission and is the opposite:
            // `plumbingVisible` is `gate.enabled && unlocked`, so a build with no
            // hash hides the per-location editor and "create custom location"
            // permanently rather than until seven taps. What an ungated build
            // actually exposes is the SOCKS5 proxy row. Refusing to build over
            // that stopped a release for a configuration that is stricter than
            // the one it was insisting on.
            if (adminPassSha256.get().isBlank()) {
                logger.warn(
                    "olcbox: release build with no admin gate. The 7-tap unlock does " +
                        "nothing; the per-location configurator stays hidden, and the " +
                        "SOCKS5 proxy row is visible. Set olcbox.adminPassSha256 to gate it."
                )
            }
        }
        val packageDir = outputDir.get().asFile.resolve("org/olcbox/app")
        packageDir.mkdirs()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        packageDir.resolve("GeneratedAppInfo.kt").writeText(
            """
            package org.olcbox.app

            internal object GeneratedAppInfo {
                const val NAME: String = "olcbox"
                const val VERSION: String = "${esc(version.get())}"
                const val BUILD: String = "${esc(buildId.get())}"
                const val CRYPT_KEY_V1: String = "${esc(cryptKeyV1.get())}"
                const val ADMIN_PASS_SHA256: String = "${esc(adminPassSha256.get())}"
                const val RESOLVER_BASE: String = "${esc(resolverBase.get())}"
            }
            """.trimIndent() + "\n"
        )
    }
}

// Xcode exports CONFIGURATION into the build phase that calls Gradle, so this is
// how an archive announces itself. Absent (a plain `./gradlew` run) is not an
// archive, and neither is Debug.
val xcodeIsReleaseBuild = providers.environmentVariable("CONFIGURATION")
    .map { it.equals("Release", ignoreCase = true) }
    .orElse(false)

val generateAppInfo by tasks.registering(GenerateAppInfoTask::class) {
    version.set(olcboxVersionValue)
    buildId.set(olcboxBuildIdValue)
    cryptKeyV1.set(olcboxCryptKeyV1)
    adminPassSha256.set(olcboxAdminPassSha256)
    resolverBase.set(olcboxResolverBase)
    shippingBuild.set(xcodeIsReleaseBuild)
    outputDir.set(generatedAppInfoDir)
}

// sing-box and Xray come prebuilt from the iOS Frameworks workflow rather than
// being compiled here: they need Go and the sagernet gomobile fork, and twenty
// minutes of a macOS runner that has already been spent once.
//
// One framework, not two. Each `gomobile bind` carries its own cgo bootstrap
// and seq glue, so linking two of them into the extension fails with 49
// duplicate symbols — see scripts/build-cores-ios.sh.
val coresIosDir = layout.buildDirectory.dir("generated/cores/ios")

val fetchCoresIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Downloads the prebuilt Cores.xcframework (sing-box + Xray) for the packet tunnel extension."

    outputs.dir(coresIosDir)
    commandLine(
        "bash",
        rootProject.file("scripts/fetch-cores-ios.sh").absolutePath,
        coresIosDir.get().asFile.absolutePath
    )
}

val buildOlcrtcIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc iOS XCFramework from OLCRTC_REPO using gomobile."

    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.dir(olcrtcIosXcframework)

    workingDir = olcrtcRepoDir

    doFirst {
        delete(olcrtcIosXcframeworkDir)
        olcrtcIosXcframeworkDir.parentFile.mkdirs()
    }

    commandLine(
        "gomobile",
        "bind",
        "-target=ios",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcIosXcframeworkDir.absolutePath,
        "./mobile"
    )
}

kotlin {
    // `expect`/`actual` classes are still marked Beta, and the compiler says so
    // on every build for PlatformCrypto — which is an `expect object` on purpose
    // and is not going to stop being one. The flag is the documented way to
    // accept that, and the warning it removes was drowning the ones worth
    // reading. See KT-61573.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "org.olcbox.app.sharedui"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    // Declaring any dependsOn by hand switches the default hierarchy template
    // off, and it takes appleMain/iosMain with it — every expect in commonMain
    // loses its iOS actual at once, which reads as the actuals having vanished
    // rather than as the tree they hang from having been unplugged. Re-applying
    // it explicitly is the documented way to keep both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }

        // The two JVM targets share one implementation of anything that is
        // plain java.* — path latency, so far. Written twice it would be two
        // measurements that drift, and the desktop one would be the one nobody
        // notices has stopped agreeing with the phone.
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)

            // Core only: 864 KB against 36 MB for the extended set, which we
            // were carrying in full for sixteen icons. Those sixteen now live in
            // org.olcbox.app.ui.icons.PkIcons as their own path data.
            implementation(libs.compose.material.icons.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatformSettings)
            implementation(libs.kstore)
            implementation(libs.materialKolor)
            implementation(libs.androidx.datastore.preferences)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            // See androidx-appcompat / androidx-fragment in libs.versions.toml.
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.fragment)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.zxing.core)
            implementation(project(":sharedUI:olcrtc-bin"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.jna)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }

        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }
    }

    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach {
            binaries {
                framework {
                    baseName = "SharedUI"
                    isStatic = true
                    // Said outright rather than inferred. Kotlin/Native derives a
                    // bundle ID from the common package prefix of what it exports,
                    // cannot always find one, and then warns on every single build
                    // that it fell back to the bundle *name*. A framework going
                    // into an App Store submission should carry an identifier
                    // someone chose.
                    binaryOption("bundleId", "org.proofkit.app.SharedUI")
                }
            }
        }
}

// The net dump tests write sing-box and Xray configs for `sing-box check`
// (scripts/check-singbox-configs.sh, pr-checks). Declared as outputs so that a
// build-cache hit restores them: otherwise the cached test is skipped, writes
// nothing, and the check that follows finds an empty directory.
tasks.withType<org.gradle.api.tasks.testing.Test>().matching { it.name == "jvmTest" }.configureEach {
    outputs.dir(layout.buildDirectory.dir("singbox-configs"))
    outputs.dir(layout.buildDirectory.dir("xray-configs"))
}
