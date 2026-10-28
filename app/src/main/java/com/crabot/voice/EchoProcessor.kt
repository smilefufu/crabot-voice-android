package com.crabot.voice

internal interface EchoProcessor : AutoCloseable {
    fun render(samples: ShortArray)
    fun capture(samples: ShortArray, delayMs: Int): ShortArray
}

internal class NativeEchoProcessor : EchoProcessor {
    private var handle = create().also { check(it != 0L) { "本地回声组件加载失败" } }
    private fun active(): Long { check(handle != 0L); return handle }
    override fun render(samples: ShortArray) = render(active(), samples)
    override fun capture(samples: ShortArray, delayMs: Int) = capture(active(), samples, delayMs)
    override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(): Long
    private external fun destroy(handle: Long)
    private external fun render(handle: Long, samples: ShortArray)
    private external fun capture(handle: Long, samples: ShortArray, delayMs: Int): ShortArray
    companion object { init { System.loadLibrary("crabot_voice") } }
}
