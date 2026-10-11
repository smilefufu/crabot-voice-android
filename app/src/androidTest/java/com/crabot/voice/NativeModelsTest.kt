package com.crabot.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Isolated-device finite public fixture tests. No microphone, playback, cloud, or accuracy claim. */
@RunWith(AndroidJUnit4::class)
class NativeModelsTest {
    @Before fun requireDisposableEmulator() {
        assumeTrue("Run only on an explicitly disposable emulator", android.os.Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("disposable_voice_test") == "true")
    }
    @Test fun executesFrozenVadDiarizationEmbeddingAndNativeMatching() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Models.prepare(instrumentation.targetContext)
        val tokens = java.io.File(root, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
        assertTrue(Models.keyword("你好螃蟹", tokens).contains("n ǐ h ǎo p áng x iè"))
        val wave = instrumentation.context.assets.open("voice-service-test.wav").use { it.readBytes() }
        val b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var pcm = ShortArray(0)
        while (offset + 8 <= wave.size) {
            val size = b.getInt(offset + 4)
            if (String(wave, offset, 4) == "data") {
                pcm = ShortArray(size / 2) { b.getShort(offset + 8 + it * 2) }; break
            }
            offset += 8 + size + size % 2
        }
        assertTrue(pcm.isNotEmpty())
        NativeModels(root).use { native ->
            native.reset(Models.keyword("你好螃蟹", tokens))
            var speech = false
            pcm.toList().chunked(320).forEach { speech = speech || native.feed(it.toShortArray(), true, false) and 1 != 0 }
            assertTrue("Synthetic public speech should reach VAD", speech)
            val segments = Identity.ranges(native.segments(pcm), pcm.size)
            assertTrue(segments.isNotEmpty())
            val unknown = Identity.report(pcm, native, JSONObject(), org.json.JSONArray())
            assertTrue(unknown.length() > 0)
            (0 until unknown.length()).forEach { i -> assertEquals("unknown", unknown.getJSONObject(i).getString("identity_status")); assertFalse(unknown.getJSONObject(i).has("registration_id")) }
            val vector = native.embedding(pcm)
            try {
                assertTrue(vector.isNotEmpty() && vector.all { it.isFinite() })
                val hits = native.matches(vector, listOf(vector))
                assertEquals(0f, hits[0], 0f); assertTrue(hits[1] > .99f)
            } finally { vector.fill(0f) }
        }
        pcm.fill(0); wave.fill(0)
    }
    @Test fun protectsCredentialAndVectorWithKeystoreRatherThanPlainFile() {
        // Run only on the disposable emulator: never point this fixture at a paired human device.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureStore(context)
        try {
            secure.savePairing(JSONObject().put("credential", "fixture-only-sensitive-marker"))
            secure.putVector("fixture-registration", floatArrayOf(.1f, .2f, .3f))
            val raw = java.io.File(context.filesDir, "terminal.enc").readBytes()
            assertFalse(String(raw).contains("fixture-only-sensitive-marker"))
            val restored = SecureStore(context)
            assertEquals("fixture-only-sensitive-marker", restored.pairing()!!.getString("credential"))
            assertTrue(restored.vectors().has("fixture-registration"))
            restored.clearVector("fixture-registration")
            assertFalse(SecureStore(context).vectors().has("fixture-registration"))
            raw.fill(0)
        } finally { secure.forget() }
    }
    @Test fun refusesOldEncryptedWipWithoutDeletingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureStore(context)
        try {
            secure.forget()
            secure.savePairing(JSONObject().put("credential", "temporary-v2"))
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, ks.getKey("crabot.voice.terminal.v1", null))
            val old = JSONObject().put("pairing", JSONObject().put("credential", "old-wip"))
                .put("vectors", JSONObject().put("old-registration", JSONObject()))
            val blob = cipher.iv + cipher.doFinal(old.toString().toByteArray())
            val file = java.io.File(context.filesDir, "terminal.enc"); file.writeBytes(blob)
            val restored = SecureStore(context)
            assertFalse(restored.compatible); assertNull(restored.pairing())
            assertThrows(IllegalStateException::class.java) { restored.savePairing(JSONObject().put("credential", "new")) }
            assertArrayEquals(blob, file.readBytes())
            restored.forget(); assertTrue(SecureStore(context).compatible)
            blob.fill(0)
        } finally { secure.forget() }
    }
    @Test fun rejectsInferenceAfterCloseWithoutDereferencingNativeHandle() {
        val root = Models.prepare(InstrumentationRegistry.getInstrumentation().targetContext)
        val native = NativeModels(root)
        native.close(); native.close()
        assertThrows(IllegalStateException::class.java) { native.reset("fixture") }
        assertThrows(IllegalStateException::class.java) { native.feed(ShortArray(320), true, false) }
        assertThrows(IllegalStateException::class.java) { native.segments(ShortArray(320)) }
        assertThrows(IllegalStateException::class.java) { native.embedding(ShortArray(320)) }
        assertThrows(IllegalStateException::class.java) { native.matches(FloatArray(1), emptyList()) }
    }
}
