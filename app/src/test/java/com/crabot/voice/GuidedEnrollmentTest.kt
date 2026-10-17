package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class GuidedEnrollmentTest {
    @Test fun recordingRequiresActualCompletionAndMatchingHostConfirmation() {
        val session = GuidedEnrollment("operation", "epoch", 100)
        session.prompt(1, "playback", 1)
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", true, true, 2) }
        session.ended("playback", 480)
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", true, true, 2) }
        session.played("playback", 480)
        for (epoch in listOf(null, "old")) assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, epoch, true, true, 2) }
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", false, true, 2) }
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", true, false, 2) }
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", true, true, 100) }
        session.record("playback", 1, "epoch", true, true, 2)
        assertThrows(IllegalArgumentException::class.java) { session.record("playback", 1, "epoch", true, true, 3) }
    }
    @Test fun retriesKeepTheOriginalDeadlineAndOldReceiptsCannotOpenTheNextClip() {
        val session = GuidedEnrollment("operation", "epoch", 100)
        session.prompt(1, "first", 1); session.ended("first", 480); session.played("first", 480)
        session.record("first", 1, "epoch", true, true, 2)
        session.prompt(1, "retry", 3)
        assertThrows(IllegalArgumentException::class.java) { session.played("first", 480) }
        assertThrows(IllegalArgumentException::class.java) { session.record("first", 1, "epoch", true, true, 4) }
        assertThrows(IllegalArgumentException::class.java) { session.prompt(3, "skip", 4) }
        assertThrows(IllegalArgumentException::class.java) { session.prompt(2, "late", 100) }
        assertEquals(100, session.deadline)
    }
}
