package com.crabot.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Standalone, offline diagnostics. No product Controller, storage, credentials or network code. */
class AudioProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var echo: Button
    private lateinit var near: Button
    private lateinit var stop: Button
    private var focus: AudioFocusRequest? = null
    private var session: DuplexAudioSession? = null
    private var detector: NativeSpeechDetector? = null
    private var candidate: DuplexAudioSession.Candidate? = null
    @Volatile private var generation = 0
    @Volatile private var active = false
    @Volatile private var confirmed = false
    @Volatile private var playbackActive = false
    private var stage = ""
    private var beganAt = 0L
    private var candidateAt = 0L
    private var stats = JSONObject()
    private val lock = Any()
    private var firstStamp: AudioStamp? = null
    private var lastStamp: AudioStamp? = null
    private var producer: Thread? = null
    private val resultFile get() = File(filesDir, "last-audio-probe.json")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24) }
        text = TextView(this).apply { textSize = 18f }
        echo = Button(this).apply { text = "1. 测试扬声器回声（保持安静）"; setOnClickListener { start("echo") } }
        near = Button(this).apply { text = "2. 测试开口插话"; setOnClickListener { start("near") } }
        stop = Button(this).apply { text = "立即结束 · 关闭麦克风"; isEnabled = false; setOnClickListener { finishRun("用户结束") } }
        layout.addView(text); layout.addView(echo); layout.addView(near); layout.addView(stop)
        setContentView(ScrollView(this).apply { addView(layout) })
        text.text = if (resultFile.exists()) "上次结果（麦克风关闭）\n${resultFile.readText()}" else
            "独立音频验证 · 麦克风关闭\n\n不连接 Crabot，不读取配对或声音记录，不保存录音。每项由你点开始，最多 12 秒。\n\n第一项保持安静；第二项在播放时自然说：你好，我现在想补充一句，请先听我说。\n\n这里只验证硬件时钟和回声/插话，不验证中文说完判定。结果会留在此页。"
    }

    private fun increment(key: String, amount: Int = 1) = synchronized(lock) { stats.put(key, stats.optLong(key) + amount) }
    private fun start(kind: String) {
        if (active) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            text.text = "请允许麦克风权限，然后再次点测试按钮。尚未采音或播放。"; return
        }
        val token = ++generation
        stage = kind; active = true; confirmed = false; candidate = null
        firstStamp = null; lastStamp = null
        stats = JSONObject().put("stage", kind).put("audio_saved", false).put("network", false).put("receipts", JSONArray())
        echo.isEnabled = false; near.isEnabled = false; stop.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text.text = if (kind == "echo") "回声测试中：请保持安静。\n最多 12 秒，随时可以结束。" else
            "请在播放时说：\n你好，我现在想补充一句，请先听我说。\n\n不需要喊唤醒词，最多 12 秒。"
        beganAt = SystemClock.elapsedRealtime()
        try {
            val manager = getSystemService(AudioManager::class.java)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) main.post { if (generation == token) finishRun("音频焦点变化") } }, main).build()
            check(manager.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法获得音频焦点" }
            val modelRoot = File(filesDir, "models").apply { mkdirs() }
            val model = File(modelRoot, "silero.onnx")
            if (!model.exists()) assets.open("silero.onnx").use { input -> model.outputStream().use { input.copyTo(it) } }
            val native = NativeSpeechDetector(modelRoot.path); detector = native
            val microphone = Microphone(this, systemAec = false) { reason -> main.post { if (generation == token) finishRun(reason) } }
            val capture = object : AudioCapture {
                override fun start(frame: (ShortArray) -> Unit) = error("Timed capture required")
                override fun startTimed(frame: (CapturedAudio) -> Unit) = microphone.startTimed { value ->
                    increment("captured_samples", value.samples.size)
                    synchronized(lock) {
                        val stamp = value.stamp
                        if (stamp == null) increment("missing_capture_stamps")
                        else { if (firstStamp == null) firstStamp = stamp; lastStamp = stamp }
                    }
                    frame(value)
                }
                override fun stop() = microphone.stop()
            }
            val played = AtomicLong()
            val audio = DuplexAudio(NativeEchoProcessor())
            val current = DuplexAudioSession(capture, audio) {
                val device = AndroidPlaybackDevice(this)
                object : PlaybackDevice by device {
                    override fun stamp(): AudioStamp? = device.stamp().also { if (it == null) increment("missing_render_stamps") else increment("valid_render_stamps") }
                    override fun head() = device.head().also(played::set)
                }
            }
            session = current
            current.startCapture { raw, clean ->
                val started = System.nanoTime()
                val activity = native.feed(clean)
                increment("delivered_samples", raw.samples.size)
                synchronized(lock) { stats.put("vad_max_us", maxOf(stats.optLong("vad_max_us"), (System.nanoTime() - started) / 1000)) }
                main.post { if (active && generation == token) {
                    try { observe(current, activity, token) } catch (error: Exception) { finishRun(error.message ?: "插话检测失败") }
                } }
            }
            val id = UUID.randomUUID().toString()
            playbackActive = true
            val player = current.startPlayback(id, {}, { _, state, count ->
                if (state in listOf("completed", "failed", "interrupted")) playbackActive = false
                synchronized(lock) { stats.getJSONArray("receipts").put(JSONObject().put("state", state).put("played_samples", count)) }
                if (state == "failed") main.post { if (active && generation == token) finishRun("播放失败") }
            })
            val reference = reference()
            producer = Thread({
                var sequence = 0L
                var sent = 0L
                try {
                    while (active && generation == token && !confirmed && sent < 240000) {
                        if (sent - played.get() >= 12000) { Thread.sleep(5); continue }
                        val samples = ShortArray(480) { reference[((sent + it) % reference.size).toInt()] }
                        player.add(Wire.AudioFrame(id, sequence++, samples)); sent += samples.size
                    }
                    if (active && generation == token && !confirmed) player.finish(sent)
                } catch (error: Exception) {
                    if (active && generation == token && !confirmed) main.post { if (generation == token) finishRun(error.message ?: "播放生产失败") }
                } finally { reference.fill(0) }
            }, "probe-public-render").apply { start() }
            main.postDelayed({ if (active && generation == token) finishRun(if (kind == "echo") "回声观察结束" else "观察结束，未确认插话") }, 12000)
        } catch (error: Exception) { finishRun(error.message ?: "音频验证失败") }
    }

    private fun observe(current: DuplexAudioSession, activity: SpeechActivity, token: Int) {
        if (activity.candidate) increment("candidate_frames")
        if (activity.confirmed) increment("confirmed_frames")
        if (stage == "echo" && activity.confirmed) { finishRun("仅回声阶段确认了人声：需核对现场噪声和回声"); return }
        if (candidate == null && (activity.candidate || activity.confirmed) && !confirmed && playbackActive) {
            candidate = current.pauseForCandidate()
            check(candidate != null) { "不能暂停原播放器" }
            val pending = candidate
            candidateAt = SystemClock.elapsedRealtime(); increment("candidate_pauses")
            main.postDelayed({ if (active && generation == token && candidate === pending) finishRun("插话候选未在原一秒内收口") }, 1000)
        }
        if (candidate != null && activity.confirmed) {
            confirmed = true
            check(current.confirm(candidate!!)) { "不能中断原播放器" }
            candidate = null
            synchronized(lock) { stats.put("candidate_to_confirm_ms", SystemClock.elapsedRealtime() - candidateAt) }
            text.text = "已确认插话并停止原播音。正在收口，结果会留在页面。"
            main.postDelayed({ if (active && generation == token) finishRun("插话观察结束") }, 1000)
        } else if (candidate != null && !activity.candidate) {
            check(current.reject(candidate!!)) { "不能恢复原播放器" }
            candidate = null; increment("candidate_resumes")
        }
    }

    private fun finishRun(reason: String) {
        if (!active) return
        active = false; generation++; candidate = null
        var cleanupError: String? = null
        try { session?.close() } catch (error: Exception) { cleanupError = error.message ?: "音频释放失败" }
        finally { session = null; try { detector?.close() } catch (error: Exception) { cleanupError = error.message ?: "检测器释放失败" }; detector = null }
        producer?.join(300); producer = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        synchronized(lock) {
            stats.put("reason", reason).put("elapsed_ms", SystemClock.elapsedRealtime() - beganAt).put("microphone_closed", cleanupError == null)
            if (cleanupError != null) stats.put("cleanup_error", cleanupError)
            val first = firstStamp; val last = lastStamp
            if (first != null && last != null && last.nanoTime > first.nanoTime) {
                stats.put("capture_clock_ppm", ((last.position - first.position) * 1e9 / (last.nanoTime - first.nanoTime) / 16000 - 1) * 1e6)
            }
            resultFile.writeText(stats.toString(2))
            val state = if (cleanupError == null) "麦克风已关闭" else "音频释放异常，请核对系统麦克风指示"
            text.text = "$state\n\n${stats.toString(2)}\n\n结果不会自动消失。该结果不代表中文判停或完整对话验收通过。"
        }
        echo.isEnabled = true; near.isEnabled = true; stop.isEnabled = false
    }

    private fun reference(): ShortArray {
        val bytes = assets.open("reference.wav").use { it.readBytes() }
        try {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val size = data.getInt(offset + 4)
                check(size >= 0 && size <= bytes.size - offset - 8)
                if (String(bytes, offset, 4) == "fmt ") check(size >= 16 && data.getShort(offset + 8).toInt() == 1 && data.getShort(offset + 10).toInt() == 1 && data.getInt(offset + 12) == 24000 && data.getShort(offset + 22).toInt() == 16)
                if (String(bytes, offset, 4) == "data") return ShortArray(size / 2) { data.getShort(offset + 8 + it * 2) }.also { check(it.isNotEmpty()) }
                offset += 8 + size + size % 2
            }
            error("公开参考 WAV 缺少 PCM")
        } finally { bytes.fill(0) }
    }

    override fun onPause() { finishRun("离开页面，立即结束"); super.onPause() }
    override fun onDestroy() { finishRun("页面关闭"); main.removeCallbacksAndMessages(null); super.onDestroy() }
}
