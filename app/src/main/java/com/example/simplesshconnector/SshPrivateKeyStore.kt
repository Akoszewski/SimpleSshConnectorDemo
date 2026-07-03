package com.example.simplesshconnector

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SshPrivateKeyStore(
    private val preferences: SharedPreferences,
    private val encryptor: SshPrivateKeyEncryptor = AndroidKeystoreSshPrivateKeyEncryptor()
) {
    fun get(): SshPrivateKey {
        val encryptedValue = preferences.getString(KEY_ENCRYPTED_PRIVATE_SSH_KEY, null)
        if (!encryptedValue.isNullOrBlank()) {
            return runCatching {
                SshPrivateKey(encryptor.decrypt(encryptedValue))
            }.getOrElse {
                clear()
                SshPrivateKey.Empty
            }
        }

        return migrateLegacyPlaintextKey()
    }

    fun set(privateKey: SshPrivateKey) {
        if (privateKey.isBlank()) {
            clear()
            return
        }

        preferences.edit()
            .putString(KEY_ENCRYPTED_PRIVATE_SSH_KEY, encryptor.encrypt(privateKey.pem))
            .remove(KEY_LEGACY_PRIVATE_SSH_KEY)
            .apply()
    }

    private fun migrateLegacyPlaintextKey(): SshPrivateKey {
        val legacyKey = preferences.getString(KEY_LEGACY_PRIVATE_SSH_KEY, null)
        if (legacyKey.isNullOrBlank()) {
            return SshPrivateKey.Empty
        }

        val privateKey = SshPrivateKey(legacyKey)
        return runCatching {
            set(privateKey)
            privateKey
        }.getOrElse {
            clear()
            SshPrivateKey.Empty
        }
    }

    private fun clear() {
        preferences.edit()
            .remove(KEY_ENCRYPTED_PRIVATE_SSH_KEY)
            .remove(KEY_LEGACY_PRIVATE_SSH_KEY)
            .apply()
    }

    companion object {
        private const val KEY_LEGACY_PRIVATE_SSH_KEY = "private_ssh_key"
        private const val KEY_ENCRYPTED_PRIVATE_SSH_KEY = "encrypted_private_ssh_key_v1"
    }
}

interface SshPrivateKeyEncryptor {
    fun encrypt(plaintext: String): String

    fun decrypt(encryptedValue: String): String
}

class AndroidKeystoreSshPrivateKeyEncryptor : SshPrivateKeyEncryptor {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            PAYLOAD_VERSION,
            cipher.iv.toBase64(),
            ciphertext.toBase64()
        ).joinToString(PAYLOAD_SEPARATOR)
    }

    override fun decrypt(encryptedValue: String): String {
        val parts = encryptedValue.split(PAYLOAD_SEPARATOR)
        require(parts.size == PAYLOAD_PART_COUNT && parts[0] == PAYLOAD_VERSION) {
            "Unsupported encrypted SSH key payload"
        }

        val iv = parts[1].fromBase64()
        val ciphertext = parts[2].fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }

        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun ByteArray.toBase64(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private fun String.fromBase64(): ByteArray {
        return Base64.decode(this, Base64.NO_WRAP)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ssh_apk_downloader_private_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PAYLOAD_VERSION = "v1"
        private const val PAYLOAD_SEPARATOR = ":"
        private const val PAYLOAD_PART_COUNT = 3
    }
}
