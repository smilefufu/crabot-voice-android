package com.crabot.voice

import android.content.Context
import android.media.*

internal interface PlaybackDevice {
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun write(pcm: ShortArray, offset: Int, count: Int): Int
    fun head(): Long
    fun stamp(): AudioStamp?
    fun routeIsValid(): Boolean
    fun routeIsConfirmed(): Boolean
}

internal class AndroidPlaybackDevice(context: Context) : PlaybackDevice {
    private var clockEpoch = System.nanoTime()
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3840)).build()
    init {
        try {
            val speaker = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            check(track.state == AudioTrack.STATE_INITIALIZED && speaker != null && track.setPreferredDevice(speaker)) { "首版只支持内置扬声器" }
        } catch (e: Exception) { track.release(); throw e }
    }
    override fun play() { clockEpoch = System.nanoTime(); track.play() }
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
    override fun write(pcm: ShortArray, offset: Int, count: Int) = track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun head() = track.playbackHeadPosition.toLong() and 0xffffffffL
    override fun stamp(): AudioStamp? {
        val value = AudioTimestamp()
        return if (track.getTimestamp(value) && value.nanoTime >= clockEpoch) AudioStamp(value.framePosition, value.nanoTime) else null
    }
    override fun routeIsValid() = track.routedDevice?.let { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: true
    override fun routeIsConfirmed() = track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}
