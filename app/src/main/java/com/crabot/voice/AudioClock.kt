package com.crabot.voice

/** Hardware frame position in CLOCK_MONOTONIC, never a read/write callback time. */
internal data class AudioStamp(val position: Long, val nanoTime: Long)
internal data class CapturedAudio(val samples: ShortArray, val position: Long, val stamp: AudioStamp?)

internal class SampleClock(private val rate: Int) {
    private var anchor: AudioStamp? = null
    fun reset() { anchor = null }
    fun update(stamp: AudioStamp) {
        check(stamp.position >= 0 && stamp.nanoTime > 0) { "音频时间戳无效" }
        anchor?.let { old ->
            check(stamp.position >= old.position && stamp.nanoTime >= old.nanoTime) { "音频时钟回退" }
            check(stamp.position != old.position || stamp.nanoTime == old.nanoTime) { "音频时钟没有前进" }
        }
        anchor = stamp
    }
    fun positionAt(nanoTime: Long): Long? {
        val stamp = anchor ?: return null
        val delta = Math.subtractExact(nanoTime, stamp.nanoTime)
        val whole = Math.multiplyExact(delta / 1_000_000_000L, rate.toLong())
        val remainder = delta % 1_000_000_000L * rate
        val fraction = Math.floorDiv(remainder + 500_000_000L, 1_000_000_000L)
        return Math.addExact(stamp.position, Math.addExact(whole, fraction))
    }

    fun timeOf(position: Long): Long? {
        require(position >= 0)
        val stamp = anchor ?: return null
        val delta = position - stamp.position
        return Math.addExact(stamp.nanoTime, Math.addExact(Math.multiplyExact(delta / rate, 1_000_000_000L), delta % rate * 1_000_000_000L / rate))
    }
}

/** Synchronous consumer: must copy anything retained beyond this callback. */
internal interface PlaybackReference {
    fun written(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?)
    fun clock(stamp: AudioStamp?) = Unit
    fun starved(played: Long) = Unit
    fun replenished() = Unit
    fun paused(played: Long)
    fun resumed()
    fun closed(played: Long?)
}
