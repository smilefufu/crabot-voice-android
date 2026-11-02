package com.crabot.voice

import android.Manifest
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioProbeLifecycleTest {
    private lateinit var activity: AudioProbeActivity
    private fun field(name: String) = AudioProbeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun text() = (field("text").get(activity) as TextView).text.toString()
    private val result get() = java.io.File(activity.filesDir, "last-audio-probe.json")
    @Before fun setup() {
        activity = Robolectric.buildActivity(AudioProbeActivity::class.java).create().get()
        result.delete()
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @After fun cleanup() { activity.finish(); result.delete() }
    @Test fun openingAndWaitingNeverStartCaptureOrDismissThePage() {
        val before = text()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))
        assertEquals(before, text()); assertFalse(activity.isFinishing)
        assertFalse(field("active").getBoolean(activity)); assertNull(field("session").get(activity))
    }
    @Test fun permissionRequestDoesNotAutomaticallyStartEitherDevice() {
        (field("echo").get(activity) as Button).performClick()
        assertTrue(text().contains("尚未采音或播放"))
        assertFalse(field("active").getBoolean(activity)); assertNull(field("session").get(activity))
    }
    @Test fun leavingThePageClosesOnlyTheOwnedAudioSessionAndKeepsTheResult() {
        var stops = 0; var closes = 0
        val microphone = object : AudioCapture {
            override fun start(frame: (ShortArray) -> Unit) = Unit
            override fun stop() { stops++ }
        }
        val processor = object : EchoProcessor {
            override fun render(samples: ShortArray) = Unit
            override fun capture(samples: ShortArray, delayMs: Int) = samples.copyOf()
            override fun close() { closes++ }
        }
        field("session").set(activity, DuplexAudioSession(microphone, DuplexAudio(processor)) { error("No playback permitted") })
        field("active").setBoolean(activity, true)
        val method = AudioProbeActivity::class.java.getDeclaredMethod("onPause").apply { isAccessible = true }
        method.invoke(activity)
        assertEquals(1, stops); assertEquals(1, closes)
        assertFalse(field("active").getBoolean(activity)); assertNull(field("session").get(activity))
        assertTrue(result.readText().contains("离开页面")); assertTrue(text().contains("结果不会自动消失"))
    }
}
