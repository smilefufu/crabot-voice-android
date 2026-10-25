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
    enum class Mode { CLOSED, WAKE, RECORDING, PROCESSING, WAITING, PLAYING, FOLLOWUP, ENROLLING, OWNER_PREPARING, OWNER_RECORDING, GUIDED_WAITING, GUIDED_PLAYING }
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
    private val mic: AudioCapture = Microphone(context) { reason -> main.post { stop(reason, disableWake = true) } }
    private var player: Playback? = null
    private var playbackId: String? = null
    private var playbackTextSegments = 0L
    private var latencyTurn: String? = null
    private var captureEndedByButton = false
    private var interactionHadPlayback = false
    private var pairing: JSONObject? = null
    private var currentTurn: String? = null
    private var speechEnabled = false
    private var reservedPlayback: String? = null
    private var reservedContext: JSONObject? = null
    private var notificationPlayback = false
    private var playbackReturnMode = Mode.CLOSED
    private var playbackReturnDeadline = 0L
    private val playbackReceipts = runCatching { PlaybackReceiptJournal(java.io.File(context.filesDir, "playback-receipts.bin")) }.getOrNull()
    var pendingResults = 0
        private set
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
    private var localOwnerName: String? = null
    var ownerNeedsRetry = false
        private set
    private var enrollment: JSONObject? = null
    private var enrollmentRevision = 0L
    private var enrollmentDeadline = 0L
    private val enrollmentVectors = mutableListOf<FloatArray>()
    private var guided: GuidedEnrollment? = null
    private var guidedPlaybackId: String? = null
    private var guidedResumeWake = false
    private var guidedResumeEpoch: String? = null
    private var guidedAwaitingResult = false
    private var guidedPrivateComplete = false
    private var guidedSourceRequest: String? = null
    private var guidedResultPlayback = false
    val guidedEnrollmentActive get() = guided != null
    var guidedPromptText = ""
        private set
    private var registrations = store.catalog()?.optJSONArray("registrations") ?: JSONArray()
    private var revision = store.catalog()?.getLong("enrollment_revision") ?: 0L
    private var confirmedRevision = -1L
    private var startEnrollmentRequest: String? = null
    private var enrollmentRequestId: String? = null
    private val unknownRequests = mutableSetOf<String>()
    private var connectionEpoch: String? = null
    private var pendingOwnerOffer: JSONObject? = null
    private var ownerRecoveryReason: String? = null
    val ownerRecoveryHint get() = ownerRecoveryReason ?: ""
    val canManageContacts get() = paired && store.pendingOwnerSaved() == null
    private var setupComplete = false
    private var servicesConfigured = false
    private var previousEpoch: String? = null
    private val reconnectLater: Runnable = Runnable { if (foreground && !connected && store.pairing() != null) reconnect() }
    private var reconnectDelay = 1000L
    private val connection: TerminalConnection = TerminalConnection(::control, ::audio) { reason ->
        stop(reason, disableWake = true); connectionEpoch = null; confirmedRevision = -1; pendingOwnerOffer = null
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
    val owner get() = store.owner()
    val ownerActivation get() = store.ownerActivation()
    val needsOwnerEnrollment get() = !paired && store.compatible && !store.needsRepair && owner == null
    val ownerEnrollmentActive get() = localOwnerName != null
    val ownerPrompt get() = listOf("你好，这是我的第一段声音，请记住我的说话方式。", "我正在登记自己的声音，以后请认出是我在说话。", "这台设备将成为我的语音助手，很高兴认识你。")[(enrollmentStep - 1).coerceIn(0, 2)]
    val modelReady get() = models != null && keyword.isNotEmpty()
    val keepScreenOn get() = foreground && (mode != Mode.CLOSED || ownerEnrollmentActive)
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
    val readyForConversation get() = setupComplete && servicesConfigured && store.pendingOwnerSaved() == null && synchronizedCatalog && store.pendingRequests().length() == 0 && !waitingConfirmation && pendingTask == null
    val pendingManagement get() = store.pendingRequests().length() > 0
    fun hasVoiceSample(id: String) = store.hasVector(id)
    val microphoneState get() = when (mode) { Mode.WAKE, Mode.FOLLOWUP -> "wake"; Mode.RECORDING, Mode.ENROLLING, Mode.OWNER_RECORDING -> "recording"; else -> "closed" }
    init {
        connection.heartbeat = { JSONObject().put("type", "heartbeat").put("foreground", foreground)
            .put("microphone", microphoneState).put("playback", if (mode in listOf(Mode.PLAYING, Mode.GUIDED_PLAYING)) "playing" else "idle").put("enrollment_revision", revision).put("speech_enabled", speechEnabled).put("activity", outputActivity()) }
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
                status = if (store.needsRepair && store.compatible) "旧配对需重新扫码；同渠道声纹已保留，请先在 Admin 撤销旧终端。" else if (needsOwnerEnrollment) "已准备好，请填写名字并开始登记；麦克风关闭" else if (store.compatible) "模型已加载；麦克风关闭。声纹和唤醒尚待真人校准验收。" else "配对数据不兼容；原文件保留，请明确清除本机数据后重新配对"
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
    fun enrollOwner(name: String) {
        try {
            require(foreground && modelReady && needsOwnerEnrollment) { "请等待本机模型就绪；已有配对不能重新认主" }
            val descriptor = DeviceOwner.create(name)
            stop("准备登记设备所有者；麦克风关闭", true)
            localOwnerName = descriptor.getString("display_name")
            enrollmentDeadline = SystemClock.elapsedRealtime() + 60_000
            val token = generation
            main.postDelayed({ if (token == generation && ownerEnrollmentActive) stop("本人声纹登记已超时，请重新开始", true) }, 60_000)
            queueOwnerClip(token)
        } catch (e: Exception) { stop(e.message ?: "无法开始本人登记", true) }
        changed()
    }
    fun retryOwnerClip() {
        if (ownerEnrollmentActive && ownerNeedsRetry && foreground) queueOwnerClip(generation)
    }
    private fun queueOwnerClip(token: Int) {
        ownerNeedsRetry = false; mode = Mode.OWNER_PREPARING
        fun countdown(remaining: Int) {
            if (token != generation || !ownerEnrollmentActive || !foreground) return
            if (SystemClock.elapsedRealtime() >= enrollmentDeadline) { stop("本人声纹登记已超时，请重新开始", true); return }
            if (remaining > 0) {
                status = "第 $enrollmentStep/3 段，$remaining 秒后开始，请看读句准备"; changed()
                main.postDelayed({ countdown(remaining - 1) }, 1000)
            } else try {
                count = 0; pcm.fill(0); startedAt = SystemClock.elapsedRealtime(); lastSpeechAt = startedAt; hasSpeech = false
                mode = Mode.OWNER_RECORDING; startMic(); status = "请自然朗读第 $enrollmentStep/3 段，说完会自动结束"; changed()
            } catch (e: Exception) { stop(e.message ?: "无法开启麦克风", true) }
        }
        countdown(3)
    }
    private fun ownerRetry(reason: String) {
        count = 0; pcm.fill(0); mode = Mode.OWNER_PREPARING; ownerNeedsRetry = true
        status = "$reason；请重录第 $enrollmentStep/3 段"; changed()
    }
    fun scan(raw: String) {
        try {
            stop("开始配对；麦克风关闭", disableWake = true)
            check(store.compatible) { "旧 WIP 配对/声纹不兼容，请明确清除本机数据后重新配对" }
            val qr = JSONObject(raw)
            require(qr.getInt("protocol_version") == Wire.PROTOCOL_VERSION) { "协议版本不兼容，需要 v${Wire.PROTOCOL_VERSION} 配对码" }
            require(System.currentTimeMillis() < java.time.Instant.parse(qr.getString("expires_at")).toEpochMilli()) { "二维码已过期，请在 Admin 重新生成后再扫描" }
            store.checkChannel(qr.getString("channel_id"))
            TerminalEndpoint.parse(qr.getString("url"), qr.getString("channel_id"))
            val descriptor = store.owner() ?: error("请先登记本人声纹；已有设备请先连接原 Crabot 恢复所有者引用")
            pairing = qr
            connectionEpoch = null
            connection.connect(qr, JSONObject().put("type", "pair").put("protocol_version", Wire.PROTOCOL_VERSION).put("nonce", qr.getString("nonce")).put("owner", descriptor))
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
                .put("models", Models.versions()).put("aec_verified", false).apply { store.owner()?.let { put("owner", it) } })
        } catch (e: Exception) { status = e.message ?: "请扫描 Admin 的 v${Wire.PROTOCOL_VERSION} 配对二维码" }
        changed()
    }
    fun forget() {
        stop("本机已清除配对和登记；请在 Admin 撤销旧终端后重新配对", true)
        connection.disconnect(null); connectionEpoch = null; store.forget()
        revision = 0; confirmedRevision = -1; registrations = JSONArray(); unknownRequests.clear(); enrollmentCompleted = false; pairing = null
        pendingOwnerOffer = null; ownerRecoveryReason = null; setupComplete = false; servicesConfigured = false
        changed()
    }
    private fun heartbeat() { if (connection.ready) try { connection.send(connection.heartbeat!!.invoke()) } catch (_: Exception) { connection.disconnect("连接已中断") } }
    fun toggleWake() {
        if (wakeEnabled) stop("麦克风关闭", true)
        else try {
            require(foreground && readyForConversation && modelReady) { "请等待连接及声纹目录同步，或完成当前登记" }
            stop("开启本地唤醒；不上传待机音频", true)
            wakeEnabled = true; speechEnabled = true; mode = Mode.WAKE; startMic(); status = "本地唤醒待机：$wakeWord。准确率与功耗尚未验收。"; heartbeat()
        } catch (e: Exception) { stop(e.message ?: "无法开启麦克风", true) }
        changed()
    }
    fun recordButton() {
        if (mode == Mode.RECORDING || mode == Mode.ENROLLING) { captureEndedByButton = true; finishCapture(); return }
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
        if (guided != null) { stop("本次录入已取消；麦克风关闭", true); return }
        val task = pendingTask ?: run { mute(); return }
        if (waitingConfirmation) { mute(); status = "声纹已保存，正在等待后台确认"; changed(); return }
        requestManagement(JSONObject().put("action", "cancel_enrollment").put("registration_id", task.getJSONObject("registration").getString("registration_id")))
    }
    private fun requestManagement(command: JSONObject, beginRecording: Boolean = false) {
        try {
            require(foreground && synchronizedCatalog && modelReady && canManageContacts) { "请先连接并等待目录同步" }
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
            heartbeat(); retryOwnerSaved()
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
        if (store.owner() == null) pendingOwnerOffer?.let { applyOwnerActivation(it) }
        if (revision != nextRevision) confirmedRevision = -1
        revision = nextRevision
        registrations = catalog.getJSONArray("registrations")
        val task = catalog.optJSONObject("pending_enrollment")
        if (enrollment != null && task?.getJSONObject("registration")?.getString("registration_id") != enrollment!!.getString("registration_id")) stop("声纹登记状态已更新；麦克风关闭", true)
        for (id in clears) connection.send(JSONObject().put("type", "registration_cleared").put("registration_id", id).put("enrollment_revision", revision))
        retryCompletion(); retryOwnerSaved()
        connection.send(JSONObject().put("type", "catalog_applied").put("enrollment_revision", revision))
    }
    fun stop(reason: String, disableWake: Boolean = false) {
        android.util.Log.i("CrabotVoiceLifecycle", "stop from=$mode disable_wake=$disableWake wake_enabled=$wakeEnabled caller=${Throwable().stackTrace.getOrNull(1)?.methodName}")
        val guidedId = guided?.operationId
        guided = null; guidedPlaybackId = null; guidedResumeWake = false; guidedResumeEpoch = null; guidedAwaitingResult = false; guidedPrivateComplete = false; guidedPromptText = ""
        guidedSourceRequest = null; guidedResultPlayback = false
        if (guidedId != null) {
            wakeEnabled = false; speechEnabled = false
            if (connected) try { connection.send(JSONObject().put("type", "guided_cancel").put("operation_id", guidedId)) } catch (_: Exception) {}
        }
        generation++; captureGeneration++; processing?.cancel(); processing = null
        mic.stop(); mode = Mode.CLOSED
        val turn = currentTurn; val playback = playbackId ?: reservedPlayback
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
        reservedPlayback = null; reservedContext = null; notificationPlayback = false
        if (disableWake) { wakeEnabled = false; speechEnabled = false }
        if (disableWake || guidedId != null) { startEnrollmentRequest = null; enrollmentRequestId = null }
        enrollmentVectors.forEach { it.fill(0f) }; enrollmentVectors.clear()
        localOwnerName = null; ownerNeedsRetry = false
        if (disableWake || guidedId != null) enrollment = null
        manager.abandonAudioFocusRequest(focus)
        status = reason; heartbeat(); changed()
    }
    private fun startMic() {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法获得音频焦点" }
        models!!.reset(keyword)
        val token = ++captureGeneration
        val captureMode = mode
        mic.start { audio ->
            val flags = try { models!!.feed(audio, captureMode != Mode.WAKE, captureMode == Mode.WAKE || captureMode == Mode.ENROLLING && guided != null) } catch (_: Exception) { audio.fill(0); main.post { stop("模型音频检测失败", true) }; return@start }
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
        speechEnabled = true
        mode = Mode.RECORDING; currentTurn = UUID.randomUUID().toString(); readyTurn = null
        sequence = 0; sentSamples = 0; count = 0; startedAt = SystemClock.elapsedRealtime(); hasSpeech = false; lastSpeechAt = startedAt; captureEndedByButton = false
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
            Mode.RECORDING, Mode.ENROLLING, Mode.OWNER_RECORDING -> {
                if (mode == Mode.ENROLLING && guided != null && flags and 2 != 0) { stop("本次录入已取消；麦克风关闭", true); return }
                val n = audio.size.coerceAtMost(Wire.MAX_INPUT - count)
                audio.copyInto(pcm, count, 0, n); count += n
                if (flags and 1 != 0) { hasSpeech = true; lastSpeechAt = now }
                if (mode == Mode.RECORDING) {
                    if (readyTurn == currentTurn) upload()
                    else check(now - startedAt <= 1000) { "终端未就绪，录音已结束" }
                    if (count == Wire.MAX_INPUT || (!hasSpeech && now - startedAt >= 3000) || (hasSpeech && now - lastSpeechAt >= 800)) finishCapture()
                } else if (now >= enrollmentDeadline) stop("登记任务已过期，请重新开始", true)
                else if (now - startedAt >= 5000 || count >= 80000 || (mode == Mode.OWNER_RECORDING || guided != null) && count >= 48000 && hasSpeech && now - lastSpeechAt >= 800) finishCapture()
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
        val localOwner = mode == Mode.OWNER_RECORDING
        val enroll = localOwner || mode == Mode.ENROLLING
        mic.stop(); captureGeneration++
        var peak = 0
        var energy = 0.0
        for (i in 0 until count) { val sample = pcm[i].toInt(); peak = maxOf(peak, kotlin.math.abs(sample)); energy += sample.toDouble() * sample }
        val rms = if (count == 0) 0 else kotlin.math.sqrt(energy / count).toInt()
        android.util.Log.i("CrabotVoiceInput", "samples=$count peak=$peak rms=$rms vad_seen=$hasSpeech")
        if (!hasSpeech || count == 0) {
            if (guided != null) retryGuided("没听清这一段，请再读一次")
            else if (localOwner) ownerRetry("未检测到清楚的人声") else { stop("未检测到有效人声，请重说", false); standby() }
            return
        }
        if (enroll && count !in 48000..80000) {
            if (guided != null) retryGuided("这一段请完整读完")
            else if (localOwner) ownerRetry("这一段需完整朗读 3–5 秒") else { count = 0; pcm.fill(0); mode = Mode.CLOSED; status = "每段登记需 3–5 秒；请重新录这一段"; changed() }
            return
        }
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
                val completed = if (localOwner) completeLocalOwner(audio, token) else if (enroll) completeEnrollment(audio, token) else completeCommand(audio, token)
                if (!completed) return@launch
                heartbeat(); changed()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) {
                android.util.Log.w("CrabotVoiceInput", "processing_failed enrollment=$enroll samples=${audio.size}", e)
                if (guided != null && e !is ExistingVoiceIdentityException && enrollmentVectors.size < 3) retryGuided(e.message ?: "请重新读这一段")
                else if (localOwner && ownerEnrollmentActive && enrollmentVectors.size < 3) ownerRetry(e.message ?: "本段声音不清楚")
                else stop(e.message ?: "声纹处理失败；整轮未提交", localOwner)
            } }
            finally { audio.fill(0) }
        }
    }
    private suspend fun enrollmentCandidate(): FloatArray {
        val samples = enrollmentVectors.map { it.copyOf() }
        return try { withContext(Dispatchers.Default) { Identity.enrollmentCandidate(samples, models!!) } }
            finally { samples.forEach { it.fill(0f) } }
    }
    private suspend fun completeLocalOwner(audio: ShortArray, token: Int): Boolean {
        val sample = withContext(Dispatchers.Default) { Identity.enrollmentSample(audio, models!!) }
        if (token != generation || !ownerEnrollmentActive || SystemClock.elapsedRealtime() >= enrollmentDeadline) { sample.fill(0f); return false }
        enrollmentVectors += sample
        if (enrollmentVectors.size < 3) { queueOwnerClip(token); return true }
        val candidate = enrollmentCandidate()
        try {
            if (token != generation || !ownerEnrollmentActive || SystemClock.elapsedRealtime() >= enrollmentDeadline) return false
            store.saveLocalOwner(localOwnerName!!, candidate)
        } finally { candidate.fill(0f) }
        stop("本人声纹已录入，接下来扫描 Crabot 配对二维码；麦克风关闭", true)
        return true
    }
    private suspend fun completeEnrollment(audio: ShortArray, token: Int): Boolean {
        val v = withContext(Dispatchers.Default) { Identity.enrollmentSample(audio, models!!) }
        if (token != generation || SystemClock.elapsedRealtime() >= enrollmentDeadline) { v.fill(0f); return false }
        if (guided != null) {
            val previous = enrollmentVectors.map { it.copyOf() }
            try { withContext(Dispatchers.Default) { Identity.requireConsistentSample(v, previous, models!!) } }
            catch (e: Exception) { v.fill(0f); throw e }
            finally { previous.forEach { it.fill(0f) } }
            if (token != generation) { v.fill(0f); return false }
        }
        enrollmentVectors += v
        if (enrollmentVectors.size == 3) {
            val mean = enrollmentCandidate()
            if (token != generation || SystemClock.elapsedRealtime() >= enrollmentDeadline) { mean.fill(0f); return false }
            val target = enrollment ?: error("登记已取消")
            if (guided != null) try { withContext(Dispatchers.Default) { Identity.requireDistinctContact(mean, models!!, store.vectors(), registrations, target.getString("platform_user_id")) } }
            catch (e: Exception) { mean.fill(0f); throw e }
            if (token != generation) { mean.fill(0f); return false }
            val completion = JSONObject().put("registration_id", target.getString("registration_id")).put("model_id", Models.EMBEDDING).put("enrollment_revision", enrollmentRevision)
            enrollmentRequestId?.let { completion.put("request_id", it) }
            try { store.saveEnrollment(target.getString("registration_id"), mean, completion) } finally { mean.fill(0f) }
            enrollmentVectors.forEach { it.fill(0f) }; enrollmentVectors.clear()
            guided?.saved()
            connection.send(JSONObject().put("type", "enrollment_done").put("registration_id", target.getString("registration_id"))
                .put("model_id", Models.EMBEDDING).put("enrollment_revision", enrollmentRevision))
            enrollment = null; mode = if (guided != null) Mode.GUIDED_WAITING else Mode.CLOSED; status = "声音已保存，等待完成登记；麦克风关闭"
        } else if (guided != null) requestGuidedPrompt()
        else { mode = Mode.CLOSED; status = "已录 ${enrollmentVectors.size}/3 段；点按录制下一段（不同内容，3–5 秒）" }
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
        if (waitingTurn == currentTurn && mode == Mode.WAITING && !interactionHadPlayback && reservedPlayback == null) releaseWaitingTurn()
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
        mic.stop(); captureGeneration++
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
    private fun prepareGuided(value: JSONObject) {
        require(foreground && connected && speechEnabled && guided == null && !waitingConfirmation && player == null
            && mode in listOf(Mode.CLOSED, Mode.WAKE, Mode.FOLLOWUP, Mode.WAITING))
        require(value.getString("connection_epoch") == connectionEpoch)
        val task = value.getJSONObject("task")
        val registration = task.getJSONObject("registration")
        require(registration.getString("status") == "pending" && registration.getString("model_id") == Models.EMBEDDING)
        val remaining = java.time.Instant.parse(task.getString("expires_at")).toEpochMilli() - System.currentTimeMillis()
        require(remaining in 1..60_000 && task.getLong("enrollment_revision") >= revision)
        val id = value.getString("operation_id"); require(UUID.fromString(id).toString() == id)
        val restore = wakeEnabled
        stop("准备记住 ${registration.getString("display_name")} 的声音", false)
        guidedResumeWake = restore; guidedResumeEpoch = connectionEpoch
        guided = GuidedEnrollment(id, connectionEpoch!!, SystemClock.elapsedRealtime() + remaining, value.getString("delivery"))
        guidedSourceRequest = value.getString("request_id").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        enrollment = JSONObject(registration.toString()); enrollmentRevision = task.getLong("enrollment_revision")
        enrollmentDeadline = guided!!.deadline; enrollmentRequestId = id; enrollmentCompleted = false
        main.postDelayed({ if (guided?.operationId == id && guided?.sampled == false) stop("本次录入已超时；麦克风关闭", true) }, remaining)
        requestGuidedPrompt()
    }
    private fun requestGuidedPrompt() {
        val session = guided ?: return
        require(foreground && connected && connectionEpoch == session.epoch && SystemClock.elapsedRealtime() < session.deadline && !session.sampled)
        mic.stop(); captureGeneration++; mode = Mode.GUIDED_WAITING
        status = "正在准备第 ${enrollmentVectors.size + 1}/3 段提示；麦克风关闭"
        heartbeat()
        connection.send(JSONObject().put("type", "guided_prompt").put("operation_id", session.operationId).put("step", enrollmentVectors.size + 1))
        changed()
    }
    private fun retryGuided(reason: String) {
        val session = guided ?: return
        mic.stop(); captureGeneration++; count = 0; pcm.fill(0); mode = Mode.GUIDED_WAITING
        status = reason; heartbeat(); changed()
        val token = generation
        main.postDelayed({ if (token == generation && guided === session) try { requestGuidedPrompt() } catch (_: Exception) { stop("本次录入已结束；麦克风关闭", true) } }, 500)
    }
    private fun startGuidedPlayback(control: JSONObject) {
        val session = guided ?: error("没有当前录入任务")
        require(control.getString("operation_id") == session.operationId && connected && foreground && speechEnabled
            && connectionEpoch == session.epoch && mode == Mode.GUIDED_WAITING && player == null)
        val step = control.getInt("step"); require(step == enrollmentVectors.size + 1)
        val id = control.getString("playback_id"); require(UUID.fromString(id).toString() == id)
        session.prompt(step, id, SystemClock.elapsedRealtime()); guidedPlaybackId = id
        guidedPromptText = control.getString("text"); require(guidedPromptText.length in 1..512)
        mic.stop(); captureGeneration++; mode = Mode.GUIDED_PLAYING
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        player = Playback(context, id, finite = true) { playback, receipt, played -> main.post {
            if (guided !== session || guidedPlaybackId != playback) return@post
            try {
                if (receipt == "completed") { session.played(playback, (played ?: 0L).toInt()); player = null; mode = Mode.GUIDED_WAITING }
                val report = JSONObject().put("type", "guided_playback").put("operation_id", session.operationId).put("playback_id", playback).put("status", receipt)
                played?.let { report.put("played_samples", it) }; connection.send(report)
                if (receipt !in listOf("started", "completed")) stop("提示播放中断，本次录入已停止", true)
                heartbeat(); changed()
            } catch (_: Exception) { stop("提示播放未确认；麦克风关闭", true) }
        } }
        status = "请听提示，第 $step/3 段"; heartbeat()
    }
    private fun control(c: JSONObject) {
        try {
            if (c.optString("type") in listOf("hello", "turn_ready", "turn_status", "playback_prepare", "playback_start", "response_end", "error")) {
                android.util.Log.i("CrabotVoiceLifecycle", "control=${c.getString("type")} mode=$mode wake_enabled=$wakeEnabled")
            }
            when (c.getString("type")) {
                "guided_enroll" -> prepareGuided(c.getJSONObject("enrollment"))
                "guided_prompt_start" -> startGuidedPlayback(c)
                "guided_prompt_end" -> {
                    val session = guided ?: error("没有当前录入任务")
                    require(c.getString("operation_id") == session.operationId && c.getString("playback_id") == guidedPlaybackId && mode == Mode.GUIDED_PLAYING)
                    val count = c.getInt("sample_count"); session.ended(c.getString("playback_id"), count); player!!.finish(count.toLong())
                }
                "guided_record" -> {
                    val session = guided ?: error("没有当前录入任务")
                    require(c.getString("operation_id") == session.operationId && mode == Mode.GUIDED_WAITING && speechEnabled && player == null)
                    session.record(c.getString("playback_id"), c.getInt("step"), connectionEpoch, foreground, connected, SystemClock.elapsedRealtime())
                    beginEnrollmentClip()
                }
                "guided_end" -> {
                    val session = guided ?: return
                    require(c.getString("operation_id") == session.operationId && c.getString("status") in listOf("completed", "rejected"))
                    if (c.getString("status") == "completed" && session.sampled) {
                        mic.stop(); captureGeneration++; guided = null; guidedPlaybackId = null; enrollment = null; mode = Mode.CLOSED
                        guidedAwaitingResult = guidedResumeWake && guidedResumeEpoch == connectionEpoch
                        guidedPrivateComplete = session.delivery == "requester_private"
                        status = c.getString("message"); heartbeat()
                        restoreAfterPrivateManagement()
                    } else stop(c.getString("message"), true)
                }
                "pair_waiting" -> { require(c.getString("pairing_id") == pairing!!.getString("pairing_id") && c.getString("verification_code") == pairing!!.getString("verification_code")); status = "核对码：${c.getString("verification_code")}\n请在 Admin 确认；麦克风关闭"; detail = status }
                "paired" -> {
                    val qr = pairing ?: error("无配对任务")
                    val saved = JSONObject().put("protocol_version", Wire.PROTOCOL_VERSION).put("url", qr.getString("url")).put("channel_id", qr.getString("channel_id")).put("credential", c.getString("credential"))
                    if (qr.has("tls_fingerprint_sha256")) saved.put("tls_fingerprint_sha256", qr.getString("tls_fingerprint_sha256"))
                    try {
                        val activation = c.optJSONObject("owner_activation")
                        if (activation?.getString("status") == "pending") store.saveOwnerPairing(saved, activation) else store.savePairing(saved)
                    }
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
                    heartbeat()
                    val setup = c.getString("setup_status"); require(setup in listOf("draft", "complete"))
                    setupComplete = setup == "complete"
                    val config = c.getJSONObject("config")
                    servicesConfigured = !config.isNull("asr") && !config.isNull("tts")
                    wakeWord = config.getString("wake_word")
                    keyword = Models.keyword(wakeWord, tokens)
                    val activation = c.optJSONObject("owner_activation")
                    pendingOwnerOffer = activation?.let { JSONObject(it.toString()) }; ownerRecoveryReason = null
                    if (activation?.getString("status") == "completed" && store.ownerActivation() != null) applyOwnerActivation(activation)
                    applyCatalog(c.getJSONObject("catalog"))
                    if (activation != null) applyOwnerActivation(activation)
                    destinations = (0 until c.getJSONArray("destinations").length()).joinToString("\n") { i ->
                        val d = c.getJSONArray("destinations").getJSONObject(i); "${d.getString("capability").uppercase()}: ${d.getString("endpoint")}" }
                    status = connectionSummary()
                    heartbeat(); retryOwnerSaved(); retrySynchronization(); retryPlaybackReceipts()
                }
                "owner_activation" -> {
                    applyOwnerActivation(c.getJSONObject("activation"))
                    if (mode == Mode.CLOSED) status = connectionSummary()
                }
                "setup_status" -> {
                    val state = c.getString("status"); require(state in listOf("draft", "complete"))
                    setupComplete = state == "complete"
                    if (!setupComplete) stop("请在后台完成语音创建向导；麦克风关闭", true)
                    else if (mode == Mode.CLOSED) status = connectionSummary()
                }
                "heartbeat" -> retryPlaybackReceipts()
                "catalog" -> applyCatalog(c.getJSONObject("catalog"))
                "catalog_confirmed" -> {
                    if (c.getLong("enrollment_revision") == revision) {
                        confirmedRevision = revision
                        restoreAfterPrivateManagement()
                        if (mode == Mode.CLOSED && !pendingManagement && !waitingConfirmation && store.pendingOwnerSaved() == null) status = connectionSummary()
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
                "playback_prepare" -> preparePlayback(c)
                "playback_confirmed" -> receiptScope()?.let { playbackReceipts?.confirm(it, c.getString("playback_id")) }
                "pending_results" -> { val count = c.getInt("count"); require(count >= 0); pendingResults = count }
                "playback_start" -> {
                    require(connected && foreground && speechEnabled && reservedPlayback == c.getString("playback_id") && mode == Mode.WAITING && player == null)
                    val prepared = reservedContext ?: error("播放未准备")
                    require(prepared.getString("connection_epoch") == connectionEpoch)
                    notificationPlayback = prepared.getString("kind") == "notification"
                    require(if (notificationPlayback) c.getString("turn_id") == reservedPlayback else currentTurn == c.getString("turn_id"))
                    mic.stop(); captureGeneration++; mode = Mode.PLAYING
                    check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    playbackId = c.getString("playback_id"); playbackTextSegments = 0; lastReply = ""; status = "播放中可点按打断"; heartbeat()
                    player = Playback(context, playbackId!!, onProgress = { played -> main.post {
                        if (connected && playbackId == c.getString("playback_id")) connection.send(JSONObject().put("type", "playback_progress").put("playback_id", playbackId).put("played_samples", played))
                    } }) { id, receipt, played -> main.post { playbackReceipt(id, receipt, played) } }
                }
                "playback_text" -> {
                    require(mode == Mode.PLAYING && c.getString("playback_id") == playbackId && Wire.sampleCount(c.get("sequence")) == playbackTextSegments)
                    val text = c.getString("text"); require(text.codePointCount(0, text.length) in 1..120)
                    lastReply += text; playbackTextSegments++
                }
                "playback_end" -> { if (c.getString("playback_id") == playbackId) { require(Wire.sampleCount(c.get("text_segment_count")) == playbackTextSegments); player!!.finish(Wire.sampleCount(c.get("sample_count"))) } }
                "response_end" -> { if (c.getString("turn_id") == currentTurn && player == null) finishResponse() }
                "playback_cancel" -> if (c.getString("playback_id") == (playbackId ?: reservedPlayback)) {
                    val restore = notificationPlayback || reservedContext?.optString("kind") == "notification"
                    stop("播报已取消；已接纳任务仍由 Crabot 负责", false)
                    if (restore) restoreAfterNotification()
                }
                "enroll" -> {
                    prepareEnrollment(c.getJSONObject("task"))
                }
                "clear_registration" -> { stop("联系人声纹已撤销；本轮已结束", true); clearRegistration(c.getString("registration_id"), c.getLong("enrollment_revision")) }
                "error" -> if (c.getString("code") == "VOICE_REPLY_EXPIRED") {
                    if (c.optString("turn_id") == currentTurn && mode == Mode.WAITING) releaseWaitingTurn()
                } else stop("语音端错误：${c.getString("message")}", true)
                else -> error("未知协议控制")
            }
            changed()
        } catch (_: Exception) { stop("协议、模型或登记版本不兼容；麦克风关闭", true); connection.disconnect(null); connectionEpoch = null; changed() }
    }
    private fun connectionSummary(): String = when {
        ownerRecoveryReason != null -> ownerRecoveryReason!!
        store.pendingOwnerSaved() != null -> "已连接，正在完成所有者关联；麦克风关闭"
        !servicesConfigured -> "已连接，请在 Crabot 后台完成语音服务配置；麦克风关闭"
        !setupComplete -> "已连接，请在后台完成语音创建向导；麦克风关闭"
        !synchronizedCatalog -> "已连接，正在核对声纹目录；麦克风关闭"
        else -> "已连接，声纹目录已同步；麦克风关闭"
    }
    private fun applyOwnerActivation(activation: JSONObject) {
        DeviceOwner.validateActivation(activation)
        pendingOwnerOffer = JSONObject(activation.toString())
        if (store.owner() == null) {
            try { store.adoptOwner(activation, models!!.dimension()) }
            catch (_: Exception) { ownerRecoveryReason = "本机缺少可用的本人声纹，请在后台发起重录恢复；原联系人和配对已保留"; return }
        }
        ownerRecoveryReason = null
        val local = store.ownerActivation() ?: error("本机所有者尚未关联；请在 Admin 恢复既有渠道身份")
        require(DeviceOwner.sameActivation(local, activation)) { "所有者引用与当前渠道不符，请在 Admin 恢复" }
        if (activation.getString("status") == "completed") store.completeOwnerActivation(activation)
        else require(local.getString("status") != "completed") { "所有者关联状态不能回退" }
    }
    private fun retryOwnerSaved() {
        if (connected && foreground) store.pendingOwnerSaved()?.let { connection.send(JSONObject().put("type", "owner_saved").put("saved", it)) }
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
        if (mode == Mode.GUIDED_PLAYING && foreground) {
            val frame = Wire.output(bytes)
            if (frame.id != guidedPlaybackId) { frame.samples.fill(0); return }
            try { player!!.add(frame) } catch (e: Exception) { frame.samples.fill(0); throw e }
            return
        }
        if (mode != Mode.PLAYING || !foreground) return
        val frame = Wire.output(bytes)
        if (frame.id != playbackId) { frame.samples.fill(0); return }
        try { player!!.add(frame) } catch (e: Exception) { frame.samples.fill(0); throw e }
    }
    private fun playbackReceipt(id: String, receipt: String, played: Long?) {
        if (receipt == "started" && id == playbackId && !notificationPlayback && currentTurn != null && latencyTurn != currentTurn) {
            latencyTurn = currentTurn
            android.util.Log.i("CrabotVoiceLatency", "turn=" + currentTurn + " playback=" + id + " button_end=" + captureEndedByButton + " first_audio_ms=" + (SystemClock.elapsedRealtime() - lastSpeechAt))
        }
        if (receipt != "started") try {
            val scope = receiptScope() ?: error("播放回执缺少配对")
            val journal = playbackReceipts ?: error("播放记录无法读取")
            journal.remember(PlaybackReceipt(scope, id, receipt, played))
        } catch (_: Exception) { stop("播放回执暂时无法保存，未记作送达；原记录保留", true); return }
        if (connected) try {
            val control = JSONObject().put("type", "playback").put("playback_id", id).put("status", receipt)
            if (played != null) control.put("played_samples", played)
            connection.send(control)
        } catch (_: Exception) {}
        if (id != playbackId) return
        if (receipt == "completed") {
            player = null; playbackId = null; reservedPlayback = null; reservedContext = null
            if (notificationPlayback) { notificationPlayback = false; restoreAfterNotification(guidedResultPlayback); guidedResultPlayback = false }
            else { interactionHadPlayback = true; mode = Mode.WAITING; status = "播报完成，等待后续答复；麦克风关闭"; heartbeat() }
        } else if (receipt != "started") {
            val restore = notificationPlayback
            if (guidedResultPlayback) { guidedAwaitingResult = false; guidedResumeWake = false; guidedResumeEpoch = null; wakeEnabled = false; guidedResultPlayback = false }
            interactionHadPlayback = false; player = null; playbackId = null; reservedPlayback = null; reservedContext = null; notificationPlayback = false
            if (restore) restoreAfterNotification() else { mode = Mode.CLOSED; heartbeat() }
            status = "播放${if (receipt == "failed") "失败" else "已打断"}，未自动重播"
        }
        changed()
    }
    private fun outputActivity(): String = when {
        guided != null -> "management"
        reservedPlayback != null || mode == Mode.PLAYING -> "answering"
        mode in listOf(Mode.ENROLLING, Mode.OWNER_PREPARING, Mode.OWNER_RECORDING) || pendingTask != null || waitingConfirmation -> "management"
        mode == Mode.RECORDING -> "recording"
        mode == Mode.PROCESSING || mode == Mode.WAITING -> "processing"
        else -> "idle"
    }
    private fun receiptScope(): String? = store.pairing()?.optString("credential")?.takeIf { it.isNotBlank() }?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }
    private fun retryPlaybackReceipts() {
        val scope = receiptScope() ?: return
        for (receipt in playbackReceipts?.pending(scope).orEmpty()) {
            val value = JSONObject().put("type", "playback").put("playback_id", receipt.id).put("status", receipt.status)
            receipt.played?.let { value.put("played_samples", it) }
            if (connected) connection.send(value)
        }
    }
    private fun preparePlayback(control: JSONObject) {
        val id = control.getString("playback_id"); require(UUID.fromString(id).toString() == id)
        val prepared = control.getJSONObject("context")
        val sameEndpoint = prepared.getJSONObject("binding").getString("channel_id") == store.pairing()?.getString("channel_id") && prepared.getString("connection_epoch") == connectionEpoch
        if (playbackReceipts == null) status = "播放记录暂时无法读取，已保留原数据；本轮答复会留在待通知中"
        val accepted = sameEndpoint && playbackReceipts != null && PlaybackAdmission.allows(prepared.getString("kind"), mode.name, foreground, connected,
            speechEnabled, readyForConversation, prepared.optString("turn_id") == currentTurn, player != null || reservedPlayback != null)
        if (accepted) {
            guidedResultPlayback = guidedAwaitingResult && prepared.getJSONObject("binding").optString("request_id") == guidedSourceRequest
            playbackReturnMode = mode; playbackReturnDeadline = deadline
            mic.stop(); captureGeneration++; reservedPlayback = id; reservedContext = JSONObject(prepared.toString()); mode = Mode.WAITING
            main.postDelayed({ if (reservedPlayback == id && player == null) { reservedPlayback = null; reservedContext = null; restoreAfterNotification() } }, 3000)
        }
        connection.send(JSONObject().put("type", "playback_ready").put("playback_id", id).put("accepted", accepted))
        heartbeat()
    }
    private fun restoreAfterNotification(allowGuided: Boolean = false) {
        mode = Mode.CLOSED
        if (allowGuided && guidedAwaitingResult && guidedResumeEpoch == connectionEpoch && guidedResumeWake && speechEnabled && foreground && readyForConversation) {
            guidedAwaitingResult = false; guidedPrivateComplete = false; guidedResumeWake = false; guidedResumeEpoch = null
            wakeEnabled = true; standby(); heartbeat(); changed(); return
        }
        if (speechEnabled && foreground && readyForConversation) {
            if (playbackReturnMode == Mode.FOLLOWUP && SystemClock.elapsedRealtime() < playbackReturnDeadline) {
                mode = Mode.FOLLOWUP; deadline = playbackReturnDeadline; startMic()
                main.postDelayed({ if (mode == Mode.FOLLOWUP && SystemClock.elapsedRealtime() >= deadline) standby() }, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            } else if (playbackReturnMode in listOf(Mode.WAKE, Mode.FOLLOWUP) && wakeEnabled) standby()
        }
        status = if (mode == Mode.WAKE) "等你唤醒" else if (mode == Mode.FOLLOWUP) "可以接着说" else "麦克风已关闭"
        heartbeat(); changed()
    }
    private fun restoreAfterPrivateManagement() {
        if (guidedPrivateComplete && guidedAwaitingResult && readyForConversation) restoreAfterNotification(true)
    }
    private fun releaseWaitingTurn() {
        currentTurn = null; interactionHadPlayback = false; standby(); heartbeat(); changed()
    }
    private fun finishResponse() {
        currentTurn = null
        if (!interactionHadPlayback) { mode = Mode.CLOSED; status = "本轮结束，没有可播回复；麦克风关闭"; heartbeat(); changed(); return }
        interactionHadPlayback = false
        if (foreground && readyForConversation) try {
            mode = Mode.FOLLOWUP; deadline = SystemClock.elapsedRealtime() + 8000; status = "播放完成；8 秒本地接话窗口"; startMic()
            main.postDelayed({ if (mode == Mode.FOLLOWUP && SystemClock.elapsedRealtime() >= deadline) {
                try { standby() } catch (_: Exception) { stop("无法恢复本地唤醒；麦克风关闭", true) }
            } }, 8000)
        } catch (_: Exception) { stop("无法开启接话窗口；麦克风关闭", true) }
        heartbeat(); changed()
    }
}
