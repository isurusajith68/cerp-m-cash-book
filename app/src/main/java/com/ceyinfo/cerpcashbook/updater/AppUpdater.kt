package com.ceyinfo.cerpcashbook.updater

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.ceyinfo.cerpcashbook.BuildConfig
import com.ceyinfo.cerpcashbook.data.model.GitHubRelease
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdater"
        private const val GITHUB_API = "https://api.github.com/repos"
        private const val PREF_NAME = "app_updater"
        private const val PREF_SKIP_VERSION = "skip_version"
        private const val PREF_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Check for updates from GitHub Releases.
     * Returns the latest release if a newer version is available, null otherwise.
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): GitHubRelease? {
        if (!forceCheck) {
            val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Skipping update check - checked recently")
                return null
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val owner = BuildConfig.GITHUB_OWNER
                val repo = BuildConfig.GITHUB_REPO
                val url = URL("$GITHUB_API/$owner/$repo/releases/latest")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode != 200) {
                    Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                    return@withContext null
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val release = Gson().fromJson(body, GitHubRelease::class.java)

                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()

                if (release.draft || release.prerelease) return@withContext null

                val remoteVersion = release.tagName.removePrefix("v")
                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewerVersion(remoteVersion, currentVersion)) {
                    val skippedVersion = prefs.getString(PREF_SKIP_VERSION, null)
                    if (!forceCheck && skippedVersion == remoteVersion) {
                        Log.d(TAG, "User skipped version $remoteVersion")
                        return@withContext null
                    }
                    Log.i(TAG, "Update available: $currentVersion -> $remoteVersion")
                    release
                } else {
                    Log.d(TAG, "App is up to date ($currentVersion)")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                null
            }
        }
    }

    /**
     * Download the APK using Android's DownloadManager and install when complete.
     */
    fun downloadAndInstall(activity: Activity, release: GitHubRelease) {
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        if (apkAsset == null) {
            Log.e(TAG, "No APK asset found in release")
            return
        }

        val fileName = apkAsset.name
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = File(downloadDir, fileName)

        // Delete old APK if exists
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkAsset.downloadUrl))
            .setTitle("CERP Cash Book Update")
            .setDescription("Downloading ${release.tagName}...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apkFile))

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // Register receiver to install after download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(ctx, apkFile)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Launch the APK installer intent.
     */
    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /**
     * Mark a version as skipped so the user won't be prompted again.
     */
    fun skipVersion(version: String) {
        prefs.edit().putString(PREF_SKIP_VERSION, version.removePrefix("v")).apply()
    }

    /**
     * Compare semantic versions. Returns true if remote > current.
     */
    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
