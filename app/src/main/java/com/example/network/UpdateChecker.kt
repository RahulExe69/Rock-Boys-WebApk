package com.example.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changeLog: String,
    val apkUrl: String,
    val forceUpdate: Boolean
)

data class DownloadProgressState(
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val formattedProgress: String = ""
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    
    // Primary official GitHub Releases API endpoint (only available after CI build finishes)
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/RahulExe69/Rock-Boys-WebApk/releases/latest"
    
    // Backup fallback endpoints for older clients / failover
    private const val BACKUP_UPDATE_URL = "https://raw.githubusercontent.com/RahulExe69/Rock-Boys-WebApk/main/.versions/update.json"

    // High-performance OkHttpClient with optimized connection pooling and fast connect timeout
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun getRunningVersionCode(context: Context): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Throwable) {
            1
        }
    }

    fun getRunningVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Throwable) {
            "1.0"
        }
    }

    /**
     * Compares two semantic version strings (e.g., "1.9.8" vs "1.9.7").
     * Returns true if remoteVersion is strictly higher than currentVersion.
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val cleanRemote = remoteVersion.trimStart('v', 'V').trim()
        val cleanCurrent = currentVersion.trimStart('v', 'V').trim()

        val remoteParts = cleanRemote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split('.', '-').mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Sanitizes changelogs to ensure users only see user-friendly feature and performance notes.
     */
    private fun sanitizeUserChangelog(rawText: String, versionName: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isBlank() || trimmed.contains("Automated build", ignoreCase = true) || trimmed.contains("skip ci", ignoreCase = true) || trimmed.contains("workflow", ignoreCase = true) || trimmed.contains("keystore", ignoreCase = true)) {
            return "Faster in-app downloads with instant connection, smoother performance, and stability enhancements."
        }
        return trimmed
    }

    /**
     * Checks for updates by querying GitHub Releases API first, with fallback to update.json.
     */
    suspend fun checkForUpdates(): UpdateInfo? {
        Log.d(TAG, "Checking update on GitHub Releases API: $GITHUB_RELEASES_API")
        var updateInfo = fetchFromGitHubReleases()

        if (updateInfo == null) {
            Log.d(TAG, "GitHub Releases API returned null or was rate-limited. Trying fallback JSON URL: $BACKUP_UPDATE_URL")
            updateInfo = fetchFromFallbackJson("$BACKUP_UPDATE_URL?t=${System.currentTimeMillis()}")
        }

        if (updateInfo != null) {
            Log.d(TAG, "Update check successful. Version: ${updateInfo.versionName}, APK URL: ${updateInfo.apkUrl}")
        } else {
            Log.e(TAG, "Update check failed across all endpoints.")
        }

        return updateInfo
    }

    private suspend fun fetchFromGitHubReleases(): UpdateInfo? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) RockBoysApp/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub Releases API returned HTTP ${response.code}")
                        return@withContext null
                    }

                    val bodyString = response.body?.string() ?: return@withContext null
                    val json = JSONObject(bodyString)

                    val tagName = json.optString("tag_name", "").trim()
                    val versionName = tagName.trimStart('v', 'V')
                    if (versionName.isBlank()) return@withContext null

                    val rawNotes = json.optString("body", "")
                    val changeLog = sanitizeUserChangelog(rawNotes, versionName)

                    // Locate the APK download asset
                    var apkUrl = ""
                    val assetsArray = json.optJSONArray("assets")
                    if (assetsArray != null) {
                        for (i in 0 until assetsArray.length()) {
                            val asset = assetsArray.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (apkUrl.isBlank()) {
                        apkUrl = "https://github.com/RahulExe69/Rock-Boys-WebApk/releases/download/$tagName/app-release.apk"
                    }

                    UpdateInfo(
                        versionCode = 0, // Semantic versioning comparison is preferred
                        versionName = versionName,
                        changeLog = changeLog,
                        apkUrl = apkUrl,
                        forceUpdate = true
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "GitHub Releases API check error: ${e.message}")
                null
            }
        }
    }

    private suspend fun fetchFromFallbackJson(targetUrl: String): UpdateInfo? {
        val request = Request.Builder()
            .url(targetUrl)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) RockBoysApp/1.0")
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to fetch version info: ${response.code} for $targetUrl")
                        return@withContext null
                    }
                    val bodyString = response.body?.string() ?: return@withContext null
                    val json = JSONObject(bodyString)
                    val vName = json.optString("versionName", "1.0")
                    UpdateInfo(
                        versionCode = json.optInt("versionCode", 1),
                        versionName = vName,
                        changeLog = sanitizeUserChangelog(json.optString("changeLog", ""), vName),
                        apkUrl = json.optString("apkUrl", ""),
                        forceUpdate = json.optBoolean("forceUpdate", true)
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Fallback JSON check error for $targetUrl: ${e.message}")
                null
            }
        }
    }

    /**
     * High-speed direct streaming APK downloader with zero-delay buffer and instant UI feedback.
     */
    suspend fun downloadApk(
        urlString: String,
        targetFile: File,
        onProgress: (DownloadProgressState) -> Unit
    ): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                targetFile.parentFile?.mkdirs()
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val request = Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Accept", "application/vnd.android.package-archive, */*")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download request returned HTTP ${response.code} for $urlString")
                        return@withContext false
                    }

                    val body = response.body ?: return@withContext false
                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L
                    var lastUiUpdateTime = System.currentTimeMillis()

                    // Immediately dispatch progress right after connecting so user sees instant reaction
                    onProgress(
                        DownloadProgressState(
                            bytesDownloaded = 0L,
                            totalBytes = totalBytes,
                            progress = 0.01f,
                            formattedProgress = if (totalBytes > 0) "0 B / ${formatBytes(totalBytes)}" else "Starting download..."
                        )
                    )

                    val bufferSize = 128 * 1024 // 128 KB high-speed socket buffer
                    val buffer = ByteArray(bufferSize)

                    BufferedInputStream(body.byteStream(), bufferSize).use { inputStream ->
                        BufferedOutputStream(targetFile.outputStream(), bufferSize).use { outputStream ->
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdateTime > 40 || bytesDownloaded == totalBytes) {
                                    lastUiUpdateTime = now
                                    val progress = if (totalBytes > 0) {
                                        (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    val formatted = if (totalBytes > 0) {
                                        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
                                    } else {
                                        "${formatBytes(bytesDownloaded)} downloaded"
                                    }
                                    onProgress(
                                        DownloadProgressState(
                                            bytesDownloaded = bytesDownloaded,
                                            totalBytes = totalBytes,
                                            progress = progress,
                                            formattedProgress = formatted
                                        )
                                    )
                                }
                            }
                            outputStream.flush()
                        }
                    }
                }
                return@withContext targetFile.exists() && targetFile.length() > 0
            } catch (e: Throwable) {
                Log.e(TAG, "Stream exception during high-speed APK download", e)
                return@withContext false
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "Installer file not found!", Toast.LENGTH_SHORT).show()
                return
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                authority,
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "Failed to start installation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
