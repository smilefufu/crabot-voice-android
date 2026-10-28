package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PlaybackCandidateTest {
    private class Device : PlaybackDevice {
        val accepted = Collections.synchronizedList(mutableListOf<Short>())
        @Volatile var playing = false
        @Volatile var writable = false
        @Volatile var consumed = 0L
        @Volatile var flushes = 0
        @Volatile var released = false
        @Volatile var validRoute = true
        var zeroWrite = true
        var failResume = false
        override fun play() { check(!failResume) { "fixture resume failure" }; playing = true }
        override fun pause() { playing = false }
        override fun flush() { flushes++ }
        override fun release() { released = true }
        override fun write(pcm: ShortArray, offset: Int, count: Int): Int {
            check(playing)
            if (!writable || zeroWrite) { zeroWrite = false; return 0 }
            val n = minOf(count, 73)
            synchronized(accepted) { for (i in offset until offset + n) accepted += pcm[i] }
            return n
        }
        override fun head() = consumed
        override fun stamp() = AudioStamp(consumed, 1_000_000_000)
        override fun routeIsValid() = validRoute
        override fun routeIsConfirmed() = true
    }
    private class Reference : PlaybackReference {
        val samples = Collections.synchronizedList(mutableListOf<Short>())
        val positions = Collections.synchronizedList(mutableListOf<Long>())
        var pauses = 0
        var resumes = 0
        var closed = 0
        override fun written(samples: ShortArray, offset: Int, count: Int, position: Long, stamp: AudioStamp?) {
            positions += position
            synchronized(this.samples) { for (i in offset until offset + count) this.samples += samples[i] }
        }
        override fun paused() { pauses++ }
        override fun resumed() { resumes++ }
        override fun closed() { closed++ }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.nanoTime() + 2_000_000_000
        while (!condition() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue("playback worker did not reach expected state", condition())
    }
    @Test fun thirtyFalseCandidatesKeepSamePlayerPcmAndCursorWithoutTerminalReceipts() {
        repeat(30) {
            val id = UUID.randomUUID().toString()
            val device = Device(); val reference = Reference(); val time = AtomicLong(0)
            val receipts = Collections.synchronizedList(mutableListOf<Pair<String, Long?>>())
            val progress = Collections.synchronizedList(mutableListOf<Long>())
            val player = Playback(id, device, { progress += it }, reference = reference, now = time::get) { seen, status, count ->
                assertEquals(id, seen); receipts += status to count
            }
            try {
                val first = ShortArray(480) { (it + 1).toShort() }
                player.add(Wire.AudioFrame(id, 0, first))
                device.writable = true
                waitFor { device.accepted.size == 480 }
                device.consumed = 120; time.set(500)
                waitFor { receipts.any { it.first == "started" } }
                val token = player.pauseForCandidate()!!
                val before = device.accepted.toList()
                player.add(Wire.AudioFrame(id, 1, ShortArray(480) { (it + 481).toShort() }))
                player.finish(960)
                time.set(800); Thread.sleep(15)
                assertEquals(before, device.accepted.toList())
                assertEquals(0, device.flushes)
                assertEquals(listOf("started"), receipts.map { it.first })
                assertTrue(progress.all { it == 120L })
                assertTrue(player.resumeCandidate(token))
                assertFalse(player.resumeCandidate(token))
                waitFor { device.accepted.size == 960 }
                device.consumed = 960
                waitFor { device.released }
                assertEquals((1..960).map { it.toShort() }, device.accepted.toList())
                assertEquals(device.accepted.toList(), reference.samples.toList())
                assertEquals(0L, reference.positions.first())
                assertEquals(listOf("started", "completed"), receipts.map { it.first })
                assertEquals(960L, receipts.last().second)
                assertEquals(1, reference.pauses); assertEquals(1, reference.resumes); assertEquals(1, reference.closed)
            } finally { player.stop() }
        }
    }
    @Test fun confirmedCandidateReportsRealConsumedPositionAndDiscardsOnlyOldOutput() {
        val id = UUID.randomUUID().toString(); val device = Device(); val receipts = Collections.synchronizedList(mutableListOf<Pair<String, Long?>>())
        val player = Playback(id, device) { _, status, count -> receipts += status to count }
        try {
            player.add(Wire.AudioFrame(id, 0, ShortArray(480) { 12 })); device.writable = true
            waitFor { device.accepted.size == 480 }; device.consumed = 91
            val token = player.pauseForCandidate()!!
            player.add(Wire.AudioFrame(id, 1, ShortArray(480) { 24 }))
            assertTrue(player.interruptCandidate(token))
            waitFor { device.released }
            assertEquals("interrupted" to 91L, receipts.last())
            assertFalse(receipts.any { it.first == "completed" })
            assertEquals(480, device.accepted.size)
            assertFalse(player.resumeCandidate(token))
        } finally { player.stop() }
    }
    @Test fun candidateDeadlineFailsWithoutExtendingCursorWatchdog() {
        val id = UUID.randomUUID().toString(); val device = Device(); val time = AtomicLong(0)
        val receipts = Collections.synchronizedList(mutableListOf<String>())
        val player = Playback(id, device, now = time::get) { _, status, _ -> receipts += status }
        try {
            player.add(Wire.AudioFrame(id, 0, ShortArray(480)))
            val token = player.pauseForCandidate()!!
            time.set(999); assertNull(player.pauseForCandidate()); time.set(1000)
            assertFalse(player.resumeCandidate(token))
            waitFor { device.released }
            assertEquals(listOf("failed"), receipts.toList())
            assertEquals(0, device.accepted.size)
        } finally { player.stop() }
    }
    @Test fun pausedTotalBudgetIncludesAudioTrackAndQueue() {
        val id = UUID.randomUUID().toString(); val device = Device()
        val player = Playback(id, device) { _, _, _ -> }
        try {
            player.add(Wire.AudioFrame(id, 0, ShortArray(480))); device.writable = true
            waitFor { device.accepted.size == 480 }
            player.pauseForCandidate()
            for (seq in 1..49) player.add(Wire.AudioFrame(id, seq.toLong(), ShortArray(480)))
            assertThrows(IllegalArgumentException::class.java) { player.add(Wire.AudioFrame(id, 50, ShortArray(480))) }
        } finally { player.stop() }
    }
    @Test fun resumeDoesNotRestartTheOriginalFiveSecondWatchdog() {
        val id = UUID.randomUUID().toString(); val device = Device(); val time = AtomicLong(0)
        val receipts = Collections.synchronizedList(mutableListOf<String>())
        val player = Playback(id, device, now = time::get) { _, status, _ -> receipts += status }
        try {
            player.add(Wire.AudioFrame(id, 0, ShortArray(480))); device.writable = true
            waitFor { device.accepted.size == 480 }
            time.set(4500); val token = player.pauseForCandidate()!!
            time.set(4900); assertTrue(player.resumeCandidate(token))
            time.set(5000); waitFor { device.released }
            assertEquals(listOf("failed"), receipts.toList())
        } finally { player.stop() }
    }
    @Test fun routeLossWhilePausedReleasesDeviceAndRejectsOldCandidate() {
        val id = UUID.randomUUID().toString(); val device = Device()
        val receipts = Collections.synchronizedList(mutableListOf<String>())
        val player = Playback(id, device) { _, status, _ -> receipts += status }
        try {
            val token = player.pauseForCandidate()!!
            device.validRoute = false
            waitFor { device.released }
            assertEquals(listOf("failed"), receipts.toList())
            assertFalse(player.resumeCandidate(token))
        } finally { player.stop() }
    }

    @Test fun resumeDeviceFailureCannotLeaveOldAudioRunning() {
        val id = UUID.randomUUID().toString(); val device = Device()
        val receipts = Collections.synchronizedList(mutableListOf<String>())
        val player = Playback(id, device) { _, status, _ -> receipts += status }
        try {
            val token = player.pauseForCandidate()!!
            device.failResume = true
            assertThrows(IllegalStateException::class.java) { player.resumeCandidate(token) }
            waitFor { device.released }
            assertEquals(listOf("failed"), receipts.toList())
            assertFalse(player.resumeCandidate(token))
        } finally { player.stop() }
    }

}
