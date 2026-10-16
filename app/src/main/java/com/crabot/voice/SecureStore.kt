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
                val version = state.optInt("storage_version")
                if (version in 1..2 && validState()) {
                    val backup = File(context.filesDir, "terminal.enc.v$version.bak")
                    val original = file.readFully()
                    try {
                        if (!backup.exists()) replaceAtomic(backup, original)
                        require(backup.readBytes().contentEquals(original)) { "配对迁移备份校验失败" }
                        persist(JSONObject(state.toString()).put("storage_version", 3))
                    } finally { original.fill(0) }
                }
            } catch (_: Exception) {
                loadFailed = true // Preserve the original encrypted file; clearing is an explicit user action.
            }
        }
    }
    val needsRepair get() = state.optInt("protocol_version") == 2
    val compatible get() = !loadFailed && (state.length() == 0 || validState())
    private fun validState(): Boolean = try {
        val version = state.optInt("storage_version")
        val oldFields = setOf("storage_version", "pairing", "vectors", "catalog", "pending_management", "pending_completion")
        val allowed = when {
            needsRepair -> setOf("protocol_version", "pairing", "vectors")
            version == 1 -> setOf("storage_version", "pairing", "vectors")
            version == 2 -> oldFields
            version == 3 -> oldFields + setOf("owner", "owner_vector", "owner_activation", "pending_owner_saved")
            else -> error("不支持的配对存储版本")
        }
        require(state.keys().asSequence().all { it in allowed })
        val pairing = state.optJSONObject("pairing")
        if (pairing == null) {
            require(version == 3 && state.has("owner") && state.has("owner_vector") && !state.has("owner_activation") && !state.has("pending_owner_saved"))
            require(listOf("catalog", "pending_management", "pending_completion").none { state.has(it) })
            require((state.optJSONObject("vectors")?.length() ?: 0) == 0)
        } else {
            require(pairing.getString("channel_id").isNotBlank() && pairing.getString("credential").isNotBlank())
            if (needsRepair) {
                val old = java.net.URI(pairing.getString("url"))
                require(old.scheme == "wss" && old.host != null && old.path == "/voice" && old.userInfo == null && old.query == null && old.fragment == null)
                require(pairing.getString("tls_fingerprint_sha256").matches(Regex("[0-9a-f]{64}")))
            } else {
                Wire.requirePairingVersion(pairing.getInt("protocol_version"), saved = true)
                TerminalEndpoint.parse(pairing.getString("url"), pairing.getString("channel_id"))
                if (pairing.has("tls_fingerprint_sha256")) require(pairing.getString("tls_fingerprint_sha256").matches(Regex("[0-9a-f]{64}")))
            }
        }
        if (state.has("owner")) DeviceOwner.validate(state.getJSONObject("owner"))
        if (state.has("owner_vector")) {
            require(state.has("owner"))
            validateOwnerVector(state.getJSONObject("owner_vector"))
        }
        if (state.has("owner_activation")) {
            val activation = state.getJSONObject("owner_activation")
            DeviceOwner.validateActivation(activation)
            require(pairing != null && DeviceOwner.sameOwner(state.getJSONObject("owner"), activation.getJSONObject("owner")))
            if (activation.getString("status") == "pending") {
                require(state.has("pending_owner_saved"))
            } else require(!state.has("pending_owner_saved") && !state.has("owner_vector"))
        }
        if (state.has("pending_owner_saved")) {
            val activation = state.getJSONObject("owner_activation")
            require(activation.getString("status") == "pending")
            DeviceOwner.validateSaved(state.getJSONObject("pending_owner_saved"), activation)
        }
        val vectors = state.optJSONObject("vectors") ?: JSONObject()
        vectors.keys().forEach { id ->
            val vector = vectors.getJSONObject(id)
            require(vector.getString("model_id") == Models.EMBEDDING)
            val values = vector.getJSONArray("values")
            require(values.length() > 0)
            for (i in 0 until values.length()) require(values.getDouble(i).isFinite())
        }
        if (state.has("catalog")) VoiceCatalog.validate(state.getJSONObject("catalog"))
        if (state.has("pending_management")) state.getJSONObject("pending_management").let { requests -> requests.keys().forEach { id -> val request = requests.getJSONObject(id); VoiceCatalog.validateRequest(request); require(request.getString("request_id") == id) } }
        if (state.has("pending_completion")) validateCompletion(state.getJSONObject("pending_completion"))
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
    private fun validateOwnerVector(vector: JSONObject) {
        require(vector.keys().asSequence().toSet() == setOf("model_id", "values") && vector.getString("model_id") == Models.EMBEDDING)
        val values = vector.getJSONArray("values")
        require(values.length() > 0)
        val samples = DoubleArray(values.length()) { values.getDouble(it) }
        require(samples.all { it.isFinite() && it.toFloat().isFinite() } && samples.sumOf { it * it } > 0) { "所有者声纹特征无效" }
    }
    @Synchronized fun owner(): JSONObject? = state.optJSONObject("owner")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun ownerActivation(): JSONObject? = state.optJSONObject("owner_activation")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun pendingOwnerSaved(): JSONObject? = state.optJSONObject("pending_owner_saved")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun saveLocalOwner(name: String, vector: FloatArray) {
        check(compatible && !needsRepair && !state.has("owner") && !state.has("pairing")) { "已有所有者或配对，不能重新认主" }
        val owner = DeviceOwner.create(name)
        val sample = JSONObject().put("model_id", Models.EMBEDDING).put("values", org.json.JSONArray(vector.toList()))
        validateOwnerVector(sample)
        persist(JSONObject(state.toString()).put("storage_version", 3).put("owner", owner).put("owner_vector", sample))
    }
    @Synchronized fun saveOwnerPairing(pairing: JSONObject, activation: JSONObject) {
        checkChannel(pairing.getString("channel_id")); DeviceOwner.validateActivation(activation)
        require(activation.getString("status") == "pending" && DeviceOwner.sameOwner(owner() ?: error("请先登记本人声纹"), activation.getJSONObject("owner")))
        val previous = ownerActivation()
        require(previous == null || previous.getString("status") == "pending" && DeviceOwner.sameActivation(previous, activation)) { "不能替换原所有者关联操作" }
        require(pairing.getInt("protocol_version") == Wire.PROTOCOL_VERSION && pairing.getString("credential").isNotBlank())
        TerminalEndpoint.parse(pairing.getString("url"), pairing.getString("channel_id"))
        val id = activation.getString("registration_id")
        val sample = state.optJSONObject("owner_vector") ?: vectors().getJSONObject(id)
        validateOwnerVector(sample)
        val vectors = vectors(); vectors.put(id, JSONObject(sample.toString()))
        persist(JSONObject(state.toString()).put("storage_version", 3).put("pairing", JSONObject(pairing.toString())).put("vectors", vectors)
            .put("owner_activation", JSONObject(activation.toString())).put("pending_owner_saved", DeviceOwner.saved(activation)).apply { remove("owner_vector") })
    }
    @Synchronized fun adoptOwner(activation: JSONObject, dimension: Int) {
        check(compatible && !needsRepair && pairing() != null && owner() == null) { "只能恢复既有设备的所有者引用" }
        DeviceOwner.validateActivation(activation)
        val catalog = catalog() ?: error("请先同步后台目录")
        val registrations = catalog.getJSONArray("registrations")
        val registration = (0 until registrations.length()).map { registrations.getJSONObject(it) }.singleOrNull {
            it.getString("platform_user_id") == activation.getString("platform_user_id") &&
                it.getString("status") == "active" && it.getString("model_id") == Models.EMBEDDING
        } ?: error("后台所有者没有兼容有效登记，请在 Admin 恢复")
        val sample = vectors().getJSONObject(registration.getString("registration_id"))
        validateOwnerVector(sample)
        require(dimension > 0 && sample.getJSONArray("values").length() == dimension) { "本机所有者声纹模型不兼容，请在 Admin 恢复" }
        val next = JSONObject(state.toString()).put("storage_version", 3).put("owner", JSONObject(activation.getJSONObject("owner").toString()))
            .put("owner_activation", JSONObject(activation.toString()))
        if (activation.getString("status") == "pending") next.put("pending_owner_saved", DeviceOwner.saved(activation))
        persist(next)
    }
    @Synchronized fun completeOwnerActivation(activation: JSONObject) {
        check(compatible && !needsRepair); DeviceOwner.validateActivation(activation)
        val previous = ownerActivation() ?: error("没有待完成的所有者关联")
        require(activation.getString("status") == "completed" && DeviceOwner.sameActivation(previous, activation)) { "所有者关联结果与原操作不符" }
        if (previous.getString("status") == "completed") return
        persist(JSONObject(state.toString()).put("owner_activation", JSONObject(activation.toString())).apply { remove("pending_owner_saved"); remove("owner_vector") })
    }
    @Synchronized fun pairing(): JSONObject? = state.optJSONObject("pairing")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun savePairing(pairing: JSONObject) {
        checkChannel(pairing.getString("channel_id"))
        require(pairing.getInt("protocol_version") == Wire.PROTOCOL_VERSION && pairing.getString("credential").isNotBlank())
        TerminalEndpoint.parse(pairing.getString("url"), pairing.getString("channel_id"))
        val next = JSONObject(state.toString()).apply { remove("protocol_version"); put("storage_version", 3); put("pairing", JSONObject(pairing.toString())) }
        persist(next)
    }
    @Synchronized fun vectors(): JSONObject = state.optJSONObject("vectors")?.let { JSONObject(it.toString()) } ?: JSONObject()
    @Synchronized fun putVector(id: String, vector: FloatArray) {
        check(compatible && !needsRepair) { "请先重新扫码，保留同渠道声纹后再登记" }
        val vectors = vectors()
        vectors.put(id, JSONObject().put("model_id", Models.EMBEDDING).put("values", org.json.JSONArray(vector.toList())))
        persist(JSONObject(state.toString()).put("storage_version", 3).put("vectors", vectors))
    }
    @Synchronized fun hasVector(id: String): Boolean = state.optJSONObject("vectors")?.has(id) == true
    @Synchronized fun catalog(): JSONObject? = state.optJSONObject("catalog")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    @Synchronized fun pendingRequests(): JSONObject = state.optJSONObject("pending_management")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) } ?: JSONObject()
    @Synchronized fun rememberRequest(request: JSONObject) {
        check(compatible && !needsRepair)
        VoiceCatalog.validateRequest(request)
        val requests = pendingRequests(); requests.put(request.getString("request_id"), JSONObject(request.toString()))
        persist(JSONObject(state.toString()).put("pending_management", requests))
    }
    @Synchronized fun resolveRequest(id: String) {
        val requests = pendingRequests(); if (!requests.has(id)) return
        requests.remove(id); persist(JSONObject(state.toString()).put("pending_management", requests))
    }
    @Synchronized fun pendingCompletion(): JSONObject? = state.optJSONObject("pending_completion")?.takeIf { compatible && !needsRepair }?.let { JSONObject(it.toString()) }
    private fun validateCompletion(completion: JSONObject) {
        val allowed = setOf("registration_id", "model_id", "enrollment_revision", "request_id")
        require(completion.keys().asSequence().all { it in allowed })
        require(completion.getString("registration_id") == java.util.UUID.fromString(completion.getString("registration_id")).toString())
        require(completion.getString("model_id") == Models.EMBEDDING); VoiceCatalog.revision(completion)
        if (completion.has("request_id")) require(completion.getString("request_id") == java.util.UUID.fromString(completion.getString("request_id")).toString())
    }
    @Synchronized fun saveEnrollment(id: String, vector: FloatArray, completion: JSONObject) {
        check(compatible && !needsRepair)
        validateCompletion(completion); require(completion.getString("registration_id") == id && vector.isNotEmpty() && vector.all { it.isFinite() })
        val vectors = vectors(); vectors.put(id, JSONObject().put("model_id", Models.EMBEDDING).put("values", org.json.JSONArray(vector.toList())))
        persist(JSONObject(state.toString()).put("vectors", vectors).put("pending_completion", JSONObject(completion.toString())))
    }
    @Synchronized fun resolveCompletion() {
        if (state.has("pending_completion")) persist(JSONObject(state.toString()).apply { remove("pending_completion") })
    }
    @Synchronized fun applyCatalog(catalog: JSONObject): List<String> {
        check(compatible && !needsRepair); VoiceCatalog.validate(catalog)
        val previous = state.optJSONObject("catalog")
        if (previous != null && VoiceCatalog.revision(previous) > VoiceCatalog.revision(catalog)) return emptyList()
        val vectors = vectors(); val clears = mutableListOf<String>(); val records = catalog.getJSONArray("registrations")
        for (i in 0 until records.length()) {
            val registration = records.getJSONObject(i)
            if (registration.getString("status") == "revoked") {
                val id = registration.getString("registration_id"); vectors.remove(id)
                if (!registration.getBoolean("terminal_cleared")) clears.add(id)
            }
        }
        persist(JSONObject(state.toString()).put("catalog", JSONObject(catalog.toString())).put("vectors", vectors))
        return clears
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
