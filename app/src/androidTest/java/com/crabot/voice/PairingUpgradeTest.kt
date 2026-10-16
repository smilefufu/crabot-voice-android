package com.crabot.voice

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.Cipher
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Isolated cache directory only. Never writes the user's terminal.enc or deletes a key.
class PairingUpgradeTest {
    @Test fun savedV3MetadataReconnectsWithV6WireHandshake() {
        val server = MockWebServer()
        val hello = AtomicReference<JSONObject>()
        val connected = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                hello.set(JSONObject(text)); socket.send("{\"type\":\"hello\"}")
            }
        }))
        server.start()
        val connection = TerminalConnection({ connected.countDown() }, {}, {})
        try {
            val saved = JSONObject().put("protocol_version", 3).put("channel_id", "fixture")
                .put("url", server.url("/ws/voice/fixture").toString().replace("http://", "ws://"))
                .put("credential", "isolated-fixture")
            connection.connect(saved, JSONObject().put("type", "hello").put("protocol_version", Wire.PROTOCOL_VERSION).put("credential", saved.getString("credential")))
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            assertTrue(connection.ready)
            assertEquals(6, hello.get().getInt("protocol_version"))
            assertEquals("isolated-fixture", hello.get().getString("credential"))
            assertEquals(3, saved.getInt("protocol_version"))
        } finally { connection.disconnect(null); server.shutdown() }
    }

    @Test fun validV3PairingAndVectorsMigrateWithOriginalCiphertextBackup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "pairing-upgrade-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        try {
            val pairing = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION)
                .put("url", "ws://localhost:3000/ws/voice/fixture").put("channel_id", "fixture").put("credential", "isolated-fixture")
            SecureStore(context).savePairing(pairing) // Ensure the existing application key is available.
            pairing.put("protocol_version", 3)
            val vectors = JSONObject().put("fixture-speaker", JSONObject().put("model_id", Models.EMBEDDING).put("values", JSONArray(listOf(.1, .2, .3))))
            val state = JSONObject().put("storage_version", 1).put("pairing", pairing).put("vectors", vectors)
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("crabot.voice.terminal.v1", null)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val blob = cipher.iv + cipher.doFinal(state.toString().toByteArray())
            val file = java.io.File(directory, "terminal.enc"); file.writeBytes(blob)
            val restored = SecureStore(context)
            assertTrue("Valid v3 pairing must survive a wire v5 upgrade", restored.compatible)
            assertFalse(restored.needsRepair)
            assertEquals("isolated-fixture", restored.pairing()!!.getString("credential"))
            assertEquals(3, restored.pairing()!!.getInt("protocol_version"))
            assertEquals(vectors.toString(), restored.vectors().toString())
            assertArrayEquals(blob, java.io.File(directory, "terminal.enc.v1.bak").readBytes())
            assertFalse(blob.contentEquals(file.readBytes()))
        } finally { directory.deleteRecursively() }
    }
}
