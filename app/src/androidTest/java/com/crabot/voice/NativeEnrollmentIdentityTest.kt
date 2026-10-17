package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Public finite fixtures only; never opens a microphone or touches SecureStore.
class NativeEnrollmentIdentityTest {
    @Test fun aKnownPublicSpeakerCannotBeRegisteredAsAnotherContactButCanBeRerecordedForTheSameContact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeModels(Models.prepare(context)).use { models ->
            val audio = audio("fangjun-sr-1.wav")
            val vector = try { models.embedding(audio) } finally { audio.fill(0) }
            try {
                val contact = UUID.randomUUID().toString()
                val registration = UUID.randomUUID().toString()
                val stored = JSONObject().put(registration, JSONObject().put("model_id", Models.EMBEDDING).put("values", JSONArray(vector.map { it.toDouble() })))
                val catalog = JSONArray().put(JSONObject().put("registration_id", registration).put("platform_user_id", contact).put("status", "active").put("model_id", Models.EMBEDDING))
                assertThrows(ExistingVoiceIdentityException::class.java) { Identity.requireDistinctContact(vector, models, stored, catalog, UUID.randomUUID().toString()) }
                Identity.requireDistinctContact(vector, models, stored, catalog, contact)
                Identity.requireConsistentSample(vector, listOf(vector), models)
            } finally { vector.fill(0f) }
        }
    }
    private fun audio(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = buffer.getInt(at + 4)
            if (String(bytes, at, 4) == "data") return ShortArray(size / 2) { buffer.getShort(at + 8 + it * 2) }
            at += 8 + size + size % 2
        }
        error("Public fixture has no PCM")
    }
    @Test fun differentPublicUtterancesOfOneSpeakerPassPairwiseConsistency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeModels(Models.prepare(context)).use { models ->
            val samples = (1..3).map { i ->
                val pcm = audio("fangjun-sr-$i.wav")
                try { models.embedding(pcm) } finally { pcm.fill(0) }
            }
            try {
                val candidate = Identity.enrollmentCandidate(samples, models)
                try { assertEquals(models.dimension(), candidate.size); assertTrue(candidate.any { it != 0f }) }
                finally { candidate.fill(0f) }
            } finally { samples.forEach { it.fill(0f) } }
        }
    }
    @Test fun aDifferentPublicSpeakerCannotReplaceTheThirdEnrollmentSample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeModels(Models.prepare(context)).use { models ->
            val samples = listOf("fangjun-sr-1.wav", "fangjun-sr-2.wav", "short-b.wav").map { name ->
                val pcm = audio(name)
                try { models.embedding(pcm) } finally { pcm.fill(0) }
            }
            try { assertThrows(IllegalArgumentException::class.java) { Identity.enrollmentCandidate(samples, models) } }
            finally { samples.forEach { it.fill(0f) } }
        }
    }
}
