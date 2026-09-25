package org.olcbox.app

import org.olcbox.app.ui.tv.TelevisionAware
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {

    // The import link the system handed us, until the screen takes it. Launch
    // mode is singleInstance, so a link while running arrives in onNewIntent.
    private val pendingImportLink = MutableStateFlow<String?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)
        // Null where the store owns updates (the `play` flavor): the screen then
        // neither polls the release feed nor shows the Updates section, and the
        // manifest of that flavor carries no REQUEST_INSTALL_PACKAGES to use.
        val updateService = if (resources.getBoolean(R.bool.store_self_update)) {
            AppUpdateService(
                deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
            )
        } else {
            null
        }

        val viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        val locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            AppTheme {
                TelevisionAware {
                    AndroidMainScreen(
                        viewModel = viewModel,
                        locationViewModel = locationViewModel,
                        vpnManager = vpnManager,
                        appUpdateService = updateService,
                        pendingImportLink = pendingImportLink
                    )
                }
            }
        }
        intent?.dataString?.let { pendingImportLink.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { pendingImportLink.value = it }
    }
}
