package com.axion.ultimatefetcher

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axion.ultimatefetcher.data.database.UltimateFetcherDatabase
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.ui.logs.DateFilter
import com.axion.ultimatefetcher.ui.logs.LogCategory
import com.axion.ultimatefetcher.ui.logs.LogsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogExplorerAndRoomPersistenceTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var viewModel: LogsViewModel

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        container = (context as UltimateFetcherApplication).container
        container.repository.clearAllLocalLogs()
        viewModel = LogsViewModel(container)
    }

    @After
    fun tearDown() = runBlocking {
        container.repository.clearAllLocalLogs()
    }

    @Test
    fun `logs persist in Room across database close and reopen simulating app restart`() = runBlocking {
        val dbName = "restart_persistence_test.db"
        context.deleteDatabase(dbName)

        // First "app launch": insert records and close DB
        val db1 = Room.databaseBuilder(context, UltimateFetcherDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        db1.packetLogDao().insertAll(
            listOf(
                PacketLog(timestamp = now, protocol = "TCP", srcIp = "10.0.0.2", srcPort = 44100, dstIp = "1.1.1.1", dstPort = 443, packetSize = 512, appPackage = "com.demo.app"),
                PacketLog(timestamp = now + 10, protocol = "UDP", srcIp = "10.0.0.2", srcPort = 51200, dstIp = "8.8.8.8", dstPort = 53, packetSize = 84, appPackage = "com.demo.app")
            )
        )
        db1.dnsLogDao().insert(DNSLog(timestamp = now, queryName = "persist.example.com", queryType = "A", dnsServer = "1.1.1.1", responseIps = "93.184.216.34", latencyMs = 14L))
        db1.close()

        // Second "app launch": reopen DB and verify all records survived
        val db2 = Room.databaseBuilder(context, UltimateFetcherDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        val restoredPackets = db2.packetLogDao().getRecentSnapshot(50)
        val restoredDns = db2.dnsLogDao().getRecentSnapshot(50)
        db2.close()
        context.deleteDatabase(dbName)

        assertEquals(2, restoredPackets.size)
        assertEquals(1, restoredDns.size)
        assertEquals("persist.example.com", restoredDns.first().queryName)
    }

    @Test
    fun `logs update live via Flow without reopening and support search protocol date pause resume pagination export and clear`() = runBlocking {
        // Keep active collector on packetLogs (like Compose collectAsStateWithLifecycle)
        val collectorJob = launch { viewModel.packetLogs.collect {} }

        val now = System.currentTimeMillis()
        // 1. Insert initial batch -> verify live Flow updates automatically
        val initialBatch = (1..75).map { idx ->
            val proto = when (idx % 3) {
                0 -> "TCP"
                1 -> "UDP"
                else -> "DNS"
            }
            PacketLog(
                timestamp = now - idx * 1000L,
                protocol = proto,
                srcIp = "10.215.173.1",
                srcPort = 30000 + idx,
                dstIp = if (idx == 5) "203.0.113.77" else "198.51.100.${idx % 200}",
                dstPort = if (proto == "DNS") 53 else 443,
                packetSize = 128 + idx,
                appPackage = "com.target.browser",
                metadata = if (idx == 5) "TLS 1.3 SNI=payments.acme.test" else ""
            )
        }
        // Also insert 1 old record (5 days ago) to test DateFilter.LAST_HOUR
        val oldPacket = PacketLog(
            timestamp = now - 5L * 24L * 3_600_000L,
            protocol = "TCP",
            srcIp = "10.215.173.1",
            srcPort = 22222,
            dstIp = "192.0.2.99",
            dstPort = 80,
            packetSize = 60,
            appPackage = "com.old.app"
        )
        container.repository.insertPackets(initialBatch + oldPacket)

        awaitCondition { viewModel.packetLogs.value.size == 60 } // Default pageSize is 60
        assertEquals(60, viewModel.packetLogs.value.size)

        // 2. Dynamic Pagination (Load More) -> grows past 60 to all 76 rows
        viewModel.loadMore()
        awaitCondition { viewModel.packetLogs.value.size == 76 }
        assertEquals(76, viewModel.packetLogs.value.size)

        // 3. Protocol Filter (TCP only vs UDP only)
        viewModel.selectProtocol("TCP")
        awaitCondition { viewModel.packetLogs.value.isNotEmpty() && viewModel.packetLogs.value.all { it.protocol == "TCP" } }
        assertTrue(viewModel.packetLogs.value.all { it.protocol == "TCP" })

        viewModel.selectProtocol("UDP")
        awaitCondition { viewModel.packetLogs.value.isNotEmpty() && viewModel.packetLogs.value.all { it.protocol == "UDP" } }
        assertTrue(viewModel.packetLogs.value.all { it.protocol == "UDP" })

        viewModel.selectProtocol("ALL")
        awaitCondition { viewModel.packetLogs.value.size == 76 }

        // 4. Search Filter (matches IP or metadata SNI)
        viewModel.updateSearch("payments.acme.test")
        awaitCondition { viewModel.packetLogs.value.size == 1 }
        assertEquals("203.0.113.77", viewModel.packetLogs.value.first().dstIp)
        viewModel.updateSearch("")
        awaitCondition { viewModel.packetLogs.value.size == 76 }

        // 5. Date Filter (LAST_HOUR excludes the 5-day-old packet)
        viewModel.selectDateFilter(DateFilter.LAST_HOUR)
        awaitCondition { viewModel.packetLogs.value.size == 75 }
        assertFalse(viewModel.packetLogs.value.any { it.dstIp == "192.0.2.99" })
        viewModel.selectDateFilter(DateFilter.ALL)
        awaitCondition { viewModel.packetLogs.value.size == 76 }

        // 6. Pause & Resume: pausing freezes visible list even when new packets arrive in Room
        viewModel.togglePauseStream()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.filter.value.isPaused)
        val countWhenPaused = viewModel.packetLogs.value.size
        container.repository.insertPackets(
            listOf(
                PacketLog(timestamp = now + 5000L, protocol = "TCP", srcIp = "10.215.173.1", srcPort = 61001, dstIp = "1.0.0.1", dstPort = 443, packetSize = 400, appPackage = "com.target.browser"),
                PacketLog(timestamp = now + 5001L, protocol = "UDP", srcIp = "10.215.173.1", srcPort = 61002, dstIp = "1.0.0.1", dstPort = 443, packetSize = 400, appPackage = "com.target.browser")
            )
        )
        repeat(8) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(25L)
        }
        assertEquals(countWhenPaused, viewModel.packetLogs.value.size)

        // Resume -> new packets appear immediately from Room Flow
        viewModel.togglePauseStream()
        assertFalse(viewModel.filter.value.isPaused)
        awaitCondition { viewModel.packetLogs.value.size == 78 }
        assertEquals(78, viewModel.packetLogs.value.size)

        // 7. CSV Export and Sanitized JSON Export
        val csv = container.repository.buildPacketsCsvExport(500)
        assertTrue(csv.startsWith("id,timestamp,protocol,src_ip,src_port,dst_ip,dst_port"))
        assertTrue(csv.contains("203.0.113.77"))
        assertTrue(csv.contains("payments.acme.test"))

        val jsonStr = container.repository.buildSanitizedJsonReport(container.telemetryMonitor.telemetry.value)
        val json = JSONObject(jsonStr)
        assertEquals("UltimateFetcher", json.getString("application"))
        assertTrue(json.getBoolean("sanitized"))
        assertEquals(78, json.getInt("packetCount"))

        // 8. Clear Logs actually deletes records from Room DB
        viewModel.selectCategory(LogCategory.PACKETS)
        viewModel.clearCurrentCategory()
        awaitCondition { viewModel.packetLogs.value.isEmpty() }
        assertEquals(0, container.database.packetLogDao().getRecentSnapshot(50).size)

        collectorJob.cancel()
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            delay(25L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
