package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class EnrollmentIdentityTest {
    private class Model : SpeakerIdentityModel {
        var segments = floatArrayOf(0f, 2f, 0f)
        var embedding = floatArrayOf(1f, 0f)
        var scores = ArrayDeque<FloatArray>()
        var comparisons = 0
        override fun dimension() = 2
        override fun segments(samples: ShortArray) = segments
        override fun embedding(samples: ShortArray) = embedding.copyOf()
        override fun matches(vector: FloatArray, candidates: List<FloatArray>): FloatArray {
            comparisons++
            return if (scores.isEmpty()) floatArrayOf(0f, .9f) else scores.removeFirst()
        }
    }
    private fun samples() = listOf(floatArrayOf(1f, 0f), floatArrayOf(.9f, .1f), floatArrayOf(.8f, .2f))

    @Test fun validatesEachSegmentBeforeAcceptingItsEmbedding() {
        val model = Model()
        assertArrayEquals(floatArrayOf(1f, 0f), Identity.enrollmentSample(ShortArray(48000), model), 0f)
        for (segments in listOf(floatArrayOf(), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f, 1f, 2f, 1f), floatArrayOf(0f, 2f, 0f, 1f, 3f, 0f))) {
            model.segments = segments
            assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentSample(ShortArray(48000), model) }
        }
        model.segments = floatArrayOf(0f, 2f, 0f)
        for (vector in listOf(floatArrayOf(), floatArrayOf(0f, 0f), floatArrayOf(1f), floatArrayOf(Float.NaN, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 1f))) {
            model.embedding = vector
            assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentSample(ShortArray(48000), model) }
        }
    }

    @Test fun checksAllThreePairsAndPreservesInputVectors() {
        val model = Model()
        val vectors = samples()
        val before = vectors.map { it.copyOf() }
        val mean = Identity.enrollmentCandidate(vectors, model)
        assertEquals(3, model.comparisons)
        assertArrayEquals(floatArrayOf(.9f, .1f), mean, .00001f)
        vectors.zip(before).forEach { (actual, original) -> assertArrayEquals(original, actual, 0f) }
    }

    @Test fun anyInconsistentPairPreventsEnrollment() {
        for (badPair in 0..2) {
            val model = Model()
            repeat(3) { model.scores.add(floatArrayOf(0f, if (it == badPair) .49f else .9f)) }
            assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentCandidate(samples(), model) }
        }
        val model = Model()
        repeat(3) { model.scores.add(floatArrayOf(0f, Identity.SCORE)) }
        Identity.enrollmentCandidate(samples(), model)
    }

    @Test fun rejectsInvalidComparisonResultsAndInvalidCandidates() {
        for (hits in listOf(floatArrayOf(), floatArrayOf(1f, .9f), floatArrayOf(0f, Float.NaN), floatArrayOf(0f, 2f), floatArrayOf(0f, .9f, 0f, .8f))) {
            val model = Model(); model.scores.add(hits)
            assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentCandidate(samples(), model) }
        }
        for (vectors in listOf(samples().take(2), samples() + floatArrayOf(1f, 0f), listOf(floatArrayOf(), floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), listOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 0f)))) {
            assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentCandidate(vectors, Model()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Identity.enrollmentCandidate(listOf(floatArrayOf(1f, 0f), floatArrayOf(-.5f, 0f), floatArrayOf(-.5f, 0f)), Model())
        }
    }
}
