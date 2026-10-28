package com.crabot.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal interface AudioCapture {
    fun start(frame: (ShortArray) -> Unit)
    fun startTimed(frame: (CapturedAudio) -> Unit) {
        var position = 0L
        start { samples -> frame(CapturedAudio(samples, position, null)); position += samples.size }
    }
    fun stop()
}

internal class Microphone(private val context: Context, private val systemAec: Boolean = true, private val failure: (String) -> Unit) : AudioCapture {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var running = AtomicBoolean(false)
    @SuppressLint("MissingPermission")
    internal fun createRecorder(): AudioRecord {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        return AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).build()
    }
    @SuppressLint("MissingPermission")
    override fun start(frame: (ShortArray) -> Unit) = startTimed { frame(it.samples) }
    @SuppressLint("MissingPermission")
    @Synchronized override fun startTimed(frame: (CapturedAudio) -> Unit) {
        check(record == null) { "麦克风已经打开" }
        val rec = createRecorder()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "不能初始化麦克风" }
            val manager = context.getSystemService(AudioManager::class.java)
            val mic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            check(mic != null && rec.setPreferredDevice(mic)) { "首版只支持内置麦克风" }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(rec.audioSessionId)
                    check(systemAec || aec != null) { "不能确认系统AEC已关闭" }
                    aec?.apply { enabled = systemAec; check(enabled == systemAec) { "无法切换系统AEC" } }
                }
            } catch (e: Exception) {
                aec?.release(); aec = null
                if (!systemAec) throw e // Never stack an unverified system effect with software AEC.
            }
            rec.startRecording(); record = rec
            val session = AtomicBoolean(true); running = session
            worker = Thread({
                val buffer = ShortArray(320)
                var position = 0L
                val stamp = AudioTimestamp()
                try {
                    while (session.get()) {
                        val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (!session.get()) break
                        check(n > 0) { "采音失败或麦克风权限已撤销" }
                        check(rec.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "音频路由已变化" }
                        val hardware = if (rec.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                            AudioStamp(stamp.framePosition, stamp.nanoTime) else null
                        frame(CapturedAudio(buffer.copyOf(n), position, hardware))
                        position += n
                    }
                } catch (_: Exception) { if (session.get()) failure("采音结束：权限、焦点或音频路由发生变化") }
                finally { buffer.fill(0) }
            }, "voice-microphone").apply { start() }
        } catch (e: Exception) { aec?.release(); aec = null; rec.release(); throw e }
    }
    @Synchronized override fun stop() {
        running.set(false)
        record?.let {
            try { android.util.Log.i("CrabotVoiceInput", "source=${it.audioSource} silenced=${it.activeRecordingConfiguration?.isClientSilenced} route=${it.routedDevice?.type}") } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        worker?.let { if (Thread.currentThread() != it) it.join(300) }
        record?.release(); record = null; worker = null; aec?.release(); aec = null
    }
}

