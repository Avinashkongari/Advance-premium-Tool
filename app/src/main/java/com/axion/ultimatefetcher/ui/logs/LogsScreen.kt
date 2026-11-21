package com.axion.ultimatefetcher.ui.logs

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.EmptyStateCard
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.components.LivePulseIndicator
import com.axion.ultimatefetcher.ui.components.MethodBadge
import com.axion.ultimatefetcher.ui.components.ProtocolBadge
import com.axion.ultimatefetcher.ui.components.StatusCodeBadge
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils

@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    modifier: Modifier = Modifier
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val packets by viewModel.packetLogs.collectAsStateWithLifecycle()
    val dnsLogs by viewModel.dnsLogs.collectAsStateWithLifecycle()
    val tlsLogs by viewModel.tlsLogs.collectAsStateWithLifecycle()
    val apiLogs by viewModel.apiLogs.collectAsStateWithLifecycle()
    val rootLogs by viewModel.rootAuditLogs.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    fun shareFile(mime: String, uri: android.net.Uri, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Log Explorer", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Local Room Database • Sanitized Export",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    LivePulseIndicator(
                        isActive = !filter.isPaused,
                        activeLabel = "STREAMING",
                        inactiveLabel = "PAUSED"
                    )
                }
            }

            if (statusMsg != null) {
                item {
                    InfoCalloutBanner("Logs", statusMsg.orEmpty(), cyber.cyan)
                }
            }

            // Category Chips
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = filter.category == cat,
                            onClick = { viewModel.selectCategory(cat) },
                            label = { Text(cat.label) },
                            modifier = Modifier.testTag("logs_cat_${cat.name.lowercase()}")
                        )
                    }
                }
            }

            // Search + Stream Controls
            item {
                CyberGlassCard {
                    OutlinedTextField(
                        value = filter.searchQuery,
                        onValueChange = { viewModel.updateSearch(it) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        placeholder = { Text("Search IP, domain, port, method, URL...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("logs_search_input")
                    )
                    Spacer(Modifier.height(8.dp))

                    // Date Filter & Protocol Filter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DateFilter.entries.forEach { df ->
                            FilterChip(
                                selected = filter.dateFilter == df,
                                onClick = { viewModel.selectDateFilter(df) },
                                label = { Text(df.label) }
                            )
                        }
                    }

                    if (filter.category == LogCategory.PACKETS) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("ALL", "TCP", "UDP", "QUIC", "DNS").forEach { proto ->
                                FilterChip(
                                    selected = filter.protocolFilter == proto,
                                    onClick = { viewModel.selectProtocol(proto) },
                                    label = { Text(proto) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.togglePauseStream() },
                            modifier = Modifier.testTag("logs_pause_toggle"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                if (filter.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pause/Resume",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (filter.isPaused) "Resume" else "Pause")
                        }
                        OutlinedButton(
                            onClick = { viewModel.toggleSortOrder() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Sort", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (filter.ascending) "Oldest" else "Newest")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.exportCsvShareUri { shareFile("text/csv", it, "Export Packets CSV") }
                            },
                            modifier = Modifier.testTag("logs_export_csv"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export CSV", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("CSV")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.exportJsonReportShareUri { shareFile("application/json", it, "Share Sanitized JSON Report") }
                            },
                            modifier = Modifier.testTag("logs_export_json"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export JSON", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("JSON")
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearCurrentCategory() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = cs.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", color = cs.error)
                        }
                    }
                }
            }

            when (filter.category) {
                LogCategory.PACKETS -> {
                    if (packets.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.ReceiptLong,
                                title = "No Packet Logs Yet",
                                description = "Start Local VPN Monitoring on the Dashboard to record live packet metadata."
                            )
                        }
                    } else {
                        items(packets, key = { it.id }) { pkt ->
                            ExpandableLogCard(
                                headerBadge = { ProtocolBadge(pkt.protocol, pkt.isBlocked) },
                                timestamp = FormatUtils.formatTime(pkt.timestamp),
                                headline = "${pkt.srcIp}:${pkt.srcPort} → ${pkt.dstIp}:${pkt.dstPort}",
                                subline = "${pkt.packetSize} B • ${FormatUtils.classifyPort(pkt.dstPort, pkt.protocol)}",
                                copyPayload = "${FormatUtils.formatDateTime(pkt.timestamp)} ${pkt.protocol} ${pkt.srcIp}:${pkt.srcPort} -> ${pkt.dstIp}:${pkt.dstPort} (${pkt.packetSize} B) ${pkt.metadata}",
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onDelete = { viewModel.deletePacket(pkt.id) }
                            ) {
                                KeyValueRow("Timestamp", FormatUtils.formatDateTime(pkt.timestamp))
                                KeyValueRow("Direction", pkt.direction)
                                KeyValueRow("Packet Size", "${pkt.packetSize} bytes")
                                if (pkt.appPackage.isNotBlank()) KeyValueRow("Target App", pkt.appPackage)
                                if (pkt.metadata.isNotBlank()) KeyValueRow("Metadata", pkt.metadata)
                            }
                        }
                        if (packets.size >= filter.pageSize) {
                            item {
                                TextButton(
                                    onClick = { viewModel.loadMore() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Load More Records") }
                            }
                        }
                    }
                }

                LogCategory.DNS -> {
                    if (dnsLogs.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.ReceiptLong,
                                title = "No DNS Logs",
                                description = "DNS queries observed via Local VPN or executed in DNS Resolver Tester appear here."
                            )
                        }
                    } else {
                        items(dnsLogs, key = { it.id }) { d ->
                            ExpandableLogCard(
                                headerBadge = { ProtocolBadge(if (d.isEncrypted) "TLS" else "DNS") },
                                timestamp = FormatUtils.formatTime(d.timestamp),
                                headline = "${d.queryType} ${d.queryName}",
                                subline = "Server ${d.dnsServer} • ${if (d.latencyMs >= 0) "${d.latencyMs}ms" else d.protocolClass}",
                                copyPayload = "${d.queryType} ${d.queryName} @ ${d.dnsServer} => ${d.responseIps}",
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onDelete = { viewModel.deleteDns(d.id) }
                            ) {
                                KeyValueRow("Protocol", d.protocolClass)
                                KeyValueRow("Resolved IPs", d.responseIps.ifBlank { "None / Encrypted" })
                                if (d.errorMessage.isNotBlank()) KeyValueRow("Diagnostic", d.errorMessage, valueColor = cs.error)
                            }
                        }
                    }
                }

                LogCategory.TLS -> {
                    if (tlsLogs.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.ReceiptLong,
                                title = "No TLS Observations",
                                description = "Observed TLS ClientHello SNI handshakes and SSL inspections are stored locally here."
                            )
                        }
                    } else {
                        items(tlsLogs, key = { it.id }) { t ->
                            ExpandableLogCard(
                                headerBadge = { ProtocolBadge("TLS") },
                                timestamp = FormatUtils.formatTime(t.timestamp),
                                headline = t.sniHostname,
                                subline = "${t.tlsVersion} • ${t.dstIp}:${t.dstPort}",
                                copyPayload = "TLS ${t.tlsVersion} SNI=${t.sniHostname} ${t.dstIp}:${t.dstPort} ALPN=${t.alpnProtocols}",
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onDelete = { viewModel.deleteTls(t.id) }
                            ) {
                                KeyValueRow("TLS Version", t.tlsVersion)
                                KeyValueRow("ALPN", t.alpnProtocols.ifBlank { "Not advertised" })
                                KeyValueRow("Cipher Info", t.cipherInfo.ifBlank { "N/A" })
                                KeyValueRow("ECH / Unavailable", if (t.isEchOrUnavailable) "Yes (Metadata encrypted)" else "No")
                            }
                        }
                    }
                }

                LogCategory.API -> {
                    if (apiLogs.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.ReceiptLong,
                                title = "No API Logs",
                                description = "Requests executed in API Lab or Own-App Debug Interceptor are logged here with sensitive headers redacted."
                            )
                        }
                    } else {
                        items(apiLogs, key = { it.id }) { a ->
                            ExpandableLogCard(
                                headerBadge = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MethodBadge(a.method)
                                        StatusCodeBadge(a.statusCode)
                                    }
                                },
                                timestamp = FormatUtils.formatTime(a.timestamp),
                                headline = a.url,
                                subline = "${a.durationMs}ms • ${FormatUtils.formatBytes(a.responseSizeBytes)}${if (a.isOwnAppDebug) " • OWN-APP DEBUG" else ""}",
                                copyPayload = "${a.method} ${a.url} -> HTTP ${a.statusCode} (${a.durationMs}ms)",
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onDelete = { viewModel.deleteApi(a.id) }
                            ) {
                                KeyValueRow("Req Headers (Redacted)", a.requestHeadersJson)
                                KeyValueRow("Resp Headers", a.responseHeadersJson)
                                if (a.errorMessage.isNotBlank()) KeyValueRow("Error", a.errorMessage, valueColor = cs.error)
                            }
                        }
                    }
                }

                LogCategory.ROOT -> {
                    if (rootLogs.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.ReceiptLong,
                                title = "No Root Audit Entries",
                                description = "Every user-authorized read-only root diagnostic command is audited here."
                            )
                        }
                    } else {
                        items(rootLogs, key = { it.id }) { r ->
                            ExpandableLogCard(
                                headerBadge = { ProtocolBadge(r.category) },
                                timestamp = FormatUtils.formatTime(r.timestamp),
                                headline = r.command,
                                subline = "Exit ${r.exitCode} • User Authorized: ${r.authorizedByUser}",
                                copyPayload = "${r.command}\nExit: ${r.exitCode}\n${r.outputPreview}",
                                onCopy = { clipboard.setText(AnnotatedString(it)) },
                                onDelete = { viewModel.deleteRootAudit(r.id) }
                            ) {
                                Text(
                                    text = r.outputPreview,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ExpandableLogCard(
    headerBadge: @Composable () -> Unit,
    timestamp: String,
    headline: String,
    subline: String,
    copyPayload: String,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    details: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    CyberGlassCard(onClick = { expanded = !expanded }, cornerRadius = 15.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            headerBadge()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timestamp, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.labelMedium,
            maxLines = if (expanded) 4 else 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subline,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                details()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { onCopy(copyPayload) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Metadata", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Record", tint = cs.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
