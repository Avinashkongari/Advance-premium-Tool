package com.axion.ultimatefetcher.root

import com.axion.ultimatefetcher.data.database.entities.RootAuditLog
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.domain.models.RootAvailabilityState
import com.axion.ultimatefetcher.domain.models.RootDiagnosticResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class SafeRootDiagnosticCommand(
    val title: String,
    val category: String,
    val description: String,
    val command: String
) {
    INTERFACES(
        title = "Network Interfaces (ip addr)",
        category = "INTERFACES",
        description = "Read-only list of kernel network interfaces and IP addresses.",
        command = "ip addr show"
    ),
    ROUTING_TABLE(
        title = "Routing Table (ip route)",
        category = "ROUTES",
        description = "Read-only inspection of active kernel routing table.",
        command = "ip route show"
    ),
    FIREWALL_RULES(
        title = "Firewall Rules (iptables -L)",
        category = "FIREWALL",
        description = "Read-only listing of iptables filter rules (zero modifications).",
        command = "iptables -L -n -v"
    ),
    NET_DEV_COUNTERS(
        title = "Kernel Interface Counters (/proc/net/dev)",
        category = "COUNTERS",
        description = "Read-only packet/byte counters per network interface.",
        command = "cat /proc/net/dev"
    ),
    TCP_SYSCTL(
        title = "Network Sysctl Parameters",
        category = "SYSCTL",
        description = "Read-only TCP/IP kernel tuning variables.",
        command = "sysctl net.ipv4.tcp_congestion_control net.ipv4.ip_forward"
    )
}

class RootDiagnosticsEngine(
    private val repository: UltimateFetcherRepository
) {
    private val commonSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/adb/magisk/magisk",
        "/data/adb/ksu/bin/su"
    )

    suspend fun checkRootAvailabilityPassive(): RootAvailabilityState = withContext(Dispatchers.IO) {
        val foundPath = commonSuPaths.firstOrNull { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
        if (foundPath != null) {
            RootAvailabilityState(
                isRootDetected = true,
                isAuthorized = false,
                statusLabel = "Root Binary Detected (Authorization Pending)",
                details = "Passive filesystem check found '$foundPath'. Superuser is never requested automatically; tap Authorize to verify read-only access.",
                suPath = foundPath
            )
        } else {
            RootAvailabilityState(
                isRootDetected = false,
                isAuthorized = false,
                statusLabel = "Not Rooted (Standard Non-Root Android)",
                details = "No su binary detected. All core UltimateFetcher modules work fully without root.",
                suPath = null
            )
        }
    }

    suspend fun requestExplicitSuperuserVerification(current: RootAvailabilityState): RootAvailabilityState =
        withContext(Dispatchers.IO) {
            if (!current.isRootDetected) return@withContext current
            val (exitCode, out) = executeSuReadOnly("id")
            val authorized = exitCode == 0 && out.contains("uid=0")
            repository.insertRootAuditLog(
                RootAuditLog(
                    category = "AUTH",
                    command = "su -c id",
                    exitCode = exitCode,
                    authorizedByUser = true,
                    outputPreview = out.take(600)
                )
            )
            current.copy(
                isAuthorized = authorized,
                statusLabel = if (authorized) "Root Authorized (Read-Only Mode)" else "Superuser Request Denied",
                details = if (authorized) {
                    "Explicit user authorization verified (uid=0). Every diagnostic command still requires confirmation and is audited in Room."
                } else {
                    "Superuser authorization was denied or failed (exit $exitCode)."
                }
            )
        }

    suspend fun runReadOnlyDiagnostic(
        cmd: SafeRootDiagnosticCommand,
        state: RootAvailabilityState
    ): RootDiagnosticResult = withContext(Dispatchers.IO) {
        if (!state.isRootDetected || !state.isAuthorized) {
            return@withContext RootDiagnosticResult(
                title = cmd.title,
                command = cmd.command,
                exitCode = -1,
                output = "Root diagnostics require explicit user authorization on a rooted device."
            )
        }
        val (exitCode, output) = executeSuReadOnly(cmd.command)
        repository.insertRootAuditLog(
            RootAuditLog(
                category = cmd.category,
                command = cmd.command,
                exitCode = exitCode,
                authorizedByUser = true,
                outputPreview = output.take(1500)
            )
        )
        RootDiagnosticResult(
            title = cmd.title,
            command = cmd.command,
            exitCode = exitCode,
            output = output.ifBlank { "(No output)" }
        )
    }

    private fun executeSuReadOnly(command: String): Pair<Int, String> {
        return runCatching {
            val proc = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            code to out.trim()
        }.getOrElse { err ->
            -1 to "Execution error: ${err.message}"
        }
    }
}
