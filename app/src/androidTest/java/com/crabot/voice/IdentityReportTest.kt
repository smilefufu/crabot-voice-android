package com.crabot.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// In-memory model results only: no native models, microphone, files or credentials.
class IdentityReportTest {
    private class Model : SpeakerIdentityModel {
        var timeline = floatArrayOf(0f, .1f, 0f)
        var vector = floatArrayOf(1f, 0f)
        var hits = floatArrayOf(0f, .8f)
        var embeddingError: RuntimeException? = null
        var embeddingCalls = 0
        var matchCalls = 0
        val received = mutableListOf<ShortArray>()
        var features: ((ShortArray) -> FloatArray)? = null
        var compare: ((FloatArray) -> FloatArray)? = null
        override fun dimension() = 2
        override fun segments(samples: ShortArray) = timeline
        override fun embedding(samples: ShortArray): FloatArray {
            embeddingCalls++; received += samples.copyOf()
            embeddingError?.let { throw it }
            return features?.invoke(samples) ?: vector.copyOf()
        }
        override fun matches(vector: FloatArray, candidates: List<FloatArray>): FloatArray {
            matchCalls++; return compare?.invoke(vector) ?: hits.copyOf()
        }
    }
    private class Fixture(count: Int = 1) {
        val model = Model()
        val stored = JSONObject()
        val registrations = JSONArray()
        val diagnostics = mutableListOf<Identity.Diagnostic>()
        init {
            repeat(count) { i ->
                stored.put("r$i", JSONObject().put("model_id", Models.EMBEDDING).put("values", JSONArray(listOf(1f, i.toFloat()))))
                registrations.put(JSONObject().put("registration_id", "r$i").put("status", "active").put("model_id", Models.EMBEDDING))
            }
        }
        fun report(pcm: ShortArray = ShortArray(1600) { 11 }) = Identity.report(pcm, model, stored, registrations) { diagnostics += it }
        fun result() = report().getJSONObject(0)
    }
    @Test fun shortReadySpeechReachesMatchingWithoutDurationGate() {
        val f = Fixture(); val original = f.stored.toString(); val result = f.result()
        assertEquals("matched", result.getString("identity_status")); assertEquals("r0", result.getString("registration_id"))
        assertEquals(1, f.model.embeddingCalls); assertEquals(1, f.model.matchCalls)
        assertTrue(f.diagnostics.single().compared); assertNull(f.diagnostics.single().reason)
        assertEquals(1600, f.diagnostics.single().samples)
        assertEquals(original, f.stored.toString())
        assertEquals(setOf("identity_status", "registration_id", "score", "margin", "segment_index", "start_sample", "end_sample", "anonymous_speaker"), result.keys().asSequence().toSet())
    }
    @Test fun oneCandidateStillRejectsLowScore() {
        val f = Fixture(); f.model.hits = floatArrayOf(0f, .2f); val result = f.result()
        assertEquals("unknown", result.getString("identity_status")); assertFalse(result.has("registration_id"))
        assertTrue(f.diagnostics.single().compared); assertTrue(f.diagnostics.single().reason!!.contains("不匹配"))
    }
    @Test fun closeCandidatesRemainUncertain() {
        val f = Fixture(2); f.model.hits = floatArrayOf(0f, .8f, 1f, .75f); val result = f.result()
        assertEquals("uncertain", result.getString("identity_status")); assertFalse(result.has("registration_id"))
        assertTrue(f.diagnostics.single().reason!!.contains("相近"))
    }
    @Test fun unavailableFeatureDoesNotInvokeMatcher() {
        val f = Fixture(); f.model.vector = floatArrayOf(); val result = f.result()
        assertEquals("uncertain", result.getString("identity_status")); assertFalse(result.has("score"))
        assertEquals(0, f.model.matchCalls); assertFalse(f.diagnostics.single().compared)
        assertTrue(f.diagnostics.single().reason!!.contains("无法提取"))
    }
    @Test fun invalidFeatureDoesNotBecomeIdentity() {
        for (vector in listOf(floatArrayOf(1f), floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f), floatArrayOf(Float.POSITIVE_INFINITY, 1f))) {
            val f = Fixture(); f.model.vector = vector; val result = f.result()
            assertEquals("uncertain", result.getString("identity_status")); assertFalse(result.has("registration_id"))
            assertFalse(result.has("score")); assertEquals(0, f.model.matchCalls)
            assertTrue(f.diagnostics.single().reason!!.contains("特征无效"))
        }
    }
    @Test fun invalidEnrolledVectorDoesNotSilentlyDropCandidate() {
        for (values in listOf(listOf(1f), listOf(0f, 0f))) {
            val f = Fixture(2); f.stored.getJSONObject("r1").put("values", JSONArray(values)); val result = f.result()
            assertEquals("uncertain", result.getString("identity_status")); assertEquals(0, f.model.matchCalls)
            assertEquals(2, f.diagnostics.single().candidates); assertFalse(result.has("registration_id"))
        }
    }
    @Test fun malformedMatchingResultsNeverAssignIdentity() {
        val bad = listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(-1f, .8f), floatArrayOf(1f, .8f), floatArrayOf(.5f, .8f), floatArrayOf(Float.NaN, .8f), floatArrayOf(0f, Float.NaN), floatArrayOf(0f, Float.POSITIVE_INFINITY), floatArrayOf(0f, 1.2f))
        for (hits in bad) {
            val f = Fixture(); f.model.hits = hits; val result = f.result()
            assertEquals("uncertain", result.getString("identity_status")); assertFalse(result.has("registration_id")); assertFalse(result.has("score"))
            assertTrue(f.diagnostics.single().compared); assertEquals("声纹比对结果无效", f.diagnostics.single().reason)
        }
    }
    @Test fun duplicateOrUnorderedCandidatesAreNotTrusted() {
        for (hits in listOf(floatArrayOf(0f, .8f, 0f, .7f), floatArrayOf(0f, .6f, 1f, .8f), floatArrayOf(0f, .8f))) {
            val f = Fixture(2); f.model.hits = hits; val result = f.result()
            assertEquals("uncertain", result.getString("identity_status")); assertFalse(result.has("registration_id"))
        }
    }
    @Test fun noUsableProfilesStayAnonymousWithoutInference() {
        for (count in listOf(0, 1)) {
            val f = Fixture(count)
            if (count > 0) f.registrations.getJSONObject(0).put("status", "revoked")
            val result = f.result()
            assertEquals("unknown", result.getString("identity_status")); assertEquals(0, f.model.embeddingCalls)
            assertEquals(0, f.diagnostics.single().candidates); assertTrue(f.diagnostics.single().reason!!.contains("尚无"))
        }
    }
    @Test fun sameSpeakerEvidenceIsGatheredWithinTurnAndTimelinePreserved() {
        val f = Fixture(2)
        f.model.timeline = floatArrayOf(0f, .1f, 0f, .1f, .2f, 1f, .2f, .3f, 0f)
        f.model.features = { if (it[0] == 11.toShort()) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        f.model.compare = { if (it[0] == 1f) floatArrayOf(0f, .8f, 1f, .1f) else floatArrayOf(1f, .8f, 0f, .1f) }
        val pcm = ShortArray(4800) { if (it < 1600) 11 else if (it < 3200) 22 else 33 }
        val results = f.report(pcm)
        assertEquals(listOf("r0", "r1", "r0"), (0..2).map { results.getJSONObject(it).getString("registration_id") })
        assertEquals(listOf(0, 1600, 3200), (0..2).map { results.getJSONObject(it).getInt("start_sample") })
        assertEquals(listOf(1600, 3200, 4800), (0..2).map { results.getJSONObject(it).getInt("end_sample") })
        assertArrayEquals(ShortArray(3200) { if (it < 1600) 11 else 33 }, f.model.received[0])
        assertArrayEquals(ShortArray(1600) { 22 }, f.model.received[1])
        assertEquals(2, f.model.matchCalls)
    }
    @Test fun laterAnonymousTurnDoesNotReusePreviousMatch() {
        val f = Fixture(); assertEquals("r0", f.result().getString("registration_id"))
        f.model.hits = floatArrayOf(0f, .2f)
        val next = f.result(); assertEquals("unknown", next.getString("identity_status")); assertFalse(next.has("registration_id"))
    }
    @Test fun unexpectedInferenceFailureStillFailsWholeTurn() {
        val f = Fixture(); f.model.embeddingError = IllegalStateException("fixture inference failed")
        assertThrows(IllegalStateException::class.java) { f.report() }
        assertEquals(0, f.model.matchCalls); assertTrue(f.diagnostics.isEmpty())
    }
    @Test fun overlappingSpeechStillRejectsWholeTurnBeforeMatching() {
        val f = Fixture(); f.model.timeline = floatArrayOf(0f, .1f, 0f, .05f, .1f, 1f)
        assertThrows(IllegalArgumentException::class.java) { f.report() }
        assertEquals(0, f.model.embeddingCalls); assertTrue(f.diagnostics.isEmpty())
    }
}