class Playback internal constructor(private val id: String, private val device: PlaybackDevice,
               private val onProgress: ((Long) -> Unit)? = null, private val finite: Boolean = false,
               private val reference: PlaybackReference? = null,
               private val now: () -> Long = SystemClock::elapsedRealtime,
               private val receipt: (String, String, Long?) -> Unit) {
    constructor(context: Context, id: String, onProgress: ((Long) -> Unit)? = null, finite: Boolean = false,
                receipt: (String, String, Long?) -> Unit) : this(id, AndroidPlaybackDevice(context), onProgress, finite, receipt = receipt)
    // The host and this player limit total unplayed PCM, including AudioTrack, to one second.
    private val queue = ArrayBlockingQueue<ShortArray>(50)
    private val io = Any()
    private val running = AtomicBoolean(true)
    @Volatile private var ended = false
    private val progress = PlaybackProgress(id, if (finite) Wire.MAX_OUTPUT.toLong() else Wire.MAX_SAFE_SAMPLES)
    @Volatile private var playedSamples: Long? = null
    private var written = 0L
    private var previousHead = 0L
    private var wraps = 0L
    private var candidate: Long? = null
    private var candidateSequence = 0L
    private var pausedAt = 0L
    private var stamp: AudioStamp? = null
    private var stampAt = Long.MIN_VALUE
    private val worker = Thread({ play() }, "voice-playback").apply { start() }
    fun add(frame: Wire.AudioFrame) = synchronized(io) {
        require(running.get() && !ended) { "播放已结束" }
        require(progress.received - (playedSamples ?: 0L) + frame.samples.size <= 24000) { "播放在途音频超过一秒" }
        progress.add(frame)
        require(queue.offer(frame.samples)) { "播放队列超过一秒" }
    }
    fun finish(count: Long) = synchronized(io) { progress.finish(count); ended = true }
    /** Candidate-only pause: no flush, terminal receipt, new playback ID or watchdog extension. */
    internal fun pauseForCandidate(): Long? = synchronized(io) {
        if (!running.get() || candidate != null) return null
        try {
            device.pause()
            pausedAt = now(); candidate = ++candidateSequence
            reference?.paused()
            candidate
        } catch (e: Exception) { stopLocked("failed"); throw e }
    }
    internal fun resumeCandidate(token: Long): Boolean = synchronized(io) {
        if (!running.get() || candidate != token || now() - pausedAt >= 1000) return false
        try {
            device.play(); stamp = null; stampAt = Long.MIN_VALUE
            reference?.resumed(); candidate = null
            true
        } catch (e: Exception) { stopLocked("failed"); throw e }
    }
    internal fun interruptCandidate(token: Long): Boolean {
        synchronized(io) {
            if (!running.get() || candidate != token || now() - pausedAt >= 1000) return false
            stopLocked("interrupted")
        }
        if (Thread.currentThread() != worker) worker.join(300)
        return true
    }
    private fun actual(): Long {
        val head = device.head()
        if (head < previousHead) { check(previousHead - head > 0x80000000L) { "播放游标回退" }; wraps += 0x100000000L }
        previousHead = head
        return (wraps + head).coerceAtMost(written).also { playedSamples = it }
    }
    private fun play() {
        var started = false
        val beganAt = now()
        var advancedAt = beganAt
        var reportedAt = beganAt
        var reportedSamples = 0L
        var previousActual = 0L
        var gapAt: Long? = null
        var gaps = 0
        var gapMs = 0L
        fun observe() {
            val time = now()
            val actual = actual()
            if (actual > previousActual) advancedAt = time
            previousActual = actual
            if (!started && actual > 0) {
                check(device.routeIsConfirmed()) { "无法确认内置扬声器路由" }
                started = true; receipt(id, "started", actual)
            }
            if (onProgress != null && time - reportedAt >= 500 && actual > reportedSamples) {
                onProgress.invoke(actual); reportedAt = time; reportedSamples = actual
            }
            if (candidate == null && started && actual == written && !ended && queue.isEmpty()) {
                if (gapAt == null) gapAt = time
            } else if (gapAt != null && actual < written) {
                gaps++; gapMs += time - gapAt!!; gapAt = null
            }
            check(!finite || time - beganAt < 25_000) { "管理读句播放超时" }
            check(candidate == null || time - pausedAt < 1000) { "插话检测未及时完成" }
            check(progress.received <= actual || time - advancedAt < 5000) { "播放游标没有前进" }
        }
        try {
            synchronized(io) { if (candidate == null && running.get()) device.play() }
            while (running.get()) {
                val pcm = if (synchronized(io) { candidate == null }) queue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS) else null
                if (pcm != null) {
                    try {
                        var offset = 0
                        while (running.get() && offset < pcm.size) {
                            val n = synchronized(io) { write(pcm, offset) }
                            offset += n
                            synchronized(io) { observe() }
                            if (n == 0) Thread.sleep(5)
                        }
                    } finally { pcm.fill(0) }
                }
                if (!running.get()) break
                synchronized(io) {
                    check(device.routeIsValid()) { "播放路由已变化" }
                    observe()
                    val count = progress.count
                    if (candidate == null && ended && queue.isEmpty() && count != null && playedSamples == count) {
                        if (running.compareAndSet(true, false)) {
                            android.util.Log.i("CrabotVoicePlayback", "id=" + id + " samples=" + count + " gap_count=" + gaps + " gap_ms=" + gapMs)
                            receipt(id, "completed", count)
                        }
                    }
                }
                if (synchronized(io) { candidate != null }) Thread.sleep(5)
            }
        } catch (_: Exception) { synchronized(io) { stopLocked("failed") } }
        finally {
            synchronized(io) {
                while (true) { val item = queue.poll() ?: break; item.fill(0) }
                try { reference?.closed() } catch (_: Exception) {}
                try { device.pause(); device.flush() } catch (_: Exception) {}
                device.release()
            }
        }
    }
    private fun write(pcm: ShortArray, offset: Int): Int {
        if (!running.get() || candidate != null) return 0
        val count = device.write(pcm, offset, pcm.size - offset)
        check(count in 0..pcm.size - offset) { "播放失败" }
        if (count == 0) return 0
        val time = now()
        val stampInterval = if (stamp == null) 100 else 10_000
        if (stampAt == Long.MIN_VALUE || time - stampAt >= stampInterval) {
            stamp = device.stamp(); stampAt = time
        }
        val position = written; written += count
        reference?.written(pcm, offset, count, position, stamp)
        return count
    }
    private fun stopLocked(status: String) {
        if (running.compareAndSet(true, false)) {
            try { device.pause(); actual(); device.flush() } catch (_: Exception) {}
            receipt(id, status, playedSamples)
        }
    }
    fun stop(status: String = "interrupted") {
        synchronized(io) { stopLocked(status) }
        if (Thread.currentThread() != worker) worker.join(300)
    }
}
