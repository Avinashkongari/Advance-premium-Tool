package com.axion.ultimatefetcher.ui.fetchers

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.UserSettings
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.domain.models.ConnectionSummary
import com.axion.ultimatefetcher.domain.models.DnsResolveResult
import com.axion.ultimatefetcher.domain.models.EndpointInspectionResult
import com.axion.ultimatefetcher.domain.models.HeaderAnalysisResult
import com.axion.ultimatefetcher.domain.models.InstalledAppInfo
import com.axion.ultimatefetcher.domain.models.JsonDiffLine
import com.axion.ultimatefetcher.domain.models.NetworkQualityReport
import com.axion.ultimatefetcher.domain.models.OpenApiDiscoveryResult
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import com.axion.ultimatefetcher.domain.models.RootDiagnosticResult
import com.axion.ultimatefetcher.domain.models.SslCertInspectionResult
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import com.axion.ultimatefetcher.network.api.WebhookServerState
import com.axion.ultimatefetcher.network.api.WebSocketUiState
import com.axion.ultimatefetcher.network.vpn.VpnMonitorService
import com.axion.ultimatefetcher.root.SafeRootDiagnosticCommand
import com.axion.ultimatefetcher.services.ApiHealthCheckWorker
import com.axion.ultimatefetcher.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FetcherToolId(
    val title: String,
    val subtitle: String,
    val category: String
) {
    VPN_TRAFFIC("VPN Traffic Monitor", "Local TUN metadata capture & stats", "Network Fetchers"),
    DNS_MONITOR("DNS Query Monitor", "Observable UDP/TCP:53 & DoH/DoT detection", "Network Fetchers"),
    APP_FILTER("App Traffic Filter", "Per-app VPN filter for launchable apps", "Network Fetchers"),
    QUIC_DIAGNOSTIC("QUIC Diagnostic & Blocker", "UDP:443 telemetry & opt-in local blocker", "Network Fetchers"),
    TLS_EXTRACTOR("TLS Domain Extractor", "ClientHello SNI, TLS version & ALPN", "Network Fetchers"),
    CONNECTION_ANALYZER("Connection Analyzer", "Remote endpoints, ports & volume", "Network Fetchers"),
    DNS_RESOLVER("DNS Resolver Tester", "Custom A/AAAA/MX/TXT/CNAME lookup", "Network Diagnostics"),
    NETWORK_QUALITY("Network Quality Tester", "Real latency, jitter, loss & speed", "Network Diagnostics"),
    ENDPOINT_INSPECTOR("API Endpoint Inspector", "Redirects, status, TLS & duration", "API Diagnostics"),
    SSL_INSPECTOR("SSL Certificate Inspector", "X.509 issuer, expiry, SANs & chain", "API Diagnostics"),
    HEADER_ANALYZER("HTTP Header Analyzer", "CORS, security headers & redacted cookies", "API Diagnostics"),
    OPENAPI_DISCOVERY("REST API Discovery", "Parse provided OpenAPI/Swagger specs", "API Diagnostics"),
    WEBSOCKET_TESTER("WebSocket Tester", "Live WS/WSS messaging & latency", "API Diagnostics"),
    WEBHOOK_TESTER("Webhook Tester", "Local HTTP listener & payload viewer", "API Diagnostics"),
    RESPONSE_DIFF("API Response Diff", "Structural JSON & text diff viewer", "API Diagnostics"),
    HEALTH_MONITOR("API Health Monitor", "Scheduled checks & failure alerts", "API Diagnostics"),
    ROOT_DIAGNOSTICS("Root Diagnostics", "Read-only kernel net diagnostics", "System & Dev"),
    OWN_APP_DEBUG("Own-App API Debugger", "Opt-in debug OkHttp metadata log", "System & Dev")
}

class FetchersViewModel(private val container: AppContainer) : ViewModel() {

