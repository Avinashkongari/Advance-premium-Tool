package com.axion.ultimatefetcher.network.diagnostics

import com.axion.ultimatefetcher.domain.models.NetworkQualityReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

class NetworkQualityEngine(
    private val okHttpClient: OkHttpClient
) {
    suspend fun runFullQualityTest(
        probeHost: String,
        onProgress: (String) -> Unit
    ): NetworkQualityReport = withContext(Dispatchers.IO) {
        val host = probeHost.trim().ifEmpty { "1.1.1.1" }
        val disclosure = "Latency probes connect to $host:443 via TCP. Download speed fetches a 2 MB payload from speed.cloudflare.com. Upload speed posts a 512 KB zero-filled payload to httpbin.org/post."

        onProgress("Running TCP latency probes against $host:443...")
        val samples = mutableListOf<Double>()
        val probeCount = 6
        var failures = 0
        for (i in 1..probeCount) {
            val ms = runCatching {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(host, 443), 2500) }
                (System.nanoTime() - t0) / 1_000_000.0
            }.getOrNull()
            if (ms != null) samples.add(ms) else failures++
        }

        val lossPct = (failures * 100.0) / probeCount
        val minMs = samples.minOrNull() ?: 0.0
        val maxMs = samples.maxOrNull() ?: 0.0
        val avgMs = if (samples.isNotEmpty()) samples.average() else 0.0
        val jitterMs = if (samples.size >= 2) {
            samples.zipWithNext { a, b -> abs(b - a) }.average()
        } else 0.0

        onProgress("Measuring real HTTP download speed (Cloudflare 2 MB)...")
        var dlMbps = 0.0
        var errorMsg: String? = null
        runCatching {
            val req = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=2000000").get().build()
            val t0 = System.nanoTime()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download HTTP ${resp.code}")
                val bytes = resp.body?.bytes()?.size ?: 0
                val elapsedSec = ((System.nanoTime() - t0) / 1_000_000_000.0).coerceAtLeast(0.01)
                dlMbps = (bytes * 8.0) / (elapsedSec * 1_000_000.0)
            }
        }.onFailure { errorMsg = "Download test: ${it.message}" }

        onProgress("Measuring real HTTP upload speed (512 KB)...")
        var ulMbps = 0.0
        runCatching {
            val payload = ByteArray(512 * 1024)
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder().url("https://httpbin.org/post").post(body).build()
            val t0 = System.nanoTime()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Upload HTTP ${resp.code}")
                val elapsedSec = ((System.nanoTime() - t0) / 1_000_000_000.0).coerceAtLeast(0.01)
                ulMbps = (payload.size * 8.0) / (elapsedSec * 1_000_000.0)
            }
        }.onFailure {
            errorMsg = listOfNotNull(errorMsg, "Upload test: ${it.message}").joinToString(" | ")
        }

        val grade = when {
            samples.isEmpty() -> "Offline"
            lossPct == 0.0 && avgMs < 60 && jitterMs < 15 -> "Excellent"
            lossPct <= 10.0 && avgMs < 130 -> "Good"
            lossPct <= 25.0 -> "Fair"
            else -> "Degraded"
        }

        NetworkQualityReport(
            probeHost = host,
            latencyMinMs = minMs,
            latencyAvgMs = avgMs,
            latencyMaxMs = maxMs,
            jitterMs = jitterMs,
            packetLossPercent = lossPct,
            downloadMbps = dlMbps,
            uploadMbps = ulMbps,
            stabilityGrade = grade,
            testServerDisclosure = disclosure,
            error = errorMsg
        )
    }
}
