package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.example.network.NetworkMonitor
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.webview.GameWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var networkMonitor: NetworkMonitor
    
    // State to toggle bottom bar visibility dynamically via Javascript Interface
    var isBottomBarVisible by mutableStateOf(false)
        private set

    fun updateBottomBarVisibility(visible: Boolean) {
        isBottomBarVisible = visible
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Core Starting Window API setup - disappears immediately to hand control to Jetpack Compose
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        networkMonitor = NetworkMonitor(applicationContext)

        // Monitor network state dynamically with high safety margins
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            var wasPreviouslyConnected: Boolean? = null
            try {
                networkMonitor.isConnected.collectLatest { isConnected ->
                    if (wasPreviouslyConnected != null && wasPreviouslyConnected == true && !isConnected) {
                        try {
                            Toast.makeText(
                                applicationContext,
                                "Internet Connection Lost. Core sectors operating on cache.",
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Throwable) {
                            // Suppress exceptions in transient states
                        }
                    }
                    wasPreviouslyConnected = isConnected
                }
            } catch (e: Throwable) {
                // Ignore flow collection failures
            }
        }

        setContent {
            MyApplicationTheme {
                // Reactive Compose-level custom splash screen layer shown for exactly 1.2s minimum
                var splashActive by remember { mutableStateOf(true) }
                
                LaunchedEffect(Unit) {
                    delay(1200)
                    splashActive = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Start loading and rendering main web or custom offline screen immediately
                    GameWebView(
                        targetUrl = "https://rockboys.netlify.app",
                        isBottomBarVisible = isBottomBarVisible,
                        onExitRequested = {
                            finish()
                        }
                    )

                    // Overlay starting animation with same exact background drawable, blocking white flashes beautifully
                    if (splashActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF07090E))
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.rock_boys_splash),
                                contentDescription = "Starting Screen",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
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

