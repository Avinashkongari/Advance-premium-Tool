package com.axion.ultimatefetcher.domain.models

import android.graphics.drawable.Drawable

enum class PipelineStageStatus(val label: String) {
    IDLE("Idle"),
    WAITING("Waiting"),
    ACTIVE("Active"),
    ERROR("Error")
}

data class TrafficSample(
    val timestamp: Long = System.currentTimeMillis(),
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L
)

data class LiveNetworkTelemetry(
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val sessionDownloadedBytes: Long = 0L,
    val sessionUploadedBytes: Long = 0L,
    val activeInterface: String = "Unavailable",
    val networkType: String = "Offline",
    val isConnected: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val privateDnsMode: String = "System Default",
    val vpnSystemActive: Boolean = false,
    val latencyMs: Long = -1L,
    val activeConnectionsCount: Int = 0,
    val totalPackets: Long = 0L,
    val tcpPackets: Long = 0L,
    val udpPackets: Long = 0L,
    val dnsPackets: Long = 0L,
    val quicBlockedPackets: Long = 0L,
    val packetsSavedToDb: Long = 0L,
    val publicIp: String? = null,
    val publicIpLoading: Boolean = false,
    val publicIpError: String? = null,
    val trafficHistory: List<TrafficSample> = emptyList()
)

data class VpnLiveState(
    val isRunning: Boolean = false,
    val vpnInterfaceEstablished: Boolean = false,
    val vpnConnectedState: PipelineStageStatus = PipelineStageStatus.IDLE,
    val packetReadingState: PipelineStageStatus = PipelineStageStatus.IDLE,
    val packetProcessingState: PipelineStageStatus = PipelineStageStatus.IDLE,
    val databaseSavingState: PipelineStageStatus = PipelineStageStatus.IDLE,
    val statusMessage: String = "Stopped",
    val lastError: String? = null,
    val packetsRead: Long = 0L,
    val packetsParsed: Long = 0L,
    val packetsRejected: Long = 0L,
    val totalPackets: Long = 0L,
    val tcpPackets: Long = 0L,
    val udpPackets: Long = 0L,
    val dnsPackets: Long = 0L,
    val quicPackets: Long = 0L,
    val blockedQuicPackets: Long = 0L,
    val packetsSavedToDb: Long = 0L,
    val dbInsertErrors: Long = 0L,
    val lastDbError: String? = null,
    val lastPacketTimestamp: Long = 0L,
    val capturedBytes: Long = 0L,
    val selectedAppPackages: List<String> = emptyList(),
    val selectedAppNames: List<String> = emptyList(),
    val unavailablePackages: List<String> = emptyList(),
    val quicBlockingEnabled: Boolean = false
) {
    val targetAppPackage: String
        get() = selectedAppPackages.joinToString(", ")

    val isActivelyCapturing: Boolean
        get() = isRunning && vpnInterfaceEstablished && packetsRead > 0L

    val selectedAppsSummary: String
        get() = when {
            selectedAppNames.isNotEmpty() -> selectedAppNames.joinToString(", ")
            selectedAppPackages.isNotEmpty() -> selectedAppPackages.joinToString(", ")
            else -> "All Launchable Apps"
        }
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

data class ConnectionSummary(
    val remoteIp: String,
    val remotePort: Int,
    val protocol: String,
    val serviceName: String,
    val packetCount: Int,
    val totalBytes: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val isBlocked: Boolean = false
)

data class DnsResolveResult(
    val hostname: String,
    val recordType: String,
    val dnsServer: String,
    val records: List<String>,
    val latencyMs: Long,
    val protocolUsed: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class NetworkQualityReport(
    val probeHost: String,
    val latencyMinMs: Double,
    val latencyAvgMs: Double,
    val latencyMaxMs: Double,
    val jitterMs: Double,
    val packetLossPercent: Double,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val stabilityGrade: String,
    val testServerDisclosure: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class EndpointInspectionResult(
    val initialUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val durationMs: Long,
    val redirects: List<String>,
    val tlsVersion: String,
    val cipherSuite: String,
    val headers: Map<String, String>,
    val error: String? = null
)

data class SslCertInspectionResult(
    val host: String,
    val port: Int,
    val isTrusted: Boolean,
    val tlsProtocol: String,
    val cipherSuite: String,
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validUntil: String,
    val daysUntilExpiry: Long,
    val sanDomains: List<String>,
    val chainSummary: List<String>,
    val validationDetail: String,
    val error: String? = null
)

data class SecurityHeaderCheck(
    val name: String,
    val present: Boolean,
    val value: String,
    val recommendation: String
)

data class HeaderAnalysisResult(
    val url: String,
    val statusCode: Int,
    val securityScore: Int,
    val cacheControl: String,
    val contentType: String,
    val contentLength: String,
    val serverHeader: String,
    val securityHeaders: List<SecurityHeaderCheck>,
    val corsHeaders: Map<String, String>,
    val cookieAttributes: List<String>,
    val error: String? = null
)

data class OpenApiEndpointItem(
    val method: String,
    val path: String,
    val summary: String,
    val parameters: List<String>,
    val sampleUrl: String,
    val sampleBody: String
)

data class OpenApiDiscoveryResult(
    val title: String,
    val version: String,
    val baseUrl: String,
    val endpoints: List<OpenApiEndpointItem>,
    val error: String? = null
)

enum class WebSocketStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class WebSocketMessageItem(
    val id: Long,
    val direction: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long? = null
)

data class WebhookPayloadItem(
    val id: Long,
    val method: String,
    val path: String,
    val remoteAddress: String,
    val headers: Map<String, String>,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class DiffType {
    ADDED,
    REMOVED,
    MODIFIED,
    UNCHANGED
}

data class JsonDiffLine(
    val path: String,
    val type: DiffType,
    val leftValue: String? = null,
    val rightValue: String? = null
)

data class RootAvailabilityState(
    val isRootDetected: Boolean = false,
    val isAuthorized: Boolean = false,
    val statusLabel: String = "Not Rooted (Standard Android)",
    val details: String = "Passive check only. Superuser is never requested automatically.",
    val suPath: String? = null
)

data class RootDiagnosticResult(
    val title: String,
    val command: String,
    val exitCode: Int,
    val output: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class KeyValuePair(
    val key: String = "",
    val value: String = "",
    val enabled: Boolean = true
)

data class ApiRequestDraft(
    val method: String = "GET",
    val url: String = "https://httpbin.org/get",
    val queryParams: List<KeyValuePair> = emptyList(),
    val headers: List<KeyValuePair> = listOf(KeyValuePair("Accept", "application/json", true)),
    val bodyType: String = "NONE",
    val bodyText: String = "",
    val formFields: List<KeyValuePair> = emptyList()
)

data class ApiExecutionResult(
    val statusCode: Int,
    val durationMs: Long,
    val responseSizeBytes: Long,
    val headers: Map<String, String>,
    val body: String,
    val isJson: Boolean,
    val error: String? = null
)
