package com.axion.ultimatefetcher.network.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.axion.ultimatefetcher.UltimateFetcherApplication
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VpnMonitorService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private val quicBlockFlag = AtomicBoolean(false)
    private var targetAppPackage: String = ""
    private var customDnsServer: String = "1.1.1.1"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_QUIC -> {
                val enabled = intent.getBooleanExtra(EXTRA_QUIC_BLOCK, false)
                quicBlockFlag.set(enabled)
                _liveState.update { it.copy(quicBlockingEnabled = enabled) }
                return START_STICKY
            }
            else -> {
                quicBlockFlag.set(intent?.getBooleanExtra(EXTRA_QUIC_BLOCK, false) ?: false)
                targetAppPackage = intent?.getStringExtra(EXTRA_TARGET_APP).orEmpty()
                customDnsServer = intent?.getStringExtra(EXTRA_DNS_SERVER).orEmpty().ifBlank { "1.1.1.1" }
                startVpnInternal()
                return START_STICKY
            }
        }
    }

    private fun startVpnInternal() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Local metadata monitor active"))

        stopCaptureOnly()
        val builder = Builder()
            .setSession("UltimateFetcher Local Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(customDnsServer)

        runCatching {
            if (targetAppPackage.isNotBlank()) {
                builder.addAllowedApplication(targetAppPackage)
            } else {
                builder.addDisallowedApplication(packageName)
            }
        }

        val tun = runCatching { builder.establish() }.getOrNull()
        if (tun == null) {
            _liveState.value = VpnLiveState(
                isRunning = false,
                statusMessage = "VPN authorization required or another VPN is active"
            )
            stopSelf()
            return
        }

        vpnInterface = tun
        _liveState.value = VpnLiveState(
            isRunning = true,
            statusMessage = if (targetAppPackage.isNotBlank()) "Monitoring $targetAppPackage" else "Local TUN active (10.215.173.1)",
            targetAppPackage = targetAppPackage,
            quicBlockingEnabled = quicBlockFlag.get()
        )

        val repo = (application as UltimateFetcherApplication).container.repository
        captureJob = serviceScope.launch {
            val input = FileInputStream(tun.fileDescriptor)
            val buffer = ByteArray(32767)
            val packetBatch = ArrayList<PacketLog>(24)
            var lastFlushMs = System.currentTimeMillis()

            while (isActive) {
                val readLen = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (readLen <= 0) break

                val parsed = PacketParser.parseIpPacket(buffer, readLen) ?: continue
                val isQuic = parsed.protocol == "QUIC" || (parsed.protocol == "UDP" && parsed.dstPort == 443)
                val isBlocked = isQuic && quicBlockFlag.get()

                var metadataNote = ""
                if (parsed.protocol == "DNS" && parsed.payloadLength >= 12) {
                    val dns = PacketParser.parseDnsPayload(buffer, parsed.payloadOffset, parsed.payloadLength)
                    if (dns != null && dns.queryName.isNotBlank()) {
                        metadataNote = "DNS ${dns.queryType} ${dns.queryName}"
                        val relayResult = relayDnsMetadataQuery(dns.queryName, dns.queryType)
                        repo.insertDnsLog(
                            DNSLog(
                                queryName = dns.queryName,
                                queryType = dns.queryType,
                                dnsServer = parsed.dstIp,
                                responseIps = relayResult.first.joinToString(", "),
                                latencyMs = relayResult.second,
                                protocolClass = "UDP_53",
                                isEncrypted = false
                            )
                        )
                    }
                } else if (parsed.dstPort == 853) {
                    metadataNote = "Encrypted DNS-over-TLS (DoT)"
                    repo.insertDnsLog(
                        DNSLog(
                            queryName = "Encrypted DNS-over-TLS (${parsed.dstIp}:853)",
                            queryType = "DoT",
                            dnsServer = parsed.dstIp,
                            responseIps = "",
                            latencyMs = -1L,
                            protocolClass = "DOT_ENCRYPTED",
                            isEncrypted = true
                        )
                    )
                } else if (parsed.protocol == "TCP" && parsed.payloadLength > 43) {
                    val tls = PacketParser.parseTlsClientHello(buffer, parsed.payloadOffset, parsed.payloadLength)
                    if (tls != null) {
                        val sniDisplay = tls.sniHostname ?: if (tls.hasEchExtension) "[ECH Encrypted SNI]" else "[SNI Unavailable]"
                        metadataNote = "${tls.tlsVersion} SNI=$sniDisplay"
                        repo.insertTlsLog(
                            TLSLog(
                                sniHostname = sniDisplay,
                                dstIp = parsed.dstIp,
                                dstPort = parsed.dstPort,
                                tlsVersion = tls.tlsVersion,
                                alpnProtocols = tls.alpnProtocols.joinToString(", "),
                                isEchOrUnavailable = tls.hasEchExtension || tls.sniHostname == null
                            )
                        )
                    }
                } else if (isBlocked) {
                    metadataNote = "Filtered locally (User opt-in UDP:443 diagnostic filter)"
                }

                packetBatch.add(
                    PacketLog(
                        protocol = parsed.protocol,
                        srcIp = parsed.srcIp,
                        srcPort = parsed.srcPort,
                        dstIp = parsed.dstIp,
                        dstPort = parsed.dstPort,
                        packetSize = parsed.packetSize,
                        direction = "OUTBOUND",
                        appPackage = targetAppPackage,
                        isBlocked = isBlocked,
                        metadata = metadataNote
                    )
                )

                _liveState.update { curr ->
                    curr.copy(
                        totalPackets = curr.totalPackets + 1,
                        tcpPackets = curr.tcpPackets + if (parsed.protocol == "TCP") 1 else 0,
                        udpPackets = curr.udpPackets + if (parsed.protocol == "UDP" || parsed.protocol == "QUIC" || parsed.protocol == "DNS") 1 else 0,
                        quicPackets = curr.quicPackets + if (isQuic) 1 else 0,
                        blockedQuicPackets = curr.blockedQuicPackets + if (isBlocked) 1 else 0,
                        capturedBytes = curr.capturedBytes + parsed.packetSize
                    )
                }

                val now = System.currentTimeMillis()
                if (packetBatch.size >= 16 || now - lastFlushMs >= 1000L) {
                    val toSave = ArrayList(packetBatch)
                    packetBatch.clear()
                    lastFlushMs = now
                    repo.insertPackets(toSave)
                }
            }

            if (packetBatch.isNotEmpty()) {
                repo.insertPackets(ArrayList(packetBatch))
                packetBatch.clear()
            }
        }
    }

    private fun relayDnsMetadataQuery(hostname: String, recordType: String): Pair<List<String>, Long> {
        return runCatching {
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 1500
            socket.use { sock ->
                val query = PacketParser.buildDnsQueryPacket(hostname, recordType)
                val server = InetAddress.getByName(customDnsServer)
                val t0 = System.nanoTime()
                sock.send(DatagramPacket(query, query.size, server, 53))
                val respBuf = ByteArray(1024)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPkt)
                val latencyMs = (System.nanoTime() - t0) / 1_000_000L
                val parsed = PacketParser.parseDnsPayload(respBuf, 0, respPkt.length)
                (parsed?.answers ?: emptyList()) to latencyMs
            }
        }.getOrDefault(emptyList<String>() to -1L)
    }

    private fun stopCaptureOnly() {
        captureJob?.cancel()
        captureJob = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun stopVpnInternal() {
        stopCaptureOnly()
        _liveState.update { it.copy(isRunning = false, statusMessage = "Stopped") }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopVpnInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnInternal()
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
        private const val ACTION_STOP = "com.axion.ultimatefetcher.VPN_STOP"
        private const val ACTION_UPDATE_QUIC = "com.axion.ultimatefetcher.VPN_UPDATE_QUIC"
        private const val EXTRA_QUIC_BLOCK = "extra_quic_block"
        private const val EXTRA_TARGET_APP = "extra_target_app"
        private const val EXTRA_DNS_SERVER = "extra_dns_server"

        private val _liveState = MutableStateFlow(VpnLiveState())
        val liveState: StateFlow<VpnLiveState> = _liveState.asStateFlow()

        fun start(context: Context, quicBlocking: Boolean, targetAppPackage: String, dnsServer: String) {
            val intent = Intent(context, VpnMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_QUIC_BLOCK, quicBlocking)
                putExtra(EXTRA_TARGET_APP, targetAppPackage)
                putExtra(EXTRA_DNS_SERVER, dnsServer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VpnMonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
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
                    totalPackets = 0L,
                    tcpPackets = 0L,
                    udpPackets = 0L,
                    quicPackets = 0L,
                    blockedQuicPackets = 0L,
                    capturedBytes = 0L
                )
            }
        }
    }
}
