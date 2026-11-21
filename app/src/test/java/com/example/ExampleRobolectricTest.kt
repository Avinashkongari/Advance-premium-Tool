package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.axion.ultimatefetcher.domain.models.DiffType
import com.axion.ultimatefetcher.network.diagnostics.ApiDiagnosticsEngine
import com.axion.ultimatefetcher.network.vpn.PacketParser
import com.axion.ultimatefetcher.utils.SensitiveDataRedactor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches UltimateFetcher`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("UltimateFetcher", appName)
    }

    @Test
    fun `dns query builder and parser round trip`() {
        val raw = PacketParser.buildDnsQueryPacket("api.example.com", "A", 0x1234)
        val parsed = PacketParser.parseDnsPayload(raw, 0, raw.size)
        assertNotNull(parsed)
        assertEquals(0x1234, parsed!!.transactionId)
        assertEquals("api.example.com", parsed.queryName)
        assertEquals("A", parsed.queryType)
        assertFalse(parsed.isResponse)
    }

    @Test
    fun `sensitive data redactor hides tokens and cookie values while preserving attributes`() {
        val redacted = SensitiveDataRedactor.redactHeaders(
            mapOf(
                "Authorization" to "Bearer super-secret-jwt",
                "X-Api-Key" to "live_123456",
                "Content-Type" to "application/json",
                "Set-Cookie" to "session_id=secretval999; HttpOnly; Secure; SameSite=Strict"
            )
        )
        assertFalse(redacted["Authorization"].orEmpty().contains("super-secret-jwt"))
        assertFalse(redacted["X-Api-Key"].orEmpty().contains("live_123456"))
        assertEquals("application/json", redacted["Content-Type"])
        val cookieAttr = redacted["Set-Cookie"].orEmpty()
        assertFalse(cookieAttr.contains("secretval999"))
        assertTrue(cookieAttr.contains("HttpOnly"))
        assertTrue(cookieAttr.contains("Secure"))
    }

    @Test
    fun `json response diff detects added removed and modified fields`() {
        val engine = ApiDiagnosticsEngine(OkHttpClient())
        val diff = engine.computeResponseDiff(
            """{"status":"ok","version":1,"oldField":"x"}""",
            """{"status":"ok","version":2,"newField":"y"}"""
        )
        assertTrue(diff.any { it.path == "$.version" && it.type == DiffType.MODIFIED })
        assertTrue(diff.any { it.path == "$.newField" && it.type == DiffType.ADDED })
        assertTrue(diff.any { it.path == "$.oldField" && it.type == DiffType.REMOVED })
        assertTrue(diff.any { it.path == "$.status" && it.type == DiffType.UNCHANGED })
    }
}
