package com.example.sshapkdownloader

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var ipAddressEditText: EditText
    private lateinit var apkListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        restoreSavedValues()
    }

    override fun onPause() {
        super.onPause()
        saveValues()
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 32)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "SshApkDownloader"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(Button(this).apply {
            text = "Konfiguracja"
            textSize = 12f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
            }
        })

        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 48, 0, 0)
        }

        ipAddressEditText = EditText(this).apply {
            hint = "user@host:port"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        content.addView(ipAddressEditText)

        content.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                connectAndLoadApks()
            }
        })

        apkListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(apkListContainer)

        root.addView(content)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun restoreSavedValues() {
        ipAddressEditText.setText(preferences.getString("ip_address", ""))
    }

    private fun saveValues() {
        preferences.edit()
            .putString("ip_address", ipAddressEditText.text.toString())
            .apply()
    }

    private fun connectAndLoadApks() {
        saveValues()
        val address = ipAddressEditText.text.toString().trim()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast("SSH target and generated key are required")
            return
        }

        apkListContainer.removeAllViews()
        showToast("Connecting")

        Thread {
            runCatching {
                val session = createSession(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    listRemoteApks(session)
                } finally {
                    session.disconnect()
                }
            }.onSuccess { apkNames ->
                runOnUiThread {
                    displayApkButtons(apkNames)
                }
            }.onFailure { error ->
                runOnUiThread {
                    apkListContainer.removeAllViews()
                    showToast("SSH error: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun createSession(target: SshTarget, privateKey: String): Session {
        val jsch = JSch()
        jsch.addIdentity(
            "ssh-apk-downloader-key",
            privateKey.toByteArray(Charsets.UTF_8),
            null,
            null
        )

        return jsch.getSession(target.username, target.host, target.port).apply {
            setConfig("StrictHostKeyChecking", "no")
            timeout = 15_000
        }
    }

    private fun listRemoteApks(session: Session): List<String> {
        val command = "find ~/Artifacts/android -maxdepth 1 -type f -name '*.apk' -printf '%f\\n' | sort"
        val output = executeRemoteCommand(session, command)
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".apk") }
            .toList()
    }

    private fun executeRemoteCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()

        channel.setCommand(command)
        channel.outputStream = output
        channel.setErrStream(errorOutput)
        channel.connect(15_000)

        while (!channel.isClosed) {
            Thread.sleep(100)
        }

        val exitStatus = channel.exitStatus
        channel.disconnect()

        if (exitStatus != 0) {
            val message = errorOutput.toString(Charsets.UTF_8.name()).ifBlank {
                "Remote command failed with exit status $exitStatus"
            }
            error(message)
        }

        return output.toString(Charsets.UTF_8.name())
    }

    private fun displayApkButtons(apkNames: List<String>) {
        apkListContainer.removeAllViews()

        if (apkNames.isEmpty()) {
            apkListContainer.addView(TextView(this).apply {
                text = "No APK files found"
                textSize = 16f
            })
            return
        }

        apkNames.forEach { apkName ->
            apkListContainer.addView(Button(this).apply {
                text = apkName
                setOnClickListener {
                    downloadApk(apkName)
                }
            })
        }
    }

    private fun downloadApk(apkName: String) {
        saveValues()
        val address = ipAddressEditText.text.toString().trim()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast("SSH target and generated key are required")
            return
        }

        showToast("Download started: $apkName")

        Thread {
            runCatching {
                val session = createSession(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    downloadRemoteApk(session, apkName)
                } finally {
                    session.disconnect()
                }
            }.onSuccess {
                showToastOnUiThread("Download completed: $apkName")
            }.onFailure { error ->
                showToastOnUiThread("Download error: ${error.message ?: error.javaClass.simpleName}")
            }
        }.start()
    }

    private fun downloadRemoteApk(session: Session, apkName: String) {
        ApkNameValidator.requireValid(apkName)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(15_000)
        try {
            channel.cd("Artifacts/android")
            openDownloadOutputStream(apkName).use { output ->
                channel.get(apkName, output)
            }
        } finally {
            channel.disconnect()
        }
    }

    private fun openDownloadOutputStream(apkName: String): OutputStream {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apkName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SshApkDownloader")
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download file")
            contentResolver.openOutputStream(uri) ?: error("Cannot open download file")
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SshApkDownloader"
            )
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                error("Cannot create ${downloadsDir.absolutePath}")
            }
            File(downloadsDir, apkName).outputStream()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getStoredPrivateKey(): String {
        return preferences.getString("private_ssh_key", "") ?: ""
    }

    private fun showToastOnUiThread(message: String) {
        runOnUiThread {
            showToast(message)
        }
    }
}
