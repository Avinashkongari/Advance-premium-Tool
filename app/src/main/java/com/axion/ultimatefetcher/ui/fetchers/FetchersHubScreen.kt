package com.axion.ultimatefetcher.ui.fetchers

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.CyberSectionHeader
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors

@Composable
fun FetchersScreen(
    viewModel: FetchersViewModel,
    onOpenInApiLab: (method: String, url: String, body: String) -> Unit,
    resetTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    var selectedToolName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            selectedToolName = null
        }
    }
    val activeTool = selectedToolName?.let { runCatching { FetcherToolId.valueOf(it) }.getOrNull() }

    if (activeTool != null) {
        BackHandler { selectedToolName = null }
        FetcherToolDetailContainer(
            tool = activeTool,
            onBack = { selectedToolName = null },
            modifier = modifier
        ) {
            when (activeTool) {
                FetcherToolId.VPN_TRAFFIC -> VpnTrafficMonitorTool(viewModel)
                FetcherToolId.DNS_MONITOR -> DnsQueryMonitorTool(viewModel)
                FetcherToolId.APP_FILTER -> AppTrafficFilterTool(viewModel)
                FetcherToolId.QUIC_DIAGNOSTIC -> QuicDiagnosticTool(viewModel)
                FetcherToolId.TLS_EXTRACTOR -> TlsDomainExtractorTool(viewModel)
                FetcherToolId.CONNECTION_ANALYZER -> ConnectionAnalyzerTool(viewModel)
                FetcherToolId.DNS_RESOLVER -> DnsResolverTesterTool(viewModel)
                FetcherToolId.NETWORK_QUALITY -> NetworkQualityTesterTool(viewModel)
                FetcherToolId.ENDPOINT_INSPECTOR -> ApiEndpointInspectorTool(viewModel)
                FetcherToolId.SSL_INSPECTOR -> SslCertInspectorTool(viewModel)
                FetcherToolId.HEADER_ANALYZER -> HttpHeaderAnalyzerTool(viewModel)
                FetcherToolId.OPENAPI_DISCOVERY -> OpenApiDiscoveryTool(viewModel, onOpenInApiLab)
                FetcherToolId.WEBSOCKET_TESTER -> WebSocketTesterTool(viewModel)
                FetcherToolId.WEBHOOK_TESTER -> WebhookTesterTool(viewModel)
                FetcherToolId.RESPONSE_DIFF -> ApiResponseDiffTool(viewModel)
                FetcherToolId.HEALTH_MONITOR -> ApiHealthMonitorTool(viewModel)
                FetcherToolId.ROOT_DIAGNOSTICS -> RootDiagnosticsTool(viewModel)
                FetcherToolId.OWN_APP_DEBUG -> OwnAppDebugTool(viewModel)
            }
        }
    } else {
        FetchersDirectoryHub(
            onSelectTool = { selectedToolName = it.name },
            modifier = modifier
        )
    }
}

@Composable
private fun FetchersDirectoryHub(
    onSelectTool: (FetcherToolId) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    val grouped = remember(query) {
        FetcherToolId.entries
            .filter {
                query.isBlank() ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.subtitle.contains(query, ignoreCase = true) ||
                    it.category.contains(query, ignoreCase = true)
            }
            .groupBy { it.category }
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
                Column {
                    Text("Fetchers & Diagnostics", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "18 Non-Root Network, API, TLS, WebSocket & Read-Only System Modules",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Tools") },
                    placeholder = { Text("Filter fetchers (DNS, QUIC, SSL, WebSocket, Root...)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("fetchers_search_input"),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            grouped.forEach { (category, tools) ->
                item(key = "header_$category") {
                    Spacer(Modifier.height(4.dp))
                    CyberSectionHeader(title = category)
                }
                items(tools, key = { it.name }) { tool ->
                    val (icon, accent) = toolVisualMeta(tool, cs.primary, cyber.cyan, cyber.success, cyber.warning, cyber.violet)
                    CyberGlassCard(
                        onClick = { onSelectTool(tool) },
                        cornerRadius = 16.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fetcher_card_${tool.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, contentDescription = tool.title, tint = accent, modifier = Modifier.size(22.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tool.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FetcherToolDetailContainer(
    tool: FetcherToolId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("fetcher_detail_back")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Fetchers")
            }
            Column {
                Text(tool.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.fillMaxSize().widthIn(max = 880.dp)) {
                content()
            }
        }
    }
}

private fun toolVisualMeta(
    tool: FetcherToolId,
    primary: Color,
    cyan: Color,
    success: Color,
    warning: Color,
    violet: Color
): Pair<ImageVector, Color> = when (tool) {
    FetcherToolId.VPN_TRAFFIC -> Icons.Default.VpnKey to primary
    FetcherToolId.DNS_MONITOR -> Icons.Default.Dns to success
    FetcherToolId.APP_FILTER -> Icons.Default.Apps to cyan
    FetcherToolId.QUIC_DIAGNOSTIC -> Icons.Default.Block to warning
    FetcherToolId.TLS_EXTRACTOR -> Icons.Default.Lock to violet
    FetcherToolId.CONNECTION_ANALYZER -> Icons.Default.Hub to cyan
    FetcherToolId.DNS_RESOLVER -> Icons.Default.ManageSearch to success
    FetcherToolId.NETWORK_QUALITY -> Icons.Default.Speed to primary
    FetcherToolId.ENDPOINT_INSPECTOR -> Icons.Default.Http to cyan
    FetcherToolId.SSL_INSPECTOR -> Icons.Default.VerifiedUser to success
    FetcherToolId.HEADER_ANALYZER -> Icons.Default.FactCheck to primary
    FetcherToolId.OPENAPI_DISCOVERY -> Icons.Default.Explore to violet
    FetcherToolId.WEBSOCKET_TESTER -> Icons.Default.Cable to cyan
    FetcherToolId.WEBHOOK_TESTER -> Icons.Default.Webhook to warning
    FetcherToolId.RESPONSE_DIFF -> Icons.AutoMirrored.Filled.CompareArrows to violet
    FetcherToolId.HEALTH_MONITOR -> Icons.Default.HealthAndSafety to success
    FetcherToolId.ROOT_DIAGNOSTICS -> Icons.Default.AdminPanelSettings to warning
    FetcherToolId.OWN_APP_DEBUG -> Icons.Default.BugReport to primary
}
