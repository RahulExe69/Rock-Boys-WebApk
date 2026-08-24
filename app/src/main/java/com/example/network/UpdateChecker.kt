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
    
    // Toggle between GitHub update-file hosting or Google Drive update-file hosting
    const val USE_GOOGLE_DRIVE = false
    
    // Google Drive File ID for 'version.json' (make sure file is shared as 'Anyone with link can view')
    const val GOOGLE_DRIVE_VERSION_FILE_ID = "1A_2B_3C_Replace_With_Your_Google_Drive_File_ID_Here"

    private const val UPDATE_URL = "https://raw.githubusercontent.com/RahulExe69/Rock-Boys-WebApk/main/.versions/update.json"
    private const val BACKUP_UPDATE_URL = "https://raw.githubusercontent.com/RahulExe69/Rock-Boys-WebApk/main/.versions/version.json"
    private const val FALLBACK_RAW_APK_URL = "https://github.com/RahulExe69/Rock-Boys-WebApk/raw/refs/heads/main/.build-outputs/app-release.apk"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
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

    suspend fun checkForUpdates(): UpdateInfo? {
        val targetUrl = if (USE_GOOGLE_DRIVE) {
            "https://docs.google.com/uc?export=download&id=$GOOGLE_DRIVE_VERSION_FILE_ID"
        } else {
            "$UPDATE_URL?t=${System.currentTimeMillis()}"
        }
        
        Log.d(TAG, "Checking update on primary URL: $targetUrl")
        var updateInfo = fetchUpdateFromUrl(targetUrl)

        if (updateInfo == null && !USE_GOOGLE_DRIVE) {
            val fallbackUrl = "$BACKUP_UPDATE_URL?t=${System.currentTimeMillis()}"
            Log.d(TAG, "Primary update URL failed or returned null. Trying backup URL: $fallbackUrl")
            updateInfo = fetchUpdateFromUrl(fallbackUrl)
        }

        if (updateInfo != null) {
            Log.d(TAG, "Update check successful. Server version: ${updateInfo.versionName} (${updateInfo.versionCode}), forceUpdate: ${updateInfo.forceUpdate}")
        } else {
            Log.e(TAG, "Update check failed on all endpoints.")
        }

        return updateInfo
    }

    private suspend fun fetchUpdateFromUrl(targetUrl: String): UpdateInfo? {
        val request = Request.Builder()
            .url(targetUrl)
            .header("Cache-Control", "no-cache")
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
                    UpdateInfo(
                        versionCode = json.optInt("versionCode", 1),
                        versionName = json.optString("versionName", "1.0"),
                        changeLog = json.optString("changeLog", ""),
                        apkUrl = json.optString("apkUrl", ""),
                        forceUpdate = json.optBoolean("forceUpdate", false)
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error checking updates for $targetUrl: ${e.message}")
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Error checking updates for $targetUrl: ${e.message}")
                null
            }
        }
    }

    suspend fun downloadApk(
        urlString: String,
        targetFile: File,
        onProgress: (DownloadProgressState) -> Unit
    ): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Adaptive buffer tiers: 256 KB primary high-speed, then 64 KB, then 32 KB fallback
            val bufferSizes = listOf(256 * 1024, 64 * 1024, 32 * 1024)
            val urlsToTry = if (urlString.isNotBlank() && urlString != FALLBACK_RAW_APK_URL) {
                listOf(urlString, FALLBACK_RAW_APK_URL)
            } else {
                listOf(urlString)
            }

            for (currentUrl in urlsToTry) {
                for (bufSize in bufferSizes) {
                    Log.d(TAG, "Starting high-speed download from $currentUrl with buffer size: ${bufSize / 1024} KB")
                    val success = executeDownloadStream(currentUrl, targetFile, bufSize, onProgress)
                    if (success) {
                        Log.d(TAG, "Download successfully completed using ${bufSize / 1024} KB buffer.")
                        return@withContext true
                    }
                    Log.w(TAG, "Download attempt failed with buffer ${bufSize / 1024} KB. Attempting graceful adaptive fallback...")
                }
            }
            false
        }
    }

    private fun executeDownloadStream(
        urlString: String,
        targetFile: File,
        bufferSize: Int,
        onProgress: (DownloadProgressState) -> Unit
    ): Boolean {
        try {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val request = Request.Builder()
                .url(urlString)
                .header("User-Agent", "RockBoys-Android-Updater")
                .header("Accept-Encoding", "identity")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download request returned HTTP ${response.code} for $urlString")
                    return false
                }

                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                var bytesDownloaded = 0L
                var lastUiUpdateTime = 0L

                BufferedInputStream(body.byteStream(), bufferSize).use { inputStream ->
                    BufferedOutputStream(targetFile.outputStream(), bufferSize).use { outputStream ->
                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            val now = System.currentTimeMillis()
                            // Dispatch UI progress smoothly without overloading Compose threads
                            if (now - lastUiUpdateTime > 50 || bytesDownloaded == totalBytes) {
                                lastUiUpdateTime = now
                                val progress = if (totalBytes > 0) {
                                    (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else {
                                    0f
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
            return targetFile.exists() && targetFile.length() > 0
        } catch (e: Throwable) {
            Log.e(TAG, "Stream exception during APK download with buffer size $bufferSize", e)
            return false
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
