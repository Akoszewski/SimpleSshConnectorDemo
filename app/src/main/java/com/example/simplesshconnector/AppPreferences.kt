package com.example.simplesshconnector

import android.content.Context
import android.content.SharedPreferences

class AppPreferences private constructor(
    private val preferences: SharedPreferences,
    private val sshPrivateKeyStore: SshPrivateKeyStore = SshPrivateKeyStore(preferences)
) {
    val address: String
        get() = preferences.getString(KEY_IP_ADDRESS, "")?.trim().orEmpty()

    val privateKey: SshPrivateKey
        get() = sshPrivateKeyStore.get()

    val remoteApkPath: String
        get() = preferences.getString(KEY_REMOTE_APK_PATH, "")?.trim().orEmpty()

    val terminalStartPath: String
        get() = preferences.getString(KEY_TERMINAL_START_PATH, "")?.trim().orEmpty()

    val publicKey: String
        get() {
            if (privateKey.isBlank()) {
                return ""
            }
            return preferences.getString(KEY_PUBLIC_SSH_KEY, "").orEmpty()
        }

    var installDownloadedApks: Boolean
        get() = preferences.getBoolean(KEY_INSTALL_DOWNLOADED_APKS, false)
        set(value) {
            preferences.edit().putBoolean(KEY_INSTALL_DOWNLOADED_APKS, value).apply()
        }

    var uploadScreenshotsToSharedFolder: Boolean
        get() = preferences.getBoolean(KEY_UPLOAD_SCREENSHOTS, false)
        set(value) {
            preferences.edit().putBoolean(KEY_UPLOAD_SCREENSHOTS, value).apply()
        }

    var lastUploadedScreenshotId: Long
        get() = preferences.getLong(KEY_LAST_UPLOADED_SCREENSHOT_ID, -1L)
        set(value) {
            preferences.edit().putLong(KEY_LAST_UPLOADED_SCREENSHOT_ID, value).apply()
        }

    fun setAddress(address: String) = preferences.edit().putString(KEY_IP_ADDRESS, address).apply()

    fun setRemoteApkPath(remoteApkPath: String) = preferences.edit().putString(KEY_REMOTE_APK_PATH, remoteApkPath).apply()

    fun setTerminalStartPath(terminalStartPath: String) =
        preferences.edit().putString(KEY_TERMINAL_START_PATH, terminalStartPath).apply()

    fun serverConfig(): SshServerConfig {
        return SshServerConfig(
            address = address,
            privateKey = privateKey,
            remoteApkPath = remoteApkPath,
            terminalStartPath = terminalStartPath
        )
    }

    fun setGeneratedKeys(privateKeyPem: String, publicKeyOpenSsh: String) {
        sshPrivateKeyStore.set(SshPrivateKey(privateKeyPem))
        preferences.edit()
            .putString(KEY_PUBLIC_SSH_KEY, publicKeyOpenSsh)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "ssh_apk_downloader"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PUBLIC_SSH_KEY = "public_ssh_key"
        private const val KEY_REMOTE_APK_PATH = "remote_apk_path"
        private const val KEY_TERMINAL_START_PATH = "terminal_start_path"
        private const val KEY_INSTALL_DOWNLOADED_APKS = "install_downloaded_apks"
        private const val KEY_UPLOAD_SCREENSHOTS = "upload_screenshots_to_shared_folder"
        private const val KEY_LAST_UPLOADED_SCREENSHOT_ID = "last_uploaded_screenshot_id"

        fun from(context: Context): AppPreferences {
            return AppPreferences(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            )
        }
    }
}
