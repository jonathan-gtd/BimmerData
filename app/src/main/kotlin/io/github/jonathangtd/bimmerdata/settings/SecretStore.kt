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
 * leaves the Android Keystore. The ciphertext lives in app-private SharedPreferences (not backed
 * up). If the key is lost (e.g. device restore), the secret reads as empty and must be typed again.
 */
class SecretStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes whenever a secret is written (observe to rebuild clients). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decrypt $name", e)
            ""
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    @Synchronized
    fun set(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit { remove(name) }
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
            val out = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit { putString(name, Base64.encodeToString(out, Base64.NO_WRAP)) }
        }
        _version.value++
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
