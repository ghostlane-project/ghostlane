package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object DesktopNativeAssets {
    fun resolveOlcRtcBinary(): Path {
        return resolveOlcRtcBinaryCandidates().first()
    }

    fun resolveOlcRtcBinaryCandidates(): List<Path> {
        val fileNames = olcRtcFileNames()
        return fileNames.mapNotNull { resolveOlcRtcBinaryOrNull(it) }.also {
            if (it.isEmpty()) {
                error("Bundled native binary is missing: ${fileNames.joinToString(", ") { name -> "native/$name" }}")
            }
        }
    }

    private fun resolveOlcRtcBinaryOrNull(fileName: String): Path? {
        return try {
            resolveBinary(
                fileName = fileName,
                resourceName = "native/$fileName",
                candidates = olcRtcSourceCandidates(fileName)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun olcRtcFileNames(): List<String> {
        return when (DesktopPaths.os) {
            DesktopOs.MacOS -> listOf(
                "olcrtc-darwin-${desktopArch()}",
                "olcrtc-darwin-${desktopArchFallback()}"
            ).distinct()
            DesktopOs.Windows -> listOf("olcrtc-windows-amd64.exe")
            DesktopOs.Linux -> listOf("olcrtc-linux-${desktopArch()}")
            DesktopOs.Other -> error("Ghostlane desktop supports macOS, Windows and Linux")
        }
    }

    private fun olcRtcFileName(): String = olcRtcFileNames().first()

    private fun desktopArchFallback(): String {
        return when (desktopArch()) {
            "arm64" -> "amd64"
            "amd64" -> "arm64"
            else -> "amd64"
        }
    }

    fun resolveHevSocks5TunnelBinary(): Path {
        val fileName = hevSocks5TunnelFileName()
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = hevSocks5TunnelSourceCandidates(fileName)
        )
    }

    fun resolveWindowsTun2SocksBinary(): Path {
        val fileName = windowsTun2SocksFileName()
        val binary = resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = windowsTun2SocksSourceCandidates(fileName)
        )
        copyRuntimeAsset("wintun.dll")
        return binary
    }

    /**
     * The bundled core binaries carry `.exe` on Windows and nothing elsewhere.
     *
     * Everything else here is already platform-named — [windowsTun2SocksFileName]
     * has always been `tun2socks-windows-amd64.exe` — and these two were the
     * exception. Whether Windows will execute an extension-less PE depends on how
     * `CreateProcess` is reached and is not worth depending on when the extension
     * costs nothing; [makeExecutable] is a no-op there, so the name is the only
     * thing making the file runnable.
     */
    fun singBoxFileName(): String = coreFileName("sing-box")

    fun xrayFileName(): String = coreFileName("xray")

    private fun coreFileName(base: String): String =
        if (DesktopPaths.os == DesktopOs.Windows) "$base.exe" else base

    /**
     * Resolve the bundled `sing-box` core binary. Honors the `OLCBOX_SINGBOX_BINARY`
     * env override (tests / dev) before falling back to the bundled resource.
     */
    fun resolveSingBoxBinary(): Path =
        resolveExternalCore(singBoxFileName(), "OLCBOX_SINGBOX_BINARY")

    /**
     * Resolve the bundled `xray` core binary (used for xhttp locations). Honors the
     * `OLCBOX_XRAY_BINARY` env override before falling back to the bundled resource.
     */
    fun resolveXrayBinary(): Path =
        resolveExternalCore(xrayFileName(), "OLCBOX_XRAY_BINARY")

    private fun resolveExternalCore(fileName: String, envVar: String): Path {
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return Path(it) }
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = emptyList()
        )
    }

    private fun resolveBinary(
        fileName: String,
        resourceName: String,
        candidates: List<Path>
    ): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)

        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            // Windows locks an executable while it runs. A second sing-box
            // instance (TUN front or latency probe) must reuse identical bytes,
            // not overwrite the backend's running executable.
            val staged = Files.createTempFile(target.parent, "native-", ".tmp")
            try {
                resource.use { Files.copy(it, staged, StandardCopyOption.REPLACE_EXISTING) }
                if (!Files.exists(target) || Files.mismatch(staged, target) != -1L) {
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(staged)
            }
            makeExecutable(target)
            return target
        }

        candidates.firstOrNull { it.exists() }?.let {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(target)
            return target
        }

        error("Bundled native binary is missing: $resourceName")
    }

    fun hevSocks5TunnelFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Linux -> "hev-socks5-tunnel-linux-${desktopArch()}"
            else -> error("hev-socks5-tunnel desktop binary is only used for TUN mode")
        }
    }

    fun windowsTun2SocksFileName(): String {
        return when (DesktopPaths.os) {
            DesktopOs.Windows -> "tun2socks-windows-amd64.exe"
            else -> error("tun2socks desktop binary is only used for Windows TUN mode")
        }
    }

    private fun desktopArch(): String {
        return when (DesktopPaths.arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported desktop architecture: ${DesktopPaths.arch}")
        }
    }

    private fun olcRtcSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("OLCRTC_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val explicitRepo = System.getenv("OLCRTC_REPO")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        val defaultRepo = Path("..").resolve("olcrtc")
        return listOfNotNull(
            explicitBinary,
            explicitRepo
        ).flatMap { repoOrBinary ->
            if (repoOrBinary.fileName?.toString() == fileName || repoOrBinary.fileName?.toString() == fileName.removeSuffix(".exe")) {
                listOf(repoOrBinary)
            } else {
                repoCandidates(repoOrBinary, fileName)
            }
        } + repoCandidates(defaultRepo, fileName)
    }

    private fun repoCandidates(repo: Path, fileName: String): List<Path> {
        return listOf(
            repo.resolve("build").resolve(fileName),
            repo.resolve(fileName.removeSuffix(".exe")),
            repo.resolve("olcrtc")
        )
    }

    private fun hevSocks5TunnelSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("HEV_SOCKS5_TUNNEL_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(explicitBinary) + projectRootCandidates().flatMap { root ->
            val sourceBin = root.resolve("androidApp").resolve("src").resolve("main")
                .resolve("jni").resolve("hev-socks5-tunnel").resolve("bin")
            listOf(
                root.resolve("desktopApp").resolve("build").resolve("generated")
                    .resolve("desktopNativeResources").resolve("native").resolve(fileName),
                root.resolve("build").resolve("generated").resolve("desktopNativeResources")
                    .resolve("native").resolve(fileName),
                sourceBin.resolve("hev-socks5-tunnel.exe"),
                sourceBin.resolve("hev-socks5-tunnel")
            )
        }.distinct()
    }

    private fun windowsTun2SocksSourceCandidates(fileName: String): List<Path> {
        val explicitBinary = System.getenv("TUN2SOCKS_BINARY")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        return listOfNotNull(explicitBinary) + desktopNativeResourceCandidates(fileName)
    }

    private fun copyRuntimeAsset(fileName: String): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)
        val resourceName = "native/$fileName"
        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            resource.use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        }

        desktopNativeResourceCandidates(fileName)
            .firstOrNull { it.exists() }
            ?.let {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                return target
            }

        error("Bundled runtime asset is missing: $resourceName")
    }

    private fun makeExecutable(path: Path) {
        if (DesktopPaths.os != DesktopOs.Windows) {
            path.toFile().setExecutable(true, true)
        }
    }

    private fun desktopNativeResourceCandidates(fileName: String): List<Path> {
        return projectRootCandidates().flatMap { root ->
            listOf(
                root.resolve("desktopApp").resolve("build").resolve("generated")
                    .resolve("desktopNativeResources").resolve("native").resolve(fileName),
                root.resolve("build").resolve("generated").resolve("desktopNativeResources")
                    .resolve("native").resolve(fileName)
            )
        }.distinct()
    }

    private fun projectRootCandidates(): List<Path> {
        val cwd = Path("").toAbsolutePath().normalize()
        return generateSequence(cwd) { it.parent }
            .take(5)
            .toList()
    }
}
