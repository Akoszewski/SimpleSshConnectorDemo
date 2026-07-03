package com.example.sshapkdownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView

class ConfigActivity : Activity() {
    private val preferences by lazy {
        AppPreferences.from(this)
    }

    private lateinit var ipAddressEditText: EditText
    private lateinit var remoteApkPathEditText: EditText
    private lateinit var terminalStartPathEditText: EditText
    private lateinit var installDownloadedApksCheckBox: CheckBox
    private lateinit var uploadScreenshotsCheckBox: CheckBox
    private lateinit var publicKeyEditText: TextView
    private lateinit var generateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        ipAddressEditText = findViewById(R.id.ipAddressEditText)
        remoteApkPathEditText = findViewById(R.id.remoteApkPathEditText)
        terminalStartPathEditText = findViewById(R.id.terminalStartPathEditText)
        installDownloadedApksCheckBox = findViewById(R.id.installDownloadedApksCheckBox)
        uploadScreenshotsCheckBox = findViewById(R.id.uploadScreenshotsCheckBox)
        publicKeyEditText = findViewById(R.id.publicKeyEditText)
        generateButton = findViewById(R.id.generateButton)
        ipAddressEditText.doOnTextChanged(preferences::setAddress)
        remoteApkPathEditText.doOnTextChanged(preferences::setRemoteApkPath)
        terminalStartPathEditText.doOnTextChanged(preferences::setTerminalStartPath)
        generateButton.setOnClickListener {
            generateKey()
        }
        uploadScreenshotsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveScreenshotUploadEnabled(isChecked)
        }
        installDownloadedApksCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveDownloadedApkInstallEnabled(isChecked)
        }
        findViewById<ImageButton>(R.id.copyButton).setOnClickListener {
            copyPublicKey()
        }
        restoreSavedValues()
    }

    private fun restoreSavedValues() {
        ipAddressEditText.setText(preferences.address)
        remoteApkPathEditText.setText(preferences.remoteApkPath)
        terminalStartPathEditText.setText(preferences.terminalStartPath)
        installDownloadedApksCheckBox.isChecked = preferences.installDownloadedApks
        uploadScreenshotsCheckBox.isChecked = preferences.uploadScreenshotsToSharedFolder
        publicKeyEditText.setText(preferences.publicKey)
    }

    private fun saveDownloadedApkInstallEnabled(enabled: Boolean) {
        preferences.installDownloadedApks = enabled
    }

    private fun saveScreenshotUploadEnabled(enabled: Boolean) {
        if (enabled && !canReadImages()) {
            requestPermissions(arrayOf(imageReadPermission()), IMAGE_READ_PERMISSION_REQUEST_CODE)
        }
        preferences.uploadScreenshotsToSharedFolder = enabled
        if (enabled && canReadImages()) {
            ScreenshotUploadManager.start(this)
        } else {
            ScreenshotUploadManager.stop(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != IMAGE_READ_PERMISSION_REQUEST_CODE) {
            return
        }

        val granted = canReadImages()
        uploadScreenshotsCheckBox.isChecked = granted
        preferences.uploadScreenshotsToSharedFolder = granted
        if (granted) {
            ScreenshotUploadManager.start(this)
        } else {
            ScreenshotUploadManager.stop(this)
            showShortToast(getString(R.string.message_image_permission_required))
        }
    }

    private fun generateKey() {
        generateButton.isEnabled = false
        showShortToast(getString(R.string.message_generating_key))

        Thread {
            runCatching {
                SshKeyGenerator.generate()
            }.onSuccess { keyPair ->
                preferences.setGeneratedKeys(keyPair.privateKeyPem, keyPair.publicKeyOpenSsh)

                runOnUiThread {
                    publicKeyEditText.setText(keyPair.publicKeyOpenSsh)
                    generateButton.isEnabled = true
                    showShortToast(getString(R.string.message_key_generated))
                }
            }.onFailure { error ->
                runOnUiThread {
                    generateButton.isEnabled = true
                    showShortToast(getString(R.string.message_generation_error, error.displayMessage()))
                }
            }
        }.start()
    }

    private fun copyPublicKey() {
        val publicKey = publicKeyEditText.text.toString()
        if (publicKey.isBlank()) {
            showShortToast(getString(R.string.message_no_public_key))
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_ssh_public_key), publicKey))
        showShortToast(getString(R.string.message_public_key_copied))
    }

    companion object {
        private const val IMAGE_READ_PERMISSION_REQUEST_CODE = 200
    }
}
