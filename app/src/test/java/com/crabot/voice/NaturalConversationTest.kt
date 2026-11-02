package com.crabot.voice

import android.content.ContextWrapper
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Controller/event loop; device, endpoint and transport are controlled, never enabled by a capability flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NaturalConversationTest {
    private class Capture : AudioCapture {
        var active = false
        var frame: ((ShortArray) -> Unit)? = null
        override fun start(frame: (ShortArray) -> Unit) { check(!active); active = true; this.frame = frame }
        override fun stop() { active = false }
    }
    private class Models : VoiceInputModels {
        var flags = 0
        var identityGate: CountDownLatch? = null
        val identityStarted = CountDownLatch(1)
        override fun reset(keyword: String) = Unit
        override fun feed(samples: ShortArray, vad: Boolean, kws: Boolean) = flags
        override fun dimension() = 2
        override fun embedding(samples: ShortArray) = floatArrayOf(1f, 0f)
        override fun segments(samples: ShortArray): FloatArray {
            identityStarted.countDown(); identityGate?.let { check(it.await(3, TimeUnit.SECONDS)) }
            return floatArrayOf(0f, samples.size / 16000f, 0f)
        }
        override fun matches(vector: FloatArray, candidates: List<FloatArray>) = floatArrayOf(0f, 1f)
        override fun close() = Unit
    }
    private class Device : PlaybackDevice {
        @Volatile var pauses = 0
        @Volatile var plays = 0
        @Volatile var flushes = 0
        @Volatile var released = false
        @Volatile var writable = false
        @Volatile var consumed = 0L
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun flush() { flushes++ }
        override fun release() { released = true }
        override fun write(pcm: ShortArray, offset: Int, count: Int): Int {
            if (!writable) return 0
            consumed += count; return count
        }
        override fun head() = consumed
        override fun stamp(): AudioStamp? = null
        override fun routeIsValid() = true
        override fun routeIsConfirmed() = true
    }
    private class Input : ConversationInput {
        var active = false
        var starts = 0
        var token = 0L
        var frame: ((ConversationFrame) -> Unit)? = null
        var failure: ((String) -> Unit)? = null
        val devices = mutableListOf<Device>()
        override fun start(frame: (ConversationFrame) -> Unit, failure: (String) -> Unit) {
            check(!active); active = true; starts++; this.frame = frame; this.failure = failure
        }
        override fun resetTurn() = ++token
        override fun stop() { active = false }
        override fun close() { stop() }
        override fun playback(id: String, progress: (Long) -> Unit, receipt: (String, String, Long?) -> Unit): Playback =
            Playback(id, Device().also { devices += it }, progress, receipt = receipt)
        fun emit(candidate: Boolean = false, confirmed: Boolean = false, ended: Boolean = false, value: Short = 1, endpoint: Long = token) {
            check(active)
            val raw = ShortArray(320) { value }
            frame!!(ConversationFrame(raw, SpeechActivity(candidate, confirmed), ended, endpoint)); raw.fill(0)
        }
    }
    private class Socket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        val audio = mutableListOf<ByteArray>()
        override fun request() = Request.Builder().url("http://localhost/ws/voice/fixture").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += JSONObject(text); return true }
        override fun send(bytes: ByteString): Boolean { audio += bytes.toByteArray(); return true }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
    private lateinit var phone: VoiceController
    private lateinit var input: Input
    private lateinit var capture: Capture
    private lateinit var models: Models
    private lateinit var socket: Socket
    private lateinit var directory: java.io.File
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(phone, name).set(phone, value)
    private fun get(name: String) = field(phone, name).get(phone)
    private fun control(value: JSONObject) {
        val method = VoiceController::class.java.getDeclaredMethod("control", JSONObject::class.java).apply { isAccessible = true }
        method.invoke(phone, value)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun ready(id: String = get("currentTurn") as String) = control(JSONObject().put("type", "turn_ready").put("turn_id", id))
    @Before fun setup() {
        directory = Files.createTempDirectory("voice-natural-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = directory }
        input = Input(); phone = VoiceController(context, input) {}
        (field(phone, "scope").get(phone) as CoroutineScope).coroutineContext.cancelChildren()
        capture = Capture(); models = Models(); socket = Socket()
        set("mic", capture); set("models", models); set("keyword", "fixture")
        set("foreground", true); set("setupComplete", true); set("servicesConfigured", true)
        set("confirmedRevision", 0L); set("connectionEpoch", "fixture-epoch")
        val connection = get("connection") as TerminalConnection
        field(connection, "ready").setBoolean(connection, true); field(connection, "socket").set(connection, socket)
        val store = get("store") as SecureStore
        field(store, "state").set(store, JSONObject().put("storage_version", 3).put("pairing", JSONObject()
            .put("protocol_version", Wire.PROTOCOL_VERSION).put("channel_id", "fixture").put("credential", "test-only")
            .put("url", "ws://localhost/ws/voice/fixture")))
        idle(); phone.toggleWake()
        assertTrue(capture.active); assertFalse(input.active)
        models.flags = 2; capture.frame!!(ShortArray(320)); idle(); ready()
        assertTrue(input.active); assertFalse(capture.active); assertEquals(VoiceController.Mode.RECORDING, phone.mode)
    }
    @After fun cleanup() {
        models.identityGate?.countDown(); phone.destroy(); idle()
        assertFalse(input.active); assertFalse(capture.active)
        directory.deleteRecursively()
    }
    private fun waiting(): String {
        val id = get("currentTurn") as String
        set("mode", VoiceController.Mode.WAITING)
        return id
    }
    private fun playback(): Pair<String, String> {
        val turn = waiting(); val id = UUID.randomUUID().toString()
        control(JSONObject().put("type", "playback_prepare").put("playback_id", id).put("context", JSONObject()
            .put("kind", "reply").put("connection_epoch", "fixture-epoch").put("turn_id", turn)
            .put("binding", JSONObject().put("channel_id", "fixture").put("session_id", "fixture").put("response_id", "result"))))
        control(JSONObject().put("type", "playback_start").put("playback_id", id).put("turn_id", turn))
        assertTrue(phone.localConversationActive); assertEquals("wake", phone.microphoneState)
        return turn to id
    }
    @Test fun falseCandidateResumesTheSamePlayerAndNeverUploadsWaitingAudio() {
        val (_, id) = playback(); val original = get("player")
        input.emit(candidate = true); idle()
        val pauses = input.devices.single().pauses
        assertTrue(pauses > 0); assertEquals(0, input.devices.single().flushes)
        input.emit(); idle()
        assertSame(original, get("player")); assertEquals(id, get("playbackId"))
        assertEquals(0, input.devices.single().flushes)
        assertTrue(socket.audio.isEmpty()); assertFalse(socket.messages.any { it.optString("type") == "cancel" })
    }
    @Test fun confirmationKeepsContinuousCaptureAndSendsOnlyNewTurnsLeadingAudio() {
        val (oldTurn, oldPlayback) = playback()
        input.emit(candidate = true, value = 111); idle()
        assertTrue(socket.audio.isEmpty())
        input.emit(candidate = true, confirmed = true, value = 222); idle()
        val next = get("currentTurn") as String
        assertNotEquals(oldTurn, next); assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertEquals(1, input.starts); assertTrue(input.active); assertFalse(capture.active)
        val cancel = socket.messages.single { it.optString("type") == "cancel" }
        assertEquals(oldTurn, cancel.getString("turn_id")); assertEquals(oldPlayback, cancel.getString("playback_id"))
        assertTrue(socket.audio.isEmpty())
        ready(next)
        assertEquals(2, socket.audio.size)
        val actual = socket.audio.flatMap { bytes ->
            val samples = ByteBuffer.wrap(bytes, 21, bytes.size - 21).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            List(samples.remaining()) { samples.get() }
        }
        assertEquals(List(320) { 111.toShort() } + List(320) { 222.toShort() }, actual)
        control(JSONObject().put("type", "error").put("turn_id", oldTurn).put("code", "VOICE_REJECTED").put("message", "old ASR failed"))
        control(JSONObject().put("type", "playback_start").put("playback_id", oldPlayback).put("turn_id", oldTurn))
        control(JSONObject().put("type", "response_end").put("turn_id", oldTurn))
        control(JSONObject().put("type", "playback_cancel").put("playback_id", oldPlayback))
        control(JSONObject().put("type", "playback_text").put("playback_id", oldPlayback).put("sequence", 0).put("text", "late"))
        assertEquals(next, get("currentTurn")); assertEquals(VoiceController.Mode.RECORDING, phone.mode)
    }
    @Test fun thinkingPauseDoesNotSubmitAtEightHundredMsAndEndpointEndsOnlyThisInput() {
        input.emit(confirmed = true); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        input.emit(); idle()
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertFalse(socket.messages.any { it.optString("type") == "turn_end" })
        models.identityGate = CountDownLatch(1)
        input.emit(ended = true); idle()
        assertEquals(VoiceController.Mode.PROCESSING, phone.mode)
        assertTrue(input.active); assertTrue(models.identityStarted.await(1, TimeUnit.SECONDS))
        val old = get("currentTurn") as String
        input.emit(candidate = true, confirmed = true); idle()
        assertNotEquals(old, get("currentTurn")); assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        models.identityGate!!.countDown()
    }
    @Test fun oldEndpointResultCannotFinishTheNewUtterance() {
        playback(); val oldToken = input.token
        input.emit(candidate = true, confirmed = true); idle(); ready()
        input.emit(ended = true, endpoint = oldToken); idle()
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertFalse(socket.messages.any { it.optString("type") == "turn_end" })
    }
    @Test fun muteRevokesCaptureAndIgnoresQueuedCandidate() {
        playback(); input.emit(candidate = true); phone.mute(); idle()
        assertEquals(VoiceController.Mode.CLOSED, phone.mode); assertFalse(phone.wakeEnabled)
        assertFalse(input.active); assertEquals("closed", phone.microphoneState)
        assertEquals(1, socket.messages.count { it.optString("type") == "turn_start" })
    }
    @Test fun unresolvedCandidateFailsClosedWithoutANewTurn() {
        playback(); input.emit(candidate = true); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001)); idle()
        assertFalse(input.active); assertFalse(phone.wakeEnabled); assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        assertEquals(1, socket.messages.count { it.optString("type") == "turn_start" })
    }
    @Test fun confirmationDuringProcessingCancelsLocalComputationNotBusinessExecution() {
        val old = waiting(); set("mode", VoiceController.Mode.PROCESSING)
        val processing = Job(); set("processing", processing)
        input.emit(candidate = true, confirmed = true); idle()
        assertTrue(processing.isCancelled)
        assertNotEquals(old, get("currentTurn"))
        assertEquals(setOf("type", "turn_id"), socket.messages.single { it.optString("type") == "cancel" }.keys().asSequence().toSet())
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 2_000_000_000
        while (!condition() && System.nanoTime() < until) { idle(); Thread.sleep(2) }
        idle(); assertTrue("controller did not reach expected state: ${phone.mode} ${phone.status}", condition())
    }
    @Test fun thirtyInterruptionCyclesKeepOneCaptureOwnerAndFreshRequestIds() {
        val turns = mutableSetOf<String>()
        repeat(30) {
            ready()
            input.emit(confirmed = true); idle(); input.emit(ended = true); idle()
            waitFor { phone.mode == VoiceController.Mode.WAITING }
            val (old, _) = playback()
            assertTrue(turns.add(old))
            input.emit(candidate = true); idle(); input.emit(); idle()
            input.emit(candidate = true, confirmed = true); idle()
            assertEquals(VoiceController.Mode.RECORDING, phone.mode)
            assertTrue(input.active); assertEquals(1, input.starts)
        }
        assertEquals(30, socket.messages.count { it.optString("type") == "cancel" })
        assertEquals(31, socket.messages.count { it.optString("type") == "turn_start" })
        assertEquals(30, socket.messages.count { it.optString("type") == "turn_end" })
    }
    @Test fun completedReplyOpensOneFollowupAndSilenceReturnsToLocalWake() {
        val (turn, id) = playback()
        control(JSONObject().put("type", "playback_text").put("playback_id", id).put("sequence", 0).put("text", "好"))
        val method = VoiceController::class.java.getDeclaredMethod("audio", ByteArray::class.java).apply { isAccessible = true }
        method.invoke(phone, Wire.frame(2, id, 0, ShortArray(480) { 20 }))
        control(JSONObject().put("type", "playback_end").put("playback_id", id).put("sample_count", 480).put("text_segment_count", 1))
        input.devices.single().writable = true
        waitFor { get("player") == null }
        assertEquals(VoiceController.Mode.WAITING, phone.mode); assertTrue(input.active)
        control(JSONObject().put("type", "response_end").put("turn_id", turn))
        assertEquals(VoiceController.Mode.FOLLOWUP, phone.mode); assertEquals(1, input.starts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8001))
        assertEquals(VoiceController.Mode.WAKE, phone.mode); assertTrue(capture.active); assertFalse(input.active)
    }
    @Test fun arrivingReplyRemainsPausedUntilAnExistingCandidateIsRejected() {
        waiting(); input.emit(candidate = true); idle()
        playback()
        assertTrue(input.devices.single().pauses > 0)
        input.emit(); idle()
        assertEquals(VoiceController.Mode.PLAYING, phone.mode)
        assertTrue(input.devices.single().plays > 0)
        assertTrue(socket.audio.isEmpty())
    }
    @Test fun oldKnownAudioIsDroppedButUnknownAudioIsRejected() {
        val (_, old) = playback(); input.emit(candidate = true, confirmed = true); idle()
        val method = VoiceController::class.java.getDeclaredMethod("audio", ByteArray::class.java).apply { isAccessible = true }
        method.invoke(phone, Wire.frame(2, old, 0, ShortArray(480)))
        val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            method.invoke(phone, Wire.frame(2, UUID.randomUUID().toString(), 0, ShortArray(480)))
        }
        assertTrue(error.cause is IllegalArgumentException)
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
    }
    @Test fun endpointCannotSubmitAFrameThatExceedsTheOriginalInputCapacity() {
        set("count", Wire.MAX_INPUT - 160)
        input.emit(confirmed = true, ended = true); idle()
        assertFalse(socket.messages.any { it.optString("type") == "turn_end" })
        assertEquals(VoiceController.Mode.WAKE, phone.mode)
        assertTrue(capture.active); assertFalse(input.active)
    }
    @Test fun aGlobalServerErrorStillRevokesLocalCaptureAfterAnInterruption() {
        playback(); input.emit(candidate = true, confirmed = true); idle()
        control(JSONObject().put("type", "error").put("code", "VOICE_REJECTED").put("message", "current connection failed"))
        assertEquals(VoiceController.Mode.CLOSED, phone.mode)
        assertFalse(input.active); assertFalse(phone.wakeEnabled)
    }
    @Test fun serverCancellationDuringCandidateReturnsToWakeAndCannotResumeTheOldPlayer() {
        val (oldTurn, oldPlayback) = playback()
        val oldFrame = input.frame!!
        input.emit(candidate = true); idle()
        control(JSONObject().put("type", "playback_cancel").put("playback_id", oldPlayback))
        assertEquals(VoiceController.Mode.WAKE, phone.mode)
        assertTrue(phone.wakeEnabled); assertTrue(capture.active); assertFalse(input.active)
        assertTrue(input.devices.single().released)
        models.flags = 2; capture.frame!!(ShortArray(320)); idle(); ready()
        val next = get("currentTurn")
        assertNotEquals(oldTurn, next)
        oldFrame(ConversationFrame(ShortArray(320), SpeechActivity(false, false), false, 0)); idle()
        control(JSONObject().put("type", "response_end").put("turn_id", oldTurn))
        assertEquals(next, get("currentTurn")); assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertTrue(input.active); assertEquals(2, input.starts)
    }
    @Test fun emptyResponseHeldByCandidateRecoversOnlyWakeWhenCandidateIsRejected() {
        val turn = waiting()
        input.emit(candidate = true); idle()
        control(JSONObject().put("type", "response_end").put("turn_id", turn))
        assertEquals(VoiceController.Mode.WAITING, phone.mode); assertTrue(input.active)
        input.emit(); idle()
        assertEquals(VoiceController.Mode.WAKE, phone.mode)
        assertTrue(capture.active); assertFalse(input.active)
        assertTrue(socket.messages.none { it.optString("type") == "cancel" })
    }
    @Test fun speechInNaturalFollowupSurvivesTheOldFollowupDeadline() {
        val (turn, id) = playback()
        control(JSONObject().put("type", "playback_text").put("playback_id", id).put("sequence", 0).put("text", "好"))
        val method = VoiceController::class.java.getDeclaredMethod("audio", ByteArray::class.java).apply { isAccessible = true }
        method.invoke(phone, Wire.frame(2, id, 0, ShortArray(480) { 20 }))
        control(JSONObject().put("type", "playback_end").put("playback_id", id).put("sample_count", 480).put("text_segment_count", 1))
        input.devices.single().writable = true
        waitFor { get("player") == null }
        control(JSONObject().put("type", "response_end").put("turn_id", turn))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7000))
        input.emit(confirmed = true); idle(); ready()
        val next = get("currentTurn")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        input.emit(); idle()
        assertNotEquals(turn, next); assertEquals(next, get("currentTurn"))
        assertEquals(VoiceController.Mode.RECORDING, phone.mode)
        assertTrue(input.active); assertEquals(1, input.starts); assertFalse(capture.active)
        assertTrue(socket.messages.none { it.optString("type") == "turn_end" })
    }

}
