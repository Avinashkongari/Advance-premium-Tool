package com.axion.ultimatefetcher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axion.ultimatefetcher.data.AppThemeMode
import com.axion.ultimatefetcher.data.SettingsDataStore
import com.axion.ultimatefetcher.data.database.UltimateFetcherDatabase
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.axion.ultimatefetcher.data.database.entities.PacketLog
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.domain.models.ApiRequestDraft
import com.axion.ultimatefetcher.domain.models.DiffType
import com.axion.ultimatefetcher.domain.models.KeyValuePair
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import com.axion.ultimatefetcher.domain.models.WebSocketStatus
import com.axion.ultimatefetcher.network.api.ApiLabClient
import com.axion.ultimatefetcher.network.api.LocalWebhookServer
import com.axion.ultimatefetcher.network.api.WebSocketManager
import com.axion.ultimatefetcher.network.diagnostics.ApiDiagnosticsEngine
import com.axion.ultimatefetcher.network.diagnostics.DnsResolverEngine
import com.axion.ultimatefetcher.network.diagnostics.LiveNetworkTelemetryMonitor
import com.axion.ultimatefetcher.network.vpn.PacketParser
import com.axion.ultimatefetcher.root.RootDiagnosticsEngine
import com.axion.ultimatefetcher.root.SafeRootDiagnosticCommand
import com.axion.ultimatefetcher.services.ApiHealthCheckWorker
import com.axion.ultimatefetcher.ui.settings.SettingsViewModel
import com.axion.ultimatefetcher.utils.FormatUtils
import com.axion.ultimatefetcher.utils.SensitiveDataRedactor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecondaryAuditModulesTest {

    private lateinit var context: Context
    private lateinit var db: UltimateFetcherDatabase
    private lateinit var repository: UltimateFetcherRepository
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, UltimateFetcherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = UltimateFetcherRepository(context, db)
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dashboard_telemetryCountsRealDistinctEndpointsAndResetsSessionCleanly() = runBlocking {
        val baseTs = System.currentTimeMillis() - 10_000L
        repository.insertPackets(
            listOf(
                PacketLog(timestamp = baseTs + 100, protocol = "TCP", srcIp = "10.0.0.2", srcPort = 50001, dstIp = "1.1.1.1", dstPort = 443, packetSize = 120),
                PacketLog(timestamp = baseTs + 200, protocol = "TCP", srcIp = "10.0.0.2", srcPort = 50002, dstIp = "1.1.1.1", dstPort = 443, packetSize = 240),
                PacketLog(timestamp = baseTs + 300, protocol = "UDP", srcIp = "10.0.0.2", srcPort = 50003, dstIp = "8.8.8.8", dstPort = 53, packetSize = 64)
            )
        )

        val distinctCount = db.packetLogDao().countDistinctEndpointsSince(baseTs)
        assertEquals("Should count 2 unique endpoints, not 3 total packets", 2, distinctCount)

        val monitor = LiveNetworkTelemetryMonitor(
            context = context,
            okHttpClient = okHttpClient,
            distinctEndpointsCounter = { since -> db.packetLogDao().countDistinctEndpointsSince(since) }
        )
        monitor.resetSessionBaseline()
        val state = monitor.telemetry.value
        assertEquals(0L, state.downloadSpeedBps)
        assertEquals(0L, state.uploadSpeedBps)
        assertEquals(0L, state.sessionDownloadedBytes)
        assertEquals(0L, state.sessionUploadedBytes)
        assertEquals(0, state.activeConnectionsCount)
        assertTrue(state.trafficHistory.isEmpty())

        assertEquals("512 B", FormatUtils.formatBytes(512))
        assertEquals("2.0 KB", FormatUtils.formatBytes(2048))
        assertEquals("1.00 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("0 B/s", FormatUtils.formatSpeed(0))
        assertEquals("1.5 KB/s", FormatUtils.formatSpeed(1536))
    }

    @Test
    fun dns_localUdpServerResolvesARecordDecodesNxdomainAndValidatesTxid() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        server.soTimeout = 4000

        val responder = thread(isDaemon = true) {
            val buf = ByteArray(1024)
            repeat(3) {
                runCatching {
                    val reqPkt = DatagramPacket(buf, buf.size)
                    server.receive(reqPkt)
                    val reqBytes = reqPkt.data.copyOf(reqPkt.length)
                    val parsed = PacketParser.parseDnsPayload(reqBytes, 0, reqBytes.size)
                    val tx0 = reqBytes[0]
                    val tx1 = reqBytes[1]

                    val responseBytes = when (parsed?.queryName) {
                        "api.example.test" -> buildDnsAResponse(tx0, tx1, "api.example.test", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
                        "missing.example.test" -> buildDnsErrorResponse(tx0, tx1, rcode = 3)
                        else -> buildDnsAResponse((tx0.toInt() xor 0xFF).toByte(), tx1, "badtx.example.test", byteArrayOf(1, 2, 3, 4))
                    }
                    server.send(DatagramPacket(responseBytes, responseBytes.size, reqPkt.address, reqPkt.port))
                }
            }
        }

        try {
            val engine = DnsResolverEngine()

            val aRes = engine.resolve("api.example.test", "A", "127.0.0.1", port = port)
            assertNull("Expected no error for valid A lookup, got: ${aRes.error}", aRes.error)
            assertEquals("UDP_53", aRes.protocolUsed)
            assertTrue("Expected 93.184.216.34 in ${aRes.records}", aRes.records.contains("93.184.216.34"))

            val nxRes = engine.resolve("missing.example.test", "A", "127.0.0.1", port = port)
            assertNotNull(nxRes.error)
            assertTrue("Expected NXDOMAIN error, got: ${nxRes.error}", nxRes.error!!.contains("NXDOMAIN"))

            val badTxRes = engine.resolve("badtx.example.test", "A", "127.0.0.1", port = port)
            assertNotNull("TXID mismatch should be rejected", badTxRes.error)
            assertTrue(badTxRes.error!!.contains("transaction ID mismatch"))

            val invalidHostRes = engine.resolve("invalid host!@#", "A", "127.0.0.1", port = port)
            assertNotNull(invalidHostRes.error)
            assertTrue(invalidHostRes.error!!.contains("valid hostname", ignoreCase = true))

            // Verify query builder supports A, AAAA, NS, HTTPS
            listOf("A", "AAAA", "NS", "HTTPS").forEach { rt ->
                val q = PacketParser.buildDnsQueryPacket("example.com", rt)
                val parsed = PacketParser.parseDnsPayload(q, 0, q.size)
                assertNotNull("Should parse $rt query", parsed)
                assertEquals("example.com", parsed!!.queryName)
                assertEquals(rt, parsed.queryType)
            }
        } finally {
            server.close()
            responder.join(500)
        }
    }

    @Test
    fun quic_rfc9000ClassificationAndDaoExcludeTcp443FalsePositives() = runBlocking {
        // Real QUIC Short Header (Fixed Bit 0x40 set, >= 4 bytes)
        val shortHdr = byteArrayOf(0x43, 0x11, 0x22, 0x33, 0x44)
        assertTrue(PacketParser.isLikelyQuicPayload(shortHdr, 0, shortHdr.size))
        // Real QUIC Long Header (0xC0: Header Form + Fixed Bit)
        val longHdr = byteArrayOf(0xC3.toByte(), 0x00, 0x00, 0x00, 0x01, 0x08)
        assertTrue(PacketParser.isLikelyQuicPayload(longHdr, 0, longHdr.size))
        // Real QUIC Version Negotiation (RFC 9000 §17.2.1: 1B flags + 4B version=0 + 1B DCID len + 1B SCID len + 4B supported version)
        val verNeg = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)
        assertTrue(PacketParser.isLikelyQuicPayload(verNeg, 0, verNeg.size))
        // Non-QUIC UDP:443 payload without Fixed Bit -> rejected
        val nonQuic = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertFalse(PacketParser.isLikelyQuicPayload(nonQuic, 0, nonQuic.size))
        // Too short -> rejected
        val tooShort = byteArrayOf(0x43, 0x01, 0x02)
        assertFalse(PacketParser.isLikelyQuicPayload(tooShort, 0, tooShort.size))

        // Room DAO must NOT classify TCP:443 HTTPS as QUIC
        repository.insertPackets(
            listOf(
                PacketLog(protocol = "TCP", srcIp = "10.0.0.2", srcPort = 41000, dstIp = "142.250.1.1", dstPort = 443, packetSize = 500),
                PacketLog(protocol = "UDP", srcIp = "10.0.0.2", srcPort = 41001, dstIp = "142.250.1.2", dstPort = 443, packetSize = 1200),
                PacketLog(protocol = "QUIC", srcIp = "10.0.0.2", srcPort = 41002, dstIp = "142.250.1.3", dstPort = 443, packetSize = 1250, isBlocked = true)
            )
        )
        val quicRows = repository.observeQuicPackets(50).first()
        assertEquals(2, quicRows.size)
        assertTrue("TCP:443 must be excluded from QUIC results", quicRows.none { it.protocol == "TCP" })
    }

    @Test
    fun tls_clientHelloParserExtractsSniAlpnDetectsEchAndRejectsMalformed() {
        val validHello = buildTlsClientHello(sni = "secure.example.com", alpn = listOf("h2", "http/1.1"), includeEch = false)
        val parsed = PacketParser.parseTlsClientHello(validHello, 0, validHello.size)
        assertNotNull(parsed)
        assertEquals("secure.example.com", parsed!!.sniHostname)
        assertEquals("TLS 1.3", parsed.tlsVersion)
        assertEquals(listOf("h2", "http/1.1"), parsed.alpnProtocols)
        assertFalse(parsed.hasEchExtension)

        val echHello = buildTlsClientHello(sni = null, alpn = listOf("h2"), includeEch = true)
        val parsedEch = PacketParser.parseTlsClientHello(echHello, 0, echHello.size)
        assertNotNull(parsedEch)
        assertNull(parsedEch!!.sniHostname)
        assertTrue(parsedEch.hasEchExtension)

        // Malformed / truncated packets must return null safely
        val trunc = byteArrayOf(0x16, 0x03, 0x01)
        val wrongType = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x10)
        assertNull(PacketParser.parseTlsClientHello(trunc, 0, trunc.size))
        assertNull(PacketParser.parseTlsClientHello(wrongType, 0, wrongType.size))

        // Non-hostname SNI must be ignored cleanly
        val badSniHello = buildTlsClientHello(sni = "bad/host\u0000name", alpn = emptyList(), includeEch = false)
        val parsedBadSni = PacketParser.parseTlsClientHello(badSniHello, 0, badSniHello.size)
        assertNotNull(parsedBadSni)
        assertNull(parsedBadSni!!.sniHostname)
    }

    @Test
    fun apiLab_executesMethodsValidatesJsonHeadersRedactsDuplicateCookiesAndCapsLargeBody() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        sock.use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            val reqLine = reader.readLine() ?: return@use
                            val parts = reqLine.split(" ")
                            val method = parts.getOrNull(0) ?: "GET"
                            val path = parts.getOrNull(1) ?: "/"
                            var contentLen = 0
                            var customHeader = ""
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) {
                                    contentLen = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                                if (line.startsWith("X-Test-Header:", true)) {
                                    customHeader = line.substringAfter(":").trim()
                                }
                            }
                            if (contentLen > 0) {
                                val chars = CharArray(contentLen)
                                var r = 0
                                while (r < contentLen) {
                                    val n = reader.read(chars, r, contentLen - r)
                                    if (n <= 0) break
                                    r += n
                                }
                            }

                            val out = client.getOutputStream()
                            if (path.startsWith("/large")) {
                                val payloadSize = 2_300_000
                                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: $payloadSize\r\nConnection: close\r\n\r\n"
                                out.write(header.toByteArray())
                                val chunk = ByteArray(10_000) { 'A'.code.toByte() }
                                repeat(payloadSize / chunk.size) { out.write(chunk) }
                                out.flush()
                            } else {
                                val body = """{"method":"$method","path":"$path","header":"$customHeader"}"""
                                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                                val resp = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: application/json\r\n")
                                    append("Set-Cookie: sid=supersecret123; HttpOnly; Secure\r\n")
                                    append("Set-Cookie: csrf=token456; SameSite=Strict\r\n")
                                    append("Content-Length: ${bodyBytes.size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                out.write(resp.toByteArray(Charsets.UTF_8))
                                out.write(bodyBytes)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        try {
            val apiClient = ApiLabClient(okHttpClient, repository)

            // 1. GET with query params & custom header + duplicate Set-Cookie redaction
            val getRes = apiClient.execute(
                ApiRequestDraft(
                    method = "GET",
                    url = "http://127.0.0.1:$port/echo",
                    queryParams = listOf(KeyValuePair("page", "2", true)),
                    headers = listOf(KeyValuePair("X-Test-Header", "audit-ok", true))
                ),
                timeoutSeconds = 10
            )
            assertNull(getRes.error)
            assertEquals(200, getRes.statusCode)
            assertTrue(getRes.isJson)
            assertTrue(getRes.body.contains("page=2"))
            assertTrue(getRes.body.contains("audit-ok"))
            val setCookie = getRes.headers["Set-Cookie"].orEmpty()
            assertFalse("Secret cookie value must never appear", setCookie.contains("supersecret123"))
            assertFalse("Second secret cookie value must never appear", setCookie.contains("token456"))
            assertTrue("Both cookie attributes preserved", setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"))

            // 2. Invalid JSON body rejected cleanly before sending
            val badJsonRes = apiClient.execute(
                ApiRequestDraft(
                    method = "POST",
                    url = "http://127.0.0.1:$port/submit",
                    bodyType = "JSON",
                    bodyText = "{broken json"
                ),
                timeoutSeconds = 10
            )
            assertNotNull(badJsonRes.error)
            assertTrue(badJsonRes.error!!.contains("Invalid JSON"))

            // 3. Invalid header characters caught without crashing
            val badHeaderRes = apiClient.execute(
                ApiRequestDraft(
                    method = "GET",
                    url = "http://127.0.0.1:$port/echo",
                    headers = listOf(KeyValuePair("Bad\nHeader", "val\r\ninject", true))
                ),
                timeoutSeconds = 10
            )
            assertNotNull(badHeaderRes.error)
            assertTrue(badHeaderRes.error!!.contains("Invalid request configuration"))

            // 4. POST / PUT / PATCH / DELETE all succeed
            listOf("POST", "PUT", "PATCH", "DELETE").forEach { m ->
                val res = apiClient.execute(
                    ApiRequestDraft(
                        method = m,
                        url = "http://127.0.0.1:$port/resource",
                        bodyType = "JSON",
                        bodyText = """{"op":"$m"}"""
                    ),
                    timeoutSeconds = 10
                )
                assertNull("$m should succeed without error", res.error)
                assertEquals(200, res.statusCode)
                assertTrue(res.body.contains("\"method\":\"$m\""))
            }

            // 5. Large >2MB response safely bounded without OOM
            val largeRes = apiClient.execute(
                ApiRequestDraft(method = "GET", url = "http://127.0.0.1:$port/large"),
                timeoutSeconds = 15
            )
            assertNull(largeRes.error)
            assertEquals(200, largeRes.statusCode)
            assertEquals(2_300_000L, largeRes.responseSizeBytes)
            assertTrue(largeRes.body.contains("Response preview truncated"))

            // 6. Collections save/export/import preserves params & headers while stripping sensitive secrets
            val draftToSave = ApiRequestDraft(
                method = "POST",
                url = "https://api.example.com/v1/items",
                queryParams = listOf(KeyValuePair("limit", "10", true)),
                headers = listOf(
                    KeyValuePair("X-Client", "UltimateFetcher", true),
                    KeyValuePair("Authorization", "Bearer top-secret-token", true)
                ),
                bodyType = "JSON",
                bodyText = """{"name":"probe"}"""
            )
            repository.saveRequestToCollection("Audit Suite", "Saved tests", draftToSave)
            val exportedJson = repository.exportCollectionsJson()
            assertFalse("Authorization secret must not be exported in collections", exportedJson.contains("top-secret-token"))
            assertTrue(exportedJson.contains("X-Client"))
            assertTrue(exportedJson.contains("limit"))
            val importedCount = repository.importCollectionsJson(exportedJson)
            assertEquals(1, importedCount)
        } finally {
            server.close()
            serverThread.join(500)
        }
    }

    @Test
    fun additionalApiTools_openApiOriginDiffWebhookUtf8WebSocketAndHealthPost() = runBlocking {
        // 1. OpenAPI relative servers[0].url + path-level parameters
        val specServer = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val specPort = specServer.localPort
        val specJson = """
            {
              "openapi": "3.0.0",
              "info": {"title": "Audit API", "version": "2.1"},
              "servers": [{"url": "/api/v2"}],
              "paths": {
                "/users/{id}": {
                  "parameters": [{"name": "id", "in": "path"}],
                  "get": {
                    "summary": "Get user",
                    "parameters": [{"name": "expand", "in": "query"}]
                  }
                }
              }
            }
        """.trimIndent()
        val specThread = thread(isDaemon = true) {
            repeat(2) {
                val sock = runCatching { specServer.accept() }.getOrNull() ?: return@repeat
                sock.use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = specJson.toByteArray(Charsets.UTF_8)
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }

        try {
            val diag = ApiDiagnosticsEngine(okHttpClient)
            val openApi = diag.parseOpenApiSpec("http://127.0.0.1:$specPort/docs/openapi.json")
            assertNull("OpenAPI should parse without error: ${openApi.error}", openApi.error)
            assertEquals("http://127.0.0.1:$specPort/api/v2", openApi.baseUrl)
            assertEquals(1, openApi.endpoints.size)
            val ep = openApi.endpoints.first()
            assertEquals("http://127.0.0.1:$specPort/api/v2/users/{id}", ep.sampleUrl)
            assertTrue("Should merge path-level parameter", ep.parameters.contains("id (path)"))
            assertTrue("Should include op-level parameter", ep.parameters.contains("expand (query)"))

            // 2. API Health Monitor POST method must not crash OkHttp
            val postMonitor = EndpointMonitor(
                name = "POST Health",
                url = "http://127.0.0.1:$specPort/health",
                method = "POST",
                intervalMinutes = 15
            )
            val checked = ApiHealthCheckWorker.checkSingleEndpoint(okHttpClient, postMonitor)
            assertEquals(200, checked.lastStatusCode)
            assertTrue(checked.lastHealthy)

            // 3. Response Diff structural JSON & plain text
            val jsonDiff = diag.computeResponseDiff(
                """{"a":1,"b":"old","removed":true}""",
                """{"a":1,"b":"new","added":"yes"}"""
            )
            assertTrue(jsonDiff.any { it.path == "$.a" && it.type == DiffType.UNCHANGED })
            assertTrue(jsonDiff.any { it.path == "$.b" && it.type == DiffType.MODIFIED })
            assertTrue(jsonDiff.any { it.path == "$.removed" && it.type == DiffType.REMOVED })
            assertTrue(jsonDiff.any { it.path == "$.added" && it.type == DiffType.ADDED })

            val textDiff = diag.computeResponseDiff("line1\nline2", "line1\nline2-mod\nline3")
            assertEquals(3, textDiff.size)
            assertEquals(DiffType.ADDED, textDiff[2].type)
        } finally {
            specServer.close()
            specThread.join(500)
        }

        // 4. LocalWebhookServer UTF-8 multi-byte body & rapid restart (reuseAddress)
        val webhookPort = ServerSocket(0).use { it.localPort }
        val webhook = LocalWebhookServer()
        try {
            webhook.start(webhookPort)
            var tries = 0
            while (!webhook.state.value.isRunning && tries < 40) {
                delay(50)
                tries++
            }
            assertTrue("Webhook server should start", webhook.state.value.isRunning)

            val utf8Payload = """{"msg":"Café 🚀 日本語"}"""
            val conn = (URL("http://127.0.0.1:$webhookPort/hook").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer webhook-secret")
            }
            conn.outputStream.use { it.write(utf8Payload.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, conn.responseCode)
            conn.disconnect()

            var waitPayload = 0
            while (webhook.state.value.payloads.isEmpty() && waitPayload < 40) {
                delay(50)
                waitPayload++
            }
            assertEquals(1, webhook.state.value.payloads.size)
            val captured = webhook.state.value.payloads.first()
            assertEquals(utf8Payload, captured.body)
            assertEquals("[REDACTED]", captured.headers["Authorization"])

            // Rapid stop + restart on same port verifies SO_REUSEADDR
            webhook.stop()
            webhook.start(webhookPort)
            var restartTries = 0
            while (!webhook.state.value.isRunning && restartTries < 40) {
                delay(50)
                restartTries++
            }
            assertTrue("Webhook server should re-bind immediately", webhook.state.value.isRunning)
        } finally {
            webhook.stop()
        }

        // 5. WebSocketManager handles malformed URL cleanly without crashing
        val ws = WebSocketManager(okHttpClient)
        ws.connect("http://not-websocket.example.com")
        assertEquals(WebSocketStatus.ERROR, ws.state.value.status)
        ws.connect("ws://bad url with spaces")
        assertEquals(WebSocketStatus.ERROR, ws.state.value.status)
    }

    @Test
    fun rootModeSettingsValidationAndRedactorComplete() = runBlocking {
        // 1. Root diagnostics refuse unauthorized/non-root execution
        val rootEngine = RootDiagnosticsEngine(repository)
        val nonRootState = RootAvailabilityState(isRootDetected = false, isAuthorized = false)
        val blockedResult = rootEngine.runReadOnlyDiagnostic(SafeRootDiagnosticCommand.INTERFACES, nonRootState)
        assertEquals(-1, blockedResult.exitCode)
        assertTrue(blockedResult.output.contains("require explicit user authorization"))

        // 2. Custom DNS IP validation
        assertTrue(SettingsViewModel.isValidDnsIpOrSystem("1.1.1.1"))
        assertTrue(SettingsViewModel.isValidDnsIpOrSystem("8.8.8.8"))
        assertTrue(SettingsViewModel.isValidDnsIpOrSystem("2606:4700:4700::1111"))
        assertTrue(SettingsViewModel.isValidDnsIpOrSystem("System"))
        assertFalse(SettingsViewModel.isValidDnsIpOrSystem(""))
        assertFalse(SettingsViewModel.isValidDnsIpOrSystem("999.1.1.1"))
        assertFalse(SettingsViewModel.isValidDnsIpOrSystem("not-an-ip"))
        assertFalse(SettingsViewModel.isValidDnsIpOrSystem("1.1.1.1; rm -rf /"))

        // 3. DataStore settings persistence
        val ds = SettingsDataStore(context)
        ds.setThemeMode(AppThemeMode.LIGHT)
        ds.setCustomDnsServer("9.9.9.9")
        ds.setDefaultTimeoutSeconds(45)
        ds.setLogRetentionDays(14)
        ds.setAutoClearLogs(true)
        ds.setHealthMonitorNotifications(true)
        ds.setExportSanitizedOnly(true)

        val persisted = ds.settingsFlow.first()
        assertEquals(AppThemeMode.LIGHT, persisted.themeMode)
        assertEquals("9.9.9.9", persisted.customDnsServer)
        assertEquals(45, persisted.defaultTimeoutSeconds)
        assertEquals(14, persisted.logRetentionDays)
        assertTrue(persisted.autoClearLogs)
        assertTrue(persisted.healthMonitorNotifications)
        assertTrue(persisted.exportSanitizedOnly)

        // 4. SensitiveDataRedactor complete coverage
        val redacted = SensitiveDataRedactor.redactHeaders(
            mapOf(
                "Authorization" to "Bearer abc",
                "Proxy-Authorization" to "Basic xyz",
                "Cookie" to "session=secret",
                "X-Api-Key" to "key123",
                "X-Auth-Token" to "tok999",
                "Set-Cookie" to "a=1; HttpOnly\nb=2; Secure",
                "Content-Type" to "application/json"
            )
        )
        assertEquals("[REDACTED]", redacted["Authorization"])
        assertEquals("[REDACTED]", redacted["Proxy-Authorization"])
        assertEquals("[REDACTED]", redacted["Cookie"])
        assertEquals("[REDACTED]", redacted["X-Api-Key"])
        assertEquals("[REDACTED]", redacted["X-Auth-Token"])
        assertEquals("a=[REDACTED]; HttpOnly\nb=[REDACTED]; Secure", redacted["Set-Cookie"])
        assertEquals("application/json", redacted["Content-Type"])
    }

    private fun buildDnsAResponse(tx0: Byte, tx1: Byte, domain: String, ipBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(tx0.toInt())
        dos.writeByte(tx1.toInt())
        dos.writeShort(0x8180) // Standard response, no error
        dos.writeShort(1) // QDCOUNT
        dos.writeShort(1) // ANCOUNT
        dos.writeShort(0) // NSCOUNT
        dos.writeShort(0) // ARCOUNT
        domain.split('.').forEach { lbl ->
            val b = lbl.toByteArray(Charsets.US_ASCII)
            dos.writeByte(b.size)
            dos.write(b)
        }
        dos.writeByte(0)
        dos.writeShort(1) // A
        dos.writeShort(1) // IN
        // Answer pointing to offset 12
        dos.writeShort(0xC00C)
        dos.writeShort(1) // A
        dos.writeShort(1) // IN
        dos.writeInt(60) // TTL
        dos.writeShort(4) // RDLENGTH
        dos.write(ipBytes)
        return out.toByteArray()
    }

    private fun buildDnsErrorResponse(tx0: Byte, tx1: Byte, rcode: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(tx0.toInt())
        dos.writeByte(tx1.toInt())
        dos.writeShort(0x8180 or (rcode and 0x0F))
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        return out.toByteArray()
    }

    private fun buildTlsClientHello(sni: String?, alpn: List<String>, includeEch: Boolean): ByteArray {
        val extOut = ByteArrayOutputStream()
        val extDos = DataOutputStream(extOut)

        if (sni != null) {
            val hostBytes = sni.toByteArray(Charsets.UTF_8)
            extDos.writeShort(0x0000) // SNI extension
            extDos.writeShort(2 + 1 + 2 + hostBytes.size)
            extDos.writeShort(1 + 2 + hostBytes.size)
            extDos.writeByte(0) // host_name
            extDos.writeShort(hostBytes.size)
            extDos.write(hostBytes)
        }

        if (alpn.isNotEmpty()) {
            val protoBuf = ByteArrayOutputStream()
            alpn.forEach { p ->
                val b = p.toByteArray(Charsets.US_ASCII)
                protoBuf.write(b.size)
                protoBuf.write(b)
            }
            val pBytes = protoBuf.toByteArray()
            extDos.writeShort(0x0010) // ALPN
            extDos.writeShort(2 + pBytes.size)
            extDos.writeShort(pBytes.size)
            extDos.write(pBytes)
        }

        // supported_versions advertising TLS 1.3 (0x0304)
        extDos.writeShort(0x002B)
        extDos.writeShort(3)
        extDos.writeByte(2)
        extDos.writeShort(0x0304)

        if (includeEch) {
            extDos.writeShort(0xFE0D)
            extDos.writeShort(2)
            extDos.writeShort(0x0000)
        }

        val extBytes = extOut.toByteArray()
        val bodyOut = ByteArrayOutputStream()
        val bodyDos = DataOutputStream(bodyOut)
        bodyDos.writeShort(0x0303) // legacy_version TLS 1.2
        bodyDos.write(ByteArray(32)) // random
        bodyDos.writeByte(0) // session_id_len
        bodyDos.writeShort(2) // cipher_suites_len
        bodyDos.writeShort(0x1301) // TLS_AES_128_GCM_SHA256
        bodyDos.writeByte(1) // compression_methods_len
        bodyDos.writeByte(0) // null
        bodyDos.writeShort(extBytes.size)
        bodyDos.write(extBytes)
        val bodyBytes = bodyOut.toByteArray()

        val hsOut = ByteArrayOutputStream()
        hsOut.write(0x01) // ClientHello
        hsOut.write((bodyBytes.size ushr 16) and 0xFF)
        hsOut.write((bodyBytes.size ushr 8) and 0xFF)
        hsOut.write(bodyBytes.size and 0xFF)
        hsOut.write(bodyBytes)
        val hsBytes = hsOut.toByteArray()

        val recOut = ByteArrayOutputStream()
        recOut.write(0x16) // Handshake
        recOut.write(0x03)
        recOut.write(0x01)
        recOut.write((hsBytes.size ushr 8) and 0xFF)
        recOut.write(hsBytes.size and 0xFF)
        recOut.write(hsBytes)
        return recOut.toByteArray()
    }
}
