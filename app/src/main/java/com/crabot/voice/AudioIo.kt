package com.crabot.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class Microphone(private val context: Context, private val failure: (String) -> Unit) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private val running = AtomicBoolean(false)
    @SuppressLint("MissingPermission")
    internal fun createRecorder(): AudioRecord {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        return AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).build()
    }
    @SuppressLint("MissingPermission")
    @Synchronized fun start(frame: (ShortArray) -> Unit) {
        check(record == null) { "麦克风已经打开" }
        val rec = createRecorder()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "不能初始化麦克风" }
            val manager = context.getSystemService(AudioManager::class.java)
            val mic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            check(mic != null && rec.setPreferredDevice(mic)) { "首版只支持内置麦克风" }
            try {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            } catch (_: Exception) { aec?.release(); aec = null } // Playback stays closed-mic until actual echo acceptance.
            rec.startRecording(); record = rec; running.set(true)
            worker = Thread({
                val buffer = ShortArray(320)
                try {
                    while (running.get()) {
                        val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (!running.get()) break
                        check(n > 0) { "采音失败或麦克风权限已撤销" }
                        check(rec.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "音频路由已变化" }
                        frame(buffer.copyOf(n))
                    }
                } catch (_: Exception) { if (running.get()) failure("采音结束：权限、焦点或音频路由发生变化") }
                finally { buffer.fill(0) }
            }, "voice-microphone").apply { start() }
        } catch (e: Exception) { aec?.release(); aec = null; rec.release(); throw e }
    }
    @Synchronized fun stop() {
        running.set(false)
        record?.let {
            try { android.util.Log.i("CrabotVoiceInput", "source=${it.audioSource} silenced=${it.activeRecordingConfiguration?.isClientSilenced} route=${it.routedDevice?.type}") } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        worker?.let { if (Thread.currentThread() != it) it.join(300) }
        record?.release(); record = null; worker = null; aec?.release(); aec = null
    }
}

class Playback(private val context: Context, private val id: String,
               private val onProgress: ((Long) -> Unit)? = null, private val finite: Boolean = false,
               private val receipt: (String, String, Long?) -> Unit) {
    // The host limits total unplayed PCM to one second, including AudioTrack.
    private val queue = ArrayBlockingQueue<ShortArray>(50)
    private val running = AtomicBoolean(true)
    @Volatile private var ended = false
    private val progress = PlaybackProgress(id, if (finite) Wire.MAX_OUTPUT.toLong() else Wire.MAX_SAFE_SAMPLES)
    @Volatile private var playedSamples: Long? = null
    private val track: AudioTrack
    private val worker: Thread
    init {
        val format = AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3840)).build()
        try {
            val speaker = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            check(track.state == AudioTrack.STATE_INITIALIZED && speaker != null && track.setPreferredDevice(speaker)) { "首版只支持内置扬声器" }
        } catch (e: Exception) { track.release(); throw e }
        worker = Thread({ play() }, "voice-playback").apply { start() }
    }
    @Synchronized fun add(frame: Wire.AudioFrame) {
        require(running.get() && !ended) { "播放已结束" }
        progress.add(frame)
        require(queue.offer(frame.samples)) { "播放队列超过一秒" }
    }
    @Synchronized fun finish(count: Long) { progress.finish(count); ended = true }
    private fun play() {
        var started = false
        val beganAt = SystemClock.elapsedRealtime()
        var advancedAt = beganAt
        var reportedAt = beganAt
        var reportedSamples = 0L
        var written = 0L
        var previousHead = 0L
        var wraps = 0L
        var gapAt: Long? = null
        var gaps = 0
        var gapMs = 0L
        fun observe() {
            val now = SystemClock.elapsedRealtime()
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (head < previousHead) { check(previousHead - head > 0x80000000L) { "播放游标回退" }; wraps += 0x100000000L }
            previousHead = head
            val actual = (wraps + head).coerceAtMost(written)
            if (actual > (playedSamples ?: 0L)) advancedAt = now
            playedSamples = actual
            if (!started && actual > 0) {
                check(track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { "无法确认内置扬声器路由" }
                started = true; receipt(id, "started", actual)
            }
            if (onProgress != null && now - reportedAt >= 500 && actual > reportedSamples) {
                onProgress.invoke(actual); reportedAt = now; reportedSamples = actual
            }
            if (started && actual == written && !ended && queue.isEmpty()) {
                if (gapAt == null) gapAt = now
            } else if (gapAt != null && actual < written) {
                gaps++; gapMs += now - gapAt!!; gapAt = null
            }
            check(!finite || now - beganAt < 25_000) { "管理读句播放超时" }
            check(progress.received <= actual || now - advancedAt < 5000) { "播放游标没有前进" }
        }
        try {
            track.play()
            while (running.get()) {
                val pcm = queue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (pcm != null) {
                    try {
                        var offset = 0
                        while (running.get() && offset < pcm.size) {
                            val n = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(n >= 0) { "播放失败" }; offset += n; written += n
                            observe()
                            if (n == 0) Thread.sleep(5)
                        }
                    } finally { pcm.fill(0) }
                }
                if (!running.get()) break
                val route = track.routedDevice
                check(route == null || route.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { "播放路由已变化" }
                observe()
                val count = progress.count
                if (ended && queue.isEmpty() && count != null && playedSamples == count) {
                    if (running.compareAndSet(true, false)) {
                        android.util.Log.i("CrabotVoicePlayback", "id=" + id + " samples=" + count + " gap_count=" + gaps + " gap_ms=" + gapMs)
                        receipt(id, "completed", count)
                    }
                    break
                }
            }
        } catch (_: Exception) { if (running.compareAndSet(true, false)) receipt(id, "failed", playedSamples) }
        finally {
            while (true) { val item = queue.poll() ?: break; item.fill(0) }
            try { track.pause(); track.flush() } catch (_: Exception) {}
            track.release()
        }
    }
    fun stop(status: String = "interrupted") {
        if (running.compareAndSet(true, false)) {
            try { track.pause(); track.flush() } catch (_: Exception) {}
            receipt(id, status, playedSamples)
        }
        if (Thread.currentThread() != worker) worker.join(300)
    }
}
