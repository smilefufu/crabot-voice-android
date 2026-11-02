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
            capture += samples.toList(); delays += delayMs; return samples.copyOf()
        }
        override fun close() { closed = true }
    }
    @Test fun partialWritesAndReadsAlignBothStreamsToHardwareTime() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val reference = audio.startPlayback()
            val output = ShortArray(480) { it.toShort() }
            reference.written(output, 0, 73, 0, null)
            reference.written(output, 73, 407, 73, AudioStamp(0, 1_040_000_000))
            assertTrue(processor.render.isEmpty())
            val input = ShortArray(320) { (it + 1).toShort() }; val processed = mutableListOf<Short>()
            time.set(1_100_000_000)
            audio.capture(CapturedAudio(input.copyOfRange(0, 97), 0, AudioStamp(0, 1_040_000_000))) { _, _ -> fail("Incomplete APM block") }
            audio.capture(CapturedAudio(input.copyOfRange(97, 320), 97, null)) { ready, clean ->
                processed += clean.toList(); assertArrayEquals(ready.samples, clean)
            }
            assertEquals(input.toList(), processed)
            assertEquals(output.toList(), processor.render.flatten())
            assertEquals(listOf(0, 0), processor.delays)
            assertArrayEquals(ShortArray(320) { (it + 1).toShort() }, input)
        }
        assertTrue(processor.closed)
    }
    @Test fun missingClockQueuesOriginalAudioWithoutGuessingOrDroppingIt() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val ref = audio.startPlayback()
            ref.written(ShortArray(480) { 9 }, 0, 480, 0, null)
            val delivered = mutableListOf<Long>()
            audio.capture(CapturedAudio(ShortArray(160) { 3 }, 0, null)) { _, _ -> fail("Clock unavailable") }
            assertTrue(processor.capture.isEmpty())
            ref.clock(AudioStamp(0, 1_000_000_000))
            time.set(1_020_000_000)
            audio.capture(CapturedAudio(ShortArray(160) { 4 }, 160, AudioStamp(160, 1_010_000_000))) { ready, clean ->
                delivered += ready.position; assertArrayEquals(ready.samples, clean)
            }
            assertEquals(listOf(0L, 160L), delivered)
            assertEquals(listOf(3, 4), processor.capture.map { it.first().toInt() })
        }
    }
    @Test fun pauseContinuesApmWithSilenceAndResumeUsesPreviouslyAcceptedUnplayedPcm() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val ref = audio.startPlayback()
            val pcm = ShortArray(720) { if (it < 240) 5 else if (it < 480) 6 else 7 }
            ref.written(pcm, 0, pcm.size, 0, AudioStamp(0, time.get()))
            fun input(index: Long, at: Long, expect: Int) {
                time.set(at + 10_000_000)
                audio.capture(CapturedAudio(ShortArray(160) { 12 }, index, AudioStamp(index, at))) { _, clean -> assertEquals(12, clean.first().toInt()) }
                assertTrue(processor.render.last().all { it.toInt() == expect })
            }
            input(0, 1_000_000_000, 5)
            ref.paused(240)
            input(160, 1_010_000_000, 0)
            input(320, 1_020_000_000, 0)
            time.set(1_030_000_000); ref.resumed()
            // No new write: these samples were queued before the pause.
            ref.clock(AudioStamp(240, 1_030_000_000))
            input(480, 1_030_000_000, 6)
            input(640, 1_040_000_000, 7)
            assertEquals(5, processor.capture.size)
            ref.closed(720)
            val next = audio.startPlayback()
            ref.closed(720); ref.written(ShortArray(240), 0, 240, 720, null)
            next.closed(0)
        }
    }
    @Test fun synchronizationQueueIsBoundedAndDoesNotSkipUnknownBlocks() {
        val processor = Processor()
        DuplexAudio(processor).use { audio ->
            audio.startCapture()
            repeat(50) { i -> audio.capture(CapturedAudio(ShortArray(320), i * 320L, null)) { _, _ -> fail("Unknown clock") } }
            assertThrows(IllegalStateException::class.java) { audio.capture(CapturedAudio(ShortArray(320), 16000, null)) { _, _ -> } }
            assertTrue(processor.capture.isEmpty())
        }
    }
    @Test fun segmentStarvationAndCandidatePausePreserveTheNextUnplayedSample() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val ref = audio.startPlayback()
            ref.written(ShortArray(240) { 5 }, 0, 240, 0, AudioStamp(0, time.get()))
            fun input(position: Long, at: Long, expected: Int) {
                time.set(at)
                audio.capture(CapturedAudio(ShortArray(160), position, AudioStamp(position, at))) { _, _ -> }
                assertTrue(processor.render.last().all { it.toInt() == expected })
            }
            input(0, 1_000_000_000, 5)
            ref.starved(240); ref.paused(240)
            input(160, 1_010_000_000, 0)
            ref.resumed()
            input(320, 1_020_000_000, 0)
            time.set(1_030_000_000); ref.replenished()
            ref.written(ShortArray(240) { 8 }, 0, 240, 240, AudioStamp(240, time.get()))
            input(480, 1_030_000_000, 8)
            ref.closed(480)
        }
    }
    @Test fun cancelledQueuedTailIsNeverUsedAsTheNextPlayersReference() {
        val processor = Processor(); val time = AtomicLong(1_000_000_000)
        DuplexAudio(processor, time::get).use { audio ->
            audio.startCapture(); val old = audio.startPlayback()
            old.written(ShortArray(480) { 5 }, 0, 480, 0, AudioStamp(0, time.get()))
            old.closed(120)
            time.set(1_005_000_000); val next = audio.startPlayback()
            next.written(ShortArray(360) { 8 }, 0, 360, 0, AudioStamp(0, time.get()))
            audio.capture(CapturedAudio(ShortArray(320), 0, AudioStamp(0, 1_000_000_000))) { _, _ -> }
            assertEquals(List(120) { 5.toShort() } + List(360) { 8.toShort() }, processor.render.flatten())
            next.closed(360)
        }
    }
    @Test fun referenceHistoryOverrunDoesNotInventAudioAndCaptureGapFails() {
        val processor = Processor()
        DuplexAudio(processor) { 1_000_000_000 }.use { audio ->
            audio.startCapture(); val ref = audio.startPlayback()
            ref.written(ShortArray(24480), 0, 24480, 0, AudioStamp(0, 1_000_000_000))
            audio.capture(CapturedAudio(ShortArray(160), 0, AudioStamp(0, 1_000_000_000))) { _, _ -> fail("Lost reference") }
            assertTrue(processor.capture.isEmpty())
            assertThrows(IllegalStateException::class.java) { audio.capture(CapturedAudio(ShortArray(160), 161, null)) { _, _ -> } }
        }
    }
}
