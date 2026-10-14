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
    private var enrollmentRevision = 0L
    private var enrollmentDeadline = 0L
    private val enrollmentVectors = mutableListOf<FloatArray>()
    private var registrations = store.catalog()?.optJSONArray("registrations") ?: JSONArray()
    private var revision = store.catalog()?.getLong("enrollment_revision") ?: 0L
    private var confirmedRevision = -1L
    private var startEnrollmentRequest: String? = null
    private var enrollmentRequestId: String? = null
    private val unknownRequests = mutableSetOf<String>()
    private var connectionEpoch: String? = null
    private var previousEpoch: String? = null
    private val reconnectLater: Runnable = Runnable { if (foreground && !connected && store.pairing() != null) reconnect() }
    private var reconnectDelay = 1000L
    private val connection: TerminalConnection = TerminalConnection(::control, ::audio) { reason ->
        stop(reason, disableWake = true); connectionEpoch = null; confirmedRevision = -1
        if (foreground && store.pairing() != null) {
            main.removeCallbacks(reconnectLater); main.postDelayed(reconnectLater, reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(10_000)
        }
        changed()
    }
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
    val connected: Boolean get() = connection.ready && connectionEpoch != null
    val paired get() = store.pairing() != null
    val modelReady get() = models != null && keyword.isNotEmpty()
    val keepScreenOn get() = foreground && mode != Mode.CLOSED
    val enrollmentName get() = enrollment?.getString("display_name") ?: pendingTask?.getJSONObject("registration")?.getString("display_name")
    val wakePhrase get() = wakeWord
    var lastReply = ""
        private set
    var speakerSummary = ""
        private set
    var speakerReason = ""
        private set
    var enrollmentCompleted = false
        private set
    val followupSeconds get() = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceIn(0, 8).toInt()
    val contacts get() = store.catalog()?.optJSONArray("contacts") ?: JSONArray()
    val voiceRegistrations get() = store.catalog()?.optJSONArray("registrations") ?: JSONArray()
    val catalogRevision get() = revision
    val pendingTask get() = store.catalog()?.optJSONObject("pending_enrollment")
    val waitingConfirmation get() = store.pendingCompletion() != null
    val enrollmentStep get() = enrollmentVectors.size + 1
    val synchronizedCatalog get() = connected && confirmedRevision == revision
    val readyForConversation get() = synchronizedCatalog && store.pendingRequests().length() == 0 && !waitingConfirmation && pendingTask == null
    val pendingManagement get() = store.pendingRequests().length() > 0
    fun hasVoiceSample(id: String) = store.hasVector(id)
    val microphoneState get() = when (mode) { Mode.WAKE, Mode.FOLLOWUP -> "wake"; Mode.RECORDING, Mode.ENROLLING -> "recording"; else -> "closed" }
    init {
        connection.heartbeat = { JSONObject().put("type", "heartbeat").put("foreground", foreground)
            .put("microphone", microphoneState).put("playback", if (mode == Mode.PLAYING) "playing" else "idle").put("enrollment_revision", revision) }
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
    fun pause() { foreground = false; main.removeCallbacks(reconnectLater); stop("已退到后台；麦克风与播放关闭", disableWake = true); heartbeat() }
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
        main.removeCallbacks(reconnectLater)
        stop("正在连接；麦克风关闭", disableWake = true)
        confirmedRevision = -1
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
    fun forget() {
        stop("本机已清除配对和登记；请在 Admin 撤销旧终端后重新配对", true)
        connection.disconnect(null); connectionEpoch = null; store.forget()
        revision = 0; confirmedRevision = -1; registrations = JSONArray(); unknownRequests.clear(); enrollmentCompleted = false; pairing = null
        changed()
    }
    private fun heartbeat() { if (connection.ready) try { connection.send(connection.heartbeat!!.invoke()) } catch (_: Exception) { connection.disconnect("连接已中断") } }
    fun toggleWake() {
        if (wakeEnabled) stop("麦克风关闭", true)
        else try {
            require(foreground && readyForConversation && modelReady) { "请等待连接及声纹目录同步，或完成当前登记" }
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
            if (enrollment != null) beginEnrollmentClip() else { require(readyForConversation) { "请等待声纹目录同步或完成当前登记" }; beginTurn(ShortArray(0)) }
        } catch (e: Exception) { stop(e.message ?: "无法开始录音", true) }
        changed()
    }
    fun mute() { stop("完全静音；麦克风与播放已关闭", true) }
    fun enrollNew(name: String) = requestManagement(JSONObject().put("action", "enroll_new").put("display_name", name.trim()), true)
    fun reEnroll(id: String) = requestManagement(JSONObject().put("action", "enroll").put("platform_user_id", id), true)
    fun renameContact(id: String, name: String) = requestManagement(JSONObject().put("action", "rename_contact").put("platform_user_id", id).put("display_name", name.trim()))
    fun deleteVoiceSample(id: String) = requestManagement(JSONObject().put("action", "delete_registration").put("registration_id", id))
    fun cancelEnrollment() {
        val task = pendingTask ?: run { mute(); return }
        if (waitingConfirmation) { mute(); status = "声纹已保存，正在等待后台确认"; changed(); return }
        requestManagement(JSONObject().put("action", "cancel_enrollment").put("registration_id", task.getJSONObject("registration").getString("registration_id")))
    }
    private fun requestManagement(command: JSONObject, beginRecording: Boolean = false) {
        try {
            require(foreground && synchronizedCatalog && modelReady) { "请先连接并等待目录同步" }
            if (command.getString("action") != "rename_contact") stop("准备管理声纹；麦克风关闭", true)
            val id = UUID.randomUUID().toString()
            val request = JSONObject().put("request_id", id).put("expected_revision", revision).put("command", command)
            store.rememberRequest(request)
            startEnrollmentRequest = if (beginRecording) id else null
            connection.send(JSONObject().put("type", "contact_action").put("request", request))
            status = "正在等待 Crabot 确认操作"
        } catch (e: Exception) { status = e.message ?: "操作尚未确认，请重试同步" }
        changed()
    }
    fun retrySynchronization() {
        if (!foreground) return
        if (!connected) { reconnect(); return }
        try {
            val requests = store.pendingRequests()
            requests.keys().forEach { id ->
                if (id in unknownRequests) connection.send(JSONObject().put("type", "contact_action").put("request", requests.getJSONObject(id)))
                else connection.send(JSONObject().put("type", "contact_action_query").put("request_id", id))
            }
            retryCompletion()
        } catch (_: Exception) { status = "同步未完成，原操作已保留" }
        changed()
    }
    private fun retryCompletion() {
        val completion = store.pendingCompletion() ?: return
        val task = pendingTask
        val id = completion.getString("registration_id")
        val active = (0 until registrations.length()).map { registrations.getJSONObject(it) }.any { it.getString("registration_id") == id && it.getString("status") == "active" }
        if (active) {
            completion.optString("request_id").takeIf { it.isNotEmpty() }?.let { store.resolveRequest(it) }
            store.resolveCompletion(); enrollmentCompleted = true; status = "声纹已同步到 Crabot"
            return
        }
        val revoked = (0 until registrations.length()).map { registrations.getJSONObject(it) }.any { it.getString("registration_id") == id && it.getString("status") == "revoked" }
        if (revoked) {
            store.resolveCompletion(); enrollmentCompleted = false; status = "录入任务或样本已撤销，请核对联系人状态"
            return
        }
        if (task?.getJSONObject("registration")?.getString("registration_id") == id && java.time.Instant.parse(task.getString("expires_at")).toEpochMilli() > System.currentTimeMillis()) {
            connection.send(JSONObject().put("type", "enrollment_done").put("registration_id", id).put("model_id", completion.getString("model_id")).put("enrollment_revision", completion.getLong("enrollment_revision")))
        }
    }
    private fun applyCatalog(catalog: JSONObject) {
        VoiceCatalog.validate(catalog)
        val nextRevision = VoiceCatalog.revision(catalog)
        if (nextRevision < revision) return
        if (nextRevision != revision && enrollment == null && mode in listOf(Mode.WAKE, Mode.FOLLOWUP, Mode.RECORDING, Mode.PROCESSING)) stop("声纹目录已更新，请重新开启语音", true)
        val clears = store.applyCatalog(catalog)
        if (revision != nextRevision) confirmedRevision = -1
        revision = nextRevision
        registrations = catalog.getJSONArray("registrations")
        val task = catalog.optJSONObject("pending_enrollment")
        if (enrollment != null && task?.getJSONObject("registration")?.getString("registration_id") != enrollment!!.getString("registration_id")) stop("声纹登记状态已更新；麦克风关闭", true)
        for (id in clears) connection.send(JSONObject().put("type", "registration_cleared").put("registration_id", id).put("enrollment_revision", revision))
        retryCompletion()
        connection.send(JSONObject().put("type", "catalog_applied").put("enrollment_revision", revision))
    }
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
        if (disableWake) { startEnrollmentRequest = null; enrollmentRequestId = null }
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
        speakerSummary = ""
        speakerReason = ""
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
            require(segments.map { it.speaker }.distinct().size == 1 && segments.sumOf { it.end - it.start } >= Identity.MIN_ENROLLMENT_SAMPLES) { "登记需一人清楚说话，请重新录这一段" }
            models!!.embedding(audio).also { require(it.isNotEmpty()) { "语音证据不足" } }
        }
        if (token != generation || SystemClock.elapsedRealtime() >= enrollmentDeadline) { v.fill(0f); return false }
        enrollmentVectors += v
        if (enrollmentVectors.size == 3) {
            val mean = FloatArray(v.size) { i -> enrollmentVectors.sumOf { it[i].toDouble() }.toFloat() / 3 }
            val target = enrollment ?: error("登记已取消")
            val completion = JSONObject().put("registration_id", target.getString("registration_id")).put("model_id", Models.EMBEDDING).put("enrollment_revision", enrollmentRevision)
            enrollmentRequestId?.let { completion.put("request_id", it) }
            try { store.saveEnrollment(target.getString("registration_id"), mean, completion) } finally { mean.fill(0f) }
            enrollmentVectors.forEach { it.fill(0f) }; enrollmentVectors.clear()
            connection.send(JSONObject().put("type", "enrollment_done").put("registration_id", target.getString("registration_id"))
                .put("model_id", Models.EMBEDDING).put("enrollment_revision", enrollmentRevision))
            enrollment = null; mode = Mode.CLOSED; status = "声纹样本完成，等待后台确认；麦克风关闭"
        } else { mode = Mode.CLOSED; status = "已录 ${enrollmentVectors.size}/3 段；点按录制下一段（不同内容，3–5 秒）" }
        return true
    }
    private suspend fun completeCommand(audio: ShortArray, token: Int): Boolean {
        val diagnostics = mutableListOf<Identity.Diagnostic>()
        val segments = withContext(Dispatchers.Default) { Identity.report(audio, models!!, store.vectors(), registrations) { diagnostics += it } }
        if (token != generation) return false
        speakerReason = diagnostics.mapNotNull { d -> d.reason?.let { if (diagnostics.size == 1) it else "speaker-${d.speaker}：$it" } }.joinToString("\n")
        diagnostics.forEach { d -> android.util.Log.i("CrabotVoiceIdentity", "speaker=${d.speaker} voiced_samples=${d.samples} candidates=${d.candidates} compared=${d.compared} score=${d.score} margin=${d.margin} reason=${d.reason}") }
        detail = (0 until segments.length()).joinToString("\n") { i ->
            val s = segments.getJSONObject(i)
            val r = (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("registration_id") == s.optString("registration_id") }
            "${s.getInt("start_sample") / 16000.0}–${s.getInt("end_sample") / 16000.0}s  ${s.getString("anonymous_speaker")}: ${r?.getString("display_name") ?: if (s.getString("identity_status") == "uncertain") "身份未确认（陌生人）" else "陌生人"}" }
        detail += diagnostics.joinToString("\n", prefix = "\n") { d -> "speaker-${d.speaker} 人声${d.samples / 16000.0}s，候选${d.candidates}，已比对${d.compared}，分数${d.score}，差值${d.margin}；${d.reason ?: "已匹配"}" }
        speakerSummary = (0 until segments.length()).map { i -> val segment = segments.getJSONObject(i); (0 until registrations.length()).map { registrations.getJSONObject(it) }.find { it.getString("registration_id") == segment.optString("registration_id") }?.getString("display_name") ?: if (segment.getString("identity_status") == "uncertain") "身份未确认" else "陌生人" }.distinct().joinToString("、")
        connection.send(JSONObject().put("type", "turn_end").put("turn_id", currentTurn).put("audio_sample_count", audio.size).put("sample_rate", 16000)
            .put("models", Models.versions()).put("enrollment_revision", revision).put("segments", segments))
        mode = Mode.WAITING; status = "已提交，等待接纳；麦克风关闭"
        val waitingTurn = currentTurn
        main.postDelayed({ expireWaitingTurn(waitingTurn) }, 60_000)
        return true
    }
    private fun expireWaitingTurn(waitingTurn: String?) {
        if (waitingTurn == currentTurn && mode == Mode.WAITING) stop("回答等待超时（60 秒）；已接纳任务仍由 Crabot 负责，请重新开启语音待机后询问", true)
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
        if (wakeEnabled && foreground && readyForConversation) { mode = Mode.WAKE; startMic(); status += "；恢复本地唤醒待机" }
        else manager.abandonAudioFocusRequest(focus)
        heartbeat(); changed()
    }
    fun confirmEnrollment() {
        try {
            require(foreground && connected && modelReady && !waitingConfirmation)
            if (enrollment == null) prepareEnrollment(pendingTask ?: error("没有待录入任务"))
            beginEnrollmentClip()
        }
        catch (e: Exception) { stop(e.message ?: "无法开始登记", true) }; changed()
    }
    private fun prepareEnrollment(task: JSONObject) {
        val registration = task.getJSONObject("registration")
        if (enrollment?.getString("registration_id") == registration.getString("registration_id")) return
        stop("请现场确认声纹录入", true)
        require(registration.getString("model_id") == Models.EMBEDDING)
        val remaining = java.time.Instant.parse(task.getString("expires_at")).toEpochMilli() - System.currentTimeMillis()
        require(remaining > 0) { "声纹录入已过期，请重新发起" }
        enrollment = registration; enrollmentRevision = task.getLong("enrollment_revision")
        enrollmentCompleted = false
        enrollmentDeadline = SystemClock.elapsedRealtime() + remaining
        status = "录入 ${enrollmentName}：三段不同短句，每段 3–5 秒"
        val id = registration.getString("registration_id")
        main.postDelayed({ if (enrollment?.getString("registration_id") == id && SystemClock.elapsedRealtime() >= enrollmentDeadline) stop("声纹录入任务已过期，请重新开始", true) }, remaining)
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
                    main.removeCallbacks(reconnectLater); reconnectDelay = 1000
                    val epoch = c.getString("connection_epoch")
                    if (previousEpoch != epoch) { stop("已连接；麦克风关闭", true); previousEpoch = epoch }
                    connectionEpoch = epoch
                    wakeWord = c.getJSONObject("config").getString("wake_word")
                    keyword = Models.keyword(wakeWord, tokens)
                    applyCatalog(c.getJSONObject("catalog"))
                    destinations = (0 until c.getJSONArray("destinations").length()).joinToString("\n") { i ->
                        val d = c.getJSONArray("destinations").getJSONObject(i); "${d.getString("capability").uppercase()}: ${d.getString("endpoint")}" }
                    status = "已连接，正在核对声纹目录；麦克风关闭"; heartbeat(); retrySynchronization()
                }
                "heartbeat" -> Unit
                "catalog" -> applyCatalog(c.getJSONObject("catalog"))
                "catalog_confirmed" -> {
                    if (c.getLong("enrollment_revision") == revision) {
                        confirmedRevision = revision
                        if (mode == Mode.CLOSED && !pendingManagement && !waitingConfirmation) status = "已连接，声纹目录已同步；麦克风关闭"
                    }
                }
                "contact_result" -> contactResult(c)
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
                    playbackId = c.getString("playback_id"); lastReply = c.getString("text"); status = "播放中可点按打断"; detail += "\n回答：${lastReply}"; heartbeat()
                    player = Playback(context, playbackId!!) { id, receipt, played -> main.post { playbackReceipt(id, receipt, played) } }
                }
                "playback_end" -> { if (c.getString("playback_id") == playbackId) { player!!.finish(c.getInt("sample_count")); lastReply = c.getString("text"); detail += "\n完整回答：${lastReply}" } }
                "response_end" -> { if (c.getString("turn_id") == currentTurn && player == null) finishResponse() }
                "playback_cancel" -> { if (c.getString("playback_id") == playbackId) stop("播报已取消；已接纳任务仍由 Crabot 负责", false) }
                "enroll" -> {
                    prepareEnrollment(c.getJSONObject("task"))
                }
                "clear_registration" -> { stop("联系人声纹已撤销；本轮已结束", true); clearRegistration(c.getString("registration_id"), c.getLong("enrollment_revision")) }
                "error" -> stop("语音端错误：${c.getString("message")}", true)
                else -> error("未知协议控制")
            }
            changed()
        } catch (_: Exception) { stop("协议、模型或登记版本不兼容；麦克风关闭", true); connection.disconnect(null); connectionEpoch = null; changed() }
    }
    private fun contactResult(c: JSONObject) {
        val id = c.getString("request_id")
        val request = store.pendingRequests().optJSONObject(id)
        val result = c.getJSONObject("result")
        val shouldBegin = startEnrollmentRequest == id && foreground
        applyCatalog(result.getJSONObject("catalog"))
        when (result.getString("status")) {
            "pending" -> {
                val task = result.getJSONObject("catalog").optJSONObject("pending_enrollment")
                if (request?.getJSONObject("command")?.getString("action") in listOf("enroll_new", "enroll") && task != null) {
                    prepareEnrollment(task); enrollmentRequestId = id
                    if (shouldBegin) beginEnrollmentClip()
                }
            }
            "completed", "rejected" -> {
                store.resolveRequest(id); unknownRequests.remove(id)
                if (store.pendingCompletion()?.optString("request_id") == id) store.resolveCompletion()
                if (result.getString("status") == "rejected") status = result.optString("message", "操作被拒绝，请核对最新目录后重试")
                else status = "后台已提交，正在核对本机状态"
                val remaining = store.pendingRequests(); remaining.keys().forEach { requestId -> connection.send(JSONObject().put("type", "contact_action_query").put("request_id", requestId)) }
            }
            "unknown" -> { unknownRequests.add(id); status = "操作尚未确认，原请求已保留；请点重试同步" }
            else -> error("管理结果无效")
        }
    }
    private fun clearRegistration(id: String, rev: Long) {
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
        if (foreground && readyForConversation) try {
            mode = Mode.FOLLOWUP; deadline = SystemClock.elapsedRealtime() + 8000; status = "播放完成；8 秒本地接话窗口"; startMic()
            main.postDelayed({ if (mode == Mode.FOLLOWUP && SystemClock.elapsedRealtime() >= deadline) {
                mic.stop(); captureGeneration++
                try { standby() } catch (_: Exception) { stop("无法恢复本地唤醒；麦克风关闭", true) }
            } }, 8000)
        } catch (_: Exception) { stop("无法开启接话窗口；麦克风关闭", true) }
        heartbeat(); changed()
    }
}
