package com.crabot.voice

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

// Own cache files only, no microphone, network, user data or key deletion.
class VoiceCatalogStorageTest {
    private fun fixture(work: (SecureStore, ContextWrapper) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "voice-catalog-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        try {
            val store = SecureStore(context)
            store.savePairing(JSONObject().put("protocol_version", 5).put("channel_id", "fixture").put("credential", "isolated-fixture").put("url", "ws://localhost:3000/ws/voice/fixture"))
            work(store, context)
        } finally { directory.deleteRecursively() }
    }
    private fun sample(id: String, contact: String, status: String) = JSONObject().put("registration_id", id).put("platform_user_id", contact).put("display_name", "Fixture").put("model_id", Models.EMBEDDING).put("status", status).put("terminal_cleared", false)
    @Test fun candidateCompletionSurvivesRestartAndReplacementClearsOnlyRevokedSample() = fixture { store, context ->
        val contact = UUID.randomUUID().toString(); val old = UUID.randomUUID().toString(); val fresh = UUID.randomUUID().toString()
        val request = JSONObject().put("request_id", UUID.randomUUID().toString()).put("expected_revision", 0).put("command", JSONObject().put("action", "enroll").put("platform_user_id", contact))
        store.rememberRequest(request); store.putVector(old, floatArrayOf(.1f, .2f))
        val candidate = sample(fresh, contact, "pending")
        val catalog = JSONObject().put("enrollment_revision", 1).put("contacts", JSONArray().put(JSONObject().put("platform_user_id", contact).put("display_name", "Fixture")))
            .put("registrations", JSONArray().put(sample(old, contact, "active")).put(candidate)).put("pending_enrollment", JSONObject().put("registration", candidate).put("enrollment_revision", 1).put("expires_at", Instant.now().plusSeconds(60).toString()))
        store.applyCatalog(catalog)
        val completion = JSONObject().put("registration_id", fresh).put("model_id", Models.EMBEDDING).put("enrollment_revision", 1).put("request_id", request.getString("request_id"))
        store.saveEnrollment(fresh, floatArrayOf(.3f, .4f), completion)
        val loaded = SecureStore(context)
        assertTrue(loaded.compatible); assertTrue(loaded.hasVector(old)); assertTrue(loaded.hasVector(fresh))
        assertEquals(completion.toString(), loaded.pendingCompletion()!!.toString())
        assertEquals(request.toString(), loaded.pendingRequests().getJSONObject(request.getString("request_id")).toString())
        catalog.put("enrollment_revision", 2).put("registrations", JSONArray().put(sample(old, contact, "revoked")).put(sample(fresh, contact, "active"))).put("pending_enrollment", JSONObject.NULL)
        assertEquals(listOf(old), loaded.applyCatalog(catalog))
        assertFalse(loaded.hasVector(old)); assertTrue(loaded.hasVector(fresh)); assertEquals(2L, loaded.catalog()!!.getLong("enrollment_revision"))
        val stale = JSONObject(catalog.toString()).put("enrollment_revision", 1)
        loaded.applyCatalog(stale); assertEquals(2L, loaded.catalog()!!.getLong("enrollment_revision"))
    }
    @Test fun rejectsInventedFriendFieldsAndActiveIdentityWithoutConfirmedContact() {
        val contact = UUID.randomUUID().toString()
        val catalog = JSONObject().put("enrollment_revision", 1).put("contacts", JSONArray()).put("registrations", JSONArray().put(sample(UUID.randomUUID().toString(), contact, "active"))).put("pending_enrollment", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { VoiceCatalog.validate(catalog) }
        catalog.put("contacts", JSONArray().put(JSONObject().put("platform_user_id", contact).put("display_name", "Fixture").put("friend_id", "master")))
        assertThrows(IllegalArgumentException::class.java) { VoiceCatalog.validate(catalog) }
    }
}
