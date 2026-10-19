package com.crabot.voice

class PlaybackProgress(private val id: String, private val maximum: Long = Wire.MAX_SAFE_SAMPLES) {
    @Volatile var received = 0L; private set
    @Volatile var count: Long? = null; private set
    private var sequence = 0L
    private var tail = false
    fun add(frame: Wire.AudioFrame) {
        require(count == null && !tail && frame.id == id && frame.sequence == sequence && frame.samples.size in 1..480 && received <= maximum - frame.samples.size) { "播放音频帧不完整" }
        received += frame.samples.size; sequence++; tail = frame.samples.size < 480
    }
    fun finish(sampleCount: Long) {
        require(count == null && sampleCount in 1..maximum && received == sampleCount) { "播放音频未完整收到" }
        count = sampleCount
    }
}
