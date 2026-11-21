package com.axion.ultimatefetcher.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.AppThemeMode
import com.axion.ultimatefetcher.data.UserSettings
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<UserSettings> = container.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    private val _rootState = MutableStateFlow(RootAvailabilityState())
    val rootState: StateFlow<RootAvailabilityState> = _rootState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _rootState.value = container.rootDiagnosticsEngine.checkRootAvailabilityPassive()
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { container.settingsDataStore.setThemeMode(mode) }
    }

    fun setRootMode(enabled: Boolean) {
        viewModelScope.launch {
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

    fun setCustomDns(dns: String) {
        viewModelScope.launch {
            container.settingsDataStore.setCustomDnsServer(dns)
            postMessage("Default DNS server updated to ${dns.trim().ifEmpty { "1.1.1.1" }}.")
        }
    }

    fun setDefaultTimeout(seconds: Int) {
        viewModelScope.launch { container.settingsDataStore.setDefaultTimeoutSeconds(seconds) }
    }

    fun setLogRetentionDays(days: Int) {
        viewModelScope.launch { container.settingsDataStore.setLogRetentionDays(days) }
    }

    fun setAutoClearLogs(enabled: Boolean) {
        viewModelScope.launch { container.settingsDataStore.setAutoClearLogs(enabled) }
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
}
