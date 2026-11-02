package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PauseCaptureRegressionTest {
    @Test fun candidatePauseDoesNotDisableNearEndProcessing() {
        var calls = 0
        val processor = object : EchoProcessor {
            override fun render(samples: ShortArray) = Unit
            override fun capture(samples: ShortArray, delayMs: Int): ShortArray { calls++; return samples.copyOf() }
            override fun close() = Unit
        }
        val now = AtomicLong(1_000_000_000)
        DuplexAudio(processor, now::get).use { audio ->
            audio.startCapture()
            val reference = audio.startPlayback()
            reference.written(ShortArray(480) { 7 }, 0, 480, 0, AudioStamp(0, now.get()))
            reference.paused(240)
            now.addAndGet(30_000_000)
            audio.capture(CapturedAudio(ShortArray(160) { 12 }, 0, AudioStamp(0, now.get()))) { _, clean -> assertNotNull("暂停后仍须处理近端声音", clean) }
            assertEquals(1, calls)
        }
    }
}
