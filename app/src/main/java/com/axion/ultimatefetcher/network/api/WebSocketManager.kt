package com.axion.ultimatefetcher.network.api

import com.axion.ultimatefetcher.domain.models.WebSocketMessageItem
import com.axion.ultimatefetcher.domain.models.WebSocketStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class WebSocketUiState(
    val status: WebSocketStatus = WebSocketStatus.DISCONNECTED,
    val statusDetail: String = "Disconnected",
    val lastLatencyMs: Long? = null,
    val messages: List<WebSocketMessageItem> = emptyList()
)

class WebSocketManager(
    private val okHttpClient: OkHttpClient
) {
    private val _state = MutableStateFlow(WebSocketUiState())
    val state: StateFlow<WebSocketUiState> = _state.asStateFlow()

    private val wsClient: OkHttpClient = okHttpClient.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val idGen = AtomicLong(1L)
    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var lastSentNano: Long = 0L

    fun connect(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("ws://", true) && !url.startsWith("wss://", true)) {
            _state.update { it.copy(status = WebSocketStatus.ERROR, statusDetail = "URL must start with ws:// or wss://") }
            return
        }
        val req = runCatching { Request.Builder().url(url).build() }.getOrElse { err ->
            _state.update {
                it.copy(
                    status = WebSocketStatus.ERROR,
                    statusDetail = "Invalid WebSocket URL: ${err.message ?: "malformed address"}"
                )
            }
            return
        }

        disconnect()
        lastSentNano = 0L
        _state.update { it.copy(status = WebSocketStatus.CONNECTING, statusDetail = "Connecting to $url...") }

        activeSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== activeSocket) return
                _state.update { it.copy(status = WebSocketStatus.CONNECTED, statusDetail = "Connected (HTTP ${response.code})") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== activeSocket) return
                val sent = lastSentNano
                val rtt = if (sent > 0L) {
                    lastSentNano = 0L
                    ((System.nanoTime() - sent) / 1_000_000L).coerceAtLeast(1L)
                } else {
                    null
                }
                val item = WebSocketMessageItem(
                    id = idGen.getAndIncrement(),
                    direction = "RECEIVED",
                    payload = text,
                    latencyMs = rtt
                )
                _state.update { curr ->
                    curr.copy(
                        lastLatencyMs = rtt ?: curr.lastLatencyMs,
                        messages = (listOf(item) + curr.messages).take(120)
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== activeSocket) return
                webSocket.close(1000, null)
                activeSocket = null
                _state.update { it.copy(status = WebSocketStatus.DISCONNECTED, statusDetail = "Closed ($code $reason)".trim()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== activeSocket) return
                activeSocket = null
                _state.update { it.copy(status = WebSocketStatus.DISCONNECTED, statusDetail = "Closed ($code)") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== activeSocket) return
                activeSocket = null
                _state.update { it.copy(status = WebSocketStatus.ERROR, statusDetail = "Error: ${t.message ?: "Connection failed"}") }
            }
        })
    }

    fun sendMessage(payload: String) {
        val ws = activeSocket ?: return
        if (payload.isEmpty()) return
        lastSentNano = System.nanoTime()
        if (ws.send(payload)) {
            val item = WebSocketMessageItem(
                id = idGen.getAndIncrement(),
                direction = "SENT",
                payload = payload
            )
            _state.update { curr -> curr.copy(messages = (listOf(item) + curr.messages).take(120)) }
        }
    }

    fun disconnect() {
        val old = activeSocket
        activeSocket = null
        lastSentNano = 0L
        runCatching { old?.close(1000, "User disconnected") }
        _state.update { it.copy(status = WebSocketStatus.DISCONNECTED, statusDetail = "Disconnected") }
    }

    fun clearMessages() {
        _state.update { it.copy(messages = emptyList()) }
    }
}
