package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class JvmDesktopSocksProxySettingsStore(
    private val file: Path = DesktopPaths.appDataDir().resolve("desktop_socks_proxy_settings.json")
) {
    suspend fun load(): DesktopSocksProxySettings {
        return runCatching {
            if (!Files.exists(file)) return DesktopSocksProxySettings()
            json.decodeFromString(DesktopSocksProxySettings.serializer(), Files.readString(file)).normalized()
        }.getOrDefault(DesktopSocksProxySettings())
    }

    suspend fun save(settings: DesktopSocksProxySettings) {
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) {
            runCatching {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
            }.getOrElse {
                if (!Files.exists(file)) Files.createFile(file)
            }
        }
        Files.writeString(
            file,
            json.encodeToString(DesktopSocksProxySettings.serializer(), settings.normalized())
        )
        // Windows has no POSIX mode bits. On Unix this closes settings created by
        // older builds under a permissive umask as well as protecting new files.
        runCatching { Files.setPosixFilePermissions(file, OWNER_ONLY) }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
        val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
