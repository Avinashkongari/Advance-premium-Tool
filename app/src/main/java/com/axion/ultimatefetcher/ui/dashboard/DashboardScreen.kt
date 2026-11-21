package com.axion.ultimatefetcher.ui.dashboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.CyberSectionHeader
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.components.LivePulseIndicator
import com.axion.ultimatefetcher.ui.components.LiveTrafficChart
import com.axion.ultimatefetcher.ui.components.MetricTile
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val banner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnMonitoring(context)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnMonitoring(context)
        }
    }

    fun launchVpnStartFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.startVpnMonitoring(context)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Hero Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "UltimateFetcher",
                            style = MaterialTheme.typography.headlineMedium,
                            color = cs.onBackground
                        )
                        Text(
                            text = "Live Telemetry • Local-First Security Suite",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    LivePulseIndicator(
                        isActive = vpnState.isRunning || telemetry.isConnected,
                        activeLabel = if (vpnState.isRunning) "VPN LIVE" else "ONLINE",
                        inactiveLabel = "OFFLINE"
                    )
                }
            }

            // Transient Feedback Banner
            item {
                AnimatedVisibility(visible = banner != null) {
                    banner?.let { msg ->
                        InfoCalloutBanner(
                            title = "Monitor Status",
                            message = msg,
                            accentColor = cyber.cyan
                        )
                    }
                }
            }

            // Primary Action Controls
            item {
                CyberGlassCard(accentGlow = if (vpnState.isRunning) cyber.success else cs.primary) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { launchVpnStartFlow() },
                            enabled = !vpnState.isRunning,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dashboard_start_monitoring"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = cs.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start Monitoring", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Monitor")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.stopVpnMonitoring(context) },
                            enabled = vpnState.isRunning,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dashboard_stop_monitoring"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = cs.error.copy(alpha = 0.2f),
                                contentColor = cs.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop Monitoring", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop Monitor")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearSession() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dashboard_clear_session"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Session", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear Session")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.buildReportShareUri { uri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "UltimateFetcher Sanitized Report")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Export Sanitized Report"))
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dashboard_export_report"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export Report", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export Report")
                        }
                    }
                }
            }

            // Live Throughput Chart
            item {
                CyberGlassCard(accentGlow = cyber.cyan) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Real-Time Throughput", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Measured via Android TrafficStats (1s interval)",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (telemetry.latencyMs >= 0) "${telemetry.latencyMs} ms" else "N/A",
                            style = MaterialTheme.typography.labelMedium,
                            color = cyber.success,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LiveTrafficChart(samples = telemetry.trafficHistory)
                }
            }

            // Download / Upload Speed Tiles
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "Download Speed",
                        value = FormatUtils.formatSpeed(telemetry.downloadSpeedBps),
                        subtitle = "Session: ${FormatUtils.formatBytes(telemetry.sessionDownloadedBytes)}",
                        icon = Icons.Default.ArrowDownward,
                        accentColor = cyber.cyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "Upload Speed",
                        value = FormatUtils.formatSpeed(telemetry.uploadSpeedBps),
                        subtitle = "Session: ${FormatUtils.formatBytes(telemetry.sessionUploadedBytes)}",
                        icon = Icons.Default.ArrowUpward,
                        accentColor = cs.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Network Interface + Latency Tiles
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "Network Status",
                        value = telemetry.networkType,
                        subtitle = "IF: ${telemetry.activeInterface}",
                        icon = Icons.Default.Wifi,
                        accentColor = if (telemetry.isConnected) cyber.success else cs.error,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "TCP Latency",
                        value = if (telemetry.latencyMs >= 0) "${telemetry.latencyMs} ms" else "Offline",
                        subtitle = "Endpoints: ${telemetry.activeConnectionsCount}",
                        icon = Icons.Default.Speed,
                        accentColor = cyber.warning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Packet Counters & TCP vs UDP Split
            item {
                CyberGlassCard {
                    CyberSectionHeader(
                        title = "Packet Telemetry",
                        subtitle = if (vpnState.isRunning) vpnState.statusMessage else "Start Local VPN Monitor to inspect live packet metadata"
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PacketStatChip("TOTAL", telemetry.totalPackets.toString(), cs.onSurface, Modifier.weight(1f))
                        PacketStatChip("TCP", telemetry.tcpPackets.toString(), cs.primary, Modifier.weight(1f))
                        PacketStatChip("UDP", telemetry.udpPackets.toString(), cyber.cyan, Modifier.weight(1f))
                        PacketStatChip("QUIC BLK", telemetry.quicBlockedPackets.toString(), cyber.warning, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    val totalProto = (telemetry.tcpPackets + telemetry.udpPackets).coerceAtLeast(1L)
                    val tcpRatio = (telemetry.tcpPackets.toFloat() / totalProto.toFloat()).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "TCP ${(tcpRatio * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary
                        )
                        Text(
                            "UDP ${((1f - tcpRatio) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = cyber.cyan
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (telemetry.tcpPackets + telemetry.udpPackets == 0L) 0.5f else tcpRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = cs.primary,
                        trackColor = cyber.cyan.copy(alpha = 0.45f)
                    )
                }
            }

            // Interface, DNS & VPN Details Card
            item {
                CyberGlassCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lan, contentDescription = "Interface Details", tint = cyber.cyan, modifier = Modifier.size(19.dp))
                        Text("Link & DNS Details", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Active Interface", telemetry.activeInterface)
                    KeyValueRow("Transport", telemetry.networkType)
                    KeyValueRow(
                        "DNS Servers",
                        telemetry.dnsServers.ifEmpty { listOf("Unavailable") }.joinToString(", ")
                    )
                    KeyValueRow("DNS Privacy", telemetry.privateDnsMode)
                    KeyValueRow(
                        "Local VPN Monitor",
                        if (vpnState.isRunning) "Active (TUN 10.215.173.1)" else if (telemetry.vpnSystemActive) "External VPN Active" else "Inactive",
                        valueColor = if (vpnState.isRunning) cyber.success else cs.onSurfaceVariant
                    )
                    KeyValueRow("App Filter Scope", settings.selectedAppName)
                }
            }

            // Consented Public IP Card
            item {
                CyberGlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Public, contentDescription = "Public IP", tint = cyber.violet, modifier = Modifier.size(19.dp))
                            Column {
                                Text("Public IP Address", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Opt-in lookup via api.ipify.org only with consent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        telemetry.publicIpLoading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        telemetry.publicIp != null -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = telemetry.publicIp.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = cyber.cyan
                                )
                                TextButton(onClick = { viewModel.revokePublicIp() }) {
                                    Text("Hide")
                                }
                            }
                        }
                        else -> {
                            if (telemetry.publicIpError != null) {
                                Text(
                                    text = "Lookup error: ${telemetry.publicIpError}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.error
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            OutlinedButton(
                                onClick = { viewModel.fetchPublicIpWithConsent() },
                                modifier = Modifier.testTag("dashboard_fetch_public_ip"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Consent & Fetch Public IP")
                            }
                        }
                    }
                }
            }

            // Root Mode Quick Card
            item {
                CyberGlassCard(
                    accentGlow = if (rootState.isRootDetected) cyber.warning else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Root Status",
                                tint = if (rootState.isRootDetected) cyber.warning else cs.onSurfaceVariant
                            )
                            Column {
                                Text("Root Mode (Read-Only)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = rootState.statusLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rootState.isRootDetected) cyber.warning else cs.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.rootModeEnabled && rootState.isRootDetected,
                            onCheckedChange = { viewModel.toggleRootMode(it) },
                            enabled = rootState.isRootDetected,
                            modifier = Modifier.testTag("dashboard_root_toggle")
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PacketStatChip(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cs.background.copy(alpha = 0.5f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
            color = accent,
            fontWeight = FontWeight.Bold
        )
    }
}
