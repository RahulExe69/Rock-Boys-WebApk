package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
    
    // Webview reference to evaluate callback scripts directly
    var mainWebView: android.webkit.WebView? = null

    // Register a standard Activity Result Launcher to track VPN authorization results reliably
    val vpnLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        notifyVpnPermission(granted)
        if (granted) {
            Toast.makeText(this, "VPN authorization granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN authorization denied.", Toast.LENGTH_SHORT).show()
        }
    }

    fun notifyVpnPermission(granted: Boolean) {
        runOnUiThread {
            mainWebView?.evaluateJavascript("javascript:if(window.onVpnPermissionResult) { window.onVpnPermissionResult($granted); }", null)
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically monitor and update system overlay permission on resume
        val hasOverlay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
        mainWebView?.evaluateJavascript("javascript:if(window.onOverlayPermissionResult) { window.onOverlayPermissionResult($hasOverlay); }", null)
    }

    // State to toggle bottom bar visibility dynamically via Javascript Interface
    var isBottomBarVisible by mutableStateOf(false)
        private set

    fun updateBottomBarVisibility(visible: Boolean) {
        isBottomBarVisible = visible
    }

    var isRaidReloadActive by mutableStateOf(false)
        private set

    fun updateRaidReloadActive(active: Boolean) {
        isRaidReloadActive = active
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
                                "Internet Connection Lost. Please check your network connection.",
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
                val context = LocalContext.current
                // Reactive Compose-level custom splash screen layer shown for exactly 1.2s minimum
                var splashActive by remember { mutableStateOf(true) }
                
                LaunchedEffect(Unit) {
                    delay(1200)
                    splashActive = false
                    try {
                        val soundId = context.resources.getIdentifier("app_opening_sound", "raw", context.packageName)
                        if (soundId != 0) {
                            val mediaPlayer: android.media.MediaPlayer? = android.media.MediaPlayer.create(context, soundId)
                            mediaPlayer?.setOnCompletionListener { mp ->
                                mp.release()
                            }
                            mediaPlayer?.start()
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Start loading and rendering main web or custom offline screen immediately
                    GameWebView(
                        targetUrl = "https://rockboys.netlify.app",
                        isBottomBarVisible = isBottomBarVisible,
                        isRaidReloadActive = isRaidReloadActive,
                        onRaidReloadActiveChanged = { updateRaidReloadActive(it) },
                        onExitRequested = {
                            finish()
                        }
                    )

                    // Overlay starting animation with same exact background drawable, blocking white flashes beautifully
                    if (splashActive) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF07090E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.rock_boys_splash),
                                contentDescription = "Starting Screen",
                                modifier = Modifier.size(144.dp),
                                contentScale = ContentScale.Fit
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

    @android.webkit.JavascriptInterface
    fun hasOverlayPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(activity)
        } else {
            true
        }
    }

    @android.webkit.JavascriptInterface
    fun requestOverlayPermission() {
        activity.runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            } else {
                android.widget.Toast.makeText(activity, "Overlay permission is granted by default.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun hasVpnPermission(): Boolean {
        return android.net.VpnService.prepare(activity) == null
    }

    @android.webkit.JavascriptInterface
    fun requestVpnPermission() {
        activity.runOnUiThread {
            val vpnIntent = android.net.VpnService.prepare(activity)
            if (vpnIntent != null) {
                activity.vpnLauncher.launch(vpnIntent)
            } else {
                android.widget.Toast.makeText(activity, "VPN authorization is already configured & authenticated.", android.widget.Toast.LENGTH_SHORT).show()
                activity.notifyVpnPermission(true)
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun isFloatingButtonEnabled(): Boolean {
        return com.example.ui.webview.RaidReloadSettings.isShieldEnabled(activity)
    }

    @android.webkit.JavascriptInterface
    fun setFloatingButtonEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled && !hasOverlayPermission()) {
                android.widget.Toast.makeText(activity, "System drawing overlay permission is required.", android.widget.Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }

            com.example.ui.webview.RaidReloadSettings.saveShieldEnabled(activity, enabled)
            val serviceIntent = android.content.Intent(activity, com.example.ui.webview.FloatingShieldService::class.java)
            if (enabled) {
                com.example.ui.webview.FloatingShieldService.buttonSizeDp = com.example.ui.webview.RaidReloadSettings.getShieldSize(activity)
                com.example.ui.webview.FloatingShieldService.idleTransparencyPercent = com.example.ui.webview.RaidReloadSettings.getShieldTransparency(activity)
                activity.startService(serviceIntent)
                android.widget.Toast.makeText(activity, "Floating reload tool activated!", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                activity.stopService(serviceIntent)
                android.widget.Toast.makeText(activity, "Floating tool stopped.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun getFloatingButtonSize(): Int {
        return com.example.ui.webview.RaidReloadSettings.getShieldSize(activity)
    }

    @android.webkit.JavascriptInterface
    fun setFloatingButtonSize(size: Int) {
        activity.runOnUiThread {
            com.example.ui.webview.RaidReloadSettings.saveShieldSize(activity, size)
            com.example.ui.webview.FloatingShieldService.buttonSizeDp = size
            if (isFloatingButtonEnabled() && com.example.ui.webview.FloatingShieldService.isServiceRunning) {
                val updateIntent = android.content.Intent(activity, com.example.ui.webview.FloatingShieldService::class.java).apply {
                    action = "UPDATE_STYLE"
                }
                activity.startService(updateIntent)
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun getFloatingButtonTransparency(): Int {
        return com.example.ui.webview.RaidReloadSettings.getShieldTransparency(activity)
    }

    @android.webkit.JavascriptInterface
    fun setFloatingButtonTransparency(transparency: Int) {
        activity.runOnUiThread {
            com.example.ui.webview.RaidReloadSettings.saveShieldTransparency(activity, transparency)
            com.example.ui.webview.FloatingShieldService.idleTransparencyPercent = transparency
            if (isFloatingButtonEnabled() && com.example.ui.webview.FloatingShieldService.isServiceRunning) {
                val updateIntent = android.content.Intent(activity, com.example.ui.webview.FloatingShieldService::class.java).apply {
                    action = "UPDATE_STYLE"
                }
                activity.startService(updateIntent)
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun centerFloatingButton() {
        activity.runOnUiThread {
            if (isFloatingButtonEnabled() && com.example.ui.webview.FloatingShieldService.isServiceRunning) {
                val stopIntent = android.content.Intent(activity, com.example.ui.webview.FloatingShieldService::class.java)
                activity.stopService(stopIntent)
                val startIntent = android.content.Intent(activity, com.example.ui.webview.FloatingShieldService::class.java)
                activity.startService(startIntent)
                android.widget.Toast.makeText(activity, "Floating icon recentered", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(activity, "Activate Floating Shield to configure position", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun setNativeRaidReloadActive(active: Boolean) {
        activity.runOnUiThread {
            activity.updateRaidReloadActive(active)
        }
    }
}

