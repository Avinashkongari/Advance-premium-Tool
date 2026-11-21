package com.axion.ultimatefetcher.network.api

import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.domain.models.ApiExecutionResult
import com.axion.ultimatefetcher.domain.models.ApiRequestDraft
import com.axion.ultimatefetcher.utils.FormatUtils
import com.axion.ultimatefetcher.utils.SensitiveDataRedactor
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OwnAppDebugInterceptor(
    private val repositoryProvider: () -> UltimateFetcherRepository
) : Interceptor {

    private val enabled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setOptInEnabled(isEnabled: Boolean) {
        enabled.set(BuildConfig.DEBUG && isEnabled)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!BuildConfig.DEBUG || !enabled.get()) {
            return chain.proceed(request)
        }

        val t0 = System.nanoTime()
        val reqHeaders = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) {
            reqHeaders[request.headers.name(i)] = request.headers.value(i)
        }

        return try {
            val response = chain.proceed(request)
            val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            val respHeaders = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                respHeaders[response.headers.name(i)] = response.headers.value(i)
            }
            val sizeBytes = response.body?.contentLength()?.coerceAtLeast(0L) ?: 0L
            scope.launch {
                repositoryProvider().insertApiLog(
                    ApiRequestLog(
                        method = request.method,
                        url = request.url.toString(),
                        requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeaders),
                        statusCode = response.code,
                        durationMs = durationMs,
                        responseSizeBytes = sizeBytes,
                        responseHeadersJson = SensitiveDataRedactor.headersToRedactedJson(respHeaders),
                        isOwnAppDebug = true
                    )
                )
            }
            response
        } catch (e: Exception) {
            val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            scope.launch {
                repositoryProvider().insertApiLog(
                    ApiRequestLog(
                        method = request.method,
                        url = request.url.toString(),
                        requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeaders),
                        statusCode = 0,
                        durationMs = durationMs,
                        errorMessage = e.message ?: "Network failure",
                        isOwnAppDebug = true
                    )
                )
            }
            throw e
        }
    }
}

class ApiLabClient(
    private val baseClient: OkHttpClient,
    private val repository: UltimateFetcherRepository
) {
    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    suspend fun execute(draft: ApiRequestDraft, timeoutSeconds: Int): ApiExecutionResult = withContext(Dispatchers.IO) {
        val rawUrl = draft.url.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        val parsedUrl = rawUrl.toHttpUrlOrNull()
            ?: return@withContext ApiExecutionResult(0, 0, 0, emptyMap(), "", false, "Invalid endpoint URL")

        val urlBuilder = parsedUrl.newBuilder()
        draft.queryParams.filter { it.enabled && it.key.isNotBlank() }.forEach { kv ->
            urlBuilder.addQueryParameter(kv.key.trim(), kv.value)
        }
        val finalUrl = urlBuilder.build()

        val reqBuilder = Request.Builder().url(finalUrl)
        val reqHeadersMap = LinkedHashMap<String, String>()
        draft.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { kv ->
            val k = kv.key.trim()
            reqBuilder.header(k, kv.value)
            reqHeadersMap[k] = kv.value
        }

        val method = draft.method.uppercase()
        val allowsBody = method in setOf("POST", "PUT", "PATCH", "DELETE")
        val requestBody = if (allowsBody) {
            when (draft.bodyType) {
                "JSON" -> draft.bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
                "FORM_URLENCODED" -> FormBody.Builder().apply {
                    draft.formFields.filter { it.enabled && it.key.isNotBlank() }.forEach {
                        add(it.key.trim(), it.value)
                    }
                }.build()
                else -> if (method == "DELETE") null else "".toRequestBody(null)
            }
        } else null

        reqBuilder.method(method, requestBody)

        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        val call = client.newCall(reqBuilder.build())
        activeCall = call
        val t0 = System.nanoTime()

        try {
            call.execute().use { resp ->
                val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val bodyStr = String(bodyBytes, Charsets.UTF_8)
                val contentType = resp.header("Content-Type")
                val respHeaders = LinkedHashMap<String, String>()
                for (i in 0 until resp.headers.size) {
                    respHeaders[resp.headers.name(i)] = resp.headers.value(i)
                }
                val redactedRespHeaders = SensitiveDataRedactor.redactHeaders(respHeaders)
                val isJson = FormatUtils.isLikelyJson(bodyStr, contentType)

                repository.insertApiLog(
                    ApiRequestLog(
                        method = method,
                        url = finalUrl.toString(),
                        requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeadersMap),
                        requestBodyPreview = draft.bodyText.take(600),
                        statusCode = resp.code,
                        durationMs = durationMs,
                        responseSizeBytes = bodyBytes.size.toLong(),
                        responseHeadersJson = SensitiveDataRedactor.headersToRedactedJson(respHeaders),
                        responseBodyPreview = bodyStr.take(1200),
                        isOwnAppDebug = false
                    )
                )

                ApiExecutionResult(
                    statusCode = resp.code,
                    durationMs = durationMs,
                    responseSizeBytes = bodyBytes.size.toLong(),
                    headers = redactedRespHeaders,
                    body = bodyStr,
                    isJson = isJson
                )
            }
        } catch (e: Exception) {
            val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            repository.insertApiLog(
                ApiRequestLog(
                    method = method,
                    url = finalUrl.toString(),
                    requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeadersMap),
                    requestBodyPreview = draft.bodyText.take(600),
                    statusCode = 0,
                    durationMs = durationMs,
                    errorMessage = e.message ?: "Request failed",
                    isOwnAppDebug = false
                )
            )
            ApiExecutionResult(
                statusCode = 0,
                durationMs = durationMs,
                responseSizeBytes = 0L,
                headers = emptyMap(),
                body = "",
                isJson = false,
                error = e.message ?: "Request failed"
            )
        } finally {
            activeCall = null
        }
    }
}
