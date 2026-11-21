package com.axion.ultimatefetcher.network.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import com.axion.ultimatefetcher.domain.models.LiveNetworkTelemetry
import com.axion.ultimatefetcher.domain.models.TrafficSample
import com.axion.ultimatefetcher.network.vpn.VpnMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket

class LiveNetworkTelemetryMonitor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _telemetry = MutableStateFlow(LiveNetworkTelemetry())
    val telemetry: StateFlow<LiveNetworkTelemetry> = _telemetry.asStateFlow()

    private var pollingJob: Job? = null
    private var baselineRx = safeTrafficStat(TrafficStats.getTotalRxBytes())
    private var baselineTx = safeTrafficStat(TrafficStats.getTotalTxBytes())
    private var lastRx = baselineRx
    private var lastTx = baselineTx
    private var lastTimestamp = System.currentTimeMillis()
    private var tickCount = 0

    fun start() {
        if (pollingJob?.isActive == true) return
        baselineRx = safeTrafficStat(TrafficStats.getTotalRxBytes())
        baselineTx = safeTrafficStat(TrafficStats.getTotalTxBytes())
        lastRx = baselineRx
        lastTx = baselineTx
        lastTimestamp = System.currentTimeMillis()

        pollingJob = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(1000L)
            }
        }
    }

    fun resetSessionBaseline() {
        baselineRx = safeTrafficStat(TrafficStats.getTotalRxBytes())
        baselineTx = safeTrafficStat(TrafficStats.getTotalTxBytes())
        lastRx = baselineRx
        lastTx = baselineTx
        lastTimestamp = System.currentTimeMillis()
        VpnMonitorService.resetSessionCounters()
        _telemetry.update {
            it.copy(
                sessionDownloadedBytes = 0L,
                sessionUploadedBytes = 0L,
                totalPackets = 0L,
                tcpPackets = 0L,
                udpPackets = 0L,
                quicBlockedPackets = 0L,
                trafficHistory = emptyList()
            )
        }
    }

    suspend fun fetchPublicIpWithConsent() = withContext(Dispatchers.IO) {
        _telemetry.update { it.copy(publicIpLoading = true, publicIpError = null) }
        val result = runCatching {
            val req = Request.Builder().url("https://api.ipify.org?format=text").get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() } ?: error("Empty response")
            }
        }
        result.fold(
            onSuccess = { ip ->
                _telemetry.update { it.copy(publicIp = ip, publicIpLoading = false, publicIpError = null) }
            },
            onFailure = { err ->
                _telemetry.update { it.copy(publicIpLoading = false, publicIpError = err.message ?: "Lookup failed") }
            }
        )
    }

    fun clearPublicIp() {
        _telemetry.update { it.copy(publicIp = null, publicIpLoading = false, publicIpError = null) }
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        val curRx = safeTrafficStat(TrafficStats.getTotalRxBytes())
        val curTx = safeTrafficStat(TrafficStats.getTotalTxBytes())
        val dtSec = ((now - lastTimestamp).coerceAtLeast(100L)) / 1000.0

        val downBps = ((curRx - lastRx).coerceAtLeast(0L) / dtSec).toLong()
        val upBps = ((curTx - lastTx).coerceAtLeast(0L) / dtSec).toLong()
        lastRx = curRx
        lastTx = curTx
        lastTimestamp = now

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
        val linkProps = activeNet?.let { cm.getLinkProperties(it) }

        val isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val networkType = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
        val iface = linkProps?.interfaceName ?: "Unavailable"
        val dnsList = linkProps?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        val privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && linkProps != null) {
            when {
                linkProps.isPrivateDnsActive && !linkProps.privateDnsServerName.isNullOrBlank() ->
                    "Private DNS (${linkProps.privateDnsServerName})"
                linkProps.isPrivateDnsActive -> "Automatic Private DNS (DoT)"
                else -> "Standard DNS"
            }
        } else {
            "Standard DNS"
        }
        val systemVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        tickCount++
        val measuredLatency = if (isConnected && (tickCount == 1 || tickCount % 5 == 0)) {
            measureTcpLatencyMs("1.1.1.1", 443)
        } else {
            _telemetry.value.latencyMs
        }

        val vpn = VpnMonitorService.liveState.value
        val sample = TrafficSample(timestamp = now, downloadBps = downBps, uploadBps = upBps)

        _telemetry.update { prev ->
            prev.copy(
                downloadSpeedBps = downBps,
                uploadSpeedBps = upBps,
                sessionDownloadedBytes = (curRx - baselineRx).coerceAtLeast(0L),
                sessionUploadedBytes = (curTx - baselineTx).coerceAtLeast(0L),
                activeInterface = iface,
                networkType = networkType,
                isConnected = isConnected,
                dnsServers = dnsList,
                privateDnsMode = privateDns,
                vpnSystemActive = systemVpn,
                latencyMs = if (isConnected) measuredLatency else -1L,
                activeConnectionsCount = if (vpn.isRunning) (vpn.totalPackets.coerceAtMost(999L)).toInt() else 0,
                totalPackets = vpn.packetsParsed,
                tcpPackets = vpn.tcpPackets,
                udpPackets = vpn.udpPackets,
                dnsPackets = vpn.dnsPackets,
                quicBlockedPackets = vpn.blockedQuicPackets,
                packetsSavedToDb = vpn.packetsSavedToDb,
                trafficHistory = (prev.trafficHistory + sample).takeLast(36)
            )
        }
    }

    private fun measureTcpLatencyMs(host: String, port: Int): Long {
        return runCatching {
            val t0 = System.nanoTime()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1800)
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrDefault(-1L)
    }

    private fun safeTrafficStat(raw: Long): Long = if (raw == TrafficStats.UNSUPPORTED.toLong() || raw < 0L) 0L else raw
}
