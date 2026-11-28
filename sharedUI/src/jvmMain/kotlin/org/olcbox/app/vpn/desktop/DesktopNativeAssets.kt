package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists

internal object DesktopNativeAssets {
    fun resolveHevSocks5TunnelBinary(): Path {
        val fileName = hevSocks5TunnelFileName()
        return resolveBinary(
            fileName = fileName,
            resourceName = "native/$fileName",
            candidates = hevSocks5TunnelSourceCandidates(fileName)
        )
    }

    /**
     * The bundled sing-box carries `.exe` on Windows and nothing elsewhere.
     */
    fun singBoxFileName(): String = coreFileName("sing-box")

    private fun coreFileName(base: String): String =
        if (DesktopPaths.os == DesktopOs.Windows) "$base.exe" else base

    /**
     * Resolve the bundled `sing-box` core binary. Honors the `OLCBOX_SINGBOX_BINARY`
     * env override (tests / dev) before falling back to the bundled resource.
     */
    fun resolveSingBoxBinary(): Path =
        resolveExternalCore(singBoxFileName(), "OLCBOX_SINGBOX_BINARY")

    /**
     * hev.exe, for the Windows tun, staged beside the wintun.dll it loads (the
     * two are looked up by the process's own directory). Both are copied only
     * when their bytes differ: Windows locks a program that runs.
     */
    fun resolveHevWindowsBinary(): Path {
        ensureWintunRuntime()
        return resolveBinary(
            fileName = "hev.exe",
            resourceName = "native/hev.exe",
            candidates = desktopNativeResourceCandidates("hev.exe")
        )
    }

    /** Stage Wintun only for the mode that loads it, never for System Proxy. */
    fun ensureWintunRuntime() {
        if (DesktopPaths.os == DesktopOs.Windows) copyRuntimeAsset("wintun.dll")
    }

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

    private fun desktopArch(): String {
        return when (DesktopPaths.arch) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> error("Unsupported desktop architecture: ${DesktopPaths.arch}")
        }
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

    private fun copyRuntimeAsset(fileName: String): Path {
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName)
        Files.createDirectories(target.parent)
        val resourceName = "native/$fileName"
        val resource = javaClass.classLoader.getResourceAsStream(resourceName)
        if (resource != null) {
            copyIfChanged(target) { staged ->
                resource.use { Files.copy(it, staged, StandardCopyOption.REPLACE_EXISTING) }
            }
            return target
        }

        desktopNativeResourceCandidates(fileName)
            .firstOrNull { it.exists() }
            ?.let { source ->
                copyIfChanged(target) { staged ->
                    Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
                }
                return target
            }

        error("Bundled runtime asset is missing: $resourceName")
    }

    /** Avoid replacing DLLs that a running Windows core still has mapped. */
    private inline fun copyIfChanged(target: Path, writeStaged: (Path) -> Unit) {
        val staged = Files.createTempFile(target.parent, "native-", ".tmp")
        try {
            writeStaged(staged)
            if (!Files.exists(target) || Files.mismatch(staged, target) != -1L) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
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
