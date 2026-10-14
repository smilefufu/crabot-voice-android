package com.crabot.voice

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

// Isolated empty storage; no resume, connection, models or microphone are started.
class VoiceWaitTimeoutTest {
    private fun checkTimeout(mode: VoiceController.Mode, current: String, expired: String, check: (VoiceController) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "voice-wait-").toFile()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        try {
            instrumentation.runOnMainSync {
                val controller = VoiceController(context) {}
                fun set(name: String, value: Any) = VoiceController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
                try {
                    set("mode", mode); set("currentTurn", current); set("wakeEnabled", true); set("status", "等待回答")
                    VoiceController::class.java.getDeclaredMethod("expireWaitingTurn", String::class.java).apply { isAccessible = true }.invoke(controller, expired)
                    check(controller)
                } finally { controller.destroy() }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun expiredWaitingTurnClosesMicrophoneAndKeepsActionableReason() = checkTimeout(VoiceController.Mode.WAITING, "turn-a", "turn-a") {
        assertEquals(VoiceController.Mode.CLOSED, it.mode)
        assertEquals("closed", it.microphoneState)
        assertFalse(it.wakeEnabled)
        assertTrue(it.status.contains("回答等待超时"))
        assertTrue(it.status.contains("重新开启语音待机"))
    }
    @Test fun oldTimerCannotStopNewTurn() = checkTimeout(VoiceController.Mode.WAITING, "turn-b", "turn-a") {
        assertEquals(VoiceController.Mode.WAITING, it.mode)
        assertTrue(it.wakeEnabled)
        assertEquals("等待回答", it.status)
    }
    @Test fun waitingTimerDoesNotInterruptActivePlayback() = checkTimeout(VoiceController.Mode.PLAYING, "turn-a", "turn-a") {
        assertEquals(VoiceController.Mode.PLAYING, it.mode)
        assertEquals("等待回答", it.status)
    }
}
