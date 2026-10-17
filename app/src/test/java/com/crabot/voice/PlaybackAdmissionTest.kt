package com.crabot.voice

import org.junit.Assert.*
import org.junit.Test

class PlaybackAdmissionTest {
    @Test fun notificationDoesNotRequireAnOldInputTurnButNeedsExplicitSpeechPermission() {
        for (mode in listOf("CLOSED", "WAKE", "FOLLOWUP")) {
            assertTrue(PlaybackAdmission.allows("notification", mode, true, true, true, true, false, false))
            assertFalse(PlaybackAdmission.allows("notification", mode, true, true, false, true, false, false))
        }
    }
    @Test fun liveReplyRequiresTheActualCurrentTurn() {
        assertTrue(PlaybackAdmission.allows("reply", "WAITING", true, true, true, true, true, false))
        assertFalse(PlaybackAdmission.allows("reply", "WAITING", true, true, true, true, false, false))
    }
    @Test fun conversationSamplingAndUnavailableDevicesNeverGetInterrupted() {
        for (mode in listOf("RECORDING", "PROCESSING", "WAITING", "PLAYING", "ENROLLING", "OWNER_RECORDING"))
            assertFalse(PlaybackAdmission.allows("notification", mode, true, true, true, true, false, false))
        assertFalse(PlaybackAdmission.allows("notification", "WAKE", false, true, true, true, false, false))
        assertFalse(PlaybackAdmission.allows("notification", "WAKE", true, false, true, true, false, false))
        assertFalse(PlaybackAdmission.allows("notification", "WAKE", true, true, true, false, false, false))
        assertFalse(PlaybackAdmission.allows("notification", "WAKE", true, true, true, true, false, true))
    }
}
