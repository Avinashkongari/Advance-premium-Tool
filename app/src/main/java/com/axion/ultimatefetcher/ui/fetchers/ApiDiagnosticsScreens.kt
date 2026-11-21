package com.axion.ultimatefetcher.ui.fetchers

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axion.ultimatefetcher.domain.models.DiffType
import com.axion.ultimatefetcher.domain.models.WebSocketStatus
import com.axion.ultimatefetcher.root.SafeRootDiagnosticCommand
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.components.LivePulseIndicator
import com.axion.ultimatefetcher.ui.components.MethodBadge
import com.axion.ultimatefetcher.ui.components.StatusCodeBadge
import com.axion.ultimatefetcher.ui.components.highlightJson
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils
import com.example.BuildConfig

@Composable
fun ApiEndpointInspectorTool(viewModel: FetchersViewModel) {
    val result by viewModel.endpointResult.collectAsStateWithLifecycle()
    val loading by viewModel.endpointLoading.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("https://httpbin.org/get") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Endpoint URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.inspectEndpoint(url) }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Inspect Endpoint")
                }
            }
        }
        result?.let { r ->
            item {
                CyberGlassCard(accentGlow = if (r.error == null) cyber.cyan else MaterialTheme.colorScheme.error) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatusCodeBadge(r.statusCode)
                        Text("${r.durationMs} ms", style = MaterialTheme.typography.labelMedium, color = cyber.cyan)
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("Final URL", r.finalUrl)
                    KeyValueRow("TLS Version", r.tlsVersion)
                    KeyValueRow("Cipher Suite", r.cipherSuite)
                    KeyValueRow("Redirects", if (r.redirects.isEmpty()) "None" else r.redirects.joinToString("\n"))
                    r.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (r.headers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Response Headers (Sensitive Redacted)", style = MaterialTheme.typography.titleSmall)
                        r.headers.forEach { (k, v) -> KeyValueRow(k, v) }
                    }
                }
            }
        }
    }
}

@Composable
fun SslCertInspectorTool(viewModel: FetchersViewModel) {
    val result by viewModel.sslResult.collectAsStateWithLifecycle()
    val loading by viewModel.sslLoading.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("google.com") }
    var portStr by remember { mutableStateOf("443") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(0.72f))
                    OutlinedTextField(value = portStr, onValueChange = { portStr = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.28f))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.inspectSslCert(host, portStr.toIntOrNull() ?: 443) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Inspect TLS Certificate")
                }
            }
        }
        result?.let { r ->
            item {
                CyberGlassCard(accentGlow = if (r.isTrusted) cyber.success else MaterialTheme.colorScheme.error) {
                    Text(
                        if (r.isTrusted) "Certificate Chain Trusted" else "Certificate Warning / Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (r.isTrusted) cyber.success else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    if (r.error != null) {
                        Text(r.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        KeyValueRow("TLS Protocol", r.tlsProtocol)
                        KeyValueRow("Cipher Suite", r.cipherSuite)
                        KeyValueRow("Subject", r.subject)
                        KeyValueRow("Issuer", r.issuer)
                        KeyValueRow("Valid From", r.validFrom)
                        KeyValueRow("Expires", "${r.validUntil} (${r.daysUntilExpiry} days)")
                        KeyValueRow("Validation", r.validationDetail)
                        KeyValueRow("SAN Domains", r.sanDomains.take(15).joinToString(", ").ifBlank { "None" })
                        KeyValueRow("Cert Chain", r.chainSummary.joinToString("\n"))
                    }
                }
            }
        }
    }
}

