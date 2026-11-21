package com.axion.ultimatefetcher.network.api

import com.axion.ultimatefetcher.domain.models.WebhookPayloadItem
import com.axion.ultimatefetcher.utils.FormatUtils
import com.axion.ultimatefetcher.utils.SensitiveDataRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class WebhookServerState(
    val isRunning: Boolean = false,
    val port: Int = 8899,
    val localEndpointUrl: String = "",
    val lanEndpointUrl: String? = null,
    val statusMessage: String = "Local HTTP webhook listener is stopped.",
    val payloads: List<WebhookPayloadItem> = emptyList()
)

class LocalWebhookServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WebhookServerState())
    val state: StateFlow<WebhookServerState> = _state.asStateFlow()

    private val idGen = AtomicLong(1L)
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(port: Int = 8899) {
        if (_state.value.isRunning) return
        val bindPort = port.coerceIn(1024, 65535)
        acceptJob = scope.launch {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindPort))
                }
            }.getOrElse { err ->
                _state.update { it.copy(isRunning = false, statusMessage = "Failed to bind port $bindPort: ${err.message}") }
                return@launch
            }
            serverSocket = socket
            val lanIp = findLocalLanIpv4()
            _state.update {
                it.copy(
                    isRunning = true,
                    port = bindPort,
                    localEndpointUrl = "http://127.0.0.1:$bindPort/webhook",
                    lanEndpointUrl = lanIp?.let { ip -> "http://$ip:$bindPort/webhook" },
                    statusMessage = "Listening on local port $bindPort (on-device loopback & LAN)."
                )
            }

            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handleClient(client) }
            }
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (out.size() < 8192) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) out.toString(Charsets.ISO_8859_1.name()) else null
            }
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun handleClient(client: Socket) {
        runCatching {
            client.use { sock ->
                sock.soTimeout = 4000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readHttpLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "POST"
                val path = parts.getOrNull(1) ?: "/"

                val headers = LinkedHashMap<String, String>()
                var contentLength = 0
                var headerCount = 0
                while (headerCount < 100) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    headerCount++
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim()
                        val v = line.substring(colon + 1).trim()
                        headers[k] = v
                        if (k.equals("Content-Length", ignoreCase = true)) {
                            contentLength = v.toIntOrNull()?.coerceIn(0, 256 * 1024) ?: 0
                        }
                    }
                }

                val body = if (contentLength > 0) {
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    String(bodyBytes, 0, readTotal, Charsets.UTF_8)
                } else ""

                val respJson = """{"received":true,"server":"UltimateFetcher","timestamp":${System.currentTimeMillis()}}"""
                val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${respJson.toByteArray(Charsets.UTF_8).size}\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(respJson)
                writer.flush()

                val item = WebhookPayloadItem(
                    id = idGen.getAndIncrement(),
                    method = method,
                    path = path,
                    remoteAddress = sock.inetAddress?.hostAddress ?: "127.0.0.1",
                    headers = SensitiveDataRedactor.redactHeaders(headers),
                    body = body
                )
                _state.update { curr -> curr.copy(payloads = (listOf(item) + curr.payloads).take(80)) }
            }
        }
    }

    suspend fun sendLocalSelfTest(jsonPayload: String) = withContext(Dispatchers.IO) {
        val urlStr = _state.value.localEndpointUrl
        if (!_state.value.isRunning || urlStr.isBlank()) return@withContext
        runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-UltimateFetcher-Test", "self-probe")
            }
            conn.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.update {
            it.copy(
                isRunning = false,
                statusMessage = "Local HTTP webhook listener stopped."
            )
        }
    }

    fun clearPayloads() {
        _state.update { it.copy(payloads = emptyList()) }
    }

    fun exportPayloadsAsJson(): String {
        val root = JSONObject().apply {
            put("app", "UltimateFetcher")
            put("exportedAt", FormatUtils.formatDateTime(System.currentTimeMillis()))
            val arr = JSONArray()
            _state.value.payloads.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("timestamp", FormatUtils.formatDateTime(p.timestamp))
                        put("method", p.method)
                        put("path", p.path)
                        put("remoteAddress", p.remoteAddress)
                        put("headers", JSONObject(p.headers))
                        put("body", p.body)
                    }
                )
            }
            put("payloads", arr)
        }
        return root.toString(2)
    }

    private fun findLocalLanIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()
}
