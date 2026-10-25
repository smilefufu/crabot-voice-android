package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Public finite files only. Never opens the microphone or reads/writes SecureStore.
class NativeShortIdentityTest {
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
    private fun checkQuery(name: String, expected: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        NativeModels(Models.prepare(target)).use { models ->
            val enrolled = (1..3).map { i ->
                val pcm = audio("fangjun-sr-$i.wav")
                try { models.embedding(pcm) } finally { pcm.fill(0) }
            }
            val profile = FloatArray(enrolled[0].size) { i -> enrolled.sumOf { it[i].toDouble() }.toFloat() / 3 }
            val stored = JSONObject().put("fixture-a", JSONObject().put("model_id", Models.EMBEDDING).put("values", JSONArray(profile.toList())))
            val registrations = JSONArray().put(JSONObject().put("registration_id", "fixture-a").put("status", "active").put("model_id", Models.EMBEDDING))
            val query = audio(name)
            try {
                assertEquals(31680, query.size)
                val ranges = Identity.ranges(models.segments(query), query.size)
                Identity.requireComplete(ranges)
                assertTrue("Only voiced evidence is counted", ranges.sumOf { it.end - it.start } < 24000)
                val results = Identity.report(query, models, stored, registrations)
                assertTrue(results.length() > 0)
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    assertEquals(expected, result.getString("identity_status"))
                    assertTrue("Native comparison must run even below 1.5 seconds", result.has("score"))
                    if (expected == "matched") assertEquals("fixture-a", result.getString("registration_id"))
                    else assertFalse(result.has("registration_id"))
                }
            } finally { query.fill(0); profile.fill(0f); enrolled.forEach { it.fill(0f) } }
        }
    }
    @Test fun independentShortRegisteredVoiceIsActuallyCompared() = checkQuery("short-a.wav", "matched")
    @Test fun singleProfileDoesNotForceAnotherVoiceToMatch() = checkQuery("short-b.wav", "unknown")
    @Test fun singleProfileKeepsUnregisteredPublicVoiceUnknown() = checkQuery("short-c.wav", "unknown")
    @Test fun repeatedFollowupResetsKeepNativeSegmentsInsideEachFiniteClip() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        NativeModels(Models.prepare(target)).use { models ->
            for (name in listOf("short-a.wav", "short-b.wav", "short-a.wav")) {
                val query = audio(name)
                val clip = ShortArray(43200)
                query.copyInto(clip, 4800)
                try {
                    val keyword = "n ǐ h ǎo p áng x iè @你好螃蟹"
                    models.reset(keyword)
                    for (at in 0 until 4800 step 320) models.feed(clip.copyOfRange(at, at + 320), true, false)
                    models.reset(keyword)
                    for (at in clip.indices step 320) models.feed(clip.copyOfRange(at, at + 320), true, false)
                    val result = Identity.report(clip, models, JSONObject(), JSONArray())
                    assertTrue(result.length() > 0)
                    for (i in 0 until result.length()) {
                        val segment = result.getJSONObject(i)
                        assertTrue(segment.getInt("start_sample") >= 0)
                        assertTrue(segment.getInt("end_sample") <= clip.size)
                        assertEquals("unknown", segment.getString("identity_status"))
                    }
                } finally { query.fill(0); clip.fill(0) }
            }
        }
    }
}
