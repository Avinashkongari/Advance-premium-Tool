package com.axion.ultimatefetcher.network.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.axion.ultimatefetcher.UltimateFetcherApplication
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.domain.models.PipelineStageStatus
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class VpnMonitorService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunInputStream: FileInputStream? = null
    private var tunOutputStream: FileOutputStream? = null
    private val tunWriteLock = Any()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipeline: PacketCapturePipeline? = null

    private val quicBlockFlag = AtomicBoolean(false)
    private var selectedPackages: List<String> = emptyList()
    private var customDnsServer: String = "1.1.1.1"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal(clearError = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_QUIC -> {
                val enabled = intent.getBooleanExtra(EXTRA_QUIC_BLOCK, false)
                quicBlockFlag.set(enabled)
                _liveState.update { it.copy(quicBlockingEnabled = enabled) }
                return START_STICKY
            }
            ACTION_START, ACTION_RECONFIGURE, null -> {
                quicBlockFlag.set(intent?.getBooleanExtra(EXTRA_QUIC_BLOCK, false) ?: false)
                selectedPackages = intent?.getStringArrayListExtra(EXTRA_TARGET_APPS)?.toList().orEmpty()
                customDnsServer = intent?.getStringExtra(EXTRA_DNS_SERVER).orEmpty().ifBlank { "1.1.1.1" }
                startVpnInternal()
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    private fun startVpnInternal() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Configuring local VPN monitor..."))

        val appContainer = (application as UltimateFetcherApplication).container
        val wasRunning = _liveState.value.isRunning

        // Cleanly tear down previous TUN/pipeline before re-establishing
        stopCaptureAndCloseTun()

        // Detect if another external VPN is already active on the device before establishing
        if (!wasRunning && isExternalVpnActive()) {
            reportFailure(
                "Another VPN is already active on this device. Disconnect the active VPN before starting UltimateFetcher."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Validate every selected package name before configuring VpnService.Builder
        val validation = AppFilterValidator.validateSelectedPackages(this, selectedPackages, packageName)
        if (validation.unavailablePackages.isNotEmpty()) {
            serviceScope.launch {
                appContainer.settingsDataStore.pruneUnavailableApps(validation.unavailablePackages)
            }
        }
        if (validation.fatalError != null) {
            reportFailure(validation.fatalError, unavailable = validation.unavailablePackages)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("UltimateFetcher Local Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(customDnsServer)

        // Configure valid IPv6 address, route, and DNS (safe fallback if device disables IPv6)
        runCatching {
            builder
                .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")
        }

        // Apply per-app allowed application filtering cleanly
        val (appliedPackages, filterError) = AppFilterValidator.applyToBuilder(
            validation = validation,
            selfPackageName = packageName,
            target = object : VpnAppFilterTarget {
                override fun addAllowedApplication(packageName: String) {
                    builder.addAllowedApplication(packageName)
                }

                override fun addDisallowedApplication(packageName: String) {
                    builder.addDisallowedApplication(packageName)
                }
            }
        )

        if (filterError != null) {
            reportFailure(filterError, unavailable = validation.unavailablePackages)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val tun = runCatching { builder.establish() }.getOrNull()
        if (tun == null) {
            reportFailure(
                "Failed to establish VPN interface. VPN permission may be denied/revoked or another VPN is already active."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        vpnInterface = tun
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        tunInputStream = input
        tunOutputStream = output

        val scopeSummary = if (validation.validAppNames.isNotEmpty()) {
            validation.validAppNames.joinToString(", ")
        } else {
            "All Launchable Apps"
        }

        val unavailableNote = if (validation.unavailablePackages.isNotEmpty()) {
            " (skipped uninstalled: ${validation.unavailablePackages.joinToString(", ")})"
        } else ""

        _liveState.value = VpnLiveState(
            isRunning = true,
            vpnInterfaceEstablished = true,
            vpnConnectedState = PipelineStageStatus.ACTIVE,
            packetReadingState = PipelineStageStatus.WAITING,
            packetProcessingState = PipelineStageStatus.WAITING,
            databaseSavingState = PipelineStageStatus.WAITING,
            statusMessage = "VPN interface established ($scopeSummary) — waiting for packets (0 captured)$unavailableNote",
            lastError = if (validation.unavailablePackages.isNotEmpty()) {
                "Skipped unavailable/uninstalled app(s): ${validation.unavailablePackages.joinToString(", ")}"
            } else null,
            selectedAppPackages = appliedPackages,
            selectedAppNames = validation.validAppNames,
            unavailablePackages = validation.unavailablePackages,
            quicBlockingEnabled = quicBlockFlag.get()
        )

        updateNotification("Monitoring $scopeSummary")

        val repo = appContainer.repository
        val sink = object : PacketCaptureSink {
            override suspend fun insertPackets(items: List<PacketLog>) = repo.insertPackets(items)
            override suspend fun insertDnsLogs(items: List<DNSLog>) = repo.insertDnsLogs(items)
            override suspend fun insertTlsLogs(items: List<TLSLog>) = repo.insertTlsLogs(items)
        }

        val capturePipeline = PacketCapturePipeline(
            scope = serviceScope,
            liveState = _liveState,
            sink = sink,
            quicBlockFlag = quicBlockFlag,
            appScopeLabel = appliedPackages.joinToString(", "),
            onDnsQueryObserved = { parsed, rawDnsQuery, dns ->
                // Relay DNS query asynchronously on a separate coroutine so the TUN reader NEVER blocks
                serviceScope.launch(Dispatchers.IO) {
                    relayDnsAndWriteBackToTun(parsed, rawDnsQuery, dns)
                }
            }
        )
        pipeline = capturePipeline
        capturePipeline.start(input)
    }

    private fun relayDnsAndWriteBackToTun(
        parsed: ParsedPacket,
        rawDnsQuery: ByteArray,
        dns: ParsedDnsPacket
    ) {
        val t0 = System.nanoTime()
        val result = runCatching {
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2000
            socket.use { sock ->
                val server = InetAddress.getByName(customDnsServer)
                sock.send(DatagramPacket(rawDnsQuery, rawDnsQuery.size, server, 53))
                val respBuf = ByteArray(1500)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPkt)
                val latencyMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val dnsRespBytes = respBuf.copyOf(respPkt.length)
                val parsedResp = PacketParser.parseDnsPayload(dnsRespBytes, 0, dnsRespBytes.size)

                // Inject valid IP+UDP DNS response packet back into TUN so apps resolve names and continue sending traffic!
                val tunReplyPacket = if (parsed.ipVersion == 6) {
                    PacketParser.buildIpv6UdpPacket(parsed.dstIp, parsed.dstPort, parsed.srcIp, parsed.srcPort, dnsRespBytes)
                } else {
                    PacketParser.buildIpv4UdpPacket(parsed.dstIp, parsed.dstPort, parsed.srcIp, parsed.srcPort, dnsRespBytes)
                }
                if (tunReplyPacket != null) {
                    synchronized(tunWriteLock) {
                        runCatching { tunOutputStream?.write(tunReplyPacket) }
                    }
                }
                Triple(parsedResp?.answers.orEmpty(), latencyMs, "")
            }
        }.getOrElse { err ->
            Triple(emptyList(), -1L, err.message ?: "DNS relay timeout")
        }

        pipeline?.submitRelayedDnsLog(
            DNSLog(
                queryName = dns.queryName,
                queryType = dns.queryType,
                dnsServer = parsed.dstIp,
                responseIps = result.first.joinToString(", "),
                latencyMs = result.second,
                protocolClass = "UDP_53",
                isEncrypted = false,
                errorMessage = result.third
            )
        )
    }

    private fun isExternalVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun reportFailure(message: String, unavailable: List<String> = emptyList()) {
        _liveState.value = VpnLiveState(
            isRunning = false,
            vpnInterfaceEstablished = false,
            vpnConnectedState = PipelineStageStatus.ERROR,
            packetReadingState = PipelineStageStatus.IDLE,
            packetProcessingState = PipelineStageStatus.IDLE,
            databaseSavingState = PipelineStageStatus.IDLE,
            statusMessage = message,
            lastError = message,
            unavailablePackages = unavailable
        )
    }

    private fun stopCaptureAndCloseTun() {
        pipeline?.stopAsync()
        pipeline = null
        runCatching { tunInputStream?.close() }
        runCatching { tunOutputStream?.close() }
        runCatching { vpnInterface?.close() }
        tunInputStream = null
        tunOutputStream = null
        vpnInterface = null
    }

    private fun stopVpnInternal(clearError: Boolean) {
        stopCaptureAndCloseTun()
        _liveState.update { curr ->
            curr.copy(
                isRunning = false,
                vpnInterfaceEstablished = false,
                vpnConnectedState = PipelineStageStatus.IDLE,
                packetReadingState = PipelineStageStatus.IDLE,
                packetProcessingState = PipelineStageStatus.IDLE,
                databaseSavingState = PipelineStageStatus.IDLE,
                statusMessage = "Stopped",
                lastError = if (clearError) null else curr.lastError
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onRevoke() {
        stopCaptureAndCloseTun()
        reportFailure("VPN permission was revoked by the Android system or superseded by another VPN.")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal(clearError = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VpnMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("UltimateFetcher Monitor")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "uf_vpn_monitor"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.axion.ultimatefetcher.VPN_START"
        private const val ACTION_RECONFIGURE = "com.axion.ultimatefetcher.VPN_RECONFIGURE"
        private const val ACTION_STOP = "com.axion.ultimatefetcher.VPN_STOP"
        private const val ACTION_UPDATE_QUIC = "com.axion.ultimatefetcher.VPN_UPDATE_QUIC"
        private const val EXTRA_QUIC_BLOCK = "extra_quic_block"
        private const val EXTRA_TARGET_APPS = "extra_target_apps"
        private const val EXTRA_DNS_SERVER = "extra_dns_server"

        private val _liveState = MutableStateFlow(VpnLiveState())
        val liveState: StateFlow<VpnLiveState> = _liveState.asStateFlow()

        fun start(
            context: Context,
            quicBlocking: Boolean,
            targetAppPackages: List<String>,
            dnsServer: String
        ) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_QUIC_BLOCK, quicBlocking)
                putStringArrayListExtra(EXTRA_TARGET_APPS, ArrayList(targetAppPackages))
                putExtra(EXTRA_DNS_SERVER, dnsServer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reconfigureIfRunning(
            context: Context,
            quicBlocking: Boolean,
            targetAppPackages: List<String>,
            dnsServer: String
        ): Boolean {
            if (!_liveState.value.isRunning) return false
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_RECONFIGURE
                putExtra(EXTRA_QUIC_BLOCK, quicBlocking)
                putStringArrayListExtra(EXTRA_TARGET_APPS, ArrayList(targetAppPackages))
                putExtra(EXTRA_DNS_SERVER, dnsServer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun reportPermissionDenied() {
            _liveState.update {
                it.copy(
                    isRunning = false,
                    vpnInterfaceEstablished = false,
                    vpnConnectedState = PipelineStageStatus.ERROR,
                    packetReadingState = PipelineStageStatus.IDLE,
                    packetProcessingState = PipelineStageStatus.IDLE,
                    databaseSavingState = PipelineStageStatus.IDLE,
                    statusMessage = "VPN permission was denied. Authorize Android VPN access to capture traffic.",
                    lastError = "VPN permission was denied by user."
                )
            }
        }

        fun updateQuicBlocking(context: Context, enabled: Boolean) {
            _liveState.update { it.copy(quicBlockingEnabled = enabled) }
            if (_liveState.value.isRunning) {
                val intent = Intent(context, VpnMonitorService::class.java).apply {
                    action = ACTION_UPDATE_QUIC
                    putExtra(EXTRA_QUIC_BLOCK, enabled)
                }
                context.startService(intent)
            }
        }

        fun resetSessionCounters() {
            _liveState.update {
                it.copy(
                    packetsRead = 0L,
                    packetsParsed = 0L,
                    packetsRejected = 0L,
                    totalPackets = 0L,
                    tcpPackets = 0L,
                    udpPackets = 0L,
                    dnsPackets = 0L,
                    quicPackets = 0L,
                    blockedQuicPackets = 0L,
                    packetsSavedToDb = 0L,
                    dbInsertErrors = 0L,
                    lastDbError = null,
                    lastPacketTimestamp = 0L,
                    capturedBytes = 0L
                )
            }
        }
    }
}
