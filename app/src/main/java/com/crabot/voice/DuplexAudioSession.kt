package com.crabot.voice

import android.content.Context

/** Device wiring for isolated qualification and, after qualification, the controller's audio owner.
 * Construction never starts capture; this class cannot grant standby or conversation permission.
 */
internal class DuplexAudioSession(
    private val microphone: AudioCapture,
    private val audio: DuplexAudio,
    private val playbackDevice: () -> PlaybackDevice
) : AutoCloseable {
    constructor(context: Context, failure: (String) -> Unit) : this(
        Microphone(context, systemAec = false, failure = failure), DuplexAudio(NativeEchoProcessor()), { AndroidPlaybackDevice(context) })
    @Volatile private var capturing = false
    @Volatile private var captureGeneration = 0L
    private var player: Playback? = null
    private var closed = false
    internal data class Candidate(val player: Playback, val token: Long)
    /** The callback must consume or copy PCM before returning; no user audio is retained here. */
    fun startCapture(frame: (CapturedAudio, List<ShortArray>?) -> Unit) {
        check(!closed && !capturing)
        capturing = true; val token = ++captureGeneration; audio.startCapture()
        try {
            microphone.startTimed { captured ->
                if (!capturing || token != captureGeneration) { captured.samples.fill(0); return@startTimed }
                val cleaned = mutableListOf<ShortArray>()
                var clockReady = true
                try {
                    audio.capture(captured) { block -> if (block == null) clockReady = false else cleaned += block.copyOf() }
                    if (capturing && token == captureGeneration) frame(captured, if (clockReady) cleaned else null)
                } finally { cleaned.forEach { it.fill(0) }; captured.samples.fill(0) }
            }
        } catch (e: Exception) { capturing = false; microphone.stop(); throw e }
    }
    fun stopCapture() { capturing = false; captureGeneration++; microphone.stop() }
    fun startPlayback(id: String, progress: (Long) -> Unit, receipt: (String, String, Long?) -> Unit) {
        check(!closed && player == null)
        val reference = audio.startPlayback()
        try {
            player = Playback(id, playbackDevice(), progress, reference = reference, receipt = receipt)
        } catch (e: Exception) { reference.closed(); throw e }
    }
    fun add(frame: Wire.AudioFrame) { checkNotNull(player).add(frame) }
    fun finish(count: Long) { checkNotNull(player).finish(count) }
    fun pauseForCandidate(): Candidate? {
        val current = player ?: return null
        return current.pauseForCandidate()?.let { Candidate(current, it) }
    }
    fun reject(candidate: Candidate): Boolean = candidate.player === player && candidate.player.resumeCandidate(candidate.token)
    fun confirm(candidate: Candidate): Boolean = candidate.player === player && candidate.player.interruptCandidate(candidate.token)
    fun stopPlayback() { player?.stop(); player = null }
    override fun close() {
        if (closed) return
        closed = true
        try { stopCapture() } finally { try { stopPlayback() } finally { audio.close() } }
    }
}
