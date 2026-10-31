package com.sari.subtitle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for optional translation-provider secrets. */
class ExtraApiKeyStore(context: Context, providerId: String) {
    private val id = providerId.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    private val prefs = context.applicationContext.getSharedPreferences("sari_${id}_secret", Context.MODE_PRIVATE)
    private val alias = "sari_${id}_aes_gcm_v1"
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
    @Synchronized fun save(value: String) {
        val text = value.trim(); require(text.isNotEmpty()) { "المفتاح فارغ" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey(true)) }
        val encrypted = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun read(): String {
        val encoded = prefs.getString("ciphertext", null) ?: return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { runCatching { clear() }; "" }
    }
    @Synchronized fun clear() { check(prefs.edit().remove("ciphertext").commit()) }
    private fun secretKey(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "مفتاح التشفير غير موجود" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
