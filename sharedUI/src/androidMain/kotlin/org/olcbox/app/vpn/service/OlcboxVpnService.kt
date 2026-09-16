package org.olcbox.app.vpn.service


import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mobile.LogWriter
import mobile.Mobile
import mobile.Runtime as OlcrtcRuntime
import mobile.SocketProtector
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.net.UpstreamDns
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.net.AndroidSingBoxController
import org.olcbox.app.net.AndroidXrayController
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.RuleSets
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.TunnelExit
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import org.olcbox.app.log.LogScrubber

class OlcboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var currentNetworkTransport: UpstreamTransport? = null
    private var isCallbackRegistered = false
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var socksProxy: AuthenticatedSocksProxy? = null

    // Unified client: sing-box / Xray cores for vless/hy2/xhttp locations. Exec'd
    // subprocesses (this VpnService is a Context). The app's UID already bypasses
    // the tun (addDisallowedApp(self) in applySplitTunneling), so a core's own
    // sockets don't loop back through the tunnel. `activeCorePort` is the SOCKS
    // port the hev bridge targets when a core is active (null = olcrtc default).
    private val singBoxCore by lazy { AndroidSingBoxController(this) }
    private val xrayCore by lazy { AndroidXrayController(this) }
    private var activeCorePort: Int? = null

    /** The routing choice read at the last start, so a reconnect in place keeps it. */
    private var routingMode = RoutingMode.Global

    /**
     * Whether sing-box is standing in front of olcRTC. Both then have to be
     * alive for the transport to count as running; the core port alone would
     * hide a dead engine behind a live front.
     */
    private var frontsOlcrtc = false

    // One engine per service. Created by the Go constructor: the generated
    // no-argument Runtime() allocates an empty struct that cannot start.
    private val olcrtc: OlcrtcRuntime by lazy { Mobile.new_() }

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network != currentNetwork) return

            networkLossJob?.cancel()
            networkLossJob = scope.launch {
                delay(NETWORK_LOSS_GRACE_MS)
                if (network != currentNetwork) return@launch

                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                    return@launch
                }

                if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification("Waiting for network...")
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return
            networkLossJob?.cancel()

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    requestTransportRecovery(
                        reason = "Network available",
                        fullRestart = false,
                        delayMs = NETWORK_STABILITY_GRACE_MS
                    )
                }
                return
            }

            val previousTransport = currentNetworkTransport
            val nextTransport = upstream.transportOrNull()
            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (OlcboxVpnState.status.value) {
                is VpnStatus.Connected -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Keeping transport on refreshed Wi-Fi network")
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS,
                            setReconnectingImmediately = false
                        )
                    }
                }

                is VpnStatus.Reconnecting -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport) &&
                        isActiveTransportRunning() &&
                        canReconnectTransportInPlace()
                    ) {
                        setStatus(VpnStatus.Connected)
                        updateNotification(connectedNotificationText())
                        startWatchdog()
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olcbox::VpnWakeLock")
            .apply { setReferenceCounted(false) }

        installMobileCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        applyStartOptions(loadStartOptions(intent))
        val isRestart = shouldRestartForStartCommand()
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        olcrtc.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@OlcboxVpnService.protect(fd.toInt())
            }
        })
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                // Its own tag, so it needs its own scrub: this one does not go through
                // addLog, and logcat is captured by bug reports.
                Log.v("olcrtc", LogScrubber.default.scrub(line))
                handleRtcLine(line)
            }
        })
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelMode = AndroidSplitTunnelMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                    ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            ),
            splitTunnelProxyApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
            splitTunnelBypassApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        splitTunnelMode = options.splitTunnelMode
        splitTunnelProxyApps = options.splitTunnelProxyApps
        splitTunnelBypassApps = options.splitTunnelBypassApps
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false
    ) {
        val previousStartupJob = startupJob
        val hadPendingStartup = previousStartupJob?.isActive == true
        previousStartupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        if (hadPendingStartup) {
            addLog("Canceling pending olcRTC start")
            stopMobile()
            stopTun2socks()
        }
        if (!isMigration) {
            resetRecoveryState()
        }
        val requestedGeneration = ++generation
        refreshWakeLock(force = true)

        startupJob = scope.launch {
            try {
                cleanupJob?.takeIf { it.isActive }?.let {
                    addLog("Waiting for previous olcRTC cleanup")
                    val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                        it.join()
                        true
                    } ?: false

                    if (!completed) {
                        addLog("Previous olcRTC cleanup is still pending; forcing transport cleanup")
                        it.cancel()
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    }
                }

                if (!isMigration) {
                    registerNetworkMonitor()
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock

                    val active = repository.getActiveLocation()
                    val location = active?.location?.normalized()
                    if (location == null || !location.isComplete()) {
                        setStatus(VpnStatus.Error("No active location"))
                        updateNotification("Add a location first")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }
                    OlcboxVpnState.activeLocation = location.normalized()
                    routingMode = repository.getRoutingSettings().mode

                    if (isMigration && !forceFullRestart && canReconnectTransportInPlace()) {
                        reconnectTransport(location, requestedGeneration)
                    } else {
                        startFullTunnel(location, requestedGeneration, isMigration, isRestart)
                    }
                }
            } finally {
                if (requestedGeneration == generation) {
                    releaseWakeLock()
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification("Waiting for network...")
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (startTransport(location, upstream, requestedGeneration, setErrorOnFailure = false)) {
            val exit = verifyTunnel(location)
            if (requestedGeneration != generation) return
            if (exit == null) {
                failUnverifiedTunnel(
                    what = "${activeModeLabel()} transport",
                    isMigration = true,
                    requestedGeneration = requestedGeneration
                )
                return
            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected — exit ${exit.label()}")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleTransportRetry(requestedGeneration, "transport reconnect failed")
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for network...")
            if (isMigration) {
                scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            }
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startTransport(location, upstream, requestedGeneration, setErrorOnFailure = !isMigration)) {
            if (isMigration) {
                updateUnderlyingNetwork(null)
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Waiting for transport...")
                scheduleTransportRetry(requestedGeneration, "transport start failed")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            val proxyExit = verifyTunnel(location)
            if (requestedGeneration != generation) return
            if (proxyExit == null) {
                failUnverifiedTunnel("Proxy mode", isMigration, requestedGeneration)
                return
            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog(
                "Proxy mode connected on SOCKS $socksListenHost:" +
                    "${activeCorePort ?: socksListenPort} — exit ${proxyExit.label()}"
            )
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val exit = verifyTunnel(location)
        if (requestedGeneration != generation) return
        if (exit == null) {
            failUnverifiedTunnel("VPN tunnel", isMigration, requestedGeneration)
            return
        }

        setStatus(VpnStatus.Connected)
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established — exit ${exit.label()}")
        startWatchdog()
    }

    /**
     * Ask the internet what this tunnel looks like from the far end, before the app
     * is allowed to call itself connected.
     *
     * Every connect failure seen in the field so far reported success: a core whose
     * port was already taken, a hysteria2 outbound rejected at TLS, an xhttp path
     * that never carried a byte. Each of them ran its steps without error, so the
     * status meant "we did the setup", not "traffic reaches the internet". A reply
     * that came back through the tunnel is the one signal that cannot be faked.
     *
     * The probe goes through the local SOCKS port the transport opened, which proves
     * the transport itself reaches the internet. It says nothing about the
     * tun → tun2socks leg: this app's UID bypasses the tun by design, so its own
     * traffic cannot travel that path.
     */
    private suspend fun verifyTunnel(location: LocationConfig): TunnelExit? {
        val isOlcrtc = location.kind == LocationKind.Olcrtc
        updateNotification("Verifying tunnel...")
        return TunnelVerifier.verify(
            socksHost = AndroidSocksProxySettings.connectHost(socksListenHost),
            socksPort = activeCorePort ?: socksListenPort,
            // Only olcRTC's local proxy asks for a login; the cores listen open.
            username = if (isOlcrtc) socksUsername else "",
            password = if (isOlcrtc) socksPassword else ""
        )
    }

    /**
     * A transport that came up but carried nothing. On a first connect that is a
     * plain failure; during a migration the recovery machinery owns the retry, the
     * same way a failed transport start is handled.
     */
    private suspend fun failUnverifiedTunnel(
        what: String,
        isMigration: Boolean,
        requestedGeneration: Long
    ) {
        addLog("$what is up but no traffic reached the internet through it")
        if (isMigration) {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleTransportRetry(requestedGeneration, "tunnel carried no traffic")
        } else {
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            setStatus(VpnStatus.Error("No traffic reached the internet through the tunnel"))
            updateNotification("Connection failed")
        }
    }

    /**
     * Start the transport for [location], branching on its kind: olcrtc uses the
     * existing [startMobile] path (unchanged); vless/hysteria2/xhttp start a
     * sing-box / Xray core subprocess via [startCore]. Both leave the hev bridge
     * pointed at the right SOCKS port (via [activeCorePort]).
     */
    private suspend fun startTransport(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val routing = routingFor(upstream)
        return if (location.kind == LocationKind.Olcrtc) {
            activeCorePort = null
            val started = startMobile(location, upstream, requestedGeneration, setErrorOnFailure)
            if (started && routing is Routing.BypassRussia) startFront(routing, setErrorOnFailure) else started
        } else {
            startCore(location, setErrorOnFailure, routing)
        }
    }

    /**
     * sing-box between hev-socks5-tunnel and olcRTC, so the routing rules see
     * every connection before the relay does. Only in tun mode: in proxy mode
     * the promised endpoint is olcRTC's own port, and a front there would be a
     * second port nobody was told about.
     */
    private suspend fun startFront(routing: Routing.BypassRussia, setErrorOnFailure: Boolean): Boolean {
        if (connectionMode != AndroidConnectionMode.Tun) {
            addLog("Routing: proxy mode keeps olcRTC global")
            return true
        }
        // The front cannot share olcRTC's port. The core port is the default,
        // but the SOCKS port is user-set and may be exactly that; then the
        // front takes the alternate, and hev follows activeCorePort as always.
        val port = if (socksListenPort == SingBoxConfig.SINGBOX_SOCKS_PORT) {
            FRONT_ALTERNATE_PORT
        } else {
            SingBoxConfig.SINGBOX_SOCKS_PORT
        }
        return try {
            stopCoreProcesses()
            waitForSocksPortReleased(port, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            singBoxCore.start(
                SingBoxConfig.buildSocksChain(
                    upstreamPort = socksListenPort,
                    socksPort = port,
                    username = socksUsername,
                    password = socksPassword,
                    routing = routing
                )
            )
            activeCorePort = port
            frontsOlcrtc = true
            // A port that answers proves nothing about who answers: had the
            // front failed to bind, whatever already listened there would
            // satisfy the probe. So the process is asked as well.
            if (!waitForSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS) || !singBoxCore.isRunning()) {
                addLog(singBoxCore.diagnostics())
                error("sing-box front not running on $port")
            }
            coroutineContext.ensureActive()
            addLog("sing-box front ready on $socksListenHost:$port")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopCoreProcesses() }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "sing-box front failed"
            addLog("front start failed: $msg")
            stopCoreProcesses()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }

    /**
     * Start a sing-box (reality/hy2) or Xray (xhttp) core.
     *
     * In proxy mode the core listens on [socksListenPort], because that is the
     * endpoint the app tells other apps to use — putting the core anywhere else
     * leaves that promised port dead. In tun mode nothing outside talks to it, so
     * it takes the internal core port and tun2socks follows [activeCorePort].
     */
    private suspend fun startCore(
        location: LocationConfig,
        setErrorOnFailure: Boolean,
        routing: Routing
    ): Boolean {
        val port = if (connectionMode == AndroidConnectionMode.Proxy) {
            socksListenPort
        } else {
            SingBoxConfig.SINGBOX_SOCKS_PORT
        }
        return try {
            val raw = location.rawLink ?: error("core location has no link")
            val spec = LinkParser.parse(raw) ?: error("unparseable core link")
            stopCoreProcesses()
            waitForSocksPortReleased(port, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            val label: String
            // Which core to ask afterwards, decided here rather than re-derived
            // from the label — the failure path should not have to parse a string
            // we build for humans.
            val diagnose: () -> String
            // Which processes must be alive once the port answers. A port that
            // answers proves nothing about who answers.
            val alive: () -> Boolean
            val fronted = routing is Routing.BypassRussia && connectionMode == AndroidConnectionMode.Tun
            if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) {
                if (fronted) {
                    // Xray does not route; sing-box does, so it goes in front.
                    xrayCore.start(XrayConfig.buildXhttp(spec, socksPort = XRAY_BEHIND_FRONT_PORT))
                    singBoxCore.start(
                        SingBoxConfig.buildSocksChain(XRAY_BEHIND_FRONT_PORT, socksPort = port, routing = routing)
                    )
                    label = "sing-box front + Xray/xhttp"
                    diagnose = { singBoxCore.diagnostics() + "\n" + xrayCore.diagnostics() }
                    alive = { singBoxCore.isRunning() && xrayCore.isRunning() }
                } else {
                    if (routing is Routing.BypassRussia) addLog("Routing: proxy mode keeps xhttp global")
                    xrayCore.start(XrayConfig.buildXhttp(spec, socksPort = port))
                    label = "Xray/xhttp"
                    diagnose = xrayCore::diagnostics
                    alive = xrayCore::isRunning
                }
            } else {
                singBoxCore.start(SingBoxConfig.build(spec, socksPort = port, routing = routing))
                label = "sing-box/${location.kind}"
                diagnose = singBoxCore::diagnostics
                alive = singBoxCore::isRunning
            }
            activeCorePort = port
            if (!waitForSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS) || !alive()) {
                // The core's own account of what went wrong, which otherwise sits
                // in a cache file only root can read. Without it this branch says
                // a port did not open and nothing about why, and that is all a
                // user has ever been able to report.
                addLog(diagnose())
                error("$label SOCKS not ready on $port")
            }
            coroutineContext.ensureActive()
            addLog("$label ready on $socksListenHost:$port")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("core start canceled")
                stopCoreProcesses()
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Core transport failed"
            addLog("core start failed: $msg")
            stopCoreProcesses()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }

    private fun stopCoreProcesses() {
        singBoxCore.stopNow()
        xrayCore.stopNow()
        activeCorePort = null
        frontsOlcrtc = false
    }

    /** Poll until the given local SOCKS port accepts connections, or timeout. */
    private suspend fun waitForSocksPortOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        return isLocalSocksPortOpen(port)
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            installMobileCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            waitForJitsiRoomCleanup(config.bypassProvider)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            configureMobileTransport(config, upstream)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
            lastMobileProvider = config.bypassProvider
            olcrtc.setProvider(config.bypassProvider)
            olcrtc.setRoom(config.id)
            olcrtc.setDeviceID(deviceId)
            olcrtc.setKey(config.key)
            olcrtc.setSocksPort(targetSocksPort.toLong())
            olcrtc.setSocksCredentials(socksUsername, socksPassword)
            olcrtc.start()
            olcrtc.waitReady(MOBILE_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                return false
            }
            coroutineContext.ensureActive()
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        } finally {
            if (!keepProcessBound || !olcrtc.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileTransport(location: LocationConfig, upstream: Network?) {
        val config = location.normalized()
        olcrtc.setTransport(config.transport)
        // The upstream network's own resolvers first, the public operator
        // behind them: some mobile networks answer only their own (olcbox#16).
        olcrtc.setDNS(upstreamDnsList(upstream))
        olcrtc.setSocksListenHost(socksListenHost)
        olcrtc.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Olcbox VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)

            if (!applySplitTunneling(builder)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun applySplitTunneling(builder: Builder): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                addLog("Split tunneling: all apps use TUN")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification("Split tunneling error")
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification("Split tunneling error")
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                val applied = splitTunnelBypassApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)

        // Only olcRTC's local proxy asks for a login; the cores listen open — the
        // same rule verifyTunnel() already follows, stated there in as many words.
        //
        // Sending credentials to a core makes hev offer username/password as its
        // only SOCKS method, and a sing-box or Xray inbound is built with no `auth`
        // at all, so it answers "no matching auth method" and closes. Nothing about
        // that is visible from here: the core's SOCKS port opens before it has
        // touched the server, so the transport reports ready, the tunnel is
        // established, and not one packet crosses.
        val socksAuth = if (activeCorePort != null) {
            ""
        } else {
            "\n              username: '$socksUsername'" +
                "\n              password: '$socksPassword'"
        }

        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS

            socks5:
              address: ${socksConnectHost()}
              port: ${activeCorePort ?: socksListenPort}
              udp: 'tcp'
              pipeline: false$socksAuth

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                when {
                    !isActiveTransportRunning() -> {
                        val what = activeTransportLabel()
                        addLog("Watchdog: $what stopped")
                        requestTransportRecovery("$what stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                if (currentNetwork != upstream) {
                    val previousTransport = currentNetworkTransport
                    val nextTransport = upstream.transportOrNull()
                    updateUnderlyingNetwork(upstream)
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Watchdog: refreshed Wi-Fi upstream")
                        continue
                    }
                    addLog("Watchdog: upstream changed to ${getNetName(upstream)}")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                if (mode == AndroidConnectionMode.Tun && isTunTrafficStalled()) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    // A core whose SOCKS port opened is reported as connected, and
                    // the port opens before the core has touched the server — so
                    // "connected but nothing loads" and "the exit is unreachable"
                    // look identical from here. The core knows which it is.
                    addLog(activeCoreDiagnostics())
                    requestTransportRecovery("TUN traffic stalled", fullRestart = false)
                    return@launch
                }
            }
        }
    }

    /**
     * The running core's account of itself, whichever core that is. An olcRTC
     * location runs neither, and saying so is the useful answer there rather than
     * an empty string that reads like a core with nothing to report.
     */
    private fun activeCoreDiagnostics(): String = when {
        xrayCore.isRunning() -> xrayCore.diagnostics()
        singBoxCore.isRunning() -> singBoxCore.diagnostics()
        else -> "no core process is running"
    }

    private fun cleanup(stopService: Boolean = true) {
        if (cleanupJob?.isActive == true) return

        val status = OlcboxVpnState.status.value
        if (status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            cleanupJob?.isActive != true
        ) {
            if (stopService) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        releaseWakeLock()

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        stopAuthenticatedSocksProxy()
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        cleanupJob = scope.launch {
            try {
                stopVisibleVpnProcesses()
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Disconnected)
                    addLog("${activeModeLabel()} stopped")
                }

                stopMobileAndWait()
                resetRecoveryState()
            } finally {
                if (stopService && generation == cleanupGeneration) stopSelf()
            }
        }
    }

    private suspend fun stopVisibleVpnProcesses() {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        cleanupVpnInterface()
        tunThread?.interrupt()
        waitForTun2socksStopped(tunThread)
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        unbindProcessFromNetwork()
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?) {
        if (thread == null) return
        val stopped = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (thread.isAlive) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("tun2socks cleanup is still pending")
        }
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true,
        stopMobileBeforeTun: Boolean = false
    ) {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        if (stopMobileBeforeTun) {
            stopMobile()
        }
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tunThread?.interrupt()
        if (closeTun) {
            waitForTun2socksStopped(tunThread)
        }
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        if (waitForSocksPort) {
            if (stopMobileBeforeTun) {
                waitForSocksPortReleased()
            } else {
                stopMobileAndWait()
            }
        } else if (!stopMobileBeforeTun) {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
    }

    private fun stopMobile() {
        val provider = lastMobileProvider
        val wasRunning = olcrtc.isRunning()
        // Logged rather than swallowed. A stop that misses its deadline is the
        // difference between the next room joining and "olcRTC runtime is
        // already active", and for one whole debugging session this line threw
        // that away: the phone reported a start refused by a runtime nobody
        // could see had failed to stop.
        runCatching { olcrtc.stop(MOBILE_STOP_TIMEOUT_MS) }
            .onFailure { addLog("olcRTC stop did not finish in time: ${it.message ?: it}") }
        // Also tear down any active sing-box / Xray core (no-op if none running).
        runCatching { stopCoreProcesses() }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait() {
        val socksPort = socksListenPort
        stopMobile()
        waitForSocksPortReleased(socksPort)
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(socksConnectHost(), port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && isActiveTransportRunning()) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()?.also { stats ->
            // The byte figures were read and thrown away — only the packet
            // deltas fed the stall watchdog. They are the session's traffic, and
            // the screen had nothing to show.
            OlcboxVpnState.setTraffic(bytesIn = stats.rxBytes, bytesOut = stats.txBytes)
        }
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Reconnecting...")
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Reconnecting...")
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(isMigration = true, forceFullRestart = fullRestart)
        }
    }

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock", it)
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(isMigration = true)
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy -> isActiveTransportRunning()
        }
    }

    /**
     * Whether the transport that is supposed to be carrying traffic is still alive.
     *
     * olcRTC runs inside the gomobile library; sing-box and Xray run as child
     * processes. Everything here was written when olcRTC was the only transport and
     * asked the engine's `isRunning()` directly — which is false whenever a core is the
     * active transport, so the watchdog declared every core connection dead fifteen
     * seconds after it came up and restarted it, forever.
     */
    private fun isActiveTransportRunning(): Boolean =
        if (frontsOlcrtc) {
            olcrtc.isRunning() && singBoxCore.isRunning()
        } else if (activeCorePort != null) {
            singBoxCore.isRunning() || xrayCore.isRunning()
        } else {
            olcrtc.isRunning()
        }

    private fun activeTransportLabel(): String =
        if (activeCorePort != null && !frontsOlcrtc) "core transport" else "olcRTC"

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            tun2socksThread != null ||
            socksProxy != null ||
            isActiveTransportRunning()
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        currentNetworkTransport = network?.transportOrNull()
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
        // A running olcRTC re-points its lookups at the new network's resolvers
        // at once; the engine applies SetDNS live and refuses a malformed list.
        if (network != null && olcrtc.isRunning()) {
            runCatching { olcrtc.setDNS(upstreamDnsList(network)) }
                .onFailure { addLog("olcRTC resolvers not updated: ${it.message}") }
        }
    }

    /**
     * The engine's resolver list for [network]: its DNS servers as the system
     * lists them, then the public operator. See [UpstreamDns].
     */
    private fun upstreamDnsList(network: Network?): String {
        val servers = network
            ?.let { connectivityManager.getLinkProperties(it)?.dnsServers }
            ?.map { it.hostAddress ?: "" }
            .orEmpty()
        addLog("Resolvers from ${network?.let { getNetName(it) } ?: "no network"}: ${servers.size}")
        return UpstreamDns.list(servers)
    }

    /**
     * What the builders get for [routingMode]: the rule files on disk and the
     * network's resolvers for direct names. Files are rewritten on every start —
     * 59 KB, and the alternative is a version check that can be wrong.
     */
    private suspend fun routingFor(upstream: Network?): Routing = when (routingMode) {
        RoutingMode.Global -> Routing.Global
        RoutingMode.BypassRussia -> {
            val dir = File(filesDir, RULE_SETS_DIR).apply { mkdirs() }
            for (file in RuleSets.all) File(dir, file.name).writeBytes(RuleSets.bytes(file))
            addLog("Routing: ${routingMode.hubSummary()}")
            Routing.BypassRussia(
                ruleSetDir = dir.absolutePath,
                directDns = DirectDns.Servers(upstreamDnsAddresses(upstream))
            )
        }
    }

    /** The network's resolvers as the system lists them, for the direct DNS server. */
    private fun upstreamDnsAddresses(network: Network?): List<String> =
        network?.let { connectivityManager.getLinkProperties(it)?.dnsServers }
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun isBenignWifiRefresh(
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?
    ): Boolean {
        return previousTransport == UpstreamTransport.Wifi &&
            nextTransport == UpstreamTransport.Wifi
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ghostlane VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ghostlane ${activeModeLabel()}")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, OlcboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        if (status is VpnStatus.Connected) {
            OlcboxVpnState.channelProxy = org.olcbox.app.data.repository.SubscriptionFetchProxy(
                AndroidSocksProxySettings.connectHost(socksListenHost), activeCorePort ?: socksListenPort,
                if (activeCorePort == null) socksUsername else "",
                if (activeCorePort == null) socksPassword else ""
            )
        } else {
            OlcboxVpnState.channelProxy = null
        }
        // Retire pooled sockets on migration as well as stop: they must not
        // carry a measurement from the previous network into the new session.
        if (status is VpnStatus.Connected && OlcboxVpnState.channelProbe == null) {
            OlcboxVpnState.channelProbe = org.olcbox.app.net.ChannelLatency.Session(OlcboxVpnState.channelProxy)
        } else if (status !is VpnStatus.Connected) {
            OlcboxVpnState.channelProbe?.close()
            OlcboxVpnState.channelProbe = null
        }
        OlcboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "${activeModeLabel()} Connected"

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("olcbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val MOBILE_STOP_TIMEOUT_MS = 5_000L
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 3
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 4_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val TUN_MTU = 1500

        /** Under filesDir, so a cache sweep cannot take the lists out from under a running core. */
        private const val RULE_SETS_DIR = "rulesets"

        /** Where Xray listens when sing-box fronts it, so the front can keep the core port. */
        private const val XRAY_BEHIND_FRONT_PORT = 10811

        /** Where the olcRTC front listens when the user-set SOCKS port is the core port itself. */
        private const val FRONT_ALTERNATE_PORT = 10812
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "OlcboxVpnService"

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
        }
    }
}
