package org.olcbox.app.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.olcbox.app.data.LEGACY_LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.desktop.DesktopPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class JvmLocationsDataSourceImpl(
    private val appDir: Path = DesktopPaths.appDataDir()
) : LocationsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val bundleFile: Path
        get() = appDir.resolve(LOCATIONS_BUNDLE_FILE_NAME)

    private val legacyBundleFile: Path
        get() = appDir.resolve(LEGACY_LOCATIONS_BUNDLE_FILE_NAME)

    private val deviceIdentityFile: Path
        get() = appDir.resolve("device_identity")

    override suspend fun loadLocationBundle(): LocationBundleV4? = withContext(Dispatchers.IO) {
        val file = bundleFile.takeIf { it.exists() } ?: legacyBundleFile.takeIf { it.exists() }
            ?: return@withContext null
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(LocationBundleV4.serializer(), file.readText()).normalized()
        }.getOrNull()
    }

    override suspend fun saveLocationBundle(bundle: LocationBundleV4): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(appDir)
        val text = json.encodeToString(LocationBundleV4.serializer(), bundle.normalized())
        // Written beside the bundle and moved over it. A write in place empties
        // the file first, and an app that dies there comes back to a bundle
        // that does not parse: no server lists.
        // A bundle that is a symlink stays one: what it points at is replaced.
        val target = runCatching { bundleFile.toRealPath() }.getOrDefault(bundleFile)
        val temp = Files.createTempFile(target.parent, "$LOCATIONS_BUNDLE_FILE_NAME.", ".tmp")
        try {
            // A write that fails here, on a full disk, leaves the bundle as it
            // was. Only a move that is refused falls back to the write in place
            // there was before.
            temp.writeText(text)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                target.writeText(text)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()

    override suspend fun loadLegacyActiveLocationId(): String? = null

    override suspend fun loadDeviceIdentity(): String? = withContext(Dispatchers.IO) {
        deviceIdentityFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.ifBlank { null }
    }

    override suspend fun saveDeviceIdentity(value: String): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(appDir)
        deviceIdentityFile.writeText(value.trim())
    }
}
