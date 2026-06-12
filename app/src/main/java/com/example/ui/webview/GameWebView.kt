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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.example.MainActivity
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

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GameWebView(
    targetUrl: String = "https://rockboys.netlify.app",
    isBottomBarVisible: Boolean = false,
    onExitRequested: () -> Unit,
    onPageLoaded: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // WebView reference and states
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
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
            val updateInfo = com.example.network.UpdateChecker.checkForUpdates()
            if (updateInfo != null) {
                val runningCode = com.example.network.UpdateChecker.getRunningVersionCode(context)
                if (updateInfo.versionCode > runningCode && updateInfo.forceUpdate) {
                    detectedUpdateInfo = updateInfo
                    showForceUpdateScreen = true
                }
            }
        } catch (e: Exception) {
            // Silence to avoid blocking the user if they're completely offline
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
                isRaidReloadActive -> Color(0xFF2E1A0C)
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
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
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

                            override fun onPageFinished(view: WebView?, url: String?) {
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

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    isPageLoading = false
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



            // Beautiful Gaming Offline Retry Screen Overlay
            AnimatedVisibility(
                visible = hasError,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CyberBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(32.dp)
                            .background(CyberDark, shape = RoundedCornerShape(16.dp))
                            .border(width = 1.dp, color = LaserRed.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp))
                            .padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Disconnection Warning",
                            tint = LaserRed,
                            modifier = Modifier.size(64.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "ESTABLISHING CONNECTION FAILED",
                            color = TextColorLaserRed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = 1.sp
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "We are unable to communicate with RockBoys servers. Please verify that your system is online.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Button(
                            onClick = {
                                hasError = false
                                isPageLoading = true
                                webViewInstance?.reload()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LaserRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "RETRY SENSORS",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // CoC Interactive Under Maintenance Overlay screen for Raid Reload
            AnimatedVisibility(
                visible = isRaidReloadActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                RaidReloadMaintenanceScreen(
                    onReturnClick = {
                        isRaidReloadActive = false
                    }
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

            // High-fidelity Gaming Exit Confirmation Overlay
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = {
                        Text(
                            text = "EXIT PORTAL?",
                            color = ToxicGreen,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to disconnect and exit the RockBoys application?",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitDialog = false
                                onExitRequested()
                            }
                        ) {
                            Text(
                                text = "DISCONNECT",
                                color = LaserRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text(
                                text = "CANCEL",
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    containerColor = CyberDark,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.border(width = 1.dp, color = CyberLine, shape = RoundedCornerShape(12.dp))
                )
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
            color = Color(0xFF6366F1) // Slate Blue / Indigo
        )
        drawPath(
            path = path,
            color = Color(0xFF4F46E5),
            style = Stroke(width = 3.dp.toPx())
        )
        drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = Offset(w * 0.5f, h * 0.18f),
            end = Offset(w * 0.5f, h * 0.85f),
            strokeWidth = 2.5.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.45f),
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
fun RaidReloadMaintenanceScreen(
    onReturnClick: () -> Unit
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

    // Handle initial configurations on first mounting
    LaunchedEffect(Unit) {
        FloatingShieldService.buttonSizeDp = shieldSize.toInt()
        FloatingShieldService.idleTransparencyPercent = shieldTransparency.toInt()
    }

    // Clean, modern slate-light design tokens
    val lightBg = Color(0xFFF8FAFC) // slate-50
    val textPrimary = Color(0xFF0F172A) // slate-900
    val textSecondary = Color(0xFF475569) // slate-600
    val cardBg = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFE2E8F0) // slate-200
    val indigoPrimary = Color(0xFF4F46E5) // Indigo-600
    val indigoLight = Color(0xFFEEF2FF) // Indigo-50
    val greenSuccess = Color(0xFF10B981) // Green-500
    val greenSuccessLight = Color(0xFFECFDF5) // Green-50

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lightBg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Elegant Light Theme Tool Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Arrow Navigation to Web Interface
                IconButton(
                    onClick = onReturnClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Return to Game Page",
                        tint = textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Gold Star badge icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFFEF08A), shape = RoundedCornerShape(10.dp)), // Soft gold yellow-200
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Raid Reload Star Icon",
                        tint = Color(0xFFCA8A04), // Darker yellow-600 gold accent
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Raid Reload",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Simulation Shield Helper",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Ready Badge Status
                Box(
                    modifier = Modifier
                        .background(greenSuccessLight, shape = RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(greenSuccess, shape = RoundedCornerShape(percent = 50))
                        )
                        Text(
                            text = "Ready",
                            color = Color(0xFF065F46), // Green-800
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Minimal Setup Settings
                IconButton(
                    onClick = {
                        val vpnIntent = android.net.VpnService.prepare(context)
                        if (vpnIntent != null) {
                            vpnLauncher.launch(vpnIntent!!)
                        } else {
                            Toast.makeText(context, "VPN already configured & authenticated.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Setup Context",
                        tint = textSecondary
                    )
                }
            }

            // Scrollable Content Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card 1: Raid Connection Info Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = cardBorder, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shield custom icon
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
                                text = "Raid Connection Tool",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "A selective 1-second network cutoff engine customized strictly for Clash of Clans, and no other apps.",
                                color = textSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Card 2: Floating Battle Shield Options Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = cardBorder, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Light background play indicator icon capsule
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(indigoLight, shape = RoundedCornerShape(percent = 50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Enable Toggle",
                                tint = indigoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Floating Battle Shield",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Turn on to show floating reload button",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        }

                        // Switch Button Custom style
                        Switch(
                            checked = isShieldEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !hasOverlayPermissionState) {
                                    Toast.makeText(context, "System drawing overlay permission is required. Click 'Setup Settings' to authorize.", Toast.LENGTH_LONG).show()
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
                                checkedTrackColor = indigoPrimary,
                                uncheckedThumbColor = Color(0xFF64748B),
                                uncheckedTrackColor = Color(0xFFE2E8F0)
                            )
                        )
                    }

                    // Divider separator line
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(cardBorder)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Title header sub-options
                    Text(
                        text = "Compact Shield Options",
                        color = Color(0xFF6366F1), // Modern non-vibrant theme color
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option A: Shield Button Size Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shield Button Size",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${shieldSize.toInt()} dp",
                            color = indigoPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                thumbColor = indigoPrimary,
                                activeTrackColor = Color(0xFF818CF8),
                                inactiveTrackColor = cardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option B: Shield Idle Transparency Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shield Idle Transparency",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${shieldTransparency.toInt()}%",
                            color = indigoPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                thumbColor = indigoPrimary,
                                activeTrackColor = Color(0xFF818CF8),
                                inactiveTrackColor = cardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Double action buttons at bottom
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Center Position Button
                        OutlinedButton(
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
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, cardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Center Symbol",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Center Position",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Setup Settings Button
                        Button(
                            onClick = {
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = indigoLight, contentColor = indigoPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "Build Symbol",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Setup Settings",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Card 3: Instant Duel Cutoff Tester Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, shape = RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = cardBorder, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Instant Duel Cutoff Tester",
                        color = textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Test out the 1-second restabilization shield logic instantly inside the app here below.",
                        color = textSecondary,
                        fontSize = 12.sp,
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
                                color = cardBorder,
                                style = Stroke(width = 8.dp.toPx())
                            )
                            // Animated angle track
                            drawArc(
                                color = if (RaidReloadManager.isCutoffActive) Color(0xFFEF4444) else indigoPrimary,
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
                                    color = Color(0xFFEF4444),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "Active",
                                    color = Color(0xFFEF4444),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
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
                                    tint = indigoPrimary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Large dynamic Action testing button
                    Button(
                        onClick = { RaidReloadManager.triggerCutoff(context) },
                        enabled = !RaidReloadManager.isCutoffActive,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (RaidReloadManager.isCutoffActive) Color(0xFFEF4444) else Color(0xFF0F172A),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (RaidReloadManager.isCutoffActive) "SHIELD ACTIVE (1s)" else "TEST RE-CONNECT SHIELD (1s)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
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
    val bgWebsite = Color(0xFFEBE8DF) // Warm beige background matching the website
    val titleBarColor = Color(0xFF423C35) // Deep Slate/Charcoal for headers
    val parchmentBg = Color(0xFFF6F4EB) // Creamy white container background
    val borderCharcoal = Color(0xFF4C473E) // Muted border color
    
    // 3D Tab-style Colors
    val tabGrayBase = Color(0xFFC7C5BD)
    val tabGrayShadow = Color(0xFF8F8E86)
    val tabYellowBase = Color(0xFFF3C32B)
    val tabYellowShadow = Color(0xFFB08C15)
    
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



