package com.crabot.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "terminal.enc"))
    private val alias = "crabot.voice.terminal.v1"
    private var state = JSONObject()
    init {
        if (file.baseFile.exists()) {
            try {
                val blob = file.readFully()
                require(blob.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                val plain = cipher.doFinal(blob.copyOfRange(12, blob.size))
                try { state = JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0); blob.fill(0) }
            } catch (_: Exception) {
                file.delete() // Lost/invalid Keystore requires a new pairing; never fall back to plaintext.
            }
        }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun pairing(): JSONObject? = state.optJSONObject("pairing")?.let { JSONObject(it.toString()) }
    @Synchronized fun savePairing(pairing: JSONObject) { state.put("pairing", pairing); persist() }
    @Synchronized fun vectors(): JSONObject = state.optJSONObject("vectors")?.let { JSONObject(it.toString()) } ?: JSONObject()
    @Synchronized fun putVector(id: String, vector: FloatArray) {
        val vectors = vectors()
        vectors.put(id, JSONObject().put("model_id", Models.EMBEDDING).put("values", org.json.JSONArray(vector.toList())))
        state.put("vectors", vectors); persist()
    }
    @Synchronized fun clearVector(id: String) { val vectors = vectors(); vectors.remove(id); state.put("vectors", vectors); persist() }
    @Synchronized fun forget() { state = JSONObject(); file.delete() }
    private fun persist() {
        val plain = state.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        finally { encrypted.fill(0) }
    }
}
