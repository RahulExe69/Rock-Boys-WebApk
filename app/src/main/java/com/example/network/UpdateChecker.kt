package com.example.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changeLog: String,
    val apkUrl: String,
    val forceUpdate: Boolean
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    
    // Toggle between GitHub update-file hosting or Google Drive update-file hosting
    const val USE_GOOGLE_DRIVE = false
    
    // Google Drive File ID for 'version.json' (make sure file is shared as 'Anyone with link can view')
    const val GOOGLE_DRIVE_VERSION_FILE_ID = "1A_2B_3C_Replace_With_Your_Google_Drive_File_ID_Here"

    private const val UPDATE_URL = "https://raw.githubusercontent.com/RahulExe69/Rock-Boys-WebApk/main/.versions/update.json"
    private val client = OkHttpClient()

    fun getRunningVersionCode(context: Context): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getRunningVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    suspend fun checkForUpdates(): UpdateInfo? {
        val targetUrl = if (USE_GOOGLE_DRIVE) {
            "https://docs.google.com/uc?export=download&id=$GOOGLE_DRIVE_VERSION_FILE_ID"
        } else {
            "$UPDATE_URL?t=${System.currentTimeMillis()}"
        }
        
        val request = Request.Builder()
            .url(targetUrl)
            .header("Cache-Control", "no-cache")
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to fetch version info: ${response.code}")
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
                Log.e(TAG, "Network error checking updates", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Parsing error checking updates", e)
                null
            }
        }
    }

    suspend fun downloadApk(
        urlString: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                targetFile.parentFile?.mkdirs()
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val request = Request.Builder()
                    .url(urlString)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed, response code: ${response.code}")
                        return@withContext false
                    }

                    val body = response.body ?: return@withContext false
                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L

                    body.byteStream().use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead
                                if (totalBytes > 0) {
                                    val progress = bytesDownloaded.toFloat() / totalBytes
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Exception during APK download", e)
                false
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "Failed to start installation: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
