package com.example.sshapkdownloader

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object ScreenshotUploadManager {
    private const val RECENT_SCREENSHOT_GRACE_SECONDS = 5L

    private var observer: ContentObserver? = null
    private var startedAtSeconds = 0L
    private val uploadInProgress = AtomicBoolean(false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        val preferences = AppPreferences.from(appContext)
        if (!preferences.uploadScreenshotsToSharedFolder || !appContext.canReadImages()) {
            stop(appContext)
            return
        }
        if (observer != null) {
            return
        }

        startedAtSeconds = System.currentTimeMillis() / 1000L
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uploadNewestScreenshot(appContext)
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer as ContentObserver
        )
    }

    fun stop(context: Context) {
        observer?.let {
            context.applicationContext.contentResolver.unregisterContentObserver(it)
        }
        observer = null
    }

    private fun uploadNewestScreenshot(context: Context) {
        if (!uploadInProgress.compareAndSet(false, true)) {
            return
        }

        Thread {
            try {
                runCatching {
                    val screenshot = findNewestScreenshot(context) ?: return@Thread
                    uploadScreenshot(context, screenshot)
                    rememberUploadedScreenshot(context, screenshot.id)
                }.onSuccess {
                    showToast(context, context.getString(R.string.message_screenshot_uploaded))
                }.onFailure { error ->
                    showToast(
                        context,
                        context.getString(R.string.message_screenshot_upload_error, error.displayMessage())
                    )
                }
            } finally {
                uploadInProgress.set(false)
            }
        }.start()
    }

    private fun findNewestScreenshot(context: Context): ScreenshotFile? {
        val preferences = AppPreferences.from(context)
        val lastUploadedId = preferences.lastUploadedScreenshotId
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            pathColumn
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val pathColumnIndex = cursor.getColumnIndexOrThrow(pathColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(displayNameColumn) ?: "screenshot-$id.png"
                val dateAdded = cursor.getLong(dateAddedColumn)
                val path = cursor.getString(pathColumnIndex).orEmpty()
                val createdAfterStart = dateAdded >= startedAtSeconds - RECENT_SCREENSHOT_GRACE_SECONDS
                if (id > lastUploadedId && createdAfterStart && isScreenshot(displayName, path)) {
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    return ScreenshotFile(id, displayName, uri)
                }
            }
        }

        return null
    }

    private fun uploadScreenshot(context: Context, screenshot: ScreenshotFile) {
        val preferences = AppPreferences.from(context)
        val serverConfig = preferences.serverConfig()
        if (!serverConfig.hasRemoteFileConnectionInfo()) {
            error(context.getString(R.string.message_ssh_target_key_and_path_required))
        }

        context.contentResolver.openInputStream(screenshot.uri)?.use { input ->
            SshRemoteFileSession.connect(serverConfig).use { remoteFiles ->
                remoteFiles.uploadFile(input, screenshot.displayName, validateFileName = false)
            }
        } ?: error("Cannot open screenshot")
    }

    private fun rememberUploadedScreenshot(context: Context, id: Long) {
        AppPreferences.from(context).lastUploadedScreenshotId = id
    }

    private fun isScreenshot(displayName: String, relativePath: String): Boolean {
        val text = "$relativePath/$displayName".lowercase(Locale.US)
        return "screenshot" in text || "screenshots" in text
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class ScreenshotFile(
        val id: Long,
        val displayName: String,
        val uri: Uri
    )
}
