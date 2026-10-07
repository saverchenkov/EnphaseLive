package com.saver.enphaselive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted with a non-exportable Android Keystore key; never included in the APK. */
class TokenStore(private val context: Context) {
    private val path get() = File(context.noBackupFilesDir, "gateway.enc")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gateway", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("gateway", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(input: String) {
        val token = if (input.trim().startsWith("{")) JSONObject(input).getString("token") else input.trim()
        require(token.split('.').size == 3) { "Enter an Enphase token or its JSON response" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray())
        path.writeText(JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString())
    }
    fun read(): String? {
        val imported = File(context.filesDir, "gateway.token")
        if (imported.exists()) { save(imported.readText()); imported.delete() }
        if (!path.exists()) return null
        val data = JSONObject(path.readText())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(data.getString("data"), Base64.NO_WRAP)))
    }
}
