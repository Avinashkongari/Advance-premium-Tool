package com.axion.ultimatefetcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ultimateFetcherDataStore by preferencesDataStore(name = "ultimate_fetcher_settings")

enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

data class UserSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val rootModeEnabled: Boolean = false,
    val customDnsServer: String = "1.1.1.1",
    val defaultTimeoutSeconds: Int = 20,
    val logRetentionDays: Int = 7,
    val autoClearLogs: Boolean = true,
    val exportSanitizedOnly: Boolean = true,
    val publicIpConsentGranted: Boolean = false,
    val quicBlockingEnabled: Boolean = false,
    val selectedAppPackage: String = "",
    val selectedAppName: String = "All Launchable Apps",
    val ownAppDebugEnabled: Boolean = false,
    val healthMonitorNotifications: Boolean = true
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ROOT_MODE_ENABLED = booleanPreferencesKey("root_mode_enabled")
        val CUSTOM_DNS_SERVER = stringPreferencesKey("custom_dns_server")
        val DEFAULT_TIMEOUT_SEC = intPreferencesKey("default_timeout_sec")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val AUTO_CLEAR_LOGS = booleanPreferencesKey("auto_clear_logs")
        val EXPORT_SANITIZED_ONLY = booleanPreferencesKey("export_sanitized_only")
        val PUBLIC_IP_CONSENT = booleanPreferencesKey("public_ip_consent")
        val QUIC_BLOCKING_ENABLED = booleanPreferencesKey("quic_blocking_enabled")
        val SELECTED_APP_PACKAGE = stringPreferencesKey("selected_app_package")
        val SELECTED_APP_NAME = stringPreferencesKey("selected_app_name")
        val OWN_APP_DEBUG_ENABLED = booleanPreferencesKey("own_app_debug_enabled")
        val HEALTH_MONITOR_NOTIFICATIONS = booleanPreferencesKey("health_monitor_notifications")
    }

    val settingsFlow: Flow<UserSettings> = context.ultimateFetcherDataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: AppThemeMode.DARK.name
        val themeMode = runCatching { AppThemeMode.valueOf(themeStr) }.getOrDefault(AppThemeMode.DARK)
        UserSettings(
            themeMode = themeMode,
            rootModeEnabled = prefs[Keys.ROOT_MODE_ENABLED] ?: false,
            customDnsServer = prefs[Keys.CUSTOM_DNS_SERVER] ?: "1.1.1.1",
            defaultTimeoutSeconds = (prefs[Keys.DEFAULT_TIMEOUT_SEC] ?: 20).coerceIn(5, 60),
            logRetentionDays = (prefs[Keys.LOG_RETENTION_DAYS] ?: 7).coerceIn(1, 30),
            autoClearLogs = prefs[Keys.AUTO_CLEAR_LOGS] ?: true,
            exportSanitizedOnly = prefs[Keys.EXPORT_SANITIZED_ONLY] ?: true,
            publicIpConsentGranted = prefs[Keys.PUBLIC_IP_CONSENT] ?: false,
            quicBlockingEnabled = prefs[Keys.QUIC_BLOCKING_ENABLED] ?: false,
            selectedAppPackage = prefs[Keys.SELECTED_APP_PACKAGE] ?: "",
            selectedAppName = prefs[Keys.SELECTED_APP_NAME] ?: "All Launchable Apps",
            ownAppDebugEnabled = prefs[Keys.OWN_APP_DEBUG_ENABLED] ?: false,
            healthMonitorNotifications = prefs[Keys.HEALTH_MONITOR_NOTIFICATIONS] ?: true
        )
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.ultimateFetcherDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setRootModeEnabled(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.ROOT_MODE_ENABLED] = enabled }
    }

    suspend fun setCustomDnsServer(dns: String) {
        val clean = dns.trim().ifEmpty { "1.1.1.1" }
        context.ultimateFetcherDataStore.edit { it[Keys.CUSTOM_DNS_SERVER] = clean }
    }

    suspend fun setDefaultTimeoutSeconds(seconds: Int) {
        context.ultimateFetcherDataStore.edit { it[Keys.DEFAULT_TIMEOUT_SEC] = seconds.coerceIn(5, 60) }
    }

    suspend fun setLogRetentionDays(days: Int) {
        context.ultimateFetcherDataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days.coerceIn(1, 30) }
    }

    suspend fun setAutoClearLogs(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.AUTO_CLEAR_LOGS] = enabled }
    }

    suspend fun setExportSanitizedOnly(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.EXPORT_SANITIZED_ONLY] = enabled }
    }

    suspend fun setPublicIpConsentGranted(granted: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.PUBLIC_IP_CONSENT] = granted }
    }

    suspend fun setQuicBlockingEnabled(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.QUIC_BLOCKING_ENABLED] = enabled }
    }

    suspend fun setSelectedAppFilter(packageName: String, appName: String) {
        context.ultimateFetcherDataStore.edit {
            it[Keys.SELECTED_APP_PACKAGE] = packageName.trim()
            it[Keys.SELECTED_APP_NAME] = appName.trim().ifEmpty { "All Launchable Apps" }
        }
    }

    suspend fun setOwnAppDebugEnabled(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.OWN_APP_DEBUG_ENABLED] = enabled }
    }

    suspend fun setHealthMonitorNotifications(enabled: Boolean) {
        context.ultimateFetcherDataStore.edit { it[Keys.HEALTH_MONITOR_NOTIFICATIONS] = enabled }
    }
}
