package com.crabot.voice

/** Local audio only. Does not open devices, upload samples, identify a speaker or create a turn. */
internal class DuplexAudio(private val processor: EchoProcessor, private val nowNs: () -> Long = System::nanoTime) : AutoCloseable {
    private val captureClock = SampleClock(16000)
    private val renderClock = SampleClock(24000)
    private val render = ShortArray(240)
    private val capture = ShortArray(160)
    private var renderCount = 0
    private var captureCount = 0
    private var renderPosition = 0L
    private var capturePosition = 0L
    private var renderLead: Long? = null
    private var playing = false
    private var paused = false
    private var closed = false
    @Volatile var synchronizationReason = "等待硬件音频时间戳"
        private set
    @Synchronized fun startCapture() {
        check(!closed)
        captureClock.reset(); captureCount = 0; capturePosition = 0; capture.fill(0)
    }
    @Synchronized fun startPlayback(): PlaybackReference {
        check(!closed && !playing)
        playing = true; paused = false; renderPosition = 0; renderCount = 0; renderLead = null; renderClock.reset()
        return object : PlaybackReference {
            // A separate reference object belongs to exactly one player; late callbacks are ignored.
            var active = true
            override fun written(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?) = synchronized(this@DuplexAudio) {
                if (active && !closed) writeRender(samples, offset, count, position, stamp)
            }
            override fun paused() = synchronized(this@DuplexAudio) {
                if (active && !closed) { paused = true; renderLead = null; synchronizationReason = "播放暂停，声学时钟未同步" }
            }
            override fun resumed() = synchronized(this@DuplexAudio) {
                if (active && !closed) { paused = false; renderLead = null; renderClock.reset() }
            }
            override fun closed() = synchronized(this@DuplexAudio) {
                if (active) {
                    active = false; playing = false; paused = false; renderLead = null
                    renderCount = 0; render.fill(0); renderClock.reset()
                }
            }
        }
    }
    private fun writeRender(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?) {
        check(playing && !paused && position == renderPosition && count > 0) { "播放参考不连续" }
        require(offset >= 0 && count <= samples.size - offset)
        if (stamp != null) renderClock.update(stamp)
        for (i in offset until offset + count) {
            render[renderCount++] = samples[i]; renderPosition++
            if (renderCount == render.size) {
                val presentation = renderClock.timeOf(renderPosition - render.size)
                val analyzed = nowNs()
                processor.render(render)
                renderLead = presentation?.minus(analyzed)
                renderCount = 0; render.fill(0)
            }
        }
    }
    /** Null blocks are deliberately unusable for speech decisions; raw identity input is untouched. */
    @Synchronized fun capture(frame: CapturedAudio, consume: (ShortArray?) -> Unit) {
        check(!closed && frame.position == capturePosition) { "采音样本不连续" }
        if (frame.stamp != null) captureClock.update(frame.stamp)
        for (sample in frame.samples) {
            capture[captureCount++] = sample; capturePosition++
            if (captureCount == capture.size) {
                val time = captureClock.timeOf(capturePosition - capture.size)
                val lead = if (playing) renderLead else 0L
                val delay = if (time != null && lead != null) lead + nowNs() - time else null
                val output = if (!paused && delay != null && delay in 0L..500_000_000L) {
                    synchronizationReason = ""
                    processor.capture(capture, (delay / 1_000_000L).toInt())
                } else {
                    synchronizationReason = if (paused) "播放暂停，声学时钟未同步" else "音频硬件时钟未同步或超出APM范围"
                    null
                }
                captureCount = 0; capture.fill(0)
                try { consume(output) } finally { output?.fill(0) }
            }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; playing = false; renderCount = 0; captureCount = 0; renderLead = null
        render.fill(0); capture.fill(0); captureClock.reset(); renderClock.reset(); processor.close()
    }
}
