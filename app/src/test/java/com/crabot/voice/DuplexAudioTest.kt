package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DuplexAudioTest {
    private class Processor : EchoProcessor {
        val render = mutableListOf<List<Short>>()
        val capture = mutableListOf<List<Short>>()
        val delays = mutableListOf<Int>()
        var closed = false
        override fun render(samples: ShortArray) { render += samples.toList() }
        override fun capture(samples: ShortArray, delayMs: Int): ShortArray {
            capture += samples.toList(); delays += delayMs
            return samples.copyOf()
        }
        override fun close() { closed = true }
    }
    @Test fun partialWritesAndReadsMakeExactTenMsBlocksWithHardwareDelay() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val reference = audio.startPlayback()
            val output = ShortArray(480) { it.toShort() }
            reference.written(output, 0, 73, 0, null)
            assertTrue(processor.render.isEmpty())
            reference.written(output, 73, 407, 73, AudioStamp(0, 1_040_000_000))
            assertEquals(output.toList(), processor.render.flatten())
            val input = ShortArray(320) { (it + 1).toShort() }
            val processed = mutableListOf<Short>()
            audio.capture(CapturedAudio(input.copyOfRange(0, 97), 0, AudioStamp(0, 990_000_000))) { fail("Incomplete capture block") }
            audio.capture(CapturedAudio(input.copyOfRange(97, 320), 97, null)) { processed += it!!.toList() }
            assertEquals(input.toList(), processed)
            assertEquals(listOf(60, 50), processor.delays)
            assertEquals(input.toList(), processor.capture.flatten())
            assertEquals("", audio.synchronizationReason)
            assertArrayEquals(ShortArray(320) { (it + 1).toShort() }, input)
        }
        assertTrue(processor.closed)
    }
    @Test fun unavailableOrImpossibleClockNeverBecomesVerifiedByCallbackTime() {
        val processor = Processor()
        DuplexAudio(processor) { 9_000_000_000 }.use { audio ->
            audio.startCapture(); val reference = audio.startPlayback()
            reference.written(ShortArray(240), 0, 240, 0, null)
            audio.capture(CapturedAudio(ShortArray(160), 0, null)) { assertNull(it) }
            reference.written(ShortArray(240), 0, 240, 240, AudioStamp(240, 10_000_000_000))
            audio.capture(CapturedAudio(ShortArray(160), 160, AudioStamp(160, 8_000_000_000))) { assertNull(it) }
            assertTrue(processor.capture.isEmpty())
            assertTrue(audio.synchronizationReason.isNotEmpty())
        }
    }
    @Test fun pauseAndResumeRequireNewClockAndDoNotLeakOldPlayerCallbacks() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val old = audio.startPlayback()
            old.written(ShortArray(240) { 5 }, 0, 240, 0, AudioStamp(0, time.get()))
            old.paused()
            audio.capture(CapturedAudio(ShortArray(160), 0, AudioStamp(0, time.get()))) { assertNull(it) }
            old.resumed()
            old.written(ShortArray(240) { 6 }, 0, 240, 240, null)
            audio.capture(CapturedAudio(ShortArray(160), 160, AudioStamp(160, time.get()))) { assertNull(it) }
            old.written(ShortArray(240) { 7 }, 0, 240, 480, AudioStamp(480, time.get()))
            audio.capture(CapturedAudio(ShortArray(160), 320, AudioStamp(320, time.get()))) { assertNotNull(it) }
            old.closed()
            val current = audio.startPlayback()
            old.closed(); old.written(ShortArray(240) { 9 }, 0, 240, 720, null)
            current.written(ShortArray(240) { 8 }, 0, 240, 0, AudioStamp(0, time.get()))
            assertEquals(listOf(5, 6, 7, 8), processor.render.map { it.first().toInt() })
            assertEquals(1, processor.capture.size)
        }
    }
    @Test fun gapAndClockRegressionFailInsteadOfFabricatingMissingSamples() {
        val processor = Processor()
        DuplexAudio(processor).use { audio ->
            audio.startCapture()
            assertThrows(IllegalStateException::class.java) { audio.capture(CapturedAudio(ShortArray(160), 1, null)) {} }
            val reference = audio.startPlayback()
            assertThrows(IllegalStateException::class.java) { reference.written(ShortArray(240), 0, 240, 240, null) }
        }
    }
}
