package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class IdentityTest {
    @Test fun preservesShortAndOverlappingSpeechForWholeTurnGate() {
        val segments = Identity.ranges(floatArrayOf(0f, 1f, 0f, .99f, 1.05f, 1f, 1.05f, 2f, 0f), 32000)
        assertEquals(listOf(0, 1, 0), segments.map { it.speaker })
        assertEquals(960, segments[1].end - segments[1].start)
        assertTrue(segments[1].start < segments[0].end)
    }
    @Test fun rejectsMissingNonfiniteOrExcessiveDiarization() {
        listOf(floatArrayOf(), floatArrayOf(0f, Float.NaN, 0f), floatArrayOf(2f, 1f, 0f), FloatArray(99)).forEach {
            try { Identity.ranges(it, 32000); fail("Accepted invalid diarization") } catch (_: IllegalArgumentException) {}
        }
    }
}
