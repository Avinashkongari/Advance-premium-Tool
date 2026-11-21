package com.axion.ultimatefetcher.ui.fetchers

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.EmptyStateCard
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.components.LivePulseIndicator
import com.axion.ultimatefetcher.ui.components.ProtocolBadge
import com.axion.ultimatefetcher.ui.components.VpnDiagnosticsPanel
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils
import java.util.Locale

@Composable
fun VpnTrafficMonitorTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val packets by viewModel.recentPackets.collectAsStateWithLifecycle()
    val cyber = LocalCyberColors.current

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn(context)
        } else {
            viewModel.reportVpnPermissionDenied()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "Local Non-Root Android VpnService (IPv4 + IPv6)",
                message = "Creates a local TUN interface on-device to capture packet metadata, relay UDP:53 DNS queries asynchronously, and persist logs to Room in non-blocking batches. Traffic is NEVER uploaded to external servers.",
                accentColor = cyber.cyan
            )
        }
        item {
            CyberGlassCard(accentGlow = if (vpnState.isActivelyCapturing) cyber.success else if (vpnState.lastError != null) MaterialTheme.colorScheme.error else null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Capture Controller", style = MaterialTheme.typography.titleMedium)
                    val badgeText = when {
                        vpnState.isActivelyCapturing -> "CAPTURING"
                        vpnState.vpnInterfaceEstablished -> "WAITING (0 PKTS)"
                        vpnState.lastError != null -> "ERROR"
                        else -> "STOPPED"
                    }
                    LivePulseIndicator(vpnState.isActivelyCapturing, badgeText, badgeText)
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Packets Read / Parsed / Saved", "${vpnState.packetsRead} / ${vpnState.packetsParsed} / ${vpnState.packetsSavedToDb}")
                KeyValueRow("TCP / UDP / DNS", "${vpnState.tcpPackets} / ${vpnState.udpPackets} / ${vpnState.dnsPackets}")
                KeyValueRow("Observed Volume", FormatUtils.formatBytes(vpnState.capturedBytes))
                KeyValueRow("Active Scope", vpnState.selectedAppsSummary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val prep = VpnService.prepare(context)
                            if (prep != null) vpnLauncher.launch(prep) else viewModel.startVpn(context)
                        },
                        enabled = !vpnState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start VPN")
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopVpn(context) },
                        enabled = vpnState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop VPN")
                    }
                }
            }
        }
        item {
            VpnDiagnosticsPanel(vpnState = vpnState)
        }
        if (packets.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.VpnKey, "No Packets Recorded", "Start the local VPN monitor to observe live packet metadata.")
            }
        } else {
            items(packets.take(80), key = { it.id }) { pkt ->
                CyberGlassCard(cornerRadius = 14.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolBadge(pkt.protocol, pkt.isBlocked)
                        Text(FormatUtils.formatTime(pkt.timestamp), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${pkt.srcIp}:${pkt.srcPort} → ${pkt.dstIp}:${pkt.dstPort}", style = MaterialTheme.typography.labelMedium)
                    Text("${pkt.packetSize} B • ${pkt.metadata.ifBlank { FormatUtils.classifyPort(pkt.dstPort, pkt.protocol) }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DnsQueryMonitorTool(viewModel: FetchersViewModel) {
    val logs by viewModel.dnsLogs.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var filterProto by remember { mutableStateOf("ALL") }
    val cyber = LocalCyberColors.current

    val filtered = remember(logs, search, filterProto) {
        logs.filter {
            (filterProto == "ALL" || it.protocolClass.contains(filterProto, true)) &&
                (search.isBlank() || it.queryName.contains(search, true) || it.responseIps.contains(search, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "Encrypted DNS (DoH / DoT) Transparency",
                message = "Plaintext UDP/TCP port 53 queries are decoded via RFC 1035. Encrypted DNS-over-TLS (port 853) or DNS-over-HTTPS (DoH) traffic is transparently marked as Encrypted — never falsely decoded.",
                accentColor = cyber.success
            )
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search domain or resolved IP...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("ALL", "UDP_53", "DOT_ENCRYPTED", "SYSTEM_RESOLVER").forEach { p ->
                    FilterChip(selected = filterProto == p, onClick = { filterProto = p }, label = { Text(p) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.Dns, "No DNS Queries Observed", "Start VPN monitoring or execute a test lookup in DNS Resolver Tester.")
            }
        } else {
            items(filtered, key = { it.id }) { d ->
                CyberGlassCard(cornerRadius = 14.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolBadge(if (d.isEncrypted) "TLS" else "DNS")
                        Text(
                            if (d.latencyMs >= 0) "${d.latencyMs} ms • ${FormatUtils.formatTime(d.timestamp)}" else FormatUtils.formatTime(d.timestamp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${d.queryType}  ${d.queryName}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    KeyValueRow("DNS Server", d.dnsServer)
                    KeyValueRow("Protocol", d.protocolClass)
                    KeyValueRow("Response", d.responseIps.ifBlank { if (d.isEncrypted) "Encrypted / Unavailable" else d.errorMessage.ifBlank { "Pending" } })
                }
            }
        }
    }
}

@Composable
fun AppTrafficFilterTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val loading by viewModel.appsLoading.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var multiSelectMode by remember { mutableStateOf(true) }
    val cyber = LocalCyberColors.current

    LaunchedEffect(Unit) { viewModel.loadLaunchableApps(forceRefresh = true) }

    val selectedSet = remember(settings.selectedAppPackages) { settings.selectedAppPackages.toSet() }

    val filtered = remember(apps, search) {
        apps.filter {
            search.isBlank() ||
                it.appName.contains(search, true) ||
                it.packageName.contains(search, true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "Android Per-App VPN Filtering (Single or Multi-App)",
                message = "Applies VpnService.Builder.addAllowedApplication() for every selected package. Persisted via DataStore and automatically restarts active VPN monitoring whenever your selection changes.",
                accentColor = cyber.cyan
            )
        }
        item {
            CyberGlassCard(accentGlow = if (selectedSet.isNotEmpty()) cyber.cyan else null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Selected Apps (${selectedSet.size})", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.loadLaunchableApps(forceRefresh = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Installed Apps", tint = cyber.cyan)
                    }
                }
                KeyValueRow("Active Filter Scope", settings.selectedAppName)
                KeyValueRow(
                    "Packages",
                    if (settings.selectedAppPackages.isEmpty()) "All Launchable Apps"
                    else settings.selectedAppPackages.joinToString(", ")
                )
                if (vpnState.isRunning) {
                    Text(
                        "VPN is running — changing app selection automatically reconfigures allowed apps.",
                        style = MaterialTheme.typography.labelSmall,
                        color = cyber.success
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = multiSelectMode,
                        onClick = { multiSelectMode = true },
                        label = { Text("Multi-App") }
                    )
                    FilterChip(
                        selected = !multiSelectMode,
                        onClick = { multiSelectMode = false },
                        label = { Text("Single-App") }
                    )
                    if (selectedSet.isNotEmpty()) {
                        OutlinedButton(onClick = { viewModel.clearSelectedApps(context) }) {
                            Text("Clear (All)")
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search installed launchable apps...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } else if (filtered.isEmpty()) {
            item { EmptyStateCard(Icons.Default.Apps, "No Matching Apps", "Try a different application name or package filter.") }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                val isSelected = app.packageName in selectedSet
                val bitmap = remember(app.packageName) {
                    runCatching { app.icon?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull()
                }
                val onPick = {
                    if (multiSelectMode) {
                        viewModel.toggleAppSelection(context, app.packageName, app.appName)
                    } else {
                        if (isSelected) viewModel.clearSelectedApps(context)
                        else viewModel.selectSingleApp(context, app.packageName, app.appName)
                    }
                }
                CyberGlassCard(
                    onClick = onPick,
                    accentGlow = if (isSelected) cyber.success else null,
                    cornerRadius = 14.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(36.dp))
                        } else {
                            Icon(Icons.Default.Apps, contentDescription = app.appName, modifier = Modifier.size(36.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (multiSelectMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onPick() }
                            )
                        } else if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = cyber.success)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuicDiagnosticTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val quicPackets by viewModel.quicPackets.collectAsStateWithLifecycle()
    val blockedTotal by viewModel.blockedQuicCount.collectAsStateWithLifecycle()
    val cyber = LocalCyberColors.current

    val distinctIps = remember(quicPackets) {
        quicPackets.groupBy { it.dstIp }.map { (ip, pkts) -> ip to pkts.size }.sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "Explicit Opt-In UDP:443 (QUIC) Filtering",
                message = "QUIC/HTTP3 uses UDP port 443. UltimateFetcher NEVER silently drops traffic. Enable the switch below only if you explicitly want the local VPN service to filter UDP:443 packets for diagnostic testing.",
                accentColor = cyber.warning
            )
        }
        item {
            CyberGlassCard(accentGlow = if (settings.quicBlockingEnabled) cyber.warning else null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Filter UDP Port 443 (QUIC)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                settings.quicBlockingEnabled && vpnState.isRunning -> "LIVE: Filtering UDP:443 via Local VPN"
                                settings.quicBlockingEnabled -> "Staged — start Local VPN to enforce UDP:443 filtering"
                                else -> "Observation Only (No packets dropped)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.quicBlockingEnabled && vpnState.isRunning) cyber.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.quicBlockingEnabled,
                        onCheckedChange = { viewModel.setQuicBlocking(context, it) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Live QUIC Packets", vpnState.quicPackets.toString())
                KeyValueRow("Blocked This Session", vpnState.blockedQuicPackets.toString(), valueColor = cyber.warning)
                KeyValueRow("Total Blocked (DB)", blockedTotal.toString())
            }
        }
        if (distinctIps.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.Block, "No QUIC (UDP:443) Traffic Logged", "Start Local VPN monitoring to detect UDP port 443 destination endpoints.")
            }
        } else {
            items(distinctIps, key = { it.first }) { (ip, count) ->
                CyberGlassCard(cornerRadius = 14.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(ip, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("UDP Port 443 • QUIC / HTTP3 Candidate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProtocolBadge("QUIC ($count)")
                    }
                }
            }
        }
    }
}

@Composable
fun TlsDomainExtractorTool(viewModel: FetchersViewModel) {
    val tlsLogs by viewModel.tlsLogs.collectAsStateWithLifecycle()
    val cyber = LocalCyberColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "TLS ClientHello & ECH Limitations",
                message = "Parses unencrypted TLS ClientHello SNI, TLS version, and ALPN extensions locally. When Encrypted ClientHello (ECH) or non-standard framing hides SNI, UltimateFetcher explicitly marks metadata as unavailable rather than guessing.",
                accentColor = cyber.violet
            )
        }
        if (tlsLogs.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.Lock, "No TLS Handshakes Observed", "Start Local VPN monitoring or run SSL Certificate Inspector to record TLS observations.")
            }
        } else {
            items(tlsLogs, key = { it.id }) { tls ->
                CyberGlassCard(cornerRadius = 14.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolBadge(tls.tlsVersion)
                        Text(FormatUtils.formatTime(tls.timestamp), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(tls.sniHostname, style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMonoFamily)
                    KeyValueRow("Endpoint", "${tls.dstIp}:${tls.dstPort}")
                    KeyValueRow("ALPN", tls.alpnProtocols.ifBlank { "None advertised" })
                    KeyValueRow("ECH / Encrypted", if (tls.isEchOrUnavailable) "Metadata encrypted / unavailable" else "Cleartext ClientHello SNI")
                }
            }
        }
    }
}

@Composable
fun ConnectionAnalyzerTool(viewModel: FetchersViewModel) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    var protoFilter by remember { mutableStateOf("ALL") }

    val filtered = remember(connections, protoFilter) {
        connections.filter { protoFilter == "ALL" || it.protocol.equals(protoFilter, true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "TCP", "UDP", "QUIC", "DNS").forEach { p ->
                    FilterChip(selected = protoFilter == p, onClick = { protoFilter = p }, label = { Text(p) })
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyStateCard(Icons.Default.Hub, "No Observed Connections", "Start Local VPN Monitoring to group active remote endpoints and traffic volume.")
            }
        } else {
            items(filtered, key = { "${it.protocol}_${it.remoteIp}_${it.remotePort}" }) { c ->
                val durSec = ((c.lastSeen - c.firstSeen).coerceAtLeast(0L)) / 1000.0
                CyberGlassCard(cornerRadius = 14.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProtocolBadge(c.protocol, c.isBlocked)
                        Text(c.serviceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${c.remoteIp}:${c.remotePort}", style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMonoFamily)
                    KeyValueRow("Volume", "${FormatUtils.formatBytes(c.totalBytes)} (${c.packetCount} pkts)")
                    KeyValueRow("Observed Duration", String.format(Locale.US, "%.1f s", durSec))
                }
            }
        }
    }
}

@Composable
fun DnsResolverTesterTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val history by viewModel.dnsTestHistory.collectAsStateWithLifecycle()
    val testing by viewModel.dnsTesting.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cyber = LocalCyberColors.current

    var hostname by remember { mutableStateOf("example.com") }
    var recordType by remember { mutableStateOf("A") }
    var dnsServer by remember(settings.customDnsServer) { mutableStateOf(settings.customDnsServer) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCalloutBanner(
                title = "Direct UDP:53 Resolver & DoH/DoT Transparency",
                message = "Sends RFC 1035 queries over UDP:53 (IPv4 or IPv6) with transaction-ID validation and RCODE decoding. Selecting System uses Android's active resolver (including DoT/Private DNS when configured).",
                accentColor = cyber.success
            )
        }
        item {
            CyberGlassCard {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("Hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("A", "AAAA", "MX", "TXT", "CNAME", "NS", "HTTPS").forEach { rt ->
                        FilterChip(selected = recordType == rt, onClick = { recordType = rt }, label = { Text(rt) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "2606:4700:4700::1111", "System").forEach { srv ->
                        FilterChip(selected = dnsServer == srv, onClick = { dnsServer = srv }, label = { Text(srv) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.runDnsLookup(hostname, recordType, dnsServer) },
                        enabled = !testing,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Resolve $recordType Record")
                    }
                    if (history.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                viewModel.exportDnsHistoryShareUri { uri ->
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Export DNS Results"))
                                }
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export DNS Results", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        itemsIndexed(history, key = { idx, it -> "${it.timestamp}_${it.hostname}_${it.recordType}_$idx" }) { _, res ->
            CyberGlassCard(cornerRadius = 14.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${res.recordType} • ${res.hostname}", style = MaterialTheme.typography.titleSmall, fontFamily = JetBrainsMonoFamily)
                    Text(if (res.latencyMs >= 0) "${res.latencyMs} ms" else "ERR", style = MaterialTheme.typography.labelMedium, color = LocalCyberColors.current.cyan)
                }
                KeyValueRow("Server", "${res.dnsServer} (${res.protocolUsed})")
                if (res.records.isNotEmpty()) {
                    KeyValueRow("Answers", res.records.joinToString("\n"))
                }
                res.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun NetworkQualityTesterTool(viewModel: FetchersViewModel) {
    val report by viewModel.qualityReport.collectAsStateWithLifecycle()
    val progress by viewModel.qualityProgress.collectAsStateWithLifecycle()
    var probeHost by remember { mutableStateOf("1.1.1.1") }
    val cyber = LocalCyberColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            CyberGlassCard {
                OutlinedTextField(
                    value = probeHost,
                    onValueChange = { probeHost = it },
                    label = { Text("Latency Probe Host (TCP:443)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.startNetworkQualityTest(probeHost) },
                        enabled = progress == null,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Run Quality Test") }
                    if (progress != null) {
                        OutlinedButton(onClick = { viewModel.cancelNetworkQualityTest() }, modifier = Modifier.height(48.dp)) {
                            Text("Cancel")
                        }
                    }
                }
                progress?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cyber.cyan)
                }
            }
        }
        report?.let { r ->
            item {
                CyberGlassCard(accentGlow = if (r.error == null) cyber.success else MaterialTheme.colorScheme.error) {
                    Text("Connection Stability: ${r.stabilityGrade}", style = MaterialTheme.typography.titleLarge, color = cyber.success)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Latency (Min / Avg / Max)", String.format(Locale.US, "%.1f / %.1f / %.1f ms", r.latencyMinMs, r.latencyAvgMs, r.latencyMaxMs))
                    KeyValueRow("Jitter", String.format(Locale.US, "%.2f ms", r.jitterMs))
                    KeyValueRow("Probe Loss", String.format(Locale.US, "%.1f%%", r.packetLossPercent))
                    KeyValueRow("Download Speed", String.format(Locale.US, "%.2f Mbps", r.downloadMbps), valueColor = cyber.cyan)
                    KeyValueRow("Upload Speed", String.format(Locale.US, "%.2f Mbps", r.uploadMbps), valueColor = MaterialTheme.colorScheme.primary)
                    r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                InfoCalloutBanner("Test Server Disclosure", r.testServerDisclosure, cyber.cyan)
            }
        }
    }
}
