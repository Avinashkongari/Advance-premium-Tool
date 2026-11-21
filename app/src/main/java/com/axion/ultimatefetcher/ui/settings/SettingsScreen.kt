package com.axion.ultimatefetcher.ui.settings

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axion.ultimatefetcher.data.AppThemeMode
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.example.BuildConfig

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    var dnsInput by remember(settings.customDnsServer) { mutableStateOf(settings.customDnsServer) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var vpnPermGranted by remember { mutableStateOf(VpnService.prepare(context) == null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        vpnPermGranted = res.resultCode == Activity.RESULT_OK || VpnService.prepare(context) == null
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Appearance, Network Defaults, Root Controls & Local Privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            if (message != null) {
                item {
                    InfoCalloutBanner("Settings Updated", message.orEmpty(), cyber.cyan)
                }
            }

            // Theme Mode
            item {
                CyberGlassCard {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Root Mode
            item {
                CyberGlassCard(accentGlow = if (rootState.isRootDetected) cyber.warning else null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Root Mode (Read-Only Diagnostics)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = rootState.statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rootState.isRootDetected) cyber.warning else cs.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.rootModeEnabled && rootState.isRootDetected,
                            onCheckedChange = { viewModel.setRootMode(it) },
                            enabled = rootState.isRootDetected,
                            modifier = Modifier.testTag("settings_root_toggle")
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = rootState.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            // VPN Permission & Monitoring
            item {
                CyberGlassCard {
                    Text("VPN & Monitoring Preferences", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow(
                        "Android VPN Permission",
                        if (vpnPermGranted) "Granted" else "Not Yet Authorized",
                        valueColor = if (vpnPermGranted) cyber.success else cyber.warning
                    )
                    if (!vpnPermGranted) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                VpnService.prepare(context)?.let { vpnLauncher.launch(it) }
                                    ?: run { vpnPermGranted = true }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Authorize Local VPN Service")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Public IP Lookup Consent",
                        subtitle = "Allow opt-in lookup via api.ipify.org on Dashboard",
                        checked = settings.publicIpConsentGranted,
                        onCheckedChange = { viewModel.setPublicIpConsent(it) }
                    )
                    SettingSwitchRow(
                        title = "Own-App API Debug Interceptor",
                        subtitle = if (BuildConfig.DEBUG) "Log UltimateFetcher's own OkHttp metadata with sensitive headers redacted" else "Disabled automatically in release builds",
                        checked = settings.ownAppDebugEnabled && BuildConfig.DEBUG,
                        enabled = BuildConfig.DEBUG,
                        onCheckedChange = { viewModel.setOwnAppDebugEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "API Health Failure Notifications",
                        subtitle = "Notify locally when a monitored endpoint goes offline",
                        checked = settings.healthMonitorNotifications,
                        onCheckedChange = { viewModel.setHealthMonitorNotifications(it) }
                    )
                }
            }

            // DNS & Timeout Settings
            item {
                CyberGlassCard {
                    Text("DNS & Network Timeouts", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = dnsInput,
                            onValueChange = { dnsInput = it },
                            label = { Text("Default DNS Server") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { viewModel.setCustomDns(dnsInput) }) {
                            Text("Apply")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1.1.1.1", "8.8.8.8", "9.9.9.9").forEach { preset ->
                            FilterChip(
                                selected = settings.customDnsServer == preset,
                                onClick = {
                                    dnsInput = preset
                                    viewModel.setCustomDns(preset)
                                },
                                label = { Text(preset) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Default HTTP Timeout: ${settings.defaultTimeoutSeconds} seconds",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.defaultTimeoutSeconds.toFloat(),
                        onValueChange = { viewModel.setDefaultTimeout(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                }
            }

            // Log Retention, Export & Privacy
            item {
                CyberGlassCard {
                    Text("Retention, Export & Privacy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Log Retention Period: ${settings.logRetentionDays} days",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.logRetentionDays.toFloat(),
                        onValueChange = { viewModel.setLogRetentionDays(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28
                    )
                    SettingSwitchRow(
                        title = "Auto-Clear Logs on Cleanup",
                        subtitle = "Prune expired packet, DNS, TLS, and API logs automatically",
                        checked = settings.autoClearLogs,
                        onCheckedChange = { viewModel.setAutoClearLogs(it) }
                    )
                    SettingSwitchRow(
                        title = "Sanitize Exported Reports",
                        subtitle = "Always redact Authorization, Cookie, and API Key values on export",
                        checked = settings.exportSanitizedOnly,
                        onCheckedChange = { viewModel.setExportSanitizedOnly(it) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Clear All Logs", color = cs.error)
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearSavedCredentials() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Wipe KeyStore Vault")
                        }
                    }
                }
            }

            // Privacy Statement & About UltimateFetcher
            item {
                CyberGlassCard {
                    Text("About UltimateFetcher", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Application", "UltimateFetcher")
                    KeyValueRow("Developer", "Avinash")
                    KeyValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow("Architecture", "Local-First • Zero Telemetry • Room + KeyStore")
                    Spacer(Modifier.height(8.dp))
                    InfoCalloutBanner(
                        title = "Local-First Security Guarantee",
                        message = "All captured packet metadata, DNS/TLS observations, and API history stay strictly on your device. No analytics SDKs, no hidden background uploads, and zero covert interception.",
                        accentColor = cyber.success
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showLicensesDialog = true },
                        modifier = Modifier.testTag("settings_licenses_button")
                    ) {
                        Text("View Open-Source Licenses")
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear All Local Logs?") },
            text = { Text("This permanently deletes all local packet, DNS, TLS, API history, session, and root audit records from Room.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllLocalLogs()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error)
                ) { Text("Delete All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Open-Source Licenses") },
            text = {
                Text(
                    "UltimateFetcher incorporates open-source libraries under Apache License 2.0:\n\n" +
                        "• Jetpack Compose & Material 3 (Google)\n" +
                        "• AndroidX Room, Navigation, DataStore & WorkManager (Google)\n" +
                        "• Kotlin Coroutines (JetBrains)\n" +
                        "• OkHttp & Retrofit (Square, Inc.)\n" +
                        "• Moshi (Square, Inc.)\n" +
                        "• Space Grotesk, Inter & JetBrains Mono Fonts (SIL OFL 1.1)",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
