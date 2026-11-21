package com.axion.ultimatefetcher.network.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class AppFilterValidationResult(
    val requestedPackages: List<String>,
    val validPackages: List<String>,
    val validAppNames: List<String>,
    val unavailablePackages: List<String>,
    val monitorAllApps: Boolean,
    val fatalError: String? = null
)

interface VpnAppFilterTarget {
    fun addAllowedApplication(packageName: String)
    fun addDisallowedApplication(packageName: String)
}

object AppFilterValidator {

    fun validateSelectedPackages(
        context: Context,
        requestedPackages: List<String>,
        selfPackageName: String = context.packageName
    ): AppFilterValidationResult {
        val distinct = requestedPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackageName }
            .distinct()

        if (distinct.isEmpty()) {
            return AppFilterValidationResult(
                requestedPackages = emptyList(),
                validPackages = emptyList(),
                validAppNames = emptyList(),
                unavailablePackages = emptyList(),
                monitorAllApps = true,
                fatalError = null
            )
        }

        val pm = context.packageManager
        val validPkgs = mutableListOf<String>()
        val validNames = mutableListOf<String>()
        val unavailable = mutableListOf<String>()

        distinct.forEach { pkg ->
            val appInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
            }.getOrNull()

            if (appInfo != null && appInfo.enabled) {
                validPkgs.add(pkg)
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                validNames.add(label.ifBlank { pkg })
            } else {
                unavailable.add(pkg)
            }
        }

        val fatal = if (validPkgs.isEmpty()) {
            "Selected app(s) are uninstalled or unavailable: ${unavailable.joinToString(", ")}"
        } else {
            null
        }

        return AppFilterValidationResult(
            requestedPackages = distinct,
            validPackages = validPkgs,
            validAppNames = validNames,
            unavailablePackages = unavailable,
            monitorAllApps = false,
            fatalError = fatal
        )
    }

    /**
     * Applies validated packages onto VpnService.Builder (via VpnAppFilterTarget adapter).
     * Returns pair of (appliedPackages, errorMessageIfAny).
     */
    fun applyToBuilder(
        validation: AppFilterValidationResult,
        selfPackageName: String,
        target: VpnAppFilterTarget
    ): Pair<List<String>, String?> {
        if (validation.fatalError != null) {
            return emptyList<String>() to validation.fatalError
        }

        if (validation.monitorAllApps) {
            runCatching { target.addDisallowedApplication(selfPackageName) }
            return emptyList<String>() to null
        }

        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        validation.validPackages.forEach { pkg ->
            val ok = runCatching {
                target.addAllowedApplication(pkg)
                true
            }.getOrDefault(false)
            if (ok) applied.add(pkg) else failed.add(pkg)
        }

        if (applied.isEmpty()) {
            return emptyList<String>() to "VpnService failed to allow selected app(s): ${failed.joinToString(", ")}"
        }
        return applied to null
    }
}
