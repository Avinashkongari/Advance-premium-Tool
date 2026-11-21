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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun collectHeadersMap(headers: okhttp3.Headers): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    for (i in 0 until headers.size) {
        val name = headers.name(i)
        val value = headers.value(i)
        val existing = map[name]
        map[name] = if (existing == null) {
            value
        } else if (name.equals("Set-Cookie", ignoreCase = true)) {
            "$existing\n$value"
        } else {
            "$existing, $value"
        }
    }
    return map
}

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
        val reqHeaders = collectHeadersMap(request.headers)

        return try {
            val response = chain.proceed(request)
            val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            val respHeaders = collectHeadersMap(response.headers)
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
        val trimmedInput = draft.url.trim()
        if (trimmedInput.isEmpty()) {
            return@withContext ApiExecutionResult(0, 0, 0, emptyMap(), "", false, "Endpoint URL cannot be empty")
        }
        val rawUrl = if (trimmedInput.startsWith("http://", true) || trimmedInput.startsWith("https://", true)) {
            trimmedInput
        } else {
            "https://$trimmedInput"
        }
        val parsedUrl = rawUrl.toHttpUrlOrNull()
            ?: return@withContext ApiExecutionResult(0, 0, 0, emptyMap(), "", false, "Invalid endpoint URL")

        val urlBuilder = parsedUrl.newBuilder()
        draft.queryParams.filter { it.enabled && it.key.isNotBlank() }.forEach { kv ->
            urlBuilder.addQueryParameter(kv.key.trim(), kv.value)
        }
        val finalUrl = urlBuilder.build()

        val method = draft.method.trim().uppercase(Locale.US).ifBlank { "GET" }
        val allowsBody = method in setOf("POST", "PUT", "PATCH", "DELETE")
        if (allowsBody && draft.bodyType == "JSON" && draft.bodyText.isNotBlank()) {
            val trimmedBody = draft.bodyText.trim()
            val jsonValidationError = runCatching {
                when {
                    trimmedBody.startsWith("{") -> {
                        JSONObject(trimmedBody)
                        null
                    }
                    trimmedBody.startsWith("[") -> {
                        JSONArray(trimmedBody)
                        null
                    }
                    else -> "JSON body must be a valid JSON object ({...}) or array ([...])"
                }
            }.getOrElse { err ->
                "Invalid JSON request body: ${err.message ?: "syntax error"}"
            }
            if (jsonValidationError != null) {
                return@withContext ApiExecutionResult(0, 0, 0, emptyMap(), "", false, jsonValidationError)
            }
        }

        val reqHeadersMap = LinkedHashMap<String, String>()
        val builtRequest = runCatching {
            val reqBuilder = Request.Builder().url(finalUrl)
            draft.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { kv ->
                val k = kv.key.trim()
                reqBuilder.header(k, kv.value)
                reqHeadersMap[k] = kv.value
            }

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

            reqBuilder.method(method, requestBody).build()
        }.getOrElse { err ->
            return@withContext ApiExecutionResult(
                statusCode = 0,
                durationMs = 0L,
                responseSizeBytes = 0L,
                headers = emptyMap(),
                body = "",
                isJson = false,
                error = "Invalid request configuration: ${err.message ?: "check headers or method"}"
            )
        }

        val safeTimeout = timeoutSeconds.coerceIn(5, 120).toLong()
        val client = baseClient.newBuilder()
            .connectTimeout(safeTimeout, TimeUnit.SECONDS)
            .readTimeout(safeTimeout, TimeUnit.SECONDS)
            .writeTimeout(safeTimeout, TimeUnit.SECONDS)
            .build()

        val call = client.newCall(builtRequest)
        activeCall = call
        val t0 = System.nanoTime()

        try {
            call.execute().use { resp ->
                val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val (bodyStr, totalBytes, truncated) = readBoundedBody(resp)
                val contentType = resp.header("Content-Type")
                val respHeaders = collectHeadersMap(resp.headers)
                val redactedRespHeaders = SensitiveDataRedactor.redactHeaders(respHeaders)
                val isJson = !truncated && FormatUtils.isLikelyJson(bodyStr, contentType)

                repository.insertApiLog(
                    ApiRequestLog(
                        method = method,
                        url = finalUrl.toString(),
                        requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeadersMap),
                        requestBodyPreview = draft.bodyText.take(600),
                        statusCode = resp.code,
                        durationMs = durationMs,
                        responseSizeBytes = totalBytes,
                        responseHeadersJson = SensitiveDataRedactor.headersToRedactedJson(respHeaders),
                        responseBodyPreview = bodyStr.take(1200),
                        isOwnAppDebug = false
                    )
                )

                ApiExecutionResult(
                    statusCode = resp.code,
                    durationMs = durationMs,
                    responseSizeBytes = totalBytes,
                    headers = redactedRespHeaders,
                    body = bodyStr,
                    isJson = isJson
                )
            }
        } catch (e: Exception) {
            val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            val errMsg = if (call.isCanceled()) {
                "Request canceled by user"
            } else {
                e.message ?: "Request failed"
            }
            repository.insertApiLog(
                ApiRequestLog(
                    method = method,
                    url = finalUrl.toString(),
                    requestHeadersJson = SensitiveDataRedactor.headersToRedactedJson(reqHeadersMap),
                    requestBodyPreview = draft.bodyText.take(600),
                    statusCode = 0,
                    durationMs = durationMs,
                    errorMessage = errMsg,
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
                error = errMsg
            )
        } finally {
            if (activeCall === call) {
                activeCall = null
            }
        }
    }

    private fun readBoundedBody(resp: Response): Triple<String, Long, Boolean> {
        val body = resp.body ?: return Triple("", 0L, false)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var totalBytes = 0L
        var truncated = false
        body.byteStream().use { stream ->
            while (true) {
                val r = stream.read(buf)
                if (r <= 0) break
                totalBytes += r
                val remaining = MAX_RESPONSE_BODY_BYTES - out.size()
                if (remaining > 0) {
                    out.write(buf, 0, minOf(r, remaining))
                } else {
                    truncated = true
                }
            }
        }
        val text = out.toString(Charsets.UTF_8.name())
        return if (truncated) {
            Triple(
                "$text\n\n[Response preview truncated at ${FormatUtils.formatBytes(MAX_RESPONSE_BODY_BYTES.toLong())}; total received: ${FormatUtils.formatBytes(totalBytes)}]",
                totalBytes,
                true
            )
        } else {
            Triple(text, totalBytes, false)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BODY_BYTES = 2 * 1024 * 1024
    }
}
