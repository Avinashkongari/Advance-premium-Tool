package com.axion.ultimatefetcher.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.AppThemeMode
import com.axion.ultimatefetcher.data.UserSettings
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import com.axion.ultimatefetcher.network.vpn.VpnMonitorService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.InetAddress

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<UserSettings> = container.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _rootState = MutableStateFlow(RootAvailabilityState())
    val rootState: StateFlow<RootAvailabilityState> = _rootState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val state = container.rootDiagnosticsEngine.checkRootAvailabilityPassive()
            _rootState.value = state
            if (!state.isRootDetected) {
                container.settingsDataStore.setRootModeEnabled(false)
            }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { container.settingsDataStore.setThemeMode(mode) }
    }

    fun setRootMode(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !_rootState.value.isRootDetected) {
                container.settingsDataStore.setRootModeEnabled(false)
                postMessage("Root is not available on this device. Root Mode remains disabled.")
                return@launch
            }
            container.settingsDataStore.setRootModeEnabled(enabled)
            postMessage(if (enabled) "Read-only Root Mode enabled." else "Root Mode disabled.")
        }
    }

    fun setPublicIpConsent(granted: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setPublicIpConsentGranted(granted)
            if (!granted) container.telemetryMonitor.clearPublicIp()
        }
    }

    fun setOwnAppDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setOwnAppDebugEnabled(enabled)
            container.ownAppDebugInterceptor.setOptInEnabled(enabled)
            postMessage(if (enabled) "Own-App debug logging active (sensitive headers redacted)." else "Own-App debug logging paused.")
        }
    }

    fun setHealthMonitorNotifications(enabled: Boolean) {
        viewModelScope.launch { container.settingsDataStore.setHealthMonitorNotifications(enabled) }
    }

    fun setCustomDns(dns: String, context: Context? = null) {
        val clean = dns.trim()
        if (!isValidDnsIpOrSystem(clean)) {
            postMessage("Invalid DNS server '$clean'. Enter a valid IPv4/IPv6 address (e.g. 1.1.1.1) or System.")
            return
        }
        val normalized = if (clean.equals("System", ignoreCase = true)) "System" else clean
        viewModelScope.launch {
            container.settingsDataStore.setCustomDnsServer(normalized)
            if (context != null) {
                val current = container.settingsDataStore.settingsFlow.first()
                VpnMonitorService.reconfigureIfRunning(
                    context = context,
                    quicBlocking = current.quicBlockingEnabled,
                    targetAppPackages = current.selectedAppPackages,
                    dnsServer = normalized
                )
            }
            postMessage("Default DNS server updated to $normalized.")
        }
    }

    fun setDefaultTimeout(seconds: Int) {
        viewModelScope.launch { container.settingsDataStore.setDefaultTimeoutSeconds(seconds) }
    }

    fun setLogRetentionDays(days: Int) {
        val safeDays = days.coerceIn(1, 30)
        viewModelScope.launch {
            container.settingsDataStore.setLogRetentionDays(safeDays)
            if (container.settingsDataStore.settingsFlow.first().autoClearLogs) {
                container.repository.pruneExpiredLogs(safeDays)
            }
        }
    }

    fun setAutoClearLogs(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setAutoClearLogs(enabled)
            if (enabled) {
                val days = container.settingsDataStore.settingsFlow.first().logRetentionDays
                container.repository.pruneExpiredLogs(days)
                postMessage("Auto-clear enabled; logs older than $days day(s) pruned.")
            }
        }
    }

    fun setExportSanitizedOnly(enabled: Boolean) {
        viewModelScope.launch { container.settingsDataStore.setExportSanitizedOnly(enabled) }
    }

    fun clearAllLocalLogs() {
        viewModelScope.launch {
            container.repository.clearAllLocalLogs()
            postMessage("All local Room logs permanently cleared.")
        }
    }

    fun clearSavedCredentials() {
        container.credentialStore.clearAll()
        postMessage("Android KeyStore credential vault wiped.")
    }

    private fun postMessage(msg: String) {
        viewModelScope.launch {
            _message.value = msg
            delay(3200L)
            if (_message.value == msg) _message.value = null
        }
    }

    companion object {
        fun isValidDnsIpOrSystem(raw: String): Boolean {
            val t = raw.trim()
            if (t.isEmpty()) return false
            if (t.equals("System", ignoreCase = true)) return true
            val isIpv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""").matches(t) &&
                t.split('.').all { it.toIntOrNull() in 0..255 }
            if (isIpv4) return true
            if (t.contains(':')) {
                return runCatching { InetAddress.getByName(t).address.size == 16 }.getOrDefault(false)
            }
            return false
        }
    }
}
