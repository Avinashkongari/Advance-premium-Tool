package com.axion.ultimatefetcher.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.UserSettings
import com.axion.ultimatefetcher.data.database.entities.NetworkSession
import com.axion.ultimatefetcher.domain.models.LiveNetworkTelemetry
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import com.axion.ultimatefetcher.network.vpn.VpnMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    val telemetry: StateFlow<LiveNetworkTelemetry> = container.telemetryMonitor.telemetry
    val vpnState: StateFlow<VpnLiveState> = VpnMonitorService.liveState
    val settings: StateFlow<UserSettings> = container.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _rootState = MutableStateFlow(RootAvailabilityState())
    val rootState: StateFlow<RootAvailabilityState> = _rootState.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var sessionStartedAt: Long = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            _rootState.value = container.rootDiagnosticsEngine.checkRootAvailabilityPassive()
        }
    }

    fun startVpnMonitoring(context: Context) {
        val s = settings.value
        sessionStartedAt = System.currentTimeMillis()
        VpnMonitorService.start(
            context = context,
            quicBlocking = s.quicBlockingEnabled,
            targetAppPackages = s.selectedAppPackages,
            dnsServer = s.customDnsServer
        )
        postBanner("Configuring local VPN monitor for ${s.selectedAppName}...")
    }

    fun reportVpnPermissionDenied() {
        VpnMonitorService.reportPermissionDenied()
        postBanner("VPN permission denied — local traffic capture cannot start without Android VPN authorization.")
    }

    fun stopVpnMonitoring(context: Context) {
        val t = telemetry.value
        VpnMonitorService.stop(context)
        viewModelScope.launch {
            container.repository.insertSession(
                NetworkSession(
                    startedAt = sessionStartedAt,
                    endedAt = System.currentTimeMillis(),
                    downloadedBytes = t.sessionDownloadedBytes,
                    uploadedBytes = t.sessionUploadedBytes,
                    totalPackets = t.totalPackets,
                    tcpPackets = t.tcpPackets,
                    udpPackets = t.udpPackets,
                    quicBlockedPackets = t.quicBlockedPackets,
                    activeInterface = t.activeInterface
                )
            )
        }
        postBanner("Local VPN monitor stopped and session summary saved.")
    }

    fun clearSession() {
        sessionStartedAt = System.currentTimeMillis()
        container.telemetryMonitor.resetSessionBaseline()
        postBanner("Session counters and live chart cleared.")
    }

    fun buildReportShareUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val reportJson = container.repository.buildSanitizedJsonReport(telemetry.value)
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_report_${System.currentTimeMillis()}.json",
                reportJson
            )
            onReady(uri)
        }
    }

    fun fetchPublicIpWithConsent() {
        viewModelScope.launch {
            container.settingsDataStore.setPublicIpConsentGranted(true)
            container.telemetryMonitor.fetchPublicIpWithConsent()
        }
    }

    fun revokePublicIp() {
        viewModelScope.launch {
            container.settingsDataStore.setPublicIpConsentGranted(false)
            container.telemetryMonitor.clearPublicIp()
        }
    }

    fun toggleRootMode(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setRootModeEnabled(enabled)
            postBanner(if (enabled) "Read-only Root Mode enabled." else "Root Mode disabled.")
        }
    }

    private fun postBanner(message: String) {
        viewModelScope.launch {
            _statusBanner.value = message
            delay(4000L)
            if (_statusBanner.value == message) {
                _statusBanner.value = null
            }
        }
    }
}
