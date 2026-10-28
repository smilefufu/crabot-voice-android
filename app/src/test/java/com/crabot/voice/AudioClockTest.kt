package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class AudioClockTest {
    @Test fun hardwarePositionMapsBothRatesWithoutUsingCallbackArrivalTime() {
        val capture = SampleClock(16000)
        assertNull(capture.timeOf(0))
        capture.update(AudioStamp(1600, 2_000_000_000))
        assertEquals(1_900_000_000L, capture.timeOf(0))
        assertEquals(2_010_000_000L, capture.timeOf(1760))
        val render = SampleClock(24000)
        render.update(AudioStamp(2400, 2_000_000_000))
        assertEquals(2_010_000_000L, render.timeOf(2640))
    }
    @Test fun repeatedStampIsAllowedButClockRegressionFails() {
        val clock = SampleClock(16000)
        clock.update(AudioStamp(320, 30_000_000))
        clock.update(AudioStamp(320, 30_000_000))
        assertThrows(IllegalStateException::class.java) { clock.update(AudioStamp(319, 31_000_000)) }
        assertThrows(IllegalStateException::class.java) { clock.update(AudioStamp(321, 29_000_000)) }
    }
    @Test fun restartDoesNotReuseThePreviousRecordingClock() {
        val clock = SampleClock(16000)
        clock.update(AudioStamp(16000, 2_000_000_000))
        clock.reset()
        assertNull(clock.timeOf(0))
        clock.update(AudioStamp(0, 3_000_000_000))
        assertEquals(3_000_000_000L, clock.timeOf(0))
    }
    @Test fun unrepresentableTimelineIsRejectedInsteadOfWrapping() {
        val clock = SampleClock(16000)
        clock.update(AudioStamp(0, 1_000_000_000))
        assertThrows(ArithmeticException::class.java) { clock.timeOf(Long.MAX_VALUE) }
    }

}
