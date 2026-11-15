// The Android lab of the olcRTC port's M9 (plan §10.7's Android twin), run
// by .github/workflows/lab-olc-ffi-android.yml with the repository's own
// wrapper: ./gradlew -p lab/olc-ffi/android ...
//
// A build of its own, not a module of the app's: it takes the app's
// versions (gradle/libs.versions.toml: AGP, Kotlin, JNA) and nothing else.
rootProject.name = "olc-ffi-android-lab"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    }
    versionCatalogs {
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
        }
    }
}
