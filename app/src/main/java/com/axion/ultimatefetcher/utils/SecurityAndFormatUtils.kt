package com.axion.ultimatefetcher.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SensitiveDataRedactor {
    private val sensitiveHeaderKeys = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "x-api-key",
        "api-key",
        "apikey",
        "x-auth-token",
        "x-access-token",
        "x-refresh-token",
        "x-csrf-token",
        "x-secret-key",
        "x-session-token",
        "token",
        "access-token"
    )

    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.trim().lowercase(Locale.US)
        if (lower in sensitiveHeaderKeys) return true
        return lower.contains("token") ||
            lower.contains("secret") ||
            lower.contains("api-key") ||
            lower.contains("apikey") ||
            lower.contains("password") ||
            lower == "authorization" ||
            lower == "cookie"
    }

    fun redactSetCookiePreserveAttributes(rawSetCookie: String): String {
        val parts = rawSetCookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "[REDACTED]"
        val first = parts.first()
        val eqIdx = first.indexOf('=')
        val safeNamePart = if (eqIdx > 0) "${first.substring(0, eqIdx)}=[REDACTED]" else "[REDACTED]"
        val attrs = parts.drop(1)
        return if (attrs.isEmpty()) safeNamePart else "$safeNamePart; ${attrs.joinToString("; ")}"
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(headers.size)
        for ((k, v) in headers) {
            out[k] = when {
                k.equals("Set-Cookie", ignoreCase = true) -> redactSetCookiePreserveAttributes(v)
                isSensitiveHeader(k) -> "[REDACTED]"
                else -> v
            }
        }
        return out
    }

    fun headersToRedactedJson(headers: Map<String, String>): String {
        val obj = JSONObject()
        redactHeaders(headers).forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}

class SecureCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun saveCredential(profileName: String, headerName: String, secretValue: String): Boolean {
        val cleanName = profileName.trim()
        val cleanHeader = headerName.trim()
        if (cleanName.isEmpty() || cleanHeader.isEmpty() || secretValue.isEmpty()) return false
        return runCatching {
            val payload = JSONObject().apply {
                put("header", cleanHeader)
                put("value", secretValue)
            }.toString()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + ciphertext.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString("$KEY_PREFIX$cleanName", encoded).apply()
            true
        }.getOrDefault(false)
    }

    fun loadCredential(profileName: String): Pair<String, String>? {
        val encoded = prefs.getString("$KEY_PREFIX$profileName", null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 14) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            val obj = JSONObject(plain)
            obj.getString("header") to obj.getString("value")
        }.getOrNull()
    }

    fun listCredentialNames(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
            .sorted()

    fun deleteCredential(profileName: String) {
        prefs.edit().remove("$KEY_PREFIX$profileName").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "ultimate_fetcher_cred_vault_aes256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "uf_encrypted_credentials"
        private const val KEY_PREFIX = "cred_"
    }
}

object FormatUtils {
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        val bps = bytesPerSec.coerceAtLeast(0L)
        return when {
            bps < 1024L -> "$bps B/s"
            bps < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
        }
    }

    @Synchronized
    fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        return timeFormatter.format(Date(timestamp))
    }

    @Synchronized
    fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        return dateTimeFormatter.format(Date(timestamp))
    }

    fun prettyPrintJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return raw
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        }.getOrDefault(raw)
    }

    fun isLikelyJson(raw: String, contentType: String? = null): Boolean {
        if (contentType?.contains("json", ignoreCase = true) == true) return true
        val t = raw.trim()
        if (t.isEmpty()) return false
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }

    fun classifyPort(port: Int, protocol: String): String = when {
        port == 53 -> "DNS"
        port == 80 -> "HTTP"
        port == 443 && protocol.equals("UDP", ignoreCase = true) -> "QUIC / HTTP3"
        port == 443 && protocol.equals("QUIC", ignoreCase = true) -> "QUIC / HTTP3"
        port == 443 -> "HTTPS / TLS"
        port == 853 -> "DNS-over-TLS (DoT)"
        port == 22 -> "SSH"
        port == 25 || port == 587 || port == 465 -> "SMTP"
        port == 123 -> "NTP"
        port == 5228 || port == 5222 -> "Push / XMPP"
        port == 8080 || port == 8443 -> "Alt HTTP(S)"
        port == 1883 || port == 8883 -> "MQTT"
        else -> "$protocol:$port"
    }
}
