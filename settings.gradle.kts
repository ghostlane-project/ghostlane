rootProject.name = "Multiplatform-App"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
        flatDir {
            dirs("androidApp/jniLibs/arm64-v8a")
        }
    }
}
include(":sharedUI")

// Gradle configures every project, so building the iOS framework on a Mac used
// to fail on ":androidApp" wanting an Android SDK — for a build that never
// touches Android. GitHub's macOS runners ship the SDK, which is why CI never
// noticed and a laptop did.
//
// Nothing depends on :androidApp as a project, so leaving it out when there is
// no SDK costs nothing and lets someone work on iOS without installing one.
val androidSdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":androidApp")
} else {
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties) — " +
            "skipping :androidApp. iOS and desktop builds are unaffected; set one to build Android."
    )
}

include(":desktopApp")

