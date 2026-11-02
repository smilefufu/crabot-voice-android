package com.crabot.voice

/** A bounded copy of accepted PCM, addressed by its hardware presentation clock.
 * Pausing closes a span at the consumed cursor. The unplayed tail stays available
 * for a new clock span when the same AudioTrack resumes.
 */
internal class RenderTimeline {
    private class Span(val start: Long, val requestedAt: Long) {
        val clock = SampleClock(24000)
        var end: Long? = null
        var anchored = false
    }
    private val pcm = ShortArray(24000)
    private val spans = ArrayDeque<Span>()
    private var current: Span? = null
    private var written = 0L
    private var base = 0L
    private var lastCaptureTime: Long? = null
    fun open(now: Long) {
        check(current == null)
        base = written
        resume(now, 0)
    }
    fun resume(now: Long, played: Long) {
        check(current == null && spans.size < 50) { "播放时钟历史超过有界窗口" }
        current = Span(Math.addExact(base, played), now).also { spans.addLast(it) }
    }
    fun clock(stamp: AudioStamp?) {
        val span = current ?: return
        if (stamp == null || stamp.nanoTime < span.requestedAt) return
        val position = Math.addExact(base, stamp.position)
        if (position < span.start) return
        span.clock.update(AudioStamp(position, stamp.nanoTime)); span.anchored = true
    }
    fun write(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?) {
        check(current != null && Math.addExact(base, position) == written) { "播放参考不连续" }
        require(count > 0 && offset >= 0 && count <= samples.size - offset)
        clock(stamp)
        for (i in offset until offset + count) {
            pcm[(written % pcm.size).toInt()] = samples[i]
            written = Math.incrementExact(written)
        }
    }
    fun pause(played: Long?) {
        val span = current ?: return
        check(played != null && Math.addExact(base, played) in span.start..written) { "暂停缺少真实播放游标" }
        span.end = base + played; current = null
        if (span.end == span.start) spans.removeLast()
    }
    fun read(time: Long): ShortArray? {
        lastCaptureTime?.let { check(time >= it) { "采音时间回退" } }
        val out = ShortArray(240)
        for (i in out.indices) {
            val at = Math.addExact(time, i * 1_000_000_000L / 24000)
            var value: Short = 0
            for (span in spans) {
                val end = span.end ?: written
                if (span.start == end) continue
                // A missing hardware anchor is unavailable, never inferred from callback time.
                if (!span.anchored) { out.fill(0); return null }
                val startTime = span.clock.timeOf(span.start)!!
                val endTime = span.clock.timeOf(end)!!
                if (at < startTime || at >= endTime) continue
                val position = span.clock.positionAt(at)!!.coerceIn(span.start, end - 1)
                if (position < written - pcm.size) { out.fill(0); return null }
                value = pcm[(position % pcm.size).toInt()]
                break
            }
            out[i] = value
        }
        lastCaptureTime = time
        while (spans.size > 1) {
            val first = spans.first()
            val end = first.end ?: break
            if (!first.anchored || first.clock.timeOf(end)!! > time) break
            spans.removeFirst()
        }
        return out
    }
    fun clear() { pcm.fill(0); spans.clear(); current = null; written = 0; base = 0; lastCaptureTime = null }
}