@Composable
fun HttpHeaderAnalyzerTool(viewModel: FetchersViewModel) {
    val result by viewModel.headerResult.collectAsStateWithLifecycle()
    val loading by viewModel.headerLoading.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("https://example.com") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL to Analyze") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.analyzeHeaders(url) }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Analyze HTTP Headers")
                }
            }
        }
        result?.let { r ->
            item {
                CyberGlassCard {
                    if (r.error != null) {
                        Text(r.error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatusCodeBadge(r.statusCode)
                            Text("Security Score: ${r.securityScore}/100", style = MaterialTheme.typography.titleSmall, color = if (r.securityScore >= 60) cyber.success else cyber.warning)
                        }
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow("Cache-Control", r.cacheControl)
                        KeyValueRow("Content-Type", r.contentType)
                        KeyValueRow("Content-Length", r.contentLength)
                        KeyValueRow("Server", r.serverHeader)
                        Spacer(Modifier.height(8.dp))
                        Text("Security Headers", style = MaterialTheme.typography.titleSmall)
                        r.securityHeaders.forEach { chk ->
                            KeyValueRow(chk.name, if (chk.present) chk.value else "Missing — ${chk.recommendation}", valueColor = if (chk.present) cyber.success else cyber.warning)
                        }
                        if (r.corsHeaders.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("CORS Headers", style = MaterialTheme.typography.titleSmall)
                            r.corsHeaders.forEach { (k, v) -> KeyValueRow(k, v) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Cookie Security Attributes (Values Never Displayed)", style = MaterialTheme.typography.titleSmall)
                        if (r.cookieAttributes.isEmpty()) {
                            Text("No Set-Cookie headers returned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            r.cookieAttributes.forEach { c -> Text(c, style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFamily) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OpenApiDiscoveryTool(
    viewModel: FetchersViewModel,
    onOpenInApiLab: (String, String, String) -> Unit
) {
    val result by viewModel.openApiResult.collectAsStateWithLifecycle()
    val loading by viewModel.openApiLoading.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("https://petstore3.swagger.io/api/v3/openapi.json") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InfoCalloutBanner(
                title = "Explicit OpenAPI / Swagger Parser",
                message = "Parses ONLY explicitly provided OpenAPI/Swagger JSON URLs or pasted specs. Never brute-forces endpoints or scans unrelated hosts.",
                accentColor = cyber.violet
            )
        }
        item {
            CyberGlassCard {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("OpenAPI / Swagger JSON URL or Raw JSON") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.discoverOpenApi(input) }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Parse Specification")
                }
            }
        }
        result?.let { r ->
            if (r.error != null) {
                item { CyberGlassCard { Text(r.error, color = MaterialTheme.colorScheme.error) } }
            } else {
                item {
                    CyberGlassCard {
                        Text("${r.title} v${r.version}", style = MaterialTheme.typography.titleMedium)
                        Text("Base: ${r.baseUrl} • ${r.endpoints.size} endpoints", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(r.endpoints.take(60)) { ep ->
                    CyberGlassCard(cornerRadius = 14.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                MethodBadge(ep.method)
                                Text(ep.path, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { onOpenInApiLab(ep.method, ep.sampleUrl, ep.sampleBody) }) {
                                Icon(Icons.Default.OpenInNew, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("API Lab")
                            }
                        }
                        Text(ep.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ep.parameters.isNotEmpty()) {
                            Text("Params: ${ep.parameters.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = cyber.cyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WebSocketTesterTool(viewModel: FetchersViewModel) {
    val state by viewModel.webSocketState.collectAsStateWithLifecycle()
    var wsUrl by remember { mutableStateOf("wss://echo.websocket.events") }
    var msgText by remember { mutableStateOf("""{"ping":"ultimatefetcher"}""") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("WebSocket Connection", style = MaterialTheme.typography.titleMedium)
                    LivePulseIndicator(state.status == WebSocketStatus.CONNECTED, "CONNECTED", state.status.name)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = wsUrl, onValueChange = { wsUrl = it }, label = { Text("ws:// or wss:// Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.connectWebSocket(wsUrl) },
                        enabled = state.status != WebSocketStatus.CONNECTED && state.status != WebSocketStatus.CONNECTING,
                        modifier = Modifier.weight(1f)
                    ) { Text("Connect") }
                    OutlinedButton(
                        onClick = { viewModel.disconnectWebSocket() },
                        enabled = state.status == WebSocketStatus.CONNECTED || state.status == WebSocketStatus.CONNECTING,
                        modifier = Modifier.weight(1f)
                    ) { Text("Disconnect") }
                }
                Text(
                    "${state.statusDetail}${state.lastLatencyMs?.let { " • RTT ${it}ms" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            CyberGlassCard {
                OutlinedTextField(value = msgText, onValueChange = { msgText = it }, label = { Text("Test Frame Payload") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.sendWebSocketMessage(msgText) },
                        enabled = state.status == WebSocketStatus.CONNECTED,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                    OutlinedButton(onClick = { viewModel.clearWebSocketMessages() }) { Text("Clear") }
                }
            }
        }
        items(state.messages, key = { it.id }) { m ->
            CyberGlassCard(cornerRadius = 12.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.direction, style = MaterialTheme.typography.labelSmall, color = if (m.direction == "SENT") cyber.cyan else cyber.success, fontWeight = FontWeight.Bold)
                    Text("${FormatUtils.formatTime(m.timestamp)}${m.latencyMs?.let { " • ${it}ms" } ?: ""}", style = MaterialTheme.typography.labelSmall)
                }
                Text(m.payload, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun WebhookTesterTool(viewModel: FetchersViewModel) {
    val state by viewModel.webhookState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard(accentGlow = if (state.isRunning) cyber.success else null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Local Webhook Listener", style = MaterialTheme.typography.titleMedium)
                    LivePulseIndicator(state.isRunning, "LISTENING", "STOPPED")
                }
                Spacer(Modifier.height(6.dp))
                Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isRunning) {
                    Spacer(Modifier.height(6.dp))
                    KeyValueRow("Local URL", state.localEndpointUrl, valueColor = cyber.cyan)
                    state.lanEndpointUrl?.let { KeyValueRow("Wi-Fi LAN URL", it, valueColor = cyber.success) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (state.isRunning) viewModel.stopWebhookServer() else viewModel.startWebhookServer() },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.isRunning) "Stop Server" else "Start Listener") }
                    OutlinedButton(
                        onClick = { viewModel.sendWebhookSelfTest() },
                        enabled = state.isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Send Self-Test") }
                }
                if (state.payloads.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            viewModel.exportWebhookPayloadsUri { uri ->
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Export Webhook Payloads"))
                            }
                        }) {
                            Icon(Icons.Default.IosShare, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export JSON")
                        }
                        TextButton(onClick = { viewModel.clearWebhookPayloads() }) { Text("Clear") }
                    }
                }
            }
        }
        items(state.payloads, key = { it.id }) { p ->
            CyberGlassCard(cornerRadius = 14.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MethodBadge(p.method)
                    Text("${p.remoteAddress} • ${FormatUtils.formatTime(p.timestamp)}", style = MaterialTheme.typography.labelSmall)
                }
                Text("Path: ${p.path}", style = MaterialTheme.typography.labelMedium)
                if (p.body.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                            .padding(10.dp)
                    ) {
                        Text(highlightJson(FormatUtils.prettyPrintJson(p.body)), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ApiResponseDiffTool(viewModel: FetchersViewModel) {
    val lines by viewModel.diffLines.collectAsStateWithLifecycle()
    var leftJson by remember { mutableStateOf("{\n  \"status\": \"ok\",\n  \"version\": 1,\n  \"deprecated\": true\n}") }
    var rightJson by remember { mutableStateOf("{\n  \"status\": \"ok\",\n  \"version\": 2,\n  \"region\": \"us-east\"\n}") }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                OutlinedTextField(value = leftJson, onValueChange = { leftJson = it }, label = { Text("Baseline Response (Left)") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = rightJson, onValueChange = { rightJson = it }, label = { Text("Target Response (Right)") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.computeDiff(leftJson, rightJson) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Compare Responses")
                }
            }
        }
        items(lines) { diff ->
            val (prefix, color) = when (diff.type) {
                DiffType.ADDED -> "+ ADDED" to cyber.success
                DiffType.REMOVED -> "- REMOVED" to MaterialTheme.colorScheme.error
                DiffType.MODIFIED -> "~ MODIFIED" to cyber.warning
                DiffType.UNCHANGED -> "= SAME" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            CyberGlassCard(cornerRadius = 12.dp, accentGlow = if (diff.type != DiffType.UNCHANGED) color else null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(diff.path, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(prefix, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                }
                if (diff.leftValue != null) Text("Left:  ${diff.leftValue}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (diff.rightValue != null) Text("Right: ${diff.rightValue}", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
fun ApiHealthMonitorTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val monitors by viewModel.endpointMonitors.collectAsStateWithLifecycle()
    val checking by viewModel.healthChecking.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showAdd = true }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Endpoint")
                }
                OutlinedButton(onClick = { viewModel.runAllHealthChecksNow() }, enabled = !checking && monitors.isNotEmpty(), modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Check Now")
                }
            }
        }
        if (checking) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(monitors, key = { it.id }) { m ->
            CyberGlassCard(accentGlow = if (m.lastCheckedAt == 0L) null else if (m.lastHealthy) cyber.success else MaterialTheme.colorScheme.error) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium)
                        Text(m.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = m.isEnabled, onCheckedChange = { viewModel.toggleEndpointMonitor(context, m) })
                    IconButton(onClick = { viewModel.deleteEndpointMonitor(m.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                KeyValueRow("Last Status", if (m.lastCheckedAt == 0L) "Not checked yet" else "HTTP ${m.lastStatusCode} (${m.lastLatencyMs} ms)")
                KeyValueRow("Schedule", "WorkManager every ${m.intervalMinutes} min")
                KeyValueRow("Last Checked", FormatUtils.formatDateTime(m.lastCheckedAt))
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://httpbin.org/status/200") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Monitored Endpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Endpoint URL") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addEndpointMonitor(context, name, url, "GET", 15)
                    showAdd = false
                }) { Text("Add & Schedule") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun RootDiagnosticsTool(viewModel: FetchersViewModel) {
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val output by viewModel.rootOutput.collectAsStateWithLifecycle()
    val busy by viewModel.rootBusy.collectAsStateWithLifecycle()
    val auditLogs by viewModel.rootAuditLogs.collectAsStateWithLifecycle()
    var pendingConfirmCmd by remember { mutableStateOf<SafeRootDiagnosticCommand?>(null) }
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard(accentGlow = if (rootState.isAuthorized) cyber.warning else null) {
                Text("Root Status: ${rootState.statusLabel}", style = MaterialTheme.typography.titleMedium, color = if (rootState.isRootDetected) cyber.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(rootState.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.checkRootPassive() }) { Text("Re-Check") }
                    if (rootState.isRootDetected && !rootState.isAuthorized) {
                        Button(onClick = { viewModel.requestExplicitRootAuthorization() }, enabled = !busy) {
                            Text("Authorize Read-Only Root")
                        }
                    }
                }
            }
        }
        items(SafeRootDiagnosticCommand.entries) { cmd ->
            CyberGlassCard(cornerRadius = 14.dp) {
                Text(cmd.title, style = MaterialTheme.typography.titleSmall)
                Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { pendingConfirmCmd = cmd },
                    enabled = rootState.isRootDetected && rootState.isAuthorized && !busy
                ) {
                    Text(if (rootState.isRootDetected) "Run Read-Only Diagnostic" else "Root Not Available")
                }
            }
        }
        output?.let { out ->
            item {
                CyberGlassCard {
                    Text("${out.title} (exit ${out.exitCode})", style = MaterialTheme.typography.titleSmall, color = cyber.cyan)
                    Spacer(Modifier.height(6.dp))
                    Text(out.output, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), fontFamily = JetBrainsMonoFamily)
                }
            }
        }
        if (auditLogs.isNotEmpty()) {
            item { Text("Recent Root Audit Log (${auditLogs.size})", style = MaterialTheme.typography.titleSmall) }
            items(auditLogs.take(10), key = { it.id }) { log ->
                CyberGlassCard(cornerRadius = 12.dp) {
                    Text("${log.category} • Exit ${log.exitCode} • ${FormatUtils.formatTime(log.timestamp)}", style = MaterialTheme.typography.labelSmall, color = cyber.warning)
                    Text(log.command, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    pendingConfirmCmd?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pendingConfirmCmd = null },
            title = { Text("Confirm Privileged Read-Only Operation") },
            text = { Text("Execute read-only diagnostic command:\n\n${cmd.command}\n\nThis operation will be logged in RootAuditLog.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.runReadOnlyRootDiagnostic(cmd)
                    pendingConfirmCmd = null
                }) { Text("Authorize & Run") }
            },
            dismissButton = { TextButton(onClick = { pendingConfirmCmd = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun OwnAppDebugTool(viewModel: FetchersViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.ownAppDebugLogs.collectAsStateWithLifecycle()
    val cyber = LocalCyberColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CyberGlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Own-App OkHttp Debug Interceptor", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (BuildConfig.DEBUG) "Debug build active. Opt-in to record sanitized metadata for UltimateFetcher's own HTTP calls." else "Automatically locked off in release builds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.ownAppDebugEnabled && BuildConfig.DEBUG,
                        enabled = BuildConfig.DEBUG,
                        onCheckedChange = { viewModel.setOwnAppDebugEnabled(it) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.exportOwnAppDebugShareUri { uri ->
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Export Sanitized Debug Report"))
                        }
                    }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Default.IosShare, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export Sanitized")
                    }
                    TextButton(onClick = { viewModel.clearOwnAppDebugLogs() }) { Text("Clear") }
                }
            }
        }
        items(logs, key = { it.id }) { item ->
            CyberGlassCard(cornerRadius = 14.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MethodBadge(item.method)
                        StatusCodeBadge(item.statusCode)
                    }
                    Text("${item.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = cyber.cyan)
                }
                Text(item.url, style = MaterialTheme.typography.labelMedium)
                KeyValueRow("Req Headers", item.requestHeadersJson)
                if (item.errorMessage.isNotBlank()) Text(item.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
