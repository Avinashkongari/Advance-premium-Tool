package com.axion.ultimatefetcher.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.axion.ultimatefetcher.UltimateFetcherApplication
import com.axion.ultimatefetcher.data.database.entities.EndpointMonitor
import com.example.R
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiHealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UltimateFetcherApplication ?: return Result.success()
        val db = app.container.database
        val settings = app.container.settingsDataStore.settingsFlow.first()
        val enabledMonitors = db.endpointMonitorDao().getEnabledSnapshot()

        enabledMonitors.forEach { monitor ->
            val updated = checkSingleEndpoint(app.container.okHttpClient, monitor)
            db.endpointMonitorDao().update(updated)
            if (!updated.lastHealthy && settings.healthMonitorNotifications) {
                postFailureNotification(applicationContext, updated)
            }
        }
        return Result.success()
    }

    private fun postFailureNotification(context: Context, monitor: EndpointMonitor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.health_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.health_notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Endpoint Offline: ${monitor.name}")
            .setContentText("HTTP ${monitor.lastStatusCode} (${monitor.lastLatencyMs} ms) — ${monitor.url}")
            .setAutoCancel(true)
            .build()
        nm.notify((4200 + monitor.id.toInt()), notif)
    }

    companion object {
        private const val WORK_NAME = "uf_api_health_periodic_check"
        private const val CHANNEL_ID = "uf_api_health_alerts"

        fun checkSingleEndpoint(client: OkHttpClient, monitor: EndpointMonitor): EndpointMonitor {
            val url = monitor.url.trim().let {
                if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
            }
            val t0 = System.nanoTime()
            return runCatching {
                val req = Request.Builder().url(url).method(monitor.method.uppercase(), null).build()
                client.newCall(req).execute().use { resp ->
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                    monitor.copy(
                        lastStatusCode = resp.code,
                        lastLatencyMs = ms,
                        lastHealthy = resp.code in 200..399,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                }
            }.getOrElse {
                val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
                monitor.copy(
                    lastStatusCode = 0,
                    lastLatencyMs = ms,
                    lastHealthy = false,
                    lastCheckedAt = System.currentTimeMillis()
                )
            }
        }

        fun schedulePeriodicChecks(context: Context, intervalMinutes: Long) {
            val safeMin = intervalMinutes.coerceAtLeast(15L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<ApiHealthCheckWorker>(safeMin, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancelPeriodicChecks(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
