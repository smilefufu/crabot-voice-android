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
    private var loadFailed = false
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
                loadFailed = true // Preserve the original encrypted file; clearing is an explicit user action.
            }
        }
    }
    val needsRepair get() = state.optInt("protocol_version") == 2
    val compatible get() = !loadFailed && (state.length() == 0 || validState())
    private fun validState(): Boolean = try {
        val allowed = if (needsRepair) setOf("protocol_version", "pairing", "vectors") else setOf("storage_version", "pairing", "vectors")
        require(state.keys().asSequence().all { it in allowed })
        val pairing = state.getJSONObject("pairing")
        require(pairing.getString("channel_id").isNotBlank() && pairing.getString("credential").isNotBlank())
        if (needsRepair) {
            val old = java.net.URI(pairing.getString("url"))
            require(old.scheme == "wss" && old.host != null && old.path == "/voice" && old.userInfo == null && old.query == null && old.fragment == null)
            require(pairing.getString("tls_fingerprint_sha256").matches(Regex("[0-9a-f]{64}")))
        } else {
            require(state.getInt("storage_version") == 1)
            Wire.requirePairingVersion(pairing.getInt("protocol_version"), saved = true)
            TerminalEndpoint.parse(pairing.getString("url"), pairing.getString("channel_id"))
            if (pairing.has("tls_fingerprint_sha256")) require(pairing.getString("tls_fingerprint_sha256").matches(Regex("[0-9a-f]{64}")))
        }
        val vectors = state.optJSONObject("vectors") ?: JSONObject()
        vectors.keys().forEach { id ->
            val vector = vectors.getJSONObject(id)
            require(vector.getString("model_id") == Models.EMBEDDING)
            val values = vector.getJSONArray("values")
            require(values.length() > 0)
            for (i in 0 until values.length()) require(values.getDouble(i).isFinite())
        }
        true
    } catch (_: Exception) { false }
    @Synchronized fun checkChannel(channelId: String) {
        check(compatible) { "配对数据无法读取，原文件保留；请明确清除本机数据后再配对" }
        val previous = state.optJSONObject("pairing")?.getString("channel_id")
        check(previous == null || previous == channelId) { "当前声纹属于另一渠道；请先明确清除本机配对与登记" }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun pairing(): JSONObject? = state.optJSONObject("pairing")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun savePairing(pairing: JSONObject) {
        checkChannel(pairing.getString("channel_id"))
        require(pairing.getInt("protocol_version") == Wire.PROTOCOL_VERSION && pairing.getString("credential").isNotBlank())
        TerminalEndpoint.parse(pairing.getString("url"), pairing.getString("channel_id"))
        val next = JSONObject(state.toString()).apply { remove("protocol_version"); put("storage_version", 1); put("pairing", JSONObject(pairing.toString())) }
        persist(next)
    }
    @Synchronized fun vectors(): JSONObject = state.optJSONObject("vectors")?.let { JSONObject(it.toString()) } ?: JSONObject()
    @Synchronized fun putVector(id: String, vector: FloatArray) {
        check(compatible && !needsRepair) { "请先重新扫码，保留同渠道声纹后再登记" }
        val vectors = vectors()
        vectors.put(id, JSONObject().put("model_id", Models.EMBEDDING).put("values", org.json.JSONArray(vector.toList())))
        persist(JSONObject(state.toString()).put("storage_version", 1).put("vectors", vectors))
    }
    @Synchronized fun clearVector(id: String) {
        check(compatible && !needsRepair)
        val vectors = vectors(); vectors.remove(id)
        persist(JSONObject(state.toString()).put("vectors", vectors))
    }
    @Synchronized fun forget() { file.delete(); state = JSONObject(); loadFailed = false }
    private fun persist(next: JSONObject) {
        val plain = next.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        try { replaceAtomic(file.baseFile, encrypted); state = next }
        finally { encrypted.fill(0) }
    }
}
