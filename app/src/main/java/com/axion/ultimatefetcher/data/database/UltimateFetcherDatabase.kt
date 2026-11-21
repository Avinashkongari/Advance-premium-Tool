package com.axion.ultimatefetcher.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.axion.ultimatefetcher.data.database.dao.ApiRequestLogDao
import com.axion.ultimatefetcher.data.database.dao.DnsLogDao
import com.axion.ultimatefetcher.data.database.dao.EndpointMonitorDao
import com.axion.ultimatefetcher.data.database.dao.NetworkSessionDao
import com.axion.ultimatefetcher.data.database.dao.PacketLogDao
import com.axion.ultimatefetcher.data.database.dao.RootAuditLogDao
import com.axion.ultimatefetcher.data.database.dao.SavedCollectionDao
import com.axion.ultimatefetcher.data.database.dao.TlsLogDao
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.axion.ultimatefetcher.data.database.entities.NetworkSession
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.database.entities.SavedCollection
import com.axion.ultimatefetcher.data.database.entities.TLSLog

@Database(
    entities = [
        PacketLog::class,
        DNSLog::class,
        TLSLog::class,
        ApiRequestLog::class,
        NetworkSession::class,
        EndpointMonitor::class,
        SavedCollection::class,
        RootAuditLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class UltimateFetcherDatabase : RoomDatabase() {
    abstract fun packetLogDao(): PacketLogDao
    abstract fun dnsLogDao(): DnsLogDao
    abstract fun tlsLogDao(): TlsLogDao
    abstract fun apiRequestLogDao(): ApiRequestLogDao
    abstract fun networkSessionDao(): NetworkSessionDao
    abstract fun endpointMonitorDao(): EndpointMonitorDao
    abstract fun savedCollectionDao(): SavedCollectionDao
    abstract fun rootAuditLogDao(): RootAuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: UltimateFetcherDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Forward-compatible migration scaffold for future schema extensions
            }
        }

        fun getInstance(context: Context): UltimateFetcherDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UltimateFetcherDatabase::class.java,
                    "ultimate_fetcher.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
