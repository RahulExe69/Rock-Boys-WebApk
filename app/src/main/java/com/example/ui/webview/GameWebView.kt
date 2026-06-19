package com.example.ui.webview

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.example.MainActivity
import com.example.R
import com.example.WebAppInterface
import com.example.ui.theme.CyberBlack
import com.example.ui.theme.CyberDark
import com.example.ui.theme.CyberGray
import com.example.ui.theme.CyberLine
import com.example.ui.theme.ToxicGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.LaserRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.network.NetworkMonitor

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameWebView(
    targetUrl: String = "https://rockboys.netlify.app",
    isBottomBarVisible: Boolean = false,
    onExitRequested: () -> Unit,
    onPageLoaded: () -> Unit = {}
) {
    val context = LocalContext.current
    
    val networkMonitor = remember { NetworkMonitor(context.applicationContext) }
    
    // WebView reference and states
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val isInitiallyConnected = remember {
        try {
            networkMonitor.isCurrentlyConnected()
        } catch (e: Throwable) {
            false
        }
    }
    var isPageLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var hasError by remember { mutableStateOf(false) }
    var isScrollAtTop by remember { mutableStateOf(true) }
    var canGoBackState by remember { mutableStateOf(false) }
    var canGoForwardState by remember { mutableStateOf(false) }
    var currentUrlState by remember { mutableStateOf(targetUrl) }
    
    // Exit validation confirmation dialog
    var showExitDialog by remember { mutableStateOf(false) }

    // State for temporary Raid Reload under maintenance screen
    var isRaidReloadActive by remember { mutableStateOf(false) }

    // Force Update overlay state
    var showForceUpdateScreen by remember { mutableStateOf(false) }
    var detectedUpdateInfo by remember { mutableStateOf<com.example.network.UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // Check for raw Github dynamic force updates on startup
    LaunchedEffect(Unit) {
        isCheckingUpdate = true
        try {
            android.util.Log.d("UpdateCheck", "Starting background update check tasks...")
            val updateInfo = com.example.network.UpdateChecker.checkForUpdates()
            if (updateInfo != null) {
                val runningCode = com.example.network.UpdateChecker.getRunningVersionCode(context)
                android.util.Log.d("UpdateCheck", "Version details fetched. Running Code: $runningCode, Remote Code: ${updateInfo.versionCode}")
                if (updateInfo.versionCode > runningCode && updateInfo.forceUpdate) {
                    android.util.Log.d("UpdateCheck", "New force update available! Displaying force update alert overlay.")
                    detectedUpdateInfo = updateInfo
                    showForceUpdateScreen = true
                } else {
                    android.util.Log.d("UpdateCheck", "Application is fully up to date. Version comparison matched (Running: $runningCode vs Remote: ${updateInfo.versionCode})")
                }
            } else {
                android.util.Log.e("UpdateCheck", "Could not fetch remote version metadata. Check skipped.")
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateCheck", "Failure executing state check tasks: ${e.message}", e)
        } finally {
            isCheckingUpdate = false
        }
    }

    // Dynamic status bar color adjustment based on active screen and load state
    val activity = LocalContext.current as? Activity
    LaunchedEffect(isRaidReloadActive, showForceUpdateScreen, isPageLoading) {
        val window = activity?.window
        if (window != null) {
            val statusBarColor = when {
                showForceUpdateScreen -> Color(0xFF2E1A0C)
                isRaidReloadActive -> Color(0xFF423C35)
                isPageLoading -> Color(0xFF07090E)
                else -> Color(0xFFEAD8C3)
            }
            val darkIcons = when {
                showForceUpdateScreen -> false
                isRaidReloadActive -> false
                isPageLoading -> false
                else -> true
            }
            window.statusBarColor = statusBarColor.toArgb()
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = darkIcons
        }
    }

    // File selection callback for uploading files
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // File chooser launcher
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val result = if (uri != null) arrayOf(uri) else null
        fileChooserCallback?.onReceiveValue(result)
        fileChooserCallback = null
    }

    // Intercept hardware Android back button
    BackHandler {
        if (showForceUpdateScreen) {
            Toast.makeText(context, "A mandatory update is required to proceed.", Toast.LENGTH_SHORT).show()
        } else if (isRaidReloadActive) {
            isRaidReloadActive = false
        } else {
            val webView = webViewInstance
            if (webView != null && webView.canGoBack()) {
                webView.goBack()
            } else {
                showExitDialog = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        bottomBar = {
            AnimatedVisibility(
                visible = isBottomBarVisible && !showForceUpdateScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 16.dp,
                            clip = false
                        )
                        .background(
                            color = Color(0xFFEAD8C3)
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(72.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Options 1: HOME Tab (loads the target URL dashboard)
                        val item1Selected = !isRaidReloadActive
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    isRaidReloadActive = false
                                }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Selected capsule pill indicator backdrop
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(28.dp)
                                    .background(
                                        color = if (item1Selected) Color(0xFFF7D5C8) else Color.Transparent,
                                        shape = RoundedCornerShape(percent = 50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = if (item1Selected) Color(0xFF3C2414) else Color(0xFF3C2414).copy(alpha = 0.62f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            Text(
                                text = "Home",
                                color = if (item1Selected) Color(0xFF3C2414) else Color(0xFF3C2414).copy(alpha = 0.62f),
                                fontSize = 11.sp,
                                fontWeight = if (item1Selected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.3.sp
                            )
                        }

                        // Options 2: RAID RELOAD Tab (Opens the custom interactive under maintenance page)
                        val item2Selected = isRaidReloadActive
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    isRaidReloadActive = true
                                }
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Selected capsule pill indicator backdrop
                            Box(
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(28.dp)
                                    .background(
                                        color = if (item2Selected) Color(0xFFF7D5C8) else Color.Transparent,
                                        shape = RoundedCornerShape(percent = 50)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Raid Reload",
                                    tint = if (item2Selected) Color(0xFF3C2414) else Color(0xFF3C2414).copy(alpha = 0.62f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            Text(
                                text = "Raid Reload Tool",
                                color = if (item2Selected) Color(0xFF3C2414) else Color(0xFF3C2414).copy(alpha = 0.62f),
                                fontSize = 11.sp,
                                fontWeight = if (item2Selected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
                .background(CyberBlack)
        ) {
            // Raw Android WebView container
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        // Prevent the classic default white flash before/while loading web content
                        setBackgroundColor(android.graphics.Color.parseColor("#07090E"))
                        
                        // Append Native custom user agent with trailing space for bulletproof web detection
                        settings.userAgentString = settings.userAgentString + " RockBoysNative"

                        // Performance and features configurations
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)
                        settings.setBuiltInZoomControls(false)
                        settings.setDisplayZoomControls(false)
                        settings.textZoom = 100
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        // Add WebView Interface Handler to listen to bottom bar state transitions
                        val mainActivity = ctx as? MainActivity
                        if (mainActivity != null) {
                            addJavascriptInterface(WebAppInterface(mainActivity), "Android")
                        }
                        
                        // Enable cookie acceptance for smoother state synchronization
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        // Download integration support - intercept APK download calls dynamically to point to our custom updated APK endpoint
                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            try {
                                val resolvedUrl = if (url.endsWith(".apk") || url.contains("app-debug.apk") || url.contains(".apk") || mimetype == "application/vnd.android.package-archive") {
                                    "https://github.com/RahulExe69/Rock-Boys-WebApk/raw/refs/heads/main/.build-outputs/app-debug.apk"
                                } else {
                                    url
                                }
                                val request = DownloadManager.Request(Uri.parse(resolvedUrl)).apply {
                                    setMimeType("application/vnd.android.package-archive")
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading the updated RockBoys Native Experience...")
                                    setTitle("rockboys-update.apk")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "rockboys-update.apk")
                                }
                                val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                downloadManager.enqueue(request)
                                Toast.makeText(ctx, "Downloading native update from GitHub repository...", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }

                        // Customized clients
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isPageLoading = true
                                currentUrlState = url ?: targetUrl
                                canGoBackState = view?.canGoBack() ?: false
                                canGoForwardState = view?.canGoForward() ?: false
                                
                                // Automatically manage bottom bar visibility based on URL keyword heuristics as a robust fallback
                                val mainActivity = ctx as? MainActivity
                                if (mainActivity != null) {
                                    val isClanDashboard = url?.contains("/clan", ignoreCase = true) == true || 
                                                          url?.contains("dashboard", ignoreCase = true) == true || 
                                                          url?.contains("alliance", ignoreCase = true) == true ||
                                                          url?.contains("/alliance", ignoreCase = true) == true
                                    mainActivity.updateBottomBarVisibility(isClanDashboard)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) { if (hasError) { onPageLoaded(); return } // {
                                isPageLoading = false
                                canGoBackState = view?.canGoBack() ?: false
                                canGoForwardState = view?.canGoForward() ?: false
                                
                                // Re-verify on page loaded
                                val mainActivity = ctx as? MainActivity
                                if (mainActivity != null) {
                                    val isClanDashboard = url?.contains("/clan", ignoreCase = true) == true || 
                                                          url?.contains("dashboard", ignoreCase = true) == true || 
                                                          url?.contains("alliance", ignoreCase = true) == true ||
                                                          url?.contains("/alliance", ignoreCase = true) == true
                                    if (isClanDashboard) {
                                        mainActivity.updateBottomBarVisibility(true)
                                    }
                                }
                                
                                // Inject mobile viewport configurations to completely block pinch and double-tap zoom
                                view?.evaluateJavascript(
                                    "var valSet = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no'; " +
                                    "var metas = document.getElementsByTagName('meta'); " +
                                    "var found = false; " +
                                    "for (var i = 0; i < metas.length; i++) { " +
                                    "  if (metas[i].getAttribute('name') === 'viewport') { " +
                                    "    metas[i].setAttribute('content', valSet); " +
                                    "    found = true; " +
                                    "    break; " +
                                    "  } " +
                                    "} " +
                                    "if (!found) { " +
                                        "  var meta = document.createElement('meta'); " +
                                        "  meta.name = 'viewport'; " +
                                        "  meta.content = valSet; " +
                                        "  document.getElementsByTagName('head')[0].appendChild(meta); " +
                                        "}",
                                    null
                                )

                                // Bulletproof dynamic injection to completely hide App / APK download components 
                                // and force deep native APK URLs on remaining references.
                                view?.evaluateJavascript(
                                    "(function() { " +
                                    "  var style = document.createElement('style'); " +
                                    "  style.innerHTML = ' " +
                                    "    [class*=\\\"download\\\" i], [id*=\\\"download\\\" i], " +
                                    "    a[href*=\\\".apk\\\"], a[href*=\\\"download\\\"], " +
                                    "    button[class*=\\\"download\\\" i], " +
                                    "    .download-btn, .apk-download, .download-badge, " +
                                    "    a[href*=\\\"RahulExe69\\\"], a[href*=\\\"WebApk\\\"] { " +
                                    "      display: none !important; " +
                                    "    } " +
                                    "  '; " +
                                    "  document.head.appendChild(style); " +
                                    " " +
                                    "  function hideAppDownloadControls() { " +
                                    "    var anchors = document.querySelectorAll('a'); " +
                                    "    for (var i = 0; i < anchors.length; i++) { " +
                                      "      var href = anchors[i].href || ''; " +
                                      "      if (href.toLowerCase().indexOf('.apk') !== -1 || href.toLowerCase().indexOf('app-debug') !== -1) { " +
                                      "        anchors[i].href = 'https://github.com/RahulExe69/Rock-Boys-WebApk/raw/refs/heads/main/.build-outputs/app-debug.apk'; " +
                                      "      } " +
                                      "    } " +
                                      " " +
                                      "    var candidates = document.querySelectorAll('a, button, div, span, p, h1, h2, h3'); " +
                                      "    for (var j = 0; j < candidates.length; j++) { " +
                                      "      var node = candidates[j]; " +
                                      "      var text = (node.textContent || '').toLowerCase(); " +
                                      "      if ( " +
                                      "        text.indexOf('download apk') !== -1 || " +
                                      "        text.indexOf('download app') !== -1 || " +
                                      "        text.indexOf('download android') !== -1 || " +
                                      "        text.indexOf('get android app') !== -1 || " +
                                      "        text.indexOf('native apk') !== -1 || " +
                                      "        text.indexOf('install native') !== -1 " +
                                      "      ) { " +
                                      "        node.style.setProperty('display', 'none', 'important'); " +
                                      "        if (node.tagName === 'A' || node.tagName === 'BUTTON') { " +
                                      "           node.style.setProperty('display', 'none', 'important'); " +
                                      "        } else { " +
                                      "           var closestAOrBtn = node.closest('a') || node.closest('button'); " +
                                      "           if (closestAOrBtn) { " +
                                      "             closestAOrBtn.style.setProperty('display', 'none', 'important'); " +
                                      "           } " +
                                      "        } " +
                                      "      } " +
                                      "    } " +
                                      "  } " +
                                      "  hideAppDownloadControls(); " +
                                      "  var scanInterval = setInterval(hideAppDownloadControls, 300); " +
                                      "  setTimeout(function() { clearInterval(scanInterval); }, 20000); " +
                                      "})();",
                                    null
                                )
                                onPageLoaded()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                
                                // Direct APK download check - intercept and reroute to newest custom GitHub build URL
                                if (url.endsWith(".apk") || url.contains("app-debug.apk") || url.contains(".apk")) {
                                    val newApkUrl = "https://github.com/RahulExe69/Rock-Boys-WebApk/raw/refs/heads/main/.build-outputs/app-debug.apk"
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(newApkUrl))
                                        ctx.startActivity(intent)
                                    } catch (e: Exception) {
                                        view?.loadUrl(newApkUrl)
                                    }
                                    return true
                                }

                                // Support internal web links
                                if (url.contains("rockboys.netlify.app") || url.contains("localhost") || url.startsWith("file://")) {
                                    return false
                                }

                                // Socials & Communications app redirects
                                if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:")) {
                                    try {
                                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url))
                                        ctx.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Ignore fail
                                    }
                                    return true
                                }

                                if (url.contains("discord.com") || url.contains("youtube.com") || url.contains("twitter.com") || url.contains("facebook.com")) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        ctx.startActivity(intent)
                                    } catch (e: Exception) {
                                        return false // Load in WebView if system doesn't resolve
                                    }
                                    return true
                                }

                                return false
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                hasError = true
                                isPageLoading = false
                                onPageLoaded()
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    isPageLoading = false
                                    onPageLoaded()
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadingProgress = newProgress / 100f
                            }

                            // Dynamic pick files for uploading elements
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = filePathCallback
                                
                                val mimeType = fileChooserParams?.acceptTypes?.firstOrNull() ?: "*/*"
                                try {
                                    fileChooserLauncher.launch(mimeType)
                                } catch (e: Exception) {
                                    fileChooserCallback?.onReceiveValue(null)
                                    fileChooserCallback = null
                                    return false
                                }
                                return true
                            }
                        }

                        // Listen to scroll to coordinate pull refresh action
                        setOnScrollChangeListener { _, _, scrollY, _, _ ->
                            isScrollAtTop = scrollY == 0
                        }

                        loadUrl(targetUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                }
            )



            // Beautiful Gaming Offline Retry Screen Overlay (Clash of Clans style)
            AnimatedVisibility(
                visible = hasError,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF2E1A16), Color(0xFF0F0806)),
                                radius = 2000f
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Subtle grid background drawn safely on Canvas - 100% crash-proof
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 1.dp.toPx()
                        val gridColor = Color(0xFF3E2620).copy(alpha = 0.22f)
                        val step = 44.dp.toPx()
                        
                        // Diagonal crossing lines
                        val maxDim = size.width + size.height
                        var offset = -maxDim
                        while (offset < maxDim) {
                            drawLine(
                                color = gridColor,
                                start = Offset(offset, 0f),
                                end = Offset(offset + size.height, size.height),
                                strokeWidth = strokeWidth
                            )
                            drawLine(
                                color = gridColor,
                                start = Offset(offset, size.height),
                                end = Offset(offset + size.height, 0f),
                                strokeWidth = strokeWidth
                            )
                            offset += step
                        }
                    }

                    // Authentically styled CoC Connection Error Card
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxWidth(0.88f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF231512)) // Dark warm chocolate brown box
                            .border(BorderStroke(1.5.dp, Color(0xFF110705)), RoundedCornerShape(8.dp))
                            .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 24.dp)
                    ) {
                        Text(
                            text = "Connection error",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        )
                        
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        Text(
                            text = "Unable to connect with the server. Check your internet connection and try again.",
                            color = Color(0xFFDFD1C4), // Sand beige parchment color
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        )
                        
                        Spacer(modifier = Modifier.height(28.dp))
                        
                        // Try Again click area exactly as in-game design
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    hasError = false
                                    isPageLoading = true
                                    val webView = webViewInstance
                                    if (webView != null) {
                                        if (webView.url == null || webView.url == "about:blank" || webView.url == "") {
                                            webView.loadUrl(targetUrl)
                                        } else {
                                            webView.reload()
                                        }
                                    }
                                    if (!networkMonitor.isCurrentlyConnected()) {
                                        Toast.makeText(context, "Attempting connection... please check your internet.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp)
                                .offset(x = (-12).dp)
                        ) {
                            Text(
                                text = "Try again",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                            )
                        }
                    }
                }
            }

            // CoC Interactive Under Maintenance Overlay screen for Raid Reload
            AnimatedVisibility(
                visible = isRaidReloadActive,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
            ) {
                RaidReloadMaintenanceScreen(
                    webViewInstance = webViewInstance,
                    onCloseRequest = { isRaidReloadActive = false }
                )
            }

            // Force Dynamic APK Update Overlay Screen
            AnimatedVisibility(
                visible = showForceUpdateScreen && detectedUpdateInfo != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val updateInfo = detectedUpdateInfo
                if (updateInfo != null) {
                    val runningVersionName = com.example.network.UpdateChecker.getRunningVersionName(context)
                    ForceUpdateScreen(
                        currentVersion = runningVersionName,
                        updateInfo = updateInfo,
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress,
                        downloadError = downloadError,
                        onUpgradeClick = {
                            if (!isDownloading) {
                                isDownloading = true
                                downloadProgress = 0f
                                downloadError = null
                                coroutineScope.launch {
                                    try {
                                        val apkFile = java.io.File(context.cacheDir, "rockboys-update.apk")
                                        val downloadSuccess = com.example.network.UpdateChecker.downloadApk(
                                            updateInfo.apkUrl,
                                            apkFile
                                        ) { progress ->
                                            downloadProgress = progress
                                        }
                                        if (downloadSuccess) {
                                            isDownloading = false
                                            com.example.network.UpdateChecker.installApk(context, apkFile)
                                        } else {
                                            isDownloading = false
                                            downloadError = "Download failed. Please check internet connection and try again."
                                        }
                                    } catch (e: Exception) {
                                        isDownloading = false
                                        downloadError = "Download Error: ${e.localizedMessage}"
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // CoC Immerse Wood-board style Exit Confirmation Dialogue
            if (showExitDialog) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showExitDialog = false }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF2C1B12)) // Deep carved wood brown
                            .border(BorderStroke(3.dp, Color(0xFFC79E61)), RoundedCornerShape(16.dp)) // pine frame
                            .border(BorderStroke(1.dp, Color(0xFF150B05)), RoundedCornerShape(16.dp)) // outline
                            .padding(20.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Exit Icon",
                                    tint = Color(0xFFE4B359),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "QUIT BATTLE?",
                                    color = Color(0xFFEAD8C3), // Honey cream/gold title
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Are you sure you want to disconnect and exit back to the home screen?",
                                color = Color(0xFFDFD1C4), // Parchment beige helper
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // NO / CANCEL Button (styled like a Clash of Clans green button)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                        .height(44.dp)
                                        .background(Color(0xFF4A8F24), shape = RoundedCornerShape(8.dp))
                                        .border(BorderStroke(2.dp, Color(0xFF1A440D)), shape = RoundedCornerShape(8.dp))
                                        .clickable { showExitDialog = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "PLAY ON",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // YES / EXIT Button (styled like a Clash of Clans red button)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                        .height(44.dp)
                                        .background(Color(0xFFC13E2E), shape = RoundedCornerShape(8.dp))
                                        .border(BorderStroke(2.dp, Color(0xFF55100A)), shape = RoundedCornerShape(8.dp))
                                        .clickable {
                                            showExitDialog = false
                                            onExitRequested()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "DISCONNECT",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Inline fallback since LaserRed can be referenced directly
val TextColorLaserRed = Color(0xFFFF1744)

@Composable
fun CoCBannerBackground(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        // Define exact color palette matching the requested design
        val bgParchment = Color(0xFFDFCDA6)      // Flipped parchment/tan base
        val borderDark = Color(0xFF3C2414)       // Dark brown hand-carved outline
        val shadowDepth = Color(0xFF5D432C)      // 3D wood shadow/depth
        val highlightColor = Color(0xFFFFF6E5)   // Warm cream edge highlight
        
        // Coordinates for the hand-carved rugged top edge
        // The top edge is rugged (representing the hand-cut wood block), while the bottom edge is completely flat.
        val nodes = listOf(
            0.00f to 8f,
            0.04f to 8f,
            0.05f to 14f,  // notch down
            0.07f to 14f,
            0.08f to 10f,  // step up
            0.15f to 10f,
            0.17f to 16f,  // step down
            0.24f to 16f,
            0.25f to 10f,  // step up
            0.38f to 10f,
            0.40f to 18f,  // deep protrusion
            0.54f to 18f,
            0.55f to 12f,  // step up
            0.58f to 12f,
            0.60f to 15f,  // notch down
            0.62f to 15f,
            0.63f to 10f,  // step up
            0.76f to 10f,
            0.78f to 12f,  // notch down
            0.82f to 12f,
            0.84f to 8f,   // step up
            0.92f to 8f,
            0.94f to 12f,  // small notch
            0.96f to 12f,
            1.00f to 8f
        )
        
        // 0. Render soft blur shadow layers above the wood top edge to create a natural, deep drop shadow cast on the game content
        for (i in 1..4) {
            val strokeOffset = (i * 1.5).dp.toPx()
            val shadowOutlinePath = Path().apply {
                moveTo(0f, nodes[0].second.dp.toPx() - strokeOffset)
                nodes.forEach { (pct, offsetDp) ->
                    lineTo(pct * width, offsetDp.dp.toPx() - strokeOffset)
                }
            }
            drawPath(
                path = shadowOutlinePath,
                color = Color.Black.copy(alpha = 0.12f / i),
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 1. Draw 3D shadow depth underneath (offset downwards by 5dp)
        val depthOffset = 5.dp.toPx()
        val depthPath = Path().apply {
            moveTo(0f, height)
            lineTo(0f, nodes[0].second.dp.toPx() + depthOffset)
            nodes.forEach { (pct, offsetDp) ->
                lineTo(pct * width, offsetDp.dp.toPx() + depthOffset)
            }
            lineTo(width, height)
            close()
        }
        drawPath(path = depthPath, color = shadowDepth)
        
        // 2. Draw main parchment banner body
        val mainPath = Path().apply {
            moveTo(0f, height)
            lineTo(0f, nodes[0].second.dp.toPx())
            nodes.forEach { (pct, offsetDp) ->
                lineTo(pct * width, offsetDp.dp.toPx())
            }
            lineTo(width, height)
            close()
        }
        drawPath(path = mainPath, color = bgParchment)
        
        // 3. Draw dark hand-carved wood bounding line along the rugged top edge
        val borderPath = Path().apply {
            moveTo(0f, nodes[0].second.dp.toPx())
            nodes.forEach { (pct, offsetDp) ->
                lineTo(pct * width, offsetDp.dp.toPx())
            }
        }
        drawPath(
            path = borderPath,
            color = borderDark,
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        
        // 4. Draw detailed vertical crevices/splits at key notch steps to give realistic timber splits
        val creviceIndices = listOf(2, 6, 10, 14, 18, 22)
        creviceIndices.forEach { idx ->
            if (idx < nodes.size) {
                val nodeX = nodes[idx].first * width
                val nodeY = nodes[idx].second.dp.toPx()
                // Dark crevice crack
                drawLine(
                    color = borderDark.copy(alpha = 0.9f),
                    start = Offset(nodeX, nodeY),
                    end = Offset(nodeX, nodeY + 14.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Highlights on the side of the cracks to give 3D beveled thickness
                drawLine(
                    color = highlightColor.copy(alpha = 0.65f),
                    start = Offset(nodeX + 1.5.dp.toPx(), nodeY + 1.dp.toPx()),
                    end = Offset(nodeX + 1.5.dp.toPx(), nodeY + 15.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        
        // 5. Draw sunset golden highlight line along the inside of the rugged path
        val highlightPath = Path().apply {
            moveTo(0f, nodes[0].second.dp.toPx() + 2.dp.toPx())
            nodes.forEach { (pct, offsetDp) ->
                lineTo(pct * width, offsetDp.dp.toPx() + 2.dp.toPx())
            }
        }
        drawPath(
            path = highlightPath,
            color = highlightColor.copy(alpha = 0.7f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ClashShieldIconView(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.12f)
            lineTo(w * 0.85f, h * 0.12f)
            lineTo(w * 0.85f, h * 0.5f)
            quadraticTo(w * 0.85f, h * 0.82f, w * 0.5f, h * 0.93f)
            quadraticTo(w * 0.15f, h * 0.82f, w * 0.15f, h * 0.5f)
            close()
        }
        drawPath(
            path = path,
            color = Color(0xFFFDBC11) // Clash Gold
        )
        drawPath(
            path = path,
            color = Color(0xFF4C473E), // Wood Charcoal Outline
            style = Stroke(width = 3.dp.toPx())
        )
        drawLine(
            color = Color(0xFF4C473E).copy(alpha = 0.5f),
            start = Offset(w * 0.5f, h * 0.18f),
            end = Offset(w * 0.5f, h * 0.85f),
            strokeWidth = 2.5.dp.toPx()
        )
        drawLine(
            color = Color(0xFF4C473E).copy(alpha = 0.5f),
            start = Offset(w * 0.25f, h * 0.45f),
            end = Offset(w * 0.75f, h * 0.45f),
            strokeWidth = 2.5.dp.toPx()
        )
    }
}

object RaidReloadSettings {
    private const val PREFS_NAME = "raid_reload_prefs"
    private const val KEY_SHIELD_SIZE = "shield_size"
    private const val KEY_SHIELD_TRANSPARENCY = "shield_transparency"
    private const val KEY_SHIELD_ENABLED = "shield_enabled"

    fun getShieldSize(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SHIELD_SIZE, 56)
    }

    fun saveShieldSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SHIELD_SIZE, size).apply()
    }

    fun getShieldTransparency(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SHIELD_TRANSPARENCY, 100)
    }

    fun saveShieldTransparency(context: Context, transparency: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SHIELD_TRANSPARENCY, transparency).apply()
    }

    fun isShieldEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHIELD_ENABLED, false)
    }

    fun saveShieldEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHIELD_ENABLED, enabled).apply()
    }
}

object RaidReloadManager {
    var isCutoffActive by mutableStateOf(false)
    var countdownRemaining by mutableStateOf(0f)

    fun triggerCutoff(context: Context, onFinished: () -> Unit = {}) {
        if (isCutoffActive) return
        isCutoffActive = true
        countdownRemaining = 1.0f

        // 1. Fire ConnectionCutoffVpnService VPN selectively blocking Clash of Clans
        val startIntent = Intent(context, com.example.network.ConnectionCutoffVpnService::class.java).apply {
            action = "START_VPN"
        }
        try {
            context.startService(startIntent)
        } catch (e: Exception) {
            // Service startup handle fallback
        }

        // 2. Exact 1.0 second countdown timer loop for visual precision in test suite and service interface
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startTime = System.currentTimeMillis()
        val checkRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= 1000L) {
                    val stopIntent = Intent(context, com.example.network.ConnectionCutoffVpnService::class.java).apply {
                        action = "STOP_VPN"
                    }
                    try {
                        context.startService(stopIntent)
                    } catch (e: Exception) {
                        // ignore
                    }
                    isCutoffActive = false
                    countdownRemaining = 0.0f
                    onFinished()
                } else {
                    countdownRemaining = (1000f - elapsed) / 1000f
                    handler.postDelayed(this, 16)
                }
            }
        }
        handler.post(checkRunnable)
    }
}

@Composable
fun Clash3DButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    baseColor: Color = Color(0xFF4BAC1A),
    shadowColor: Color = Color(0xFF28650A),
    borderColor: Color = Color(0xFF1B4006),
    shape: androidx.compose.foundation.shape.RoundedCornerShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier = modifier
            .alpha(alpha)
            .clickable(enabled = enabled, onClick = onClick)
            .background(shadowColor, shape = shape)
            .padding(bottom = 4.dp) // shadow depth
            .background(baseColor, shape = shape)
            .border(width = 1.5.dp, color = borderColor, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
fun RaidReloadMaintenanceScreen(
    webViewInstance: WebView?,
    onCloseRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 1. Pull settings persist states on startup
    var isShieldEnabled by remember { mutableStateOf(RaidReloadSettings.isShieldEnabled(context)) }
    var shieldSize by remember { mutableStateOf(RaidReloadSettings.getShieldSize(context).toFloat()) }
    var shieldTransparency by remember { mutableStateOf(RaidReloadSettings.getShieldTransparency(context).toFloat()) }

    // Dynamic reactive overlay checking
    var hasOverlayPermissionState by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermissionState = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "VPN cutoff authorization granted!", Toast.LENGTH_SHORT).show()
        }
    }

    // Modal dialogue choice option triggers
    var showSetupOptionsDialog by remember { mutableStateOf(false) }

    // Handle initial configurations on first mounting
    LaunchedEffect(Unit) {
        FloatingShieldService.buttonSizeDp = shieldSize.toInt()
        FloatingShieldService.idleTransparencyPercent = shieldTransparency.toInt()
    }

    val bgWebsite = Color(0xFFDDD9CD) // Sandy desaturated concrete clay background matching the website screenshot
    val titleBarColor = Color(0xFF423C35) // Deep Slate/Charcoal for headers
    val parchmentBg = Color(0xFFFAF7EE) // Premium warm parchment card background matching screenshot elements
    val borderCharcoal = Color(0xFF4C473E) // Muted border color
    val textPrimary = Color(0xFF3C342C) // Warm dark brown/charcoal
    val textSecondary = Color(0xFF6B5E52) // Soft medium brown text

    // Clash 3D Green Button Colors
    val cocGreen = Color(0xFF4BAC1A)
    val cocGreenShadow = Color(0xFF28650A)

    // Clash 3D Blue Button Colors
    val cocBlue = Color(0xFF2D76EC)
    val cocBlueShadow = Color(0xFF1542A1)

    // Setup options dialog overlay if clicked Setup Settings
    if (showSetupOptionsDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSetupOptionsDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(parchmentBg, shape = RoundedCornerShape(16.dp))
                    .border(width = 3.dp, color = borderCharcoal, shape = RoundedCornerShape(16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Wooden charcoal top header strip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(titleBarColor, shape = RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CHOOSE SETUP OPTION",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Configure system tools to activate simulation shield features:",
                        color = textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Option 1: Setup VPN Protection
                    Clash3DButton(
                        onClick = {
                            showSetupOptionsDialog = false
                            val vpnIntent = android.net.VpnService.prepare(context)
                            if (vpnIntent != null) {
                                vpnLauncher.launch(vpnIntent)
                            } else {
                                Toast.makeText(context, "VPN protection already configured & authenticated.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        baseColor = cocBlue,
                        shadowColor = cocBlueShadow,
                        borderColor = borderCharcoal
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "VPN Lock Icon",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Authorize VPN Shield",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Option 2: Setup Overlay Permission
                    Clash3DButton(
                        onClick = {
                            showSetupOptionsDialog = false
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                launcher.launch(intent)
                            } else {
                                Toast.makeText(context, "Overlay permission is granted by default on your system.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        baseColor = cocGreen,
                        shadowColor = cocGreenShadow,
                        borderColor = borderCharcoal
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Overlay Drawing Action",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Authorize Floating Menu",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cancellation
                    OutlinedButton(
                        onClick = { showSetupOptionsDialog = false },
                        border = BorderStroke(1.5.dp, borderCharcoal),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary)
                    ) {
                        Text(text = "Cancel", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Elegant Website Embedded Header Bar or Offline Fallback Header Bar
    val networkMonitor = remember { NetworkMonitor(context) }
    val isOnline = networkMonitor.isCurrentlyConnected()

    var isTopbarOverlayActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgWebsite)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (isOnline) {
                Spacer(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(84.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // Elegant Wood Theme Tool Banner/Header Bar (with NO arrow closes inside standard standalone page tab!)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(vertical = 12.dp)
                            .background(titleBarColor, shape = RoundedCornerShape(12.dp))
                            .border(width = 1.5.dp, color = borderCharcoal, shape = RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp, horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Gold Star Badge Icon
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFFDBC11), shape = RoundedCornerShape(8.dp))
                                    .border(width = 1.5.dp, color = borderCharcoal, shape = RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Raid Reload Star Icon",
                                    tint = Color(0xFF381504),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Title fields
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "RAID RELOAD TOOL",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Simulation Shield Helper",
                                    color = Color(0xFFD4D4D8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // Green Ready Badge status tag
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFECFDF5), shape = RoundedCornerShape(50))
                                    .border(width = 1.dp, color = Color(0xFF34D399), shape = RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF10B981), shape = RoundedCornerShape(percent = 50))
                                    )
                                    Text(
                                        text = "READY",
                                        color = Color(0xFF065F46),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Scrollable Content Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card 1: Raid Connection Info Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(parchmentBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 2.dp, color = borderCharcoal, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shield custom icon beautifully styled
                        ClashShieldIconView(
                            modifier = Modifier
                                .size(56.dp)
                                .padding(end = 4.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "What is Raid Reload?",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "During Clan Capital Raid attacks, players often force-close and restart Clash of Clans to skip the long attack animation and save time. Raid Reload makes this instant! Instead of manually closing and restarting the game, this tool safely cuts the internet for exactly 1 second, causing the game to immediately reload itself directly back in without any app-switching.",
                                color = textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Card 2: Floating Battle Shield Options Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(parchmentBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 2.dp, color = borderCharcoal, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Yellow gold play indicator badge
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFFDBC11), shape = RoundedCornerShape(percent = 50))
                                .border(width = 1.5.dp, color = borderCharcoal, shape = RoundedCornerShape(percent = 50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Enable Toggle",
                                tint = Color(0xFF381504),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Floating Reload Button",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Show a floating shortcut icon over the game screen",
                                color = textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Switch Button Custom style (Green/Red themed switch)
                        Switch(
                            checked = isShieldEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !hasOverlayPermissionState) {
                                    Toast.makeText(context, "System drawing overlay permission is required. Click 'Setup Settings' below.", Toast.LENGTH_LONG).show()
                                    return@Switch
                                }

                                isShieldEnabled = checked
                                RaidReloadSettings.saveShieldEnabled(context, checked)

                                val serviceIntent = Intent(context, FloatingShieldService::class.java)
                                if (checked) {
                                    FloatingShieldService.buttonSizeDp = shieldSize.toInt()
                                    FloatingShieldService.idleTransparencyPercent = shieldTransparency.toInt()
                                    context.startService(serviceIntent)
                                    Toast.makeText(context, "Floating reload tool activated!", Toast.LENGTH_SHORT).show()
                                } else {
                                    context.stopService(serviceIntent)
                                    Toast.makeText(context, "Floating tool stopped.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = cocGreen,
                                uncheckedThumbColor = Color(0xFF8F8E86),
                                uncheckedTrackColor = Color(0xFFC7C5BD)
                            )
                        )
                    }

                    // Divider separator line
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(borderCharcoal.copy(alpha = 0.2f))
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Title header sub-options
                    Text(
                        text = "COMPACT BUTTON OPTIONS",
                        color = Color(0xFF8F4E10),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option A: Button Size Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reload Button Size",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${shieldSize.toInt()} dp",
                            color = Color(0xFF8F4E10),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Slider(
                        value = shieldSize,
                        onValueChange = { size ->
                            shieldSize = size
                            RaidReloadSettings.saveShieldSize(context, size.toInt())
                            FloatingShieldService.buttonSizeDp = size.toInt()
                            if (isShieldEnabled && FloatingShieldService.isServiceRunning) {
                                val updateIntent = Intent(context, FloatingShieldService::class.java).apply {
                                    action = "UPDATE_STYLE"
                                }
                                context.startService(updateIntent)
                            }
                        },
                        valueRange = 40f..96f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFDBC11),
                            activeTrackColor = Color(0xFFFCDD8F),
                            inactiveTrackColor = Color(0xFFC7C5BD)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option B: Button Idle Transparency Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Button Idle Transparency",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${shieldTransparency.toInt()}%",
                            color = Color(0xFF8F4E10),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Slider(
                        value = shieldTransparency,
                        onValueChange = { alph ->
                            shieldTransparency = alph
                            RaidReloadSettings.saveShieldTransparency(context, alph.toInt())
                            FloatingShieldService.idleTransparencyPercent = alph.toInt()
                            if (isShieldEnabled && FloatingShieldService.isServiceRunning) {
                                val updateIntent = Intent(context, FloatingShieldService::class.java).apply {
                                    action = "UPDATE_STYLE"
                                }
                                context.startService(updateIntent)
                            }
                        },
                        valueRange = 20f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFDBC11),
                            activeTrackColor = Color(0xFFFCDD8F),
                            inactiveTrackColor = Color(0xFFC7C5BD)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Double action buttons at bottom
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Center Position Button (Gray 3D Button)
                        Clash3DButton(
                            onClick = {
                                if (isShieldEnabled) {
                                    // Restart service to reset to default position
                                    val stopIntent = Intent(context, FloatingShieldService::class.java)
                                    context.stopService(stopIntent)
                                    val startIntent = Intent(context, FloatingShieldService::class.java)
                                    context.startService(startIntent)
                                    Toast.makeText(context, "Floating icon recentered on screen border", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Activate Floating Shield to configure position", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            baseColor = Color(0xFFC7C5BD),
                            shadowColor = Color(0xFF8F8E86),
                            borderColor = borderCharcoal,
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Center Symbol",
                                tint = textPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Center Position",
                                color = textPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Setup Settings Button (Yellow 3D Button popping 2 actions dialog)
                        Clash3DButton(
                            onClick = {
                                showSetupOptionsDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            baseColor = Color(0xFFFDBC11),
                            shadowColor = Color(0xFFB37306),
                            borderColor = borderCharcoal,
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "Build Symbol",
                                tint = Color(0xFF381504),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Setup Settings",
                                color = Color(0xFF381504),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Card 3: Instant Duel Cutoff Tester Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(parchmentBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 2.dp, color = borderCharcoal, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Interactive Raid Reload Tester",
                        color = textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Quickly test the 1-second network reload trigger directly inside the app below.",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Simulated interactive circle indicator
                    val angle = if (RaidReloadManager.isCutoffActive) {
                        (RaidReloadManager.countdownRemaining * 360f)
                    } else {
                        360f
                    }

                    Box(
                        modifier = Modifier.size(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Circular track
                            drawCircle(
                                color = Color(0xFFE2E8F0),
                                style = Stroke(width = 8.dp.toPx())
                            )
                            // Animated angle track
                            drawArc(
                                color = if (RaidReloadManager.isCutoffActive) Color(0xFFDC2626) else Color(0xFF9C5FEB),
                                startAngle = -90f,
                                sweepAngle = angle,
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        // Countdown or reload icon
                        if (RaidReloadManager.isCutoffActive) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format("%.1fs", RaidReloadManager.countdownRemaining),
                                    color = Color(0xFFDC2626),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Active",
                                    color = Color(0xFFDC2626),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { RaidReloadManager.triggerCutoff(context) },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Test Trigger",
                                    tint = Color(0xFF9C5FEB),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Testing Action Re-connect Shield button (3D Styled)
                    val testBtnBase = if (RaidReloadManager.isCutoffActive) Color(0xFFDC2626) else Color(0xFF9C5FEB)
                    val testBtnShadow = if (RaidReloadManager.isCutoffActive) Color(0xFF991B1B) else Color(0xFF642FA8)
                    Clash3DButton(
                        onClick = { RaidReloadManager.triggerCutoff(context) },
                        enabled = !RaidReloadManager.isCutoffActive,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(52.dp),
                        baseColor = testBtnBase,
                        shadowColor = testBtnShadow,
                        borderColor = borderCharcoal,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(
                            text = if (RaidReloadManager.isCutoffActive) "RELOADING... (1S)" else "TRIGGER 1S RAID RELOAD",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        if (isOnline) {
            val density = LocalDensity.current
            val statusBarHeightPx = with(density) { WindowInsets.statusBars.getTop(this) }
            val topBarHeightPx = with(density) { 84.dp.roundToPx() }
            val totalActiveHeight = statusBarHeightPx + topBarHeightPx

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TouchPassThroughWebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            setBackgroundColor(0) // transparent background
                            
                            val topbarInterface = TopbarAppInterface(
                                onOverlayStateChanged = { active ->
                                    (ctx as? android.app.Activity)?.runOnUiThread {
                                        isTopbarOverlayActive = active
                                    }
                                },
                                onNavigateToUrl = { url ->
                                    (ctx as? android.app.Activity)?.runOnUiThread {
                                        isTopbarOverlayActive = false
                                        webViewInstance?.loadUrl(url)
                                        onCloseRequest()
                                    }
                                }
                            )
                            addJavascriptInterface(topbarInterface, "AndroidTopbar")
                            addJavascriptInterface(topbarInterface, "AndroidInterface")
                            
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.contains("rockboys.netlify.app") && !url.contains("/topbar")) {
                                        (ctx as? android.app.Activity)?.runOnUiThread {
                                            isTopbarOverlayActive = false
                                            webViewInstance?.loadUrl(url)
                                            onCloseRequest()
                                        }
                                        return true
                                    }
                                    return super.shouldOverrideUrlLoading(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript(
                                        "document.body.style.margin = '0';" +
                                        "document.body.style.padding = '0';" +
                                        "document.body.style.overflow = 'hidden';" +
                                        "document.body.style.backgroundColor = 'transparent';" +
                                        "document.body.style.width = '100vw';" +
                                        "(function() { " +
                                        "  function checkOverlays() { " +
                                        "    var hasActiveOverlay = false; " +
                                        "    var elements = document.querySelectorAll('*'); " +
                                        "    for (var i = 0; i < elements.length; i++) { " +
                                        "      var el = elements[i]; " +
                                        "      if (el === document.body || el === document.documentElement || el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue; " +
                                        "      var style = window.getComputedStyle(el); " +
                                        "      if ((style.position === 'fixed' || style.position === 'absolute') && style.display !== 'none' && style.visibility !== 'hidden') { " +
                                        "        var rect = el.getBoundingClientRect(); " +
                                        "        if (rect.bottom > 90 && rect.height > 100 && rect.width > 100) { " +
                                        "          hasActiveOverlay = true; " +
                                        "          break; " +
                                        "        } " +
                                        "      } " +
                                        "    } " +
                                        "    var bridge = window.AndroidInterface || window.AndroidTopbar; " +
                                        "    if (bridge && typeof bridge.setOverlayActive === 'function') { " +
                                        "      bridge.setOverlayActive(hasActiveOverlay); " +
                                        "    } " +
                                        "  } " +
                                        "  document.addEventListener('click', function() { " +
                                        "    setTimeout(checkOverlays, 100); " +
                                        "    setTimeout(checkOverlays, 350); " +
                                        "  }); " +
                                        "  var observer = new MutationObserver(function() { " +
                                        "    checkOverlays(); " +
                                        "  }); " +
                                        "  observer.observe(document.body, { childList: true, subtree: true, attributes: true }); " +
                                        "  setTimeout(checkOverlays, 500); " +
                                        "  var lastUrl = window.location.href; " +
                                        "  function checkUrlChange() { " +
                                        "    var currentUrl = window.location.href; " +
                                        "    if (currentUrl !== lastUrl) { " +
                                        "      lastUrl = currentUrl; " +
                                        "      if (currentUrl.indexOf('/topbar') === -1) { " +
                                        "        var bridge = window.AndroidInterface || window.AndroidTopbar; " +
                                        "        if (bridge && typeof bridge.navigateToUrl === 'function') { " +
                                        "          bridge.navigateToUrl(currentUrl); " +
                                        "        } " +
                                        "        window.history.replaceState(null, '', '/topbar'); " +
                                        "      } " +
                                        "    } " +
                                        "  } " +
                                        "  setInterval(checkUrlChange, 100); " +
                                        "  document.addEventListener('click', function(e) { " +
                                        "    var target = e.target; " +
                                        "    while (target && target !== document.body) { " +
                                        "      if (target.tagName === 'A') { " +
                                        "        var href = target.getAttribute('href'); " +
                                        "        if (href) { " +
                                        "          var absoluteUrl = new URL(href, window.location.href).href; " +
                                        "          if (absoluteUrl.indexOf('rockboys.netlify.app') !== -1 && absoluteUrl.indexOf('/topbar') === -1) { " +
                                        "            e.preventDefault(); " +
                                        "            e.stopPropagation(); " +
                                        "            var bridge = window.AndroidInterface || window.AndroidTopbar; " +
                                        "            if (bridge && typeof bridge.navigateToUrl === 'function') { " +
                                        "              bridge.navigateToUrl(absoluteUrl); " +
                                        "            } " +
                                        "            return; " +
                                        "          } " +
                                        "        } " +
                                        "      } " +
                                        "      target = target.parentNode; " +
                                        "    } " +
                                        "  }, true); " +
                                        "})();",
                                        null
                                    )
                                }
                            }
                            loadUrl("https://rockboys.netlify.app/topbar")
                        }
                    },
                    update = { view ->
                        if (view is TouchPassThroughWebView) {
                            view.isOverlayActive = isTopbarOverlayActive
                            view.topBarHeightPx = totalActiveHeight
                        }
                    }
                )
            }
        }
    }
}


@Composable
fun ForceUpdateScreen(
    currentVersion: String,
    updateInfo: com.example.network.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float?,
    downloadError: String?,
    onUpgradeClick: () -> Unit
) {
    val bgWebsite = Color(0xFFDDD9CD) // Sandy desaturated concrete clay background matching the website screenshot
    val titleBarColor = Color(0xFF423C35) // Deep Slate/Charcoal for headers
    val parchmentBg = Color(0xFFFAF7EE) // Premium warm parchment card background matching screenshot elements
    val borderCharcoal = Color(0xFF4C473E) // Muted border color
    
    // 3D Tab-style Colors
    val tabGrayBase = Color(0xFFC7C5BD)
    val tabGrayShadow = Color(0xFF8F8E86)
    val tabYellowBase = Color(0xFFFDBC11)
    val tabYellowShadow = Color(0xFFB37306)
    
    // Clash 3D Green Button Colors
    val cocGreen = Color(0xFF4BAC1A)
    val cocGreenLight = Color(0xFF78C01C)
    val cocGreenShadow = Color(0xFF28650A)
    val cocGreenBorder = Color(0xFF1B4006)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgWebsite)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Main Dialog Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .background(parchmentBg, shape = RoundedCornerShape(16.dp))
                .border(width = 3.dp, color = borderCharcoal, shape = RoundedCornerShape(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Title Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        titleBarColor,
                        shape = RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp)
                    )
                    .padding(vertical = 14.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "UPGRADE REQUIRED",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            // Dialog Contents Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Description Text
                Text(
                    text = "A critical system upgrade is available! To continue accessing rock boys portal resources, please download the latest version update below.",
                    color = Color(0xFF4E463E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Interactive 3D Tab-like Version Badges (Matching Website Tabs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Current Version Tab (Classic Grey Style)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .background(tabGrayShadow, shape = RoundedCornerShape(8.dp))
                            .padding(bottom = 4.dp) // Bottom shadow offset
                            .background(tabGrayBase, shape = RoundedCornerShape(8.dp))
                            .border(width = 1.5.dp, color = borderCharcoal, shape = RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "CURRENT VERSION",
                                color = Color(0xFF5E5C55),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = currentVersion,
                                color = Color(0xFF2E2D2B),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // New Version Tab (War League Yellow Style)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .background(tabYellowShadow, shape = RoundedCornerShape(8.dp))
                            .padding(bottom = 4.dp) // Bottom shadow offset
                            .background(tabYellowBase, shape = RoundedCornerShape(8.dp))
                            .border(width = 1.5.dp, color = borderCharcoal, shape = RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "NEW VERSION",
                                color = Color(0xFF6B4B04),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = updateInfo.versionName,
                                color = Color(0xFF4A3403),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Changelog Container
                if (updateInfo.changeLog.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEFECE2), shape = RoundedCornerShape(8.dp))
                            .border(width = 1.5.dp, color = Color(0xFFCDCBAF), shape = RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "WHAT'S NEW",
                            color = Color(0xFF5E544A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = updateInfo.changeLog,
                            color = Color(0xFF4A453F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action download / install controls
                if (isDownloading) {
                    val progressVal = downloadProgress ?: 0f
                    val percentageStr = (progressVal * 100).toInt().toString()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "DOWNLOADING UPDATE... $percentageStr%",
                            color = Color(0xFF4C473E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Custom Progress Bar Frame
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .background(Color(0xFFD4D0C5), shape = RoundedCornerShape(8.dp))
                                .border(width = 1.5.dp, color = Color(0xFF908C80), shape = RoundedCornerShape(8.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progressVal)
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(cocGreenLight, cocGreen)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Installing automatically upon success",
                            color = Color(0xFF807A70),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    if (downloadError != null) {
                        Text(
                            text = downloadError,
                            color = Color(0xFFFF1744),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }

                    // 3D Green Clash Style Upgrade Action Button (Matching WAR LOG)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(52.dp)
                            .background(cocGreenShadow, shape = RoundedCornerShape(10.dp))
                            .padding(bottom = 4.dp) // Bottom shadow offset
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(cocGreenLight, cocGreen)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = 1.5.dp,
                                color = cocGreenBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onUpgradeClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "UPGRADE NATIVE EXPERIENCE",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.offset(y = (-1).dp)
                        )
                    }
                }
            }
        }
    }
}

class TopbarAppInterface(
    private val onOverlayStateChanged: (Boolean) -> Unit,
    private val onNavigateToUrl: (String) -> Unit
) {
    @android.webkit.JavascriptInterface
    fun setOverlayActive(active: Boolean) {
        onOverlayStateChanged(active)
    }

    @android.webkit.JavascriptInterface
    fun navigateToUrl(url: String) {
        onNavigateToUrl(url)
    }
}

class TouchPassThroughWebView(context: android.content.Context) : android.webkit.WebView(context) {
    var isOverlayActive: Boolean = false
    var topBarHeightPx: Int = 0

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event == null) return super.dispatchTouchEvent(event)
        if (isOverlayActive) {
            return super.dispatchTouchEvent(event)
        }
        // If the overlay is not active, only intercept touches within the top bar height
        if (event.y < topBarHeightPx) {
            return super.dispatchTouchEvent(event)
        }
        // Otherwise, allow the touch events to pass through
        return false
    }
}




