package com.axion.ultimatefetcher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axion.ultimatefetcher.data.SettingsDataStore
import com.axion.ultimatefetcher.data.database.UltimateFetcherDatabase
import com.axion.ultimatefetcher.data.database.entities.DNSLog
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.database.entities.TLSLog
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.domain.models.PipelineStageStatus
import com.axion.ultimatefetcher.domain.models.VpnLiveState
import com.axion.ultimatefetcher.network.vpn.AppFilterValidator
import com.axion.ultimatefetcher.network.vpn.PacketCapturePipeline
import com.axion.ultimatefetcher.network.vpn.PacketCaptureSink
import com.axion.ultimatefetcher.network.vpn.PacketParser
import com.axion.ultimatefetcher.network.vpn.VpnAppFilterTarget
import com.axion.ultimatefetcher.network.vpn.VpnMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnAppFilterAndPipelineTest {

    private lateinit var context: Context
    private lateinit var db: UltimateFetcherDatabase
    private lateinit var repository: UltimateFetcherRepository
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, UltimateFetcherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = UltimateFetcherRepository(context, db)
        settingsDataStore = SettingsDataStore(context)
        VpnMonitorService.resetSessionCounters()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 1: No app selected -> monitor all apps, exclude self
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 1 - no app selected monitors all apps and excludes self package`() = runBlocking {
        settingsDataStore.clearSelectedApps()
        val settings = settingsDataStore.settingsFlow.first()
        assertTrue(settings.selectedAppPackages.isEmpty())
        assertEquals("All Launchable Apps", settings.selectedAppName)

        val validation = AppFilterValidator.validateSelectedPackages(context, settings.selectedAppPackages)
        assertTrue(validation.monitorAllApps)
        assertNull(validation.fatalError)
        assertTrue(validation.validPackages.isEmpty())

        val recorder = RecordingVpnFilterTarget()
        val (applied, error) = AppFilterValidator.applyToBuilder(validation, context.packageName, recorder)
        assertNull(error)
        assertTrue(applied.isEmpty())
        assertTrue(recorder.allowed.isEmpty())
        assertEquals(listOf(context.packageName), recorder.disallowed)
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 2: One app selected -> persisted + addAllowedApplication
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 2 - single app selected persists in DataStore and applies addAllowedApplication`() = runBlocking {
        installFakeLaunchableApp("com.target.browser", "Target Browser")

        settingsDataStore.setSelectedAppFilter("com.target.browser", "Target Browser")
        val settings = settingsDataStore.settingsFlow.first()
        assertEquals(listOf("com.target.browser"), settings.selectedAppPackages)
        assertEquals("Target Browser", settings.selectedAppName)

        val launchable = repository.loadLaunchableApps()
        assertTrue(launchable.any { it.packageName == "com.target.browser" && it.appName == "Target Browser" })

        val validation = AppFilterValidator.validateSelectedPackages(context, settings.selectedAppPackages)
        assertFalse(validation.monitorAllApps)
        assertNull(validation.fatalError)
        assertEquals(listOf("com.target.browser"), validation.validPackages)
        assertEquals(listOf("Target Browser"), validation.validAppNames)

        val recorder = RecordingVpnFilterTarget()
        val (applied, error) = AppFilterValidator.applyToBuilder(validation, context.packageName, recorder)
        assertNull(error)
        assertEquals(listOf("com.target.browser"), applied)
        assertEquals(listOf("com.target.browser"), recorder.allowed)
        assertTrue(recorder.disallowed.isEmpty())
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 3: Multiple apps selected -> persisted + all allowed
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 3 - multiple apps selected persists all in DataStore and allows every selected package`() = runBlocking {
        installFakeLaunchableApp("com.alpha.chat", "Alpha Chat")
        installFakeLaunchableApp("com.beta.stream", "Beta Stream")
        installFakeLaunchableApp("com.gamma.mail", "Gamma Mail")

        settingsDataStore.clearSelectedApps()
        settingsDataStore.toggleSelectedApp("com.alpha.chat", "Alpha Chat")
        settingsDataStore.toggleSelectedApp("com.beta.stream", "Beta Stream")
        settingsDataStore.toggleSelectedApp("com.gamma.mail", "Gamma Mail")

        val settings = settingsDataStore.settingsFlow.first()
        assertEquals(listOf("com.alpha.chat", "com.beta.stream", "com.gamma.mail"), settings.selectedAppPackages)
        assertEquals("Alpha Chat, Beta Stream, Gamma Mail", settings.selectedAppName)

        val validation = AppFilterValidator.validateSelectedPackages(context, settings.selectedAppPackages)
        assertFalse(validation.monitorAllApps)
        assertNull(validation.fatalError)
        assertEquals(3, validation.validPackages.size)

        val recorder = RecordingVpnFilterTarget()
        val (applied, error) = AppFilterValidator.applyToBuilder(validation, context.packageName, recorder)
        assertNull(error)
        assertEquals(setOf("com.alpha.chat", "com.beta.stream", "com.gamma.mail"), applied.toSet())
        assertEquals(setOf("com.alpha.chat", "com.beta.stream", "com.gamma.mail"), recorder.allowed.toSet())
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 4 + BUG 2: Continuous multi-packet capture -> Room DB
    // Verifies 30+ packets (IPv4 TCP, IPv6 w/ extension header, UDP, QUIC,
    // DNS, TLS ClientHello SNI, malformed frames, and duplicate echo guard).
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 4 and bug 2 - continuous packet capture parses IPv4 IPv6 TCP UDP DNS TLS rejects malformed and saves all to Room`() = runBlocking {
        val frames = mutableListOf<ByteArray>()

        // 15 IPv4 TCP packets to distinct ports
        for (i in 0 until 15) {
            frames.add(buildIpv4TcpPacket("10.215.173.1", 40000 + i, "93.184.216.34", 443 + (i % 3), byteArrayOf(0x01, i.toByte())))
        }
        // 6 IPv6 TCP packets with Hop-by-Hop extension header (NextHeader=0 -> TCP=6)
        for (i in 0 until 6) {
            frames.add(buildIpv6TcpWithHopByHopExtension("fd00:1::1", 51000 + i, "2606:2800:220:1:248:1893:25c8:1946", 443, byteArrayOf(0x02, i.toByte())))
        }
        // 5 IPv4 UDP DNS query packets (distinct hostnames + txIds)
        for (i in 0 until 5) {
            val dnsQuery = PacketParser.buildDnsQueryPacket("api$i.example.com", "A", 0x2000 + i)
            frames.add(PacketParser.buildIpv4UdpPacket("10.215.173.1", 53000 + i, "1.1.1.1", 53, dnsQuery)!!)
        }
        // 4 IPv4 UDP QUIC packets on port 443
        for (i in 0 until 4) {
            frames.add(PacketParser.buildIpv4UdpPacket("10.215.173.1", 54000 + i, "172.217.16.14", 443, byteArrayOf(0xC0.toByte(), i.toByte(), 0x00, 0x01))!!)
        }
        // 2 IPv4 TCP packets with real TLS ClientHello (SNI + ALPN + TLS 1.3)
        val tlsPayload1 = buildTlsClientHelloBytes("secure.ultimatefetcher.dev", listOf("h2", "http/1.1"))
        val tlsPayload2 = buildTlsClientHelloBytes("telemetry.example.org", listOf("h2"))
        frames.add(buildIpv4TcpPacket("10.215.173.1", 49100, "104.16.132.229", 443, tlsPayload1))
        frames.add(buildIpv4TcpPacket("10.215.173.1", 49101, "104.16.133.229", 443, tlsPayload2))

        // 3 Malformed / truncated frames (must be safely rejected, never crash)
        frames.add(byteArrayOf(0x45, 0x00, 0x00)) // too short (< 20 bytes)
        frames.add(byteArrayOf(0x95.toByte(), 0x00, 0x00, 0x28, 0, 0, 0, 0, 64, 6, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)) // invalid IP version 9
        frames.add(ByteArray(24) { idx -> if (idx == 0) 0x45 else if (idx == 9) 17 else 0 }.also {
            // IPv4 UDP with truncated UDP header (only 4 bytes after IP header)
            it[2] = 0; it[3] = 24
        })

        // 1 Instant duplicate copy of the final valid frame (back-to-back within <15ms -> deduplicated)
        val duplicateFrame = buildIpv4TcpPacket("10.215.173.1", 49999, "8.8.8.8", 80, byteArrayOf(0x47, 0x45, 0x54))
        frames.add(duplicateFrame)
        frames.add(duplicateFrame.copyOf())

        val validUniqueCount = 15 + 6 + 5 + 4 + 2 + 1 // = 33 unique valid packets
        val malformedCount = 3

        val liveState = MutableStateFlow(
            VpnLiveState(
                isRunning = true,
                vpnInterfaceEstablished = true,
                vpnConnectedState = PipelineStageStatus.ACTIVE,
                selectedAppPackages = listOf("com.target.browser"),
                selectedAppNames = listOf("Target Browser")
            )
        )
        val dnsCallbacks = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sink = object : PacketCaptureSink {
            override suspend fun insertPackets(items: List<PacketLog>) = repository.insertPackets(items)
            override suspend fun insertDnsLogs(items: List<DNSLog>) = repository.insertDnsLogs(items)
            override suspend fun insertTlsLogs(items: List<TLSLog>) = repository.insertTlsLogs(items)
        }

        val pipeline = PacketCapturePipeline(
            scope = scope,
            liveState = liveState,
            sink = sink,
            quicBlockFlag = AtomicBoolean(true),
            appScopeLabel = "com.target.browser",
            onDnsQueryObserved = null, // Let pipeline persist DNS logs directly into Room
            batchSize = 12,
            flushIntervalMs = 150L
        )

        val framedInput = FramedPacketInputStream(frames)
        pipeline.start(framedInput)

        // Wait until all frames are read, processed, and cleanly drained to Room
        var waited = 0L
        while (liveState.value.packetsRead < frames.size && waited < 4000L) {
            delay(25L)
            waited += 25L
        }
        pipeline.stopAndDrain()
        scope.cancel()

        val state = liveState.value
        assertEquals(frames.size.toLong(), state.packetsRead)
        assertEquals(validUniqueCount.toLong(), state.packetsParsed)
        assertEquals(malformedCount.toLong(), state.packetsRejected)
        assertEquals(validUniqueCount.toLong(), state.packetsSavedToDb)
        assertEquals(0L, state.dbInsertErrors)
        assertTrue(state.lastPacketTimestamp > 0L)
        assertTrue(state.isActivelyCapturing)

        // Verify all 4 pipeline stages independently reached ACTIVE
        assertEquals(PipelineStageStatus.ACTIVE, state.vpnConnectedState)
        assertEquals(PipelineStageStatus.ACTIVE, state.packetReadingState)
        assertEquals(PipelineStageStatus.ACTIVE, state.packetProcessingState)
        assertEquals(PipelineStageStatus.ACTIVE, state.databaseSavingState)

        // Verify protocol breakdown counters
        assertEquals(24L, state.tcpPackets) // 15 IPv4 TCP + 6 IPv6 TCP + 2 TLS TCP + 1 deduped TCP
        assertEquals(9L, state.udpPackets)  // 5 DNS UDP + 4 QUIC UDP
        assertEquals(5L, state.dnsPackets)
        assertEquals(4L, state.quicPackets)
        assertEquals(4L, state.blockedQuicPackets)

        // Verify actual Room database rows persisted (not stuck at 1-2 entries!)
        val savedPackets = db.packetLogDao().getRecentSnapshot(100)
        assertEquals(validUniqueCount, savedPackets.size)
        assertTrue(savedPackets.all { it.appPackage == "com.target.browser" })
        assertTrue(savedPackets.any { it.srcIp.contains(":") }) // IPv6 captured

        val savedDns = db.dnsLogDao().getRecentSnapshot(50)
        assertEquals(5, savedDns.size)
        assertTrue(savedDns.any { it.queryName == "api0.example.com" })
        assertTrue(savedDns.any { it.queryName == "api4.example.com" })

        val savedTls = db.tlsLogDao().getRecentSnapshot(50)
        assertEquals(2, savedTls.size)
        assertTrue(savedTls.any { it.sniHostname == "secure.ultimatefetcher.dev" && it.tlsVersion == "TLS 1.3" })
        assertTrue(savedTls.any { it.sniHostname == "telemetry.example.org" })
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 5: Selected app uninstalled -> explicit error + prune
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 5 - uninstalled selected app surfaces explicit error and prunes stale DataStore entry`() = runBlocking {
        installFakeLaunchableApp("com.still.installed", "Still Installed")

        // Case A: ONLY uninstalled app selected -> fatal error, VPN refuses silently falling back
        settingsDataStore.setSelectedAppFilter("com.removed.ghost", "Ghost App")
        val allMissing = AppFilterValidator.validateSelectedPackages(context, listOf("com.removed.ghost"))
        assertNotNull(allMissing.fatalError)
        assertTrue(allMissing.fatalError!!.contains("uninstalled or unavailable", ignoreCase = true))
        assertEquals(listOf("com.removed.ghost"), allMissing.unavailablePackages)

        // Case B: Mix of valid + uninstalled -> keeps valid, reports unavailable, DataStore prunes unavailable
        settingsDataStore.setSelectedApps(
            mapOf(
                "com.still.installed" to "Still Installed",
                "com.removed.ghost" to "Ghost App"
            )
        )
        val mixed = AppFilterValidator.validateSelectedPackages(
            context,
            listOf("com.still.installed", "com.removed.ghost")
        )
        assertNull(mixed.fatalError)
        assertEquals(listOf("com.still.installed"), mixed.validPackages)
        assertEquals(listOf("com.removed.ghost"), mixed.unavailablePackages)

        settingsDataStore.pruneUnavailableApps(mixed.unavailablePackages)
        val afterPrune = settingsDataStore.settingsFlow.first()
        assertEquals(listOf("com.still.installed"), afterPrune.selectedAppPackages)
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 6: VPN permission denied -> explicit error, not live
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 6 - VPN permission denied sets explicit error state and never reports actively capturing`() {
        VpnMonitorService.reportPermissionDenied()
        val state = VpnMonitorService.liveState.value
        assertFalse(state.isRunning)
        assertFalse(state.vpnInterfaceEstablished)
        assertFalse(state.isActivelyCapturing)
        assertEquals(PipelineStageStatus.ERROR, state.vpnConnectedState)
        assertEquals(PipelineStageStatus.IDLE, state.packetReadingState)
        assertNotNull(state.lastError)
        assertTrue(state.lastError!!.contains("denied", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // APP FILTER SCENARIO 7: Another VPN already active -> explicit conflict error
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 7 - another VPN already active blocks start with explicit conflict error`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        assertNotNull(activeNetwork)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        Shadows.shadowOf(cm).setNetworkCapabilities(activeNetwork, caps)

        // Start VpnMonitorService through Robolectric ServiceController
        val controller = Robolectric.buildService(VpnMonitorService::class.java).create()
        val startIntent = Intent(context, VpnMonitorService::class.java).apply {
            action = "com.axion.ultimatefetcher.VPN_START"
            putStringArrayListExtra("extra_target_apps", arrayListOf())
            putExtra("extra_dns_server", "1.1.1.1")
        }
        controller.withIntent(startIntent).startCommand(0, 1)

        val state = VpnMonitorService.liveState.value
        assertFalse(state.isRunning)
        assertFalse(state.vpnInterfaceEstablished)
        assertFalse(state.isActivelyCapturing)
        assertEquals(PipelineStageStatus.ERROR, state.vpnConnectedState)
        assertNotNull(state.lastError)
        assertTrue(state.lastError!!.contains("Another VPN is already active", ignoreCase = true))

        controller.destroy()
    }

    // -------------------------------------------------------------------------
    // EXTRA VERIFICATION: Honest status & IPv4/IPv6 DNS reply packet builders
    // -------------------------------------------------------------------------
    @Test
    fun `established VPN with zero packets is never reported as actively capturing`() {
        val waitingState = VpnLiveState(
            isRunning = true,
            vpnInterfaceEstablished = true,
            vpnConnectedState = PipelineStageStatus.ACTIVE,
            packetReadingState = PipelineStageStatus.WAITING,
            packetsRead = 0L
        )
        assertFalse(waitingState.isActivelyCapturing)
    }

    @Test
    fun `IPv4 and IPv6 UDP response packet builders round trip cleanly through PacketParser`() {
        val dnsPayload = PacketParser.buildDnsQueryPacket("dns.google", "AAAA", 0xABCD)

        val ipv4Bytes = PacketParser.buildIpv4UdpPacket("1.1.1.1", 53, "10.215.173.1", 51234, dnsPayload)
        assertNotNull(ipv4Bytes)
        val parsedV4 = PacketParser.parseIpPacket(ipv4Bytes!!, ipv4Bytes.size)
        assertNotNull(parsedV4)
        assertEquals(4, parsedV4!!.ipVersion)
        assertEquals("DNS", parsedV4.protocol)
        assertEquals("UDP", parsedV4.transportProtocol)
        assertEquals("1.1.1.1", parsedV4.srcIp)
        assertEquals(53, parsedV4.srcPort)
        assertEquals("10.215.173.1", parsedV4.dstIp)
        assertEquals(51234, parsedV4.dstPort)

        val ipv6Bytes = PacketParser.buildIpv6UdpPacket("2606:4700:4700::1111", 53, "fd00:1::1", 51235, dnsPayload)
        assertNotNull(ipv6Bytes)
        val parsedV6 = PacketParser.parseIpPacket(ipv6Bytes!!, ipv6Bytes.size)
        assertNotNull(parsedV6)
        assertEquals(6, parsedV6!!.ipVersion)
        assertEquals("DNS", parsedV6.protocol)
        assertEquals("UDP", parsedV6.transportProtocol)
        assertEquals(53, parsedV6.srcPort)
        assertEquals(51235, parsedV6.dstPort)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun installFakeLaunchableApp(packageName: String, label: String) {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.nonLocalizedLabel = label
            this.enabled = true
        }
        val pkgInfo = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = appInfo
        }
        shadowPm.installPackage(pkgInfo)

        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "$packageName.MainActivity"
                this.applicationInfo = appInfo
                this.nonLocalizedLabel = label
            }
            nonLocalizedLabel = label
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    private class RecordingVpnFilterTarget : VpnAppFilterTarget {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        override fun addAllowedApplication(packageName: String) { allowed.add(packageName) }
        override fun addDisallowedApplication(packageName: String) { disallowed.add(packageName) }
    }

    private class FramedPacketInputStream(private val frames: List<ByteArray>) : InputStream() {
        private var index = 0
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index >= frames.size) return -1
            val frame = frames[index++]
            val copyLen = frame.size.coerceAtMost(len)
            System.arraycopy(frame, 0, b, off, copyLen)
            return copyLen
        }
    }

    private fun buildIpv4TcpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 20 + payload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45
        pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[8] = 64
        pkt[9] = 6 // TCP
        System.arraycopy(InetAddress.getByName(srcIp).address, 0, pkt, 12, 4)
        System.arraycopy(InetAddress.getByName(dstIp).address, 0, pkt, 16, 4)
        // TCP header (20 bytes)
        pkt[20] = ((srcPort ushr 8) and 0xFF).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = ((dstPort ushr 8) and 0xFF).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[32] = 0x50 // Data offset = 5 (20 bytes)
        pkt[33] = 0x18 // PSH + ACK
        System.arraycopy(payload, 0, pkt, 40, payload.size)
        return pkt
    }

    private fun buildIpv6TcpWithHopByHopExtension(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val extLen = 8
        val tcpLen = 20 + payload.size
        val payloadLen = extLen + tcpLen
        val pkt = ByteArray(40 + payloadLen)
        pkt[0] = 0x60 // IPv6
        pkt[4] = ((payloadLen ushr 8) and 0xFF).toByte()
        pkt[5] = (payloadLen and 0xFF).toByte()
        pkt[6] = 0 // Next header = Hop-by-Hop extension
        pkt[7] = 64
        System.arraycopy(InetAddress.getByName(srcIp).address, 0, pkt, 8, 16)
        System.arraycopy(InetAddress.getByName(dstIp).address, 0, pkt, 24, 16)
        // Hop-by-Hop extension header (8 bytes): nextHeader=6 (TCP), hdrExtLen=0 => (0+1)*8 = 8 bytes
        pkt[40] = 6
        pkt[41] = 0
        // TCP header at offset 48
        pkt[48] = ((srcPort ushr 8) and 0xFF).toByte()
        pkt[49] = (srcPort and 0xFF).toByte()
        pkt[50] = ((dstPort ushr 8) and 0xFF).toByte()
        pkt[51] = (dstPort and 0xFF).toByte()
        pkt[60] = 0x50
        pkt[61] = 0x18
        System.arraycopy(payload, 0, pkt, 68, payload.size)
        return pkt
    }

    private fun buildTlsClientHelloBytes(sniHost: String, alpns: List<String>): ByteArray {
        val extOut = ByteArrayOutputStream()
        // SNI extension (0x0000)
        val hostBytes = sniHost.toByteArray(Charsets.US_ASCII)
        val sniListLen = 1 + 2 + hostBytes.size
        val sniExtLen = 2 + sniListLen
        extOut.write(0x00); extOut.write(0x00)
        extOut.write((sniExtLen ushr 8) and 0xFF); extOut.write(sniExtLen and 0xFF)
        extOut.write((sniListLen ushr 8) and 0xFF); extOut.write(sniListLen and 0xFF)
        extOut.write(0x00) // host_name
        extOut.write((hostBytes.size ushr 8) and 0xFF); extOut.write(hostBytes.size and 0xFF)
        extOut.write(hostBytes)

        // ALPN extension (0x0010)
        if (alpns.isNotEmpty()) {
            val alpnBody = ByteArrayOutputStream()
            alpns.forEach { p ->
                val pb = p.toByteArray(Charsets.US_ASCII)
                alpnBody.write(pb.size)
                alpnBody.write(pb)
            }
            val alpnBytes = alpnBody.toByteArray()
            val alpnExtLen = 2 + alpnBytes.size
            extOut.write(0x00); extOut.write(0x10)
            extOut.write((alpnExtLen ushr 8) and 0xFF); extOut.write(alpnExtLen and 0xFF)
            extOut.write((alpnBytes.size ushr 8) and 0xFF); extOut.write(alpnBytes.size and 0xFF)
            extOut.write(alpnBytes)
        }

        // Supported versions extension (0x002B) advertising TLS 1.3 (0x0304)
        extOut.write(0x00); extOut.write(0x2B)
        extOut.write(0x00); extOut.write(0x03)
        extOut.write(0x02); extOut.write(0x03); extOut.write(0x04)

        val extBytes = extOut.toByteArray()

        val body = ByteArrayOutputStream()
        body.write(0x03); body.write(0x03) // Legacy TLS 1.2
        body.write(ByteArray(32) { it.toByte() }) // Random
        body.write(0x00) // Session ID len 0
        body.write(0x00); body.write(0x02); body.write(0x13); body.write(0x01) // Cipher suite TLS_AES_128_GCM_SHA256
        body.write(0x01); body.write(0x00) // Compression null
        body.write((extBytes.size ushr 8) and 0xFF); body.write(extBytes.size and 0xFF)
        body.write(extBytes)
        val bodyBytes = body.toByteArray()

        val record = ByteArrayOutputStream()
        val hsTotal = 4 + bodyBytes.size
        record.write(0x16) // Handshake
        record.write(0x03); record.write(0x01) // Record version TLS 1.0
        record.write((hsTotal ushr 8) and 0xFF); record.write(hsTotal and 0xFF)
        record.write(0x01) // ClientHello
        record.write((bodyBytes.size ushr 16) and 0xFF)
        record.write((bodyBytes.size ushr 8) and 0xFF)
        record.write(bodyBytes.size and 0xFF)
        record.write(bodyBytes)
        return record.toByteArray()
    }
}
