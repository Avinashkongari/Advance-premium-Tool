package com.axion.ultimatefetcher.data.repositories

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.axion.ultimatefetcher.data.database.UltimateFetcherDatabase
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.axion.ultimatefetcher.data.database.entities.NetworkSession
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.database.entities.SavedCollection
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.domain.models.ApiRequestDraft
import com.axion.ultimatefetcher.domain.models.InstalledAppInfo
import com.axion.ultimatefetcher.domain.models.LiveNetworkTelemetry
import com.axion.ultimatefetcher.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class UltimateFetcherRepository(
    private val context: Context,
    private val db: UltimateFetcherDatabase
) {
    fun observePackets(limit: Int = 200): Flow<List<PacketLog>> = db.packetLogDao().observeRecent(limit)
    fun observeQuicPackets(limit: Int = 150): Flow<List<PacketLog>> = db.packetLogDao().observeQuicPackets(limit)
    fun observeBlockedQuicCount(): Flow<Int> = db.packetLogDao().observeBlockedQuicCount()
    fun observeDnsLogs(limit: Int = 200): Flow<List<DNSLog>> = db.dnsLogDao().observeRecent(limit)
    fun observeTlsLogs(limit: Int = 200): Flow<List<TLSLog>> = db.tlsLogDao().observeRecent(limit)
    fun observeApiLabHistory(limit: Int = 100): Flow<List<ApiRequestLog>> = db.apiRequestLogDao().observeApiLabHistory(limit)
    fun observeOwnAppDebugLogs(limit: Int = 150): Flow<List<ApiRequestLog>> = db.apiRequestLogDao().observeOwnAppDebugLogs(limit)
    fun observeAllApiLogs(limit: Int = 200): Flow<List<ApiRequestLog>> = db.apiRequestLogDao().observeAll(limit)
    fun observeEndpointMonitors(): Flow<List<EndpointMonitor>> = db.endpointMonitorDao().observeAll()
    fun observeCollections(): Flow<List<SavedCollection>> = db.savedCollectionDao().observeAll()
    fun observeRootAuditLogs(limit: Int = 100): Flow<List<RootAuditLog>> = db.rootAuditLogDao().observeRecent(limit)

    suspend fun insertPackets(items: List<PacketLog>) = withContext(Dispatchers.IO) {
        if (items.isNotEmpty()) db.packetLogDao().insertAll(items)
    }

    suspend fun insertDnsLog(item: DNSLog) = withContext(Dispatchers.IO) { db.dnsLogDao().insert(item) }
    suspend fun insertDnsLogs(items: List<DNSLog>) = withContext(Dispatchers.IO) {
        if (items.isNotEmpty()) db.dnsLogDao().insertAll(items)
    }

    suspend fun insertTlsLog(item: TLSLog) = withContext(Dispatchers.IO) { db.tlsLogDao().insert(item) }
    suspend fun insertTlsLogs(items: List<TLSLog>) = withContext(Dispatchers.IO) {
        if (items.isNotEmpty()) db.tlsLogDao().insertAll(items)
    }

    suspend fun insertApiLog(item: ApiRequestLog): Long = withContext(Dispatchers.IO) {
        db.apiRequestLogDao().insert(item)
    }

    suspend fun insertSession(session: NetworkSession): Long = withContext(Dispatchers.IO) {
        db.networkSessionDao().insert(session)
    }

    suspend fun insertEndpointMonitor(monitor: EndpointMonitor): Long = withContext(Dispatchers.IO) {
        db.endpointMonitorDao().insert(monitor)
    }

    suspend fun updateEndpointMonitor(monitor: EndpointMonitor) = withContext(Dispatchers.IO) {
        db.endpointMonitorDao().update(monitor)
    }

    suspend fun deleteEndpointMonitor(id: Long) = withContext(Dispatchers.IO) {
        db.endpointMonitorDao().deleteById(id)
    }

    suspend fun insertRootAuditLog(item: RootAuditLog): Long = withContext(Dispatchers.IO) {
        db.rootAuditLogDao().insert(item)
    }

    suspend fun deletePacket(id: Long) = withContext(Dispatchers.IO) { db.packetLogDao().deleteById(id) }
    suspend fun deleteDns(id: Long) = withContext(Dispatchers.IO) { db.dnsLogDao().deleteById(id) }
    suspend fun deleteTls(id: Long) = withContext(Dispatchers.IO) { db.tlsLogDao().deleteById(id) }
    suspend fun deleteApi(id: Long) = withContext(Dispatchers.IO) { db.apiRequestLogDao().deleteById(id) }
    suspend fun deleteRootAudit(id: Long) = withContext(Dispatchers.IO) { db.rootAuditLogDao().deleteById(id) }

    suspend fun clearPackets() = withContext(Dispatchers.IO) { db.packetLogDao().clearAll() }
    suspend fun clearDns() = withContext(Dispatchers.IO) { db.dnsLogDao().clearAll() }
    suspend fun clearTls() = withContext(Dispatchers.IO) { db.tlsLogDao().clearAll() }
    suspend fun clearApiLogs() = withContext(Dispatchers.IO) { db.apiRequestLogDao().clearAll() }
    suspend fun clearOwnAppDebugLogs() = withContext(Dispatchers.IO) { db.apiRequestLogDao().clearOwnAppDebugLogs() }
    suspend fun clearRootAuditLogs() = withContext(Dispatchers.IO) { db.rootAuditLogDao().clearAll() }

    suspend fun clearAllLocalLogs() = withContext(Dispatchers.IO) {
        db.packetLogDao().clearAll()
        db.dnsLogDao().clearAll()
        db.tlsLogDao().clearAll()
        db.apiRequestLogDao().clearAll()
        db.networkSessionDao().clearAll()
        db.rootAuditLogDao().clearAll()
    }

    suspend fun pruneExpiredLogs(retentionDays: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        db.packetLogDao().pruneOlderThan(cutoff)
        db.dnsLogDao().pruneOlderThan(cutoff)
        db.tlsLogDao().pruneOlderThan(cutoff)
        db.apiRequestLogDao().pruneOlderThan(cutoff)
    }

    suspend fun saveRequestToCollection(
        collectionName: String,
        description: String,
        draft: ApiRequestDraft
    ) = withContext(Dispatchers.IO) {
        val cleanName = collectionName.trim().ifEmpty { "Default Collection" }
        val existing = db.savedCollectionDao().findByName(cleanName)
        val arr = if (existing != null) {
            runCatching { JSONArray(existing.itemsJson) }.getOrElse { JSONArray() }
        } else {
            JSONArray()
        }
        val itemObj = JSONObject().apply {
            put("method", draft.method)
            put("url", draft.url)
            put("bodyType", draft.bodyType)
            put("bodyText", draft.bodyText)
        }
        arr.put(itemObj)
        val now = System.currentTimeMillis()
        if (existing != null) {
            db.savedCollectionDao().update(
                existing.copy(
                    description = description.ifBlank { existing.description },
                    itemsJson = arr.toString(),
                    updatedAt = now
                )
            )
        } else {
            db.savedCollectionDao().insert(
                SavedCollection(
                    name = cleanName,
                    description = description.trim(),
                    itemsJson = arr.toString(),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun deleteCollection(id: Long) = withContext(Dispatchers.IO) {
        db.savedCollectionDao().deleteById(id)
    }

    suspend fun exportCollectionsJson(): String = withContext(Dispatchers.IO) {
        val all = db.savedCollectionDao().getAllSnapshot()
        val root = JSONObject().apply {
            put("app", "UltimateFetcher")
            put("exportedAt", FormatUtils.formatDateTime(System.currentTimeMillis()))
            val arr = JSONArray()
            all.forEach { col ->
                arr.put(
                    JSONObject().apply {
                        put("name", col.name)
                        put("description", col.description)
                        put("items", runCatching { JSONArray(col.itemsJson) }.getOrElse { JSONArray() })
                    }
                )
            }
            put("collections", arr)
        }
        root.toString(2)
    }

    suspend fun importCollectionsJson(rawJson: String): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("collections") ?: return@withContext 0
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim().ifEmpty { "Imported ${i + 1}" }
            val desc = obj.optString("description", "")
            val items = obj.optJSONArray("items") ?: JSONArray()
            db.savedCollectionDao().insert(
                SavedCollection(
                    name = name,
                    description = desc,
                    itemsJson = items.toString(),
                    createdAt = now,
                    updatedAt = now
                )
            )
            imported++
        }
        imported
    }

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(
                    android.content.pm.PackageManager.MATCH_ALL.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
        }
        resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                InstalledAppInfo(packageName = pkg, appName = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    suspend fun buildPacketsCsvExport(limit: Int = 500): String = withContext(Dispatchers.IO) {
        val packets = db.packetLogDao().getRecentSnapshot(limit)
        val sb = StringBuilder()
        sb.appendLine("id,timestamp,protocol,src_ip,src_port,dst_ip,dst_port,size_bytes,direction,blocked,app_package,metadata")
        packets.forEach { p ->
            val safeMeta = p.metadata.replace("\"", "\"\"")
            sb.appendLine(
                "${p.id},\"${FormatUtils.formatDateTime(p.timestamp)}\",${p.protocol},${p.srcIp},${p.srcPort},${p.dstIp},${p.dstPort},${p.packetSize},${p.direction},${p.isBlocked},\"${p.appPackage}\",\"$safeMeta\""
            )
        }
        sb.toString()
    }

    suspend fun buildSanitizedJsonReport(telemetry: LiveNetworkTelemetry? = null): String = withContext(Dispatchers.IO) {
        val packets = db.packetLogDao().getRecentSnapshot(200)
        val dns = db.dnsLogDao().getRecentSnapshot(150)
        val tls = db.tlsLogDao().getRecentSnapshot(150)
        val apis = db.apiRequestLogDao().getRecentSnapshot(100)
        val rootAudits = db.rootAuditLogDao().getRecentSnapshot(50)

        val root = JSONObject().apply {
            put("application", "UltimateFetcher")
            put("developer", "Avinash")
            put("sanitized", true)
            put("generatedAt", FormatUtils.formatDateTime(System.currentTimeMillis()))
            telemetry?.let { t ->
                put(
                    "telemetry",
                    JSONObject().apply {
                        put("activeInterface", t.activeInterface)
                        put("networkType", t.networkType)
                        put("dnsServers", JSONArray(t.dnsServers))
                        put("privateDnsMode", t.privateDnsMode)
                        put("latencyMs", t.latencyMs)
                        put("sessionDownloadedBytes", t.sessionDownloadedBytes)
                        put("sessionUploadedBytes", t.sessionUploadedBytes)
                        put("totalPackets", t.totalPackets)
                        put("tcpPackets", t.tcpPackets)
                        put("udpPackets", t.udpPackets)
                        put("quicBlockedPackets", t.quicBlockedPackets)
                    }
                )
            }
            put("packetCount", packets.size)
            put("dnsLogCount", dns.size)
            put("tlsLogCount", tls.size)
            put("apiLogCount", apis.size)
            put("rootAuditCount", rootAudits.size)

            val pktArr = JSONArray()
            packets.forEach { p ->
                pktArr.put(
                    JSONObject().apply {
                        put("timestamp", FormatUtils.formatDateTime(p.timestamp))
                        put("protocol", p.protocol)
                        put("src", "${p.srcIp}:${p.srcPort}")
                        put("dst", "${p.dstIp}:${p.dstPort}")
                        put("size", p.packetSize)
                        put("blocked", p.isBlocked)
                        put("metadata", p.metadata)
                    }
                )
            }
            put("packets", pktArr)

            val dnsArr = JSONArray()
            dns.forEach { d ->
                dnsArr.put(
                    JSONObject().apply {
                        put("timestamp", FormatUtils.formatDateTime(d.timestamp))
                        put("queryName", d.queryName)
                        put("queryType", d.queryType)
                        put("dnsServer", d.dnsServer)
                        put("protocolClass", d.protocolClass)
                        put("responseIps", d.responseIps)
                        put("latencyMs", d.latencyMs)
                    }
                )
            }
            put("dnsLogs", dnsArr)

            val tlsArr = JSONArray()
            tls.forEach { t ->
                tlsArr.put(
                    JSONObject().apply {
                        put("timestamp", FormatUtils.formatDateTime(t.timestamp))
                        put("sniHostname", t.sniHostname)
                        put("endpoint", "${t.dstIp}:${t.dstPort}")
                        put("tlsVersion", t.tlsVersion)
                        put("alpn", t.alpnProtocols)
                        put("echOrUnavailable", t.isEchOrUnavailable)
                    }
                )
            }
            put("tlsLogs", tlsArr)

            val apiArr = JSONArray()
            apis.forEach { a ->
                apiArr.put(
                    JSONObject().apply {
                        put("timestamp", FormatUtils.formatDateTime(a.timestamp))
                        put("method", a.method)
                        put("url", a.url)
                        put("statusCode", a.statusCode)
                        put("durationMs", a.durationMs)
                        put("requestHeadersRedacted", a.requestHeadersJson)
                        put("responseHeadersRedacted", a.responseHeadersJson)
                    }
                )
            }
            put("apiLogs", apiArr)
        }
        root.toString(2)
    }

    suspend fun writeTempExportAndGetShareUri(fileName: String, content: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
