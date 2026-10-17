package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class IdentityTest {
    @Test fun guidedSamplesMustMatchEveryPreviousClip() {
        var score = .8f
        val models = object : SpeakerIdentityModel {
            override fun dimension() = 2
            override fun segments(samples: ShortArray) = floatArrayOf()
            override fun embedding(samples: ShortArray) = floatArrayOf(1f, 0f)
            override fun matches(vector: FloatArray, candidates: List<FloatArray>) = floatArrayOf(0f, score)
        }
        val first = floatArrayOf(1f, 0f)
        Identity.requireConsistentSample(first, listOf(first, first), models)
        score = .49f
        assertThrows(IllegalArgumentException::class.java) { Identity.requireConsistentSample(first, listOf(first), models) }
        score = Float.NaN
        assertThrows(IllegalArgumentException::class.java) { Identity.requireConsistentSample(first, listOf(first), models) }
    }
    @Test fun preservesShortAndOverlappingSpeechForWholeTurnGate() {
        val segments = Identity.ranges(floatArrayOf(0f, 1f, 0f, .99f, 1.05f, 1f, 1.05f, 2f, 0f), 32000)
        assertEquals(listOf(0, 1, 0), segments.map { it.speaker })
        assertEquals(960, segments[1].end - segments[1].start)
        assertTrue(segments[1].start < segments[0].end)
        assertThrows(IllegalArgumentException::class.java) { Identity.requireComplete(segments) }
        Identity.requireComplete(listOf(Identity.Segment(0, 16000, 0), Identity.Segment(16000, 16960, 1), Identity.Segment(16960, 32000, 0)))
    }
    @Test fun rejectsMissingNonfiniteOrExcessiveDiarization() {
        listOf(floatArrayOf(), floatArrayOf(0f, Float.NaN, 0f), floatArrayOf(2f, 1f, 0f), floatArrayOf(-1f, 1f, 0f), floatArrayOf(0f, 3f, 0f), FloatArray(99)).forEach {
            try { Identity.ranges(it, 32000); fail("Accepted invalid diarization") } catch (_: IllegalArgumentException) {}
        }
    }
}
