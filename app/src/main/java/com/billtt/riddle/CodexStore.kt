package com.billtt.riddle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted, outside Android backup and device transfer. */
class CodexStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "codex-session.enc")
    fun read(): JSONObject? = synchronized(LOCK) {
        if (!file.exists()) return@synchronized null
        try {
            val envelope = JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.getDecoder().decode(envelope.getString("iv"))))
            JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))), Charsets.UTF_8))
        } catch (_: Exception) {
            file.delete() // Keystore invalidation requires a new sign-in.
            null
        }
    }
    fun write(value: JSONObject) = synchronized(LOCK) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))))
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(envelope.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun clear() = synchronized(LOCK) { android.util.AtomicFile(file).delete() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "riddle.codex.session.v1"
        private val LOCK = Any()
    }
}
