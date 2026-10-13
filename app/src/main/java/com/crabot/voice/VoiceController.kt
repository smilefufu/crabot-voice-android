package com.crabot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class VoiceController(private val context: Context, private val changed: () -> Unit) {
    enum class Mode { CLOSED, WAKE, RECORDING, PROCESSING, WAITING, PLAYING, FOLLOWUP, ENROLLING }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = SecureStore(context)
    private var models: NativeModels? = null
    private var tokens = emptySet<String>()
    private var keyword = ""
    private var wakeWord = "你好螃蟹"
    private var generation = 0
    private var captureGeneration = 0
    private var processing: Job? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) stop("音频焦点已丢失", disableWake = true) }.build()
    private val mic = Microphone(context) { reason -> main.post { stop(reason, disableWake = true) } }
    private var player: Playback? = null
    private var playbackId: String? = null
    private var interactionHadPlayback = false
    private var pairing: JSONObject? = null
    private var currentTurn: String? = null
    private var readyTurn: String? = null
    private var sequence = 0
    private var sentSamples = 0
    private var pcm = ShortArray(Wire.MAX_INPUT)
    private var count = 0
    private var preRoll = ShortArray(4800)
    private var preCount = 0
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var hasSpeech = false
    private var deadline = 0L
    private var pendingFrames = 0
    private var enrollment: JSONObject? = null
    private var enrollmentRevision = 0
    private var enrollmentDeadline = 0L
    private val enrollmentVectors = mutableListOf<FloatArray>()
    private var registrations = JSONArray()
    private var revision = 0
    private var connectionEpoch: String? = null
    private var previousEpoch: String? = null
    private val connection = TerminalConnection(::control, ::audio) { reason -> stop(reason, disableWake = true); connectionEpoch = null; changed() }
    var mode = Mode.CLOSED
        private set
    var foreground = false
        private set
    var wakeEnabled = false
        private set
    var status = "正在加载端侧模型；麦克风关闭"
        private set
    var detail = ""
        private set
    var destinations = "尚未连接"
        private set
    val connected get() = connection.ready && connectionEpoch != null
    val modelReady get() = models != null && keyword.isNotEmpty()
    val keepScreenOn get() = foreground && mode != Mode.CLOSED
    val enrollmentName get() = enrollment?.getString("display_name")
    val microphoneState get() = when (mode) { Mode.WAKE, Mode.FOLLOWUP -> "wake"; Mode.RECORDING, Mode.ENROLLING -> "recording"; else -> "closed" }
    init {
        connection.heartbeat = { JSONObject().put("type", "heartbeat").put("foreground", foreground)
            .put("microphone", microphoneState).put("playback", if (mode == Mode.PLAYING) "playing" else "idle") }
        scope.launch {
            var prepared: NativeModels? = null
            try {
                withContext(Dispatchers.Default) {
                    val root = Models.prepare(context)
                    tokens = java.io.File(root, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
                    keyword = Models.keyword("你好螃蟹", tokens)
                    prepared = NativeModels(root)
                }
                models = prepared; prepared = null
                status = if (store.needsRepair && store.compatible) "旧配对需重新扫码；同渠道声纹已保留，请先在 Admin 撤销旧终端。" else if (store.compatible) "模型已加载；麦克风关闭。声纹和唤醒尚待真人校准验收。" else "配对数据不兼容；原文件保留，请明确清除本机数据后重新配对"
                if (foreground && !connected && store.pairing() != null) reconnect()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "端侧分段模型加载失败；麦克风关闭" }
            catch (_: LinkageError) { status = "本设备无法加载端侧推理组件；麦克风关闭" }
            finally { prepared?.close() }
            changed()
        }
    }
    fun resume() {
        foreground = true
        if (!connected && store.pairing() != null) reconnect()
        heartbeat(); changed() // Resume/reconnect never opens the microphone.
    }
    fun pause() { foreground = false; stop("已退到后台；麦克风与播放关闭", disableWake = true); heartbeat() }
    fun destroy() { pause(); connection.disconnect(null); scope.cancel(); models?.close(); models = null }
    fun scan(raw: String) {
        try {
            stop("开始配对；麦克风关闭", disableWake = true)
            check(store.compatible) { "旧 WIP 配对/声纹不兼容，请明确清除本机数据后重新配对" }
            val qr = JSONObject(raw)
            require(qr.getInt("protocol_version") == Wire.PROTOCOL_VERSION) { "协议版本不兼容，需要 v${Wire.PROTOCOL_VERSION} 配对码" }
            require(System.currentTimeMillis() < java.time.Instant.parse(qr.getString("expires_at")).toEpochMilli()) { "二维码已过期，请在 Admin 重新生成后再扫描" }
            store.checkChannel(qr.getString("channel_id"))
            TerminalEndpoint.parse(qr.getString("url"), qr.getString("channel_id"))
            pairing = qr
            connectionEpoch = null
            connection.connect(qr, JSONObject().put("type", "pair").put("protocol_version", Wire.PROTOCOL_VERSION).put("nonce", qr.getString("nonce")))
            status = "正在配对；请在 Admin 核对手机显示的代码后确认"
        } catch (e: Exception) {
            android.util.Log.w("CrabotPairing", "scan_rejected=${e.javaClass.simpleName}")
            status = e.message ?: "二维码无效或已过期，请在 Admin 重新生成"
        }
        changed()
    }
    fun reconnect() {
        stop("正在连接；麦克风关闭", disableWake = true)
        if (models == null) { status = "等待端侧模型加载；麦克风关闭"; changed(); return }
        try {
            check(store.compatible) { "旧 WIP 配对/声纹不兼容；原数据保留，请明确清除本机数据后重新配对" }
            check(!store.needsRepair) { "旧配对需重新扫码；声纹保留，请先在 Admin 撤销旧终端后扫描同渠道 v${Wire.PROTOCOL_VERSION} 配对码" }
            val saved = store.pairing() ?: error("请扫描 Admin 的 v${Wire.PROTOCOL_VERSION} 配对二维码")
            connectionEpoch = null
            connection.connect(saved, JSONObject().put("type", "hello").put("protocol_version", Wire.PROTOCOL_VERSION).put("credential", saved.getString("credential"))
                .put("models", Models.versions()).put("aec_verified", false))
        } catch (e: Exception) { status = e.message ?: "请扫描 Admin 的 v${Wire.PROTOCOL_VERSION} 配对二维码" }
        changed()
    }
    fun forget() { stop("本机已清除配对和登记；请在 Admin 撤销旧终端后重新配对", true); connection.disconnect(null); connectionEpoch = null; store.forget(); changed() }
    private fun heartbeat() { if (connection.ready) try { connection.send(connection.heartbeat!!.invoke()) } catch (_: Exception) { connection.disconnect("连接已中断") } }
    fun toggleWake() {
        if (wakeEnabled) stop("麦克风关闭", true)
        else try {
            require(foreground && connected && modelReady) { "需在前台连接并完成模型加载" }
            stop("开启本地唤醒；不上传待机音频", true)
            wakeEnabled = true; mode = Mode.WAKE; startMic(); status = "本地唤醒待机：$wakeWord。准确率与功耗尚未验收。"; heartbeat()
        } catch (e: Exception) { stop(e.message ?: "无法开启麦克风", true) }
        changed()
    }
    fun recordButton() {
        if (mode == Mode.RECORDING || mode == Mode.ENROLLING) { finishCapture(); return }
        try {
            require(foreground && connected && modelReady) { "需在前台连接并完成模型加载" }
            if (enrollment != null && mode == Mode.CLOSED) { beginEnrollmentClip(); changed(); return }
            val keepWake = wakeEnabled
            stop("开始有限录音", false); wakeEnabled = keepWake
            if (enrollment != null) beginEnrollmentClip() else beginTurn(ShortArray(0))
        } catch (e: Exception) { stop(e.message ?: "无法开始录音", true) }
        changed()
    }
    fun mute() { stop("完全静音；麦克风与播放已关闭", true) }
    fun stop(reason: String, disableWake: Boolean = false) {
        generation++; captureGeneration++; processing?.cancel(); processing = null
        mic.stop(); mode = Mode.CLOSED
        val turn = currentTurn; val playback = playbackId
        player?.stop(); player = null; playbackId = null
        if (connected && (turn != null || playback != null)) try {
            val cancel = JSONObject().put("type", "cancel")
            if (turn != null) cancel.put("turn_id", turn)
            if (playback != null) cancel.put("playback_id", playback)
            connection.send(cancel)
        } catch (_: Exception) {}
        interactionHadPlayback = false
        currentTurn = null; readyTurn = null; count = 0; sentSamples = 0; sequence = 0
        pcm.fill(0); preRoll.fill(0); preCount = 0
        if (disableWake) wakeEnabled = false
        enrollmentVectors.forEach { it.fill(0f) }; enrollmentVectors.clear()
        if (disableWake) enrollment = null
        manager.abandonAudioFocusRequest(focus)
        status = reason; heartbeat(); changed()
    }
    private fun startMic() {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法获得音频焦点" }
        models!!.reset(keyword)
        val token = ++captureGeneration
        val captureMode = mode
        mic.start { audio ->
            val flags = try { models!!.feed(audio, captureMode != Mode.WAKE, captureMode == Mode.WAKE) } catch (_: Exception) { audio.fill(0); main.post { stop("模型音频检测失败", true) }; return@start }
            synchronized(this) { pendingFrames++ }
            main.post {
                synchronized(this) { pendingFrames-- }
                try {
                    if (token != captureGeneration) return@post
                    check(pendingFrames <= 50) { "采音处理队列超过一秒" }
                    frame(audio, flags)
                } catch (e: Exception) { stop(e.message ?: "录音处理失败", true) }
                finally { audio.fill(0) }
            }
        }
    }
    private fun beginTurn(leading: ShortArray) {
        mode = Mode.RECORDING; currentTurn = UUID.randomUUID().toString(); readyTurn = null
        sequence = 0; sentSamples = 0; count = 0; startedAt = SystemClock.elapsedRealtime(); hasSpeech = false; lastSpeechAt = startedAt
        leading.copyInto(pcm); count = leading.size; leading.fill(0)
        connection.send(JSONObject().put("type", "turn_start").put("turn_id", currentTurn))
        startMic(); status = "正在录音，最多 15 秒；可点按结束"; heartbeat(); changed()
    }
    private fun frame(audio: ShortArray, flags: Int) {
        val now = SystemClock.elapsedRealtime()
        when (mode) {
            Mode.WAKE, Mode.FOLLOWUP -> {
                if (mode == Mode.FOLLOWUP && now >= deadline) { standby(); return }
                val n = audio.size.coerceAtMost(preRoll.size)
                val keep = preCount.coerceAtMost(preRoll.size - n)
                preRoll.copyInto(preRoll, 0, preCount - keep, preCount); audio.copyInto(preRoll, keep, audio.size - n, audio.size); preCount = keep + n
                if (mode == Mode.WAKE && flags and 2 != 0) {
                    // Never include the recognized wake phrase in the first command.
                    mic.stop(); captureGeneration++; preRoll.fill(0); preCount = 0; beginTurn(ShortArray(0))
                } else if (mode == Mode.FOLLOWUP && flags and 1 != 0) {
                    val leading = preRoll.copyOf(preCount)
                    mic.stop(); captureGeneration++; preRoll.fill(0); preCount = 0; beginTurn(leading)
                    hasSpeech = true; lastSpeechAt = now
                }
            }
            Mode.RECORDING, Mode.ENROLLING -> {
                val n = audio.size.coerceAtMost(Wire.MAX_INPUT - count)
                audio.copyInto(pcm, count, 0, n); count += n
                if (flags and 1 != 0) { hasSpeech = true; lastSpeechAt = now }
                if (mode == Mode.RECORDING) {
                    if (readyTurn == currentTurn) upload()
                    else check(now - startedAt <= 1000) { "终端未就绪，录音已结束" }
                    if (count == Wire.MAX_INPUT || (!hasSpeech && now - startedAt >= 3000) || (hasSpeech && now - lastSpeechAt >= 800)) finishCapture()
                } else if (now >= enrollmentDeadline) stop("登记任务已过期，请在 Admin 重新开始", true)
                else if (now - startedAt >= 5000) finishCapture()
            }
            else -> Unit
        }
    }
    private fun upload() {
        while (count - sentSamples >= 320) {
            val chunk = pcm.copyOfRange(sentSamples, sentSamples + 320)
            val bytes = Wire.frame(1, currentTurn!!, sequence++, chunk)
            try { connection.sendAudio(bytes) } finally { bytes.fill(0); chunk.fill(0) }
            sentSamples += 320
        }
    }
    private fun finishCapture() {
        val enroll = mode == Mode.ENROLLING
        mic.stop(); captureGeneration++
        var peak = 0
        var energy = 0.0
        for (i in 0 until count) { val sample = pcm[i].toInt(); peak = maxOf(peak, kotlin.math.abs(sample)); energy += sample.toDouble() * sample }
        val rms = if (count == 0) 0 else kotlin.math.sqrt(energy / count).toInt()
        android.util.Log.i("CrabotVoiceInput", "samples=$count peak=$peak rms=$rms vad_seen=$hasSpeech")
        if (!hasSpeech || count == 0) { stop("未检测到有效人声，请重说", false); standby(); return }
        if (enroll && count !in 48000..80000) { count = 0; pcm.fill(0); mode = Mode.CLOSED; status = "每段登记需 3–5 秒；请重新录这一段"; changed(); return }
        val audio = pcm.copyOf(count); pcm.fill(0)
        val token = generation
        if (!enroll) {
            try {
                check(readyTurn == currentTurn) { "终端未准备好" }; uploadRemaining(audio)
            } catch (_: Exception) { audio.fill(0); stop("上传失败；本轮没有重传", true); return }
        }
        mode = Mode.PROCESSING; status = if (enroll) "正在提取登记声纹" else "正在本机分段并逐人匹配"; heartbeat(); changed()
        processing = scope.launch {
            try {
                val completed = if (enroll) completeEnrollment(audio, token) else completeCommand(audio, token)
                if (!completed) return@launch
                heartbeat(); changed()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) stop(e.message ?: "声纹处理失败；整轮未提交", false) }
            finally { audio.fill(0) }
        }
    }
    private suspend fun completeEnrollment(audio: ShortArray, token: Int): Boolean {
        val v = withContext(Dispatchers.Default) {
            val segments = Identity.ranges(models!!.segments(audio), audio.size)
            Identity.requireComplete(segments)
            require(segments.map { it.speaker }.distinct().size == 1 && segments.sumOf { it.end - it.start } >= Identity.MIN_CLUSTER_SAMPLES) { "登记需一人清楚说话，请重新录这一段" }
            models!!.embedding(audio).also { require(it.isNotEmpty()) { "语音证据不足" } }
        }
        if (token != generation || SystemClock.elapsedRealtime() >= enrollmentDeadline) { v.fill(0f); return false }
        enrollmentVectors += v
        if (enrollmentVectors.size == 3) {
            val mean = FloatArray(v.size) { i -> enrollmentVectors.sumOf { it[i].toDouble() }.toFloat() / 3 }
            val target = enrollment ?: error("登记已取消")
            try { store.putVector(target.getString("registration_id"), mean) } finally { mean.fill(0f) }
            enrollmentVectors.forEach { it.fill(0f) }; enrollmentVectors.clear()
            connection.send(JSONObject().put("type", "enrollment_done").put("registration_id", target.getString("registration_id"))
                .put("model_id", Models.EMBEDDING).put("enrollment_revision", enrollmentRevision))
            enrollment = null; mode = Mode.CLOSED; status = "声纹样本完成，等待后台确认；麦克风关闭"
        } else { mode = Mode.CLOSED; status = "已录 ${enrollmentVectors.size}/3 段；点按录制下一段（不同内容，3–5 秒）" }
        return true
    }
    private suspend fun completeCommand(audio: ShortArray, token: Int): Boolean {
        val segments = withContext(Dispatchers.Default) { Identity.report(audio, models!!, store.vectors(), registrations) }
        if (token != generation) return false
        detail = (0 until segments.length()).joinToString("\n") { i ->
            val s = segments.getJSONObject(i)
            val r = (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("registration_id") == s.optString("registration_id") }
            "${s.getInt("start_sample") / 16000.0}–${s.getInt("end_sample") / 16000.0}s  ${s.getString("anonymous_speaker")}: ${r?.getString("display_name") ?: if (s.getString("identity_status") == "uncertain") "身份未确认（陌生人）" else "陌生人"}" }
        connection.send(JSONObject().put("type", "turn_end").put("turn_id", currentTurn).put("audio_sample_count", audio.size).put("sample_rate", 16000)
            .put("models", Models.versions()).put("enrollment_revision", revision).put("segments", segments))
        mode = Mode.WAITING; status = "已提交，等待接纳；麦克风关闭"
        val waitingTurn = currentTurn
        main.postDelayed({ if (waitingTurn == currentTurn && mode == Mode.WAITING) { stop("本轮回答机会已结束；已接纳任务仍由 Crabot 负责，可重新唤醒询问", false); standby() } }, 60_000)
        return true
    }
    private fun uploadRemaining(audio: ShortArray) {
        // Full frames were streamed during finite capture; only the final short frame remains.
        while (sentSamples < audio.size) {
            val end = (sentSamples + 320).coerceAtMost(audio.size)
            val chunk = audio.copyOfRange(sentSamples, end); val bytes = Wire.frame(1, currentTurn!!, sequence++, chunk)
            try { connection.sendAudio(bytes) } finally { chunk.fill(0); bytes.fill(0) }
            sentSamples = end
        }
    }
    private fun standby() {
        mode = Mode.CLOSED
        if (wakeEnabled && foreground && connected) { mode = Mode.WAKE; startMic(); status += "；恢复本地唤醒待机" }
        else manager.abandonAudioFocusRequest(focus)
        heartbeat(); changed()
    }
    fun confirmEnrollment() {
        try { require(enrollment != null && foreground && connected && modelReady); beginEnrollmentClip() }
        catch (e: Exception) { stop(e.message ?: "无法开始登记", true) }; changed()
    }
    private fun beginEnrollmentClip() {
        check(SystemClock.elapsedRealtime() < enrollmentDeadline) { "登记任务已过期" }
        count = 0; pcm.fill(0); startedAt = SystemClock.elapsedRealtime(); hasSpeech = false
        mode = Mode.ENROLLING; startMic(); status = "现场登记 ${enrollmentName}：第 ${enrollmentVectors.size + 1}/3 段，3–5 秒"; heartbeat()
    }
    private fun control(c: JSONObject) {
        try {
            when (c.getString("type")) {
                "pair_waiting" -> { require(c.getString("pairing_id") == pairing!!.getString("pairing_id") && c.getString("verification_code") == pairing!!.getString("verification_code")); status = "核对码：${c.getString("verification_code")}\n请在 Admin 确认；麦克风关闭"; detail = status }
                "paired" -> {
                    val qr = pairing ?: error("无配对任务")
                    val saved = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("url", qr.getString("url")).put("channel_id", qr.getString("channel_id")).put("credential", c.getString("credential"))
                    if (qr.has("tls_fingerprint_sha256")) saved.put("tls_fingerprint_sha256", qr.getString("tls_fingerprint_sha256"))
                    try { store.savePairing(saved) }
                    catch (_: Exception) {
                        pairing = null; stop("新凭据保存失败，旧配对和声纹保留。请在 Admin 撤销终端，再扫描同渠道新二维码。", true)
                        connection.disconnect(null); connectionEpoch = null; changed(); return
                    }
                    pairing = null; detail = ""; reconnect()
                }
                "hello" -> {
                    val epoch = c.getString("connection_epoch")
                    if (previousEpoch != epoch) { stop("已连接；麦克风关闭", true); previousEpoch = epoch }
                    connectionEpoch = epoch
                    registrations = c.getJSONArray("registrations"); revision = c.getInt("enrollment_revision")
                    (0 until registrations.length()).forEach { i -> val r = registrations.getJSONObject(i); require(r.getString("platform_user_id").isNotBlank() && !r.has("friend_id")) }
                    wakeWord = c.getJSONObject("config").getString("wake_word")
                    keyword = Models.keyword(wakeWord, tokens)
                    val revoked = (0 until registrations.length()).map { registrations.getJSONObject(it) }.filter { it.getString("status") == "revoked" }
                    revoked.forEach { clearRegistration(it.getString("registration_id"), revision) }
                    destinations = (0 until c.getJSONArray("destinations").length()).joinToString("\n") { i ->
                        val d = c.getJSONArray("destinations").getJSONObject(i); "${d.getString("capability").uppercase()}: ${d.getString("endpoint")}" }
                    status = "已连接；麦克风关闭。声纹分数配置尚待真人校准。"; heartbeat()
                }
                "heartbeat" -> Unit
                "turn_ready" -> { if (c.getString("turn_id") == currentTurn && mode == Mode.RECORDING) { readyTurn = currentTurn; upload() } }
                "turn_status" -> if (c.getString("turn_id") == currentTurn) {
                    status = when (c.getString("status")) { "accepted" -> "整轮已接纳，等待回答；麦克风关闭"; "pending", "unknown" -> "已提交，接纳状态待确认；不会换 ID 重传"; else -> "整轮拒绝：${c.optString("reason", "请重说或登记")}" }
                    if (c.optString("payload_sha256").isNotBlank() && c.getString("status") in listOf("pending", "unknown")) {
                        val id = currentTurn; val digest = c.getString("payload_sha256"); main.postDelayed({ if (id == currentTurn && connected) connection.send(JSONObject().put("type", "turn_query").put("turn_id", id).put("payload_sha256", digest)) }, 2000)
                    }
                    if (c.getString("status") == "rejected") { currentTurn = null; standby() }
                }
                "playback_start" -> {
                    require(connected && foreground && currentTurn == c.getString("turn_id") && mode == Mode.WAITING && player == null)
                    mic.stop(); captureGeneration++; mode = Mode.PLAYING
                    check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    playbackId = c.getString("playback_id"); status = "播放中可点按打断"; detail += "\n回答：${c.getString("text")}"; heartbeat()
                    player = Playback(context, playbackId!!) { id, receipt, played -> main.post { playbackReceipt(id, receipt, played) } }
                }
                "playback_end" -> { if (c.getString("playback_id") == playbackId) { player!!.finish(c.getInt("sample_count")); detail += "\n完整回答：${c.getString("text")}" } }
                "response_end" -> { if (c.getString("turn_id") == currentTurn && player == null) finishResponse() }
                "playback_cancel" -> { if (c.getString("playback_id") == playbackId) stop("播报已取消；已接纳任务仍由 Crabot 负责", false) }
                "enroll" -> {
                    stop("后台发起登记，请现场确认后录音", true)
                    require(c.getJSONObject("registration").getString("model_id") == Models.EMBEDDING)
                    enrollment = c.getJSONObject("registration"); enrollmentRevision = c.getInt("enrollment_revision")
                    enrollmentDeadline = SystemClock.elapsedRealtime() + 60_000
                    status = "登记 ${enrollmentName}：请现场点按确认；3 段不同内容，每段 3–5 秒，60 秒内完成"
                    main.postDelayed({ if (enrollment != null && SystemClock.elapsedRealtime() >= enrollmentDeadline) stop("登记任务已过期，请从 Admin 重新开始", true) }, 60_000)
                }
                "clear_registration" -> { stop("联系人声纹已撤销；本轮已结束", true); clearRegistration(c.getString("registration_id"), c.getInt("enrollment_revision")) }
                "error" -> stop("语音端错误：${c.getString("message")}", true)
                else -> error("未知协议控制")
            }
            changed()
        } catch (_: Exception) { stop("协议、模型或登记版本不兼容；麦克风关闭", true); connection.disconnect(null); connectionEpoch = null; changed() }
    }
    private fun clearRegistration(id: String, rev: Int) {
        store.clearVector(id)
        if (connected) connection.send(JSONObject().put("type", "registration_cleared").put("registration_id", id).put("enrollment_revision", rev))
    }
    private fun audio(bytes: ByteArray) {
        if (mode != Mode.PLAYING || !foreground) return
        val frame = Wire.output(bytes)
        if (frame.id != playbackId) { frame.samples.fill(0); return }
        try { player!!.add(frame) } catch (e: Exception) { frame.samples.fill(0); throw e }
    }
    private fun playbackReceipt(id: String, receipt: String, played: Int?) {
        if (connected) try {
            val control = JSONObject().put("type", "playback").put("playback_id", id).put("status", receipt)
            if (played != null) control.put("played_samples", played)
            connection.send(control)
        } catch (_: Exception) {}
        if (id != playbackId) return
        if (receipt == "completed") {
            player = null; playbackId = null; interactionHadPlayback = true
            mode = Mode.WAITING; status = "播报完成，等待后续答复；麦克风关闭"; heartbeat()
        } else if (receipt != "started") { interactionHadPlayback = false; player = null; playbackId = null; mode = Mode.CLOSED; status = "播放${if (receipt == "failed") "失败" else "已打断"}，未自动重播" }
        changed()
    }
    private fun finishResponse() {
        currentTurn = null
        if (!interactionHadPlayback) { mode = Mode.CLOSED; status = "本轮结束，没有可播回复；麦克风关闭"; heartbeat(); changed(); return }
        interactionHadPlayback = false
        if (foreground && connected) try {
            mode = Mode.FOLLOWUP; deadline = SystemClock.elapsedRealtime() + 8000; status = "播放完成；8 秒本地接话窗口"; startMic()
            main.postDelayed({ if (mode == Mode.FOLLOWUP && SystemClock.elapsedRealtime() >= deadline) {
                mic.stop(); captureGeneration++
                try { standby() } catch (_: Exception) { stop("无法恢复本地唤醒；麦克风关闭", true) }
            } }, 8000)
        } catch (_: Exception) { stop("无法开启接话窗口；麦克风关闭", true) }
        heartbeat(); changed()
    }
}