    val vpnState: StateFlow<VpnLiveState> = VpnMonitorService.liveState
    val settings: StateFlow<UserSettings> = container.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val recentPackets: StateFlow<List<PacketLog>> = container.repository.observePackets(limit = 150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quicPackets: StateFlow<List<PacketLog>> = container.repository.observeQuicPackets(150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedQuicCount: StateFlow<Int> = container.repository.observeBlockedQuicCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val dnsLogs: StateFlow<List<DNSLog>> = container.repository.observeDnsLogs(limit = 150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tlsLogs: StateFlow<List<TLSLog>> = container.repository.observeTlsLogs(limit = 150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ownAppDebugLogs: StateFlow<List<ApiRequestLog>> = container.repository.observeOwnAppDebugLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val endpointMonitors: StateFlow<List<EndpointMonitor>> = container.repository.observeEndpointMonitors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootAuditLogs: StateFlow<List<RootAuditLog>> = container.repository.observeRootAuditLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connections: StateFlow<List<ConnectionSummary>> = recentPackets.map { list ->
        list.groupBy { "${it.protocol}|${it.dstIp}|${it.dstPort}" }
            .map { (_, group) ->
                val sample = group.first()
                ConnectionSummary(
                    remoteIp = sample.dstIp,
                    remotePort = sample.dstPort,
                    protocol = sample.protocol,
                    serviceName = FormatUtils.classifyPort(sample.dstPort, sample.protocol),
                    packetCount = group.size,
                    totalBytes = group.sumOf { it.packetSize.toLong() },
                    firstSeen = group.minOf { it.timestamp },
                    lastSeen = group.maxOf { it.timestamp },
                    isBlocked = group.any { it.isBlocked }
                )
            }
            .sortedByDescending { it.lastSeen }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // App Filter
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()
    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    // DNS Resolver Tester
    private val _dnsTestHistory = MutableStateFlow<List<DnsResolveResult>>(emptyList())
    val dnsTestHistory: StateFlow<List<DnsResolveResult>> = _dnsTestHistory.asStateFlow()
    private val _dnsTesting = MutableStateFlow(false)
    val dnsTesting: StateFlow<Boolean> = _dnsTesting.asStateFlow()

    // Network Quality Tester
    private val _qualityReport = MutableStateFlow<NetworkQualityReport?>(null)
    val qualityReport: StateFlow<NetworkQualityReport?> = _qualityReport.asStateFlow()
    private val _qualityProgress = MutableStateFlow<String?>(null)
    val qualityProgress: StateFlow<String?> = _qualityProgress.asStateFlow()
    private var qualityJob: Job? = null

    // Endpoint Inspector
    private val _endpointResult = MutableStateFlow<EndpointInspectionResult?>(null)
    val endpointResult: StateFlow<EndpointInspectionResult?> = _endpointResult.asStateFlow()
    private val _endpointLoading = MutableStateFlow(false)
    val endpointLoading: StateFlow<Boolean> = _endpointLoading.asStateFlow()

    // SSL Cert Inspector
    private val _sslResult = MutableStateFlow<SslCertInspectionResult?>(null)
    val sslResult: StateFlow<SslCertInspectionResult?> = _sslResult.asStateFlow()
    private val _sslLoading = MutableStateFlow(false)
    val sslLoading: StateFlow<Boolean> = _sslLoading.asStateFlow()

    // Header Analyzer
    private val _headerResult = MutableStateFlow<HeaderAnalysisResult?>(null)
    val headerResult: StateFlow<HeaderAnalysisResult?> = _headerResult.asStateFlow()
    private val _headerLoading = MutableStateFlow(false)
    val headerLoading: StateFlow<Boolean> = _headerLoading.asStateFlow()

    // OpenAPI Discovery
    private val _openApiResult = MutableStateFlow<OpenApiDiscoveryResult?>(null)
    val openApiResult: StateFlow<OpenApiDiscoveryResult?> = _openApiResult.asStateFlow()
    private val _openApiLoading = MutableStateFlow(false)
    val openApiLoading: StateFlow<Boolean> = _openApiLoading.asStateFlow()

    // WebSocket & Webhook
    val webSocketState: StateFlow<WebSocketUiState> = container.webSocketManager.state
    val webhookState: StateFlow<WebhookServerState> = container.localWebhookServer.state

    // Diff
    private val _diffLines = MutableStateFlow<List<JsonDiffLine>>(emptyList())
    val diffLines: StateFlow<List<JsonDiffLine>> = _diffLines.asStateFlow()

    // Health check busy state
    private val _healthChecking = MutableStateFlow(false)
    val healthChecking: StateFlow<Boolean> = _healthChecking.asStateFlow()

    // Root Diagnostics
    private val _rootState = MutableStateFlow(RootAvailabilityState())
    val rootState: StateFlow<RootAvailabilityState> = _rootState.asStateFlow()
    private val _rootOutput = MutableStateFlow<RootDiagnosticResult?>(null)
    val rootOutput: StateFlow<RootDiagnosticResult?> = _rootOutput.asStateFlow()
    private val _rootBusy = MutableStateFlow(false)
    val rootBusy: StateFlow<Boolean> = _rootBusy.asStateFlow()

    init {
        checkRootPassive()
    }

    fun loadLaunchableApps(forceRefresh: Boolean = false) {
        if (!forceRefresh && _installedApps.value.isNotEmpty()) return
        if (_appsLoading.value) return
        viewModelScope.launch {
            _appsLoading.value = true
            val loaded = container.repository.loadLaunchableApps()
            _installedApps.value = loaded
            // Prune any previously selected package that has since been uninstalled
            val installedPkgs = loaded.map { it.packageName }.toSet()
            val stale = settings.value.selectedAppPackages.filter { it !in installedPkgs }
            if (stale.isNotEmpty()) {
                container.settingsDataStore.pruneUnavailableApps(stale)
            }
            _appsLoading.value = false
        }
    }

    fun toggleAppSelection(context: Context, packageName: String, appName: String) {
        viewModelScope.launch {
            val updatedMap = container.settingsDataStore.toggleSelectedApp(packageName, appName)
            val s = settings.value
            VpnMonitorService.reconfigureIfRunning(
                context = context,
                quicBlocking = s.quicBlockingEnabled,
                targetAppPackages = updatedMap.keys.sorted(),
                dnsServer = s.customDnsServer
            )
        }
    }

    fun selectSingleApp(context: Context, packageName: String, appName: String) {
        viewModelScope.launch {
            val pkgs = if (packageName.isBlank()) {
                container.settingsDataStore.clearSelectedApps()
                emptyList()
            } else {
                container.settingsDataStore.setSelectedAppFilter(packageName, appName)
                listOf(packageName.trim())
            }
            val s = settings.value
            VpnMonitorService.reconfigureIfRunning(
                context = context,
                quicBlocking = s.quicBlockingEnabled,
                targetAppPackages = pkgs,
                dnsServer = s.customDnsServer
            )
        }
    }

    fun clearSelectedApps(context: Context) {
        selectSingleApp(context, "", "All Launchable Apps")
    }

    fun setQuicBlocking(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setQuicBlockingEnabled(enabled)
            VpnMonitorService.updateQuicBlocking(context, enabled)
        }
    }

    fun startVpn(context: Context) {
        val s = settings.value
        VpnMonitorService.start(
            context = context,
            quicBlocking = s.quicBlockingEnabled,
            targetAppPackages = s.selectedAppPackages,
            dnsServer = s.customDnsServer
        )
    }

    fun reportVpnPermissionDenied() {
        VpnMonitorService.reportPermissionDenied()
    }

    fun stopVpn(context: Context) {
        VpnMonitorService.stop(context)
    }

    fun runDnsLookup(hostname: String, recordType: String, dnsServer: String) {
        viewModelScope.launch {
            _dnsTesting.value = true
            val res = container.dnsResolverEngine.resolve(hostname, recordType, dnsServer)
            _dnsTestHistory.update { (listOf(res) + it).take(30) }
            container.repository.insertDnsLog(
                DNSLog(
                    queryName = res.hostname,
                    queryType = res.recordType,
                    dnsServer = res.dnsServer,
                    responseIps = res.records.joinToString(", "),
                    latencyMs = res.latencyMs,
                    protocolClass = res.protocolUsed,
                    isEncrypted = false,
                    errorMessage = res.error.orEmpty()
                )
            )
            _dnsTesting.value = false
        }
    }

    fun startNetworkQualityTest(probeHost: String) {
        qualityJob?.cancel()
        qualityJob = viewModelScope.launch {
            _qualityReport.value = null
            _qualityProgress.value = "Starting network diagnostics..."
            val report = container.networkQualityEngine.runFullQualityTest(probeHost) { msg ->
                _qualityProgress.value = msg
            }
            _qualityReport.value = report
            _qualityProgress.value = null
        }
    }

    fun cancelNetworkQualityTest() {
        qualityJob?.cancel()
        _qualityProgress.value = null
    }

    fun inspectEndpoint(url: String) {
        viewModelScope.launch {
            _endpointLoading.value = true
            _endpointResult.value = container.apiDiagnosticsEngine.inspectEndpoint(url)
            _endpointLoading.value = false
        }
    }

    fun inspectSslCert(host: String, port: Int) {
        viewModelScope.launch {
            _sslLoading.value = true
            val res = container.apiDiagnosticsEngine.inspectSslCertificate(host, port)
            _sslResult.value = res
            if (res.error == null) {
                container.repository.insertTlsLog(
                    TLSLog(
                        sniHostname = res.host,
                        dstIp = res.host,
                        dstPort = res.port,
                        tlsVersion = res.tlsProtocol,
                        alpnProtocols = "TLS Direct Probe",
                        isEchOrUnavailable = false,
                        cipherInfo = res.cipherSuite
                    )
                )
            }
            _sslLoading.value = false
        }
    }

    fun analyzeHeaders(url: String) {
        viewModelScope.launch {
            _headerLoading.value = true
            _headerResult.value = container.apiDiagnosticsEngine.analyzeHttpHeaders(url)
            _headerLoading.value = false
        }
    }

    fun discoverOpenApi(specInput: String) {
        viewModelScope.launch {
            _openApiLoading.value = true
            _openApiResult.value = container.apiDiagnosticsEngine.parseOpenApiSpec(specInput)
            _openApiLoading.value = false
        }
    }

    fun connectWebSocket(url: String) = container.webSocketManager.connect(url)
    fun sendWebSocketMessage(msg: String) = container.webSocketManager.sendMessage(msg)
    fun disconnectWebSocket() = container.webSocketManager.disconnect()
    fun clearWebSocketMessages() = container.webSocketManager.clearMessages()

    fun startWebhookServer(port: Int = 8899) = container.localWebhookServer.start(port)
    fun stopWebhookServer() = container.localWebhookServer.stop()
    fun clearWebhookPayloads() = container.localWebhookServer.clearPayloads()
    fun sendWebhookSelfTest() {
        viewModelScope.launch {
            container.localWebhookServer.sendLocalSelfTest(
                """{"event":"device.test","source":"UltimateFetcher","timestamp":${System.currentTimeMillis()},"status":"ok"}"""
            )
        }
    }

    fun exportWebhookPayloadsUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val json = container.localWebhookServer.exportPayloadsAsJson()
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_webhooks_${System.currentTimeMillis()}.json",
                json
            )
            onReady(uri)
        }
    }

    fun computeDiff(left: String, right: String) {
        _diffLines.value = container.apiDiagnosticsEngine.computeResponseDiff(left, right)
    }

    fun addEndpointMonitor(context: Context, name: String, url: String, method: String, intervalMin: Int) {
        val cleanName = name.trim().ifEmpty { url.trim() }
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        viewModelScope.launch {
            container.repository.insertEndpointMonitor(
                EndpointMonitor(
                    name = cleanName,
                    url = cleanUrl,
                    method = method,
                    intervalMinutes = intervalMin.coerceAtLeast(15)
                )
            )
            ApiHealthCheckWorker.schedulePeriodicChecks(context, intervalMin.toLong())
        }
    }

    fun toggleEndpointMonitor(context: Context, monitor: EndpointMonitor) {
        viewModelScope.launch {
            container.repository.updateEndpointMonitor(monitor.copy(isEnabled = !monitor.isEnabled))
            val anyEnabled = endpointMonitors.value.any { if (it.id == monitor.id) !it.isEnabled else it.isEnabled }
            if (anyEnabled) {
                ApiHealthCheckWorker.schedulePeriodicChecks(context, 15L)
            } else {
                ApiHealthCheckWorker.cancelPeriodicChecks(context)
            }
        }
    }

    fun deleteEndpointMonitor(id: Long) {
        viewModelScope.launch { container.repository.deleteEndpointMonitor(id) }
    }

    fun runAllHealthChecksNow() {
        viewModelScope.launch {
            _healthChecking.value = true
            withContext(Dispatchers.IO) {
                endpointMonitors.value.filter { it.isEnabled }.forEach { monitor ->
                    val updated = ApiHealthCheckWorker.checkSingleEndpoint(container.okHttpClient, monitor)
                    container.repository.updateEndpointMonitor(updated)
                }
            }
            _healthChecking.value = false
        }
    }

    fun checkRootPassive() {
        viewModelScope.launch {
            _rootState.value = container.rootDiagnosticsEngine.checkRootAvailabilityPassive()
        }
    }

    fun requestExplicitRootAuthorization() {
        viewModelScope.launch {
            _rootBusy.value = true
            _rootState.value = container.rootDiagnosticsEngine.requestExplicitSuperuserVerification(_rootState.value)
            _rootBusy.value = false
        }
    }

    fun runReadOnlyRootDiagnostic(cmd: SafeRootDiagnosticCommand) {
        viewModelScope.launch {
            _rootBusy.value = true
            _rootOutput.value = container.rootDiagnosticsEngine.runReadOnlyDiagnostic(cmd, _rootState.value)
            _rootBusy.value = false
        }
    }

    fun setOwnAppDebugEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setOwnAppDebugEnabled(enabled)
            container.ownAppDebugInterceptor.setOptInEnabled(enabled)
        }
    }

    fun clearOwnAppDebugLogs() {
        viewModelScope.launch { container.repository.clearOwnAppDebugLogs() }
    }

    fun exportOwnAppDebugShareUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val logs = ownAppDebugLogs.value
            val sb = StringBuilder()
            sb.appendLine("# UltimateFetcher Sanitized Own-App API Debug Report")
            sb.appendLine("# Sensitive headers (Authorization, Cookie, API Keys, Tokens) are strictly redacted.")
            logs.forEach { log ->
                sb.appendLine("${FormatUtils.formatDateTime(log.timestamp)} | ${log.method} ${log.url} -> HTTP ${log.statusCode} (${log.durationMs}ms)")
                sb.appendLine("  Request Headers: ${log.requestHeadersJson}")
                sb.appendLine("  Response Headers: ${log.responseHeadersJson}")
                if (log.errorMessage.isNotBlank()) sb.appendLine("  Error: ${log.errorMessage}")
                sb.appendLine()
            }
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_ownapp_debug_${System.currentTimeMillis()}.txt",
                sb.toString()
            )
            onReady(uri)
        }
    }
}
