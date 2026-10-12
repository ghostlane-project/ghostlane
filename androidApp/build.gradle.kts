import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

composeCompiler {
    // No Compose mapping file in a minified build.
    //
    // The plugin makes one for every minified variant — a table that turns the
    // group keys in a Compose stack trace back into names — and to make it,
    // downloads a tool with the compiler's own version:
    // org.jetbrains.kotlin:compose-group-mapping:2.4.10-375. That version is a
    // development build, and the repository that held it keeps development
    // builds for a while: on 2026-10-12 the tool was gone (404, with 2.4.10 and
    // the 2.4.2x builds listed beside the gap) while the compiler itself was
    // still there. The release build failed in its first twenty seconds,
    // resolving `composeMappingProducerClasspath`. It had gone on working until
    // then only because the runner kept the jar in a cache, and a cache lives a
    // week from its last use, which was the previous release.
    //
    // Nothing reads the table: the app turns no Compose diagnostic traces on, and
    // the R8 mapping that deobfuscates an ordinary stack trace is made as before.
    // The debug build and the pull-request checks never asked for the tool, which
    // is why they stayed green over a release build that could not start.
    //
    // The cure is a compiler that is a release rather than a build of one; this
    // is what lets a release be built until the pin moves.
    includeComposeMappingFile.set(false)
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        // org.proofkit.app, as on iOS: the olcbox id collided with every other
        // app built from olcbox on the same phone. The Kotlin namespace stays.
        applicationId = "org.proofkit.app"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    // One app, two channels. `github` is the build on the releases page and
    // updates itself from there, which is what REQUEST_INSTALL_PACKAGES is
    // for. `play` is the bundle Google Play distributes: Play owns updates and
    // refuses that permission — and QUERY_ALL_PACKAGES — at upload, before a
    // human looks (Device and Network Abuse; package visibility). Both are
    // removed in src/play/AndroidManifest.xml, and src/play/res overrides the
    // store_self_update bool so AppActivity builds no updater. Same
    // applicationId, version code and signing key on both, so a phone can move
    // between them and update. (Plain res files rather than resValue: AGP 9
    // ships with buildFeatures.resValues off.)
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
        }
        create("play") {
            dimension = "store"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
        // Stated explicitly rather than relying on the plugin default, so the
        // instrumented sources cannot silently stop being compiled.
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)

    // Instrumented tests: the only way to exercise the packaged core binary the way
    // the app does — extracted into nativeLibraryDir and exec'd on a real Android.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // sharedUI keeps the olcRTC binding to itself; the tests need it directly to
    // pin what its liveness check reports when olcRTC was never started.
    androidTestImplementation(project(":sharedUI:olcrtc-bin"))
}
