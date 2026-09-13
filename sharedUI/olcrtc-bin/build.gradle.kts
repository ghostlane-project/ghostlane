val olcrtcRepoPath = providers.environmentVariable("OLCRTC_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = rootProject.file(olcrtcRepoPath.get())
val olcrtcAndroidAarFile = layout.buildDirectory.file("olcrtc.aar").get().asFile

val gomobileExecutable = providers.environmentVariable("GOMOBILE_PATH")
    .orElse(
        providers.systemProperty("user.home")
            .map { "$it/go/bin/gomobile" }
    ).get()

val buildOlcrtcAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc Android AAR from OLCRTC_REPO using gomobile."
    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.file(olcrtcAndroidAarFile)

    workingDir = olcrtcRepoDir
    
    // Ensure Go is in PATH for gomobile bind
    val path = System.getenv("PATH") ?: ""
    val goBin = file("${System.getProperty("user.home")}/go/bin").absolutePath
    if (!path.contains(goBin)) {
        environment("PATH", "$goBin:$path")
    }

    // olcrtc_lean leaves the videochannel transport (a QR codec) and the
    // livekit provider (protobuf and CEL initialisers) out of the bind. The
    // app offers neither and refuses a videochannel link on import; on the
    // phone profile they cost ~10 MB of resident memory and 21 MB of binary
    // for nothing. Same tag as the iOS cores build.
    commandLine(
        gomobileExecutable, "bind", "-target=android/arm,android/arm64,android/amd64",
        "-androidapi", "21", "-tags", "olcrtc_lean", "-ldflags", "-s -w -checklinkname=0",
        "-o", olcrtcAndroidAarFile.absolutePath, "./mobile"
    )
}

configurations.maybeCreate("default")
artifacts.add("default", olcrtcAndroidAarFile) {
    builtBy(buildOlcrtcAndroidAar)
}
