package com.axion.ultimatefetcher.ui.logs

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LogCategory(val label: String) {
    PACKETS("Packets"),
    DNS("DNS"),
    TLS("TLS"),
    API("API"),
    ROOT("Root Audit")
}

enum class DateFilter(val label: String, val windowMs: Long) {
    ALL("All Time", 0L),
    LAST_HOUR("1 Hour", 3_600_000L),
    LAST_24H("24 Hours", 86_400_000L),
    LAST_7D("7 Days", 604_800_000L)
}

data class LogsFilterState(
    val category: LogCategory = LogCategory.PACKETS,
    val searchQuery: String = "",
    val protocolFilter: String = "ALL",
    val dateFilter: DateFilter = DateFilter.ALL,
    val isPaused: Boolean = false,
    val ascending: Boolean = false,
    val pageSize: Int = 60
)

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModel(private val container: AppContainer) : ViewModel() {

    private val _filter = MutableStateFlow(LogsFilterState())
    val filter: StateFlow<LogsFilterState> = _filter.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    @Volatile private var frozenPackets: List<PacketLog> = emptyList()
    @Volatile private var frozenDns: List<DNSLog> = emptyList()
    @Volatile private var frozenTls: List<TLSLog> = emptyList()
    @Volatile private var frozenApi: List<ApiRequestLog> = emptyList()
    @Volatile private var frozenRoot: List<RootAuditLog> = emptyList()

    private val dbQueryLimit = _filter
        .map { maxOf(it.pageSize * 3, 300) }
        .distinctUntilChanged()

    private val rawPacketsFlow = dbQueryLimit.flatMapLatest { limit ->
        container.repository.observePackets(limit = limit)
    }

    private val rawDnsFlow = dbQueryLimit.flatMapLatest { limit ->
        container.repository.observeDnsLogs(limit = limit)
    }

    private val rawTlsFlow = dbQueryLimit.flatMapLatest { limit ->
        container.repository.observeTlsLogs(limit = limit)
    }

    private val rawApiFlow = dbQueryLimit.flatMapLatest { limit ->
        container.repository.observeAllApiLogs(limit = limit)
    }

    private val rawRootFlow = dbQueryLimit.flatMapLatest { limit ->
        container.repository.observeRootAuditLogs(limit = limit)
    }

    val packetLogs: StateFlow<List<PacketLog>> = combine(rawPacketsFlow, _filter) { live, f ->
        val src = if (f.isPaused) frozenPackets else live.also { frozenPackets = it }
        val cutoff = if (f.dateFilter.windowMs > 0L) System.currentTimeMillis() - f.dateFilter.windowMs else 0L
        val q = f.searchQuery.trim()
        src.filter { p ->
            (cutoff == 0L || p.timestamp >= cutoff) &&
                (f.protocolFilter == "ALL" || p.protocol.equals(f.protocolFilter, ignoreCase = true)) &&
                (q.isEmpty() || p.srcIp.contains(q, true) || p.dstIp.contains(q, true) ||
                    p.dstPort.toString().contains(q) || p.srcPort.toString().contains(q) ||
                    p.protocol.contains(q, true) || p.appPackage.contains(q, true) ||
                    p.metadata.contains(q, true))
        }.let { list -> if (f.ascending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }
            .take(f.pageSize)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dnsLogs: StateFlow<List<DNSLog>> = combine(rawDnsFlow, _filter) { live, f ->
        val src = if (f.isPaused) frozenDns else live.also { frozenDns = it }
        val cutoff = if (f.dateFilter.windowMs > 0L) System.currentTimeMillis() - f.dateFilter.windowMs else 0L
        val q = f.searchQuery.trim()
        src.filter { d ->
            (cutoff == 0L || d.timestamp >= cutoff) &&
                (q.isEmpty() || d.queryName.contains(q, true) || d.responseIps.contains(q, true) || d.dnsServer.contains(q, true))
        }.let { list -> if (f.ascending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }
            .take(f.pageSize)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tlsLogs: StateFlow<List<TLSLog>> = combine(rawTlsFlow, _filter) { live, f ->
        val src = if (f.isPaused) frozenTls else live.also { frozenTls = it }
        val cutoff = if (f.dateFilter.windowMs > 0L) System.currentTimeMillis() - f.dateFilter.windowMs else 0L
        val q = f.searchQuery.trim()
        src.filter { t ->
            (cutoff == 0L || t.timestamp >= cutoff) &&
                (q.isEmpty() || t.sniHostname.contains(q, true) || t.dstIp.contains(q, true) || t.tlsVersion.contains(q, true))
        }.let { list -> if (f.ascending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }
            .take(f.pageSize)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiLogs: StateFlow<List<ApiRequestLog>> = combine(rawApiFlow, _filter) { live, f ->
        val src = if (f.isPaused) frozenApi else live.also { frozenApi = it }
        val cutoff = if (f.dateFilter.windowMs > 0L) System.currentTimeMillis() - f.dateFilter.windowMs else 0L
        val q = f.searchQuery.trim()
        src.filter { a ->
            (cutoff == 0L || a.timestamp >= cutoff) &&
                (q.isEmpty() || a.url.contains(q, true) || a.method.contains(q, true) || a.statusCode.toString().contains(q))
        }.let { list -> if (f.ascending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }
            .take(f.pageSize)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootAuditLogs: StateFlow<List<RootAuditLog>> = combine(rawRootFlow, _filter) { live, f ->
        val src = if (f.isPaused) frozenRoot else live.also { frozenRoot = it }
        val cutoff = if (f.dateFilter.windowMs > 0L) System.currentTimeMillis() - f.dateFilter.windowMs else 0L
        val q = f.searchQuery.trim()
        src.filter { r ->
            (cutoff == 0L || r.timestamp >= cutoff) &&
                (q.isEmpty() || r.command.contains(q, true) || r.category.contains(q, true) || r.outputPreview.contains(q, true))
        }.let { list -> if (f.ascending) list.sortedBy { it.timestamp } else list.sortedByDescending { it.timestamp } }
            .take(f.pageSize)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectCategory(cat: LogCategory) = _filter.update { it.copy(category = cat, pageSize = 60) }
    fun updateSearch(query: String) = _filter.update { it.copy(searchQuery = query) }
    fun selectDateFilter(df: DateFilter) = _filter.update { it.copy(dateFilter = df) }
    fun selectProtocol(proto: String) = _filter.update { it.copy(protocolFilter = proto) }
    fun togglePauseStream() = _filter.update { it.copy(isPaused = !it.isPaused) }
    fun toggleSortOrder() = _filter.update { it.copy(ascending = !it.ascending) }
    fun loadMore() = _filter.update { it.copy(pageSize = it.pageSize + 60) }

    fun deletePacket(id: Long) { viewModelScope.launch { container.repository.deletePacket(id) } }
    fun deleteDns(id: Long) { viewModelScope.launch { container.repository.deleteDns(id) } }
    fun deleteTls(id: Long) { viewModelScope.launch { container.repository.deleteTls(id) } }
    fun deleteApi(id: Long) { viewModelScope.launch { container.repository.deleteApi(id) } }
    fun deleteRootAudit(id: Long) { viewModelScope.launch { container.repository.deleteRootAudit(id) } }

    fun clearCurrentCategory() {
        viewModelScope.launch {
            when (_filter.value.category) {
                LogCategory.PACKETS -> {
                    container.repository.clearPackets()
                    frozenPackets = emptyList()
                }
                LogCategory.DNS -> {
                    container.repository.clearDns()
                    frozenDns = emptyList()
                }
                LogCategory.TLS -> {
                    container.repository.clearTls()
                    frozenTls = emptyList()
                }
                LogCategory.API -> {
                    container.repository.clearApiLogs()
                    frozenApi = emptyList()
                }
                LogCategory.ROOT -> {
                    container.repository.clearRootAuditLogs()
                    frozenRoot = emptyList()
                }
            }
            postStatus("Cleared ${_filter.value.category.label} logs from Room database.")
        }
    }

    fun exportCsvShareUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val csv = container.repository.buildPacketsCsvExport(1000)
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_packets_${System.currentTimeMillis()}.csv",
                csv
            )
            onReady(uri)
        }
    }

    fun exportJsonReportShareUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val json = container.repository.buildSanitizedJsonReport(container.telemetryMonitor.telemetry.value)
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_logs_${System.currentTimeMillis()}.json",
                json
            )
            onReady(uri)
        }
    }

    private fun postStatus(msg: String) {
        viewModelScope.launch {
            _statusMessage.value = msg
            delay(3000L)
            if (_statusMessage.value == msg) _statusMessage.value = null
        }
    }
}
