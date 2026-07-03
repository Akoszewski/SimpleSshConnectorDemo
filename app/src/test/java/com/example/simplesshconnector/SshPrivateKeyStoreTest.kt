package com.example.simplesshconnector

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPrivateKeyStoreTest {
    @Test
    fun setStoresEncryptedPrivateKeyOnly() {
        val preferences = FakeSharedPreferences()
        val store = SshPrivateKeyStore(preferences, PrefixEncryptor())

        store.set(SshPrivateKey("private-key"))

        assertEquals("encrypted:private-key", preferences.getString(ENCRYPTED_KEY, null))
        assertFalse(preferences.contains(LEGACY_KEY))
    }

    @Test
    fun getDecryptsStoredPrivateKey() {
        val preferences = FakeSharedPreferences(
            ENCRYPTED_KEY to "encrypted:private-key"
        )
        val store = SshPrivateKeyStore(preferences, PrefixEncryptor())

        assertEquals(SshPrivateKey("private-key"), store.get())
    }

    @Test
    fun getMigratesLegacyPlaintextPrivateKeyToEncryptedStorage() {
        val preferences = FakeSharedPreferences(
            LEGACY_KEY to "legacy-private-key"
        )
        val store = SshPrivateKeyStore(preferences, PrefixEncryptor())

        assertEquals(SshPrivateKey("legacy-private-key"), store.get())
        assertEquals("encrypted:legacy-private-key", preferences.getString(ENCRYPTED_KEY, null))
        assertFalse(preferences.contains(LEGACY_KEY))
    }

    @Test
    fun getClearsStoredKeyWhenDecryptionFails() {
        val preferences = FakeSharedPreferences(
            ENCRYPTED_KEY to "unreadable"
        )
        val store = SshPrivateKeyStore(preferences, PrefixEncryptor())

        assertEquals(SshPrivateKey.Empty, store.get())
        assertFalse(preferences.contains(ENCRYPTED_KEY))
        assertFalse(preferences.contains(LEGACY_KEY))
    }

    @Test
    fun getClearsLegacyPlaintextPrivateKeyWhenMigrationEncryptionFails() {
        val preferences = FakeSharedPreferences(
            LEGACY_KEY to "legacy-private-key"
        )
        val store = SshPrivateKeyStore(preferences, FailingEncryptor())

        assertEquals(SshPrivateKey.Empty, store.get())
        assertFalse(preferences.contains(ENCRYPTED_KEY))
        assertFalse(preferences.contains(LEGACY_KEY))
    }

    private class PrefixEncryptor : SshPrivateKeyEncryptor {
        override fun encrypt(plaintext: String): String = "encrypted:$plaintext"

        override fun decrypt(encryptedValue: String): String {
            require(encryptedValue.startsWith("encrypted:"))
            return encryptedValue.removePrefix("encrypted:")
        }
    }

    private class FailingEncryptor : SshPrivateKeyEncryptor {
        override fun encrypt(plaintext: String): String = error("Encryption failed")

        override fun decrypt(encryptedValue: String): String = error("Decryption failed")
    }

    private class FakeSharedPreferences(
        vararg initialValues: Pair<String, String>
    ) : SharedPreferences {
        private val values = initialValues.toMap().toMutableMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? {
            return values[key] as? String ?: defValue
        }

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return values[key] as? MutableSet<String> ?: defValues
        }

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                updates[key] = values
                removals.remove(key)
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                removals.add(key)
                updates.remove(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                values.clear()
                updates.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    values[key] = value
                }
            }
        }
    }

    companion object {
        private const val LEGACY_KEY = "private_ssh_key"
        private const val ENCRYPTED_KEY = "encrypted_private_ssh_key_v1"
    }
}
