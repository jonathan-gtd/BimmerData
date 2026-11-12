/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.settings

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (Telegraf token and password), encrypted with an AES-256-GCM key that never
 * leaves the Android Keystore (`secrets` prefs, not backed up).
 *
 * A copy goes to `secrets_backup` prefs, which Android's app backup sends to the user's Google
 * account (end-to-end encrypted with the screen lock), like the Local API token in the settings.
 * After a reinstall the Keystore key is new: the sealed value cannot be read any more, so the
 * restored copy is used and sealed again with the new key. No need to type the secret again.
 */
class SecretStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val backup = context.applicationContext.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes whenever a secret is written (observe to rebuild clients). */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    fun get(name: String): String {
        val sealed = prefs.getString(name, null)?.let { unseal(name, it) }
        val copy = backup.getString(name, null)
        if (sealed != null) {
            if (copy != sealed) backup.edit { putString(name, sealed) } // secrets stored before the copy existed
            return sealed
        }
        if (copy.isNullOrEmpty()) return ""
        // restored from the backup: seal it with this phone's key
        runCatching { prefs.edit { putString(name, seal(copy)) } }.onFailure { Log.w(TAG, "Cannot seal $name", it) }
        return copy
    }

    fun has(name: String): Boolean = prefs.contains(name) || backup.contains(name)

    @Synchronized
    fun set(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit { remove(name) }
            backup.edit { remove(name) }
        } else {
            prefs.edit { putString(name, seal(value)) }
            backup.edit { putString(name, value) }
        }
        _version.value++
    }

    private fun unseal(name: String, stored: String): String? = try {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot decrypt $name", e)
        null
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
        return Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val TELEGRAF_TOKEN = "telegraf_token"
        const val TELEGRAF_PASSWORD = "telegraf_password"

        /** Prefs file with the copy that Android's backup keeps (see backup_rules.xml). */
        const val BACKUP_PREFS = "secrets_backup"

        private const val TAG = "SecretStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "bimmerdata_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: SecretStore? = null

        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) { instance ?: SecretStore(context).also { instance = it } }
    }
}
