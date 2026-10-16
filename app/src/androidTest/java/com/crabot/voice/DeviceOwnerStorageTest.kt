package com.crabot.voice

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher

// Isolated cache files only. No microphone, network, real terminal.enc or key deletion.
class DeviceOwnerStorageTest {
    private fun fixture(work: (SecureStore, ContextWrapper) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "device-owner-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        try { work(SecureStore(context), context) } finally { directory.deleteRecursively() }
    }
    private fun pairing() = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture")
        .put("credential", "isolated-owner-credential").put("url", "ws://localhost:3000/ws/voice/fixture")
    private fun activation(owner: JSONObject) = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("owner", owner)
        .put("platform_user_id", UUID.randomUUID().toString()).put("registration_id", UUID.randomUUID().toString()).put("status", "pending")

    @Test fun ownerCanPersistBeforePairingWithoutClaimingAContactOrPermission() = fixture { store, context ->
        store.saveLocalOwner("  Fixture Owner  ", floatArrayOf(.1f, .2f))
        val owner = store.owner()!!
        assertEquals("Fixture Owner", owner.getString("display_name"))
        assertEquals(setOf("profile_id", "display_name", "model_id"), owner.keys().asSequence().toSet())
        assertNull(store.pairing()); assertEquals(0, store.vectors().length()); assertNull(store.ownerActivation())
        val restored = SecureStore(context)
        assertTrue(restored.compatible); assertEquals(owner.toString(), restored.owner()!!.toString())
        assertFalse(String(File(context.filesDir, "terminal.enc").readBytes()).contains("Fixture Owner"))
        assertThrows(IllegalStateException::class.java) { restored.saveLocalOwner("Another person", floatArrayOf(.3f, .4f)) }
        assertEquals(owner.toString(), SecureStore(context).owner()!!.toString())
    }
    @Test fun pairingMapsTheOriginalSampleAndCompletionIsBackendConfirmed() = fixture { store, context ->
        store.saveLocalOwner("Owner", floatArrayOf(.1f, .2f))
        val operation = activation(store.owner()!!)
        store.saveOwnerPairing(pairing(), operation)
        val restored = SecureStore(context)
        val id = operation.getString("registration_id")
        assertTrue(restored.compatible); assertTrue(restored.hasVector(id))
        assertEquals("pending", restored.ownerActivation()!!.getString("status"))
        assertEquals(operation.getString("operation_id"), restored.pendingOwnerSaved()!!.getString("operation_id"))
        restored.saveOwnerPairing(pairing(), operation)
        assertEquals(1, restored.vectors().length())
        val completed = JSONObject(operation.toString()).put("status", "completed")
        restored.completeOwnerActivation(completed)
        val done = SecureStore(context)
        assertNull(done.pendingOwnerSaved()); assertEquals("completed", done.ownerActivation()!!.getString("status"))
        done.clearVector(id)
        val withoutSample = SecureStore(context)
        assertFalse(withoutSample.hasVector(id)); assertEquals(operation.getJSONObject("owner").getString("profile_id"), withoutSample.owner()!!.getString("profile_id"))
        assertEquals("completed", withoutSample.ownerActivation()!!.getString("status"))
    }
    @Test fun refusesChangedActivationAndKeepsOriginalCiphertext() = fixture { store, context ->
        store.saveLocalOwner("Owner", floatArrayOf(.1f, .2f))
        val operation = activation(store.owner()!!)
        store.saveOwnerPairing(pairing(), operation)
        val before = File(context.filesDir, "terminal.enc").readBytes()
        for (field in listOf("operation_id", "registration_id", "platform_user_id")) {
            val changed = JSONObject(operation.toString()).put(field, UUID.randomUUID().toString())
            assertThrows(IllegalArgumentException::class.java) { store.saveOwnerPairing(pairing(), changed) }
            assertThrows(IllegalArgumentException::class.java) { store.completeOwnerActivation(changed.put("status", "completed")) }
            assertArrayEquals(before, File(context.filesDir, "terminal.enc").readBytes())
        }
        val forged = JSONObject(operation.toString()).put("friend_id", "master")
        assertThrows(IllegalArgumentException::class.java) { store.saveOwnerPairing(pairing(), forged) }
        assertArrayEquals(before, File(context.filesDir, "terminal.enc").readBytes())
    }
    @Test fun legacyVersionTwoIsBackedUpWithoutGuessingOwnerFromFirstVector() = fixture { store, context ->
        store.savePairing(pairing())
        val state = JSONObject().put("storage_version", 2).put("pairing", pairing()).put("vectors", JSONObject()
            .put("first-fixture", JSONObject().put("model_id", Models.EMBEDDING).put("values", JSONArray(listOf(.1, .2)))))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("crabot.voice.terminal.v1", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val blob = cipher.iv + cipher.doFinal(state.toString().toByteArray())
        File(context.filesDir, "terminal.enc").writeBytes(blob)
        val loaded = SecureStore(context)
        assertTrue(loaded.compatible); assertNull(loaded.owner()); assertTrue(loaded.hasVector("first-fixture"))
        assertEquals("isolated-owner-credential", loaded.pairing()!!.getString("credential"))
        assertArrayEquals(blob, File(context.filesDir, "terminal.enc.v2.bak").readBytes())
    }
    @Test fun conflictingMigrationBackupFailsClosedAndPreservesTheOldFile() = fixture { store, context ->
        store.savePairing(pairing())
        val state = JSONObject().put("storage_version", 2).put("pairing", pairing()).put("vectors", JSONObject())
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("crabot.voice.terminal.v1", null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val blob = cipher.iv + cipher.doFinal(state.toString().toByteArray())
        val file = File(context.filesDir, "terminal.enc"); file.writeBytes(blob)
        File(context.filesDir, "terminal.enc.v2.bak").writeText("conflicting backup")
        val loaded = SecureStore(context)
        assertFalse(loaded.compatible); assertNull(loaded.pairing())
        assertArrayEquals(blob, file.readBytes())
        assertEquals("conflicting backup", File(context.filesDir, "terminal.enc.v2.bak").readText())
    }
    @Test fun legacyOwnerUsesTheBackendRegistrationRatherThanTheFirstStoredVoice() = fixture { store, context ->
        store.savePairing(pairing())
        store.putVector("not-the-owner", floatArrayOf(.3f, .4f))
        val operation = activation(DeviceOwner.create("Verified backend owner"))
        val id = operation.getString("registration_id")
        store.putVector(id, floatArrayOf(.1f, .2f))
        val registration = JSONObject().put("registration_id", id).put("platform_user_id", operation.getString("platform_user_id"))
            .put("display_name", "Owner").put("model_id", Models.EMBEDDING).put("status", "active").put("terminal_cleared", false)
        val catalog = JSONObject().put("enrollment_revision", 1).put("pending_enrollment", JSONObject.NULL)
            .put("contacts", JSONArray().put(JSONObject().put("platform_user_id", operation.getString("platform_user_id")).put("display_name", "Owner")))
            .put("registrations", JSONArray().put(registration))
        store.applyCatalog(catalog)
        val before = File(context.filesDir, "terminal.enc").readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.adoptOwner(operation, 3) }
        assertNull(store.owner()); assertArrayEquals(before, File(context.filesDir, "terminal.enc").readBytes())
        store.adoptOwner(operation, 2)
        val loaded = SecureStore(context)
        assertTrue(loaded.compatible); assertEquals(operation.getJSONObject("owner").toString(), loaded.owner()!!.toString())
        assertTrue(loaded.hasVector("not-the-owner")); assertTrue(loaded.hasVector(id))
        assertEquals(id, loaded.pendingOwnerSaved()!!.getString("registration_id"))
        assertEquals("isolated-owner-credential", loaded.pairing()!!.getString("credential"))
    }
    @Test fun legacyOwnerHistoryUsesCurrentActiveSampleAfterExplicitRerecording() = fixture { store, context ->
        store.savePairing(pairing())
        val operation = activation(DeviceOwner.create("Existing owner"))
        val historical = operation.getString("registration_id"); val current = UUID.randomUUID().toString()
        store.putVector(current, floatArrayOf(.1f, .2f))
        fun sample(id: String, status: String) = JSONObject().put("registration_id", id).put("platform_user_id", operation.getString("platform_user_id"))
            .put("display_name", "Owner").put("model_id", Models.EMBEDDING).put("status", status).put("terminal_cleared", false)
        store.applyCatalog(JSONObject().put("enrollment_revision", 2).put("pending_enrollment", JSONObject.NULL)
            .put("contacts", JSONArray().put(JSONObject().put("platform_user_id", operation.getString("platform_user_id")).put("display_name", "Owner")))
            .put("registrations", JSONArray().put(sample(historical, "revoked")).put(sample(current, "active"))))
        store.adoptOwner(operation, 2)
        val restored = SecureStore(context)
        assertTrue(restored.compatible); assertTrue(restored.hasVector(current)); assertFalse(restored.hasVector(historical))
        assertEquals(historical, restored.pendingOwnerSaved()!!.getString("registration_id"))
        assertEquals(operation.getJSONObject("owner").getString("profile_id"), restored.owner()!!.getString("profile_id"))
    }
    @Test fun laterSampleRevocationDoesNotInvalidateTheAlreadySavedOwnerReceipt() = fixture { store, context ->
        store.saveLocalOwner("Owner", floatArrayOf(.1f, .2f))
        val operation = activation(store.owner()!!); store.saveOwnerPairing(pairing(), operation)
        val id = operation.getString("registration_id")
        val sample = JSONObject().put("registration_id", id).put("platform_user_id", operation.getString("platform_user_id"))
            .put("display_name", "Owner").put("model_id", Models.EMBEDDING).put("status", "revoked").put("terminal_cleared", false)
        store.applyCatalog(JSONObject().put("enrollment_revision", 2).put("pending_enrollment", JSONObject.NULL)
            .put("contacts", JSONArray().put(JSONObject().put("platform_user_id", operation.getString("platform_user_id")).put("display_name", "Owner")))
            .put("registrations", JSONArray().put(sample)))
        val restored = SecureStore(context)
        assertTrue(restored.compatible); assertNotNull(restored.pendingOwnerSaved()); assertFalse(restored.hasVector(id))
        restored.completeOwnerActivation(JSONObject(operation.toString()).put("status", "completed"))
        assertEquals(operation.getJSONObject("owner").getString("profile_id"), restored.owner()!!.getString("profile_id"))
    }
    @Test fun invalidLocalFeaturesOrOwnerMetadataDoNotCreateAProfile() = fixture { store, context ->
        for (vector in listOf(floatArrayOf(), floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 1f))) {
            assertThrows(Exception::class.java) { store.saveLocalOwner("Owner", vector) }
            assertNull(store.owner()); assertFalse(File(context.filesDir, "terminal.enc").exists())
        }
        for (name in listOf("  ", "x".repeat(81), "Owner\nname")) {
            assertThrows(IllegalArgumentException::class.java) { store.saveLocalOwner(name, floatArrayOf(.1f, .2f)) }
            assertNull(store.owner())
        }
    }
    @Test fun failedSaveDoesNotCreateAnOwnerInMemoryOrReplaceOriginalData() = fixture { store, context ->
        val original = context.filesDir
        val moved = File(original.parentFile, original.name + "-moved")
        assertTrue(original.renameTo(moved)); original.writeText("block temporary writes")
        try {
            assertThrows(Exception::class.java) { store.saveLocalOwner("Owner", floatArrayOf(.1f, .2f)) }
            assertNull(store.owner()); assertTrue(store.compatible)
        } finally { original.delete(); assertTrue(moved.renameTo(original)) }
        assertNull(SecureStore(context).owner())
    }
}
