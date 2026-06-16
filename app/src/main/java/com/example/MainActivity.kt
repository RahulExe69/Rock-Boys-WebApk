package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.example.network.NetworkMonitor
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.webview.GameWebView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var networkMonitor: NetworkMonitor
    private var isWebViewLoading = true
    
    // State to toggle bottom bar visibility dynamically via Javascript Interface
    var isBottomBarVisible by mutableStateOf(false)
        private set

    fun updateBottomBarVisibility(visible: Boolean) {
        isBottomBarVisible = visible
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        splashScreen.setKeepOnScreenCondition {
            isWebViewLoading
        }
        
        enableEdgeToEdge()
        
        networkMonitor = NetworkMonitor(applicationContext)

        // Monitor network state dynamically with a robust application context and main dispatcher safety
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            networkMonitor.isConnected.collectLatest { isConnected ->
                if (!isConnected) {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "Internet Connection Lost. Core sectors operating on cache.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        // Suppress any unexpected toast exceptions in background lifecycle transitions
                    }
                }
            }
        }

        setContent {
            MyApplicationTheme {
                GameWebView(
                    targetUrl = "https://rockboys.netlify.app",
                    isBottomBarVisible = isBottomBarVisible,
                    onExitRequested = {
                        finish() // Properly tear down and exit the app
                    },
                    onPageLoaded = {
                        isWebViewLoading = false
                    }
                )
            }
        }
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @android.webkit.JavascriptInterface
    fun setBottomBarVisibility(visible: Boolean) {
        activity.runOnUiThread {
            activity.updateBottomBarVisibility(visible)
        }
    }
}

