package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Synthetic silent PCM only: no microphone, models, storage or user data.
class PlaybackReceiptTest {
    @Test fun threeMinutePlaybackUsesOneTrackAndReportsActualConsumption() {
        val id = "00112233-4455-6677-8899-aabbccddee00"
        val completed = CountDownLatch(1)
        val consumed = AtomicLong(0)
        val total = 24_000L * 181
        var result = ""
        var actual = 0L
        val began = android.os.SystemClock.elapsedRealtime()
        val player = Playback(InstrumentationRegistry.getInstrumentation().targetContext, id, onProgress = { consumed.set(it) }) { _, status, played ->
            if (status != "started") { result = status; actual = played ?: 0; completed.countDown() }
        }
        val feeder = Thread {
            try {
                for (sequence in 0 until total / 480) {
                    while ((sequence + 1) * 480 - consumed.get() > 24_000 && completed.count > 0) Thread.sleep(10)
                    if (completed.count == 0L) return@Thread
                    player.add(Wire.AudioFrame(id, sequence, ShortArray(480)))
                }
                player.finish(total)
            } catch (_: InterruptedException) { /* test cleanup */ }
        }
        try {
            feeder.start()
            assertTrue(completed.await(195, TimeUnit.SECONDS))
            assertEquals("completed", result)
            assertEquals(total, actual)
            assertTrue(android.os.SystemClock.elapsedRealtime() - began >= 181_000)
        } finally { feeder.interrupt(); player.stop(); feeder.join(1000) }
    }

    @Test fun firstSamplesPlayBeforeFinalLengthAndOnlyActualPlaybackCompletes() {
        val id = "00112233-4455-6677-8899-aabbccddeeff"
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var actual = 0L
        val player = Playback(InstrumentationRegistry.getInstrumentation().targetContext, id) { _, status, played ->
            if (status == "started") { actual = played!!; started.countDown() }
            if (status == "completed") { actual = played!!; completed.countDown() }
        }
        try {
            repeat(15) { player.add(Wire.AudioFrame(id, it.toLong(), ShortArray(480))) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(actual > 0)
            assertEquals(1L, completed.count)
            player.finish(7200)
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(7200L, actual)
        } finally { player.stop() }
    }
}
