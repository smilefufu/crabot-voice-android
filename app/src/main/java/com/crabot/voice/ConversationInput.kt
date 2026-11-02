package com.crabot.voice

/** An endpoint implementation is supplied only after its separate qualification.
 * No provider, timer-based fallback or terminal capability flag selects one here.
 */
internal interface TurnEndpoint : AutoCloseable {
    fun reset()
    // Must be nonblocking: an adapter owns its single bounded inference job and
    // only returns a completed result for its current reset generation.
    fun feed(samples: ShortArray, speaking: Boolean): Boolean
}
internal data class ConversationFrame(val raw: ShortArray, val activity: SpeechActivity, val ended: Boolean, val endpointToken: Long)
internal interface ConversationInput : AutoCloseable {
    fun start(frame: (ConversationFrame) -> Unit, failure: (String) -> Unit)
    fun resetTurn(): Long
    fun stop()
    fun playback(id: String, progress: (Long) -> Unit, receipt: (String, String, Long?) -> Unit): Playback
}

/** Real APM and VAD path; an explicit owner still grants/revokes all capture. */
internal class DeviceConversationInput(
    private val session: DuplexAudioSession,
    private val detector: SpeechDetector,
    private val endpoint: TurnEndpoint
) : ConversationInput {
    @Volatile private var active = false
    @Volatile private var failureCallback: (String) -> Unit = {}
    private var endpointToken = 0L
    override fun start(frame: (ConversationFrame) -> Unit, failure: (String) -> Unit) {
        check(!active); detector.reset(); resetTurn(); failureCallback = failure; active = true
        try {
            session.startCapture { raw, clean ->
                try {
                    val activity = detector.feed(clean)
                    val (token, ended) = synchronized(endpoint) { endpointToken to endpoint.feed(clean, activity.confirmed) }
                    if (active) frame(ConversationFrame(raw.samples, activity, ended, token))
                } catch (_: Exception) { if (active) failure("本地语音检测失败") }
            }
        } catch (e: Exception) { active = false; throw e }
    }
    override fun resetTurn(): Long = synchronized(endpoint) { endpoint.reset(); ++endpointToken }
    override fun playback(id: String, progress: (Long) -> Unit, receipt: (String, String, Long?) -> Unit): Playback {
        session.stopPlayback()
        return session.startPlayback(id, progress, receipt)
    }
    override fun stop() { active = false; session.reset() }
    companion object {
        fun create(context: android.content.Context, root: String, endpoint: TurnEndpoint): DeviceConversationInput {
            lateinit var input: DeviceConversationInput
            val session = DuplexAudioSession(context) { if (input.active) input.failureCallback(it) }
            return DeviceConversationInput(session, NativeSpeechDetector(root), endpoint).also { input = it }
        }
    }
    override fun close() {
        active = false
        try { session.close() } finally { try { detector.close() } finally { synchronized(endpoint) { endpoint.close() } } }
    }
}
