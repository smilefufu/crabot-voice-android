package com.crabot.voice

import org.json.JSONObject
import java.util.UUID

/** Metadata only; never turns a local profile or a display name into Crabot authority. */
object DeviceOwner {
    private fun fields(value: JSONObject, names: Set<String>) {
        require(value.keys().asSequence().toSet() == names) { "所有者信息字段不兼容" }
    }
    private fun text(value: JSONObject, field: String): String {
        require(value.get(field) is String) { "所有者信息格式无效" }
        return value.getString(field)
    }
    private fun uuid(value: JSONObject, field: String) {
        val id = text(value, field)
        require(UUID.fromString(id).toString() == id) { "所有者信息引用无效" }
    }
    fun create(name: String): JSONObject = JSONObject().put("profile_id", UUID.randomUUID().toString())
        .put("display_name", name.trim()).put("model_id", Models.EMBEDDING).also { validate(it) }
    fun validate(owner: JSONObject) {
        fields(owner, setOf("profile_id", "display_name", "model_id")); uuid(owner, "profile_id")
        val name = text(owner, "display_name")
        require(name == name.trim() && name.codePointCount(0, name.length) in 1..80 && name.none { Character.isISOControl(it) }) { "所有者名字需为 1–80 个字，不能包含控制字符" }
        require(text(owner, "model_id") == Models.EMBEDDING) { "所有者声纹模型不兼容" }
    }
    fun validateActivation(activation: JSONObject) {
        fields(activation, setOf("operation_id", "owner", "platform_user_id", "registration_id", "status"))
        for (field in listOf("operation_id", "platform_user_id", "registration_id")) uuid(activation, field)
        validate(activation.getJSONObject("owner"))
        require(text(activation, "status") in setOf("pending", "completed")) { "所有者关联状态无效" }
    }
    fun sameOwner(a: JSONObject, b: JSONObject): Boolean = listOf("profile_id", "display_name", "model_id").all { a.get(it) == b.get(it) }
    fun sameActivation(a: JSONObject, b: JSONObject): Boolean = sameOwner(a.getJSONObject("owner"), b.getJSONObject("owner")) &&
        listOf("operation_id", "platform_user_id", "registration_id").all { a.get(it) == b.get(it) }
    fun saved(activation: JSONObject): JSONObject {
        validateActivation(activation)
        return JSONObject().put("operation_id", activation.getString("operation_id")).put("profile_id", activation.getJSONObject("owner").getString("profile_id"))
            .put("registration_id", activation.getString("registration_id")).put("model_id", Models.EMBEDDING)
    }
    fun validateSaved(saved: JSONObject, activation: JSONObject) {
        fields(saved, setOf("operation_id", "profile_id", "registration_id", "model_id"))
        val expected = saved(activation)
        require(expected.keys().asSequence().all { expected.get(it) == saved.get(it) }) { "所有者保存回执与原操作不符" }
    }
}
