package com.crabot.voice

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

// Isolated cache, loopback and a fake capture device: never samples the user's microphone.
class VoiceStandbyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun field(phone: VoiceController, name: String) = VoiceController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false; main { done = check() }; if (done) return
            Thread.sleep(30)
        }
        fail("Expected standby state was not reached")
    }
    private class Capture : AudioCapture {
        var active = false
        var starts = 0
        override fun start(frame: (ShortArray) -> Unit) {
            check(!active) { "麦克风已经打开" }
            active = true; starts++
        }
        override fun stop() { active = false }
    }
    private fun fixture(work: (VoiceController, Capture) -> Unit) {
        val directory = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "voice-standby-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = directory }
        val server = MockWebServer()
        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val control = JSONObject(text)
                when (control.getString("type")) {
                    "hello" -> ws.send(JSONObject().put("type", "hello").put("connection_epoch", UUID.randomUUID().toString()).put("setup_status", "complete")
                        .put("config", JSONObject().put("wake_word", "你好螃蟹").put("asr", JSONObject().put("provider_id", "fixture").put("model_id", "asr"))
                            .put("tts", JSONObject().put("provider_id", "fixture").put("model_id", "tts")))
                        .put("owner_activation", JSONObject.NULL).put("destinations", JSONArray())
                        .put("catalog", JSONObject().put("enrollment_revision", 0).put("contacts", JSONArray()).put("registrations", JSONArray()).put("pending_enrollment", JSONObject.NULL)).toString())
                    "catalog_applied" -> ws.send("{\"type\":\"catalog_confirmed\",\"enrollment_revision\":0}")
                    "heartbeat" -> ws.send("{\"type\":\"heartbeat\"}")
                    "turn_start" -> ws.send(JSONObject().put("type", "turn_ready").put("turn_id", control.getString("turn_id")).toString())
                }
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        SecureStore(context).savePairing(JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture").put("credential", "isolated-credential")
            .put("url", server.url("/ws/voice/fixture").toString().replace("http:", "ws:")))
        val capture = Capture()
        lateinit var phone: VoiceController
        main { phone = VoiceController(context) {}; field(phone, "mic").set(phone, capture); phone.resume() }
        try {
            await { phone.modelReady && phone.readyForConversation }
            main { phone.toggleWake(); assertEquals(VoiceController.Mode.WAKE, phone.mode) }
            work(phone, capture)
        } finally { main { phone.destroy(); assertFalse(capture.active) }; server.shutdown(); directory.deleteRecursively() }
    }
    private fun responseEnded(phone: VoiceController, capture: Capture) {
        capture.stop()
        val turn = UUID.randomUUID().toString()
        field(phone, "currentTurn").set(phone, turn)
        field(phone, "mode").set(phone, VoiceController.Mode.WAITING)
        field(phone, "interactionHadPlayback").setBoolean(phone, true)
        VoiceController::class.java.getDeclaredMethod("control", JSONObject::class.java).apply { isAccessible = true }
            .invoke(phone, JSONObject().put("type", "response_end").put("turn_id", turn))
        assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode)
        assertTrue(capture.active)
    }
    @Test fun captureFrameAtFollowupDeadlineReturnsToWakeAcrossThreeResponses() = fixture { phone, capture ->
        main {
            repeat(3) {
                responseEnded(phone, capture)
                field(phone, "deadline").setLong(phone, SystemClock.elapsedRealtime() - 1)
                VoiceController::class.java.getDeclaredMethod("frame", ShortArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(phone, ShortArray(320), 0)
                assertEquals(VoiceController.Mode.WAKE, phone.mode)
                assertTrue(phone.wakeEnabled); assertTrue(capture.active)
            }
            assertEquals(7, capture.starts)
        }
    }
    @Test fun timerReturnsToWakeAndMuteDoesNotAllowAnOldFrameToReopen() = fixture { phone, capture ->
        main { responseEnded(phone, capture) }
        await { phone.mode == VoiceController.Mode.WAKE }
        main {
            assertTrue(capture.active); assertTrue(phone.wakeEnabled)
            phone.mute()
            VoiceController::class.java.getDeclaredMethod("frame", ShortArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(phone, ShortArray(320), 2)
            assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(capture.active); assertFalse(phone.wakeEnabled)
        }
    }
    @Test fun returningFromBackgroundKeepsMicrophoneClosed() = fixture { phone, capture ->
        main {
            responseEnded(phone, capture)
            phone.pause(); phone.resume()
            assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        }
    }
    @Test fun notificationOutlastingFollowupReturnsToPreviouslyEnabledWake() = fixture { phone, capture ->
        main {
            capture.stop()
            field(phone, "mode").set(phone, VoiceController.Mode.WAITING)
            field(phone, "playbackReturnMode").set(phone, VoiceController.Mode.FOLLOWUP)
            field(phone, "playbackReturnDeadline").setLong(phone, SystemClock.elapsedRealtime() - 1)
            VoiceController::class.java.getDeclaredMethod("restoreAfterNotification", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(phone, false)
            assertEquals(VoiceController.Mode.WAKE, phone.mode)
            assertTrue(phone.wakeEnabled); assertTrue(capture.active)
        }
    }
    @Test fun completedBlocksWaitForMatchingResponseEndAndDuplicateEndCannotRestartCapture() = fixture { phone, capture ->
        main {
            capture.stop()
            val turn = UUID.randomUUID().toString()
            field(phone, "currentTurn").set(phone, turn)
            repeat(2) {
                val playback = UUID.randomUUID().toString()
                field(phone, "playbackId").set(phone, playback)
                field(phone, "mode").set(phone, VoiceController.Mode.PLAYING)
                VoiceController::class.java.getDeclaredMethod("playbackReceipt", String::class.java, String::class.java, java.lang.Long::class.java).apply { isAccessible = true }
                    .invoke(phone, playback, "completed", 480L)
                assertEquals(VoiceController.Mode.WAITING, phone.mode)
                assertEquals("closed", phone.microphoneState); assertFalse(capture.active)
                assertEquals(1, capture.starts)
            }
            val control = VoiceController::class.java.getDeclaredMethod("control", JSONObject::class.java).apply { isAccessible = true }
            control.invoke(phone, JSONObject().put("type", "response_end").put("turn_id", UUID.randomUUID().toString()))
            assertEquals(VoiceController.Mode.WAITING, phone.mode); assertFalse(capture.active)
            repeat(2) { control.invoke(phone, JSONObject().put("type", "response_end").put("turn_id", turn)) }
            assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode); assertTrue(capture.active)
            assertEquals(2, capture.starts)
        }
    }
    @Test fun manualConversationDoesNotGrantPersistentWakeAfterFollowup() = fixture { phone, capture ->
        main {
            phone.mute(); phone.recordButton()
            assertFalse(phone.wakeEnabled); assertTrue(capture.active)
            capture.stop()
            val turn = field(phone, "currentTurn").get(phone) as String
            field(phone, "mode").set(phone, VoiceController.Mode.WAITING)
            field(phone, "interactionHadPlayback").setBoolean(phone, true)
            VoiceController::class.java.getDeclaredMethod("control", JSONObject::class.java).apply { isAccessible = true }
                .invoke(phone, JSONObject().put("type", "response_end").put("turn_id", turn))
            assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode)
            field(phone, "deadline").setLong(phone, SystemClock.elapsedRealtime() - 1)
            VoiceController::class.java.getDeclaredMethod("frame", ShortArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(phone, ShortArray(320), 0)
            assertEquals(VoiceController.Mode.CLOSED, phone.mode)
            assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        }
    }
    @Test fun wakeWithoutACommandReturnsToWakeRatherThanDisablingStandby() = fixture { phone, capture ->
        val frame = VoiceController::class.java.getDeclaredMethod("frame", ShortArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        main {
            frame.invoke(phone, ShortArray(320), 0)
            assertEquals(0, field(phone, "sentSamples").getInt(phone))
            frame.invoke(phone, ShortArray(320), 2)
            assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        }
        await { field(phone, "readyTurn").get(phone) == field(phone, "currentTurn").get(phone) }
        main {
            field(phone, "startedAt").setLong(phone, SystemClock.elapsedRealtime() - 3001)
            frame.invoke(phone, ShortArray(320), 0)
            assertEquals(VoiceController.Mode.WAKE, phone.mode)
            assertTrue(phone.wakeEnabled); assertTrue(capture.active)
            assertTrue(phone.status.contains("未检测到有效人声"))
        }
    }
    @Test fun disconnectThenSuccessfulReconnectKeepsMicrophoneClosed() = fixture { phone, capture ->
        main {
            (field(phone, "connection").get(phone) as TerminalConnection).disconnect("fixture disconnected")
            assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(capture.active)
            phone.pause(); phone.resume()
        }
        await { phone.readyForConversation }
        main {
            assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(phone.wakeEnabled)
            assertFalse(capture.active); assertEquals(1, capture.starts)
        }
    }
}
