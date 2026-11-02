package com.crabot.voice

internal data class SpeechActivity(val candidate: Boolean, val confirmed: Boolean)
internal interface SpeechDetector : AutoCloseable {
    fun feed(samples: ShortArray): SpeechActivity
    fun reset()
}

/** Independent from embedding/diarization so identity work cannot block live VAD. */
internal class NativeSpeechDetector(root: String) : SpeechDetector {
    private var handle = create(root).also { check(it != 0L) }
    private fun active(): Long { check(handle != 0L); return handle }
    @Synchronized override fun feed(samples: ShortArray): SpeechActivity {
        val flags = feed(active(), samples)
        return SpeechActivity(flags and 1 != 0, flags and 2 != 0)
    }
    @Synchronized override fun reset() = reset(active())
    @Synchronized override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(root: String): Long
    private external fun feed(handle: Long, samples: ShortArray): Int
    private external fun reset(handle: Long)
    private external fun destroy(handle: Long)
    companion object { init { System.loadLibrary("crabot_voice") } }
}
