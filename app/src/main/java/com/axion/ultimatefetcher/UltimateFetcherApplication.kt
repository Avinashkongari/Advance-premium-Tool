package com.axion.ultimatefetcher

import android.app.Application
import android.content.Context
import com.axion.ultimatefetcher.data.SettingsDataStore
import com.axion.ultimatefetcher.data.database.UltimateFetcherDatabase
import com.axion.ultimatefetcher.data.repositories.UltimateFetcherRepository
import com.axion.ultimatefetcher.network.api.ApiLabClient
import com.axion.ultimatefetcher.network.api.LocalWebhookServer
import com.axion.ultimatefetcher.network.api.OwnAppDebugInterceptor
import com.axion.ultimatefetcher.network.api.WebSocketManager
import com.axion.ultimatefetcher.network.diagnostics.ApiDiagnosticsEngine
import com.axion.ultimatefetcher.network.diagnostics.DnsResolverEngine
import com.axion.ultimatefetcher.network.diagnostics.LiveNetworkTelemetryMonitor
import com.axion.ultimatefetcher.network.diagnostics.NetworkQualityEngine
import com.axion.ultimatefetcher.root.RootDiagnosticsEngine
import com.axion.ultimatefetcher.utils.SecureCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: UltimateFetcherDatabase = UltimateFetcherDatabase.getInstance(appContext)
    val settingsDataStore: SettingsDataStore = SettingsDataStore(appContext)
    val credentialStore: SecureCredentialStore = SecureCredentialStore(appContext)
    val repository: UltimateFetcherRepository = UltimateFetcherRepository(appContext, database)

    val ownAppDebugInterceptor: OwnAppDebugInterceptor = OwnAppDebugInterceptor { repository }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(ownAppDebugInterceptor)
        .build()

    val telemetryMonitor: LiveNetworkTelemetryMonitor = LiveNetworkTelemetryMonitor(appContext, okHttpClient)
    val dnsResolverEngine: DnsResolverEngine = DnsResolverEngine()
    val networkQualityEngine: NetworkQualityEngine = NetworkQualityEngine(okHttpClient)
    val apiDiagnosticsEngine: ApiDiagnosticsEngine = ApiDiagnosticsEngine(okHttpClient)
    val apiLabClient: ApiLabClient = ApiLabClient(okHttpClient, repository)
    val webSocketManager: WebSocketManager = WebSocketManager(okHttpClient)
    val localWebhookServer: LocalWebhookServer = LocalWebhookServer()
    val rootDiagnosticsEngine: RootDiagnosticsEngine = RootDiagnosticsEngine(repository)
}

class UltimateFetcherApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.telemetryMonitor.start()

        appScope.launch {
            val settings = container.settingsDataStore.settingsFlow.first()
            container.ownAppDebugInterceptor.setOptInEnabled(settings.ownAppDebugEnabled)
            if (settings.autoClearLogs) {
                container.repository.pruneExpiredLogs(settings.logRetentionDays)
            }
        }
    }
}
