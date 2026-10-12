package com.crabot.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Public fixture only: no microphone, playback, pairing or voiceprint storage access. */
@RunWith(AndroidJUnit4::class)
class VadStreamTest {
    @Test fun microphoneSizedFramesDetectSpeechWithinTheInitialBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Models.prepare(instrumentation.targetContext)
        val tokens = java.io.File(root, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
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
        try {
            assertTrue(pcm.isNotEmpty())
            NativeModels(root).use { native ->
                for (frameSize in listOf(320, 512)) {
                    native.reset(Models.keyword("你好螃蟹", tokens))
                    var firstSpeech = -1
                    var speechFrames = 0
                    for (start in pcm.indices step frameSize) {
                        val frame = pcm.copyOfRange(start, (start + frameSize).coerceAtMost(pcm.size))
                        val flags = try { native.feed(frame, true, false) } finally { frame.fill(0) }
                        if (flags and 1 != 0) { if (firstSpeech < 0) firstSpeech = start; speechFrames++ }
                    }
                    android.util.Log.i("CrabotVadTest", "frame_size=$frameSize first_speech_sample=$firstSpeech speech_frames=$speechFrames")
                    assertTrue("Public speech must trigger VAD within 3 seconds with $frameSize samples", firstSpeech in 0 until 48000)
                    native.reset(Models.keyword("你好螃蟹", tokens))
                    repeat(50) { assertTrue("Silence must not trigger VAD", native.feed(ShortArray(frameSize), true, false) and 1 == 0) }
                }
            }
        } finally { pcm.fill(0); wave.fill(0) }
    }
}
