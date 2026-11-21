package com.axion.ultimatefetcher.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "packet_logs",
    indices = [Index("timestamp"), Index("protocol"), Index("dstIp")]
)
data class PacketLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val packetSize: Int,
    val direction: String = "OUTBOUND",
    val appPackage: String = "",
    val isBlocked: Boolean = false,
    val metadata: String = ""
)

@Entity(
    tableName = "dns_logs",
    indices = [Index("timestamp"), Index("queryName")]
)
data class DNSLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val queryName: String,
    val queryType: String = "A",
    val dnsServer: String = "",
    val responseIps: String = "",
    val latencyMs: Long = -1L,
    val protocolClass: String = "UDP_53",
    val isEncrypted: Boolean = false,
    val errorMessage: String = ""
)

@Entity(
    tableName = "tls_logs",
    indices = [Index("timestamp"), Index("sniHostname")]
)
data class TLSLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val sniHostname: String,
    val dstIp: String,
    val dstPort: Int = 443,
    val tlsVersion: String = "TLS",
    val alpnProtocols: String = "",
    val isEchOrUnavailable: Boolean = false,
    val cipherInfo: String = ""
)

@Entity(
    tableName = "api_request_logs",
    indices = [Index("timestamp"), Index("isOwnAppDebug")]
)
data class ApiRequestLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val url: String,
    val requestHeadersJson: String = "{}",
    val requestBodyPreview: String = "",
    val statusCode: Int = 0,
    val durationMs: Long = 0L,
    val responseSizeBytes: Long = 0L,
    val responseHeadersJson: String = "{}",
    val responseBodyPreview: String = "",
    val errorMessage: String = "",
    val isOwnAppDebug: Boolean = false
)

@Entity(tableName = "network_sessions")
data class NetworkSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0L,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val totalPackets: Long = 0L,
    val tcpPackets: Long = 0L,
    val udpPackets: Long = 0L,
    val quicBlockedPackets: Long = 0L,
    val activeInterface: String = "Unavailable"
)

@Entity(tableName = "endpoint_monitors")
data class EndpointMonitor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val url: String,
    val method: String = "GET",
    val intervalMinutes: Int = 15,
    val isEnabled: Boolean = true,
    val lastStatusCode: Int = 0,
    val lastLatencyMs: Long = 0L,
    val lastHealthy: Boolean = true,
    val lastCheckedAt: Long = 0L
)

@Entity(tableName = "saved_collections")
data class SavedCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val itemsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "root_audit_logs",
    indices = [Index("timestamp")]
)
data class RootAuditLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String,
    val command: String,
    val exitCode: Int,
    val authorizedByUser: Boolean = true,
    val outputPreview: String = ""
)
