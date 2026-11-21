package com.axion.ultimatefetcher.network.diagnostics

import com.axion.ultimatefetcher.domain.models.DiffType
import com.axion.ultimatefetcher.domain.models.EndpointInspectionResult
import com.axion.ultimatefetcher.domain.models.HeaderAnalysisResult
import com.axion.ultimatefetcher.domain.models.JsonDiffLine
import com.axion.ultimatefetcher.domain.models.OpenApiDiscoveryResult
import com.axion.ultimatefetcher.domain.models.OpenApiEndpointItem
import com.axion.ultimatefetcher.domain.models.SecurityHeaderCheck
import com.axion.ultimatefetcher.domain.models.SslCertInspectionResult
import com.axion.ultimatefetcher.utils.SensitiveDataRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ApiDiagnosticsEngine(
    private val okHttpClient: OkHttpClient
) {
    suspend fun inspectEndpoint(rawUrl: String): EndpointInspectionResult = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) {
            return@withContext EndpointInspectionResult("", "", 0, 0, emptyList(), "N/A", "N/A", emptyMap(), "Invalid URL")
        }
        val redirects = mutableListOf<String>()
        val inspectClient = okHttpClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                if (resp.isRedirect) {
                    val loc = resp.header("Location").orEmpty()
                    redirects.add("${resp.code} ${resp.request.url} -> $loc")
                }
                resp
            }
            .build()

        val t0 = System.nanoTime()
        runCatching {
            val req = Request.Builder().url(url).get().build()
            inspectClient.newCall(req).execute().use { resp ->
                val durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                val headersMap = LinkedHashMap<String, String>()
                for (i in 0 until resp.headers.size) {
                    val name = resp.headers.name(i)
                    val value = resp.headers.value(i)
                    headersMap[name] = headersMap[name]?.let { "$it; $value" } ?: value
                }
                EndpointInspectionResult(
                    initialUrl = url,
                    finalUrl = resp.request.url.toString(),
                    statusCode = resp.code,
                    durationMs = durationMs,
                    redirects = redirects,
                    tlsVersion = resp.handshake?.tlsVersion?.javaName ?: "Plaintext / None",
                    cipherSuite = resp.handshake?.cipherSuite?.javaName ?: "N/A",
                    headers = SensitiveDataRedactor.redactHeaders(headersMap)
                )
            }
        }.getOrElse { err ->
            EndpointInspectionResult(
                initialUrl = url,
                finalUrl = url,
                statusCode = 0,
                durationMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L),
                redirects = redirects,
                tlsVersion = "N/A",
                cipherSuite = "N/A",
                headers = emptyMap(),
                error = err.message ?: "Endpoint inspection failed"
            )
        }
    }

    suspend fun inspectSslCertificate(rawHost: String, port: Int = 443): SslCertInspectionResult = withContext(Dispatchers.IO) {
        val safePort = port.coerceIn(1, 65535)
        val host = rawHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
        if (host.isEmpty()) {
            return@withContext SslCertInspectionResult(
                host = "", port = safePort, isTrusted = false, tlsProtocol = "N/A", cipherSuite = "N/A",
                subject = "", issuer = "", validFrom = "", validUntil = "", daysUntilExpiry = 0,
                sanDomains = emptyList(), chainSummary = emptyList(), validationDetail = "",
                error = "Enter a valid hostname"
            )
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        runCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val rawSocket = Socket()
            try {
                rawSocket.connect(InetSocketAddress(host, safePort), 6000)
                rawSocket.soTimeout = 6000
                (factory.createSocket(rawSocket, host, safePort, true) as SSLSocket).use { socket ->
                    socket.soTimeout = 6000
                    val params = socket.sslParameters
                    val isIpLiteral = host.all { it.isDigit() || it == '.' || it == ':' }
                    if (!isIpLiteral && host.contains('.')) {
                        runCatching { params.serverNames = listOf(SNIHostName(host)) }
                    }
                    socket.sslParameters = params
                    socket.startHandshake()

                    val session = socket.session
                    val certs = session.peerCertificates.filterIsInstance<X509Certificate>()
                    val leaf = certs.firstOrNull() ?: error("No X.509 leaf certificate presented")

                    var trusted = true
                    var validationMsg = "Verified against Android system root trust store"
                    val hostnameOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                    if (!hostnameOk) {
                        trusted = false
                        validationMsg = "Hostname mismatch: certificate SAN/CN does not match $host"
                    } else {
                        val keyAlg = leaf.publicKey?.algorithm?.uppercase(Locale.US).orEmpty()
                        val authType = when {
                            keyAlg.contains("EC") -> "ECDHE_ECDSA"
                            keyAlg.contains("RSA") -> "ECDHE_RSA"
                            keyAlg.isNotBlank() -> keyAlg
                            else -> "UNKNOWN"
                        }
                        runCatching {
                            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                            tmf.init(null as KeyStore?)
                            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                            tm?.checkServerTrusted(certs.toTypedArray(), authType)
                        }.onFailure { e ->
                            trusted = false
                            validationMsg = "Trust check note: ${e.message}"
                        }
                    }

                    val now = Date()
                    val daysLeft = TimeUnit.MILLISECONDS.toDays(leaf.notAfter.time - now.time)
                    val sans = runCatching {
                        leaf.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }.orEmpty()
                    }.getOrDefault(emptyList())

                    val chain = certs.mapIndexed { idx, cert ->
                        "#${idx + 1}: ${cert.subjectX500Principal.name}"
                    }

                    SslCertInspectionResult(
                        host = host,
                        port = safePort,
                        isTrusted = trusted && daysLeft >= 0,
                        tlsProtocol = session.protocol ?: "TLS",
                        cipherSuite = session.cipherSuite ?: "Unknown",
                        subject = leaf.subjectX500Principal.name,
                        issuer = leaf.issuerX500Principal.name,
                        validFrom = fmt.format(leaf.notBefore),
                        validUntil = fmt.format(leaf.notAfter),
                        daysUntilExpiry = daysLeft,
                        sanDomains = sans,
                        chainSummary = chain,
                        validationDetail = validationMsg
                    )
                }
            } finally {
                runCatching { if (!rawSocket.isClosed) rawSocket.close() }
            }
        }.getOrElse { err ->
            SslCertInspectionResult(
                host = host, port = safePort, isTrusted = false, tlsProtocol = "N/A", cipherSuite = "N/A",
                subject = "", issuer = "", validFrom = "", validUntil = "", daysUntilExpiry = 0,
                sanDomains = emptyList(), chainSummary = emptyList(), validationDetail = "",
                error = err.message ?: "TLS handshake failed"
            )
        }
    }

    suspend fun analyzeHttpHeaders(rawUrl: String): HeaderAnalysisResult = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty()) {
            return@withContext HeaderAnalysisResult(
                "", 0, 0, "N/A", "N/A", "N/A", "N/A", emptyList(), emptyMap(), emptyList(), "Invalid URL"
            )
        }
        runCatching {
            val req = Request.Builder().url(url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                val checks = listOf(
                    Triple("Strict-Transport-Security", "Enforce HTTPS via HSTS", 20),
                    Triple("Content-Security-Policy", "Mitigate XSS and injection", 25),
                    Triple("X-Content-Type-Options", "Set to nosniff", 15),
                    Triple("X-Frame-Options", "Prevent clickjacking (DENY/SAMEORIGIN)", 15),
                    Triple("Referrer-Policy", "Control referrer leakage", 15),
                    Triple("Permissions-Policy", "Restrict browser feature access", 10)
                )
                var score = 0
                val secResults = checks.map { (headerName, rec, weight) ->
                    val v = resp.header(headerName)
                    val present = !v.isNullOrBlank()
                    if (present) score += weight
                    SecurityHeaderCheck(
                        name = headerName,
                        present = present,
                        value = v.orEmpty(),
                        recommendation = rec
                    )
                }

                val cors = LinkedHashMap<String, String>()
                for (i in 0 until resp.headers.size) {
                    val name = resp.headers.name(i)
                    if (name.startsWith("Access-Control-", ignoreCase = true)) {
                        cors[name] = resp.headers.value(i)
                    }
                }

                val cookieAttrs = resp.headers("Set-Cookie").map { rawCookie ->
                    SensitiveDataRedactor.redactSetCookiePreserveAttributes(rawCookie)
                }

                HeaderAnalysisResult(
                    url = resp.request.url.toString(),
                    statusCode = resp.code,
                    securityScore = score.coerceIn(0, 100),
                    cacheControl = resp.header("Cache-Control") ?: "Not specified",
                    contentType = resp.header("Content-Type") ?: "Not specified",
                    contentLength = resp.header("Content-Length") ?: "Chunked / Unspecified",
                    serverHeader = resp.header("Server") ?: "Hidden",
                    securityHeaders = secResults,
                    corsHeaders = cors,
                    cookieAttributes = cookieAttrs
                )
            }
        }.getOrElse { err ->
            HeaderAnalysisResult(
                url, 0, 0, "N/A", "N/A", "N/A", "N/A", emptyList(), emptyMap(), emptyList(),
                error = err.message ?: "Header analysis failed"
            )
        }
    }

    suspend fun parseOpenApiSpec(input: String): OpenApiDiscoveryResult = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return@withContext OpenApiDiscoveryResult("", "", "", emptyList(), "Provide an OpenAPI/Swagger JSON URL or paste raw JSON")
        }

        val rawSpecJson: String = if (trimmed.startsWith("{")) {
            trimmed
        } else if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            runCatching {
                val req = Request.Builder().url(trimmed).get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code} fetching OpenAPI spec")
                    val stream = resp.body?.byteStream() ?: return@use ""
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_OPENAPI_BYTES) {
                            error("OpenAPI document exceeds 2 MB safety cap")
                        }
                        out.write(buf, 0, read)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            }.getOrElse { err ->
                return@withContext OpenApiDiscoveryResult("", "", "", emptyList(), err.message ?: "Failed to download OpenAPI spec")
            }
        } else {
            return@withContext OpenApiDiscoveryResult("", "", "", emptyList(), "Input must be an explicit http(s) OpenAPI JSON URL or raw JSON document")
        }

        runCatching {
            val root = JSONObject(rawSpecJson)
            val info = root.optJSONObject("info")
            val title = info?.optString("title").orEmpty().ifBlank { "OpenAPI Spec" }
            val version = info?.optString("version").orEmpty().ifBlank { "1.0" }

            var baseUrl = ""
            val servers = root.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                baseUrl = servers.optJSONObject(0)?.optString("url").orEmpty()
            } else if (root.has("host")) {
                val scheme = root.optJSONArray("schemes")?.optString(0).orEmpty().ifBlank { "https" }
                baseUrl = "$scheme://${root.optString("host")}${root.optString("basePath")}"
            }
            if (baseUrl.startsWith("/") && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
                val origin = runCatching {
                    val uri = URI(trimmed)
                    if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                        val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
                        "${uri.scheme}://${uri.host}$portSuffix"
                    } else {
                        ""
                    }
                }.getOrDefault("")
                if (origin.isNotEmpty()) {
                    baseUrl = origin.trimEnd('/') + baseUrl
                }
            }

            val paths = root.optJSONObject("paths") ?: JSONObject()
            val validMethods = setOf("get", "post", "put", "patch", "delete", "head", "options")
            val endpoints = mutableListOf<OpenApiEndpointItem>()

            val pathKeys = paths.keys()
            while (pathKeys.hasNext()) {
                val path = pathKeys.next()
                val pathObj = paths.optJSONObject(path) ?: continue
                val sharedParams = extractParameters(pathObj.optJSONArray("parameters"))
                val methodKeys = pathObj.keys()
                while (methodKeys.hasNext()) {
                    val m = methodKeys.next().lowercase(Locale.US)
                    if (m !in validMethods) continue
                    val op = pathObj.optJSONObject(m) ?: continue
                    val summary = op.optString("summary").ifBlank { op.optString("description").ifBlank { "Endpoint operation" } }
                    val mergedParams = LinkedHashSet<String>().apply {
                        addAll(sharedParams)
                        addAll(extractParameters(op.optJSONArray("parameters")))
                    }.toList()
                    val sampleUrl = if (baseUrl.isNotBlank()) "${baseUrl.trimEnd('/')}$path" else path
                    val sampleBody = if (m in setOf("post", "put", "patch")) "{\n  \n}" else ""
                    endpoints.add(
                        OpenApiEndpointItem(
                            method = m.uppercase(Locale.US),
                            path = path,
                            summary = summary,
                            parameters = mergedParams,
                            sampleUrl = sampleUrl,
                            sampleBody = sampleBody
                        )
                    )
                }
            }

            OpenApiDiscoveryResult(
                title = title,
                version = version,
                baseUrl = baseUrl.ifBlank { "(Relative Paths)" },
                endpoints = endpoints
            )
        }.getOrElse { err ->
            OpenApiDiscoveryResult("", "", "", emptyList(), "Failed to parse JSON OpenAPI specification: ${err.message}")
        }
    }

    private fun extractParameters(paramsArr: JSONArray?): List<String> {
        if (paramsArr == null) return emptyList()
        val params = mutableListOf<String>()
        for (i in 0 until paramsArr.length()) {
            val pObj = paramsArr.optJSONObject(i) ?: continue
            val pName = pObj.optString("name")
            val pIn = pObj.optString("in")
            if (pName.isNotBlank()) params.add("$pName ($pIn)")
        }
        return params
    }

    companion object {
        private const val MAX_OPENAPI_BYTES = 2 * 1024 * 1024
    }

    fun computeResponseDiff(leftRaw: String, rightRaw: String): List<JsonDiffLine> {
        val leftTrim = leftRaw.trim()
        val rightTrim = rightRaw.trim()

        val leftJson = parseAnyJson(leftTrim)
        val rightJson = parseAnyJson(rightTrim)
        if (leftJson != null && rightJson != null) {
            val out = mutableListOf<JsonDiffLine>()
            diffJsonValues("$", leftJson, rightJson, out)
            return out
        }

        // Line-based fallback diff for non-JSON payloads
        val leftLines = leftRaw.lines()
        val rightLines = rightRaw.lines()
        val maxLen = maxOf(leftLines.size, rightLines.size)
        val out = mutableListOf<JsonDiffLine>()
        for (i in 0 until maxLen) {
            val l = leftLines.getOrNull(i)
            val r = rightLines.getOrNull(i)
            when {
                l == r -> out.add(JsonDiffLine("Line ${i + 1}", DiffType.UNCHANGED, l, r))
                l == null -> out.add(JsonDiffLine("Line ${i + 1}", DiffType.ADDED, null, r))
                r == null -> out.add(JsonDiffLine("Line ${i + 1}", DiffType.REMOVED, l, null))
                else -> out.add(JsonDiffLine("Line ${i + 1}", DiffType.MODIFIED, l, r))
            }
        }
        return out
    }

    private fun parseAnyJson(raw: String): Any? = runCatching {
        when {
            raw.startsWith("{") -> JSONObject(raw)
            raw.startsWith("[") -> JSONArray(raw)
            else -> null
        }
    }.getOrNull()

    private fun diffJsonValues(path: String, left: Any?, right: Any?, out: MutableList<JsonDiffLine>) {
        when {
            left is JSONObject && right is JSONObject -> {
                val keys = LinkedHashSet<String>()
                left.keys().forEachRemaining { keys.add(it) }
                right.keys().forEachRemaining { keys.add(it) }
                keys.forEach { key ->
                    val childPath = "$path.$key"
                    val hasL = left.has(key)
                    val hasR = right.has(key)
                    when {
                        hasL && !hasR -> out.add(JsonDiffLine(childPath, DiffType.REMOVED, left.opt(key)?.toString(), null))
                        !hasL && hasR -> out.add(JsonDiffLine(childPath, DiffType.ADDED, null, right.opt(key)?.toString()))
                        else -> diffJsonValues(childPath, left.opt(key), right.opt(key), out)
                    }
                }
            }
            left is JSONArray && right is JSONArray -> {
                val maxLen = maxOf(left.length(), right.length())
                for (i in 0 until maxLen) {
                    val childPath = "$path[$i]"
                    when {
                        i >= left.length() -> out.add(JsonDiffLine(childPath, DiffType.ADDED, null, right.opt(i)?.toString()))
                        i >= right.length() -> out.add(JsonDiffLine(childPath, DiffType.REMOVED, left.opt(i)?.toString(), null))
                        else -> diffJsonValues(childPath, left.opt(i), right.opt(i), out)
                    }
                }
            }
            else -> {
                val lStr = left?.toString()
                val rStr = right?.toString()
                if (lStr == rStr) {
                    out.add(JsonDiffLine(path, DiffType.UNCHANGED, lStr, rStr))
                } else {
                    out.add(JsonDiffLine(path, DiffType.MODIFIED, lStr, rStr))
                }
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }
}
