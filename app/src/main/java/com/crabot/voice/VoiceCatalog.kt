package com.crabot.voice

import org.json.JSONObject
import java.time.Instant

object VoiceCatalog {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun fields(value: JSONObject, required: Set<String>) {
        require(value.keys().asSequence().toSet() == required) { "声纹目录字段不兼容" }
    }
    fun revision(value: JSONObject, field: String = "enrollment_revision"): Long {
        val number = value.get(field)
        require(number is Number && number.toDouble() == number.toLong().toDouble() && number.toLong() in 0..9_007_199_254_740_991L)
        return number.toLong()
    }
    fun validate(value: JSONObject) {
        fields(value, setOf("enrollment_revision", "contacts", "registrations", "pending_enrollment")); revision(value)
        val contacts = value.getJSONArray("contacts")
        val registrations = value.getJSONArray("registrations")
        val ids = mutableSetOf<String>()
        for (i in 0 until contacts.length()) {
            val contact = contacts.getJSONObject(i); fields(contact, setOf("platform_user_id", "display_name"))
            require(uuid.matches(contact.getString("platform_user_id")) && ids.add(contact.getString("platform_user_id")))
            require(contact.getString("display_name").trim().isNotEmpty())
        }
        val samples = mutableSetOf<String>()
        for (i in 0 until registrations.length()) {
            val r = registrations.getJSONObject(i)
            fields(r, setOf("registration_id", "display_name", "platform_user_id", "model_id", "status", "terminal_cleared"))
            require(uuid.matches(r.getString("registration_id")) && samples.add(r.getString("registration_id")) && uuid.matches(r.getString("platform_user_id")))
            require(r.get("terminal_cleared") is Boolean && r.getString("status") in setOf("pending", "active", "revoked"))
            require(r.getString("model_id").isNotBlank() && r.getString("display_name").isNotBlank())
            if (r.getString("status") == "active") require(r.getString("platform_user_id") in ids)
        }
        if (!value.isNull("pending_enrollment")) {
            val task = value.getJSONObject("pending_enrollment")
            fields(task, setOf("registration", "enrollment_revision", "expires_at")); revision(task)
            require(Instant.parse(task.getString("expires_at")).toEpochMilli() > 0)
            val sample = task.getJSONObject("registration")
            fields(sample, setOf("registration_id", "display_name", "platform_user_id", "model_id", "status", "terminal_cleared"))
            require(sample.getString("status") == "pending" && sample.getString("registration_id") in samples)
            val actual = (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("registration_id") == sample.getString("registration_id") }!!
            require(actual.toString() == sample.toString() || sample.keys().asSequence().all { actual.get(it) == sample.get(it) })
        }
    }
    fun validateRequest(value: JSONObject) {
        fields(value, setOf("request_id", "expected_revision", "command"))
        require(uuid.matches(value.getString("request_id"))); revision(value, "expected_revision")
        val command = value.getJSONObject("command")
        when (command.getString("action")) {
            "enroll_new" -> fields(command, setOf("action", "display_name"))
            "enroll" -> fields(command, setOf("action", "platform_user_id"))
            "rename_contact" -> fields(command, setOf("action", "platform_user_id", "display_name"))
            "delete_registration", "cancel_enrollment" -> fields(command, setOf("action", "registration_id"))
            else -> error("未知声纹管理操作")
        }
        for (field in listOf("platform_user_id", "registration_id")) if (command.has(field)) require(uuid.matches(command.getString(field)))
        if (command.has("display_name")) require(command.getString("display_name").trim().length in 1..128)
    }
}
