package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.axion.ultimatefetcher.UltimateFetcherApplication
import com.axion.ultimatefetcher.ui.UltimateFetcherApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            UltimateFetcherApp()
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? UltimateFetcherApplication)?.container?.telemetryMonitor?.start()
    }

    override fun onStop() {
        super.onStop()
        (application as? UltimateFetcherApplication)?.container?.telemetryMonitor?.stop()
    }
}
