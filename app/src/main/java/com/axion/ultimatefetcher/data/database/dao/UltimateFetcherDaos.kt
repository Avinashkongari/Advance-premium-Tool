package com.axion.ultimatefetcher.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.axion.ultimatefetcher.data.database.entities.NetworkSession
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.database.entities.SavedCollection
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PacketLog>)

    @Query("SELECT * FROM packet_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PacketLog>>

    @Query("SELECT * FROM packet_logs WHERE protocol = 'QUIC' OR dstPort = 443 ORDER BY timestamp DESC LIMIT :limit")
    fun observeQuicPackets(limit: Int): Flow<List<PacketLog>>

    @Query("SELECT COUNT(*) FROM packet_logs WHERE isBlocked = 1")
    fun observeBlockedQuicCount(): Flow<Int>

    @Query("SELECT * FROM packet_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<PacketLog>

    @Query("DELETE FROM packet_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM packet_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM packet_logs")
    suspend fun clearAll()
}

@Dao
interface DnsLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DNSLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DNSLog>)

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DNSLog>>

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<DNSLog>

    @Query("DELETE FROM dns_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dns_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM dns_logs")
    suspend fun clearAll()
}

@Dao
interface TlsLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TLSLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TLSLog>)

    @Query("SELECT * FROM tls_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TLSLog>>

    @Query("SELECT * FROM tls_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<TLSLog>

    @Query("DELETE FROM tls_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tls_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM tls_logs")
    suspend fun clearAll()
}

@Dao
interface ApiRequestLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ApiRequestLog): Long

    @Query("SELECT * FROM api_request_logs WHERE isOwnAppDebug = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun observeApiLabHistory(limit: Int): Flow<List<ApiRequestLog>>

    @Query("SELECT * FROM api_request_logs WHERE isOwnAppDebug = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun observeOwnAppDebugLogs(limit: Int): Flow<List<ApiRequestLog>>

    @Query("SELECT * FROM api_request_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<ApiRequestLog>>

    @Query("SELECT * FROM api_request_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<ApiRequestLog>

    @Query("DELETE FROM api_request_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM api_request_logs WHERE isOwnAppDebug = 1")
    suspend fun clearOwnAppDebugLogs()

    @Query("DELETE FROM api_request_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM api_request_logs")
    suspend fun clearAll()
}

@Dao
interface NetworkSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: NetworkSession): Long

    @Query("SELECT * FROM network_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NetworkSession>>

    @Query("DELETE FROM network_sessions")
    suspend fun clearAll()
}

@Dao
interface EndpointMonitorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(monitor: EndpointMonitor): Long

    @Update
    suspend fun update(monitor: EndpointMonitor)

    @Query("SELECT * FROM endpoint_monitors ORDER BY id DESC")
    fun observeAll(): Flow<List<EndpointMonitor>>

    @Query("SELECT * FROM endpoint_monitors WHERE isEnabled = 1")
    suspend fun getEnabledSnapshot(): List<EndpointMonitor>

    @Query("DELETE FROM endpoint_monitors WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SavedCollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: SavedCollection): Long

    @Update
    suspend fun update(collection: SavedCollection)

    @Query("SELECT * FROM saved_collections ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedCollection>>

    @Query("SELECT * FROM saved_collections ORDER BY updatedAt DESC")
    suspend fun getAllSnapshot(): List<SavedCollection>

    @Query("SELECT * FROM saved_collections WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): SavedCollection?

    @Query("DELETE FROM saved_collections WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RootAuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RootAuditLog): Long

    @Query("SELECT * FROM root_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RootAuditLog>>

    @Query("SELECT * FROM root_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<RootAuditLog>

    @Query("DELETE FROM root_audit_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM root_audit_logs")
    suspend fun clearAll()
}
