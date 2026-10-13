package com.crabot.voice

class PlaybackProgress(private val id: String) {
    var received = 0; private set
    var count: Int? = null; private set
    private var sequence = 0
    private var tail = false
    fun add(frame: Wire.AudioFrame) {
        require(count == null && !tail && frame.id == id && frame.sequence == sequence && frame.samples.size in 1..480 && received + frame.samples.size <= Wire.MAX_OUTPUT) { "播放音频帧不完整" }
        received += frame.samples.size; sequence++; tail = frame.samples.size < 480
    }
    fun finish(sampleCount: Int) {
        require(count == null && sampleCount in 1..Wire.MAX_OUTPUT && received == sampleCount) { "播放音频未完整收到" }
        count = sampleCount
    }
}
