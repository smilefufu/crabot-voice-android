package com.crabot.voice

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/** Configuration regression does not record. Live diagnostic requires its explicit argument. */
@RunWith(AndroidJUnit4::class)
class MicrophoneSourceTest {
    @Test fun productRecorderUsesTheWorkingMicrophoneSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = Microphone(context) { error(it) }.createRecorder()
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
            assertEquals(MediaRecorder.AudioSource.MIC, record.audioSource)
            assertEquals(16000, record.sampleRate)
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, record.audioFormat)
            assertEquals(1, record.channelCount)
        } finally { record.release() }
    }

    @Test fun compareCaptureSources() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_microphone_diagnostic") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val manager = context.getSystemService(AudioManager::class.java)
            val root = Models.prepare(context)
            val tokens = java.io.File(root, "tokens.txt").readLines().map { it.substringBefore(' ') }.toSet()
            NativeModels(root).use { native ->
                for (source in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
                    native.reset(Models.keyword("你好螃蟹", tokens))
                    val record = AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)).build()
                    val buffer = ShortArray(320)
                    var aec: AcousticEchoCanceler? = null
                    var samples = 0
                    var peak = 0
                    var sum = 0L
                    var energy = 0.0
                    var speechFrames = 0
                    val uniqueFrames = mutableSetOf<Int>()
                    try {
                        val mic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
                        assertTrue(record.setPreferredDevice(mic))
                        try {
                            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                        } catch (_: Exception) { aec?.release(); aec = null }
                        android.util.Log.i("CrabotMicTest", "starting_source=$source duration_ms=3500")
                        record.startRecording()
                        val deadline = SystemClock.elapsedRealtime() + 3500
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                            check(n >= 0) { "AudioRecord read failed: $n" }
                            if (n == 0) { SystemClock.sleep(5); continue }
                            val frame = buffer.copyOf(n)
                            try {
                                uniqueFrames.add(frame.contentHashCode())
                                for (s in frame) { peak = maxOf(peak, abs(s.toInt())); sum += s; energy += s.toDouble() * s }
                                samples += n
                                if (native.feed(frame, true, false) and 1 != 0) speechFrames++
                            } finally { frame.fill(0); buffer.fill(0) }
                        }
                        android.util.Log.i("CrabotMicTest", "source=$source samples=$samples peak=$peak rms=${if (samples == 0) 0 else sqrt(energy / samples).toInt()} mean=${if (samples == 0) 0 else sum / samples} unique_frames=${uniqueFrames.size} speech_frames=$speechFrames silenced=${record.activeRecordingConfiguration?.isClientSilenced} route=${record.routedDevice?.type}")
                        assertTrue("Capture must return at least two seconds", samples >= 32000)
                    } finally {
                        try { record.stop() } catch (_: Exception) {}
                        record.release(); aec?.release(); buffer.fill(0); uniqueFrames.clear()
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
