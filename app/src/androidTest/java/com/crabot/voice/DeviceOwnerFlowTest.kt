package com.crabot.voice

import android.content.ContextWrapper
import android.os.Handler
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

// Isolated encrypted cache files and loopback fixtures. Never opens a microphone or uses human audio.
class DeviceOwnerFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            var done = false; main { done = check() }
            if (done) return
            Thread.sleep(30)
        }
        fail("Controller did not reach the expected state")
    }
    private fun fixture(work: (ContextWrapper, VoiceController) -> Unit) {
        val target = instrumentation.targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "owner-flow-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        lateinit var controller: VoiceController
        main { controller = VoiceController(context) {}; controller.resume() }
        try { await { controller.modelReady }; work(context, controller) }
        finally { main { controller.destroy() }; directory.deleteRecursively() }
    }
    private fun field(controller: VoiceController, name: String) = VoiceController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun cancelCountdown(controller: VoiceController) = (field(controller, "main").get(controller) as Handler).removeCallbacksAndMessages(null)
    private fun audio(name: String): ShortArray {
        val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = buffer.getInt(at + 4)
            if (String(bytes, at, 4) == "data") return ShortArray((size / 2).coerceIn(48000, 80000)) { if (it * 2 < size) buffer.getShort(at + 8 + it * 2) else 0 }
            at += 8 + size + size % 2
        }
        error("Public PCM fixture missing")
    }
    @Test fun threeLocalPublicClipsPersistOwnerWithoutPairingOrOpeningMicrophone() = fixture { context, controller ->
        main { controller.enrollOwner("Local Owner"); cancelCountdown(controller) }
        for (step in 1..3) {
            val samples = audio("fangjun-sr-$step.wav")
            main {
                assertEquals(step, controller.enrollmentStep)
                field(controller, "mode").set(controller, VoiceController.Mode.OWNER_RECORDING)
                field(controller, "count").set(controller, samples.size)
                samples.copyInto(field(controller, "pcm").get(controller) as ShortArray)
                field(controller, "hasSpeech").set(controller, true)
                VoiceController::class.java.getDeclaredMethod("finishCapture").apply { isAccessible = true }.invoke(controller)
            }
            samples.fill(0)
            await { controller.owner != null || controller.ownerNeedsRetry || controller.enrollmentStep > step || !controller.ownerEnrollmentActive }
            main { cancelCountdown(controller); assertFalse(controller.status, controller.ownerNeedsRetry) }
            if (step < 3) main { assertNull(controller.owner); assertTrue(controller.ownerEnrollmentActive) }
        }
        main {
            assertNotNull(controller.owner); assertFalse(controller.paired); assertFalse(controller.ownerEnrollmentActive)
            assertEquals("closed", controller.microphoneState); assertFalse(controller.wakeEnabled)
        }
        val restored = SecureStore(context)
        assertEquals("Local Owner", restored.owner()!!.getString("display_name")); assertNull(restored.pairing())
    }
    @Test fun backgroundDuringCountdownCancelsCandidateAndOldTimerCannotOpenMicrophone() = fixture { context, controller ->
        main { controller.enrollOwner("Cancelled Owner"); controller.pause() }
        Thread.sleep(3300)
        main {
            assertFalse(controller.ownerEnrollmentActive); assertNull(controller.owner)
            assertEquals("closed", controller.microphoneState); assertFalse(controller.wakeEnabled)
            controller.resume(); assertEquals(VoiceController.Mode.CLOSED, controller.mode)
        }
        assertNull(SecureStore(context).owner())
    }
    @Test fun pairingSavesBeforeReceiptAndCompletionNeverOpensMicrophoneOrSkipsSetup() = fixture { context, controller ->
        val local = SecureStore(context)
        local.saveLocalOwner("Owner Fixture", floatArrayOf(.1f, .2f))
        // Recreate the controller so it reads this isolated pre-pair owner.
        main { controller.destroy() }
        lateinit var phone: VoiceController
        main { phone = VoiceController(context) {}; phone.resume() }
        val server = MockWebServer()
        val messages = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable>()
        val peer = AtomicReference<WebSocket>()
        val operation = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("owner", local.owner())
            .put("platform_user_id", UUID.randomUUID().toString()).put("registration_id", UUID.randomUUID().toString()).put("status", "pending")
        fun catalog() = JSONObject().put("enrollment_revision", 0).put("contacts", JSONArray()).put("registrations", JSONArray()).put("pending_enrollment", JSONObject.NULL)
        fun listener(pairing: Boolean) = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val packet = JSONObject(text); val type = packet.getString("type"); messages += type
                    when (type) {
                        "pair" -> {
                            assertTrue(pairing); assertEquals(local.owner().toString(), packet.getJSONObject("owner").toString())
                            ws.send(JSONObject().put("type", "paired").put("credential", "fixture-credential").put("owner_activation", operation).toString())
                        }
                        "hello" -> {
                            assertFalse(pairing); val saved = SecureStore(context)
                            assertEquals("fixture-credential", saved.pairing()!!.getString("credential"))
                            assertTrue(saved.hasVector(operation.getString("registration_id"))); assertNotNull(saved.pendingOwnerSaved())
                            peer.set(ws)
                            ws.send(JSONObject().put("type", "hello").put("connection_epoch", "fixture-epoch").put("setup_status", "draft")
                                .put("config", JSONObject().put("asr", JSONObject().put("provider_id", "fixture").put("model_id", "asr")).put("tts", JSONObject().put("provider_id", "fixture").put("model_id", "tts")).put("wake_word", "你好螃蟹"))
                                .put("destinations", JSONArray()).put("catalog", catalog()).put("owner_activation", operation).toString())
                        }
                        "heartbeat" -> { assertEquals("closed", packet.getString("microphone")); ws.send("{\"type\":\"heartbeat\"}") }
                        "owner_saved" -> {
                            assertTrue(messages.indexOf("heartbeat") < messages.indexOf("owner_saved"))
                            assertEquals(operation.getString("operation_id"), packet.getJSONObject("saved").getString("operation_id"))
                        }
                        "catalog_applied" -> ws.send("{\"type\":\"catalog_confirmed\",\"enrollment_revision\":0}")
                        else -> error("Unexpected packet $type")
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { failure.set(AssertionError("No audio is allowed during activation")) }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener(true)))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener(false))); server.start()
        try {
            await { phone.modelReady }
            val qr = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture").put("pairing_id", "fixture-pair")
                .put("verification_code", "123456").put("nonce", "fixture-nonce").put("expires_at", Instant.now().plusSeconds(60).toString())
                .put("url", server.url("/ws/voice/fixture").toString().replace("http:", "ws:"))
            main { phone.scan(qr.toString()) }
            await { "owner_saved" in messages || failure.get() != null }
            failure.get()?.let { throw it }
            main { assertFalse(phone.readyForConversation); assertEquals("pending", phone.ownerActivation!!.getString("status")) }
            val complete = JSONObject(operation.toString()).put("status", "completed")
            peer.get().send(JSONObject().put("type", "owner_activation").put("activation", complete).toString())
            await { phone.ownerActivation?.getString("status") == "completed" }
            main { assertFalse(phone.readyForConversation); assertFalse(phone.wakeEnabled); assertEquals("closed", phone.microphoneState) }
            peer.get().send("{\"type\":\"setup_status\",\"status\":\"complete\"}")
            await { phone.readyForConversation }
            main { assertFalse(phone.wakeEnabled); assertEquals("closed", phone.microphoneState) }
            assertNull(SecureStore(context).pendingOwnerSaved()); assertNull(failure.get())
            assertFalse(messages.contains("turn_start"))
        } finally { main { phone.destroy() }; server.shutdown() }
    }
}
