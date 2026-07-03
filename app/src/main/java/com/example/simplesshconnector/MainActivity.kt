package com.example.simplesshconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.OutputStream

class MainActivity : Activity() {
    private val preferences by lazy {
        AppPreferences.from(this)
    }

    private lateinit var sharedFolderPathTextView: TextView
    private lateinit var apkListContainer: LinearLayout
    private var isLoadingRemoteFileList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermission()
        setContentView(R.layout.activity_main)
        sharedFolderPathTextView = findViewById(R.id.sharedFolderPathTextView)
        apkListContainer = findViewById(R.id.apkListContainer)
        findViewById<Button>(R.id.terminalButton).setOnClickListener {
            openTerminal()
        }
        findViewById<Button>(R.id.configurationButton).setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connectAndLoadApks()
        }
        findViewById<Button>(R.id.uploadButton).setOnClickListener {
            openUploadFilePicker()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPLOAD_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                uploadSelectedFile(uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        displaySharedFolderPath()
        if (preferences.uploadScreenshotsToSharedFolder && canReadImages()) {
            ScreenshotUploadManager.start(this)
        } else {
            ScreenshotUploadManager.stop(this)
        }
        connectAndLoadApks()
    }

    private fun displaySharedFolderPath() {
        displaySharedFolderPath(preferences.remoteApkPath.toOsInterpretedPathDisplay())
    }

    private fun displaySharedFolderPath(remotePath: String) {
        sharedFolderPathTextView.text = getString(
            R.string.label_shared_folder_path,
            remotePath
        )
    }

    private fun connectAndLoadApks() {
        if (isLoadingRemoteFileList) {
            return
        }

        runRemoteFileTask(
            onFailureMessage = { getString(R.string.message_ssh_error, it) },
            onFailure = { error ->
                isLoadingRemoteFileList = false
                apkListContainer.removeAllViews()
                if (error is RemotePathResolutionException) {
                    displaySharedFolderPath(getString(R.string.label_shared_folder_path_error))
                }
            },
            onStart = {
                isLoadingRemoteFileList = true
                apkListContainer.removeAllViews()
                showShortToast(getString(R.string.message_connecting))
            }
        ) { remoteFiles ->
            val resolvedPath = remoteFiles.resolvedDirectory()
            val apkNames = remoteFiles.listFiles()
            runOnUiThread {
                isLoadingRemoteFileList = false
                displaySharedFolderPath(resolvedPath)
                displayApkButtons(apkNames)
            }
        }
    }

    private fun runRemoteFileTask(
        onFailureMessage: (String) -> String,
        onStart: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        task: (SshRemoteFileSession) -> Unit
    ) {
        val serverConfig = preferences.serverConfig()
        if (!serverConfig.hasRemoteFileConnectionInfo()) {
            showShortToast(getString(R.string.message_ssh_target_key_and_path_required))
            return
        }

        onStart()
        Thread {
            runCatching {
                SshRemoteFileSession.connect(serverConfig).use(task)
            }.onFailure { error ->
                runOnUiThread {
                    onFailure(error)
                    showShortToast(onFailureMessage(error.displayMessage()))
                }
            }
        }.start()
    }

    private fun openTerminal() {
        if (!preferences.serverConfig().hasTerminalConnectionInfo()) {
            showShortToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        startActivity(Intent(this, TerminalActivity::class.java))
    }

    private fun displayApkButtons(apkNames: List<String>) {
        apkListContainer.removeAllViews()

        if (apkNames.isEmpty()) {
            apkListContainer.gravity = Gravity.CENTER
            apkListContainer.addView(createEmptyFolderView())
            return
        }

        apkListContainer.gravity = Gravity.NO_GRAVITY
        apkNames.forEach { apkName ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.file_list_item_background)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }

            row.addView(Button(this).apply {
                text = apkName
                setAllCaps(false)
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 2
                setBackgroundResource(R.drawable.button_primary)
                minHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(48),
                    1f
                )
                setOnClickListener {
                    downloadApk(apkName)
                }
            })

            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete_24)
                setBackgroundResource(R.drawable.button_danger)
                contentDescription = getString(R.string.action_delete)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    dp(48),
                    dp(48)
                ).apply {
                    leftMargin = dp(8)
                }
                setOnClickListener {
                    confirmDeleteFile(apkName)
                }
            })

            apkListContainer.addView(row)
        }
    }

    private fun createEmptyFolderView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(36))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder_open_48)
                contentDescription = null
                layoutParams = LinearLayout.LayoutParams(
                    dp(56),
                    dp(56)
                ).apply {
                    bottomMargin = dp(16)
                }
            })

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.message_no_files_found)
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.message_no_files_found_detail)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(getColor(R.color.text_muted))
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            })
        }
    }

    private fun confirmDeleteFile(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete_file))
            .setMessage(getString(R.string.message_confirm_delete_file, fileName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleteRemoteListFile(fileName)
            }
            .show()
    }

    private fun downloadApk(apkName: String) {
        runRemoteFileTask(
            onFailureMessage = { getString(R.string.message_download_error, it) },
            onStart = { showShortToast(getString(R.string.message_download_started, apkName)) }
        ) { remoteFiles ->
            val apkUri = downloadRemoteApk(remoteFiles, apkName)
            showShortToastOnUiThread(getString(R.string.message_download_completed, apkName))
            showDownloadedNotification(apkName, apkUri)
            if (shouldInstallDownloadedApk(apkName)) {
                openApkInstaller(apkName, apkUri)
            }
        }
    }

    private fun deleteRemoteListFile(fileName: String) {
        runRemoteFileTask(
            onFailureMessage = { getString(R.string.message_delete_error, it) },
            onStart = { showShortToast(getString(R.string.message_delete_started, fileName)) }
        ) { remoteFiles ->
            remoteFiles.deleteFile(fileName)
            val apkNames = remoteFiles.listFiles()
            runOnUiThread {
                showShortToast(getString(R.string.message_delete_completed, fileName))
                displayApkButtons(apkNames)
            }
        }
    }

    private fun uploadSelectedFile(uri: Uri) {
        val fileName = displayNameFor(uri)

        runRemoteFileTask(
            onFailureMessage = { getString(R.string.message_upload_error, it) },
            onStart = { showShortToast(getString(R.string.message_upload_started, fileName)) }
        ) { remoteFiles ->
            RemoteFileNameValidator.requireValid(fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                remoteFiles.uploadFile(input, fileName)
            } ?: error("Cannot open selected file")
            val apkNames = remoteFiles.listFiles()
            runOnUiThread {
                showShortToast(getString(R.string.message_upload_completed, fileName))
                displayApkButtons(apkNames)
            }
        }
    }

    private fun downloadRemoteApk(remoteFiles: SshRemoteFileSession, apkName: String): Uri {
        val destination = openDownloadDestination(apkName)
        try {
            destination.outputStream.use { output ->
                remoteFiles.downloadFile(apkName, output)
            }
            destination.markComplete()
        } catch (error: Throwable) {
            runCatching {
                destination.deleteIncomplete()
            }
            throw error
        }
        return destination.uri
    }

    private fun openUploadFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        runCatching {
            startActivityForResult(intent, UPLOAD_FILE_REQUEST_CODE)
        }.onFailure { error ->
            showShortToast(getString(R.string.message_file_picker_error, error.displayMessage()))
        }
    }

    private fun displayNameFor(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val displayName = cursor.getString(index)
                        if (!displayName.isNullOrBlank()) {
                            return displayName
                        }
                    }
                }
            }
        }

        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "uploaded-file"
    }

    private fun openDownloadDestination(apkName: String): DownloadDestination {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteExistingMediaStoreDownload(apkName)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apkName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(apkName))
                put(MediaStore.Downloads.RELATIVE_PATH, MEDIASTORE_DOWNLOAD_RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download file")
            val outputStream = try {
                contentResolver.openOutputStream(uri) ?: error("Cannot open download file")
            } catch (error: Throwable) {
                runCatching {
                    contentResolver.delete(uri, null, null)
                }
                throw error
            }

            DownloadDestination(
                uri = uri,
                outputStream = outputStream,
                markComplete = {
                    val completedValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, completedValues, null, null)
                },
                deleteIncomplete = {
                    contentResolver.delete(uri, null, null)
                }
            )
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SimpleSshConnector"
            )
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                error("Cannot create ${downloadsDir.absolutePath}")
            }
            val uri = Uri.Builder()
                .scheme("content")
                .authority("$packageName.downloaded-apks")
                .appendPath(apkName)
                .build()
            val destinationFile = File(downloadsDir, apkName)
            DownloadDestination(uri, destinationFile.outputStream(), markComplete = {}, deleteIncomplete = {
                destinationFile.delete()
            })
        }
    }

    private fun deleteExistingMediaStoreDownload(fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        val relativePathWithSlash = "$MEDIASTORE_DOWNLOAD_RELATIVE_PATH/"
        contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND (${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
            arrayOf(fileName, MEDIASTORE_DOWNLOAD_RELATIVE_PATH, relativePathWithSlash)
        )
    }

    private fun showDownloadedNotification(apkName: String, apkUri: Uri) {
        if (!canPostNotifications()) {
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, mimeTypeFor(apkName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            apkName.hashCode(),
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_file_downloaded))
            .setContentText(apkName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        postNotification(apkName.hashCode(), notification)
    }

    private fun shouldInstallDownloadedApk(apkName: String): Boolean {
        return apkName.endsWith(".apk", ignoreCase = true) &&
            preferences.installDownloadedApks
    }

    private fun openApkInstaller(apkName: String, apkUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, mimeTypeFor(apkName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runOnUiThread {
            runCatching {
                startActivity(installIntent)
            }.onFailure { error ->
                showShortToast(getString(R.string.message_install_open_error, error.displayMessage()))
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.notification_channel_apk_downloads),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, notification: Notification) {
        notificationManager().notify(id, notification)
    }

    private fun notificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, DOWNLOAD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        if (fileName.endsWith(".apk", ignoreCase = true)) {
            return DownloadedApkProvider.APK_MIME_TYPE
        }

        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: DEFAULT_FILE_MIME_TYPE
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class DownloadDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val markComplete: () -> Unit,
        val deleteIncomplete: () -> Unit
    )

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "apk_downloads"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 100
        private const val UPLOAD_FILE_REQUEST_CODE = 101
        private const val DEFAULT_FILE_MIME_TYPE = "application/octet-stream"
        private val MEDIASTORE_DOWNLOAD_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/SimpleSshConnector"
    }
}
