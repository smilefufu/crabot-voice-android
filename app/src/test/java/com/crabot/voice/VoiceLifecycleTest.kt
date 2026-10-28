package com.crabot.voice

import android.content.ContextWrapper
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

/** Real controller and Android event queue; fake device I/O, no JNI, network or retained user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceLifecycleTest {
    private class Capture : AudioCapture {
        var active = false
        var callback: ((ShortArray) -> Unit)? = null
        var starts = 0
        override fun start(frame: (ShortArray) -> Unit) {
            check(!active)
            active = true; starts++; callback = frame
        }
        override fun stop() { active = false }
    }
    private class Models : VoiceInputModels {
        var flags = 0
        var fail = false
        var failSegments = false
        override fun reset(keyword: String) = Unit
        override fun feed(samples: ShortArray, vad: Boolean, kws: Boolean): Int {
            check(!fail) { "fixture detector failed" }
            return flags
        }
        override fun dimension() = 2
        override fun embedding(samples: ShortArray) = floatArrayOf(1f, 0f)
        override fun segments(samples: ShortArray): FloatArray {
            check(!failSegments) { "fixture segmentation failed" }
            return floatArrayOf(0f, samples.size / 16000f, 0f)
        }
        override fun matches(vector: FloatArray, candidates: List<FloatArray>) = floatArrayOf(0f, 1f)
        override fun close() = Unit
    }
    private class Socket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("http://localhost/ws/voice/fixture").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += JSONObject(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private lateinit var phone: VoiceController
    private lateinit var capture: Capture
    private lateinit var models: Models
    private lateinit var socket: Socket
    private lateinit var directory: java.io.File
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(phone, name).set(phone, value)
    private fun call(name: String, vararg args: Any?) {
        val method = VoiceController::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true; method.invoke(phone, *args)
    }
    private fun control(type: String, id: String, reason: String? = null) {
        val value = JSONObject().put("type", type)
        if (type == "playback_cancel") value.put("playback_id", id) else value.put("turn_id", id)
        if (reason != null) value.put("reason", reason)
        if (type == "turn_status") value.put("status", "rejected")
        call("control", value)
    }
    @Before fun setup() {
        directory = Files.createTempDirectory("voice-lifecycle-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = directory
        }
        phone = VoiceController(context) {}
        // Cancel only fixture startup before dispatch; model loading/JNI have separate device tests.
        (field(phone, "scope").get(phone) as CoroutineScope).coroutineContext.cancelChildren()
        capture = Capture(); models = Models(); socket = Socket()
        set("mic", capture); set("models", models); set("keyword", "fixture")
        set("foreground", true); set("setupComplete", true); set("servicesConfigured", true)
        set("confirmedRevision", 0L); set("connectionEpoch", "fixture-epoch")
        val connection = field(phone, "connection").get(phone) as TerminalConnection
        field(connection, "ready").setBoolean(connection, true)
        field(connection, "socket").set(connection, socket)
        val store = field(phone, "store").get(phone) as SecureStore
        field(store, "state").set(store, JSONObject().put("storage_version", 3).put("pairing", JSONObject()
            .put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture").put("credential", "test-only")
            .put("url", "ws://localhost/ws/voice/fixture")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(phone.readyForConversation)
        phone.toggleWake()
        assertWake()
    }
    @After fun cleanup() {
        phone.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(capture.active)
        directory.deleteRecursively()
    }
    private fun waiting(): String {
        capture.stop()
        val id = UUID.randomUUID().toString()
        set("currentTurn", id); set("mode", VoiceController.Mode.WAITING)
        return id
    }
    private fun prepareReply(turn: String, id: String) {
        val binding = JSONObject().put("channel_id", "fixture").put("session_id", "fixture").put("response_id", "result")
        call("control", JSONObject().put("type", "playback_prepare").put("playback_id", id)
            .put("context", JSONObject().put("kind", "reply").put("connection_epoch", "fixture-epoch")
                .put("turn_id", turn).put("binding", binding)))
    }
    private fun assertWake() {
        assertTrue(phone.wakeEnabled); assertEquals(VoiceController.Mode.WAKE, phone.mode)
        assertEquals("wake", phone.microphoneState); assertTrue(capture.active)
    }
    @Test fun emptyResponseReturnsToWakeAndLateEndCannotStopANewInput() {
        val old = waiting()
        control("response_end", old)
        assertWake()
        phone.recordButton()
        val current = field(phone, "currentTurn").get(phone)
        control("response_end", old)
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertEquals(current, field(phone, "currentTurn").get(phone)); assertTrue(capture.active)
    }
    @Test fun ttsCancellationReturnsToWakeWithoutOpeningFollowup() {
        val old = waiting()
        val playback = UUID.randomUUID().toString()
        set("reservedPlayback", playback)
        set("reservedContext", JSONObject().put("kind", "reply"))
        control("playback_cancel", playback)
        assertWake()
        val starts = capture.starts
        control("response_end", old)
        assertEquals(starts, capture.starts); assertWake()
    }
    @Test fun rejectedAsrTurnKeepsItsReasonAndReturnsToWake() {
        val old = waiting()
        control("turn_status", old, "ASR websocket failed")
        assertWake()
        assertTrue(phone.status.contains("ASR websocket failed"))
        control("response_end", old)
        assertTrue(phone.status.contains("ASR websocket failed")); assertWake()
    }
    @Test fun reservationExpiryReturnsToWakeWithoutInventingAReply() {
        val turn = waiting()
        val id = UUID.randomUUID().toString()
        prepareReply(turn, id)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertWake()
        assertTrue(phone.lastReply.isEmpty())
        assertTrue(socket.messages.any { it.optString("type") == "cancel" && it.optString("playback_id") == id })
    }
    @Test fun localPlaybackFailureRevokesStandbyRatherThanPretendingItIsStillEnabled() {
        waiting()
        val playback = UUID.randomUUID().toString()
        set("playbackId", playback); set("mode", VoiceController.Mode.PLAYING)
        call("playbackReceipt", playback, "failed", 0L)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        control("response_end", UUID.randomUUID().toString())
        assertFalse(capture.active)
    }
    @Test fun oldDetectorFailureCannotDisableANewCapture() {
        val old = capture.callback!!
        phone.recordButton()
        models.fail = true
        old(ShortArray(320))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertTrue(phone.wakeEnabled); assertTrue(capture.active)
    }
    @Test fun staleReservationTimerCannotStopNewRecording() {
        val old = waiting()
        val playback = UUID.randomUUID().toString()
        prepareReply(old, playback)
        control("playback_cancel", playback)
        phone.recordButton()
        control("response_end", old)
        control("playback_cancel", playback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(VoiceController.Mode.RECORDING, phone.mode); assertTrue(capture.active)
    }
    @Test fun thirtyControlledResponsesKeepStandbyButMuteAndBackgroundRevokeIt() {
        repeat(30) {
            val turn = waiting()
            val playback = UUID.randomUUID().toString()
            set("playbackId", playback); set("mode", VoiceController.Mode.PLAYING)
            call("playbackReceipt", playback, "completed", 16000L)
            control("response_end", turn)
            assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode); assertTrue(capture.active)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            assertWake()
        }
        phone.mute()
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        phone.toggleWake(); assertWake()
        phone.pause(); phone.resume()
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
    }
    @Test fun speechInsideFollowupIsNotCutOffByTheOldWindowDeadline() {
        val turn = waiting()
        set("interactionHadPlayback", true)
        control("response_end", turn)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        models.flags = 1
        capture.callback!!(ShortArray(320) { 100 })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        val current = field(phone, "currentTurn").get(phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertEquals(current, field(phone, "currentTurn").get(phone)); assertTrue(capture.active)
    }
    @Test fun thirtyMixedOutcomesPreserveOnlyTheOriginalWakePermission() {
        repeat(30) { index ->
            val turn = waiting()
            when (index % 3) {
                0 -> control("response_end", turn)
                1 -> control("turn_status", turn, "ASR fixture failure")
                else -> {
                    val id = UUID.randomUUID().toString()
                    set("reservedPlayback", id); set("reservedContext", JSONObject().put("kind", "reply"))
                    control("playback_cancel", id)
                }
            }
            assertWake()
            assertFalse(socket.messages.any { it.optString("type") == "turn_start" })
        }
    }
    @Test fun expiredAnswerWaitReturnsToWakeWithoutCancellingTheBusinessTask() {
        val turn = waiting()
        call("expireWaitingTurn", turn)
        assertWake()
        assertTrue(phone.lastOutcome.contains("已接纳任务仍由 Crabot 负责"))
        assertTrue(socket.messages.filter { it.optString("type") == "cancel" }.all { it.keys().asSequence().toSet() == setOf("type", "turn_id") })
        phone.recordButton()
        call("expireWaitingTurn", turn)
        assertEquals(VoiceController.Mode.RECORDING, phone.mode); assertTrue(capture.active)
    }
    @Test fun invalidCatalogRevokesPermissionAndConfirmationDoesNotReopenIt() {
        val turn = waiting()
        set("confirmedRevision", -1L)
        control("response_end", turn)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        call("control", JSONObject().put("type", "catalog_confirmed").put("enrollment_revision", 0))
        assertTrue(phone.readyForConversation)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
    }
    @Test fun disconnectAndNewConnectionDoNotRestoreMicrophonePermission() {
        val turn = waiting()
        val connection = field(phone, "connection").get(phone) as TerminalConnection
        connection.disconnect("fixture disconnected")
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertNull(field(phone, "connectionEpoch").get(phone))
        field(connection, "ready").setBoolean(connection, true)
        field(connection, "socket").set(connection, socket)
        set("connectionEpoch", "new-epoch")
        call("control", JSONObject().put("type", "catalog_confirmed").put("enrollment_revision", 0))
        control("response_end", turn)
        assertTrue(phone.readyForConversation)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
    }
    @Test fun manualEmptyResponseDoesNotGrantWakeOrFollowupPermission() {
        phone.mute(); phone.recordButton()
        val turn = field(phone, "currentTurn").get(phone) as String
        capture.stop(); set("mode", VoiceController.Mode.WAITING)
        control("response_end", turn)
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
    }
    @Test fun aQueuedOldDetectorErrorCannotStopTheNextInput() {
        models.fail = true
        capture.callback!!(ShortArray(320))
        models.fail = false
        phone.recordButton()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertTrue(capture.active); assertTrue(phone.wakeEnabled)
    }
    @Test fun currentDetectorFailureRevokesPermission() {
        models.fail = true
        capture.callback!!(ShortArray(320))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        assertFalse(capture.active); assertFalse(phone.wakeEnabled)
    }
    @Test fun oldPlaybackFailureReceiptDoesNotStopNewRecording() {
        waiting()
        val playback = UUID.randomUUID().toString()
        set("reservedPlayback", playback); set("reservedContext", JSONObject().put("kind", "reply"))
        control("playback_cancel", playback)
        phone.recordButton()
        call("playbackReceipt", playback, "failed", 0L)
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertTrue(capture.active); assertTrue(phone.wakeEnabled)
    }
    @Test fun lateResponseAndCatalogAcknowledgementKeepTheActualFailureVisible() {
        val turn = waiting()
        control("turn_status", turn, "ASR fixture failure")
        val outcome = phone.lastOutcome
        call("control", JSONObject().put("type", "catalog_confirmed").put("enrollment_revision", 0))
        control("response_end", turn)
        assertTrue(outcome.contains("ASR fixture failure"))
        assertEquals(outcome, phone.lastOutcome); assertWake()
    }
    @Test fun notificationsRestoreTheOriginalFollowupDeadlineWithoutExtendingIt() {
        waiting()
        val until = SystemClock.elapsedRealtime() + 4000
        set("playbackReturnMode", VoiceController.Mode.FOLLOWUP); set("playbackReturnDeadline", until)
        call("restoreAfterNotification", false)
        assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode)
        assertEquals(until, field(phone, "deadline").getLong(phone)); assertTrue(capture.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertWake()
    }
    @Test fun protocolOrDirectoryErrorDoesNotAutomaticallyRecover() {
        val turn = waiting()
        call("control", JSONObject().put("type", "error").put("code", "VOICE_CATALOG_NOT_READY").put("message", "fixture directory invalid"))
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        control("response_end", turn)
        assertFalse(capture.active)
    }
    @Test fun cancelledManagementResultDoesNotGrantOrdinaryWakeRecovery() {
        waiting()
        val id = UUID.randomUUID().toString()
        set("reservedPlayback", id); set("reservedContext", JSONObject().put("kind", "notification"))
        set("guidedResultPlayback", true)
        control("playback_cancel", id)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
    }
    @Test fun missingLocalModelRevokesStandbyRatherThanRetryingIt() {
        val turn = waiting()
        set("models", null)
        control("response_end", turn)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
    }
    @Test fun localProcessingFailureDoesNotReuseServerFailureRecovery() {
        phone.recordButton()
        val turn = field(phone, "currentTurn").get(phone) as String
        call("control", JSONObject().put("type", "turn_ready").put("turn_id", turn))
        models.flags = 1
        repeat(30) { capture.callback!!(ShortArray(320) { 100 }); shadowOf(Looper.getMainLooper()).idle() }
        models.failSegments = true
        phone.recordButton()
        val until = System.nanoTime() + 2_000_000_000L
        while (phone.mode == VoiceController.Mode.PROCESSING && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        assertFalse(phone.wakeEnabled); assertFalse(capture.active)
        assertTrue(socket.messages.none { it.optString("type") == "turn_end" })
    }
}
