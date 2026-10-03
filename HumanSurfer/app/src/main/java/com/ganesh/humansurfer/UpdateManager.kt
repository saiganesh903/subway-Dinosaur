package com.ganesh.humansurfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateManager {

    private const val UPDATE_INFO_URL =
        "https://raw.githubusercontent.com/saiganesh903/subway-Dinosaur/main/update.json"

    private const val APK_FILE_NAME = "HumanSurfer-latest.apk"

    fun checkForUpdate(activity: Activity, showNoUpdateMessage: Boolean = false) {
        val executor = Executors.newSingleThreadExecutor()

        executor.execute {
            try {
                val connection = URL(UPDATE_INFO_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Cache-Control", "no-cache")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    post(activity) {
                        if (showNoUpdateMessage) {
                            Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@execute
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val data = JSONObject(json)
                val latestVersionCode = data.optLong("versionCode", 0L)
                val latestVersionName = data.optString("versionName", "")
                val apkUrl = data.optString("apkUrl", "")
                val notes = data.optString("notes", "")

                val currentVersionCode = getCurrentVersionCode(activity)

                if (latestVersionCode > currentVersionCode && apkUrl.isNotBlank()) {
                    post(activity) {
                        showUpdateDialog(
                            activity,
                            latestVersionName,
                            notes,
                            apkUrl
                        )
                    }
                } else if (showNoUpdateMessage) {
                    post(activity) {
                        Toast.makeText(activity, "You already have the latest version", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                post(activity) {
                    if (showNoUpdateMessage) {
                        Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        notes: String,
        apkUrl: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val message = buildString {
            append("A new version of Human Surfer is available.\n\n")
            if (versionName.isNotBlank()) {
                append("Version: ").append(versionName).append("\n")
            }
            if (notes.isNotBlank()) {
                append("\n").append(notes)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("🚀 Update available")
            .setMessage(message)
            .setPositiveButton("UPDATE NOW") { _, _ ->
                downloadAndInstall(activity, apkUrl)
            }
            .setNegativeButton("LATER", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Allow APK installation")
                .setMessage(
                    "Android needs permission to install the Human Surfer update. " +
                        "Allow this app to install packages, then tap UPDATE again."
                )
                .setPositiveButton("OPEN SETTINGS") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val targetDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: activity.filesDir
                val apkFile = File(targetDir, APK_FILE_NAME)

                if (apkFile.exists()) apkFile.delete()

                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed: ${connection.responseCode}")
                }

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                connection.disconnect()

                post(activity) {
                    installApk(activity, apkFile)
                }
            } catch (_: Exception) {
                post(activity) {
                    Toast.makeText(
                        activity,
                        "Update download failed. Check your internet connection.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Toast.makeText(activity, "Downloaded APK is invalid", Toast.LENGTH_LONG).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, "Could not open Android installer", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun post(activity: Activity, action: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                action()
            }
        }
    }
}
