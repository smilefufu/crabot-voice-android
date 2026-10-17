package com.crabot.voice

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

// Isolated encrypted cache + loopback. These lifecycle tests never open a microphone.
class GuidedEnrollmentLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun field(phone: VoiceController, name: String) = VoiceController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun await(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            var done = false; main { done = check() }; if (done) return
            Thread.sleep(30)
        }
        fail("Expected isolated phone state was not reached")
    }
    private fun fixture(work: (VoiceController, WebSocket, CopyOnWriteArrayList<String>, JSONObject) -> Unit) {
        val target = instrumentation.targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "guided-lifecycle-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val packets = CopyOnWriteArrayList<String>()
        val peer = AtomicReference<WebSocket>()
        val failure = AtomicReference<Throwable>()
        val server = MockWebServer()
        val epoch = UUID.randomUUID().toString()
        val task = JSONObject().put("enrollment_revision", 1).put("expires_at", Instant.now().plusSeconds(60).toString())
            .put("registration", JSONObject().put("registration_id", UUID.randomUUID().toString()).put("platform_user_id", UUID.randomUUID().toString())
                .put("display_name", "Synthetic guest").put("model_id", Models.EMBEDDING).put("status", "pending").put("terminal_cleared", false))
        val enrollment = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("request_id", "a".repeat(64)).put("connection_epoch", epoch).put("delivery", "origin").put("task", task)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val control = JSONObject(text)
                    val type = control.getString("type"); packets += type
                    when (type) {
                        "hello" -> {
                            peer.set(ws)
                            ws.send(JSONObject().put("type", "hello").put("connection_epoch", epoch).put("setup_status", "complete")
                                .put("config", JSONObject().put("wake_word", "你好螃蟹").put("asr", JSONObject().put("provider_id", "fixture").put("model_id", "asr")).put("tts", JSONObject().put("provider_id", "fixture").put("model_id", "tts")))
                                .put("owner_activation", JSONObject.NULL).put("destinations", JSONArray())
                                .put("catalog", JSONObject().put("enrollment_revision", 0).put("contacts", JSONArray()).put("registrations", JSONArray()).put("pending_enrollment", JSONObject.NULL)).toString())
                        }
                        "catalog_applied" -> ws.send("{\"type\":\"catalog_confirmed\",\"enrollment_revision\":0}")
                        "heartbeat" -> ws.send("{\"type\":\"heartbeat\"}")
                        "guided_prompt", "guided_cancel" -> Unit
                        else -> error("Unexpected business packet $type")
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { failure.set(AssertionError("Management samples must not be uploaded")) }
        })); server.start()
        val pairing = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture").put("credential", "isolated-credential")
            .put("url", server.url("/ws/voice/fixture").toString().replace("http:", "ws:"))
        SecureStore(context).savePairing(pairing)
        lateinit var phone: VoiceController
        main { phone = VoiceController(context) {}; phone.resume() }
        try {
            await { phone.modelReady && phone.readyForConversation && peer.get() != null }
            work(phone, peer.get(), packets, enrollment)
            failure.get()?.let { throw it }
            main {
                val mic = field(phone, "mic").get(phone)
                assertNull(Microphone::class.java.getDeclaredField("record").apply { isAccessible = true }.get(mic))
            }
            assertFalse(packets.contains("turn_start")); assertFalse(packets.contains("enrollment_done"))
        } finally { main { phone.destroy() }; server.shutdown(); directory.deleteRecursively() }
    }
    @Test fun foregroundGuidanceRequestsTheFirstPromptAutomaticallyButNeverOpensEarly() = fixture { phone, peer, packets, enrollment ->
        main { field(phone, "speechEnabled").setBoolean(phone, true); field(phone, "wakeEnabled").setBoolean(phone, true); field(phone, "mode").set(phone, VoiceController.Mode.WAKE) }
        peer.send(JSONObject().put("type", "guided_enroll").put("enrollment", enrollment).toString())
        await { packets.contains("guided_prompt") }
        main { assertTrue(phone.guidedEnrollmentActive); assertEquals("closed", phone.microphoneState); phone.pause() }
        Thread.sleep(1000)
        main { assertFalse(phone.guidedEnrollmentActive); assertFalse(phone.wakeEnabled); assertEquals("closed", phone.microphoneState) }
        assertEquals(1, packets.count { it == "guided_prompt" })
    }
    @Test fun ordinaryAdminEnrollmentKeepsItsManualConfirmation() = fixture { phone, peer, packets, enrollment ->
        peer.send(JSONObject().put("type", "enroll").put("task", enrollment.getJSONObject("task")).toString())
        await { phone.enrollmentName == "Synthetic guest" }
        main { assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(phone.guidedEnrollmentActive); assertEquals("closed", phone.microphoneState) }
        assertFalse(packets.contains("guided_prompt"))
    }
    @Test fun hostRecordBeforeActualPromptCompletionFailsClosed() = fixture { phone, peer, packets, enrollment ->
        main { field(phone, "speechEnabled").setBoolean(phone, true) }
        peer.send(JSONObject().put("type", "guided_enroll").put("enrollment", enrollment).toString())
        await { packets.contains("guided_prompt") }
        peer.send(JSONObject().put("type", "guided_record").put("operation_id", enrollment.getString("operation_id")).put("step", 1).put("playback_id", UUID.randomUUID().toString()).toString())
        await { !phone.guidedEnrollmentActive }
        main { assertEquals("closed", phone.microphoneState); assertFalse(phone.wakeEnabled) }
    }
    @Test fun completedEnrollmentAndUnrelatedNotificationDoNotRestoreTheMicrophone() = fixture { phone, peer, packets, enrollment ->
        main { field(phone, "speechEnabled").setBoolean(phone, true); field(phone, "wakeEnabled").setBoolean(phone, true); field(phone, "mode").set(phone, VoiceController.Mode.WAKE) }
        peer.send(JSONObject().put("type", "guided_enroll").put("enrollment", enrollment).toString())
        await { packets.contains("guided_prompt") }
        main {
            val session = field(phone, "guided").get(phone) as GuidedEnrollment
            for (step in 1..3) {
                val id = UUID.randomUUID().toString()
                session.prompt(step, id, 0); session.ended(id, 480); session.played(id, 480)
                session.record(id, step, session.epoch, true, true, 0)
            }
            session.saved()
        }
        peer.send(JSONObject().put("type", "guided_end").put("operation_id", enrollment.getString("operation_id")).put("status", "completed").put("message", "Synthetic completion").toString())
        await { !phone.guidedEnrollmentActive }
        main {
            assertTrue(field(phone, "guidedAwaitingResult").getBoolean(phone))
            VoiceController::class.java.getDeclaredMethod("restoreAfterNotification", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(phone, false)
            assertTrue(field(phone, "guidedAwaitingResult").getBoolean(phone)); assertEquals("closed", phone.microphoneState)
            phone.pause()
            assertFalse(field(phone, "guidedAwaitingResult").getBoolean(phone))
        }
    }
}
