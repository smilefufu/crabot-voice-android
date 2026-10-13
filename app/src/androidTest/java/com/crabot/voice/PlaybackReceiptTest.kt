package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Synthetic silent PCM only: no microphone, models, storage or user data.
class PlaybackReceiptTest {
    @Test fun firstSamplesPlayBeforeFinalLengthAndOnlyActualPlaybackCompletes() {
        val id = "00112233-4455-6677-8899-aabbccddeeff"
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var actual = 0
        val player = Playback(InstrumentationRegistry.getInstrumentation().targetContext, id) { _, status, played ->
            if (status == "started") { actual = played!!; started.countDown() }
            if (status == "completed") { actual = played!!; completed.countDown() }
        }
        try {
            repeat(15) { player.add(Wire.AudioFrame(id, it, ShortArray(480))) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(actual > 0)
            assertEquals(1L, completed.count)
            player.finish(7200)
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(7200, actual)
        } finally { player.stop() }
    }
}
