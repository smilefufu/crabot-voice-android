package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DuplexAudioSessionTest {
    private class Capture : AudioCapture {
        var frame: ((CapturedAudio) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun start(frame: (ShortArray) -> Unit) = error("Must use hardware-timed capture")
        override fun startTimed(frame: (CapturedAudio) -> Unit) { starts++; this.frame = frame }
        override fun stop() { stops++ }
    }
    private open class Processor : EchoProcessor {
        var closed = false
        override fun render(samples: ShortArray) = Unit
        open override fun capture(samples: ShortArray, delayMs: Int) = samples.copyOf()
        override fun close() { closed = true }
    }
    private class Device : PlaybackDevice {
        @Volatile var released = false
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() { released = true }
        override fun write(pcm: ShortArray, offset: Int, count: Int) = 0
        override fun head() = 0L
        override fun stamp(): AudioStamp? = null
        override fun routeIsValid() = true
        override fun routeIsConfirmed() = true
    }
    @Test fun constructionNeverOpensMicAndOldCaptureCannotEnterNewSession() {
        val microphone = Capture(); val processor = Processor()
        val session = DuplexAudioSession(microphone, DuplexAudio(processor) { 1_000_000_000 }, ::Device)
        assertEquals(0, microphone.starts)
        var callbacks = 0
        session.startCapture { raw, clean ->
            callbacks++; assertEquals(3, raw.samples[0].toInt()); assertNotNull(clean)
        }
        val old = microphone.frame!!
        microphone.frame!!(CapturedAudio(ShortArray(160) { 3 }, 0, AudioStamp(0, 1_000_000_000)))
        session.stopCapture()
        session.startCapture { _, _ -> callbacks++ }
        val stale = ShortArray(160) { 99 }
        old(CapturedAudio(stale, 160, AudioStamp(160, 1_010_000_000)))
        assertArrayEquals(ShortArray(160), stale)
        assertEquals(1, callbacks)
        session.close()
        microphone.frame!!(CapturedAudio(ShortArray(160), 0, null))
        assertEquals(1, callbacks)
        assertTrue(processor.closed)
    }
    @Test fun playbackAndCandidateNeverGrantCaptureOrAffectReplacementPlayer() {
        val microphone = Capture(); val processor = Processor(); val devices = mutableListOf<Device>()
        val session = DuplexAudioSession(microphone, DuplexAudio(processor)) { Device().also { devices += it } }
        val receipts = mutableListOf<String>()
        try {
            session.startPlayback(UUID.randomUUID().toString(), {}) { _, status, _ -> receipts += status }
            val old = session.pauseForCandidate()!!
            assertTrue(session.reject(old))
            val next = session.pauseForCandidate()!!
            assertFalse(session.confirm(old))
            assertTrue(session.confirm(next))
            session.stopPlayback()
            session.startPlayback(UUID.randomUUID().toString(), {}) { _, status, _ -> receipts += status }
            assertFalse(session.reject(next))
            assertFalse(session.confirm(next))
            assertEquals(0, microphone.starts)
            assertFalse(devices.last().released)
            assertEquals(listOf("interrupted"), receipts)
        } finally { session.close() }
        assertTrue(devices.all { it.released })
        assertEquals(0, microphone.starts)
    }
    @Test fun stopDuringProcessingCannotDeliverAnOldMicrophoneFrame() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val microphone = Capture()
        val processor = object : Processor() {
            override fun capture(samples: ShortArray, delayMs: Int): ShortArray {
                entered.countDown(); check(proceed.await(2, java.util.concurrent.TimeUnit.SECONDS))
                return samples.copyOf()
            }
        }
        val session = DuplexAudioSession(microphone, DuplexAudio(processor) { 1_000_000_000 }, ::Device)
        var callbacks = 0
        session.startCapture { _, _ -> callbacks++ }
        val samples = ShortArray(160) { 3 }
        val worker = Thread { microphone.frame!!(CapturedAudio(samples, 0, AudioStamp(0, 1_000_000_000))) }.apply { start() }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            session.stopCapture(); proceed.countDown(); worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(0, callbacks)
            assertArrayEquals(ShortArray(160), samples)
        } finally { proceed.countDown(); worker.join(2000); session.close() }
    }

    @Test fun candidateFromAnotherOwnerCannotResumeOrStopThisPlayer() {
        val first = DuplexAudioSession(Capture(), DuplexAudio(Processor()), ::Device)
        val second = DuplexAudioSession(Capture(), DuplexAudio(Processor()), ::Device)
        try {
            first.startPlayback(UUID.randomUUID().toString(), {}) { _, _, _ -> }
            second.startPlayback(UUID.randomUUID().toString(), {}) { _, _, _ -> }
            val old = first.pauseForCandidate()!!
            val current = second.pauseForCandidate()!!
            assertFalse(second.reject(old))
            assertFalse(second.confirm(old))
            assertTrue(second.reject(current))
        } finally { first.close(); second.close() }
    }

}
