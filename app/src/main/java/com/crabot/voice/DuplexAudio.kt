package com.crabot.voice

/** Local audio only. APM receives render and capture on the same 10 ms hardware interval. */
internal class DuplexAudio(private val processor: EchoProcessor, private val nowNs: () -> Long = System::nanoTime) : AutoCloseable {
    private val captureClock = SampleClock(16000)
    private val render = RenderTimeline()
    private data class Pending(val position: Long, val count: Int)
    private val pending = ArrayDeque<Pending>()
    private val raw = ShortArray(16000)
    private val clean = ShortArray(16000)
    private var received = 0L
    private var processed = 0L
    private var delivered = 0L
    private var playing = false
    private var closed = false
    @Volatile var synchronizationReason = "等待硬件音频时间戳"
        private set
    @Synchronized fun startCapture() {
        check(!closed); clearCapture()
    }
    @Synchronized fun clearCapture() {
        captureClock.reset(); pending.clear(); received = 0; processed = 0; delivered = 0
        raw.fill(0); clean.fill(0)
    }
    @Synchronized fun startPlayback(): PlaybackReference {
        check(!closed && !playing)
        render.open(nowNs()); playing = true
        return object : PlaybackReference {
            var active = true
            var pausedAt: Long? = null
            var starved = false
            var idlePosition = 0L
            override fun written(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?) = synchronized(this@DuplexAudio) {
                if (active && !closed) render.write(samples, offset, count, position, stamp)
            }
            override fun clock(stamp: AudioStamp?) = synchronized(this@DuplexAudio) {
                if (active && !closed) render.clock(stamp)
            }
            override fun starved(played: Long) = synchronized(this@DuplexAudio) {
                if (active && !closed && !starved) { render.pause(played); idlePosition = played; starved = true }
            }
            override fun replenished() = synchronized(this@DuplexAudio) {
                if (active && !closed && starved && pausedAt == null) {
                    render.resume(nowNs(), idlePosition); starved = false
                }
            }
            override fun paused(played: Long) = synchronized(this@DuplexAudio) {
                if (active && !closed) { render.pause(played); pausedAt = played }
            }
            override fun resumed() = synchronized(this@DuplexAudio) {
                if (active && !closed) {
                    if (!starved) render.resume(nowNs(), checkNotNull(pausedAt))
                    pausedAt = null
                }
            }
            override fun closed(played: Long?) = synchronized(this@DuplexAudio) {
                if (active) {
                    active = false; playing = false
                    if (!closed && pausedAt == null && !starved) render.pause(played)
                }
            }
        }
    }
    /** Hold at most one second until both hardware clocks are available. Drain in
     * order on the capture thread, including across pause/resume; never skip APM
     * time or feed unplayed PCM as a substitute for missing reference.
     */
    @Synchronized fun capture(frame: CapturedAudio, consume: (CapturedAudio, ShortArray) -> Unit) {
        check(!closed && frame.position == received) { "采音样本不连续" }
        check(frame.samples.isNotEmpty() && received - delivered + frame.samples.size <= raw.size) { "音频时钟同步等待超过一秒" }
        if (frame.stamp != null) captureClock.update(frame.stamp)
        pending.addLast(Pending(received, frame.samples.size))
        for (sample in frame.samples) { raw[(received % raw.size).toInt()] = sample; received++ }
        while (received - processed >= 160) {
            val time = captureClock.timeOf(processed)
            val reference = time?.let(render::read)
            if (reference == null) { synchronizationReason = "等待硬件音频时钟及真实播放参考"; break }
            val input = ShortArray(160) { raw[((processed + it) % raw.size).toInt()] }
            try {
                processor.render(reference)
                // Both streams represent the same hardware interval: the two
                // callback delays cancel in APM's transport-delay equation.
                val output = processor.capture(input, 0)
                try {
                    check(output.size == 160) { "回声处理块大小无效" }
                    for (i in output.indices) clean[((processed + i) % clean.size).toInt()] = output[i]
                } finally { output.fill(0) }
            } finally { input.fill(0); reference.fill(0) }
            synchronizationReason = ""; processed += 160
        }
        while (pending.isNotEmpty() && pending.first().position + pending.first().count <= processed) {
            val item = pending.removeFirst()
            val samples = ShortArray(item.count) { raw[((item.position + it) % raw.size).toInt()] }
            val output = ShortArray(item.count) { clean[((item.position + it) % clean.size).toInt()] }
            val stamp = AudioStamp(item.position, checkNotNull(captureClock.timeOf(item.position)))
            delivered = item.position + item.count
            try { consume(CapturedAudio(samples, item.position, stamp), output) }
            finally {
                samples.fill(0); output.fill(0)
                for (i in 0 until item.count) { val index = ((item.position + i) % raw.size).toInt(); raw[index] = 0; clean[index] = 0 }
            }
        }
    }
    @Synchronized fun reset() {
        check(!closed); clearCapture(); render.clear(); playing = false; processor.reset()
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; playing = false; pending.clear()
        render.clear(); raw.fill(0); clean.fill(0); captureClock.reset(); processor.close()
    }
}
